//! KDF descriptor + salt persistence for BHODL.
//!
//! R2 — extracted from `lib.rs` so the FFI surface file is no longer a
//! God Object. This module owns the on-disk `wallet.kdf.json` /
//! `registry.kdf.json` descriptor format, the Argon2id parameter
//! resolution / forward-migration helpers, the HMAC-SHA256 anti-tamper
//! seal, and the legacy raw-binary `wallet.salt` compatibility path.
//!
//! ## Unlock protocol (2026-10 security audit, H1/H2/M1)
//!
//! Every PIN-keyed store (signing wallet, device registry) is opened
//! through [`unlock_with`]:
//!
//! 1. Read the descriptor, reject unknown versions and out-of-bounds
//!    Argon2id parameters (no 4 TiB allocation / multi-hour hang from a
//!    tampered file), and refuse a MAC-less descriptor once the store has
//!    been sealed (`*.kdf.sealed` marker).
//! 2. Derive the key, verify the MAC if one is present, then let the
//!    caller open the database with that key.
//! 3. Only AFTER the database accepted the key is the descriptor sealed
//!    (v2, domain-separated MAC). Sealing before the open let a single
//!    wrong-PIN attempt bind the MAC to the wrong key and lock the user
//!    out permanently (H1).
//!
//! A KDF migration ([`migrate_kdf_if_stale`]) writes the new descriptor
//! to `<descriptor>.next` (sealed, fsynced) BEFORE `PRAGMA rekey`, and
//! renames it over the main descriptor afterwards. If the process dies
//! between the two, or the rename keeps failing (Windows AV / indexer
//! sharing violation), the next unlock falls back to the `.next`
//! descriptor when the main one cannot open the database, and promotes
//! it (H2).

use std::{
    ffi::OsString,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    time::Duration,
};

use bhodl_core::{KdfParams, SALT_LEN, generate_salt};
use bhodl_wallet_onchain::{ENCRYPTION_KEY_LEN, OnchainAccount, REGISTRY_KDF_FILE_NAME};
use zeroize::Zeroizing;

use crate::WalletError;

/// A derived SQLCipher key that is wiped when it drops.
pub(crate) type SecretKey = Zeroizing<[u8; ENCRYPTION_KEY_LEN]>;

/// HMAC domain-separation label: the descriptor MAC key is
/// `HMAC-SHA256(sqlcipher_key, MAC_KEY_LABEL)`, never the raw database
/// key itself.
const MAC_KEY_LABEL: &[u8] = b"bhodl-kdf-mac-v1";

/// How many times a descriptor rename is retried before giving up.
/// Windows Defender / the search indexer briefly hold freshly written
/// files open, which makes `MoveFileEx` fail with a sharing violation.
const RENAME_ATTEMPTS: u32 = 6;

/// On-disk descriptor of the KDF state for a wallet.
///
/// Persisted as `wallet.kdf.json` next to `wallet.sqlite` (or
/// `registry.kdf.json` next to `registry.sqlite`).
///
/// Format:
/// ```json
/// {
///   "version": 2,
///   "salt_hex": "<32 hex chars = 16 bytes>",
///   "kdf": { "m_cost_kib": 65536, "t_cost": 3, "p_cost": 4 },
///   "mac_hex": "<64 hex chars>"
/// }
/// ```
///
/// Versions:
/// - `1` — legacy. `mac_hex` optional; when present it is HMAC-SHA256
///   keyed directly with the SQLCipher key. A v1 file without MAC is
///   only accepted while the store has never been sealed (no marker).
///   Fresh descriptors are written as v1 without MAC and sealed right
///   after the first successful open.
/// - `2` — sealed. `mac_hex` mandatory, keyed with the domain-separated
///   MAC key (see [`MAC_KEY_LABEL`]).
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub(crate) struct KdfFile {
    pub(crate) version: u32,
    pub(crate) salt_hex: String,
    pub(crate) kdf: KdfParams,
    /// HMAC-SHA256 over `(salt || serialised_kdf_params)`. Detects
    /// tampering of the descriptor on disk (e.g. a parameter downgrade).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) mac_hex: Option<String>,
}

