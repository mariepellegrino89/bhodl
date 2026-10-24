//! KDF descriptor + salt persistence for BHODL.
//!
//! R2 — extracted from `lib.rs` so the FFI surface file is no longer a
//! God Object. This module owns the on-disk `registry.kdf.json`
//! descriptor format, the Argon2id parameter resolution and the
//! HMAC-SHA256 anti-tamper seal.
//!
//! ## Unlock protocol (2026-10 security audit, H1/H2/M1)
//!
//! The device-PIN registry is opened through [`unlock_with`]:
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
//! A KDF migration writes the new descriptor to `<descriptor>.next`
//! (sealed, fsynced) BEFORE `PRAGMA rekey`, and renames it over the main
//! descriptor afterwards. If the process dies between the two, or the
//! rename keeps failing (Windows AV / indexer sharing violation), the
//! next unlock falls back to the `.next` descriptor when the main one
//! cannot open the database, and promotes it (H2).
//!
//! ## Wrapped data key (v3, PIN recovery)
//!
//! Since v3 the PIN no longer keys the databases directly: the
//! descriptor carries the store's data key wrapped (XChaCha20-Poly1305)
//! by `Argon2id(PIN, salt)`. A v1/v2 store migrates on its first
//! successful unlock WITHOUT re-encrypting anything — its data key is the
//! key the PIN derived until then, re-wrapped under a fresh salt.
//!
//! The same data key can also be wrapped by a recovery code
//! (`registry.recovery.json`, same file shape). Changing the PIN or
//! recovering a forgotten one is therefore a re-wrap of 32 bytes, never
//! a rekey of the registry and every `watch-state/*.sqlite` file.

use std::{
    ffi::OsString,
    fs,
    io::Write,
    path::{Path, PathBuf},
    time::Duration,
};

use bhodl_core::{KdfParams, SALT_LEN, generate_salt};
use bhodl_wallet_onchain::{ENCRYPTION_KEY_LEN, REGISTRY_KDF_FILE_NAME};
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
/// - `3` — wrapped. `nonce_hex` + `wrapped_key_hex` mandatory, no
///   `mac_hex`: the AEAD tag authenticates the salt, the parameters and
///   the purpose (PIN vs recovery code) as associated data, so a wrong
///   secret and a tampered file fail the same way.
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub(crate) struct KdfFile {
    pub(crate) version: u32,
    pub(crate) salt_hex: String,
    pub(crate) kdf: KdfParams,
    /// HMAC-SHA256 over `(salt || serialised_kdf_params)`. Detects
    /// tampering of the descriptor on disk (e.g. a parameter downgrade).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) mac_hex: Option<String>,
    /// v3: 24-byte XChaCha20 nonce of [`Self::wrapped_key_hex`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) nonce_hex: Option<String>,
    /// v3: the data key encrypted under the secret-derived key.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) wrapped_key_hex: Option<String>,
}

