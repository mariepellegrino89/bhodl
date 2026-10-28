//! Net-worth historical reconstruction — Phase 8.4.
//!
//! Provides `reconstruct_net_worth_history`, which walks every UTC
//! midnight between `from_unix` and `to_unix` and computes a daily
//! `NwSnapshot` by summing four contributors:
//!
//!  1. **On-chain balance** — cumulative `amount_sat` of all confirmed
//!     `PaymentEventLite` events with `block_time_unix <= t`.
//!  2. **Investments** — for each lot with `purchase_unix <= t`, the
//!     lot's value is approximated as `quantity × current_value_per_unit`
//!     (Phase 8.4 approximation: value constant over time; historical
//!     valuation arrives in a later phase). The fiat value is converted
//!     to sat via the BTC rate at `t`.
//!  3. **Manual assets** — same approximation: `value_fiat` (current)
//!     used as proxy for historical value, converted to sat via rate at
//!     `t`. An asset only appears once its `purchase_unix` (or
//!     `created_at_unix` when `purchase_unix` is NULL) has passed.
//!  4. **Liabilities** — a mortgage or loan with a plan follows its
//!     amortization schedule ([`crate::loan_schedule`]): the principal
//!     outstanding that day, in its currency, converted at that day's
//!     rate (0 before its start). Any other debt is its current balance
//!     with the later manual entries undone: payments made after the day
//!     are added back, card spending after it taken off.
//!
//! Rate resolution uses `BtcRateHistoryRepository` with a bulk prefetch
//! at the start of the call (one `range()` per currency); the per-day loop
//! reads the cache only (`cached_rate_at`). When it returns `Ok(None)` for any
//! contributor that needs a rate, that contributor contributes 0 sat to
//! the snapshot for that day (soft-fail, no error). The sparkline may
//! show a "flatter" section for days with missing rate data, but it
//! never returns an error for rate gaps.
//!
//! Design notes on the on-chain side: `PaymentEventLite` is a stripped
//! struct carrying only the fields needed for balance reconstruction
//! (`amount_sat` signed + `block_time_unix` optional). The FFI caller in
//! `bhodl-uniffi` is responsible for opening each watch-state file,
//! converting to `PaymentEventLite`, and passing the slice in; the
//! reconstruction algorithm is pure and testable without touching disk.

#![allow(clippy::cast_possible_truncation)]
#![allow(clippy::cast_sign_loss)]
#![allow(clippy::cast_precision_loss)] // i64 → f64: sat values fit fine at net-worth scale
#![allow(clippy::map_unwrap_or)]

use crate::btc_rate_history::{BtcRateHistoryRepository, normalize_to_utc_midnight};
use crate::error::OnchainError;
use crate::loan_schedule::{LoanSchedule, load_loan_schedules};
use crate::registry::{
    ManualTxn, Registry, TrackingInvestment, TrackingInvestmentLot, TrackingLiability,
    TrackingManualAsset,
};

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SECS_PER_DAY: i64 = 86_400;

/// Earliest day the window may start at: 2009-01-03 (genesis block).
const GENESIS_DAY_UNIX: i64 = 1_230_940_800;

/// Longest window served in one call (~10 years). Bounds the per-day
/// loop and the output size whatever the caller passes in.
const MAX_WINDOW_DAYS: i64 = 3_700;

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/// One daily net-worth snapshot. All `_sat` fields are in the sat unit.
/// `total_sat` can be negative when liabilities exceed assets.
#[derive(Debug, Clone, PartialEq)]
pub struct NwSnapshot {
    /// UTC midnight Unix timestamp for this day.
    pub date_unix: i64,
    /// Net worth = onchain + investments + `manual_assets` − liabilities.
    /// Can be negative.
    pub total_sat: i64,
    pub onchain_sat: i64,
    pub investments_sat: i64,
    pub manual_assets_sat: i64,
    /// Subtracted from the total (positive value = debt).
    pub liabilities_sat: i64,
    /// `total_sat × rate / 1e8` — 0.0 when rate is unavailable.
    pub fiat_equivalent: f64,
    /// 1 BTC = N units of dashboard currency at this day's rate.
    /// 0.0 when rate is unavailable.
    pub rate_btc_fiat: f64,
}

/// Stripped on-chain event record for NW reconstruction.
///
/// `amount_sat` is **signed**: positive for incoming transactions,
/// negative for outgoing. `block_time_unix` is `None` while the
/// transaction is still unconfirmed (those are excluded by the filter in
/// `onchain_balance_at`).
#[derive(Debug, Clone, PartialEq)]
pub struct PaymentEventLite {
    /// Signed net sat flow: +incoming, −outgoing.
    pub amount_sat: i64,
    /// Unix timestamp of the containing block. `None` for unconfirmed.
    pub block_time_unix: Option<i64>,
}

// ---------------------------------------------------------------------------
// Main reconstruction function
// ---------------------------------------------------------------------------

