//! Linux backend: freedesktop.org Secret Service over the session D-Bus
//! (`GNOME Keyring`, `KWallet` ≥ 5.97, `KeePassXC` with the integration on).
//!
//! The secret is stored as an item in the **`default`** collection (the
//! persistent "login" keyring, encrypted at rest with the user's login
//! password). The volatile `session` collection is deliberately never
//! used: it is wiped at logout, which would lock the user out of the
//! wallet. Items carry the attributes `application = io.bhodl` and
//! `bhodl-device-key-id = <random id>`; the id is the
//! [`WrapRecord::handle`].
//!
//! The session uses the "plain" Secret Service algorithm. DH session
//! encryption only protects the secret from other clients of the same
//! per-user session bus — which run as the same user and could simply
//! ask the Secret Service for the item themselves — so it would add a
//! large crypto dependency tree for no security gain.
//!
//! Headless hosts (no session bus, no Secret Service provider) report
//! [`KeystoreError::Unavailable`]; BHODL then requires a strong
//! passphrase instead of a short PIN for new stores.

use std::collections::HashMap;

use dbus_secret_service::{EncryptionType, Error as SsError, SecretService};
use zeroize::Zeroizing;

use crate::{
    DEVICE_SECRET_LEN, DeviceKeystore, DeviceSecret, KeystoreBackend, KeystoreError, WrapRecord,
    ensure_backend, new_item_id, secret_from_bytes,
};

const APP_ATTR: &str = "application";
const APP_VALUE: &str = "io.bhodl";
const ID_ATTR: &str = "bhodl-device-key-id";
const LABEL: &str = "BHODL wallet device key";
/// Upper bound on how long an unlock prompt may block an unlock call.
const PROMPT_TIMEOUT_SECS: u64 = 120;

/// Secret Service device keystore (Linux only).
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct SecretServiceKeystore;

fn map_err(e: &SsError, what: &str) -> KeystoreError {
    let msg = format!("{what}: {e}");
    match e {
        SsError::NoResult => KeystoreError::NotFound(msg),
        SsError::Unavailable | SsError::Locked | SsError::Prompt | SsError::Dbus(_) => {
            KeystoreError::Unavailable(msg)
        }
        _ => KeystoreError::Failed(msg),
    }
}

fn connect() -> Result<SecretService, KeystoreError> {
    SecretService::connect_with_max_prompt_timeout(EncryptionType::Plain, PROMPT_TIMEOUT_SECS)
        .map_err(|e| {
            KeystoreError::Unavailable(format!("no Secret Service on the session bus: {e}"))
        })
}

impl DeviceKeystore for SecretServiceKeystore {
    fn name(&self) -> &'static str {
        KeystoreBackend::SecretService.display_name()
    }

    fn probe(&self) -> Result<(), KeystoreError> {
        let ss = connect()?;
        // A persistent default collection must exist (it may be locked;
        // the store call will prompt to unlock it).
        ss.get_default_collection()
            .map(|_| ())
            .map_err(|e| KeystoreError::Unavailable(format!("no default keyring collection: {e}")))
    }

    fn store(&self, secret: &[u8; DEVICE_SECRET_LEN]) -> Result<WrapRecord, KeystoreError> {
        let ss = connect()?;
        let collection = ss.get_default_collection().map_err(|e| {
            KeystoreError::Unavailable(format!("no default keyring collection: {e}"))
        })?;
        collection
            .ensure_unlocked()
            .map_err(|e| map_err(&e, "unlock default keyring"))?;
        let id = new_item_id()?;
        let attrs = HashMap::from([(APP_ATTR, APP_VALUE), (ID_ATTR, id.as_str())]);
        // Stored hex-encoded as text/plain: some providers only accept
        // text secrets.
        let encoded = Zeroizing::new(hex::encode(secret));
        collection
            .create_item(LABEL, attrs, encoded.as_bytes(), true, "text/plain")
            .map_err(|e| map_err(&e, "store device key in keyring"))?;
        Ok(WrapRecord {
            backend: KeystoreBackend::SecretService,
            handle: id,
        })
    }

    fn load(&self, record: &WrapRecord) -> Result<DeviceSecret, KeystoreError> {
        ensure_backend(record, KeystoreBackend::SecretService)?;
        let ss = connect()?;
        let attrs = HashMap::from([(APP_ATTR, APP_VALUE), (ID_ATTR, record.handle.as_str())]);
        let found = ss
            .search_items(attrs)
            .map_err(|e| map_err(&e, "search keyring"))?;
        let item = if let Some(item) = found.unlocked.first() {
            item
        } else if let Some(item) = found.locked.first() {
            item.unlock()
                .map_err(|e| map_err(&e, "unlock keyring item"))?;
            item
        } else {
            return Err(KeystoreError::NotFound(
                "no BHODL device key with this id in the keyring".into(),
            ));
        };
        let encoded = Zeroizing::new(
            item.get_secret()
                .map_err(|e| map_err(&e, "read device key from keyring"))?,
        );
        let raw = hex::decode(encoded.as_slice())
            .map_err(|_| KeystoreError::Failed("keyring device key is not valid hex".into()))?;
        secret_from_bytes(raw)
    }

    fn delete(&self, record: &WrapRecord) -> Result<(), KeystoreError> {
        ensure_backend(record, KeystoreBackend::SecretService)?;
        let ss = connect()?;
        let attrs = HashMap::from([(APP_ATTR, APP_VALUE), (ID_ATTR, record.handle.as_str())]);
        let found = ss
            .search_items(attrs)
            .map_err(|e| map_err(&e, "search keyring"))?;
        for item in found.unlocked.iter().chain(found.locked.iter()) {
            item.delete()
                .map_err(|e| map_err(&e, "delete device key from keyring"))?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::generate_device_secret;

    /// Needs a running Secret Service (desktop session): run manually,
    /// `cargo test -p bhodl-keystore -- --ignored`, see test-book.
    #[test]
    #[ignore = "needs a desktop session with a Secret Service provider"]
    fn secret_service_round_trip() {
        let ks = SecretServiceKeystore;
        ks.probe().unwrap();
        let secret = generate_device_secret().unwrap();
        let rec = ks.store(&secret).unwrap();
        assert_eq!(*ks.load(&rec).unwrap(), *secret);
        ks.delete(&rec).unwrap();
        assert!(matches!(ks.load(&rec), Err(KeystoreError::NotFound(_))));
    }
}
