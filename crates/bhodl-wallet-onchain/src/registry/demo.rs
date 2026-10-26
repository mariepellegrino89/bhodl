//! v9 — test-mode profiles ("modalità test").
//!
//! A test-mode registry is an ordinary registry marked as such in
//! `profile_meta`. It carries its own wallet transactions
//! (`demo_events`: watch wallets in a test profile never sync) and its
//! own BTC rates (`btc_rate_history` rows with source `demo`), so a
//! profile copied to another machine shows the same figures offline.
//! Everything that only makes sense on test data — adding demo events,
//! writing rates, shifting every date — refuses a real registry.

use super::{Registry, sat_to_i64};
use crate::btc_rate_history::normalize_to_utc_midnight;
use crate::error::OnchainError;

/// v9 — free key/value facts about the profile (test-mode flag, its
/// modules…).
pub(super) const CREATE_PROFILE_META_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS profile_meta (\
    key   TEXT PRIMARY KEY,\
    value TEXT NOT NULL\
)";

/// v9 — wallet transactions of a test-mode profile. `direction` is
/// `"in"` or `"out"`; `block_height` null = unconfirmed.
pub(super) const CREATE_DEMO_EVENTS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS demo_events (\
    wallet_id     TEXT NOT NULL,\
    event_id      TEXT NOT NULL,\
    direction     TEXT NOT NULL,\
    amount_sat    INTEGER NOT NULL,\
    fee_sat       INTEGER,\
    occurred_unix INTEGER NOT NULL,\
    block_height  INTEGER,\
    PRIMARY KEY (wallet_id, event_id)\
)";

/// `profile_meta` key marking a test-mode profile.
const DEMO_PROFILE_KEY: &str = "demo_profile";

/// Source tag of the rates a test-mode profile carries.
const DEMO_RATE_SOURCE: &str = "demo";

/// Moves every date out of the way first, so a shift never collides
/// with a primary key holding a date (`btc_rate_history`).
const SHIFT_PARKING_SECS: i64 = 1 << 40;

const SECS_PER_DAY: i64 = 86_400;

/// One wallet transaction of a test-mode profile.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DemoEvent {
    pub wallet_id: String,
    pub event_id: String,
    /// `"in"` or `"out"`.
    pub direction: String,
    pub amount_sat: u64,
    pub fee_sat: Option<u64>,
    pub occurred_unix: i64,
    pub block_height: Option<u32>,
}

fn storage(e: impl std::fmt::Display) -> OnchainError {
    OnchainError::Storage(e.to_string())
}

impl Registry {
    /// Mark this registry as a test-mode profile. Irreversible: a test
    /// profile never becomes a real one.
    ///
    /// # Errors
    ///
    /// `Storage` on a write failure.
    pub fn mark_demo_profile(&self) -> Result<(), OnchainError> {
        self.conn
            .execute(
                "INSERT OR REPLACE INTO profile_meta (key, value) VALUES (?1, '1')",
                [DEMO_PROFILE_KEY],
            )
            .map_err(storage)?;
        Ok(())
    }

    /// True for a test-mode profile.
    ///
    /// # Errors
    ///
    /// `Storage` on a read failure.
    pub fn is_demo_profile(&self) -> Result<bool, OnchainError> {
        Ok(self.profile_meta(DEMO_PROFILE_KEY)?.as_deref() == Some("1"))
    }

    fn require_demo(&self) -> Result<(), OnchainError> {
        if self.is_demo_profile()? {
            Ok(())
        } else {
            Err(OnchainError::InvalidArgument(
                "only allowed on a test-mode profile".into(),
            ))
        }
    }

    /// A `profile_meta` value, `None` when unset.
    ///
    /// # Errors
    ///
    /// `Storage` on a read failure.
    pub fn profile_meta(&self, key: &str) -> Result<Option<String>, OnchainError> {
        use rusqlite::OptionalExtension;
        self.conn
            .query_row(
                "SELECT value FROM profile_meta WHERE key = ?1",
                [key],
                |r| r.get(0),
            )
            .optional()
            .map_err(storage)
    }