/// Reconstruct the daily net-worth history between `from_unix` and
/// `to_unix` (both inclusive, normalized to UTC midnight).
///
/// # Parameters
///
/// - `registry` — open registry handle; provides investment lots,
///   manual assets, liabilities, and investment metadata.
/// - `rate_repo` — BTC/fiat rate repository; used for the bulk prefetch
///   and per-day rate lookups.
/// - `events` — pre-loaded on-chain payment events (all wallets merged);
///   the caller is responsible for opening watch-state files.
/// - `from_unix`, `to_unix` — query window (any time-of-day; normalized
///   internally to UTC midnight).
/// - `currency` — ISO 4217 fiat currency for BTC conversion.
///
/// # Errors
///
/// - [`OnchainError::InvalidArgument`] when `to_unix < from_unix` after
///   normalization.
///
/// The window is clamped before any work: `to` ≤ now + 1 day, `from` ≥
/// 2009-01-03, and at most [`MAX_WINDOW_DAYS`] days (the most recent ones
/// are kept). Rates are prefetched once per currency via
/// `BtcRateHistoryRepository::range`; the per-day loop then reads the
/// cache only (`cached_rate_at`), so it never issues network requests.
/// - [`OnchainError::Storage`] on DB query failures.
///
/// Rate gaps (days with no cached rate) are soft-failed and contribute
/// zero fiat-denominated contributors to that day's snapshot — no error.
pub fn reconstruct_net_worth_history<R: BtcRateHistoryRepository>(
    registry: &Registry,
    rate_repo: &R,
    events: &[PaymentEventLite],
    from_unix: i64,
    to_unix: i64,
    currency: &str,
) -> Result<Vec<NwSnapshot>, OnchainError> {
    let from_norm = normalize_to_utc_midnight(from_unix);
    let to_norm = normalize_to_utc_midnight(to_unix);

    if to_norm < from_norm {
        return Err(OnchainError::InvalidArgument(
            "net_worth_history: to_unix must be >= from_unix".to_string(),
        ));
    }

    // --- Step 0: clamp the window (M8) ---
    let latest =
        normalize_to_utc_midnight(crate::btc_rate_history::now_unix().saturating_add(SECS_PER_DAY));
    let to_norm = to_norm.min(latest);
    let from_norm = from_norm
        .max(GENESIS_DAY_UNIX)
        .max(to_norm.saturating_sub((MAX_WINDOW_DAYS - 1).saturating_mul(SECS_PER_DAY)));
    if to_norm < from_norm {
        // Whole window lies before genesis (or after the clamp): nothing to show.
        return Ok(Vec::new());
    }

    // --- Step 1: enumerate all days in the window ---
    let mut dates: Vec<i64> = Vec::new();
    let mut d = from_norm;
    while d <= to_norm {
        dates.push(d);
        match d.checked_add(SECS_PER_DAY) {
            Some(next) => d = next,
            None => break,
        }
    }

    // --- Step 2: bulk-prefetch all rates we will need ---
    // One `range()` per distinct currency (dashboard currency, every
    // investment's cost-basis currency, every manual asset's currency).
    // Prefetch failures are soft: missing days read back as `None` from
    // the cache and contribute 0 for that day.
    let investments = registry.list_investments()?;
    let lots = with_legacy_aggregate_lots(registry.list_all_investment_lots()?, &investments);
    let sold_back = sold_quantities_by_lot(registry)?;
    let manual_assets = registry.list_manual_assets()?;
    let liabilities = registry.list_liabilities()?;
    let schedules = load_loan_schedules(registry, currency)?;
    let manual_txns = registry.list_manual_txns()?;

    let mut currencies: Vec<&str> = vec![currency];
    currencies.extend(
        investments
            .iter()
            .map(|inv| inv.cost_basis_currency.as_str()),
    );
    currencies.extend(manual_assets.iter().map(|a| a.currency.as_str()));
    currencies.extend(schedules.iter().map(|(_, s)| s.terms.currency.as_str()));
    currencies.sort_unstable();
    currencies.dedup();
    for ccy in currencies {
        let _ = rate_repo.range(from_norm, to_norm, ccy);
    }

    // --- Step 3: compute per-lot current price ---
    // The spec approximation: use current_value_per_unit if available,
    // otherwise fall back to cost_basis_per_unit (price_per_unit from the lot).
    // We build a map from investment_id → effective_price_per_unit.
    let invest_price_map: std::collections::HashMap<i64, (f64, String)> = investments
        .iter()
        .map(|inv| {
            let price = inv
                .fetched_current_value
                .filter(|v| v.is_finite() && *v > 0.0)
                .or(inv
                    .manual_current_value
                    .filter(|v| v.is_finite() && *v > 0.0))
                .unwrap_or(0.0); // 0 means "use lot cost_basis_per_unit"
            (inv.id, (price, inv.cost_basis_currency.clone()))
        })
        .collect();

    // --- Step 4: liabilities without a plan, walked back from today ---
    let unscheduled: Vec<&TrackingLiability> = liabilities
        .iter()
        .filter(|l| schedules.iter().all(|(s, _)| s.id != l.id))
        .collect();

    // --- Step 5: per-day loop ---
    let mut out: Vec<NwSnapshot> = Vec::with_capacity(dates.len());

    for &t in &dates {
        let rate = rate_repo
            .cached_rate_at(t, currency)
            .unwrap_or(None)
            .unwrap_or(0.0);

        let onchain = onchain_balance_at(events, t);

        let lots_at_t = lots_as_of(&lots, &sold_back, t);
        let investments_btc =
            investments_value_btc_at(&lots_at_t, &invest_price_map, t, currency, rate_repo);
        let investments_sat = btc_to_sat(investments_btc);

        let manual_btc = manual_assets_value_btc_at(&manual_assets, t, rate_repo);
        let manual_sat = btc_to_sat(manual_btc);

        let liabilities_sat_total =
            liabilities_sat_at(&unscheduled, &schedules, &manual_txns, t, rate_repo);

        let total = onchain
            .saturating_add(investments_sat)
            .saturating_add(manual_sat)
            .saturating_sub(liabilities_sat_total);
        let fiat_eq = if rate > 0.0 {
            (total as f64) / 1e8 * rate
        } else {
            0.0
        };

        out.push(NwSnapshot {
            date_unix: t,
            total_sat: total,
            onchain_sat: onchain,
            investments_sat,
            manual_assets_sat: manual_sat,
            liabilities_sat: liabilities_sat_total,
            fiat_equivalent: fiat_eq,
            rate_btc_fiat: rate,
        });
    }

    Ok(out)
}

