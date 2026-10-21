//! KDF descriptor + salt persistence for BHODL.
//!
//! R2 — extracted from `lib.rs` so the FFI surface file is no longer a
//! God Object. This module owns the on-disk `wallet.kdf.json` /
//! `registry.kdf.json` descriptor format, the Argon2id parameter
//! resolution / forward-migration helpers, the OS-keystore device-key
//! binding, the HMAC-SHA256 anti-tamper seal, and the legacy raw-binary
//! `wallet.salt` compatibility path.
//!
//! ## Key schedule (2026-10 security audit, device-bound keys)
//!
//! `pin_key = Argon2id(PIN, salt, params)`. A **device-bound** store
//! (descriptor v3, `protection.mode = "device_key"`) is keyed with
//! `HKDF(pin_key || device_secret)` where the 256-bit device secret is
//! held by the OS keystore (`bhodl-keystore`: DPAPI / Keychain / Secret
//! Service). Every other store is keyed with `pin_key` directly. See
//! `bhodl_core::key_derivation` for the threat model.
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
//! 2. For a device-bound descriptor, load the device secret from the OS
//!    keystore FIRST: a missing entry fails fast with
//!    [`WalletError::DeviceKeyMissing`] (never "wrong PIN"), an
//!    unreachable keystore with [`WalletError::DeviceKeystoreUnavailable`].
//! 3. Derive the key, verify the MAC if one is present, then let the
//!    caller open the database with that key.
//! 4. Only AFTER the database accepted the key is the descriptor sealed.
//!    Sealing before the open let a single wrong-PIN attempt bind the MAC
//!    to the wrong key and lock the user out permanently (H1).
//!
//! ## Migrations ([`migrate_if_stale`])
//!
//! A store is migrated after a successful unlock when its Argon2id
//! parameters are stale (wallet scope) or it is not yet device-bound and
//! a keystore is reachable. The new descriptor goes to
//! `<descriptor>.next` (sealed, fsynced) BEFORE `PRAGMA rekey`, and is
//! renamed over the main descriptor only after the rekey committed; the
//! old main descriptor stays untouched until then. A device secret is
//! stored in the OS keystore before the `.next` file references it. If
//! the process dies anywhere in between, exactly one of the two
//! descriptors opens the database: the next unlock tries the main one,
//! falls back to `.next`, and promotes it (H2). A stale `.next` (rekey
//! never committed) is discarded together with the keystore entry it
//! created.
//!
//! The registry key also encrypts the per-watch-wallet state files
//! (`watch-state/*.sqlite`). A registry migration therefore writes
//! [`WATCH_REKEY_MARKER`] before its rekey and re-encrypts every watch
//! file afterwards ([`sweep_watch_states`]); the sweep is idempotent and
//! re-runs on every unlock until the marker is gone.

use std::{
    ffi::OsString,
    fs,
    io::{Read, Write},
    path::{Path, PathBuf},
    time::Duration,
};

use bhodl_core::{KdfParams, SALT_LEN, generate_salt};
use bhodl_keystore::{DeviceKeystore, KeystoreError, WrapRecord, generate_device_secret};
use bhodl_wallet_onchain::{
    ENCRYPTION_KEY_LEN, OnchainAccount, REGISTRY_KDF_FILE_NAME, rekey_encrypted_db_if_needed,
};
use zeroize::Zeroizing;

use crate::WalletError;

/// A derived SQLCipher key that is wiped when it drops.
pub(crate) type SecretKey = Zeroizing<[u8; ENCRYPTION_KEY_LEN]>;

/// HMAC domain-separation label: the descriptor MAC key is
/// `HMAC-SHA256(sqlcipher_key, MAC_KEY_LABEL)`, never the raw database
/// key itself.
const MAC_KEY_LABEL: &[u8] = b"bhodl-kdf-mac-v1";

/// Prefix of the v3 MAC message, so a v3 MAC can never verify a v2
/// message (or vice versa) under the same key.
const MAC_V3_PREFIX: &[u8] = b"bhodl-kdf-v3";