impl KdfFile {
    /// Pre-seal format (MAC optional, legacy MAC keying).
    pub(crate) const LEGACY_VERSION: u32 = 1;
    /// Sealed format written by this build after every successful open.
    pub(crate) const CURRENT_VERSION: u32 = 2;
    pub(crate) const FILE_NAME: &'static str = "wallet.kdf.json";

    pub(crate) fn salt(&self) -> Result<[u8; SALT_LEN], WalletError> {
        let bytes = hex::decode(&self.salt_hex).map_err(|_| WalletError::InvalidSalt {
            expected: SALT_LEN as u32,
            actual: 0,
        })?;
        if bytes.len() != SALT_LEN {
            return Err(WalletError::InvalidSalt {
                expected: SALT_LEN as u32,
                actual: bytes.len() as u32,
            });
        }
        let mut out = [0u8; SALT_LEN];
        out.copy_from_slice(&bytes);
        Ok(out)
    }

    /// Unsealed descriptor for a brand-new store (sealed after the
    /// first successful open).
    fn unsealed(salt: &[u8; SALT_LEN], kdf: &KdfParams) -> Self {
        Self {
            version: Self::LEGACY_VERSION,
            salt_hex: hex::encode(salt),
            kdf: *kdf,
            mac_hex: None,
        }
    }

    /// Sealed (v2) descriptor whose MAC is keyed from [key].
    fn sealed(salt: &[u8; SALT_LEN], kdf: &KdfParams, key: &[u8; ENCRYPTION_KEY_LEN]) -> Self {
        Self {
            version: Self::CURRENT_VERSION,
            salt_hex: hex::encode(salt),
            kdf: *kdf,
            mac_hex: Some(hex::encode(compute_kdf_mac(key, salt, kdf))),
        }
    }

    /// Structural validation of a descriptor read from disk, BEFORE any
    /// key derivation runs on its parameters.
    ///
    /// `seal_required` is true when the store was sealed before (marker
    /// present) or the file is a `.next` descriptor (always sealed).
    fn validate(&self, seal_required: bool) -> Result<(), WalletError> {
        self.kdf.validate_bounds()?;
        self.salt()?;
        match self.version {
            Self::LEGACY_VERSION if self.mac_hex.is_none() && seal_required => {
                Err(WalletError::Storage {
                    detail:
                        "kdf descriptor integrity check failed (MAC missing on a sealed descriptor)"
                            .into(),
                })
            }
            Self::LEGACY_VERSION => Ok(()),
            Self::CURRENT_VERSION if self.mac_hex.is_none() => Err(WalletError::Storage {
                detail: "kdf descriptor integrity check failed (MAC missing)".into(),
            }),
            Self::CURRENT_VERSION => Ok(()),
            other => Err(WalletError::Storage {
                detail: format!("unsupported kdf descriptor version {other}"),
            }),
        }
    }

    /// Verify the embedded MAC against [key]. Returns Ok when the MAC is
    /// absent (unsealed v1 file — [`Self::validate`] already refused this
    /// when a seal is mandatory) OR present and matching.
    pub(crate) fn verify_mac(&self, key: &[u8; ENCRYPTION_KEY_LEN]) -> Result<(), WalletError> {
        let Some(mac_hex) = &self.mac_hex else {
            return Ok(());
        };
        let salt = self.salt()?;
        let expected = if self.version == Self::LEGACY_VERSION {
            compute_kdf_mac_legacy(key, &salt, &self.kdf)
        } else {
            compute_kdf_mac(key, &salt, &self.kdf)
        };
        let provided = hex::decode(mac_hex).map_err(|_| WalletError::Storage {
            detail: "kdf descriptor MAC is not valid hex".into(),
        })?;
        // Constant-time comparison to avoid timing side channels.
        if !constant_time_eq(&expected, &provided) {
            return Err(WalletError::Storage {
                detail: "kdf descriptor integrity check failed (MAC mismatch — wrong PIN or file tampered)"
                    .into(),
            });
        }
        Ok(())
    }

