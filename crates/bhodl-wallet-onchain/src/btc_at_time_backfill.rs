//! Phase 8.2 — BTC-at-time backfill for the 4 fiat-denominated tracker
//! tables.
//!
//! Adds `btc_per_unit_at_purchase` / `btc_per_unit_at_sale` /
//! `gain_loss_btc` / `purchase_*` columns to:
//!   - `tracking_investment_lots`
//!   - `tracking_investment_sells`
//!   - `tracking_lot_disposals`
//!   - `tracking_manual_assets`
//!
//! The migration is idempotent: each `ALTER TABLE … ADD COLUMN` is swallowed
//! when the column already exists (`SQLite` error "duplicate column name"),
//! following the same pattern as earlier phases.
//!
//! The backfill orchestrator iterates each table, fetches the BTC/fiat rate
//! for rows with the new column still NULL, and UPDATEs those rows. Rows
//! whose rate cannot be recovered (e.g. pre-genesis date, or network
//! unavailable) are counted as `failed` and left NULL — a later call will
//! retry them automatically (the IS NULL filter acts as a resumption
//! bookmark).

#![allow(clippy::cast_possible_truncation)]
#![allow(clippy::cast_possible_wrap)]

use rusqlite::{Connection, params};

use crate::{
    btc_rate_history::{BtcRateHistoryRepository, RateError, normalize_to_utc_midnight},
    error::OnchainError,
};

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/// Per-table counters returned by the backfill orchestrator.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TableBackfillStats {
    /// Rows whose target column was NULL at the start of this run.
    pub considered: usize,
    /// Rows successfully backfilled during this run.
    pub fetched: usize,
    /// Rows whose target column was already non-NULL (skipped, already done).
    /// These are NOT in `considered`: we count already-done rows up-front via
    /// a separate COUNT query.
    pub already_done: usize,
    /// Rows where a rate could not be obtained (date out of range, currency
    /// unsupported, or all sources returned None). Stays NULL.
    pub failed: usize,
}

/// Top-level report from `phase_8_2_backfill_btc_at_time`.
///
/// Named `MigrationReport` rather than `BackfillReport` to avoid a
/// collision with `btc_rate_history::BackfillReport` (the rate-repo
/// bulk-fetch summary introduced in Phase 8.1).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MigrationReport {
    pub investment_lots: TableBackfillStats,
    pub investment_sells: TableBackfillStats,
    pub lot_disposals: TableBackfillStats,
    pub manual_assets: TableBackfillStats,
}

// ---------------------------------------------------------------------------
// ALTER TABLE migrations (idempotent)
// ---------------------------------------------------------------------------

const ALTER_LOTS_ADD_BTC_AT_PURCHASE: &str =
    "ALTER TABLE tracking_investment_lots ADD COLUMN btc_per_unit_at_purchase REAL";

const ALTER_SELLS_ADD_BTC_AT_SALE: &str =
    "ALTER TABLE tracking_investment_sells ADD COLUMN btc_per_unit_at_sale REAL";

const ALTER_DISPOSALS_ADD_GAIN_LOSS_BTC: &str =
    "ALTER TABLE tracking_lot_disposals ADD COLUMN gain_loss_btc REAL";

const ALTER_MANUAL_ASSETS_ADD_PURCHASE_UNIX: &str =
    "ALTER TABLE tracking_manual_assets ADD COLUMN purchase_unix INTEGER";

const ALTER_MANUAL_ASSETS_ADD_PURCHASE_VALUE_FIAT: &str =
    "ALTER TABLE tracking_manual_assets ADD COLUMN purchase_value_fiat REAL";

const ALTER_MANUAL_ASSETS_ADD_PURCHASE_CURRENCY: &str =
    "ALTER TABLE tracking_manual_assets ADD COLUMN purchase_currency TEXT";

const ALTER_MANUAL_ASSETS_ADD_PURCHASE_BTC_AT_TIME: &str =
    "ALTER TABLE tracking_manual_assets ADD COLUMN purchase_btc_at_time REAL";

