//! Area C (tracker) use cases: registry-side validation of rules,
//! bills and budgets that used to be enforced only by the UI.

use super::*;
use tempfile::TempDir;

fn open(dir: &TempDir) -> Registry {
    open_registry(
        &dir.path().join(REGISTRY_FILE_NAME),
        &[0x42u8; ENCRYPTION_KEY_LEN],
    )
    .unwrap()
}

fn invalid<T>(r: &Result<T, OnchainError>) -> bool {
    matches!(r, Err(OnchainError::InvalidArgument(_)))
}

fn add_food_rule(r: &Registry) -> i64 {
    r.add_rule(
        "Food",
        "description",
        "contains",
        "coop",
        "category",
        "Food",
        0,
        true,
    )
    .unwrap()
}

// TRK-057
#[test]
fn rules_reject_blank_category_on_add_and_write_nothing() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    assert!(invalid(&r.add_rule(
        "R",
        "description",
        "contains",
        "x",
        "category",
        "",
        0,
        true
    )));
    assert!(invalid(&r.add_rule(
        "R",
        "description",
        "contains",
        "x",
        "category",
        "  ",
        0,
        true
    )));
    assert!(r.list_rules().unwrap().is_empty());
}

// TRK-057
#[test]
fn rules_reject_blank_category_on_update_and_keep_the_row() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let id = add_food_rule(&r);
    assert!(invalid(&r.update_rule(
        id,
        "Food",
        "description",
        "contains",
        "coop",
        "category",
        " ",
        0,
        true
    )));
    assert_eq!(r.list_rules().unwrap()[0].action_value, "Food");
}

// TRK-053
#[test]
fn rules_update_of_a_missing_id_returns_false() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let id = add_food_rule(&r);
    assert!(
        !r.update_rule(
            id + 99,
            "X",
            "description",
            "contains",
            "v",
            "category",
            "C",
            0,
            true
        )
        .unwrap()
    );
    assert_eq!(r.list_rules().unwrap()[0].name, "Food");
}

// TRK-053
#[test]
fn rules_update_runs_the_same_validation_as_add() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let id = add_food_rule(&r);
    assert!(invalid(&r.update_rule(
        id, "Food", "amount", "contains", "coop", "category", "Food", 0, true
    )));
    assert!(invalid(&r.update_rule(
        id, "Food", "amount", "gt", "abc", "category", "Food", 0, true
    )));
    assert!(invalid(&r.update_rule(
        id, "Food", "nope", "contains", "coop", "category", "Food", 0, true
    )));
    let row = &r.list_rules().unwrap()[0];
    assert_eq!(
        (row.condition_field.as_str(), row.condition_op.as_str()),
        ("description", "contains")
    );
}

// Schema v7 — variable-amount bills.
#[test]
fn a_variable_bill_records_what_was_paid_and_unmarking_clears_it() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let id = r.add_bill("Luce e gas", 140_000, 15, None).unwrap();
    let fresh = &r.list_bills().unwrap()[0];
    assert!(!fresh.variable, "bills are fixed by default");
    assert_eq!(fresh.last_paid_amount_sat, None);

    assert!(r.set_bill_variable(id, true).unwrap());
    assert!(r.mark_bill_paid_amount(id, 1_760_000_000, 163_500).unwrap());
    let paid = &r.list_bills().unwrap()[0];
    assert!(paid.variable);
    assert_eq!(
        (paid.last_paid_unix, paid.last_paid_amount_sat),
        (Some(1_760_000_000), Some(163_500))
    );
    assert_eq!(paid.amount_sat, 140_000, "the estimate stays");

    assert!(r.mark_bill_paid(id, None).unwrap());
    let cleared = &r.list_bills().unwrap()[0];
    assert_eq!(
        (cleared.last_paid_unix, cleared.last_paid_amount_sat),
        (None, None)
    );
    assert!(!r.set_bill_variable(9_999, true).unwrap(), "no such bill");
}

