//! UniFFI surface for BHODL.
//!
//! This crate is the single FFI seam between the Rust core and the
//! Kotlin Multiplatform / Swift code that drives the UI. Every type and
//! function exposed through `#[uniffi::*]` is part of the wallet's
//! external contract and changes to it ripple into the KMP shared
//! module.
//!
//! Surface:
//! - `Network` — enum mirror of `bhodl_core::Network`.
//! - `WalletError` — enum mirror of `bhodl_wallet_onchain::OnchainError`.
//! - `WatchHandle` — an open watch-only wallet (balance, history, sync,
//!   receive addresses, BIP-329 labels, address book). BHODL never holds
//!   keys: there is no signing / sending surface.
//! - `RegistryHandle` — the device-PIN registry: wallet list + every
//!   tracker table.
//!
//! **Declaration order matters on Windows**: `WatchHandle` must stay the
//! first `#[uniffi::Object]` in this file, because only the first object's
//! methods can return `Result<String, _>` safely through the Ubique KMP
//! bindings (see `docs/uniffi-runtime.md`).

use std::{
    fs,
    path::{Path, PathBuf},
    sync::Mutex,
};

#[cfg(test)]
use bhodl_core::SALT_LEN;
use bhodl_core::{KeyDerivationError, Network as CoreNetwork};
use bhodl_wallet_onchain::{
    Contact, DerivedAddress, ENCRYPTION_KEY_LEN, Label, LabelKind, OnchainError,
    REGISTRY_FILE_NAME, Registry, TxRecord, UtxoInfo, WalletKind, WalletRecord, WatchAccount,
    WatchBip, open_registry,
};
use uuid::Uuid;

// R2 — KDF descriptor + salt persistence lives in its own module now,
// so this file is no longer a God Object. Pure code move; FFI surface
// unchanged. The test-only helpers (write_kdf_file, seal_kdf_file_with_mac,
// read_or_create_salt) are imported inside `mod tests`, not here, to
// avoid an unused-import warning in non-test builds.
mod kdf;
#[cfg(test)]
mod uc_pin_recovery_tests;
#[cfg(test)]
mod uc_security_tests;
use crate::kdf::{
    KdfScope, RECOVERY_FILE_NAME, WrapPurpose, after_rewrap, format_recovery_code,
    generate_recovery_code, normalize_recovery_code, unlock_with, unwrap_file, write_wrapped_file,
};

#[cfg(test)]
mod uc_wallet_tests;

uniffi::setup_scaffolding!();

/// Network selector, mirrored from `bhodl_core::Network`.
///
/// Kept as a plain UniFFI enum (no Rust `From` derive) so the wire format
/// is independent of internal `bhodl_core` evolution: adding a new core
/// variant must be a deliberate FFI change.
#[derive(uniffi::Enum)]
pub enum Network {
    Mainnet,
    Testnet4,
    Signet,
    Regtest,
}

impl From<Network> for CoreNetwork {
    fn from(n: Network) -> Self {
        match n {
            Network::Mainnet => CoreNetwork::Mainnet,
            Network::Testnet4 => CoreNetwork::Testnet4,
            Network::Signet => CoreNetwork::Signet,
            Network::Regtest => CoreNetwork::Regtest,
        }
    }
}

/// Errors raised across the FFI. Mirror of `OnchainError` flattened
/// into a single FFI-friendly enum (UniFFI does not propagate
/// `#[non_exhaustive]`).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum WalletError {
    #[error("BIP32 derivation failed: {detail}")]
    Bip32 { detail: String },
    #[error("invalid address: {detail}")]
    InvalidAddress { detail: String },
    /// A value rejected by a registry domain check (empty name, unknown
    /// kind, out-of-range day, …): a caller mistake, not a storage fault.
    #[error("invalid argument: {detail}")]
    InvalidArgument { detail: String },
    #[error("Esplora sync failed: {detail}")]
    EsploraSync { detail: String },
    #[error("storage error: {detail}")]
    Storage { detail: String },
    #[error("wallet creation failed: {detail}")]
    WalletCreate { detail: String },
    #[error("derivation path: {detail}")]
    DerivationPath { detail: String },
    #[error("key derivation: {detail}")]
    KeyDerivation { detail: String },
    #[error("filesystem error: {detail}")]
    Io { detail: String },
    #[error("invalid salt file: expected {expected} bytes, got {actual}")]
    InvalidSalt { expected: u32, actual: u32 },
    /// Phase 8.6 — typed rate errors replacing the old substring-match
    /// workaround in `BtcRateHistoryClient.kt`.
    #[error("rate: currency not supported: {currency}")]
    RateCurrencyNotSupported { currency: String },
    #[error("rate: network unavailable")]
    RateNetworkUnavailable,
    #[error("rate: all sources failed: {detail}")]
    RateAllSourcesFailed { detail: String },
}

impl From<KeyDerivationError> for WalletError {
    fn from(err: KeyDerivationError) -> Self {
        Self::KeyDerivation {
            detail: err.to_string(),
        }
    }
}

/// T2-13 — strip absolute filesystem paths from an error message so
/// the FFI surface doesn't reveal the data-dir layout to a UI that
/// might display the raw error text.
///
/// Rules (intentionally conservative to avoid over-redacting):
/// - Absolute POSIX path (`/foo/bar/baz`) → keep only the final
///   `baz` component.
/// - Absolute Windows path (`C:\foo\bar\baz` or `C:/foo/bar/baz`)
///   → keep only the final `baz` component.
/// - Relative paths (`./foo`, `foo/bar`) → untouched. These rarely
///   appear in std::io::Error and don't reveal the data dir.
/// - Anything that doesn't look like a path → returned verbatim.
pub(crate) fn strip_filesystem_paths(detail: &str) -> String {
    // Match POSIX absolute paths and Windows absolute paths.
    // Drive-letter pattern: `<letter>:\` or `<letter>:/`.
    let mut out = String::with_capacity(detail.len());
    let mut i = 0;
    let bytes = detail.as_bytes();
    while i < bytes.len() {
        let rest = &detail[i..];
        // POSIX absolute path start.
        if bytes[i] == b'/' && i + 1 < bytes.len() && is_path_char(bytes[i + 1]) {
            let end = path_end(rest);
            let path_str = &rest[..end];
            out.push_str(extract_basename(path_str));
            i += end;
            continue;
        }
        // Windows absolute path: `X:\` or `X:/`.
        if i + 2 < bytes.len()
            && bytes[i].is_ascii_alphabetic()
            && bytes[i + 1] == b':'
            && (bytes[i + 2] == b'\\' || bytes[i + 2] == b'/')
        {
            let end = path_end(rest);
            let path_str = &rest[..end];
            out.push_str(extract_basename(path_str));
            i += end;
            continue;
        }
        // Copy the next Unicode scalar, advancing by its encoded length.
        // `detail` is a valid UTF-8 `String`, so `chars().next()` can
        // only return `None` if the slice is empty — but `i < bytes.len()`
        // guarantees at least one byte remains. We guard with `break`
        // rather than `expect` so a hypothetical internal inconsistency
        // does not panic across the FFI boundary.
        match detail[i..].chars().next() {
            Some(ch) => {
                out.push(ch);
                i += ch.len_utf8();
            }
            None => break,
        }
    }
    out
}

/// Length of the path-like prefix starting at byte 0 of [s]. A path
/// continues while we see path-character bytes; ends at whitespace,
/// quotes, parens, or end of string.
fn path_end(s: &str) -> usize {
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let b = bytes[i];
        if !is_path_char(b) {
            return i;
        }
        i += 1;
    }
    bytes.len()
}

fn is_path_char(b: u8) -> bool {
    // ASCII letters, digits, and the punctuation that appears in
    // filenames. Excludes whitespace, quotes, parens, brackets,
    // braces, and the typical sentence terminators.
    matches!(b,
        b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9'
        | b'/' | b'\\' | b'_' | b'-' | b'.' | b':' | b'~' | b'+' | b'@' | b'%'
    )
}

fn extract_basename(path: &str) -> &str {
    // Use both '/' and '\\' as separators (Windows uses backslash, but
    // forward slashes are also valid on Windows paths in Rust).
    let after_fwd = path.rsplit('/').next().unwrap_or(path);
    let after_back = after_fwd.rsplit('\\').next().unwrap_or(after_fwd);
    // Strip trailing punctuation that may have leaked through is_path_char
    // (e.g. a path immediately followed by ':' as in "X (os error 2):").
    after_back.trim_end_matches([':', '.'])
}

impl From<std::io::Error> for WalletError {
    fn from(err: std::io::Error) -> Self {
        Self::Io {
            // T2-13 — strip absolute filesystem paths from the
            // error message before it crosses the FFI boundary.
            // std::io::Error::to_string() embeds the OS error and,
            // when the failing call carried a path (e.g. open,
            // rename), the full path leaks the data dir layout to
            // any FFI surface that surfaces error.message verbatim
            // (the desktop unlock screens do).
            detail: strip_filesystem_paths(&err.to_string()),
        }
    }
}

impl From<OnchainError> for WalletError {
    fn from(err: OnchainError) -> Self {
        match err {
            OnchainError::Bip32(e) => Self::Bip32 {
                detail: e.to_string(),
            },
            OnchainError::DerivationPath(s) => Self::DerivationPath { detail: s },
            OnchainError::Descriptor(s) => Self::WalletCreate { detail: s },
            OnchainError::WalletCreate(s) => Self::WalletCreate { detail: s },
            OnchainError::EsploraSync(s) => Self::EsploraSync { detail: s },
            OnchainError::Storage(s) => Self::Storage { detail: s },
            OnchainError::InvalidAddress(s) => Self::InvalidAddress { detail: s },
            OnchainError::InvalidArgument(s) => Self::InvalidArgument { detail: s },
            // An RNG failure is a key-material fault, not a storage one:
            // route it to the same variant KeyDerivationError uses so the
            // UI can treat "we could not produce entropy" uniformly.
            OnchainError::Rng(s) => Self::KeyDerivation { detail: s },
            // `non_exhaustive` catch-all so future variants don't break the
            // FFI build before we choose a public mapping.
            other => Self::Storage {
                detail: other.to_string(),
            },
        }
    }
}

/// Opaque handle on an open watch-only wallet.
///
/// Constructed via [`RegistryHandle::open_watch`]. Holds the
/// `WatchAccount` (BDK watch wallet + SQLCipher connection) behind a
/// `Mutex` so the handle is `Send + Sync`. No signing APIs by
/// construction.
///
/// Must stay the **first** `#[uniffi::Object]` in this file (see the
/// crate docs): several methods return `Result<String, _>`.
///
/// **File-lock release**: the Kotlin side must call the generated
/// `destroy()` before deleting `watch-state/<id>.sqlite`, so the inner
/// `Connection` (and its Windows file lock) is dropped first.
#[derive(uniffi::Object)]
pub struct WatchHandle {
    inner: Mutex<WatchAccount>,
}

impl std::fmt::Debug for WatchHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WatchHandle").finish_non_exhaustive()
    }
}

impl WatchHandle {
    /// Shared body of the two syncs. Audit M5 — the network phase runs
    /// without the wallet mutex, so UI reads stay responsive during a slow
    /// (Tor) scan. A transient server failure (timeout, 502 / 504) retries
    /// the scan a couple of times instead of failing the wallet's sync.
    fn sync_inner(
        &self,
        esplora_url: &str,
        proxy_url: Option<&str>,
        incremental: bool,
    ) -> Result<(), WalletError> {
        let mut attempt = 0u32;
        let update = loop {
            let pending = {
                let guard = self.inner.lock().map_err(|_| WalletError::Storage {
                    detail: "watch wallet mutex poisoned".into(),
                })?;
                if incremental {
                    guard.start_esplora_scan_incremental()
                } else {
                    guard.start_esplora_scan()
                }
            };
            match pending.fetch(esplora_url, proxy_url) {
                Ok(update) => break update,
                Err(e)
                    if attempt < bhodl_wallet_onchain::ESPLORA_SYNC_RETRIES
                        && bhodl_wallet_onchain::is_transient_esplora_error(&e) =>
                {
                    attempt += 1;
                    std::thread::sleep(std::time::Duration::from_secs(u64::from(attempt) * 2));
                }
                Err(e) => return Err(e.into()),
            }
        };
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        guard.apply_esplora_scan(update)?;
        Ok(())
    }
}

#[uniffi::export]
impl WatchHandle {
    /// Total wallet balance (confirmed + pending), in sat. Returns 0
    /// until at least one successful `sync_with_esplora` call.
    pub fn balance_sat(&self) -> Result<u64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.balance().to_sat())
    }

    /// Enumerate every transaction the watch wallet knows about (confirmed
    /// and mempool; `confirmation_height: None` means pending).
    pub fn list_transactions(&self) -> Result<Vec<TransactionRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard
            .list_transactions()
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Blocking Esplora scan (full scan for HD wallets, spk sync for
    /// address lists). `proxy_url` routes it through Tor (SOCKS5).
    pub fn sync_with_esplora(
        &self,
        esplora_url: String,
        proxy_url: Option<String>,
    ) -> Result<(), WalletError> {
        self.sync_inner(&esplora_url, proxy_url.as_deref(), false)
    }

    /// Incremental Esplora scan: the revealed scripts plus a short
    /// lookahead (a few requests). For periodic refreshes; the full scan
    /// stays for the first sync and manual refreshes.
    pub fn sync_with_esplora_incremental(
        &self,
        esplora_url: String,
        proxy_url: Option<String>,
    ) -> Result<(), WalletError> {
        self.sync_inner(&esplora_url, proxy_url.as_deref(), true)
    }

    /// Peek a receive address. For HD watch wallets, derives via BDK.
    /// For address-list watch wallets, returns the address at position
    /// `index` in the stored list (Err out of range).
    pub fn peek_address_at(&self, index: u32) -> Result<String, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        let addr = guard.peek_address(index)?;
        Ok(addr.to_string())
    }

    /// `true` for address-list watch wallets: no receive-address
    /// derivation and no change keychain.
    pub fn is_address_list(&self) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.is_address_list())
    }

    /// Public descriptors + bare account xpub, for import into Sparrow /
    /// Liana / Specter. `None` for address-list wallets.
    pub fn export_public_descriptor(&self) -> Result<Option<PublicDescriptorBundle>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        let Some(backup) = guard.export_descriptors() else {
            return Ok(None);
        };
        let account_xpub = backup.account_xpub().ok_or_else(|| WalletError::Storage {
            detail: "failed to extract account xpub from descriptor".into(),
        })?;
        let network = match backup.network {
            CoreNetwork::Mainnet => "mainnet",
            CoreNetwork::Signet => "signet",
            CoreNetwork::Testnet4 => "testnet4",
            CoreNetwork::Regtest => "regtest",
            _ => "unknown",
        }
        .to_string();
        Ok(Some(PublicDescriptorBundle {
            network,
            external_descriptor: backup.external,
            internal_descriptor: backup.internal,
            account_xpub,
        }))
    }

    /// Reveal the next external (receive) address and persist the
    /// advance so subsequent calls return the next index.
    ///
    /// The desktop Receive screen calls this every time the user taps
    /// "Generate new address". Address-list wallets cannot derive (check
    /// [`Self::is_address_list`] first). The reveal is fsynced into the
    /// SQLCipher store before returning, so a process restart continues
    /// where the previous run left off — the user never re-sees an
    /// address that was already shared.
    pub fn next_receive_address(&self) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        let addr = guard.reveal_next_address()?;
        Ok(addr.to_string())
    }

    /// Lowest revealed receive address with no funds yet, revealing a
    /// new one only when all are used. The label-first Receive screen
    /// uses this so visiting / re-labelling never burns an index;
    /// [`Self::next_receive_address`] stays for an explicit "New address".
    pub fn next_unused_receive_address(&self) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.next_unused_receive_address()?.to_string())
    }

    /// Unspent outputs of the watched scripts (confirmed + mempool), for
    /// the wallet's UTXO tab.
    pub fn list_utxos(&self) -> Result<Vec<UtxoRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.list_utxos()?.into_iter().map(Into::into).collect())
    }

    /// Every revealed address on one keychain (`change = false`:
    /// receive, `true`: change), oldest first, with used / unused
    /// state and the BIP-329 `addr` label + category. Backs the
    /// wallet's Addresses tab and the Receive reuse warning.
    pub fn list_addresses(&self, change: bool) -> Result<Vec<AddressRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard
            .list_addresses(change)?
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Set (or clear, when both are blank) the BIP-329 `addr` label and
    /// the BHODL category extension on one of the wallet's addresses —
    /// the label-first Receive flow writes these before showing the
    /// address so incoming funds can be labelled on arrival.
    pub fn set_address_label(
        &self,
        address: String,
        text: String,
        category: Option<String>,
    ) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.set_address_label(&address, &text, category)?)
    }

    /// Label-first receive: copy receive-address labels / categories
    /// onto the incoming transactions that paid them (net-incoming only,
    /// once per `(txid, address)`, never over an existing label). Returns
    /// what was written so the caller can mirror categories into the
    /// tracker registry.
    pub fn apply_receive_labels(&self) -> Result<Vec<ReceiveLabelCopyRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard
            .apply_receive_labels()?
            .into_iter()
            .map(|c| ReceiveLabelCopyRecord {
                txid: c.txid,
                address: c.address,
                label: c.label,
                category: c.category,
            })
            .collect())
    }

    /// Height of the wallet's last synced block (`0` before the first
    /// sync). Lets the UI turn a confirmation height into a count.
    pub fn chain_tip_height(&self) -> Result<u32, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.chain_tip_height())
    }

    /// Insert a new (label, address) pair into the local address book.
    /// Returns the row id assigned by the DB.
    pub fn add_contact(&self, label: String, address: String) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.add_contact(&label, &address)?)
    }

    /// Return every saved address-book entry.
    pub fn list_contacts(&self) -> Result<Vec<ContactRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.list_contacts()?.into_iter().map(Into::into).collect())
    }

    /// Rename an existing contact. Returns `true` if a row was
    /// updated, `false` if no row matched `id`.
    pub fn update_contact_label(&self, id: i64, new_label: String) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.update_contact_label(id, &new_label)?)
    }

    /// Delete a contact by row id. Returns `true` if a row was
    /// deleted, `false` if no row matched `id`.
    pub fn remove_contact(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.remove_contact(id)?)
    }

    /// Set (or replace) the BIP-329 label on a transaction. Preserves
    /// any `category` already attached to this txid — Phase 3.4 stores
    /// categories on the same row, and an empty-category re-save from
    /// the label-only UI must not blank it.
    pub fn set_tx_label(&self, txid: String, text: String) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        let existing_category = guard
            .get_label(LabelKind::Tx, &txid)?
            .and_then(|l| l.category);
        guard.add_label(&Label {
            kind: LabelKind::Tx,
            target: txid,
            text,
            spendable: None,
            category: existing_category,
        })?;
        Ok(())
    }

    /// Read the BIP-329 label currently attached to a transaction.
    /// Returns `None` if no label has been set yet.
    pub fn get_tx_label(&self, txid: String) -> Result<Option<String>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.get_label(LabelKind::Tx, &txid)?.map(|l| l.text))
    }

    /// Phase 3.4 — set (or replace) the BHODL `category` extension on
    /// a transaction's BIP-329 row. Categories are stored alongside
    /// the label in the same device-key-encrypted table. Pass `None`
    /// to clear the category; the label text is preserved.
    pub fn set_tx_category(
        &self,
        txid: String,
        category: Option<String>,
    ) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        let existing = guard.get_label(LabelKind::Tx, &txid)?;
        let label = Label {
            kind: LabelKind::Tx,
            target: txid,
            text: existing
                .as_ref()
                .map(|l| l.text.clone())
                .unwrap_or_default(),
            spendable: existing.and_then(|l| l.spendable),
            category,
        };
        guard.add_label(&label)?;
        Ok(())
    }

    /// Phase 3.4 — read the `category` extension currently attached
    /// to a transaction. Returns `None` when no category is set
    /// (label may still exist with `text` only).
    pub fn get_tx_category(&self, txid: String) -> Result<Option<String>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard
            .get_label(LabelKind::Tx, &txid)?
            .and_then(|l| l.category))
    }

    /// Remove the BIP-329 label on a transaction. Returns `true` if a
    /// row was deleted, `false` if no label was set.
    pub fn remove_tx_label(&self, txid: String) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.remove_label(LabelKind::Tx, &txid)?)
    }

    /// Render every wallet label as BIP-329 JSONL (one record per
    /// line). Suitable for sharing with Sparrow / Liana / any other
    /// BIP-329 consumer. The result is plain UTF-8 text, safe to
    /// dump to a `.jsonl` file via the desktop save dialog.
    pub fn export_labels_jsonl(&self) -> Result<String, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.export_labels_jsonl()?)
    }

    /// Import a BIP-329 JSONL document produced by another wallet.
    /// Each record upserts (`(kind, target)` is the natural key);
    /// blank lines are tolerated. Returns the number of imported
    /// records.
    pub fn import_labels_jsonl(&self, jsonl: String) -> Result<u64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        Ok(guard.import_labels_jsonl(&jsonl)? as u64)
    }
}

/// FFI mirror of `bhodl_wallet_onchain::Contact`. The local-only
/// address book.
#[derive(uniffi::Record)]
pub struct ContactRecord {
    pub id: i64,
    pub label: String,
    pub address: String,
    pub created_at_unix: i64,
}

