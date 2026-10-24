//! Portfolio use cases (docs/use-cases.md area D + GAP-018) through the
//! public API: an in-memory registry and a constant-rate repository.

#![allow(clippy::float_cmp)]

use bhodl_wallet_onchain::{
    BackfillReport, BtcRateHistoryRepository, NwSnapshot, RateError, RatePoint, Registry,
    open_registry_in_memory, reconstruct_net_worth_history,
};
use rusqlite::Connection;

const DAY: i64 = 86_400;
/// 2024-01-01 00:00 UTC.
const D0: i64 = 1_704_067_200;

/// 1 BTC = 50 000 in every currency, every day.
struct FlatRate;

impl BtcRateHistoryRepository for FlatRate {
    fn rate_at(&self, _date_unix: i64, _currency: &str) -> Result<Option<f64>, RateError> {
        Ok(Some(50_000.0))
    }
    fn range(&self, _from: i64, _to: i64, _currency: &str) -> Result<Vec<RatePoint>, RateError> {
        Ok(vec![])
    }
    fn current(&self, _currency: &str) -> Result<f64, RateError> {
        Ok(50_000.0)
    }
    fn backfill(&self, requests: &[(i64, &str)]) -> Result<BackfillReport, RateError> {
        Ok(BackfillReport {
            requested: requests.len(),
            ..BackfillReport::default()
        })
    }
}

fn registry() -> Registry {
    open_registry_in_memory(Connection::open_in_memory().unwrap()).unwrap()
}

/// ETF priced at 100 EUR/unit, no lots yet.
fn etf(r: &Registry) -> i64 {
    r.add_investment(
        "VWCE",
        "etf",
        None,
        0.0,
        "shares",
        0.0,
        "EUR",
        D0,
        Some(100.0),
        None,
    )
    .unwrap()
}

fn history(r: &Registry, days: i64) -> Vec<NwSnapshot> {
    reconstruct_net_worth_history(r, &FlatRate, &[], D0, D0 + days * DAY, "EUR").unwrap()
}

// 10 units × 100 EUR = 1000 EUR = 0.02 BTC.
const TEN_UNITS_SAT: i64 = 2_000_000;

#[test]
fn gap_018_a_partial_sell_only_shrinks_the_history_from_the_sale_date() {
    let r = registry();
    let id = etf(&r);
    r.add_investment_lot(id, 10.0, 90.0, 0.0, D0, None).unwrap();
    r.record_sell(id, D0 + 2 * DAY + 3_600, 120.0, 5.0, 0.0, None)
        .unwrap();

    let s = history(&r, 4);
    assert_eq!(s[0].investments_sat, TEN_UNITS_SAT);
    assert_eq!(s[1].investments_sat, TEN_UNITS_SAT);
    assert_eq!(
        s[2].investments_sat, TEN_UNITS_SAT,
        "sale happens later that day"
    );
    assert_eq!(s[3].investments_sat, TEN_UNITS_SAT / 2);
    assert_eq!(s[4].investments_sat, TEN_UNITS_SAT / 2);
}

#[test]
fn gap_018_a_fully_sold_lot_still_counts_before_its_sale() {
    let r = registry();
    let id = etf(&r);
    r.add_investment_lot(id, 10.0, 90.0, 0.0, D0, None).unwrap();
    r.record_sell(id, D0 + DAY, 120.0, 10.0, 0.0, None).unwrap();

    let s = history(&r, 2);
    assert_eq!(s[0].investments_sat, TEN_UNITS_SAT);
    assert_eq!(s[1].investments_sat, 0, "sold at midnight of day 1");
    assert_eq!(s[2].investments_sat, 0);
}

#[test]
fn gap_018_deleting_the_sell_restores_the_full_history() {
    let r = registry();
    let id = etf(&r);
    r.add_investment_lot(id, 10.0, 90.0, 0.0, D0, None).unwrap();
    let sell = r.record_sell(id, D0 + DAY, 120.0, 4.0, 0.0, None).unwrap();
    assert!(r.delete_investment_sell(sell).unwrap());

    assert!(
        history(&r, 2)
            .iter()
            .all(|s| s.investments_sat == TEN_UNITS_SAT)
    );
}

#[test]
fn gap_018_a_legacy_aggregate_investment_appears_from_its_purchase_date() {
    let r = registry();
    // 4 units bought for 2000 EUR, no manual price → valued at cost.
    r.add_investment(
        "Gold",
        "commodity",
        None,
        4.0,
        "oz",
        2_000.0,
        "EUR",
        D0 + DAY,
        None,
        None,
    )
    .unwrap();

    let s = history(&r, 2);
    assert_eq!(s[0].investments_sat, 0, "not bought yet");
    assert_eq!(s[1].investments_sat, 4_000_000); // 2000 / 50 000 BTC
    assert_eq!(s[2].investments_sat, 4_000_000);
}

#[test]
fn gap_018_a_legacy_aggregate_uses_the_current_price_when_set() {
    let r = registry();
    r.add_investment(
        "Gold",
        "commodity",
        None,
        4.0,
        "oz",
        2_000.0,
        "EUR",
        D0,
        Some(750.0),
        None,
    )
    .unwrap();
    assert_eq!(history(&r, 0)[0].investments_sat, 6_000_000); // 3000 / 50 000
}

#[test]
fn gap_018_seeding_a_lot_does_not_double_count_the_aggregate() {
    let r = registry();
    let id = r
        .add_investment(
            "Gold",
            "commodity",
            None,
            4.0,
            "oz",
            2_000.0,
            "EUR",
            D0,
            None,
            None,
        )
        .unwrap();
    r.seed_investment_lot_from_aggregate(id).unwrap();
    assert_eq!(history(&r, 0)[0].investments_sat, 4_000_000);
}

#[test]
fn prt_034_lot_btc_rate_round_trips_and_rejects_bad_input() {
    let r = registry();
    let id = etf(&r);
    let lot = r.add_investment_lot(id, 1.0, 100.0, 0.0, D0, None).unwrap();

    assert!(r.set_investment_lot_btc_rate(lot, 42_000.0).unwrap());
    let stored = r.list_investment_lots(id).unwrap();
    assert_eq!(stored[0].btc_per_unit_at_purchase, Some(42_000.0));

    assert!(!r.set_investment_lot_btc_rate(lot + 999, 42_000.0).unwrap());
    for bad in [0.0, -1.0, f64::NAN, f64::INFINITY] {
        assert!(
            r.set_investment_lot_btc_rate(lot, bad).is_err(),
            "{bad} must be rejected"
        );
    }
    assert_eq!(
        r.list_investment_lots(id).unwrap()[0].btc_per_unit_at_purchase,
        Some(42_000.0)
    );
}

#[test]
fn gap_032_per_investment_lists_only_return_that_investment() {
    let r = registry();
    let a = etf(&r);
    let b = etf(&r);
    r.add_investment_lot(a, 2.0, 100.0, 0.0, D0, None).unwrap();
    r.add_investment_lot(b, 3.0, 100.0, 0.0, D0, None).unwrap();
    r.record_sell(b, D0 + DAY, 110.0, 1.0, 0.0, None).unwrap();

    assert_eq!(r.list_investment_lots(a).unwrap().len(), 1);
    assert!(r.list_investment_sells(a).unwrap().is_empty());
    let sells = r.list_investment_sells(b).unwrap();
    assert_eq!(sells.len(), 1);
    assert_eq!(sells[0].investment_id, b);
    assert!(r.list_investment_lots(a + b + 10).unwrap().is_empty());
}
