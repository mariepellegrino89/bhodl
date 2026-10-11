//! Local address book — `(label, address)` pairs persisted inside the
//! same ``SQLCipher`` database as the wallet itself.
//!
//! BIP-329 already covers labelling individual addresses on the wire
//! (kind = `Addr`). The address book is a separate concern: it lets
//! the user store recipients they pay repeatedly under a friendly
//! name without having to know about a specific outpoint or wallet
//! address. Entries are local — they never travel over the network
//! and they never leave the encrypted DB.
//!
//! Threat model: identical to the rest of the `SQLCipher` store. The
//! recipient addresses are not secret in themselves (they are public
//! Bitcoin addresses) but the `(label -> address)` mapping is privacy-
//! sensitive — knowing that "Carol's address" is `tb1q…` lets an
//! observer correlate transactions to a person. Encryption-at-rest
//! by the wallet's PIN-derived key keeps that private from anyone
//! who only has access to the DB file.

use rusqlite::{Connection, params};

use crate::error::OnchainError;

pub(crate) const CREATE_CONTACTS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS bhodl_contacts (\
    id         INTEGER PRIMARY KEY AUTOINCREMENT,\
    label      TEXT NOT NULL,\
    address    TEXT NOT NULL UNIQUE,\
    created_at INTEGER NOT NULL\
)";

/// One entry in the address book.
///
/// `id` is the SQL row id; the FFI / UI uses it as a stable handle for
/// `remove_contact`. `created_at_unix` is whatever the server-side OS
/// reported at insert time — the UI should treat it as advisory and
/// always allow manual sorting.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Contact {
    pub id: i64,
    pub label: String,
    pub address: String,
    pub created_at_unix: i64,
}

/// Insert a new contact. Returns the generated row id.
///
/// The `address` column has a `UNIQUE` constraint so a second insert
/// with the same `address` value fails with [`OnchainError::Storage`].
/// Callers that want "upsert" semantics should call
/// [`update_contact_label`] first and fall through here on
/// `RowNotFound`.
pub(crate) fn insert_contact(
    conn: &Connection,
    label: &str,
    address: &str,
    created_at_unix: i64,
) -> Result<i64, OnchainError> {
    conn.execute(
        "INSERT INTO bhodl_contacts (label, address, created_at) VALUES (?1, ?2, ?3)",
        params![label, address, created_at_unix],
    )
    .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(conn.last_insert_rowid())
}

/// Return every contact in insertion order.
pub(crate) fn list_contacts(conn: &Connection) -> Result<Vec<Contact>, OnchainError> {
    let mut stmt = conn
        .prepare("SELECT id, label, address, created_at FROM bhodl_contacts ORDER BY id ASC")
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    let rows = stmt
        .query_map([], |row| {
            Ok(Contact {
                id: row.get(0)?,
                label: row.get(1)?,
                address: row.get(2)?,
                created_at_unix: row.get(3)?,
            })
        })
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    let mut out = Vec::new();
    for r in rows {
        out.push(r.map_err(|e| OnchainError::Storage(e.to_string()))?);
    }
    Ok(out)
}

/// Update the label of an existing contact.
///
/// Returns `true` if a row was updated, `false` if no row matched
/// `id`. The address itself cannot be edited — change a recipient by
/// removing and re-adding it, so the UNIQUE invariant on `address`
/// stays trivially honest.
pub(crate) fn update_contact_label(
    conn: &Connection,
    id: i64,
    new_label: &str,
) -> Result<bool, OnchainError> {
    let n = conn
        .execute(
            "UPDATE bhodl_contacts SET label = ?1 WHERE id = ?2",
            params![new_label, id],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(n > 0)
}

pub(crate) fn delete_contact(conn: &Connection, id: i64) -> Result<bool, OnchainError> {
    let n = conn
        .execute("DELETE FROM bhodl_contacts WHERE id = ?1", params![id])
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(n > 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    fn fresh() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute(CREATE_CONTACTS_TABLE_SQL, []).unwrap();
        conn
    }

    #[test]
    fn insert_returns_monotonic_ids() {
        let conn = fresh();
        let id1 = insert_contact(&conn, "A", "addr-a", 100).unwrap();
        let id2 = insert_contact(&conn, "B", "addr-b", 200).unwrap();
        assert!(id2 > id1, "ids must increase");
    }

    #[test]
    fn list_returns_insertion_order() {
        let conn = fresh();
        insert_contact(&conn, "A", "addr-a", 100).unwrap();
        insert_contact(&conn, "B", "addr-b", 200).unwrap();
        insert_contact(&conn, "C", "addr-c", 300).unwrap();
        let labels: Vec<_> = list_contacts(&conn)
            .unwrap()
            .into_iter()
            .map(|c| c.label)
            .collect();
        assert_eq!(
            labels,
            vec!["A".to_string(), "B".to_string(), "C".to_string()]
        );
    }

    #[test]
    fn list_on_empty_table_returns_empty_vec() {
        let conn = fresh();
        assert!(list_contacts(&conn).unwrap().is_empty());
    }

    #[test]
    fn duplicate_address_is_rejected_by_unique_constraint() {
        let conn = fresh();
        insert_contact(&conn, "First", "same-addr", 100).unwrap();
        let dup = insert_contact(&conn, "Second", "same-addr", 200);
        assert!(matches!(dup, Err(OnchainError::Storage(_))));
        // List still has only one row.
        assert_eq!(list_contacts(&conn).unwrap().len(), 1);
    }

    #[test]
    fn update_label_changes_label_and_returns_true() {
        let conn = fresh();
        let id = insert_contact(&conn, "Old", "addr-x", 100).unwrap();
        let updated = update_contact_label(&conn, id, "New").unwrap();
        assert!(updated);
        let entry = list_contacts(&conn).unwrap().pop().unwrap();
        assert_eq!(entry.label, "New");
    }

    #[test]
    fn update_unknown_id_returns_false() {
        let conn = fresh();
        assert!(!update_contact_label(&conn, 9999, "any").unwrap());
    }

    #[test]
    fn delete_existing_returns_true_and_drops_row() {
        let conn = fresh();
        let id = insert_contact(&conn, "Bye", "addr-bye", 100).unwrap();
        assert!(delete_contact(&conn, id).unwrap());
        assert!(list_contacts(&conn).unwrap().is_empty());
    }

    #[test]
    fn delete_unknown_id_returns_false() {
        let conn = fresh();
        assert!(!delete_contact(&conn, 9999).unwrap());
    }

    #[test]
    fn record_round_trips_label_address_timestamp() {
        let conn = fresh();
        let id = insert_contact(&conn, "Carol", "tb1pcarol", 1_700_000_000).unwrap();
        let entry = list_contacts(&conn).unwrap().pop().unwrap();
        assert_eq!(entry.id, id);
        assert_eq!(entry.label, "Carol");
        assert_eq!(entry.address, "tb1pcarol");
        assert_eq!(entry.created_at_unix, 1_700_000_000);
    }
}