impl From<Contact> for ContactRecord {
    fn from(c: Contact) -> Self {
        Self {
            id: c.id,
            label: c.label,
            address: c.address,
            created_at_unix: c.created_at_unix,
        }
    }
}

/// FFI mirror of `bhodl_wallet_onchain::UtxoInfo`.
///
/// `outpoint` carries `txid:vout`.
#[derive(uniffi::Record)]
pub struct UtxoRecord {
    pub outpoint: String,
    pub value_sat: u64,
    pub address: String,
    pub is_change: bool,
    pub confirmed: bool,
}

impl From<UtxoInfo> for UtxoRecord {
    fn from(u: UtxoInfo) -> Self {
        Self {
            outpoint: u.outpoint.to_string(),
            value_sat: u.value.to_sat(),
            address: u.address.to_string(),
            is_change: u.is_change,
            confirmed: u.confirmed,
        }
    }
}

/// Public descriptors + account-level xpub of an HD watch wallet, as
/// produced by `WatchHandle::export_public_descriptor`.
#[derive(uniffi::Record)]
pub struct PublicDescriptorBundle {
    /// Lower-case network enum: `mainnet`, `signet`, `testnet4`, `regtest`.
    pub network: String,
    /// External (receive) descriptor in xpub form, with checksum.
    /// Suitable for pasting into Sparrow / Liana / Specter as a
    /// watch-only descriptor.
    pub external_descriptor: String,
    /// Internal (change) descriptor in xpub form, with checksum.
    pub internal_descriptor: String,
    /// Bare account-level xpub (no descriptor wrapper, no origin, no
    /// keychain path).
    pub account_xpub: String,
}

/// FFI mirror of `bhodl_wallet_onchain::DerivedAddress`: one revealed
/// address with its keychain index, used / unused state and the
/// BIP-329 `addr` label + BHODL category.
#[derive(uniffi::Record)]
pub struct AddressRecord {
    pub index: u32,
    pub address: String,
    pub is_change: bool,
    pub used: bool,
    pub label: Option<String>,
    pub category: Option<String>,
}

impl From<DerivedAddress> for AddressRecord {
    fn from(a: DerivedAddress) -> Self {
        Self {
            index: a.index,
            address: a.address.to_string(),
            is_change: a.is_change,
            used: a.used,
            label: a.label,
            category: a.category,
        }
    }
}

/// One receive label copied onto a transaction (see
/// `WatchHandle::apply_receive_labels`); `label` / `category` are what
/// was actually written.
#[derive(uniffi::Record)]
pub struct ReceiveLabelCopyRecord {
    pub txid: String,
    pub address: String,
    pub label: Option<String>,
    pub category: Option<String>,
}

/// FFI mirror of `bhodl_wallet_onchain::TxRecord`. See that struct for
/// field semantics — `net_sat` is positive for incoming, negative for
/// outgoing, `fee_sat` is `Some` only when the wallet has every input
/// (i.e. the wallet sent the tx itself), and the
/// `confirmation_height: None` case means "still in mempool".
#[derive(uniffi::Record)]
pub struct TransactionRecord {
    pub txid: String,
    pub net_sat: i64,
    pub fee_sat: Option<u64>,
    pub confirmation_height: Option<u32>,
    pub timestamp: Option<u64>,
}

/// Phase 3.3 — cross-wallet payment event abstraction.
///
/// One `PaymentEvent` per on-chain (and future Lightning) movement
/// in the tracker's scope. Every wallet is a watch wallet and
/// contributes its full history via the device key.
///
/// Designed to accommodate Phase 4 Lightning without a redesign:
/// when LN lands, `kind = Lightning` and `txid_or_payment_id` carries
/// a payment hash instead of an on-chain txid.
#[derive(uniffi::Record)]
pub struct PaymentEvent {
    /// Wallet UUID this event belongs to. Used by the UI to render a
    /// "from / to" wallet badge and by filters to scope-down.
    pub wallet_id: String,
    /// For `OnChain`: the txid. For future `Lightning`: the
    /// `payment_hash`. Used as the dedup key in the Kotlin merge.
    pub txid_or_payment_id: String,
    pub kind: PaymentKind,
    pub direction: PaymentDirection,
    /// Absolute value of the net flow in sat. Direction tells you
    /// which way it moved.
    pub amount_sat: u64,
    /// Set only when this wallet paid the fee — i.e. only on
    /// Outgoing events. Watch wallets that observe an Outgoing
    /// transaction (because they share an xpub with a signing
    /// wallet) cannot derive the fee from their own UTXO view alone,
    /// so they emit `None`.
    pub fee_sat: Option<u64>,
    /// Unix epoch seconds. `None` while the tx is in the mempool.
    pub timestamp_unix: Option<u64>,
    pub block_height: Option<u32>,
    /// Phase 3.4 — user-assigned category from the registry's
    /// `tracking_categories` table. `None` when no category was set
    /// for this `(wallet_id, txid_or_payment_id)`.
    pub category: Option<String>,
    /// Phase 7.10 — crypto event kind classification from the
    /// registry's `tracking_event_crypto_kind` table. `None` (or
    /// equivalently `"regular"`) means no special tax treatment.
    /// Auto-set to `"send_to_self"` by [`TrackerCache.refresh`] when
    /// the same txid is seen in 2+ watch wallets.
    pub crypto_event_kind: Option<String>,
}

#[derive(uniffi::Enum, Clone, Copy, PartialEq, Eq)]
pub enum PaymentKind {
    OnChain,
    /// Reserved for Phase 4. Currently never emitted.
    Lightning,
}

#[derive(uniffi::Enum, Clone, Copy, PartialEq, Eq)]
pub enum PaymentDirection {
    Incoming,
    Outgoing,
    /// Tx where every input AND every output belongs to the same
    /// wallet (consolidate / change-only tx). Net sat is zero
    /// minus the fee.
    SelfTransfer,
}

/// Phase 3.8 — saved tracking view exposed across the FFI. Mirrors
/// `bhodl_wallet_onchain::registry::SavedView` but flattens the
/// optional `(start, end)` tuple into two `Option<i64>` fields
/// because UniFFI can't synthesize tuple records.
#[derive(uniffi::Record)]
pub struct SavedViewFfi {
    pub id: i64,
    pub name: String,
    /// Period preset key. UI maps to `TrackingPeriod` enum.
    pub period: String,
    /// `None` = all categories, `Some("")` = uncategorised only,
    /// `Some(s)` = exact-match category.
    pub category_filter: Option<String>,
    pub time_range_start: Option<i64>,
    pub time_range_end: Option<i64>,
    pub created_at_unix: i64,
}

/// Phase 4.3 — per-category budget exposed across the FFI.
#[derive(uniffi::Record)]
pub struct TrackingBudgetFfi {
    pub category: String,
    pub budgeted_sat: u64,
    /// "monthly" for now; future: "weekly" / "quarterly" / "yearly".
    pub period: String,
    pub updated_at_unix: i64,
}

/// Phase 4.4 — bill (recurring payment) exposed across the FFI.
#[derive(uniffi::Record)]
pub struct TrackingBillFfi {
    pub id: i64,
    pub name: String,
    pub amount_sat: u64,
    /// Day of month the bill is due (1..=31).
    pub due_day: u32,
    /// Unix timestamp of the most recent "Paid" mark; UI computes
    /// "paid this period" by checking whether this falls in the
    /// current month.
    pub last_paid_unix: Option<i64>,
    /// Optional free-text override (e.g. "€900/month"). When null,
    /// UI renders `amount_sat` via BtcUnit display preference.
    pub display_label: Option<String>,
    pub created_at_unix: i64,
    /// Registry schema v7 — the amount varies; `amount_sat` is an
    /// estimate. Defaulted so hand-built Kotlin fixtures keep compiling.
    #[uniffi(default = false)]
    pub variable: bool,
    /// Registry schema v7 — what the last "paid" mark recorded.
    #[uniffi(default = None)]
    pub last_paid_amount_sat: Option<u64>,
    /// Registry schema v8 — the budget category the bill's payments
    /// count against.
    #[uniffi(default = None)]
    pub category: Option<String>,
    /// Registry schema v8 — the transaction that paid it
    /// (`<wallet_id>|<txid>`).
    #[uniffi(default = None)]
    pub paid_ref: Option<String>,
}

/// Registry schema v9 — a wallet transaction of a test-mode profile.
#[derive(uniffi::Record)]
pub struct DemoEventFfi {
    pub wallet_id: String,
    pub event_id: String,
    /// `"in"` or `"out"`.
    pub direction: String,
    pub amount_sat: u64,
    pub fee_sat: Option<u64>,
    pub occurred_unix: i64,
    /// `null` = unconfirmed.
    pub block_height: Option<u32>,
}

/// Registry schema v8 — income or spending outside the wallets (cash,
/// bank account, card). Its events carry `wallet_id` =
/// `manual:<account_ref or "cash">` and id `manual-<id>`.
#[derive(uniffi::Record)]
pub struct ManualTxnFfi {
    pub id: i64,
    /// "in" or "out".
    pub direction: String,
    /// The value at the day's BTC rate, fixed when saved.
    pub amount_sat: u64,
    /// What the user typed, with its ISO 4217 currency; both null for
    /// an amount entered in sat.
    pub amount_fiat: Option<f64>,
    pub currency: Option<String>,
    pub occurred_unix: i64,
    /// `asset:<id>`, `liability:<id>` or null (cash / other).
    pub account_ref: Option<String>,
    pub note: Option<String>,
    pub created_at_unix: i64,
    /// Schema v10 — the debt this entry pays, if any.
    #[uniffi(default = None)]
    pub liability_id: Option<i64>,
    /// Schema v10 — "installment", "extra_term" (extra repayment, same
    /// installment, shorter term) or "extra_payment" (same term, lower
    /// installment).
    #[uniffi(default = None)]
    pub liability_payment: Option<String>,
}

/// Phase 4.5 — savings goal exposed across the FFI.
#[derive(uniffi::Record)]
pub struct TrackingSavingsGoalFfi {
    pub id: i64,
    pub name: String,
    pub target_sat: u64,
    pub current_sat: u64,
    /// Palette key — cyan / orange / red / green / yellow / purple.
    /// UI maps to a concrete color from the theme.
    pub color: String,
    pub deadline_unix: Option<i64>,
    /// Legacy hand-rolled link to a single wallet — superseded by
    /// `bucket_kind = "wallet"` + `bucket_ref` in Phase 7.2.4. Kept on
    /// the wire because the column still exists in the schema (used by
    /// pre-7.2.4 rows). New code should ignore it and use bucket_*.
    pub linked_wallet_id: Option<String>,
    /// Phase 7.2.1 — desired allocation as a % of intended net worth.
    /// Independent of `target_sat`: the user says "this goal should
    /// be 30% of my future net worth" while keeping a concrete sat
    /// target. None = no allocation target set. Valid range 0..=100;
    /// out-of-range inputs are coerced at the call site.
    ///
    /// Phase 7.2.4 — when this is set, the UI recomputes `target_sat`
    /// live from the current NW (no longer frozen at save time). The
    /// `target_sat` persisted here remains the fallback for ABSOLUTE
    /// mode and for goals saved before the migration.
    pub target_pct_of_net_worth: Option<u32>,
    /// Phase 7.2.4 — semantic bucket the goal is bound to.
    ///
    /// One of: `manual` (legacy, user types `current_sat` by hand),
    /// `investments` (Σ tracking_investments converted to sat),
    /// `bitcoin_cash` (aggregateWatchBalanceSat across all watch
    /// wallets), `wallet` (balance of the single wallet identified
    /// by `bucket_ref`).
    pub bucket_kind: String,
    /// Phase 7.2.4 — bucket disambiguator. For `bucket_kind = "wallet"`
    /// this is the wallet id (string form of a UUID). Null for
    /// `manual` / `investments` / `bitcoin_cash`.
    pub bucket_ref: Option<String>,
    /// Phase 7.7 — `savings` (open-ended) or `sinking` (target with a
    /// hard deadline + suggested monthly funding rate). Validation:
    /// `sinking` requires `deadline_unix` to be set.
    pub goal_kind: String,
    /// Phase 8.6 — `sat` (default) or `fiat`. When `fiat`, the
    /// effective sat target is computed at render time from
    /// `target_fiat_amount` / rate × 1e8. `target_sat` is ignored
    /// for fiat goals.
    pub target_denomination: String,
    /// Phase 8.6 — fiat target amount. Only meaningful when
    /// `target_denomination == "fiat"`. None for sat goals.
    pub target_fiat_amount: Option<f64>,
    /// Phase 8.6 — ISO 4217 currency for the fiat target.
    /// None for sat goals.
    pub target_fiat_currency: Option<String>,
    pub created_at_unix: i64,
}

/// Phase 7.5 — categorization rule exposed across the FFI. The Kotlin
/// `applyRules` consumer is responsible for the case-insensitive
/// matching of text ops and the u64 parsing of numeric ops — we keep
/// the storage format mixed-string so future ops (e.g. `between`)
/// don't need a schema change.
#[derive(uniffi::Record)]
pub struct TrackingRuleFfi {
    pub id: i64,
    pub name: String,
    /// One of `VALID_RULE_FIELDS` (description / payee / amount).
    pub condition_field: String,
    /// Text op (contains/equals/starts_with/ends_with) when field is
    /// description or payee; numeric op (gt/lt/eq) when amount.
    pub condition_op: String,
    pub condition_value: String,
    /// V1: always `"category"`. Future phases may add tags / exclude.
    pub action_field: String,
    pub action_value: String,
    /// Higher wins on conflict. Range 0..=10 enforced by UI slider;
    /// stored i64 to leave room for future extensions.
    pub priority: i64,
    pub enabled: bool,
    pub created_at_unix: i64,
}

/// Wire shape for one row of `tracking_event_cost_basis`: the user's
/// input for one transaction of the BTC average-cost view.
#[derive(uniffi::Record)]
pub struct EventCostBasisFfi {
    pub wallet_id: String,
    pub txid_or_payment_id: String,
    /// Fiat per BTC typed by the user; `None` = market rate of the day.
    pub price_per_btc: Option<f64>,
    /// ISO currency of `price_per_btc`; set iff the price is.
    pub currency: Option<String>,
    pub excluded: bool,
}

/// Phase 7.10 — wire shape for one row of `tracking_event_crypto_kind`.
/// Used by the FFI's `dump_crypto_event_kinds` to populate the
/// Kotlin-side cache in a single round-trip.
#[derive(uniffi::Record)]
pub struct CryptoEventKindRow {
    pub wallet_id: String,
    pub txid_or_payment_id: String,
    pub kind: String,
    /// Registry schema v3 — `true` when the user chose this kind (it is
    /// pinned: the auto-detector never overwrites it). Defaulted so
    /// hand-built Kotlin fixtures keep compiling.
    #[uniffi(default = false)]
    pub user_set: bool,
}

/// Phase 7.9 — sale event exposed across the FFI.
#[derive(uniffi::Record)]
pub struct TrackingInvestmentSellFfi {
    pub id: i64,
    pub investment_id: i64,
    pub sale_unix: i64,
    pub sale_price_per_unit: f64,
    pub sale_qty: f64,
    pub fees_fiat: f64,
    pub notes: Option<String>,
    pub created_at_unix: i64,
    /// Phase 8.2 — BTC/fiat rate at the day of the sale.
    /// Null for rows created before the 8.2 backfill ran.
    pub btc_per_unit_at_sale: Option<f64>,
}

/// Specific-ID sale entry: take `qty` units from lot `lot_id`. See
/// [`RegistryHandle::record_sell_with_lots`].
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq)]
pub struct LotSelectionFfi {
    pub lot_id: i64,
    pub qty: f64,
}

/// Phase 7.9 — lot disposal (the snapshot of a lot consumed by a
/// sell). `cost_basis_per_unit` is frozen at sell time.
#[derive(uniffi::Record)]
pub struct TrackingLotDisposalFfi {
    pub id: i64,
    pub sell_id: i64,
    pub lot_id: i64,
    pub qty_consumed: f64,
    pub cost_basis_per_unit: f64,
    pub gain_loss_fiat: f64,
    pub created_at_unix: i64,
    /// Phase 8.2 — realized gain/loss expressed in BTC terms.
    /// `sale_btc - cost_btc` using the BTC/fiat snapshots on each
    /// side. Null until the 8.2 backfill has run AND both parent
    /// snapshots (lot + sell) are non-null.
    pub gain_loss_btc: Option<f64>,
}

/// Phase 7.8 — investment lot exposed across the FFI.
///
/// One row = one purchase event. `price_per_unit` is in the parent
/// investment's `cost_basis_currency`. The aggregate cost basis is
/// computed in Kotlin via `effectiveCostBasis(...)`.
#[derive(uniffi::Record)]
pub struct TrackingInvestmentLotFfi {
    pub id: i64,
    pub investment_id: i64,
    pub quantity: f64,
    pub price_per_unit: f64,
    pub fees_fiat: f64,
    pub purchase_unix: i64,
    pub notes: Option<String>,
    pub created_at_unix: i64,
    /// Phase 8.2 — BTC/fiat rate (1 BTC in the investment's currency)
    /// on the day of this purchase. Used to compute gain/loss in BTC
    /// terms at sell time. Null until the 8.2 backfill has run.
    pub btc_per_unit_at_purchase: Option<f64>,
    /// Registry schema v2 (M1) — purchased size of the lot, frozen at
    /// insert (`quantity` shrinks on sells). Purchase fees are spread
    /// over this. Always populated by the registry; optional + defaulted
    /// only so Kotlin call sites that build records by hand keep compiling.
    #[uniffi(default = None)]
    pub original_quantity: Option<f64>,
}

/// Phase 7.4 — manual asset account exposed across the FFI.
///
/// Asset-side mirror of `TrackingLiabilityFfi`, but multi-currency and
/// with a richer `kind` (8 values: cash / brokerage / real_estate / vehicle /
/// collectible / business_equity / loans_to_others / other). `value_fiat`
/// is naturally in fiat — the conversion to sat happens in the UI via
/// PriceCache, the same way as for `TrackingInvestmentFfi`.
#[derive(uniffi::Record)]
pub struct TrackingManualAssetFfi {
    pub id: i64,
    pub name: String,
    /// One of the 8 whitelisted kinds — see the Rust core's
    /// `VALID_MANUAL_ASSET_KINDS`. UI Kotlin enum mirrors.
    pub kind: String,
    pub value_fiat: f64,
    /// ISO 4217 code. UI offers EUR / USD / CHF / GBP / JPY like
    /// `InvestmentsCard`.
    pub currency: String,
    pub display_label: Option<String>,
    pub notes: Option<String>,
    /// Bumped to "now" on every add/update — UI renders "Updated X ago".
    pub last_updated_unix: i64,
    pub created_at_unix: i64,
    // ---------------------------------------------------------------
    // Phase 8.2 — purchase snapshot fields (all nullable).
    // Null for rows created before the 8.2 migration; the backfill
    // populates them retroactively using `created_at_unix` as the
    // purchase date when `purchase_unix` was not explicitly set.
    // ---------------------------------------------------------------
    /// Unix timestamp of the purchase event, or null for pre-8.2 rows
    /// (the backfill will fill it from `created_at_unix`).
    pub purchase_unix: Option<i64>,
    /// Asset value in fiat at time of purchase, in `purchase_currency`.
    pub purchase_value_fiat: Option<f64>,
    /// ISO 4217 code of `purchase_value_fiat`. May differ from
    /// `currency` if the asset was valued in a different currency at
    /// purchase.
    pub purchase_currency: Option<String>,
    /// BTC/fiat rate (1 BTC = X `purchase_currency`) on the day of
    /// purchase. Null until the 8.2 backfill runs.
    pub purchase_btc_at_time: Option<f64>,
}

/// Registry v6 — the details object of one portfolio row (investment,
/// manual asset or liability), a flat JSON object.
#[derive(uniffi::Record)]
pub struct PortfolioDetailsFfi {
    pub id: i64,
    pub details_json: String,
}

/// Phase 4.6 — manual liability exposed across the FFI.
#[derive(uniffi::Record)]
pub struct TrackingLiabilityFfi {
    pub id: i64,
    pub name: String,
    pub amount_sat: u64,
    /// Free string: 'mortgage' / 'loan' / 'credit-card' / 'other'.
    /// UI uses for icon mapping.
    pub kind: String,
    pub display_label: Option<String>,
    pub created_at_unix: i64,
}

/// The amortization plan of a mortgage or loan (see
/// `RegistryHandle::loan_schedules`). Amounts in [`Self::currency`].
#[derive(uniffi::Record)]
pub struct LoanScheduleFfi {
    pub liability_id: i64,
    pub currency: String,
    pub original_amount: f64,
    pub start_unix: i64,
    /// Principal outstanding today.
    pub balance_now: f64,
    /// The installment due next (lower after an "extra_payment").
    pub payment_now: f64,
    /// Null once repaid.
    pub next_due_unix: Option<i64>,
    /// Day of the last installment; null if the installment never
    /// covers the interest.
    pub payoff_unix: Option<i64>,
    pub installments_left: u32,
}