    /// True when the file is not in the current sealed format.
    fn needs_reseal(&self) -> bool {
        self.version != Self::CURRENT_VERSION || self.mac_hex.is_none()
    }
}

/// Domain-separated MAC key: `HMAC-SHA256(key, MAC_KEY_LABEL)`.
fn mac_key(key: &[u8; ENCRYPTION_KEY_LEN]) -> Zeroizing<[u8; 32]> {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;
    let mut mac = <Hmac<Sha256>>::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(MAC_KEY_LABEL);
    let mut out = Zeroizing::new([0u8; 32]);
    out.copy_from_slice(&mac.finalize().into_bytes());
    out
}

/// v2 MAC: HMAC-SHA256 keyed with [`mac_key`] over the raw salt bytes
/// followed by the deterministic JSON serialisation of `kdf`.
fn compute_kdf_mac(
    key: &[u8; ENCRYPTION_KEY_LEN],
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
) -> Vec<u8> {
    let k = mac_key(key);
    hmac_salt_params(k.as_slice(), salt, kdf)
}

/// v1 MAC (pre-2026-10): same message, keyed directly with the SQLCipher
/// key. Only ever verified — a successful open reseals as v2.
fn compute_kdf_mac_legacy(
    key: &[u8; ENCRYPTION_KEY_LEN],
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
) -> Vec<u8> {
    hmac_salt_params(key, salt, kdf)
}

fn hmac_salt_params(key: &[u8], salt: &[u8; SALT_LEN], kdf: &KdfParams) -> Vec<u8> {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;
    let mut mac = <Hmac<Sha256>>::new_from_slice(key).expect("HMAC accepts any key length");
    mac.update(salt);
    // serde_json output for a struct is deterministic (stable field
    // order). The MAC is over the exact bytes the integrity check
    // will see again on read.
    let kdf_bytes = serde_json::to_vec(kdf).expect("KdfParams serialises infallibly");
    mac.update(&kdf_bytes);
    mac.finalize().into_bytes().to_vec()
}

/// Constant-time byte slice equality. We don't pull in `subtle` —
/// this five-line version is sufficient for the 32-byte MAC compare.
pub(crate) fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff: u8 = 0;
    for (x, y) in a.iter().zip(b.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

// ---------------------------------------------------------------------------
// Scopes + file layout
// ---------------------------------------------------------------------------

/// Which PIN-keyed store a descriptor belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum KdfScope {
    /// `wallet.kdf.json` (+ legacy `wallet.salt`) next to `wallet.sqlite`.
    Wallet,
    /// `registry.kdf.json` next to `registry.sqlite`. No legacy salt.
    Registry,
}

impl KdfScope {
    fn file_name(self) -> &'static str {
        match self {
            Self::Wallet => KdfFile::FILE_NAME,
            Self::Registry => REGISTRY_KDF_FILE_NAME,
        }
    }

    fn legacy_salt_name(self) -> Option<&'static str> {
        match self {
            Self::Wallet => Some(LEGACY_SALT_FILE_NAME),
            Self::Registry => None,
        }
    }

    fn marker_name(self) -> &'static str {
        match self {
            Self::Wallet => WALLET_SEALED_MARKER,
            Self::Registry => "registry.kdf.sealed",
        }
    }

    pub(crate) fn descriptor_path(self, dir: &Path) -> PathBuf {
        dir.join(self.file_name())
    }

    /// `<descriptor>.next` — descriptor of a KDF migration whose rekey
    /// may or may not have committed.
    pub(crate) fn pending_path(self, dir: &Path) -> PathBuf {
        dir.join(format!("{}.next", self.file_name()))
    }

    /// Marker recording that this store has been sealed at least once,
    /// so a MAC-less descriptor is refused from then on.
    pub(crate) fn marker_path(self, dir: &Path) -> PathBuf {
        dir.join(self.marker_name())
    }
}

/// Legacy raw-binary salt file of pre-descriptor wallets.
pub(crate) const LEGACY_SALT_FILE_NAME: &str = "wallet.salt";
const WALLET_SEALED_MARKER: &str = "wallet.kdf.sealed";

