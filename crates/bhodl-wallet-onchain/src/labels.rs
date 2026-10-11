//! `BIP329` labels — wallet annotations stored alongside the BDK changeset
//! in the same `SQLCipher` database.
//!
//! `BIP329` defines a JSONL (newline-delimited JSON) format for sharing
//! wallet labels across software. Each line carries a `type` (tx, addr,
//! output, input, xpub), a `ref` (the txid / address / outpoint / xpub
//! the label points to), and a `label` string. Outputs may also carry
//! a `spendable` boolean (defaults to `true` when omitted).
//!
//! BHODL stores labels in a dedicated `bhodl_labels` table inside the
//! account's `SQLCipher` database. Persistence happens automatically on
//! every `add_label` / `remove_label`; no extra `persist()` call is
//! needed (labels are committed inside their own transaction).

use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};

use crate::error::OnchainError;

/// SQL statement that creates the labels table if it does not exist.
/// Idempotent — safe to call on every account open.
pub(crate) const CREATE_LABELS_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS bhodl_labels (
    label_type TEXT NOT NULL,
    target     TEXT NOT NULL,
    text       TEXT NOT NULL,
    spendable  INTEGER,
    category   TEXT,
    PRIMARY KEY (label_type, target)
)";

/// UX review 2026-10 — one row per `(txid, address)` whose receive
/// label / category was already copied onto the transaction. Lives in
/// the wallet-PIN-encrypted DB (never the plaintext settings file), so
/// a label the user later clears on the transaction is not re-applied.
pub(crate) const CREATE_LABEL_COPIES_TABLE_SQL: &str = "\
CREATE TABLE IF NOT EXISTS bhodl_label_copies (
    txid    TEXT NOT NULL,
    address TEXT NOT NULL,
    PRIMARY KEY (txid, address)
)";

/// `true` when the receive label of `address` was already copied onto `txid`.
pub(crate) fn label_copy_done(
    conn: &Connection,
    txid: &str,
    address: &str,
) -> Result<bool, OnchainError> {
    conn.query_row(
        "SELECT COUNT(*) FROM bhodl_label_copies WHERE txid = ?1 AND address = ?2",
        params![txid, address],
        |r| r.get::<_, i64>(0),
    )
    .map(|n| n > 0)
    .map_err(|e| OnchainError::Storage(e.to_string()))
}

/// Record that the receive label of `address` was copied onto `txid`.
pub(crate) fn mark_label_copy(
    conn: &Connection,
    txid: &str,
    address: &str,
) -> Result<(), OnchainError> {
    conn.execute(
        "INSERT OR IGNORE INTO bhodl_label_copies (txid, address) VALUES (?1, ?2)",
        params![txid, address],
    )
    .map(|_| ())
    .map_err(|e| OnchainError::Storage(e.to_string()))
}

/// BIP329 label kind. Maps 1:1 to the spec's `type` field.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LabelKind {
    Tx,
    Addr,
    Output,
    Input,
    Xpub,
}

impl LabelKind {
    fn as_db_str(self) -> &'static str {
        match self {
            Self::Tx => "tx",
            Self::Addr => "addr",
            Self::Output => "output",
            Self::Input => "input",
            Self::Xpub => "xpub",
        }
    }

    fn parse_db_str(s: &str) -> Result<Self, OnchainError> {
        match s {
            "tx" => Ok(Self::Tx),
            "addr" => Ok(Self::Addr),
            "output" => Ok(Self::Output),
            "input" => Ok(Self::Input),
            "xpub" => Ok(Self::Xpub),
            other => Err(OnchainError::Storage(format!(
                "unknown BIP329 label type {other:?}"
            ))),
        }
    }
}