// Schema v8 — income and spending outside the wallets.
#[test]
fn manual_transactions_round_trip_and_validate() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let cash = r
        .add_manual_txn(
            "out",
            52_000,
            Some(30.5),
            Some("eur"),
            1_760_000_000,
            None,
            Some(" pizza "),
        )
        .unwrap();
    let card = r
        .add_manual_txn(
            "in",
            10_000,
            None,
            None,
            1_760_100_000,
            Some(MANUAL_ACCOUNT_OTHER),
            None,
        )
        .unwrap();
    let rows = r.list_manual_txns().unwrap();
    assert_eq!(
        rows.iter().map(|t| t.id).collect::<Vec<_>>(),
        vec![card, cash],
        "newest first"
    );
    let c = &rows[1];
    assert_eq!(
        (
            c.direction.as_str(),
            c.amount_sat,
            c.amount_fiat,
            c.currency.as_deref(),
            c.note.as_deref()
        ),
        ("out", 52_000, Some(30.5), Some("EUR"), Some("pizza"))
    );
    assert_eq!(
        manual_txn_wallet_id(None).to_string(),
        "6d616e75-616c-4000-8000-000000000000"
    );
    assert_eq!(
        manual_txn_wallet_id(Some("asset:26")).to_string(),
        "6d616e75-616c-4000-8000-a0000000001a"
    );
    assert_eq!(
        manual_txn_wallet_id(Some("liability:2")).to_string(),
        "6d616e75-616c-4000-8000-b00000000002"
    );
    assert_eq!(
        manual_txn_wallet_id(Some("bogus")),
        manual_txn_wallet_id(None)
    );

    assert!(invalid(
        &r.add_manual_txn("sideways", 1, None, None, 0, None, None)
    ));
    assert!(invalid(
        &r.add_manual_txn("out", 0, None, None, 0, None, None)
    ));
    assert!(invalid(&r.add_manual_txn(
        "out",
        1,
        Some(2.0),
        None,
        0,
        None,
        None
    )));

    assert!(
        r.update_manual_txn(
            cash,
            "out",
            60_000,
            Some(35.0),
            Some("EUR"),
            1_760_000_000,
            None
        )
        .unwrap()
    );
    assert_eq!(r.list_manual_txns().unwrap()[1].amount_sat, 60_000);
}

// Schema v8 — a bill's category and the transaction that paid it.
#[test]
fn deleting_a_manual_transaction_unlinks_the_bill_it_paid() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let cash = r
        .add_manual_txn("out", 60_000, None, None, 1_760_000_000, None, None)
        .unwrap();
    r.add_manual_txn("out", 1_000, None, None, 1_760_000_000, None, None)
        .unwrap();
    let bill = r.add_bill("Luce e gas", 140_000, 15, None).unwrap();
    assert!(r.set_bill_category(bill, Some(" Bollette ")).unwrap());
    let event = manual_txn_event_id(cash);
    let wallet = manual_txn_wallet_id(None);
    r.set_event_category(&wallet, &event, Some("Bollette"))
        .unwrap();
    assert!(
        r.mark_bill_paid_by(bill, 1_760_000_000, 60_000, &format!("{wallet}|{event}"))
            .unwrap()
    );
    let b = &r.list_bills().unwrap()[0];
    assert_eq!(
        (
            b.category.as_deref(),
            b.paid_ref.as_deref(),
            b.last_paid_amount_sat
        ),
        (
            Some("Bollette"),
            Some("6d616e75-616c-4000-8000-000000000000|manual-1"),
            Some(60_000)
        )
    );

    assert!(r.delete_manual_txn(cash).unwrap());
    let b = &r.list_bills().unwrap()[0];
    assert_eq!(
        (b.last_paid_unix, b.paid_ref.as_deref()),
        (None, None),
        "the bill is unpaid again"
    );
    assert_eq!(
        r.get_event_category(&wallet, &event).unwrap(),
        None,
        "its category goes too"
    );
    assert_eq!(r.list_manual_txns().unwrap().len(), 1, "only that one");
    assert!(!r.delete_manual_txn(cash).unwrap());

    // Unmarking a bill drops its link.
    assert!(r.mark_bill_paid_by(bill, 1, 1, "w|tx").unwrap());
    assert!(r.mark_bill_paid(bill, None).unwrap());
    assert_eq!(r.list_bills().unwrap()[0].paid_ref, None);
    assert!(r.set_bill_category(bill, None).unwrap());
    assert_eq!(r.list_bills().unwrap()[0].category, None);
}