/// Every file that carries the KDF state of a signing wallet. A
/// migration that relocates `wallet.sqlite` must relocate all of them,
/// or the moved wallet cannot be unlocked.
pub(crate) const WALLET_KDF_ARTIFACTS: [&str; 4] = [
    KdfFile::FILE_NAME,
    LEGACY_SALT_FILE_NAME,
    WALLET_SEALED_MARKER,
    "wallet.kdf.json.next",
];

// ---------------------------------------------------------------------------
// Unlock
// ---------------------------------------------------------------------------

/// Where the key that opened the store came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum KdfSource {
    /// The main JSON descriptor.
    Descriptor,
    /// Legacy `wallet.salt` (implicitly `LEGACY_OWASP_MIN`).
    LegacySalt,
    /// A descriptor generated by this call for a brand-new store.
    Fresh,
    /// The `.next` descriptor of an interrupted KDF migration.
    Pending,
}

/// Result of a successful [`unlock_with`].
pub(crate) struct UnlockedKdf {
    pub(crate) params: KdfParams,
    pub(crate) key: SecretKey,
    pub(crate) source: KdfSource,
    salt: [u8; SALT_LEN],
    needs_seal: bool,
}

struct Candidate {
    salt: [u8; SALT_LEN],
    params: KdfParams,
    file: Option<KdfFile>,
    source: KdfSource,
}

fn read_descriptor(path: &Path, seal_required: bool) -> Result<KdfFile, WalletError> {
    let raw = fs::read(path)?;
    let name = path
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_default();
    let parsed: KdfFile = serde_json::from_slice(&raw).map_err(|e| WalletError::Storage {
        detail: format!("invalid {name}: {e}"),
    })?;
    parsed.validate(seal_required)?;
    Ok(parsed)
}

fn primary_candidate(
    dir: &Path,
    scope: KdfScope,
    pin: &str,
    sealed_before: bool,
    create_if_missing: bool,
) -> Result<Option<Candidate>, WalletError> {
    let path = scope.descriptor_path(dir);
    if path.exists() {
        let file = read_descriptor(&path, sealed_before)?;
        return Ok(Some(Candidate {
            salt: file.salt()?,
            params: file.kdf,
            file: Some(file),
            source: KdfSource::Descriptor,
        }));
    }
    if let Some(legacy) = scope.legacy_salt_name() {
        let legacy_path = dir.join(legacy);
        if legacy_path.exists() {
            return Ok(Some(Candidate {
                salt: read_existing_salt(&legacy_path)?,
                params: KdfParams::LEGACY_OWASP_MIN,
                file: None,
                source: KdfSource::LegacySalt,
            }));
        }
    }
    if !create_if_missing {
        return Ok(None);
    }
    // Brand-new store: this is a PIN *creation*, so the new-PIN policy
    // applies (unlock paths only require a non-empty PIN).
    bhodl_core::validate_new_pin(pin)?;
    let salt = generate_salt()?;
    let file = KdfFile::unsealed(&salt, &KdfParams::CURRENT);
    write_descriptor_file(&path, &file)?;
    // A marker without a descriptor is a leftover of a wiped store.
    let _ = fs::remove_file(scope.marker_path(dir));
    Ok(Some(Candidate {
        salt,
        params: KdfParams::CURRENT,
        file: Some(file),
        source: KdfSource::Fresh,
    }))
}

