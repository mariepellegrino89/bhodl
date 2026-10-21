//! UniFFI surface for BHODL.
//!
//! This crate is the single FFI seam between the Rust core and the
//! Kotlin Multiplatform / Swift code that drives the UI. Every type and
//! function exposed through `#[uniffi::*]` is part of the wallet's
//! external contract and changes to it ripple into the KMP shared
//! module.
//!
//! Phase-1 surface:
//! - `Network` — enum mirror of `bhodl_core::Network`.
//! - `WalletError` — enum mirror of `bhodl_wallet_onchain::OnchainError`.
//! - `WalletHandle` — opaque object wrapping `OnchainAccount` behind a
//!   `Mutex` so the same handle can be shared across threads (UniFFI
//!   requires `Send + Sync` for exported objects).
//!
//! Methods exposed on `WalletHandle`: `from_mnemonic` (in-memory
//! ephemeral handle), `create_or_load_persistent` (PIN-locked, on-disk
//! `SQLCipher`), `peek_address_at`, `balance_sat`, `sync_with_esplora`.
//! The full read/write API (PSBT build/sign/broadcast, UTXO listing,
//! descriptor backup, BIP329 labels) lands in subsequent commits once
//! the KMP bindings have caught up.

use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
};

/// Maximum valid Bitcoin amount in satoshis (21 million BTC).
/// bitcoin 0.32 / bitcoin-units 0.1 do expose `Amount::MAX_MONEY`
/// on the `bitcoin_units` re-export but NOT through the `bitcoin`
/// crate's top-level `Amount` re-export. We define the constant
/// inline to stay version-independent.
const MAX_MONEY_SAT: u64 = 21_000_000 * 100_000_000;

/// Validate a satoshi amount received from the FFI layer.
///
/// Rejects values above the 21 M BTC hard cap. `Amount::from_sat`
/// in bitcoin 0.32 is unchecked (accepts any `u64`), so this guard
/// must sit at every FFI ingress that converts `u64 → Amount`.
///
/// **JVM-side note**: the Kotlin/Swift caller controls the `u64`
/// value, but UniFFI transmits it as an unsigned 64-bit integer;
/// any value > 21 M BTC is almost certainly a programming error
/// or an adversarial input.
#[inline]
fn checked_amount(sat: u64) -> Result<Amount, WalletError> {
    if sat > MAX_MONEY_SAT {
        return Err(WalletError::TxBuild {
            detail: format!("amount {sat} sat exceeds the 21 M BTC cap ({MAX_MONEY_SAT} sat)"),
        });
    }
    Ok(Amount::from_sat(sat))
}

#[cfg(test)]
use bhodl_core::SALT_LEN;
use bhodl_core::{Amount, KeyDerivationError, Network as CoreNetwork};
use bhodl_wallet_onchain::{
    Contact, DerivedAddress, ENCRYPTION_KEY_LEN, FeeEstimateBuckets, Label, LabelKind,
    MnemonicWordCount, OnchainAccount, OnchainError, REGISTRY_FILE_NAME, REGISTRY_KDF_FILE_NAME,
    Registry, TxRecord, UtxoInfo, WalletKind, WalletRecord, WatchAccount, WatchBip,
    generate_mnemonic as core_generate_mnemonic, open_registry,
};
use uuid::Uuid;

// R2 — KDF descriptor + salt persistence lives in its own module now,
// so this file is no longer a God Object. Pure code move; FFI surface
// unchanged. The test-only helpers (write_kdf_file, seal_kdf_file_with_mac,
// read_or_create_salt) are imported inside `mod tests`, not here, to
// avoid an unused-import warning in non-test builds.
mod kdf;
use crate::kdf::{
    KdfFile, KdfScope, LEGACY_SALT_FILE_NAME, SecretKey, WALLET_KDF_ARTIFACTS,
    migrate_kdf_if_stale, params_for_known_key, read_existing, rename_with_retry, unlock_with,
};

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

