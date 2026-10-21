//! OS-protected **device secret** for BHODL's encrypted databases.
//!
//! Every `SQLCipher` database of the wallet is keyed from the user's PIN
//! through Argon2id (`bhodl_core::key_derivation`). A 6-digit PIN has only
//! 10^6 candidates, so anyone holding a copy of the data directory could
//! run that search offline. This crate closes the gap: on store creation
//! a random 256-bit *device secret* is generated and handed to the
//! operating system's secret store, and the database key becomes
//! `HKDF(argon2id(PIN) || device_secret)`. A copy of the data directory
//! alone no longer contains everything needed to test a PIN guess.
//!
//! | OS      | Backend                     | Where the secret lives                         |
//! |---------|-----------------------------|------------------------------------------------|
//! | Windows | DPAPI, `CurrentUser` scope  | DPAPI blob inside the KDF descriptor; only the same Windows account on the same machine can decrypt it |
//! | macOS   | login Keychain              | generic-password item, service `io.bhodl.device-key` |
//! | Linux   | freedesktop Secret Service  | item in the `default` collection (`GNOME Keyring`, `KWallet`, `KeePassXC`) |
//!
//! The KDF descriptor stores a [`WrapRecord`]: the backend plus an opaque
//! handle (the DPAPI blob, or the Keychain / Secret Service item id). The
//! handle by itself never reveals the secret.
//!
//! ## Losing the device secret
//!
//! If the OS entry is gone (OS reinstall, another user account, keyring
//! reset, data dir copied to another machine) the database **cannot be
//! opened with the PIN any more**. [`KeystoreError::NotFound`] reports
//! exactly that case so the UI can tell it apart from a wrong PIN and
//! send the user to the recovery phrase / encrypted backup.
//!
//! ## Tests
//!
//! [`EphemeralKeystore`] keeps secrets in process memory only. It is
//! selected by the test suites and by `BHODL_KEYSTORE=ephemeral-test`
//! (see [`platform_keystore`]); stores created under it become
//! unopenable once the process exits, so the override can only ever lose
//! data, never weaken it.

use std::{
    collections::HashMap,
    fmt,
    sync::{Mutex, OnceLock},
};

use rand::TryRngCore;
use zeroize::{Zeroize, Zeroizing};

#[cfg(windows)]
mod dpapi;
#[cfg(target_os = "macos")]
mod keychain;
#[cfg(target_os = "linux")]
mod secret_service;

/// Length of the device secret (256 bits).
pub const DEVICE_SECRET_LEN: usize = 32;

/// A device secret that is wiped when it drops.
pub type DeviceSecret = Zeroizing<[u8; DEVICE_SECRET_LEN]>;

/// Environment variable that overrides the platform backend.
pub const KEYSTORE_ENV: &str = "BHODL_KEYSTORE";
/// [`KEYSTORE_ENV`] value selecting [`EphemeralKeystore`] (tests only).
pub const EPHEMERAL_TEST_VALUE: &str = "ephemeral-test";

/// Which OS facility protects a device secret. Persisted (snake case)
/// inside the KDF descriptor.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum KeystoreBackend {
    /// Windows Data Protection API, `CurrentUser` scope.
    WindowsDpapi,
    /// macOS login Keychain (generic password item).
    MacosKeychain,
    /// freedesktop.org Secret Service over the session D-Bus.
    SecretService,
    /// Process-memory store used by tests ([`EphemeralKeystore`]).
    EphemeralTest,
}

impl KeystoreBackend {
    /// Short human-readable name for UI / error text.
    pub fn display_name(self) -> &'static str {
        match self {
            Self::WindowsDpapi => "Windows DPAPI",
            Self::MacosKeychain => "macOS Keychain",
            Self::SecretService => "Secret Service",
            Self::EphemeralTest => "ephemeral test keystore",
        }
    }
}

impl fmt::Display for KeystoreBackend {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.display_name())
    }
}

/// How to find a device secret again: persisted in the KDF descriptor.
///
/// `handle` is backend specific: the hex DPAPI blob (Windows) or the item
/// id (Keychain / Secret Service / ephemeral). It is not secret by
/// itself.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct WrapRecord {
    /// Backend that holds the secret.
    pub backend: KeystoreBackend,
    /// Backend-specific locator.
    pub handle: String,
}

/// Failures of the OS keystore.
#[derive(Debug, thiserror::Error)]
#[non_exhaustive]
pub enum KeystoreError {
    /// No usable keystore right now: no Secret Service on the session bus,
    /// keychain locked and the user dismissed the prompt, unsupported
    /// platform. Retrying later (after unlocking the keyring) may work.
    #[error("OS keystore unavailable: {0}")]
    Unavailable(String),
    /// The keystore answered but the device secret is gone or cannot be
    /// decrypted by this OS account: OS reinstall, another user, deleted
    /// keyring entry, data dir moved to another machine. Permanent.
    #[error("device key not found in the OS keystore: {0}")]
    NotFound(String),
    /// Any other backend failure.
    #[error("OS keystore error: {0}")]
    Failed(String),
}