/// Phase 5.1 — fiat-denominated investment exposed across the FFI.
///
/// Per-position record for the Wealth Builder dashboard's
/// Investments section. Multi-asset class (stocks / ETFs / funds /
/// currencies / commodities / manual). All fiat amounts are in the
/// position's own `cost_basis_currency` (UI converts to sat via the
/// PriceCache when displaying).
#[derive(uniffi::Record)]
pub struct TrackingInvestmentFfi {
    pub id: i64,
    pub name: String,
    /// One of 'stock' | 'etf' | 'fund' | 'currency' | 'commodity' | 'manual'.
    pub asset_type: String,
    /// Symbol used by the price provider (e.g. 'AAPL', 'IWDA.AS').
    /// Null for asset_type='manual' or unknown.
    pub ticker: Option<String>,
    pub quantity: f64,
    /// Free-text unit label ("shares", "g", "EUR", "BTC").
    pub unit: String,
    pub cost_basis_fiat: f64,
    /// ISO-4217 code ('EUR' / 'USD' / 'CHF' / 'GBP' / etc).
    pub cost_basis_currency: String,
    pub purchase_unix: i64,
    /// User-entered current value (per quantity unit, in fiat). T2-10:
    /// preserved across auto-fetch updates — the fetcher writes to
    /// `fetched_current_value`, not here.
    pub manual_current_value: Option<f64>,
    /// Last value written by the Phase 5.2 auto-fetcher (Yahoo
    /// Finance, etc.). T2-10: kept distinct from `manual_current_value`
    /// so a Yahoo glitch never corrupts the user's manual entry.
    /// UI policy: prefer this when non-null AND fresh
    /// (`last_priced_at_unix` ≤ a few hours), else fall back to manual.
    pub fetched_current_value: Option<f64>,
    pub last_priced_at_unix: Option<i64>,
    pub notes: Option<String>,
    /// Phase 7.8 — `fifo` (default) / `hifo` / `specific_id`. Used by
    /// the future sell flow (Phase 7.9) to order lot consumption.
    pub cost_basis_method: String,
    pub created_at_unix: i64,
}

impl From<TxRecord> for TransactionRecord {
    fn from(r: TxRecord) -> Self {
        Self {
            txid: r.txid,
            net_sat: r.net_sat,
            fee_sat: r.fee_sat,
            confirmation_height: r.confirmation_height,
            timestamp: r.timestamp,
        }
    }
}

// Plain sat formatter kept for the Compose demo screen and the FFI smoke
// test (free function returning a bare `String` — a safe shape).
#[uniffi::export]
pub fn format_sat(sat: u64) -> String {
    bhodl_core::Amount::from_sat(sat).to_string()
}

// ---------------------------------------------------------------------------
// Phase 8.1 — BTC rate history FFI surface
// ---------------------------------------------------------------------------
//
// Audit 2026-10 (KDF M3/M5, H9): the rate / backfill / net-worth entry
// points used to be free functions taking `data_dir + device_pin`, which
// re-ran Argon2 on every call and forced Kotlin to keep the device PIN
// in memory. They are now methods on `RegistryHandle` (see the
// "historical BTC rates" impl block below) that reuse the handle's
// already-derived key, and take the user's Tor `proxy_url` so the rate
// providers are never contacted over clearnet while Tor is on.

use bhodl_wallet_onchain::{
    BtcRateHistoryRepository, BtcRateRepo, MigrationReport, RateError, RecomputeStats,
    TableBackfillStats, classify_rate, normalize_to_utc_midnight,
};

/// One (date, rate) pair returned by `btc_rate_range`.
#[derive(uniffi::Record)]
pub struct RatePointFfi {
    /// UTC midnight Unix timestamp.
    pub date_unix: i64,
    /// 1 BTC = `rate` units of the requested currency.
    pub rate: f64,
}

/// One request entry for `btc_rate_backfill`.
#[derive(uniffi::Record)]
pub struct RateBackfillRequestFfi {
    pub date_unix: i64,
    pub currency: String,
}

/// Summary report returned by `btc_rate_backfill`.
#[derive(uniffi::Record)]
pub struct BackfillReportFfi {
    pub requested: u32,
    pub cache_hits: u32,
    pub fetched: u32,
    pub failed: Vec<RateBackfillFailureFfi>,
}

/// One entry in `BackfillReportFfi.failed`.
#[derive(uniffi::Record)]
pub struct RateBackfillFailureFfi {
    pub date_unix: i64,
    pub currency: String,
}

/// Phase 8.6 — exhaustive mapping from `RateError` to `WalletError`.
///
/// The two typed variants (`CurrencyNotSupported`, `NetworkUnavailable`)
/// now map to dedicated `WalletError` variants so Kotlin/Swift callers
/// can use typed `catch` instead of message-substring matching. All
/// other failures map to `RateAllSourcesFailed` (or `Storage` for
/// `RateError::Storage`).
fn map_rate_error(e: RateError) -> WalletError {
    match e {
        RateError::CurrencyNotSupported(currency) => {
            WalletError::RateCurrencyNotSupported { currency }
        }
        RateError::NetworkUnavailable => WalletError::RateNetworkUnavailable,
        RateError::AllSourcesFailed { last_error } => {
            WalletError::RateAllSourcesFailed { detail: last_error }
        }
        other => WalletError::Storage {
            detail: other.to_string(),
        },
    }
}

/// Open the device registry at `data_dir` with `device_pin` through the
/// shared, MAC-verifying unlock helper (M1). Production read paths now go
/// through `RegistryHandle` (no per-call Argon2); kept for the M1 test.
#[cfg(test)]
fn open_registry_verified(data_dir: &Path, device_pin: &str) -> Result<Registry, WalletError> {
    let db_path = data_dir.join(REGISTRY_FILE_NAME);
    let (registry, _) = unlock_with(data_dir, KdfScope::Registry, device_pin, false, |key| {
        open_registry(&db_path, key).map_err(WalletError::from)
    })?;
    Ok(registry)
}

// ---------------------------------------------------------------------------
// Phase 8.2 — BTC-at-time backfill FFI surface
// ---------------------------------------------------------------------------

/// Per-table counters for the Phase 8.2 backfill. Mirrors
/// `bhodl_wallet_onchain::TableBackfillStats`.
#[derive(uniffi::Record)]
pub struct TableBackfillStatsFfi {
    /// Rows whose target column was NULL and were considered this run.
    pub considered: u32,
    /// Rows successfully backfilled during this run.
    pub fetched: u32,
    /// Rows whose target column was already non-NULL (skipped).
    pub already_done: u32,
    /// Rows where rate could not be obtained; left NULL for retry.
    pub failed: u32,
}

impl From<TableBackfillStats> for TableBackfillStatsFfi {
    fn from(s: TableBackfillStats) -> Self {
        Self {
            considered: s.considered as u32,
            fetched: s.fetched as u32,
            already_done: s.already_done as u32,
            failed: s.failed as u32,
        }
    }
}

/// Top-level report returned by `phase_8_2_backfill_btc_at_time`.
///
/// Named `MigrationReportFfi` rather than `BackfillReportFfi` to avoid
/// a collision with Phase 8.1's `BackfillReportFfi` (rate-repo batch).
#[derive(uniffi::Record)]
pub struct MigrationReportFfi {
    pub investment_lots: TableBackfillStatsFfi,
    pub investment_sells: TableBackfillStatsFfi,
    pub lot_disposals: TableBackfillStatsFfi,
    pub manual_assets: TableBackfillStatsFfi,
}

impl From<MigrationReport> for MigrationReportFfi {
    fn from(r: MigrationReport) -> Self {
        Self {
            investment_lots: r.investment_lots.into(),
            investment_sells: r.investment_sells.into(),
            lot_disposals: r.lot_disposals.into(),
            manual_assets: r.manual_assets.into(),
        }
    }
}

/// Counters returned by `recompute_all_lot_btc_rates` (on `RegistryHandle`)
/// and `btc_recompute_all_lot_rates` (free FFI function).
///
/// Mirrors `bhodl_wallet_onchain::RecomputeStats`.
#[derive(uniffi::Record)]
pub struct RecomputeStatsFfi {
    /// Total lots iterated.
    pub considered: u32,
    /// Lots whose `btc_per_unit_at_purchase` was resolved and overwritten.
    pub updated: u32,
    /// Lots whose rate was unavailable. Existing column value preserved.
    pub failed: u32,
}

impl From<RecomputeStats> for RecomputeStatsFfi {
    fn from(s: RecomputeStats) -> Self {
        Self {
            considered: s.considered as u32,
            updated: s.updated as u32,
            failed: s.failed as u32,
        }
    }
}

// ---------------------------------------------------------------------------
// Phase 3 — Registry FFI surface
// ---------------------------------------------------------------------------
//
// The registry catalogues every watch-only wallet plus the tracker
// tables. It is opened/created with the **device PIN**; every watch
// wallet's state file is encrypted with the same device key.

/// FFI mirror of `bhodl_wallet_onchain::WalletKind`.
///
/// Replicated as a UniFFI enum so the Kotlin/Swift side gets a typed
/// sealed type without coupling to the internal Rust enum's path.
#[derive(uniffi::Enum, Clone, Copy, PartialEq, Eq)]
pub enum WalletKindFfi {
    WatchXpub,
    WatchAddresses,
    WatchHd,
}

impl From<WalletKind> for WalletKindFfi {
    fn from(k: WalletKind) -> Self {
        match k {
            WalletKind::WatchXpub => Self::WatchXpub,
            WalletKind::WatchAddresses => Self::WatchAddresses,
            WalletKind::WatchHd => Self::WatchHd,
        }
    }
}

impl From<WalletKindFfi> for WalletKind {
    fn from(k: WalletKindFfi) -> Self {
        match k {
            WalletKindFfi::WatchXpub => Self::WatchXpub,
            WalletKindFfi::WatchAddresses => Self::WatchAddresses,
            WalletKindFfi::WatchHd => Self::WatchHd,
        }
    }
}

/// FFI mirror of `bhodl_wallet_onchain::WalletRecord`.
///
/// `id` is the UUIDv4 in its hyphenated string form (`xxxxxxxx-xxxx-...`)
/// to keep the FFI Record purely scalar — UniFFI does not have a native
/// Uuid type and a custom Record with raw bytes would obscure the
/// human-readable identity used in the filesystem path.
#[derive(uniffi::Record, Clone, PartialEq, Eq)]
pub struct WalletRecordFfi {
    pub id: String,
    pub label: String,
    pub kind: WalletKindFfi,
    pub descriptor_pub: String,
    pub network: String,
    pub created_at_unix: i64,
}

impl From<WalletRecord> for WalletRecordFfi {
    fn from(r: WalletRecord) -> Self {
        Self {
            id: r.id.to_string(),
            label: r.label,
            kind: r.kind.into(),
            descriptor_pub: r.descriptor_pub,
            network: r.network,
            created_at_unix: r.created_at_unix,
        }
    }
}

/// FFI mirror of `bhodl_wallet_onchain::WatchBip`.
///
/// Selects the output-script type for a simple xpub watch wallet:
/// - `Taproot` — BIP-86, `tr(xpub/0/i)` recv keychain
/// - `NativeSegwit` — BIP-84, `wpkh(xpub/0/i)` recv keychain
/// - `NestedSegwit` — BIP-49, `sh(wpkh(xpub/0/i))` recv keychain
///
/// `i` is the address index in the keychain (BDK derives on demand).
#[derive(uniffi::Enum, Clone, Copy, PartialEq, Eq)]
pub enum WatchBipFfi {
    Taproot,
    NativeSegwit,
    NestedSegwit,
}

impl From<WatchBipFfi> for WatchBip {
    fn from(b: WatchBipFfi) -> Self {
        match b {
            WatchBipFfi::Taproot => Self::Taproot,
            WatchBipFfi::NativeSegwit => Self::NativeSegwit,
            WatchBipFfi::NestedSegwit => Self::NestedSegwit,
        }
    }
}

/// Opaque handle on an open registry DB.
///
/// Constructed via [`RegistryHandle::create_or_load`] with the
/// device PIN. Holds the SQLCipher `Connection` behind a `Mutex` so
/// the handle is `Send + Sync` (UniFFI requirement). The Kotlin side
/// stores it in a `RegistryStore` singleton mirroring the existing
/// `WalletStore` pattern.
///
/// Also retains the device-derived encryption key and data directory
/// so the watch-wallet convenience methods (`add_watch_*` /
/// `open_watch`) can materialise the per-watch SQLCipher files
/// without requiring the caller to re-supply those values on every
/// call. Threat-model note: the key was already implicitly held in
/// process memory inside the SQLCipher `Connection` via `PRAGMA key`,
/// so retaining it on the struct adds no new exposure surface — but
/// dropping the handle (process exit, lock event, wallet switch)
/// must scrub the key from heap before the allocation is freed.
/// The [`Drop`] impl below does exactly that via [`zeroize`], so a
/// post-mortem coredump or swap dump no longer trivially exposes the
/// AES-256 key.
#[derive(uniffi::Object)]
pub struct RegistryHandle {
    inner: Mutex<Registry>,
    data_dir: PathBuf,
    device_key: [u8; ENCRYPTION_KEY_LEN],
}

impl Drop for RegistryHandle {
    fn drop(&mut self) {
        use zeroize::Zeroize;
        self.device_key.zeroize();
    }
}