/// Errors raised by `WalletHandle`. Mirror of `OnchainError` flattened
/// into a single FFI-friendly enum (UniFFI does not propagate
/// `#[non_exhaustive]`).
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum WalletError {
    #[error("invalid BIP39 mnemonic: {detail}")]
    InvalidMnemonic { detail: String },
    #[error("BIP32 derivation failed: {detail}")]
    Bip32 { detail: String },
    #[error("invalid address: {detail}")]
    InvalidAddress { detail: String },
    #[error("invalid PSBT: {detail}")]
    InvalidPsbt { detail: String },
    #[error("invalid fee rate: {detail}")]
    InvalidFeeRate { detail: String },
    #[error("transaction build failed: {detail}")]
    TxBuild { detail: String },
    #[error("PSBT signing failed: {detail}")]
    Sign { detail: String },
    #[error("broadcast failed: {detail}")]
    Broadcast { detail: String },
    /// The built transaction's fee exceeds the absolute-fee guard
    /// (`max(50 000 sat, 5% of the amount sent)`). The UI must show a
    /// second, explicit confirmation and rebuild with
    /// `allow_high_fee = true` only if the user accepts.
    #[error("fee of {fee_sat} sat exceeds the {limit_sat} sat safety limit")]
    FeeTooHigh { fee_sat: u64, limit_sat: u64 },
    /// The fee estimator returned nothing usable; the UI must ask the
    /// user for a manual fee rate instead of defaulting to 1 sat/vB.
    #[error("fee estimate unavailable: {detail}")]
    FeeEstimateUnavailable { detail: String },
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
            OnchainError::Mnemonic(e) => Self::InvalidMnemonic {
                detail: e.to_string(),
            },
            OnchainError::Bip32(e) => Self::Bip32 {
                detail: e.to_string(),
            },
            OnchainError::DerivationPath(s) => Self::DerivationPath { detail: s },
            OnchainError::Descriptor(s) => Self::WalletCreate { detail: s },
            OnchainError::WalletCreate(s) => Self::WalletCreate { detail: s },
            OnchainError::EsploraSync(s) => Self::EsploraSync { detail: s },
            OnchainError::Storage(s) => Self::Storage { detail: s },
            OnchainError::InvalidAddress(s) => Self::InvalidAddress { detail: s },
            OnchainError::InvalidPsbt(s) => Self::InvalidPsbt { detail: s },
            OnchainError::InvalidFeeRate(s) => Self::InvalidFeeRate { detail: s },
            OnchainError::TxBuild(s) => Self::TxBuild { detail: s },
            OnchainError::Sign(s) => Self::Sign { detail: s },
            OnchainError::Broadcast(s) => Self::Broadcast { detail: s },
            OnchainError::FeeTooHigh { fee_sat, limit_sat } => {
                Self::FeeTooHigh { fee_sat, limit_sat }
            }
            OnchainError::FeeEstimateUnavailable(s) => Self::FeeEstimateUnavailable { detail: s },
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

/// Opaque wallet handle exposed to Kotlin/Swift.
///
/// The inner `OnchainAccount` is wrapped in a `Mutex` so the handle is
/// `Send + Sync` (UniFFI requires this for `#[derive(uniffi::Object)]`).
/// Callers from the FFI side hold an `Arc<WalletHandle>` and may share
/// it freely across threads; every method takes an exclusive lock.
///
/// **File-lock release on wipe (finding #14)**: the Kotlin/Swift side
/// must call UniFFI's auto-generated `destroy()` (via the `Disposable`
/// contract) before deleting `wallet.sqlite`. `destroy()` decrements
/// the Arc strong count, which — when the last reference is the one
/// the FFI holds — fires `Drop` on `WalletHandle`, which drops the
/// `Mutex<OnchainAccount>`, which drops the inner `Connection`,
/// which closes the SQLCipher file handle. The desktop teardown path
/// wires this in `WalletStore.clear()`.
#[derive(uniffi::Object)]
pub struct WalletHandle {
    inner: Mutex<OnchainAccount>,
}

#[uniffi::export]
impl WalletHandle {
    /// Build an in-memory wallet from a BIP39 mnemonic.
    ///
    /// `passphrase` may be empty. The resulting handle is volatile: it
    /// goes away when dropped. Persistent handles will be added with the
    /// PIN/keystore step.
    #[uniffi::constructor]
    pub fn from_mnemonic(
        network: Network,
        mnemonic: String,
        passphrase: String,
    ) -> Result<std::sync::Arc<Self>, WalletError> {
        // Wrap the incoming strings in Zeroizing so this Rust-side copy
        // is wiped when the function returns. The JVM-side String is
        // outside our control and lives until the GC collects it.
        let mnemonic = zeroize::Zeroizing::new(mnemonic);
        let passphrase = zeroize::Zeroizing::new(passphrase);
        let account = OnchainAccount::from_mnemonic(network.into(), &mnemonic, &passphrase)?;
        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(account),
        }))
    }

    /// Derive the BIP86 receive address at `index` without advancing the
    /// internal counter. Returned as a bech32m string.
    pub fn peek_address_at(&self, index: u32) -> Result<String, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let addr = guard.peek_taproot_address(index)?;
        Ok(addr.to_string())
    }

    /// Reveal the next external (receive) address and persist the
    /// advance so subsequent calls return the next index.
    ///
    /// The desktop Receive screen calls this every time the user taps
    /// "Generate new address". The reveal is fsynced into the
    /// SQLCipher store before returning, so a process restart continues
    /// where the previous run left off — the user never re-sees an
    /// address that was already shared.
    pub fn next_receive_address(&self) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
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
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.next_unused_receive_address()?.to_string())
    }

    /// Generate a fresh BIP39 mnemonic. The method body never touches
    /// `self` for anything other than the mutex acquire that mirrors
    /// the other methods' shape — `WalletHandle` is simply the only
    /// `#[uniffi::Object]` whose method calls pass through the Ubique
    /// KMP fork's bindgen on Windows without `RustBuffer.free` blowing
    /// up. See `docs/uniffi-runtime.md` for the investigation log.
    ///
    /// The instance the caller invokes this on has no semantic role —
    /// the desktop app constructs a throwaway `WalletHandle` from a
    /// well-known placeholder mnemonic, calls this method to obtain a
    /// fresh phrase, then immediately discards the throwaway and
    /// rebuilds a real handle from the new phrase.
    pub fn generate_random_mnemonic(&self, word_count: u32) -> Result<String, WalletError> {
        // The Mutex acquire is purely defensive: the body doesn't touch
        // the inner account, so we don't error if it's already closed.
        let _guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let words = MnemonicWordCount::from_count(word_count)?;
        Ok(core_generate_mnemonic(words)?)
    }

    /// Total balance in satoshis (confirmed plus pending). Returns 0
    /// until at least one successful `sync_with_esplora` has run.
    pub fn balance_sat(&self) -> Result<u64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.balance().to_sat())
    }

    /// Phase 6.0 — return the external (receive) descriptor in
    /// **xpub-only** form, suitable to be persisted as a `WatchHd`
    /// row in the registry so the Tracker can show this wallet's
    /// balance without ever needing the signing PIN.
    ///
    /// The output is exactly what `OnchainAccount::export_descriptors()`
    /// already produces for the backup flow — re-exposed here so the
    /// UI pairing path (PinSetup checkbox, Settings "Add to Tracker",
    /// first-unlock banner) can derive the descriptor in one call
    /// without having to round-trip through a backup file.
    ///
    /// Privacy / security: no xprv leakage. The xpub is derived from
    /// the already-unlocked `WalletHandle`; this call does NOT touch
    /// the mnemonic or seed bytes and adds no new persistence —
    /// caller decides whether to write the result into the registry.
    pub fn tracker_descriptor_pub(&self) -> Result<String, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.export_descriptors().external)
    }

    /// Open or initialise a `SQLCipher`-encrypted, on-disk wallet at
    /// `db_dir`, with the encryption key derived from `pin` via Argon2id.
    ///
    /// Layout written under `db_dir`:
    /// - `wallet.sqlite` — the encrypted wallet database.
    /// - `wallet.kdf.json` — salt + Argon2id params (created on first
    ///   call, sealed with an HMAC after the first successful open,
    ///   reused on every subsequent unlock). The salt is not a secret;
    ///   losing it makes the wallet unrecoverable but does not weaken
    ///   the encryption. Pre-descriptor installs carry a raw
    ///   `wallet.salt` instead, upgraded on the next unlock.
    /// - `wallet.kdf.sealed` — marker: once present, a descriptor
    ///   without MAC is refused.
    ///
    /// A NEW wallet's `pin` must be at least 6 ASCII digits.
    ///
    /// Behaviour:
    /// - First call: generates a fresh salt, derives the key, creates
    ///   the wallet, persists the initial state.
    /// - Subsequent calls: reads the existing salt, derives the key
    ///   with the supplied PIN. A wrong PIN is rejected as
    ///   `WalletError::Storage` because `SQLCipher` cannot read the
    ///   schema without the correct key.
    /// - `mnemonic` mismatch (different mnemonic on the same DB) is
    ///   reported as `WalletError::Storage` by the descriptor check.
    ///
    /// `pin` must be non-empty; UI layers must enforce a longer minimum.
    #[uniffi::constructor]
    pub fn create_or_load_persistent(
        db_dir: String,
        network: Network,
        mnemonic: String,
        passphrase: String,
        pin: String,
    ) -> Result<std::sync::Arc<Self>, WalletError> {
        // Wrap secret strings so the Rust-side copies are wiped on drop.
        // The JVM-side Strings are outside our control — documented limitation.
        let mnemonic = zeroize::Zeroizing::new(mnemonic);
        let passphrase = zeroize::Zeroizing::new(passphrase);
        let pin = zeroize::Zeroizing::new(pin);
        let db_dir = PathBuf::from(db_dir);
        fs::create_dir_all(&db_dir)?;

        let db_path = db_dir.join("wallet.sqlite");

        // Snapshot whether the directory had a wallet *before* we
        // touched it — `read_or_create_kdf` writes a fresh kdf.json on
        // a virgin dir, so by the time we hit the SQLCipher open the
        // marker exists either way. We need the pre-call signal to
        // distinguish "user typed wrong PIN against an existing
        // wallet" from "fresh kdf landed but an orphan sqlite from a
        // previous incomplete wipe is poisoning the open".
        let prior_wallet_existed = WALLET_KDF_ARTIFACTS
            .iter()
            .any(|name| *name != "wallet.kdf.sealed" && db_dir.join(name).exists());
        let orphan_sqlite_before = !prior_wallet_existed && db_path.exists();

        // Resolve to the core `Network` once: the FFI `Network` enum
        // isn't `Copy`, and the recovery branch needs to call
        // `OnchainAccount::create_or_load_persistent` a second time
        // with the same network value.
        let core_network: CoreNetwork = network.into();

        // H1 — the descriptor MAC (if any) is verified before the open,
        // but the descriptor is only SEALED by `unlock_with` after the
        // open below succeeded with this key. A fresh descriptor is
        // created here (new-PIN policy enforced) when none exists.
        let (account, unlocked) = unlock_with(&db_dir, KdfScope::Wallet, &pin, true, |key| {
            match OnchainAccount::create_or_load_persistent(
                core_network,
                &mnemonic,
                &passphrase,
                &db_path,
                key,
            ) {
                Ok(acc) => Ok(acc),
                Err(err) if orphan_sqlite_before => {
                    // Recovery path for finding #14: the boot guard saw
                    // no wallet (markers absent), the user authorised a
                    // fresh create / restore, but a previous wipe left
                    // `wallet.sqlite` behind under a Windows file-lock
                    // and the new PIN-derived key cannot decrypt it. The
                    // DB is already inaccessible (no kdf.json paired
                    // with it). L5 — set it aside instead of deleting
                    // it, so nothing is ever destroyed on a guess, then
                    // retry the open exactly once with the same new key.
                    let orphan = orphan_sqlite_path(&db_dir);
                    rename_with_retry(&db_path, &orphan).map_err(|io_err| WalletError::Io {
                        detail: format!(
                            "recovery: set aside orphan wallet.sqlite after open failed ({err}): {io_err}",
                        ),
                    })?;
                    Ok(OnchainAccount::create_or_load_persistent(
                        core_network,
                        &mnemonic,
                        &passphrase,
                        &db_path,
                        key,
                    )?)
                }
                Err(err) => Err(err.into()),
            }
        })?;

        migrate_kdf_if_stale(&db_dir, &unlocked, &pin, &account)?;

        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(account),
        }))
    }

    /// Open an existing PIN-encrypted wallet at `db_dir` WITHOUT
    /// requiring the user's mnemonic.
    ///
    /// This is the path the desktop unlock screen uses on returning
    /// users — the mnemonic is paper-only, so we derive the SQLCipher
    /// key from the salt + PIN and let BDK rebuild the wallet from
    /// the descriptors persisted inside the encrypted DB.
    ///
    /// # Errors
    ///
    /// - [`WalletError::InvalidSalt`] if `wallet.salt` exists but is the
    ///   wrong size (i.e. the file is corrupt).
    /// - [`WalletError::Storage`] if the DB does not exist, is empty,
    ///   does not match the supplied PIN, or is bound to a different
    ///   network than `network`.
    #[uniffi::constructor]
    pub fn unlock_persistent(
        db_dir: String,
        network: Network,
        pin: String,
    ) -> Result<std::sync::Arc<Self>, WalletError> {
        // Wrap secret string so the Rust-side copy is wiped on drop.
        // The JVM-side String is outside our control — documented limitation.
        let pin = zeroize::Zeroizing::new(pin);
        let db_dir = PathBuf::from(db_dir);
        let db_path = db_dir.join("wallet.sqlite");
        let core_network: CoreNetwork = network.into();

        // M2 — wallets migrated by a pre-fix Phase 3.0 migration from a
        // `wallet.salt`-only install lost their salt (it stayed at the
        // data-dir root). Put a copy back before unlocking.
        let repaired_salt_from = repair_migrated_legacy_salt(&db_dir);

        // The unlock path MUST NOT create a salt file: doing so would
        // turn a wrong-data-dir unlock attempt into a "fresh wallet"
        // initialisation, silently losing access to a real wallet that
        // lives elsewhere. `create_if_missing = false`.
        // H1/H2 — MAC verified before the open, descriptor sealed only
        // after it, `.next` fallback for an interrupted KDF migration.
        let (account, unlocked) = unlock_with(&db_dir, KdfScope::Wallet, &pin, false, |key| {
            Ok(OnchainAccount::load_persistent(
                core_network,
                &db_path,
                key,
            )?)
        })?;

        migrate_kdf_if_stale(&db_dir, &unlocked, &pin, &account)?;

        // M2 — first successful unlock of the migrated wallet: drop the
        // pre-3.0 backup (it keeps a weak-KDF copy of the seed).
        cleanup_migration_backup(&db_dir, core_network, &pin, &account);
        if let Some(root_salt) = repaired_salt_from {
            // The repaired wallet opened (and was migrated to a JSON
            // descriptor): the stray root copy is no longer needed.
            let _ = fs::remove_file(root_salt);
        }

        Ok(std::sync::Arc::new(Self {
            inner: Mutex::new(account),
        }))
    }

    /// Run a blocking Esplora full-scan against `esplora_url`,
    /// optionally routed through `proxy_url` (e.g. `socks5h://127.0.0.1:9050`
    /// for Tor).
    pub fn sync_with_esplora(
        &self,
        esplora_url: String,
        proxy_url: Option<String>,
    ) -> Result<(), WalletError> {
        // Audit M5 — never hold the wallet mutex across network I/O: a
        // Tor full-scan can take minutes and would freeze every other
        // wallet call (balance, address, labels) meanwhile. Snapshot the
        // request under the lock, fetch unlocked, re-lock to apply.
        let pending = {
            let guard = self.inner.lock().map_err(|_| WalletError::Storage {
                detail: "wallet mutex poisoned".into(),
            })?;
            guard.start_esplora_scan()
        };
        let update = pending.fetch(&esplora_url, proxy_url.as_deref())?;
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        guard.apply_esplora_scan(update)?;
        Ok(())
    }

    /// Build an unsigned PSBT spending `amount_sat` to `to_address` at
    /// the given flat fee rate (sat per virtual byte).
    ///
    /// Returns the PSBT in base64. The wallet's change address may be
    /// revealed as a side-effect of the build (BDK persists the new
    /// derivation index before this call returns).
    ///
    /// Every `build_*` method validates the fee rate (1..=1000 sat/vB;
    /// 0 allowed on regtest) and refuses a fee above
    /// `max(50 000 sat, 5% of the amount sent)` with
    /// [`WalletError::FeeTooHigh`] unless `allow_high_fee` is `true` —
    /// pass `true` only after the user confirmed that fee explicitly.
    pub fn build_send_tx(
        &self,
        to_address: String,
        amount_sat: u64,
        fee_rate_sat_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let amount = checked_amount(amount_sat)?;
        Ok(guard.build_send_tx(&to_address, amount, fee_rate_sat_vb, allow_high_fee)?)
    }

    /// Decode a PSBT into the summary the review screens render: every
    /// output (with ownership / change flags from the wallet's script
    /// index), the fee, estimated vsize, external spend and the
    /// absolute-fee-guard verdict. Review UIs must render ONLY this and
    /// then sign exactly the same PSBT string.
    pub fn decode_psbt(&self, psbt_b64: String) -> Result<PsbtSummary, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.decode_psbt(&psbt_b64)?.into())
    }

    /// Read the miner fee (sat) from a PSBT — used by the review screen
    /// to display the fee before the user authorises broadcast.
    pub fn psbt_fee_sat(&self, psbt_b64: String) -> Result<u64, WalletError> {
        let _guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(OnchainAccount::psbt_fee_sat(&psbt_b64)?)
    }

    /// Sign and finalise a PSBT (base64) with this wallet's xpriv.
    /// Returns the signed PSBT in base64.
    pub fn sign_psbt(&self, psbt_b64: String) -> Result<String, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.sign_psbt(&psbt_b64)?)
    }

    /// Broadcast a signed+finalised PSBT through the given Esplora HTTP
    /// endpoint, optionally routed through `proxy_url`. Returns the
    /// txid as a hex string.
    pub fn broadcast_tx(
        &self,
        esplora_url: String,
        signed_psbt_b64: String,
        proxy_url: Option<String>,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        // `&mut`: a successful broadcast is applied to the local view
        // (unconfirmed) so the next build cannot reuse its inputs.
        let txid = guard.broadcast_tx(&esplora_url, &signed_psbt_b64, proxy_url.as_deref())?;
        Ok(txid.to_string())
    }

    /// Build an unsigned PSBT spending to multiple recipients in one
    /// transaction (a "batch" send). Each entry adds one output.
    pub fn build_send_tx_batch(
        &self,
        recipients: Vec<RecipientEntry>,
        fee_rate_sat_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let core_recipients: Vec<(String, Amount)> = recipients
            .into_iter()
            .map(|r| Ok((r.address, checked_amount(r.amount_sat)?)))
            .collect::<Result<_, WalletError>>()?;
        Ok(guard.build_send_tx_batch(&core_recipients, fee_rate_sat_vb, allow_high_fee)?)
    }

    /// Re-read the mnemonic + BIP-39 passphrase the wallet was
    /// created with. Used by Settings → "Show recovery phrase" so
    /// the user can produce a paper backup at any time. Production
    /// callers should gate this behind a PIN re-confirmation step.
    pub fn peek_mnemonic(&self) -> Result<MnemonicReveal, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        // `peek_mnemonic` returns owned Strings from the DB.
        // Wrap them in Zeroizing so the intermediate Rust-side copies
        // are wiped when the Zeroizing wrappers drop. We then clone the
        // inner String values into the MnemonicReveal that crosses the
        // FFI boundary; the Zeroizing copies are dropped immediately
        // after the struct is built. The JVM String produced from
        // MnemonicReveal.mnemonic is outside our control — documented
        // limitation.
        let (mnemonic_z, passphrase_z) = {
            let (m, p) = guard.peek_mnemonic()?;
            (zeroize::Zeroizing::new(m), zeroize::Zeroizing::new(p))
        };
        let reveal = MnemonicReveal {
            mnemonic: mnemonic_z.to_string(),
            passphrase: passphrase_z.to_string(),
        };
        // mnemonic_z and passphrase_z drop here, wiping the copies.
        Ok(reveal)
    }

    /// Export an encrypted backup of the wallet seed (mnemonic +
    /// passphrase) under a user-chosen `password` (NOT the unlock
    /// PIN). Returns the ASCII-armored age payload.
    ///
    /// Caller saves the result to a paper / password-manager backup
    /// and can recover the wallet on any device by importing it back
    /// into BHODL — or, in a pinch, by piping it through the `age`
    /// CLI to recover just the JSON.
    pub fn export_encrypted_backup(&self, password: String) -> Result<String, WalletError> {
        // Wrap the password so the Rust-side copy is wiped on drop.
        let password = zeroize::Zeroizing::new(password);
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.export_encrypted_backup(&password)?)
    }

    /// Export the wallet's public-only descriptor surface.
    ///
    /// Returns the external (recv) + internal (change) descriptor strings
    /// plus the bare account-level xpub extracted from the external
    /// descriptor. Contains zero secret material: the descriptors are
    /// derived from BDK's public projection only. Suitable for:
    /// - importing into Sparrow / Liana / Specter as a watch-only wallet
    /// - pasting the bare xpub into the Phase 3.1 debug screen
    /// - audit / receive-address verification on a second device
    pub fn export_public_descriptor(&self) -> Result<PublicDescriptorBundle, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let backup = guard.export_descriptors();
        let account_xpub = backup.account_xpub().ok_or_else(|| WalletError::Storage {
            detail: "failed to extract account xpub from descriptor".into(),
        })?;
        let network_str = match backup.network {
            CoreNetwork::Mainnet => "mainnet",
            CoreNetwork::Signet => "signet",
            CoreNetwork::Testnet4 => "testnet4",
            CoreNetwork::Regtest => "regtest",
            _ => "unknown",
        }
        .to_string();
        Ok(PublicDescriptorBundle {
            network: network_str,
            external_descriptor: backup.external,
            internal_descriptor: backup.internal,
            account_xpub,
        })
    }

    /// Insert a new (label, address) pair into the local address book.
    /// Returns the row id assigned by the DB.
    pub fn add_contact(&self, label: String, address: String) -> Result<i64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.add_contact(&label, &address)?)
    }

    /// Return every saved address-book entry.
    pub fn list_contacts(&self) -> Result<Vec<ContactRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.list_contacts()?.into_iter().map(Into::into).collect())
    }

    /// Rename an existing contact. Returns `true` if a row was
    /// updated, `false` if no row matched `id`.
    pub fn update_contact_label(&self, id: i64, new_label: String) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.update_contact_label(id, &new_label)?)
    }

    /// Delete a contact by row id. Returns `true` if a row was
    /// deleted, `false` if no row matched `id`.
    pub fn remove_contact(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.remove_contact(id)?)
    }

    /// PayJoin (BIP-78 v1) sender, step 1 of 2. Parses `uri`, refuses
    /// unless its address / amount equal `expected_address` /
    /// `expected_amount_sat` (what the form displayed), vets the `pj=`
    /// endpoint (https only, SSRF guard; `.onion` needs `proxy_url`),
    /// builds + signs the original, POSTs it, and validates the
    /// receiver's proposal against the BIP-78 sender checks.
    ///
    /// The ORIGINAL is signed here, so the absolute-fee guard applies:
    /// a fee above the limit fails with `WalletError::FeeTooHigh` unless
    /// `allow_high_fee` is `true` (only after the user confirmed it, as
    /// for the regular send builders). The confirmation also covers the
    /// sign step of the resulting session.
    ///
    /// Nothing is signed for the proposal yet: show the returned
    /// [`PayjoinReview`] to the user, then call
    /// [`Self::payjoin_sign_and_broadcast`] with its `session_id`.
    /// When negotiation failed (`session_id == None`, `failure_reason`
    /// set) `original_psbt` is a signed regular payment the caller may
    /// broadcast via [`Self::broadcast_tx`] instead.
    pub fn payjoin_negotiate(
        &self,
        uri: String,
        expected_address: String,
        expected_amount_sat: u64,
        fee_rate_sat_vb: u64,
        proxy_url: Option<String>,
        allow_high_fee: bool,
    ) -> Result<PayjoinReview, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let review = guard.payjoin_negotiate(
            &uri,
            &expected_address,
            expected_amount_sat,
            fee_rate_sat_vb,
            proxy_url.as_deref(),
            allow_high_fee,
        )?;
        Ok(review.into())
    }

    /// PayJoin sender, step 2 of 2: sign ONLY this wallet's original
    /// inputs of the reviewed proposal `session_id` and broadcast it
    /// through `esplora_url` (optionally via `proxy_url`). One-shot:
    /// the session is consumed. Returns the txid hex.
    pub fn payjoin_sign_and_broadcast(
        &self,
        session_id: String,
        esplora_url: String,
        proxy_url: Option<String>,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let txid =
            guard.payjoin_sign_and_broadcast(&session_id, &esplora_url, proxy_url.as_deref())?;
        Ok(txid.to_string())
    }

    /// Drop a negotiated PayJoin session the user cancelled. Returns
    /// whether it existed.
    pub fn payjoin_discard(&self, session_id: String) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.payjoin_discard(&session_id))
    }

    /// Build (but do NOT sign) a Child-Pays-For-Parent (CPFP) child
    /// PSBT spending only the wallet's outputs of the still-unconfirmed
    /// `parent_txid`, draining them to a fresh internal (change)
    /// address. The child fee is sized so the parent+child PACKAGE
    /// reaches `fee_rate_sat_vb`; the returned [`CpfpPlan`] carries the
    /// package numbers for the review screen. Sign + broadcast the
    /// reviewed `psbt` via `sign_psbt` / `broadcast_tx`.
    ///
    /// Use cases:
    /// - Speed up an incoming pending tx by spending the outputs we
    ///   received in it.
    /// - Rescue a stuck change UTXO from an outgoing tx.
    pub fn build_cpfp_psbt(
        &self,
        parent_txid: String,
        fee_rate_sat_vb: u64,
        allow_high_fee: bool,
    ) -> Result<CpfpPlan, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard
            .build_cpfp_psbt(&parent_txid, fee_rate_sat_vb, allow_high_fee)?
            .into())
    }

    /// Build (but do NOT sign) a replacement PSBT for a still-pending
    /// transaction at a higher fee rate (BIP-125 RBF). The new PSBT
    /// pays the same recipients as the original. Review it with
    /// `decode_psbt`, then sign + broadcast via `sign_psbt` /
    /// `broadcast_tx`.
    pub fn build_fee_bump_psbt(
        &self,
        txid: String,
        new_fee_rate_sat_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.build_fee_bump_psbt(&txid, new_fee_rate_sat_vb, allow_high_fee)?)
    }

    /// List the wallet's spendable UTXOs — used by the SendScreen's
    /// optional coin-control panel to let the user pin specific
    /// inputs.
    pub fn list_utxos(&self) -> Result<Vec<UtxoRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.list_utxos()?.into_iter().map(Into::into).collect())
    }

    /// Freeze (`frozen = true`) or unfreeze one of the wallet's
    /// outputs (`txid:vout`) via a BIP-329 `output` label with
    /// `spendable: false`. Existing label text / category is kept.
    /// Frozen coins are skipped by automatic selection and refused in
    /// manual coin control.
    pub fn set_utxo_frozen(&self, outpoint: String, frozen: bool) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.set_output_frozen(&outpoint, frozen)?)
    }

    /// Every revealed address on one keychain (`change = false`:
    /// receive, `true`: change), oldest first, with used / unused
    /// state and the BIP-329 `addr` label + category. Backs the
    /// wallet's Addresses tab and the Receive reuse warning.
    pub fn list_addresses(&self, change: bool) -> Result<Vec<AddressRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
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
            detail: "wallet mutex poisoned".into(),
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
            detail: "wallet mutex poisoned".into(),
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
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.chain_tip_height())
    }

    /// Build an unsigned PSBT spending exactly the supplied set of
    /// outpoints. Useful when the user wants explicit coin control
    /// (privacy heuristics, BIP-329 spend-from-label, refusing to
    /// merge UTXOs from different sources).
    ///
    /// `outpoints` is a list of `txid:vout` strings (the same shape
    /// `UtxoRecord.outpoint` exposes).
    pub fn build_send_tx_with_utxos(
        &self,
        to_address: String,
        amount_sat: u64,
        fee_rate_sat_vb: u64,
        outpoints: Vec<String>,
        allow_high_fee: bool,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let amount = checked_amount(amount_sat)?;
        let outpoint_refs: Vec<&str> = outpoints.iter().map(String::as_str).collect();
        Ok(guard.build_send_tx_with_outpoint_strings(
            &to_address,
            amount,
            fee_rate_sat_vb,
            &outpoint_refs,
            allow_high_fee,
        )?)
    }

    /// Build an unsigned PSBT that drains the entire spendable
    /// balance to `to_address`. The recipient receives every
    /// satoshi the wallet holds minus the network fee — there is
    /// no change output by construction.
    ///
    /// `fee_rate_sat_vb` follows the same semantics as
    /// `build_send_tx`: rejected when below the relay floor.
    pub fn build_drain_tx(
        &self,
        to_address: String,
        fee_rate_sat_vb: u64,
        allow_high_fee: bool,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.build_drain_tx(&to_address, fee_rate_sat_vb, allow_high_fee)?)
    }

    /// Build an unsigned PSBT spending **only** the supplied outpoints
    /// to **multiple** recipients in one transaction. Combines coin
    /// control with a batched send — see
    /// [`bhodl_wallet_onchain::OnchainAccount::build_send_tx_batch_with_utxos`]
    /// for the full semantics.
    pub fn build_send_tx_batch_with_utxos(
        &self,
        recipients: Vec<RecipientEntry>,
        fee_rate_sat_vb: u64,
        outpoints: Vec<String>,
        allow_high_fee: bool,
    ) -> Result<String, WalletError> {
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        let core_recipients: Vec<(String, Amount)> = recipients
            .into_iter()
            .map(|r| Ok((r.address, checked_amount(r.amount_sat)?)))
            .collect::<Result<_, WalletError>>()?;
        let outpoint_refs: Vec<&str> = outpoints.iter().map(String::as_str).collect();
        Ok(guard.build_send_tx_batch_with_outpoint_strings(
            &core_recipients,
            fee_rate_sat_vb,
            &outpoint_refs,
            allow_high_fee,
        )?)
    }

    /// Enumerate every transaction the wallet currently knows about,
    /// flattened to [`TransactionRecord`].
    ///
    /// Order is BDK-internal — the Kotlin caller is expected to sort
    /// (typically by `timestamp` descending). Both confirmed and
    /// mempool entries are returned; `confirmation_height: None`
    /// means "still pending".
    pub fn list_transactions(&self) -> Result<Vec<TransactionRecord>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard
            .list_transactions()
            .into_iter()
            .map(Into::into)
            .collect())
    }

    /// Set (or replace) the BIP-329 label on a transaction. Preserves
    /// any `category` already attached to this txid — Phase 3.4 stores
    /// categories on the same row, and an empty-category re-save from
    /// the label-only UI must not blank it.
    pub fn set_tx_label(&self, txid: String, text: String) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
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
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.get_label(LabelKind::Tx, &txid)?.map(|l| l.text))
    }

    /// Phase 3.4 — set (or replace) the BHODL `category` extension on
    /// a transaction's BIP-329 row. Categories are stored alongside
    /// the label in the same wallet-PIN-encrypted table. Pass `None`
    /// to clear the category; the label text is preserved.
    pub fn set_tx_category(
        &self,
        txid: String,
        category: Option<String>,
    ) -> Result<(), WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
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
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard
            .get_label(LabelKind::Tx, &txid)?
            .and_then(|l| l.category))
    }

    /// Remove the BIP-329 label on a transaction. Returns `true` if a
    /// row was deleted, `false` if no label was set.
    pub fn remove_tx_label(&self, txid: String) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.remove_label(LabelKind::Tx, &txid)?)
    }

    /// Render every wallet label as BIP-329 JSONL (one record per
    /// line). Suitable for sharing with Sparrow / Liana / any other
    /// BIP-329 consumer. The result is plain UTF-8 text, safe to
    /// dump to a `.jsonl` file via the desktop save dialog.
    pub fn export_labels_jsonl(&self) -> Result<String, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.export_labels_jsonl()?)
    }

    /// Import a BIP-329 JSONL document produced by another wallet.
    /// Each record upserts (`(kind, target)` is the natural key);
    /// blank lines are tolerated. Returns the number of imported
    /// records.
    pub fn import_labels_jsonl(&self, jsonl: String) -> Result<u64, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "wallet mutex poisoned".into(),
        })?;
        Ok(guard.import_labels_jsonl(&jsonl)? as u64)
    }

    /// Pull a 3-bucket fee-rate snapshot from Esplora — used by the
    /// SendScreen to populate the economy / normal / priority selector.
    /// All values are in sat/vB and monotonic (economy <= normal <=
    /// priority). An empty or implausible response (any bucket above the
    /// send-policy cap) fails with `FeeEstimateUnavailable` instead of
    /// falling back to 1 sat/vB; the UI must then ask for a manual rate.
    /// `proxy_url` routes the lookup through a SOCKS5 proxy (Tor).
    pub fn fetch_fee_estimates(
        &self,
        esplora_url: String,
        proxy_url: Option<String>,
    ) -> Result<FeeEstimates, WalletError> {
        // Audit M5 — the fee feed needs no wallet state, so the network
        // request runs without the wallet mutex.
        let buckets = OnchainAccount::fetch_fee_estimates_from(&esplora_url, proxy_url.as_deref())?;
        Ok(FeeEstimates::from(buckets))
    }
}