/// An OS facility that can keep a 256-bit device secret.
pub trait DeviceKeystore: Send + Sync + fmt::Debug {
    /// Human-readable backend name.
    fn name(&self) -> &'static str;

    /// Check that the backend is usable for a NEW secret, without
    /// prompting the user where avoidable.
    fn probe(&self) -> Result<(), KeystoreError>;

    /// Persist `secret` and return the record needed to load it again.
    fn store(&self, secret: &[u8; DEVICE_SECRET_LEN]) -> Result<WrapRecord, KeystoreError>;

    /// Load the secret described by `record`.
    fn load(&self, record: &WrapRecord) -> Result<DeviceSecret, KeystoreError>;

    /// Remove the secret described by `record` (best effort; a no-op for
    /// backends whose record carries the wrapped secret itself).
    fn delete(&self, record: &WrapRecord) -> Result<(), KeystoreError>;
}

/// Fresh device secret from the OS CSPRNG.
pub fn generate_device_secret() -> Result<DeviceSecret, KeystoreError> {
    let mut secret = Zeroizing::new([0u8; DEVICE_SECRET_LEN]);
    rand::rngs::OsRng
        .try_fill_bytes(secret.as_mut())
        .map_err(|e| KeystoreError::Failed(format!("OS RNG unavailable: {e}")))?;
    Ok(secret)
}

/// Random item id (128 bits, hex) for item-based backends.
pub(crate) fn new_item_id() -> Result<String, KeystoreError> {
    let mut id = [0u8; 16];
    rand::rngs::OsRng
        .try_fill_bytes(&mut id)
        .map_err(|e| KeystoreError::Failed(format!("OS RNG unavailable: {e}")))?;
    Ok(hex::encode(id))
}

/// Reject a record created by another backend (e.g. a data dir copied
/// from Windows to macOS): the secret is not reachable from here.
pub(crate) fn ensure_backend(
    record: &WrapRecord,
    expected: KeystoreBackend,
) -> Result<(), KeystoreError> {
    if record.backend == expected {
        Ok(())
    } else {
        Err(KeystoreError::NotFound(format!(
            "the device key was stored in {} but this system provides {}",
            record.backend, expected
        )))
    }
}

/// Decode a 32-byte secret from the bytes a backend returned, wiping
/// the intermediate buffer.
pub(crate) fn secret_from_bytes(mut bytes: Vec<u8>) -> Result<DeviceSecret, KeystoreError> {
    let result = if bytes.len() == DEVICE_SECRET_LEN {
        let mut out = Zeroizing::new([0u8; DEVICE_SECRET_LEN]);
        out.copy_from_slice(&bytes);
        Ok(out)
    } else {
        Err(KeystoreError::Failed(format!(
            "device key has length {} (expected {DEVICE_SECRET_LEN})",
            bytes.len()
        )))
    };
    bytes.zeroize();
    result
}

/// The keystore of the running platform.
///
/// `BHODL_KEYSTORE=ephemeral-test` selects [`EphemeralKeystore`] (used by
/// the JVM test suites, which run on CI hosts without a keyring).
/// Platforms without an integration (Android / iOS for now) get an
/// [`UnavailableKeystore`], which makes new stores require a passphrase.
pub fn platform_keystore() -> Box<dyn DeviceKeystore> {
    if std::env::var(KEYSTORE_ENV).is_ok_and(|v| v == EPHEMERAL_TEST_VALUE) {
        return Box::new(EphemeralKeystore);
    }
    platform_default()
}

#[cfg(windows)]
fn platform_default() -> Box<dyn DeviceKeystore> {
    Box::new(dpapi::DpapiKeystore)
}

#[cfg(target_os = "macos")]
fn platform_default() -> Box<dyn DeviceKeystore> {
    Box::new(keychain::KeychainKeystore)
}

#[cfg(target_os = "linux")]
fn platform_default() -> Box<dyn DeviceKeystore> {
    Box::new(secret_service::SecretServiceKeystore)
}

#[cfg(not(any(windows, target_os = "macos", target_os = "linux")))]
fn platform_default() -> Box<dyn DeviceKeystore> {
    Box::new(UnavailableKeystore::new(
        "no OS keystore integration on this platform yet",
    ))
}

// ---------------------------------------------------------------------------
// Ephemeral (tests)
// ---------------------------------------------------------------------------

fn ephemeral_map() -> &'static Mutex<HashMap<String, [u8; DEVICE_SECRET_LEN]>> {
    static MAP: OnceLock<Mutex<HashMap<String, [u8; DEVICE_SECRET_LEN]>>> = OnceLock::new();
    MAP.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Process-memory keystore for tests. Secrets vanish at process exit.