/// A BIP329 label record.
///
/// `target` carries the value the label points to: a txid for `Tx`, an
/// address string for `Addr`, an outpoint `txid:vout` for `Output`, an
/// `txid:vin` for `Input`, and the xpub string itself for `Xpub`. We
/// keep it as a free-form `String` to stay 1:1 with the wire format —
/// type-checked variants live at the API surface where it matters.
///
/// `spendable` is BIP329-defined and only meaningful for `Output`
/// labels. For every other kind it must be `None`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Label {
    /// BIP329 `type` field.
    #[serde(rename = "type")]
    pub kind: LabelKind,
    /// BIP329 `ref` field. Renamed at the Rust level because `ref` is a
    /// reserved keyword.
    #[serde(rename = "ref")]
    pub target: String,
    /// BIP329 `label` field.
    #[serde(rename = "label")]
    pub text: String,
    /// BIP329 `spendable` field. Only set for `Output` labels.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub spendable: Option<bool>,
    /// BHODL extension to BIP-329 (Phase 3.4) — free-form category
    /// string for spending breakdown ("Food", "Bills", etc.). Other
    /// BIP-329 consumers (Sparrow, Liana) ignore unknown fields per
    /// the spec, so the round-trip is safe.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub category: Option<String>,
}

/// Insert (or replace) a label in the table.
pub(crate) fn upsert_label(conn: &Connection, label: &Label) -> Result<(), OnchainError> {
    conn.execute(
        "INSERT OR REPLACE INTO bhodl_labels \
            (label_type, target, text, spendable, category) \
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![
            label.kind.as_db_str(),
            label.target,
            label.text,
            label.spendable.map(i32::from),
            label.category,
        ],
    )
    .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