// Schema v8 — an entry moves its account's balance.
#[test]
fn a_manual_transaction_moves_its_account_balance() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let bank = r
        .add_manual_asset(
            "Conto", "cash", 1_000.0, "EUR", None, None, None, None, None,
        )
        .unwrap();
    let bank_ref = format!("asset:{bank}");
    let balance = |r: &Registry| {
        r.list_manual_assets()
            .unwrap()
            .into_iter()
            .find(|a| a.id == bank)
            .unwrap()
            .value_fiat
    };

    // Spending from the bank account lowers it, in its currency.
    let spend = r
        .add_manual_txn(
            "out",
            50_000,
            Some(40.0),
            Some("EUR"),
            1,
            Some(&bank_ref),
            None,
        )
        .unwrap();
    assert!((balance(&r) - 960.0).abs() < 1e-9);
    // Income raises it.
    r.add_manual_txn(
        "in",
        10_000,
        Some(15.5),
        Some("EUR"),
        1,
        Some(&bank_ref),
        None,
    )
    .unwrap();
    assert!((balance(&r) - 975.5).abs() < 1e-9);
    // Another currency, or sat, is refused for that account.
    assert!(invalid(&r.add_manual_txn(
        "out",
        1,
        Some(1.0),
        Some("USD"),
        1,
        Some(&bank_ref),
        None
    )));
    assert!(invalid(&r.add_manual_txn(
        "out",
        1,
        None,
        None,
        1,
        Some(&bank_ref),
        None
    )));
    // An edit moves it by the difference, a delete moves it back.
    assert!(
        r.update_manual_txn(spend, "out", 60_000, Some(50.0), Some("EUR"), 1, None)
            .unwrap()
    );
    assert!((balance(&r) - 965.5).abs() < 1e-9);
    assert!(r.delete_manual_txn(spend).unwrap());
    assert!((balance(&r) - 1_015.5).abs() < 1e-9);
}

// Schema v8 — a card entry moves the debt; cash and "other" move nothing.
#[test]
fn a_card_entry_moves_the_debt() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let card = r
        .add_liability("Visa", 100_000, "credit-card", None)
        .unwrap();
    let card_ref = format!("liability:{card}");
    let debt = |r: &Registry| r.list_liabilities().unwrap()[0].amount_sat;

    // Spending raises the debt (in sat), a refund lowers it.
    let buy = r
        .add_manual_txn(
            "out",
            30_000,
            Some(25.0),
            Some("EUR"),
            1,
            Some(&card_ref),
            None,
        )
        .unwrap();
    assert_eq!(debt(&r), 130_000);
    r.add_manual_txn("in", 5_000, None, None, 1, Some(&card_ref), None)
        .unwrap();
    assert_eq!(debt(&r), 125_000);
    assert!(r.delete_manual_txn(buy).unwrap());
    assert_eq!(debt(&r), 95_000);

    // Cash and "other" have no balance; an unknown account is refused.
    r.add_manual_txn(
        "out",
        1_000,
        None,
        None,
        1,
        Some(MANUAL_ACCOUNT_OTHER),
        None,
    )
    .unwrap();
    assert_eq!(
        manual_txn_wallet_id(Some(MANUAL_ACCOUNT_OTHER)).to_string(),
        "6d616e75-616c-4000-8000-c00000000000"
    );
    assert!(invalid(&r.add_manual_txn(
        "out",
        1,
        None,
        None,
        1,
        Some("liability:999"),
        None
    )));
    // Deleting an entry whose account is gone still works.
    let orphan = r
        .add_manual_txn("out", 1_000, None, None, 1, Some(&card_ref), None)
        .unwrap();
    r.delete_liability(card).unwrap();
    assert!(r.delete_manual_txn(orphan).unwrap());
}

// TRK-091
#[test]
fn bills_reject_a_blank_name_on_add() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    assert!(invalid(&r.add_bill("", 1_000, 5, None)));
    assert!(invalid(&r.add_bill("   ", 1_000, 5, None)));
    assert!(r.list_bills().unwrap().is_empty());
}

// TRK-091
#[test]
fn bills_reject_a_blank_name_on_update_and_keep_the_row() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    let id = r.add_bill("Rent", 1_000, 5, None).unwrap();
    assert!(invalid(&r.update_bill(id, " ", 2_000, 6, None)));
    let row = &r.list_bills().unwrap()[0];
    assert_eq!(
        (row.name.as_str(), row.amount_sat, row.due_day),
        ("Rent", 1_000, 5)
    );
}

// TRK-098
#[test]
fn budgets_reject_a_blank_category() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    assert!(invalid(&r.set_budget("", 1_000, "monthly")));
    assert!(invalid(&r.set_budget(" \t", 1_000, "monthly")));
    assert!(r.list_budgets().unwrap().is_empty());
}

// TRK-098
#[test]
fn budgets_accept_only_the_periods_the_ui_can_prorate() {
    let dir = TempDir::new().unwrap();
    let r = open(&dir);
    for p in ["weekly", "monthly", "yearly"] {
        r.set_budget(&format!("c-{p}"), 1_000, p).unwrap();
    }
    for p in ["", "daily", "Monthly", "quarterly"] {
        assert!(invalid(&r.set_budget("Food", 1_000, p)), "{p:?} accepted");
    }
    assert_eq!(r.list_budgets().unwrap().len(), 3);
}