/// How many times a descriptor rename is retried before giving up.
/// Windows Defender / the search indexer briefly hold freshly written
/// files open, which makes `MoveFileEx` fail with a sharing violation.
const RENAME_ATTEMPTS: u32 = 6;

/// Marker written next to `registry.sqlite` while the watch-state files
/// still have to follow a registry rekey (see [`sweep_watch_states`]).
pub(crate) const WATCH_REKEY_MARKER: &str = "registry.watch-rekey.pending";

/// How the database key is derived from the PIN-derived key.
///
/// Serialised inside the v3 descriptor as
/// `{"mode":"device_key","wrap":{"backend":"windows_dpapi","handle":"…"}}`
/// or `{"mode":"secret_only"}`.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub(crate) enum KeyProtection {
    /// `db_key = argon2id(secret)`. Only created when no OS keystore is
    /// available, and then only with a passphrase (see
    /// `bhodl_core::validate_new_secret`).
    SecretOnly,
    /// `db_key = HKDF(argon2id(secret) || device_secret)`, the device
    /// secret being held by the OS keystore described by `wrap`.
    DeviceKey { wrap: WrapRecord },
}

impl KeyProtection {
    fn wrap(&self) -> Option<&WrapRecord> {
        match self {
            Self::DeviceKey { wrap } => Some(wrap),
            Self::SecretOnly => None,
        }
    }
}

/// On-disk descriptor of the KDF state for a wallet.
///
/// Persisted as `wallet.kdf.json` next to `wallet.sqlite` (or
/// `registry.kdf.json` next to `registry.sqlite`).
///
/// Format:
/// ```json
/// {
///   "version": 3,
///   "salt_hex": "<32 hex chars = 16 bytes>",
///   "kdf": { "m_cost_kib": 65536, "t_cost": 3, "p_cost": 4 },
///   "protection": { "mode": "device_key",
///                   "wrap": { "backend": "windows_dpapi", "handle": "<hex>" } },
///   "mac_hex": "<64 hex chars>"
/// }
/// ```
///
/// Versions:
/// - `1` — legacy. `mac_hex` optional; when present it is HMAC-SHA256
///   keyed directly with the SQLCipher key. A v1 file without MAC is
///   only accepted while the store has never been sealed (no marker).
/// - `2` — sealed, PIN-only key. `mac_hex` mandatory, keyed with the
///   domain-separated MAC key (see [`MAC_KEY_LABEL`]). Still written for
///   stores that cannot be device-bound (no keystore) when they are
///   resealed or have their Argon2id parameters migrated, so an older
///   build can still open them.
/// - `3` — records the [`KeyProtection`] (device-bound or not). The MAC
///   also covers the protection record. Fresh v3 descriptors are written
///   without MAC and sealed after the first successful open, like v1.
///   Builds that predate v3 refuse it ("unsupported kdf descriptor
///   version 3") instead of deriving a wrong key.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub(crate) struct KdfFile {
    pub(crate) version: u32,
    pub(crate) salt_hex: String,
    pub(crate) kdf: KdfParams,
    /// v3 only: how the database key is bound (or not) to the device.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) protection: Option<KeyProtection>,
    /// HMAC-SHA256 over `(salt || serialised_kdf_params [|| protection])`.
    /// Detects tampering of the descriptor on disk (e.g. a parameter
    /// downgrade).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) mac_hex: Option<String>,
}

impl KdfFile {
    /// Pre-seal format (MAC optional, legacy MAC keying).
    pub(crate) const LEGACY_VERSION: u32 = 1;
    /// Sealed PIN-only format.
    pub(crate) const SEALED_V2_VERSION: u32 = 2;
    /// Format recording the key protection (device-bound or not).
    pub(crate) const CURRENT_VERSION: u32 = 3;
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

    /// Unsealed v1 descriptor (legacy layout; test fixtures).
    #[cfg(test)]
    fn unsealed(salt: &[u8; SALT_LEN], kdf: &KdfParams) -> Self {
        Self {
            version: Self::LEGACY_VERSION,
            salt_hex: hex::encode(salt),
            kdf: *kdf,
            protection: None,
            mac_hex: None,
        }
    }