impl KdfFile {
    /// Pre-seal format (MAC optional, legacy MAC keying).
    pub(crate) const LEGACY_VERSION: u32 = 1;
    /// Sealed format written by builds before the wrapped data key.
    pub(crate) const CURRENT_VERSION: u32 = 2;
    /// Wrapped-data-key format written by this build after every
    /// successful open (see the module docs).
    pub(crate) const WRAPPED_VERSION: u32 = 3;

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
            nonce_hex: None,
            wrapped_key_hex: None,
        }
    }

    /// Sealed (v2) descriptor whose MAC is keyed from [key] (test
    /// fixture: this build writes v3).
    #[cfg(test)]
    fn sealed(salt: &[u8; SALT_LEN], kdf: &KdfParams, key: &[u8; ENCRYPTION_KEY_LEN]) -> Self {
        Self {
            version: Self::CURRENT_VERSION,
            salt_hex: hex::encode(salt),
            kdf: *kdf,
            mac_hex: Some(hex::encode(compute_kdf_mac(key, salt, kdf))),
            nonce_hex: None,
            wrapped_key_hex: None,
        }
    }

    /// Wrapped (v3) file: `data_key` encrypted under `kek`, with the
    /// salt, parameters and `purpose` bound as associated data.
    fn wrapped(
        salt: &[u8; SALT_LEN],
        kdf: &KdfParams,
        kek: &[u8; ENCRYPTION_KEY_LEN],
        data_key: &[u8; ENCRYPTION_KEY_LEN],
        purpose: WrapPurpose,
    ) -> Result<Self, WalletError> {
        use chacha20poly1305::{
            KeyInit, XChaCha20Poly1305,
            aead::{Aead, Payload},
        };
        let nonce: [u8; WRAP_NONCE_LEN] = bhodl_core::random_bytes()?;
        let aad = wrap_aad(purpose, salt, kdf);
        let wrapped = XChaCha20Poly1305::new(kek.into())
            .encrypt(
                (&nonce).into(),
                Payload {
                    msg: data_key,
                    aad: &aad,
                },
            )
            .map_err(|_| WalletError::Storage {
                detail: "data key wrap failed".into(),
            })?;
        Ok(Self {
            version: Self::WRAPPED_VERSION,
            salt_hex: hex::encode(salt),
            kdf: *kdf,
            mac_hex: None,
            nonce_hex: Some(hex::encode(nonce)),
            wrapped_key_hex: Some(hex::encode(wrapped)),
        })
    }

    pub(crate) fn is_wrapped(&self) -> bool {
        self.version == Self::WRAPPED_VERSION
    }

    /// Decrypt the data key of a v3 file with `kek`. A wrong secret and a
    /// tampered file are indistinguishable (AEAD tag mismatch).
    fn unwrap_data_key(
        &self,
        kek: &[u8; ENCRYPTION_KEY_LEN],
        purpose: WrapPurpose,
    ) -> Result<SecretKey, WalletError> {
        use chacha20poly1305::{
            KeyInit, XChaCha20Poly1305,
            aead::{Aead, Payload},
        };
        let corrupt = || WalletError::Storage {
            detail: "kdf descriptor wrapped key is malformed".into(),
        };
        let nonce = hex::decode(self.nonce_hex.as_deref().unwrap_or_default())
            .ok()
            .and_then(|b| <[u8; WRAP_NONCE_LEN]>::try_from(b).ok())
            .ok_or_else(corrupt)?;
        let wrapped = hex::decode(self.wrapped_key_hex.as_deref().unwrap_or_default())
            .map_err(|_| corrupt())?;
        let aad = wrap_aad(purpose, &self.salt()?, &self.kdf);
        let plain = Zeroizing::new(
            XChaCha20Poly1305::new(kek.into())
                .decrypt(
                    (&nonce).into(),
                    Payload {
                        msg: &wrapped,
                        aad: &aad,
                    },
                )
                .map_err(|_| WalletError::Storage {
                    detail: purpose.rejected_detail().into(),
                })?,
        );
        if plain.len() != ENCRYPTION_KEY_LEN {
            return Err(corrupt());
        }
        let mut key: SecretKey = Zeroizing::new([0u8; ENCRYPTION_KEY_LEN]);
        key.copy_from_slice(&plain);
        Ok(key)
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
            Self::WRAPPED_VERSION if self.nonce_hex.is_none() || self.wrapped_key_hex.is_none() => {
                Err(WalletError::Storage {
                    detail: "kdf descriptor integrity check failed (wrapped key missing)".into(),
                })
            }
            Self::WRAPPED_VERSION => Ok(()),
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
}

/// What a v3 file's key was derived from. Bound into the AEAD
/// associated data, so a recovery file can never stand in for the PIN
/// descriptor (or the other way round).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum WrapPurpose {
    DevicePin,
    RecoveryCode,
}

impl WrapPurpose {
    fn label(self) -> &'static [u8] {
        match self {
            Self::DevicePin => b"device-pin",
            Self::RecoveryCode => b"recovery-code",
        }
    }

    fn rejected_detail(self) -> &'static str {
        match self {
            // PinErrors treats an unrecognised Storage detail as a wrong
            // PIN (fail-closed), like the v2 MAC mismatch.
            Self::DevicePin => "kdf key unwrap failed (wrong PIN or file tampered)",
            Self::RecoveryCode => RECOVERY_CODE_REJECTED,
        }
    }
}

/// XChaCha20-Poly1305 nonce length.
const WRAP_NONCE_LEN: usize = 24;

/// Associated-data label of every v3 wrap.
const WRAP_AAD_LABEL: &[u8] = b"bhodl-data-key-wrap-v1";

/// Error detail of a rejected recovery code. Kotlin matches on it to
/// count the attempt against the lockout.
pub(crate) const RECOVERY_CODE_REJECTED: &str = "recovery code rejected";

fn wrap_aad(purpose: WrapPurpose, salt: &[u8; SALT_LEN], kdf: &KdfParams) -> Vec<u8> {
    let mut aad = Vec::with_capacity(96);
    aad.extend_from_slice(WRAP_AAD_LABEL);
    aad.push(0);
    aad.extend_from_slice(purpose.label());
    aad.push(0);
    aad.extend_from_slice(salt);
    aad.extend_from_slice(&serde_json::to_vec(kdf).expect("KdfParams serialises infallibly"));
    aad
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
    /// `registry.kdf.json` next to `registry.sqlite`.
    Registry,
}