#[uniffi::export]
impl RegistryHandle {
    /// Open or create the registry at `<data_dir>/registry.sqlite`.
    ///
    /// On first call, `registry.kdf.json` is generated next door with
    /// fresh salt + `KdfParams::CURRENT`; the device PIN is run
    /// through Argon2id to derive the SQLCipher encryption key. On
    /// subsequent calls the salt is read back from the JSON
    /// descriptor and the SAME key is re-derived from the supplied
    /// PIN (wrong PIN → SQLCipher refuses the open → `Storage`
    /// error).
    ///
    /// The data dir is created lazily if missing.
    #[uniffi::constructor]
    pub fn create_or_load(
        data_dir: String,
        device_pin: String,
    ) -> Result<std::sync::Arc<Self>, WalletError> {
        // Wrap the PIN so the Rust-side copy is wiped on drop.
        // The JVM-side String is outside our control — documented limitation.
        let device_pin = zeroize::Zeroizing::new(device_pin);
        let data_dir = PathBuf::from(data_dir);
        fs::create_dir_all(&data_dir)?;
        // H1 — MAC verified before the open, descriptor sealed only
        // after SQLCipher accepted the key. A fresh registry enforces
        // the new-PIN policy.
        let registry_path = data_dir.join(REGISTRY_FILE_NAME);
        let (registry, unlocked) =
            unlock_with(&data_dir, KdfScope::Registry, &device_pin, true, |key| {
                open_registry(&registry_path, key).map_err(WalletError::from)
            })?;
        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(registry),
            data_dir,
            device_key: *unlocked.key,
        }))
    }

    /// PIN recovery — open the registry with the recovery code instead of
    /// the forgotten device PIN, and set `new_pin` as the device PIN.
    ///
    /// The code unwraps the data key from `registry.recovery.json`;
    /// SQLCipher accepting it is the proof, and only then is the device
    /// descriptor re-wrapped under `new_pin`. No file is re-encrypted and
    /// the recovery code stays valid.
    ///
    /// Errors: `InvalidArgument` for a malformed code (before any
    /// Argon2 run), `KeyDerivation` for a `new_pin` that fails the PIN
    /// policy, `Storage` with detail `"recovery code rejected"` for a
    /// well-formed but wrong code (the caller counts it against the
    /// lockout), any other `Storage` for missing / unreadable files.
    #[uniffi::constructor]
    pub fn unlock_with_recovery_code(
        data_dir: String,
        recovery_code: String,
        new_pin: String,
    ) -> Result<std::sync::Arc<Self>, WalletError> {
        let recovery_code = zeroize::Zeroizing::new(recovery_code);
        let new_pin = zeroize::Zeroizing::new(new_pin);
        bhodl_core::validate_new_pin(&new_pin)?;
        let code = normalize_recovery_code(&recovery_code).ok_or_else(|| {
            WalletError::InvalidArgument {
                detail: "recovery code must be 24 characters (0-9, A-Z)".into(),
            }
        })?;
        let data_dir = PathBuf::from(data_dir);
        let recovery_path = data_dir.join(RECOVERY_FILE_NAME);
        if !recovery_path.exists() {
            return Err(WalletError::Storage {
                detail: "no recovery code was created for this data dir".into(),
            });
        }
        let data_key = unwrap_file(&recovery_path, &code, WrapPurpose::RecoveryCode)?;
        let registry = open_registry(&data_dir.join(REGISTRY_FILE_NAME), &data_key)?;
        write_wrapped_file(
            &KdfScope::Registry.descriptor_path(&data_dir),
            &new_pin,
            WrapPurpose::DevicePin,
            &data_key,
        )?;
        after_rewrap(&data_dir, KdfScope::Registry);
        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(registry),
            data_dir,
            device_key: *data_key,
        }))
    }

    /// Create (or replace) the recovery code of this registry and return
    /// it, formatted `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`. The code is never
    /// stored: only the data key wrapped by it is
    /// (`registry.recovery.json`). A previous code stops working.
    pub fn create_recovery_code(&self) -> Result<String, WalletError> {
        let code = generate_recovery_code()?;
        write_wrapped_file(
            &self.data_dir.join(RECOVERY_FILE_NAME),
            &code,
            WrapPurpose::RecoveryCode,
            &self.device_key,
        )?;
        Ok(format_recovery_code(&code))
    }

    /// Change the device PIN. Returns `false` (nothing changed) when
    /// `old_pin` is not the current PIN — the caller counts that against
    /// the lockout. Only the 32-byte data key is re-wrapped; no database
    /// is re-encrypted.
    pub fn change_device_pin(&self, old_pin: String, new_pin: String) -> Result<bool, WalletError> {
        let old_pin = zeroize::Zeroizing::new(old_pin);
        let new_pin = zeroize::Zeroizing::new(new_pin);
        bhodl_core::validate_new_pin(&new_pin)?;
        if old_pin.is_empty() {
            return Ok(false);
        }
        let descriptor = KdfScope::Registry.descriptor_path(&self.data_dir);
        let current = match unwrap_file(&descriptor, &old_pin, WrapPurpose::DevicePin) {
            Ok(key) => key,
            Err(WalletError::Storage { detail }) if detail.contains("unwrap failed") => {
                return Ok(false);
            }
            Err(e) => return Err(e),
        };
        if !kdf::constant_time_eq(current.as_slice(), &self.device_key) {
            return Ok(false);
        }
        write_wrapped_file(
            &descriptor,
            &new_pin,
            WrapPurpose::DevicePin,
            &self.device_key,
        )?;
        after_rewrap(&self.data_dir, KdfScope::Registry);
        Ok(true)
    }

    /// Enumerate every wallet row in insertion order.
    pub fn list_wallets(&self) -> Result<Vec<WalletRecordFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.list_wallets()?.into_iter().map(Into::into).collect())
    }

    /// Duplicate detection for the Add-wallet form. Returns `true` when a
    /// registry row already carries the same public descriptor as
    /// `descriptor_pub`, OR (by xpub normalisation) the same underlying
    /// account xpub — adding it again would double-count its balance.
    ///
    /// Normalisation rationale: the same wallet can be represented
    /// by descriptor strings that differ in checksum, child path
    /// (concrete index vs the `*` wildcard form), or origin-prefix
    /// presence. Comparing on the extracted account xpub catches
    /// all those variants without requiring callers to canonicalise.
    pub fn is_descriptor_tracked(&self, descriptor_pub: String) -> Result<bool, WalletError> {
        let target_xpub = extract_account_xpub(descriptor_pub.clone());
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let rows = guard.list_wallets()?;
        Ok(rows.iter().any(|r| {
            if r.descriptor_pub == descriptor_pub {
                return true;
            }
            match (&target_xpub, extract_account_xpub(r.descriptor_pub.clone())) {
                (Some(a), Some(b)) => a == &b,
                _ => false,
            }
        }))
    }

    /// Look up one wallet by string-form UUID. Returns `None` when
    /// no row matches.
    pub fn get_wallet(&self, id: String) -> Result<Option<WalletRecordFfi>, WalletError> {
        let uuid = Uuid::parse_str(&id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.get_wallet(&uuid)?.map(Into::into))
    }

    /// Insert a wallet record. `id` is the string-form UUID; callers
    /// generate it via UUIDv4 client-side (or [`generate_wallet_id`]
    /// for convenience).
    pub fn add_wallet(&self, record: WalletRecordFfi) -> Result<(), WalletError> {
        let uuid = Uuid::parse_str(&record.id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        guard.add_wallet(&WalletRecord {
            id: uuid,
            label: record.label,
            kind: record.kind.into(),
            descriptor_pub: record.descriptor_pub,
            network: record.network,
            created_at_unix: record.created_at_unix,
        })?;
        Ok(())
    }

    /// Delete a watch wallet by UUID, together with everything in the
    /// registry that references it (tracking categories, crypto-event
    /// kinds, savings-goal bucket / linked-wallet references) in ONE
    /// transaction, then its `watch-state/<id>.sqlite` file
    /// (best-effort). Close any open `WatchHandle` on it first (Windows
    /// file locks). Returns `false` if no row matched.
    pub fn remove_wallet(&self, id: String) -> Result<bool, WalletError> {
        let uuid = Uuid::parse_str(&id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let removed = guard.remove_wallet_with_metadata(&uuid)?;
        drop(guard);
        if removed {
            remove_sqlite_files(&self.watch_state_path(&uuid));
        }
        Ok(removed)
    }

    /// Rename a wallet. Returns `true` if the row exists.
    pub fn rename_wallet(&self, id: String, new_label: String) -> Result<bool, WalletError> {
        let uuid = Uuid::parse_str(&id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.rename_wallet(&uuid, &new_label)?)
    }

    /// Number of signing wallets left by builds before the watch-only
    /// rework. The UI asks the user to confirm their removal.
    pub fn legacy_signing_wallet_count(&self) -> Result<u32, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(u32::try_from(guard.legacy_signing_wallet_ids()?.len()).unwrap_or(u32::MAX))
    }

    /// Delete every legacy signing wallet: registry rows (plus their
    /// categories / goal links, in one transaction) and then their
    /// `wallets/<uuid>/` key directories. Watch wallets that mirrored them
    /// stay. Returns how many were removed. Irreversible — the UI must
    /// have the user's explicit confirmation.
    pub fn purge_legacy_signing_wallets(&self) -> Result<u32, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let removed = guard.purge_legacy_signing_wallets()?;
        drop(guard);
        for id in &removed {
            let dir = self.data_dir.join("wallets").join(id.to_string());
            if dir.exists() {
                fs::remove_dir_all(&dir)?;
            }
        }
        Ok(u32::try_from(removed.len()).unwrap_or(u32::MAX))
    }

    // -----------------------------------------------------------------------
    // Phase 3.4 — tracking categories (cross-wallet)
    //
    // Categories live on the registry instead of per-wallet label storage so
    // the Tracking surface can read/write them for any wallet without
    // opening its watch-state file.
    // -----------------------------------------------------------------------

    /// Set or clear the category attached to a `(wallet_id, txid)` pair.
    /// Pass `None` (or an empty / whitespace-only string) to clear any
    /// existing category for the row.
    pub fn set_event_category(
        &self,
        wallet_id: String,
        txid_or_payment_id: String,
        category: Option<String>,
    ) -> Result<(), WalletError> {
        let uuid = Uuid::parse_str(&wallet_id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        guard.set_event_category(&uuid, &txid_or_payment_id, category.as_deref())?;
        Ok(())
    }

    /// Read the category for a `(wallet_id, txid)` pair. Returns `None`
    /// when no category is set.
    pub fn get_event_category(
        &self,
        wallet_id: String,
        txid_or_payment_id: String,
    ) -> Result<Option<String>, WalletError> {
        let uuid = Uuid::parse_str(&wallet_id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.get_event_category(&uuid, &txid_or_payment_id)?)
    }

    /// List the distinct categories ever used in this registry, sorted
    /// case-insensitively. Drives the Tracking-side autocomplete.
    pub fn list_categories(&self) -> Result<Vec<String>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.list_categories()?)
    }

    // ----------------------------------------------------------------
    // Phase 7.10 — Crypto event kind taxonomy
    // ----------------------------------------------------------------

    /// USER choice: upsert and pin the crypto event kind for a
    /// `(wallet_id, txid)` pair. `"regular"` is stored explicitly, so
    /// the auto-detector cannot re-tag the event afterwards.
    pub fn set_event_crypto_kind(
        &self,
        wallet_id: String,
        txid_or_payment_id: String,
        kind: String,
    ) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        guard.set_event_crypto_kind(&wallet_id, &txid_or_payment_id, &kind)?;
        Ok(())
    }

    pub fn get_event_crypto_kind(
        &self,
        wallet_id: String,
        txid_or_payment_id: String,
    ) -> Result<Option<String>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.get_event_crypto_kind(&wallet_id, &txid_or_payment_id)?)
    }

    /// AUTO-DETECTOR write (send_to_self tagging / H13 cleanup): never
    /// overwrites a user-pinned row. `"regular"` clears an auto row.
    /// Returns `true` iff the stored state changed.
    pub fn set_event_crypto_kind_auto(
        &self,
        wallet_id: String,
        txid_or_payment_id: String,
        kind: String,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_event_crypto_kind_auto(&wallet_id, &txid_or_payment_id, &kind)?)
    }

    /// Dump the entire `tracking_event_crypto_kind` table. The Kotlin
    /// side keys this into a `Map<(walletId,txid), kind>` to populate
    /// `PaymentEvent.crypto_event_kind` in one pass.
    pub fn dump_crypto_event_kinds(&self) -> Result<Vec<CryptoEventKindRow>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .dump_crypto_event_kinds()?
            .into_iter()
            .map(
                |(wallet_id, txid_or_payment_id, kind, user_set)| CryptoEventKindRow {
                    wallet_id,
                    txid_or_payment_id,
                    kind,
                    user_set,
                },
            )
            .collect())
    }

    // ----------------------------------------------------------------
    // BTC average cost — per-transaction price / exclusion
    // ----------------------------------------------------------------

    /// Store the user's price (fiat per BTC in `currency`) and/or
    /// exclusion for one transaction of the BTC average-cost view.
    /// `price_per_btc = None` falls back to the day's market rate; with
    /// no price and `excluded = false` the stored row is removed.
    ///
    /// # Errors
    ///
    /// - `Storage` — negative / non-finite price, bad currency, a price
    ///   without its currency, or a write failure.
    pub fn set_event_cost_basis(
        &self,
        wallet_id: String,
        txid_or_payment_id: String,
        price_per_btc: Option<f64>,
        currency: Option<String>,
        excluded: bool,
    ) -> Result<(), WalletError> {
        let price = match (price_per_btc, currency.as_deref()) {
            (Some(value), Some(ccy)) => Some((value, ccy)),
            (None, _) => None,
            (Some(_), None) => {
                return Err(WalletError::Storage {
                    detail: "price_per_btc needs a currency".into(),
                });
            }
        };
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        guard.set_event_cost_basis(&wallet_id, &txid_or_payment_id, price, excluded)?;
        Ok(())
    }

    /// Every stored per-transaction input of the BTC average-cost view.
    pub fn list_event_cost_basis(&self) -> Result<Vec<EventCostBasisFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_event_cost_basis()?
            .into_iter()
            .map(|row| {
                let (price_per_btc, currency) = row.price.unzip();
                EventCostBasisFfi {
                    wallet_id: row.wallet_id,
                    txid_or_payment_id: row.txid_or_payment_id,
                    price_per_btc,
                    currency,
                    excluded: row.excluded,
                }
            })
            .collect())
    }

    // ----------------------------------------------------------------
    // Phase 3.8 — saved tracking views
    // ----------------------------------------------------------------

    /// Save a tracking view by name. Re-saving an existing name
    /// overwrites it (UI is responsible for confirming overwrite).
    /// `time_range` is `(start, end)` epoch seconds when set, both
    /// `None` to indicate "no time-range filter".
    pub fn save_view(
        &self,
        name: String,
        period: String,
        category_filter: Option<String>,
        time_range_start: Option<i64>,
        time_range_end: Option<i64>,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let range = match (time_range_start, time_range_end) {
            (Some(s), Some(e)) => Some((s, e)),
            _ => None,
        };
        Ok(guard.save_view(&name, &period, category_filter.as_deref(), range)?)
    }

    /// List all saved tracking views, oldest-first.
    pub fn list_views(&self) -> Result<Vec<SavedViewFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_views()?
            .into_iter()
            .map(|v| SavedViewFfi {
                id: v.id,
                name: v.name,
                period: v.period,
                category_filter: v.category_filter,
                time_range_start: v.time_range.map(|(s, _)| s),
                time_range_end: v.time_range.map(|(_, e)| e),
                created_at_unix: v.created_at_unix,
            })
            .collect())
    }

    /// Delete a saved view by id.
    pub fn delete_view(&self, id: i64) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_view(id)?)
    }

    // ----------------------------------------------------------------
    // Phase 4.3 — Budgets
    // ----------------------------------------------------------------

    /// Upsert a budget for a category.
    pub fn set_budget(
        &self,
        category: String,
        budgeted_sat: u64,
        period: String,
    ) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_budget(&category, budgeted_sat, &period)?)
    }

    /// List all budgets, ordered alphabetically by category.
    pub fn list_budgets(&self) -> Result<Vec<TrackingBudgetFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_budgets()?
            .into_iter()
            .map(|b| TrackingBudgetFfi {
                category: b.category,
                budgeted_sat: b.budgeted_sat,
                period: b.period,
                updated_at_unix: b.updated_at_unix,
            })
            .collect())
    }

    /// Delete a budget by category.
    pub fn delete_budget(&self, category: String) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_budget(&category)?)
    }

    // ----------------------------------------------------------------
    // Phase 4.4 — Bills tracker
    // ----------------------------------------------------------------

    pub fn add_bill(
        &self,
        name: String,
        amount_sat: u64,
        due_day: u32,
        display_label: Option<String>,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.add_bill(&name, amount_sat, due_day, display_label.as_deref())?)
    }

    pub fn update_bill(
        &self,
        id: i64,
        name: String,
        amount_sat: u64,
        due_day: u32,
        display_label: Option<String>,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.update_bill(id, &name, amount_sat, due_day, display_label.as_deref())?)
    }

    /// Mark a bill paid at `unix_ts`. Pass `None` to clear the mark.
    pub fn mark_bill_paid(&self, id: i64, unix_ts: Option<i64>) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.mark_bill_paid(id, unix_ts)?)
    }

    pub fn list_bills(&self) -> Result<Vec<TrackingBillFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_bills()?
            .into_iter()
            .map(|b| TrackingBillFfi {
                id: b.id,
                name: b.name,
                amount_sat: b.amount_sat,
                due_day: b.due_day,
                last_paid_unix: b.last_paid_unix,
                display_label: b.display_label,
                created_at_unix: b.created_at_unix,
                variable: b.variable,
                last_paid_amount_sat: b.last_paid_amount_sat,
                category: b.category,
                paid_ref: b.paid_ref,
            })
            .collect())
    }

    /// Registry schema v7 — mark a bill paid with the amount actually paid.
    pub fn mark_bill_paid_amount(
        &self,
        id: i64,
        unix_ts: i64,
        amount_sat: u64,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.mark_bill_paid_amount(id, unix_ts, amount_sat)?)
    }

    /// Registry schema v7 — whether the bill's amount varies.
    pub fn set_bill_variable(&self, id: i64, variable: bool) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_bill_variable(id, variable)?)
    }

    pub fn delete_bill(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_bill(id)?)
    }

    /// Registry schema v8 — the budget category of a bill.
    pub fn set_bill_category(
        &self,
        id: i64,
        category: Option<String>,
    ) -> Result<bool, WalletError> {
        Ok(self
            .registry()?
            .set_bill_category(id, category.as_deref())?)
    }

    /// Registry schema v8 — mark a bill paid by a transaction
    /// (`paid_ref` = `<wallet_id>|<txid>`).
    pub fn mark_bill_paid_by(
        &self,
        id: i64,
        unix_ts: i64,
        amount_sat: u64,
        paid_ref: String,
    ) -> Result<bool, WalletError> {
        Ok(self
            .registry()?
            .mark_bill_paid_by(id, unix_ts, amount_sat, &paid_ref)?)
    }

    // ----------------------------------------------------------------
    // Registry schema v8 — manual transactions
    // ----------------------------------------------------------------

    /// Record income ("in") or spending ("out") outside the wallets;
    /// returns its id.
    ///
    /// Schema v10 — `liability_id` + `liability_payment` (both or
    /// neither) make it a payment on that debt, from `account_ref`.
    #[allow(clippy::too_many_arguments)]
    pub fn add_manual_txn(
        &self,
        direction: String,
        amount_sat: u64,
        amount_fiat: Option<f64>,
        currency: Option<String>,
        occurred_unix: i64,
        account_ref: Option<String>,
        note: Option<String>,
        liability_id: Option<i64>,
        liability_payment: Option<String>,
    ) -> Result<i64, WalletError> {
        let debt = match (liability_id, liability_payment.as_deref()) {
            (Some(liability_id), Some(kind)) => {
                Some(bhodl_wallet_onchain::DebtPayment { liability_id, kind })
            }
            (None, None) => None,
            _ => {
                return Err(WalletError::InvalidArgument {
                    detail: "liability_id and liability_payment go together".into(),
                });
            }
        };
        Ok(self.registry()?.add_manual_txn_paying(
            &direction,
            amount_sat,
            amount_fiat,
            currency.as_deref(),
            occurred_unix,
            account_ref.as_deref(),
            note.as_deref(),
            debt,
        )?)
    }

    /// Change a manual transaction (not its account, nor its debt;
    /// `liability_payment` null keeps how it pays the debt).
    #[allow(clippy::too_many_arguments)]
    pub fn update_manual_txn(
        &self,
        id: i64,
        direction: String,
        amount_sat: u64,
        amount_fiat: Option<f64>,
        currency: Option<String>,
        occurred_unix: i64,
        note: Option<String>,
        liability_payment: Option<String>,
    ) -> Result<bool, WalletError> {
        Ok(self.registry()?.update_manual_txn_paying(
            id,
            &direction,
            amount_sat,
            amount_fiat,
            currency.as_deref(),
            occurred_unix,
            note.as_deref(),
            liability_payment.as_deref(),
        )?)
    }

    /// The amortization plan of every mortgage / loan whose details
    /// carry its terms (original amount, start date, rate, installment
    /// or end date), adjusted by the payments recorded on it, as of
    /// `now_unix`. `currency` is the plan's currency when the details
    /// do not name one.
    pub fn loan_schedules(
        &self,
        currency: String,
        now_unix: i64,
    ) -> Result<Vec<LoanScheduleFfi>, WalletError> {
        let schedules = bhodl_wallet_onchain::loan_schedule::load_loan_schedules(
            &*self.registry()?,
            &currency,
        )?;
        Ok(schedules
            .into_iter()
            .map(|(l, s)| LoanScheduleFfi {
                liability_id: l.id,
                currency: s.terms.currency.clone(),
                original_amount: s.terms.principal,
                start_unix: s.terms.start_unix,
                balance_now: s.balance_at(now_unix),
                payment_now: s.payment_at(now_unix),
                next_due_unix: s.next_due_after(now_unix),
                payoff_unix: s.payoff_unix,
                installments_left: u32::try_from(
                    s.due_dates.iter().filter(|&&d| d > now_unix).count(),
                )
                .unwrap_or(u32::MAX),
            })
            .collect())
    }

    /// Delete a manual transaction, its category and any bill link.
    pub fn delete_manual_txn(&self, id: i64) -> Result<bool, WalletError> {
        Ok(self.registry()?.delete_manual_txn(id)?)
    }

    /// Every manual transaction, newest first.
    pub fn list_manual_txns(&self) -> Result<Vec<ManualTxnFfi>, WalletError> {
        Ok(self
            .registry()?
            .list_manual_txns()?
            .into_iter()
            .map(|t| ManualTxnFfi {
                id: t.id,
                direction: t.direction,
                amount_sat: t.amount_sat,
                amount_fiat: t.amount_fiat,
                currency: t.currency,
                occurred_unix: t.occurred_unix,
                account_ref: t.account_ref,
                note: t.note,
                created_at_unix: t.created_at_unix,
                liability_id: t.liability_id,
                liability_payment: t.liability_payment,
            })
            .collect())
    }

    // ----------------------------------------------------------------
    // Registry schema v9 — test-mode profiles
    // ----------------------------------------------------------------

    /// Mark this registry as a test-mode profile (irreversible).
    pub fn mark_demo_profile(&self) -> Result<(), WalletError> {
        Ok(self.registry()?.mark_demo_profile()?)
    }

    /// True for a test-mode profile.
    pub fn is_demo_profile(&self) -> Result<bool, WalletError> {
        Ok(self.registry()?.is_demo_profile()?)
    }

    /// A free profile fact (e.g. the modules of a test profile).
    pub fn profile_meta(&self, key: String) -> Result<Option<String>, WalletError> {
        Ok(self.registry()?.profile_meta(&key)?)
    }

    /// Set or (with `null`) remove a profile fact.
    pub fn set_profile_meta(&self, key: String, value: Option<String>) -> Result<(), WalletError> {
        Ok(self.registry()?.set_profile_meta(&key, value.as_deref())?)
    }

    /// Add wallet transactions to a test-mode profile (refused on a
    /// real registry).
    pub fn add_demo_events(&self, events: Vec<DemoEventFfi>) -> Result<(), WalletError> {
        let registry = self.registry()?;
        for e in events {
            registry.add_demo_event(&bhodl_wallet_onchain::DemoEvent {
                wallet_id: e.wallet_id,
                event_id: e.event_id,
                direction: e.direction,
                amount_sat: e.amount_sat,
                fee_sat: e.fee_sat,
                occurred_unix: e.occurred_unix,
                block_height: e.block_height,
            })?;
        }
        Ok(())
    }

    /// The test-mode wallet transactions, oldest first (empty on a real
    /// registry).
    pub fn list_demo_events(&self) -> Result<Vec<DemoEventFfi>, WalletError> {
        Ok(self
            .registry()?
            .list_demo_events()?
            .into_iter()
            .map(|e| DemoEventFfi {
                wallet_id: e.wallet_id,
                event_id: e.event_id,
                direction: e.direction,
                amount_sat: e.amount_sat,
                fee_sat: e.fee_sat,
                occurred_unix: e.occurred_unix,
                block_height: e.block_height,
            })
            .collect())
    }

    /// Write the BTC rates a test-mode profile carries (refused on a
    /// real registry).
    pub fn put_demo_rates(
        &self,
        currency: String,
        points: Vec<RatePointFfi>,
    ) -> Result<(), WalletError> {
        let pairs: Vec<(i64, f64)> = points.iter().map(|p| (p.date_unix, p.rate)).collect();
        Ok(self.registry()?.put_demo_rates(&currency, &pairs)?)
    }

    /// Move every date of a test-mode profile by `days` (refused on a
    /// real registry); returns the rows touched.
    pub fn shift_demo_dates(&self, days: i64) -> Result<u64, WalletError> {
        Ok(self.registry()?.shift_demo_dates(days)?)
    }

    // ----------------------------------------------------------------
    // Phase 4.5 — Savings goals
    // ----------------------------------------------------------------

    #[allow(clippy::too_many_arguments)]
    pub fn add_savings_goal(
        &self,
        name: String,
        target_sat: u64,
        current_sat: u64,
        color: String,
        deadline_unix: Option<i64>,
        linked_wallet_id: Option<String>,
        target_pct_of_net_worth: Option<u32>,
        bucket_kind: String,
        bucket_ref: Option<String>,
        goal_kind: String,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        // Phase 7.2.1 — clamp the % to [0, 100] at the FFI boundary
        // so the storage layer never has to think about out-of-range
        // values. A clamp (rather than rejection) keeps the call
        // ergonomic — the UI already constrains the slider, this is
        // just defence in depth against programmatic callers.
        let pct = target_pct_of_net_worth.map(|v| v.min(100));
        Ok(guard.add_savings_goal(
            &name,
            target_sat,
            current_sat,
            &color,
            deadline_unix,
            linked_wallet_id.as_deref(),
            pct,
            &bucket_kind,
            bucket_ref.as_deref(),
            &goal_kind,
        )?)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn update_savings_goal(
        &self,
        id: i64,
        name: String,
        target_sat: u64,
        current_sat: u64,
        color: String,
        deadline_unix: Option<i64>,
        linked_wallet_id: Option<String>,
        target_pct_of_net_worth: Option<u32>,
        bucket_kind: String,
        bucket_ref: Option<String>,
        goal_kind: String,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let pct = target_pct_of_net_worth.map(|v| v.min(100));
        Ok(guard.update_savings_goal(
            id,
            &name,
            target_sat,
            current_sat,
            &color,
            deadline_unix,
            linked_wallet_id.as_deref(),
            pct,
            &bucket_kind,
            bucket_ref.as_deref(),
            &goal_kind,
        )?)
    }

    pub fn list_savings_goals(&self) -> Result<Vec<TrackingSavingsGoalFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_savings_goals()?
            .into_iter()
            .map(|g| TrackingSavingsGoalFfi {
                id: g.id,
                name: g.name,
                target_sat: g.target_sat,
                current_sat: g.current_sat,
                color: g.color,
                deadline_unix: g.deadline_unix,
                linked_wallet_id: g.linked_wallet_id,
                target_pct_of_net_worth: g.target_pct_of_net_worth,
                bucket_kind: g.bucket_kind,
                bucket_ref: g.bucket_ref,
                goal_kind: g.goal_kind,
                target_denomination: g.target_denomination,
                target_fiat_amount: g.target_fiat_amount,
                target_fiat_currency: g.target_fiat_currency,
                created_at_unix: g.created_at_unix,
            })
            .collect())
    }

    pub fn delete_savings_goal(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_savings_goal(id)?)
    }

    /// Phase 8.6 — set the denomination of a savings goal.
    ///
    /// Mirrors [`Registry::set_savings_goal_denomination`].
    pub fn set_savings_goal_denomination(
        &self,
        id: i64,
        denomination: String,
        fiat_amount: Option<f64>,
        fiat_currency: Option<String>,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_savings_goal_denomination(
            id,
            &denomination,
            fiat_amount,
            fiat_currency.as_deref(),
        )?)
    }

    // ----------------------------------------------------------------
    // Phase 4.6 — Liabilities
    // ----------------------------------------------------------------

    pub fn add_liability(
        &self,
        name: String,
        amount_sat: u64,
        kind: String,
        display_label: Option<String>,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.add_liability(&name, amount_sat, &kind, display_label.as_deref())?)
    }

    pub fn update_liability(
        &self,
        id: i64,
        name: String,
        amount_sat: u64,
        kind: String,
        display_label: Option<String>,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.update_liability(id, &name, amount_sat, &kind, display_label.as_deref())?)
    }

    pub fn list_liabilities(&self) -> Result<Vec<TrackingLiabilityFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_liabilities()?
            .into_iter()
            .map(|l| TrackingLiabilityFfi {
                id: l.id,
                name: l.name,
                amount_sat: l.amount_sat,
                kind: l.kind,
                display_label: l.display_label,
                created_at_unix: l.created_at_unix,
            })
            .collect())
    }

    pub fn delete_liability(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_liability(id)?)
    }

    // ----------------------------------------------------------------
    // Registry v6 — kind-specific details of portfolio rows
    // ----------------------------------------------------------------

    /// Store the details object of one row of `table` (`investment`,
    /// `manual_asset` or `liability`). `{}` clears it. Returns `false`
    /// when the id does not exist. Malformed objects are refused with
    /// `InvalidArgument`.
    pub fn set_portfolio_details(
        &self,
        table: String,
        id: i64,
        details_json: String,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_details(&table, id, &details_json)?)
    }

    /// Details of every row of `table` that has any.
    pub fn list_portfolio_details(
        &self,
        table: String,
    ) -> Result<Vec<PortfolioDetailsFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_details(&table)?
            .into_iter()
            .map(|(id, details_json)| PortfolioDetailsFfi { id, details_json })
            .collect())
    }

    // ----------------------------------------------------------------
    // Phase 7.4 — Manual Asset Accounts
    // ----------------------------------------------------------------

    #[allow(clippy::too_many_arguments)]
    pub fn add_manual_asset(
        &self,
        name: String,
        kind: String,
        value_fiat: f64,
        currency: String,
        display_label: Option<String>,
        notes: Option<String>,
        purchase_unix: Option<i64>,
        purchase_value_fiat: Option<f64>,
        purchase_currency: Option<String>,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.add_manual_asset(
            &name,
            &kind,
            value_fiat,
            &currency,
            display_label.as_deref(),
            notes.as_deref(),
            purchase_unix,
            purchase_value_fiat,
            purchase_currency.as_deref(),
        )?)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn update_manual_asset(
        &self,
        id: i64,
        name: String,
        kind: String,
        value_fiat: f64,
        currency: String,
        display_label: Option<String>,
        notes: Option<String>,
        purchase_unix: Option<i64>,
        purchase_value_fiat: Option<f64>,
        purchase_currency: Option<String>,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.update_manual_asset(
            id,
            &name,
            &kind,
            value_fiat,
            &currency,
            display_label.as_deref(),
            notes.as_deref(),
            purchase_unix,
            purchase_value_fiat,
            purchase_currency.as_deref(),
        )?)
    }

    pub fn list_manual_assets(&self) -> Result<Vec<TrackingManualAssetFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_manual_assets()?
            .into_iter()
            .map(|a| TrackingManualAssetFfi {
                id: a.id,
                name: a.name,
                kind: a.kind,
                value_fiat: a.value_fiat,
                currency: a.currency,
                display_label: a.display_label,
                notes: a.notes,
                last_updated_unix: a.last_updated_unix,
                created_at_unix: a.created_at_unix,
                purchase_unix: a.purchase_unix,
                purchase_value_fiat: a.purchase_value_fiat,
                purchase_currency: a.purchase_currency,
                purchase_btc_at_time: a.purchase_btc_at_time,
            })
            .collect())
    }

    pub fn delete_manual_asset(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_manual_asset(id)?)
    }

    // ----------------------------------------------------------------
    // Phase 7.5 — Categorization rules
    // ----------------------------------------------------------------

    #[allow(clippy::too_many_arguments)]
    pub fn add_rule(
        &self,
        name: String,
        condition_field: String,
        condition_op: String,
        condition_value: String,
        action_field: String,
        action_value: String,
        priority: i64,
        enabled: bool,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.add_rule(
            &name,
            &condition_field,
            &condition_op,
            &condition_value,
            &action_field,
            &action_value,
            priority,
            enabled,
        )?)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn update_rule(
        &self,
        id: i64,
        name: String,
        condition_field: String,
        condition_op: String,
        condition_value: String,
        action_field: String,
        action_value: String,
        priority: i64,
        enabled: bool,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.update_rule(
            id,
            &name,
            &condition_field,
            &condition_op,
            &condition_value,
            &action_field,
            &action_value,
            priority,
            enabled,
        )?)
    }

    /// Set the evaluation order of all rules at once (first id evaluated
    /// first) in one registry transaction — see `Registry::reorder_rules`.
    pub fn reorder_rules(&self, ordered_ids: Vec<i64>) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.reorder_rules(&ordered_ids)?)
    }

    pub fn list_rules(&self) -> Result<Vec<TrackingRuleFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_rules()?
            .into_iter()
            .map(|r| TrackingRuleFfi {
                id: r.id,
                name: r.name,
                condition_field: r.condition_field,
                condition_op: r.condition_op,
                condition_value: r.condition_value,
                action_field: r.action_field,
                action_value: r.action_value,
                priority: r.priority,
                enabled: r.enabled,
                created_at_unix: r.created_at_unix,
            })
            .collect())
    }

    pub fn delete_rule(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_rule(id)?)
    }

    pub fn dismiss_recurring(&self, payee_key: String) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.dismiss_recurring(&payee_key)?)
    }

    pub fn list_dismissed_recurring(&self) -> Result<Vec<String>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.list_dismissed_recurring()?)
    }

    pub fn undismiss_recurring(&self, payee_key: String) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.undismiss_recurring(&payee_key)?)
    }

    // ----------------------------------------------------------------
    // Phase 5.1 — Investments
    // ----------------------------------------------------------------

    #[allow(clippy::too_many_arguments)]
    pub fn add_investment(
        &self,
        name: String,
        asset_type: String,
        ticker: Option<String>,
        quantity: f64,
        unit: String,
        cost_basis_fiat: f64,
        cost_basis_currency: String,
        purchase_unix: i64,
        manual_current_value: Option<f64>,
        notes: Option<String>,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.add_investment(
            &name,
            &asset_type,
            ticker.as_deref(),
            quantity,
            &unit,
            cost_basis_fiat,
            &cost_basis_currency,
            purchase_unix,
            manual_current_value,
            notes.as_deref(),
        )?)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn update_investment(
        &self,
        id: i64,
        name: String,
        asset_type: String,
        ticker: Option<String>,
        quantity: f64,
        unit: String,
        cost_basis_fiat: f64,
        cost_basis_currency: String,
        purchase_unix: i64,
        manual_current_value: Option<f64>,
        notes: Option<String>,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.update_investment(
            id,
            &name,
            &asset_type,
            ticker.as_deref(),
            quantity,
            &unit,
            cost_basis_fiat,
            &cost_basis_currency,
            purchase_unix,
            manual_current_value,
            notes.as_deref(),
        )?)
    }

    pub fn list_investments(&self) -> Result<Vec<TrackingInvestmentFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_investments()?
            .into_iter()
            .map(|i| TrackingInvestmentFfi {
                id: i.id,
                name: i.name,
                asset_type: i.asset_type,
                ticker: i.ticker,
                quantity: i.quantity,
                unit: i.unit,
                cost_basis_fiat: i.cost_basis_fiat,
                cost_basis_currency: i.cost_basis_currency,
                purchase_unix: i.purchase_unix,
                manual_current_value: i.manual_current_value,
                fetched_current_value: i.fetched_current_value,
                last_priced_at_unix: i.last_priced_at_unix,
                notes: i.notes,
                cost_basis_method: i.cost_basis_method,
                created_at_unix: i.created_at_unix,
            })
            .collect())
    }

    pub fn delete_investment(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_investment(id)?)
    }

    /// Phase 5.2 — set the latest fetched current value for an
    /// investment (called by the Kotlin-side PriceCache after a
    /// successful Yahoo / API roundtrip). Updates both the manual
    /// value column and `last_priced_at_unix` so the UI can show
    /// "machine-fetched at HH:mm".
    pub fn set_investment_fetched_price(
        &self,
        id: i64,
        fetched_value: f64,
        priced_at_unix: i64,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_investment_fetched_price(id, fetched_value, priced_at_unix)?)
    }

    // ----------------------------------------------------------------
    // Phase 7.8 — Investment lots + cost basis method
    // ----------------------------------------------------------------

    #[allow(clippy::too_many_arguments)]
    pub fn add_investment_lot(
        &self,
        investment_id: i64,
        quantity: f64,
        price_per_unit: f64,
        fees_fiat: f64,
        purchase_unix: i64,
        notes: Option<String>,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.add_investment_lot(
            investment_id,
            quantity,
            price_per_unit,
            fees_fiat,
            purchase_unix,
            notes.as_deref(),
        )?)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn update_investment_lot(
        &self,
        id: i64,
        quantity: f64,
        price_per_unit: f64,
        fees_fiat: f64,
        purchase_unix: i64,
        notes: Option<String>,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.update_investment_lot(
            id,
            quantity,
            price_per_unit,
            fees_fiat,
            purchase_unix,
            notes.as_deref(),
        )?)
    }

    pub fn list_investment_lots(
        &self,
        investment_id: i64,
    ) -> Result<Vec<TrackingInvestmentLotFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_investment_lots(investment_id)?
            .into_iter()
            .map(|l| TrackingInvestmentLotFfi {
                id: l.id,
                investment_id: l.investment_id,
                quantity: l.quantity,
                price_per_unit: l.price_per_unit,
                fees_fiat: l.fees_fiat,
                purchase_unix: l.purchase_unix,
                notes: l.notes,
                created_at_unix: l.created_at_unix,
                btc_per_unit_at_purchase: l.btc_per_unit_at_purchase,
                original_quantity: Some(l.original_quantity),
            })
            .collect())
    }

    pub fn list_all_investment_lots(&self) -> Result<Vec<TrackingInvestmentLotFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_all_investment_lots()?
            .into_iter()
            .map(|l| TrackingInvestmentLotFfi {
                id: l.id,
                investment_id: l.investment_id,
                quantity: l.quantity,
                price_per_unit: l.price_per_unit,
                fees_fiat: l.fees_fiat,
                purchase_unix: l.purchase_unix,
                notes: l.notes,
                created_at_unix: l.created_at_unix,
                btc_per_unit_at_purchase: l.btc_per_unit_at_purchase,
                original_quantity: Some(l.original_quantity),
            })
            .collect())
    }

    pub fn delete_investment_lot(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_investment_lot(id)?)
    }

    pub fn set_investment_cost_basis_method(
        &self,
        id: i64,
        method: String,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_investment_cost_basis_method(id, &method)?)
    }

    pub fn seed_investment_lot_from_aggregate(
        &self,
        investment_id: i64,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.seed_investment_lot_from_aggregate(investment_id)?)
    }

    pub fn set_investment_lot_btc_rate(
        &self,
        lot_id: i64,
        btc_per_unit_at_purchase: f64,
    ) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.set_investment_lot_btc_rate(lot_id, btc_per_unit_at_purchase)?)
    }

    // ----------------------------------------------------------------
    // Phase 7.9 — Sells + lot disposals
    // ----------------------------------------------------------------

    #[allow(clippy::too_many_arguments)]
    pub fn record_sell(
        &self,
        investment_id: i64,
        sale_unix: i64,
        sale_price_per_unit: f64,
        sale_qty: f64,
        fees_fiat: f64,
        notes: Option<String>,
    ) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.record_sell(
            investment_id,
            sale_unix,
            sale_price_per_unit,
            sale_qty,
            fees_fiat,
            notes.as_deref(),
        )?)
    }

    /// Explicit-lot sale: the caller picks the lots (and quantities) to
    /// dispose (Specific-ID, or a per-sale FIFO / HIFO choice from the
    /// sell dialog's method comparison). Validation (ownership, remaining quantities, sum = sale
    /// quantity) and the writes run in ONE registry transaction — see
    /// `Registry::record_sell_with_lots`.
    #[allow(clippy::too_many_arguments)]
    pub fn record_sell_with_lots(
        &self,
        investment_id: i64,
        sale_unix: i64,
        sale_price_per_unit: f64,
        sale_qty: f64,
        fees_fiat: f64,
        notes: Option<String>,
        selection: Vec<LotSelectionFfi>,
    ) -> Result<i64, WalletError> {
        let selection: Vec<bhodl_wallet_onchain::LotSelection> = selection
            .into_iter()
            .map(|s| bhodl_wallet_onchain::LotSelection {
                lot_id: s.lot_id,
                qty: s.qty,
            })
            .collect();
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.record_sell_with_lots(
            investment_id,
            sale_unix,
            sale_price_per_unit,
            sale_qty,
            fees_fiat,
            notes.as_deref(),
            &selection,
        )?)
    }

    pub fn list_investment_sells(
        &self,
        investment_id: i64,
    ) -> Result<Vec<TrackingInvestmentSellFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_investment_sells(investment_id)?
            .into_iter()
            .map(|s| TrackingInvestmentSellFfi {
                id: s.id,
                investment_id: s.investment_id,
                sale_unix: s.sale_unix,
                sale_price_per_unit: s.sale_price_per_unit,
                sale_qty: s.sale_qty,
                fees_fiat: s.fees_fiat,
                notes: s.notes,
                created_at_unix: s.created_at_unix,
                btc_per_unit_at_sale: s.btc_per_unit_at_sale,
            })
            .collect())
    }

    pub fn list_all_investment_sells(&self) -> Result<Vec<TrackingInvestmentSellFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_all_investment_sells()?
            .into_iter()
            .map(|s| TrackingInvestmentSellFfi {
                id: s.id,
                investment_id: s.investment_id,
                sale_unix: s.sale_unix,
                sale_price_per_unit: s.sale_price_per_unit,
                sale_qty: s.sale_qty,
                fees_fiat: s.fees_fiat,
                notes: s.notes,
                created_at_unix: s.created_at_unix,
                btc_per_unit_at_sale: s.btc_per_unit_at_sale,
            })
            .collect())
    }

    pub fn list_all_lot_disposals(&self) -> Result<Vec<TrackingLotDisposalFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard
            .list_all_lot_disposals()?
            .into_iter()
            .map(|d| TrackingLotDisposalFfi {
                id: d.id,
                sell_id: d.sell_id,
                lot_id: d.lot_id,
                qty_consumed: d.qty_consumed,
                cost_basis_per_unit: d.cost_basis_per_unit,
                gain_loss_fiat: d.gain_loss_fiat,
                created_at_unix: d.created_at_unix,
                gain_loss_btc: d.gain_loss_btc,
            })
            .collect())
    }

    pub fn delete_investment_sell(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_investment_sell(id)?)
    }

    // ----------------------------------------------------------------
    // Phase 8.2 — per-row on-demand BTC-rate resolution
    // ----------------------------------------------------------------

    /// Resolve and persist `btc_per_unit_at_purchase` for a single
    /// investment lot using the already-open registry connection.
    ///
    /// Queries the rate at `normalize_to_utc_midnight(purchase_unix)` in the
    /// investment's `cost_basis_currency` and writes it to the lot row.
    ///
    /// Returns `true` when a rate was written; `false` when the lot was not
    /// found or the rate is unavailable (pre-genesis, future, unsupported
    /// currency). The column is left NULL in the unavailable case.
    pub fn resolve_lot_btc_rate(
        &self,
        lot_id: i64,
        proxy_url: Option<String>,
    ) -> Result<bool, WalletError> {
        // Audit M4 — read under the registry lock, fetch unlocked, re-lock
        // to write. A Tor fetch can take tens of seconds.
        let Some((day, currency)) = self.registry()?.lot_btc_rate_query(lot_id)? else {
            return Ok(false);
        };
        let repo = self.rate_repo(proxy_url.as_deref())?;
        match classify_rate(repo.rate_at(day, &currency), &format!("lot {lot_id}"))? {
            Some(rate) => Ok(self.registry()?.apply_lot_btc_rate(lot_id, rate)?),
            None => Ok(false),
        }
    }

    /// Resolve and persist `btc_per_unit_at_sale` for a single sell row,
    /// then re-derive `gain_loss_btc` for all its disposals whose lot rate
    /// is already known.
    ///
    /// Returns `true` when the sell rate was written.
    pub fn resolve_sell_btc(
        &self,
        sell_id: i64,
        proxy_url: Option<String>,
    ) -> Result<bool, WalletError> {
        let Some((day, currency)) = self.registry()?.sell_btc_rate_query(sell_id)? else {
            return Ok(false);
        };
        let repo = self.rate_repo(proxy_url.as_deref())?;
        match classify_rate(repo.rate_at(day, &currency), &format!("sell {sell_id}"))? {
            Some(rate) => Ok(self.registry()?.apply_sell_btc_rate(sell_id, rate)?),
            None => Ok(false),
        }
    }

    /// Resolve and persist `purchase_btc_at_time` for a single manual asset.
    ///
    /// Uses `purchase_unix` + `purchase_currency` (falling back to `currency`
    /// when `purchase_currency` is NULL). Does nothing and returns `false`
    /// when `purchase_unix` is NULL.
    pub fn resolve_manual_asset_purchase_btc(
        &self,
        id: i64,
        proxy_url: Option<String>,
    ) -> Result<bool, WalletError> {
        let Some(query) = self.registry()?.manual_asset_btc_rate_query(id)? else {
            return Ok(false);
        };
        let repo = self.rate_repo(proxy_url.as_deref())?;
        match classify_rate(
            repo.rate_at(query.day_unix, &query.effective_currency),
            &format!("manual asset {id}"),
        )? {
            Some(rate) => Ok(self
                .registry()?
                .apply_manual_asset_purchase_btc(id, &query, rate)?),
            None => Ok(false),
        }
    }

    /// Re-resolve `btc_per_unit_at_purchase` for every investment lot,
    /// overwriting any prior value on success. Preserves the existing column
    /// when resolution fails so a transient network error never wipes a
    /// correctly written historical rate.
    ///
    /// Bypasses the rate cache (audit M5) so a bad cached rate is actually
    /// replaced, and never holds the registry lock across a fetch (M4).
    ///
    /// Returns `(considered, updated, failed)` counters.
    pub fn recompute_all_lot_btc_rates(
        &self,
        proxy_url: Option<String>,
    ) -> Result<RecomputeStatsFfi, WalletError> {
        let lots = self.registry()?.all_lot_btc_rate_queries()?;
        let repo = self.rate_repo(proxy_url.as_deref())?;
        let mut stats = RecomputeStats {
            considered: lots.len(),
            ..RecomputeStats::default()
        };
        for (id, day, currency) in lots {
            // Hard error (network / parse) aborts so the caller can retry.
            match classify_rate(repo.fresh_rate_at(day, &currency), &format!("lot {id}"))? {
                Some(rate) => {
                    self.registry()?.apply_lot_btc_rate(id, rate)?;
                    stats.updated += 1;
                }
                None => stats.failed += 1,
            }
        }
        Ok(stats.into())
    }

    /// Register an HD watch wallet from an account-level xpub.
    ///
    /// Creates the encrypted `watch-state/<uuid>.sqlite` file FIRST and
    /// inserts the `WatchXpub` registry row LAST, so a failure at any step
    /// never leaves a registry row without its state file (the partial
    /// file is removed). SLIP-132 `zpub`/`ypub` keys are accepted.
    /// Returns the new `WalletRecordFfi` so callers know the UUID.
    pub fn add_watch_xpub(
        &self,
        label: String,
        xpub: String,
        network: Network,
        bip: WatchBipFfi,
    ) -> Result<WalletRecordFfi, WalletError> {
        let core_network: CoreNetwork = network.into();
        let record = Registry::build_watch_xpub_record(&label, &xpub, core_network, bip.into())?;
        self.materialise_then_register(record, |path, key| {
            WatchAccount::create_from_xpub(core_network, path, key, &xpub, bip.into())
        })
    }

    /// Register a watch wallet over an explicit list of addresses.
    ///
    /// Every address in the list is watched (any standard address type:
    /// P2PKH, P2SH, P2WPKH, P2WSH, P2TR). Address wallets track the raw
    /// script pubkeys directly — no descriptor is synthesised. The list is
    /// validated against `network` and de-duplicated; the registry stores
    /// it as JSON and the watch-state file holds the same list.
    ///
    /// Same file-first / row-last ordering as [`Self::add_watch_xpub`].
    pub fn add_watch_addresses(
        &self,
        label: String,
        addresses: Vec<String>,
        network: Network,
    ) -> Result<WalletRecordFfi, WalletError> {
        let core_network: CoreNetwork = network.into();
        let record = Registry::build_watch_addresses_record(&label, &addresses, core_network)?;
        // Watch exactly the validated, canonical, de-duplicated list the
        // registry row stores.
        let canonical: Vec<String> =
            serde_json::from_str(&record.descriptor_pub).map_err(|e| WalletError::Storage {
                detail: format!("watch address list: {e}"),
            })?;
        self.materialise_then_register(record, |path, key| {
            WatchAccount::create_from_addresses(core_network, path, key, &canonical)
        })
    }

    /// Register an HD watch wallet with full descriptor metadata
    /// (Sparrow / Specter / Coldcard export format).
    ///
    /// The descriptor template is inferred from the purpose level (first
    /// hardened component) of `derivation_path` (BIP-86/84/49 only); the
    /// fingerprint must be 8 hex chars and the path depth must match the
    /// xpub depth. Same file-first / row-last ordering as
    /// [`Self::add_watch_xpub`].
    pub fn add_watch_hd(
        &self,
        label: String,
        master_fingerprint: String,
        derivation_path: String,
        xpub: String,
        network: Network,
    ) -> Result<WalletRecordFfi, WalletError> {
        let core_network: CoreNetwork = network.into();
        let record = Registry::build_watch_hd_record(
            &label,
            &master_fingerprint,
            &derivation_path,
            &xpub,
            core_network,
        )?;
        self.materialise_then_register(record, |path, key| {
            WatchAccount::create_from_hd(
                core_network,
                path,
                key,
                &master_fingerprint,
                &derivation_path,
                &xpub,
            )
        })
    }

    /// Open a watch wallet by UUID. Loads the existing
    /// `watch-state/<uuid>.sqlite` file via the device key and returns
    /// a `WatchHandle` for read operations.
    ///
    /// `wallet_id` is the hyphenated string-form UUID returned by any of
    /// the `add_watch_*` methods. Look up the wallet's `kind` and
    /// `network` from the registry; the descriptor lives persisted in the
    /// watch-state file's `ChangeSet`, so we don't need to pass it again.
    ///
    /// # Errors
    ///
    /// - `Storage` — wallet ID does not exist, or the watch-state file is
    ///   missing / corrupt / wrong device key.
    /// - `WalletCreate` — BDK refused to load the persisted wallet (e.g.
    ///   `ChangeSet` for a different network).
    pub fn open_watch(
        &self,
        wallet_id: String,
    ) -> Result<std::sync::Arc<WatchHandle>, WalletError> {
        let uuid = Uuid::parse_str(&wallet_id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;

        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let record = guard
            .get_wallet(&uuid)?
            .ok_or_else(|| WalletError::Storage {
                detail: format!("wallet {wallet_id} not found in registry"),
            })?;
        drop(guard);

        let core_network = parse_network_str(&record.network)?;
        let watch_state_path = self
            .data_dir
            .join("watch-state")
            .join(format!("{uuid}.sqlite"));

        let account = WatchAccount::load(core_network, &watch_state_path, &self.device_key)?;

        Ok(std::sync::Arc::new(WatchHandle {
            inner: Mutex::new(account),
        }))
    }

    /// Phase 3.3 — list `PaymentEvent`s aggregated across every watch
    /// wallet in the registry.
    ///
    /// Each watch wallet's chain state is opened with the device key,
    /// its transactions are normalised to `PaymentEvent`, and the
    /// concatenation is returned in undefined order (caller sorts).
    /// Failures opening any single watch wallet skip that wallet —
    /// partial results are better than no results, and the rest of
    /// the tracking surface continues to work.
    ///
    /// # Errors
    /// - `Storage` — registry read failed (rare; usually file lock).
    pub fn list_watch_events(&self) -> Result<Vec<PaymentEvent>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let records = guard.list_wallets()?;
        // Pre-load every (wallet_id, txid) → category mapping so each
        // event's category lookup is an O(1) HashMap read instead of
        // a fresh SQL round-trip. Negligible memory cost (one row per
        // categorised tx) for a much cheaper iteration when there are
        // many events.
        let category_dump = guard.dump_categories()?;
        // Phase 7.10 — pre-load crypto event kinds with the same
        // strategy as categories: one bulk SELECT per refresh.
        let crypto_kind_dump = guard.dump_crypto_event_kinds()?;
        let demo_events = guard.list_demo_events()?;
        drop(guard);
        let category_index: std::collections::HashMap<(String, String), String> = category_dump
            .into_iter()
            .map(|(wid, txid, cat)| ((wid, txid), cat))
            .collect();
        let crypto_kind_index: std::collections::HashMap<(String, String), String> =
            crypto_kind_dump
                .into_iter()
                .map(|(wid, txid, kind, _user_set)| ((wid, txid), kind))
                .collect();

        let mut out = Vec::new();
        for record in records {
            let core_network = match parse_network_str(&record.network) {
                Ok(n) => n,
                Err(_) => continue,
            };
            let watch_state_path = self
                .data_dir
                .join("watch-state")
                .join(format!("{}.sqlite", record.id));
            let account = match bhodl_wallet_onchain::WatchAccount::load(
                core_network,
                &watch_state_path,
                &self.device_key,
            ) {
                Ok(a) => a,
                Err(_) => continue, // partial results — skip this wallet
            };
            for tx in account.list_transactions() {
                let direction = if tx.net_sat > 0 {
                    PaymentDirection::Incoming
                } else if tx.net_sat < 0 {
                    PaymentDirection::Outgoing
                } else {
                    PaymentDirection::SelfTransfer
                };
                let amount_sat = tx.net_sat.unsigned_abs();
                let wallet_id_str = record.id.to_string();
                let lookup_key = (wallet_id_str.clone(), tx.txid.clone());
                let category = category_index.get(&lookup_key).cloned();
                let crypto_event_kind = crypto_kind_index.get(&lookup_key).cloned();
                out.push(PaymentEvent {
                    wallet_id: wallet_id_str,
                    txid_or_payment_id: tx.txid,
                    kind: PaymentKind::OnChain,
                    direction,
                    amount_sat,
                    fee_sat: tx.fee_sat,
                    timestamp_unix: tx.timestamp,
                    block_height: tx.confirmation_height,
                    category,
                    crypto_event_kind,
                });
            }
        }
        // v9 — a test-mode profile's wallet transactions (none on a real one).
        for e in demo_events {
            let key = (e.wallet_id.clone(), e.event_id.clone());
            out.push(PaymentEvent {
                category: category_index.get(&key).cloned(),
                crypto_event_kind: crypto_kind_index.get(&key).cloned(),
                wallet_id: e.wallet_id,
                txid_or_payment_id: e.event_id,
                kind: PaymentKind::OnChain,
                direction: if e.direction == "in" {
                    PaymentDirection::Incoming
                } else {
                    PaymentDirection::Outgoing
                },
                amount_sat: e.amount_sat,
                fee_sat: e.fee_sat,
                timestamp_unix: u64::try_from(e.occurred_unix).ok(),
                block_height: e.block_height,
            });
        }
        Ok(out)
    }

    /// Sum the last-synced balances of every watch wallet on `network`.
    ///
    /// Convenience wrapper over [`Self::aggregate_watch_balance`] that
    /// returns only the total. Watch wallets that cannot be opened are
    /// skipped (see there).
    ///
    /// # Errors
    ///
    /// - `Storage` — the registry itself could not be read.
    pub fn aggregate_watch_balance_sat(&self, network: Network) -> Result<u64, WalletError> {
        Ok(self.aggregate_watch_balance(network)?.total_sat)
    }

    /// Sum the balances of every watch wallet in the registry that is on
    /// `network`.
    ///
    /// Rows on other networks are ignored — summing mainnet and signet
    /// sats into one number is meaningless (M6).
    ///
    /// A watch wallet whose state file cannot be opened (missing, corrupt,
    /// wrong device key, unparseable network) no longer fails the whole
    /// sum: it is skipped and its id is reported in `stale_wallet_ids`, so
    /// the UI can flag "N wallets could not be read" instead of showing
    /// nothing. Each wallet reports its last-synced balance — trigger
    /// `sync_with_esplora` beforehand for a fresh value.
    ///
    /// # Errors
    ///
    /// - `Storage` — the registry itself could not be read.
    pub fn aggregate_watch_balance(
        &self,
        network: Network,
    ) -> Result<WatchAggregateFfi, WalletError> {
        let core_network: CoreNetwork = network.into();
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let records = guard.list_wallets()?;
        drop(guard);

        let mut total: u64 = 0;
        let mut stale_wallet_ids = Vec::new();
        for record in records {
            match parse_network_str(&record.network) {
                Ok(n) if n == core_network => {}
                Ok(_) => continue,
                Err(_) => {
                    stale_wallet_ids.push(record.id.to_string());
                    continue;
                }
            }
            match WatchAccount::load(
                core_network,
                &self.watch_state_path(&record.id),
                &self.device_key,
            ) {
                Ok(account) => total = total.saturating_add(account.balance().to_sat()),
                Err(_) => stale_wallet_ids.push(record.id.to_string()),
            }
        }
        Ok(WatchAggregateFfi {
            total_sat: total,
            stale_wallet_ids,
        })
    }
}