// ---------------------------------------------------------------------------
// Sub-helpers
// ---------------------------------------------------------------------------

/// GAP-018 — a legacy investment (aggregate quantity / cost, no lot rows)
/// enters the history as one synthetic lot bought at its `purchase_unix`,
/// like the live net worth counts it. Synthetic ids are negative so they
/// never match a disposal.
fn with_legacy_aggregate_lots(
    mut lots: Vec<TrackingInvestmentLot>,
    investments: &[TrackingInvestment],
) -> Vec<TrackingInvestmentLot> {
    let with_lots: std::collections::HashSet<i64> = lots.iter().map(|l| l.investment_id).collect();
    for inv in investments {
        if with_lots.contains(&inv.id) || !inv.quantity.is_finite() || inv.quantity <= 0.0 {
            continue;
        }
        let price_per_unit = if inv.cost_basis_fiat.is_finite() && inv.cost_basis_fiat > 0.0 {
            inv.cost_basis_fiat / inv.quantity
        } else {
            0.0
        };
        lots.push(TrackingInvestmentLot {
            id: -inv.id,
            investment_id: inv.id,
            quantity: inv.quantity,
            price_per_unit,
            fees_fiat: 0.0,
            purchase_unix: inv.purchase_unix,
            notes: None,
            created_at_unix: inv.created_at_unix,
            btc_per_unit_at_purchase: None,
            original_quantity: inv.quantity,
        });
    }
    lots
}

/// GAP-018 — `(sale_unix, qty)` consumed from each lot by recorded sells.
fn sold_quantities_by_lot(
    registry: &Registry,
) -> Result<std::collections::HashMap<i64, Vec<(i64, f64)>>, OnchainError> {
    let sale_unix: std::collections::HashMap<i64, i64> = registry
        .list_all_investment_sells()?
        .iter()
        .map(|s| (s.id, s.sale_unix))
        .collect();
    let mut out: std::collections::HashMap<i64, Vec<(i64, f64)>> = std::collections::HashMap::new();
    for d in registry.list_all_lot_disposals()? {
        if !d.qty_consumed.is_finite() || d.qty_consumed <= 0.0 {
            continue;
        }
        if let Some(&at) = sale_unix.get(&d.sell_id) {
            out.entry(d.lot_id).or_default().push((at, d.qty_consumed));
        }
    }
    Ok(out)
}

/// GAP-018 — the lots as they stood at `t`: units sold after `t` are
/// still held that day (a lot's `quantity` is only the remainder today).
fn lots_as_of(
    lots: &[TrackingInvestmentLot],
    sold_back: &std::collections::HashMap<i64, Vec<(i64, f64)>>,
    t: i64,
) -> Vec<TrackingInvestmentLot> {
    lots.iter()
        .map(|l| {
            let back: f64 = sold_back
                .get(&l.id)
                .map(|v| v.iter().filter(|(at, _)| *at > t).map(|(_, q)| q).sum())
                .unwrap_or(0.0);
            let mut lot = l.clone();
            if back > 0.0 {
                lot.quantity += back;
            }
            lot
        })
        .collect()
}

/// Sum the signed `amount_sat` of all events confirmed at or before `t`.
/// Unconfirmed events (`block_time_unix = None`) are excluded.
pub fn onchain_balance_at(events: &[PaymentEventLite], t: i64) -> i64 {
    events
        .iter()
        .filter(|e| e.block_time_unix.is_some_and(|bt| bt <= t))
        .map(|e| e.amount_sat)
        .fold(0i64, i64::saturating_add)
}

