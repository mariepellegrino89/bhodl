//! macOS backend: generic-password item in the user's login Keychain.
//!
//! Service `io.bhodl.device-key`, account = random 128-bit item id (the
//! [`WrapRecord::handle`]). The Keychain encrypts the item at rest under
//! the login keychain key, which is unlocked by the user's macOS login;
//! a copy of `~/.bhodl` carries only the item id.

use security_framework::passwords::{
    delete_generic_password, get_generic_password, set_generic_password,
};

use crate::{
    DEVICE_SECRET_LEN, DeviceKeystore, DeviceSecret, KeystoreBackend, KeystoreError, WrapRecord,
    ensure_backend, new_item_id, secret_from_bytes,
};

const SERVICE: &str = "io.bhodl.device-key";
/// Account name used by [`DeviceKeystore::probe`]; never written.
const PROBE_ACCOUNT: &str = "bhodl-availability-probe";

/// `errSecItemNotFound`.
const ERR_ITEM_NOT_FOUND: i32 = -25_300;
/// `errSecInteractionNotAllowed` (keychain locked, no UI possible).
const ERR_INTERACTION_NOT_ALLOWED: i32 = -25_308;
/// `errSecAuthFailed`.
const ERR_AUTH_FAILED: i32 = -25_293;
/// `errSecUserCanceled`.
const ERR_USER_CANCELED: i32 = -128;
/// `errSecNoSuchKeychain`.
const ERR_NO_SUCH_KEYCHAIN: i32 = -25_294;
/// `errSecNotAvailable`.
const ERR_NOT_AVAILABLE: i32 = -25_291;

/// Login-Keychain device keystore (macOS only).
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct KeychainKeystore;

fn map_err(e: security_framework::base::Error, what: &str) -> KeystoreError {
    let code = e.code();
    let msg = format!("{what}: {e} (OSStatus {code})");
    match code {
        ERR_ITEM_NOT_FOUND => KeystoreError::NotFound(msg),
        ERR_INTERACTION_NOT_ALLOWED
        | ERR_AUTH_FAILED
        | ERR_USER_CANCELED
        | ERR_NO_SUCH_KEYCHAIN
        | ERR_NOT_AVAILABLE => KeystoreError::Unavailable(msg),
        _ => KeystoreError::Failed(msg),
    }
}

impl DeviceKeystore for KeychainKeystore {
    fn name(&self) -> &'static str {
        KeystoreBackend::MacosKeychain.display_name()
    }

    fn probe(&self) -> Result<(), KeystoreError> {
        // A lookup of an item that never exists: "not found" proves the
        // keychain is reachable without creating anything or prompting.
        match get_generic_password(SERVICE, PROBE_ACCOUNT) {
            Ok(_) => Ok(()),
            Err(e) => match map_err(e, "keychain probe") {
                KeystoreError::NotFound(_) => Ok(()),
                other => Err(other),
            },
        }
    }

    fn store(&self, secret: &[u8; DEVICE_SECRET_LEN]) -> Result<WrapRecord, KeystoreError> {
        let id = new_item_id()?;
        set_generic_password(SERVICE, &id, secret)
            .map_err(|e| map_err(e, "store device key in keychain"))?;
        Ok(WrapRecord {
            backend: KeystoreBackend::MacosKeychain,
            handle: id,
        })
    }

    fn load(&self, record: &WrapRecord) -> Result<DeviceSecret, KeystoreError> {
        ensure_backend(record, KeystoreBackend::MacosKeychain)?;
        let bytes = get_generic_password(SERVICE, &record.handle)
            .map_err(|e| map_err(e, "read device key from keychain"))?;
        secret_from_bytes(bytes)
    }

    fn delete(&self, record: &WrapRecord) -> Result<(), KeystoreError> {
        ensure_backend(record, KeystoreBackend::MacosKeychain)?;
        match delete_generic_password(SERVICE, &record.handle) {
            Ok(()) => Ok(()),
            Err(e) if e.code() == ERR_ITEM_NOT_FOUND => Ok(()),
            Err(e) => Err(map_err(e, "delete device key from keychain")),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::generate_device_secret;

    /// Touches the real login keychain: run manually on a Mac
    /// (`cargo test -p bhodl-keystore -- --ignored`), see test-book.
    #[test]
    #[ignore = "needs an unlocked macOS login keychain"]
    fn keychain_round_trip() {
        let ks = KeychainKeystore;
        ks.probe().unwrap();
        let secret = generate_device_secret().unwrap();
        let rec = ks.store(&secret).unwrap();
        assert_eq!(*ks.load(&rec).unwrap(), *secret);
        ks.delete(&rec).unwrap();
        assert!(matches!(ks.load(&rec), Err(KeystoreError::NotFound(_))));
    }
}