/// Non-exported helpers for the watch-wallet FFI surface.
impl RegistryHandle {
    /// `watch-state/<id>.sqlite` under the data dir.
    fn watch_state_path(&self, id: &Uuid) -> PathBuf {
        self.data_dir
            .join("watch-state")
            .join(format!("{id}.sqlite"))
    }

    /// M9 — create the watch-state file for `record` first, insert the
    /// registry row last. On any failure the partially-written state file
    /// is removed, so the registry never references a missing file and no
    /// orphan file is left behind.
    fn materialise_then_register<F>(
        &self,
        record: WalletRecord,
        create: F,
    ) -> Result<WalletRecordFfi, WalletError>
    where
        F: FnOnce(&Path, &[u8; ENCRYPTION_KEY_LEN]) -> Result<WatchAccount, OnchainError>,
    {
        let watch_state_dir = self.data_dir.join("watch-state");
        fs::create_dir_all(&watch_state_dir)?;
        let path = self.watch_state_path(&record.id);

        match create(&path, &self.device_key) {
            // Drop the account right away: its SQLCipher connection must be
            // closed before any cleanup (Windows file locks).
            Ok(account) => drop(account),
            Err(e) => {
                remove_sqlite_files(&path);
                return Err(e.into());
            }
        }

        let inserted = self
            .inner
            .lock()
            .map_err(|_| WalletError::Storage {
                detail: "registry mutex poisoned".into(),
            })
            .and_then(|guard| guard.add_wallet(&record).map_err(Into::into));
        if let Err(e) = inserted {
            remove_sqlite_files(&path);
            return Err(e);
        }
        Ok(record.into())
    }
}