/// Compute the BTC value of all investment lots that existed at time `t`.
///
/// For each lot:
/// - Skip if `lot.purchase_unix > t` (future purchase).
/// - Skip if `lot.quantity <= 0` (fully consumed by sells).
/// - Value per unit = parent investment's `fetched_current_value` when
///   available, else `manual_current_value`, else `lot.price_per_unit`
///   (Phase 8.4 approximation: current value constant over history).
/// - Convert fiat → BTC using the rate at `t` for the investment's
///   currency. When the rate is 0 / unavailable that lot contributes 0.
pub fn investments_value_btc_at<R: BtcRateHistoryRepository, S: std::hash::BuildHasher>(
    lots: &[TrackingInvestmentLot],
    invest_price_map: &std::collections::HashMap<i64, (f64, String), S>,
    t: i64,
    _dashboard_currency: &str,
    rate_repo: &R,
) -> f64 {
    let mut total_btc = 0.0;

    for lot in lots {
        if lot.purchase_unix > t {
            continue; // future lot
        }
        if lot.quantity <= 0.0 {
            continue; // fully consumed
        }

        let (current_price, currency_str) = match invest_price_map.get(&lot.investment_id) {
            Some(pair) => pair.clone(),
            None => (0.0, String::new()),
        };

        // If we don't have an investment-level current price, fall back to
        // lot cost basis per unit.
        let price_per_unit = if current_price > 0.0 {
            current_price
        } else {
            lot.price_per_unit
        };

        if price_per_unit <= 0.0 || currency_str.is_empty() {
            continue;
        }

        let value_fiat = lot.quantity * price_per_unit;
        if !value_fiat.is_finite() {
            continue;
        }

        let rate = rate_repo
            .cached_rate_at(t, &currency_str)
            .unwrap_or(None)
            .unwrap_or(0.0);

        if rate > 0.0 {
            total_btc += value_fiat / rate;
        }
        // else: rate unavailable → this lot contributes 0 (soft-fail)
    }

    total_btc
}

/// Every debt on day `t`, in sat (see step 4 of the module docs).
fn liabilities_sat_at<R: BtcRateHistoryRepository>(
    unscheduled: &[&TrackingLiability],
    schedules: &[(TrackingLiability, LoanSchedule)],
    txns: &[ManualTxn],
    t: i64,
    rate_repo: &R,
) -> i64 {
    unscheduled
        .iter()
        .map(|l| unscheduled_liability_sat_at(l, txns, t))
        .chain(
            schedules
                .iter()
                .map(|(_, s)| btc_to_sat(scheduled_liability_btc_at(s, t, rate_repo))),
        )
        .fold(0i64, i64::saturating_add)
}

/// A debt without a plan on day `t`: its balance today with every manual
/// entry dated after `t` undone — a payment on it added back, spending
/// on it as a card taken off, income on the card added back. Never below
/// zero. `amount_sat` is u64: converted with saturation so a corrupt row
/// cannot wrap the total negative.
fn unscheduled_liability_sat_at(l: &TrackingLiability, txns: &[ManualTxn], t: i64) -> i64 {
    let card_ref = format!("liability:{}", l.id);
    let now = i64::try_from(l.amount_sat).unwrap_or(i64::MAX);
    txns.iter()
        .filter(|x| x.occurred_unix > t)
        .fold(now, |acc, x| {
            let amount = i64::try_from(x.amount_sat).unwrap_or(i64::MAX);
            let mut v = acc;
            if x.liability_id == Some(l.id) {
                v = v.saturating_add(amount);
            }
            if x.account_ref.as_deref() == Some(card_ref.as_str()) {
                v = if x.direction == "out" {
                    v.saturating_sub(amount)
                } else {
                    v.saturating_add(amount)
                };
            }
            v
        })
        .max(0)
}

/// A planned debt on day `t` in BTC: the principal outstanding that day
/// at the day's rate of its currency (0 without a rate — soft-fail).
fn scheduled_liability_btc_at<R: BtcRateHistoryRepository>(
    s: &LoanSchedule,
    t: i64,
    rate_repo: &R,
) -> f64 {
    let balance = s.balance_at(t);
    if balance <= 0.0 {
        return 0.0;
    }
    let rate = rate_repo
        .cached_rate_at(t, &s.terms.currency)
        .unwrap_or(None)
        .unwrap_or(0.0);
    if rate > 0.0 { balance / rate } else { 0.0 }
}

