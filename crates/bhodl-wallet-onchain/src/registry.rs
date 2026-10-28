//! Multi-wallet registry — Phase 3 foundation.
//!
//! The registry is the **device-PIN-encrypted** `SQLite` database that
//! catalogues every (watch-only) wallet known to this BHODL
//! installation, plus every tracker table. The wallet rows hold **only
//! public data**:
//!
//! - Wallet UUID (filesystem path identity).
//! - User-chosen label.
//! - Wallet kind (one of the three watch-only variants).
//! - Public descriptor (xpub-derived) or address list.
//! - Network (mainnet / signet / testnet4 / regtest).
//! - Creation timestamp.
//!
//! BDK chain state, labels and the address book live in
//! `watch-state/<uuid>.sqlite`, also encrypted with the device key; the
//! registry only knows the descriptor + label.
//!
//! Builds before the watch-only rework also stored `signing` rows (keys
//! in `wallets/<uuid>/`). They are hidden from every listing and removed
//! by [`Registry::purge_legacy_signing_wallets`] once the user confirms.
//!
//! See `docs/phase-3-architecture.md` §"Two-tier security model" and
//! §"Storage layout" for the surrounding design.

use std::path::Path;

use rusqlite::{Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::common::{ENCRYPTION_KEY_LEN, open_encrypted_connection};
use crate::error::OnchainError;
use crate::watch::{
    WatchBip, hd_watch_descriptors, validate_watch_address, xpub_watch_descriptors,
};

/// File name of the registry DB, sibling of `wallets/` and
/// `watch-state/` under the data dir.
pub const REGISTRY_FILE_NAME: &str = "registry.sqlite";

/// File name of the registry KDF descriptor next to the DB. Mirrors
/// the wallet-level `wallet.kdf.json` layout for consistency.
pub const REGISTRY_KDF_FILE_NAME: &str = "registry.kdf.json";

/// One of the three (watch-only) wallet kinds the registry supports.
///
/// Persisted as a `TEXT` column rather than an integer enum so the
/// schema is debuggable from a `SQLCipher` shell with the device PIN.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum WalletKind {
    /// HD watch-only at a known BIP path (86 / 84 / 49). Derives
    /// addresses from the xpub. No signing.
    WatchXpub,
    /// Explicit list of addresses, no BIP-32 derivation. No xpub,
    /// no gap-limit scan — just monitors the addresses literally.
    WatchAddresses,
    /// HD watch-only with full Sparrow/Specter output-descriptor
    /// metadata (master fingerprint + derivation path + xpub).
    WatchHd,
}

impl WalletKind {
    pub fn as_db_str(self) -> &'static str {
        match self {
            Self::WatchXpub => "watch_xpub",
            Self::WatchAddresses => "watch_addresses",
            Self::WatchHd => "watch_hd",
        }
    }

    pub fn from_db_str(s: &str) -> Option<Self> {
        match s {
            "watch_xpub" => Some(Self::WatchXpub),
            "watch_addresses" => Some(Self::WatchAddresses),
            "watch_hd" => Some(Self::WatchHd),
            _ => None,
        }
    }
}

/// One row of the `wallets` table.
///
/// `id` is a `UUIDv4` generated when the wallet is added; it identifies
/// the watch-state file (`watch-state/<id>.sqlite`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WalletRecord {
    pub id: Uuid,
    pub label: String,
    pub kind: WalletKind,
    /// Public-only descriptor in BDK string form, e.g.
    /// `tr([fingerprint/86h/1h/0h]xpub/0/i)` where `i` is the BDK-managed
    /// address index. Never contains xprv-encoded material.
    pub descriptor_pub: String,
    /// Lower-case network enum: `mainnet`, `signet`, `testnet4`,
    /// `regtest`.
    pub network: String,
    /// Unix epoch seconds when the wallet was added.
    pub created_at_unix: i64,
}

/// `wallets.kind` of the signing wallets written by builds before the
/// watch-only rework. Never listed; see
/// [`Registry::purge_legacy_signing_wallets`].
const LEGACY_SIGNING_KIND: &str = "signing";

/// Schema DDL kept as a single constant so versioning is explicit and
/// migrations land here rather than scattered across `add_*` calls.
const CREATE_WALLETS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS wallets (\
    id TEXT PRIMARY KEY,\
    label TEXT NOT NULL,\
    kind TEXT NOT NULL,\
    descriptor_pub TEXT NOT NULL,\
    network TEXT NOT NULL,\
    created_at_unix INTEGER NOT NULL,\
    pins_warned INTEGER NOT NULL DEFAULT 0\
)";

/// Phase 3.4 — categories for the cross-wallet Tracking surface.
///
/// Lives in the registry (device-PIN-encrypted) rather than per-wallet
/// label storage so the Tracking tab can show, edit, and aggregate
/// categories for transactions of wallets that are NOT currently
/// unlocked. The trade-off accepted on 2026-05-15: BIP-329
/// label files (which are per-wallet, wallet-PIN-encrypted) remain the
/// portable backup format for `text` annotations; categories are a
/// BHODL-internal Tracker concept and don't round-trip through
/// BIP-329 export.
const CREATE_TRACKING_CATEGORIES_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_categories (\
    wallet_id          TEXT NOT NULL,\
    txid_or_payment_id TEXT NOT NULL,\
    category           TEXT NOT NULL,\
    updated_at_unix    INTEGER NOT NULL,\
    PRIMARY KEY (wallet_id, txid_or_payment_id)\
)";

/// Phase 3.8 — saved tracking views. A "view" is a named bookmark
/// of the user's current Dashboard / History filter state: which
/// period preset they had active, which category was selected (or
/// "" for uncategorised, NULL for all), and an optional
/// time-range window. Stored on the registry (device-PIN-encrypted)
/// so it survives across signing-wallet switches.
const CREATE_TRACKING_VIEWS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_views (\
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,\
    name                TEXT NOT NULL UNIQUE,\
    period              TEXT NOT NULL,\
    category_filter     TEXT,\
    time_range_start    INTEGER,\
    time_range_end      INTEGER,\
    created_at_unix     INTEGER NOT NULL\
)";

/// Phase 4.3 — per-category budget for the Wealth Builder dashboard.
/// Lives in the registry (device-PIN-encrypted) so budgets persist
/// across signing-wallet switches and aren't tied to a single wallet.
/// `period` is "monthly" for now; future iterations may add weekly /
/// quarterly / yearly. `budgeted_sat` is the absolute target value
/// per period; computing variance against actuals is the UI layer's
/// job (sums the period's events for the matching category).
const CREATE_TRACKING_BUDGETS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_budgets (\
    category       TEXT NOT NULL PRIMARY KEY,\
    budgeted_sat   INTEGER NOT NULL,\
    period         TEXT NOT NULL DEFAULT 'monthly',\
    updated_at_unix INTEGER NOT NULL\
)";

/// Phase 4.4 — recurring payments / bills tracker.
/// `amount_sat` is the canonical numeric value for Net-Worth math.
/// `display_label` is an OPTIONAL free-text override (e.g. "€900/month")
/// shown in the UI when present; when null the UI renders `amount_sat`
/// via the user's `BtcUnit` display preference. `due_day` is 1..=31
/// (day of month the bill is due); `last_paid_unix` is the timestamp
/// of the most recent "Paid" mark — UI computes "paid this period"
/// by checking whether `last_paid_unix` falls in the current month.
const CREATE_TRACKING_BILLS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_bills (\
    id              INTEGER PRIMARY KEY AUTOINCREMENT,\
    name            TEXT NOT NULL,\
    amount_sat      INTEGER NOT NULL,\
    due_day         INTEGER NOT NULL,\
    last_paid_unix  INTEGER,\
    display_label   TEXT,\
    created_at_unix INTEGER NOT NULL\
)";

/// Phase 4.5 — savings goals (sat-only in Phase 4; fiat-denominated
/// goals arrive with the Phase 5 fiat overlay).
/// `current_sat` can be a user-maintained running total OR derived
/// from `linked_wallet_id` (when set, the UI updates `current_sat` to
/// the linked wallet's balance on each reload). `color` is an
/// app-internal palette index hint (string for forward-compat with a
/// future "free color" picker).
const CREATE_TRACKING_SAVINGS_GOALS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_savings_goals (\
    id                       INTEGER PRIMARY KEY AUTOINCREMENT,\
    name                     TEXT NOT NULL,\
    target_sat               INTEGER NOT NULL,\
    current_sat              INTEGER NOT NULL DEFAULT 0,\
    color                    TEXT NOT NULL DEFAULT 'cyan',\
    deadline_unix            INTEGER,\
    linked_wallet_id         TEXT,\
    target_pct_of_net_worth  INTEGER,\
    bucket_kind              TEXT NOT NULL DEFAULT 'manual',\
    bucket_ref               TEXT,\
    goal_kind                TEXT NOT NULL DEFAULT 'savings',\
    created_at_unix          INTEGER NOT NULL\
)";

/// Phase 7.2.1 migration: add `target_pct_of_net_worth` to pre-existing
/// `tracking_savings_goals` tables. Idempotent — `SQLite` raises
/// "duplicate column name" when the column already exists, which we
/// swallow as a no-op (same pattern as the T2-10 investments migration).
const ALTER_TRACKING_SAVINGS_GOALS_ADD_PCT_SQL: &str =
    "ALTER TABLE tracking_savings_goals ADD COLUMN target_pct_of_net_worth INTEGER";

/// Phase 7.2.4 migration: add `bucket_kind` + `bucket_ref` to pre-existing
/// `tracking_savings_goals` tables. `bucket_kind` defaults to 'manual'
/// so old goals keep their hand-entered `current_sat` semantics; bucket
/// binding is opt-in. Same idempotency dance — "duplicate column name"
/// is swallowed as a no-op.
const ALTER_TRACKING_SAVINGS_GOALS_ADD_BUCKET_KIND_SQL: &str =
    "ALTER TABLE tracking_savings_goals ADD COLUMN bucket_kind TEXT NOT NULL DEFAULT 'manual'";
const ALTER_TRACKING_SAVINGS_GOALS_ADD_BUCKET_REF_SQL: &str =
    "ALTER TABLE tracking_savings_goals ADD COLUMN bucket_ref TEXT";

/// Phase 7.7 migration: add `goal_kind` (savings|sinking). Idempotent
/// — same pattern as 7.2.4: "duplicate column name" is swallowed.
const ALTER_TRACKING_SAVINGS_GOALS_ADD_GOAL_KIND_SQL: &str =
    "ALTER TABLE tracking_savings_goals ADD COLUMN goal_kind TEXT NOT NULL DEFAULT 'savings'";

/// Phase 8.6 migration: add 3 denomination columns to pre-existing
/// `tracking_savings_goals` tables. All idempotent — "duplicate column
/// name" is swallowed as a no-op (same pattern as 7.2.4 / 7.7).
///  - `target_denomination`: whitelist `'sat' | 'fiat'`; defaults to
///    `'sat'` so all legacy goals keep their current behaviour.
///  - `target_fiat_amount`: NULLABLE; only set when `denomination = 'fiat'`.
///  - `target_fiat_currency`: NULLABLE; ISO 4217 when fiat.
const ALTER_TRACKING_SAVINGS_GOALS_ADD_TARGET_DENOMINATION_SQL: &str =
    "ALTER TABLE tracking_savings_goals ADD COLUMN target_denomination TEXT NOT NULL DEFAULT 'sat'";
const ALTER_TRACKING_SAVINGS_GOALS_ADD_TARGET_FIAT_AMOUNT_SQL: &str =
    "ALTER TABLE tracking_savings_goals ADD COLUMN target_fiat_amount REAL";
const ALTER_TRACKING_SAVINGS_GOALS_ADD_TARGET_FIAT_CURRENCY_SQL: &str =
    "ALTER TABLE tracking_savings_goals ADD COLUMN target_fiat_currency TEXT";

/// Phase 4.6 — manual liabilities for the Net Worth snapshot.
/// Self-custodial Bitcoin has no native liability concept; this table
/// lets the user track external debts (mortgages, loans) so the
/// dashboard's Net Worth = wallet assets − manual liabilities. `kind`
/// is a free string ('mortgage' / 'loan' / 'credit-card' / 'other')
/// used for filtering and icon mapping in the UI. `display_label`
/// works the same as in `tracking_bills` (optional free-text override).
const CREATE_TRACKING_LIABILITIES_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_liabilities (\
    id              INTEGER PRIMARY KEY AUTOINCREMENT,\
    name            TEXT NOT NULL,\
    amount_sat      INTEGER NOT NULL,\
    kind            TEXT NOT NULL DEFAULT 'other',\
    display_label   TEXT,\
    created_at_unix INTEGER NOT NULL\
)";

/// Phase 7.5 — categorization rules. Condition (field/op/value) →
/// action (only `category` in v1). Applied at-import to any new
/// `PaymentEvent` that doesn't already have a category set; the user's
/// manual category always wins (rules only fill the gaps).
///
/// `priority` is signed-friendly i64 in SQL but we treat as a small
/// integer in UI (slider 0..=10); higher wins on conflict. `enabled`
/// is a bool stored as 0/1 — disable lets the user keep a rule around
/// without deleting it.
const CREATE_TRACKING_RULES_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_rules (\
    id              INTEGER PRIMARY KEY AUTOINCREMENT,\
    name            TEXT NOT NULL,\
    condition_field TEXT NOT NULL,\
    condition_op    TEXT NOT NULL,\
    condition_value TEXT NOT NULL,\
    action_field    TEXT NOT NULL DEFAULT 'category',\
    action_value    TEXT NOT NULL,\
    priority        INTEGER NOT NULL DEFAULT 0,\
    enabled         INTEGER NOT NULL DEFAULT 1,\
    created_at_unix INTEGER NOT NULL\
)";

/// Phase 7.5 — dismissed recurring-suggestion keys. The detector
/// surfaces "Netflix €11.99 monthly" as a candidate; when the user
/// clicks "Dismiss", we insert the normalized payee key here so the
/// same suggestion doesn't keep coming back on every refresh.
const CREATE_TRACKING_RECURRING_DISMISSED_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_recurring_dismissed (\
    payee_key       TEXT PRIMARY KEY,\
    dismissed_unix  INTEGER NOT NULL\
)";

/// Phase 7.4 — manual asset accounts (cash, real estate, vehicles,
/// brokerages outside our tracking, collectibles, business equity,
/// loans to others, etc.). Asset-side mirror of `tracking_liabilities`,
/// but multi-currency, with `value_fiat REAL` like `tracking_investments`
/// and a richer `kind` (8 values in v1).
///
/// Field decisions:
///  - `value_fiat REAL`: same choice as investments. Validated
///    `is_finite` at the FFI boundary; a €1B real-estate value is far
///    below the f64 ceiling of ~1.8e308.
///  - `currency TEXT default 'EUR'`: ISO 4217. The UI offers EUR/USD/CHF/GBP/JPY
///    like `InvestmentsCard`.
///  - `last_updated_unix`: useful for `real_estate` / `collectible`
///    (values that are updated rarely). The UI renders "Updated X ago".
///  - `notes`: free-text memory aid (e.g. "2-bed flat, bought 2015").
const CREATE_TRACKING_MANUAL_ASSETS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_manual_assets (\
    id                INTEGER PRIMARY KEY AUTOINCREMENT,\
    name              TEXT NOT NULL,\
    kind              TEXT NOT NULL DEFAULT 'cash',\
    value_fiat        REAL NOT NULL,\
    currency          TEXT NOT NULL DEFAULT 'EUR',\
    display_label     TEXT,\
    notes             TEXT,\
    last_updated_unix INTEGER NOT NULL,\
    created_at_unix   INTEGER NOT NULL\
)";

/// Phase 5.1 — fiat-denominated investments (stocks, ETFs, funds,
/// currencies, commodities, manual entries). Each position carries
/// its cost basis in the original currency + the current market
/// value (`manual_current_value`, or computed from a fetched price in
/// Phase 5.2). The dashboard converts to sat using the BTC/fiat
/// spot price from `PriceCache` when displaying.
///
/// `asset_type` is one of 'stock' | 'etf' | 'fund' | 'currency' |
/// 'commodity' | 'manual'. `ticker` is the symbol used by the price
/// provider (e.g. 'AAPL', 'IWDA.AS', 'XAU'); null when the user
/// updates manually. `quantity` is REAL because shares can be
/// fractional (many retail brokers), grams are decimals,
/// currencies can have cents. `cost_basis_currency` lets the user
/// hold AAPL in USD even when their dashboard currency is EUR;
/// cross-currency conversion comes with Phase 5.2.
/// T2-10 — `fetched_current_value` is a separate column from
/// `manual_current_value` so the Phase 5.2 auto-fetch loop does not
/// overwrite a user-entered value. The UI prefers `fetched` when it
/// is non-null AND fresher than the manual entry, otherwise falls
/// back to the manual value.
const CREATE_TRACKING_INVESTMENTS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_investments (\
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,\
    name                   TEXT NOT NULL,\
    asset_type             TEXT NOT NULL DEFAULT 'manual',\
    ticker                 TEXT,\
    quantity               REAL NOT NULL,\
    unit                   TEXT NOT NULL DEFAULT '',\
    cost_basis_fiat        REAL NOT NULL,\
    cost_basis_currency    TEXT NOT NULL DEFAULT 'EUR',\
    purchase_unix          INTEGER NOT NULL,\
    manual_current_value   REAL,\
    fetched_current_value  REAL,\
    last_priced_at_unix    INTEGER,\
    notes                  TEXT,\
    created_at_unix        INTEGER NOT NULL\
)";

/// Phase 7.8 — investment lots. One row per purchase. Replaces the
/// single-row cost basis model of Phase 5.1 when the user opts in
/// (the existing `cost_basis_fiat` field on `tracking_investments`
/// stays the canonical aggregate until a lot is added). Cascade
/// delete on the parent investment so dangling lots are impossible.
///
/// `price_per_unit` is in the parent's `cost_basis_currency`.
/// `fees_fiat` is the broker fee for THIS lot only.
const CREATE_TRACKING_INVESTMENT_LOTS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_investment_lots (\
    id              INTEGER PRIMARY KEY AUTOINCREMENT,\
    investment_id   INTEGER NOT NULL,\
    quantity        REAL NOT NULL,\
    price_per_unit  REAL NOT NULL,\
    fees_fiat       REAL NOT NULL DEFAULT 0,\
    purchase_unix   INTEGER NOT NULL,\
    notes           TEXT,\
    created_at_unix INTEGER NOT NULL,\
    FOREIGN KEY (investment_id) REFERENCES tracking_investments(id) ON DELETE CASCADE\
)";

/// Phase 8.1 — BTC/fiat historical rate cache. One row per
/// (`date_unix`, currency) pair. `date_unix` is always UTC midnight.
/// Cache lifetime is infinite for historical dates (rates don't
/// change). `source` is one of the whitelisted strings in
/// `btc_rate_history::VALID_SOURCES`.
const CREATE_BTC_RATE_HISTORY_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS btc_rate_history (\
    date_unix       INTEGER NOT NULL, \
    currency        TEXT NOT NULL, \
    rate            REAL NOT NULL, \
    source          TEXT NOT NULL, \
    fetched_at_unix INTEGER NOT NULL, \
    PRIMARY KEY (date_unix, currency)\
)";

const CREATE_BTC_RATE_HISTORY_INDEX_SQL: &str = "\
CREATE INDEX IF NOT EXISTS idx_btc_rate_history_date \
    ON btc_rate_history(date_unix)";

/// Phase 7.10 — crypto event taxonomy. One row per `(wallet_id,
/// txid_or_payment_id)` carrying a `kind` from
/// [`VALID_CRYPTO_EVENT_KINDS`]. The absence of a row equals
/// `regular` (default), so the table only stores explicit overrides
/// and auto-detected `send_to_self` markers.
///
/// Schema v3 adds `user_set` (see [`SCHEMA_VERSION`]): a user choice —
/// including an explicit `regular`, which is then STORED rather than
/// deleted — is pinned and the auto-detector
/// ([`Registry::set_event_crypto_kind_auto`]) never overwrites it.
const CREATE_TRACKING_EVENT_CRYPTO_KIND_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_event_crypto_kind (\
    wallet_id           TEXT NOT NULL,\
    txid_or_payment_id  TEXT NOT NULL,\
    kind                TEXT NOT NULL,\
    updated_at_unix     INTEGER NOT NULL,\
    PRIMARY KEY (wallet_id, txid_or_payment_id)\
)";

/// v5 — per-transaction input of the BTC average-cost view: a fiat price
/// per BTC the user typed in place of the day's market rate, and/or an
/// exclusion from the calculation. Sparse: a transaction without a row
/// uses the market rate of its day. The price is stored with its fiat
/// currency because the view is computed in the display currency.
const CREATE_TRACKING_EVENT_COST_BASIS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_event_cost_basis (\
    wallet_id           TEXT NOT NULL,\
    txid_or_payment_id  TEXT NOT NULL,\
    price_per_btc       REAL,\
    currency            TEXT,\
    excluded            INTEGER NOT NULL DEFAULT 0,\
    updated_at_unix     INTEGER NOT NULL,\
    PRIMARY KEY (wallet_id, txid_or_payment_id)\
)";

/// Phase 7.9 — sell event for an investment. Records the proceeds
/// side of the transaction; the consumption of lots happens in
/// `tracking_lot_disposals` (1 row per lot touched). Cascade delete
/// on the parent investment.
const CREATE_TRACKING_INVESTMENT_SELLS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_investment_sells (\
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,\
    investment_id        INTEGER NOT NULL,\
    sale_unix            INTEGER NOT NULL,\
    sale_price_per_unit  REAL NOT NULL,\
    sale_qty             REAL NOT NULL,\
    fees_fiat            REAL NOT NULL DEFAULT 0,\
    notes                TEXT,\
    created_at_unix      INTEGER NOT NULL,\
    FOREIGN KEY (investment_id) REFERENCES tracking_investments(id) ON DELETE CASCADE\
)";

/// Phase 7.9 — many-to-many between sells and lots. Snapshots the
/// lot's `cost_basis_per_unit` at the moment of sale (the lot row may
/// be edited later, but the realized gain stays frozen). The
/// pro-rata sell fee is already baked into `gain_loss_fiat`.
const CREATE_TRACKING_LOT_DISPOSALS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_lot_disposals (\
    id                   INTEGER PRIMARY KEY AUTOINCREMENT,\
    sell_id              INTEGER NOT NULL,\
    lot_id               INTEGER NOT NULL,\
    qty_consumed         REAL NOT NULL,\
    cost_basis_per_unit  REAL NOT NULL,\
    gain_loss_fiat       REAL NOT NULL,\
    created_at_unix      INTEGER NOT NULL,\
    FOREIGN KEY (sell_id) REFERENCES tracking_investment_sells(id) ON DELETE CASCADE,\
    FOREIGN KEY (lot_id) REFERENCES tracking_investment_lots(id) ON DELETE CASCADE\
)";

/// Phase 7.8 — per-investment cost basis method. Defaults to FIFO
/// because that's the IT/US broker default. The method is persisted
/// even when there are no lots yet — useful for future sell flow
/// (Phase 7.9) which will consume lots ordered by method.
const ALTER_TRACKING_INVESTMENTS_ADD_COST_BASIS_METHOD_SQL: &str =
    "ALTER TABLE tracking_investments ADD COLUMN cost_basis_method TEXT NOT NULL DEFAULT 'fifo'";

/// T2-10 migration: add `fetched_current_value` to pre-existing DBs.
/// Idempotent — when the column already exists `SQLite` returns
/// "duplicate column name", which we swallow as a no-op.
const ALTER_TRACKING_INVESTMENTS_ADD_FETCHED_SQL: &str =
    "ALTER TABLE tracking_investments ADD COLUMN fetched_current_value REAL";