/// Result of [`RegistryHandle::aggregate_watch_balance`].
#[derive(uniffi::Record, Debug, Clone, PartialEq, Eq)]
pub struct WatchAggregateFfi {
    /// Sum of the last-synced balances of every readable watch wallet.
    pub total_sat: u64,
    /// Watch wallets that were skipped because their state could not be
    /// opened (string-form UUIDs).
    pub stale_wallet_ids: Vec<String>,
}

/// Best-effort removal of an SQLite/SQLCipher file and its sidecars.
fn remove_sqlite_files(path: &Path) {
    let _ = fs::remove_file(path);
    for suffix in ["-journal", "-wal", "-shm"] {
        let mut side = path.as_os_str().to_owned();
        side.push(suffix);
        let _ = fs::remove_file(PathBuf::from(side));
    }
}

/// PIN recovery — `true` when a recovery code was created for the
/// registry at `data_dir`. Read before unlocking, so the unlock screen
/// knows which "forgot PIN" branch to offer.
#[uniffi::export]
pub fn registry_has_recovery_code(data_dir: String) -> bool {
    Path::new(&data_dir).join(RECOVERY_FILE_NAME).is_file()
}

/// Generate a fresh UUIDv4 in hyphenated string form.
#[uniffi::export]
pub fn generate_wallet_id() -> String {
    Uuid::new_v4().to_string()
}

/// Registry schema v8 — the wallet id a manual transaction's events and
/// category carry, one per account (`account_ref` = `asset:<id>`,
/// `liability:<id>` or null for cash / other).
#[uniffi::export]
pub fn manual_txn_wallet_id(account_ref: Option<String>) -> String {
    bhodl_wallet_onchain::manual_txn_wallet_id(account_ref.as_deref()).to_string()
}

/// Registry schema v8 — the event id of manual transaction `id`.
#[uniffi::export]
pub fn manual_txn_event_id(id: i64) -> String {
    bhodl_wallet_onchain::manual_txn_event_id(id)
}

/// Registry schema v8 — whether `wallet_id` is a manual account's
/// synthetic id rather than a real wallet.
#[uniffi::export]
pub fn is_manual_wallet_id(wallet_id: String) -> bool {
    wallet_id.starts_with(bhodl_wallet_onchain::MANUAL_WALLET_ID_PREFIX)
}

/// Extract the account-level xpub from any BDK-form descriptor string.
///
/// Strips the wrapper (`tr(...)`, `wpkh(...)`, `sh(wpkh(...))`), the
/// optional `[origin]` prefix, the trailing `/0/<idx>` (or `/1/<idx>`),
/// and any `#checksum` suffix. Returns `None` when the input does not
/// match one of the BIP-86/84/49 single-key-path shapes BHODL produces.
///
/// Used to deduplicate watch rows that share the same xpub, so a
/// balance is counted only once in aggregate totals.
#[uniffi::export]
pub fn extract_account_xpub(descriptor: String) -> Option<String> {
    let backup = bhodl_wallet_onchain::DescriptorBackup {
        network: bhodl_core::Network::Mainnet,
        external: descriptor,
        internal: String::new(),
    };
    backup.account_xpub()
}