    /// Unsealed v3 descriptor for a brand-new store (sealed after the
    /// first successful open).
    fn fresh(salt: &[u8; SALT_LEN], kdf: &KdfParams, protection: KeyProtection) -> Self {
        Self {
            version: Self::CURRENT_VERSION,
            salt_hex: hex::encode(salt),
            kdf: *kdf,
            protection: Some(protection),
            mac_hex: None,
        }
    }

    /// Sealed descriptor whose MAC is keyed from [key]: v3 when a
    /// protection record is given, v2 (PIN-only, readable by older
    /// builds) otherwise.
    fn sealed(
        salt: &[u8; SALT_LEN],
        kdf: &KdfParams,
        protection: Option<&KeyProtection>,
        key: &[u8; ENCRYPTION_KEY_LEN],
    ) -> Self {
        match protection {
            Some(p) => Self {
                version: Self::CURRENT_VERSION,
                salt_hex: hex::encode(salt),
                kdf: *kdf,
                protection: Some(p.clone()),
                mac_hex: Some(hex::encode(compute_kdf_mac_v3(key, salt, kdf, p))),
            },
            None => Self {
                version: Self::SEALED_V2_VERSION,
                salt_hex: hex::encode(salt),
                kdf: *kdf,
                protection: None,
                mac_hex: Some(hex::encode(compute_kdf_mac(key, salt, kdf))),
            },
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
        let missing_mac = |detail: &str| WalletError::Storage {
            detail: format!("kdf descriptor integrity check failed ({detail})"),
        };
        match self.version {
            Self::LEGACY_VERSION | Self::SEALED_V2_VERSION if self.protection.is_some() => {
                Err(WalletError::Storage {
                    detail: format!(
                        "kdf descriptor v{} must not carry a protection record",
                        self.version
                    ),
                })
            }
            Self::LEGACY_VERSION | Self::CURRENT_VERSION
                if self.mac_hex.is_none() && seal_required =>
            {
                Err(missing_mac("MAC missing on a sealed descriptor"))
            }
            Self::LEGACY_VERSION => Ok(()),
            Self::SEALED_V2_VERSION if self.mac_hex.is_none() => Err(missing_mac("MAC missing")),
            Self::SEALED_V2_VERSION => Ok(()),
            Self::CURRENT_VERSION if self.protection.is_none() => Err(WalletError::Storage {
                detail: "kdf descriptor v3 without protection record".into(),
            }),
            Self::CURRENT_VERSION => Ok(()),
            other => Err(WalletError::Storage {
                detail: format!("unsupported kdf descriptor version {other}"),
            }),
        }
    }

    /// Verify the embedded MAC against [key]. Returns Ok when the MAC is
    /// absent (unsealed v1/v3 file — [`Self::validate`] already refused
    /// this when a seal is mandatory) OR present and matching.
    pub(crate) fn verify_mac(&self, key: &[u8; ENCRYPTION_KEY_LEN]) -> Result<(), WalletError> {
        let Some(mac_hex) = &self.mac_hex else {
            return Ok(());
        };
        let salt = self.salt()?;
        let expected = match (&self.protection, self.version) {
            (Some(p), _) => compute_kdf_mac_v3(key, &salt, &self.kdf, p),
            (None, Self::LEGACY_VERSION) => compute_kdf_mac_legacy(key, &salt, &self.kdf),
            (None, _) => compute_kdf_mac(key, &salt, &self.kdf),
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

    /// True when the file is not in a sealed, current-keying format.
    fn needs_reseal(&self) -> bool {
        self.version == Self::LEGACY_VERSION || self.mac_hex.is_none()
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
    hmac_parts(k.as_slice(), &[salt, &kdf_bytes(kdf)])
}

/// v3 MAC: as v2, prefixed with [`MAC_V3_PREFIX`] and followed by the
/// JSON serialisation of the protection record, so swapping the wrap
/// record (or stripping the device binding) is detected.
fn compute_kdf_mac_v3(
    key: &[u8; ENCRYPTION_KEY_LEN],
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
    protection: &KeyProtection,
) -> Vec<u8> {
    let k = mac_key(key);
    let protection_bytes =
        serde_json::to_vec(protection).expect("KeyProtection serialises infallibly");
    hmac_parts(
        k.as_slice(),
        &[MAC_V3_PREFIX, salt, &kdf_bytes(kdf), &protection_bytes],
    )
}

/// v1 MAC (pre-2026-10): same message as v2, keyed directly with the
/// SQLCipher key. Only ever verified — a successful open reseals.
fn compute_kdf_mac_legacy(
    key: &[u8; ENCRYPTION_KEY_LEN],
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
) -> Vec<u8> {
    hmac_parts(key, &[salt, &kdf_bytes(kdf)])
}

fn kdf_bytes(kdf: &KdfParams) -> Vec<u8> {
    // serde_json output for a struct is deterministic (stable field
    // order). The MAC is over the exact bytes the integrity check will
    // see again on read.
    serde_json::to_vec(kdf).expect("KdfParams serialises infallibly")
}

fn hmac_parts(key: &[u8], parts: &[&[u8]]) -> Vec<u8> {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;
    let mut mac = <Hmac<Sha256>>::new_from_slice(key).expect("HMAC accepts any key length");
    for part in parts {
        mac.update(part);
    }
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

/// Map a keystore failure to the FFI error the UI distinguishes from a
/// wrong PIN.
pub(crate) fn keystore_error(e: KeystoreError) -> WalletError {
    match e {
        KeystoreError::NotFound(detail) => WalletError::DeviceKeyMissing { detail },
        other => WalletError::DeviceKeystoreUnavailable {
            detail: other.to_string(),
        },
    }
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
    /// The key that opened the database.
    pub(crate) key: SecretKey,
    /// `Argon2id(secret, salt, params)` — equal to `key` unless the
    /// store is device-bound.
    pub(crate) pin_key: SecretKey,
    pub(crate) source: KdfSource,
    /// `None` for v1/v2/legacy-salt stores (PIN-only key).
    pub(crate) protection: Option<KeyProtection>,
    pub(crate) salt: [u8; SALT_LEN],
    needs_seal: bool,
}

impl UnlockedKdf {
    /// True when the database key is bound to an OS-held device secret.
    pub(crate) fn is_device_bound(&self) -> bool {
        matches!(self.protection, Some(KeyProtection::DeviceKey { .. }))
    }
}

struct Candidate {
    salt: [u8; SALT_LEN],
    params: KdfParams,
    file: Option<KdfFile>,
    source: KdfSource,
}

impl Candidate {
    fn protection(&self) -> Option<&KeyProtection> {
        self.file.as_ref().and_then(|f| f.protection.as_ref())
    }
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

/// Choose the protection of a brand-new store and enforce the matching
/// secret policy: device-bound (6-digit PIN or passphrase) when the
/// keystore works, otherwise passphrase-only — never a silent downgrade.
fn protection_for_new_store(
    pin: &str,
    keystore: &dyn DeviceKeystore,
) -> Result<KeyProtection, WalletError> {
    if keystore.probe().is_ok() {
        bhodl_core::validate_new_secret(pin, true)?;
        let secret = generate_device_secret().map_err(keystore_error)?;
        let wrap = keystore.store(&secret).map_err(keystore_error)?;
        Ok(KeyProtection::DeviceKey { wrap })
    } else {
        bhodl_core::validate_new_secret(pin, false)?;
        Ok(KeyProtection::SecretOnly)
    }
}

fn primary_candidate(
    dir: &Path,
    scope: KdfScope,
    pin: &str,
    sealed_before: bool,
    create_with: Option<&dyn DeviceKeystore>,
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
    let Some(keystore) = create_with else {
        return Ok(None);
    };
    // Brand-new store: this is a PIN *creation*, so the new-secret policy
    // applies (unlock paths only require a non-empty PIN).
    let protection = protection_for_new_store(pin, keystore)?;
    let salt = generate_salt()?;
    let file = KdfFile::fresh(&salt, &KdfParams::CURRENT, protection);
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

/// Derive `(db_key, pin_key)` for a candidate. The device secret is
/// loaded BEFORE the (expensive) Argon2id run so a lost keystore entry
/// fails fast and is never reported as a wrong PIN.
fn derive_candidate_keys(
    pin: &str,
    cand: &Candidate,
    keystore: &dyn DeviceKeystore,
) -> Result<(SecretKey, SecretKey), WalletError> {
    let device = match cand.protection().and_then(KeyProtection::wrap) {
        Some(wrap) => Some(keystore.load(wrap).map_err(keystore_error)?),
        None => None,
    };
    let pin_key: SecretKey =
        bhodl_core::derive_key_from_pin_with_params(pin, &cand.salt, &cand.params)?;
    let db_key = match device {
        Some(d) => bhodl_core::combine_with_device_secret(&pin_key, &d, &cand.salt),
        None => pin_key.clone(),
    };
    Ok((db_key, pin_key))
}

/// Derive the key for the store at `dir` from `pin` and open it with
/// `open`, then seal / promote the descriptor — in that order.
///
/// - `create_if_missing`: when neither descriptor nor legacy salt (nor a
///   pending `.next`) exists, create a fresh v3 descriptor with
///   [`KdfParams::CURRENT`], device-bound through `keystore` when it is
///   available (enforcing the new-secret policy). Otherwise a missing
///   descriptor is a `Storage` error.
/// - `open` receives the derived key and must actually open the
///   database: its success is the only proof that the PIN is right, and
///   only then is the descriptor sealed with that key.
/// - If the main descriptor fails (wrong key, MAC mismatch, corrupt,
///   device key unavailable) and a `.next` descriptor from an
///   interrupted migration exists, it is tried next and, on success,
///   promoted to the main descriptor.
///
/// On failure the error of the FIRST candidate is returned, so a wrong
/// PIN surfaces exactly as before (`Storage`), and a lost device key as
/// [`WalletError::DeviceKeyMissing`].
pub(crate) fn unlock_with<T>(
    dir: &Path,
    scope: KdfScope,
    pin: &str,
    create_if_missing: bool,
    keystore: &dyn DeviceKeystore,
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
    let create_with = (create_if_missing && !has_pending).then_some(keystore);
    match primary_candidate(dir, scope, pin, sealed_before, create_with) {
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
        let attempt = derive_candidate_keys(pin, &cand, keystore).and_then(|(key, pin_key)| {
            match &cand.file {
                Some(f) => f.verify_mac(&key),
                None => Ok(()),
            }?;
            let value = open(&key)?;
            Ok((value, key, pin_key))
        });
        match attempt {
            Ok((value, key, pin_key)) => {
                let needs_seal = cand.file.as_ref().is_some_and(KdfFile::needs_reseal);
                let unlocked = UnlockedKdf {
                    params: cand.params,
                    key,
                    pin_key,
                    source: cand.source,
                    protection: cand.protection().cloned(),
                    salt: cand.salt,
                    needs_seal,
                };
                finalize_after_open(dir, scope, &unlocked, keystore)?;
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
fn finalize_after_open(
    dir: &Path,
    scope: KdfScope,
    u: &UnlockedKdf,
    keystore: &dyn DeviceKeystore,
) -> Result<(), WalletError> {
    match u.source {
        KdfSource::Pending => {
            // The rekey of an interrupted migration did commit: make
            // the `.next` descriptor the main one. Best-effort — if the
            // rename still fails, the next unlock falls back again.
            let _ = promote_pending(dir, scope);
        }
        KdfSource::Descriptor | KdfSource::Fresh => {
            if u.needs_seal {
                write_descriptor_file(
                    &scope.descriptor_path(dir),
                    &KdfFile::sealed(&u.salt, &u.params, u.protection.as_ref(), &u.key),
                )?;
            }
            if !scope.marker_path(dir).exists() {
                let _ = write_marker(dir, scope);
            }
            // The main descriptor opened the DB, so any `.next` is from
            // a rekey that never committed.
            discard_stale_pending(dir, scope, u.protection.as_ref(), keystore);
        }
        KdfSource::LegacySalt => {
            // Upgrade the raw `wallet.salt` to a sealed JSON descriptor
            // carrying the same salt + LEGACY params (equivalent KDF
            // state, now integrity-protected). The descriptor is durable
            // before the salt file goes, and takes precedence on read.
            write_descriptor_file(
                &scope.descriptor_path(dir),
                &KdfFile::sealed(&u.salt, &u.params, None, &u.key),
            )?;
            let _ = write_marker(dir, scope);
            if let Some(legacy) = scope.legacy_salt_name() {
                let _ = fs::remove_file(dir.join(legacy));
            }
            discard_stale_pending(dir, scope, None, keystore);
        }
    }
    Ok(())
}

/// Remove a `.next` descriptor whose rekey never committed, together
/// with a keystore entry it created that the live descriptor does not
/// use. Best-effort.
fn discard_stale_pending(
    dir: &Path,
    scope: KdfScope,
    live: Option<&KeyProtection>,
    keystore: &dyn DeviceKeystore,
) {
    let pending = scope.pending_path(dir);
    if !pending.exists() {
        return;
    }
    let stale_wrap = fs::read(&pending)
        .ok()
        .and_then(|raw| serde_json::from_slice::<KdfFile>(&raw).ok())
        .and_then(|f| f.protection)
        .and_then(|p| match p {
            KeyProtection::DeviceKey { wrap } => Some(wrap),
            KeyProtection::SecretOnly => None,
        });
    if let Some(wrap) = stale_wrap {
        if live.and_then(KeyProtection::wrap) != Some(&wrap) {
            let _ = keystore.delete(&wrap);
        }
    }
    let _ = fs::remove_file(&pending);
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

/// Bring an open store's key up to date, crash-safely:
///
/// - Argon2id parameters below [`KdfParams::CURRENT`] are bumped (only
///   when `allow_param_bump`; a new salt is drawn in that case);
/// - a store that is not device-bound becomes device-bound when the
///   keystore is reachable (same salt and params, so the PIN-derived
///   key is unchanged — the registry's watch-state sweep relies on it).
///
/// Ordering (H2):
/// 1. Store a NEW device secret in the OS keystore (if binding now).
/// 2. Write `<descriptor>.next` — sealed with the NEW key — and fsync.
///    The old main descriptor is not touched.
/// 3. `rekey(new_key)` (`PRAGMA rekey`). Once this commits the DB only
///    opens with the new key, and the matching descriptor + keystore
///    entry are already durable.
/// 4. Rename `.next` over the main descriptor (retried on Windows
///    sharing violations). If that still fails, [`unlock_with`] recovers
///    on the next unlock by falling back to `.next` and promoting it.
///
/// Returns the new key when a migration ran. Binding is opportunistic:
/// if the keystore refuses to store the secret (e.g. the user dismissed
/// a keyring prompt) the store keeps working as before and the binding
/// is retried at the next unlock.
pub(crate) fn migrate_if_stale(
    dir: &Path,
    scope: KdfScope,
    unlocked: &UnlockedKdf,
    pin: &str,
    keystore: &dyn DeviceKeystore,
    allow_param_bump: bool,
    rekey: impl FnOnce(&[u8; ENCRYPTION_KEY_LEN]) -> Result<(), WalletError>,
) -> Result<Option<SecretKey>, WalletError> {
    let params_stale = allow_param_bump && unlocked.params != KdfParams::CURRENT;
    let want_bind = !unlocked.is_device_bound() && keystore.probe().is_ok();
    if !params_stale && !want_bind {
        return Ok(None);
    }

    // Target protection + the device secret that goes with it.
    let (protection, device) = if let Some(KeyProtection::DeviceKey { wrap }) = &unlocked.protection
    {
        let secret = keystore.load(wrap).map_err(keystore_error)?;
        (
            Some(KeyProtection::DeviceKey { wrap: wrap.clone() }),
            Some(secret),
        )
    } else if want_bind {
        let stored = generate_device_secret().and_then(|s| keystore.store(&s).map(|w| (s, w)));
        match stored {
            Ok((secret, wrap)) => (Some(KeyProtection::DeviceKey { wrap }), Some(secret)),
            Err(_) if !params_stale => return Ok(None),
            Err(_) => (unlocked.protection.clone(), None),
        }
    } else {
        (unlocked.protection.clone(), None)
    };

    let (new_salt, new_params, new_pin_key) = if params_stale {
        let salt = generate_salt()?;
        let pin_key: SecretKey =
            bhodl_core::derive_key_from_pin_with_params(pin, &salt, &KdfParams::CURRENT)?;
        (salt, KdfParams::CURRENT, pin_key)
    } else {
        (unlocked.salt, unlocked.params, unlocked.pin_key.clone())
    };
    let new_key: SecretKey = match &device {
        Some(d) => bhodl_core::combine_with_device_secret(&new_pin_key, d, &new_salt),
        None => new_pin_key,
    };

    let pending = scope.pending_path(dir);
    write_descriptor_file(
        &pending,
        &KdfFile::sealed(&new_salt, &new_params, protection.as_ref(), &new_key),
    )?;
    // On a rekey error the `.next` file is deliberately left in place:
    // if the error came after SQLCipher committed, it is the only copy
    // of the new key state. If the rekey did not commit, the next unlock
    // opens with the main descriptor and discards the stale `.next`
    // (and the keystore entry it references).
    rekey(&new_key)?;
    let _ = promote_pending(dir, scope);
    Ok(Some(new_key))
}

/// Wallet-scope [`migrate_if_stale`] over an open [`OnchainAccount`].
pub(crate) fn migrate_wallet_if_stale(
    db_dir: &Path,
    unlocked: &UnlockedKdf,
    pin: &str,
    keystore: &dyn DeviceKeystore,
    account: &OnchainAccount,
) -> Result<(), WalletError> {
    migrate_if_stale(
        db_dir,
        KdfScope::Wallet,
        unlocked,
        pin,
        keystore,
        true,
        |key| account.rekey(key).map_err(WalletError::from),
    )
    .map(|_| ())
}

/// Re-encrypt every `watch-state/*.sqlite` under `data_dir` from
/// `old_key` to `new_key` (idempotent per file), then drop
/// [`WATCH_REKEY_MARKER`]. Files readable by neither key are left alone.
/// The marker stays when a rekey failed, so the next unlock retries.
pub(crate) fn sweep_watch_states(
    data_dir: &Path,
    old_key: &[u8; ENCRYPTION_KEY_LEN],
    new_key: &[u8; ENCRYPTION_KEY_LEN],
) {
    let mut ok = true;
    if let Ok(entries) = fs::read_dir(data_dir.join("watch-state")) {
        for entry in entries.filter_map(Result::ok) {
            let path = entry.path();
            if path.extension().is_some_and(|e| e == "sqlite") && path.is_file() {
                ok &= rekey_encrypted_db_if_needed(&path, old_key, new_key).is_ok();
            }
        }
    }
    if ok {
        let _ = fs::remove_file(data_dir.join(WATCH_REKEY_MARKER));
        sync_dir(data_dir);
    }
}

/// Write [`WATCH_REKEY_MARKER`] (before a registry rekey).
pub(crate) fn write_watch_rekey_marker(data_dir: &Path) -> Result<(), WalletError> {
    write_file_atomic(&data_dir.join(WATCH_REKEY_MARKER), b"pending\n")
}

// ---------------------------------------------------------------------------
// File writing helpers
// ---------------------------------------------------------------------------

/// Re-write [path] as a sealed PIN-only (v2) descriptor with a MAC keyed
/// from [key]. Test fixture for pre-v3 layouts.
#[cfg(test)]
pub(crate) fn seal_kdf_file_with_mac(
    path: &Path,
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
    key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<(), WalletError> {
    write_descriptor_file(path, &KdfFile::sealed(salt, kdf, None, key))
}

/// Write a sealed v3 descriptor carrying `protection` (test fixture).
#[cfg(test)]
pub(crate) fn seal_v3_for_test(
    path: &Path,
    salt: &[u8; SALT_LEN],
    kdf: &KdfParams,
    protection: &KeyProtection,
    key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<(), WalletError> {
    write_descriptor_file(path, &KdfFile::sealed(salt, kdf, Some(protection), key))
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
    match primary_candidate(dir, scope, "", sealed_before, None)? {
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
        protection: None,
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
