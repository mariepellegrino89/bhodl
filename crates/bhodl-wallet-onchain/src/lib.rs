//! Watch-only Bitcoin wallet primitives for BHODL.
//!
//! BHODL never holds keys: every wallet is a [`WatchAccount`] built from
//! public material only, stored in a `SQLCipher` database encrypted with
//! the device key. Three kinds are supported:
//!
//! - **xpub** — account-level extended public key at BIP-86 / 84 / 49
//!   (SLIP-132 `zpub` / `ypub` accepted);
//! - **HD descriptor** — xpub plus master fingerprint and derivation
//!   path (Sparrow / Specter export);
//! - **address list** — explicit addresses, no derivation.
//!
//! On top of balance / history / Esplora sync (optionally over Tor), a
//! watch wallet offers receive-address derivation, BIP-329 labels and a
//! local address book. The [`Registry`] holds the wallet list and every
//! tracker table (budgets, goals, investments, manual assets, ...).

#![deny(unsafe_code)]

mod btc_at_time_backfill;
mod btc_rate_history;
mod common;
mod contacts;
mod error;
mod labels;
pub mod loan_schedule;
pub mod net_worth_history;
mod registry;
#[cfg(test)]
mod test_support;
mod tor;
#[cfg(all(test, feature = "tor-tests"))]
mod tor_live_tests;
mod watch;

pub use btc_at_time_backfill::{
    ManualAssetRateQuery, MigrationReport, RecomputeStats, TableBackfillStats,
    apply_8_2_migrations, classify_rate, compute_gain_loss_btc, phase_8_2_backfill_btc_at_time,
    recompute_all_lot_btc_rates_impl, resolve_lot_btc_rate_impl,
    resolve_manual_asset_purchase_btc_impl, resolve_sell_btc_impl,
};
pub use btc_rate_history::{
    BackfillReport, BtcRateHistoryRepository, BtcRateRepo, RateError, RatePoint,
    SUPPORTED_CURRENCIES, VALID_SOURCES, normalize_to_utc_midnight,
};
pub use common::{
    DerivedAddress, DescriptorBackup, ENCRYPTION_KEY_LEN, ESPLORA_SYNC_RETRIES, ReceiveLabelCopy,
    TxRecord, UtxoInfo, is_transient_esplora_error,
};
pub use contacts::Contact;
pub use error::OnchainError;
pub use labels::{Label, LabelKind};
pub use net_worth_history::{
    NwSnapshot, PaymentEventLite, investments_value_btc_at, manual_assets_value_btc_at,
    onchain_balance_at, reconstruct_net_worth_history,
};
pub use registry::{
    DebtPayment, DemoEvent, EventCostBasis, LotSelection, MANUAL_ACCOUNT_OTHER,
    MANUAL_WALLET_ID_PREFIX, REGISTRY_FILE_NAME, REGISTRY_KDF_FILE_NAME, Registry,
    SCHEMA_VERSION as REGISTRY_SCHEMA_VERSION, WalletKind, WalletRecord, manual_txn_event_id,
    manual_txn_wallet_id, open_registry, open_registry_in_memory,
};
pub use watch::{PendingWatchScan, WatchAccount, WatchBip, WatchScanUpdate};