    /// Set (or, with `None`, remove) a `profile_meta` value. The
    /// test-mode flag itself is not settable here.
    ///
    /// # Errors
    ///
    /// `InvalidArgument` for the test-mode key, `Storage` on a write
    /// failure.
    pub fn set_profile_meta(&self, key: &str, value: Option<&str>) -> Result<(), OnchainError> {
        if key == DEMO_PROFILE_KEY {
            return Err(OnchainError::InvalidArgument(
                "the test-mode flag is set with mark_demo_profile".into(),
            ));
        }
        match value {
            Some(v) => self.conn.execute(
                "INSERT OR REPLACE INTO profile_meta (key, value) VALUES (?1, ?2)",
                [key, v],
            ),
            None => self
                .conn
                .execute("DELETE FROM profile_meta WHERE key = ?1", [key]),
        }
        .map_err(storage)?;
        Ok(())
    }

    /// Add (or replace) a wallet transaction of a test-mode profile.
    ///
    /// # Errors
    ///
    /// `InvalidArgument` on a real registry, a direction other than
    /// `"in"` / `"out"` or a zero amount; `Storage` on a write failure.
    pub fn add_demo_event(&self, event: &DemoEvent) -> Result<(), OnchainError> {
        self.require_demo()?;
        if event.direction != "in" && event.direction != "out" {
            return Err(OnchainError::InvalidArgument(format!(
                "direction must be in or out, got {}",
                event.direction
            )));
        }
        if event.amount_sat == 0 {
            return Err(OnchainError::InvalidArgument(
                "amount_sat must be positive".into(),
            ));
        }
        let fee = event
            .fee_sat
            .map(|f| sat_to_i64("fee_sat", f))
            .transpose()?;
        self.conn
            .execute(
                "INSERT OR REPLACE INTO demo_events \
                    (wallet_id, event_id, direction, amount_sat, fee_sat, occurred_unix, block_height) \
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                rusqlite::params![
                    event.wallet_id,
                    event.event_id,
                    event.direction,
                    sat_to_i64("amount_sat", event.amount_sat)?,
                    fee,
                    event.occurred_unix,
                    event.block_height,
                ],
            )
            .map_err(storage)?;
        Ok(())
    }

    /// The test-mode wallet transactions of wallets still in the
    /// registry, oldest first. Empty on a real registry.
    ///
    /// # Errors
    ///
    /// `Storage` on a read failure.
    pub fn list_demo_events(&self) -> Result<Vec<DemoEvent>, OnchainError> {
        let mut stmt = self
            .conn
            .prepare(
                "SELECT e.wallet_id, e.event_id, e.direction, e.amount_sat, e.fee_sat, \
                        e.occurred_unix, e.block_height \
                 FROM demo_events e JOIN wallets w ON w.id = e.wallet_id \
                 ORDER BY e.occurred_unix, e.event_id",
            )
            .map_err(storage)?;
        let rows = stmt
            .query_map([], |row| {
                Ok(DemoEvent {
                    wallet_id: row.get(0)?,
                    event_id: row.get(1)?,
                    direction: row.get(2)?,
                    amount_sat: row.get::<_, i64>(3)?.cast_unsigned(),
                    fee_sat: row.get::<_, Option<i64>>(4)?.map(i64::cast_unsigned),
                    occurred_unix: row.get(5)?,
                    block_height: row.get(6)?,
                })
            })
            .map_err(storage)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(storage)
    }

    /// Write the BTC rates of a test-mode profile (one per day,
    /// normalised to UTC midnight), replacing any rate of those days.
    ///
    /// # Errors
    ///
    /// `InvalidArgument` on a real registry or a non-positive rate,
    /// `Storage` on a write failure.
    pub fn put_demo_rates(
        &self,
        currency: &str,
        points: &[(i64, f64)],
    ) -> Result<(), OnchainError> {
        self.require_demo()?;
        if points.iter().any(|(_, r)| !r.is_finite() || *r <= 0.0) {
            return Err(OnchainError::InvalidArgument(
                "rates must be positive".into(),
            ));
        }
        let tx = self.conn.unchecked_transaction().map_err(storage)?;
        {
            let mut stmt = tx
                .prepare(
                    "INSERT OR REPLACE INTO btc_rate_history \
                        (date_unix, currency, rate, source, fetched_at_unix) \
                     VALUES (?1, ?2, ?3, ?4, ?1)",
                )
                .map_err(storage)?;
            for (day, rate) in points {
                stmt.execute(rusqlite::params![
                    normalize_to_utc_midnight(*day),
                    currency,
                    rate,
                    DEMO_RATE_SOURCE
                ])
                .map_err(storage)?;
            }
        }
        tx.commit().map_err(storage)
    }

    /// Move every date of a test-mode profile by `days` (positive =
    /// later): every `*_unix` column of every table, rates included, so
    /// shared test data stays recent. Returns the number of rows
    /// touched.
    ///
    /// # Errors
    ///
    /// `InvalidArgument` on a real registry, `Storage` on a failure
    /// (nothing is changed then).
    pub fn shift_demo_dates(&self, days: i64) -> Result<u64, OnchainError> {
        self.require_demo()?;
        if days == 0 {
            return Ok(0);
        }
        let delta = days
            .checked_mul(SECS_PER_DAY)
            .ok_or_else(|| OnchainError::InvalidArgument("shift too large".into()))?;
        let tx = self.conn.unchecked_transaction().map_err(storage)?;
        let tables: Vec<String> = {
            let mut stmt = tx
                .prepare(
                    "SELECT name FROM sqlite_master \
                     WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'profile_meta'",
                )
                .map_err(storage)?;
            stmt.query_map([], |r| r.get(0))
                .map_err(storage)?
                .collect::<Result<_, _>>()
                .map_err(storage)?
        };
        let mut touched = 0u64;
        for table in tables {
            let columns: Vec<String> = {
                let mut stmt = tx
                    .prepare("SELECT name FROM pragma_table_info(?1)")
                    .map_err(storage)?;
                stmt.query_map([&table], |r| r.get::<_, String>(0))
                    .map_err(storage)?
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(storage)?
                    .into_iter()
                    .filter(|c| c.ends_with("_unix"))
                    .collect()
            };
            if columns.is_empty() {
                continue;
            }
            // Names come from the schema itself, quoted for safety.
            let set = |offset: &str| {
                columns
                    .iter()
                    .map(|c| format!("\"{c}\" = \"{c}\" + {offset}"))
                    .collect::<Vec<_>>()
                    .join(", ")
            };
            let n = tx
                .execute(
                    &format!(
                        "UPDATE \"{table}\" SET {}",
                        set(&(delta + SHIFT_PARKING_SECS).to_string())
                    ),
                    [],
                )
                .map_err(storage)?;
            tx.execute(
                &format!(
                    "UPDATE \"{table}\" SET {}",
                    set(&(-SHIFT_PARKING_SECS).to_string())
                ),
                [],
            )
            .map_err(storage)?;
            touched += n as u64;
        }
        tx.commit().map_err(storage)?;
        Ok(touched)
    }
}