impl KdfScope {
    fn file_name(self) -> &'static str {
        match self {
            Self::Registry => REGISTRY_KDF_FILE_NAME,
        }
    }

    fn marker_name(self) -> &'static str {
        match self {
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

// ---------------------------------------------------------------------------
// Unlock
// ---------------------------------------------------------------------------

/// Where the key that opened the store came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum KdfSource {
    /// The main JSON descriptor.
    Descriptor,
    /// A descriptor generated by this call for a brand-new store.
    Fresh,
    /// The `.next` descriptor of an interrupted KDF migration.
    Pending,
}

/// Result of a successful [`unlock_with`].
pub(crate) struct UnlockedKdf {
    /// The store's data key (what SQLCipher was opened with).
    pub(crate) key: SecretKey,
    pub(crate) source: KdfSource,
    /// The descriptor that opened the store predates v3.
    needs_wrap: bool,
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
/// - `create_if_missing`: when neither descriptor nor a pending `.next`
///   exists, create a fresh descriptor with
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
            detail: format!("no {} at this data dir", scope.file_name()),
        }));
    }

    for cand in candidates {
        let derived: SecretKey = Zeroizing::new(bhodl_core::derive_key_from_pin_with_params(
            pin,
            &cand.salt,
            &cand.params,
        )?);
        let wrapped = cand.file.as_ref().is_some_and(KdfFile::is_wrapped);
        // v3: the PIN-derived key unwraps the data key. v1/v2: the
        // PIN-derived key IS the data key (checked by the MAC, if any).
        let attempt = match &cand.file {
            Some(f) if wrapped => f.unwrap_data_key(&derived, WrapPurpose::DevicePin),
            Some(f) => f.verify_mac(&derived).map(|()| derived.clone()),
            None => Ok(derived.clone()),
        }
        .and_then(|key| open(&key).map(|value| (value, key)));
        match attempt {
            Ok((value, key)) => {
                let unlocked = UnlockedKdf {
                    key,
                    source: cand.source,
                    needs_wrap: !wrapped,
                };
                finalize_after_open(dir, scope, pin, &unlocked)?;
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
    pin: &str,
    u: &UnlockedKdf,
) -> Result<(), WalletError> {
    let pending = scope.pending_path(dir);
    if u.needs_wrap {
        // v1/v2 → v3: wrap the data key that just opened the store under
        // a fresh salt. Atomic write — a failure leaves the old
        // descriptor (still valid for this PIN) in place.
        write_wrapped_file(
            &scope.descriptor_path(dir),
            pin,
            WrapPurpose::DevicePin,
            &u.key,
        )?;
        // Whichever descriptor opened the store is now superseded.
        let _ = fs::remove_file(&pending);
    } else if u.source == KdfSource::Pending {
        // A wrapped `.next` opened the store: make it the main one.
        // Best-effort — if the rename still fails, the next unlock falls
        // back again.
        let _ = promote_pending(dir, scope);
    } else {
        // The main descriptor opened the DB, so any `.next` is from
        // a rekey that never committed.
        let _ = fs::remove_file(&pending);
    }
    if !scope.marker_path(dir).exists() {
        let _ = write_marker(dir, scope);
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Re-wrapping: change PIN, recovery code
// ---------------------------------------------------------------------------

/// Write a v3 file at `path` wrapping `data_key` under
/// `Argon2id(secret, fresh salt, KdfParams::CURRENT)`.
pub(crate) fn write_wrapped_file(
    path: &Path,
    secret: &str,
    purpose: WrapPurpose,
    data_key: &[u8; ENCRYPTION_KEY_LEN],
) -> Result<(), WalletError> {
    let salt = generate_salt()?;
    let params = KdfParams::CURRENT;
    let kek: SecretKey = Zeroizing::new(bhodl_core::derive_key_from_pin_with_params(
        secret, &salt, &params,
    )?);
    write_descriptor_file(
        path,
        &KdfFile::wrapped(&salt, &params, &kek, data_key, purpose)?,
    )
}

/// Unwrap the data key of the v3 file at `path` with `secret`.
pub(crate) fn unwrap_file(
    path: &Path,
    secret: &str,
    purpose: WrapPurpose,
) -> Result<SecretKey, WalletError> {
    let file = read_descriptor(path, true)?;
    if !file.is_wrapped() {
        return Err(WalletError::Storage {
            detail: format!(
                "unsupported kdf descriptor version {} for unwrap",
                file.version
            ),
        });
    }
    let kek: SecretKey = Zeroizing::new(bhodl_core::derive_key_from_pin_with_params(
        secret,
        &file.salt()?,
        &file.kdf,
    )?);
    file.unwrap_data_key(&kek, purpose)
}

/// After the device PIN changed (or was recovered): drop any `.next`
/// left by an older migration and make sure the seal marker exists.
pub(crate) fn after_rewrap(dir: &Path, scope: KdfScope) {
    let _ = fs::remove_file(scope.pending_path(dir));
    if !scope.marker_path(dir).exists() {
        let _ = write_marker(dir, scope);
    }
}

/// `registry.recovery.json` — the data key wrapped by the recovery code.
pub(crate) const RECOVERY_FILE_NAME: &str = "registry.recovery.json";

/// Crockford base32 alphabet (no I, L, O, U).
const CROCKFORD: &[u8; 32] = b"0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/// Random bytes behind a recovery code: 15 bytes = 120 bits = 24 symbols.
const RECOVERY_CODE_BYTES: usize = 15;

/// Symbols in a recovery code.
pub(crate) const RECOVERY_CODE_LEN: usize = RECOVERY_CODE_BYTES * 8 / 5;

/// A fresh recovery code: 24 Crockford base32 symbols (~120 bits),
/// returned in canonical form (no separators).
pub(crate) fn generate_recovery_code() -> Result<Zeroizing<String>, WalletError> {
    let bytes = Zeroizing::new(bhodl_core::random_bytes::<RECOVERY_CODE_BYTES>()?);
    let mut out = Zeroizing::new(String::with_capacity(RECOVERY_CODE_LEN));
    let mut acc: u32 = 0;
    let mut bits = 0u32;
    for &b in bytes.iter() {
        acc = (acc << 8) | u32::from(b);
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            out.push(char::from(CROCKFORD[((acc >> bits) & 31) as usize]));
        }
        acc &= (1 << bits) - 1;
    }
    Ok(out)
}

/// `K7QM-3XH9-…`: the canonical code split into groups of four.
pub(crate) fn format_recovery_code(canonical: &str) -> String {
    canonical
        .as_bytes()
        .chunks(4)
        .map(|c| String::from_utf8_lossy(c).into_owned())
        .collect::<Vec<_>>()
        .join("-")
}

/// Canonical form of a typed recovery code: separators and whitespace
/// dropped, case folded, and the Crockford look-alikes mapped
/// (`O`→`0`, `I`/`L`→`1`). `None` when the result is not a well-formed
/// code, so a typo is reported before any Argon2 run.
pub(crate) fn normalize_recovery_code(typed: &str) -> Option<Zeroizing<String>> {
    let mut out = Zeroizing::new(String::with_capacity(RECOVERY_CODE_LEN));
    for c in typed.chars() {
        let c = match c.to_ascii_uppercase() {
            '-' | ' ' | '\t' | '\n' | '\r' => continue,
            'O' => '0',
            'I' | 'L' => '1',
            other => other,
        };
        if !c.is_ascii() || !CROCKFORD.contains(&(c as u8)) {
            return None;
        }
        out.push(c);
    }
    (out.len() == RECOVERY_CODE_LEN).then_some(out)
}

/// Rename `<descriptor>.next` over the main descriptor and record the
/// seal marker.
fn promote_pending(dir: &Path, scope: KdfScope) -> Result<(), WalletError> {
    rename_with_retry(&scope.pending_path(dir), &scope.descriptor_path(dir))?;
    sync_dir(dir);
    write_marker(dir, scope)?;
    Ok(())
}

// ---------------------------------------------------------------------------
// File writing helpers
// ---------------------------------------------------------------------------

/// Re-write [path] as a sealed (v2) descriptor with a MAC keyed from
/// [key], as builds before v3 did (test fixture).
#[cfg(test)]
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

/// Load the registry salt + KDF params, or create both with the current
/// defaults if the directory has none yet. Validates the descriptor
/// (version, bounds, mandatory MAC once sealed) but does not verify the
/// MAC — that needs the key; see [`unlock_with`].
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
        Err(_) if !scope.descriptor_path(dir).exists() => {
            let salt = generate_salt()?;
            write_kdf_file_for(dir, scope, &salt, &KdfParams::CURRENT)?;
            Ok((salt, KdfParams::CURRENT))
        }
        Err(e) => Err(e),
    }
}

/// Read-only: the salt + params recorded for `dir`. Never creates.
#[cfg(test)]
fn read_existing(dir: &Path, scope: KdfScope) -> Result<([u8; SALT_LEN], KdfParams), WalletError> {
    let sealed_before = scope.marker_path(dir).exists();
    match primary_candidate(dir, scope, "", sealed_before, false)? {
        Some(c) => Ok((c.salt, c.params)),
        None => Err(WalletError::Storage {
            detail: format!("no {} at this data dir", scope.file_name()),
        }),
    }
}

/// Write an UNSEALED descriptor (test fixture).
#[cfg(test)]
pub(crate) fn write_kdf_file_for(
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
        nonce_hex: None,
        wrapped_key_hex: None,
    };
    write_descriptor_file(path, &file)
}