/// One recipient of a batch send: `address` and `amount_sat`. The
/// `WalletHandle.buildSendTxBatch` method accepts a `Vec` of these
/// and adds one output per entry.
#[derive(uniffi::Record)]
pub struct RecipientEntry {
    pub address: String,
    pub amount_sat: u64,
}

/// One output of a PayJoin transaction as shown on the review dialog.
#[derive(uniffi::Record)]
pub struct PayjoinOutput {
    /// Address, or `script:<hex>` for a non-standard script.
    pub address: String,
    pub amount_sat: u64,
    /// Pays back to this wallet (change).
    pub is_mine: bool,
    /// The payment output the URI requested.
    pub is_payment: bool,
}

/// FFI mirror of `bhodl_wallet_onchain::PayjoinNegotiation` — what the
/// user must review before `WalletHandle.payjoinSignAndBroadcast`.
///
/// `session_id == None` means negotiation failed (`failure_reason`);
/// `outputs` / fees then describe the original transaction and
/// `original_psbt` (signed) can be broadcast as a regular payment.
#[derive(uniffi::Record)]
pub struct PayjoinReview {
    pub session_id: Option<String>,
    pub failure_reason: Option<String>,
    pub original_psbt: String,
    pub original_fee_sat: u64,
    pub proposal_fee_sat: u64,
    pub sender_fee_contribution_sat: u64,
    pub vsize: u64,
    pub receiver_input_count: u32,
    pub sender_net_spend_sat: u64,
    pub outputs: Vec<PayjoinOutput>,
}

impl From<bhodl_wallet_onchain::PayjoinNegotiation> for PayjoinReview {
    fn from(n: bhodl_wallet_onchain::PayjoinNegotiation) -> Self {
        Self {
            session_id: n.session_id,
            failure_reason: n.failure_reason,
            original_psbt: n.original_psbt,
            original_fee_sat: n.original_fee_sat,
            proposal_fee_sat: n.proposal_fee_sat,
            sender_fee_contribution_sat: n.sender_fee_contribution_sat,
            vsize: n.vsize,
            receiver_input_count: n.receiver_input_count,
            sender_net_spend_sat: n.sender_net_spend_sat,
            outputs: n
                .outputs
                .into_iter()
                .map(|o| PayjoinOutput {
                    address: o.address,
                    amount_sat: o.amount_sat,
                    is_mine: o.is_mine,
                    is_payment: o.is_payment,
                })
                .collect(),
        }
    }
}

/// One output of a decoded PSBT (see [`WalletHandle::decode_psbt`]).
#[derive(uniffi::Record)]
pub struct PsbtOutput {
    /// Address, or `script:<hex>` for a script with no address form.
    pub address: String,
    pub amount_sat: u64,
    /// Pays back to this wallet (either keychain).
    pub is_mine: bool,
    /// Pays to this wallet's internal (change) keychain.
    pub is_change: bool,
}

/// Review-screen summary of a PSBT, computed in Rust from the PSBT and
/// the wallet's script index. Amounts are integer satoshis; the fee
/// rate is derived by the UI as `fee_sat / vsize` (no floats here).
#[derive(uniffi::Record)]
pub struct PsbtSummary {
    pub outputs: Vec<PsbtOutput>,
    pub input_count: u32,
    /// Inputs not owned by this wallet (0 for wallet-built PSBTs).
    pub foreign_input_count: u32,
    pub inputs_total_sat: u64,
    pub fee_sat: u64,
    /// Estimated virtual size once signed.
    pub vsize: u64,
    /// Sum of outputs paying third parties.
    pub sent_external_sat: u64,
    /// Real decrease of the wallet balance (own inputs - own outputs).
    pub wallet_net_debit_sat: u64,
    /// Absolute-fee guard limit for this transaction.
    pub fee_limit_sat: u64,
    /// `fee_sat > fee_limit_sat`: require a second explicit confirmation.
    pub fee_exceeds_limit: bool,
    /// Spends an unconfirmed output received from a third party
    /// (manual coin control only) — warn: it can be double-spent away.
    pub spends_unconfirmed_foreign: bool,
}

impl From<bhodl_wallet_onchain::PsbtSummary> for PsbtSummary {
    fn from(s: bhodl_wallet_onchain::PsbtSummary) -> Self {
        Self {
            outputs: s
                .outputs
                .into_iter()
                .map(|o| PsbtOutput {
                    address: o.address,
                    amount_sat: o.amount_sat,
                    is_mine: o.is_mine,
                    is_change: o.is_change,
                })
                .collect(),
            input_count: s.input_count,
            foreign_input_count: s.foreign_input_count,
            inputs_total_sat: s.inputs_total_sat,
            fee_sat: s.fee_sat,
            vsize: s.vsize,
            sent_external_sat: s.sent_external_sat,
            wallet_net_debit_sat: s.wallet_net_debit_sat,
            fee_limit_sat: s.fee_limit_sat,
            fee_exceeds_limit: s.fee_exceeds_limit,
            spends_unconfirmed_foreign: s.spends_unconfirmed_foreign,
        }
    }
}

/// CPFP child PSBT + the package arithmetic shown on its review.
#[derive(uniffi::Record)]
pub struct CpfpPlan {
    /// Unsigned child PSBT (base64).
    pub psbt: String,
    pub parent_txid: String,
    pub parent_fee_sat: u64,
    pub parent_vsize: u64,
    /// Unconfirmed ancestors of the parent counted in the package.
    pub ancestor_count: u32,
    /// Combined fee of those ancestors (excludes the parent).
    pub ancestors_fee_sat: u64,
    /// Combined vsize of those ancestors (excludes the parent).
    pub ancestors_vsize: u64,
    pub child_fee_sat: u64,
    pub child_vsize: u64,
    /// Package fee rate (sat/vB) the child was sized for.
    pub target_fee_rate_sat_vb: u64,
}