#[cfg(test)]
mod tests {
    use super::super::{
        ENCRYPTION_KEY_LEN, REGISTRY_FILE_NAME, WalletKind, WalletRecord, open_registry,
    };
    use super::*;
    use tempfile::TempDir;
    use uuid::Uuid;

    const DAY: i64 = 86_400;
    /// 2026-01-10 00:00 UTC.
    const D0: i64 = 1_768_003_200;

    fn open(dir: &TempDir) -> Registry {
        open_registry(
            &dir.path().join(REGISTRY_FILE_NAME),
            &[0x42u8; ENCRYPTION_KEY_LEN],
        )
        .unwrap()
    }

    fn wallet(r: &Registry) -> String {
        let id = Uuid::new_v4();
        r.add_wallet(&WalletRecord {
            id,
            label: "Demo".into(),
            kind: WalletKind::WatchAddresses,
            descriptor_pub: "addr(tb1qdemo)".into(),
            network: "signet".into(),
            created_at_unix: D0,
        })
        .unwrap();
        id.to_string()
    }

    fn event(wallet_id: &str, id: &str, direction: &str, sat: u64, at: i64) -> DemoEvent {
        DemoEvent {
            wallet_id: wallet_id.into(),
            event_id: id.into(),
            direction: direction.into(),
            amount_sat: sat,
            fee_sat: (direction == "out").then_some(200),
            occurred_unix: at,
            block_height: Some(300_000),
        }
    }

    fn invalid<T>(r: &Result<T, OnchainError>) -> bool {
        matches!(r, Err(OnchainError::InvalidArgument(_)))
    }

    fn rate_rows(r: &Registry) -> Vec<(i64, f64)> {
        let mut stmt = r
            .conn
            .prepare("SELECT date_unix, rate FROM btc_rate_history WHERE currency = 'EUR' ORDER BY date_unix")
            .unwrap();
        stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .unwrap()
            .collect::<Result<_, _>>()
            .unwrap()
    }