/// Derive the key for the store at `dir` from `pin` and open it with
/// `open`, then seal / promote the descriptor — in that order.
///
/// - `create_if_missing`: when neither descriptor nor legacy salt (nor a
///   pending `.next`) exists, create a fresh descriptor with
///   [`KdfParams::CURRENT`] (enforcing the new-PIN policy). Otherwise a
///   missing descriptor is a `Storage` error.
/// - `open` receives the derived key and must actually open the
///   database: its success is the only proof that the PIN is right, and
///   only then is the descriptor sealed with that key.
/// - If the main descriptor fails (wrong key, MAC mismatch, corrupt) and
///   a `.next` descriptor from an interrupted KDF migration exists, it is
///   tried next and, on success, promoted to the main descriptor.
///
/// On failure the error of the FIRST candidate is returned, so a wrong
/// PIN surfaces exactly as before (`Storage`).
pub(crate) fn unlock_with<T>(
    dir: &Path,
    scope: KdfScope,
    pin: &str,
    create_if_missing: bool,
    mut open: impl FnMut(&[u8; ENCRYPTION_KEY_LEN]) -> Result<T, WalletError>,
) -> Result<(T, UnlockedKdf), WalletError> {
    if pin.is_empty() {
        return Err(bhodl_core::KeyDerivationError::EmptyPin.into());
    }
    let sealed_before = scope.marker_path(dir).exists();
    let pending_path = scope.pending_path(dir);
    let has_pending = pending_path.exists();

    let mut first_err: Option<WalletError> = None;
    let mut candidates: Vec<Candidate> = Vec::with_capacity(2);
    match primary_candidate(
        dir,
        scope,
        pin,
        sealed_before,
        create_if_missing && !has_pending,
    ) {
        Ok(Some(c)) => candidates.push(c),
        Ok(None) => {}
        Err(e) if has_pending => first_err = Some(e),
        Err(e) => return Err(e),
    }
    if has_pending {
        // A `.next` descriptor is always written sealed.
        match read_descriptor(&pending_path, true).and_then(|f| Ok((f.salt()?, f))) {
            Ok((salt, file)) => candidates.push(Candidate {
                salt,
                params: file.kdf,
                file: Some(file),
                source: KdfSource::Pending,
            }),
            Err(e) => {
                if first_err.is_none() && candidates.is_empty() {
                    first_err = Some(e);
                }
            }
        }
    }
    if candidates.is_empty() {
        return Err(first_err.unwrap_or_else(|| WalletError::Storage {
            detail: format!(
                "no {} at this data dir{}",
                scope.file_name(),
                if scope == KdfScope::Wallet {
                    " (nor wallet.salt)"
                } else {
                    ""
                }
            ),
        }));
    }

    for cand in candidates {
        let key: SecretKey =
            bhodl_core::derive_key_from_pin_with_params(pin, &cand.salt, &cand.params)?;
        let attempt = match &cand.file {
            Some(f) => f.verify_mac(&key),
            None => Ok(()),
        }
        .and_then(|()| open(&key));
        match attempt {
            Ok(value) => {
                let needs_seal = cand.file.as_ref().is_some_and(KdfFile::needs_reseal);
                let unlocked = UnlockedKdf {
                    params: cand.params,
                    key,
                    source: cand.source,
                    salt: cand.salt,
                    needs_seal,
                };
                finalize_after_open(dir, scope, &unlocked)?;
                return Ok((value, unlocked));
            }
            Err(e) => {
                if first_err.is_none() {
                    first_err = Some(e);
                }
            }
        }
    }
    Err(first_err.unwrap_or_else(|| WalletError::Storage {
        detail: "unlock failed".into(),
    }))
}

/// Post-open bookkeeping. Runs ONLY once the database accepted the key.
fn finalize_after_open(dir: &Path, scope: KdfScope, u: &UnlockedKdf) -> Result<(), WalletError> {
    let pending = scope.pending_path(dir);
    match u.source {
        KdfSource::Pending => {
            // The rekey of an interrupted migration did commit: make
            // the `.next` descriptor the main one. Best-effort — if the
            // rename still fails, the next unlock falls back again.
            let _ = promote_pending(dir, scope);
        }
        KdfSource::Descriptor | KdfSource::Fresh => {
            if u.needs_seal {
                seal_kdf_file_with_mac(&scope.descriptor_path(dir), &u.salt, &u.params, &u.key)?;
            }
            if !scope.marker_path(dir).exists() {
                let _ = write_marker(dir, scope);
            }
            // The main descriptor opened the DB, so any `.next` is from
            // a rekey that never committed.
            let _ = fs::remove_file(&pending);
        }
        KdfSource::LegacySalt => {
            // Upgrade the raw `wallet.salt` to a sealed JSON descriptor
            // carrying the same salt + LEGACY params (equivalent KDF
            // state, now integrity-protected). The descriptor is durable
            // before the salt file goes, and takes precedence on read.
            seal_kdf_file_with_mac(&scope.descriptor_path(dir), &u.salt, &u.params, &u.key)?;
            let _ = write_marker(dir, scope);
            if let Some(legacy) = scope.legacy_salt_name() {
                let _ = fs::remove_file(dir.join(legacy));
            }
            let _ = fs::remove_file(&pending);
        }
    }
    Ok(())
}