/// Look up a label by `(kind, target)`. Returns `None` if absent.
pub(crate) fn get_label(
    conn: &Connection,
    kind: LabelKind,
    target: &str,
) -> Result<Option<Label>, OnchainError> {
    let mut stmt = conn
        .prepare(
            "SELECT label_type, target, text, spendable, category \
             FROM bhodl_labels WHERE label_type = ?1 AND target = ?2",
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    let result = stmt
        .query_row(params![kind.as_db_str(), target], row_to_label)
        .map(Some)
        .or_else(|err| match err {
            rusqlite::Error::QueryReturnedNoRows => Ok(None),
            other => Err(OnchainError::Storage(other.to_string())),
        })?;
    result.transpose()
}

/// Enumerate every label in insertion-undefined order.
pub(crate) fn list_labels(conn: &Connection) -> Result<Vec<Label>, OnchainError> {
    let mut stmt = conn
        .prepare("SELECT label_type, target, text, spendable, category FROM bhodl_labels")
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    let rows = stmt
        .query_map([], row_to_label)
        .map_err(|e| OnchainError::Storage(e.to_string()))?;

    let mut out = Vec::new();
    for row in rows {
        let label = row.map_err(|e| OnchainError::Storage(e.to_string()))??;
        out.push(label);
    }
    Ok(out)
}

/// Remove a label by `(kind, target)`. Returns `true` if a row was deleted.
pub(crate) fn remove_label(
    conn: &Connection,
    kind: LabelKind,
    target: &str,
) -> Result<bool, OnchainError> {
    let affected = conn
        .execute(
            "DELETE FROM bhodl_labels WHERE label_type = ?1 AND target = ?2",
            params![kind.as_db_str(), target],
        )
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(affected > 0)
}

/// Render every label in the connection as a BIP329 JSONL document.
pub(crate) fn export_jsonl(conn: &Connection) -> Result<String, OnchainError> {
    let labels = list_labels(conn)?;
    let mut out = String::new();
    for label in &labels {
        let line = serde_json::to_string(label)
            .map_err(|e| OnchainError::Storage(format!("BIP329 serialise: {e}")))?;
        out.push_str(&line);
        out.push('\n');
    }
    Ok(out)
}

/// Maximum number of non-empty lines accepted by a single `import_jsonl`
/// call. Prevents an adversarially large JSONL file from consuming
/// unbounded memory and DB write time.
const MAX_IMPORT_LINES: usize = 100_000;

/// Maximum byte length of a single JSONL line. BIP-329 records are a
/// few hundred bytes; 64 KiB leaves ample room for long labels while
/// bounding the JSON parser's work per record.
const MAX_IMPORT_LINE_BYTES: usize = 64 * 1024;

/// Field caps applied to imported records (bytes, UTF-8).
const MAX_TARGET_BYTES: usize = 1_024;
const MAX_TEXT_BYTES: usize = 16 * 1024;
const MAX_CATEGORY_BYTES: usize = 256;

/// Upsert used by the importer: a record that omits `category` or
/// `spendable` keeps the value already stored locally instead of
/// overwriting it with NULL (other BIP-329 producers never emit the
/// BHODL `category` extension, and silently un-freezing a coin because
/// the file omitted `spendable` would be a funds-safety regression).
fn merge_imported_label(conn: &Connection, label: &Label) -> Result<(), OnchainError> {
    conn.execute(
        "INSERT INTO bhodl_labels (label_type, target, text, spendable, category) \
         VALUES (?1, ?2, ?3, ?4, ?5) \
         ON CONFLICT(label_type, target) DO UPDATE SET \
            text = excluded.text, \
            spendable = COALESCE(excluded.spendable, bhodl_labels.spendable), \
            category = COALESCE(excluded.category, bhodl_labels.category)",
        params![
            label.kind.as_db_str(),
            label.target,
            label.text,
            label.spendable.map(i32::from),
            label.category,
        ],
    )
    .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(())
}

fn check_imported_field(
    name: &str,
    value: &str,
    max: usize,
    line: usize,
) -> Result<(), OnchainError> {
    if value.len() > max {
        return Err(OnchainError::Storage(format!(
            "BIP329 import aborted: line {line} `{name}` exceeds {max} bytes"
        )));
    }
    Ok(())
}

/// Parse a BIP329 JSONL document and upsert every record. Returns the
/// number of labels imported. Empty / whitespace-only lines are tolerated.
///
/// The whole import runs in ONE transaction: any malformed or oversized
/// record rolls back every row, so a half-applied file never lands.
/// Existing `category` / `spendable` values are preserved when the
/// imported record omits them (see [`merge_imported_label`]).
///
/// # Errors
///
/// Returns [`OnchainError::Storage`] if the input contains more than
/// [`MAX_IMPORT_LINES`] non-empty lines, a line longer than
/// [`MAX_IMPORT_LINE_BYTES`], an oversized field, a line that fails to
/// parse as a BIP329 record, or if any upsert fails.
pub(crate) fn import_jsonl(conn: &Connection, jsonl: &str) -> Result<usize, OnchainError> {
    // Dropping `tx` without `commit()` (any `?` below) rolls back.
    let tx = conn
        .unchecked_transaction()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    let mut count = 0_usize;
    for (idx, line) in jsonl.lines().enumerate() {
        let line_no = idx + 1;
        if line.len() > MAX_IMPORT_LINE_BYTES {
            return Err(OnchainError::Storage(format!(
                "BIP329 import aborted: line {line_no} exceeds {MAX_IMPORT_LINE_BYTES} bytes"
            )));
        }
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        count += 1;
        if count > MAX_IMPORT_LINES {
            return Err(OnchainError::Storage(format!(
                "BIP329 import aborted: input exceeds {MAX_IMPORT_LINES} lines"
            )));
        }
        let label: Label = serde_json::from_str(trimmed)
            .map_err(|e| OnchainError::Storage(format!("BIP329 parse (line {line_no}): {e}")))?;
        check_imported_field("ref", &label.target, MAX_TARGET_BYTES, line_no)?;
        check_imported_field("label", &label.text, MAX_TEXT_BYTES, line_no)?;
        if let Some(c) = &label.category {
            check_imported_field("category", c, MAX_CATEGORY_BYTES, line_no)?;
        }
        merge_imported_label(&tx, &label)?;
    }
    tx.commit()
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    Ok(count)
}

/// Outpoints the user froze via a BIP-329 `output` label with
/// `spendable: false`. Excluded from automatic coin selection and
/// refused in manual selection. Unparseable targets are skipped (a
/// label on something that is not an outpoint cannot match a UTXO).
pub(crate) fn frozen_outpoints(conn: &Connection) -> Result<Vec<bitcoin::OutPoint>, OnchainError> {
    let mut stmt = conn
        .prepare("SELECT target FROM bhodl_labels WHERE label_type = 'output' AND spendable = 0")
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    let rows = stmt
        .query_map([], |r| r.get::<_, String>(0))
        .map_err(|e| OnchainError::Storage(e.to_string()))?;
    let mut out = Vec::new();
    for row in rows {
        let target = row.map_err(|e| OnchainError::Storage(e.to_string()))?;
        if let Ok(op) = target.parse::<bitcoin::OutPoint>() {
            out.push(op);
        }
    }
    Ok(out)
}

/// Convert a single `SQLite` row into a `Result<Label>`. Two layers of
/// error: the rusqlite extraction itself can fail, and the `LabelKind`
/// parser can reject an unknown discriminator.
fn row_to_label(row: &rusqlite::Row<'_>) -> rusqlite::Result<Result<Label, OnchainError>> {
    let kind_str: String = row.get(0)?;
    let target: String = row.get(1)?;
    let text: String = row.get(2)?;
    let spendable: Option<i32> = row.get(3)?;
    let category: Option<String> = row.get(4)?;

    Ok(LabelKind::parse_db_str(&kind_str).map(|kind| Label {
        kind,
        target,
        text,
        spendable: spendable.map(|v| v != 0),
        category,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fresh_conn() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(CREATE_LABELS_TABLE_SQL).unwrap();
        conn
    }

    #[test]
    fn upsert_then_get_round_trips() {
        let conn = fresh_conn();
        let label = Label {
            kind: LabelKind::Tx,
            target: "abcd1234".into(),
            text: "Coffee".into(),
            spendable: None,
            category: None,
        };
        upsert_label(&conn, &label).unwrap();
        let found = get_label(&conn, LabelKind::Tx, "abcd1234").unwrap();
        assert_eq!(found, Some(label));
    }

    #[test]
    fn upsert_replaces_existing_pair() {
        let conn = fresh_conn();
        let mut label = Label {
            kind: LabelKind::Addr,
            target: "bc1pxxx".into(),
            text: "Old".into(),
            spendable: None,
            category: None,
        };
        upsert_label(&conn, &label).unwrap();
        label.text = "New".into();
        upsert_label(&conn, &label).unwrap();
        let stored = get_label(&conn, LabelKind::Addr, "bc1pxxx")
            .unwrap()
            .unwrap();
        assert_eq!(stored.text, "New");
        // Still exactly one row total.
        assert_eq!(list_labels(&conn).unwrap().len(), 1);
    }

    #[test]
    fn remove_returns_true_only_when_row_existed() {
        let conn = fresh_conn();
        let label = Label {
            kind: LabelKind::Output,
            target: "deadbeef:0".into(),
            text: "DCA".into(),
            spendable: Some(true),
            category: None,
        };
        upsert_label(&conn, &label).unwrap();
        assert!(remove_label(&conn, LabelKind::Output, "deadbeef:0").unwrap());
        assert!(!remove_label(&conn, LabelKind::Output, "deadbeef:0").unwrap());
    }

    #[test]
    fn jsonl_round_trip_preserves_labels() {
        let conn = fresh_conn();
        let labels = [
            Label {
                kind: LabelKind::Tx,
                target: "tx-1".into(),
                text: "Salary".into(),
                spendable: None,
                category: None,
            },
            Label {
                kind: LabelKind::Output,
                target: "tx-1:0".into(),
                text: "Cold storage".into(),
                spendable: Some(false),
                category: None,
            },
        ];
        for label in &labels {
            upsert_label(&conn, label).unwrap();
        }
        let jsonl = export_jsonl(&conn).unwrap();
        assert_eq!(jsonl.lines().count(), 2);

        let conn2 = fresh_conn();
        let imported = import_jsonl(&conn2, &jsonl).unwrap();
        assert_eq!(imported, 2);

        let mut got = list_labels(&conn2).unwrap();
        got.sort_by(|a, b| a.target.cmp(&b.target));
        let mut expected = labels.to_vec();
        expected.sort_by(|a, b| a.target.cmp(&b.target));
        assert_eq!(got, expected);
    }

    #[test]
    fn jsonl_omits_spendable_when_none() {
        // Per BIP329, the field is optional; we should not emit it for
        // non-output labels (kind != Output) where it would be ambiguous.
        let label = Label {
            kind: LabelKind::Tx,
            target: "tx-1".into(),
            text: "Pizza".into(),
            spendable: None,
            category: None,
        };
        let line = serde_json::to_string(&label).unwrap();
        assert!(!line.contains("spendable"), "got: {line}");
    }

    #[test]
    fn jsonl_import_tolerates_blank_lines() {
        let conn = fresh_conn();
        let jsonl = "\
            {\"type\":\"tx\",\"ref\":\"tx-1\",\"label\":\"a\"}\n\
            \n\
            \n\
            {\"type\":\"addr\",\"ref\":\"addr-1\",\"label\":\"b\"}\n";
        let imported = import_jsonl(&conn, jsonl).unwrap();
        assert_eq!(imported, 2);
        assert_eq!(list_labels(&conn).unwrap().len(), 2);
    }

    #[test]
    fn jsonl_import_rejects_garbage() {
        let conn = fresh_conn();
        let result = import_jsonl(&conn, "not-a-json-line\n");
        assert!(matches!(result, Err(OnchainError::Storage(_))));
    }

    #[test]
    fn import_jsonl_rejects_input_over_line_cap() {
        let conn = fresh_conn();
        // Build an input with MAX_IMPORT_LINES + 1 non-empty lines.
        // Each line is a valid BIP329 tx label.
        let line = "{\"type\":\"tx\",\"ref\":\"txid\",\"label\":\"x\"}\n";
        let big_input = line.repeat(MAX_IMPORT_LINES + 1);
        let result = import_jsonl(&conn, &big_input);
        assert!(
            matches!(result, Err(OnchainError::Storage(_))),
            "import must be rejected when it exceeds {MAX_IMPORT_LINES} lines; got {result:?}"
        );
    }

    #[test]
    fn import_preserves_existing_category_and_frozen_flag_when_omitted() {
        let conn = fresh_conn();
        upsert_label(
            &conn,
            &Label {
                kind: LabelKind::Tx,
                target: "tx-1".into(),
                text: "old".into(),
                spendable: None,
                category: Some("Food".into()),
            },
        )
        .unwrap();
        upsert_label(
            &conn,
            &Label {
                kind: LabelKind::Output,
                target: "tx-1:0".into(),
                text: "cold".into(),
                spendable: Some(false),
                category: None,
            },
        )
        .unwrap();
        let jsonl = "{\"type\":\"tx\",\"ref\":\"tx-1\",\"label\":\"new\"}\n\
                     {\"type\":\"output\",\"ref\":\"tx-1:0\",\"label\":\"renamed\"}\n";
        assert_eq!(import_jsonl(&conn, jsonl).unwrap(), 2);
        let tx = get_label(&conn, LabelKind::Tx, "tx-1").unwrap().unwrap();
        assert_eq!(tx.text, "new");
        assert_eq!(tx.category.as_deref(), Some("Food"));
        let out = get_label(&conn, LabelKind::Output, "tx-1:0")
            .unwrap()
            .unwrap();
        assert_eq!(out.text, "renamed");
        assert_eq!(out.spendable, Some(false));
        // An explicit value in the file still wins.
        let jsonl = "{\"type\":\"tx\",\"ref\":\"tx-1\",\"label\":\"x\",\"category\":\"Bills\"}\n";
        import_jsonl(&conn, jsonl).unwrap();
        let tx = get_label(&conn, LabelKind::Tx, "tx-1").unwrap().unwrap();
        assert_eq!(tx.category.as_deref(), Some("Bills"));
    }

    #[test]
    fn import_is_atomic_on_a_bad_line() {
        let conn = fresh_conn();
        let jsonl = "{\"type\":\"tx\",\"ref\":\"tx-1\",\"label\":\"a\"}\n\
                     {\"type\":\"tx\",\"ref\":\"tx-2\",\"label\":\"b\"}\n\
                     not-json\n";
        assert!(import_jsonl(&conn, jsonl).is_err());
        assert!(
            list_labels(&conn).unwrap().is_empty(),
            "nothing may be committed"
        );
    }

    #[test]
    fn import_rejects_oversized_line_and_fields() {
        let conn = fresh_conn();
        let long = "x".repeat(MAX_IMPORT_LINE_BYTES + 1);
        assert!(import_jsonl(&conn, &long).is_err());
        let big_ref = format!(
            "{{\"type\":\"tx\",\"ref\":\"{}\",\"label\":\"a\"}}",
            "r".repeat(MAX_TARGET_BYTES + 1)
        );
        assert!(import_jsonl(&conn, &big_ref).is_err());
        let big_cat = format!(
            "{{\"type\":\"tx\",\"ref\":\"t\",\"label\":\"a\",\"category\":\"{}\"}}",
            "c".repeat(MAX_CATEGORY_BYTES + 1)
        );
        assert!(import_jsonl(&conn, &big_cat).is_err());
        assert!(list_labels(&conn).unwrap().is_empty());
    }

    #[test]
    fn frozen_outpoints_lists_only_unspendable_output_labels() {
        let conn = fresh_conn();
        let op = format!("{}:1", "ab".repeat(32));
        let jsonl = format!(
            "{{\"type\":\"output\",\"ref\":\"{op}\",\"label\":\"f\",\"spendable\":false}}\n\
             {{\"type\":\"output\",\"ref\":\"{}:0\",\"label\":\"ok\",\"spendable\":true}}\n\
             {{\"type\":\"output\",\"ref\":\"garbage\",\"label\":\"g\",\"spendable\":false}}\n",
            "cd".repeat(32)
        );
        import_jsonl(&conn, &jsonl).unwrap();
        let frozen = frozen_outpoints(&conn).unwrap();
        assert_eq!(frozen, vec![op.parse::<bitcoin::OutPoint>().unwrap()]);
    }

    #[test]
    fn import_jsonl_accepts_input_at_line_cap() {
        let conn = fresh_conn();
        // Exactly MAX_IMPORT_LINES non-empty lines must succeed.
        // We use unique `ref` values (tx-0 .. tx-N) to avoid upsert collisions
        // masking the count; all lines hash to distinct (kind, target) keys.
        let big_input = (0..MAX_IMPORT_LINES).fold(String::new(), |mut acc, i| {
            use std::fmt::Write as _;
            writeln!(
                acc,
                "{{\"type\":\"tx\",\"ref\":\"tx-{i}\",\"label\":\"x\"}}"
            )
            .unwrap();
            acc
        });
        let result = import_jsonl(&conn, &big_input);
        assert!(
            result.is_ok(),
            "import must succeed at exactly {MAX_IMPORT_LINES} lines; got {result:?}"
        );
        assert_eq!(result.unwrap(), MAX_IMPORT_LINES);
    }
}

// -----------------------------------------------------------------------
// Property tests for the export → import round trip. The user-facing
// promise of the BIP-329 round-trip in Settings is that "re-importing
// the same file is idempotent". The `LabelsSection` UI advertises it
// in its own copy ("duplicates are upserted, not appended"), so a
// regression in the parser/serialiser would cause data loss after a
// genuine import-then-export sequence. These properties pin it down.
// -----------------------------------------------------------------------
#[cfg(test)]
mod proptests {
    use super::*;
    use proptest::prelude::*;

    fn fresh_conn() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(CREATE_LABELS_TABLE_SQL).unwrap();
        conn
    }

    /// Strategy: a single Label with all four kind-x-spendable
    /// combinations. We avoid newlines inside text/target since JSONL
    /// is line-delimited (a label with a `\n` in it would be a corner
    /// case the wire format does not support; we don't accept those
    /// from the UI either).
    fn label_strategy() -> impl Strategy<Value = Label> {
        let kind_strategy = prop_oneof![
            Just(LabelKind::Tx),
            Just(LabelKind::Addr),
            Just(LabelKind::Output),
            Just(LabelKind::Input),
            Just(LabelKind::Xpub),
        ];
        // Keep target / text printable + non-empty so they survive
        // the text TEXT NOT NULL constraint and JSON encoding.
        let text_strategy = "[\\PC&&[^\\n\\r\\t]]{1,64}".prop_map(|s| s.to_string());
        let target_strategy = "[a-zA-Z0-9]{1,64}".prop_map(|s| s.to_string());
        (kind_strategy, target_strategy, text_strategy).prop_map(|(kind, target, text)| Label {
            kind,
            target,
            text,
            // spendable is BIP-329-defined for Output only; we
            // randomise on that kind only to match the schema.
            spendable: if matches!(kind, LabelKind::Output) {
                Some(true) // simplify: avoid permuting both branches; the
            // serde defaults are covered by the unit tests
            } else {
                None
            },
            category: None,
        })
    }

    proptest! {
        #![proptest_config(ProptestConfig {
            cases: 64,
            ..ProptestConfig::default()
        })]

        /// Round-trip: insert N random labels → export → clear table →
        /// import → list. Must equal the original set (modulo
        /// `(kind, target)` deduplication — the strategy may emit the
        /// same primary key twice; we deduplicate before comparing).
        #[test]
        fn export_then_import_roundtrips(
            corpus in proptest::collection::vec(label_strategy(), 1..16),
        ) {
            let conn = fresh_conn();
            // Insert with (kind, target) as the natural key — last
            // write wins, matching upsert semantics.
            // (kind, target) primary key is matched by the
            // upsert path; deduplicate the proptest corpus the same
            // way before comparing.
            let mut by_pk: std::collections::BTreeMap<(&'static str, String), Label> =
                std::collections::BTreeMap::new();
            for label in &corpus {
                upsert_label(&conn, label).unwrap();
                by_pk.insert((label.kind.as_db_str(), label.target.clone()), label.clone());
            }
            let expected: Vec<Label> = by_pk.values().cloned().collect();

            let exported = export_jsonl(&conn).unwrap();
            // Wipe the table and re-import.
            conn.execute("DELETE FROM bhodl_labels", []).unwrap();
            prop_assert_eq!(list_labels(&conn).unwrap().len(), 0);

            let imported = import_jsonl(&conn, &exported).unwrap();
            prop_assert_eq!(imported, expected.len());

            let mut got = list_labels(&conn).unwrap();
            got.sort_by(|a, b| {
                (a.kind.as_db_str(), &a.target).cmp(&(b.kind.as_db_str(), &b.target))
            });
            let mut want = expected.clone();
            want.sort_by(|a, b| {
                (a.kind.as_db_str(), &a.target).cmp(&(b.kind.as_db_str(), &b.target))
            });
            prop_assert_eq!(got, want);
        }

        /// Idempotent re-import: importing the SAME exported document
        /// twice MUST yield the same final state (count + content).
        /// This is the user-facing promise from the Settings copy.
        #[test]
        fn double_import_is_idempotent(
            corpus in proptest::collection::vec(label_strategy(), 1..8),
        ) {
            let conn = fresh_conn();
            for label in &corpus {
                upsert_label(&conn, label).unwrap();
            }
            let exported = export_jsonl(&conn).unwrap();
            let after_first = list_labels(&conn).unwrap();

            // Second import should produce the same row set as before
            // — every record upserts onto its existing primary key.
            let _ = import_jsonl(&conn, &exported).unwrap();
            let after_second = list_labels(&conn).unwrap();
            prop_assert_eq!(after_first.len(), after_second.len());
            // Order across rusqlite list_labels is insertion order;
            // we sort before comparing to avoid spurious diffs.
            let mut a = after_first;
            let mut b = after_second;
            a.sort_by(|x, y| (x.kind.as_db_str(), &x.target).cmp(&(y.kind.as_db_str(), &y.target)));
            b.sort_by(|x, y| (x.kind.as_db_str(), &x.target).cmp(&(y.kind.as_db_str(), &y.target)));
            prop_assert_eq!(a, b);
        }
    }
}