///
/// Item ids are random, so concurrent tests never see each other's
/// entries; [`DeviceKeystore::delete`] simulates a lost OS entry.
#[derive(Debug, Clone, Copy, Default)]
pub struct EphemeralKeystore;

impl DeviceKeystore for EphemeralKeystore {
    fn name(&self) -> &'static str {
        KeystoreBackend::EphemeralTest.display_name()
    }

    fn probe(&self) -> Result<(), KeystoreError> {
        Ok(())
    }

    fn store(&self, secret: &[u8; DEVICE_SECRET_LEN]) -> Result<WrapRecord, KeystoreError> {
        let id = new_item_id()?;
        ephemeral_map()
            .lock()
            .map_err(|_| KeystoreError::Failed("ephemeral keystore poisoned".into()))?
            .insert(id.clone(), *secret);
        Ok(WrapRecord {
            backend: KeystoreBackend::EphemeralTest,
            handle: id,
        })
    }

    fn load(&self, record: &WrapRecord) -> Result<DeviceSecret, KeystoreError> {
        ensure_backend(record, KeystoreBackend::EphemeralTest)?;
        let map = ephemeral_map()
            .lock()
            .map_err(|_| KeystoreError::Failed("ephemeral keystore poisoned".into()))?;
        map.get(&record.handle)
            .map(|s| Zeroizing::new(*s))
            .ok_or_else(|| KeystoreError::NotFound("no such ephemeral entry".into()))
    }

    fn delete(&self, record: &WrapRecord) -> Result<(), KeystoreError> {
        if let Ok(mut map) = ephemeral_map().lock() {
            if let Some(mut s) = map.remove(&record.handle) {
                s.zeroize();
            }
        }
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Unavailable
// ---------------------------------------------------------------------------

/// A keystore that is never usable. Returned on platforms without an
/// integration; tests use it to exercise the passphrase fallback.
#[derive(Debug, Clone)]
pub struct UnavailableKeystore {
    reason: String,
}

impl UnavailableKeystore {
    /// Keystore that fails every call with [`KeystoreError::Unavailable`].
    pub fn new(reason: impl Into<String>) -> Self {
        Self {
            reason: reason.into(),
        }
    }
}

impl DeviceKeystore for UnavailableKeystore {
    fn name(&self) -> &'static str {
        "none"
    }

    fn probe(&self) -> Result<(), KeystoreError> {
        Err(KeystoreError::Unavailable(self.reason.clone()))
    }

    fn store(&self, _secret: &[u8; DEVICE_SECRET_LEN]) -> Result<WrapRecord, KeystoreError> {
        Err(KeystoreError::Unavailable(self.reason.clone()))
    }

    fn load(&self, _record: &WrapRecord) -> Result<DeviceSecret, KeystoreError> {
        Err(KeystoreError::Unavailable(self.reason.clone()))
    }

    fn delete(&self, _record: &WrapRecord) -> Result<(), KeystoreError> {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ephemeral_round_trip_and_delete() {
        let ks = EphemeralKeystore;
        let secret = generate_device_secret().unwrap();
        let rec = ks.store(&secret).unwrap();
        assert_eq!(rec.backend, KeystoreBackend::EphemeralTest);
        assert_eq!(*ks.load(&rec).unwrap(), *secret);
        ks.delete(&rec).unwrap();
        assert!(matches!(ks.load(&rec), Err(KeystoreError::NotFound(_))));
    }

    #[test]
    fn foreign_backend_record_is_not_found() {
        let rec = WrapRecord {
            backend: KeystoreBackend::MacosKeychain,
            handle: "abc".into(),
        };
        assert!(matches!(
            EphemeralKeystore.load(&rec),
            Err(KeystoreError::NotFound(_))
        ));
    }

    #[test]
    fn unavailable_keystore_fails_every_call() {
        let ks = UnavailableKeystore::new("headless");
        assert!(matches!(ks.probe(), Err(KeystoreError::Unavailable(_))));
        let secret = generate_device_secret().unwrap();
        assert!(matches!(
            ks.store(&secret),
            Err(KeystoreError::Unavailable(_))
        ));
    }

    #[test]
    fn device_secrets_are_random() {
        let a = generate_device_secret().unwrap();
        let b = generate_device_secret().unwrap();
        assert_ne!(*a, *b);
        assert_ne!(*a, [0u8; DEVICE_SECRET_LEN]);
    }

    #[test]
    fn record_serialises_snake_case() {
        let rec = WrapRecord {
            backend: KeystoreBackend::WindowsDpapi,
            handle: "00".into(),
        };
        let json = serde_json::to_string(&rec).unwrap();
        assert_eq!(json, r#"{"backend":"windows_dpapi","handle":"00"}"#);
        let back: WrapRecord = serde_json::from_str(&json).unwrap();
        assert_eq!(back, rec);
    }

    #[test]
    fn short_secret_is_rejected() {
        assert!(matches!(
            secret_from_bytes(vec![1, 2, 3]),
            Err(KeystoreError::Failed(_))
        ));
    }
}
