//! Registry schema migrations (docs/use-cases.md GAP-025..GAP-028),
//! driven through the public API on plain (unencrypted) `SQLite` files:
//! [`open_registry_in_memory`] runs the same `ensure_schema` as the
//! `SQLCipher` path, and a second connection inspects the result.

use std::path::{Path, PathBuf};

use bhodl_wallet_onchain::{Registry, open_registry_in_memory};
use rusqlite::Connection;

const SCHEMA_VERSION: i64 = 5;

fn db_path(dir: &tempfile::TempDir) -> PathBuf {
    dir.path().join("registry.sqlite")
}

fn open(path: &Path) -> Result<Registry, bhodl_wallet_onchain::OnchainError> {
    open_registry_in_memory(Connection::open(path).unwrap())
}

/// Fresh current-schema DB, then `prepare` rewinds it to an older shape.
fn rewound(path: &Path, prepare: &str) {
    drop(open(path).unwrap());
    let conn = Connection::open(path).unwrap();
    conn.execute_batch(prepare).unwrap();
}

fn user_version(path: &Path) -> i64 {
    Connection::open(path)
        .unwrap()
        .query_row("PRAGMA user_version", [], |r| r.get(0))
        .unwrap()
}

fn columns(path: &Path, table: &str) -> Vec<String> {
    let conn = Connection::open(path).unwrap();
    let mut stmt = conn
        .prepare(&format!("SELECT name FROM pragma_table_info('{table}')"))
        .unwrap();
    stmt.query_map([], |r| r.get::<_, String>(0))
        .unwrap()
        .map(Result::unwrap)
        .collect()
}

fn table_exists(path: &Path, table: &str) -> bool {
    Connection::open(path)
        .unwrap()
        .query_row(
            "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?1",
            [table],
            |r| r.get::<_, i64>(0),
        )
        .unwrap()
        == 1
}

fn mirror_of(path: &Path, id: &str) -> Option<String> {
    Connection::open(path)
        .unwrap()
        .query_row("SELECT mirror_of FROM wallets WHERE id = ?1", [id], |r| {
            r.get(0)
        })
        .unwrap()
}

// GAP-025
#[test]
fn v3_to_v4_links_only_unambiguous_app_generated_mirrors() {
    let dir = tempfile::tempdir().unwrap();
    let path = db_path(&dir);
    rewound(
        &path,
        "ALTER TABLE wallets DROP COLUMN mirror_of;
         INSERT INTO wallets (id, label, kind, descriptor_pub, network, created_at_unix) VALUES
           ('s-main', 'Main', 'signing', 'tr([aa/86h/1h/0h]tpubMAIN/0/*)', 'signet', 1),
           ('w-main', 'Main (tracker)', 'watch_xpub', 'tr(tpubMAIN/0/*)', 'signet', 2),
           ('s-alt', 'Alt', 'signing', 'tr(tpubALT/0/*)', 'signet', 3),
           ('w-alt1', 'Alt (tracker)', 'watch_xpub', 'tr(tpubALT/0/*)', 'signet', 4),
           ('w-alt2', 'Alt · tracker', 'watch_xpub', 'tr(tpubALT/0/*)', 'signet', 5),
           ('s-cold', 'Cold', 'signing', 'tr(tpubCOLD/0/*)', 'signet', 6),
           ('w-cold', 'My cold watch', 'watch_xpub', 'tr(tpubCOLD/0/*)', 'signet', 7),
           ('s-hot', 'Hot', 'signing', 'tr(tpubHOT/0/*)', 'signet', 8),
           ('w-hot', 'Hot (tracker)', 'watch_xpub', 'tr(tpubOTHER/0/*)', 'signet', 9),
           ('s-net', 'Net', 'signing', 'tr(tpubNET/0/*)', 'signet', 10),
           ('w-net', 'Net (tracker 2)', 'watch_xpub', 'tr(tpubNET/0/*)', 'testnet4', 11);
         PRAGMA user_version = 3;",
    );

    drop(open(&path).unwrap());

    assert_eq!(user_version(&path), SCHEMA_VERSION);
    assert_eq!(mirror_of(&path, "w-main").as_deref(), Some("s-main"));
    assert_eq!(
        mirror_of(&path, "w-alt1"),
        None,
        "two mirrors for one signing wallet"
    );
    assert_eq!(
        mirror_of(&path, "w-alt2"),
        None,
        "two mirrors for one signing wallet"
    );
    assert_eq!(mirror_of(&path, "w-cold"), None, "user label");
    assert_eq!(mirror_of(&path, "w-hot"), None, "different xpub");
    assert_eq!(mirror_of(&path, "w-net"), None, "different network");
    assert_eq!(mirror_of(&path, "s-main"), None);
}