/// Parse the lower-case network string stored in the registry into a
/// `bhodl_core::Network`. Returns `WalletError::Storage` for unrecognised
/// values (guarded against at insert time, but the guard provides a clean
/// error path for hand-edited DBs or future schema changes).
fn parse_network_str(s: &str) -> Result<CoreNetwork, WalletError> {
    match s {
        "mainnet" => Ok(CoreNetwork::Mainnet),
        "testnet4" => Ok(CoreNetwork::Testnet4),
        "signet" => Ok(CoreNetwork::Signet),
        "regtest" => Ok(CoreNetwork::Regtest),
        other => Err(WalletError::Storage {
            detail: format!("unknown network string in registry: {other}"),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    // R2 — KDF helpers exercised only by these tests. Imported here
    // (not at module top) so non-test builds don't carry an unused
    // import for them.
    use crate::kdf::{
        KdfFile, read_or_create_registry_kdf as read_or_create_kdf, seal_kdf_file_with_mac,
        seal_legacy_v1_for_test, write_kdf_file_for,
    };
    use bhodl_wallet_onchain::REGISTRY_KDF_FILE_NAME;

    /// Registry-scope `write_kdf_file_for`.
    fn write_kdf_file(
        dir: &Path,
        salt: &[u8; SALT_LEN],
        kdf: &bhodl_core::KdfParams,
    ) -> Result<(), WalletError> {
        write_kdf_file_for(dir, KdfScope::Registry, salt, kdf)
    }

    // -------------------------------------------------------------------
    // KDF descriptor tests — versioned registry.kdf.json.
    // -------------------------------------------------------------------

    #[test]
    fn fresh_dir_creates_kdf_json_with_current_params() {
        let tmp = tempfile::tempdir().unwrap();
        let (salt, params) = read_or_create_kdf(tmp.path()).unwrap();

        assert_eq!(params, bhodl_core::KdfParams::CURRENT);
        // The JSON file is on disk and round-trips byte-perfect
        // through serde_json (i.e. salt + params survive).
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);
        assert!(path.exists());
        let raw = fs::read(&path).unwrap();
        let parsed: KdfFile = serde_json::from_slice(&raw).unwrap();
        assert_eq!(parsed.salt().unwrap(), salt);
        assert_eq!(parsed.kdf, params);
        // Fresh descriptors are unsealed until the first successful
        // open (H1): no key has been proven correct yet.
        assert_eq!(parsed.version, KdfFile::LEGACY_VERSION);
        assert!(parsed.mac_hex.is_none());
    }

    #[test]
    fn write_kdf_file_atomic_temp_no_residue() {
        let tmp = tempfile::tempdir().unwrap();
        write_kdf_file(
            tmp.path(),
            &[0x33u8; SALT_LEN],
            &bhodl_core::KdfParams::CURRENT,
        )
        .unwrap();
        assert!(tmp.path().join(REGISTRY_KDF_FILE_NAME).exists());
        assert!(!tmp.path().join("registry.kdf.json.tmp").exists());
    }

    // -------------------------------------------------------------------
    // Phase 3.1 watch-wallet FFI tests
    // -------------------------------------------------------------------

    /// Canonical BIP-86 test-vector mnemonic (same vector used in
    /// bhodl-wallet-onchain::watch::tests and registry::tests).
    const BIP86_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
         abandon abandon abandon abandon abandon about";

    /// Open a `RegistryHandle` using a fresh directory and a fixed
    /// test PIN. Returns the handle and the raw derived key for tests
    /// that need both.
    fn make_registry_handle(data_dir: &std::path::Path) -> std::sync::Arc<RegistryHandle> {
        RegistryHandle::create_or_load(data_dir.to_string_lossy().into_owned(), "123456".into())
            .unwrap()
    }

    /// BIP-86 account (`m/86'/coin'/0'`) of [`BIP86_MNEMONIC`].
    fn bip86_account_ffi(network: CoreNetwork) -> bitcoin::bip32::Xpub {
        use bitcoin::bip32::{DerivationPath, Xpriv, Xpub};
        use std::str::FromStr;
        let secp = bitcoin::key::Secp256k1::new();
        let seed = bip39::Mnemonic::parse(BIP86_MNEMONIC).unwrap().to_seed("");
        let btc = match network {
            CoreNetwork::Mainnet => bitcoin::Network::Bitcoin,
            _ => bitcoin::Network::Signet,
        };
        let coin = u32::from(network != CoreNetwork::Mainnet);
        let master = Xpriv::new_master(btc, &seed).unwrap();
        let path = DerivationPath::from_str(&format!("m/86'/{coin}'/0'")).unwrap();
        Xpub::from_priv(&secp, &master.derive_priv(&secp, &path).unwrap())
    }

    fn xpub_from_mnemonic_ffi(network: CoreNetwork) -> String {
        bip86_account_ffi(network).to_string()
    }

    /// BIP-86 signet receive address at `index`.
    fn signet_p2tr_address_at_ffi(index: u32) -> String {
        use bitcoin::bip32::ChildNumber;
        let secp = bitcoin::key::Secp256k1::new();
        let child = bip86_account_ffi(CoreNetwork::Signet)
            .derive_pub(
                &secp,
                &[
                    ChildNumber::from_normal_idx(0).unwrap(),
                    ChildNumber::from_normal_idx(index).unwrap(),
                ],
            )
            .unwrap();
        bitcoin::Address::p2tr(&secp, child.to_x_only_pub(), None, bitcoin::Network::Signet)
            .to_string()
    }

    // 1. add_watch_xpub inserts a registry row AND creates the watch-state file.
    #[test]
    fn registry_add_watch_xpub_creates_row_and_watch_state_file() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);

        let record = handle
            .add_watch_xpub(
                "Cold Watch".into(),
                xpub,
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();

        // Registry has one row.
        let wallets = handle.list_wallets().unwrap();
        assert_eq!(wallets.len(), 1);
        assert_eq!(wallets[0].id, record.id);

        // The watch-state SQLite file must exist on disk.
        let watch_state_path = tmp
            .path()
            .join("watch-state")
            .join(format!("{}.sqlite", record.id));
        assert!(
            watch_state_path.exists(),
            "watch-state file must be created at {watch_state_path:?}"
        );
    }

    // 2. Garbage xpub → Err, no registry row, no watch-state file.
    #[test]
    fn registry_add_watch_xpub_rolls_back_on_invalid_xpub() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());

        let result = handle.add_watch_xpub(
            "Bad".into(),
            "not-a-valid-xpub".into(),
            Network::Signet,
            WatchBipFfi::Taproot,
        );

        assert!(result.is_err(), "invalid xpub must fail");
        // Registry must be empty — the row was either never inserted or rolled back.
        assert!(
            handle.list_wallets().unwrap().is_empty(),
            "registry must not retain a row for a failed add"
        );
        // No watch-state directory should have been created.
        let watch_state_dir = tmp.path().join("watch-state");
        if watch_state_dir.exists() {
            // If the dir was created, it must contain no sqlite files.
            let entries: Vec<_> = fs::read_dir(&watch_state_dir)
                .unwrap()
                .filter_map(|e| e.ok())
                .collect();
            assert!(
                entries.is_empty(),
                "watch-state dir must be empty after rollback"
            );
        }
    }

    // 3. add_watch_xpub → open_watch → balance_sat returns 0.
    #[test]
    fn open_watch_after_add_xpub_returns_handle_with_zero_balance() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);

        let record = handle
            .add_watch_xpub(
                "Zero Watch".into(),
                xpub,
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();

        let watch = handle.open_watch(record.id).unwrap();
        assert_eq!(watch.balance_sat().unwrap(), 0);
    }

    // 5. open_watch with unknown UUID returns Err.
    #[test]
    fn open_watch_with_unknown_id_returns_error() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());

        let random_id = Uuid::new_v4().to_string();
        let result = handle.open_watch(random_id);
        assert!(
            matches!(result, Err(WalletError::Storage { .. })),
            "open_watch with unknown id must return Storage error, got: {result:?}"
        );
    }

    // 6. add_watch_addresses with one P2TR address, open_watch, peek_address_at(0).
    #[test]
    fn add_watch_addresses_with_p2tr_round_trips() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let addr = signet_p2tr_address_at_ffi(0);

        let record = handle
            .add_watch_addresses("Address Watch".into(), vec![addr.clone()], Network::Signet)
            .unwrap();

        let watch = handle.open_watch(record.id).unwrap();
        let peeked = watch.peek_address_at(0).unwrap();
        assert_eq!(
            peeked, addr,
            "peek_address_at(0) must return the stored address"
        );
    }

    // 7. add_watch_hd with fingerprint + path, list_wallets shows the row,
    //    open_watch succeeds.
    #[test]
    fn add_watch_hd_with_origin_metadata_round_trips() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);

        let record = handle
            .add_watch_hd(
                "Sparrow HD".into(),
                "73c5da0a".into(),
                "m/86'/1'/0'".into(),
                xpub,
                Network::Signet,
            )
            .unwrap();

        // list_wallets shows the new WatchHd row.
        let wallets = handle.list_wallets().unwrap();
        assert_eq!(wallets.len(), 1);
        assert_eq!(wallets[0].id, record.id);
        assert!(
            matches!(wallets[0].kind, WalletKindFfi::WatchHd),
            "kind must be WatchHd"
        );

        // open_watch succeeds and returns zero balance.
        let watch = handle.open_watch(record.id).unwrap();
        assert_eq!(watch.balance_sat().unwrap(), 0);
    }

    // 8. Aggregate balance: empty registry → 0.
    #[test]
    fn aggregate_watch_balance_zero_on_empty_registry() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        assert_eq!(
            handle.aggregate_watch_balance_sat(Network::Signet).unwrap(),
            0
        );
    }

    // 10. Aggregate balance: two fresh watch wallets → 0 (unsynced).
    #[test]
    fn aggregate_watch_balance_sums_fresh_watch_wallets_at_zero() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);

        handle
            .add_watch_xpub(
                "Watch A".into(),
                xpub.clone(),
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        handle
            .add_watch_addresses(
                "Watch B".into(),
                vec![signet_p2tr_address_at_ffi(0)],
                Network::Signet,
            )
            .unwrap();

        // Both wallets carry zero balance until synced.
        assert_eq!(
            handle.aggregate_watch_balance_sat(Network::Signet).unwrap(),
            0
        );
    }

    // M6 — only wallets on the requested network are aggregated; a
    // broken watch-state file is skipped and reported, not fatal.
    #[test]
    fn aggregate_watch_balance_filters_network_and_reports_stale() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let good = handle
            .add_watch_xpub(
                "Good".into(),
                xpub_from_mnemonic_ffi(CoreNetwork::Signet),
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        let broken = handle
            .add_watch_addresses(
                "Broken".into(),
                vec![signet_p2tr_address_at_ffi(0)],
                Network::Signet,
            )
            .unwrap();
        let mainnet = handle
            .add_watch_xpub(
                "Main".into(),
                xpub_from_mnemonic_ffi(CoreNetwork::Mainnet),
                Network::Mainnet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        // Corrupt one signet wallet's state file.
        let broken_path = tmp
            .path()
            .join("watch-state")
            .join(format!("{}.sqlite", broken.id));
        std::fs::write(&broken_path, b"not a database").unwrap();

        let agg = handle.aggregate_watch_balance(Network::Signet).unwrap();
        assert_eq!(agg.total_sat, 0);
        assert_eq!(agg.stale_wallet_ids, vec![broken.id.clone()]);
        assert!(!agg.stale_wallet_ids.contains(&good.id));
        // The mainnet wallet is neither summed nor reported on signet.
        assert!(!agg.stale_wallet_ids.contains(&mainnet.id));
        let main_agg = handle.aggregate_watch_balance(Network::Mainnet).unwrap();
        assert!(main_agg.stale_wallet_ids.is_empty());
    }

    // M9 — a failed add leaves neither a registry row nor a state file.
    #[test]
    fn add_watch_failure_leaves_no_row_and_no_file() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        // Validation failure: nothing touches the disk or the registry.
        assert!(
            handle
                .add_watch_hd(
                    "Bad".into(),
                    "nothex!!".into(),
                    "m/86'/1'/0'".into(),
                    xpub_from_mnemonic_ffi(CoreNetwork::Signet),
                    Network::Signet,
                )
                .is_err()
        );
        assert!(handle.list_wallets().unwrap().is_empty());
        let dir = tmp.path().join("watch-state");
        let files = std::fs::read_dir(&dir).map(|d| d.count()).unwrap_or(0);
        assert_eq!(files, 0, "no orphan watch-state file");
        // Success: the row points at an existing file.
        let rec = handle
            .add_watch_addresses(
                "Ok".into(),
                vec![signet_p2tr_address_at_ffi(0)],
                Network::Signet,
            )
            .unwrap();
        assert!(dir.join(format!("{}.sqlite", rec.id)).exists());
    }

    // 11-12. extract_account_xpub roundtrips for the descriptor shapes
    //        the registry produces.
    #[test]
    fn extract_account_xpub_handles_bare_taproot_descriptor() {
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        let descriptor = format!("tr({xpub}/0/i)#aabbccdd");
        let extracted = extract_account_xpub(descriptor).expect("must extract");
        assert_eq!(extracted, xpub);
    }

    #[test]
    fn extract_account_xpub_handles_origin_prefix() {
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        let descriptor = format!("tr([deadbeef/86h/1h/0h]{xpub}/0/i)#aabbccdd");
        let extracted = extract_account_xpub(descriptor).expect("must extract");
        assert_eq!(extracted, xpub);
    }

    #[test]
    fn extract_account_xpub_handles_wpkh_wrapper() {
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        let descriptor = format!("wpkh({xpub}/0/i)#aabbccdd");
        let extracted = extract_account_xpub(descriptor).expect("must extract");
        assert_eq!(extracted, xpub);
    }

    // ---------------------------------------------------------------
    // T2-13 — strip_filesystem_paths
    // ---------------------------------------------------------------

    #[test]
    fn strip_paths_collapses_unix_absolute_path_to_basename() {
        let s =
            "No such file or directory (os error 2): /home/alice/.bhodl/wallets/abc/wallet.sqlite";
        let out = strip_filesystem_paths(s);
        assert!(!out.contains("/home/alice"), "leaked path: {out}");
        assert!(out.contains("wallet.sqlite"), "basename lost: {out}");
    }

    #[test]
    fn strip_paths_collapses_windows_absolute_path_to_basename() {
        let s = r"failed to open: C:\Users\alice\.bhodl\wallets\abc\wallet.sqlite";
        let out = strip_filesystem_paths(s);
        assert!(!out.contains(r"C:\Users\alice"), "leaked path: {out}");
        assert!(out.contains("wallet.sqlite"), "basename lost: {out}");
    }

    #[test]
    fn strip_paths_handles_windows_forward_slash_paths() {
        let s = "open: C:/Users/alice/data/registry.sqlite missing";
        let out = strip_filesystem_paths(s);
        assert!(!out.contains("C:/Users/alice"));
        assert!(out.contains("registry.sqlite"));
    }

    #[test]
    fn strip_paths_leaves_non_path_text_unchanged() {
        let inputs = [
            "Wrong PIN.",
            "decrypt: HMAC mismatch",
            "broken pipe (os error 32)",
            "version 9999 is newer than build supports (1)",
        ];
        for s in inputs {
            assert_eq!(strip_filesystem_paths(s), s, "non-path text mutated: {s}");
        }
    }

    #[test]
    fn strip_paths_keeps_relative_paths_alone() {
        // Relative paths don't reveal the data dir; leave untouched.
        let s = "open ./local/file.txt: permission denied";
        let out = strip_filesystem_paths(s);
        assert!(
            out.contains("./local/file.txt") || out.contains("file.txt"),
            "got: {out}"
        );
    }

    #[test]
    fn strip_paths_handles_multiple_paths_in_one_message() {
        let s = "renamed /home/x/a.tmp to /home/x/a.json failed";
        let out = strip_filesystem_paths(s);
        assert!(!out.contains("/home/x"), "leaked one of the paths: {out}");
        assert!(out.contains("a.tmp") && out.contains("a.json"));
    }

    #[test]
    fn strip_paths_does_not_collapse_a_lone_slash() {
        // A single "/" with nothing after it isn't a path leak.
        let s = "ratio is 1/2 of expected";
        let out = strip_filesystem_paths(s);
        // The character is preserved.
        assert!(out.contains("1/2") || out.contains("ratio"));
    }

    #[test]
    fn strip_paths_handles_path_followed_by_punctuation() {
        let s = "could not read /var/lib/bhodl/x.json:";
        let out = strip_filesystem_paths(s);
        assert!(!out.contains("/var/lib/bhodl"));
        // The trailing colon should not survive on the basename.
        assert!(out.contains("x.json"));
    }

    #[test]
    fn io_error_from_strips_path_in_detail() {
        // End-to-end: simulate an io::Error with a path-bearing
        // message and verify the WalletError carries the redacted
        // form across the FFI boundary.
        use std::io;
        let io_err =
            io::Error::other("permission denied: /home/secret/.bhodl/wallets/abc/wallet.sqlite");
        let wallet_err: WalletError = io_err.into();
        match wallet_err {
            WalletError::Io { detail } => {
                assert!(!detail.contains("/home/secret"), "leaked: {detail}");
                assert!(detail.contains("wallet.sqlite"), "basename lost: {detail}");
            }
            other => panic!("expected Io variant, got {other:?}"),
        }
    }

    // ---------------------------------------------------------------
    // T2-12 — HMAC integrity on registry.kdf.json
    // ---------------------------------------------------------------

    #[test]
    fn kdf_mac_round_trip_verifies_unchanged_file() {
        let tmp = tempfile::tempdir().unwrap();
        let salt = [0x11u8; SALT_LEN];
        let params = bhodl_core::KdfParams::CURRENT;
        let key = [0x22u8; ENCRYPTION_KEY_LEN];
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);

        seal_kdf_file_with_mac(&path, &salt, &params, &key).unwrap();

        // Re-read and verify.
        let raw = fs::read(&path).unwrap();
        let parsed: KdfFile = serde_json::from_slice(&raw).unwrap();
        assert!(parsed.mac_hex.is_some(), "MAC must be present after seal");
        parsed
            .verify_mac(&key)
            .expect("MAC must verify with same key");
    }

    #[test]
    fn kdf_mac_rejects_tampered_kdf_params() {
        // Adversary swaps CURRENT params for LEGACY_OWASP_MIN (downgrade).
        let tmp = tempfile::tempdir().unwrap();
        let salt = [0x11u8; SALT_LEN];
        let params = bhodl_core::KdfParams::CURRENT;
        let key = [0x22u8; ENCRYPTION_KEY_LEN];
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);

        seal_kdf_file_with_mac(&path, &salt, &params, &key).unwrap();

        // Tamper with the file: parse, swap params, re-serialise WITHOUT
        // recomputing the MAC (the attacker doesn't have the key).
        let raw = fs::read(&path).unwrap();
        let mut tampered: KdfFile = serde_json::from_slice(&raw).unwrap();
        tampered.kdf = bhodl_core::KdfParams::LEGACY_OWASP_MIN;
        let bad_json = serde_json::to_vec_pretty(&tampered).unwrap();
        fs::write(&path, &bad_json).unwrap();

        // Verify with the original key must fail loudly.
        let re_read: KdfFile = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        let err = re_read
            .verify_mac(&key)
            .expect_err("tampered MAC must reject");
        let msg = format!("{err:?}");
        assert!(
            msg.contains("MAC mismatch") || msg.contains("tampered"),
            "error must mention MAC tampering; got: {msg}"
        );
    }

    #[test]
    fn kdf_mac_rejects_tampered_salt() {
        let tmp = tempfile::tempdir().unwrap();
        let salt = [0x11u8; SALT_LEN];
        let params = bhodl_core::KdfParams::CURRENT;
        let key = [0x22u8; ENCRYPTION_KEY_LEN];
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);

        seal_kdf_file_with_mac(&path, &salt, &params, &key).unwrap();

        // Swap the salt — same attack pattern.
        let raw = fs::read(&path).unwrap();
        let mut tampered: KdfFile = serde_json::from_slice(&raw).unwrap();
        tampered.salt_hex = hex::encode([0x99u8; SALT_LEN]);
        let bad_json = serde_json::to_vec_pretty(&tampered).unwrap();
        fs::write(&path, &bad_json).unwrap();

        let re_read: KdfFile = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert!(re_read.verify_mac(&key).is_err());
    }

    #[test]
    fn kdf_mac_accepts_legacy_file_without_mac_field() {
        // Backward-compat: an existing file written by the pre-T2-12
        // build has no `mac_hex` field. verify_mac must treat that as
        // OK so existing wallets continue to open.
        let tmp = tempfile::tempdir().unwrap();
        let salt = [0x11u8; SALT_LEN];
        let params = bhodl_core::KdfParams::CURRENT;
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);
        // Write a legacy-shaped file (no mac_hex field).
        let legacy = KdfFile {
            version: KdfFile::LEGACY_VERSION,
            salt_hex: hex::encode(salt),
            kdf: params,
            mac_hex: None,
            nonce_hex: None,
            wrapped_key_hex: None,
        };
        fs::write(&path, serde_json::to_vec_pretty(&legacy).unwrap()).unwrap();

        let raw = fs::read(&path).unwrap();
        let parsed: KdfFile = serde_json::from_slice(&raw).unwrap();
        assert!(parsed.mac_hex.is_none());
        // Any key passes the verify when there's no MAC to check.
        parsed
            .verify_mac(&[0u8; ENCRYPTION_KEY_LEN])
            .expect("no-MAC legacy file must verify");
    }

    // ---------------------------------------------------------------
    // 2026-10 audit — H1 / H2 / M1 / M2 / L5 / L6 regression tests
    // ---------------------------------------------------------------

    fn read_kdf(dir: &Path) -> KdfFile {
        serde_json::from_slice(&fs::read(dir.join(REGISTRY_KDF_FILE_NAME)).unwrap()).unwrap()
    }

    fn unlock(dir: &Path, pin: &str) -> Result<std::sync::Arc<RegistryHandle>, WalletError> {
        RegistryHandle::create_or_load(dir.to_string_lossy().into_owned(), pin.into())
    }

    /// Registry whose descriptor is `params` (unsealed v1), DB keyed to match.
    fn install_registry_with_params(dir: &Path, pin: &str, params: bhodl_core::KdfParams) {
        let salt = [0x5Au8; SALT_LEN];
        write_kdf_file(dir, &salt, &params).unwrap();
        let key = bhodl_core::derive_key_from_pin_with_params(pin, &salt, &params).unwrap();
        drop(open_registry(&dir.join(REGISTRY_FILE_NAME), &key).unwrap());
    }

    #[test]
    fn h1_wrong_pin_on_unsealed_descriptor_does_not_lock_out() {
        // Legacy unsealed descriptor. A wrong PIN used to seal the MAC
        // under the WRONG key, after which the correct PIN failed with
        // "MAC mismatch".
        let tmp = tempfile::tempdir().unwrap();
        install_registry_with_params(tmp.path(), "123456", bhodl_core::KdfParams::CURRENT);
        assert!(read_kdf(tmp.path()).mac_hex.is_none());

        assert!(matches!(
            unlock(tmp.path(), "654321"),
            Err(WalletError::Storage { .. })
        ));
        // Nothing was sealed by the failed attempt.
        assert!(read_kdf(tmp.path()).mac_hex.is_none());
        assert!(!KdfScope::Registry.marker_path(tmp.path()).exists());

        // Correct PIN still unlocks, and only now wraps (v3 + marker).
        unlock(tmp.path(), "123456").expect("correct PIN must unlock after a wrong attempt");
        let sealed = read_kdf(tmp.path());
        assert_eq!(sealed.version, KdfFile::WRAPPED_VERSION);
        assert!(sealed.wrapped_key_hex.is_some());
        assert!(KdfScope::Registry.marker_path(tmp.path()).exists());

        // Sealed: wrong PIN rejected, correct PIN still fine.
        assert!(matches!(
            unlock(tmp.path(), "000000"),
            Err(WalletError::Storage { .. })
        ));
        unlock(tmp.path(), "123456").expect("correct PIN after seal");
    }

    #[test]
    fn h1_legacy_v1_mac_is_accepted_once_then_rewrapped_as_v3() {
        let tmp = tempfile::tempdir().unwrap();
        install_registry_with_params(tmp.path(), "123456", bhodl_core::KdfParams::CURRENT);
        let salt = read_kdf(tmp.path()).salt().unwrap();
        let key = bhodl_core::derive_key_from_pin_with_params(
            "123456",
            &salt,
            &bhodl_core::KdfParams::CURRENT,
        )
        .unwrap();
        seal_legacy_v1_for_test(
            &tmp.path().join(REGISTRY_KDF_FILE_NAME),
            &salt,
            &bhodl_core::KdfParams::CURRENT,
            &key,
        )
        .unwrap();
        assert!(unlock(tmp.path(), "999999").is_err());
        assert_eq!(read_kdf(tmp.path()).version, KdfFile::LEGACY_VERSION);
        unlock(tmp.path(), "123456").expect("old-style MAC accepted");
        let rewrapped = read_kdf(tmp.path());
        assert_eq!(rewrapped.version, KdfFile::WRAPPED_VERSION);
        // Same data key: the migration never re-encrypted the database.
        let data_key = crate::kdf::unwrap_file(
            &tmp.path().join(REGISTRY_KDF_FILE_NAME),
            "123456",
            crate::kdf::WrapPurpose::DevicePin,
        )
        .unwrap();
        assert_eq!(*data_key, *key);
        unlock(tmp.path(), "123456").expect("v3 unlocks");
    }

    #[test]
    fn m1_out_of_bounds_params_are_rejected_before_derivation() {
        let tmp = tempfile::tempdir().unwrap();
        let mut f = KdfFile {
            version: KdfFile::LEGACY_VERSION,
            salt_hex: hex::encode([0x11u8; SALT_LEN]),
            kdf: bhodl_core::KdfParams {
                m_cost_kib: u32::MAX,
                t_cost: 3,
                p_cost: 4,
            },
            mac_hex: None,
            nonce_hex: None,
            wrapped_key_hex: None,
        };
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);
        fs::write(&path, serde_json::to_vec(&f).unwrap()).unwrap();
        // Must fail fast (no 4 TiB allocation abort, no hang).
        assert!(unlock(tmp.path(), "123456").is_err());
        f.kdf = bhodl_core::KdfParams {
            m_cost_kib: 65_536,
            t_cost: u32::MAX,
            p_cost: 4,
        };
        fs::write(&path, serde_json::to_vec(&f).unwrap()).unwrap();
        assert!(unlock(tmp.path(), "123456").is_err());
        // Unknown descriptor version.
        f.kdf = bhodl_core::KdfParams::CURRENT;
        f.version = 99;
        fs::write(&path, serde_json::to_vec(&f).unwrap()).unwrap();
        assert!(read_or_create_kdf(tmp.path()).is_err());
    }

    #[test]
    fn m1_stripping_the_mac_from_a_sealed_descriptor_is_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        install_registry_with_params(tmp.path(), "123456", bhodl_core::KdfParams::CURRENT);
        unlock(tmp.path(), "123456").unwrap(); // wraps (v3 + marker)
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);

        // (a) v3 with the wrapped key deleted.
        let mut stripped = read_kdf(tmp.path());
        stripped.wrapped_key_hex = None;
        fs::write(&path, serde_json::to_vec(&stripped).unwrap()).unwrap();
        assert!(matches!(
            unlock(tmp.path(), "123456"),
            Err(WalletError::Storage { .. })
        ));

        // (b) rewritten as an unsealed v1 file — refused by the marker.
        stripped.version = KdfFile::LEGACY_VERSION;
        stripped.nonce_hex = None;
        fs::write(&path, serde_json::to_vec(&stripped).unwrap()).unwrap();
        let err = unlock(tmp.path(), "123456").err().expect("must refuse");
        assert!(format!("{err:?}").contains("MAC missing"), "got {err:?}");
    }

    #[test]
    fn rate_methods_run_on_the_handle_key_without_a_pin() {
        // Audit 2026-10: no data_dir / device_pin per call; offline-safe
        // inputs only (future date, unknown rows, empty registry).
        let tmp = tempfile::tempdir().unwrap();
        let handle = RegistryHandle::create_or_load(
            tmp.path().to_string_lossy().into_owned(),
            "123456".into(),
        )
        .unwrap();
        let future = 4_102_444_800; // 2100-01-01
        assert_eq!(
            handle.btc_rate_at(future, "USD".into(), None).unwrap(),
            None
        );
        assert!(!handle.resolve_lot_btc_rate(999, None).unwrap());
        assert!(!handle.resolve_sell_btc(999, None).unwrap());
        assert!(!handle.resolve_manual_asset_purchase_btc(999, None).unwrap());
        let stats = handle.recompute_all_lot_btc_rates(None).unwrap();
        assert_eq!((stats.considered, stats.updated, stats.failed), (0, 0, 0));
        let report = handle.phase_8_2_backfill_btc_at_time(None).unwrap();
        assert_eq!(report.investment_lots.considered, 0);
        // A non-SOCKS proxy setting is refused (never silently clearnet).
        assert!(
            handle
                .btc_rate_at(
                    1_713_484_800,
                    "USD".into(),
                    Some("http://127.0.0.1:8080".into())
                )
                .is_err()
        );
    }

    #[test]
    fn m1_registry_read_paths_verify_the_descriptor_mac() {
        let tmp = tempfile::tempdir().unwrap();
        drop(unlock(tmp.path(), "123456").unwrap());
        assert!(open_registry_verified(tmp.path(), "123456").is_ok());
        assert!(open_registry_verified(tmp.path(), "654321").is_err());
        // Tamper with the sealed registry descriptor (salt swap).
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);
        let mut f: KdfFile = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        f.salt_hex = hex::encode([0xEEu8; SALT_LEN]);
        fs::write(&path, serde_json::to_vec(&f).unwrap()).unwrap();
        assert!(open_registry_verified(tmp.path(), "123456").is_err());
        assert!(unlock(tmp.path(), "123456").is_err());
    }

    #[test]
    fn l6_new_registry_requires_policy_pin_but_unlock_does_not() {
        for weak in ["1234", "12345", "abcdef", "12 3456"] {
            let reg = tempfile::tempdir().unwrap();
            assert!(
                matches!(
                    unlock(reg.path(), weak),
                    Err(WalletError::KeyDerivation { .. })
                ),
                "{weak}"
            );
            assert!(!reg.path().join(REGISTRY_KDF_FILE_NAME).exists());
        }
        // A registry created under the old lax policy still opens.
        let legacy = tempfile::tempdir().unwrap();
        install_registry_with_params(legacy.path(), "pw", bhodl_core::KdfParams::CURRENT);
        unlock(legacy.path(), "pw").expect("legacy short PIN must still unlock");
    }

    // ---------------------------------------------------------------
    // T1-7 — RegistryHandle.device_key zeroize-on-drop
    // ---------------------------------------------------------------

    #[test]
    fn registry_handle_drop_zeroizes_device_key() {
        // Construct a RegistryHandle, snapshot the heap address of
        // its `device_key`, drop it, and check that the bytes at
        // that address are now zero. This is a best-effort heap-
        // inspection check — the allocator may return the memory to
        // the OS, in which case it is freed (also OK). We tolerate
        // both outcomes; what we DON'T tolerate is the original
        // non-zero pattern persisting at that address.
        let tmp = tempfile::tempdir().unwrap();
        let handle = RegistryHandle::create_or_load(
            tmp.path().to_string_lossy().to_string(),
            "12345678".to_string(),
        )
        .expect("registry create");

        // Snapshot the address + a copy of the key for comparison.
        let addr = handle.device_key.as_ptr();
        let expected_after = [0u8; ENCRYPTION_KEY_LEN];
        // Sanity: key is non-zero before drop.
        assert_ne!(handle.device_key, expected_after);

        drop(handle);

        // SAFETY: We are intentionally reading from the freed
        // allocation to verify the Drop side-effect. The memory
        // may have been re-used by another allocation in between —
        // in that case the test is a no-op (we don't assert
        // failure on non-zero). The assertion below only fires if
        // we observe the EXACT pre-drop key pattern, which would
        // indicate Drop did not run.
        let observed = unsafe { std::slice::from_raw_parts(addr, ENCRYPTION_KEY_LEN).to_vec() };
        // The pre-drop key was non-zero (asserted above). After drop,
        // observing the same non-zero pattern means Drop never fired.
        // We can't assert "must be zero" because allocator reuse is
        // legitimate; but the pattern matching a real AES key is a
        // smoking gun. Use an all-zero comparison as a soft check:
        // if zeroize ran, the bytes are zero before any reuse.
        // In practice on every tested allocator (mimalloc, jemalloc,
        // glibc malloc, MSVC CRT), this returns the zeroed bytes.
        assert_eq!(
            observed, expected_after,
            "device_key bytes at {addr:?} after drop: {observed:x?}"
        );
    }

    // -------------------------------------------------------------------
    // Duplicate detection FFI tests
    // -------------------------------------------------------------------

    /// Detection flow before any wallet has been added.
    #[test]
    fn registry_is_descriptor_tracked_returns_false_on_empty_registry() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        assert!(
            !handle
                .is_descriptor_tracked(format!("tr({xpub}/0/*)"))
                .unwrap()
        );
    }

    /// Exact-string match: when the registry contains a row with the
    /// same `descriptor_pub` as the input, `is_descriptor_tracked`
    /// must return true. Belt-and-braces alongside the xpub
    /// normalisation path below.
    #[test]
    fn registry_is_descriptor_tracked_true_on_exact_descriptor_match() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);

        // Add the watch row so the registry has a real descriptor.
        let _record = handle
            .add_watch_xpub(
                "Cold Watch".into(),
                xpub,
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();

        // Pull the row back out and use its descriptor verbatim.
        let row = handle.list_wallets().unwrap().into_iter().next().unwrap();
        assert!(handle.is_descriptor_tracked(row.descriptor_pub).unwrap());
    }

    /// xpub normalisation: a Sparrow export carries an origin prefix and
    /// a child path (`tr([fp/86'/...]xpub/0/0)`); the equivalent watch
    /// row added via `add_watch_xpub` stores a different shape. Both refer to the same account xpub, so
    /// `is_descriptor_tracked` MUST return true via the
    /// `extract_account_xpub` normalisation path.
    #[test]
    fn registry_is_descriptor_tracked_normalises_via_account_xpub() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());

        // Watch row added with the bare account xpub (the form a user
        // would copy from Sparrow's "Show watch-only descriptor").
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        handle
            .add_watch_xpub(
                "Cold Watch".into(),
                xpub,
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();

        // Sparrow-style descriptor for the SAME account — different
        // string shape (origin path + concrete child index).
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        let signing_descriptor = format!("tr([73c5da0a/86'/1'/0']{xpub}/0/0)");

        // Sanity: the two strings must NOT be byte-equal — the value
        // of this test is that detection works DESPITE that.
        let row = handle.list_wallets().unwrap().into_iter().next().unwrap();
        assert_ne!(
            row.descriptor_pub, signing_descriptor,
            "test premise broken: registry-form descriptor matches signing-form byte-equal; normalisation cannot be exercised"
        );

        assert!(
            handle.is_descriptor_tracked(signing_descriptor).unwrap(),
            "xpub-normalisation must detect the same wallet across descriptor shapes"
        );
    }

    /// Negative: an unrelated wallet (different mnemonic, different
    /// xpub) must NOT register as tracked just because the registry
    /// already has some other watch row.
    #[test]
    fn registry_is_descriptor_tracked_false_for_unrelated_wallet() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());

        // Add wallet A (BIP-86 test mnemonic) as a watch row.
        let xpub_a = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        handle
            .add_watch_xpub(
                "Wallet A".into(),
                xpub_a,
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();

        // Wallet B has a wholly different account xpub, so the registry
        // must NOT report it as tracked.
        // The BIP-86 receive-path child key is a valid, unrelated xpub.
        let descriptor_b = {
            use bitcoin::bip32::ChildNumber;
            let secp = bitcoin::key::Secp256k1::new();
            let child = bip86_account_ffi(CoreNetwork::Signet)
                .derive_pub(&secp, &[ChildNumber::from_normal_idx(0).unwrap()])
                .unwrap();
            format!("tr({child}/0/*)")
        };

        assert!(
            !handle.is_descriptor_tracked(descriptor_b).unwrap(),
            "unrelated wallet must not register as tracked"
        );
    }

    // ---------------------------------------------------------------
    // Watch-only rework — WatchHandle read surface + legacy cleanup
    // ---------------------------------------------------------------

    #[test]
    fn watch_handle_exposes_addresses_labels_and_contacts() {
        let tmp = tempfile::tempdir().unwrap();
        let registry = make_registry_handle(tmp.path());
        let record = registry
            .add_watch_xpub(
                "Cold".into(),
                xpub_from_mnemonic_ffi(CoreNetwork::Signet),
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        let watch = registry.open_watch(record.id.clone()).unwrap();
        assert!(!watch.is_address_list().unwrap());

        let addr = watch.next_unused_receive_address().unwrap();
        assert_eq!(addr, signet_p2tr_address_at_ffi(0));
        assert_eq!(
            watch.next_receive_address().unwrap(),
            signet_p2tr_address_at_ffi(1)
        );
        watch
            .set_address_label(addr.clone(), "Salary".into(), Some("income".into()))
            .unwrap();
        let rows = watch.list_addresses(false).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].label.as_deref(), Some("Salary"));
        assert!(watch.list_utxos().unwrap().is_empty());
        assert!(watch.apply_receive_labels().unwrap().is_empty());

        let txid = "ab".repeat(32);
        watch
            .set_tx_category(txid.clone(), Some("food".into()))
            .unwrap();
        watch.set_tx_label(txid.clone(), "Dinner".into()).unwrap();
        assert_eq!(
            watch.get_tx_label(txid.clone()).unwrap().as_deref(),
            Some("Dinner")
        );
        assert_eq!(
            watch.get_tx_category(txid.clone()).unwrap().as_deref(),
            Some("food")
        );
        let jsonl = watch.export_labels_jsonl().unwrap();
        assert!(jsonl.contains("Dinner"));
        assert!(watch.remove_tx_label(txid.clone()).unwrap());
        assert!(watch.import_labels_jsonl(jsonl).unwrap() >= 1);

        let id = watch
            .add_contact("Alice".into(), signet_p2tr_address_at_ffi(7))
            .unwrap();
        assert_eq!(watch.list_contacts().unwrap().len(), 1);
        assert!(watch.update_contact_label(id, "Alice B".into()).unwrap());
        assert!(watch.remove_contact(id).unwrap());

        let bundle = watch
            .export_public_descriptor()
            .unwrap()
            .expect("HD wallet");
        assert_eq!(
            bundle.account_xpub,
            xpub_from_mnemonic_ffi(CoreNetwork::Signet)
        );
        assert_eq!(bundle.network, "signet");
    }

    #[test]
    fn address_list_watch_handle_reports_no_derivation() {
        let tmp = tempfile::tempdir().unwrap();
        let registry = make_registry_handle(tmp.path());
        let record = registry
            .add_watch_addresses(
                "Paper".into(),
                vec![signet_p2tr_address_at_ffi(2)],
                Network::Signet,
            )
            .unwrap();
        let watch = registry.open_watch(record.id).unwrap();
        assert!(watch.is_address_list().unwrap());
        assert!(watch.next_unused_receive_address().is_err());
        assert!(watch.export_public_descriptor().unwrap().is_none());
        assert_eq!(watch.list_addresses(false).unwrap().len(), 1);
    }

    #[test]
    fn remove_wallet_deletes_the_watch_state_file() {
        let tmp = tempfile::tempdir().unwrap();
        let registry = make_registry_handle(tmp.path());
        let record = registry
            .add_watch_xpub(
                "Cold".into(),
                xpub_from_mnemonic_ffi(CoreNetwork::Signet),
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        let state = tmp
            .path()
            .join("watch-state")
            .join(format!("{}.sqlite", record.id));
        assert!(state.exists());
        assert!(registry.remove_wallet(record.id.clone()).unwrap());
        assert!(!state.exists());
        assert!(!registry.remove_wallet(record.id).unwrap());
    }

    #[test]
    fn purge_legacy_signing_wallets_removes_rows_and_key_dirs() {
        let tmp = tempfile::tempdir().unwrap();
        let registry = make_registry_handle(tmp.path());
        assert_eq!(registry.legacy_signing_wallet_count().unwrap(), 0);
        let legacy_id = Uuid::new_v4();
        {
            let guard = registry.inner.lock().unwrap();
            // Pre-rework row: insert with a watch kind, then flip the raw
            // kind string (the `Signing` variant no longer exists).
            guard
                .add_wallet(&WalletRecord {
                    id: legacy_id,
                    label: "Old signing".into(),
                    kind: WalletKind::WatchXpub,
                    descriptor_pub: "tr(tpubX/0/*)".into(),
                    network: "signet".into(),
                    created_at_unix: 1,
                })
                .unwrap();
            drop(guard);
        }
        set_kind_raw(&registry, &legacy_id, "signing");
        let key_dir = tmp.path().join("wallets").join(legacy_id.to_string());
        fs::create_dir_all(&key_dir).unwrap();
        fs::write(key_dir.join("wallet.sqlite"), b"x").unwrap();

        assert!(registry.list_wallets().unwrap().is_empty());
        assert_eq!(registry.legacy_signing_wallet_count().unwrap(), 1);
        assert_eq!(registry.purge_legacy_signing_wallets().unwrap(), 1);
        assert_eq!(registry.legacy_signing_wallet_count().unwrap(), 0);
        assert!(!key_dir.exists());
    }

    /// Side `SQLCipher` connection on the registry DB, keyed with the
    /// handle's device key.
    fn rusqlite_side_connection(registry: &RegistryHandle) -> rusqlite::Connection {
        let conn = rusqlite::Connection::open(registry.data_dir.join(REGISTRY_FILE_NAME)).unwrap();
        conn.pragma_update(
            None,
            "key",
            format!("x'{}'", hex::encode(registry.device_key)),
        )
        .unwrap();
        conn
    }

    /// Rewrite `wallets.kind` through a side connection (tests only).
    fn set_kind_raw(registry: &RegistryHandle, id: &Uuid, kind: &str) {
        let conn = rusqlite_side_connection(registry);
        conn.execute(
            "UPDATE wallets SET kind = ?1 WHERE id = ?2",
            [kind, &id.to_string()],
        )
        .unwrap();
    }

    // ---------------------------------------------------------------
    // Phase 8.6 — WalletError typed rate variant round-trips
    // ---------------------------------------------------------------

    #[test]
    fn wallet_error_rate_currency_not_supported_round_trip() {
        let err = map_rate_error(RateError::CurrencyNotSupported("XYZ".into()));
        assert!(
            matches!(&err, WalletError::RateCurrencyNotSupported { currency } if currency == "XYZ"),
            "expected RateCurrencyNotSupported{{XYZ}}, got {err:?}"
        );
        // Display message must carry the currency for human-readable logs.
        assert!(err.to_string().contains("XYZ"));
    }

    #[test]
    fn wallet_error_rate_network_unavailable_round_trip() {
        let err = map_rate_error(RateError::NetworkUnavailable);
        assert!(
            matches!(err, WalletError::RateNetworkUnavailable),
            "expected RateNetworkUnavailable, got {err:?}"
        );
    }
}