/// Execute all 6 Phase 8.2 `ALTER TABLE` migrations idempotently.
///
/// Errors for "duplicate column name" are swallowed; every other error is
/// propagated. Safe to call on every app boot.
pub fn apply_8_2_migrations(conn: &Connection) -> Result<(), OnchainError> {
    let stmts = [
        ALTER_LOTS_ADD_BTC_AT_PURCHASE,
        ALTER_SELLS_ADD_BTC_AT_SALE,
        ALTER_DISPOSALS_ADD_GAIN_LOSS_BTC,
        ALTER_MANUAL_ASSETS_ADD_PURCHASE_UNIX,
        ALTER_MANUAL_ASSETS_ADD_PURCHASE_VALUE_FIAT,
        ALTER_MANUAL_ASSETS_ADD_PURCHASE_CURRENCY,
        ALTER_MANUAL_ASSETS_ADD_PURCHASE_BTC_AT_TIME,
    ];
    for stmt in stmts {
        match conn.execute(stmt, []) {
            Ok(_) => {}
            Err(e) => {
                let msg = e.to_string();
                // Swallow expected no-op conditions:
                // - "duplicate column name": column already added by a
                //   previous run of this migration (idempotent).
                // - "no such table": the table does not yet exist (e.g.
                //   a partial test DB that only created a subset of
                //   tables). The column is therefore also absent — no
                //   action required.
                if !msg.contains("duplicate column name") && !msg.contains("no such table") {
                    return Err(OnchainError::Storage(msg));
                }
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Orchestrator
// ---------------------------------------------------------------------------

/// Main entry point called by the FFI layer.
///
/// Opens a single `SQLite` connection and runs all four sub-backfills in
/// order. Each sub-function is fault-tolerant at the row level: a failed
/// rate fetch does not abort the overall backfill. Only hard database
/// errors (schema corruption, write failures) propagate.
pub fn phase_8_2_backfill_btc_at_time<R: BtcRateHistoryRepository>(
    conn: &Connection,
    rate_repo: &R,
) -> Result<MigrationReport, OnchainError> {
    let mut report = MigrationReport::default();
    backfill_investment_lots(conn, rate_repo, &mut report.investment_lots)?;
    backfill_investment_sells(conn, rate_repo, &mut report.investment_sells)?;
    backfill_lot_disposals(conn, &mut report.lot_disposals)?;
    backfill_manual_assets(conn, rate_repo, &mut report.manual_assets)?;
    Ok(report)
}

// ---------------------------------------------------------------------------
// Sub-functions
// ---------------------------------------------------------------------------

/// Backfill `tracking_investment_lots.btc_per_unit_at_purchase`.
///
/// Joins with `tracking_investments` to get `cost_basis_currency`.
fn backfill_investment_lots<R: BtcRateHistoryRepository>(
    conn: &Connection,
    rate_repo: &R,
    stats: &mut TableBackfillStats,
) -> Result<(), OnchainError> {
    struct LotRow {
        id: i64,
        purchase_unix: i64,
        currency: String,
    }

    // Count already-done rows.
    let already_done: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM tracking_investment_lots WHERE btc_per_unit_at_purchase IS NOT NULL",
            [],
            |r| r.get::<_, i64>(0),
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    stats.already_done = usize::try_from(already_done).unwrap_or(0);

    // Fetch rows that still need backfilling. Bring currency from parent.
    let mut stmt = conn
        .prepare(
            "SELECT l.id, l.purchase_unix, i.cost_basis_currency \
             FROM tracking_investment_lots l \
             JOIN tracking_investments i ON l.investment_id = i.id \
             WHERE l.btc_per_unit_at_purchase IS NULL",
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    let rows: Vec<LotRow> = stmt
        .query_map([], |r| {
            Ok(LotRow {
                id: r.get(0)?,
                purchase_unix: r.get(1)?,
                currency: r.get(2)?,
            })
        })
        .map_err(|e| OnchainError::Storage(e.to_string()))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    stats.considered = rows.len();

    for row in rows {
        let norm = normalize_to_utc_midnight(row.purchase_unix);
        match rate_repo.rate_at(norm, &row.currency) {
            Ok(Some(rate)) => {
                conn.execute(
                    "UPDATE tracking_investment_lots SET btc_per_unit_at_purchase = ?1 WHERE id = ?2",
                    params![rate, row.id],
                )
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
                stats.fetched += 1;
            }
            // Rate unavailable (pre-genesis, future, or unsupported currency) — soft fail.
            Ok(None) | Err(RateError::CurrencyNotSupported(_)) => {
                stats.failed += 1;
            }
            Err(e) => {
                // Network error / all sources failed → abort the whole
                // backfill so the caller can retry later.
                return Err(OnchainError::Storage(format!(
                    "rate fetch error for lot {}: {e}",
                    row.id
                )));
            }
        }
    }
    Ok(())
}

/// Backfill `tracking_investment_sells.btc_per_unit_at_sale`.
fn backfill_investment_sells<R: BtcRateHistoryRepository>(
    conn: &Connection,
    rate_repo: &R,
    stats: &mut TableBackfillStats,
) -> Result<(), OnchainError> {
    struct SellRow {
        id: i64,
        sale_unix: i64,
        currency: String,
    }

    let already_done: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM tracking_investment_sells WHERE btc_per_unit_at_sale IS NOT NULL",
            [],
            |r| r.get::<_, i64>(0),
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    stats.already_done = usize::try_from(already_done).unwrap_or(0);

    let mut stmt = conn
        .prepare(
            "SELECT s.id, s.sale_unix, i.cost_basis_currency \
             FROM tracking_investment_sells s \
             JOIN tracking_investments i ON s.investment_id = i.id \
             WHERE s.btc_per_unit_at_sale IS NULL",
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    let rows: Vec<SellRow> = stmt
        .query_map([], |r| {
            Ok(SellRow {
                id: r.get(0)?,
                sale_unix: r.get(1)?,
                currency: r.get(2)?,
            })
        })
        .map_err(|e| OnchainError::Storage(e.to_string()))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    stats.considered = rows.len();

    for row in rows {
        let norm = normalize_to_utc_midnight(row.sale_unix);
        match rate_repo.rate_at(norm, &row.currency) {
            Ok(Some(rate)) => {
                conn.execute(
                    "UPDATE tracking_investment_sells SET btc_per_unit_at_sale = ?1 WHERE id = ?2",
                    params![rate, row.id],
                )
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
                stats.fetched += 1;
            }
            // Rate unavailable (pre-genesis, future, or unsupported currency) — soft fail.
            Ok(None) | Err(RateError::CurrencyNotSupported(_)) => {
                stats.failed += 1;
            }
            Err(e) => {
                return Err(OnchainError::Storage(format!(
                    "rate fetch error for sell {}: {e}",
                    row.id
                )));
            }
        }
    }
    Ok(())
}

/// Backfill `tracking_lot_disposals.gain_loss_btc`.
///
/// Derived from the already-backfilled `btc_per_unit_at_purchase` (on the
/// lot) and `btc_per_unit_at_sale` (on the sell). No network call required.
/// If either parent value is still NULL, the disposal is skipped — a later
/// run will retry it once the parents are backfilled.
fn backfill_lot_disposals(
    conn: &Connection,
    stats: &mut TableBackfillStats,
) -> Result<(), OnchainError> {
    struct DisposalRow {
        id: i64,
        qty_consumed: f64,
        cost_basis_per_unit: f64,
        sale_price_per_unit: f64,
        btc_per_unit_at_sale: Option<f64>,
        btc_per_unit_at_purchase: Option<f64>,
    }

    let already_done: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM tracking_lot_disposals WHERE gain_loss_btc IS NOT NULL",
            [],
            |r| r.get::<_, i64>(0),
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    stats.already_done = usize::try_from(already_done).unwrap_or(0);

    // Join to parent sell and lot to get all needed values in one pass.
    let mut stmt = conn
        .prepare(
            "SELECT d.id, d.qty_consumed, d.cost_basis_per_unit, \
                    s.sale_price_per_unit, s.btc_per_unit_at_sale, \
                    l.btc_per_unit_at_purchase \
             FROM tracking_lot_disposals d \
             JOIN tracking_investment_sells s ON d.sell_id = s.id \
             JOIN tracking_investment_lots l ON d.lot_id = l.id \
             WHERE d.gain_loss_btc IS NULL",
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    let rows: Vec<DisposalRow> = stmt
        .query_map([], |r| {
            Ok(DisposalRow {
                id: r.get(0)?,
                qty_consumed: r.get(1)?,
                cost_basis_per_unit: r.get(2)?,
                sale_price_per_unit: r.get(3)?,
                btc_per_unit_at_sale: r.get(4)?,
                btc_per_unit_at_purchase: r.get(5)?,
            })
        })
        .map_err(|e| OnchainError::Storage(e.to_string()))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    stats.considered = rows.len();

    for row in rows {
        let (Some(btc_at_sale), Some(btc_at_purchase)) =
            (row.btc_per_unit_at_sale, row.btc_per_unit_at_purchase)
        else {
            // Parent rate still NULL — skip; a later run will retry.
            stats.failed += 1;
            continue;
        };

        if btc_at_sale <= 0.0 || btc_at_purchase <= 0.0 {
            stats.failed += 1;
            continue;
        }

        let gain_loss_btc = compute_gain_loss_btc(
            row.qty_consumed,
            row.cost_basis_per_unit,
            btc_at_purchase,
            row.sale_price_per_unit,
            btc_at_sale,
        );

        conn.execute(
            "UPDATE tracking_lot_disposals SET gain_loss_btc = ?1 WHERE id = ?2",
            params![gain_loss_btc, row.id],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
        stats.fetched += 1;
    }
    Ok(())
}

/// Backfill all 4 `purchase_*` columns on `tracking_manual_assets`.
///
/// Legacy rows (those with `purchase_unix IS NULL`) fall back silently to
/// `created_at_unix` + `value_fiat` + `currency` per the Phase 8.2 spec.
fn backfill_manual_assets<R: BtcRateHistoryRepository>(
    conn: &Connection,
    rate_repo: &R,
    stats: &mut TableBackfillStats,
) -> Result<(), OnchainError> {
    struct AssetRow {
        id: i64,
        purchase_unix: Option<i64>,
        purchase_value_fiat: Option<f64>,
        purchase_currency: Option<String>,
        created_at_unix: i64,
        value_fiat: f64,
        currency: String,
    }

    let already_done: i64 = conn
        .query_row(
            "SELECT COUNT(*) FROM tracking_manual_assets WHERE purchase_btc_at_time IS NOT NULL",
            [],
            |r| r.get::<_, i64>(0),
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    stats.already_done = usize::try_from(already_done).unwrap_or(0);

    let mut stmt = conn
        .prepare(
            "SELECT id, purchase_unix, purchase_value_fiat, purchase_currency, \
                    created_at_unix, value_fiat, currency \
             FROM tracking_manual_assets \
             WHERE purchase_btc_at_time IS NULL",
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    let rows: Vec<AssetRow> = stmt
        .query_map([], |r| {
            Ok(AssetRow {
                id: r.get(0)?,
                purchase_unix: r.get(1)?,
                purchase_value_fiat: r.get(2)?,
                purchase_currency: r.get(3)?,
                created_at_unix: r.get(4)?,
                value_fiat: r.get(5)?,
                currency: r.get(6)?,
            })
        })
        .map_err(|e| OnchainError::Storage(e.to_string()))?
        .collect::<Result<Vec<_>, _>>()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    stats.considered = rows.len();

    for row in rows {
        // Legacy fallback: use created_at_unix / value_fiat / currency when
        // the purchase fields were never set (pre-8.2 data). This is
        // intentionally silent — no log, no prompt (per spec §4.4).
        let effective_unix = row.purchase_unix.unwrap_or(row.created_at_unix);
        let effective_value = row.purchase_value_fiat.unwrap_or(row.value_fiat);
        let effective_currency = row
            .purchase_currency
            .clone()
            .unwrap_or_else(|| row.currency.clone());

        let norm = normalize_to_utc_midnight(effective_unix);
        match rate_repo.rate_at(norm, &effective_currency) {
            Ok(Some(rate)) => {
                conn.execute(
                    "UPDATE tracking_manual_assets \
                     SET purchase_unix = ?1, purchase_value_fiat = ?2, \
                         purchase_currency = ?3, purchase_btc_at_time = ?4 \
                     WHERE id = ?5",
                    params![
                        effective_unix,
                        effective_value,
                        effective_currency,
                        rate,
                        row.id,
                    ],
                )
                .map_err(|e| OnchainError::Storage(e.to_string()))?;
                stats.fetched += 1;
            }
            // Rate unavailable (pre-genesis, future, or unsupported currency) — soft fail.
            Ok(None) | Err(RateError::CurrencyNotSupported(_)) => {
                stats.failed += 1;
            }
            Err(e) => {
                return Err(OnchainError::Storage(format!(
                    "rate fetch error for manual asset {}: {e}",
                    row.id
                )));
            }
        }
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Formula
// ---------------------------------------------------------------------------

/// Compute `gain_loss_btc` for one disposal.
///
/// ```text
/// cost_btc  = qty_consumed × cost_basis_per_unit  / btc_per_unit_at_purchase
/// sale_btc  = qty_consumed × sale_price_per_unit  / btc_per_unit_at_sale
/// gain_loss = sale_btc − cost_btc
/// ```
///
/// The result can be negative (sold for fewer BTC than you spent, even if
/// `gain_loss_fiat` is positive — that's the key insight of the BTC lens).
pub fn compute_gain_loss_btc(
    qty_consumed: f64,
    cost_basis_per_unit: f64,
    btc_per_unit_at_purchase: f64,
    sale_price_per_unit: f64,
    btc_per_unit_at_sale: f64,
) -> f64 {
    let cost_btc = qty_consumed * cost_basis_per_unit / btc_per_unit_at_purchase;
    let sale_btc = qty_consumed * sale_price_per_unit / btc_per_unit_at_sale;
    sale_btc - cost_btc
}

// ---------------------------------------------------------------------------
// Per-row resolve helpers (shared between on-demand and backfill)
// ---------------------------------------------------------------------------
//
// Audit M4 — each resolver is split into three phases so an FFI caller
// holding the registry behind a mutex can run the network phase unlocked:
//   1. `*_rate_query(conn, id)`  — read the (day, currency) to price (DB, fast);
//   2. `rate_repo.rate_at(..)`   — network / rate cache (no registry lock);
//   3. `apply_*(conn, id, rate)` — write the result (DB, fast).
// The `*_impl` functions below compose the three for single-connection
// callers (tests, backfill).

type Storage = crate::error::OnchainError;

fn storage_err(e: impl std::fmt::Display) -> Storage {
    Storage::Storage(e.to_string())
}

/// Counters returned by `recompute_all_lot_btc_rates`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RecomputeStats {
    /// Total number of lots iterated.
    pub considered: usize,
    /// Lots whose `btc_per_unit_at_purchase` was successfully resolved
    /// and written (overwrites any prior value).
    pub updated: usize,
    /// Lots whose rate could not be obtained. The existing column value
    /// (which may be NULL or a previously-written good value) is
    /// preserved to avoid wiping a good rate on a transient failure.
    pub failed: usize,
}

/// Map a rate lookup to `Ok(Some(rate))`, `Ok(None)` for "genuinely
/// unavailable" (pre-genesis, future, unsupported currency), or a hard
/// error naming `what` for network / storage failures.
pub fn classify_rate(
    res: Result<Option<f64>, RateError>,
    what: &str,
) -> Result<Option<f64>, Storage> {
    match res {
        Ok(Some(rate)) => Ok(Some(rate)),
        Ok(None) | Err(RateError::CurrencyNotSupported(_)) => Ok(None),
        Err(e) => Err(Storage::Storage(format!(
            "rate fetch error for {what}: {e}"
        ))),
    }
}

/// `(utc_midnight_day, currency)` to price for one investment lot, or
/// `None` when the lot does not exist.
pub(crate) fn lot_rate_query(
    conn: &Connection,
    lot_id: i64,
) -> Result<Option<(i64, String)>, Storage> {
    let mut stmt = conn
        .prepare(
            "SELECT l.purchase_unix, i.cost_basis_currency \
             FROM tracking_investment_lots l \
             JOIN tracking_investments i ON l.investment_id = i.id \
             WHERE l.id = ?1",
        )
        .map_err(storage_err)?;
    let mut rows = stmt
        .query_map([lot_id], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })
        .map_err(storage_err)?;
    Ok(rows
        .next()
        .transpose()
        .map_err(storage_err)?
        .map(|(unix, ccy)| (normalize_to_utc_midnight(unix), ccy)))
}

/// Every lot as `(lot_id, utc_midnight_day, currency)` — input of the
/// recompute-all repair.
pub(crate) fn all_lot_rate_queries(conn: &Connection) -> Result<Vec<(i64, i64, String)>, Storage> {
    let mut stmt = conn
        .prepare(
            "SELECT l.id, l.purchase_unix, i.cost_basis_currency \
             FROM tracking_investment_lots l \
             JOIN tracking_investments i ON l.investment_id = i.id",
        )
        .map_err(storage_err)?;
    stmt.query_map([], |r| {
        Ok((
            r.get::<_, i64>(0)?,
            normalize_to_utc_midnight(r.get::<_, i64>(1)?),
            r.get::<_, String>(2)?,
        ))
    })
    .map_err(storage_err)?
    .collect::<Result<Vec<_>, _>>()
    .map_err(storage_err)
}

/// Write `btc_per_unit_at_purchase` for one lot. Returns `false` when the
/// lot vanished in the meantime.
pub(crate) fn apply_lot_btc_rate(
    conn: &Connection,
    lot_id: i64,
    rate: f64,
) -> Result<bool, Storage> {
    let n = conn
        .execute(
            "UPDATE tracking_investment_lots SET btc_per_unit_at_purchase = ?1 WHERE id = ?2",
            params![rate, lot_id],
        )
        .map_err(storage_err)?;
    Ok(n > 0)
}

/// `(utc_midnight_day, currency)` to price for one sell, or `None`.
pub(crate) fn sell_rate_query(
    conn: &Connection,
    sell_id: i64,
) -> Result<Option<(i64, String)>, Storage> {
    let mut stmt = conn
        .prepare(
            "SELECT s.sale_unix, i.cost_basis_currency \
             FROM tracking_investment_sells s \
             JOIN tracking_investments i ON s.investment_id = i.id \
             WHERE s.id = ?1",
        )
        .map_err(storage_err)?;
    let mut rows = stmt
        .query_map([sell_id], |r| {
            Ok((r.get::<_, i64>(0)?, r.get::<_, String>(1)?))
        })
        .map_err(storage_err)?;
    Ok(rows
        .next()
        .transpose()
        .map_err(storage_err)?
        .map(|(unix, ccy)| (normalize_to_utc_midnight(unix), ccy)))
}

/// Write `btc_per_unit_at_sale` for one sell and re-derive
/// `gain_loss_btc` for its disposals whose lot rate is known. Returns
/// `false` when the sell vanished in the meantime.
pub(crate) fn apply_sell_btc_rate(
    conn: &Connection,
    sell_id: i64,
    btc_at_sale: f64,
) -> Result<bool, Storage> {
    struct DisposalRow {
        id: i64,
        qty_consumed: f64,
        cost_basis_per_unit: f64,
        sale_price_per_unit: f64,
        btc_per_unit_at_purchase: Option<f64>,
    }

    let n = conn
        .execute(
            "UPDATE tracking_investment_sells SET btc_per_unit_at_sale = ?1 WHERE id = ?2",
            params![btc_at_sale, sell_id],
        )
        .map_err(storage_err)?;
    if n == 0 {
        return Ok(false);
    }

    let mut stmt = conn
        .prepare(
            "SELECT d.id, d.qty_consumed, d.cost_basis_per_unit, \
                    s.sale_price_per_unit, l.btc_per_unit_at_purchase \
             FROM tracking_lot_disposals d \
             JOIN tracking_investment_sells s ON d.sell_id = s.id \
             JOIN tracking_investment_lots l ON d.lot_id = l.id \
             WHERE d.sell_id = ?1",
        )
        .map_err(storage_err)?;

    let disposals: Vec<DisposalRow> = stmt
        .query_map([sell_id], |r| {
            Ok(DisposalRow {
                id: r.get(0)?,
                qty_consumed: r.get(1)?,
                cost_basis_per_unit: r.get(2)?,
                sale_price_per_unit: r.get(3)?,
                btc_per_unit_at_purchase: r.get(4)?,
            })
        })
        .map_err(storage_err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(storage_err)?;

    for d in disposals {
        if let Some(btc_at_purchase) = d.btc_per_unit_at_purchase {
            if btc_at_sale > 0.0 && btc_at_purchase > 0.0 {
                let gain_loss_btc = compute_gain_loss_btc(
                    d.qty_consumed,
                    d.cost_basis_per_unit,
                    btc_at_purchase,
                    d.sale_price_per_unit,
                    btc_at_sale,
                );
                conn.execute(
                    "UPDATE tracking_lot_disposals SET gain_loss_btc = ?1 WHERE id = ?2",
                    params![gain_loss_btc, d.id],
                )
                .map_err(storage_err)?;
            }
        }
    }

    Ok(true)
}

/// Inputs to price one manual asset's purchase snapshot.
#[derive(Debug, Clone, PartialEq)]
pub struct ManualAssetRateQuery {
    /// Raw purchase timestamp (persisted back unchanged).
    pub purchase_unix: i64,
    /// `purchase_value_fiat`, falling back to `value_fiat`.
    pub effective_value: f64,
    /// `purchase_currency`, falling back to `currency`.
    pub effective_currency: String,
    /// UTC-midnight day to price.
    pub day_unix: i64,
}

/// Inputs for one manual asset, or `None` when the asset does not exist
/// or has no `purchase_unix` (nothing to resolve).
pub(crate) fn manual_asset_rate_query(
    conn: &Connection,
    id: i64,
) -> Result<Option<ManualAssetRateQuery>, Storage> {
    let mut stmt = conn
        .prepare(
            "SELECT purchase_unix, purchase_value_fiat, purchase_currency, \
                    value_fiat, currency \
             FROM tracking_manual_assets WHERE id = ?1",
        )
        .map_err(storage_err)?;
    let mut rows = stmt
        .query_map([id], |r| {
            Ok((
                r.get::<_, Option<i64>>(0)?,
                r.get::<_, Option<f64>>(1)?,
                r.get::<_, Option<String>>(2)?,
                r.get::<_, f64>(3)?,
                r.get::<_, String>(4)?,
            ))
        })
        .map_err(storage_err)?;
    let Some((purchase_unix, purchase_value, purchase_ccy, value_fiat, currency)) =
        rows.next().transpose().map_err(storage_err)?
    else {
        return Ok(None);
    };
    // No purchase date → nothing to resolve.
    let Some(purchase_unix) = purchase_unix else {
        return Ok(None);
    };
    Ok(Some(ManualAssetRateQuery {
        purchase_unix,
        effective_value: purchase_value.unwrap_or(value_fiat),
        effective_currency: purchase_ccy.unwrap_or(currency),
        day_unix: normalize_to_utc_midnight(purchase_unix),
    }))
}

/// Persist the purchase snapshot for one manual asset. Returns `false`
/// when the asset vanished in the meantime.
pub(crate) fn apply_manual_asset_purchase_btc(
    conn: &Connection,
    id: i64,
    q: &ManualAssetRateQuery,
    rate: f64,
) -> Result<bool, Storage> {
    let n = conn
        .execute(
            "UPDATE tracking_manual_assets \
             SET purchase_unix = ?1, purchase_value_fiat = ?2, \
                 purchase_currency = ?3, purchase_btc_at_time = ?4 \
             WHERE id = ?5",
            params![
                q.purchase_unix,
                q.effective_value,
                q.effective_currency,
                rate,
                id
            ],
        )
        .map_err(storage_err)?;
    Ok(n > 0)
}

/// Resolve `btc_per_unit_at_purchase` for one lot from the rate repo.
///
/// Shared by `Registry::resolve_lot_btc_rate` and the recompute path.
/// Returns `Ok(true)` when a rate was written, `Ok(false)` when the lot
/// was not found or the rate was unavailable.
pub fn resolve_lot_btc_rate_impl<R: BtcRateHistoryRepository>(
    conn: &Connection,
    lot_id: i64,
    rate_repo: &R,
) -> Result<bool, Storage> {
    let Some((day, ccy)) = lot_rate_query(conn, lot_id)? else {
        return Ok(false);
    };
    match classify_rate(rate_repo.rate_at(day, &ccy), &format!("lot {lot_id}"))? {
        Some(rate) => apply_lot_btc_rate(conn, lot_id, rate),
        None => Ok(false),
    }
}

/// Resolve `btc_per_unit_at_sale` for one sell, then re-derive
/// `gain_loss_btc` for all its disposals.
///
/// Returns `Ok(true)` when the sell rate was written.
pub fn resolve_sell_btc_impl<R: BtcRateHistoryRepository>(
    conn: &Connection,
    sell_id: i64,
    rate_repo: &R,
) -> Result<bool, Storage> {
    let Some((day, ccy)) = sell_rate_query(conn, sell_id)? else {
        return Ok(false);
    };
    match classify_rate(rate_repo.rate_at(day, &ccy), &format!("sell {sell_id}"))? {
        Some(rate) => apply_sell_btc_rate(conn, sell_id, rate),
        None => Ok(false),
    }
}

/// Resolve `purchase_btc_at_time` for one manual asset.
///
/// Uses `purchase_unix` + `purchase_currency` (or falls back to
/// `currency` when `purchase_currency` is NULL). Does nothing and
/// returns `Ok(false)` when `purchase_unix` is NULL.
pub fn resolve_manual_asset_purchase_btc_impl<R: BtcRateHistoryRepository>(
    conn: &Connection,
    id: i64,
    rate_repo: &R,
) -> Result<bool, Storage> {
    let Some(q) = manual_asset_rate_query(conn, id)? else {
        return Ok(false);
    };
    match classify_rate(
        rate_repo.rate_at(q.day_unix, &q.effective_currency),
        &format!("manual asset {id}"),
    )? {
        Some(rate) => apply_manual_asset_purchase_btc(conn, id, &q, rate),
        None => Ok(false),
    }
}

/// Re-resolve `btc_per_unit_at_purchase` for every investment lot,
/// overwriting any prior value on success but preserving the existing
/// column when resolution fails (avoids wiping a good rate on a
/// transient error).
///
/// Uses [`BtcRateHistoryRepository::fresh_rate_at`] — the repair must
/// refetch, otherwise a bad cached rate would just be re-applied.
pub fn recompute_all_lot_btc_rates_impl<R: BtcRateHistoryRepository>(
    conn: &Connection,
    rate_repo: &R,
) -> Result<RecomputeStats, Storage> {
    let rows = all_lot_rate_queries(conn)?;
    let mut stats = RecomputeStats {
        considered: rows.len(),
        ..RecomputeStats::default()
    };

    for (id, day, ccy) in rows {
        // Hard error (network / parse) aborts the whole recompute so the
        // caller can retry later when the network recovers.
        match classify_rate(rate_repo.fresh_rate_at(day, &ccy), &format!("lot {id}"))? {
            Some(rate) => {
                apply_lot_btc_rate(conn, id, rate)?;
                stats.updated += 1;
            }
            // Rate unavailable: leave the existing column value (NULL or a
            // previously-written good rate).
            None => stats.failed += 1,
        }
    }

    Ok(stats)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::btc_rate_history::RatePoint;

    // -----------------------------------------------------------------------
    // Mock rate repository
    // -----------------------------------------------------------------------

    /// Cherry-picked known rates from Phase 8.1 + a synthetic "fail" date.
    struct MockRateRepo {
        /// Date (UTC midnight unix) and currency → rate. Returned as `Some`.
        rates: Vec<(i64, &'static str, f64)>,
        /// Date+currency pairs that return `Ok(None)` — simulates an
        /// unrecoverable rate (pre-genesis, future, etc.).
        none_pairs: Vec<(i64, &'static str)>,
    }

    impl MockRateRepo {
        fn standard() -> Self {
            // All timestamps stored as UTC-midnight values (pre-normalized).
            // normalize_to_utc_midnight(x) = (x / 86400) * 86400.
            //
            // 2024-04-19 UTC midnight = 1_713_484_800 (already midnight)
            // 2017-12-17 UTC midnight = 1_513_468_800 (already midnight)
            // 2020-11-13 UTC midnight = 1_605_225_600 (already midnight)
            // normalize(1_000_000) = 950_400  (1970-01-12)
            // normalize(2_000_000) = 1_987_200 (1970-01-24)
            // normalize(100)       = 0          (1970-01-01)
            //
            // None pair: (86_400, "JPY") → 1970-01-02 midnight, a date
            // distinct from all rate entries so no conflict.
            Self {
                rates: vec![
                    // 2024-04-19 BTC halving day — USD
                    (1_713_484_800, "USD", 63_800.0),
                    // 2017-12-17 ATH cycle 2 — USD
                    (1_513_468_800, "USD", 19_500.0),
                    // 2020-11-13 post-halving — EUR
                    (1_605_225_600, "EUR", 13_800.0),
                    // Extra rates needed by unit tests
                    (1_713_484_800, "EUR", 59_000.0),
                    // normalize(100) = 0 → UTC epoch day
                    (0, "EUR", 50_000.0),
                    // Apple storyboard: normalize(1_000_000) = 950_400
                    (950_400, "USD", 60_000.0),
                    // Apple storyboard: normalize(2_000_000) = 1_987_200
                    (1_987_200, "USD", 90_000.0),
                ],
                none_pairs: vec![
                    // Synthetic unrecoverable date: 1970-01-02 midnight
                    (86_400, "JPY"),
                ],
            }
        }
    }

    impl BtcRateHistoryRepository for MockRateRepo {
        fn rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError> {
            let norm = normalize_to_utc_midnight(date_unix);
            // Check explicit "none" pairs first.
            if self
                .none_pairs
                .iter()
                .any(|(d, c)| *d == norm && *c == currency)
            {
                return Ok(None);
            }
            // Find a matching rate.
            for (d, c, r) in &self.rates {
                if *d == norm && *c == currency {
                    return Ok(Some(*r));
                }
            }
            // Unknown date — return None (treated as soft-fail in backfill).
            Ok(None)
        }

        fn range(
            &self,
            _from_unix: i64,
            _to_unix: i64,
            _currency: &str,
        ) -> Result<Vec<RatePoint>, RateError> {
            Ok(vec![])
        }

        fn current(&self, _currency: &str) -> Result<f64, RateError> {
            Err(RateError::NetworkUnavailable)
        }

        fn backfill(
            &self,
            _requests: &[(i64, &str)],
        ) -> Result<crate::btc_rate_history::BackfillReport, RateError> {
            Ok(crate::btc_rate_history::BackfillReport::default())
        }
    }

    // -----------------------------------------------------------------------
    // DB helpers
    // -----------------------------------------------------------------------

    fn open_test_conn() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        // Enable FK constraints.
        conn.execute("PRAGMA foreign_keys = ON", []).unwrap();
        // Create the required tables.
        conn.execute_batch(
            "
            CREATE TABLE tracking_investments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                asset_type TEXT NOT NULL DEFAULT 'manual',
                ticker TEXT,
                quantity REAL NOT NULL,
                unit TEXT NOT NULL DEFAULT '',
                cost_basis_fiat REAL NOT NULL,
                cost_basis_currency TEXT NOT NULL DEFAULT 'USD',
                purchase_unix INTEGER NOT NULL,
                manual_current_value REAL,
                fetched_current_value REAL,
                last_priced_at_unix INTEGER,
                notes TEXT,
                cost_basis_method TEXT NOT NULL DEFAULT 'fifo',
                created_at_unix INTEGER NOT NULL
            );
            CREATE TABLE tracking_investment_lots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                investment_id INTEGER NOT NULL,
                quantity REAL NOT NULL,
                price_per_unit REAL NOT NULL,
                fees_fiat REAL NOT NULL DEFAULT 0,
                purchase_unix INTEGER NOT NULL,
                notes TEXT,
                created_at_unix INTEGER NOT NULL,
                FOREIGN KEY (investment_id) REFERENCES tracking_investments(id) ON DELETE CASCADE
            );
            CREATE TABLE tracking_investment_sells (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                investment_id INTEGER NOT NULL,
                sale_unix INTEGER NOT NULL,
                sale_price_per_unit REAL NOT NULL,
                sale_qty REAL NOT NULL,
                fees_fiat REAL NOT NULL DEFAULT 0,
                notes TEXT,
                created_at_unix INTEGER NOT NULL,
                FOREIGN KEY (investment_id) REFERENCES tracking_investments(id) ON DELETE CASCADE
            );
            CREATE TABLE tracking_lot_disposals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sell_id INTEGER NOT NULL,
                lot_id INTEGER NOT NULL,
                qty_consumed REAL NOT NULL,
                cost_basis_per_unit REAL NOT NULL,
                gain_loss_fiat REAL NOT NULL,
                created_at_unix INTEGER NOT NULL,
                FOREIGN KEY (sell_id) REFERENCES tracking_investment_sells(id) ON DELETE CASCADE,
                FOREIGN KEY (lot_id) REFERENCES tracking_investment_lots(id) ON DELETE CASCADE
            );
            CREATE TABLE tracking_manual_assets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'cash',
                value_fiat REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'EUR',
                display_label TEXT,
                notes TEXT,
                last_updated_unix INTEGER NOT NULL,
                created_at_unix INTEGER NOT NULL
            );
            ",
        )
        .unwrap();
        apply_8_2_migrations(&conn).unwrap();
        conn
    }

    fn insert_investment(conn: &Connection, currency: &str) -> i64 {
        conn.execute(
            "INSERT INTO tracking_investments \
             (name, quantity, cost_basis_fiat, cost_basis_currency, purchase_unix, created_at_unix) \
             VALUES ('AAPL', 10.0, 1500.0, ?1, 0, 0)",
            params![currency],
        )
        .unwrap();
        conn.last_insert_rowid()
    }

    fn insert_lot(conn: &Connection, investment_id: i64, purchase_unix: i64) -> i64 {
        conn.execute(
            "INSERT INTO tracking_investment_lots \
             (investment_id, quantity, price_per_unit, fees_fiat, purchase_unix, created_at_unix) \
             VALUES (?1, 10.0, 150.0, 0.0, ?2, 0)",
            params![investment_id, purchase_unix],
        )
        .unwrap();
        conn.last_insert_rowid()
    }

    fn insert_sell(conn: &Connection, investment_id: i64, sale_unix: i64) -> i64 {
        conn.execute(
            "INSERT INTO tracking_investment_sells \
             (investment_id, sale_unix, sale_price_per_unit, sale_qty, fees_fiat, created_at_unix) \
             VALUES (?1, ?2, 200.0, 3.0, 0.0, 0)",
            params![investment_id, sale_unix],
        )
        .unwrap();
        conn.last_insert_rowid()
    }

    fn insert_disposal(conn: &Connection, sell_id: i64, lot_id: i64) -> i64 {
        conn.execute(
            "INSERT INTO tracking_lot_disposals \
             (sell_id, lot_id, qty_consumed, cost_basis_per_unit, gain_loss_fiat, created_at_unix) \
             VALUES (?1, ?2, 3.0, 150.0, 150.0, 0)",
            params![sell_id, lot_id],
        )
        .unwrap();
        conn.last_insert_rowid()
    }

    fn insert_manual_asset(conn: &Connection, created_at: i64, currency: &str) -> i64 {
        conn.execute(
            "INSERT INTO tracking_manual_assets \
             (name, kind, value_fiat, currency, last_updated_unix, created_at_unix) \
             VALUES ('Flat', 'real_estate', 250000.0, ?1, 0, ?2)",
            params![currency, created_at],
        )
        .unwrap();
        conn.last_insert_rowid()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    #[test]
    fn migration_adds_btc_at_purchase_column_idempotent() {
        let conn = Connection::open_in_memory().unwrap();
        // Create the base table (without the new column).
        conn.execute_batch(
            "CREATE TABLE tracking_investment_lots (
                id INTEGER PRIMARY KEY,
                investment_id INTEGER NOT NULL,
                quantity REAL NOT NULL,
                price_per_unit REAL NOT NULL,
                fees_fiat REAL NOT NULL DEFAULT 0,
                purchase_unix INTEGER NOT NULL,
                notes TEXT,
                created_at_unix INTEGER NOT NULL
             );",
        )
        .unwrap();

        // First call — adds the column.
        apply_8_2_migrations(&conn).unwrap();
        // Second call — must not error (duplicate column name swallowed).
        apply_8_2_migrations(&conn).unwrap();

        // Verify column exists by inserting a row with it.
        conn.execute(
            "INSERT INTO tracking_investment_lots \
             (investment_id, quantity, price_per_unit, fees_fiat, purchase_unix, \
              created_at_unix, btc_per_unit_at_purchase) \
             VALUES (1, 1.0, 100.0, 0.0, 0, 0, 63000.0)",
            [],
        )
        .unwrap();
    }

    #[test]
    fn migration_adds_4_manual_asset_columns_idempotent() {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE tracking_manual_assets (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'cash',
                value_fiat REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'EUR',
                display_label TEXT,
                notes TEXT,
                last_updated_unix INTEGER NOT NULL,
                created_at_unix INTEGER NOT NULL
             );
             CREATE TABLE tracking_investment_lots (
                id INTEGER PRIMARY KEY,
                investment_id INTEGER NOT NULL,
                quantity REAL NOT NULL,
                price_per_unit REAL NOT NULL,
                fees_fiat REAL NOT NULL DEFAULT 0,
                purchase_unix INTEGER NOT NULL,
                notes TEXT,
                created_at_unix INTEGER NOT NULL
             );
             CREATE TABLE tracking_investment_sells (
                id INTEGER PRIMARY KEY,
                investment_id INTEGER NOT NULL,
                sale_unix INTEGER NOT NULL,
                sale_price_per_unit REAL NOT NULL,
                sale_qty REAL NOT NULL,
                fees_fiat REAL NOT NULL DEFAULT 0,
                notes TEXT,
                created_at_unix INTEGER NOT NULL
             );
             CREATE TABLE tracking_lot_disposals (
                id INTEGER PRIMARY KEY,
                sell_id INTEGER NOT NULL,
                lot_id INTEGER NOT NULL,
                qty_consumed REAL NOT NULL,
                cost_basis_per_unit REAL NOT NULL,
                gain_loss_fiat REAL NOT NULL,
                created_at_unix INTEGER NOT NULL
             );",
        )
        .unwrap();

        apply_8_2_migrations(&conn).unwrap();
        // Idempotent.
        apply_8_2_migrations(&conn).unwrap();

        // Verify all 4 columns exist.
        conn.execute(
            "INSERT INTO tracking_manual_assets \
             (name, kind, value_fiat, currency, last_updated_unix, created_at_unix,
              purchase_unix, purchase_value_fiat, purchase_currency, purchase_btc_at_time) \
             VALUES ('X', 'cash', 1.0, 'EUR', 0, 0, 1000, 1.0, 'EUR', 50000.0)",
            [],
        )
        .unwrap();
    }

    #[test]
    fn backfill_lots_with_known_rate_populates_btc_at_purchase() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "USD");
        // purchase_unix = 1_713_484_800 → known rate $63_800
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800);

        let report = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert_eq!(report.investment_lots.fetched, 1);
        assert_eq!(report.investment_lots.failed, 0);
        assert_eq!(report.investment_lots.considered, 1);

        let rate: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!((rate - 63_800.0).abs() < 1.0);
    }

    #[test]
    fn backfill_lots_with_unrecoverable_rate_leaves_null_and_reports_failed() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        // purchase_unix = 0 (1970-01-01) in JPY → mock returns None.
        let inv_id = insert_investment(&conn, "JPY");
        let lot_id = insert_lot(&conn, inv_id, 0);

        let report = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert_eq!(report.investment_lots.failed, 1);
        assert_eq!(report.investment_lots.fetched, 0);

        let rate: Option<f64> = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(rate.is_none(), "unrecoverable rate must leave column NULL");
    }

    #[test]
    fn backfill_manual_asset_legacy_falls_back_to_created_at_unix() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        // Asset without purchase_unix — the fallback uses created_at_unix = 100.
        let asset_id = insert_manual_asset(&conn, 100, "EUR");

        let report = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert_eq!(report.manual_assets.fetched, 1);

        // purchase_unix must have been written as 100 (the created_at_unix).
        let purchase_unix: i64 = conn
            .query_row(
                "SELECT purchase_unix FROM tracking_manual_assets WHERE id = ?1",
                [asset_id],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(purchase_unix, 100);

        // purchase_btc_at_time must be set (mock returns 50_000 for unix=100, EUR).
        let btc_rate: Option<f64> = conn
            .query_row(
                "SELECT purchase_btc_at_time FROM tracking_manual_assets WHERE id = ?1",
                [asset_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(btc_rate.is_some(), "purchase_btc_at_time must be populated");
    }

    #[test]
    fn backfill_disposals_derives_gain_loss_btc_from_parent_snapshots() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        // Apple storyboard: lot 10 AAPL @ $150 when BTC = $60k,
        // sell 3 @ $200 when BTC = $90k.
        // purchase_unix = 1_000_000 → mock returns $60k
        // sale_unix    = 2_000_000 → mock returns $90k
        let inv_id = insert_investment(&conn, "USD");
        let lot_id = insert_lot(&conn, inv_id, 1_000_000);
        let sell_id = insert_sell(&conn, inv_id, 2_000_000);
        let disposal_id = insert_disposal(&conn, sell_id, lot_id);

        let report = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert_eq!(report.investment_lots.fetched, 1);
        assert_eq!(report.investment_sells.fetched, 1);
        assert_eq!(report.lot_disposals.fetched, 1);

        let gain_loss: f64 = conn
            .query_row(
                "SELECT gain_loss_btc FROM tracking_lot_disposals WHERE id = ?1",
                [disposal_id],
                |r| r.get(0),
            )
            .unwrap();

        // cost_btc = 3 × 150 / 60_000 = 0.0075
        // sale_btc = 3 × 200 / 90_000 ≈ 0.006667
        // gain     ≈ −0.000833
        let expected = -3.0 * 150.0 / 60_000.0 + 3.0 * 200.0 / 90_000.0;
        assert!(
            (gain_loss - expected).abs() < 1e-9,
            "gain_loss_btc = {gain_loss:.10}, expected ≈ {expected:.10}"
        );
    }

    #[test]
    fn backfill_disposal_with_null_parent_btc_leaves_gain_loss_btc_null() {
        let conn = open_test_conn();
        // Use a repo that has NO rates — so lots/sells will not be backfilled,
        // then disposals cannot be computed.
        let empty_repo = MockRateRepo {
            rates: vec![],
            none_pairs: vec![],
        };

        let inv_id = insert_investment(&conn, "USD");
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800);
        let sell_id = insert_sell(&conn, inv_id, 1_713_484_800);
        let disposal_id = insert_disposal(&conn, sell_id, lot_id);

        let report = phase_8_2_backfill_btc_at_time(&conn, &empty_repo).unwrap();
        // Lots and sells failed (no rates), so disposal also fails.
        assert_eq!(report.lot_disposals.failed, 1);
        assert_eq!(report.lot_disposals.fetched, 0);

        let gain_loss: Option<f64> = conn
            .query_row(
                "SELECT gain_loss_btc FROM tracking_lot_disposals WHERE id = ?1",
                [disposal_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(gain_loss.is_none());
    }

    #[test]
    fn backfill_idempotent_across_runs() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "USD");
        insert_lot(&conn, inv_id, 1_713_484_800);
        insert_manual_asset(&conn, 100, "EUR");

        // First run: backfills everything.
        let r1 = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert!(r1.investment_lots.fetched > 0 || r1.investment_lots.failed > 0);

        // Second run: everything already done, considered = 0.
        let r2 = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert_eq!(r2.investment_lots.considered, 0);
        assert_eq!(r2.investment_sells.considered, 0);
        assert_eq!(r2.lot_disposals.considered, 0);
        assert_eq!(r2.manual_assets.considered, 0);
        // already_done should match how many rows were successfully backfilled.
        assert_eq!(r2.investment_lots.already_done, r1.investment_lots.fetched);
        assert_eq!(r2.manual_assets.already_done, r1.manual_assets.fetched);
    }

    #[test]
    fn backfill_dedupes_rate_fetches_for_same_date_currency_pair() {
        // Two lots at the same purchase date + currency should both succeed,
        // and the mock (which always returns the same rate) satisfies them both.
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "USD");
        let lot1 = insert_lot(&conn, inv_id, 1_713_484_800);
        let lot2 = insert_lot(&conn, inv_id, 1_713_484_800);

        let report = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert_eq!(report.investment_lots.fetched, 2);

        // Both lots must have the same rate.
        for lot_id in [lot1, lot2] {
            let rate: f64 = conn
                .query_row(
                    "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                    [lot_id],
                    |r| r.get(0),
                )
                .unwrap();
            assert!((rate - 63_800.0).abs() < 1.0);
        }
    }

    #[test]
    fn gain_loss_btc_formula_matches_storyboard_example() {
        // §4.3 example: 10 AAPL @ $150 when BTC = $60k, sell 3 @ $200 when BTC = $90k.
        // gain_loss_fiat = (200 − 150) × 3 = +$150.
        // gain_loss_btc  ≈ −0.000833 BTC (you spent MORE BTC at purchase than you received).
        let result = compute_gain_loss_btc(
            3.0,      // qty_consumed
            150.0,    // cost_basis_per_unit
            60_000.0, // btc_per_unit_at_purchase
            200.0,    // sale_price_per_unit
            90_000.0, // btc_per_unit_at_sale
        );

        // cost_btc = 3 * 150 / 60000 = 0.0075
        // sale_btc = 3 * 200 / 90000 = 0.006667
        // gain     = 0.006667 - 0.0075 = −0.000833...
        let expected: f64 = 3.0 * 200.0 / 90_000.0 - 3.0 * 150.0 / 60_000.0;
        assert!(
            (result - expected).abs() < 1e-10,
            "formula result = {result:.10}, expected = {expected:.10}"
        );
        // Storyboard says ≈ −0.00083:
        assert!(
            result < 0.0,
            "gain_loss_btc must be negative in the Apple storyboard"
        );
        assert!(
            (result - (-0.000_833_333)).abs() < 1e-6,
            "gain_loss_btc ≈ −0.000833, got {result}"
        );
    }

    #[test]
    fn backfill_migration_on_existing_db_without_new_columns_then_backfill() {
        // Simulate a Phase 7.x DB: tables exist but without the Phase 8.2
        // columns. Migration adds them; data is preserved.
        let conn = Connection::open_in_memory().unwrap();
        conn.execute("PRAGMA foreign_keys = ON", []).unwrap();
        conn.execute_batch(
            "CREATE TABLE tracking_investments (
                id INTEGER PRIMARY KEY, name TEXT NOT NULL, quantity REAL NOT NULL,
                cost_basis_fiat REAL NOT NULL, cost_basis_currency TEXT NOT NULL DEFAULT 'USD',
                purchase_unix INTEGER NOT NULL, created_at_unix INTEGER NOT NULL,
                asset_type TEXT NOT NULL DEFAULT 'manual', ticker TEXT, unit TEXT NOT NULL DEFAULT '',
                manual_current_value REAL, fetched_current_value REAL, last_priced_at_unix INTEGER,
                notes TEXT, cost_basis_method TEXT NOT NULL DEFAULT 'fifo'
             );
             CREATE TABLE tracking_investment_lots (
                id INTEGER PRIMARY KEY, investment_id INTEGER NOT NULL,
                quantity REAL NOT NULL, price_per_unit REAL NOT NULL,
                fees_fiat REAL NOT NULL DEFAULT 0, purchase_unix INTEGER NOT NULL,
                notes TEXT, created_at_unix INTEGER NOT NULL
             );
             CREATE TABLE tracking_investment_sells (
                id INTEGER PRIMARY KEY, investment_id INTEGER NOT NULL,
                sale_unix INTEGER NOT NULL, sale_price_per_unit REAL NOT NULL,
                sale_qty REAL NOT NULL, fees_fiat REAL NOT NULL DEFAULT 0,
                notes TEXT, created_at_unix INTEGER NOT NULL
             );
             CREATE TABLE tracking_lot_disposals (
                id INTEGER PRIMARY KEY, sell_id INTEGER NOT NULL,
                lot_id INTEGER NOT NULL, qty_consumed REAL NOT NULL,
                cost_basis_per_unit REAL NOT NULL, gain_loss_fiat REAL NOT NULL,
                created_at_unix INTEGER NOT NULL
             );
             CREATE TABLE tracking_manual_assets (
                id INTEGER PRIMARY KEY, name TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'cash', value_fiat REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'EUR', display_label TEXT,
                notes TEXT, last_updated_unix INTEGER NOT NULL, created_at_unix INTEGER NOT NULL
             );",
        )
        .unwrap();

        // Pre-8.2 data.
        conn.execute(
            "INSERT INTO tracking_investments (name, quantity, cost_basis_fiat, cost_basis_currency, purchase_unix, created_at_unix) VALUES ('AAPL', 10.0, 1500.0, 'USD', 0, 0)",
            [],
        ).unwrap();
        let inv_id = conn.last_insert_rowid();
        conn.execute(
            "INSERT INTO tracking_investment_lots (investment_id, quantity, price_per_unit, fees_fiat, purchase_unix, created_at_unix) VALUES (?1, 10.0, 150.0, 0.0, ?2, 0)",
            params![inv_id, 1_713_484_800_i64],
        ).unwrap();

        // Migration: adds new columns without destroying existing data.
        apply_8_2_migrations(&conn).unwrap();

        // Verify existing data still there.
        let count: i64 = conn
            .query_row("SELECT COUNT(*) FROM tracking_investment_lots", [], |r| {
                r.get(0)
            })
            .unwrap();
        assert_eq!(count, 1);

        // Now backfill.
        let repo = MockRateRepo::standard();
        let report = phase_8_2_backfill_btc_at_time(&conn, &repo).unwrap();
        assert_eq!(report.investment_lots.fetched, 1);
    }

    // -----------------------------------------------------------------------
    // Per-row resolve helpers
    // -----------------------------------------------------------------------

    /// Insert a manual asset with explicit purchase fields (Phase 8.2).
    fn insert_manual_asset_with_purchase(
        conn: &Connection,
        purchase_unix: Option<i64>,
        purchase_value_fiat: Option<f64>,
        purchase_currency: Option<&str>,
        currency: &str,
    ) -> i64 {
        conn.execute(
            "INSERT INTO tracking_manual_assets \
             (name, kind, value_fiat, currency, last_updated_unix, created_at_unix, \
              purchase_unix, purchase_value_fiat, purchase_currency) \
             VALUES ('Flat', 'real_estate', 250000.0, ?1, 0, 0, ?2, ?3, ?4)",
            params![
                currency,
                purchase_unix,
                purchase_value_fiat,
                purchase_currency
            ],
        )
        .unwrap();
        conn.last_insert_rowid()
    }

    #[test]
    fn resolve_lot_btc_rate_writes_historical_rate_not_current() {
        // Verifies that the rate written is keyed on the lot's purchase_unix,
        // NOT on the current timestamp ("now"). The mock returns 63_800 for
        // 2024-04-19 (1_713_484_800) and a different rate for other dates.
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "USD");
        // purchase_unix = halving day 2024-04-19 → BTC/USD 63_800
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800);

        let wrote = resolve_lot_btc_rate_impl(&conn, lot_id, &repo).unwrap();
        assert!(wrote, "should have written a rate");

        let rate: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            (rate - 63_800.0).abs() < 1.0,
            "expected historical rate 63_800, got {rate}"
        );
    }

    #[test]
    fn resolve_lot_btc_rate_uses_own_currency_not_dashboard_currency() {
        // A EUR lot resolves BTC/EUR, independent of any global "dashboard"
        // currency setting. The mock has EUR at 1_713_484_800 = 59_000.
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "EUR");
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800);

        let wrote = resolve_lot_btc_rate_impl(&conn, lot_id, &repo).unwrap();
        assert!(wrote);

        let rate: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            (rate - 59_000.0).abs() < 1.0,
            "expected EUR rate 59_000, got {rate}"
        );
    }

    #[test]
    fn resolve_lot_btc_rate_leaves_null_when_rate_unavailable() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard(); // JPY at any date returns None

        let inv_id = insert_investment(&conn, "JPY");
        let lot_id = insert_lot(&conn, inv_id, 86_400); // 1970-01-02 JPY → None

        let wrote = resolve_lot_btc_rate_impl(&conn, lot_id, &repo).unwrap();
        assert!(!wrote, "should not have written a rate");

        let rate: Option<f64> = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            rate.is_none(),
            "column must remain NULL when rate unavailable"
        );
    }

    #[test]
    fn resolve_lot_btc_rate_returns_false_for_unknown_lot() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();
        let wrote = resolve_lot_btc_rate_impl(&conn, 99_999, &repo).unwrap();
        assert!(!wrote, "unknown lot_id should return false without error");
    }

    #[test]
    fn resolve_lot_btc_rate_idempotent_on_second_call() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "USD");
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800);

        let wrote1 = resolve_lot_btc_rate_impl(&conn, lot_id, &repo).unwrap();
        let rate1: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();

        let wrote2 = resolve_lot_btc_rate_impl(&conn, lot_id, &repo).unwrap();
        let rate2: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();

        assert!(wrote1 && wrote2);
        assert!(
            (rate1 - rate2).abs() < f64::EPSILON,
            "rate must be stable across calls"
        );
    }

    #[test]
    fn resolve_sell_btc_writes_sale_rate_and_gain_loss_for_disposal() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        // purchase 1_000_000 (BTC/USD = 60_000), sale 2_000_000 (BTC/USD = 90_000)
        let inv_id = insert_investment(&conn, "USD");
        let lot_id = insert_lot(&conn, inv_id, 1_000_000);
        let sell_id = insert_sell(&conn, inv_id, 2_000_000);
        let disposal_id = insert_disposal(&conn, sell_id, lot_id);

        // First resolve the lot rate so the disposal gain_loss_btc can be computed.
        resolve_lot_btc_rate_impl(&conn, lot_id, &repo).unwrap();

        let wrote = resolve_sell_btc_impl(&conn, sell_id, &repo).unwrap();
        assert!(wrote);

        let btc_at_sale: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_sale FROM tracking_investment_sells WHERE id = ?1",
                [sell_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!((btc_at_sale - 90_000.0).abs() < 1.0);

        let gain_loss: f64 = conn
            .query_row(
                "SELECT gain_loss_btc FROM tracking_lot_disposals WHERE id = ?1",
                [disposal_id],
                |r| r.get(0),
            )
            .unwrap();
        let expected = compute_gain_loss_btc(3.0, 150.0, 60_000.0, 200.0, 90_000.0);
        assert!(
            (gain_loss - expected).abs() < 1e-9,
            "gain_loss_btc mismatch: got {gain_loss}, expected {expected}"
        );
    }

    #[test]
    fn resolve_sell_btc_leaves_null_when_rate_unavailable() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard(); // JPY at any date returns None

        let inv_id = insert_investment(&conn, "JPY");
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800);
        let sell_id = insert_sell(&conn, inv_id, 86_400); // date returns None
        let _disposal_id = insert_disposal(&conn, sell_id, lot_id);

        let wrote = resolve_sell_btc_impl(&conn, sell_id, &repo).unwrap();
        assert!(!wrote);

        let btc_at_sale: Option<f64> = conn
            .query_row(
                "SELECT btc_per_unit_at_sale FROM tracking_investment_sells WHERE id = ?1",
                [sell_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(btc_at_sale.is_none());
    }

    #[test]
    fn resolve_manual_asset_purchase_btc_writes_rate_for_explicit_purchase_unix() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        // Asset with explicit purchase_unix = 1_713_484_800, EUR → 59_000
        let asset_id = insert_manual_asset_with_purchase(
            &conn,
            Some(1_713_484_800),
            Some(250_000.0),
            Some("EUR"),
            "EUR",
        );

        let wrote = resolve_manual_asset_purchase_btc_impl(&conn, asset_id, &repo).unwrap();
        assert!(wrote);

        let btc_rate: f64 = conn
            .query_row(
                "SELECT purchase_btc_at_time FROM tracking_manual_assets WHERE id = ?1",
                [asset_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            (btc_rate - 59_000.0).abs() < 1.0,
            "expected EUR rate at halving day 59_000, got {btc_rate}"
        );
    }

    #[test]
    fn resolve_manual_asset_purchase_btc_does_nothing_when_no_purchase_unix() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        // No purchase_unix → nothing to resolve.
        let asset_id = insert_manual_asset_with_purchase(&conn, None, None, None, "EUR");

        let wrote = resolve_manual_asset_purchase_btc_impl(&conn, asset_id, &repo).unwrap();
        assert!(!wrote, "should return false when purchase_unix is NULL");

        let btc_rate: Option<f64> = conn
            .query_row(
                "SELECT purchase_btc_at_time FROM tracking_manual_assets WHERE id = ?1",
                [asset_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(btc_rate.is_none());
    }

    #[test]
    fn resolve_manual_asset_purchase_btc_falls_back_to_currency_when_purchase_currency_null() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        // purchase_unix set but purchase_currency is NULL → fallback to currency
        // mock has EUR at 1_713_484_800 = 59_000
        let asset_id = insert_manual_asset_with_purchase(
            &conn,
            Some(1_713_484_800),
            Some(200_000.0),
            None, // purchase_currency = NULL
            "EUR",
        );

        let wrote = resolve_manual_asset_purchase_btc_impl(&conn, asset_id, &repo).unwrap();
        assert!(wrote);

        let btc_rate: f64 = conn
            .query_row(
                "SELECT purchase_btc_at_time FROM tracking_manual_assets WHERE id = ?1",
                [asset_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!((btc_rate - 59_000.0).abs() < 1.0);
    }

    #[test]
    fn recompute_all_lot_btc_rates_overwrites_wrong_seeded_value() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "USD");
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800); // correct rate = 63_800

        // Seed a wrong value (simulates the "current rate at add time" bug).
        conn.execute(
            "UPDATE tracking_investment_lots SET btc_per_unit_at_purchase = 99999.0 WHERE id = ?1",
            [lot_id],
        )
        .unwrap();

        let stats = recompute_all_lot_btc_rates_impl(&conn, &repo).unwrap();
        assert_eq!(stats.considered, 1);
        assert_eq!(stats.updated, 1);
        assert_eq!(stats.failed, 0);

        let rate: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            (rate - 63_800.0).abs() < 1.0,
            "recompute must overwrite wrong value with historical rate, got {rate}"
        );
    }

    #[test]
    fn recompute_preserves_existing_when_resolution_fails() {
        let conn = open_test_conn();
        // Use an empty repo that never returns rates.
        let empty_repo = MockRateRepo {
            rates: vec![],
            none_pairs: vec![],
        };

        let inv_id = insert_investment(&conn, "USD");
        let lot_id = insert_lot(&conn, inv_id, 1_713_484_800);

        // Seed a "good" value already.
        let seeded_rate: f64 = 63_800.0;
        conn.execute(
            "UPDATE tracking_investment_lots SET btc_per_unit_at_purchase = ?2 WHERE id = ?1",
            rusqlite::params![lot_id, seeded_rate],
        )
        .unwrap();

        let stats = recompute_all_lot_btc_rates_impl(&conn, &empty_repo).unwrap();
        assert_eq!(stats.considered, 1);
        assert_eq!(stats.updated, 0);
        assert_eq!(stats.failed, 1);

        // The previously-written good value must survive a failed recompute.
        let rate: f64 = conn
            .query_row(
                "SELECT btc_per_unit_at_purchase FROM tracking_investment_lots WHERE id = ?1",
                [lot_id],
                |r| r.get(0),
            )
            .unwrap();
        assert!(
            (rate - 63_800.0).abs() < 1.0,
            "good prior rate must be preserved when resolution fails, got {rate}"
        );
    }

    #[test]
    fn recompute_idempotent_on_two_calls() {
        let conn = open_test_conn();
        let repo = MockRateRepo::standard();

        let inv_id = insert_investment(&conn, "USD");
        insert_lot(&conn, inv_id, 1_713_484_800);

        let s1 = recompute_all_lot_btc_rates_impl(&conn, &repo).unwrap();
        let s2 = recompute_all_lot_btc_rates_impl(&conn, &repo).unwrap();

        assert_eq!(s1.considered, s2.considered);
        assert_eq!(s1.updated, s2.updated);
        assert_eq!(s1.failed, s2.failed);
    }
}