/// Rename `<descriptor>.next` over the main descriptor, record the seal
/// marker and drop the legacy salt file.
fn promote_pending(dir: &Path, scope: KdfScope) -> Result<(), WalletError> {
    rename_with_retry(&scope.pending_path(dir), &scope.descriptor_path(dir))?;
    sync_dir(dir);
    write_marker(dir, scope)?;
    if let Some(legacy) = scope.legacy_salt_name() {
        let _ = fs::remove_file(dir.join(legacy));
    }
    Ok(())
}

/// Resolve the salt + params that belong to an already-known good
/// `key` (e.g. the device key held by an open `RegistryHandle`),
/// verifying the descriptor's MAC against it. Never writes.
pub(crate) fn params_for_known_key(
    dir: &Path,
    scope: KdfScope,
    key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<([u8; SALT_LEN], KdfParams), WalletError> {
    let sealed_before = scope.marker_path(dir).exists();
    let main = read_descriptor(&scope.descriptor_path(dir), sealed_before)
        .and_then(|f| f.verify_mac(key).map(|()| f));
    match main {
        Ok(f) => Ok((f.salt()?, f.kdf)),
        Err(e) => {
            let pending = scope.pending_path(dir);
            if pending.exists() {
                let f = read_descriptor(&pending, true)?;
                f.verify_mac(key).map_err(|_| e)?;
                return Ok((f.salt()?, f.kdf));
            }
            Err(e)
        }
    }
}

/// If `unlocked.params` are below [`KdfParams::CURRENT`], rotate the
/// SQLCipher key of the open wallet forward to today's parameters.
///
/// Crash-safe ordering (H2):
/// 1. Derive the new key from `pin` + a fresh salt + CURRENT params.
/// 2. Write `wallet.kdf.json.next` — sealed with the NEW key — and fsync.
/// 3. `PRAGMA rekey`. Once this commits the DB only opens with the new
///    key, and the matching descriptor is already durable on disk.
/// 4. Rename `.next` over `wallet.kdf.json` (retried on Windows sharing
///    violations). If that still fails, [`unlock_with`] recovers on the
///    next unlock by falling back to `.next` and promoting it.
pub(crate) fn migrate_kdf_if_stale(
    db_dir: &Path,
    unlocked: &UnlockedKdf,
    pin: &str,
    account: &OnchainAccount,
) -> Result<(), WalletError> {
    if unlocked.params == KdfParams::CURRENT {
        return Ok(());
    }
    let scope = KdfScope::Wallet;
    let new_salt = generate_salt()?;
    let new_key: SecretKey =
        bhodl_core::derive_key_from_pin_with_params(pin, &new_salt, &KdfParams::CURRENT)?;
    let pending = scope.pending_path(db_dir);
    write_descriptor_file(
        &pending,
        &KdfFile::sealed(&new_salt, &KdfParams::CURRENT, &new_key),
    )?;
    // On a rekey error the `.next` file is deliberately left in place:
    // if the error came after SQLCipher committed, it is the only copy
    // of the new salt. If the rekey did not commit, the next unlock
    // opens with the main descriptor and discards the stale `.next`.
    account.rekey(&new_key)?;
    let _ = promote_pending(db_dir, scope);
    Ok(())
}

// ---------------------------------------------------------------------------
// File writing helpers
// ---------------------------------------------------------------------------

/// Re-write [path] as a sealed (v2) descriptor with a MAC keyed from
/// [key]. Only ever call this with a key that has just opened the
/// database (see [`unlock_with`]).
pub(crate) fn seal_kdf_file_with_mac(
    path: &Path,
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
    key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<(), WalletError> {
    write_descriptor_file(path, &KdfFile::sealed(salt, kdf, key))
}

/// Write a descriptor atomically: sibling `.tmp`, fsync, rename (with
/// retries), best-effort directory fsync.
fn write_descriptor_file(path: &Path, file: &KdfFile) -> Result<(), WalletError> {
    let json = serde_json::to_vec_pretty(file).map_err(|e| WalletError::Storage {
        detail: format!("serialise kdf descriptor: {e}"),
    })?;
    write_file_atomic(path, &json)
}

fn write_marker(dir: &Path, scope: KdfScope) -> Result<(), WalletError> {
    write_file_atomic(&scope.marker_path(dir), b"sealed\n")
}

fn write_file_atomic(path: &Path, bytes: &[u8]) -> Result<(), WalletError> {
    let mut tmp_name: OsString = path.as_os_str().to_owned();
    tmp_name.push(".tmp");
    let tmp_path = PathBuf::from(tmp_name);
    {
        let mut tmp = fs::File::create(&tmp_path)?;
        tmp.write_all(bytes)?;
        tmp.sync_all()?;
    }
    rename_with_retry(&tmp_path, path)?;
    if let Some(parent) = path.parent() {
        sync_dir(parent);
    }
    Ok(())
}

/// `fs::rename` with a short back-off retry. On Windows a rename onto a
/// file that an antivirus / indexer has just opened fails transiently
/// with a sharing violation; retrying for a few hundred ms is the
/// standard remedy.
pub(crate) fn rename_with_retry(from: &Path, to: &Path) -> std::io::Result<()> {
    let mut attempt = 0u32;
    loop {
        match fs::rename(from, to) {
            Ok(()) => return Ok(()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Err(e),
            Err(e) => {
                attempt += 1;
                if attempt >= RENAME_ATTEMPTS {
                    return Err(e);
                }
                std::thread::sleep(Duration::from_millis(50 * u64::from(attempt)));
            }
        }
    }
}

/// Best-effort fsync of a directory so a completed rename survives a
/// power loss. Not supported (nor needed — NTFS journals metadata) on
/// Windows.
fn sync_dir(dir: &Path) {
    #[cfg(unix)]
    {
        if let Ok(d) = fs::File::open(dir) {
            let _ = d.sync_all();
        }
    }
    #[cfg(not(unix))]
    {
        let _ = dir;
    }
}

// ---------------------------------------------------------------------------
// Read-only helpers (tests, backup inspection)
// ---------------------------------------------------------------------------

/// Load the salt + KDF params for `db_dir`, or create both with the
/// current defaults if the directory has no wallet yet. Validates the
/// descriptor (version, bounds, mandatory MAC once sealed) but does not
/// verify the MAC — that needs the key; see [`unlock_with`].
#[cfg(test)]
pub(crate) fn read_or_create_kdf(
    db_dir: &Path,
) -> Result<([u8; SALT_LEN], KdfParams), WalletError> {
    read_or_create(db_dir, KdfScope::Wallet)
}

/// Registry-scoped counterpart of [`read_or_create_kdf`].
#[cfg(test)]
pub(crate) fn read_or_create_registry_kdf(
    data_dir: &Path,
) -> Result<([u8; SALT_LEN], KdfParams), WalletError> {
    read_or_create(data_dir, KdfScope::Registry)
}

#[cfg(test)]
fn read_or_create(dir: &Path, scope: KdfScope) -> Result<([u8; SALT_LEN], KdfParams), WalletError> {
    match read_existing(dir, scope) {
        Ok(found) => Ok(found),
        Err(_)
            if !scope.descriptor_path(dir).exists()
                && scope
                    .legacy_salt_name()
                    .is_none_or(|n| !dir.join(n).exists()) =>
        {
            let salt = generate_salt()?;
            write_kdf_file_for(dir, scope, &salt, &KdfParams::CURRENT)?;
            Ok((salt, KdfParams::CURRENT))
        }
        Err(e) => Err(e),
    }
}

/// Read-only: the salt + params recorded for `dir` (descriptor first,
/// then legacy `wallet.salt` for the wallet scope). Never creates.
pub(crate) fn read_existing(
    dir: &Path,
    scope: KdfScope,
) -> Result<([u8; SALT_LEN], KdfParams), WalletError> {
    let sealed_before = scope.marker_path(dir).exists();
    match primary_candidate(dir, scope, "", sealed_before, false)? {
        Some(c) => Ok((c.salt, c.params)),
        None => Err(WalletError::Storage {
            detail: format!("no {} or wallet.salt at this data dir", scope.file_name()),
        }),
    }
}

/// Wallet-scoped [`read_existing`].
#[cfg(test)]
pub(crate) fn read_existing_kdf(db_dir: &Path) -> Result<([u8; SALT_LEN], KdfParams), WalletError> {
    read_existing(db_dir, KdfScope::Wallet)
}

/// Write an UNSEALED `wallet.kdf.json` (test fixture / legacy layout).
#[cfg(test)]
pub(crate) fn write_kdf_file(
    db_dir: &Path,
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
) -> Result<(), WalletError> {
    write_kdf_file_for(db_dir, KdfScope::Wallet, salt, kdf)
}

#[cfg(test)]
fn write_kdf_file_for(
    dir: &Path,
    scope: KdfScope,
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
) -> Result<(), WalletError> {
    write_descriptor_file(&scope.descriptor_path(dir), &KdfFile::unsealed(salt, kdf))
}

/// Write a v1 descriptor carrying the pre-2026-10 MAC (keyed directly
/// with the SQLCipher key), as older builds did.
#[cfg(test)]
pub(crate) fn seal_legacy_v1_for_test(
    path: &Path,
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
    key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<(), WalletError> {
    let file = KdfFile {
        version: KdfFile::LEGACY_VERSION,
        salt_hex: hex::encode(salt),
        kdf: *kdf,
        mac_hex: Some(hex::encode(compute_kdf_mac_legacy(key, salt, kdf))),
    };
    write_descriptor_file(path, &file)
}

/// Load the salt at `path`, or create + persist a fresh one if absent.
///
/// The salt file is 16 raw bytes — no header, no version. We treat any
/// other size as corruption rather than silently regenerating, because
/// a regenerated salt would derive a different key and lock the user
/// out of their existing wallet.
#[cfg(test)]
pub(crate) fn read_or_create_salt(path: &Path) -> Result<[u8; SALT_LEN], WalletError> {
    match fs::File::open(path) {
        Ok(file) => read_salt_from_handle(file),
        Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
            let salt = generate_salt()?;
            write_file_atomic(path, &salt)?;
            Ok(salt)
        }
        Err(other) => Err(other.into()),
    }
}

/// Read an existing salt file. Errors if the file is missing or the wrong
/// size — the unlock path uses this to refuse to silently re-initialise a
/// missing wallet.
fn read_existing_salt(path: &Path) -> Result<[u8; SALT_LEN], WalletError> {
    let file = fs::File::open(path)?;
    read_salt_from_handle(file)
}

fn read_salt_from_handle(mut file: fs::File) -> Result<[u8; SALT_LEN], WalletError> {
    let mut buf = [0u8; SALT_LEN];
    let mut read_total = 0usize;
    while read_total < SALT_LEN {
        let n = file.read(&mut buf[read_total..])?;
        if n == 0 {
            return Err(WalletError::InvalidSalt {
                expected: SALT_LEN as u32,
                actual: read_total as u32,
            });
        }
        read_total += n;
    }
    // Reject files larger than expected.
    let mut overflow = [0u8; 1];
    if file.read(&mut overflow)? != 0 {
        return Err(WalletError::InvalidSalt {
            expected: SALT_LEN as u32,
            actual: (SALT_LEN + 1) as u32,
        });
    }
    Ok(buf)
}