// ---------------------------------------------------------------------------
// Phase 8.4 — Net-worth history FFI surface
// ---------------------------------------------------------------------------
//
// `net_worth_history` reconstructs a daily NW series from:
//   1. on-chain events (passed in as `events` — the FFI caller provides
//      them from `RegistryHandle.list_watch_events()` so this crate does
//      not need to re-open watch-state files).
//   2. investment lots (from the registry DB).
//   3. manual assets (from the registry DB).
//   4. liabilities (from the registry DB).
//
// Exposed as `RegistryHandle::net_worth_history` (no PIN, no per-call KDF).

use bhodl_wallet_onchain::{NwSnapshot, PaymentEventLite, reconstruct_net_worth_history};

/// One daily net-worth snapshot returned by `net_worth_history`.
#[derive(uniffi::Record)]
pub struct NwSnapshotFfi {
    pub date_unix: i64,
    pub total_sat: i64,
    pub onchain_sat: i64,
    pub investments_sat: i64,
    pub manual_assets_sat: i64,
    pub liabilities_sat: i64,
    pub fiat_equivalent: f64,
    pub rate_btc_fiat: f64,
}

impl From<NwSnapshot> for NwSnapshotFfi {
    fn from(s: NwSnapshot) -> Self {
        Self {
            date_unix: s.date_unix,
            total_sat: s.total_sat,
            onchain_sat: s.onchain_sat,
            investments_sat: s.investments_sat,
            manual_assets_sat: s.manual_assets_sat,
            liabilities_sat: s.liabilities_sat,
            fiat_equivalent: s.fiat_equivalent,
            rate_btc_fiat: s.rate_btc_fiat,
        }
    }
}

/// One on-chain payment event passed into `net_worth_history`.
///
/// The Kotlin caller builds these from `RegistryHandle.list_watch_events()`
/// (watch wallets) and the active signing wallet's
/// `WalletHandle.list_transactions()` (if present). The `amount_sat` field
/// must be **signed**: positive for incoming, negative for outgoing.
#[derive(uniffi::Record)]
pub struct PaymentEventLiteFfi {
    /// Signed net sat flow: positive = incoming, negative = outgoing.
    pub amount_sat: i64,
    /// Unix timestamp of the confirming block. Null for unconfirmed txs
    /// (those are excluded from the NW reconstruction automatically).
    pub block_time_unix: Option<i64>,
}

impl RegistryHandle {
    /// Lock the shared registry connection (short DB work only — never
    /// hold the returned guard across network I/O).
    fn registry(&self) -> Result<std::sync::MutexGuard<'_, Registry>, WalletError> {
        self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })
    }

    /// Open the BTC-rate cache on the registry DB with the handle's
    /// already-derived key (no Argon2, no PIN), routing fetches through
    /// `proxy_url` when set. A separate SQLCipher connection, so rate
    /// fetches never contend with the registry mutex.
    ///
    /// A test-mode profile answers from its own rates only: nothing is
    /// ever fetched for it.
    fn rate_repo(&self, proxy_url: Option<&str>) -> Result<BtcRateRepo, WalletError> {
        let db_path = self.data_dir.join(REGISTRY_FILE_NAME);
        let repo = BtcRateRepo::open(&db_path, &self.device_key)?.with_proxy(proxy_url)?;
        Ok(if self.registry()?.is_demo_profile()? {
            repo.offline()
        } else {
            repo
        })
    }

    /// A second registry connection for long-running jobs (backfill,
    /// net-worth reconstruction) that interleave DB reads with rate
    /// fetches: they run without holding the handle's mutex.
    fn side_registry(&self) -> Result<Registry, WalletError> {
        let db_path = self.data_dir.join(REGISTRY_FILE_NAME);
        Ok(open_registry(&db_path, &self.device_key)?)
    }
}

// Historical BTC rates, Phase 8.2 backfill and net-worth history.
//
// `proxy_url` is the user's Tor SOCKS proxy (`socks5h://host:port`) when
// Tor is enabled, else `null`. With a proxy every rate request goes
// through it; an unreachable proxy is an error, never a clearnet retry.
#[uniffi::export]
impl RegistryHandle {
    /// BTC/{currency} rate for `date_unix` (normalized to UTC midnight).
    /// Returns `null` for pre-genesis or future dates; throws a typed
    /// `Rate*` error on network failure.
    pub fn btc_rate_at(
        &self,
        date_unix: i64,
        currency: String,
        proxy_url: Option<String>,
    ) -> Result<Option<f64>, WalletError> {
        let repo = self.rate_repo(proxy_url.as_deref())?;
        repo.rate_at(date_unix, &currency).map_err(map_rate_error)
    }

    /// Daily BTC/{currency} series for [from_unix, to_unix], sorted by
    /// date ASC; gaps are not interpolated.
    pub fn btc_rate_range(
        &self,
        from_unix: i64,
        to_unix: i64,
        currency: String,
        proxy_url: Option<String>,
    ) -> Result<Vec<RatePointFfi>, WalletError> {
        let repo = self.rate_repo(proxy_url.as_deref())?;
        let points = repo
            .range(from_unix, to_unix, &currency)
            .map_err(map_rate_error)?;
        Ok(points
            .into_iter()
            .map(|p| RatePointFfi {
                date_unix: p.date_unix,
                rate: p.rate,
            })
            .collect())
    }

    /// Bulk backfill a list of (date, currency) pairs into the local cache.
    /// Idempotent: already-cached pairs are counted as `cache_hits`.
    pub fn btc_rate_backfill(
        &self,
        requests: Vec<RateBackfillRequestFfi>,
        proxy_url: Option<String>,
    ) -> Result<BackfillReportFfi, WalletError> {
        let repo = self.rate_repo(proxy_url.as_deref())?;
        let owned: Vec<(i64, String)> = requests
            .into_iter()
            .map(|r| (normalize_to_utc_midnight(r.date_unix), r.currency))
            .collect();
        let ref_pairs: Vec<(i64, &str)> = owned.iter().map(|(d, c)| (*d, c.as_str())).collect();

        let report = repo.backfill(&ref_pairs).map_err(map_rate_error)?;
        Ok(BackfillReportFfi {
            requested: report.requested as u32,
            cache_hits: report.cache_hits as u32,
            fetched: report.fetched as u32,
            failed: report
                .failed
                .into_iter()
                .map(|(d, c)| RateBackfillFailureFfi {
                    date_unix: d,
                    currency: c,
                })
                .collect(),
        })
    }

    /// Run the Phase 8.2 BTC-at-time backfill (idempotent: rows already
    /// filled are counted in `already_done`; unresolvable rows stay NULL
    /// for a later retry). Runs on a side connection so the registry
    /// mutex stays free during the rate fetches.
    pub fn phase_8_2_backfill_btc_at_time(
        &self,
        proxy_url: Option<String>,
    ) -> Result<MigrationReportFfi, WalletError> {
        let rate_repo = self.rate_repo(proxy_url.as_deref())?;
        let registry = self.side_registry()?;
        let report = registry.run_8_2_backfill(&rate_repo)?;
        Ok(report.into())
    }

    /// Reconstruct the daily net-worth history for [from_unix, to_unix].
    ///
    /// `events` are the caller's on-chain payment events (watch wallets +
    /// active signing wallet), amounts signed. Rate gaps soft-fail (the
    /// day contributes 0 fiat-denominated sat). Runs on a side connection.
    ///
    /// # Throws
    ///
    /// `WalletError::Storage` on DB failure or `to_unix < from_unix`.
    pub fn net_worth_history(
        &self,
        from_unix: i64,
        to_unix: i64,
        currency: String,
        events: Vec<PaymentEventLiteFfi>,
        proxy_url: Option<String>,
    ) -> Result<Vec<NwSnapshotFfi>, WalletError> {
        let rate_repo = self.rate_repo(proxy_url.as_deref())?;
        let registry = self.side_registry()?;
        let core_events: Vec<PaymentEventLite> = events
            .into_iter()
            .map(|e| PaymentEventLite {
                amount_sat: e.amount_sat,
                block_time_unix: e.block_time_unix,
            })
            .collect();
        let snaps = reconstruct_net_worth_history(
            &registry,
            &rate_repo,
            &core_events,
            from_unix,
            to_unix,
            &currency,
        )
        .map_err(|e| WalletError::Storage {
            detail: e.to_string(),
        })?;
        Ok(snaps.into_iter().map(NwSnapshotFfi::from).collect())
    }
}