/// Open or initialise the registry DB at `db_path`. Schema is
/// idempotent (`CREATE TABLE IF NOT EXISTS`) so this call is safe to
/// invoke on every app boot.
///
/// The encryption key MUST be derived from the **device PIN** via
/// the same Argon2id pipeline the wallet DB uses. The registry's
/// KDF descriptor is `registry.kdf.json` in the same dir as the DB
/// file; managing it is the caller's responsibility (the FFI layer
/// handles it, mirroring `wallet.kdf.json`).
pub fn open_registry(
    db_path: &Path,
    encryption_key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<Registry, OnchainError> {
    let conn = open_encrypted_connection(db_path, encryption_key)?;
    ensure_schema(&conn)?;
    Ok(Registry { conn })
}

/// Open a non-encrypted, in-memory registry. Intended for unit tests in
/// this crate and in `bhodl-wallet-onchain::net_worth_history` tests where
/// creating a temp `SQLCipher` file is unnecessary overhead.
///
/// Uses an already-opened [`rusqlite::Connection`] so the caller can
/// control whether to use `:memory:` or a shared-cache in-memory DB.
///
/// # Errors
///
/// - [`OnchainError::Storage`] if applying the schema DDL fails.
pub fn open_registry_in_memory(conn: Connection) -> Result<Registry, OnchainError> {
    ensure_schema(&conn)?;
    Ok(Registry { conn })
}

/// R1 — current registry schema version, stamped into the `SQLite` header
/// via `PRAGMA user_version` once [`ensure_schema`] has finished applying
/// the schema.
///
/// Bump this whenever a new migration step is added below and gate that
/// step on `current_version < N`. The value `1` represents the full
/// accumulated schema as of Phase 8.6 — every historical `CREATE TABLE`
/// and `ALTER TABLE ... ADD COLUMN` collapses into the v0→v1 baseline
/// because they are all already deployed in the field; there is no value
/// in replaying them as separate numbered steps.
///
/// - v2: `tracking_investment_lots.original_quantity` (M1 — purchase fees
///   are spread over the purchased size, not the post-sell remainder),
///   backfilled as `quantity + Σ disposals.qty_consumed`.
/// - v3: `tracking_event_crypto_kind.user_set` — persisted user override.
///   A user-chosen kind (including an explicit `regular`) is never
///   overwritten by the `send_to_self` auto-detector. Backfill: every
///   pre-v3 row whose kind is not `send_to_self` was necessarily set by
///   the user (the detector only ever writes `send_to_self`).
/// - v4: `wallets.mirror_of` — explicit link from a watch-only mirror to
///   the signing wallet it mirrors (decoupling decision 2026-05-18: the
///   Tracker sees a signing wallet only through that mirror). Removing
///   the signing wallet removes exactly the rows linked to it — no label
///   heuristic. Backfill is conservative, see [`backfill_mirror_links`].
/// - v5: `tracking_event_cost_basis` — user price / exclusion per
///   transaction for the BTC average-cost view. New table, no backfill.
/// - v6: `details_json` on `tracking_investments`,
///   `tracking_manual_assets` and `tracking_liabilities` — the
///   kind-specific details shown on the Portfolio cards (TER, interest
///   rate, maturity, linked mortgage…). Default `'{}'`, no backfill.
/// - v7: `tracking_bills.variable` and `tracking_bills.last_paid_amount_sat`
///   — a bill whose amount changes (power, gas): `amount_sat` is then an
///   estimate and each "paid" mark records what was actually paid.
///   Defaults: fixed, no amount; no backfill.
/// - v8: `tracking_manual_txns` — income and spending outside the
///   wallets (cash, bank account, card), entered by hand in their own
///   currency and stored with the sat value at the day's BTC rate; and
///   `tracking_bills.category` / `tracking_bills.paid_ref` — the budget
///   category of a bill and the transaction that paid it. No backfill.
/// - v9: test-mode profiles —: `profile_meta` (key/value facts, among
///   them the test-mode flag) and `demo_events` (the wallet transactions
///   of a test profile). No backfill.
/// - v10: `tracking_manual_txns.liability_id` / `liability_payment` — a
///   manual entry that pays a debt (an installment or an extra
///   repayment of a mortgage, a credit-card balance…) from its channel.
///   Null for every existing row; no backfill.
///
/// Not downgrade-compatible: an older build refuses a registry stamped
/// with a higher version (see the L9 gate in `ensure_schema`).
pub const SCHEMA_VERSION: i64 = 10;

mod demo;
pub use demo::DemoEvent;

/// v8 — income and spending outside the wallets. `amount_sat` is the
/// value at the day's BTC rate, fixed when the row is saved (the
/// budget counts sat). `amount_fiat` / `currency` keep what the user
/// typed; both null for an amount entered in sat. `account_ref` names
/// the channel the money moved through: `asset:<id>` (a manual "cash"
/// account: its balance moves with the entry, in its own currency),
/// `liability:<id>` (a credit card: spending raises the debt),
/// `other` (`PayPal`, meal vouchers…) or null (cash). The
/// category lives in `tracking_categories` like any transaction's,
/// keyed by [`manual_txn_wallet_id`] and [`manual_txn_event_id`].
const CREATE_TRACKING_MANUAL_TXNS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS tracking_manual_txns (\
    id              INTEGER PRIMARY KEY AUTOINCREMENT,\
    direction       TEXT NOT NULL,\
    amount_sat      INTEGER NOT NULL,\
    amount_fiat     REAL,\
    currency        TEXT,\
    occurred_unix   INTEGER NOT NULL,\
    account_ref     TEXT,\
    note            TEXT,\
    created_at_unix INTEGER NOT NULL\
)";

/// v8 — the budget category of a bill and the transaction that paid it
/// (`<wallet_id>|<txid>`; a manual one is `manual:…|manual-<id>`).
const ALTER_BILLS_LINK_SQL: [&str; 2] = [
    "ALTER TABLE tracking_bills ADD COLUMN category TEXT",
    "ALTER TABLE tracking_bills ADD COLUMN paid_ref TEXT",
];

/// v10 — the debt a manual entry pays (`tracking_liabilities.id`) and
/// how: one of [`crate::loan_schedule::VALID_LOAN_PAYMENT_KINDS`].
const ALTER_MANUAL_TXNS_LIABILITY_SQL: [&str; 2] = [
    "ALTER TABLE tracking_manual_txns ADD COLUMN liability_id INTEGER",
    "ALTER TABLE tracking_manual_txns ADD COLUMN liability_payment TEXT",
];

/// v8 — allowed values for `tracking_manual_txns.direction`.
pub(crate) const VALID_MANUAL_TXN_DIRECTIONS: &[&str] = &["in", "out"];

/// v8 — prefix of the wallet ids manual transactions carry ("manual"
/// in hex). Categories are keyed by wallet UUID, so each account gets a
/// stable synthetic one: see [`manual_txn_wallet_id`].
pub const MANUAL_WALLET_ID_PREFIX: &str = "6d616e75-616c-4000-8000-";

/// v8 — the wallet id a manual transaction's events and category carry,
/// one per channel: the last UUID group is `0…0` for cash, `c0…0` for
/// `other`, `a` + the asset id or `b` + the liability id (11 hex digits).
///
/// # Panics
///
/// Never: the prefix plus 12 hex digits always parses.
#[must_use]
pub fn manual_txn_wallet_id(account_ref: Option<&str>) -> Uuid {
    let tail = if account_ref == Some(MANUAL_ACCOUNT_OTHER) {
        Some(format!("c{}", "0".repeat(11)))
    } else {
        match account_ref.and_then(|r| r.split_once(':')) {
            Some(("asset", id)) => id.parse::<u64>().ok().map(|n| format!("a{n:011x}")),
            Some(("liability", id)) => id.parse::<u64>().ok().map(|n| format!("b{n:011x}")),
            _ => None,
        }
    }
    .unwrap_or_else(|| "0".repeat(12));
    Uuid::parse_str(&format!("{MANUAL_WALLET_ID_PREFIX}{tail}"))
        .expect("prefix plus 12 hex digits is a valid UUID")
}

/// v8 — the `account_ref` of the "other" channel (`PayPal`, vouchers…).
pub const MANUAL_ACCOUNT_OTHER: &str = "other";

/// v8 — the event id of manual transaction `id`.
#[must_use]
pub fn manual_txn_event_id(id: i64) -> String {
    format!("manual-{id}")
}

/// v7 — variable-amount bills.
const ALTER_BILLS_VARIABLE_SQL: [&str; 2] = [
    "ALTER TABLE tracking_bills ADD COLUMN variable INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE tracking_bills ADD COLUMN last_paid_amount_sat INTEGER",
];

/// v6 — kind-specific details of a portfolio row, a small JSON object
/// (see [`Registry::set_details`]).
const ALTER_DETAILS_JSON_SQL: [&str; 3] = [
    "ALTER TABLE tracking_investments ADD COLUMN details_json TEXT NOT NULL DEFAULT '{}'",
    "ALTER TABLE tracking_manual_assets ADD COLUMN details_json TEXT NOT NULL DEFAULT '{}'",
    "ALTER TABLE tracking_liabilities ADD COLUMN details_json TEXT NOT NULL DEFAULT '{}'",
];

/// v4 — `wallets.mirror_of`: UUID of the signing wallet a watch row was
/// created to mirror; `NULL` for user-added watch rows and signing rows.
const ALTER_WALLETS_ADD_MIRROR_OF_SQL: &str = "ALTER TABLE wallets ADD COLUMN mirror_of TEXT";

/// v2 — frozen purchased size of each lot.
const ALTER_TRACKING_INVESTMENT_LOTS_ADD_ORIGINAL_QUANTITY_SQL: &str =
    "ALTER TABLE tracking_investment_lots ADD COLUMN original_quantity REAL";

/// v2 backfill: purchased size = what is left + what was sold from it.
const BACKFILL_LOT_ORIGINAL_QUANTITY_SQL: &str = "\
UPDATE tracking_investment_lots SET original_quantity = quantity + COALESCE(\
    (SELECT SUM(d.qty_consumed) FROM tracking_lot_disposals d \
     WHERE d.lot_id = tracking_investment_lots.id), 0) \
WHERE original_quantity IS NULL";

/// v3 — origin of a crypto event kind row: 1 = chosen by the user,
/// 0 = written by the auto-detector.
const ALTER_TRACKING_EVENT_CRYPTO_KIND_ADD_USER_SET_SQL: &str =
    "ALTER TABLE tracking_event_crypto_kind ADD COLUMN user_set INTEGER NOT NULL DEFAULT 0";

/// v3 backfill: only `send_to_self` can have been auto-written.
const BACKFILL_CRYPTO_KIND_USER_SET_SQL: &str =
    "UPDATE tracking_event_crypto_kind SET user_set = 1 WHERE kind <> 'send_to_self'";

fn ensure_schema(conn: &Connection) -> Result<(), OnchainError> {
    // Phase 7.8 — enforce FK constraints (e.g. tracking_investment_lots
    // → tracking_investments CASCADE). SQLite defaults FK OFF; we
    // turn it on per-connection so the cascade actually runs.
    //
    // This pragma is per-CONNECTION state, not schema, so it MUST run on
    // every open — it lives above the version gate deliberately.
    conn.execute("PRAGMA foreign_keys = ON", [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    // R1 — version gate. Once a DB has been stamped to SCHEMA_VERSION we
    // skip the whole CREATE/ALTER block: in steady state the schema is
    // known-current, and we no longer depend on the fragile "duplicate
    // column name" error-string swallow at all. A fresh DB (user_version
    // 0) and a legacy DB migrated by the pre-versioning code (also
    // user_version 0, but with the columns already present) both fall
    // into the baseline reconciliation below — which is safe on both
    // because every statement is `CREATE ... IF NOT EXISTS` or an
    // `ADD COLUMN` whose duplicate is swallowed.
    let current_version: i64 = conn
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // L9 — a registry stamped by a NEWER build may carry columns or
    // semantics this build does not know; writing to it could corrupt
    // it. Refuse instead of silently downgrading.
    if current_version > SCHEMA_VERSION {
        return Err(OnchainError::Storage(format!(
            "registry schema version {current_version} is newer than this app supports \
             ({SCHEMA_VERSION}); update BHODL to open it"
        )));
    }
    if current_version == SCHEMA_VERSION {
        return Ok(());
    }

    // L9 — every migration step plus the version stamp commit together:
    // a crash mid-migration leaves the DB at its previous version, never
    // half-migrated but stamped current.
    let tx = conn
        .unchecked_transaction()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    if current_version < 1 {
        apply_schema_v1_baseline(&tx)?;
    }
    if current_version < 2 {
        add_column_idempotent(
            &tx,
            ALTER_TRACKING_INVESTMENT_LOTS_ADD_ORIGINAL_QUANTITY_SQL,
        )?;
        tx.execute(BACKFILL_LOT_ORIGINAL_QUANTITY_SQL, [])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
    }
    if current_version < 3 {
        add_column_idempotent(&tx, ALTER_TRACKING_EVENT_CRYPTO_KIND_ADD_USER_SET_SQL)?;
        tx.execute(BACKFILL_CRYPTO_KIND_USER_SET_SQL, [])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
    }
    if current_version < 4 {
        add_column_idempotent(&tx, ALTER_WALLETS_ADD_MIRROR_OF_SQL)?;
        backfill_mirror_links(&tx)?;
    }
    if current_version < 5 {
        tx.execute(CREATE_TRACKING_EVENT_COST_BASIS_TABLE_SQL, [])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
    }
    if current_version < 6 {
        for stmt in ALTER_DETAILS_JSON_SQL {
            add_column_idempotent(&tx, stmt)?;
        }
    }
    if current_version < 7 {
        for stmt in ALTER_BILLS_VARIABLE_SQL {
            add_column_idempotent(&tx, stmt)?;
        }
    }
    if current_version < 8 {
        tx.execute(CREATE_TRACKING_MANUAL_TXNS_TABLE_SQL, [])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        for stmt in ALTER_BILLS_LINK_SQL {
            add_column_idempotent(&tx, stmt)?;
        }
    }
    if current_version < 9 {
        for stmt in [
            demo::CREATE_PROFILE_META_TABLE_SQL,
            demo::CREATE_DEMO_EVENTS_TABLE_SQL,
        ] {
            tx.execute(stmt, [])
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
        }
    }
    if current_version < 10 {
        for stmt in ALTER_MANUAL_TXNS_LIABILITY_SQL {
            add_column_idempotent(&tx, stmt)?;
        }
    }

    // R1 — stamp the schema version into the DB header. From the next
    // open onward, the version gate at the top short-circuits this whole
    // block. `pragma_update` can't bind `user_version` as a parameter
    // (SQLite forbids it), so the value is interpolated from the
    // compile-time constant — never from user input.
    tx.execute(&format!("PRAGMA user_version = {SCHEMA_VERSION}"), [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    tx.commit()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

/// `ALTER TABLE ... ADD COLUMN`, treating "duplicate column name" (the
/// column is already there) as success.
fn add_column_idempotent(conn: &Connection, stmt: &str) -> Result<(), OnchainError> {
    match conn.execute(stmt, []) {
        Ok(_) => Ok(()),
        Err(e) => {
            let msg = e.to_string();
            if msg.contains("duplicate column name") {
                Ok(())
            } else {
                Err(OnchainError::Storage(msg))
            }
        }
    }
}

/// `true` when `label` is one of the labels the app gives to the
/// auto-created watch mirror of signing wallet `signing_label`
/// (case-insensitive):
///
/// - `"<signing_label> (tracker)"` / `"<signing_label> (tracker N)"` —
///   post-create pairing flow (`uniqueMirrorLabel` in `PinSetupScreen.kt`);
/// - `"<signing_label> · tracker"` — "Add to Tracker" from Settings and
///   the Dashboard banner.
///
/// Only used by the one-off v4 backfill ([`backfill_mirror_links`]);
/// runtime deletion follows the explicit `mirror_of` link.
pub(crate) fn is_pairing_mirror_label(label: &str, signing_label: &str) -> bool {
    let label = label.trim().to_lowercase();
    let signing = signing_label.trim().to_lowercase();
    if label == format!("{signing} \u{b7} tracker") {
        return true;
    }
    let base = format!("{signing} (tracker");
    let Some(rest) = label.strip_prefix(&base) else {
        return false;
    };
    match rest.strip_suffix(')') {
        Some("") => true,
        Some(n) => n
            .strip_prefix(' ')
            .is_some_and(|d| !d.is_empty() && d.chars().all(|c| c.is_ascii_digit())),
        None => false,
    }
}

/// Account-level xpub of a registry `descriptor_pub`; `None` when it is
/// not one of the single-key BIP-86/84/49 shapes (e.g. an address list).
fn descriptor_account_xpub(descriptor_pub: &str) -> Option<String> {
    crate::common::DescriptorBackup {
        network: bhodl_core::Network::Mainnet,
        external: descriptor_pub.to_string(),
        internal: String::new(),
    }
    .account_xpub()
}

/// v4 backfill: link pre-existing auto-created mirrors to their signing
/// wallet, **only when unambiguous**. A `watch_xpub` row W is linked to
/// signing row S iff:
///
/// 1. W carries an app-generated mirror label for S
///    ([`is_pairing_mirror_label`]),
/// 2. W and S share the same account xpub and network, and
/// 3. S is the ONLY signing row W could belong to, and W is the ONLY
///    candidate row for S.
///
/// Anything else stays unlinked — deleting the signing wallet then
/// leaves the watch row alone (the user can remove it from the Tracker
/// by hand). Never deleting a user-created watch wallet by mistake wins
/// over cleaning up every legacy mirror.
fn backfill_mirror_links(conn: &Connection) -> Result<(), OnchainError> {
    struct Row {
        id: String,
        label: String,
        kind: String,
        xpub: Option<String>,
        network: String,
    }
    let rows: Vec<Row> = {
        let mut stmt = conn
            .prepare(
                "SELECT id, label, kind, descriptor_pub, network FROM wallets \
                 WHERE mirror_of IS NULL",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mapped = stmt
            .query_map([], |r| {
                let desc: String = r.get(3)?;
                Ok(Row {
                    id: r.get(0)?,
                    label: r.get(1)?,
                    kind: r.get(2)?,
                    xpub: descriptor_account_xpub(&desc),
                    network: r.get(4)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        mapped
            .collect::<Result<_, _>>()
            .map_err(|e| OnchainError::Storage(e.to_string()))?
    };
    let signing: Vec<&Row> = rows
        .iter()
        .filter(|r| r.kind == LEGACY_SIGNING_KIND && r.xpub.is_some())
        .collect();
    // (watch id, its single possible signing id)
    let mut proposals: Vec<(&str, &str)> = Vec::new();
    for w in rows
        .iter()
        .filter(|r| r.kind == WalletKind::WatchXpub.as_db_str() && r.xpub.is_some())
    {
        let candidates: Vec<&&Row> = signing
            .iter()
            .filter(|s| {
                s.network == w.network
                    && s.xpub == w.xpub
                    && is_pairing_mirror_label(&w.label, &s.label)
            })
            .collect();
        if let [only] = candidates.as_slice() {
            proposals.push((w.id.as_str(), only.id.as_str()));
        }
    }
    for (watch_id, signing_id) in &proposals {
        let competing = proposals.iter().filter(|(_, s)| s == signing_id).count();
        if competing != 1 {
            continue;
        }
        conn.execute(
            "UPDATE wallets SET mirror_of = ?1 WHERE id = ?2",
            [signing_id, watch_id],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    }
    Ok(())
}

/// Delete every registry row that references wallet `wid` (categories,
/// crypto-event kinds, savings-goal links, mirror links) and the wallet
/// row itself, on the caller's transaction. Returns `true` if the wallet
/// row existed.
fn purge_wallet_rows(conn: &Connection, wid: &str) -> Result<bool, OnchainError> {
    for sql in [
        "DELETE FROM tracking_categories WHERE wallet_id = ?1",
        "DELETE FROM tracking_event_crypto_kind WHERE wallet_id = ?1",
        "DELETE FROM tracking_event_cost_basis WHERE wallet_id = ?1",
        "UPDATE tracking_savings_goals SET bucket_kind = 'manual', bucket_ref = NULL \
         WHERE bucket_kind = 'wallet' AND bucket_ref = ?1",
        "UPDATE tracking_savings_goals SET linked_wallet_id = NULL \
         WHERE linked_wallet_id = ?1",
        // A mirror that outlives its signing wallet (plain removal path)
        // must not keep a dangling link.
        "UPDATE wallets SET mirror_of = NULL WHERE mirror_of = ?1",
    ] {
        conn.execute(sql, [wid])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
    }
    let n = conn
        .execute("DELETE FROM wallets WHERE id = ?1", [wid])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(n > 0)
}

/// v0 → v1 baseline: the full accumulated schema as of Phase 8.6.
fn apply_schema_v1_baseline(conn: &Connection) -> Result<(), OnchainError> {
    conn.execute(CREATE_WALLETS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_CATEGORIES_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_VIEWS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_BUDGETS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_BILLS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_SAVINGS_GOALS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_LIABILITIES_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_INVESTMENTS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Phase 7.4 — new table, no migration needed (CREATE IF NOT EXISTS
    // is itself idempotent).
    conn.execute(CREATE_TRACKING_MANUAL_ASSETS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Phase 7.5 — rules + dismissed recurring suggestions.
    conn.execute(CREATE_TRACKING_RULES_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_RECURRING_DISMISSED_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Phase 7.8 — investment lots table + cost basis method column.
    // FK CASCADE is enforced by the connection-level `PRAGMA
    // foreign_keys = ON` set in open_encrypted_connection.
    conn.execute(CREATE_TRACKING_INVESTMENT_LOTS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Phase 7.9 — sells + lot disposals. Both cascade off their
    // parent (investment + sell respectively).
    conn.execute(CREATE_TRACKING_INVESTMENT_SELLS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_TRACKING_LOT_DISPOSALS_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Phase 7.10 — crypto event kind taxonomy. Sparse: rows only
    // exist for explicit overrides + auto-detected send_to_self.
    conn.execute(CREATE_TRACKING_EVENT_CRYPTO_KIND_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Idempotent ALTER for the cost basis method column.
    match conn.execute(ALTER_TRACKING_INVESTMENTS_ADD_COST_BASIS_METHOD_SQL, []) {
        Ok(_) => {}
        Err(e) => {
            let msg = e.to_string();
            if !msg.contains("duplicate column name") {
                return Err(OnchainError::Storage(msg));
            }
        }
    }
    // T2-10 migration: idempotently add fetched_current_value to a
    // pre-existing tracking_investments table. SQLite raises
    // "duplicate column name" when the column is already there; we
    // treat that as success. Every other error fails loudly.
    match conn.execute(ALTER_TRACKING_INVESTMENTS_ADD_FETCHED_SQL, []) {
        Ok(_) => {}
        Err(e) => {
            let msg = e.to_string();
            if !msg.contains("duplicate column name") {
                return Err(OnchainError::Storage(msg));
            }
        }
    }
    // Phase 7.2.1 migration: add target_pct_of_net_worth to existing
    // tracking_savings_goals tables. Same idempotency dance.
    match conn.execute(ALTER_TRACKING_SAVINGS_GOALS_ADD_PCT_SQL, []) {
        Ok(_) => {}
        Err(e) => {
            let msg = e.to_string();
            if !msg.contains("duplicate column name") {
                return Err(OnchainError::Storage(msg));
            }
        }
    }
    // Phase 7.2.4 + 7.7 migrations: add bucket_kind + bucket_ref +
    // goal_kind to existing tracking_savings_goals tables. Three
    // separate ALTERs because SQLite accepts only one column per
    // ALTER TABLE statement. Same idempotency dance — all can be
    // re-run safely (we swallow "duplicate column name").
    for stmt in [
        ALTER_TRACKING_SAVINGS_GOALS_ADD_BUCKET_KIND_SQL,
        ALTER_TRACKING_SAVINGS_GOALS_ADD_BUCKET_REF_SQL,
        ALTER_TRACKING_SAVINGS_GOALS_ADD_GOAL_KIND_SQL,
    ] {
        match conn.execute(stmt, []) {
            Ok(_) => {}
            Err(e) => {
                let msg = e.to_string();
                if !msg.contains("duplicate column name") {
                    return Err(OnchainError::Storage(msg));
                }
            }
        }
    }
    // Phase 8.1 — BTC rate history cache table + index. Both are
    // idempotent (CREATE TABLE/INDEX IF NOT EXISTS).
    conn.execute(CREATE_BTC_RATE_HISTORY_TABLE_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    conn.execute(CREATE_BTC_RATE_HISTORY_INDEX_SQL, [])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    // Phase 8.2 — btc_at_time columns on the 4 fiat-denominated tables.
    // Each ALTER is idempotent ("duplicate column name" is swallowed).
    crate::btc_at_time_backfill::apply_8_2_migrations(conn)?;
    // Phase 8.6 — denomination columns on tracking_savings_goals. Three
    // separate ALTERs; all swallow "duplicate column name".
    for stmt in [
        ALTER_TRACKING_SAVINGS_GOALS_ADD_TARGET_DENOMINATION_SQL,
        ALTER_TRACKING_SAVINGS_GOALS_ADD_TARGET_FIAT_AMOUNT_SQL,
        ALTER_TRACKING_SAVINGS_GOALS_ADD_TARGET_FIAT_CURRENCY_SQL,
    ] {
        match conn.execute(stmt, []) {
            Ok(_) => {}
            Err(e) => {
                let msg = e.to_string();
                if !msg.contains("duplicate column name") {
                    return Err(OnchainError::Storage(msg));
                }
            }
        }
    }

    Ok(())
}

/// Handle on an open, device-PIN-decrypted registry DB.
///
/// The handle owns the `SQLCipher` `Connection` and is the only path
/// to read or mutate registry rows. When dropped, the underlying
/// `SQLite` handle closes and the file lock is released — see
/// `bhodl-uniffi`'s `RegistryHandle::shutdown` for the explicit
/// synchronous release used by the wipe path.
#[derive(Debug)]
pub struct Registry {
    conn: Connection,
}

impl Registry {
    /// Insert a new wallet row. Returns `Err` if a row with the
    /// same `id` already exists (UUID collisions are
    /// statistically impossible from CSPRNG; an error here means
    /// the caller passed a duplicate ID).
    pub fn add_wallet(&self, record: &WalletRecord) -> Result<(), OnchainError> {
        self.conn
            .execute(
                "INSERT INTO wallets \
                    (id, label, kind, descriptor_pub, network, created_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                (
                    record.id.to_string(),
                    &record.label,
                    record.kind.as_db_str(),
                    &record.descriptor_pub,
                    &record.network,
                    record.created_at_unix,
                ),
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    /// Read every wallet row in insertion order (by `created_at_unix`
    /// ascending). Insertion order is stable enough for the UI list
    /// while leaving room for the user to reorder via a separate
    /// `display_order` column in a future migration.
    pub fn list_wallets(&self) -> Result<Vec<WalletRecord>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, label, kind, descriptor_pub, network, created_at_unix \
                 FROM wallets WHERE kind != ?1 ORDER BY created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([LEGACY_SIGNING_KIND], |row| {
                let id_str: String = row.get(0)?;
                let kind_str: String = row.get(2)?;
                Ok((
                    id_str,
                    row.get::<_, String>(1)?,
                    kind_str,
                    row.get::<_, String>(3)?,
                    row.get::<_, String>(4)?,
                    row.get::<_, i64>(5)?,
                ))
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;

        let mut out = Vec::new();
        for row in rows {
            let (id_str, label, kind_str, descriptor_pub, network, created_at_unix) =
                row.map_err(|e| OnchainError::Storage(e.to_string()))?;
            let id = Uuid::parse_str(&id_str)
                .map_err(|e| OnchainError::Storage(format!("invalid UUID in registry row: {e}")))?;
            let kind = WalletKind::from_db_str(&kind_str).ok_or_else(|| {
                OnchainError::Storage(format!("unknown wallet kind in registry: {kind_str}"))
            })?;
            out.push(WalletRecord {
                id,
                label,
                kind,
                descriptor_pub,
                network,
                created_at_unix,
            });
        }
        Ok(out)
    }

    /// Look up one wallet by its UUID. Returns `Ok(None)` when no
    /// row matches (not an error — the caller decides how to
    /// surface it).
    pub fn get_wallet(&self, id: &Uuid) -> Result<Option<WalletRecord>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, label, kind, descriptor_pub, network, created_at_unix \
                 FROM wallets WHERE id = ?1 AND kind != ?2",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut rows = stmt
            .query((id.to_string(), LEGACY_SIGNING_KIND))
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        match rows
            .next()
            .map_err(|e| OnchainError::Storage(e.to_string()))?
        {
            None => Ok(None),
            Some(row) => {
                let id_str: String = row
                    .get(0)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?;
                let id = Uuid::parse_str(&id_str).map_err(|e| {
                    OnchainError::Storage(format!("invalid UUID in registry row: {e}"))
                })?;
                let kind_str: String = row
                    .get(2)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?;
                let kind = WalletKind::from_db_str(&kind_str).ok_or_else(|| {
                    OnchainError::Storage(format!("unknown wallet kind in registry: {kind_str}"))
                })?;
                Ok(Some(WalletRecord {
                    id,
                    label: row
                        .get(1)
                        .map_err(|e| OnchainError::Storage(e.to_string()))?,
                    kind,
                    descriptor_pub: row
                        .get(3)
                        .map_err(|e| OnchainError::Storage(e.to_string()))?,
                    network: row
                        .get(4)
                        .map_err(|e| OnchainError::Storage(e.to_string()))?,
                    created_at_unix: row
                        .get(5)
                        .map_err(|e| OnchainError::Storage(e.to_string()))?,
                }))
            }
        }
    }

    /// Delete one wallet by UUID. Returns `true` if a row was
    /// removed, `false` if no row matched.
    ///
    /// **Does not delete the on-disk artefact**
    /// `watch-state/<id>.sqlite` — that is the caller's
    /// responsibility because the registry layer should not be
    /// reaching outside its own DB file. The FFI / Kotlin side
    /// orchestrates the full delete sequence (close active wallet
    /// handle, remove file tree, remove registry row).
    pub fn remove_wallet(&self, id: &Uuid) -> Result<bool, OnchainError> {
        let affected = self
            .conn
            .execute("DELETE FROM wallets WHERE id = ?1", [id.to_string()])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(affected > 0)
    }

    /// Delete one wallet row **and** every registry row that references
    /// it, in a single transaction: tracking categories, crypto-event
    /// kinds, and savings-goal links (a goal bound to the wallet's
    /// bucket falls back to `manual`, keeping its last `current_sat`).
    /// Any failure rolls the whole removal back — nothing is left
    /// half-deleted or orphaned. Returns `true` if the wallet row existed.
    ///
    /// Like [`Registry::remove_wallet`], on-disk artefacts are the
    /// caller's responsibility.
    pub fn remove_wallet_with_metadata(&self, id: &Uuid) -> Result<bool, OnchainError> {
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let removed = purge_wallet_rows(&tx, &id.to_string())?;
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(removed)
    }

    /// Rename a wallet. Returns `true` if the row exists, `false`
    /// otherwise.
    pub fn rename_wallet(&self, id: &Uuid, new_label: &str) -> Result<bool, OnchainError> {
        let affected = self
            .conn
            .execute(
                "UPDATE wallets SET label = ?1 WHERE id = ?2",
                (new_label, id.to_string()),
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(affected > 0)
    }

    /// UUIDs of the signing wallets left by builds before the watch-only
    /// rework (keys under `wallets/<uuid>/`). Empty on a clean install.
    pub fn legacy_signing_wallet_ids(&self) -> Result<Vec<Uuid>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare("SELECT id FROM wallets WHERE kind = ?1")
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let ids: Vec<String> = stmt
            .query_map([LEGACY_SIGNING_KIND], |r| r.get(0))
            .map_err(|e| OnchainError::Storage(e.to_string()))?
            .collect::<Result<_, _>>()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        ids.iter()
            .map(|id| {
                Uuid::parse_str(id).map_err(|e| {
                    OnchainError::Storage(format!("invalid UUID in registry row: {e}"))
                })
            })
            .collect()
    }

    /// Remove every legacy signing wallet row and the registry rows that
    /// reference it, in one transaction. Watch rows that mirrored one of
    /// them stay as ordinary watch wallets. Returns the removed UUIDs; the
    /// caller deletes their `wallets/<uuid>/` directories.
    pub fn purge_legacy_signing_wallets(&self) -> Result<Vec<Uuid>, OnchainError> {
        let ids = self.legacy_signing_wallet_ids()?;
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        for id in &ids {
            purge_wallet_rows(&tx, &id.to_string())?;
        }
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(ids)
    }

    // -----------------------------------------------------------------------
    // Watch-wallet convenience constructors
    // -----------------------------------------------------------------------

    /// Register an HD watch wallet from an account-level xpub.
    ///
    /// Builds the canonical xpub descriptor (BIP-86/84/49) and inserts a
    /// `WalletKind::WatchXpub` row. Returns the freshly-inserted `WalletRecord`.
    ///
    /// Only the **external** descriptor is stored (the internal/change
    /// descriptor is derived by BDK at wallet-open time using the same xpub).
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Descriptor`] — xpub fails to parse, or its network
    ///   byte does not match `network`, or `label` is empty.
    /// - [`OnchainError::Storage`] — DB insert failed.
    pub fn add_watch_xpub(
        &self,
        label: &str,
        xpub: &str,
        network: bhodl_core::Network,
        bip: WatchBip,
    ) -> Result<WalletRecord, OnchainError> {
        let record = Self::build_watch_xpub_record(label, xpub, network, bip)?;
        self.add_wallet(&record)?;
        Ok(record)
    }

    /// Validate and build (but do NOT insert) a `WatchXpub` record.
    ///
    /// Lets the FFI layer materialise the watch-state file under the
    /// record's UUID first and insert the registry row last, so a crash
    /// or failure never leaves a registry row without its state file.
    /// SLIP-132 `zpub`/`ypub` keys are normalised to `xpub` form.
    ///
    /// # Errors
    ///
    /// Same validation errors as [`Registry::add_watch_xpub`].
    pub fn build_watch_xpub_record(
        label: &str,
        xpub: &str,
        network: bhodl_core::Network,
        bip: WatchBip,
    ) -> Result<WalletRecord, OnchainError> {
        if label.is_empty() {
            return Err(OnchainError::Descriptor("label must not be empty".into()));
        }
        let (desc_ext, _desc_int) = xpub_watch_descriptors(xpub, network, bip)?;
        let record = WalletRecord {
            id: Uuid::new_v4(),
            label: label.to_string(),
            kind: WalletKind::WatchXpub,
            descriptor_pub: desc_ext,
            network: network_as_db_str(network)?,
            created_at_unix: now_unix(),
        };
        Ok(record)
    }

    /// Register a watch wallet over an explicit list of addresses.
    ///
    /// Each address is validated against `network`; any standard address
    /// type is accepted (address wallets track raw script pubkeys). The list
    /// is deduplicated (on the canonical address string) and stored as a
    /// JSON array in `descriptor_pub`. An empty list returns
    /// `Err(InvalidAddress)`.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidAddress`] — empty list, parse failure, wrong
    ///   network.
    /// - [`OnchainError::Descriptor`] — empty label.
    /// - [`OnchainError::Storage`] — DB insert failed.
    pub fn add_watch_addresses(
        &self,
        label: &str,
        addresses: &[String],
        network: bhodl_core::Network,
    ) -> Result<WalletRecord, OnchainError> {
        let record = Self::build_watch_addresses_record(label, addresses, network)?;
        self.add_wallet(&record)?;
        Ok(record)
    }

    /// Validate and build (but do NOT insert) a `WatchAddresses` record.
    /// See [`Registry::build_watch_xpub_record`] for the rationale.
    ///
    /// # Errors
    ///
    /// Same validation errors as [`Registry::add_watch_addresses`].
    pub fn build_watch_addresses_record(
        label: &str,
        addresses: &[String],
        network: bhodl_core::Network,
    ) -> Result<WalletRecord, OnchainError> {
        if label.is_empty() {
            return Err(OnchainError::Descriptor("label must not be empty".into()));
        }
        if addresses.is_empty() {
            return Err(OnchainError::InvalidAddress("empty address list".into()));
        }
        // Validate every address and deduplicate while preserving order.
        let mut seen = std::collections::HashSet::new();
        let mut deduped: Vec<String> = Vec::new();
        for addr_str in addresses {
            let canonical = validate_watch_address(addr_str, network)?.to_string();
            if seen.insert(canonical.clone()) {
                deduped.push(canonical);
            }
        }
        // Serialise as a compact JSON array.
        let descriptor_pub = serde_json::to_string(&deduped)
            .map_err(|e| OnchainError::Storage(format!("JSON serialisation failed: {e}")))?;
        let record = WalletRecord {
            id: Uuid::new_v4(),
            label: label.to_string(),
            kind: WalletKind::WatchAddresses,
            descriptor_pub,
            network: network_as_db_str(network)?,
            created_at_unix: now_unix(),
        };
        Ok(record)
    }

    /// Register an HD watch wallet with full descriptor metadata
    /// (Sparrow / Specter / Coldcard export format).
    ///
    /// Builds the canonical HD descriptor with origin
    /// `[fingerprint/path]xpub/0/*` and stores it under
    /// `WalletKind::WatchHd`. Same validation as `add_watch_xpub`, plus the
    /// path/purpose check (only BIP-86/84/49 paths accepted).
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Descriptor`] — invalid xpub, network mismatch,
    ///   unsupported BIP path, or empty label.
    /// - [`OnchainError::Storage`] — DB insert failed.
    pub fn add_watch_hd(
        &self,
        label: &str,
        master_fingerprint: &str,
        derivation_path: &str,
        xpub: &str,
        network: bhodl_core::Network,
    ) -> Result<WalletRecord, OnchainError> {
        let record =
            Self::build_watch_hd_record(label, master_fingerprint, derivation_path, xpub, network)?;
        self.add_wallet(&record)?;
        Ok(record)
    }

    /// Validate and build (but do NOT insert) a `WatchHd` record.
    /// See [`Registry::build_watch_xpub_record`] for the rationale.
    ///
    /// # Errors
    ///
    /// Same validation errors as [`Registry::add_watch_hd`].
    pub fn build_watch_hd_record(
        label: &str,
        master_fingerprint: &str,
        derivation_path: &str,
        xpub: &str,
        network: bhodl_core::Network,
    ) -> Result<WalletRecord, OnchainError> {
        if label.is_empty() {
            return Err(OnchainError::Descriptor("label must not be empty".into()));
        }
        let (desc_ext, _desc_int) =
            hd_watch_descriptors(master_fingerprint, derivation_path, xpub, network)?;
        let record = WalletRecord {
            id: Uuid::new_v4(),
            label: label.to_string(),
            kind: WalletKind::WatchHd,
            descriptor_pub: desc_ext,
            network: network_as_db_str(network)?,
            created_at_unix: now_unix(),
        };
        Ok(record)
    }

    // -----------------------------------------------------------------------
    // Phase 3.4 — tracking categories (cross-wallet)
    // -----------------------------------------------------------------------

    /// Set or clear the category attached to a `(wallet_id, txid)` pair.
    /// Pass `None` to delete any existing category for the row; pass
    /// `Some("")` to do the same (empty strings are treated as "no
    /// category" so the UI doesn't accumulate ghost rows from the user
    /// clearing the input field).
    pub fn set_event_category(
        &self,
        wallet_id: &Uuid,
        txid_or_payment_id: &str,
        category: Option<&str>,
    ) -> Result<(), OnchainError> {
        let trimmed = category.map(str::trim).filter(|s| !s.is_empty());
        match trimmed {
            None => {
                self.conn
                    .execute(
                        "DELETE FROM tracking_categories \
                         WHERE wallet_id = ?1 AND txid_or_payment_id = ?2",
                        (wallet_id.to_string(), txid_or_payment_id),
                    )
                    .map_err(|e| OnchainError::Storage(e.to_string()))?;
            }
            Some(cat) => {
                self.conn
                    .execute(
                        "INSERT OR REPLACE INTO tracking_categories \
                            (wallet_id, txid_or_payment_id, category, updated_at_unix) \
                         VALUES (?1, ?2, ?3, ?4)",
                        (wallet_id.to_string(), txid_or_payment_id, cat, now_unix()),
                    )
                    .map_err(|e| OnchainError::Storage(e.to_string()))?;
            }
        }
        Ok(())
    }

    /// Read the category for a `(wallet_id, txid)` pair. Returns
    /// `Ok(None)` when no category is set.
    pub fn get_event_category(
        &self,
        wallet_id: &Uuid,
        txid_or_payment_id: &str,
    ) -> Result<Option<String>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT category FROM tracking_categories \
                 WHERE wallet_id = ?1 AND txid_or_payment_id = ?2",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut rows = stmt
            .query((wallet_id.to_string(), txid_or_payment_id))
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        match rows
            .next()
            .map_err(|e| OnchainError::Storage(e.to_string()))?
        {
            None => Ok(None),
            Some(row) => Ok(Some(
                row.get::<_, String>(0)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?,
            )),
        }
    }

    /// List the distinct categories ever used in this registry, sorted
    /// alphabetically. Drives the autocomplete field in the Tracking
    /// category picker.
    pub fn list_categories(&self) -> Result<Vec<String>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT DISTINCT category FROM tracking_categories \
                 ORDER BY category COLLATE NOCASE ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Dump every (`wallet_id`, `txid_or_payment_id`, category) row in the
    /// table. Used by the FFI when populating `PaymentEvent.category`
    /// across many events at once — one query is much cheaper than N
    /// `get_event_category` round-trips when building the Tracking
    /// history list. Returned tuples carry the UUID as a string so the
    /// caller can index a `HashMap` keyed on `(wallet_id, txid)` without
    /// re-parsing.
    pub fn dump_categories(&self) -> Result<Vec<(String, String, String)>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare("SELECT wallet_id, txid_or_payment_id, category FROM tracking_categories")
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete every category row that belongs to `wallet_id`. Called
    /// when the wallet itself is removed so we don't accumulate
    /// orphan rows pointing at a wallet UUID that no longer exists.
    pub fn purge_categories_for_wallet(&self, wallet_id: &Uuid) -> Result<(), OnchainError> {
        self.conn
            .execute(
                "DELETE FROM tracking_categories WHERE wallet_id = ?1",
                [wallet_id.to_string()],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    // ----------------------------------------------------------------
    // Phase 7.10 — crypto event kind taxonomy
    // ----------------------------------------------------------------

    /// USER override: upsert the `kind` for a `(wallet_id,
    /// txid_or_payment_id)` pair and pin it (`user_set = 1`). Every
    /// kind — including `"regular"` — is stored explicitly, so the
    /// `send_to_self` auto-detector cannot re-tag an event the user
    /// reset to regular. Validated against [`VALID_CRYPTO_EVENT_KINDS`].
    pub fn set_event_crypto_kind(
        &self,
        wallet_id: &str,
        txid_or_payment_id: &str,
        kind: &str,
    ) -> Result<(), OnchainError> {
        validate_crypto_event_kind(kind)?;
        self.conn
            .execute(
                "INSERT OR REPLACE INTO tracking_event_crypto_kind \
                    (wallet_id, txid_or_payment_id, kind, updated_at_unix, user_set) \
                 VALUES (?1, ?2, ?3, ?4, 1)",
                (wallet_id, txid_or_payment_id, kind, now_unix()),
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    /// AUTO-DETECTOR write: like [`Self::set_event_crypto_kind`] but
    /// never touches a user-pinned row (`user_set = 1`). `"regular"`
    /// deletes an auto row (the implicit default). Returns `true` when
    /// the stored state changed, `false` when a user override won (or
    /// there was nothing to clear).
    pub fn set_event_crypto_kind_auto(
        &self,
        wallet_id: &str,
        txid_or_payment_id: &str,
        kind: &str,
    ) -> Result<bool, OnchainError> {
        validate_crypto_event_kind(kind)?;
        let changed = if kind == "regular" {
            self.conn.execute(
                "DELETE FROM tracking_event_crypto_kind \
                 WHERE wallet_id = ?1 AND txid_or_payment_id = ?2 AND user_set = 0",
                (wallet_id, txid_or_payment_id),
            )
        } else {
            self.conn.execute(
                "INSERT INTO tracking_event_crypto_kind \
                    (wallet_id, txid_or_payment_id, kind, updated_at_unix, user_set) \
                 VALUES (?1, ?2, ?3, ?4, 0) \
                 ON CONFLICT (wallet_id, txid_or_payment_id) DO UPDATE SET \
                    kind = excluded.kind, updated_at_unix = excluded.updated_at_unix \
                 WHERE tracking_event_crypto_kind.user_set = 0",
                (wallet_id, txid_or_payment_id, kind, now_unix()),
            )
        }
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(changed > 0)
    }

    /// Read the kind for a `(wallet_id, txid)` pair. Returns `None`
    /// when no row exists — caller interprets that as the implicit
    /// `"regular"`. A user-pinned `"regular"` is returned as
    /// `Some("regular")`.
    pub fn get_event_crypto_kind(
        &self,
        wallet_id: &str,
        txid_or_payment_id: &str,
    ) -> Result<Option<String>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT kind FROM tracking_event_crypto_kind \
                 WHERE wallet_id = ?1 AND txid_or_payment_id = ?2",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut rows = stmt
            .query((wallet_id, txid_or_payment_id))
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        match rows
            .next()
            .map_err(|e| OnchainError::Storage(e.to_string()))?
        {
            None => Ok(None),
            Some(row) => Ok(Some(
                row.get::<_, String>(0)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?,
            )),
        }
    }

    /// Dump every (`wallet_id`, `txid_or_payment_id`, kind, `user_set`) row.
    /// Used by the FFI to populate `PaymentEvent.crypto_event_kind`
    /// across many events at once — one query is much cheaper than N
    /// per-event lookups.
    pub fn dump_crypto_event_kinds(
        &self,
    ) -> Result<Vec<(String, String, String, bool)>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT wallet_id, txid_or_payment_id, kind, user_set \
                 FROM tracking_event_crypto_kind",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)? != 0,
                ))
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for row in rows {
            out.push(row.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete every crypto-kind row that belongs to `wallet_id`.
    /// Called alongside `purge_categories_for_wallet` when the wallet
    /// itself is removed.
    pub fn purge_crypto_event_kinds_for_wallet(&self, wallet_id: &str) -> Result<(), OnchainError> {
        self.conn
            .execute(
                "DELETE FROM tracking_event_crypto_kind WHERE wallet_id = ?1",
                [wallet_id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    // ----------------------------------------------------------------
    // BTC average cost — per-transaction price / exclusion (schema v5)
    // ----------------------------------------------------------------

    /// Set the user's input for one transaction of the BTC average-cost
    /// view. `price` is `(fiat per BTC, ISO currency)`; `None` means "use
    /// the market rate of the day". `excluded` drops the transaction from
    /// the calculation. With neither set the row is deleted, so a
    /// transaction back on its defaults leaves nothing behind.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidArgument`] — negative / non-finite price,
    ///   or a currency that is not three ASCII letters.
    /// - [`OnchainError::Storage`] — write failure.
    pub fn set_event_cost_basis(
        &self,
        wallet_id: &str,
        txid_or_payment_id: &str,
        price: Option<(f64, &str)>,
        excluded: bool,
    ) -> Result<(), OnchainError> {
        let price = match price {
            Some((value, currency)) => {
                if !value.is_finite() || value < 0.0 {
                    return Err(OnchainError::InvalidArgument(format!(
                        "price_per_btc must be a finite number >= 0, got {value}"
                    )));
                }
                let currency = currency.trim().to_ascii_uppercase();
                if currency.len() != 3 || !currency.bytes().all(|b| b.is_ascii_uppercase()) {
                    return Err(OnchainError::InvalidArgument(format!(
                        "currency must be a 3-letter ISO code, got {currency:?}"
                    )));
                }
                Some((value, currency))
            }
            None => None,
        };
        if price.is_none() && !excluded {
            self.conn
                .execute(
                    "DELETE FROM tracking_event_cost_basis \
                     WHERE wallet_id = ?1 AND txid_or_payment_id = ?2",
                    (wallet_id, txid_or_payment_id),
                )
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            return Ok(());
        }
        let (value, currency) = price.unzip();
        self.conn
            .execute(
                "INSERT OR REPLACE INTO tracking_event_cost_basis \
                    (wallet_id, txid_or_payment_id, price_per_btc, currency, excluded, \
                     updated_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                (
                    wallet_id,
                    txid_or_payment_id,
                    value,
                    currency,
                    i64::from(excluded),
                    now_unix(),
                ),
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    /// Every stored per-transaction input of the BTC average-cost view.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::Storage`] — read failure.
    pub fn list_event_cost_basis(&self) -> Result<Vec<EventCostBasis>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT wallet_id, txid_or_payment_id, price_per_btc, currency, excluded \
                 FROM tracking_event_cost_basis",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                let price: Option<f64> = row.get(2)?;
                let currency: Option<String> = row.get(3)?;
                Ok(EventCostBasis {
                    wallet_id: row.get(0)?,
                    txid_or_payment_id: row.get(1)?,
                    price: price.zip(currency),
                    excluded: row.get::<_, i64>(4)? != 0,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        rows.collect::<Result<_, _>>()
            .map_err(|e| OnchainError::Storage(e.to_string()))
    }

    // ----------------------------------------------------------------
    // Phase 3.8 — saved tracking views
    // ----------------------------------------------------------------

    /// Insert (or replace by name) a saved tracking view. The
    /// `period` is one of "D7" / "D30" / "D90" / "Y1" / "ALL"
    /// (string-compared on the FFI side, no enum reified here so
    /// future periods land without a migration). `category_filter`
    /// uses the same tri-state as the UI: `None` = "all categories",
    /// `Some("")` = uncategorised only, `Some(other)` = exact
    /// match. `time_range` is `(start, end)` epoch seconds when
    /// active, `None` otherwise.
    pub fn save_view(
        &self,
        name: &str,
        period: &str,
        category_filter: Option<&str>,
        time_range: Option<(i64, i64)>,
    ) -> Result<i64, OnchainError> {
        let trimmed = name.trim();
        if trimmed.is_empty() {
            return Err(OnchainError::Storage(
                "view name cannot be empty".to_string(),
            ));
        }
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs().cast_signed())
            .unwrap_or(0);
        // INSERT OR REPLACE on the unique `name` column means saving
        // an existing name overwrites it — convenient "update view"
        // semantics without a separate UPDATE method.
        self.conn
            .execute(
                "INSERT OR REPLACE INTO tracking_views \
                 (name, period, category_filter, time_range_start, time_range_end, created_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                rusqlite::params![
                    trimmed,
                    period,
                    category_filter,
                    time_range.map(|t| t.0),
                    time_range.map(|t| t.1),
                    now,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// List every saved view, oldest-first.
    pub fn list_views(&self) -> Result<Vec<SavedView>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name, period, category_filter, time_range_start, time_range_end, created_at_unix \
                 FROM tracking_views ORDER BY created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                let id: i64 = row.get(0)?;
                let name: String = row.get(1)?;
                let period: String = row.get(2)?;
                let category_filter: Option<String> = row.get(3)?;
                let range_start: Option<i64> = row.get(4)?;
                let range_end: Option<i64> = row.get(5)?;
                let created: i64 = row.get(6)?;
                Ok(SavedView {
                    id,
                    name,
                    period,
                    category_filter,
                    time_range: match (range_start, range_end) {
                        (Some(s), Some(e)) => Some((s, e)),
                        _ => None,
                    },
                    created_at_unix: created,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a saved view by id.
    pub fn delete_view(&self, id: i64) -> Result<(), OnchainError> {
        self.conn
            .execute("DELETE FROM tracking_views WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    // ----------------------------------------------------------------
    // Phase 4.3 — Budgets
    // ----------------------------------------------------------------

    /// Upsert a budget for a category. `period` defaults to "monthly".
    /// Re-saving overwrites — UI is responsible for confirming the
    /// overwrite when the user is editing rather than creating.
    pub fn set_budget(
        &self,
        category: &str,
        budgeted_sat: u64,
        period: &str,
    ) -> Result<(), OnchainError> {
        validate_non_blank("budget category", category)?;
        validate_budget_period(period)?;
        self.conn
            .execute(
                "INSERT OR REPLACE INTO tracking_budgets \
                    (category, budgeted_sat, period, updated_at_unix) \
                 VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![
                    category,
                    sat_to_i64("budgeted_sat", budgeted_sat)?,
                    period,
                    now_unix()
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    /// List all budgets, ordered alphabetically by category. Empty
    /// list when no budget has been set.
    pub fn list_budgets(&self) -> Result<Vec<TrackingBudget>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT category, budgeted_sat, period, updated_at_unix \
                 FROM tracking_budgets ORDER BY category ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingBudget {
                    category: row.get(0)?,
                    budgeted_sat: row.get::<_, i64>(1)?.cast_unsigned(),
                    period: row.get(2)?,
                    updated_at_unix: row.get(3)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a budget by category. Returns `Ok(true)` if a row was
    /// removed, `Ok(false)` if no row matched (UI-friendly: lets the
    /// caller skip a redundant reload when nothing changed).
    pub fn delete_budget(&self, category: &str) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute(
                "DELETE FROM tracking_budgets WHERE category = ?1",
                [category],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 4.4 — Bills tracker
    // ----------------------------------------------------------------

    /// Create a new bill row, return its auto-generated id.
    pub fn add_bill(
        &self,
        name: &str,
        amount_sat: u64,
        due_day: u32,
        display_label: Option<&str>,
    ) -> Result<i64, OnchainError> {
        validate_non_blank("bill name", name)?;
        validate_due_day(due_day)?;
        self.conn
            .execute(
                "INSERT INTO tracking_bills \
                    (name, amount_sat, due_day, last_paid_unix, display_label, created_at_unix) \
                 VALUES (?1, ?2, ?3, NULL, ?4, ?5)",
                rusqlite::params![
                    name,
                    sat_to_i64("amount_sat", amount_sat)?,
                    i64::from(due_day),
                    display_label,
                    now_unix(),
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Update fields of an existing bill. Returns `Ok(false)` if no
    /// row matched the id.
    pub fn update_bill(
        &self,
        id: i64,
        name: &str,
        amount_sat: u64,
        due_day: u32,
        display_label: Option<&str>,
    ) -> Result<bool, OnchainError> {
        validate_non_blank("bill name", name)?;
        validate_due_day(due_day)?;
        let n = self
            .conn
            .execute(
                "UPDATE tracking_bills SET name = ?1, amount_sat = ?2, due_day = ?3, \
                    display_label = ?4 WHERE id = ?5",
                rusqlite::params![
                    name,
                    sat_to_i64("amount_sat", amount_sat)?,
                    i64::from(due_day),
                    display_label,
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Mark a bill as paid at `unix_ts`. Used by the dashboard's
    /// "Paid" checkbox. Pass `None` to clear the mark (e.g. user
    /// unchecks). Returns false when no row matches.
    pub fn mark_bill_paid(&self, id: i64, unix_ts: Option<i64>) -> Result<bool, OnchainError> {
        // Clearing the mark also clears the amount it recorded (v7) and
        // the transaction it was linked to (v8).
        let n = self
            .conn
            .execute(
                "UPDATE tracking_bills SET last_paid_unix = ?1, \
                    last_paid_amount_sat = CASE WHEN ?1 IS NULL THEN NULL ELSE last_paid_amount_sat END, \
                    paid_ref = CASE WHEN ?1 IS NULL THEN NULL ELSE paid_ref END \
                 WHERE id = ?2",
                rusqlite::params![unix_ts, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// v7 — mark a bill paid at `unix_ts` with the amount actually paid
    /// (variable bills: power, gas…). Returns false when no row matches.
    pub fn mark_bill_paid_amount(
        &self,
        id: i64,
        unix_ts: i64,
        amount_sat: u64,
    ) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute(
                "UPDATE tracking_bills SET last_paid_unix = ?1, last_paid_amount_sat = ?2 WHERE id = ?3",
                rusqlite::params![unix_ts, sat_to_i64("amount_sat", amount_sat)?, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// v7 — whether the bill's amount varies (`amount_sat` is then an
    /// estimate). Returns false when no row matches.
    pub fn set_bill_variable(&self, id: i64, variable: bool) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute(
                "UPDATE tracking_bills SET variable = ?1 WHERE id = ?2",
                rusqlite::params![variable, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// v8 — the budget category of a bill (`None` clears it). Returns
    /// false when no row matches.
    pub fn set_bill_category(&self, id: i64, category: Option<&str>) -> Result<bool, OnchainError> {
        let category = category.map(str::trim).filter(|c| !c.is_empty());
        let n = self
            .conn
            .execute(
                "UPDATE tracking_bills SET category = ?1 WHERE id = ?2",
                rusqlite::params![category, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// v8 — mark a bill paid by a transaction: at `unix_ts`, for
    /// `amount_sat`, linked to `paid_ref` (`<wallet_id>|<txid>`).
    /// Returns false when no row matches.
    pub fn mark_bill_paid_by(
        &self,
        id: i64,
        unix_ts: i64,
        amount_sat: u64,
        paid_ref: &str,
    ) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute(
                "UPDATE tracking_bills SET last_paid_unix = ?1, last_paid_amount_sat = ?2, \
                    paid_ref = ?3 WHERE id = ?4",
                rusqlite::params![unix_ts, sat_to_i64("amount_sat", amount_sat)?, paid_ref, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// List all bills, ordered by `due_day` ascending then by created
    /// time (so the user reads them in calendar order).
    #[allow(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        reason = "due_day is validated to 1..=31 on every write"
    )]
    pub fn list_bills(&self) -> Result<Vec<TrackingBill>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name, amount_sat, due_day, last_paid_unix, \
                        display_label, created_at_unix, variable, last_paid_amount_sat, \
                        category, paid_ref \
                 FROM tracking_bills ORDER BY due_day ASC, created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingBill {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    amount_sat: row.get::<_, i64>(2)?.cast_unsigned(),
                    due_day: row.get::<_, i64>(3)? as u32,
                    last_paid_unix: row.get(4)?,
                    display_label: row.get(5)?,
                    created_at_unix: row.get(6)?,
                    variable: row.get(7)?,
                    last_paid_amount_sat: row.get::<_, Option<i64>>(8)?.map(i64::cast_unsigned),
                    category: row.get(9)?,
                    paid_ref: row.get(10)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a bill by id. Returns true if a row was removed.
    pub fn delete_bill(&self, id: i64) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute("DELETE FROM tracking_bills WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // v8 — manual transactions (cash, bank account, card)
    // ----------------------------------------------------------------

    /// Record income (`"in"`) or spending (`"out"`) outside the
    /// wallets. `amount_sat` must be positive; `amount_fiat` and
    /// `currency` go together.
    #[allow(clippy::too_many_arguments)]
    pub fn add_manual_txn(
        &self,
        direction: &str,
        amount_sat: u64,
        amount_fiat: Option<f64>,
        currency: Option<&str>,
        occurred_unix: i64,
        account_ref: Option<&str>,
        note: Option<&str>,
    ) -> Result<i64, OnchainError> {
        self.add_manual_txn_paying(
            direction,
            amount_sat,
            amount_fiat,
            currency,
            occurred_unix,
            account_ref,
            note,
            None,
        )
    }

    /// [`Self::add_manual_txn`], optionally paying a debt (v10): the
    /// money leaves `account_ref` and goes to `debt`. Spending only. The
    /// debt's stored balance drops by `amount_sat`; a mortgage or loan
    /// with a plan follows its plan instead (see [`crate::loan_schedule`]),
    /// and then the entry must be in the plan's currency.
    #[allow(clippy::too_many_arguments)]
    pub fn add_manual_txn_paying(
        &self,
        direction: &str,
        amount_sat: u64,
        amount_fiat: Option<f64>,
        currency: Option<&str>,
        occurred_unix: i64,
        account_ref: Option<&str>,
        note: Option<&str>,
        debt: Option<DebtPayment<'_>>,
    ) -> Result<i64, OnchainError> {
        validate_manual_txn(direction, amount_sat, amount_fiat, currency)?;
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        if let Some(d) = debt {
            validate_debt_payment(&tx, d, direction, currency, account_ref)?;
        }
        tx.execute(
            "INSERT INTO tracking_manual_txns \
                    (direction, amount_sat, amount_fiat, currency, occurred_unix, \
                     account_ref, note, created_at_unix, liability_id, liability_payment) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)",
            rusqlite::params![
                direction,
                sat_to_i64("amount_sat", amount_sat)?,
                amount_fiat,
                currency.map(|c| c.trim().to_uppercase()),
                occurred_unix,
                account_ref,
                note.map(str::trim).filter(|n| !n.is_empty()),
                now_unix(),
                debt.map(|d| d.liability_id),
                debt.map(|d| d.kind),
            ],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let id = tx.last_insert_rowid();
        let entry = AccountEffect {
            account_ref,
            direction,
            amount_sat,
            amount_fiat,
            currency,
            liability_id: debt.map(|d| d.liability_id),
        };
        move_account_balance(&tx, &entry, true)?;
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(id)
    }

    /// Change a manual transaction. The account stays: it is part of the
    /// event's identity (its category is stored under it). Its balance
    /// moves by the difference (the old entry undone, the new applied).
    /// Returns false when no row matches.
    #[allow(clippy::too_many_arguments)]
    pub fn update_manual_txn(
        &self,
        id: i64,
        direction: &str,
        amount_sat: u64,
        amount_fiat: Option<f64>,
        currency: Option<&str>,
        occurred_unix: i64,
        note: Option<&str>,
    ) -> Result<bool, OnchainError> {
        self.update_manual_txn_paying(
            id,
            direction,
            amount_sat,
            amount_fiat,
            currency,
            occurred_unix,
            note,
            None,
        )
    }

    /// [`Self::update_manual_txn`]; `debt_kind` changes how the entry
    /// pays its debt (installment / extra repayment), `None` keeps it.
    /// The debt itself stays, like the account.
    #[allow(clippy::too_many_arguments)]
    pub fn update_manual_txn_paying(
        &self,
        id: i64,
        direction: &str,
        amount_sat: u64,
        amount_fiat: Option<f64>,
        currency: Option<&str>,
        occurred_unix: i64,
        note: Option<&str>,
        debt_kind: Option<&str>,
    ) -> Result<bool, OnchainError> {
        validate_manual_txn(direction, amount_sat, amount_fiat, currency)?;
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let Some(old) = read_manual_txn(&tx, id)? else {
            return Ok(false);
        };
        let kind = debt_kind
            .or(old.liability_payment.as_deref())
            .map(str::to_owned);
        if let Some(liability_id) = old.liability_id {
            let d = DebtPayment {
                liability_id,
                kind: kind.as_deref().unwrap_or(""),
            };
            validate_debt_payment(&tx, d, direction, currency, old.account_ref.as_deref())?;
        }
        move_account_balance(&tx, &old.effect(), false)?;
        let new = AccountEffect {
            account_ref: old.account_ref.as_deref(),
            direction,
            amount_sat,
            amount_fiat,
            currency,
            liability_id: old.liability_id,
        };
        move_account_balance(&tx, &new, true)?;
        let n = tx
            .execute(
                "UPDATE tracking_manual_txns SET direction = ?1, amount_sat = ?2, \
                    amount_fiat = ?3, currency = ?4, occurred_unix = ?5, note = ?6, \
                    liability_payment = ?7 \
                 WHERE id = ?8",
                rusqlite::params![
                    direction,
                    sat_to_i64("amount_sat", amount_sat)?,
                    amount_fiat,
                    currency.map(|c| c.trim().to_uppercase()),
                    occurred_unix,
                    note.map(str::trim).filter(|n| !n.is_empty()),
                    kind.filter(|_| old.liability_id.is_some()),
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Delete a manual transaction with its category and any bill link
    /// to it (the bill goes back to unpaid); its account's balance is
    /// moved back. Returns true if a row was removed.
    pub fn delete_manual_txn(&self, id: i64) -> Result<bool, OnchainError> {
        let event_id = manual_txn_event_id(id);
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        if let Some(old) = read_manual_txn(&tx, id)? {
            move_account_balance(&tx, &old.effect(), false)?;
        }
        tx.execute(
            "DELETE FROM tracking_categories \
             WHERE txid_or_payment_id = ?1 AND wallet_id LIKE ?2",
            rusqlite::params![event_id, format!("{MANUAL_WALLET_ID_PREFIX}%")],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        tx.execute(
            "UPDATE tracking_bills SET last_paid_unix = NULL, last_paid_amount_sat = NULL, \
                paid_ref = NULL \
             WHERE paid_ref LIKE ?2 AND substr(paid_ref, instr(paid_ref, '|') + 1) = ?1",
            rusqlite::params![event_id, format!("{MANUAL_WALLET_ID_PREFIX}%")],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let n = tx
            .execute("DELETE FROM tracking_manual_txns WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Every manual transaction, newest first.
    pub fn list_manual_txns(&self) -> Result<Vec<ManualTxn>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, direction, amount_sat, amount_fiat, currency, occurred_unix, \
                        account_ref, note, created_at_unix, liability_id, liability_payment \
                 FROM tracking_manual_txns ORDER BY occurred_unix DESC, id DESC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(ManualTxn {
                    id: row.get(0)?,
                    direction: row.get(1)?,
                    amount_sat: row.get::<_, i64>(2)?.cast_unsigned(),
                    amount_fiat: row.get(3)?,
                    currency: row.get(4)?,
                    occurred_unix: row.get(5)?,
                    account_ref: row.get(6)?,
                    note: row.get(7)?,
                    created_at_unix: row.get(8)?,
                    liability_id: row.get(9)?,
                    liability_payment: row.get(10)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    // ----------------------------------------------------------------
    // Phase 4.5 — Savings goals
    // ----------------------------------------------------------------

    /// Create a new savings goal, return its auto-generated id.
    ///
    /// `bucket_kind` is whitelisted via [`validate_bucket_kind`].
    /// `bucket_ref` is opaque from the registry's POV — for the
    /// `wallet` bucket it carries a wallet id (string form of a UUID);
    /// for `manual` / `investments` / `bitcoin_cash` it is normally
    /// `None`.
    #[allow(clippy::too_many_arguments)]
    pub fn add_savings_goal(
        &self,
        name: &str,
        target_sat: u64,
        current_sat: u64,
        color: &str,
        deadline_unix: Option<i64>,
        linked_wallet_id: Option<&str>,
        target_pct_of_net_worth: Option<u32>,
        bucket_kind: &str,
        bucket_ref: Option<&str>,
        goal_kind: &str,
    ) -> Result<i64, OnchainError> {
        validate_bucket_kind(bucket_kind)?;
        validate_goal_kind(goal_kind)?;
        validate_sinking_has_deadline(goal_kind, deadline_unix)?;
        self.conn
            .execute(
                "INSERT INTO tracking_savings_goals \
                    (name, target_sat, current_sat, color, deadline_unix, \
                     linked_wallet_id, target_pct_of_net_worth, \
                     bucket_kind, bucket_ref, goal_kind, created_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                rusqlite::params![
                    name,
                    sat_to_i64("target_sat", target_sat)?,
                    sat_to_i64("current_sat", current_sat)?,
                    color,
                    deadline_unix,
                    linked_wallet_id,
                    target_pct_of_net_worth.map(i64::from),
                    bucket_kind,
                    bucket_ref,
                    goal_kind,
                    now_unix(),
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Update goal fields by id. Returns false when no row matches.
    #[allow(clippy::too_many_arguments)]
    pub fn update_savings_goal(
        &self,
        id: i64,
        name: &str,
        target_sat: u64,
        current_sat: u64,
        color: &str,
        deadline_unix: Option<i64>,
        linked_wallet_id: Option<&str>,
        target_pct_of_net_worth: Option<u32>,
        bucket_kind: &str,
        bucket_ref: Option<&str>,
        goal_kind: &str,
    ) -> Result<bool, OnchainError> {
        validate_bucket_kind(bucket_kind)?;
        validate_goal_kind(goal_kind)?;
        validate_sinking_has_deadline(goal_kind, deadline_unix)?;
        let n = self
            .conn
            .execute(
                "UPDATE tracking_savings_goals SET name = ?1, target_sat = ?2, \
                    current_sat = ?3, color = ?4, deadline_unix = ?5, \
                    linked_wallet_id = ?6, target_pct_of_net_worth = ?7, \
                    bucket_kind = ?8, bucket_ref = ?9, goal_kind = ?10 \
                 WHERE id = ?11",
                rusqlite::params![
                    name,
                    sat_to_i64("target_sat", target_sat)?,
                    sat_to_i64("current_sat", current_sat)?,
                    color,
                    deadline_unix,
                    linked_wallet_id,
                    target_pct_of_net_worth.map(i64::from),
                    bucket_kind,
                    bucket_ref,
                    goal_kind,
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// List all savings goals, ordered by creation time.
    #[allow(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        reason = "target_pct_of_net_worth is written from a u32, so it round-trips"
    )]
    pub fn list_savings_goals(&self) -> Result<Vec<TrackingSavingsGoal>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name, target_sat, current_sat, color, deadline_unix, \
                        linked_wallet_id, target_pct_of_net_worth, \
                        bucket_kind, bucket_ref, goal_kind, \
                        target_denomination, target_fiat_amount, target_fiat_currency, \
                        created_at_unix \
                 FROM tracking_savings_goals ORDER BY created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingSavingsGoal {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    target_sat: row.get::<_, i64>(2)?.cast_unsigned(),
                    current_sat: row.get::<_, i64>(3)?.cast_unsigned(),
                    color: row.get(4)?,
                    deadline_unix: row.get(5)?,
                    linked_wallet_id: row.get(6)?,
                    target_pct_of_net_worth: row.get::<_, Option<i64>>(7)?.map(|v| v as u32),
                    bucket_kind: row.get(8)?,
                    bucket_ref: row.get(9)?,
                    goal_kind: row.get(10)?,
                    target_denomination: row.get(11)?,
                    target_fiat_amount: row.get(12)?,
                    target_fiat_currency: row.get(13)?,
                    created_at_unix: row.get(14)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a goal by id. Returns true if a row was removed.
    pub fn delete_savings_goal(&self, id: i64) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute("DELETE FROM tracking_savings_goals WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Phase 8.6 — update the denomination of a savings goal.
    ///
    /// `denomination` must be one of [`VALID_GOAL_DENOMINATIONS`].
    ///
    /// - When `denomination == "sat"`: clears `target_fiat_amount` and
    ///   `target_fiat_currency` to NULL (fiat fields are irrelevant for
    ///   a sat-denominated goal and stale values could confuse future
    ///   readers).
    /// - When `denomination == "fiat"`: both `fiat_amount` and
    ///   `fiat_currency` must be `Some`; they are persisted alongside
    ///   the denomination.
    ///
    /// Returns `false` when no row with `id` exists.
    pub fn set_savings_goal_denomination(
        &self,
        id: i64,
        denomination: &str,
        fiat_amount: Option<f64>,
        fiat_currency: Option<&str>,
    ) -> Result<bool, OnchainError> {
        validate_goal_denomination(denomination)?;
        let (eff_amount, eff_currency) = if denomination == "fiat" {
            let amount = fiat_amount.ok_or_else(|| {
                OnchainError::InvalidArgument("fiat denomination requires fiat_amount".into())
            })?;
            validate_finite("fiat_amount", amount)?;
            if amount <= 0.0 {
                return Err(OnchainError::InvalidArgument(format!(
                    "fiat_amount must be > 0, got {amount}"
                )));
            }
            let currency = fiat_currency.ok_or_else(|| {
                OnchainError::InvalidArgument("fiat denomination requires fiat_currency".into())
            })?;
            (Some(amount), Some(currency))
        } else {
            // sat: clear any lingering fiat fields.
            (None, None)
        };
        let n = self
            .conn
            .execute(
                "UPDATE tracking_savings_goals \
                 SET target_denomination = ?1, \
                     target_fiat_amount = ?2, \
                     target_fiat_currency = ?3 \
                 WHERE id = ?4",
                rusqlite::params![denomination, eff_amount, eff_currency, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 4.6 — Liabilities
    // ----------------------------------------------------------------

    /// Create a new liability row, return its auto-generated id.
    pub fn add_liability(
        &self,
        name: &str,
        amount_sat: u64,
        kind: &str,
        display_label: Option<&str>,
    ) -> Result<i64, OnchainError> {
        validate_liability_kind(kind)?;
        self.conn
            .execute(
                "INSERT INTO tracking_liabilities \
                    (name, amount_sat, kind, display_label, created_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![
                    name,
                    sat_to_i64("amount_sat", amount_sat)?,
                    kind,
                    display_label,
                    now_unix(),
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Update liability fields by id. Returns false when no row matches.
    pub fn update_liability(
        &self,
        id: i64,
        name: &str,
        amount_sat: u64,
        kind: &str,
        display_label: Option<&str>,
    ) -> Result<bool, OnchainError> {
        validate_liability_kind(kind)?;
        let n = self
            .conn
            .execute(
                "UPDATE tracking_liabilities SET name = ?1, amount_sat = ?2, \
                    kind = ?3, display_label = ?4 WHERE id = ?5",
                rusqlite::params![
                    name,
                    sat_to_i64("amount_sat", amount_sat)?,
                    kind,
                    display_label,
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Store the kind-specific details of one portfolio row (v6).
    ///
    /// `table` is `investment`, `manual_asset` or `liability`; `json` is
    /// a flat JSON object whose values are strings, numbers or booleans
    /// (see [`validate_details_json`]). The canonical form is stored, so
    /// `{}` clears it. Returns `false` when no row has that id.
    ///
    /// # Errors
    ///
    /// [`OnchainError::InvalidArgument`] for an unknown table or a
    /// malformed / oversized object; [`OnchainError::Storage`] on DB errors.
    pub fn set_details(&self, table: &str, id: i64, json: &str) -> Result<bool, OnchainError> {
        let table = details_table(table)?;
        let canonical = validate_details_json(json)?;
        let n = self
            .conn
            .execute(
                &format!("UPDATE {table} SET details_json = ?1 WHERE id = ?2"),
                rusqlite::params![canonical, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// `(id, details_json)` of every row of `table` that has details
    /// (rows still at `{}` are left out).
    ///
    /// # Errors
    ///
    /// [`OnchainError::InvalidArgument`] for an unknown table;
    /// [`OnchainError::Storage`] on DB errors.
    pub fn list_details(&self, table: &str) -> Result<Vec<(i64, String)>, OnchainError> {
        let table = details_table(table)?;
        let mut stmt = self
            .conn
            .prepare(&format!(
                "SELECT id, details_json FROM {table} WHERE details_json <> '{{}}' ORDER BY id"
            ))
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        rows.collect::<Result<Vec<_>, _>>()
            .map_err(|e| OnchainError::Storage(e.to_string()))
    }

    /// List all liabilities, ordered by creation time.
    pub fn list_liabilities(&self) -> Result<Vec<TrackingLiability>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name, amount_sat, kind, display_label, created_at_unix \
                 FROM tracking_liabilities ORDER BY created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingLiability {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    amount_sat: row.get::<_, i64>(2)?.cast_unsigned(),
                    kind: row.get(3)?,
                    display_label: row.get(4)?,
                    created_at_unix: row.get(5)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a liability by id. Returns true if a row was removed.
    pub fn delete_liability(&self, id: i64) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute("DELETE FROM tracking_liabilities WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 7.4 — Manual Asset Accounts
    // ----------------------------------------------------------------

    /// Create a new manual asset row. `kind` is whitelisted via
    /// [`validate_manual_asset_kind`]; `value_fiat` is checked finite.
    /// `last_updated_unix` defaults to "now" — the caller can override
    /// via [`update_manual_asset`] later; the UI typically sets it on
    /// every save so the "Updated X ago" hint stays meaningful.
    ///
    /// Phase 8.2 — three new OPTIONAL purchase fields:
    /// - `purchase_unix`: Unix timestamp of the original acquisition.
    /// - `purchase_value_fiat`: fiat value at acquisition.
    /// - `purchase_currency`: ISO 4217 code of the acquisition currency.
    ///
    /// When all three are `Some`, the BTC-at-time rate column will be
    /// resolvable via `resolve_manual_asset_purchase_btc`. When any is
    /// `None`, the purchase BTC column stays NULL until the Phase 8.2
    /// backfill runs (which will attempt the legacy fallback to
    /// `created_at_unix` + `value_fiat` + `currency`).
    #[allow(clippy::too_many_arguments)]
    pub fn add_manual_asset(
        &self,
        name: &str,
        kind: &str,
        value_fiat: f64,
        currency: &str,
        display_label: Option<&str>,
        notes: Option<&str>,
        purchase_unix: Option<i64>,
        purchase_value_fiat: Option<f64>,
        purchase_currency: Option<&str>,
    ) -> Result<i64, OnchainError> {
        validate_manual_asset_kind(kind)?;
        validate_finite("value_fiat", value_fiat)?;
        if let Some(v) = purchase_value_fiat {
            validate_finite("purchase_value_fiat", v)?;
        }
        let now = now_unix();
        self.conn
            .execute(
                "INSERT INTO tracking_manual_assets \
                    (name, kind, value_fiat, currency, display_label, notes, \
                     last_updated_unix, created_at_unix, \
                     purchase_unix, purchase_value_fiat, purchase_currency) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                rusqlite::params![
                    name,
                    kind,
                    value_fiat,
                    currency,
                    display_label,
                    notes,
                    now,
                    now,
                    purchase_unix,
                    purchase_value_fiat,
                    purchase_currency,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Update manual asset fields by id. Bumps `last_updated_unix` to
    /// "now" so the UI can show the recency.
    ///
    /// Phase 8.2 — three new OPTIONAL purchase fields (same as `add_manual_asset`).
    /// Passing `None` for any purchase field clears the corresponding column to
    /// NULL (useful when the user removes purchase date info). To leave an
    /// existing column unchanged the caller must re-supply its current value.
    #[allow(clippy::too_many_arguments)]
    pub fn update_manual_asset(
        &self,
        id: i64,
        name: &str,
        kind: &str,
        value_fiat: f64,
        currency: &str,
        display_label: Option<&str>,
        notes: Option<&str>,
        purchase_unix: Option<i64>,
        purchase_value_fiat: Option<f64>,
        purchase_currency: Option<&str>,
    ) -> Result<bool, OnchainError> {
        validate_manual_asset_kind(kind)?;
        validate_finite("value_fiat", value_fiat)?;
        if let Some(v) = purchase_value_fiat {
            validate_finite("purchase_value_fiat", v)?;
        }
        let n = self
            .conn
            .execute(
                "UPDATE tracking_manual_assets SET name = ?1, kind = ?2, \
                    value_fiat = ?3, currency = ?4, display_label = ?5, \
                    notes = ?6, last_updated_unix = ?7, \
                    purchase_unix = ?8, purchase_value_fiat = ?9, \
                    purchase_currency = ?10 \
                 WHERE id = ?11",
                rusqlite::params![
                    name,
                    kind,
                    value_fiat,
                    currency,
                    display_label,
                    notes,
                    now_unix(),
                    purchase_unix,
                    purchase_value_fiat,
                    purchase_currency,
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// List all manual asset rows, ordered by creation time.
    pub fn list_manual_assets(&self) -> Result<Vec<TrackingManualAsset>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name, kind, value_fiat, currency, display_label, \
                        notes, last_updated_unix, created_at_unix, \
                        purchase_unix, purchase_value_fiat, purchase_currency, \
                        purchase_btc_at_time \
                 FROM tracking_manual_assets ORDER BY created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingManualAsset {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    kind: row.get(2)?,
                    value_fiat: row.get(3)?,
                    currency: row.get(4)?,
                    display_label: row.get(5)?,
                    notes: row.get(6)?,
                    last_updated_unix: row.get(7)?,
                    created_at_unix: row.get(8)?,
                    purchase_unix: row.get(9)?,
                    purchase_value_fiat: row.get(10)?,
                    purchase_currency: row.get(11)?,
                    purchase_btc_at_time: row.get(12)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a manual asset by id. Returns true if a row was removed.
    pub fn delete_manual_asset(&self, id: i64) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute("DELETE FROM tracking_manual_assets WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 7.5 — Categorization rules
    // ----------------------------------------------------------------

    /// Insert a new rule row. Validation enforces the field/op/value
    /// compatibility matrix (see [`validate_rule`]).
    #[allow(clippy::too_many_arguments)]
    pub fn add_rule(
        &self,
        name: &str,
        condition_field: &str,
        condition_op: &str,
        condition_value: &str,
        action_field: &str,
        action_value: &str,
        priority: i64,
        enabled: bool,
    ) -> Result<i64, OnchainError> {
        validate_rule(condition_field, condition_op, condition_value, action_field)?;
        validate_non_blank("rule action value", action_value)?;
        self.conn
            .execute(
                "INSERT INTO tracking_rules \
                    (name, condition_field, condition_op, condition_value, \
                     action_field, action_value, priority, enabled, created_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                rusqlite::params![
                    name,
                    condition_field,
                    condition_op,
                    condition_value,
                    action_field,
                    action_value,
                    priority,
                    i64::from(enabled),
                    now_unix(),
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Update rule fields by id. Returns false when no row matches.
    #[allow(clippy::too_many_arguments)]
    pub fn update_rule(
        &self,
        id: i64,
        name: &str,
        condition_field: &str,
        condition_op: &str,
        condition_value: &str,
        action_field: &str,
        action_value: &str,
        priority: i64,
        enabled: bool,
    ) -> Result<bool, OnchainError> {
        validate_rule(condition_field, condition_op, condition_value, action_field)?;
        validate_non_blank("rule action value", action_value)?;
        let n = self
            .conn
            .execute(
                "UPDATE tracking_rules SET name = ?1, condition_field = ?2, \
                    condition_op = ?3, condition_value = ?4, action_field = ?5, \
                    action_value = ?6, priority = ?7, enabled = ?8 \
                 WHERE id = ?9",
                rusqlite::params![
                    name,
                    condition_field,
                    condition_op,
                    condition_value,
                    action_field,
                    action_value,
                    priority,
                    i64::from(enabled),
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Set the evaluation order of ALL rules in one transaction (UX
    /// review 2026-10, item 11). `ordered_ids` lists every rule id, the
    /// first evaluated first; priorities become `n−1 … 0` in that order.
    ///
    /// The list must be a permutation of the current rule ids: a caller
    /// working from a stale snapshot (a rule added or deleted meanwhile)
    /// is refused instead of half-applying an order. Either every
    /// priority is rewritten or none is.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidArgument`] — duplicate, unknown or missing ids.
    /// - [`OnchainError::Storage`] — DB failure (nothing is written).
    pub fn reorder_rules(&self, ordered_ids: &[i64]) -> Result<(), OnchainError> {
        let storage = |e: rusqlite::Error| OnchainError::Storage(e.to_string());
        let tx = self.conn.unchecked_transaction().map_err(storage)?;
        let mut current: Vec<i64> = {
            let mut stmt = tx
                .prepare("SELECT id FROM tracking_rules")
                .map_err(storage)?;
            let rows = stmt.query_map([], |row| row.get(0)).map_err(storage)?;
            rows.collect::<Result<_, _>>().map_err(storage)?
        };
        let mut wanted = ordered_ids.to_vec();
        current.sort_unstable();
        wanted.sort_unstable();
        if current != wanted {
            return Err(OnchainError::InvalidArgument(
                "rule order must list every rule exactly once".into(),
            ));
        }
        let n = i64::try_from(ordered_ids.len())
            .map_err(|_| OnchainError::InvalidArgument("too many rules".into()))?;
        for (idx, id) in (0_i64..).zip(ordered_ids) {
            tx.execute(
                "UPDATE tracking_rules SET priority = ?1 WHERE id = ?2",
                rusqlite::params![n - 1 - idx, id],
            )
            .map_err(storage)?;
        }
        tx.commit().map_err(storage)
    }

    /// List all rules, ordered by priority DESC then `created_at` ASC
    /// — UI consumes this order directly to apply matching, and shows
    /// it as-is in the rules card.
    pub fn list_rules(&self) -> Result<Vec<TrackingRule>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name, condition_field, condition_op, condition_value, \
                        action_field, action_value, priority, enabled, created_at_unix \
                 FROM tracking_rules \
                 ORDER BY priority DESC, created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingRule {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    condition_field: row.get(2)?,
                    condition_op: row.get(3)?,
                    condition_value: row.get(4)?,
                    action_field: row.get(5)?,
                    action_value: row.get(6)?,
                    priority: row.get(7)?,
                    enabled: row.get::<_, i64>(8)? != 0,
                    created_at_unix: row.get(9)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a rule by id. Returns true when a row was removed.
    pub fn delete_rule(&self, id: i64) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute("DELETE FROM tracking_rules WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 7.5 — Dismissed recurring suggestions
    // ----------------------------------------------------------------

    /// Mark a recurring-suggestion key as dismissed so the detector
    /// can filter it out on subsequent runs. Idempotent — re-dismissing
    /// is a no-op (the existing row stays, no error).
    pub fn dismiss_recurring(&self, payee_key: &str) -> Result<(), OnchainError> {
        self.conn
            .execute(
                "INSERT OR IGNORE INTO tracking_recurring_dismissed \
                    (payee_key, dismissed_unix) VALUES (?1, ?2)",
                rusqlite::params![payee_key, now_unix()],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(())
    }

    /// Read every dismissed payee key. Returns a Vec because the
    /// caller (Kotlin detector) wants to convert to a `HashSet` anyway.
    pub fn list_dismissed_recurring(&self) -> Result<Vec<String>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare("SELECT payee_key FROM tracking_recurring_dismissed")
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| row.get::<_, String>(0))
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Forget a dismissal. Returns true when a row was removed.
    /// Used by the "Show dismissed again" admin action.
    pub fn undismiss_recurring(&self, payee_key: &str) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute(
                "DELETE FROM tracking_recurring_dismissed WHERE payee_key = ?1",
                [payee_key],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 5.1 — Investments
    // ----------------------------------------------------------------

    /// Create a new investment row, return its auto-generated id.
    #[allow(clippy::too_many_arguments)]
    pub fn add_investment(
        &self,
        name: &str,
        asset_type: &str,
        ticker: Option<&str>,
        quantity: f64,
        unit: &str,
        cost_basis_fiat: f64,
        cost_basis_currency: &str,
        purchase_unix: i64,
        manual_current_value: Option<f64>,
        notes: Option<&str>,
    ) -> Result<i64, OnchainError> {
        validate_asset_type(asset_type)?;
        validate_non_negative("quantity", quantity)?;
        validate_non_negative("cost_basis_fiat", cost_basis_fiat)?;
        if let Some(v) = manual_current_value {
            validate_finite("manual_current_value", v)?;
        }
        self.conn
            .execute(
                "INSERT INTO tracking_investments \
                    (name, asset_type, ticker, quantity, unit, \
                     cost_basis_fiat, cost_basis_currency, purchase_unix, \
                     manual_current_value, last_priced_at_unix, notes, created_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, NULL, ?10, ?11)",
                rusqlite::params![
                    name,
                    asset_type,
                    ticker,
                    quantity,
                    unit,
                    cost_basis_fiat,
                    cost_basis_currency,
                    purchase_unix,
                    manual_current_value,
                    notes,
                    now_unix(),
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Update investment fields by id. Returns false when no row matches.
    #[allow(clippy::too_many_arguments)]
    pub fn update_investment(
        &self,
        id: i64,
        name: &str,
        asset_type: &str,
        ticker: Option<&str>,
        quantity: f64,
        unit: &str,
        cost_basis_fiat: f64,
        cost_basis_currency: &str,
        purchase_unix: i64,
        manual_current_value: Option<f64>,
        notes: Option<&str>,
    ) -> Result<bool, OnchainError> {
        validate_asset_type(asset_type)?;
        validate_non_negative("quantity", quantity)?;
        validate_non_negative("cost_basis_fiat", cost_basis_fiat)?;
        if let Some(v) = manual_current_value {
            validate_finite("manual_current_value", v)?;
        }
        let n = self
            .conn
            .execute(
                "UPDATE tracking_investments SET name = ?1, asset_type = ?2, \
                    ticker = ?3, quantity = ?4, unit = ?5, \
                    cost_basis_fiat = ?6, cost_basis_currency = ?7, \
                    purchase_unix = ?8, manual_current_value = ?9, \
                    notes = ?10 WHERE id = ?11",
                rusqlite::params![
                    name,
                    asset_type,
                    ticker,
                    quantity,
                    unit,
                    cost_basis_fiat,
                    cost_basis_currency,
                    purchase_unix,
                    manual_current_value,
                    notes,
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Bulk-set the cached fetched price for an investment. Phase 5.2
    /// price fetcher calls this after a successful provider round-trip;
    /// the UI prefers `manual_current_value` as a fallback when this
    /// column is null or stale.
    pub fn set_investment_fetched_price(
        &self,
        id: i64,
        fetched_value: f64,
        priced_at_unix: i64,
    ) -> Result<bool, OnchainError> {
        // Reject NaN / Infinity before binding to SQLite. The provider
        // (Yahoo, CoinGecko) can return malformed JSON that
        // kotlinx.serialization deserialises into IEEE-754 NaN; without
        // this guard the value lands in the DB and propagates silently
        // into the P&L computations.
        validate_finite("fetched_value", fetched_value)?;
        // T2-10 — write to fetched_current_value, NOT
        // manual_current_value. The manual value is the user's
        // ground truth and must never be silently overwritten by
        // an automated provider. The UI prefers fetched when it's
        // non-null AND fresh; otherwise falls back to manual.
        let n = self
            .conn
            .execute(
                "UPDATE tracking_investments SET fetched_current_value = ?1, \
                    last_priced_at_unix = ?2 WHERE id = ?3",
                rusqlite::params![fetched_value, priced_at_unix, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// List all investments, ordered by creation time.
    pub fn list_investments(&self) -> Result<Vec<TrackingInvestment>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, name, asset_type, ticker, quantity, unit, \
                        cost_basis_fiat, cost_basis_currency, purchase_unix, \
                        manual_current_value, fetched_current_value, \
                        last_priced_at_unix, notes, cost_basis_method, created_at_unix \
                 FROM tracking_investments ORDER BY created_at_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingInvestment {
                    id: row.get(0)?,
                    name: row.get(1)?,
                    asset_type: row.get(2)?,
                    ticker: row.get(3)?,
                    quantity: row.get(4)?,
                    unit: row.get(5)?,
                    cost_basis_fiat: row.get(6)?,
                    cost_basis_currency: row.get(7)?,
                    purchase_unix: row.get(8)?,
                    manual_current_value: row.get(9)?,
                    fetched_current_value: row.get(10)?,
                    last_priced_at_unix: row.get(11)?,
                    notes: row.get(12)?,
                    cost_basis_method: row.get(13)?,
                    created_at_unix: row.get(14)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete an investment by id.
    pub fn delete_investment(&self, id: i64) -> Result<bool, OnchainError> {
        let n = self
            .conn
            .execute("DELETE FROM tracking_investments WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 7.8 — Investment lots
    // ----------------------------------------------------------------

    /// Insert a new lot under the given investment.
    #[allow(clippy::too_many_arguments)]
    pub fn add_investment_lot(
        &self,
        investment_id: i64,
        quantity: f64,
        price_per_unit: f64,
        fees_fiat: f64,
        purchase_unix: i64,
        notes: Option<&str>,
    ) -> Result<i64, OnchainError> {
        validate_non_negative("quantity", quantity)?;
        if quantity <= 0.0 {
            return Err(OnchainError::InvalidArgument(format!(
                "quantity must be > 0, got {quantity}"
            )));
        }
        validate_non_negative("price_per_unit", price_per_unit)?;
        validate_non_negative("fees_fiat", fees_fiat)?;
        // `original_quantity` freezes the purchased size: `quantity` is
        // decremented on every sell, but the per-unit fee share
        // (fees_fiat / original_quantity) must not change with it (M1).
        self.conn
            .execute(
                "INSERT INTO tracking_investment_lots \
                    (investment_id, quantity, original_quantity, price_per_unit, fees_fiat, \
                     purchase_unix, notes, created_at_unix) \
                 VALUES (?1, ?2, ?2, ?3, ?4, ?5, ?6, ?7)",
                rusqlite::params![
                    investment_id,
                    quantity,
                    price_per_unit,
                    fees_fiat,
                    purchase_unix,
                    notes,
                    now_unix(),
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(self.conn.last_insert_rowid())
    }

    /// Update a lot's fields by id. Returns false when no row matches.
    #[allow(clippy::too_many_arguments)]
    pub fn update_investment_lot(
        &self,
        id: i64,
        quantity: f64,
        price_per_unit: f64,
        fees_fiat: f64,
        purchase_unix: i64,
        notes: Option<&str>,
    ) -> Result<bool, OnchainError> {
        validate_non_negative("quantity", quantity)?;
        validate_non_negative("price_per_unit", price_per_unit)?;
        validate_non_negative("fees_fiat", fees_fiat)?;
        // `quantity` is the remaining (unsold) size; the purchased size
        // is remaining + everything already disposed from this lot.
        let n = self
            .conn
            .execute(
                "UPDATE tracking_investment_lots SET quantity = ?1, \
                    original_quantity = ?1 + COALESCE(\
                        (SELECT SUM(qty_consumed) FROM tracking_lot_disposals \
                         WHERE lot_id = ?6), 0), \
                    price_per_unit = ?2, fees_fiat = ?3, purchase_unix = ?4, \
                    notes = ?5 WHERE id = ?6",
                rusqlite::params![
                    quantity,
                    price_per_unit,
                    fees_fiat,
                    purchase_unix,
                    notes,
                    id,
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// List every lot under the given investment, ordered by
    /// purchase date ASC (the FIFO order). UI may resort by method
    /// at render time.
    pub fn list_investment_lots(
        &self,
        investment_id: i64,
    ) -> Result<Vec<TrackingInvestmentLot>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, investment_id, quantity, price_per_unit, fees_fiat, \
                        purchase_unix, notes, created_at_unix, btc_per_unit_at_purchase, \
                        COALESCE(original_quantity, quantity) \
                 FROM tracking_investment_lots \
                 WHERE investment_id = ?1 \
                 ORDER BY purchase_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([investment_id], |row| {
                Ok(TrackingInvestmentLot {
                    id: row.get(0)?,
                    investment_id: row.get(1)?,
                    quantity: row.get(2)?,
                    price_per_unit: row.get(3)?,
                    fees_fiat: row.get(4)?,
                    purchase_unix: row.get(5)?,
                    notes: row.get(6)?,
                    created_at_unix: row.get(7)?,
                    btc_per_unit_at_purchase: row.get(8)?,
                    original_quantity: row.get(9)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// List every lot across every investment. Used by the
    /// `TrackerCache` to populate `investmentLotsByInvestmentId` in a
    /// single round-trip.
    pub fn list_all_investment_lots(&self) -> Result<Vec<TrackingInvestmentLot>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, investment_id, quantity, price_per_unit, fees_fiat, \
                        purchase_unix, notes, created_at_unix, btc_per_unit_at_purchase, \
                        COALESCE(original_quantity, quantity) \
                 FROM tracking_investment_lots \
                 ORDER BY investment_id ASC, purchase_unix ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingInvestmentLot {
                    id: row.get(0)?,
                    investment_id: row.get(1)?,
                    quantity: row.get(2)?,
                    price_per_unit: row.get(3)?,
                    fees_fiat: row.get(4)?,
                    purchase_unix: row.get(5)?,
                    notes: row.get(6)?,
                    created_at_unix: row.get(7)?,
                    btc_per_unit_at_purchase: row.get(8)?,
                    original_quantity: row.get(9)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a lot by id. Returns true if a row was removed. The
    /// parent investment's aggregate cost basis is recomputed at render time.
    ///
    /// Refuses (`InvalidArgument`) when the lot has disposals: the
    /// `tracking_lot_disposals.lot_id` FK is `ON DELETE CASCADE` (`SQLite`
    /// cannot alter an existing FK in place), so deleting such a lot
    /// would silently wipe realized P&L history. Delete the sells that
    /// consumed it first (which restores their quantity) — M2.
    pub fn delete_investment_lot(&self, id: i64) -> Result<bool, OnchainError> {
        let disposals: i64 = self
            .conn
            .query_row(
                "SELECT COUNT(*) FROM tracking_lot_disposals WHERE lot_id = ?1",
                [id],
                |r| r.get(0),
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        if disposals > 0 {
            return Err(OnchainError::InvalidArgument(format!(
                "lot {id} has {disposals} sale disposal(s); delete those sells first \
                 so realized P&L history is not lost"
            )));
        }
        let n = self
            .conn
            .execute("DELETE FROM tracking_investment_lots WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Phase 7.8 — set the cost basis method for an investment. The
    /// method is persisted even when there are no lots yet — the
    /// future sell flow (Phase 7.9) consumes lots by this ordering.
    pub fn set_investment_cost_basis_method(
        &self,
        id: i64,
        method: &str,
    ) -> Result<bool, OnchainError> {
        validate_cost_basis_method(method)?;
        let n = self
            .conn
            .execute(
                "UPDATE tracking_investments SET cost_basis_method = ?1 WHERE id = ?2",
                rusqlite::params![method, id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    /// Phase 7.8 — read the per-investment cost basis method. Used by
    /// the Kotlin side to wire the picker into the `InvestmentEditDialog`
    /// without round-tripping through the full investment object.
    pub fn get_investment_cost_basis_method(
        &self,
        id: i64,
    ) -> Result<Option<String>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare("SELECT cost_basis_method FROM tracking_investments WHERE id = ?1")
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut rows = stmt
            .query([id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        match rows
            .next()
            .map_err(|e| OnchainError::Storage(e.to_string()))?
        {
            None => Ok(None),
            Some(row) => Ok(Some(
                row.get::<_, String>(0)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?,
            )),
        }
    }

    /// Phase 7.8 — Phase 5.1 → multi-lot seed migration. When the UI
    /// adds the first lot to a legacy investment (`cost_basis_fiat > 0`
    /// AND no existing lots), it can call this helper to atomically:
    ///  1. INSERT a lot from the aggregate fields
    ///  2. UPDATE the investment to zero out `cost_basis_fiat` and
    ///     `quantity` (signals "multi-lot model active")
    ///
    /// Returns the seed lot id. The atomic two-step avoids the window
    /// where the UI shows "double-counted" totals between the lot
    /// insert and the aggregate zero-out.
    pub fn seed_investment_lot_from_aggregate(
        &self,
        investment_id: i64,
    ) -> Result<i64, OnchainError> {
        // One transaction for check + insert + zero-out: a crash between
        // the steps can no longer double-count, and a concurrent/second
        // call can no longer seed twice (L3).
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let existing_lots: i64 = tx
            .query_row(
                "SELECT COUNT(*) FROM tracking_investment_lots WHERE investment_id = ?1",
                [investment_id],
                |r| r.get(0),
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        if existing_lots > 0 {
            return Err(OnchainError::InvalidArgument(format!(
                "investment {investment_id} already has lots; seeding from the aggregate \
                 would double-count"
            )));
        }
        // Read the aggregate fields.
        let (qty, cost_basis_fiat, purchase_unix): (f64, f64, i64) = {
            let mut stmt = tx
                .prepare(
                    "SELECT quantity, cost_basis_fiat, purchase_unix \
                     FROM tracking_investments WHERE id = ?1",
                )
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            let mut rows = stmt
                .query([investment_id])
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
            let row = rows
                .next()
                .map_err(|e| OnchainError::Storage(e.to_string()))?
                .ok_or_else(|| {
                    OnchainError::InvalidArgument(format!(
                        "investment {investment_id} not found for seed"
                    ))
                })?;
            (
                row.get::<_, f64>(0)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?,
                row.get::<_, f64>(1)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?,
                row.get::<_, i64>(2)
                    .map_err(|e| OnchainError::Storage(e.to_string()))?,
            )
        };
        if qty <= 0.0 || cost_basis_fiat <= 0.0 {
            return Err(OnchainError::InvalidArgument(
                "cannot seed lot from non-positive aggregate".into(),
            ));
        }
        if !qty.is_finite() || !cost_basis_fiat.is_finite() {
            return Err(OnchainError::InvalidArgument(
                "cannot seed lot from non-finite aggregate".into(),
            ));
        }
        let price_per_unit = cost_basis_fiat / qty;
        tx.execute(
            "INSERT INTO tracking_investment_lots \
                (investment_id, quantity, original_quantity, price_per_unit, fees_fiat, \
                 purchase_unix, notes, created_at_unix) \
             VALUES (?1, ?2, ?2, ?3, 0, ?4, ?5, ?6)",
            rusqlite::params![
                investment_id,
                qty,
                price_per_unit, // fees already included in the cost_basis_fiat
                purchase_unix,
                "Seed lot from Phase 5.1 single-row position",
                now_unix(),
            ],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let lot_id = tx.last_insert_rowid();
        // Zero out the aggregate fields so the UI doesn't double-count.
        tx.execute(
            "UPDATE tracking_investments \
             SET quantity = 0, cost_basis_fiat = 0 WHERE id = ?1",
            [investment_id],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(lot_id)
    }

    pub fn set_investment_lot_btc_rate(
        &self,
        lot_id: i64,
        btc_per_unit_at_purchase: f64,
    ) -> Result<bool, OnchainError> {
        if !btc_per_unit_at_purchase.is_finite() || btc_per_unit_at_purchase <= 0.0 {
            return Err(OnchainError::InvalidArgument(
                "btc_per_unit_at_purchase must be finite and > 0".into(),
            ));
        }
        let n = self
            .conn
            .execute(
                "UPDATE tracking_investment_lots SET btc_per_unit_at_purchase = ?1 WHERE id = ?2",
                rusqlite::params![btc_per_unit_at_purchase, lot_id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 7.9 — Sells + lot disposals
    // ----------------------------------------------------------------

    /// Atomically record a sale: sort the parent's lots by the
    /// investment's `cost_basis_method`, consume `sale_qty` units in
    /// order, allocate the sale fee pro-rata over the disposals, and
    /// persist a sell row + one disposal row per lot touched.
    ///
    /// Per-unit cost basis of a lot = `price_per_unit` + the lot's
    /// purchase fees spread over its **original** quantity
    /// (`fees_fiat / original_quantity`) — M1. Only lots purchased at or
    /// before `sale_unix` can be consumed.
    ///
    /// Returns the new sell's id.
    ///
    /// Errors:
    /// - `InvalidArgument` when any numeric input is non-finite or negative
    /// - `InvalidArgument` when `sale_qty > available_qty` (counting only
    ///   lots bought by `sale_unix`) — over-sell; the user must record the
    ///   missing lots first or reduce the sale
    /// - `InvalidArgument` when the investment uses `specific_id`: the lots
    ///   to dispose must then be picked explicitly via
    ///   [`Registry::record_sell_with_lots`] — silently falling back to
    ///   FIFO would book the wrong cost basis
    ///
    /// All reads and writes happen inside a single `SQLite` transaction
    /// so a mid-flight crash never leaves lots partially consumed without
    /// the matching disposal rows.
    pub fn record_sell(
        &self,
        investment_id: i64,
        sale_unix: i64,
        sale_price_per_unit: f64,
        sale_qty: f64,
        fees_fiat: f64,
        notes: Option<&str>,
    ) -> Result<i64, OnchainError> {
        self.record_sell_impl(
            investment_id,
            sale_unix,
            sale_price_per_unit,
            sale_qty,
            fees_fiat,
            notes,
            None,
        )
    }

    /// Explicit-lot sale: like [`Registry::record_sell`], but the lots to
    /// dispose — and how many units from each — are chosen by the
    /// caller instead of derived from FIFO / HIFO ordering. Required for
    /// `specific_id` investments; also accepted for FIFO / HIFO ones,
    /// where it books one sale under the method the user picked in the
    /// sell dialog's side-by-side comparison.
    ///
    /// Validated inside the write transaction (any failure rolls back
    /// everything and nothing is written):
    /// - `selection` is non-empty, has no duplicate lot, and every
    ///   quantity is finite and > 0;
    /// - every lot belongs to `investment_id` and was purchased at or
    ///   before `sale_unix`;
    /// - every quantity is ≤ that lot's remaining `quantity`;
    /// - the quantities sum to `sale_qty`.
    ///
    /// Realized P&L per disposal = `(sale price − lot unit cost incl.
    /// purchase-fee share) × qty − pro-rata sale fee`, exactly as for
    /// FIFO / HIFO. `original_quantity` is never touched; deleting the
    /// sell ([`Registry::delete_investment_sell`]) restores each lot.
    ///
    /// # Errors
    ///
    /// - [`OnchainError::InvalidArgument`] — any check above fails.
    /// - [`OnchainError::Storage`] — DB failure.
    #[allow(
        clippy::too_many_arguments,
        reason = "mirrors record_sell plus the explicit lot selection"
    )]
    pub fn record_sell_with_lots(
        &self,
        investment_id: i64,
        sale_unix: i64,
        sale_price_per_unit: f64,
        sale_qty: f64,
        fees_fiat: f64,
        notes: Option<&str>,
        selection: &[LotSelection],
    ) -> Result<i64, OnchainError> {
        self.record_sell_impl(
            investment_id,
            sale_unix,
            sale_price_per_unit,
            sale_qty,
            fees_fiat,
            notes,
            Some(selection),
        )
    }

    #[allow(
        clippy::too_many_arguments,
        clippy::too_many_lines,
        reason = "linear validate -> plan -> single-transaction write; splitting \
                  it would scatter the atomicity invariant"
    )]
    fn record_sell_impl(
        &self,
        investment_id: i64,
        sale_unix: i64,
        sale_price_per_unit: f64,
        sale_qty: f64,
        fees_fiat: f64,
        notes: Option<&str>,
        selection: Option<&[LotSelection]>,
    ) -> Result<i64, OnchainError> {
        /// One lot consumption computed before the writes.
        struct Plan {
            lot_id: i64,
            consumed: f64,
            cost_basis_per_unit: f64,
            gain_loss: f64,
            new_lot_qty: f64,
        }
        validate_non_negative("sale_price_per_unit", sale_price_per_unit)?;
        validate_finite("sale_qty", sale_qty)?;
        validate_non_negative("fees_fiat", fees_fiat)?;
        if sale_qty <= 0.0 {
            return Err(OnchainError::InvalidArgument(
                "sale_qty must be positive".into(),
            ));
        }

        // Every read below runs on the same connection inside this
        // transaction: the lots validated are the lots written. We use
        // `unchecked_transaction` because the Registry handle holds the
        // connection by shared reference.
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;

        let method = self
            .get_investment_cost_basis_method(investment_id)?
            .ok_or_else(|| {
                OnchainError::InvalidArgument(format!("investment {investment_id} not found"))
            })?;
        // A specific-ID investment always needs the explicit picks. A
        // FIFO / HIFO investment takes them too (UX review 2026-10, item
        // 10): the sell dialog compares the three methods side by side
        // and the user may book this one sale under another method — the
        // dialog then sends the lots that method consumes. Without a
        // selection, the configured method orders the lots as before.
        if method == "specific_id" && selection.is_none() {
            return Err(OnchainError::InvalidArgument(
                "specific-ID cost basis needs an explicit lot selection: pick the lots to sell"
                    .into(),
            ));
        }

        // Per-unit cost: price + purchase fees spread over the ORIGINAL
        // purchased size (M1).
        let unit_cost = |l: &TrackingInvestmentLot| -> f64 {
            if l.original_quantity > 0.0 {
                l.price_per_unit + l.fees_fiat / l.original_quantity
            } else {
                l.price_per_unit
            }
        };
        // Lots with units left, bought by the sale date.
        let mut lots: Vec<TrackingInvestmentLot> = self
            .list_investment_lots(investment_id)?
            .into_iter()
            .filter(|l| l.quantity > 0.0 && l.purchase_unix <= sale_unix)
            .collect();
        let available: f64 = lots.iter().map(|l| l.quantity).sum();
        // Relative tolerance: an absolute f64::EPSILON is meaningless
        // for quantities far from 1.0 (e.g. 1e6 shares or 1e-8 BTC).
        let tol = qty_tolerance(sale_qty.max(available));
        if sale_qty > available + tol {
            return Err(OnchainError::InvalidArgument(format!(
                "over-sell: sale_qty {sale_qty} exceeds the {available} units held \
                 on the sale date"
            )));
        }

        // (lot, qty to take from it), in consumption order.
        let picks: Vec<(&TrackingInvestmentLot, f64)> = if let Some(sel) = selection {
            if sel.is_empty() {
                return Err(OnchainError::InvalidArgument(
                    "the lot selection is empty".into(),
                ));
            }
            let mut seen = std::collections::HashSet::new();
            let mut picks = Vec::with_capacity(sel.len());
            let mut total = 0.0;
            for s in sel {
                if !s.qty.is_finite() || s.qty <= 0.0 {
                    return Err(OnchainError::InvalidArgument(format!(
                        "lot {}: quantity to sell must be finite and > 0",
                        s.lot_id
                    )));
                }
                if !seen.insert(s.lot_id) {
                    return Err(OnchainError::InvalidArgument(format!(
                        "lot {} is selected more than once",
                        s.lot_id
                    )));
                }
                // `lots` only holds this investment's lots with units
                // left that were bought by the sale date: anything else
                // (another investment's lot, an empty or future lot) is
                // refused here.
                let lot = lots.iter().find(|l| l.id == s.lot_id).ok_or_else(|| {
                    OnchainError::InvalidArgument(format!(
                        "lot {} is not an open lot of investment {investment_id} on the \
                         sale date",
                        s.lot_id
                    ))
                })?;
                if s.qty > lot.quantity + qty_tolerance(lot.quantity.max(s.qty)) {
                    return Err(OnchainError::InvalidArgument(format!(
                        "lot {}: cannot sell {} units, only {} remain",
                        s.lot_id, s.qty, lot.quantity
                    )));
                }
                total += s.qty;
                picks.push((lot, s.qty.min(lot.quantity)));
            }
            if (total - sale_qty).abs() > tol {
                return Err(OnchainError::InvalidArgument(format!(
                    "the selected lot quantities sum to {total}, not to the sale \
                     quantity {sale_qty}"
                )));
            }
            picks
        } else {
            // FIFO = oldest purchase first; HIFO = highest per-unit cost
            // (price + fee share) first.
            match method.as_str() {
                "hifo" => lots.sort_by(|a, b| {
                    unit_cost(b)
                        .partial_cmp(&unit_cost(a))
                        .unwrap_or(std::cmp::Ordering::Equal)
                }),
                _ => lots.sort_by_key(|l| l.purchase_unix),
            }
            let mut picks = Vec::with_capacity(lots.len());
            let mut remaining = sale_qty;
            for l in &lots {
                if remaining <= tol {
                    break;
                }
                let consumed = remaining.min(l.quantity);
                picks.push((l, consumed));
                remaining -= consumed;
            }
            picks
        };

        let plan: Vec<Plan> = picks
            .into_iter()
            .map(|(l, consumed)| {
                let fee_alloc = fees_fiat * (consumed / sale_qty);
                let basis = unit_cost(l);
                let mut new_lot_qty = l.quantity - consumed;
                // Snap float dust to an exact zero so a fully-sold lot
                // does not linger as 1e-17 units.
                if new_lot_qty.abs() <= tol {
                    new_lot_qty = 0.0;
                }
                Plan {
                    lot_id: l.id,
                    consumed,
                    cost_basis_per_unit: basis,
                    gain_loss: (sale_price_per_unit - basis) * consumed - fee_alloc,
                    new_lot_qty,
                }
            })
            .collect();

        tx.execute(
            "INSERT INTO tracking_investment_sells \
                (investment_id, sale_unix, sale_price_per_unit, sale_qty, \
                 fees_fiat, notes, created_at_unix) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            rusqlite::params![
                investment_id,
                sale_unix,
                sale_price_per_unit,
                sale_qty,
                fees_fiat,
                notes,
                now_unix(),
            ],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let sell_id = tx.last_insert_rowid();

        for p in &plan {
            tx.execute(
                "INSERT INTO tracking_lot_disposals \
                    (sell_id, lot_id, qty_consumed, cost_basis_per_unit, \
                     gain_loss_fiat, created_at_unix) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                rusqlite::params![
                    sell_id,
                    p.lot_id,
                    p.consumed,
                    p.cost_basis_per_unit,
                    p.gain_loss,
                    now_unix(),
                ],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
            // `original_quantity` is deliberately left alone: it is the
            // frozen purchased size the fee share is computed against.
            tx.execute(
                "UPDATE tracking_investment_lots SET quantity = ?1 WHERE id = ?2",
                rusqlite::params![p.new_lot_qty, p.lot_id],
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        }
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(sell_id)
    }

    /// List sells for a given investment, ordered by `sale_unix` DESC
    /// (most recent first — UI shows them near the top of the sells
    /// sub-section).
    pub fn list_investment_sells(
        &self,
        investment_id: i64,
    ) -> Result<Vec<TrackingInvestmentSell>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, investment_id, sale_unix, sale_price_per_unit, \
                        sale_qty, fees_fiat, notes, created_at_unix, btc_per_unit_at_sale \
                 FROM tracking_investment_sells \
                 WHERE investment_id = ?1 \
                 ORDER BY sale_unix DESC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([investment_id], |row| {
                Ok(TrackingInvestmentSell {
                    id: row.get(0)?,
                    investment_id: row.get(1)?,
                    sale_unix: row.get(2)?,
                    sale_price_per_unit: row.get(3)?,
                    sale_qty: row.get(4)?,
                    fees_fiat: row.get(5)?,
                    notes: row.get(6)?,
                    created_at_unix: row.get(7)?,
                    btc_per_unit_at_sale: row.get(8)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// List every sell across every investment. Used by the
    /// `TrackerCache` for a single-round-trip cache populate.
    pub fn list_all_investment_sells(&self) -> Result<Vec<TrackingInvestmentSell>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, investment_id, sale_unix, sale_price_per_unit, \
                        sale_qty, fees_fiat, notes, created_at_unix, btc_per_unit_at_sale \
                 FROM tracking_investment_sells \
                 ORDER BY investment_id ASC, sale_unix DESC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingInvestmentSell {
                    id: row.get(0)?,
                    investment_id: row.get(1)?,
                    sale_unix: row.get(2)?,
                    sale_price_per_unit: row.get(3)?,
                    sale_qty: row.get(4)?,
                    fees_fiat: row.get(5)?,
                    notes: row.get(6)?,
                    created_at_unix: row.get(7)?,
                    btc_per_unit_at_sale: row.get(8)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// List disposals across every sell. UI groups by `sell_id` at
    /// render. Used by both the per-sell detail view and the
    /// per-lot history view.
    pub fn list_all_lot_disposals(&self) -> Result<Vec<TrackingLotDisposal>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT id, sell_id, lot_id, qty_consumed, cost_basis_per_unit, \
                        gain_loss_fiat, created_at_unix, gain_loss_btc \
                 FROM tracking_lot_disposals \
                 ORDER BY sell_id ASC, id ASC",
            )
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let rows = stmt
            .query_map([], |row| {
                Ok(TrackingLotDisposal {
                    id: row.get(0)?,
                    sell_id: row.get(1)?,
                    lot_id: row.get(2)?,
                    qty_consumed: row.get(3)?,
                    cost_basis_per_unit: row.get(4)?,
                    gain_loss_fiat: row.get(5)?,
                    created_at_unix: row.get(6)?,
                    gain_loss_btc: row.get(7)?,
                })
            })
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
        }
        Ok(out)
    }

    /// Delete a sell row and undo it: every disposal's `qty_consumed`
    /// is added back to its lot, then the sell is deleted (disposals
    /// cascade off via the `sell_id` FK). One transaction — the lots
    /// and the realized history never disagree (M3).
    pub fn delete_investment_sell(&self, id: i64) -> Result<bool, OnchainError> {
        let tx = self
            .conn
            .unchecked_transaction()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        tx.execute(
            "UPDATE tracking_investment_lots SET quantity = quantity + \
                (SELECT COALESCE(SUM(d.qty_consumed), 0) FROM tracking_lot_disposals d \
                 WHERE d.sell_id = ?1 AND d.lot_id = tracking_investment_lots.id) \
             WHERE id IN (SELECT lot_id FROM tracking_lot_disposals WHERE sell_id = ?1)",
            [id],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        let n = tx
            .execute("DELETE FROM tracking_investment_sells WHERE id = ?1", [id])
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        tx.commit()
            .map_err(|e| OnchainError::Storage(e.to_string()))?;
        Ok(n > 0)
    }

    // ----------------------------------------------------------------
    // Phase 8.2 — BTC-at-time backfill delegation
    // ----------------------------------------------------------------

    /// Run the Phase 8.2 BTC-at-time backfill using this registry's
    /// open connection. Delegates to
    /// `crate::btc_at_time_backfill::phase_8_2_backfill_btc_at_time`.
    ///
    /// Exposed as a method so the FFI layer can reuse the already-open
    /// `Registry` connection (via `RegistryHandle`) without needing
    /// direct access to the private `conn` field.
    pub fn run_8_2_backfill<R: crate::btc_rate_history::BtcRateHistoryRepository>(
        &self,
        rate_repo: &R,
    ) -> Result<crate::btc_at_time_backfill::MigrationReport, OnchainError> {
        crate::btc_at_time_backfill::phase_8_2_backfill_btc_at_time(&self.conn, rate_repo)
    }

    // ----------------------------------------------------------------
    // Audit M4 — split-phase rate resolution (read / network / write)
    // ----------------------------------------------------------------
    //
    // The FFI keeps this `Registry` behind a mutex. These accessors let
    // it read the inputs, release the lock for the (possibly slow, Tor)
    // rate fetch, then re-lock only to write the result.

    /// `(utc_midnight_day, currency)` to price for a lot, `None` if missing.
    pub fn lot_btc_rate_query(&self, lot_id: i64) -> Result<Option<(i64, String)>, OnchainError> {
        crate::btc_at_time_backfill::lot_rate_query(&self.conn, lot_id)
    }

    /// Every lot as `(lot_id, utc_midnight_day, currency)`.
    pub fn all_lot_btc_rate_queries(&self) -> Result<Vec<(i64, i64, String)>, OnchainError> {
        crate::btc_at_time_backfill::all_lot_rate_queries(&self.conn)
    }

    /// Write a resolved purchase rate for a lot (`false` if it vanished).
    pub fn apply_lot_btc_rate(&self, lot_id: i64, rate: f64) -> Result<bool, OnchainError> {
        crate::btc_at_time_backfill::apply_lot_btc_rate(&self.conn, lot_id, rate)
    }

    /// `(utc_midnight_day, currency)` to price for a sell, `None` if missing.
    pub fn sell_btc_rate_query(&self, sell_id: i64) -> Result<Option<(i64, String)>, OnchainError> {
        crate::btc_at_time_backfill::sell_rate_query(&self.conn, sell_id)
    }

    /// Write a resolved sale rate + re-derive disposal BTC P&L.
    pub fn apply_sell_btc_rate(&self, sell_id: i64, rate: f64) -> Result<bool, OnchainError> {
        crate::btc_at_time_backfill::apply_sell_btc_rate(&self.conn, sell_id, rate)
    }

    /// Inputs to price a manual asset's purchase snapshot.
    pub fn manual_asset_btc_rate_query(
        &self,
        id: i64,
    ) -> Result<Option<crate::btc_at_time_backfill::ManualAssetRateQuery>, OnchainError> {
        crate::btc_at_time_backfill::manual_asset_rate_query(&self.conn, id)
    }

    /// Persist a manual asset's purchase snapshot.
    pub fn apply_manual_asset_purchase_btc(
        &self,
        id: i64,
        query: &crate::btc_at_time_backfill::ManualAssetRateQuery,
        rate: f64,
    ) -> Result<bool, OnchainError> {
        crate::btc_at_time_backfill::apply_manual_asset_purchase_btc(&self.conn, id, query, rate)
    }

    // ----------------------------------------------------------------
    // Phase 8.2 — per-row on-demand BTC-rate resolution
    // ----------------------------------------------------------------

    /// Resolve and persist `btc_per_unit_at_purchase` for a single
    /// investment lot.
    ///
    /// Reads the lot's `purchase_unix` and the parent investment's
    /// `cost_basis_currency`, queries the rate repository for
    /// `normalize_to_utc_midnight(purchase_unix)` in that currency,
    /// and writes the result to `btc_per_unit_at_purchase`.
    ///
    /// Returns `true` when a rate was written, `false` when the rate
    /// was unavailable (pre-genesis, future, unsupported currency) or
    /// the lot was not found. The column is left NULL in the
    /// unavailable case — a later retry will fill it.
    ///
    /// This is the single correct code path for at-save resolution;
    /// the Phase 8.2 backfill reuses the same logic via a shared
    /// helper in `btc_at_time_backfill`.
    pub fn resolve_lot_btc_rate<R: crate::btc_rate_history::BtcRateHistoryRepository>(
        &self,
        lot_id: i64,
        rate_repo: &R,
    ) -> Result<bool, OnchainError> {
        crate::btc_at_time_backfill::resolve_lot_btc_rate_impl(&self.conn, lot_id, rate_repo)
    }

    /// Resolve and persist `btc_per_unit_at_sale` for a single
    /// investment sell, and then re-derive `gain_loss_btc` for every
    /// disposal that belongs to this sell.
    ///
    /// Returns `true` when the sell rate was written. Disposal
    /// `gain_loss_btc` values are updated only when both the sell rate
    /// and the corresponding lot rate are non-NULL; otherwise they are
    /// left NULL for a later retry.
    pub fn resolve_sell_btc<R: crate::btc_rate_history::BtcRateHistoryRepository>(
        &self,
        sell_id: i64,
        rate_repo: &R,
    ) -> Result<bool, OnchainError> {
        crate::btc_at_time_backfill::resolve_sell_btc_impl(&self.conn, sell_id, rate_repo)
    }

    /// Resolve and persist `purchase_btc_at_time` for a single manual
    /// asset.
    ///
    /// Uses `purchase_unix` + `purchase_currency` when both are set;
    /// falls back to `purchase_currency = currency` when
    /// `purchase_currency` is NULL. Does nothing (returns `false`) when
    /// `purchase_unix` is NULL — the asset has no date anchor.
    pub fn resolve_manual_asset_purchase_btc<
        R: crate::btc_rate_history::BtcRateHistoryRepository,
    >(
        &self,
        id: i64,
        rate_repo: &R,
    ) -> Result<bool, OnchainError> {
        crate::btc_at_time_backfill::resolve_manual_asset_purchase_btc_impl(
            &self.conn, id, rate_repo,
        )
    }

    /// Re-resolve `btc_per_unit_at_purchase` for **every** investment lot
    /// in the database, overwriting any prior value (including a wrong
    /// "current rate at add time" that was written before the at-save
    /// logic existed).
    ///
    /// # Semantics
    ///
    /// - **Overwrite on success**: when a rate is obtained, the existing
    ///   value (even a correct one) is replaced with the freshly resolved
    ///   value. This ensures stale rates written by old code are corrected.
    /// - **Preserve on failure**: when a rate cannot be obtained (network
    ///   down, currency unsupported, date out of range), the existing
    ///   column value is left untouched. A transient network error does
    ///   not wipe a good historical rate that was written earlier.
    ///   The row will be retried on the next call.
    /// - **Idempotent**: running this twice produces the same result.
    ///
    /// Returns `(considered, updated, failed)` counters.
    pub fn recompute_all_lot_btc_rates<R: crate::btc_rate_history::BtcRateHistoryRepository>(
        &self,
        rate_repo: &R,
    ) -> Result<crate::btc_at_time_backfill::RecomputeStats, OnchainError> {
        crate::btc_at_time_backfill::recompute_all_lot_btc_rates_impl(&self.conn, rate_repo)
    }
}

/// One row of `tracking_event_cost_basis` (see
/// [`Registry::set_event_cost_basis`]).
#[derive(Debug, Clone, PartialEq)]
pub struct EventCostBasis {
    pub wallet_id: String,
    pub txid_or_payment_id: String,
    /// `(fiat per BTC, ISO currency)` typed by the user; `None` = market rate.
    pub price: Option<(f64, String)>,
    pub excluded: bool,
}

/// One row of the `tracking_views` table.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SavedView {
    pub id: i64,
    pub name: String,
    pub period: String,
    pub category_filter: Option<String>,
    pub time_range: Option<(i64, i64)>,
    pub created_at_unix: i64,
}

/// Phase 4.3 — one row of the `tracking_budgets` table.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackingBudget {
    pub category: String,
    pub budgeted_sat: u64,
    pub period: String,
    pub updated_at_unix: i64,
}

/// Phase 4.4 — one row of the `tracking_bills` table.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackingBill {
    pub id: i64,
    pub name: String,
    pub amount_sat: u64,
    pub due_day: u32,
    pub last_paid_unix: Option<i64>,
    pub display_label: Option<String>,
    pub created_at_unix: i64,
    /// v7 — the amount varies; `amount_sat` is an estimate.
    pub variable: bool,
    /// v7 — what the last "paid" mark recorded, when given.
    pub last_paid_amount_sat: Option<u64>,
    /// v8 — the budget category the bill's payments count against.
    pub category: Option<String>,
    /// v8 — the transaction that paid it (`<wallet_id>|<txid>`).
    pub paid_ref: Option<String>,
}

/// v8 — one row of the `tracking_manual_txns` table.
#[derive(Debug, Clone, PartialEq)]
pub struct ManualTxn {
    pub id: i64,
    /// One of [`VALID_MANUAL_TXN_DIRECTIONS`].
    pub direction: String,
    pub amount_sat: u64,
    pub amount_fiat: Option<f64>,
    pub currency: Option<String>,
    pub occurred_unix: i64,
    pub account_ref: Option<String>,
    pub note: Option<String>,
    pub created_at_unix: i64,
    /// v10 — the debt this entry pays, if any.
    pub liability_id: Option<i64>,
    /// v10 — how: one of [`crate::loan_schedule::VALID_LOAN_PAYMENT_KINDS`].
    pub liability_payment: Option<String>,
}

/// v10 — the debt a manual entry pays and how
/// (one of [`crate::loan_schedule::VALID_LOAN_PAYMENT_KINDS`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DebtPayment<'a> {
    pub liability_id: i64,
    pub kind: &'a str,
}

/// Phase 4.5 — one row of the `tracking_savings_goals` table.
///
/// Phase 7.2.1 — added `target_pct_of_net_worth`: a soft allocation
/// target the user expresses as a percentage of the *intended* net
/// worth. Independent of the absolute `target_sat` so the user can
/// say "this goal should be 30% of my future net worth, whatever
/// that turns out to be" while still pinning a concrete sat target.
/// Range: 0..=100 inclusive when set; `None` means no allocation
/// target was supplied. Validation happens at the FFI boundary.
///
/// Phase 7.2.4 — added `bucket_kind` + `bucket_ref`: a semantic
/// binding from the goal to a slice of the user's net worth, so the
/// UI can auto-fill `current_sat` instead of asking the user to type
/// it. `bucket_kind` is one of [`VALID_BUCKET_KINDS`]; `bucket_ref`
/// carries the optional disambiguator (e.g. wallet id for the
/// `wallet` kind). When `bucket_kind = 'manual'` (default for
/// pre-7.2.4 rows) the UI keeps the legacy hand-entered semantics.
///
/// Phase 8.6 — added `target_denomination`, `target_fiat_amount`,
/// `target_fiat_currency`. `Eq` dropped (f64 fields are not Eq).
#[derive(Debug, Clone, PartialEq)]
pub struct TrackingSavingsGoal {
    pub id: i64,
    pub name: String,
    pub target_sat: u64,
    pub current_sat: u64,
    pub color: String,
    pub deadline_unix: Option<i64>,
    pub linked_wallet_id: Option<String>,
    pub target_pct_of_net_worth: Option<u32>,
    pub bucket_kind: String,
    pub bucket_ref: Option<String>,
    /// Phase 7.7 — `savings` (legacy / open-ended) or `sinking`
    /// (target with a hard deadline). Validated against
    /// [`VALID_GOAL_KINDS`]. Sinking goals must have
    /// `deadline_unix.is_some()`.
    pub goal_kind: String,
    /// Phase 8.6 — `sat` (default) or `fiat`. Validated against
    /// [`VALID_GOAL_DENOMINATIONS`]. When `fiat`, the effective sat
    /// target is computed dynamically at render time from
    /// `target_fiat_amount / rate * 1e8`; `target_sat` is not relied
    /// upon for fiat goals.
    pub target_denomination: String,
    /// Phase 8.6 — fiat target amount in [`target_fiat_currency`].
    /// Only meaningful when `target_denomination == "fiat"`. NULL
    /// (None) for `sat`-denominated goals.
    pub target_fiat_amount: Option<f64>,
    /// Phase 8.6 — ISO 4217 currency for the fiat target (e.g. "EUR",
    /// "USD"). NULL (None) for `sat`-denominated goals.
    pub target_fiat_currency: Option<String>,
    pub created_at_unix: i64,
}

/// Phase 4.6 — one row of the `tracking_liabilities` table.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackingLiability {
    pub id: i64,
    pub name: String,
    pub amount_sat: u64,
    pub kind: String,
    pub display_label: Option<String>,
    pub created_at_unix: i64,
}

/// Phase 7.4 — one row of the `tracking_manual_assets` table.
///
/// Asset side, multi-currency, free-form value. Conceptual mirror of
/// `TrackingLiability`, but with a richer `kind` (8 values) and `value_fiat`
/// REAL instead of `amount_sat` INTEGER, because the value of real
/// estate is naturally expressed in fiat and is stable over time
/// (while its sat equivalent swings with the BTC price).
#[derive(Debug, Clone, PartialEq)]
pub struct TrackingManualAsset {
    pub id: i64,
    pub name: String,
    /// One of [`VALID_MANUAL_ASSET_KINDS`].
    pub kind: String,
    pub value_fiat: f64,
    /// ISO 4217 code. Defaults to 'EUR' at the schema layer when the
    /// caller doesn't specify.
    pub currency: String,
    pub display_label: Option<String>,
    /// Free-text memory aid (e.g. "2-bed flat, bought 2015").
    pub notes: Option<String>,
    /// Bumped to "now" on every add/update so the UI can render
    /// "Updated X ago" — most useful for `real_estate` / collectible
    /// where the user only refreshes the value occasionally.
    pub last_updated_unix: i64,
    /// Phase 8.2 — Unix timestamp of acquisition. NULL for legacy
    /// assets pre-8.2 before the backfill runs.
    pub purchase_unix: Option<i64>,
    /// Phase 8.2 — fiat value at acquisition. NULL pre-backfill.
    pub purchase_value_fiat: Option<f64>,
    /// Phase 8.2 — currency of acquisition (ISO 4217). NULL pre-backfill.
    pub purchase_currency: Option<String>,
    /// Phase 8.2 — 1 BTC = N units of `purchase_currency` at `purchase_unix`.
    /// NULL before the 8.2 backfill runs.
    pub purchase_btc_at_time: Option<f64>,
    pub created_at_unix: i64,
}

/// Phase 7.5 — one row of the `tracking_rules` table.
///
/// Applied at-import by the Kotlin `applyRules` helper to any new
/// `PaymentEvent` that doesn't already have a category set. The user's
/// manual category always wins (the matcher is only called when the
/// event has no category yet). Disable a rule by flipping `enabled`
/// rather than deleting — preserves the rule for "switch off for now".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrackingRule {
    pub id: i64,
    pub name: String,
    pub condition_field: String,
    pub condition_op: String,
    pub condition_value: String,
    pub action_field: String,
    pub action_value: String,
    pub priority: i64,
    pub enabled: bool,
    pub created_at_unix: i64,
}

/// Phase 5.1 — one row of the `tracking_investments` table.
///
/// T2-10: `manual_current_value` and `fetched_current_value` are kept
/// distinct so the auto-fetch loop never silently overwrites a
/// user-entered value. The UI's policy: prefer `fetched` when it is
/// non-null and `last_priced_at_unix` is fresh (≤ a few hours);
/// otherwise fall back to `manual`.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackingInvestment {
    pub id: i64,
    pub name: String,
    pub asset_type: String,
    pub ticker: Option<String>,
    pub quantity: f64,
    pub unit: String,
    pub cost_basis_fiat: f64,
    pub cost_basis_currency: String,
    pub purchase_unix: i64,
    /// User-entered current value (per quantity unit, in fiat). Survives
    /// auto-fetch updates — the fetcher writes to `fetched_current_value`.
    pub manual_current_value: Option<f64>,
    /// Last auto-fetched value (per quantity unit, in fiat). Null when
    /// no provider has returned a price, or the user disabled auto-fetch.
    pub fetched_current_value: Option<f64>,
    pub last_priced_at_unix: Option<i64>,
    pub notes: Option<String>,
    /// Phase 7.8 — `fifo` (default) / `hifo` / `specific_id`. Validated
    /// at the FFI boundary against [`VALID_COST_BASIS_METHODS`].
    pub cost_basis_method: String,
    pub created_at_unix: i64,
}

/// Phase 7.9 — one row of the `tracking_investment_sells` table.
///
/// The total realized gain for a sell is the sum of the
/// `gain_loss_fiat` over its `tracking_lot_disposals` rows. Stored
/// here is the "what the broker shows on the receipt" view of the
/// sale: total qty + total price + total fee.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackingInvestmentSell {
    pub id: i64,
    pub investment_id: i64,
    pub sale_unix: i64,
    pub sale_price_per_unit: f64,
    pub sale_qty: f64,
    pub fees_fiat: f64,
    pub notes: Option<String>,
    pub created_at_unix: i64,
    /// Phase 8.2 — 1 BTC = N units of `cost_basis_currency` at sale date.
    /// NULL before the 8.2 backfill runs.
    pub btc_per_unit_at_sale: Option<f64>,
}

/// Phase 7.9 — one row of the `tracking_lot_disposals` table.
///
/// Records the snapshot of "X lot disposed Y units at basis Z" so the
/// realized gain stays correct even if the underlying lot is later
/// edited or deleted (the disposal row keeps the historical cost
/// basis). Phase 8 tax export reads directly from these rows.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackingLotDisposal {
    pub id: i64,
    pub sell_id: i64,
    pub lot_id: i64,
    pub qty_consumed: f64,
    pub cost_basis_per_unit: f64,
    pub gain_loss_fiat: f64,
    pub created_at_unix: i64,
    /// Phase 8.2 — realized P&L in BTC for this disposal.
    /// NULL before the 8.2 backfill runs, or when a parent rate is unavailable.
    pub gain_loss_btc: Option<f64>,
}

/// Phase 7.8 — one row of the `tracking_investment_lots` table.
///
/// Each lot is a single purchase event. `price_per_unit` is in the
/// parent investment's `cost_basis_currency`. The aggregate cost
/// basis is computed at view-time as Σ(quantity × price + fees) so
/// the schema stays normalized.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackingInvestmentLot {
    pub id: i64,
    pub investment_id: i64,
    pub quantity: f64,
    pub price_per_unit: f64,
    pub fees_fiat: f64,
    pub purchase_unix: i64,
    pub notes: Option<String>,
    pub created_at_unix: i64,
    /// Phase 8.2 — 1 BTC = N units of `cost_basis_currency` at purchase date.
    /// NULL before the 8.2 backfill runs.
    pub btc_per_unit_at_purchase: Option<f64>,
    /// Schema v2 (M1) — purchased size, frozen at insert: `quantity` shrinks
    /// on sells, this does not. Purchase fees are spread over it.
    /// Falls back to `quantity` for a row the v2 backfill left NULL.
    pub original_quantity: f64,
}

/// One entry of a specific-ID sale: take `qty` units from lot `lot_id`.
/// See [`Registry::record_sell_with_lots`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LotSelection {
    pub lot_id: i64,
    pub qty: f64,
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/// Allowed values for `tracking_investments.asset_type`. Kept in sync
/// with the Kotlin chip picker in `InvestmentsCard.kt`. A new asset
/// class lands here AND in the UI together.
const VALID_ASSET_TYPES: &[&str] = &["stock", "etf", "fund", "currency", "commodity", "manual"];

/// Budget periods the UI can prorate (`budgetPeriodDays` in the desktop app).
const VALID_BUDGET_PERIODS: &[&str] = &["weekly", "monthly", "yearly"];

/// Reject an empty / whitespace-only user-facing text field.
fn validate_non_blank(what: &str, value: &str) -> Result<(), OnchainError> {
    if value.trim().is_empty() {
        Err(OnchainError::InvalidArgument(format!(
            "{what} must not be blank"
        )))
    } else {
        Ok(())
    }
}

fn validate_budget_period(period: &str) -> Result<(), OnchainError> {
    if VALID_BUDGET_PERIODS.contains(&period) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "budget period must be one of {VALID_BUDGET_PERIODS:?}, got {period:?}"
        )))
    }
}

/// Reject `due_day` values outside the 1..=31 calendar range. A 0 or
/// 32 silently breaks "paid this period" comparisons downstream
/// because the calendar logic assumes the value is a real day-of-month.
fn validate_due_day(d: u32) -> Result<(), OnchainError> {
    if (1..=31).contains(&d) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "due_day must be in 1..=31, got {d}"
        )))
    }
}

/// Reject `asset_type` values outside the documented whitelist.
fn validate_asset_type(t: &str) -> Result<(), OnchainError> {
    if VALID_ASSET_TYPES.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "asset_type must be one of {VALID_ASSET_TYPES:?}, got {t:?}"
        )))
    }
}

/// Phase 7.2.4 — allowed values for `tracking_savings_goals.bucket_kind`.
/// Kept in sync with the Kotlin enum in `GoalBuckets.kt` so a new
/// bucket lands here AND in the UI together.
///
///  - `manual` (default): legacy semantics — `current_sat` is whatever
///    the user typed.
///  - `investments`: UI sums `tracking_investments` (converted to sat
///    via the live BTC/fiat spot price).
///  - `bitcoin_cash`: UI uses the aggregate watch-only balance (i.e.
///    `aggregateWatchBalanceSat` — every wallet in the registry).
///  - `wallet`: UI uses the balance of the single wallet identified
///    by `bucket_ref` (must be a wallet id present in the registry).
pub(crate) const VALID_BUCKET_KINDS: &[&str] = &[
    "manual",
    "investments",
    "bitcoin_cash",
    "wallet",
    // Phase 7.4 — bind a goal to a single tracking_manual_assets row.
    // `bucket_ref` for this kind carries the asset id (i64 → string).
    "manual_asset",
];

/// Phase 7.7 — allowed values for `tracking_savings_goals.goal_kind`.
/// Orthogonal to `bucket_kind`: e.g. a sinking goal can bind to the
/// `investments` bucket.
///
///  - `savings`: open-ended target (legacy default).
///  - `sinking`: target with a hard deadline + suggested monthly
///    funding rate; the UI shows ON TRACK / BEHIND / AHEAD based on
///    progress vs schedule.
pub(crate) const VALID_GOAL_KINDS: &[&str] = &["savings", "sinking"];

/// Allowed values for `tracking_liabilities.kind`. The UI maps each one
/// to a localized label; anything else would surface as a raw string.
pub(crate) const VALID_LIABILITY_KINDS: &[&str] = &["mortgage", "loan", "credit-card", "other"];

fn validate_liability_kind(t: &str) -> Result<(), OnchainError> {
    if VALID_LIABILITY_KINDS.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "liability kind must be one of {VALID_LIABILITY_KINDS:?}, got {t:?}"
        )))
    }
}

/// Phase 8.6 — allowed values for
/// `tracking_savings_goals.target_denomination`.
///
///  - `sat`: the goal target is expressed purely in satoshis via
///    `target_sat`. Default for all pre-8.6 rows.
///  - `fiat`: the goal target is expressed in a fiat currency via
///    `target_fiat_amount` + `target_fiat_currency`. The effective
///    sat value is derived dynamically from the current BTC/fiat rate
///    and is not persisted.
pub(crate) const VALID_GOAL_DENOMINATIONS: &[&str] = &["sat", "fiat"];

fn validate_goal_kind(t: &str) -> Result<(), OnchainError> {
    if VALID_GOAL_KINDS.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "goal_kind must be one of {VALID_GOAL_KINDS:?}, got {t:?}"
        )))
    }
}

fn validate_goal_denomination(t: &str) -> Result<(), OnchainError> {
    if VALID_GOAL_DENOMINATIONS.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "target_denomination must be one of {VALID_GOAL_DENOMINATIONS:?}, got {t:?}"
        )))
    }
}

/// Phase 7.7 — sinking goals require a deadline. A `sinking` kind
/// without `deadline_unix` is meaningless (no time anchor, no
/// schedule, no monthly rate) so we reject the combination at the
/// registry boundary rather than silently degrading at render time.
fn validate_sinking_has_deadline(
    goal_kind: &str,
    deadline_unix: Option<i64>,
) -> Result<(), OnchainError> {
    if goal_kind == "sinking" && deadline_unix.is_none() {
        Err(OnchainError::InvalidArgument(
            "sinking goal must have a deadline_unix".into(),
        ))
    } else {
        Ok(())
    }
}

/// Phase 7.4 — allowed values for `tracking_manual_assets.kind`.
/// Kept in sync with the Kotlin enum / picker in `ManualAssetsCard.kt`
/// so a new kind lands here AND in the UI together. The set is
/// derived from the Kubera/Maybe canonical asset class taxonomy
/// (see `docs/research-finance-tracking.md` §2.1):
///  - `cash`: checking / savings / money-market / physical bills
///  - `brokerage`: taxable investment accounts NOT tracked by the
///    `tracking_investments` table (e.g. an account you don't want
///    to break down by ticker)
///  - `real_estate`: primary residence, rental, land
///  - `vehicle`: cars, motorbikes, boats
///  - `collectible`: art, watches, jewellery, wine, domains
///  - `business_equity`: ownership in a private company / LLC
///  - `loans_to_others`: money lent to someone that will return
///  - `other`: escape hatch for the long tail
pub(crate) const VALID_MANUAL_ASSET_KINDS: &[&str] = &[
    "cash",
    "brokerage",
    "real_estate",
    "vehicle",
    "collectible",
    "business_equity",
    "loans_to_others",
    "other",
];

/// v6 — the tables that carry `details_json`, by their API name. The
/// returned name is a compile-time constant, safe to interpolate.
fn details_table(table: &str) -> Result<&'static str, OnchainError> {
    match table {
        "investment" => Ok("tracking_investments"),
        "manual_asset" => Ok("tracking_manual_assets"),
        "liability" => Ok("tracking_liabilities"),
        other => Err(OnchainError::InvalidArgument(format!(
            "details table must be investment, manual_asset or liability, got {other:?}"
        ))),
    }
}

/// Limits of a `details_json` object.
pub(crate) const MAX_DETAILS_KEYS: usize = 24;
pub(crate) const MAX_DETAILS_KEY_LEN: usize = 40;
pub(crate) const MAX_DETAILS_VALUE_LEN: usize = 200;

/// Validate a details object and return its canonical (compact) form:
/// a flat JSON object of at most [`MAX_DETAILS_KEYS`] keys made of
/// `[a-z0-9_]`, whose values are strings of at most
/// [`MAX_DETAILS_VALUE_LEN`] characters, finite numbers or booleans.
/// Empty strings are dropped, so a cleared field leaves no key.
pub(crate) fn validate_details_json(json: &str) -> Result<String, OnchainError> {
    let bad = |m: String| OnchainError::InvalidArgument(format!("details: {m}"));
    let value: serde_json::Value =
        serde_json::from_str(json).map_err(|e| bad(format!("not JSON ({e})")))?;
    let serde_json::Value::Object(map) = value else {
        return Err(bad("must be a JSON object".into()));
    };
    let mut out = serde_json::Map::new();
    for (k, v) in map {
        if k.is_empty()
            || k.len() > MAX_DETAILS_KEY_LEN
            || !k
                .bytes()
                .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_')
        {
            return Err(bad(format!("invalid key {k:?}")));
        }
        match &v {
            serde_json::Value::String(t) if t.trim().is_empty() => continue,
            serde_json::Value::String(t) if t.chars().count() > MAX_DETAILS_VALUE_LEN => {
                return Err(bad(format!("value of {k:?} is too long")));
            }
            serde_json::Value::String(_) | serde_json::Value::Bool(_) => {}
            serde_json::Value::Number(n) if n.as_f64().is_some_and(f64::is_finite) => {}
            _ => {
                return Err(bad(format!(
                    "value of {k:?} must be a string, number or boolean"
                )));
            }
        }
        out.insert(k, v);
    }
    if out.len() > MAX_DETAILS_KEYS {
        return Err(bad(format!("at most {MAX_DETAILS_KEYS} keys")));
    }
    Ok(serde_json::Value::Object(out).to_string())
}

/// v8 — a manual transaction as it touches its account's balance.
struct AccountEffect<'a> {
    account_ref: Option<&'a str>,
    direction: &'a str,
    amount_sat: u64,
    amount_fiat: Option<f64>,
    currency: Option<&'a str>,
    /// v10 — the debt the entry pays.
    liability_id: Option<i64>,
}

impl ManualTxn {
    fn effect(&self) -> AccountEffect<'_> {
        AccountEffect {
            account_ref: self.account_ref.as_deref(),
            direction: &self.direction,
            amount_sat: self.amount_sat,
            amount_fiat: self.amount_fiat,
            currency: self.currency.as_deref(),
            liability_id: self.liability_id,
        }
    }
}

fn read_manual_txn(conn: &Connection, id: i64) -> Result<Option<ManualTxn>, OnchainError> {
    conn.query_row(
        "SELECT id, direction, amount_sat, amount_fiat, currency, occurred_unix, \
                account_ref, note, created_at_unix, liability_id, liability_payment \
         FROM tracking_manual_txns WHERE id = ?1",
        [id],
        |row| {
            Ok(ManualTxn {
                id: row.get(0)?,
                direction: row.get(1)?,
                amount_sat: row.get::<_, i64>(2)?.cast_unsigned(),
                amount_fiat: row.get(3)?,
                currency: row.get(4)?,
                occurred_unix: row.get(5)?,
                account_ref: row.get(6)?,
                note: row.get(7)?,
                created_at_unix: row.get(8)?,
                liability_id: row.get(9)?,
                liability_payment: row.get(10)?,
            })
        },
    )
    .optional()
    .map_err(|e| OnchainError::Storage(e.to_string()))
}

/// v8 — move the balance of an entry's account: spending lowers a
/// "cash" asset account (by the amount in its own currency) and raises
/// a credit card's debt (in sat, never below zero); income does the
/// opposite. `apply` false undoes the entry. Cash and `other` have no
/// balance. Applying checks the account exists and, for an asset
/// account, that the entry is in its currency; undoing an entry whose
/// account is gone is a no-op.
fn move_account_balance(
    conn: &Connection,
    e: &AccountEffect<'_>,
    apply: bool,
) -> Result<(), OnchainError> {
    if let Some(debt) = e.liability_id {
        // v10 — the payment lowers the debt (never below zero).
        let amount = sat_to_i64("amount_sat", e.amount_sat)?;
        let delta = if apply { -amount } else { amount };
        conn.execute(
            "UPDATE tracking_liabilities SET amount_sat = MAX(0, amount_sat + ?1) WHERE id = ?2",
            rusqlite::params![delta, debt],
        )
        .map_err(|err| OnchainError::Storage(err.to_string()))?;
    }
    let Some((kind, id)) = e.account_ref.and_then(|r| r.split_once(':')) else {
        return Ok(());
    };
    let Ok(id) = id.parse::<i64>() else {
        return Ok(());
    };
    let spending = e.direction == "out";
    let unknown = || OnchainError::InvalidArgument(format!("unknown account {kind}:{id}"));
    match kind {
        "asset" => {
            let account_currency: Option<String> = conn
                .query_row(
                    "SELECT currency FROM tracking_manual_assets WHERE id = ?1",
                    [id],
                    |row| row.get(0),
                )
                .optional()
                .map_err(|err| OnchainError::Storage(err.to_string()))?;
            let Some(account_currency) = account_currency else {
                return if apply { Err(unknown()) } else { Ok(()) };
            };
            let same_currency = e
                .currency
                .is_some_and(|c| c.trim().eq_ignore_ascii_case(account_currency.trim()));
            let (Some(fiat), true) = (e.amount_fiat, same_currency) else {
                return if apply {
                    Err(OnchainError::InvalidArgument(format!(
                        "an entry on this account must be in {account_currency}"
                    )))
                } else {
                    Ok(())
                };
            };
            let grows = spending != apply;
            let delta = if grows { fiat } else { -fiat };
            conn.execute(
                "UPDATE tracking_manual_assets SET value_fiat = value_fiat + ?1, \
                    last_updated_unix = ?2 WHERE id = ?3",
                rusqlite::params![delta, now_unix(), id],
            )
            .map_err(|err| OnchainError::Storage(err.to_string()))?;
        }
        "liability" => {
            let amount = sat_to_i64("amount_sat", e.amount_sat)?;
            let grows = spending == apply;
            let delta = if grows { amount } else { -amount };
            let n = conn
                .execute(
                    "UPDATE tracking_liabilities SET amount_sat = MAX(0, amount_sat + ?1) \
                     WHERE id = ?2",
                    rusqlite::params![delta, id],
                )
                .map_err(|err| OnchainError::Storage(err.to_string()))?;
            if n == 0 && apply {
                return Err(unknown());
            }
        }
        _ => {}
    }
    Ok(())
}

fn validate_manual_txn(
    direction: &str,
    amount_sat: u64,
    amount_fiat: Option<f64>,
    currency: Option<&str>,
) -> Result<(), OnchainError> {
    if !VALID_MANUAL_TXN_DIRECTIONS.contains(&direction) {
        return Err(OnchainError::InvalidArgument(format!(
            "manual transaction direction must be one of {VALID_MANUAL_TXN_DIRECTIONS:?}, got {direction:?}"
        )));
    }
    if amount_sat == 0 {
        return Err(OnchainError::InvalidArgument(
            "manual transaction amount must be positive".into(),
        ));
    }
    if let Some(v) = amount_fiat {
        validate_finite("amount_fiat", v)?;
    }
    let has_currency = currency.is_some_and(|c| !c.trim().is_empty());
    if amount_fiat.is_some() != has_currency {
        return Err(OnchainError::InvalidArgument(
            "amount_fiat and currency go together".into(),
        ));
    }
    Ok(())
}

/// v10 — a debt payment is spending, on an existing debt other than the
/// channel itself, of a known kind; on a mortgage / loan whose plan names
/// its currency, in that currency (the plan counts it in that currency).
fn validate_debt_payment(
    conn: &Connection,
    d: DebtPayment<'_>,
    direction: &str,
    currency: Option<&str>,
    account_ref: Option<&str>,
) -> Result<(), OnchainError> {
    let bad = |m: String| Err(OnchainError::InvalidArgument(m));
    if direction != "out" {
        return bad("a debt payment must be spending".into());
    }
    if !crate::loan_schedule::VALID_LOAN_PAYMENT_KINDS.contains(&d.kind) {
        return bad(format!(
            "debt payment kind must be one of {:?}, got {:?}",
            crate::loan_schedule::VALID_LOAN_PAYMENT_KINDS,
            d.kind
        ));
    }
    if account_ref == Some(format!("liability:{}", d.liability_id).as_str()) {
        return bad("a debt cannot pay itself".into());
    }
    let row: Option<(String, String)> = conn
        .query_row(
            "SELECT kind, details_json FROM tracking_liabilities WHERE id = ?1",
            [d.liability_id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .optional()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    let Some((kind, details)) = row else {
        return bad(format!("unknown debt {}", d.liability_id));
    };
    if crate::loan_schedule::SCHEDULED_LIABILITY_KINDS.contains(&kind.as_str()) {
        let plan_currency = crate::loan_schedule::terms_from_details(&details, "")
            .map(|t| t.currency)
            .filter(|c| !c.is_empty());
        if let Some(plan) = plan_currency {
            let same = currency.is_some_and(|c| c.trim().eq_ignore_ascii_case(&plan));
            if !same {
                return bad(format!("a payment on this debt must be in {plan}"));
            }
        }
    }
    Ok(())
}

fn validate_manual_asset_kind(t: &str) -> Result<(), OnchainError> {
    if VALID_MANUAL_ASSET_KINDS.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "manual_asset kind must be one of {VALID_MANUAL_ASSET_KINDS:?}, got {t:?}"
        )))
    }
}

/// Phase 7.10 — allowed values for `tracking_event_crypto_kind.kind`.
/// Nine semantic categories from the research §3.8. Tax treatment
/// varies by jurisdiction — BHODL classifies, the export reader
/// (Phase 8) maps to country-specific account names.
///
///  - `regular`: default — no special treatment, equivalent to no row
///  - `airdrop`: incoming, ordinary income at fair market value
///  - `staking_reward`: incoming, ordinary income at receipt
///  - `lending_interest`: incoming, ordinary income
///  - `mining_reward`: incoming, ordinary income
///  - `swap`: capital gain on disposed side + new lot on incoming
///  - `send_to_self`: internal transfer, NOT a taxable event
///  - `gift_received`: varies — basis usually carries from donor
///  - `gift_sent`: varies — many jurisdictions exempt capital gain
pub(crate) const VALID_CRYPTO_EVENT_KINDS: &[&str] = &[
    "regular",
    "airdrop",
    "staking_reward",
    "lending_interest",
    "mining_reward",
    "swap",
    "send_to_self",
    "gift_received",
    "gift_sent",
];

fn validate_crypto_event_kind(t: &str) -> Result<(), OnchainError> {
    if VALID_CRYPTO_EVENT_KINDS.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "crypto_event_kind must be one of {VALID_CRYPTO_EVENT_KINDS:?}, got {t:?}"
        )))
    }
}

/// Phase 7.8 — allowed values for `tracking_investments.cost_basis_method`.
/// Three canonical methods from the research §3.8:
///  - `fifo` (default): oldest lot consumed first; IT/US broker default.
///  - `hifo`: highest-priced lot consumed first; minimizes taxable gain.
///  - `specific_id`: caller picks lot per disposal (UI presents a sub-picker
///    in the future sell flow, Phase 7.9).
pub(crate) const VALID_COST_BASIS_METHODS: &[&str] = &["fifo", "hifo", "specific_id"];

fn validate_cost_basis_method(t: &str) -> Result<(), OnchainError> {
    if VALID_COST_BASIS_METHODS.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "cost_basis_method must be one of {VALID_COST_BASIS_METHODS:?}, got {t:?}"
        )))
    }
}

/// Phase 7.5 — allowed values for `tracking_rules.condition_field`.
/// Pinned to description / payee / amount for v1 — ~90% of practical
/// rules fit there (see `docs/research-finance-tracking.md` §3.3).
///
/// UX review 2026-10 (item 11) — `amount_in` / `amount_out` are `amount`
/// restricted to incoming / outgoing events, so a rule learnt from an
/// edit ("always 21,000 sat → Netflix") does not also catch an incoming
/// payment of the same size.
pub(crate) const VALID_RULE_FIELDS: &[&str] =
    &["description", "payee", "amount", "amount_in", "amount_out"];

/// The numeric subset of [`VALID_RULE_FIELDS`] (take [`VALID_RULE_OPS_NUMBER`]).
pub(crate) const NUMERIC_RULE_FIELDS: &[&str] = &["amount", "amount_in", "amount_out"];

/// Phase 7.5 — text-style ops, valid when `condition_field` is
/// description or payee. Case-insensitivity is applied at the
/// matcher layer (Kotlin `applyRules`), not stored here.
pub(crate) const VALID_RULE_OPS_TEXT: &[&str] = &["contains", "equals", "starts_with", "ends_with"];

/// Phase 7.5 — numeric ops, valid only for the [`NUMERIC_RULE_FIELDS`].
/// `value` is parsed as `u64` sat at match time; we store it as TEXT
/// because the table is mixed-type by design.
pub(crate) const VALID_RULE_OPS_NUMBER: &[&str] = &["gt", "lt", "eq"];

/// Phase 7.5 — allowed `tracking_rules.action_field`. V1 only sets
/// the category; later phases may add tags / exclude / split.
pub(crate) const VALID_RULE_ACTIONS: &[&str] = &["category"];

/// Validate a rule row pre-INSERT. Enforces:
///  - field in [`VALID_RULE_FIELDS`]
///  - op compatible with field (amount → number ops, else text ops)
///  - action in [`VALID_RULE_ACTIONS`]
///  - when op is numeric, value parses as u64
fn validate_rule(field: &str, op: &str, value: &str, action: &str) -> Result<(), OnchainError> {
    if !VALID_RULE_FIELDS.contains(&field) {
        return Err(OnchainError::InvalidArgument(format!(
            "rule field must be one of {VALID_RULE_FIELDS:?}, got {field:?}"
        )));
    }
    if !VALID_RULE_ACTIONS.contains(&action) {
        return Err(OnchainError::InvalidArgument(format!(
            "rule action must be one of {VALID_RULE_ACTIONS:?}, got {action:?}"
        )));
    }
    if NUMERIC_RULE_FIELDS.contains(&field) {
        if !VALID_RULE_OPS_NUMBER.contains(&op) {
            return Err(OnchainError::InvalidArgument(format!(
                "rule op for amount must be one of {VALID_RULE_OPS_NUMBER:?}, got {op:?}"
            )));
        }
        if value.parse::<u64>().is_err() {
            return Err(OnchainError::InvalidArgument(format!(
                "rule value for amount must parse as u64, got {value:?}"
            )));
        }
    } else if !VALID_RULE_OPS_TEXT.contains(&op) {
        return Err(OnchainError::InvalidArgument(format!(
            "rule op for {field} must be one of {VALID_RULE_OPS_TEXT:?}, got {op:?}"
        )));
    }
    Ok(())
}

/// Reject `bucket_kind` values outside the documented whitelist. A
/// future bucket added on the UI side without updating this list
/// would otherwise persist into the DB and break the Kotlin enum
/// mapping silently.
fn validate_bucket_kind(t: &str) -> Result<(), OnchainError> {
    if VALID_BUCKET_KINDS.contains(&t) {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "bucket_kind must be one of {VALID_BUCKET_KINDS:?}, got {t:?}"
        )))
    }
}

/// Reject NaN / Infinity floats before binding them to `SQLite`. `SQLite`
/// would coerce NaN to NULL but a non-finite intermediate value also
/// propagates into the UI's P&L math, where it would render as NaN%
/// or just silently corrupt aggregations.
fn validate_finite(field: &'static str, v: f64) -> Result<(), OnchainError> {
    if v.is_finite() {
        Ok(())
    } else {
        Err(OnchainError::InvalidArgument(format!(
            "{field} must be a finite number, got {v}"
        )))
    }
}

/// Finite and `>= 0` — quantities, prices and fees can never be negative.
fn validate_non_negative(field: &'static str, v: f64) -> Result<(), OnchainError> {
    validate_finite(field, v)?;
    if v < 0.0 {
        return Err(OnchainError::InvalidArgument(format!(
            "{field} must not be negative, got {v}"
        )));
    }
    Ok(())
}

/// Total BTC supply cap in satoshis (21 M BTC).
const MAX_SAT: u64 = 21_000_000 * 100_000_000;

/// Convert a sat amount for an `INTEGER` column, rejecting values above
/// the 21 M BTC supply. Besides being nonsensical, values above
/// `i64::MAX` would wrap negative on a plain `as i64` cast.
fn sat_to_i64(field: &'static str, v: u64) -> Result<i64, OnchainError> {
    if v > MAX_SAT {
        return Err(OnchainError::InvalidArgument(format!(
            "{field} {v} exceeds the 21M BTC supply"
        )));
    }
    i64::try_from(v)
        .map_err(|_| OnchainError::InvalidArgument(format!("{field} {v} does not fit in i64")))
}

/// Relative tolerance for comparing/zeroing fractional quantities:
/// ~1e-9 of the magnitude involved, never below 1e-12 absolute.
fn qty_tolerance(magnitude: f64) -> f64 {
    (magnitude.abs() * 1e-9).max(1e-12)
}

fn network_as_db_str(n: bhodl_core::Network) -> Result<String, OnchainError> {
    let s = match n {
        bhodl_core::Network::Mainnet => "mainnet",
        bhodl_core::Network::Signet => "signet",
        bhodl_core::Network::Testnet4 => "testnet4",
        bhodl_core::Network::Regtest => "regtest",
        other => {
            return Err(OnchainError::InvalidArgument(format!(
                "network {other:?} is not supported by the registry"
            )));
        }
    };
    Ok(s.to_string())
}

fn now_unix() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| i64::try_from(d.as_secs()).unwrap_or(i64::MAX))
        .unwrap_or(0)
}

#[cfg(test)]
mod uc_tracker_tests;

#[cfg(test)]
#[allow(
    clippy::float_cmp,
    reason = "tests assert exact, deterministically computed f64 values"
)]
mod tests {
    use super::*;
    use tempfile::TempDir;

    fn fixture_key() -> [u8; ENCRYPTION_KEY_LEN] {
        [0x42u8; ENCRYPTION_KEY_LEN]
    }

    fn fixture_record(label: &str) -> WalletRecord {
        WalletRecord {
            id: Uuid::new_v4(),
            label: label.to_string(),
            kind: WalletKind::WatchXpub,
            descriptor_pub: "tr(xpub6Cabcdef.../0/*)".to_string(),
            network: "signet".to_string(),
            created_at_unix: 1_715_000_000,
        }
    }

    #[test]
    fn open_creates_empty_table() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        assert_eq!(registry.list_wallets().unwrap(), Vec::new());
    }

    #[test]
    fn add_then_list_round_trips() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let record = fixture_record("My Wallet");
        registry.add_wallet(&record).unwrap();
        let listed = registry.list_wallets().unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0], record);
    }

    #[test]
    fn get_wallet_returns_none_for_unknown() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        assert_eq!(registry.get_wallet(&Uuid::new_v4()).unwrap(), None);
    }

    #[test]
    fn get_wallet_returns_inserted_row() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let record = fixture_record("Cold");
        registry.add_wallet(&record).unwrap();
        let got = registry.get_wallet(&record.id).unwrap().unwrap();
        assert_eq!(got, record);
    }

    #[test]
    fn remove_wallet_returns_true_when_row_existed() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let record = fixture_record("Soon-to-be-gone");
        registry.add_wallet(&record).unwrap();
        assert!(registry.remove_wallet(&record.id).unwrap());
        assert!(!registry.remove_wallet(&record.id).unwrap()); // second remove is a no-op
        assert!(registry.list_wallets().unwrap().is_empty());
    }

    #[test]
    fn rename_wallet_updates_label() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let record = fixture_record("Old");
        registry.add_wallet(&record).unwrap();
        assert!(registry.rename_wallet(&record.id, "New").unwrap());
        let got = registry.get_wallet(&record.id).unwrap().unwrap();
        assert_eq!(got.label, "New");
        // Unknown id returns false.
        assert!(!registry.rename_wallet(&Uuid::new_v4(), "X").unwrap());
    }

    #[test]
    fn wrong_encryption_key_fails_to_open() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        {
            let registry = open_registry(&path, &fixture_key()).unwrap();
            registry.add_wallet(&fixture_record("Alice")).unwrap();
        }
        let wrong = [0x99u8; ENCRYPTION_KEY_LEN];
        assert!(open_registry(&path, &wrong).is_err());
    }

    #[test]
    fn schema_idempotent_across_opens() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let r1 = open_registry(&path, &fixture_key()).unwrap();
        r1.add_wallet(&fixture_record("Alice")).unwrap();
        drop(r1);
        let r2 = open_registry(&path, &fixture_key()).unwrap();
        // Second open does not wipe the existing row.
        assert_eq!(r2.list_wallets().unwrap().len(), 1);
    }

    // ---------------------------------------------------------------
    // Phase 3.4 — tracking_categories tests
    // ---------------------------------------------------------------

    #[test]
    fn category_set_get_round_trip() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let wallet_id = Uuid::new_v4();
        let txid = "abc123";
        registry
            .set_event_category(&wallet_id, txid, Some("Cibo"))
            .unwrap();
        assert_eq!(
            registry.get_event_category(&wallet_id, txid).unwrap(),
            Some("Cibo".to_string())
        );
    }

    #[test]
    fn category_set_none_clears_row() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let wallet_id = Uuid::new_v4();
        registry
            .set_event_category(&wallet_id, "tx", Some("X"))
            .unwrap();
        registry.set_event_category(&wallet_id, "tx", None).unwrap();
        assert_eq!(registry.get_event_category(&wallet_id, "tx").unwrap(), None);
    }

    #[test]
    fn category_empty_string_treated_as_clear() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let wallet_id = Uuid::new_v4();
        registry
            .set_event_category(&wallet_id, "tx", Some("Initial"))
            .unwrap();
        registry
            .set_event_category(&wallet_id, "tx", Some("   "))
            .unwrap();
        assert_eq!(registry.get_event_category(&wallet_id, "tx").unwrap(), None);
    }

    #[test]
    fn category_replace_overwrites_existing() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let wallet_id = Uuid::new_v4();
        registry
            .set_event_category(&wallet_id, "tx", Some("Cibo"))
            .unwrap();
        registry
            .set_event_category(&wallet_id, "tx", Some("Trasporti"))
            .unwrap();
        assert_eq!(
            registry.get_event_category(&wallet_id, "tx").unwrap(),
            Some("Trasporti".to_string())
        );
    }

    #[test]
    fn list_categories_returns_distinct_alpha_sorted() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let w1 = Uuid::new_v4();
        let w2 = Uuid::new_v4();
        registry
            .set_event_category(&w1, "tx1", Some("Cibo"))
            .unwrap();
        registry
            .set_event_category(&w1, "tx2", Some("Trasporti"))
            .unwrap();
        registry
            .set_event_category(&w2, "tx3", Some("Cibo"))
            .unwrap();
        registry
            .set_event_category(&w2, "tx4", Some("Acquisto BTC"))
            .unwrap();
        let cats = registry.list_categories().unwrap();
        assert_eq!(
            cats,
            vec![
                "Acquisto BTC".to_string(),
                "Cibo".to_string(),
                "Trasporti".to_string(),
            ]
        );
    }

    #[test]
    fn purge_categories_for_wallet_only_touches_that_wallet() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let w1 = Uuid::new_v4();
        let w2 = Uuid::new_v4();
        registry.set_event_category(&w1, "tx1", Some("X")).unwrap();
        registry.set_event_category(&w2, "tx2", Some("Y")).unwrap();
        registry.purge_categories_for_wallet(&w1).unwrap();
        assert_eq!(registry.get_event_category(&w1, "tx1").unwrap(), None);
        assert_eq!(
            registry.get_event_category(&w2, "tx2").unwrap(),
            Some("Y".to_string())
        );
    }

    // ---------------------------------------------------------------
    // Phase 3.8 — tracking_views tests
    // ---------------------------------------------------------------

    #[test]
    fn save_and_list_views_round_trip() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let id1 = registry
            .save_view("Cibo 30g", "D30", Some("Cibo"), None)
            .unwrap();
        let id2 = registry
            .save_view(
                "Marzo 2026",
                "ALL",
                None,
                Some((1_740_787_200, 1_743_465_599)),
            )
            .unwrap();
        assert!(id1 > 0 && id2 > 0 && id2 != id1);
        let views = registry.list_views().unwrap();
        assert_eq!(views.len(), 2);
        assert_eq!(views[0].name, "Cibo 30g");
        assert_eq!(views[0].period, "D30");
        assert_eq!(views[0].category_filter, Some("Cibo".to_string()));
        assert_eq!(views[0].time_range, None);
        assert_eq!(views[1].time_range, Some((1_740_787_200, 1_743_465_599)));
    }

    #[test]
    fn save_view_replaces_by_name() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        registry.save_view("Vista A", "D7", None, None).unwrap();
        registry.save_view("Vista A", "Y1", Some(""), None).unwrap();
        let views = registry.list_views().unwrap();
        assert_eq!(views.len(), 1);
        assert_eq!(views[0].period, "Y1");
        assert_eq!(views[0].category_filter, Some(String::new()));
    }

    #[test]
    fn save_view_rejects_blank_name() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        assert!(registry.save_view("   ", "D30", None, None).is_err());
        assert!(registry.list_views().unwrap().is_empty());
    }

    #[test]
    fn delete_view_removes_only_target_row() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let _ = registry.save_view("Keep", "D7", None, None).unwrap();
        let drop_id = registry.save_view("Drop", "D30", None, None).unwrap();
        registry.delete_view(drop_id).unwrap();
        let views = registry.list_views().unwrap();
        assert_eq!(views.len(), 1);
        assert_eq!(views[0].name, "Keep");
    }

    #[test]
    fn all_three_wallet_kinds_round_trip() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        for kind in [
            WalletKind::WatchXpub,
            WalletKind::WatchAddresses,
            WalletKind::WatchHd,
        ] {
            let mut record = fixture_record(&format!("{kind:?}"));
            record.kind = kind;
            registry.add_wallet(&record).unwrap();
            let got = registry.get_wallet(&record.id).unwrap().unwrap();
            assert_eq!(got.kind, kind);
        }
        assert_eq!(registry.list_wallets().unwrap().len(), 3);
    }

    // -----------------------------------------------------------------------
    // Watch-wallet convenience constructor tests (≥ 12)
    // -----------------------------------------------------------------------

    fn xpub_from_mnemonic(network: bhodl_core::Network) -> String {
        crate::test_support::bip86_xpub(network)
    }

    fn signet_p2tr_address_at(index: u32) -> String {
        crate::test_support::bip86_address(bhodl_core::Network::Signet, index)
    }

    fn mainnet_p2tr_address_at(index: u32) -> String {
        crate::test_support::bip86_address(bhodl_core::Network::Mainnet, index)
    }

    // 1. add_watch_xpub inserts a WatchXpub row with a tr( descriptor.
    #[test]
    fn add_watch_xpub_inserts_row_with_correct_kind_and_descriptor() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let xpub = xpub_from_mnemonic(bhodl_core::Network::Signet);

        let record = registry
            .add_watch_xpub(
                "My Cold",
                &xpub,
                bhodl_core::Network::Signet,
                WatchBip::Taproot,
            )
            .unwrap();

        assert_eq!(record.kind, WalletKind::WatchXpub);
        assert!(
            record.descriptor_pub.starts_with("tr("),
            "expected tr( prefix, got: {}",
            record.descriptor_pub
        );
        assert_eq!(record.label, "My Cold");
        assert_eq!(record.network, "signet");
    }

    // 2. BIP-84 → descriptor starts with wpkh(
    #[test]
    fn add_watch_xpub_bip84_descriptor_starts_with_wpkh() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let xpub = xpub_from_mnemonic(bhodl_core::Network::Signet);

        let record = registry
            .add_watch_xpub(
                "BIP84",
                &xpub,
                bhodl_core::Network::Signet,
                WatchBip::NativeSegwit,
            )
            .unwrap();

        assert!(
            record.descriptor_pub.starts_with("wpkh("),
            "expected wpkh( prefix, got: {}",
            record.descriptor_pub
        );
    }

    // 3. BIP-49 → descriptor starts with sh(wpkh(
    #[test]
    fn add_watch_xpub_bip49_descriptor_starts_with_sh_wpkh() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let xpub = xpub_from_mnemonic(bhodl_core::Network::Signet);

        let record = registry
            .add_watch_xpub(
                "BIP49",
                &xpub,
                bhodl_core::Network::Signet,
                WatchBip::NestedSegwit,
            )
            .unwrap();

        assert!(
            record.descriptor_pub.starts_with("sh(wpkh("),
            "expected sh(wpkh( prefix, got: {}",
            record.descriptor_pub
        );
    }

    // 4. Mainnet xpub on signet network → Err(Descriptor).
    #[test]
    fn add_watch_xpub_rejects_mainnet_xpub_when_network_is_signet() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let mainnet_xpub = xpub_from_mnemonic(bhodl_core::Network::Mainnet);

        let result = registry.add_watch_xpub(
            "Bad",
            &mainnet_xpub,
            bhodl_core::Network::Signet,
            WatchBip::Taproot,
        );

        assert!(
            matches!(result, Err(OnchainError::Descriptor(_))),
            "expected Descriptor error, got: {result:?}"
        );
    }

    // 5. Full HD round-trip: fingerprint + BIP-86 path + tpub.
    #[test]
    fn add_watch_hd_round_trip() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let xpub = xpub_from_mnemonic(bhodl_core::Network::Signet);

        let record = registry
            .add_watch_hd(
                "Sparrow Import",
                "73c5da0a",
                "m/86'/1'/0'",
                &xpub,
                bhodl_core::Network::Signet,
            )
            .unwrap();

        assert_eq!(record.kind, WalletKind::WatchHd);
        assert!(
            record.descriptor_pub.contains("[73c5da0a/86'/1'/0']"),
            "expected origin in descriptor, got: {}",
            record.descriptor_pub
        );
    }

    // 6. BIP-44 (legacy) path → Err(Descriptor).
    #[test]
    fn add_watch_hd_rejects_legacy_path() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let xpub = xpub_from_mnemonic(bhodl_core::Network::Signet);

        let result = registry.add_watch_hd(
            "Legacy",
            "73c5da0a",
            "m/44'/1'/0'",
            &xpub,
            bhodl_core::Network::Signet,
        );

        assert!(
            matches!(result, Err(OnchainError::Descriptor(_))),
            "expected Descriptor error for BIP-44 path, got: {result:?}"
        );
    }

    // 7. Single signet P2TR address round-trips through the registry.
    #[test]
    fn add_watch_addresses_single_signet_tr_address_round_trips() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let addr = signet_p2tr_address_at(0);

        let record = registry
            .add_watch_addresses("My Addrs", &[addr.clone()], bhodl_core::Network::Signet)
            .unwrap();

        assert_eq!(record.kind, WalletKind::WatchAddresses);
        // descriptor_pub is a JSON array containing the address.
        let stored: Vec<String> = serde_json::from_str(&record.descriptor_pub).unwrap();
        assert_eq!(stored, vec![addr]);
    }

    // 8. Unparseable address → Err(InvalidAddress). (Non-P2TR types are
    // accepted since address wallets track raw script pubkeys.)
    #[test]
    fn add_watch_addresses_rejects_garbage() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();

        let result = registry.add_watch_addresses(
            "Bad",
            &["tb1qnotanaddress".to_string()],
            bhodl_core::Network::Signet,
        );

        assert!(
            matches!(result, Err(OnchainError::InvalidAddress(_))),
            "expected InvalidAddress for garbage, got: {result:?}"
        );
    }

    // 9. Empty address list → Err(InvalidAddress).
    #[test]
    fn add_watch_addresses_rejects_empty_list() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();

        let result = registry.add_watch_addresses("Empty", &[], bhodl_core::Network::Signet);

        assert!(
            matches!(result, Err(OnchainError::InvalidAddress(_))),
            "expected InvalidAddress for empty list, got: {result:?}"
        );
    }

    // 10. Duplicate addresses are deduplicated in stored JSON.
    #[test]
    fn add_watch_addresses_dedupes() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let addr = signet_p2tr_address_at(0);

        let record = registry
            .add_watch_addresses(
                "Dedup",
                &[addr.clone(), addr.clone()],
                bhodl_core::Network::Signet,
            )
            .unwrap();

        let stored: Vec<String> = serde_json::from_str(&record.descriptor_pub).unwrap();
        assert_eq!(stored.len(), 1, "duplicate address must be deduplicated");
        assert_eq!(stored[0], addr);
    }

    // 11. Mainnet P2TR address against signet network → Err(InvalidAddress).
    #[test]
    fn add_watch_addresses_rejects_mainnet_address_when_network_is_signet() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let mainnet_addr = mainnet_p2tr_address_at(0);

        let result =
            registry.add_watch_addresses("Wrong Net", &[mainnet_addr], bhodl_core::Network::Signet);

        assert!(
            matches!(result, Err(OnchainError::InvalidAddress(_))),
            "expected InvalidAddress for mainnet addr on signet, got: {result:?}"
        );
    }

    // 12. After add_watch_xpub, list_wallets() contains the new record.
    #[test]
    fn add_watch_xpub_then_list_wallets_returns_it() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let xpub = xpub_from_mnemonic(bhodl_core::Network::Signet);

        let record = registry
            .add_watch_xpub(
                "Listed Wallet",
                &xpub,
                bhodl_core::Network::Signet,
                WatchBip::Taproot,
            )
            .unwrap();

        let listed = registry.list_wallets().unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, record.id);
        assert_eq!(listed[0].kind, WalletKind::WatchXpub);
        assert_eq!(listed[0].label, "Listed Wallet");
    }

    // ---------------------------------------------------------------
    // Phase 4.3 — tracking_budgets tests
    // ---------------------------------------------------------------

    fn open_fresh_registry(dir: &TempDir) -> Registry {
        let path = dir.path().join(REGISTRY_FILE_NAME);
        open_registry(&path, &fixture_key()).unwrap()
    }

    #[test]
    fn budgets_list_is_empty_on_fresh_db() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(r.list_budgets().unwrap().is_empty());
    }

    #[test]
    fn budgets_set_then_list_round_trips() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_budget("Cibo", 50_000, "monthly").unwrap();
        r.set_budget("Trasporti", 30_000, "monthly").unwrap();
        let rows = r.list_budgets().unwrap();
        // Alphabetical order is guaranteed by list_budgets.
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].category, "Cibo");
        assert_eq!(rows[0].budgeted_sat, 50_000);
        assert_eq!(rows[0].period, "monthly");
        assert_eq!(rows[1].category, "Trasporti");
        assert_eq!(rows[1].budgeted_sat, 30_000);
    }

    #[test]
    fn budgets_set_overwrites_existing_category() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_budget("Cibo", 50_000, "monthly").unwrap();
        r.set_budget("Cibo", 80_000, "monthly").unwrap();
        let rows = r.list_budgets().unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].budgeted_sat, 80_000);
    }

    #[test]
    fn budgets_period_is_preserved_verbatim() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_budget("Affitto", 1_000_000, "yearly").unwrap();
        let rows = r.list_budgets().unwrap();
        assert_eq!(rows[0].period, "yearly");
    }

    #[test]
    fn budgets_delete_returns_true_only_when_row_existed() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_budget("Cibo", 50_000, "monthly").unwrap();
        assert!(r.delete_budget("Cibo").unwrap());
        assert!(!r.delete_budget("Cibo").unwrap());
        assert!(r.list_budgets().unwrap().is_empty());
    }

    #[test]
    fn budgets_survive_reopen() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.set_budget("Cibo", 50_000, "monthly").unwrap();
        }
        let r2 = open_fresh_registry(&dir);
        assert_eq!(r2.list_budgets().unwrap()[0].budgeted_sat, 50_000);
    }

    // ---------------------------------------------------------------
    // Phase 4.4 — tracking_bills tests
    // ---------------------------------------------------------------

    #[test]
    fn bills_add_assigns_autoincrement_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id1 = r.add_bill("Netflix", 12_000, 5, None).unwrap();
        let id2 = r.add_bill("Spotify", 9_000, 10, None).unwrap();
        assert!(id2 > id1);
    }

    #[test]
    fn bills_list_orders_by_due_day_then_created() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_bill("Late", 1, 28, None).unwrap();
        r.add_bill("Mid", 1, 15, None).unwrap();
        r.add_bill("Early", 1, 1, None).unwrap();
        let bills = r.list_bills().unwrap();
        assert_eq!(bills[0].name, "Early");
        assert_eq!(bills[1].name, "Mid");
        assert_eq!(bills[2].name, "Late");
    }

    #[test]
    fn bills_display_label_round_trips_when_present_and_null() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id_label = r.add_bill("Rent", 100_000, 1, Some("€900/month")).unwrap();
        let id_nolabel = r.add_bill("Luce", 50_000, 15, None).unwrap();
        let bills = r.list_bills().unwrap();
        let with_label = bills.iter().find(|b| b.id == id_label).unwrap();
        let no_label = bills.iter().find(|b| b.id == id_nolabel).unwrap();
        assert_eq!(with_label.display_label.as_deref(), Some("€900/month"));
        assert_eq!(no_label.display_label, None);
    }

    #[test]
    fn bills_update_changes_fields_in_place() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r.add_bill("Netflix", 12_000, 5, None).unwrap();
        assert!(
            r.update_bill(id, "Netflix Premium", 20_000, 7, Some("€20/mese"))
                .unwrap()
        );
        let b = r
            .list_bills()
            .unwrap()
            .into_iter()
            .find(|b| b.id == id)
            .unwrap();
        assert_eq!(b.name, "Netflix Premium");
        assert_eq!(b.amount_sat, 20_000);
        assert_eq!(b.due_day, 7);
        assert_eq!(b.display_label.as_deref(), Some("€20/mese"));
    }

    #[test]
    fn bills_update_returns_false_for_missing_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(!r.update_bill(9999, "X", 1, 1, None).unwrap());
    }

    #[test]
    fn bills_mark_paid_sets_and_clears_timestamp() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r.add_bill("Netflix", 12_000, 5, None).unwrap();
        assert!(r.list_bills().unwrap()[0].last_paid_unix.is_none());
        assert!(r.mark_bill_paid(id, Some(1_700_000_000)).unwrap());
        assert_eq!(
            r.list_bills().unwrap()[0].last_paid_unix,
            Some(1_700_000_000)
        );
        // Clearing: pass None
        assert!(r.mark_bill_paid(id, None).unwrap());
        assert_eq!(r.list_bills().unwrap()[0].last_paid_unix, None);
    }

    #[test]
    fn bills_mark_paid_returns_false_for_missing_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(!r.mark_bill_paid(9999, Some(1)).unwrap());
    }

    #[test]
    fn bills_delete_only_target_row() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id1 = r.add_bill("Netflix", 12_000, 5, None).unwrap();
        let id2 = r.add_bill("Spotify", 9_000, 10, None).unwrap();
        assert!(r.delete_bill(id1).unwrap());
        let remaining = r.list_bills().unwrap();
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].id, id2);
        // Second delete on same id returns false.
        assert!(!r.delete_bill(id1).unwrap());
    }

    // ---------------------------------------------------------------
    // Phase 4.5 — tracking_savings_goals tests
    // ---------------------------------------------------------------

    #[test]
    fn savings_add_then_list_round_trips_all_fields() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "Vacanza",
                1_000_000,
                250_000,
                "cyan",
                Some(1_800_000_000),
                Some("wallet-abc"),
                Some(30),
                "manual",
                None,
                "savings",
            )
            .unwrap();
        let goals = r.list_savings_goals().unwrap();
        assert_eq!(goals.len(), 1);
        let g = &goals[0];
        assert_eq!(g.id, id);
        assert_eq!(g.name, "Vacanza");
        assert_eq!(g.target_sat, 1_000_000);
        assert_eq!(g.current_sat, 250_000);
        assert_eq!(g.color, "cyan");
        assert_eq!(g.deadline_unix, Some(1_800_000_000));
        assert_eq!(g.linked_wallet_id.as_deref(), Some("wallet-abc"));
        assert_eq!(g.target_pct_of_net_worth, Some(30));
        assert_eq!(g.bucket_kind, "manual");
        assert_eq!(g.bucket_ref, None);
    }

    #[test]
    fn savings_add_accepts_null_deadline_and_wallet() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_savings_goal(
            "Free-form",
            1,
            0,
            "orange",
            None,
            None,
            None,
            "manual",
            None,
            "savings",
        )
        .unwrap();
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.deadline_unix, None);
        assert_eq!(g.linked_wallet_id, None);
        assert_eq!(g.target_pct_of_net_worth, None);
        assert_eq!(g.bucket_kind, "manual");
        assert_eq!(g.bucket_ref, None);
    }

    #[test]
    fn savings_update_mutates_existing_row() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "A", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        assert!(
            r.update_savings_goal(
                id,
                "B",
                2,
                1,
                "red",
                Some(42),
                Some("w"),
                Some(75),
                "manual",
                None,
                "savings"
            )
            .unwrap()
        );
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.name, "B");
        assert_eq!(g.target_sat, 2);
        assert_eq!(g.current_sat, 1);
        assert_eq!(g.color, "red");
        assert_eq!(g.deadline_unix, Some(42));
        assert_eq!(g.linked_wallet_id.as_deref(), Some("w"));
        assert_eq!(g.target_pct_of_net_worth, Some(75));
    }

    #[test]
    fn savings_update_returns_false_for_missing_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(
            !r.update_savings_goal(
                9999, "X", 1, 0, "cyan", None, None, None, "manual", None, "savings"
            )
            .unwrap()
        );
    }

    #[test]
    fn savings_update_can_clear_pct_back_to_none() {
        // Phase 7.2.1 — pin the contract that the allocation %
        // becomes null when an update passes None; otherwise a user
        // who cleared the field in the UI would still see the old
        // value persist.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "Casa",
                100,
                10,
                "cyan",
                None,
                None,
                Some(40),
                "manual",
                None,
                "savings",
            )
            .unwrap();
        assert_eq!(
            r.list_savings_goals().unwrap()[0].target_pct_of_net_worth,
            Some(40)
        );
        assert!(
            r.update_savings_goal(
                id, "Casa", 100, 10, "cyan", None, None, None, "manual", None, "savings"
            )
            .unwrap()
        );
        assert_eq!(
            r.list_savings_goals().unwrap()[0].target_pct_of_net_worth,
            None
        );
    }

    #[test]
    fn savings_delete_returns_true_then_false() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "Tmp", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        assert!(r.delete_savings_goal(id).unwrap());
        assert!(!r.delete_savings_goal(id).unwrap());
        assert!(r.list_savings_goals().unwrap().is_empty());
    }

    // ---------------------------------------------------------------
    // Phase 7.2.4 — bucket_kind / bucket_ref
    // ---------------------------------------------------------------

    #[test]
    fn savings_add_with_investments_bucket_persists_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_savings_goal(
            "Investimenti",
            10_000_000,
            0,
            "purple",
            None,
            None,
            Some(40),
            "investments",
            None,
            "savings",
        )
        .unwrap();
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.bucket_kind, "investments");
        assert_eq!(g.bucket_ref, None);
    }

    #[test]
    fn savings_add_with_wallet_bucket_persists_ref() {
        // The `wallet` bucket needs a non-null `bucket_ref` to be
        // useful at the UI layer, but the registry doesn't enforce it
        // — invalid refs (e.g. a wallet id that no longer exists)
        // surface as "0 sat" in the UI, not an error here.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_savings_goal(
            "Fondo emergenza",
            5_000_000,
            0,
            "yellow",
            None,
            None,
            None,
            "wallet",
            Some("wallet-uuid-123"),
            "savings",
        )
        .unwrap();
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.bucket_kind, "wallet");
        assert_eq!(g.bucket_ref.as_deref(), Some("wallet-uuid-123"));
    }

    #[test]
    fn savings_add_rejects_unknown_bucket_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_savings_goal(
                "X",
                1,
                0,
                "cyan",
                None,
                None,
                None,
                "not_a_bucket",
                None,
                "savings",
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn savings_update_can_switch_bucket_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "X", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        assert!(
            r.update_savings_goal(
                id,
                "X",
                1,
                0,
                "cyan",
                None,
                None,
                None,
                "bitcoin_cash",
                None,
                "savings",
            )
            .unwrap()
        );
        assert_eq!(
            r.list_savings_goals().unwrap()[0].bucket_kind,
            "bitcoin_cash"
        );
    }

    // ---------------------------------------------------------------
    // Phase 7.7 — goal_kind (savings / sinking) tests
    // ---------------------------------------------------------------

    #[test]
    fn savings_add_sinking_requires_deadline() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        // No deadline → reject
        assert!(matches!(
            r.add_savings_goal(
                "Vacanza", 1_000_000, 0, "cyan", None, // deadline_unix
                None, None, "manual", None, "sinking",
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
        // With deadline → ok
        r.add_savings_goal(
            "Vacanza",
            1_000_000,
            0,
            "cyan",
            Some(1_800_000_000),
            None,
            None,
            "manual",
            None,
            "sinking",
        )
        .unwrap();
        assert_eq!(r.list_savings_goals().unwrap()[0].goal_kind, "sinking");
    }

    #[test]
    fn savings_add_savings_kind_works_without_deadline() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_savings_goal(
            "Open-ended",
            1_000_000,
            0,
            "cyan",
            None,
            None,
            None,
            "manual",
            None,
            "savings",
        )
        .unwrap();
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.goal_kind, "savings");
        assert_eq!(g.deadline_unix, None);
    }

    #[test]
    fn savings_reject_unknown_goal_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_savings_goal(
                "X",
                1,
                0,
                "cyan",
                None,
                None,
                None,
                "manual",
                None,
                "future_kind",
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn savings_update_to_sinking_requires_deadline() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "X", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        // Switch to sinking without deadline → reject
        assert!(matches!(
            r.update_savings_goal(
                id, "X", 1, 0, "cyan", None, None, None, "manual", None, "sinking",
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
        // With deadline → ok
        assert!(
            r.update_savings_goal(
                id,
                "X",
                1,
                0,
                "cyan",
                Some(1_800_000_000),
                None,
                None,
                "manual",
                None,
                "sinking",
            )
            .unwrap()
        );
        assert_eq!(r.list_savings_goals().unwrap()[0].goal_kind, "sinking");
    }

    #[test]
    fn schema_version_is_stamped_and_gate_is_idempotent() {
        let dir = TempDir::new().unwrap();
        {
            // Fresh open: ensure_schema runs the baseline and stamps the
            // version into the DB header.
            let r = open_fresh_registry(&dir);
            let v: i64 = r
                .conn
                .query_row("PRAGMA user_version", [], |row| row.get(0))
                .unwrap();
            assert_eq!(
                v, SCHEMA_VERSION,
                "fresh open must stamp the schema version"
            );
        }
        // Re-open: the version gate must short-circuit (no error from the
        // ALTER block at all) and leave the version untouched.
        let r2 = open_fresh_registry(&dir);
        let v2: i64 = r2
            .conn
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .unwrap();
        assert_eq!(v2, SCHEMA_VERSION);
        // The DB stays fully usable after the gated open.
        r2.set_budget("Cibo", 1, "monthly").unwrap();
        assert_eq!(r2.list_budgets().unwrap().len(), 1);
    }

    #[test]
    fn v5_registry_gains_details_columns_and_keeps_its_rows() {
        let dir = TempDir::new().unwrap();
        let id = {
            let r = open_fresh_registry(&dir);
            let id = r.add_liability("Mutuo", 100, "mortgage", None).unwrap();
            for t in [
                "tracking_investments",
                "tracking_manual_assets",
                "tracking_liabilities",
            ] {
                r.conn
                    .execute(&format!("ALTER TABLE {t} DROP COLUMN details_json"), [])
                    .unwrap();
            }
            r.conn.execute("PRAGMA user_version = 5", []).unwrap();
            id
        };
        let r = open_fresh_registry(&dir);
        let v: i64 = r
            .conn
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .unwrap();
        assert_eq!(v, SCHEMA_VERSION);
        assert_eq!(r.list_liabilities().unwrap()[0].name, "Mutuo");
        assert!(r.list_details("liability").unwrap().is_empty());
        assert!(r.set_details("liability", id, r#"{"rate_pct":3}"#).unwrap());
    }

    #[test]
    fn schema_version_reconciles_a_legacy_unstamped_db() {
        // Simulate a DB migrated by the pre-R1 code: full schema present
        // but user_version still 0. ensure_schema must reconcile it to
        // SCHEMA_VERSION without replaying ALTERs destructively.
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.add_savings_goal(
                "Pre", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
            // Force the header back to the legacy (unstamped) state.
            r.conn.execute("PRAGMA user_version = 0", []).unwrap();
        }
        let r2 = open_fresh_registry(&dir);
        let v: i64 = r2
            .conn
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .unwrap();
        assert_eq!(
            v, SCHEMA_VERSION,
            "legacy DB must be reconciled to current version"
        );
        // Data survived and is readable.
        assert_eq!(r2.list_savings_goals().unwrap()[0].goal_kind, "savings");
    }

    #[test]
    fn savings_migration_adds_goal_kind_column_idempotent() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            // Insert a goal under the legacy schema path — the
            // migration must have already added `goal_kind` with the
            // default `'savings'`.
            r.add_savings_goal(
                "Pre", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        }
        // Re-open — ALTER must be idempotent, no "duplicate column".
        let r2 = open_fresh_registry(&dir);
        let g = &r2.list_savings_goals().unwrap()[0];
        assert_eq!(g.goal_kind, "savings");
    }

    // ---------------------------------------------------------------
    // Phase 8.6 — goal denomination tests
    // ---------------------------------------------------------------

    #[test]
    fn migration_adds_3_goal_denomination_columns_idempotent() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.add_savings_goal(
                "Pre", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        }
        // Re-open — 3 new ALTERs must be idempotent, no "duplicate column".
        let r2 = open_fresh_registry(&dir);
        let g = &r2.list_savings_goals().unwrap()[0];
        // Default value applied.
        assert_eq!(g.target_denomination, "sat");
        assert_eq!(g.target_fiat_amount, None);
        assert_eq!(g.target_fiat_currency, None);
    }

    #[test]
    fn set_savings_goal_denomination_sat_clears_fiat_fields() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "Casa", 5_000_000, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        // First set to fiat.
        assert!(
            r.set_savings_goal_denomination(id, "fiat", Some(50_000.0), Some("EUR"))
                .unwrap()
        );
        // Then switch back to sat — fiat fields must be NULLed.
        assert!(
            r.set_savings_goal_denomination(id, "sat", None, None)
                .unwrap()
        );
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.target_denomination, "sat");
        assert_eq!(g.target_fiat_amount, None);
        assert_eq!(g.target_fiat_currency, None);
    }

    #[test]
    fn set_savings_goal_denomination_fiat_persists_amount_and_currency() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "Vacanza", 0, 0, "orange", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        assert!(
            r.set_savings_goal_denomination(id, "fiat", Some(10_000.0), Some("USD"))
                .unwrap()
        );
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.target_denomination, "fiat");
        assert_eq!(g.target_fiat_amount, Some(10_000.0));
        assert_eq!(g.target_fiat_currency.as_deref(), Some("USD"));
    }

    #[test]
    fn set_savings_goal_denomination_rejects_unknown_value() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "X", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        assert!(matches!(
            r.set_savings_goal_denomination(id, "btc", None, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn list_savings_goals_returns_new_fields() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "Test", 1_000, 500, "green", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        r.set_savings_goal_denomination(id, "fiat", Some(999.99), Some("GBP"))
            .unwrap();
        let goals = r.list_savings_goals().unwrap();
        assert_eq!(goals.len(), 1);
        let g = &goals[0];
        assert_eq!(g.id, id);
        assert_eq!(g.target_denomination, "fiat");
        assert_eq!(g.target_fiat_amount, Some(999.99));
        assert_eq!(g.target_fiat_currency.as_deref(), Some("GBP"));
    }

    // ---------------------------------------------------------------
    // Phase 7.4 — tracking_manual_assets tests
    // ---------------------------------------------------------------

    #[test]
    fn manual_assets_add_then_list_round_trips_all_fields() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_manual_asset(
                "Main residence",
                "real_estate",
                250_000.0,
                "EUR",
                Some("Primary residence"),
                Some("2-bed, bought 2015"),
                None,
                None,
                None,
            )
            .unwrap();
        let assets = r.list_manual_assets().unwrap();
        assert_eq!(assets.len(), 1);
        let a = &assets[0];
        assert_eq!(a.id, id);
        assert_eq!(a.name, "Main residence");
        assert_eq!(a.kind, "real_estate");
        assert_eq!(a.value_fiat, 250_000.0);
        assert_eq!(a.currency, "EUR");
        assert_eq!(a.display_label.as_deref(), Some("Primary residence"));
        assert_eq!(a.notes.as_deref(), Some("2-bed, bought 2015"));
        assert!(a.last_updated_unix > 0);
        assert!(a.created_at_unix > 0);
    }

    #[test]
    fn manual_assets_accept_each_valid_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for k in [
            "cash",
            "brokerage",
            "real_estate",
            "vehicle",
            "collectible",
            "business_equity",
            "loans_to_others",
            "other",
        ] {
            r.add_manual_asset(k, k, 1.0, "EUR", None, None, None, None, None)
                .unwrap();
        }
        assert_eq!(r.list_manual_assets().unwrap().len(), 8);
    }

    #[test]
    fn manual_assets_reject_unknown_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_manual_asset("X", "not_a_kind", 1.0, "EUR", None, None, None, None, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn manual_assets_reject_non_finite_value() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_manual_asset("X", "cash", f64::NAN, "EUR", None, None, None, None, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_manual_asset(
                "X",
                "cash",
                f64::INFINITY,
                "EUR",
                None,
                None,
                None,
                None,
                None
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn manual_assets_update_persists_field_changes() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_manual_asset("Cash", "cash", 1_000.0, "EUR", None, None, None, None, None)
            .unwrap();
        assert!(
            r.update_manual_asset(
                id,
                "Cash USD",
                "cash",
                500.0,
                "USD",
                Some("Bank A"),
                Some("Sub-account"),
                None,
                None,
                None,
            )
            .unwrap()
        );
        let a = &r.list_manual_assets().unwrap()[0];
        assert_eq!(a.name, "Cash USD");
        assert_eq!(a.value_fiat, 500.0);
        assert_eq!(a.currency, "USD");
        assert_eq!(a.display_label.as_deref(), Some("Bank A"));
        assert_eq!(a.notes.as_deref(), Some("Sub-account"));
    }

    #[test]
    fn manual_assets_update_returns_false_for_missing_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(
            !r.update_manual_asset(9999, "X", "cash", 1.0, "EUR", None, None, None, None, None)
                .unwrap()
        );
    }

    #[test]
    fn manual_assets_delete_returns_true_then_false() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_manual_asset("Tmp", "cash", 1.0, "EUR", None, None, None, None, None)
            .unwrap();
        assert!(r.delete_manual_asset(id).unwrap());
        assert!(!r.delete_manual_asset(id).unwrap());
        assert!(r.list_manual_assets().unwrap().is_empty());
    }

    #[test]
    fn savings_add_with_manual_asset_bucket_persists() {
        // Phase 7.4 — goal binds to a single manual asset via
        // bucket_ref = asset id (stringified). The registry doesn't
        // enforce ref existence — UI does the validation; a stale
        // ref surfaces as "Asset (missing)" rather than a hard error.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let asset_id = r
            .add_manual_asset(
                "Checking account",
                "cash",
                5_000.0,
                "EUR",
                None,
                None,
                None,
                None,
                None,
            )
            .unwrap();
        r.add_savings_goal(
            "Fondo emergenza",
            10_000_000,
            0,
            "yellow",
            None,
            None,
            None,
            "manual_asset",
            Some(&asset_id.to_string()),
            "savings",
        )
        .unwrap();
        let g = &r.list_savings_goals().unwrap()[0];
        assert_eq!(g.bucket_kind, "manual_asset");
        assert_eq!(g.bucket_ref.as_deref(), Some(asset_id.to_string().as_str()));
    }

    // ---------------------------------------------------------------
    // Phase 7.5 — tracking_rules tests
    // ---------------------------------------------------------------

    #[test]
    fn rules_add_then_list_round_trips_all_fields() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_rule(
                "Amazon → Shopping",
                "description",
                "contains",
                "AMZN",
                "category",
                "Shopping",
                5,
                true,
            )
            .unwrap();
        let rules = r.list_rules().unwrap();
        assert_eq!(rules.len(), 1);
        let row = &rules[0];
        assert_eq!(row.id, id);
        assert_eq!(row.name, "Amazon → Shopping");
        assert_eq!(row.condition_field, "description");
        assert_eq!(row.condition_op, "contains");
        assert_eq!(row.condition_value, "AMZN");
        assert_eq!(row.action_field, "category");
        assert_eq!(row.action_value, "Shopping");
        assert_eq!(row.priority, 5);
        assert!(row.enabled);
    }

    #[test]
    fn rules_accept_each_text_op_on_description() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for op in ["contains", "equals", "starts_with", "ends_with"] {
            r.add_rule(op, "description", op, "x", "category", "Cat", 0, true)
                .unwrap();
        }
        assert_eq!(r.list_rules().unwrap().len(), 4);
    }

    #[test]
    fn rules_accept_each_number_op_on_amount() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for op in ["gt", "lt", "eq"] {
            r.add_rule(op, "amount", op, "1000", "category", "Cat", 0, true)
                .unwrap();
        }
        assert_eq!(r.list_rules().unwrap().len(), 3);
    }

    #[test]
    fn rules_reject_invalid_field() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_rule(
                "X",
                "not_a_field",
                "contains",
                "v",
                "category",
                "C",
                0,
                true
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn rules_reject_text_op_on_amount() {
        // 'contains' is a text op; amount needs a number op.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_rule("X", "amount", "contains", "100", "category", "C", 0, true),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn rules_accept_directional_amount_fields_with_number_ops_only() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for field in ["amount_in", "amount_out"] {
            r.add_rule("X", field, "eq", "21000", "category", "C", 0, true)
                .unwrap();
            assert!(matches!(
                r.add_rule("X", field, "contains", "21000", "category", "C", 0, true),
                Err(OnchainError::InvalidArgument(_))
            ));
            assert!(matches!(
                r.add_rule("X", field, "eq", "lots", "category", "C", 0, true),
                Err(OnchainError::InvalidArgument(_))
            ));
        }
    }

    #[test]
    fn rules_reject_number_op_on_description() {
        // 'gt' is numeric; description needs a text op.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_rule("X", "description", "gt", "v", "category", "C", 0, true),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn rules_reject_unparseable_amount_value() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_rule(
                "X",
                "amount",
                "gt",
                "not_a_number",
                "category",
                "C",
                0,
                true
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn rules_list_orders_by_priority_desc_then_created_asc() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let a = r
            .add_rule(
                "a",
                "description",
                "contains",
                "a",
                "category",
                "A",
                0,
                true,
            )
            .unwrap();
        let b = r
            .add_rule(
                "b",
                "description",
                "contains",
                "b",
                "category",
                "B",
                5,
                true,
            )
            .unwrap();
        let c = r
            .add_rule(
                "c",
                "description",
                "contains",
                "c",
                "category",
                "C",
                5,
                true,
            )
            .unwrap();
        let ids: Vec<i64> = r.list_rules().unwrap().iter().map(|r| r.id).collect();
        // b (prio 5, earlier) → c (prio 5, later) → a (prio 0)
        assert_eq!(ids, vec![b, c, a]);
    }

    #[test]
    fn rules_update_persists_enabled_toggle() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_rule(
                "X",
                "description",
                "contains",
                "v",
                "category",
                "C",
                0,
                true,
            )
            .unwrap();
        assert!(
            r.update_rule(
                id,
                "X",
                "description",
                "contains",
                "v",
                "category",
                "C",
                0,
                false
            )
            .unwrap()
        );
        assert!(!r.list_rules().unwrap()[0].enabled);
    }

    fn rule_ids_in_order(r: &Registry) -> Vec<i64> {
        r.list_rules().unwrap().into_iter().map(|x| x.id).collect()
    }

    fn rule_priorities(r: &Registry) -> Vec<i64> {
        r.list_rules()
            .unwrap()
            .into_iter()
            .map(|x| x.priority)
            .collect()
    }

    #[test]
    fn reorder_rules_renumbers_every_priority_in_the_given_order() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let add = |name: &str, p: i64| {
            r.add_rule(name, "amount", "gt", "1", "category", "C", p, true)
                .unwrap()
        };
        // Ties (both 7) used to make the order depend on created_at.
        let a = add("a", 7);
        let b = add("b", 7);
        let c = add("c", 0);
        assert_eq!(rule_ids_in_order(&r), vec![a, b, c]);
        r.reorder_rules(&[c, a, b]).unwrap();
        assert_eq!(rule_ids_in_order(&r), vec![c, a, b]);
        assert_eq!(rule_priorities(&r), vec![2, 1, 0]);
        // Applying the same order twice is a no-op, not an undo.
        r.reorder_rules(&[c, a, b]).unwrap();
        assert_eq!(rule_ids_in_order(&r), vec![c, a, b]);
    }

    #[test]
    fn reorder_rules_refuses_a_stale_order_and_writes_nothing() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let add = |name: &str, p: i64| {
            r.add_rule(name, "amount", "gt", "1", "category", "C", p, true)
                .unwrap()
        };
        let a = add("a", 30);
        let b = add("b", 20);
        let c = add("c", 10);
        let before = rule_priorities(&r);
        for bad in [
            vec![b, a],         // a rule missing (added since the snapshot)
            vec![b, a, c, 999], // an unknown id (deleted meanwhile)
            vec![b, b, c],      // a duplicate
        ] {
            assert!(matches!(
                r.reorder_rules(&bad),
                Err(OnchainError::InvalidArgument(_))
            ));
            assert_eq!(rule_priorities(&r), before);
            assert_eq!(rule_ids_in_order(&r), vec![a, b, c]);
        }
    }

    #[test]
    fn rules_delete_returns_true_then_false() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_rule(
                "X",
                "description",
                "contains",
                "v",
                "category",
                "C",
                0,
                true,
            )
            .unwrap();
        assert!(r.delete_rule(id).unwrap());
        assert!(!r.delete_rule(id).unwrap());
    }

    // ---------------------------------------------------------------
    // Phase 7.5 — dismissed recurring tests
    // ---------------------------------------------------------------

    #[test]
    fn recurring_dismiss_persists_and_is_idempotent() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.dismiss_recurring("netflix").unwrap();
        r.dismiss_recurring("netflix").unwrap(); // no-op
        let list = r.list_dismissed_recurring().unwrap();
        assert_eq!(list, vec!["netflix".to_string()]);
    }

    #[test]
    fn recurring_undismiss_removes_row() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.dismiss_recurring("netflix").unwrap();
        assert!(r.undismiss_recurring("netflix").unwrap());
        assert!(r.list_dismissed_recurring().unwrap().is_empty());
        assert!(!r.undismiss_recurring("netflix").unwrap());
    }

    #[test]
    fn savings_migration_is_idempotent_across_opens() {
        // Two consecutive opens must each succeed even though the
        // ALTER TABLE on the second open would otherwise raise
        // "duplicate column name". Mirrors the equivalent test for
        // investments — same migration pattern, two new columns.
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.add_savings_goal(
                "G", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        }
        let r2 = open_fresh_registry(&dir);
        assert_eq!(r2.list_savings_goals().unwrap().len(), 1);
        // And the new columns are readable.
        let g = &r2.list_savings_goals().unwrap()[0];
        assert_eq!(g.bucket_kind, "manual");
        assert_eq!(g.bucket_ref, None);
    }

    // ---------------------------------------------------------------
    // Phase 4.6 — tracking_liabilities tests
    // ---------------------------------------------------------------

    #[test]
    fn liabilities_add_and_list_round_trips_each_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_liability("Mortgage", 12_000_000, "mortgage", None)
            .unwrap();
        r.add_liability("Car loan", 3_000_000, "loan", Some("€10,000"))
            .unwrap();
        r.add_liability("Visa", 500_000, "credit-card", None)
            .unwrap();
        r.add_liability("Misc", 100_000, "other", None).unwrap();
        let rows = r.list_liabilities().unwrap();
        assert_eq!(rows.len(), 4);
        let kinds: Vec<&str> = rows.iter().map(|l| l.kind.as_str()).collect();
        // Ordered by created_at_unix asc, so insertion order.
        assert_eq!(kinds, vec!["mortgage", "loan", "credit-card", "other"]);
        assert_eq!(rows[1].display_label.as_deref(), Some("€10,000"));
    }

    #[test]
    fn liabilities_update_persists_kind_change() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r.add_liability("X", 100, "other", None).unwrap();
        assert!(
            r.update_liability(id, "X v2", 200, "credit-card", Some("Visa"))
                .unwrap()
        );
        let l = &r.list_liabilities().unwrap()[0];
        assert_eq!(l.name, "X v2");
        assert_eq!(l.amount_sat, 200);
        assert_eq!(l.kind, "credit-card");
        assert_eq!(l.display_label.as_deref(), Some("Visa"));
    }

    #[test]
    fn liabilities_update_returns_false_for_missing_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(!r.update_liability(9999, "X", 1, "other", None).unwrap());
    }

    #[test]
    fn liability_kind_is_validated_on_add_and_update() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_liability("Boat", 1, "boat", None),
            Err(OnchainError::InvalidArgument(_))
        ));
        let id = r.add_liability("X", 1, "loan", None).unwrap();
        assert!(matches!(
            r.update_liability(id, "X", 1, "Credit-card", None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert_eq!(r.list_liabilities().unwrap()[0].kind, "loan");
    }

    #[test]
    fn details_round_trip_on_every_portfolio_table() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let liab = r.add_liability("Mutuo", 100, "mortgage", None).unwrap();
        let asset = r
            .add_manual_asset(
                "Casa",
                "real_estate",
                300_000.0,
                "EUR",
                None,
                None,
                None,
                None,
                None,
            )
            .unwrap();
        let inv = r
            .add_investment(
                "VWCE",
                "etf",
                Some("VWCE.DE"),
                10.0,
                "shares",
                1000.0,
                "EUR",
                0,
                None,
                None,
            )
            .unwrap();
        assert!(r.list_details("liability").unwrap().is_empty());

        assert!(
            r.set_details(
                "liability",
                liab,
                r#"{"rate_pct":2.9,"linked_asset_id":"7"}"#
            )
            .unwrap()
        );
        assert!(
            r.set_details(
                "manual_asset",
                asset,
                r#"{"city":"Milano","rented":false,"note":"  "}"#
            )
            .unwrap()
        );
        assert!(
            r.set_details("investment", inv, r#"{"ter_pct":0.22}"#)
                .unwrap()
        );
        assert_eq!(
            r.list_details("liability").unwrap(),
            vec![(
                liab,
                r#"{"linked_asset_id":"7","rate_pct":2.9}"#.to_string()
            )]
        );
        // Blank strings are dropped.
        assert_eq!(
            r.list_details("manual_asset").unwrap()[0].1,
            r#"{"city":"Milano","rented":false}"#
        );

        // Editing the row keeps its details; `{}` clears them.
        r.update_liability(liab, "Mutuo casa", 90, "mortgage", None)
            .unwrap();
        assert_eq!(r.list_details("liability").unwrap().len(), 1);
        assert!(r.set_details("investment", inv, "{}").unwrap());
        assert!(r.list_details("investment").unwrap().is_empty());
        // Unknown id: nothing written.
        assert!(!r.set_details("investment", 9_999, "{}").unwrap());
    }

    #[test]
    fn details_reject_unknown_tables_and_malformed_objects() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r.add_liability("X", 1, "loan", None).unwrap();
        let invalid = |table: &str, json: &str| {
            matches!(
                r.set_details(table, id, json),
                Err(OnchainError::InvalidArgument(_))
            )
        };
        assert!(invalid("wallets", "{}"));
        assert!(invalid("liability", "[1,2]"));
        assert!(invalid("liability", "not json"));
        assert!(invalid("liability", r#"{"Rate":1}"#));
        assert!(invalid("liability", r#"{"nested":{"a":1}}"#));
        assert!(invalid("liability", r#"{"list":[1]}"#));
        assert!(invalid("liability", r#"{"none":null}"#));
        let long = "x".repeat(MAX_DETAILS_VALUE_LEN + 1);
        assert!(invalid("liability", &format!(r#"{{"note":"{long}"}}"#)));
        let many: Vec<String> = (0..=MAX_DETAILS_KEYS)
            .map(|i| format!(r#""k{i}":1"#))
            .collect();
        assert!(invalid("liability", &format!("{{{}}}", many.join(","))));
        assert!(r.list_details("liability").unwrap().is_empty());
    }

    #[test]
    fn liabilities_delete_returns_true_then_false() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r.add_liability("Tmp", 1, "other", None).unwrap();
        assert!(r.delete_liability(id).unwrap());
        assert!(!r.delete_liability(id).unwrap());
    }

    // ---------------------------------------------------------------
    // Phase 5.1 — tracking_investments tests
    // ---------------------------------------------------------------

    #[test]
    fn investments_add_round_trips_all_fields() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment(
                "Apple",
                "stock",
                Some("AAPL"),
                100.0,
                "shares",
                15_000.0,
                "USD",
                1_700_000_000,
                Some(19_000.0),
                Some("Held at broker"),
            )
            .unwrap();
        let invs = r.list_investments().unwrap();
        assert_eq!(invs.len(), 1);
        let i = &invs[0];
        assert_eq!(i.id, id);
        assert_eq!(i.name, "Apple");
        assert_eq!(i.asset_type, "stock");
        assert_eq!(i.ticker.as_deref(), Some("AAPL"));
        assert!((i.quantity - 100.0).abs() < 1e-9);
        assert_eq!(i.unit, "shares");
        assert!((i.cost_basis_fiat - 15_000.0).abs() < 1e-9);
        assert_eq!(i.cost_basis_currency, "USD");
        assert_eq!(i.purchase_unix, 1_700_000_000);
        assert_eq!(i.manual_current_value, Some(19_000.0));
        // No fetched price yet.
        assert_eq!(i.last_priced_at_unix, None);
        assert_eq!(i.notes.as_deref(), Some("Held at broker"));
    }

    #[test]
    fn investments_round_trips_each_asset_type() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let types = ["stock", "etf", "fund", "currency", "commodity", "manual"];
        for t in types {
            r.add_investment(t, t, None, 1.0, "", 1.0, "EUR", 0, None, None)
                .unwrap();
        }
        let invs = r.list_investments().unwrap();
        let got: Vec<&str> = invs.iter().map(|i| i.asset_type.as_str()).collect();
        assert_eq!(got, types.to_vec());
    }

    #[test]
    fn investments_accepts_null_ticker_for_manual_position() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_investment(
            "Physical gold",
            "commodity",
            None,
            50.0,
            "g",
            3_000.0,
            "EUR",
            0,
            Some(3_500.0),
            None,
        )
        .unwrap();
        let i = &r.list_investments().unwrap()[0];
        assert_eq!(i.ticker, None);
        assert!((i.quantity - 50.0).abs() < 1e-9);
    }

    #[test]
    fn investments_accepts_fractional_quantity() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_investment(
            "BTC ETF",
            "etf",
            Some("IBIT"),
            0.123_456_78,
            "shares",
            100.0,
            "USD",
            0,
            None,
            None,
        )
        .unwrap();
        let i = &r.list_investments().unwrap()[0];
        assert!((i.quantity - 0.123_456_78).abs() < 1e-9);
    }

    #[test]
    fn investments_update_changes_fields_in_place() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment(
                "Apple",
                "stock",
                Some("AAPL"),
                10.0,
                "shares",
                1_500.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        assert!(
            r.update_investment(
                id,
                "Apple Inc",
                "stock",
                Some("AAPL"),
                20.0,
                "shares",
                3_000.0,
                "EUR",
                1,
                Some(3_800.0),
                Some("doubled position"),
            )
            .unwrap()
        );
        let i = &r.list_investments().unwrap()[0];
        assert_eq!(i.name, "Apple Inc");
        assert!((i.quantity - 20.0).abs() < 1e-9);
        assert_eq!(i.cost_basis_currency, "EUR");
        assert_eq!(i.manual_current_value, Some(3_800.0));
        assert_eq!(i.notes.as_deref(), Some("doubled position"));
    }

    #[test]
    fn investments_update_returns_false_for_missing_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(
            !r.update_investment(
                9999, "X", "manual", None, 1.0, "", 1.0, "EUR", 0, None, None
            )
            .unwrap()
        );
    }

    #[test]
    fn investments_set_fetched_price_writes_value_and_timestamp() {
        // T2-10: fetched_current_value is now a distinct column.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment(
                "Apple",
                "stock",
                Some("AAPL"),
                100.0,
                "shares",
                15_000.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        // Before: both null.
        let before = &r.list_investments().unwrap()[0];
        assert_eq!(before.manual_current_value, None);
        assert_eq!(before.fetched_current_value, None);
        assert_eq!(before.last_priced_at_unix, None);
        // Set.
        assert!(
            r.set_investment_fetched_price(id, 18_500.0, 1_700_000_111)
                .unwrap()
        );
        let after = &r.list_investments().unwrap()[0];
        // Fetched written, manual untouched.
        assert_eq!(after.fetched_current_value, Some(18_500.0));
        assert_eq!(after.manual_current_value, None);
        assert_eq!(after.last_priced_at_unix, Some(1_700_000_111));
    }

    #[test]
    fn investments_set_fetched_price_returns_false_for_missing_id() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(!r.set_investment_fetched_price(9999, 100.0, 1).unwrap());
    }

    #[test]
    fn investments_set_fetched_price_preserves_previous_manual_value() {
        // T2-10 contract change: the fetcher MUST NOT overwrite the
        // user's manual value. Pre-T2-10 the manual column was
        // clobbered with the fetched price — the bug this test pins
        // is now the bug-fix.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        // User entered 1000, then auto-fetch retrieves 1200.
        let id = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                500.0,
                "USD",
                0,
                Some(1000.0),
                None,
            )
            .unwrap();
        r.set_investment_fetched_price(id, 1200.0, 1_700_000_222)
            .unwrap();
        let i = &r.list_investments().unwrap()[0];
        // Manual entry survives untouched.
        assert_eq!(i.manual_current_value, Some(1000.0));
        // Fetched value visible in its own column.
        assert_eq!(i.fetched_current_value, Some(1200.0));
    }

    #[test]
    fn investments_delete_returns_true_then_false() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment("X", "stock", Some("X"), 1.0, "", 1.0, "USD", 0, None, None)
            .unwrap();
        assert!(r.delete_investment(id).unwrap());
        assert!(!r.delete_investment(id).unwrap());
        assert!(r.list_investments().unwrap().is_empty());
    }

    #[test]
    fn investments_list_ordered_by_created_at_asc() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for n in ["Alpha", "Beta", "Gamma"] {
            r.add_investment(n, "stock", Some(n), 1.0, "", 1.0, "USD", 0, None, None)
                .unwrap();
        }
        let names: Vec<String> = r
            .list_investments()
            .unwrap()
            .into_iter()
            .map(|i| i.name)
            .collect();
        assert_eq!(names, vec!["Alpha", "Beta", "Gamma"]);
    }

    // ---------------------------------------------------------------
    // Phase 5.3 — input validation (T0-3 + T0-4)
    // ---------------------------------------------------------------

    #[test]
    fn bill_due_day_zero_rejected() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let err = r.add_bill("X", 1, 0, None).unwrap_err();
        assert!(
            matches!(err, OnchainError::InvalidArgument(_)),
            "expected InvalidArgument, got: {err:?}"
        );
    }

    #[test]
    fn bill_due_day_above_31_rejected() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.add_bill("X", 1, 32, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_bill("X", 1, 999, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn bill_due_day_1_and_31_accepted() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_bill("First", 1, 1, None).unwrap();
        r.add_bill("Last", 1, 31, None).unwrap();
        assert_eq!(r.list_bills().unwrap().len(), 2);
    }

    #[test]
    fn bill_update_rejects_invalid_due_day() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r.add_bill("X", 1, 5, None).unwrap();
        assert!(matches!(
            r.update_bill(id, "X", 1, 0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.update_bill(id, "X", 1, 32, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        // Original row untouched.
        assert_eq!(r.list_bills().unwrap()[0].due_day, 5);
    }

    #[test]
    fn investment_asset_type_outside_whitelist_rejected() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for bad in ["", "bond", "crypto", "STOCK", "stock ", " stock"] {
            let err = r.add_investment("X", bad, None, 1.0, "", 1.0, "USD", 0, None, None);
            assert!(
                matches!(err, Err(OnchainError::InvalidArgument(_))),
                "expected InvalidArgument for asset_type {bad:?}, got: {err:?}",
            );
        }
    }

    #[test]
    fn investment_asset_type_whitelist_all_accepted() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for good in ["stock", "etf", "fund", "currency", "commodity", "manual"] {
            r.add_investment(good, good, None, 1.0, "", 1.0, "EUR", 0, None, None)
                .unwrap();
        }
        assert_eq!(r.list_investments().unwrap().len(), 6);
    }

    #[test]
    fn investment_update_rejects_invalid_asset_type() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment("X", "stock", None, 1.0, "", 1.0, "USD", 0, None, None)
            .unwrap();
        let err = r.update_investment(id, "X", "BOND", None, 1.0, "", 1.0, "USD", 0, None, None);
        assert!(matches!(err, Err(OnchainError::InvalidArgument(_))));
    }

    #[test]
    fn investment_rejects_nan_quantity() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let err = r.add_investment("X", "stock", None, f64::NAN, "", 1.0, "USD", 0, None, None);
        assert!(matches!(err, Err(OnchainError::InvalidArgument(_))));
    }

    #[test]
    fn investment_rejects_infinite_cost_basis() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let err = r.add_investment(
            "X",
            "stock",
            None,
            1.0,
            "",
            f64::INFINITY,
            "USD",
            0,
            None,
            None,
        );
        assert!(matches!(err, Err(OnchainError::InvalidArgument(_))));
        let err = r.add_investment(
            "X",
            "stock",
            None,
            1.0,
            "",
            f64::NEG_INFINITY,
            "USD",
            0,
            None,
            None,
        );
        assert!(matches!(err, Err(OnchainError::InvalidArgument(_))));
    }

    #[test]
    fn investment_rejects_nan_manual_current_value() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let err = r.add_investment(
            "X",
            "stock",
            None,
            1.0,
            "",
            100.0,
            "USD",
            0,
            Some(f64::NAN),
            None,
        );
        assert!(matches!(err, Err(OnchainError::InvalidArgument(_))));
    }

    #[test]
    fn investment_accepts_zero_but_rejects_negative_amounts() {
        // Zero quantity / cost basis is legal (multi-lot positions zero
        // the aggregate). Negative quantity or cost basis is rejected
        // since the 2026-10 audit (L4) — it corrupted P&L.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.add_investment(
            "Zero",
            "stock",
            None,
            0.0,
            "",
            0.0,
            "USD",
            0,
            Some(0.0),
            None,
        )
        .unwrap();
        assert!(matches!(
            r.add_investment("Neg", "stock", None, -1.5, "", 100.0, "USD", 0, None, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_investment("Neg", "stock", None, 1.5, "", -100.0, "USD", 0, None, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert_eq!(r.list_investments().unwrap().len(), 1);
    }

    #[test]
    fn set_investment_fetched_price_rejects_nan() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                100.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        let err = r.set_investment_fetched_price(id, f64::NAN, 1);
        assert!(matches!(err, Err(OnchainError::InvalidArgument(_))));
        // Original row untouched: manual_current_value still None.
        assert_eq!(r.list_investments().unwrap()[0].manual_current_value, None);
    }

    // ---------------------------------------------------------------
    // T2-10 — separate fetched_current_value from manual_current_value
    // ---------------------------------------------------------------

    #[test]
    fn set_fetched_price_does_not_overwrite_manual_value() {
        // Regression for the Phase 5.2 footgun: the user enters a
        // manual current value of 1000; Yahoo later returns 1200.
        // Before T2-10, the fetcher overwrote the user's 1000 with
        // 1200. After T2-10, the manual value is preserved and the
        // fetched value lands in its own column.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                500.0,
                "USD",
                0,
                Some(1000.0),
                None,
            )
            .unwrap();
        // Pre-fetch: manual=1000, fetched=None.
        let before = &r.list_investments().unwrap()[0];
        assert_eq!(before.manual_current_value, Some(1000.0));
        assert_eq!(before.fetched_current_value, None);
        // Fetcher writes 1200.
        r.set_investment_fetched_price(id, 1200.0, 1_700_000_222)
            .unwrap();
        let after = &r.list_investments().unwrap()[0];
        // Manual value PRESERVED, fetched value populated.
        assert_eq!(after.manual_current_value, Some(1000.0));
        assert_eq!(after.fetched_current_value, Some(1200.0));
        assert_eq!(after.last_priced_at_unix, Some(1_700_000_222));
    }

    #[test]
    fn fetched_value_is_independent_across_multiple_calls() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                500.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        r.set_investment_fetched_price(id, 1200.0, 1_000_000)
            .unwrap();
        r.set_investment_fetched_price(id, 1250.0, 1_000_500)
            .unwrap();
        let row = &r.list_investments().unwrap()[0];
        // Latest fetched value wins; timestamp also updated.
        assert_eq!(row.fetched_current_value, Some(1250.0));
        assert_eq!(row.last_priced_at_unix, Some(1_000_500));
        // Manual still untouched.
        assert_eq!(row.manual_current_value, None);
    }

    #[test]
    fn schema_migration_is_idempotent_across_opens() {
        // Two consecutive opens must each succeed even though the
        // ALTER TABLE on the second open would otherwise raise
        // "duplicate column name". The migration code swallows that
        // specific error class.
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                500.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        }
        // Second open: must not error on the ALTER.
        let r2 = open_fresh_registry(&dir);
        assert_eq!(r2.list_investments().unwrap().len(), 1);
        // And the new column is readable.
        let row = &r2.list_investments().unwrap()[0];
        assert_eq!(row.fetched_current_value, None);
    }

    #[test]
    fn set_investment_fetched_price_rejects_infinity() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                100.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        assert!(matches!(
            r.set_investment_fetched_price(id, f64::INFINITY, 1),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.set_investment_fetched_price(id, f64::NEG_INFINITY, 1),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    // ---------------------------------------------------------------
    // Phase 7.8 — tracking_investment_lots tests
    // ---------------------------------------------------------------

    #[test]
    fn investment_lots_add_then_list_round_trips() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv_id = r
            .add_investment(
                "AAPL",
                "stock",
                Some("AAPL"),
                0.0,
                "",
                0.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        let lot_id = r
            .add_investment_lot(inv_id, 10.0, 150.0, 5.0, 1_700_000_000, Some("buy 1"))
            .unwrap();
        let lots = r.list_investment_lots(inv_id).unwrap();
        assert_eq!(lots.len(), 1);
        let l = &lots[0];
        assert_eq!(l.id, lot_id);
        assert_eq!(l.investment_id, inv_id);
        assert_eq!(l.quantity, 10.0);
        assert_eq!(l.price_per_unit, 150.0);
        assert_eq!(l.fees_fiat, 5.0);
        assert_eq!(l.purchase_unix, 1_700_000_000);
        assert_eq!(l.notes.as_deref(), Some("buy 1"));
    }

    #[test]
    fn investment_lots_list_ordered_by_purchase_asc() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 1.0, 100.0, 0.0, 2_000, None)
            .unwrap();
        r.add_investment_lot(inv, 1.0, 110.0, 0.0, 1_000, None)
            .unwrap();
        r.add_investment_lot(inv, 1.0, 120.0, 0.0, 3_000, None)
            .unwrap();
        let lots = r.list_investment_lots(inv).unwrap();
        let purchase_unixes: Vec<i64> = lots.iter().map(|l| l.purchase_unix).collect();
        assert_eq!(purchase_unixes, vec![1_000, 2_000, 3_000]);
    }

    #[test]
    fn investment_lots_reject_non_finite_quantity() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        assert!(matches!(
            r.add_investment_lot(inv, f64::NAN, 100.0, 0.0, 0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_investment_lot(inv, 1.0, f64::INFINITY, 0.0, 0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_investment_lot(inv, 1.0, 100.0, f64::NEG_INFINITY, 0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn investment_lots_update_persists_changes() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        let lot = r
            .add_investment_lot(inv, 1.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        assert!(
            r.update_investment_lot(lot, 2.0, 150.0, 3.0, 2_000, Some("updated"))
                .unwrap()
        );
        let l = &r.list_investment_lots(inv).unwrap()[0];
        assert_eq!(l.quantity, 2.0);
        assert_eq!(l.price_per_unit, 150.0);
        assert_eq!(l.fees_fiat, 3.0);
        assert_eq!(l.purchase_unix, 2_000);
        assert_eq!(l.notes.as_deref(), Some("updated"));
    }

    #[test]
    fn investment_lots_delete_returns_true_then_false() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        let lot = r.add_investment_lot(inv, 1.0, 100.0, 0.0, 0, None).unwrap();
        assert!(r.delete_investment_lot(lot).unwrap());
        assert!(!r.delete_investment_lot(lot).unwrap());
    }

    #[test]
    fn investment_lots_cascade_delete_on_investment_delete() {
        // FK ON DELETE CASCADE is enforced by the connection PRAGMA;
        // removing the parent investment must remove its lots.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 1.0, 100.0, 0.0, 0, None).unwrap();
        r.add_investment_lot(inv, 2.0, 110.0, 0.0, 0, None).unwrap();
        assert_eq!(r.list_investment_lots(inv).unwrap().len(), 2);
        r.delete_investment(inv).unwrap();
        assert_eq!(r.list_investment_lots(inv).unwrap().len(), 0);
    }

    #[test]
    fn investment_cost_basis_method_defaults_to_fifo() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                100.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        assert_eq!(
            r.get_investment_cost_basis_method(inv).unwrap().as_deref(),
            Some("fifo")
        );
        assert_eq!(r.list_investments().unwrap()[0].cost_basis_method, "fifo");
    }

    #[test]
    fn investment_cost_basis_method_set_persists() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                100.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        assert!(r.set_investment_cost_basis_method(inv, "hifo").unwrap());
        assert_eq!(
            r.get_investment_cost_basis_method(inv).unwrap().as_deref(),
            Some("hifo")
        );
        assert!(
            r.set_investment_cost_basis_method(inv, "specific_id")
                .unwrap()
        );
        assert_eq!(
            r.get_investment_cost_basis_method(inv).unwrap().as_deref(),
            Some("specific_id")
        );
    }

    #[test]
    fn investment_cost_basis_method_rejects_unknown() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment(
                "X",
                "stock",
                Some("X"),
                1.0,
                "",
                100.0,
                "USD",
                0,
                None,
                None,
            )
            .unwrap();
        assert!(matches!(
            r.set_investment_cost_basis_method(inv, "lifo"),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn investment_lots_migration_idempotent_across_opens() {
        // Re-opening must succeed (table CREATE IF NOT EXISTS + ALTER
        // duplicate-column swallow). Existing investment row stays
        // intact and cost_basis_method defaults to 'fifo'.
        let dir = TempDir::new().unwrap();
        let inv_id = {
            let r = open_fresh_registry(&dir);
            let inv = r
                .add_investment(
                    "X",
                    "stock",
                    Some("X"),
                    1.0,
                    "",
                    100.0,
                    "USD",
                    0,
                    None,
                    None,
                )
                .unwrap();
            r.add_investment_lot(inv, 1.0, 100.0, 0.0, 0, None).unwrap();
            inv
        };
        let r2 = open_fresh_registry(&dir);
        assert_eq!(r2.list_investment_lots(inv_id).unwrap().len(), 1);
        assert_eq!(r2.list_investments().unwrap()[0].cost_basis_method, "fifo");
    }

    #[test]
    fn investment_seed_lot_from_aggregate_atomic() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        // Legacy investment with aggregate cost basis.
        let inv = r
            .add_investment(
                "AAPL",
                "stock",
                Some("AAPL"),
                10.0,
                "",
                1500.0,
                "USD",
                1_000,
                None,
                None,
            )
            .unwrap();
        // Seed.
        let lot_id = r.seed_investment_lot_from_aggregate(inv).unwrap();
        // Lot has the right shape.
        let lots = r.list_investment_lots(inv).unwrap();
        assert_eq!(lots.len(), 1);
        assert_eq!(lots[0].id, lot_id);
        assert_eq!(lots[0].quantity, 10.0);
        assert_eq!(lots[0].price_per_unit, 150.0);
        // Aggregate fields zeroed.
        let invs = r.list_investments().unwrap();
        assert_eq!(invs[0].quantity, 0.0);
        assert_eq!(invs[0].cost_basis_fiat, 0.0);
    }

    #[test]
    fn investment_seed_lot_rejects_zero_aggregate() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        assert!(matches!(
            r.seed_investment_lot_from_aggregate(inv),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    // ---------------------------------------------------------------
    // Phase 7.9 — sells + lot_disposals tests
    // ---------------------------------------------------------------

    #[test]
    fn sells_record_consumes_lots_in_fifo_order() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        // Three lots, oldest first by purchase_unix.
        r.add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        let lot_mid = r
            .add_investment_lot(inv, 5.0, 200.0, 0.0, 2_000, None)
            .unwrap();
        r.add_investment_lot(inv, 3.0, 150.0, 0.0, 3_000, None)
            .unwrap();
        // Sell 12 @ 250 with FIFO default.
        let sell_id = r.record_sell(inv, 4_000, 250.0, 12.0, 0.0, None).unwrap();
        // FIFO: consumes all 10 from the first lot + 2 from the mid lot.
        let disposals: Vec<_> = r
            .list_all_lot_disposals()
            .unwrap()
            .into_iter()
            .filter(|d| d.sell_id == sell_id)
            .collect();
        assert_eq!(disposals.len(), 2);
        let by_lot: std::collections::HashMap<i64, &TrackingLotDisposal> =
            disposals.iter().map(|d| (d.lot_id, d)).collect();
        // First lot (oldest): fully consumed.
        let first = disposals
            .iter()
            .find(|d| d.cost_basis_per_unit == 100.0)
            .unwrap();
        assert_eq!(first.qty_consumed, 10.0);
        assert_eq!(first.gain_loss_fiat, (250.0 - 100.0) * 10.0);
        // Mid lot: partial.
        let mid = by_lot.get(&lot_mid).unwrap();
        assert_eq!(mid.qty_consumed, 2.0);
        assert_eq!(mid.gain_loss_fiat, (250.0 - 200.0) * 2.0);
        // Mid lot row updated: residual 3 = 5 - 2.
        let mid_lot = r
            .list_investment_lots(inv)
            .unwrap()
            .into_iter()
            .find(|l| l.id == lot_mid)
            .unwrap();
        assert_eq!(mid_lot.quantity, 3.0);
    }

    #[test]
    fn sells_record_consumes_lots_in_hifo_order() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        r.add_investment_lot(inv, 5.0, 300.0, 0.0, 2_000, None)
            .unwrap(); // highest price
        r.add_investment_lot(inv, 3.0, 200.0, 0.0, 3_000, None)
            .unwrap();
        // Switch to HIFO.
        r.set_investment_cost_basis_method(inv, "hifo").unwrap();
        let sell_id = r.record_sell(inv, 4_000, 250.0, 7.0, 0.0, None).unwrap();
        let disposals: Vec<_> = r
            .list_all_lot_disposals()
            .unwrap()
            .into_iter()
            .filter(|d| d.sell_id == sell_id)
            .collect();
        // HIFO: highest-price lot first (300) fully (5), then next-highest
        // (200) for 2.
        assert_eq!(disposals.len(), 2);
        let high = disposals
            .iter()
            .find(|d| d.cost_basis_per_unit == 300.0)
            .unwrap();
        assert_eq!(high.qty_consumed, 5.0);
        let next = disposals
            .iter()
            .find(|d| d.cost_basis_per_unit == 200.0)
            .unwrap();
        assert_eq!(next.qty_consumed, 2.0);
    }

    #[test]
    fn sells_partial_consumption_leaves_residual() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        let lot = r
            .add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        r.record_sell(inv, 2_000, 150.0, 3.0, 0.0, None).unwrap();
        let row = r
            .list_investment_lots(inv)
            .unwrap()
            .into_iter()
            .find(|l| l.id == lot)
            .unwrap();
        assert_eq!(row.quantity, 7.0);
    }

    #[test]
    fn sells_reject_over_sell() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 5.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        assert!(matches!(
            r.record_sell(inv, 2_000, 150.0, 10.0, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn sells_reject_non_finite() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 5.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        assert!(matches!(
            r.record_sell(inv, 0, f64::NAN, 1.0, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.record_sell(inv, 0, 100.0, f64::INFINITY, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.record_sell(inv, 0, 100.0, 1.0, f64::NEG_INFINITY, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn sells_reject_zero_qty() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 5.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        assert!(matches!(
            r.record_sell(inv, 0, 100.0, 0.0, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn disposals_gain_loss_includes_pro_rata_fee() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        r.add_investment_lot(inv, 5.0, 200.0, 0.0, 2_000, None)
            .unwrap();
        // Sell 12 @ 250 with 60 total fee. Fee splits pro-rata over
        // the disposals: lot1 gets 60 * (10/12) = 50; lot2 gets 60 * (2/12) = 10.
        let sell_id = r.record_sell(inv, 3_000, 250.0, 12.0, 60.0, None).unwrap();
        let disposals: Vec<_> = r
            .list_all_lot_disposals()
            .unwrap()
            .into_iter()
            .filter(|d| d.sell_id == sell_id)
            .collect();
        let first = disposals
            .iter()
            .find(|d| d.cost_basis_per_unit == 100.0)
            .unwrap();
        // gain = (250 - 100) * 10 - 50 = 1500 - 50 = 1450
        assert!((first.gain_loss_fiat - 1450.0).abs() < 0.001);
        let mid = disposals
            .iter()
            .find(|d| d.cost_basis_per_unit == 200.0)
            .unwrap();
        // gain = (250 - 200) * 2 - 10 = 100 - 10 = 90
        assert!((mid.gain_loss_fiat - 90.0).abs() < 0.001);
    }

    #[test]
    fn sells_cascade_delete_disposals_on_sell_delete() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        let sell_id = r.record_sell(inv, 2_000, 150.0, 5.0, 0.0, None).unwrap();
        assert_eq!(
            r.list_all_lot_disposals()
                .unwrap()
                .iter()
                .filter(|d| d.sell_id == sell_id)
                .count(),
            1,
        );
        assert!(r.delete_investment_sell(sell_id).unwrap());
        assert_eq!(
            r.list_all_lot_disposals()
                .unwrap()
                .iter()
                .filter(|d| d.sell_id == sell_id)
                .count(),
            0,
        );
    }

    #[test]
    fn sells_cascade_delete_on_investment_delete() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap();
        r.add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        r.record_sell(inv, 2_000, 150.0, 5.0, 0.0, None).unwrap();
        r.delete_investment(inv).unwrap();
        assert!(r.list_investment_sells(inv).unwrap().is_empty());
        assert!(r.list_all_lot_disposals().unwrap().is_empty());
    }

    #[test]
    fn sells_migration_idempotent_across_opens() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            let inv = r
                .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
                .unwrap();
            r.add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
                .unwrap();
            r.record_sell(inv, 2_000, 150.0, 5.0, 0.0, None).unwrap();
        }
        let r2 = open_fresh_registry(&dir);
        let sells = r2.list_all_investment_sells().unwrap();
        let disposals = r2.list_all_lot_disposals().unwrap();
        assert_eq!(sells.len(), 1);
        assert_eq!(disposals.len(), 1);
    }

    // ---------------------------------------------------------------
    // Phase 7.10 — crypto_event_kind tests
    // ---------------------------------------------------------------

    #[test]
    fn crypto_event_kind_set_then_get() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_event_crypto_kind("w1", "tx-airdrop", "airdrop")
            .unwrap();
        assert_eq!(
            r.get_event_crypto_kind("w1", "tx-airdrop")
                .unwrap()
                .as_deref(),
            Some("airdrop"),
        );
    }

    #[test]
    fn crypto_event_kind_user_regular_is_persisted_and_pinned() {
        // Schema v3 — a user "regular" is STORED (not deleted) so the
        // auto-detector cannot re-tag the event as send_to_self.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_event_crypto_kind_auto("w1", "tx", "send_to_self")
            .unwrap();
        r.set_event_crypto_kind("w1", "tx", "regular").unwrap();
        assert_eq!(
            r.get_event_crypto_kind("w1", "tx").unwrap().as_deref(),
            Some("regular"),
        );
        assert!(
            !r.set_event_crypto_kind_auto("w1", "tx", "send_to_self")
                .unwrap()
        );
        assert!(!r.set_event_crypto_kind_auto("w1", "tx", "regular").unwrap());
        assert_eq!(
            r.get_event_crypto_kind("w1", "tx").unwrap().as_deref(),
            Some("regular"),
        );
        let dump = r.dump_crypto_event_kinds().unwrap();
        assert_eq!(
            dump,
            vec![("w1".into(), "tx".into(), "regular".into(), true)]
        );
    }

    #[test]
    fn crypto_event_kind_auto_writes_and_clears_only_unpinned_rows() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(
            r.set_event_crypto_kind_auto("w1", "auto", "send_to_self")
                .unwrap()
        );
        assert!(
            r.set_event_crypto_kind_auto("w1", "auto", "regular")
                .unwrap()
        );
        assert!(r.get_event_crypto_kind("w1", "auto").unwrap().is_none());

        r.set_event_crypto_kind("w1", "user", "swap").unwrap();
        assert!(
            !r.set_event_crypto_kind_auto("w1", "user", "send_to_self")
                .unwrap()
        );
        assert_eq!(
            r.get_event_crypto_kind("w1", "user").unwrap().as_deref(),
            Some("swap"),
        );
    }

    #[test]
    fn crypto_event_kind_v3_migration_backfills_user_set() {
        // A v2 registry: no user_set column. Non-send_to_self rows can
        // only have come from the user; send_to_self rows stay auto.
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.conn
                .execute_batch(
                    "ALTER TABLE tracking_event_crypto_kind DROP COLUMN user_set; \
                     INSERT INTO tracking_event_crypto_kind VALUES ('w1','a','airdrop',1); \
                     INSERT INTO tracking_event_crypto_kind VALUES ('w1','s','send_to_self',1); \
                     PRAGMA user_version = 2;",
                )
                .unwrap();
        }
        let r = open_fresh_registry(&dir);
        let mut dump = r.dump_crypto_event_kinds().unwrap();
        dump.sort();
        assert_eq!(
            dump,
            vec![
                ("w1".into(), "a".into(), "airdrop".into(), true),
                ("w1".into(), "s".into(), "send_to_self".into(), false),
            ]
        );
    }

    #[test]
    fn crypto_event_kind_set_overwrites_existing() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_event_crypto_kind("w1", "tx", "airdrop").unwrap();
        r.set_event_crypto_kind("w1", "tx", "swap").unwrap();
        assert_eq!(
            r.get_event_crypto_kind("w1", "tx").unwrap().as_deref(),
            Some("swap"),
        );
    }

    #[test]
    fn crypto_event_kind_reject_unknown_kind() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(matches!(
            r.set_event_crypto_kind("w1", "tx", "rugpull"),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn crypto_event_kind_accepts_each_whitelisted_value() {
        // Round-trip every documented kind to catch typos in the
        // whitelist + the call sites (Kotlin enum mirrors this list).
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for k in [
            "airdrop",
            "staking_reward",
            "lending_interest",
            "mining_reward",
            "swap",
            "send_to_self",
            "gift_received",
            "gift_sent",
        ] {
            r.set_event_crypto_kind("w1", k, k).unwrap();
            assert_eq!(
                r.get_event_crypto_kind("w1", k).unwrap().as_deref(),
                Some(k)
            );
        }
        // 8 distinct rows (regular wasn't set).
        let dump = r.dump_crypto_event_kinds().unwrap();
        assert_eq!(dump.len(), 8);
    }

    #[test]
    fn crypto_event_kind_default_returns_none() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        assert!(
            r.get_event_crypto_kind("w1", "never-set")
                .unwrap()
                .is_none()
        );
    }

    #[test]
    fn crypto_event_kind_per_wallet_isolation() {
        // Same txid in different wallets must carry independent kinds.
        // Drives the auto-detect send_to_self heuristic: a tx visible
        // in wallet hot AND wallet cold should be classifiable per-row.
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_event_crypto_kind("w_hot", "shared-tx", "send_to_self")
            .unwrap();
        r.set_event_crypto_kind("w_cold", "shared-tx", "send_to_self")
            .unwrap();
        assert_eq!(
            r.get_event_crypto_kind("w_hot", "shared-tx")
                .unwrap()
                .as_deref(),
            Some("send_to_self"),
        );
        assert_eq!(
            r.get_event_crypto_kind("w_cold", "shared-tx")
                .unwrap()
                .as_deref(),
            Some("send_to_self"),
        );
        // Reset one — the other survives.
        r.set_event_crypto_kind("w_hot", "shared-tx", "regular")
            .unwrap();
        assert_eq!(
            r.get_event_crypto_kind("w_hot", "shared-tx")
                .unwrap()
                .as_deref(),
            Some("regular"),
        );
        assert_eq!(
            r.get_event_crypto_kind("w_cold", "shared-tx")
                .unwrap()
                .as_deref(),
            Some("send_to_self"),
        );
    }

    #[test]
    fn crypto_event_kind_purge_for_wallet() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_event_crypto_kind("w1", "tx1", "airdrop").unwrap();
        r.set_event_crypto_kind("w1", "tx2", "swap").unwrap();
        r.set_event_crypto_kind("w2", "tx3", "airdrop").unwrap();
        r.purge_crypto_event_kinds_for_wallet("w1").unwrap();
        assert!(r.get_event_crypto_kind("w1", "tx1").unwrap().is_none());
        assert!(r.get_event_crypto_kind("w1", "tx2").unwrap().is_none());
        // Other wallet untouched.
        assert_eq!(
            r.get_event_crypto_kind("w2", "tx3").unwrap().as_deref(),
            Some("airdrop"),
        );
    }

    #[test]
    fn crypto_event_kind_dump_returns_all_rows() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_event_crypto_kind("w1", "tx1", "airdrop").unwrap();
        r.set_event_crypto_kind("w2", "tx2", "swap").unwrap();
        let dump = r.dump_crypto_event_kinds().unwrap();
        assert_eq!(dump.len(), 2);
    }

    #[test]
    fn crypto_event_kind_migration_idempotent_across_opens() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.set_event_crypto_kind("w1", "tx", "airdrop").unwrap();
        }
        let r2 = open_fresh_registry(&dir);
        assert_eq!(
            r2.get_event_crypto_kind("w1", "tx").unwrap().as_deref(),
            Some("airdrop"),
        );
    }

    #[test]
    fn all_tracking_tables_survive_reopen_with_correct_key() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.set_budget("Cibo", 1, "monthly").unwrap();
            r.add_bill("Netflix", 1, 1, None).unwrap();
            r.add_savings_goal(
                "G", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
            r.add_liability("L", 1, "other", None).unwrap();
            r.add_investment("I", "stock", Some("I"), 1.0, "", 1.0, "USD", 0, None, None)
                .unwrap();
            // Phase 7.4 — same survives-across-opens guarantee for
            // manual asset rows.
            r.add_manual_asset("Cash", "cash", 1.0, "EUR", None, None, None, None, None)
                .unwrap();
            // Phase 7.5 — rules + dismissed recurring.
            r.add_rule(
                "X",
                "description",
                "contains",
                "v",
                "category",
                "C",
                0,
                true,
            )
            .unwrap();
            r.dismiss_recurring("netflix").unwrap();
        }
        let r2 = open_fresh_registry(&dir);
        assert_eq!(r2.list_budgets().unwrap().len(), 1);
        assert_eq!(r2.list_bills().unwrap().len(), 1);
        assert_eq!(r2.list_savings_goals().unwrap().len(), 1);
        assert_eq!(r2.list_liabilities().unwrap().len(), 1);
        assert_eq!(r2.list_investments().unwrap().len(), 1);
        assert_eq!(r2.list_manual_assets().unwrap().len(), 1);
        assert_eq!(r2.list_rules().unwrap().len(), 1);
        assert_eq!(r2.list_dismissed_recurring().unwrap().len(), 1);
    }

    // ---------------------------------------------------------------
    // Security audit 2026-10 — accounting / schema fixes
    // ---------------------------------------------------------------

    fn new_inv(r: &Registry) -> i64 {
        r.add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "USD", 0, None, None)
            .unwrap()
    }

    fn lot_qty(r: &Registry, inv: i64, lot: i64) -> f64 {
        r.list_investment_lots(inv)
            .unwrap()
            .into_iter()
            .find(|l| l.id == lot)
            .unwrap()
            .quantity
    }

    // M1 — purchase fees are part of the per-unit basis, spread over the
    // ORIGINAL lot size even after earlier partial sells shrank `quantity`.
    #[test]
    fn realized_pnl_includes_lot_purchase_fees_over_original_quantity() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = new_inv(&r);
        // 10 units @ 100 + 50 fees → basis 105/unit.
        r.add_investment_lot(inv, 10.0, 100.0, 50.0, 1_000, None)
            .unwrap();
        let s1 = r.record_sell(inv, 2_000, 120.0, 4.0, 0.0, None).unwrap();
        let s2 = r.record_sell(inv, 3_000, 120.0, 6.0, 0.0, None).unwrap();
        let d = r.list_all_lot_disposals().unwrap();
        let d1 = d.iter().find(|x| x.sell_id == s1).unwrap();
        let d2 = d.iter().find(|x| x.sell_id == s2).unwrap();
        assert!(
            (d1.cost_basis_per_unit - 105.0).abs() < 1e-9,
            "{}",
            d1.cost_basis_per_unit
        );
        // Second sell: remaining qty is 6 but the fee share must stay 50/10.
        assert!(
            (d2.cost_basis_per_unit - 105.0).abs() < 1e-9,
            "{}",
            d2.cost_basis_per_unit
        );
        assert!((d1.gain_loss_fiat - 60.0).abs() < 1e-9);
        assert!((d2.gain_loss_fiat - 90.0).abs() < 1e-9);
        // Total realized = 10 x 120 - (1000 + 50) = 150.
        assert!((d1.gain_loss_fiat + d2.gain_loss_fiat - 150.0).abs() < 1e-9);
    }

    #[test]
    fn original_quantity_migration_backfills_from_disposals() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            let inv = new_inv(&r);
            r.add_investment_lot(inv, 10.0, 100.0, 50.0, 1_000, None)
                .unwrap();
            r.record_sell(inv, 2_000, 120.0, 4.0, 0.0, None).unwrap();
            // Simulate a v1 DB: column empty, header at v1.
            r.conn
                .execute(
                    "UPDATE tracking_investment_lots SET original_quantity = NULL",
                    [],
                )
                .unwrap();
            r.conn.execute("PRAGMA user_version = 1", []).unwrap();
        }
        let r2 = open_fresh_registry(&dir);
        let orig: f64 = r2
            .conn
            .query_row(
                "SELECT original_quantity FROM tracking_investment_lots",
                [],
                |x| x.get(0),
            )
            .unwrap();
        assert!(
            (orig - 10.0).abs() < 1e-12,
            "6 left + 4 sold = 10, got {orig}"
        );
        let v: i64 = r2
            .conn
            .query_row("PRAGMA user_version", [], |x| x.get(0))
            .unwrap();
        assert_eq!(v, SCHEMA_VERSION);
    }

    // M2 — a lot with disposals can't be deleted (would cascade-wipe history).
    #[test]
    fn delete_lot_with_disposals_is_refused() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = new_inv(&r);
        let lot = r
            .add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        let sell = r.record_sell(inv, 2_000, 150.0, 3.0, 0.0, None).unwrap();
        assert!(matches!(
            r.delete_investment_lot(lot),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert_eq!(
            r.list_all_lot_disposals().unwrap().len(),
            1,
            "history intact"
        );
        // After removing the sell the lot can go.
        assert!(r.delete_investment_sell(sell).unwrap());
        assert!(r.delete_investment_lot(lot).unwrap());
    }

    // M3 — deleting a sell restores the quantities it consumed.
    #[test]
    fn delete_sell_restores_lot_quantities_atomically() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = new_inv(&r);
        let a = r
            .add_investment_lot(inv, 10.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        let b = r
            .add_investment_lot(inv, 5.0, 200.0, 0.0, 2_000, None)
            .unwrap();
        let keep = r.record_sell(inv, 3_000, 250.0, 2.0, 0.0, None).unwrap();
        let undo = r.record_sell(inv, 4_000, 250.0, 10.0, 0.0, None).unwrap();
        assert_eq!(lot_qty(&r, inv, a), 0.0);
        assert_eq!(lot_qty(&r, inv, b), 3.0);
        assert!(r.delete_investment_sell(undo).unwrap());
        assert_eq!(lot_qty(&r, inv, a), 8.0, "8 of lot A restored");
        assert_eq!(lot_qty(&r, inv, b), 5.0, "2 of lot B restored");
        assert!(
            r.list_all_lot_disposals()
                .unwrap()
                .iter()
                .all(|d| d.sell_id == keep)
        );
        assert!(
            !r.delete_investment_sell(undo).unwrap(),
            "second delete is a no-op"
        );
        assert_eq!(lot_qty(&r, inv, a), 8.0);
    }

    // L3 — seeding refuses when lots already exist.
    #[test]
    fn seed_lot_refuses_when_lots_exist() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = r
            .add_investment(
                "A",
                "stock",
                Some("A"),
                10.0,
                "",
                1500.0,
                "USD",
                1_000,
                None,
                None,
            )
            .unwrap();
        r.add_investment_lot(inv, 1.0, 1.0, 0.0, 1_000, None)
            .unwrap();
        assert!(matches!(
            r.seed_investment_lot_from_aggregate(inv),
            Err(OnchainError::InvalidArgument(_))
        ));
        // Nothing changed: aggregate intact, still one lot.
        assert_eq!(r.list_investment_lots(inv).unwrap().len(), 1);
        assert_eq!(r.list_investments().unwrap()[0].quantity, 10.0);
    }

    // L4 — negative / NaN quantities, prices and fees are rejected.
    #[test]
    fn negative_or_nan_amounts_are_rejected() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = new_inv(&r);
        for (q, p, f) in [
            (-1.0, 1.0, 0.0),
            (1.0, -1.0, 0.0),
            (1.0, 1.0, -1.0),
            (f64::NAN, 1.0, 0.0),
            (0.0, 1.0, 0.0),
        ] {
            assert!(
                matches!(
                    r.add_investment_lot(inv, q, p, f, 0, None),
                    Err(OnchainError::InvalidArgument(_))
                ),
                "add_investment_lot({q}, {p}, {f}) must fail"
            );
        }
        let lot = r.add_investment_lot(inv, 1.0, 1.0, 0.0, 0, None).unwrap();
        assert!(matches!(
            r.update_investment_lot(lot, 1.0, 1.0, -0.5, 0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_investment("N", "stock", None, -1.0, "", 1.0, "USD", 0, None, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.record_sell(inv, 10, -5.0, 1.0, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.record_sell(inv, 10, 5.0, 1.0, -1.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
    }

    #[test]
    fn savings_goal_fiat_amount_is_validated() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let id = r
            .add_savings_goal(
                "G", 1, 0, "cyan", None, None, None, "manual", None, "savings",
            )
            .unwrap();
        for bad in [f64::NAN, f64::INFINITY, -10.0, 0.0] {
            assert!(
                matches!(
                    r.set_savings_goal_denomination(id, "fiat", Some(bad), Some("EUR")),
                    Err(OnchainError::InvalidArgument(_))
                ),
                "fiat_amount {bad} must be rejected"
            );
        }
        assert!(
            r.set_savings_goal_denomination(id, "fiat", Some(1000.0), Some("EUR"))
                .unwrap()
        );
    }

    #[test]
    fn sat_amounts_above_21m_btc_are_rejected() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let too_much = MAX_SAT + 1;
        assert!(matches!(
            r.set_budget("food", u64::MAX, "monthly"),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_bill("b", too_much, 1, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_savings_goal(
                "G", too_much, 0, "cyan", None, None, None, "manual", None, "savings"
            ),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(matches!(
            r.add_liability("L", u64::MAX, "mortgage", None),
            Err(OnchainError::InvalidArgument(_))
        ));
        // Exactly the cap is fine.
        r.add_bill("cap", MAX_SAT, 1, None).unwrap();
    }

    // L5 — lots bought after the sale date are not consumable.
    #[test]
    fn sell_cannot_consume_lots_bought_after_sale_date() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = new_inv(&r);
        r.add_investment_lot(inv, 5.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        let later = r
            .add_investment_lot(inv, 5.0, 300.0, 0.0, 9_000, None)
            .unwrap();
        assert!(matches!(
            r.record_sell(inv, 5_000, 200.0, 8.0, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        r.set_investment_cost_basis_method(inv, "hifo").unwrap();
        r.record_sell(inv, 5_000, 200.0, 5.0, 0.0, None).unwrap();
        assert_eq!(
            lot_qty(&r, inv, later),
            5.0,
            "future lot untouched even under HIFO"
        );
    }

    #[test]
    fn specific_id_sell_is_rejected_not_silently_fifo() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = new_inv(&r);
        r.add_investment_lot(inv, 5.0, 100.0, 0.0, 1_000, None)
            .unwrap();
        r.set_investment_cost_basis_method(inv, "specific_id")
            .unwrap();
        assert!(matches!(
            r.record_sell(inv, 2_000, 200.0, 1.0, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        assert!(r.list_all_lot_disposals().unwrap().is_empty());
    }

    #[test]
    fn sell_uses_relative_tolerance_and_snaps_dust_to_zero() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let inv = new_inv(&r);
        // Three 0.1 lots: 0.1+0.1+0.1 != 0.3 in f64.
        let lots: Vec<i64> = (0..3)
            .map(|i| {
                r.add_investment_lot(inv, 0.1, 10.0, 0.0, 1_000 + i, None)
                    .unwrap()
            })
            .collect();
        r.record_sell(inv, 2_000, 20.0, 0.3, 0.0, None).unwrap();
        for l in lots {
            assert_eq!(lot_qty(&r, inv, l), 0.0, "fully sold lot must be exactly 0");
        }
        // Large quantities: a 1e-13 relative overshoot is tolerated, a real
        // over-sell is not.
        let inv2 = new_inv(&r);
        r.add_investment_lot(inv2, 1_000_000.0, 1.0, 0.0, 1_000, None)
            .unwrap();
        assert!(matches!(
            r.record_sell(inv2, 2_000, 1.0, 1_000_001.0, 0.0, None),
            Err(OnchainError::InvalidArgument(_))
        ));
        r.record_sell(inv2, 2_000, 1.0, 1_000_000.000_000_1, 0.0, None)
            .unwrap();
    }

    // L9 — downgrade protection.
    #[test]
    fn opening_a_registry_from_a_newer_schema_is_refused() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.conn
                .execute(&format!("PRAGMA user_version = {}", SCHEMA_VERSION + 1), [])
                .unwrap();
        }
        let path = dir.path().join(REGISTRY_FILE_NAME);
        assert!(matches!(
            open_registry(&path, &fixture_key()),
            Err(OnchainError::Storage(_))
        ));
    }

    // remove_wallet_with_metadata purges every dependent row.
    #[test]
    fn remove_wallet_with_metadata_purges_dependents() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let rec = fixture_record("W");
        r.add_wallet(&rec).unwrap();
        let wid = rec.id.to_string();
        r.set_event_category(&rec.id, "tx1", Some("food")).unwrap();
        r.set_event_crypto_kind(&wid, "tx1", "airdrop").unwrap();
        let g = r
            .add_savings_goal(
                "G",
                1,
                0,
                "cyan",
                None,
                Some(&wid),
                None,
                "wallet",
                Some(&wid),
                "savings",
            )
            .unwrap();
        assert!(r.remove_wallet_with_metadata(&rec.id).unwrap());
        assert!(r.get_wallet(&rec.id).unwrap().is_none());
        assert!(r.dump_categories().unwrap().is_empty());
        assert!(r.dump_crypto_event_kinds().unwrap().is_empty());
        let goal = r
            .list_savings_goals()
            .unwrap()
            .into_iter()
            .find(|x| x.id == g)
            .unwrap();
        assert_eq!(goal.bucket_kind, "manual");
        assert_eq!(goal.bucket_ref, None);
        assert_eq!(goal.linked_wallet_id, None);
        assert!(!r.remove_wallet_with_metadata(&rec.id).unwrap());
    }

    #[test]
    fn add_watch_addresses_accepts_any_standard_type_now() {
        let dir = TempDir::new().unwrap();
        let path = dir.path().join(REGISTRY_FILE_NAME);
        let registry = open_registry(&path, &fixture_key()).unwrap();
        let p2wpkh = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx".to_string();
        let rec = registry
            .add_watch_addresses("P2WPKH", &[p2wpkh.clone()], bhodl_core::Network::Signet)
            .unwrap();
        let stored: Vec<String> = serde_json::from_str(&rec.descriptor_pub).unwrap();
        assert_eq!(stored, vec![p2wpkh]);
    }

    // -----------------------------------------------------------------
    // Specific-ID sells
    // -----------------------------------------------------------------

    /// Investment with three lots (t=100: 10 @ 10 + fee 10, t=200:
    /// 10 @ 20, t=300: 10 @ 30) on `method`. Returns (inv, [lot ids]).
    fn three_lot_investment(r: &Registry, method: &str) -> (i64, [i64; 3]) {
        let inv = r
            .add_investment("X", "stock", Some("X"), 0.0, "", 0.0, "EUR", 0, None, None)
            .unwrap();
        r.set_investment_cost_basis_method(inv, method).unwrap();
        let a = r
            .add_investment_lot(inv, 10.0, 10.0, 10.0, 100, None)
            .unwrap();
        let b = r
            .add_investment_lot(inv, 10.0, 20.0, 0.0, 200, None)
            .unwrap();
        let c = r
            .add_investment_lot(inv, 10.0, 30.0, 0.0, 300, None)
            .unwrap();
        (inv, [a, b, c])
    }

    fn lot_qty_pair(r: &Registry, inv: i64, lot: i64) -> (f64, f64) {
        let l = r
            .list_investment_lots(inv)
            .unwrap()
            .into_iter()
            .find(|l| l.id == lot)
            .unwrap();
        (l.quantity, l.original_quantity)
    }

    #[test]
    fn specific_id_sell_without_selection_is_refused() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (inv, _) = three_lot_investment(&r, "specific_id");
        match r.record_sell(inv, 1_000, 50.0, 1.0, 0.0, None) {
            Err(OnchainError::InvalidArgument(msg)) => {
                // The message is shown to the user: one sentence, no
                // stray indentation from a broken line continuation.
                assert!(!msg.contains("  "), "unexpected whitespace run in {msg:?}");
                assert!(msg.ends_with("pick the lots to sell"), "{msg:?}");
            }
            other => panic!("expected InvalidArgument, got {other:?}"),
        }
        assert!(r.list_investment_sells(inv).unwrap().is_empty());
    }

    #[test]
    fn specific_id_sell_consumes_exactly_the_picked_lots() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (inv, [a, b, c]) = three_lot_investment(&r, "specific_id");
        // Sell 6 units @ 50 with a 6 EUR fee: 4 from the newest lot (c),
        // 2 from the oldest (a) — an order no FIFO / HIFO would produce.
        let sell = r
            .record_sell_with_lots(
                inv,
                1_000,
                50.0,
                6.0,
                6.0,
                None,
                &[
                    LotSelection {
                        lot_id: c,
                        qty: 4.0,
                    },
                    LotSelection {
                        lot_id: a,
                        qty: 2.0,
                    },
                ],
            )
            .unwrap();
        assert_eq!(lot_qty_pair(&r, inv, a), (8.0, 10.0));
        assert_eq!(lot_qty_pair(&r, inv, b), (10.0, 10.0));
        assert_eq!(lot_qty_pair(&r, inv, c), (6.0, 10.0));

        let disposals: Vec<TrackingLotDisposal> = r
            .list_all_lot_disposals()
            .unwrap()
            .into_iter()
            .filter(|d| d.sell_id == sell)
            .collect();
        assert_eq!(disposals.len(), 2);
        let on = |lot: i64| disposals.iter().find(|d| d.lot_id == lot).unwrap();
        // Lot c: basis 30, fee share 6 × 4/6 = 4 → (50−30)×4 − 4 = 76.
        assert!((on(c).gain_loss_fiat - 76.0).abs() < 1e-9);
        // Lot a: basis 10 + 10/10 = 11, fee share 2 → (50−11)×2 − 2 = 76.
        assert!((on(a).cost_basis_per_unit - 11.0).abs() < 1e-9);
        assert!((on(a).gain_loss_fiat - 76.0).abs() < 1e-9);

        // Deleting the sell restores the remaining quantities.
        assert!(r.delete_investment_sell(sell).unwrap());
        for lot in [a, b, c] {
            assert_eq!(lot_qty_pair(&r, inv, lot), (10.0, 10.0));
        }
        assert!(r.list_all_lot_disposals().unwrap().is_empty());
    }

    #[test]
    fn specific_id_sell_validates_the_selection_atomically() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (inv, [a, b, c]) = three_lot_investment(&r, "specific_id");
        let (other_inv, [foreign, _, _]) = three_lot_investment(&r, "specific_id");
        let sel = |v: &[(i64, f64)]| -> Vec<LotSelection> {
            v.iter()
                .map(|&(lot_id, qty)| LotSelection { lot_id, qty })
                .collect()
        };
        let bad: Vec<(&str, i64, f64, Vec<LotSelection>)> = vec![
            ("empty", 1_000, 1.0, vec![]),
            (
                "lot of another investment",
                1_000,
                1.0,
                sel(&[(foreign, 1.0)]),
            ),
            ("unknown lot", 1_000, 1.0, sel(&[(999_999, 1.0)])),
            ("more than the lot holds", 1_000, 11.0, sel(&[(a, 11.0)])),
            ("sum below sale qty", 1_000, 5.0, sel(&[(a, 2.0), (b, 2.0)])),
            ("sum above sale qty", 1_000, 3.0, sel(&[(a, 2.0), (b, 2.0)])),
            ("duplicate lot", 1_000, 4.0, sel(&[(a, 2.0), (a, 2.0)])),
            ("zero qty", 1_000, 1.0, sel(&[(a, 1.0), (b, 0.0)])),
            ("negative qty", 1_000, 1.0, sel(&[(a, 2.0), (b, -1.0)])),
            ("NaN qty", 1_000, 1.0, sel(&[(a, f64::NAN)])),
            ("lot bought after the sale", 250, 1.0, sel(&[(c, 1.0)])),
        ];
        for (why, when, qty, selection) in bad {
            assert!(
                matches!(
                    r.record_sell_with_lots(inv, when, 50.0, qty, 0.0, None, &selection),
                    Err(OnchainError::InvalidArgument(_))
                ),
                "{why} must be refused"
            );
        }
        // Nothing was written by any refused call.
        assert!(r.list_investment_sells(inv).unwrap().is_empty());
        assert!(r.list_all_lot_disposals().unwrap().is_empty());
        for lot in [a, b, c] {
            assert_eq!(lot_qty_pair(&r, inv, lot), (10.0, 10.0));
        }
        assert_eq!(lot_qty_pair(&r, other_inv, foreign), (10.0, 10.0));
    }

    #[test]
    fn lot_selection_overrides_the_method_of_a_fifo_investment() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (inv, [a, b, _]) = three_lot_investment(&r, "fifo");
        // The sell dialog books a per-sale method choice as explicit
        // picks: lot `b` is consumed even though FIFO would take `a`.
        r.record_sell_with_lots(
            inv,
            1_000,
            50.0,
            1.0,
            0.0,
            None,
            &[LotSelection {
                lot_id: b,
                qty: 1.0,
            }],
        )
        .unwrap();
        assert_eq!(lot_qty_pair(&r, inv, b), (9.0, 10.0));
        assert_eq!(lot_qty_pair(&r, inv, a), (10.0, 10.0));
        // Plain record_sell still works and is FIFO.
        r.record_sell(inv, 1_000, 50.0, 1.0, 0.0, None).unwrap();
        assert_eq!(lot_qty_pair(&r, inv, a), (9.0, 10.0));
    }

    #[test]
    fn specific_id_sell_can_empty_a_lot_exactly() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (inv, [a, b, _]) = three_lot_investment(&r, "specific_id");
        r.record_sell_with_lots(
            inv,
            1_000,
            50.0,
            10.3,
            0.0,
            None,
            &[
                LotSelection {
                    lot_id: b,
                    qty: 10.0,
                },
                LotSelection {
                    lot_id: a,
                    qty: 0.3,
                },
            ],
        )
        .unwrap();
        assert_eq!(lot_qty_pair(&r, inv, b), (0.0, 10.0));
        let (qa, oa) = lot_qty_pair(&r, inv, a);
        assert!((qa - 9.7).abs() < 1e-9);
        assert_eq!(oa, 10.0);
    }

    #[test]
    fn pairing_mirror_label_matching() {
        assert!(is_pairing_mirror_label("Main (tracker)", "Main"));
        assert!(is_pairing_mirror_label("main (TRACKER 3)", "Main"));
        assert!(is_pairing_mirror_label("Main \u{b7} tracker", "main"));
        assert!(!is_pairing_mirror_label("Main (tracker x)", "Main"));
        assert!(!is_pairing_mirror_label("Main tracker", "Main"));
        assert!(!is_pairing_mirror_label("Other (tracker)", "Main"));
        assert!(!is_pairing_mirror_label("My Main \u{b7} tracker", "Main"));
    }

    #[test]
    fn downgrade_refusal_keeps_working_with_schema_v4() {
        let dir = TempDir::new().unwrap();
        {
            let r = open_fresh_registry(&dir);
            r.conn
                .execute(&format!("PRAGMA user_version = {}", SCHEMA_VERSION + 1), [])
                .unwrap();
        }
        let path = dir.path().join(REGISTRY_FILE_NAME);
        match open_registry(&path, &fixture_key()) {
            Err(OnchainError::Storage(msg)) => {
                assert!(msg.contains("newer than this app supports"), "{msg}");
            }
            other => panic!("expected a downgrade refusal, got {other:?}"),
        }
    }

    /// Insert a pre-rework `signing` row (raw SQL: the kind no longer
    /// exists in [`WalletKind`]) plus a watch row mirroring it.
    fn insert_legacy_signing(r: &Registry) -> (Uuid, Uuid) {
        let signing = Uuid::new_v4();
        let mirror = Uuid::new_v4();
        r.conn
            .execute(
                "INSERT INTO wallets (id, label, kind, descriptor_pub, network, created_at_unix) \
                 VALUES (?1, 'Main', 'signing', 'tr(tpubX/0/*)', 'signet', 1)",
                [signing.to_string()],
            )
            .unwrap();
        r.conn
            .execute(
                "INSERT INTO wallets \
                    (id, label, kind, descriptor_pub, network, created_at_unix, mirror_of) \
                 VALUES (?1, 'Main (tracker)', 'watch_xpub', 'tr(tpubX/0/*)', 'signet', 2, ?2)",
                [mirror.to_string(), signing.to_string()],
            )
            .unwrap();
        r.set_event_category(&signing, "tx1", Some("food")).unwrap();
        (signing, mirror)
    }

    #[test]
    fn legacy_signing_rows_are_hidden_from_listings() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (signing, mirror) = insert_legacy_signing(&r);
        let listed: Vec<Uuid> = r.list_wallets().unwrap().iter().map(|w| w.id).collect();
        assert_eq!(listed, vec![mirror]);
        assert!(r.get_wallet(&signing).unwrap().is_none());
        assert_eq!(r.legacy_signing_wallet_ids().unwrap(), vec![signing]);
    }

    #[test]
    fn purge_legacy_signing_wallets_keeps_mirrors_as_plain_watch_wallets() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (signing, mirror) = insert_legacy_signing(&r);
        assert_eq!(r.purge_legacy_signing_wallets().unwrap(), vec![signing]);
        assert!(r.legacy_signing_wallet_ids().unwrap().is_empty());
        assert_eq!(r.get_event_category(&signing, "tx1").unwrap(), None);
        let kept = r.get_wallet(&mirror).unwrap().expect("mirror survives");
        assert_eq!(kept.kind, WalletKind::WatchXpub);
        let linked: Option<String> = r
            .conn
            .query_row(
                "SELECT mirror_of FROM wallets WHERE id = ?1",
                [mirror.to_string()],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(linked, None);
        // Idempotent.
        assert!(r.purge_legacy_signing_wallets().unwrap().is_empty());
    }
    // ---------------------------------------------------------------
    // v5 — BTC average cost per-transaction inputs
    // ---------------------------------------------------------------

    #[test]
    fn event_cost_basis_round_trip_and_reset() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        r.set_event_cost_basis("w1", "tx1", Some((42_000.5, "eur")), false)
            .unwrap();
        r.set_event_cost_basis("w1", "tx2", None, true).unwrap();
        let mut rows = r.list_event_cost_basis().unwrap();
        rows.sort_by(|a, b| a.txid_or_payment_id.cmp(&b.txid_or_payment_id));
        assert_eq!(
            rows,
            vec![
                EventCostBasis {
                    wallet_id: "w1".into(),
                    txid_or_payment_id: "tx1".into(),
                    price: Some((42_000.5, "EUR".into())),
                    excluded: false,
                },
                EventCostBasis {
                    wallet_id: "w1".into(),
                    txid_or_payment_id: "tx2".into(),
                    price: None,
                    excluded: true,
                },
            ]
        );
        // Back to defaults: the row disappears.
        r.set_event_cost_basis("w1", "tx1", None, false).unwrap();
        let rows = r.list_event_cost_basis().unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].txid_or_payment_id, "tx2");
    }

    #[test]
    fn event_cost_basis_rejects_bad_input() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        for (price, ccy) in [(-1.0, "EUR"), (f64::NAN, "EUR"), (f64::INFINITY, "EUR")] {
            assert!(matches!(
                r.set_event_cost_basis("w", "tx", Some((price, ccy)), false),
                Err(OnchainError::InvalidArgument(_))
            ));
        }
        for ccy in ["", "EU", "EURO", "E1R"] {
            assert!(matches!(
                r.set_event_cost_basis("w", "tx", Some((1.0, ccy)), false),
                Err(OnchainError::InvalidArgument(_))
            ));
        }
        // A zero price is valid (e.g. a gift recorded at no cost).
        r.set_event_cost_basis("w", "tx", Some((0.0, "EUR")), false)
            .unwrap();
        assert_eq!(r.list_event_cost_basis().unwrap().len(), 1);
    }

    #[test]
    fn removing_a_wallet_drops_its_cost_basis_rows() {
        let dir = TempDir::new().unwrap();
        let r = open_fresh_registry(&dir);
        let (_signing, mirror) = insert_legacy_signing(&r);
        let wid = mirror.to_string();
        r.set_event_cost_basis(&wid, "tx1", None, true).unwrap();
        r.set_event_cost_basis("other", "tx1", None, true).unwrap();
        assert!(r.remove_wallet_with_metadata(&mirror).unwrap());
        let rows = r.list_event_cost_basis().unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].wallet_id, "other");
    }
}