// GAP-026
#[test]
fn v4_to_v5_creates_the_cost_basis_table_and_keeps_existing_data() {
    let dir = tempfile::tempdir().unwrap();
    let path = db_path(&dir);
    rewound(
        &path,
        "DROP TABLE tracking_event_cost_basis;
         INSERT INTO wallets (id, label, kind, descriptor_pub, network, created_at_unix)
           VALUES ('00000000-0000-4000-8000-000000000001', 'Savings', 'watch_xpub',
                   'tr(tpubSAV/0/*)', 'signet', 1);
         INSERT INTO tracking_categories VALUES ('w1', 'tx1', 'food', 1);
         INSERT INTO tracking_budgets (category, budgeted_sat, updated_at_unix)
           VALUES ('food', 5000, 1);
         PRAGMA user_version = 4;",
    );
    assert!(!table_exists(&path, "tracking_event_cost_basis"));

    let registry = open(&path).unwrap();
    assert_eq!(user_version(&path), SCHEMA_VERSION);
    assert!(table_exists(&path, "tracking_event_cost_basis"));

    let wallets = registry.list_wallets().unwrap();
    assert_eq!(wallets.len(), 1);
    assert_eq!(wallets[0].label, "Savings");
    let budgets = registry.list_budgets().unwrap();
    assert_eq!(budgets.len(), 1);

    registry
        .set_event_cost_basis("w1", "tx1", Some((30_000.0, "EUR")), false)
        .unwrap();
    let rows = registry.list_event_cost_basis().unwrap();
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].price, Some((30_000.0, "EUR".to_string())));
}

// GAP-027
#[test]
fn a_failing_migration_step_rolls_back_to_the_previous_version() {
    let dir = tempfile::tempdir().unwrap();
    let path = db_path(&dir);
    rewound(
        &path,
        "ALTER TABLE tracking_investment_lots DROP COLUMN original_quantity;
         DROP TABLE tracking_event_cost_basis;
         INSERT INTO tracking_investments
           (id, name, quantity, cost_basis_fiat, purchase_unix, created_at_unix)
           VALUES (1, 'ETF', 10, 1000, 1, 1);
         INSERT INTO tracking_investment_lots
           (investment_id, quantity, price_per_unit, purchase_unix, created_at_unix)
           VALUES (1, 10, 100, 1, 1);
         CREATE TRIGGER block_backfill BEFORE UPDATE ON tracking_investment_lots
           BEGIN SELECT RAISE(ABORT, 'blocked by test'); END;
         PRAGMA user_version = 1;",
    );

    let err = open(&path).expect_err("the v2 backfill must fail");
    assert!(err.to_string().contains("blocked by test"), "{err}");

    assert_eq!(user_version(&path), 1, "the stamp must not move");
    assert!(
        !columns(&path, "tracking_investment_lots").contains(&"original_quantity".to_string()),
        "the v2 ALTER must be rolled back with the failed backfill",
    );
    assert!(!table_exists(&path, "tracking_event_cost_basis"));

    // Once the obstacle is gone the same DB migrates in one go.
    Connection::open(&path)
        .unwrap()
        .execute_batch("DROP TRIGGER block_backfill;")
        .unwrap();
    drop(open(&path).unwrap());
    assert_eq!(user_version(&path), SCHEMA_VERSION);
    assert!(columns(&path, "tracking_investment_lots").contains(&"original_quantity".to_string()));
}