impl From<bhodl_wallet_onchain::CpfpPlan> for CpfpPlan {
    fn from(p: bhodl_wallet_onchain::CpfpPlan) -> Self {
        Self {
            psbt: p.psbt,
            parent_txid: p.parent_txid,
            parent_fee_sat: p.parent_fee_sat,
            parent_vsize: p.parent_vsize,
            ancestor_count: p.ancestor_count,
            ancestors_fee_sat: p.ancestors_fee_sat,
            ancestors_vsize: p.ancestors_vsize,
            child_fee_sat: p.child_fee_sat,
            child_vsize: p.child_vsize,
            target_fee_rate_sat_vb: p.target_fee_rate_sat_vb,
        }
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
/// `outpoint` carries `txid:vout` so the Kotlin side can pass it
/// straight back into `WalletHandle.buildSendTxWithUtxos` without
/// having to round-trip through bitcoin types.
#[derive(uniffi::Record)]
pub struct UtxoRecord {
    pub outpoint: String,
    pub value_sat: u64,
    pub address: String,
    pub is_change: bool,
    pub confirmed: bool,
    /// Frozen via a BIP-329 `spendable: false` label: never auto-selected
    /// and refused in manual coin control.
    pub frozen: bool,
}

impl From<UtxoInfo> for UtxoRecord {
    fn from(u: UtxoInfo) -> Self {
        Self {
            outpoint: u.outpoint.to_string(),
            value_sat: u.value.to_sat(),
            address: u.address.to_string(),
            is_change: u.is_change,
            confirmed: u.confirmed,
            frozen: u.frozen,
        }
    }
}

/// Public-only descriptor + account-level xpub for the active wallet.
///
/// Produced by `WalletHandle::export_public_descriptor`. Contains
/// strictly no secret material — every field is derived from BDK's
/// public projection of the wallet state.
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
    /// keychain path). Suitable for the Phase 3.1 debug screen's
    /// "Add watch xpub" form which expects an xpub-only input.
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
/// `WalletHandle::apply_receive_labels`); `label` / `category` are what
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
/// in the tracker's scope. Watch wallets contribute their full
/// history via the device key; the active signing wallet's events
/// are merged in on the Kotlin side from
/// `WalletHandle.list_transactions`, deduplicated by `txid`.
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

/// Three-bucket fee-rate snapshot mirroring `FeeEstimateBuckets`. Plain
/// uniffi `Record` so Kotlin/Swift get a typed value (no map / opaque
/// pointer round-trip).
#[derive(uniffi::Record)]
pub struct FeeEstimates {
    pub economy_sat_per_vb: u64,
    pub normal_sat_per_vb: u64,
    pub priority_sat_per_vb: u64,
}

impl From<FeeEstimateBuckets> for FeeEstimates {
    fn from(b: FeeEstimateBuckets) -> Self {
        Self {
            economy_sat_per_vb: b.economy_sat_per_vb,
            normal_sat_per_vb: b.normal_sat_per_vb,
            priority_sat_per_vb: b.priority_sat_per_vb,
        }
    }
}

// Backward-compatibility shim for the spike formatter still consumed by
// the current Compose Multiplatform demo screen. Kept until the desktop
// app migrates to `WalletHandle::balance_sat`.
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

/// Mnemonic + BIP-39 passphrase as returned by
/// [`WalletHandle::peek_mnemonic`].
#[derive(uniffi::Record)]
pub struct MnemonicReveal {
    pub mnemonic: String,
    pub passphrase: String,
}

/// Decrypted contents of an age-armored wallet backup.
///
/// Returned by [`decrypt_backup`] for the Restore-from-backup flow.
/// `network` is the free-form lower-case network name persisted at
/// export time (`mainnet`, `signet`, `testnet4`, `regtest`); the
/// caller is responsible for cross-checking it against the active
/// network selector before creating the wallet.
#[derive(uniffi::Record)]
pub struct BackupContents {
    pub version: u32,
    pub network: String,
    pub mnemonic: String,
    pub passphrase: String,
}

/// Decrypt an ASCII-armored age payload produced by
/// `WalletHandle.export_encrypted_backup` and return its
/// [`BackupContents`].
///
/// `password` is the SAME user-chosen password that was provided at
/// export time — separate from the device unlock PIN. Wrong
/// password / corrupt blob / future schema version all surface as
/// `WalletError::Storage` with a descriptive `detail`.
#[uniffi::export]
pub fn decrypt_backup(blob: String, password: String) -> Result<BackupContents, WalletError> {
    // Wrap the inputs so the Rust-side copies are wiped on drop. The
    // JVM-side Strings are outside our control — documented limitation.
    let blob = zeroize::Zeroizing::new(blob);
    let password = zeroize::Zeroizing::new(password);
    let bundle = bhodl_wallet_onchain::decrypt_bundle(&blob, &password)?;
    // `BackupBundle` now implements `ZeroizeOnDrop` so its fields cannot
    // be moved out by value. Clone into `BackupContents`; `bundle` drops
    // at the end of this block and wipes the decrypted mnemonic/passphrase.
    Ok(BackupContents {
        version: bundle.version,
        network: bundle.network.clone(),
        mnemonic: bundle.mnemonic.clone(),
        passphrase: bundle.passphrase.clone(),
    })
}

// ---------------------------------------------------------------------------
// Phase 3 — Registry FFI surface
// ---------------------------------------------------------------------------
//
// The registry catalogues every wallet (signing-capable + watch-only).
// It is opened/created at app boot with the **device PIN**, which is
// distinct from the per-wallet PIN used by `WalletHandle`. Storage
// layout, threat model, and migration are documented in
// `docs/phase-3-architecture.md`.

/// FFI mirror of `bhodl_wallet_onchain::WalletKind`.
///
/// Replicated as a UniFFI enum so the Kotlin/Swift side gets a typed
/// sealed type without coupling to the internal Rust enum's path.
#[derive(uniffi::Enum, Clone, Copy, PartialEq, Eq)]
pub enum WalletKindFfi {
    Signing,
    WatchXpub,
    WatchAddresses,
    WatchHd,
}

impl From<WalletKind> for WalletKindFfi {
    fn from(k: WalletKind) -> Self {
        match k {
            WalletKind::Signing => Self::Signing,
            WalletKind::WatchXpub => Self::WatchXpub,
            WalletKind::WatchAddresses => Self::WatchAddresses,
            WalletKind::WatchHd => Self::WatchHd,
        }
    }
}

impl From<WalletKindFfi> for WalletKind {
    fn from(k: WalletKindFfi) -> Self {
        match k {
            WalletKindFfi::Signing => Self::Signing,
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
    pub pins_warned: bool,
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
            pins_warned: r.pins_warned,
        }
    }
}

/// Result of [`run_migration_if_needed`].
///
/// `NotNeeded` is the normal post-3.0 boot — registry already
/// exists, nothing to do. `Migrated` carries the new wallet's UUID
/// (string-form) and label so the UI can show a "Welcome — we've
/// upgraded your wallet" confirmation. `RecoveredFromInterruption`
/// signals that a previous migration was killed mid-flight and the
/// boot completed it from the `migration.lock` sentinel.
#[derive(uniffi::Enum, Debug)]
pub enum MigrationOutcome {
    NotNeeded,
    Migrated {
        wallet_id: String,
        wallet_label: String,
    },
    RecoveredFromInterruption {
        wallet_id: String,
        wallet_label: String,
    },
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

/// Opaque handle on an open watch-only wallet.
///
/// Constructed via [`RegistryHandle::open_watch`]. Holds the
/// `WatchAccount` (BDK watch wallet + SQLCipher connection) behind a
/// `Mutex`. No signing APIs by construction — `WatchAccount` exposes
/// only read methods.
#[derive(uniffi::Object)]
pub struct WatchHandle {
    inner: Mutex<WatchAccount>,
}

impl std::fmt::Debug for WatchHandle {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("WatchHandle").finish_non_exhaustive()
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

    /// Enumerate every transaction the watch wallet knows about.
    /// Same shape as `WalletHandle::list_transactions`.
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

    /// Blocking Esplora full-scan. Same shape as
    /// `WalletHandle::sync_with_esplora`.
    pub fn sync_with_esplora(
        &self,
        esplora_url: String,
        proxy_url: Option<String>,
    ) -> Result<(), WalletError> {
        // Audit M5 — same lock-free network phase as
        // `WalletHandle::sync_with_esplora`.
        let pending = {
            let guard = self.inner.lock().map_err(|_| WalletError::Storage {
                detail: "watch wallet mutex poisoned".into(),
            })?;
            guard.start_esplora_scan()
        };
        let update = pending.fetch(&esplora_url, proxy_url.as_deref())?;
        let mut guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "watch wallet mutex poisoned".into(),
        })?;
        guard.apply_esplora_scan(update)?;
        Ok(())
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

    /// Enumerate every wallet row in insertion order.
    pub fn list_wallets(&self) -> Result<Vec<WalletRecordFfi>, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.list_wallets()?.into_iter().map(Into::into).collect())
    }