    #[test]
    fn a_real_registry_refuses_every_test_only_write() {
        let dir = TempDir::new().unwrap();
        let r = open(&dir);
        let w = wallet(&r);
        assert!(!r.is_demo_profile().unwrap());
        assert!(invalid(&r.add_demo_event(&event(&w, "a", "in", 1_000, D0))));
        assert!(invalid(&r.put_demo_rates("EUR", &[(D0, 90_000.0)])));
        assert!(invalid(&r.shift_demo_dates(3)));
        assert!(r.list_demo_events().unwrap().is_empty());
        // The flag is only set on purpose, never through the generic setter.
        assert!(invalid(&r.set_profile_meta(DEMO_PROFILE_KEY, Some("1"))));
        assert!(!r.is_demo_profile().unwrap());
    }

    #[test]
    fn profile_facts_round_trip() {
        let dir = TempDir::new().unwrap();
        let r = open(&dir);
        r.set_profile_meta("modules", Some("{\"budget\":true}"))
            .unwrap();
        assert_eq!(
            r.profile_meta("modules").unwrap().as_deref(),
            Some("{\"budget\":true}")
        );
        r.set_profile_meta("modules", None).unwrap();
        assert_eq!(r.profile_meta("modules").unwrap(), None);
        r.mark_demo_profile().unwrap();
        assert!(r.is_demo_profile().unwrap());
        // Survives a reopen.
        drop(r);
        assert!(open(&dir).is_demo_profile().unwrap());
    }

    #[test]
    fn demo_events_are_validated_and_listed_for_existing_wallets_only() {
        let dir = TempDir::new().unwrap();
        let r = open(&dir);
        r.mark_demo_profile().unwrap();
        let w = wallet(&r);
        assert!(invalid(
            &r.add_demo_event(&event(&w, "x", "self", 1_000, D0))
        ));
        assert!(invalid(&r.add_demo_event(&event(&w, "x", "in", 0, D0))));
        r.add_demo_event(&event(&w, "b", "out", 2_000, D0 + DAY))
            .unwrap();
        r.add_demo_event(&event(&w, "a", "in", 5_000, D0)).unwrap();
        r.add_demo_event(&event("gone-wallet", "c", "in", 7_000, D0))
            .unwrap();
        let listed = r.list_demo_events().unwrap();
        assert_eq!(
            listed
                .iter()
                .map(|e| e.event_id.as_str())
                .collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert_eq!(listed[1].fee_sat, Some(200));
    }

    #[test]
    fn shifting_moves_every_date_and_keeps_rates_one_per_day() {
        let dir = TempDir::new().unwrap();
        let r = open(&dir);
        r.mark_demo_profile().unwrap();
        let w = wallet(&r);
        r.add_demo_event(&event(&w, "a", "in", 5_000, D0)).unwrap();
        r.add_manual_txn("out", 1_000, None, None, D0 + DAY, None, None)
            .unwrap();
        let paid = r.add_bill("Rent", 900_000, 1, None).unwrap();
        r.mark_bill_paid(paid, Some(D0)).unwrap();
        let unpaid = r.add_bill("Gym", 40_000, 3, None).unwrap();
        // Consecutive days: a one-day shift lands each on its neighbour's slot.
        r.put_demo_rates(
            "EUR",
            &[
                (D0, 90_000.0),
                (D0 + DAY, 91_000.0),
                (D0 + 2 * DAY, 92_000.0),
            ],
        )
        .unwrap();

        assert!(r.shift_demo_dates(1).unwrap() > 0);

        assert_eq!(r.list_demo_events().unwrap()[0].occurred_unix, D0 + DAY);
        assert_eq!(r.list_manual_txns().unwrap()[0].occurred_unix, D0 + 2 * DAY);
        let bills = r.list_bills().unwrap();
        let bill = |id| bills.iter().find(|b| b.id == id).unwrap();
        assert_eq!(bill(paid).last_paid_unix, Some(D0 + DAY));
        assert_eq!(
            bill(unpaid).last_paid_unix,
            None,
            "an empty date stays empty"
        );
        assert_eq!(
            rate_rows(&r),
            [
                (D0 + DAY, 90_000.0),
                (D0 + 2 * DAY, 91_000.0),
                (D0 + 3 * DAY, 92_000.0)
            ]
        );

        r.shift_demo_dates(-1).unwrap();
        assert_eq!(rate_rows(&r)[0], (D0, 90_000.0));
        assert!(r.is_demo_profile().unwrap(), "profile facts are not dates");
    }
}