// GAP-028
#[test]
fn a_pre_versioning_registry_migrates_to_the_current_schema_in_one_open() {
    let dir = tempfile::tempdir().unwrap();
    let path = db_path(&dir);
    {
        // Shape of a registry written before schema versioning: no
        // user_version stamp and none of the columns added since.
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(
            "CREATE TABLE wallets (
                 id TEXT PRIMARY KEY, label TEXT NOT NULL, kind TEXT NOT NULL,
                 descriptor_pub TEXT NOT NULL, network TEXT NOT NULL,
                 created_at_unix INTEGER NOT NULL,
                 pins_warned INTEGER NOT NULL DEFAULT 0);
             CREATE TABLE tracking_investments (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                 asset_type TEXT NOT NULL DEFAULT 'manual', ticker TEXT,
                 quantity REAL NOT NULL, unit TEXT NOT NULL DEFAULT '',
                 cost_basis_fiat REAL NOT NULL,
                 cost_basis_currency TEXT NOT NULL DEFAULT 'EUR',
                 purchase_unix INTEGER NOT NULL, manual_current_value REAL,
                 last_priced_at_unix INTEGER, notes TEXT,
                 created_at_unix INTEGER NOT NULL);
             CREATE TABLE tracking_investment_lots (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, investment_id INTEGER NOT NULL,
                 quantity REAL NOT NULL, price_per_unit REAL NOT NULL,
                 fees_fiat REAL NOT NULL DEFAULT 0, purchase_unix INTEGER NOT NULL,
                 notes TEXT, created_at_unix INTEGER NOT NULL);
             CREATE TABLE tracking_event_crypto_kind (
                 wallet_id TEXT NOT NULL, txid_or_payment_id TEXT NOT NULL,
                 kind TEXT NOT NULL, updated_at_unix INTEGER NOT NULL,
                 PRIMARY KEY (wallet_id, txid_or_payment_id));
             CREATE TABLE tracking_savings_goals (
                 id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL,
                 target_sat INTEGER NOT NULL, current_sat INTEGER NOT NULL DEFAULT 0,
                 color TEXT NOT NULL DEFAULT 'cyan', deadline_unix INTEGER,
                 linked_wallet_id TEXT, created_at_unix INTEGER NOT NULL);
             INSERT INTO wallets (id, label, kind, descriptor_pub, network, created_at_unix)
               VALUES ('00000000-0000-4000-8000-0000000000aa', 'Old', 'watch_xpub',
                       'tr(tpubOLD/0/*)', 'signet', 1);
             INSERT INTO tracking_investments
               (id, name, quantity, cost_basis_fiat, purchase_unix, created_at_unix)
               VALUES (1, 'ETF', 4, 400, 1, 1);
             INSERT INTO tracking_investment_lots
               (investment_id, quantity, price_per_unit, purchase_unix, created_at_unix)
               VALUES (1, 4, 100, 1, 1);
             INSERT INTO tracking_event_crypto_kind VALUES ('w', 'a', 'airdrop', 1);
             INSERT INTO tracking_event_crypto_kind VALUES ('w', 's', 'send_to_self', 1);
             INSERT INTO tracking_savings_goals (name, target_sat, created_at_unix)
               VALUES ('House', 1000, 1);",
        )
        .unwrap();
    }
    assert_eq!(user_version(&path), 0);

    let registry = open(&path).unwrap();
    assert_eq!(user_version(&path), SCHEMA_VERSION);

    let has = |table: &str, col: &str| columns(&path, table).contains(&col.to_string());
    assert!(has("wallets", "mirror_of"));
    assert!(has("tracking_investments", "cost_basis_method"));
    assert!(has("tracking_investments", "fetched_current_value"));
    assert!(has("tracking_investment_lots", "original_quantity"));
    assert!(has("tracking_investment_lots", "btc_per_unit_at_purchase"));
    assert!(has("tracking_event_crypto_kind", "user_set"));
    for col in [
        "target_pct_of_net_worth",
        "bucket_kind",
        "bucket_ref",
        "goal_kind",
        "target_denomination",
        "target_fiat_amount",
        "target_fiat_currency",
    ] {
        assert!(has("tracking_savings_goals", col), "{col}");
    }
    for table in [
        "tracking_categories",
        "tracking_budgets",
        "tracking_bills",
        "tracking_liabilities",
        "tracking_manual_assets",
        "tracking_rules",
        "tracking_investment_sells",
        "tracking_lot_disposals",
        "btc_rate_history",
        "tracking_event_cost_basis",
    ] {
        assert!(table_exists(&path, table), "{table}");
    }

    assert_eq!(registry.list_wallets().unwrap().len(), 1);
    let original: f64 = Connection::open(&path)
        .unwrap()
        .query_row(
            "SELECT original_quantity FROM tracking_investment_lots",
            [],
            |r| r.get(0),
        )
        .unwrap();
    assert!((original - 4.0).abs() < 1e-12);
    let mut kinds = registry.dump_crypto_event_kinds().unwrap();
    kinds.sort();
    assert_eq!(
        kinds,
        vec![
            ("w".into(), "a".into(), "airdrop".into(), true),
            ("w".into(), "s".into(), "send_to_self".into(), false),
        ]
    );
}
