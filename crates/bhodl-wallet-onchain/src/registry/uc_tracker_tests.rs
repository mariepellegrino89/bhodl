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