/// Compute the BTC value of all manual assets that existed at time `t`.
///
/// Each asset uses its `purchase_unix` if set (Phase 8.2 backfill
/// populated this), otherwise `created_at_unix` as the appearance date.
/// Value is assumed constant at `value_fiat` (current). Converted to BTC
/// via `rate_at(t, asset.currency)`. Rate missing → contributes 0.
pub fn manual_assets_value_btc_at<R: BtcRateHistoryRepository>(
    assets: &[TrackingManualAsset],
    t: i64,
    rate_repo: &R,
) -> f64 {
    let mut total_btc = 0.0;

    for asset in assets {
        let appeared_at = asset.purchase_unix.unwrap_or(asset.created_at_unix);
        if appeared_at > t {
            continue; // asset purchased/created after t
        }

        let rate = rate_repo
            .cached_rate_at(t, &asset.currency)
            .unwrap_or(None)
            .unwrap_or(0.0);

        if rate > 0.0 && asset.value_fiat.is_finite() {
            total_btc += asset.value_fiat / rate;
        }
        // else: soft-fail → 0
    }

    total_btc
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/// Convert a BTC amount (as f64) to sat (as i64), saturating at `i64::MAX`.
#[inline]
fn btc_to_sat(btc: f64) -> i64 {
    if !btc.is_finite() {
        return 0;
    }
    let sat = btc * 1e8;
    if sat > i64::MAX as f64 {
        i64::MAX
    } else if sat < i64::MIN as f64 {
        i64::MIN
    } else {
        sat as i64
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
#[allow(
    clippy::float_cmp,
    reason = "tests assert exact, deterministically computed f64 values"
)]
mod tests {
    use super::*;
    use crate::btc_rate_history::{BackfillReport, BtcRateHistoryRepository, RateError, RatePoint};
    use crate::registry::{Registry, TrackingInvestmentLot, TrackingManualAsset};

    // -----------------------------------------------------------------------
    // Mock rate repository
    // -----------------------------------------------------------------------

    struct MockRateRepo {
        /// Seeded rates: key = (`date_unix_midnight`, currency), value = rate.
        rates: std::collections::HashMap<(i64, String), f64>,
    }

    impl MockRateRepo {
        fn new() -> Self {
            Self {
                rates: std::collections::HashMap::new(),
            }
        }

        fn seed(&mut self, date_unix: i64, currency: &str, rate: f64) {
            let norm = normalize_to_utc_midnight(date_unix);
            self.rates.insert((norm, currency.to_string()), rate);
        }
    }

    impl BtcRateHistoryRepository for MockRateRepo {
        fn rate_at(&self, date_unix: i64, currency: &str) -> Result<Option<f64>, RateError> {
            let norm = normalize_to_utc_midnight(date_unix);
            let key = (norm, currency.to_string());
            Ok(self.rates.get(&key).copied())
        }

        fn range(
            &self,
            _from: i64,
            _to: i64,
            _currency: &str,
        ) -> Result<Vec<RatePoint>, RateError> {
            Ok(vec![])
        }

        fn current(&self, _currency: &str) -> Result<f64, RateError> {
            Err(RateError::NetworkUnavailable)
        }

        fn backfill(&self, requests: &[(i64, &str)]) -> Result<BackfillReport, RateError> {
            Ok(BackfillReport {
                requested: requests.len(),
                ..BackfillReport::default()
            })
        }
    }

    // -----------------------------------------------------------------------
    // Helpers to open in-memory registry
    // -----------------------------------------------------------------------

    fn open_empty_registry() -> Registry {
        use rusqlite::Connection;
        let conn = Connection::open_in_memory().unwrap();
        // Apply the same schema as the real registry, using the public
        // open_registry path via a temp file isn't needed — we call
        // the internal ensure_schema via the in-memory helper.
        // The Registry type exposes open_in_memory in tests.
        crate::registry::open_registry_in_memory(conn).unwrap()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /// DAY = 2024-01-01 00:00 UTC
    const D0: i64 = 1_704_067_200;
    const D1: i64 = D0 + SECS_PER_DAY;
    const D2: i64 = D0 + 2 * SECS_PER_DAY;

    #[test]
    fn reconstruct_empty_db_returns_zero_total_for_all_days() {
        let registry = open_empty_registry();
        let mut rate_repo = MockRateRepo::new();
        rate_repo.seed(D0, "EUR", 40_000.0);
        rate_repo.seed(D1, "EUR", 40_000.0);
        rate_repo.seed(D2, "EUR", 40_000.0);

        let snaps =
            reconstruct_net_worth_history(&registry, &rate_repo, &[], D0, D2, "EUR").unwrap();

        assert_eq!(snaps.len(), 3);
        for s in &snaps {
            assert_eq!(s.total_sat, 0, "empty DB should give zero total");
            assert_eq!(s.onchain_sat, 0);
            assert_eq!(s.investments_sat, 0);
            assert_eq!(s.manual_assets_sat, 0);
            assert_eq!(s.liabilities_sat, 0);
        }
    }

    #[test]
    fn reconstruct_with_single_onchain_event_steps_up_at_block_time() {
        let registry = open_empty_registry();
        let mut rate_repo = MockRateRepo::new();
        rate_repo.seed(D0, "EUR", 40_000.0);
        rate_repo.seed(D1, "EUR", 40_000.0);

        // A confirmed incoming tx at D1.
        let events = vec![PaymentEventLite {
            amount_sat: 100_000, // 0.001 BTC
            block_time_unix: Some(D1),
        }];

        let snaps =
            reconstruct_net_worth_history(&registry, &rate_repo, &events, D0, D1, "EUR").unwrap();

        assert_eq!(snaps.len(), 2);
        // D0: event is at D1, not included yet.
        assert_eq!(
            snaps[0].onchain_sat, 0,
            "event should not appear before its block time"
        );
        // D1: event appears.
        assert_eq!(snaps[1].onchain_sat, 100_000);
        assert_eq!(snaps[1].total_sat, 100_000);
    }

    #[test]
    fn reconstruct_excludes_future_lots() {
        let _registry = open_empty_registry();
        let mut rate_repo = MockRateRepo::new();
        rate_repo.seed(D0, "EUR", 40_000.0);

        // A lot purchased at D1 (future relative to D0).
        let lots = vec![TrackingInvestmentLot {
            id: 1,
            investment_id: 1,
            quantity: 1.0,
            original_quantity: 1.0,
            price_per_unit: 200.0,
            fees_fiat: 0.0,
            purchase_unix: D1, // future at t=D0
            notes: None,
            created_at_unix: D1,
            btc_per_unit_at_purchase: None,
        }];

        // No investments in DB; use helper.
        let mut invest_price_map = std::collections::HashMap::new();
        invest_price_map.insert(1i64, (200.0, "EUR".to_string()));

        let btc_at_d0 = investments_value_btc_at(&lots, &invest_price_map, D0, "EUR", &rate_repo);
        assert_eq!(btc_at_d0, 0.0, "future lot should be excluded");
    }

    #[test]
    fn reconstruct_excludes_future_manual_assets() {
        let mut rate_repo = MockRateRepo::new();
        rate_repo.seed(D0, "EUR", 40_000.0);

        let assets = vec![TrackingManualAsset {
            id: 1,
            name: "Car".to_string(),
            kind: "vehicle".to_string(),
            value_fiat: 10_000.0,
            currency: "EUR".to_string(),
            display_label: None,
            notes: None,
            last_updated_unix: D1,
            created_at_unix: D1,
            purchase_unix: Some(D1), // future
            purchase_value_fiat: None,
            purchase_currency: None,
            purchase_btc_at_time: None,
        }];

        let btc_at_d0 = manual_assets_value_btc_at(&assets, D0, &rate_repo);
        assert_eq!(btc_at_d0, 0.0, "future asset should be excluded");
    }

    #[test]
    fn reconstruct_with_known_rate_apple_lot_recomposes_btc_value() {
        let mut rate_repo = MockRateRepo::new();
        // BTC/USD rate at D0 = 40_000 USD/BTC
        rate_repo.seed(D0, "USD", 40_000.0);

        // An Apple stock lot: 5 shares at $200 = $1000 total.
        // BTC value = 1000 / 40_000 = 0.025 BTC = 2_500_000 sat.
        let lots = vec![TrackingInvestmentLot {
            id: 1,
            investment_id: 10,
            quantity: 5.0,
            original_quantity: 5.0,
            price_per_unit: 200.0,
            fees_fiat: 0.0,
            purchase_unix: D0 - SECS_PER_DAY, // purchased the day before
            notes: None,
            created_at_unix: D0 - SECS_PER_DAY,
            btc_per_unit_at_purchase: None,
        }];

        let mut invest_price_map = std::collections::HashMap::new();
        // current_value = 200 USD/share (same as cost basis here)
        invest_price_map.insert(10i64, (200.0, "USD".to_string()));

        let btc = investments_value_btc_at(&lots, &invest_price_map, D0, "USD", &rate_repo);
        let sat = btc_to_sat(btc);
        // 5 * 200 / 40_000 * 1e8 = 2_500_000
        assert_eq!(sat, 2_500_000, "Apple lot sat value mismatch");
    }

    #[test]
    fn reconstruct_liabilities_constant_across_days() {
        let registry = open_empty_registry();
        // Seed a liability of 0.5 BTC in sat = 50_000_000 sat.
        registry
            .add_liability("Mortgage", 50_000_000, "mortgage", None)
            .unwrap();

        let mut rate_repo = MockRateRepo::new();
        rate_repo.seed(D0, "EUR", 40_000.0);
        rate_repo.seed(D1, "EUR", 45_000.0);

        let snaps =
            reconstruct_net_worth_history(&registry, &rate_repo, &[], D0, D1, "EUR").unwrap();

        assert_eq!(snaps.len(), 2);
        assert_eq!(snaps[0].liabilities_sat, 50_000_000, "liability D0");
        assert_eq!(snaps[1].liabilities_sat, 50_000_000, "liability D1 same");
        assert_eq!(
            snaps[0].total_sat, -50_000_000,
            "total D0 should be negative"
        );
    }

    #[test]
    fn reconstruct_planned_mortgage_follows_its_schedule_and_payments() {
        use crate::registry::DebtPayment;
        let registry = open_empty_registry();
        let id = registry
            .add_liability("Mutuo", 1, "mortgage", None)
            .unwrap();
        // 12,000 € at 0 % from 2023-12-01, 1,000 € a month: the first
        // installment falls on D0 (2024-01-01).
        registry
            .set_details(
                "liability",
                id,
                r#"{"original_amount_fiat":12000,"rate_pct":0,"start_date":"2023-12-01","monthly_payment":1000,"currency":"EUR"}"#,
            )
            .unwrap();
        // 2,000 € extra on 15 January, same installment afterwards.
        let jan15 = D0 + 14 * SECS_PER_DAY;
        registry
            .add_manual_txn_paying(
                "out",
                4_000_000,
                Some(2_000.0),
                Some("EUR"),
                jan15,
                None,
                None,
                Some(DebtPayment {
                    liability_id: id,
                    kind: "extra_term",
                }),
            )
            .unwrap();
        let mut rate_repo = MockRateRepo::new();
        let from = D0 - 32 * SECS_PER_DAY; // 2023-11-30
        let to = D0 + 31 * SECS_PER_DAY;
        let mut t = from;
        while t <= to {
            rate_repo.seed(t, "EUR", 50_000.0);
            t += SECS_PER_DAY;
        }

        let snaps =
            reconstruct_net_worth_history(&registry, &rate_repo, &[], from, to, "EUR").unwrap();
        let at = |day: i64| {
            snaps
                .iter()
                .find(|s| s.date_unix == day)
                .unwrap()
                .liabilities_sat
        };

        assert_eq!(at(from), 0, "before the start there is no debt");
        assert_eq!(
            at(D0 - SECS_PER_DAY),
            24_000_000,
            "12,000 € at 50,000 €/BTC"
        );
        assert_eq!(at(D0), 22_000_000, "first installment");
        assert_eq!(at(jan15), 18_000_000, "extra repayment");
        assert_eq!(
            at(D0 + 31 * SECS_PER_DAY),
            16_000_000,
            "February installment"
        );
    }

    #[test]
    fn reconstruct_unplanned_debts_undo_later_entries() {
        use crate::registry::DebtPayment;
        let registry = open_empty_registry();
        let card = registry
            .add_liability("Visa", 100_000, "credit-card", None)
            .unwrap();
        let loan = registry
            .add_liability("Auto", 500_000, "loan", None)
            .unwrap();
        let card_ref = format!("liability:{card}");
        // D1: 30,000 sat spent on the card (its debt grows to 130,000).
        registry
            .add_manual_txn("out", 30_000, None, None, D1, Some(&card_ref), None)
            .unwrap();
        // D2: 50,000 sat paid on the loan from cash (down to 450,000).
        registry
            .add_manual_txn_paying(
                "out",
                50_000,
                None,
                None,
                D2,
                None,
                None,
                Some(DebtPayment {
                    liability_id: loan,
                    kind: "installment",
                }),
            )
            .unwrap();
        let rate_repo = MockRateRepo::new();

        let snaps =
            reconstruct_net_worth_history(&registry, &rate_repo, &[], D0, D2, "EUR").unwrap();

        assert_eq!(snaps[0].liabilities_sat, 100_000 + 500_000, "D0");
        assert_eq!(
            snaps[1].liabilities_sat,
            130_000 + 500_000,
            "D1: card spending"
        );
        assert_eq!(
            snaps[2].liabilities_sat,
            130_000 + 450_000,
            "D2: loan payment"
        );
    }

    #[test]
    fn reconstruct_normalizes_inputs_to_utc_midnight() {
        let registry = open_empty_registry();
        let mut rate_repo = MockRateRepo::new();
        rate_repo.seed(D0, "EUR", 40_000.0);

        // Pass non-midnight timestamps.
        let from_noon = D0 + 12 * 3600;
        let to_noon = D0 + 12 * 3600; // same day

        let snaps =
            reconstruct_net_worth_history(&registry, &rate_repo, &[], from_noon, to_noon, "EUR")
                .unwrap();

        // Should produce exactly 1 snapshot at D0 midnight.
        assert_eq!(snaps.len(), 1);
        assert_eq!(snaps[0].date_unix, D0, "should normalize to midnight");
    }

    #[test]
    fn reconstruct_invalid_range_returns_err() {
        let registry = open_empty_registry();
        let rate_repo = MockRateRepo::new();

        let result = reconstruct_net_worth_history(
            &registry,
            &rate_repo,
            &[],
            D1,
            D0,
            "EUR", // to < from
        );
        assert!(
            matches!(result, Err(OnchainError::InvalidArgument(_))),
            "should return InvalidArgument for reversed range"
        );
    }

    #[test]
    fn reconstruct_handles_partial_rate_history_with_zeros_not_errors() {
        let registry = open_empty_registry();
        let mut rate_repo = MockRateRepo::new();
        // Only D0 has a rate; D1 has no rate in the mock.
        rate_repo.seed(D0, "EUR", 40_000.0);
        // D1 rate not seeded — simulates a missing/failed rate fetch.

        let events = vec![PaymentEventLite {
            amount_sat: 100_000,
            block_time_unix: Some(D0),
        }];

        // Should NOT return an error.
        let snaps =
            reconstruct_net_worth_history(&registry, &rate_repo, &events, D0, D1, "EUR").unwrap();

        assert_eq!(snaps.len(), 2);
        // D0: has rate → fiat_equivalent should be non-zero.
        assert!(snaps[0].fiat_equivalent > 0.0, "D0 should have fiat value");
        // D1: no rate → fiat_equivalent = 0, but total_sat is still computed from onchain.
        assert_eq!(snaps[1].rate_btc_fiat, 0.0, "D1 rate should be 0 (missing)");
        assert_eq!(snaps[1].fiat_equivalent, 0.0, "D1 fiat_eq should be 0");
        // onchain_sat on D1 still reflects the cumulative balance.
        assert_eq!(
            snaps[1].onchain_sat, 100_000,
            "onchain still correct despite missing rate"
        );
    }

    // -----------------------------------------------------------------------
    // M8 — window clamping, saturation, cache-only per-day lookups
    // -----------------------------------------------------------------------

    /// Repo whose network path (`rate_at`) must never be hit: the per-day
    /// loop may only use `cached_rate_at`. Counts `range()` prefetches.
    struct CacheOnlyRepo {
        range_calls: std::sync::atomic::AtomicUsize,
    }

    impl BtcRateHistoryRepository for CacheOnlyRepo {
        fn rate_at(&self, _d: i64, _c: &str) -> Result<Option<f64>, RateError> {
            panic!("per-day loop must not call the network-capable rate_at");
        }
        fn cached_rate_at(&self, _d: i64, _c: &str) -> Result<Option<f64>, RateError> {
            Ok(Some(50_000.0))
        }
        fn range(&self, _f: i64, _t: i64, _c: &str) -> Result<Vec<RatePoint>, RateError> {
            self.range_calls
                .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(vec![])
        }
        fn current(&self, _c: &str) -> Result<f64, RateError> {
            Err(RateError::NetworkUnavailable)
        }
        fn backfill(&self, _r: &[(i64, &str)]) -> Result<BackfillReport, RateError> {
            panic!("per-pair backfill must not be used for the prefetch");
        }
    }

    #[test]
    fn reconstruct_uses_one_range_prefetch_per_currency_and_cache_only_loop() {
        let registry = open_empty_registry();
        registry
            .add_manual_asset(
                "Flat",
                "real_estate",
                100_000.0,
                "USD",
                None,
                None,
                None,
                None,
                None,
            )
            .unwrap();
        let repo = CacheOnlyRepo {
            range_calls: std::sync::atomic::AtomicUsize::new(0),
        };
        let snaps =
            reconstruct_net_worth_history(&registry, &repo, &[], D0, D0 + 9 * SECS_PER_DAY, "EUR")
                .unwrap();
        assert_eq!(snaps.len(), 10);
        // EUR (dashboard) + USD (asset) → exactly two prefetches.
        assert_eq!(
            repo.range_calls.load(std::sync::atomic::Ordering::SeqCst),
            2
        );
    }

    #[test]
    fn reconstruct_clamps_window_to_genesis_and_max_span() {
        let registry = open_empty_registry();
        let repo = CacheOnlyRepo {
            range_calls: std::sync::atomic::AtomicUsize::new(0),
        };
        // i64 extremes must neither panic nor allocate billions of days.
        let snaps = reconstruct_net_worth_history(&registry, &repo, &[], i64::MIN, i64::MAX, "EUR")
            .unwrap();
        assert!(!snaps.is_empty());
        assert!(
            snaps.len() <= MAX_WINDOW_DAYS as usize,
            "span capped, got {}",
            snaps.len()
        );
        assert!(snaps[0].date_unix >= GENESIS_DAY_UNIX);
        let now = crate::btc_rate_history::now_unix();
        assert!(snaps.last().unwrap().date_unix <= now + SECS_PER_DAY);

        // A window entirely before genesis yields no snapshots.
        let pre =
            reconstruct_net_worth_history(&registry, &repo, &[], 0, 86_400 * 10, "EUR").unwrap();
        assert!(pre.is_empty());
    }

    #[test]
    fn onchain_balance_saturates_instead_of_overflowing() {
        let events = vec![
            PaymentEventLite {
                amount_sat: i64::MAX,
                block_time_unix: Some(D0),
            },
            PaymentEventLite {
                amount_sat: i64::MAX,
                block_time_unix: Some(D0),
            },
        ];
        assert_eq!(onchain_balance_at(&events, D0), i64::MAX);
    }

    #[test]
    fn normalize_to_utc_midnight_handles_i64_extremes() {
        let lo = normalize_to_utc_midnight(i64::MIN);
        assert_eq!(lo.rem_euclid(SECS_PER_DAY), 0);
        // Saturates upward to the first representable midnight, so it
        // must land within one day of the lower bound.
        assert!(lo <= i64::MIN + SECS_PER_DAY);
        let hi = normalize_to_utc_midnight(i64::MAX);
        assert_eq!(hi.rem_euclid(SECS_PER_DAY), 0);
    }
}