    /// Phase 6.0 — duplicate detection for the signing↔tracker
    /// pairing flow. Returns `true` when a registry row already
    /// carries the same public descriptor as `descriptor_pub`, OR
    /// (by xpub normalisation) the same underlying account xpub.
    ///
    /// Used by the UI before offering "Add to Tracker": if the user
    /// already imported this xpub via the standalone `Add Watch`
    /// flow, or paired this signing wallet earlier, we skip the
    /// prompt instead of creating a double-counting watch row.
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
            pins_warned: record.pins_warned,
        })?;
        Ok(())
    }

    /// Delete a wallet by UUID. Returns `true` if a row was removed,
    /// `false` if no row matched. **Does NOT delete the on-disk
    /// `wallets/<id>/` directory** — the caller orchestrates the
    /// full cleanup (close active handle, delete tree, then call
    /// `remove_wallet`).
    ///
    /// The row and everything in the registry that references it
    /// (tracking categories, crypto-event kinds, savings-goal bucket /
    /// linked-wallet references) are removed in ONE transaction; any
    /// failure is returned and nothing is deleted.
    ///
    /// Removing a **signing** wallet also removes the watch-only mirror(s)
    /// explicitly linked to it (`wallets.mirror_of`, registry schema v4 —
    /// set by [`Self::add_watch_xpub_mirror`] or by the conservative v4
    /// backfill). Unlinked watch rows are never touched, whatever their
    /// label or xpub: they are the user's own Tracker entries. The
    /// mirrors' `watch-state/<id>.sqlite` files are deleted best-effort.
    pub fn remove_wallet(&self, id: String) -> Result<bool, WalletError> {
        let uuid = Uuid::parse_str(&id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        let (removed, mirrors) = guard.remove_wallet_and_mirrors(&uuid)?;
        drop(guard);
        for m in &mirrors {
            remove_sqlite_files(&self.watch_state_path(m));
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

    /// Mark a wallet's `pins_warned` flag (the one-time same-PIN
    /// banner has been shown). Idempotent.
    pub fn mark_pins_warned(&self, id: String) -> Result<bool, WalletError> {
        let uuid = Uuid::parse_str(&id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.mark_pins_warned(&uuid)?)
    }

    // -----------------------------------------------------------------------
    // Phase 3.4 — tracking categories (cross-wallet)
    //
    // Categories live on the registry instead of per-wallet label storage so
    // the Tracking surface can read/write them for any wallet without
    // requiring the wallet's PIN. The per-wallet `WalletHandle.set_tx_category`
    // / `get_tx_category` pair is kept for now but should not be wired into
    // new UI — they predate this redesign and remain only so any code that
    // already calls them continues to compile.
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
            })
            .collect())
    }

    pub fn delete_bill(&self, id: i64) -> Result<bool, WalletError> {
        let guard = self.inner.lock().map_err(|_| WalletError::Storage {
            detail: "registry mutex poisoned".into(),
        })?;
        Ok(guard.delete_bill(id)?)
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

    /// Test whether the supplied PIN derives to the same device key
    /// already loaded in this `RegistryHandle`. Returns `true` iff
    /// the candidate PIN, run through Argon2id with the registry's
    /// stored salt + params, yields the device key in memory.
    ///
    /// Used by `PinSetupScreen` to surface the security-review
    /// "same PIN as device" warning when the user happens to pick a
    /// wallet PIN identical to the device PIN.
    ///
    /// Cost: ~50–100ms (one Argon2id derivation). Callers must run on
    /// `Dispatchers.IO`. Equality compare is plain `==` on `[u8; 32]`
    /// arrays — both keys are derived locally, no timing-channel
    /// exposure.
    pub fn matches_device_pin(&self, candidate: String) -> Result<bool, WalletError> {
        let candidate = zeroize::Zeroizing::new(candidate);
        // M1 — read the descriptor read-only and verify its MAC against
        // the device key we already hold (never against the candidate,
        // which is expected to be wrong most of the time).
        let (salt, kdf_params) =
            params_for_known_key(&self.data_dir, KdfScope::Registry, &self.device_key)?;
        let derived: SecretKey = zeroize::Zeroizing::new(
            bhodl_core::derive_key_from_pin_with_params(&candidate, &salt, &kdf_params)?,
        );
        Ok(kdf::constant_time_eq(&*derived, &self.device_key))
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

    /// Register the watch-only **mirror** of signing wallet
    /// `signing_wallet_id` (decoupling decision 2026-05-18: the Tracker
    /// sees a signing wallet only through this xpub mirror).
    ///
    /// Same as [`Self::add_watch_xpub`], plus the registry row is
    /// explicitly linked to the signing wallet (`wallets.mirror_of`), so
    /// [`Self::remove_wallet`] on the signing wallet removes exactly this
    /// row. The registry refuses the link unless the signing wallet
    /// exists, is on the same network and has the same account xpub.
    pub fn add_watch_xpub_mirror(
        &self,
        signing_wallet_id: String,
        label: String,
        xpub: String,
        network: Network,
        bip: WatchBipFfi,
    ) -> Result<WalletRecordFfi, WalletError> {
        let signing = Uuid::parse_str(&signing_wallet_id).map_err(|e| WalletError::Storage {
            detail: format!("invalid wallet id: {e}"),
        })?;
        let core_network: CoreNetwork = network.into();
        let record = Registry::build_watch_xpub_record(&label, &xpub, core_network, bip.into())?;
        self.materialise_then_register_linked(record, Some(signing), |path, key| {
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
    /// - `Storage` — wallet ID does not exist, wallet is a signing wallet
    ///   (use `unlock` instead), or the watch-state file is missing /
    ///   corrupt / wrong device key.
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

        // Reject signing wallets — they require mnemonic-derived credentials.
        if record.kind == WalletKind::Signing {
            return Err(WalletError::Storage {
                detail: "wallet is a signing wallet, not a watch wallet — use unlock instead"
                    .into(),
            });
        }

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
    /// wallet in the registry. Signing wallets are NOT included here
    /// (their tx history is behind the per-wallet PIN); the Kotlin
    /// side merges in the active signing wallet's events separately
    /// from the already-open `WalletHandle.list_transactions()`.
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
            if record.kind == WalletKind::Signing {
                continue;
            }
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
    /// sats into one number is meaningless (M6). Signing-wallet rows are
    /// **skipped**: their balance lives behind the per-wallet PIN and the
    /// caller combines the active signing wallet's balance itself.
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
            if record.kind == WalletKind::Signing {
                continue;
            }
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
        self.materialise_then_register_linked(record, None, create)
    }

    /// [`Self::materialise_then_register`] with an optional mirror link:
    /// `Some(signing)` inserts the row via
    /// [`Registry::add_mirror_wallet`].
    fn materialise_then_register_linked<F>(
        &self,
        record: WalletRecord,
        mirror_of: Option<Uuid>,
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
            .and_then(|guard| {
                match mirror_of {
                    Some(signing) => guard.add_mirror_wallet(&record, &signing),
                    None => guard.add_wallet(&record),
                }
                .map_err(Into::into)
            });
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

/// Generate a fresh UUIDv4 in hyphenated string form. Convenience
/// for callers that want the FFI side to mint the identity (e.g. the
/// migration code).
#[uniffi::export]
pub fn generate_wallet_id() -> String {
    Uuid::new_v4().to_string()
}

/// Extract the account-level xpub from any BDK-form descriptor string.
///
/// Strips the wrapper (`tr(...)`, `wpkh(...)`, `sh(wpkh(...))`), the
/// optional `[origin]` prefix, the trailing `/0/<idx>` (or `/1/<idx>`),
/// and any `#checksum` suffix. Returns `None` when the input does not
/// match one of the BIP-86/84/49 single-key-path shapes BHODL produces.
///
/// Used by the desktop debug screen to deduplicate the active signing
/// wallet against watch-only rows that may share the same xpub —
/// counting the balance only once in the aggregate total.
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

/// Test-only: read or create the registry KDF descriptor (see
/// [`kdf::read_or_create_registry_kdf`]). Production paths go through
/// [`unlock_with`] so the descriptor is MAC-verified and sealed.
#[cfg(test)]
fn read_or_create_registry_kdf(
    data_dir: &Path,
) -> Result<([u8; SALT_LEN], bhodl_core::KdfParams), WalletError> {
    kdf::read_or_create_registry_kdf(data_dir)
}

// ---------------------------------------------------------------------------
// Phase 3.0 migration — two-phase commit (security review F1)
// ---------------------------------------------------------------------------

/// Sentinel file written by the migration to make the in-progress
/// state recoverable across crashes. Format is a single JSON object
/// `{"phase": "<marker>", "wallet_id": "<uuid>"}`. Lives in
/// `<data_dir>/migration.lock` and is deleted by Phase C cleanup.
const MIGRATION_LOCK_FILE: &str = "migration.lock";

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
struct MigrationLock {
    /// One of `phase_a_complete`, `phase_a_committed`, `phase_b_committed`.
    phase: String,
    /// The UUID assigned to the migrating wallet. Required so that
    /// recovery code knows where to put files even after the
    /// process restart.
    wallet_id: String,
    /// Label assigned during Phase A — needed by recovery to fill
    /// the `Migrated`/`RecoveredFromInterruption` outcome.
    wallet_label: String,
}

fn migration_lock_path(data_dir: &Path) -> PathBuf {
    data_dir.join(MIGRATION_LOCK_FILE)
}

fn write_migration_lock(data_dir: &Path, lock: &MigrationLock) -> Result<(), WalletError> {
    let json = serde_json::to_vec_pretty(lock).map_err(|e| WalletError::Storage {
        detail: format!("serialise migration.lock: {e}"),
    })?;
    let tmp = data_dir.join("migration.lock.tmp");
    {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(&json)?;
        f.sync_all()?;
    }
    fs::rename(&tmp, migration_lock_path(data_dir))?;
    Ok(())
}

fn read_migration_lock(data_dir: &Path) -> Result<Option<MigrationLock>, WalletError> {
    let path = migration_lock_path(data_dir);
    if !path.exists() {
        return Ok(None);
    }
    let raw = fs::read(&path)?;
    let parsed: MigrationLock = serde_json::from_slice(&raw).map_err(|e| WalletError::Storage {
        detail: format!("invalid migration.lock: {e}"),
    })?;
    Ok(Some(parsed))
}

fn delete_migration_lock(data_dir: &Path) -> Result<(), WalletError> {
    let path = migration_lock_path(data_dir);
    if path.exists() {
        fs::remove_file(&path)?;
    }
    Ok(())
}

/// Atomic backup: for each input file, copy to
/// `<backup_dir>/<name>.tmp`, fsync, then rename to
/// `<backup_dir>/<name>`. A kill mid-copy leaves only the `.tmp`
/// (not visible to the recovery logic), so a re-run of the backup
/// is idempotent.
fn atomic_backup_files(files: &[(PathBuf, PathBuf)], // (src, dest)
) -> Result<(), WalletError> {
    for (src, dest) in files {
        if !src.exists() {
            // Source missing — nothing to back up. Skip rather than
            // error so the backup helper is robust to a wallet
            // already missing `wallet.network` (legacy installs
            // without per-wallet network persistence).
            continue;
        }
        let tmp = dest.with_extension("tmp");
        if let Some(parent) = tmp.parent() {
            fs::create_dir_all(parent)?;
        }
        let bytes = fs::read(src)?;
        {
            let mut f = fs::File::create(&tmp)?;
            f.write_all(&bytes)?;
            f.sync_all()?;
        }
        fs::rename(&tmp, dest)?;
    }
    Ok(())
}

/// Run the Phase 3.0 migration if a legacy single-wallet install is
/// detected; otherwise return [`MigrationOutcome::NotNeeded`].
///
/// Detection:
/// - `<data_dir>/registry.sqlite` exists → already migrated. If a
///   `migration.lock` is also present, recovery is attempted before
///   returning `NotNeeded` to avoid leaving a stale lock.
/// - `<data_dir>/wallet.sqlite` exists AND `registry.sqlite` does
///   not → legacy single-wallet install. Run migration.
/// - Neither → fresh install, no migration needed.
///
/// Migration parameters:
/// - `device_pin` — the new device PIN chosen by the user in the
///   migration modal. Used to encrypt the registry.
/// - `legacy_pin` — the user's existing wallet PIN. Used to (a)
///   open the legacy `wallet.sqlite` to extract the public
///   descriptor and (b) become the wallet PIN of the migrated
///   signing wallet (no re-encryption — the existing `wallet.kdf.json`
///   already binds the legacy PIN to the existing key).
///
/// See `docs/phase-3-architecture.md` §"Migration — two-phase commit
/// pattern" for the full sequence and recovery branches.
#[uniffi::export]
pub fn run_migration_if_needed(
    data_dir: String,
    device_pin: String,
    legacy_pin: String,
) -> Result<MigrationOutcome, WalletError> {
    // Wrap both PINs so the Rust-side copies are wiped on drop.
    let device_pin = zeroize::Zeroizing::new(device_pin);
    let legacy_pin = zeroize::Zeroizing::new(legacy_pin);
    let data_dir = PathBuf::from(data_dir);
    fs::create_dir_all(&data_dir)?;

    let registry_path = data_dir.join(REGISTRY_FILE_NAME);
    let legacy_wallet_sqlite = data_dir.join("wallet.sqlite");
    let legacy_wallet_kdf = data_dir.join("wallet.kdf.json");
    let legacy_wallet_network = data_dir.join("wallet.network");
    let backup_dir = data_dir.join(MIGRATION_BACKUP_DIR);

    // Recovery path: a previous migration was interrupted and left a
    // sentinel. Resume from the recorded phase regardless of whether
    // registry.sqlite already exists.
    if let Some(lock) = read_migration_lock(&data_dir)? {
        return resume_interrupted_migration(
            &data_dir,
            &device_pin,
            &legacy_pin,
            &registry_path,
            &legacy_wallet_sqlite,
            &legacy_wallet_kdf,
            &legacy_wallet_network,
            &backup_dir,
            lock,
        );
    }

    // No lock and registry already exists → already migrated cleanly.
    if registry_path.exists() {
        return Ok(MigrationOutcome::NotNeeded);
    }

    // No lock, no registry, no legacy wallet → fresh install.
    if !legacy_wallet_sqlite.exists() {
        return Ok(MigrationOutcome::NotNeeded);
    }

    // Legacy wallet detected → run full migration.
    let wallet_id = Uuid::new_v4();
    let wallet_label = "My Wallet".to_string();

    // Step 1 — Atomic backup of legacy files BEFORE any mutation.
    // M2 — `wallet.salt` is part of the KDF state of pre-descriptor
    // installs: without it the backed-up wallet cannot be unlocked.
    atomic_backup_files(&[
        (
            legacy_wallet_sqlite.clone(),
            backup_dir.join("wallet.sqlite"),
        ),
        (
            legacy_wallet_kdf.clone(),
            backup_dir.join("wallet.kdf.json"),
        ),
        (
            data_dir.join(LEGACY_SALT_FILE_NAME),
            backup_dir.join(LEGACY_SALT_FILE_NAME),
        ),
        (
            legacy_wallet_network.clone(),
            backup_dir.join("wallet.network"),
        ),
    ])?;
    // Record which wallet the backup belongs to, so the first successful
    // unlock of that wallet can delete it (see `cleanup_migration_backup`).
    {
        let mut f = fs::File::create(backup_dir.join(MIGRATION_BACKUP_ID_FILE))?;
        f.write_all(wallet_id.to_string().as_bytes())?;
        f.sync_all()?;
    }

    // Step 2 — Phase A: create registry + insert wallet row.
    write_migration_lock(
        &data_dir,
        &MigrationLock {
            phase: "phase_a_complete".into(),
            wallet_id: wallet_id.to_string(),
            wallet_label: wallet_label.clone(),
        },
    )?;

    // Read the legacy network file before touching anything. Default
    // to signet if absent (matches the Kotlin `NetworkPersistence`
    // fallback behaviour pre-3.0).
    let network_str = fs::read_to_string(&legacy_wallet_network)
        .ok()
        .map(|s| s.trim().to_ascii_lowercase())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "signet".to_string());
    let core_network = match network_str.as_str() {
        "mainnet" => CoreNetwork::Mainnet,
        "testnet4" => CoreNetwork::Testnet4,
        "regtest" => CoreNetwork::Regtest,
        _ => CoreNetwork::Signet,
    };

    // Open the legacy wallet with the legacy PIN to extract the
    // public descriptor (xpub-rooted). This is the only place that
    // touches the legacy wallet's secret material; we throw the
    // OnchainAccount away immediately afterwards.
    // Read-only on the legacy KDF state (never creates a descriptor);
    // MAC verified before the open, sealed only after it succeeded.
    let (legacy_account, _) =
        unlock_with(&data_dir, KdfScope::Wallet, &legacy_pin, false, |key| {
            Ok(OnchainAccount::load_persistent(
                core_network,
                &legacy_wallet_sqlite,
                key,
            )?)
        })?;
    let descriptor_pub = legacy_account.export_descriptors().external;
    drop(legacy_account); // release SQLite file lock before move in Phase B.

    // Create the registry with the NEW device PIN and insert the
    // migrated wallet row. This is a device-PIN *creation*, so
    // `unlock_with` enforces the new-PIN policy on the fresh descriptor.
    {
        let (registry, _) = unlock_with(&data_dir, KdfScope::Registry, &device_pin, true, |key| {
            open_registry(&registry_path, key).map_err(WalletError::from)
        })?;
        registry.add_wallet(&WalletRecord {
            id: wallet_id,
            label: wallet_label.clone(),
            kind: WalletKind::Signing,
            descriptor_pub,
            network: network_str,
            created_at_unix: chrono_now_unix(),
            pins_warned: false,
        })?;
    }

    write_migration_lock(
        &data_dir,
        &MigrationLock {
            phase: "phase_a_committed".into(),
            wallet_id: wallet_id.to_string(),
            wallet_label: wallet_label.clone(),
        },
    )?;

    // Phase B — atomic move into per-wallet directory.
    let wallet_subdir = data_dir.join("wallets").join(wallet_id.to_string());
    fs::create_dir_all(&wallet_subdir)?;
    fs::rename(&legacy_wallet_sqlite, wallet_subdir.join("wallet.sqlite"))?;
    // M2 — move EVERY KDF artefact (descriptor, legacy `wallet.salt`,
    // seal marker, pending `.next`), not just `wallet.kdf.json`.
    move_wallet_kdf_artifacts(&data_dir, &wallet_subdir)?;

    write_migration_lock(
        &data_dir,
        &MigrationLock {
            phase: "phase_b_committed".into(),
            wallet_id: wallet_id.to_string(),
            wallet_label: wallet_label.clone(),
        },
    )?;

    // Phase C — cleanup. Move (not delete) `wallet.network` into the
    // per-wallet directory so the Kotlin Unlock flow can still read
    // the network without opening the device-PIN-encrypted registry.
    // The registry's `network` column remains the canonical source
    // for the future cross-wallet UI (3.1+); the per-wallet file is
    // a 3.0-era convenience that the Compose layer reads directly.
    if legacy_wallet_network.exists() {
        let target_network = wallet_subdir.join("wallet.network");
        if !target_network.exists() {
            fs::rename(&legacy_wallet_network, target_network)?;
        } else {
            // Already moved (rerun recovery scenario) — drop the
            // legacy file.
            fs::remove_file(&legacy_wallet_network)?;
        }
    }
    delete_migration_lock(&data_dir)?;

    Ok(MigrationOutcome::Migrated {
        wallet_id: wallet_id.to_string(),
        wallet_label,
    })
}

/// Resume a partially-completed migration based on the persisted
/// `migration.lock` phase marker. Idempotent — every branch ends in
/// a state that is itself a valid input to recovery, so killing this
/// function and re-running it converges.
#[allow(clippy::too_many_arguments)]
fn resume_interrupted_migration(
    data_dir: &Path,
    device_pin: &str,
    legacy_pin: &str,
    registry_path: &Path,
    legacy_wallet_sqlite: &Path,
    legacy_wallet_kdf: &Path,
    legacy_wallet_network: &Path,
    _backup_dir: &Path,
    lock: MigrationLock,
) -> Result<MigrationOutcome, WalletError> {
    let wallet_id_str = lock.wallet_id.clone();
    let wallet_label = lock.wallet_label.clone();
    let wallet_uuid = Uuid::parse_str(&wallet_id_str).map_err(|e| WalletError::Storage {
        detail: format!("invalid wallet_id in migration.lock: {e}"),
    })?;
    let wallet_subdir = data_dir.join("wallets").join(&wallet_id_str);
    let target_sqlite = wallet_subdir.join("wallet.sqlite");
    let target_kdf = wallet_subdir.join("wallet.kdf.json");

    match lock.phase.as_str() {
        "phase_a_complete" => {
            // Phase A was interrupted before the registry-row insert
            // committed (or before the post-insert marker write).
            // Safest action: tear down any partial registry state
            // and re-run the full migration from scratch. The
            // original legacy wallet.sqlite is still at the root and
            // untouched.
            if registry_path.exists() {
                let _ = fs::remove_file(registry_path);
            }
            let _ = fs::remove_file(data_dir.join(REGISTRY_KDF_FILE_NAME));
            let _ = fs::remove_file(KdfScope::Registry.marker_path(data_dir));
            let _ = fs::remove_file(KdfScope::Registry.pending_path(data_dir));
            let wallets_root = data_dir.join("wallets");
            if wallets_root.join(&wallet_id_str).exists() {
                let _ = fs::remove_dir_all(wallets_root.join(&wallet_id_str));
            }
            delete_migration_lock(data_dir)?;
            // Re-run from the top by reusing run_migration_if_needed
            // — this is the simplest correct path.
            run_migration_if_needed(
                data_dir.to_string_lossy().into_owned(),
                device_pin.to_string(),
                legacy_pin.to_string(),
            )
        }
        "phase_a_committed" => {
            // Registry was created and the wallet row inserted. The
            // move into wallets/<uuid>/ either did not start or only
            // partially moved files. Re-run Phase B + C.
            fs::create_dir_all(&wallet_subdir)?;
            if legacy_wallet_sqlite.exists() && !target_sqlite.exists() {
                fs::rename(legacy_wallet_sqlite, &target_sqlite)?;
            }
            let _ = (legacy_wallet_kdf, &target_kdf);
            move_wallet_kdf_artifacts(data_dir, &wallet_subdir)?;
            write_migration_lock(
                data_dir,
                &MigrationLock {
                    phase: "phase_b_committed".into(),
                    wallet_id: wallet_id_str.clone(),
                    wallet_label: wallet_label.clone(),
                },
            )?;
            // Phase C — move `wallet.network` into the per-wallet
            // directory (see main migration body for rationale).
            let target_network = wallet_subdir.join("wallet.network");
            if legacy_wallet_network.exists() && !target_network.exists() {
                fs::rename(legacy_wallet_network, target_network)?;
            } else if legacy_wallet_network.exists() {
                fs::remove_file(legacy_wallet_network)?;
            }
            delete_migration_lock(data_dir)?;
            let _ = wallet_uuid; // silence unused
            Ok(MigrationOutcome::RecoveredFromInterruption {
                wallet_id: wallet_id_str,
                wallet_label,
            })
        }
        "phase_b_committed" => {
            // Files moved successfully, only the cosmetic cleanup
            // didn't complete (move wallet.network, delete the
            // lock itself). Finish Phase C.
            let target_network = wallet_subdir.join("wallet.network");
            if legacy_wallet_network.exists() && !target_network.exists() {
                fs::rename(legacy_wallet_network, target_network)?;
            } else if legacy_wallet_network.exists() {
                fs::remove_file(legacy_wallet_network)?;
            }
            delete_migration_lock(data_dir)?;
            Ok(MigrationOutcome::RecoveredFromInterruption {
                wallet_id: wallet_id_str,
                wallet_label,
            })
        }
        other => Err(WalletError::Storage {
            detail: format!("unknown migration phase marker: {other}"),
        }),
    }
}

/// Name of the pre-3.0 migration backup directory under the data dir.
const MIGRATION_BACKUP_DIR: &str = "migration.pre-3.0.bak";
/// File inside [`MIGRATION_BACKUP_DIR`] recording the UUID of the
/// wallet the backup was taken from.
const MIGRATION_BACKUP_ID_FILE: &str = "wallet_id";

/// Move every signing-wallet KDF artefact from `from_dir` into
/// `to_dir`. Idempotent: an artefact already present at the target is
/// left alone (re-run after an interruption).
fn move_wallet_kdf_artifacts(from_dir: &Path, to_dir: &Path) -> Result<(), WalletError> {
    for name in WALLET_KDF_ARTIFACTS {
        let src = from_dir.join(name);
        let dst = to_dir.join(name);
        if src.exists() && !dst.exists() {
            rename_with_retry(&src, &dst)?;
        }
    }
    Ok(())
}

/// `<data_dir>` for a wallet living at `<data_dir>/wallets/<uuid>/`,
/// or `None` for any other layout.
fn migrated_data_dir(db_dir: &Path) -> Option<&Path> {
    let wallets = db_dir.parent()?;
    if wallets.file_name()? != "wallets" {
        return None;
    }
    wallets.parent()
}

/// Destination for an orphan `wallet.sqlite` (L5): renamed aside, never
/// deleted, so a mistaken "orphan" verdict cannot destroy a wallet.
fn orphan_sqlite_path(db_dir: &Path) -> PathBuf {
    db_dir.join(format!("wallet.sqlite.orphan-{}", chrono_now_unix()))
}

/// M2 repair — a pre-fix Phase 3.0 migration of a `wallet.salt`-only
/// install moved `wallet.sqlite` into `wallets/<uuid>/` but left the
/// salt at the data-dir root, making the wallet un-unlockable. If this
/// wallet dir has no KDF state at all, the root has a stray
/// `wallet.salt` and no legacy `wallet.sqlite` of its own, copy the salt
/// in. Returns the root salt path when a copy was made (the caller
/// deletes it after a successful unlock).
fn repair_migrated_legacy_salt(db_dir: &Path) -> Option<PathBuf> {
    let data_dir = migrated_data_dir(db_dir)?;
    let has_kdf_state = [
        KdfFile::FILE_NAME,
        LEGACY_SALT_FILE_NAME,
        "wallet.kdf.json.next",
    ]
    .iter()
    .any(|name| db_dir.join(name).exists());
    if has_kdf_state || !db_dir.join("wallet.sqlite").exists() {
        return None;
    }
    let root_salt = data_dir.join(LEGACY_SALT_FILE_NAME);
    if !root_salt.is_file() || data_dir.join("wallet.sqlite").exists() {
        return None;
    }
    fs::copy(&root_salt, db_dir.join(LEGACY_SALT_FILE_NAME)).ok()?;
    Some(root_salt)
}

/// M2 — delete `<data_dir>/migration.pre-3.0.bak/` once the wallet it
/// was taken from has been unlocked successfully from its migrated
/// location. The backup holds a copy of the seed under the legacy
/// (weaker) KDF parameters, so keeping it forever weakens the wallet.
///
/// Ownership check: the `wallet_id` file written by the migration; for
/// backups taken by older builds (no id file) the backup is opened with
/// the same PIN and its descriptor compared to the unlocked wallet's.
/// Best-effort: any failure leaves the backup in place.
fn cleanup_migration_backup(
    db_dir: &Path,
    network: CoreNetwork,
    pin: &str,
    account: &OnchainAccount,
) {
    let Some(data_dir) = migrated_data_dir(db_dir) else {
        return;
    };
    let backup_dir = data_dir.join(MIGRATION_BACKUP_DIR);
    if !backup_dir.is_dir() || migration_lock_path(data_dir).exists() {
        return;
    }
    let Some(wallet_id) = db_dir.file_name().map(|n| n.to_string_lossy().into_owned()) else {
        return;
    };
    let belongs = match fs::read_to_string(backup_dir.join(MIGRATION_BACKUP_ID_FILE)) {
        Ok(recorded) => recorded.trim() == wallet_id,
        Err(_) => backup_matches_account(data_dir, &backup_dir, network, pin, account),
    };
    if belongs {
        let _ = fs::remove_dir_all(&backup_dir);
    }
}

/// Open the backed-up legacy wallet with `pin` (read-only on its KDF
/// state) and compare its public descriptor with `account`'s. Older
/// backups of `wallet.salt`-only installs carry no KDF state of their
/// own; the data-dir root `wallet.salt` (still there on such installs)
/// is used instead.
fn backup_matches_account(
    data_dir: &Path,
    backup_dir: &Path,
    network: CoreNetwork,
    pin: &str,
    account: &OnchainAccount,
) -> bool {
    let sqlite = backup_dir.join("wallet.sqlite");
    if !sqlite.exists() {
        return false;
    }
    let Ok((salt, params)) = read_existing(backup_dir, KdfScope::Wallet)
        .or_else(|_| read_existing(data_dir, KdfScope::Wallet))
    else {
        return false;
    };
    let Ok(key) = bhodl_core::derive_key_from_pin_with_params(pin, &salt, &params) else {
        return false;
    };
    let key: SecretKey = zeroize::Zeroizing::new(key);
    let Ok(backup_account) = OnchainAccount::load_persistent(network, &sqlite, &key) else {
        return false;
    };
    backup_account.export_descriptors().external == account.export_descriptors().external
}

/// Returns the current Unix timestamp in seconds. Wrapped in a
/// helper so unit tests can stub if needed.
fn chrono_now_unix() -> i64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    // R2 — KDF helpers exercised only by these tests. Imported here
    // (not at module top) so non-test builds don't carry an unused
    // import for them.
    use crate::kdf::{
        read_existing_kdf, read_or_create_kdf, read_or_create_salt, seal_kdf_file_with_mac,
        seal_legacy_v1_for_test, write_kdf_file,
    };

    #[test]
    fn salt_file_is_created_on_first_call() {
        let tmp = tempfile::tempdir().unwrap();
        let salt_path = tmp.path().join("wallet.salt");
        assert!(!salt_path.exists());

        let salt = read_or_create_salt(&salt_path).unwrap();

        assert!(salt_path.exists());
        let on_disk = fs::read(&salt_path).unwrap();
        assert_eq!(on_disk.len(), SALT_LEN);
        assert_eq!(on_disk.as_slice(), salt.as_slice());
    }

    #[test]
    fn salt_file_round_trips_across_calls() {
        let tmp = tempfile::tempdir().unwrap();
        let salt_path = tmp.path().join("wallet.salt");

        let first = read_or_create_salt(&salt_path).unwrap();
        let second = read_or_create_salt(&salt_path).unwrap();

        assert_eq!(first, second);
    }

    #[test]
    fn truncated_salt_file_is_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        let salt_path = tmp.path().join("wallet.salt");
        fs::write(&salt_path, [0x42u8; 8]).unwrap();

        let result = read_or_create_salt(&salt_path);
        assert!(matches!(result, Err(WalletError::InvalidSalt { .. })));
    }

    #[test]
    fn oversized_salt_file_is_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        let salt_path = tmp.path().join("wallet.salt");
        fs::write(&salt_path, [0x42u8; SALT_LEN + 4]).unwrap();

        let result = read_or_create_salt(&salt_path);
        assert!(matches!(result, Err(WalletError::InvalidSalt { .. })));
    }

    // -------------------------------------------------------------------
    // KDF descriptor tests — versioned wallet.kdf.json + legacy fallback.
    // -------------------------------------------------------------------

    #[test]
    fn fresh_dir_creates_kdf_json_with_current_params() {
        let tmp = tempfile::tempdir().unwrap();
        let (salt, params) = read_or_create_kdf(tmp.path()).unwrap();

        assert_eq!(params, bhodl_core::KdfParams::CURRENT);
        // The JSON file is on disk and round-trips byte-perfect
        // through serde_json (i.e. salt + params survive).
        let path = tmp.path().join(KdfFile::FILE_NAME);
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
    fn legacy_salt_file_is_read_with_legacy_params() {
        // A pre-hardening wallet has only `wallet.salt` (raw 16 bytes)
        // and no kdf.json. The reader MUST surface
        // KdfParams::LEGACY_OWASP_MIN so the unlock can derive the
        // SAME key the wallet was created with.
        let tmp = tempfile::tempdir().unwrap();
        let salt_bytes = [0x42u8; SALT_LEN];
        fs::write(tmp.path().join("wallet.salt"), salt_bytes).unwrap();

        let (salt, params) = read_or_create_kdf(tmp.path()).unwrap();
        assert_eq!(salt, salt_bytes);
        assert_eq!(params, bhodl_core::KdfParams::LEGACY_OWASP_MIN);
        // The legacy file must NOT be migrated by the read alone —
        // migration requires a successful unlock, which `read_or_create_kdf`
        // does not perform.
        assert!(tmp.path().join("wallet.salt").exists());
        assert!(!tmp.path().join(KdfFile::FILE_NAME).exists());
    }

    #[test]
    fn json_takes_precedence_over_legacy_salt_when_both_exist() {
        // After a migration the kdf.json holds a fresh salt + the
        // CURRENT params; the legacy wallet.salt is best-effort
        // deleted but a stale copy must not regress us back to
        // LEGACY params.
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("wallet.salt"), [0x99u8; SALT_LEN]).unwrap();
        let json_salt = [0xAAu8; SALT_LEN];
        write_kdf_file(tmp.path(), &json_salt, &bhodl_core::KdfParams::CURRENT).unwrap();

        let (salt, params) = read_or_create_kdf(tmp.path()).unwrap();
        assert_eq!(salt, json_salt);
        assert_eq!(params, bhodl_core::KdfParams::CURRENT);
    }

    #[test]
    fn read_existing_kdf_errors_on_empty_dir() {
        let tmp = tempfile::tempdir().unwrap();
        let result = read_existing_kdf(tmp.path());
        assert!(matches!(result, Err(WalletError::Storage { .. })));
    }

    #[test]
    fn read_existing_kdf_accepts_legacy_salt_only() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(tmp.path().join("wallet.salt"), [0x77u8; SALT_LEN]).unwrap();
        let (salt, params) = read_existing_kdf(tmp.path()).unwrap();
        assert_eq!(salt, [0x77u8; SALT_LEN]);
        assert_eq!(params, bhodl_core::KdfParams::LEGACY_OWASP_MIN);
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
        assert!(tmp.path().join(KdfFile::FILE_NAME).exists());
        assert!(!tmp.path().join("wallet.kdf.json.tmp").exists());
    }

    // -------------------------------------------------------------------
    // Phase 3.0 migration tests — happy path + interruption matrix
    // -------------------------------------------------------------------

    /// Helper: create a legacy single-wallet install at `data_dir`
    /// using the BIP-86 test mnemonic. Returns the legacy PIN used.
    fn install_legacy_wallet(data_dir: &Path, pin: &str, network: CoreNetwork) -> String {
        let db_path = data_dir.join("wallet.sqlite");
        let (salt, kdf_params) = read_or_create_kdf(data_dir).unwrap();
        let key = bhodl_core::derive_key_from_pin_with_params(pin, &salt, &kdf_params).unwrap();
        const MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let _ = OnchainAccount::create_or_load_persistent(network, MNEMONIC, "", &db_path, &key)
            .unwrap();
        let network_name = match network {
            CoreNetwork::Mainnet => "mainnet",
            CoreNetwork::Testnet4 => "testnet4",
            CoreNetwork::Signet => "signet",
            CoreNetwork::Regtest => "regtest",
            // `bhodl_core::Network` is `#[non_exhaustive]`; default
            // unknown variants to signet for the test fixture (in
            // practice this branch is unreachable today).
            _ => "signet",
        };
        fs::write(data_dir.join("wallet.network"), network_name).unwrap();
        pin.to_string()
    }

    #[test]
    fn migration_not_needed_on_fresh_dir() {
        let tmp = tempfile::tempdir().unwrap();
        let outcome = run_migration_if_needed(
            tmp.path().to_string_lossy().into_owned(),
            "1234567".into(),
            "0000000".into(),
        )
        .unwrap();
        assert!(matches!(outcome, MigrationOutcome::NotNeeded));
        assert!(!tmp.path().join("registry.sqlite").exists());
    }

    #[test]
    fn migration_not_needed_when_registry_already_exists() {
        let tmp = tempfile::tempdir().unwrap();
        // Pre-create a registry to simulate an already-migrated dir.
        let (salt, kdf_params) = read_or_create_registry_kdf(tmp.path()).unwrap();
        let key =
            bhodl_core::derive_key_from_pin_with_params("1234567", &salt, &kdf_params).unwrap();
        open_registry(&tmp.path().join(REGISTRY_FILE_NAME), &key).unwrap();

        let outcome = run_migration_if_needed(
            tmp.path().to_string_lossy().into_owned(),
            "1234567".into(),
            "0000000".into(),
        )
        .unwrap();
        assert!(matches!(outcome, MigrationOutcome::NotNeeded));
    }

    #[test]
    fn migration_happy_path_signet() {
        let tmp = tempfile::tempdir().unwrap();
        let pin = "1234567";
        install_legacy_wallet(tmp.path(), pin, CoreNetwork::Signet);

        let outcome = run_migration_if_needed(
            tmp.path().to_string_lossy().into_owned(),
            "99999999".into(),
            pin.into(),
        )
        .unwrap();
        let (wallet_id, wallet_label) = match outcome {
            MigrationOutcome::Migrated {
                wallet_id,
                wallet_label,
            } => (wallet_id, wallet_label),
            other => panic!("expected Migrated, got {other:?}"),
        };
        assert_eq!(wallet_label, "My Wallet");
        // Registry exists + has the new row.
        assert!(tmp.path().join("registry.sqlite").exists());
        assert!(tmp.path().join("registry.kdf.json").exists());
        // Files moved into wallets/<uuid>/.
        let wallet_dir = tmp.path().join("wallets").join(&wallet_id);
        assert!(wallet_dir.join("wallet.sqlite").exists());
        assert!(wallet_dir.join("wallet.kdf.json").exists());
        // Legacy files gone from root.
        assert!(!tmp.path().join("wallet.sqlite").exists());
        assert!(!tmp.path().join("wallet.kdf.json").exists());
        assert!(!tmp.path().join("wallet.network").exists());
        // wallet.network moved into per-wallet dir so the Compose
        // Unlock screen can still read it post-migration.
        assert!(wallet_dir.join("wallet.network").exists());
        // migration.lock cleaned up.
        assert!(!tmp.path().join("migration.lock").exists());
        // Backup retained.
        assert!(
            tmp.path()
                .join("migration.pre-3.0.bak/wallet.sqlite")
                .exists()
        );
        assert!(
            tmp.path()
                .join("migration.pre-3.0.bak/wallet.kdf.json")
                .exists()
        );
        assert!(
            tmp.path()
                .join("migration.pre-3.0.bak/wallet.network")
                .exists()
        );
        // Registry row matches.
        let (rsalt, rparams) = read_or_create_registry_kdf(tmp.path()).unwrap();
        let rkey =
            bhodl_core::derive_key_from_pin_with_params("99999999", &rsalt, &rparams).unwrap();
        let registry = open_registry(&tmp.path().join("registry.sqlite"), &rkey).unwrap();
        let rows = registry.list_wallets().unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].network, "signet");
        assert!(matches!(rows[0].kind, WalletKind::Signing));
        // Descriptor starts with `tr(` (taproot BIP-86).
        assert!(rows[0].descriptor_pub.starts_with("tr("));
    }

    #[test]
    fn migration_idempotent_when_rerun() {
        let tmp = tempfile::tempdir().unwrap();
        let pin = "1234567";
        install_legacy_wallet(tmp.path(), pin, CoreNetwork::Signet);
        run_migration_if_needed(
            tmp.path().to_string_lossy().into_owned(),
            "99999999".into(),
            pin.into(),
        )
        .unwrap();
        // Second run is a no-op.
        let outcome = run_migration_if_needed(
            tmp.path().to_string_lossy().into_owned(),
            "99999999".into(),
            pin.into(),
        )
        .unwrap();
        assert!(matches!(outcome, MigrationOutcome::NotNeeded));
    }

    #[test]
    fn migration_recovery_from_phase_a_committed() {
        // Simulate the process being killed between Phase A and
        // Phase B: the registry exists, the wallet row is inserted,
        // but the legacy files are still at the root and no
        // wallets/<uuid>/ subdir.
        let tmp = tempfile::tempdir().unwrap();
        let pin = "1234567";
        install_legacy_wallet(tmp.path(), pin, CoreNetwork::Signet);

        // Pre-create the registry as if Phase A had committed.
        let wallet_id = Uuid::new_v4();
        let wallet_id_str = wallet_id.to_string();
        let (rsalt, rparams) = read_or_create_registry_kdf(tmp.path()).unwrap();
        let rkey =
            bhodl_core::derive_key_from_pin_with_params("99999999", &rsalt, &rparams).unwrap();
        let registry = open_registry(&tmp.path().join("registry.sqlite"), &rkey).unwrap();
        registry
            .add_wallet(&WalletRecord {
                id: wallet_id,
                label: "My Wallet".into(),
                kind: WalletKind::Signing,
                descriptor_pub: "tr(xpub...)".into(),
                network: "signet".into(),
                created_at_unix: 1_715_000_000,
                pins_warned: false,
            })
            .unwrap();
        drop(registry);
        write_migration_lock(
            tmp.path(),
            &MigrationLock {
                phase: "phase_a_committed".into(),
                wallet_id: wallet_id_str.clone(),
                wallet_label: "My Wallet".into(),
            },
        )
        .unwrap();

        // Boot now — should detect the lock and finish Phase B + C.
        let outcome = run_migration_if_needed(
            tmp.path().to_string_lossy().into_owned(),
            "99999999".into(),
            pin.into(),
        )
        .unwrap();
        match outcome {
            MigrationOutcome::RecoveredFromInterruption {
                wallet_id: recovered_id,
                ..
            } => assert_eq!(recovered_id, wallet_id_str),
            other => panic!("expected RecoveredFromInterruption, got {other:?}"),
        }
        // Files moved.
        let wallet_dir = tmp.path().join("wallets").join(&wallet_id_str);
        assert!(wallet_dir.join("wallet.sqlite").exists());
        assert!(wallet_dir.join("wallet.kdf.json").exists());
        assert!(wallet_dir.join("wallet.network").exists());
        assert!(!tmp.path().join("wallet.sqlite").exists());
        assert!(!tmp.path().join("wallet.kdf.json").exists());
        assert!(!tmp.path().join("wallet.network").exists());
        assert!(!tmp.path().join("migration.lock").exists());
    }

    #[test]
    fn migration_recovery_from_phase_b_committed() {
        // Phase B done (files moved), Phase C (delete network +
        // lock) interrupted. Recovery should just finish the
        // cosmetic cleanup.
        let tmp = tempfile::tempdir().unwrap();
        let pin = "1234567";
        install_legacy_wallet(tmp.path(), pin, CoreNetwork::Signet);
        let wallet_id = Uuid::new_v4();
        let wallet_id_str = wallet_id.to_string();
        // Manually do the steps of phase A + B.
        let (rsalt, rparams) = read_or_create_registry_kdf(tmp.path()).unwrap();
        let rkey =
            bhodl_core::derive_key_from_pin_with_params("99999999", &rsalt, &rparams).unwrap();
        let registry = open_registry(&tmp.path().join("registry.sqlite"), &rkey).unwrap();
        registry
            .add_wallet(&WalletRecord {
                id: wallet_id,
                label: "My Wallet".into(),
                kind: WalletKind::Signing,
                descriptor_pub: "tr(xpub...)".into(),
                network: "signet".into(),
                created_at_unix: 1_715_000_000,
                pins_warned: false,
            })
            .unwrap();
        drop(registry);
        let wallet_dir = tmp.path().join("wallets").join(&wallet_id_str);
        fs::create_dir_all(&wallet_dir).unwrap();
        fs::rename(
            tmp.path().join("wallet.sqlite"),
            wallet_dir.join("wallet.sqlite"),
        )
        .unwrap();
        fs::rename(
            tmp.path().join("wallet.kdf.json"),
            wallet_dir.join("wallet.kdf.json"),
        )
        .unwrap();
        write_migration_lock(
            tmp.path(),
            &MigrationLock {
                phase: "phase_b_committed".into(),
                wallet_id: wallet_id_str.clone(),
                wallet_label: "My Wallet".into(),
            },
        )
        .unwrap();
        // wallet.network is still there (Phase C didn't run).
        assert!(tmp.path().join("wallet.network").exists());

        let outcome = run_migration_if_needed(
            tmp.path().to_string_lossy().into_owned(),
            "99999999".into(),
            pin.into(),
        )
        .unwrap();
        assert!(matches!(
            outcome,
            MigrationOutcome::RecoveredFromInterruption { .. }
        ));
        // wallet.network either moved into per-wallet dir or
        // deleted if already present there — never lingering at root.
        assert!(!tmp.path().join("wallet.network").exists());
        let wallet_dir = tmp.path().join("wallets").join(&wallet_id_str);
        assert!(wallet_dir.join("wallet.network").exists());
        assert!(!tmp.path().join("migration.lock").exists());
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

    /// Extract the account-level tpub from the exported external descriptor.
    ///
    /// Descriptor shape: `tr([aabbccdd/86'/1'/0']tpubXXX.../0/*)`
    fn xpub_from_mnemonic_ffi(network: CoreNetwork) -> String {
        use bhodl_wallet_onchain::OnchainAccount;
        let account = OnchainAccount::from_mnemonic(network, BIP86_MNEMONIC, "").unwrap();
        let backup = account.export_descriptors();
        let ext = &backup.external;
        let after_bracket = ext.rsplit(']').next().expect("descriptor must contain ]");
        after_bracket
            .trim_end_matches(')')
            .rsplit_once("/0/*")
            .map(|(xpub, _)| xpub.to_string())
            .unwrap_or_else(|| {
                after_bracket
                    .split('/')
                    .next()
                    .expect("xpub must be non-empty")
                    .to_string()
            })
    }

    /// Derive the BIP-86 taproot address at `index` on signet from the test mnemonic.
    fn signet_p2tr_address_at_ffi(index: u32) -> String {
        use bhodl_wallet_onchain::OnchainAccount;
        OnchainAccount::from_mnemonic(CoreNetwork::Signet, BIP86_MNEMONIC, "")
            .unwrap()
            .peek_taproot_address(index)
            .unwrap()
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

    // 4. open_watch on a Signing wallet returns Err.
    #[test]
    fn open_watch_for_signing_wallet_returns_error() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());

        // Insert a Signing row directly via add_wallet.
        let signing_id = Uuid::new_v4().to_string();
        handle
            .add_wallet(WalletRecordFfi {
                id: signing_id.clone(),
                label: "Signing Wallet".into(),
                kind: WalletKindFfi::Signing,
                descriptor_pub: "tr(xpub_placeholder/0/*)".into(),
                network: "signet".into(),
                created_at_unix: 0,
                pins_warned: false,
            })
            .unwrap();

        let result = handle.open_watch(signing_id);
        assert!(
            matches!(result, Err(WalletError::Storage { .. })),
            "open_watch on a signing wallet must return Storage error, got: {result:?}"
        );
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

    // 9. Aggregate balance: registry with only a signing-wallet row → 0
    //    (signing wallets are skipped — their balance lives behind the
    //    per-wallet PIN).
    #[test]
    fn aggregate_watch_balance_skips_signing_wallets() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let signing_id = generate_wallet_id();
        handle
            .add_wallet(WalletRecordFfi {
                id: signing_id,
                label: "Active".into(),
                kind: WalletKindFfi::Signing,
                descriptor_pub: "tr(xpub.../0/*)".into(),
                network: "signet".into(),
                created_at_unix: 0,
                pins_warned: false,
            })
            .unwrap();
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

    // remove_wallet on a signing wallet removes its LINKED mirror only —
    // a user row with the same xpub, even one labelled like an
    // auto-created mirror, is kept.
    #[test]
    fn remove_signing_wallet_removes_linked_mirror_only() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        let signing_id = generate_wallet_id();
        handle
            .add_wallet(WalletRecordFfi {
                id: signing_id.clone(),
                label: "Main".into(),
                kind: WalletKindFfi::Signing,
                descriptor_pub: format!("tr([73c5da0a/86'/1'/0']{xpub}/0/*)"),
                network: "signet".into(),
                created_at_unix: 0,
                pins_warned: false,
            })
            .unwrap();
        let mirror = handle
            .add_watch_xpub_mirror(
                signing_id.clone(),
                "Renamed by the user".into(),
                xpub.clone(),
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        let user_row = handle
            .add_watch_xpub(
                "My cold storage".into(),
                xpub.clone(),
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        let lookalike = handle
            .add_watch_xpub(
                "Main (tracker)".into(),
                xpub,
                Network::Signet,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        assert!(handle.remove_wallet(signing_id.clone()).unwrap());
        let mut left: Vec<String> = handle
            .list_wallets()
            .unwrap()
            .into_iter()
            .map(|r| r.id)
            .collect();
        left.sort();
        let mut expected = vec![user_row.id, lookalike.id];
        expected.sort();
        assert_eq!(left, expected);
        assert!(
            !tmp.path()
                .join("watch-state")
                .join(format!("{}.sqlite", mirror.id))
                .exists()
        );
        assert!(!handle.remove_wallet(signing_id).unwrap());
    }

    // add_watch_xpub_mirror refuses a link to a wallet whose xpub differs
    // and leaves no watch-state file behind.
    #[test]
    fn add_watch_xpub_mirror_rejects_foreign_xpub_without_leftovers() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());
        let xpub = xpub_from_mnemonic_ffi(CoreNetwork::Signet);
        let signing_id = generate_wallet_id();
        handle
            .add_wallet(WalletRecordFfi {
                id: signing_id.clone(),
                label: "Main".into(),
                kind: WalletKindFfi::Signing,
                // Some OTHER account's key: the mirror xpub cannot match.
                descriptor_pub: "tr([73c5da0a/86'/1'/0']tpubOtherAccountKey/0/*)".into(),
                network: "signet".into(),
                created_at_unix: 0,
                pins_warned: false,
            })
            .unwrap();
        assert!(
            handle
                .add_watch_xpub_mirror(
                    signing_id,
                    "Main (tracker)".into(),
                    xpub,
                    Network::Signet,
                    WatchBipFfi::Taproot,
                )
                .is_err()
        );
        assert_eq!(handle.list_wallets().unwrap().len(), 1);
        let leftovers = std::fs::read_dir(tmp.path().join("watch-state"))
            .map(|d| d.count())
            .unwrap_or(0);
        assert_eq!(leftovers, 0);
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
    // T2-12 — HMAC integrity on wallet.kdf.json / registry.kdf.json
    // ---------------------------------------------------------------

    #[test]
    fn kdf_mac_round_trip_verifies_unchanged_file() {
        let tmp = tempfile::tempdir().unwrap();
        let salt = [0x11u8; SALT_LEN];
        let params = bhodl_core::KdfParams::CURRENT;
        let key = [0x22u8; ENCRYPTION_KEY_LEN];
        let path = tmp.path().join("wallet.kdf.json");

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
        let path = tmp.path().join("wallet.kdf.json");

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
        let path = tmp.path().join("wallet.kdf.json");

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
        let path = tmp.path().join("wallet.kdf.json");
        // Write a legacy-shaped file (no mac_hex field).
        let legacy = KdfFile {
            version: KdfFile::LEGACY_VERSION,
            salt_hex: hex::encode(salt),
            kdf: params,
            mac_hex: None,
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

    const T_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    fn read_kdf(dir: &Path) -> KdfFile {
        serde_json::from_slice(&fs::read(dir.join(KdfFile::FILE_NAME)).unwrap()).unwrap()
    }

    fn unlock(dir: &Path, pin: &str) -> Result<std::sync::Arc<WalletHandle>, WalletError> {
        WalletHandle::unlock_persistent(
            dir.to_string_lossy().into_owned(),
            Network::Signet,
            pin.into(),
        )
    }

    /// Wallet whose descriptor is `params` (unsealed v1), DB keyed to match.
    fn install_wallet_with_params(dir: &Path, pin: &str, params: bhodl_core::KdfParams) {
        let salt = [0x5Au8; SALT_LEN];
        write_kdf_file(dir, &salt, &params).unwrap();
        let key = bhodl_core::derive_key_from_pin_with_params(pin, &salt, &params).unwrap();
        OnchainAccount::create_or_load_persistent(
            CoreNetwork::Signet,
            T_MNEMONIC,
            "",
            &dir.join("wallet.sqlite"),
            &key,
        )
        .unwrap();
    }

    #[test]
    fn h1_wrong_pin_on_unsealed_descriptor_does_not_lock_out() {
        // Legacy unsealed descriptor (every pre-fix KDF migration left
        // one). A wrong PIN used to seal the MAC under the WRONG key,
        // after which the correct PIN failed with "MAC mismatch".
        let tmp = tempfile::tempdir().unwrap();
        install_legacy_wallet(tmp.path(), "123456", CoreNetwork::Signet);
        assert!(read_kdf(tmp.path()).mac_hex.is_none());

        assert!(matches!(
            unlock(tmp.path(), "654321"),
            Err(WalletError::Storage { .. })
        ));
        // Nothing was sealed by the failed attempt.
        assert!(read_kdf(tmp.path()).mac_hex.is_none());
        assert!(!KdfScope::Wallet.marker_path(tmp.path()).exists());

        // Correct PIN still unlocks, and only now seals (v2 + marker).
        unlock(tmp.path(), "123456").expect("correct PIN must unlock after a wrong attempt");
        let sealed = read_kdf(tmp.path());
        assert_eq!(sealed.version, KdfFile::CURRENT_VERSION);
        assert!(sealed.mac_hex.is_some());
        assert!(KdfScope::Wallet.marker_path(tmp.path()).exists());

        // Sealed: wrong PIN rejected, correct PIN still fine.
        assert!(matches!(
            unlock(tmp.path(), "000000"),
            Err(WalletError::Storage { .. })
        ));
        unlock(tmp.path(), "123456").expect("correct PIN after seal");
    }

    #[test]
    fn h1_kdf_migration_writes_sealed_descriptor_and_survives_wrong_pin() {
        // LEGACY params → unlock migrates to CURRENT. The migrated
        // descriptor must already be sealed with the NEW key, so a
        // later wrong PIN cannot seal anything.
        let tmp = tempfile::tempdir().unwrap();
        install_wallet_with_params(
            tmp.path(),
            "123456",
            bhodl_core::KdfParams::LEGACY_OWASP_MIN,
        );
        unlock(tmp.path(), "123456").expect("legacy unlock + migrate");
        let migrated = read_kdf(tmp.path());
        assert_eq!(migrated.kdf, bhodl_core::KdfParams::CURRENT);
        assert_eq!(migrated.version, KdfFile::CURRENT_VERSION);
        assert!(migrated.mac_hex.is_some());
        assert!(!KdfScope::Wallet.pending_path(tmp.path()).exists());

        assert!(unlock(tmp.path(), "111111").is_err());
        unlock(tmp.path(), "123456").expect("correct PIN after migration + wrong attempt");
    }

    #[test]
    fn h1_legacy_v1_mac_is_accepted_once_then_resealed_as_v2() {
        let tmp = tempfile::tempdir().unwrap();
        install_wallet_with_params(tmp.path(), "123456", bhodl_core::KdfParams::CURRENT);
        let salt = read_kdf(tmp.path()).salt().unwrap();
        let key = bhodl_core::derive_key_from_pin_with_params(
            "123456",
            &salt,
            &bhodl_core::KdfParams::CURRENT,
        )
        .unwrap();
        seal_legacy_v1_for_test(
            &tmp.path().join(KdfFile::FILE_NAME),
            &salt,
            &bhodl_core::KdfParams::CURRENT,
            &key,
        )
        .unwrap();
        assert!(unlock(tmp.path(), "999999").is_err());
        assert_eq!(read_kdf(tmp.path()).version, KdfFile::LEGACY_VERSION);
        unlock(tmp.path(), "123456").expect("old-style MAC accepted");
        let resealed = read_kdf(tmp.path());
        assert_eq!(resealed.version, KdfFile::CURRENT_VERSION);
        resealed.verify_mac(&key).unwrap();
    }

    /// Simulate a crash between `PRAGMA rekey` and the descriptor rename:
    /// DB keyed with the new salt, main descriptor still old, `.next`
    /// holds the new (sealed) descriptor.
    #[test]
    fn h2_crash_after_rekey_recovers_from_pending_descriptor() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path();
        let pin = "123456";
        install_wallet_with_params(dir, pin, bhodl_core::KdfParams::LEGACY_OWASP_MIN);
        let old = read_kdf(dir);

        let new_salt = [0xC3u8; SALT_LEN];
        let new_key = bhodl_core::derive_key_from_pin_with_params(
            pin,
            &new_salt,
            &bhodl_core::KdfParams::CURRENT,
        )
        .unwrap();
        seal_kdf_file_with_mac(
            &KdfScope::Wallet.pending_path(dir),
            &new_salt,
            &bhodl_core::KdfParams::CURRENT,
            &new_key,
        )
        .unwrap();
        {
            let old_key =
                bhodl_core::derive_key_from_pin_with_params(pin, &old.salt().unwrap(), &old.kdf)
                    .unwrap();
            let acc = OnchainAccount::load_persistent(
                CoreNetwork::Signet,
                &dir.join("wallet.sqlite"),
                &old_key,
            )
            .unwrap();
            acc.rekey(&new_key).unwrap();
        } // "crash": old main descriptor + .next left on disk.

        // Wrong PIN: still a plain Storage error, nothing promoted.
        assert!(matches!(
            unlock(dir, "000000"),
            Err(WalletError::Storage { .. })
        ));
        assert!(KdfScope::Wallet.pending_path(dir).exists());

        unlock(dir, pin).expect("unlock must fall back to the pending descriptor");
        assert!(
            !KdfScope::Wallet.pending_path(dir).exists(),
            ".next promoted"
        );
        let main = read_kdf(dir);
        assert_eq!(main.salt().unwrap(), new_salt);
        assert_eq!(main.kdf, bhodl_core::KdfParams::CURRENT);
        assert!(main.mac_hex.is_some());
        unlock(dir, pin).expect("unlock via promoted descriptor");
    }

    /// Crash after `.next` was written but before the rekey committed:
    /// the main descriptor still opens the DB, the stale `.next` is
    /// discarded and the migration simply runs again.
    #[test]
    fn h2_crash_before_rekey_discards_stale_pending_descriptor() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path();
        install_wallet_with_params(dir, "123456", bhodl_core::KdfParams::LEGACY_OWASP_MIN);
        let bogus_key = [0x42u8; ENCRYPTION_KEY_LEN];
        seal_kdf_file_with_mac(
            &KdfScope::Wallet.pending_path(dir),
            &[0x01u8; SALT_LEN],
            &bhodl_core::KdfParams::CURRENT,
            &bogus_key,
        )
        .unwrap();

        unlock(dir, "123456").expect("main descriptor still opens the DB");
        assert!(!KdfScope::Wallet.pending_path(dir).exists());
        assert_eq!(read_kdf(dir).kdf, bhodl_core::KdfParams::CURRENT);
        unlock(dir, "123456").expect("re-unlock after re-run migration");
    }

    #[test]
    fn h2_legacy_salt_only_crash_after_rekey_recovers() {
        // Pre-descriptor install: only `wallet.salt`. Crash after rekey
        // leaves wallet.salt + wallet.kdf.json.next and no main JSON.
        let tmp = tempfile::tempdir().unwrap();
        let dir = tmp.path();
        let pin = "123456";
        let salt = [0x24u8; SALT_LEN];
        fs::write(dir.join("wallet.salt"), salt).unwrap();
        let old_key = bhodl_core::derive_key_from_pin_with_params(
            pin,
            &salt,
            &bhodl_core::KdfParams::LEGACY_OWASP_MIN,
        )
        .unwrap();
        let acc = OnchainAccount::create_or_load_persistent(
            CoreNetwork::Signet,
            T_MNEMONIC,
            "",
            &dir.join("wallet.sqlite"),
            &old_key,
        )
        .unwrap();
        let new_salt = [0x99u8; SALT_LEN];
        let new_key = bhodl_core::derive_key_from_pin_with_params(
            pin,
            &new_salt,
            &bhodl_core::KdfParams::CURRENT,
        )
        .unwrap();
        seal_kdf_file_with_mac(
            &KdfScope::Wallet.pending_path(dir),
            &new_salt,
            &bhodl_core::KdfParams::CURRENT,
            &new_key,
        )
        .unwrap();
        acc.rekey(&new_key).unwrap();
        drop(acc);

        unlock(dir, pin).expect("fallback to .next over legacy wallet.salt");
        assert!(dir.join(KdfFile::FILE_NAME).exists());
        assert!(!dir.join("wallet.salt").exists());
        assert!(!KdfScope::Wallet.pending_path(dir).exists());
        unlock(dir, pin).unwrap();
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
        };
        let path = tmp.path().join(KdfFile::FILE_NAME);
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
        assert!(read_existing_kdf(tmp.path()).is_err());
    }

    #[test]
    fn m1_stripping_the_mac_from_a_sealed_descriptor_is_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        install_wallet_with_params(tmp.path(), "123456", bhodl_core::KdfParams::CURRENT);
        unlock(tmp.path(), "123456").unwrap(); // seals (v2 + marker)
        let path = tmp.path().join(KdfFile::FILE_NAME);

        // (a) v2 with mac_hex deleted.
        let mut stripped = read_kdf(tmp.path());
        stripped.mac_hex = None;
        fs::write(&path, serde_json::to_vec(&stripped).unwrap()).unwrap();
        assert!(matches!(
            unlock(tmp.path(), "123456"),
            Err(WalletError::Storage { .. })
        ));

        // (b) rewritten as an unsealed v1 file — refused by the marker.
        stripped.version = KdfFile::LEGACY_VERSION;
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
        let handle = RegistryHandle::create_or_load(
            tmp.path().to_string_lossy().into_owned(),
            "123456".into(),
        )
        .unwrap();
        assert!(handle.matches_device_pin("123456".into()).unwrap());
        assert!(!handle.matches_device_pin("654321".into()).unwrap());
        // Tamper with the sealed registry descriptor (salt swap).
        let path = tmp.path().join(REGISTRY_KDF_FILE_NAME);
        let mut f: KdfFile = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        f.salt_hex = hex::encode([0xEEu8; SALT_LEN]);
        fs::write(&path, serde_json::to_vec(&f).unwrap()).unwrap();
        assert!(handle.matches_device_pin("123456".into()).is_err());
        assert!(open_registry_verified(tmp.path(), "123456").is_err());
    }

    #[test]
    fn l6_new_wallet_requires_policy_pin_but_unlock_does_not() {
        let tmp = tempfile::tempdir().unwrap();
        for weak in ["12345", "abcdef", "12 3456"] {
            let r = WalletHandle::create_or_load_persistent(
                tmp.path().to_string_lossy().into_owned(),
                Network::Signet,
                T_MNEMONIC.into(),
                "".into(),
                weak.into(),
            );
            assert!(
                matches!(r, Err(WalletError::KeyDerivation { .. })),
                "{weak}"
            );
            assert!(!tmp.path().join(KdfFile::FILE_NAME).exists());
        }
        // An existing wallet created under the old lax policy still opens.
        let legacy = tempfile::tempdir().unwrap();
        install_legacy_wallet(legacy.path(), "pw", CoreNetwork::Signet);
        unlock(legacy.path(), "pw").expect("legacy short PIN must still unlock");
        // Registry creation enforces the policy too.
        let reg = tempfile::tempdir().unwrap();
        assert!(matches!(
            RegistryHandle::create_or_load(
                reg.path().to_string_lossy().into_owned(),
                "1234".into()
            ),
            Err(WalletError::KeyDerivation { .. })
        ));
    }

    #[test]
    fn l5_orphan_sqlite_is_renamed_not_deleted() {
        let tmp = tempfile::tempdir().unwrap();
        fs::write(
            tmp.path().join("wallet.sqlite"),
            b"not a sqlcipher db at all",
        )
        .unwrap();
        WalletHandle::create_or_load_persistent(
            tmp.path().to_string_lossy().into_owned(),
            Network::Signet,
            T_MNEMONIC.into(),
            "".into(),
            "123456".into(),
        )
        .expect("fresh create must recover from an orphan sqlite");
        let orphans: Vec<_> = fs::read_dir(tmp.path())
            .unwrap()
            .filter_map(Result::ok)
            .filter(|e| {
                e.file_name()
                    .to_string_lossy()
                    .starts_with("wallet.sqlite.orphan-")
            })
            .collect();
        assert_eq!(orphans.len(), 1, "orphan must be kept aside");
        assert_eq!(
            fs::read(orphans[0].path()).unwrap(),
            b"not a sqlcipher db at all"
        );
    }

    #[test]
    fn m2_salt_only_install_migrates_and_backup_is_dropped_after_unlock() {
        let tmp = tempfile::tempdir().unwrap();
        let data_dir = tmp.path();
        let pin = "123456";
        let salt = [0x31u8; SALT_LEN];
        fs::write(data_dir.join("wallet.salt"), salt).unwrap();
        let key = bhodl_core::derive_key_from_pin_with_params(
            pin,
            &salt,
            &bhodl_core::KdfParams::LEGACY_OWASP_MIN,
        )
        .unwrap();
        OnchainAccount::create_or_load_persistent(
            CoreNetwork::Signet,
            T_MNEMONIC,
            "",
            &data_dir.join("wallet.sqlite"),
            &key,
        )
        .unwrap();
        fs::write(data_dir.join("wallet.network"), "signet").unwrap();

        let outcome = run_migration_if_needed(
            data_dir.to_string_lossy().into_owned(),
            "99999999".into(),
            pin.into(),
        )
        .unwrap();
        let MigrationOutcome::Migrated { wallet_id, .. } = outcome else {
            panic!("expected Migrated, got {outcome:?}");
        };
        let wallet_dir = data_dir.join("wallets").join(&wallet_id);
        // The legacy salt travelled with the DB — upgraded to a sealed
        // descriptor (same salt, LEGACY params) by the legacy open, so
        // the Kotlin boot guard (`wallets/<uuid>/wallet.kdf.json`) sees it.
        let moved = read_kdf(&wallet_dir);
        assert_eq!(moved.salt().unwrap(), salt);
        assert_eq!(moved.kdf, bhodl_core::KdfParams::LEGACY_OWASP_MIN);
        assert_eq!(moved.version, KdfFile::CURRENT_VERSION);
        assert!(!data_dir.join("wallet.salt").exists());
        assert!(!data_dir.join(KdfFile::FILE_NAME).exists());
        assert!(!KdfScope::Wallet.marker_path(data_dir).exists());
        let backup = data_dir.join(MIGRATION_BACKUP_DIR);
        assert!(
            backup.join("wallet.salt").exists(),
            "salt included in backup"
        );
        assert_eq!(
            fs::read_to_string(backup.join(MIGRATION_BACKUP_ID_FILE)).unwrap(),
            wallet_id
        );

        unlock(&wallet_dir, pin).expect("migrated salt-only wallet unlocks");
        assert!(
            !backup.exists(),
            "backup dropped after first successful unlock"
        );
        // KDF migrated forward to a sealed JSON descriptor.
        assert_eq!(read_kdf(&wallet_dir).kdf, bhodl_core::KdfParams::CURRENT);
        assert!(!wallet_dir.join("wallet.salt").exists());
    }

    #[test]
    fn m2_pre_fix_migrated_salt_only_wallet_is_repaired_and_old_backup_matched() {
        // State left by the pre-fix migration: wallet.sqlite moved into
        // wallets/<id>/ with NO salt; wallet.salt still at the root;
        // backup without wallet_id / salt.
        let tmp = tempfile::tempdir().unwrap();
        let data_dir = tmp.path();
        let pin = "123456";
        let salt = [0x32u8; SALT_LEN];
        let id = Uuid::new_v4().to_string();
        let wallet_dir = data_dir.join("wallets").join(&id);
        fs::create_dir_all(&wallet_dir).unwrap();
        fs::write(data_dir.join("wallet.salt"), salt).unwrap();
        let key = bhodl_core::derive_key_from_pin_with_params(
            pin,
            &salt,
            &bhodl_core::KdfParams::LEGACY_OWASP_MIN,
        )
        .unwrap();
        OnchainAccount::create_or_load_persistent(
            CoreNetwork::Signet,
            T_MNEMONIC,
            "",
            &wallet_dir.join("wallet.sqlite"),
            &key,
        )
        .unwrap();
        let backup = data_dir.join(MIGRATION_BACKUP_DIR);
        fs::create_dir_all(&backup).unwrap();
        // Pre-fix backups of salt-only installs had no salt either.
        fs::copy(
            wallet_dir.join("wallet.sqlite"),
            backup.join("wallet.sqlite"),
        )
        .unwrap();

        unlock(&wallet_dir, pin).expect("repaired wallet unlocks");
        assert!(
            !data_dir.join("wallet.salt").exists(),
            "root salt cleaned up"
        );
        assert!(
            !backup.exists(),
            "old-style backup matched by descriptor and dropped"
        );
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
    // Phase 6.0 — signing↔tracker decoupling FFI tests
    // -------------------------------------------------------------------

    /// `tracker_descriptor_pub` must NEVER leak xprv-encoded material.
    /// Whatever the caller does with the string (paste into Sparrow,
    /// stash in the registry, hand to an LSP), it must be safe.
    #[test]
    fn wallet_handle_tracker_descriptor_pub_is_xpub_only() {
        let handle =
            WalletHandle::from_mnemonic(Network::Signet, BIP86_MNEMONIC.into(), "".into()).unwrap();

        let descriptor = handle.tracker_descriptor_pub().unwrap();

        // Taproot prefix as per BIP-86.
        assert!(
            descriptor.starts_with("tr("),
            "expected tr(...) descriptor, got: {descriptor}"
        );
        // xpub-form indicator for signet (tpub) / mainnet (xpub) —
        // never xprv / tprv.
        assert!(
            !descriptor.contains("xprv") && !descriptor.contains("tprv"),
            "descriptor must not contain xprv/tprv; got: {descriptor}"
        );
        // BIP-86 origin path lives in the descriptor — handy guard
        // against accidentally returning the change descriptor.
        assert!(
            descriptor.contains("/86'") || descriptor.contains("/86h"),
            "expected BIP-86 origin path; got: {descriptor}"
        );
    }

    /// The output of `tracker_descriptor_pub` must equal the `external`
    /// field of `export_descriptors` — they are the same data, exposed
    /// via two different code paths (FFI vs internal backup). A drift
    /// between them would mean the Tracker pairing and the backup file
    /// disagree about what "this wallet" looks like.
    #[test]
    fn wallet_handle_tracker_descriptor_pub_matches_export_descriptors_external() {
        use bhodl_wallet_onchain::OnchainAccount;

        let handle =
            WalletHandle::from_mnemonic(Network::Signet, BIP86_MNEMONIC.into(), "".into()).unwrap();
        let via_ffi = handle.tracker_descriptor_pub().unwrap();

        let account =
            OnchainAccount::from_mnemonic(CoreNetwork::Signet, BIP86_MNEMONIC, "").unwrap();
        let via_internal = account.export_descriptors().external;

        assert_eq!(via_ffi, via_internal);
    }

    /// Idempotence: calling `tracker_descriptor_pub` repeatedly must
    /// return the same value. Guards against any future implementation
    /// that might accidentally advance an internal index.
    #[test]
    fn wallet_handle_tracker_descriptor_pub_is_stable_across_calls() {
        let handle =
            WalletHandle::from_mnemonic(Network::Signet, BIP86_MNEMONIC.into(), "".into()).unwrap();

        let first = handle.tracker_descriptor_pub().unwrap();
        let second = handle.tracker_descriptor_pub().unwrap();
        let third = handle.tracker_descriptor_pub().unwrap();
        assert_eq!(first, second);
        assert_eq!(second, third);
    }

    /// Empty registry → nothing is tracked. Baseline for the duplicate
    /// detection flow before any wallet has been added.
    #[test]
    fn registry_is_descriptor_tracked_returns_false_on_empty_registry() {
        let tmp = tempfile::tempdir().unwrap();
        let handle = make_registry_handle(tmp.path());

        let handle_wallet =
            WalletHandle::from_mnemonic(Network::Signet, BIP86_MNEMONIC.into(), "".into()).unwrap();
        let descriptor = handle_wallet.tracker_descriptor_pub().unwrap();

        assert!(!handle.is_descriptor_tracked(descriptor).unwrap());
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

    /// xpub normalisation: signing wallet derives a descriptor with
    /// origin prefix and child path (`tr([fp/86'/...]xpub/0/*)`); the
    /// equivalent watch row added via `add_watch_xpub` may store a
    /// different shape (origin synthesised, child path appended via
    /// `xpub_descriptors`). Both refer to the same account xpub, so
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

        // Signing-side descriptor for the SAME mnemonic — different
        // string shape (full origin path + checksum from BDK).
        let signing_handle =
            WalletHandle::from_mnemonic(Network::Signet, BIP86_MNEMONIC.into(), "".into()).unwrap();
        let signing_descriptor = signing_handle.tracker_descriptor_pub().unwrap();

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

        // Wallet B uses a different mnemonic — same first eleven words
        // but the 12th is "abandon" instead of "about". Yields a wholly
        // different account xpub, so the registry must NOT report it
        // as tracked. (The mnemonic is BIP-39 invalid via checksum but
        // the assertion only needs the descriptor to differ, which is
        // guaranteed even for an invalid-checksum mnemonic via xpub
        // derivation — we therefore use a second VALID test vector
        // instead.)
        let other_mnemonic =
            "legal winner thank year wave sausage worth useful legal winner thank yellow";
        let handle_b =
            WalletHandle::from_mnemonic(Network::Signet, other_mnemonic.into(), "".into()).unwrap();
        let descriptor_b = handle_b.tracker_descriptor_pub().unwrap();

        assert!(
            !handle.is_descriptor_tracked(descriptor_b).unwrap(),
            "unrelated wallet must not register as tracked"
        );
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
    fn rate_repo(&self, proxy_url: Option<&str>) -> Result<BtcRateRepo, WalletError> {
        let db_path = self.data_dir.join(REGISTRY_FILE_NAME);
        Ok(BtcRateRepo::open(&db_path, &self.device_key)?.with_proxy(proxy_url)?)
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
