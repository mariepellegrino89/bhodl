//! Security audit 2026-10 — OS-keystore device binding of the SQLCipher
//! keys: creation policy, descriptor v3, transparent migration of PIN-only
//! stores (including interrupted migrations), lost / unreachable keystore
//! errors, and the registry → watch-state rekey sweep.
//!
//! Every test runs against a fake keystore (`EphemeralKeystore`, the test
//! default, or `UnavailableKeystore` via `keystore::override_for_test`):
//! CI never touches a real OS keyring.

use std::{fs, path::Path, sync::Arc};

use bhodl_core::{KdfParams, SALT_LEN};
use bhodl_keystore::{
    DeviceKeystore, EphemeralKeystore, KeystoreError, UnavailableKeystore, WrapRecord,
    generate_device_secret,
};
use bhodl_wallet_onchain::{ENCRYPTION_KEY_LEN, OnchainAccount, open_registry};

use crate::kdf::{
    KdfFile, KdfScope, KeyProtection, UnlockedKdf, WATCH_REKEY_MARKER, migrate_if_stale,
    seal_v3_for_test, unlock_with, write_kdf_file,
};
use crate::keystore;
use crate::{
    Network, RegistryHandle, WalletError, WalletHandle, WatchBipFfi, device_keystore_status,
    extract_account_xpub,
};
use bhodl_core::Network as CoreNetwork;

const MNEMONIC: &str =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
const PIN: &str = "123456";
const PASSPHRASE: &str = "Tr3s-Securise-42";

fn dir_str(p: &Path) -> String {
    p.to_string_lossy().into_owned()
}

fn read_kdf_at(path: &Path) -> KdfFile {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}

fn read_wallet_kdf(dir: &Path) -> KdfFile {
    read_kdf_at(&dir.join(KdfFile::FILE_NAME))
}

fn wrap_of(f: &KdfFile) -> WrapRecord {
    match f.protection.as_ref().expect("v3 protection") {
        KeyProtection::DeviceKey { wrap } => wrap.clone(),
        KeyProtection::SecretOnly => panic!("expected a device-bound descriptor"),
    }
}

fn create_wallet(dir: &Path, pin: &str) -> Result<Arc<WalletHandle>, WalletError> {
    WalletHandle::create_or_load_persistent(
        dir_str(dir),
        Network::Signet,
        MNEMONIC.into(),
        String::new(),
        pin.into(),
    )
}

fn unlock(dir: &Path, pin: &str) -> Result<Arc<WalletHandle>, WalletError> {
    WalletHandle::unlock_persistent(dir_str(dir), Network::Signet, pin.into())
}

/// Pre-2026-10 PIN-only wallet: unsealed v1 descriptor with CURRENT
/// params, DB keyed with the bare Argon2id output. Returns that key.
fn install_pin_only_wallet(dir: &Path, pin: &str) -> [u8; ENCRYPTION_KEY_LEN] {
    let salt = [0x5Au8; SALT_LEN];
    write_kdf_file(dir, &salt, &KdfParams::CURRENT).unwrap();
    let key = bhodl_core::derive_key_from_pin_with_params(pin, &salt, &KdfParams::CURRENT).unwrap();
    OnchainAccount::create_or_load_persistent(
        CoreNetwork::Signet,
        MNEMONIC,
        "",
        &dir.join("wallet.sqlite"),
        &key,
    )
    .unwrap();
    key
}

fn db_opens_with(dir: &Path, key: &[u8; ENCRYPTION_KEY_LEN]) -> bool {
    OnchainAccount::load_persistent(CoreNetwork::Signet, &dir.join("wallet.sqlite"), key).is_ok()
}

fn unavailable() -> keystore::TestKeystoreGuard {
    keystore::override_for_test(Arc::new(UnavailableKeystore::new("headless test host")))
}

// ---------------------------------------------------------------------------
// Creation
// ---------------------------------------------------------------------------

#[test]
fn fresh_wallet_is_device_bound_and_pin_alone_does_not_open_it() {
    let tmp = tempfile::tempdir().unwrap();
    create_wallet(tmp.path(), PIN).unwrap();

    let f = read_wallet_kdf(tmp.path());
    assert_eq!(f.version, KdfFile::CURRENT_VERSION);
    assert!(f.mac_hex.is_some(), "sealed after the first open");
    let wrap = wrap_of(&f);
    assert_eq!(wrap.backend, bhodl_keystore::KeystoreBackend::EphemeralTest);

    // The offline attacker's view: salt + params + PIN, no device secret.
    let pin_only =
        bhodl_core::derive_key_from_pin_with_params(PIN, &f.salt().unwrap(), &f.kdf).unwrap();
    assert!(
        !db_opens_with(tmp.path(), &pin_only),
        "a correct PIN without the device secret must not open the DB"
    );
    // With the device secret the HKDF key opens it.
    let secret = EphemeralKeystore.load(&wrap).unwrap();
    let bound = bhodl_core::combine_with_device_secret(&pin_only, &secret, &f.salt().unwrap());
    assert!(db_opens_with(tmp.path(), &bound));

    unlock(tmp.path(), PIN).expect("normal unlock");
    assert!(matches!(
        unlock(tmp.path(), "654321"),
        Err(WalletError::Storage { .. })
    ));
}

#[test]
fn fresh_wallet_accepts_an_optional_passphrase_when_bound() {
    let tmp = tempfile::tempdir().unwrap();
    create_wallet(tmp.path(), PASSPHRASE).unwrap();
    wrap_of(&read_wallet_kdf(tmp.path()));
    unlock(tmp.path(), PASSPHRASE).unwrap();
    // A short alphanumeric secret is neither a PIN nor a passphrase.
    let other = tempfile::tempdir().unwrap();
    assert!(matches!(
        create_wallet(other.path(), "abc123"),
        Err(WalletError::KeyDerivation { .. })
    ));
}

#[test]
fn without_keystore_a_pin_is_refused_and_a_passphrase_is_required() {
    let _g = unavailable();
    let tmp = tempfile::tempdir().unwrap();
    let err = create_wallet(tmp.path(), PIN)
        .err()
        .expect("PIN must be refused");
    match err {
        WalletError::KeyDerivation { detail } => {
            assert!(detail.contains("passphrase required"), "{detail}");
        }
        other => panic!("unexpected {other:?}"),
    }
    assert!(
        !tmp.path().join(KdfFile::FILE_NAME).exists(),
        "nothing written"
    );

    create_wallet(tmp.path(), PASSPHRASE).expect("passphrase accepted");
    let f = read_wallet_kdf(tmp.path());
    assert_eq!(f.version, KdfFile::CURRENT_VERSION);
    assert_eq!(f.protection, Some(KeyProtection::SecretOnly));
    unlock(tmp.path(), PASSPHRASE).unwrap();
}

#[test]
fn without_keystore_the_registry_also_requires_a_passphrase() {
    let _g = unavailable();
    let tmp = tempfile::tempdir().unwrap();
    assert!(matches!(
        RegistryHandle::create_or_load(dir_str(tmp.path()), PIN.into()),
        Err(WalletError::KeyDerivation { .. })
    ));
    RegistryHandle::create_or_load(dir_str(tmp.path()), PASSPHRASE.into()).unwrap();
}

#[test]
fn keystore_status_reports_policy() {
    let ok = device_keystore_status();
    assert!(ok.available);
    assert_eq!(ok.min_pin_length, 6);
    assert_eq!(ok.min_passphrase_length, 12);
    assert!(ok.detail.is_none());
    let _g = unavailable();
    let none = device_keystore_status();
    assert!(!none.available);
    assert!(none.detail.unwrap().contains("headless"));
}

// ---------------------------------------------------------------------------
// Lost / unreachable keystore
// ---------------------------------------------------------------------------

#[test]
fn lost_device_key_is_reported_as_missing_never_as_wrong_pin() {
    let tmp = tempfile::tempdir().unwrap();
    create_wallet(tmp.path(), PIN).unwrap();
    let wrap = wrap_of(&read_wallet_kdf(tmp.path()));
    // OS reinstall / other user: the keystore entry is gone.
    EphemeralKeystore.delete(&wrap).unwrap();

    for pin in [PIN, "000000"] {
        assert!(
            matches!(
                unlock(tmp.path(), pin),
                Err(WalletError::DeviceKeyMissing { .. })
            ),
            "pin {pin}"
        );
    }
    // Nothing on disk was touched by the failed attempts.
    assert_eq!(wrap_of(&read_wallet_kdf(tmp.path())), wrap);
}

#[test]
fn unreachable_keystore_is_reported_as_unavailable() {
    let tmp = tempfile::tempdir().unwrap();
    create_wallet(tmp.path(), PIN).unwrap();
    let _g = unavailable();
    assert!(matches!(
        unlock(tmp.path(), PIN),
        Err(WalletError::DeviceKeystoreUnavailable { .. })
    ));
}

#[test]
fn descriptor_mac_covers_the_protection_record() {
    let tmp = tempfile::tempdir().unwrap();
    create_wallet(tmp.path(), PIN).unwrap();
    let path = tmp.path().join(KdfFile::FILE_NAME);
    let f = read_kdf_at(&path);

    // Swap the wrap record for another valid entry: the keystore load
    // succeeds, but the MAC (and the key) no longer match.
    let other = EphemeralKeystore
        .store(&generate_device_secret().unwrap())
        .unwrap();
    let mut swapped = f.clone();
    swapped.protection = Some(KeyProtection::DeviceKey { wrap: other });
    fs::write(&path, serde_json::to_vec(&swapped).unwrap()).unwrap();
    assert!(matches!(
        unlock(tmp.path(), PIN),
        Err(WalletError::Storage { .. })
    ));

    // Downgrade to "secret_only" (strip the binding): refused too.
    let mut stripped = f.clone();
    stripped.protection = Some(KeyProtection::SecretOnly);
    fs::write(&path, serde_json::to_vec(&stripped).unwrap()).unwrap();
    assert!(unlock(tmp.path(), PIN).is_err());

    // v3 without any protection record is structurally invalid.
    let mut bare = f.clone();
    bare.protection = None;
    fs::write(&path, serde_json::to_vec(&bare).unwrap()).unwrap();
    let err = unlock(tmp.path(), PIN).err().unwrap();
    assert!(format!("{err:?}").contains("protection"), "{err:?}");

    // Restoring the original file restores access.
    fs::write(&path, serde_json::to_vec(&f).unwrap()).unwrap();
    unlock(tmp.path(), PIN).unwrap();
}

// ---------------------------------------------------------------------------
// Migration of PIN-only stores
// ---------------------------------------------------------------------------

#[test]
fn pin_only_wallet_migrates_to_device_bound_on_unlock() {
    let tmp = tempfile::tempdir().unwrap();
    let old_key = install_pin_only_wallet(tmp.path(), PIN);

    unlock(tmp.path(), PIN).expect("legacy wallet still opens");

    let f = read_wallet_kdf(tmp.path());
    assert_eq!(f.version, KdfFile::CURRENT_VERSION);
    wrap_of(&f);
    assert_eq!(f.salt().unwrap(), [0x5Au8; SALT_LEN], "salt kept");
    assert!(!KdfScope::Wallet.pending_path(tmp.path()).exists());
    assert!(
        !db_opens_with(tmp.path(), &old_key),
        "the PIN-only key must stop working after the migration"
    );
    unlock(tmp.path(), PIN).expect("unlock after migration");
    assert!(matches!(
        unlock(tmp.path(), "111111"),
        Err(WalletError::Storage { .. })
    ));
}

#[test]
fn pin_only_wallet_stays_openable_and_untouched_without_keystore() {
    let tmp = tempfile::tempdir().unwrap();
    let old_key = install_pin_only_wallet(tmp.path(), PIN);
    let _g = unavailable();
    unlock(tmp.path(), PIN).expect("must still open without a keystore");
    let f = read_wallet_kdf(tmp.path());
    // Sealed as v2 (readable by older builds), not device-bound.
    assert_eq!(f.version, KdfFile::SEALED_V2_VERSION);
    assert!(f.protection.is_none());
    assert!(db_opens_with(tmp.path(), &old_key));
    unlock(tmp.path(), PIN).unwrap();
}

#[test]
fn legacy_params_and_binding_migrate_in_one_step() {
    let tmp = tempfile::tempdir().unwrap();
    let salt = [0x33u8; SALT_LEN];
    write_kdf_file(tmp.path(), &salt, &KdfParams::LEGACY_OWASP_MIN).unwrap();
    let key = bhodl_core::derive_key_from_pin_with_params(PIN, &salt, &KdfParams::LEGACY_OWASP_MIN)
        .unwrap();
    OnchainAccount::create_or_load_persistent(
        CoreNetwork::Signet,
        MNEMONIC,
        "",
        &tmp.path().join("wallet.sqlite"),
        &key,
    )
    .unwrap();

    unlock(tmp.path(), PIN).unwrap();
    let f = read_wallet_kdf(tmp.path());
    assert_eq!(f.kdf, KdfParams::CURRENT);
    assert_ne!(f.salt().unwrap(), salt, "fresh salt on a param bump");
    wrap_of(&f);
    unlock(tmp.path(), PIN).unwrap();
}

/// Crash after the `.next` descriptor (and its keystore entry) was
/// written but BEFORE `PRAGMA rekey` committed: the old descriptor still
/// opens the DB; the stale `.next` and its orphan keystore entry are
/// discarded, and the migration simply runs again.
#[test]
fn interrupted_binding_before_rekey_keeps_old_key_and_cleans_up() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let old_key = install_pin_only_wallet(dir, PIN);

    let secret = generate_device_secret().unwrap();
    let orphan = EphemeralKeystore.store(&secret).unwrap();
    let salt = [0x5Au8; SALT_LEN];
    let pin_key =
        bhodl_core::derive_key_from_pin_with_params(PIN, &salt, &KdfParams::CURRENT).unwrap();
    let new_key = bhodl_core::combine_with_device_secret(&pin_key, &secret, &salt);
    seal_v3_for_test(
        &KdfScope::Wallet.pending_path(dir),
        &salt,
        &KdfParams::CURRENT,
        &KeyProtection::DeviceKey {
            wrap: orphan.clone(),
        },
        &new_key,
    )
    .unwrap();
    // "crash" — DB never rekeyed.
    assert!(db_opens_with(dir, &old_key));

    // Wrong PIN first: nothing promoted, nothing deleted.
    assert!(matches!(
        unlock(dir, "000000"),
        Err(WalletError::Storage { .. })
    ));
    assert!(KdfScope::Wallet.pending_path(dir).exists());

    unlock(dir, PIN).expect("old descriptor opens the DB");
    assert!(
        matches!(
            EphemeralKeystore.load(&orphan),
            Err(KeystoreError::NotFound(_))
        ),
        "orphan keystore entry removed"
    );
    // The migration re-ran with a fresh entry.
    let f = read_wallet_kdf(dir);
    assert_ne!(wrap_of(&f), orphan);
    assert!(!KdfScope::Wallet.pending_path(dir).exists());
    unlock(dir, PIN).unwrap();
}

/// Crash AFTER `PRAGMA rekey` committed but before `.next` was promoted:
/// only the `.next` descriptor opens the DB now. The unlock must fall
/// back to it and promote it — the old main descriptor is still there
/// and must not be what the user is left with.
#[test]
fn interrupted_binding_after_rekey_recovers_from_pending_descriptor() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let old_key = install_pin_only_wallet(dir, PIN);
    let old_main = fs::read(dir.join(KdfFile::FILE_NAME)).unwrap();

    let secret = generate_device_secret().unwrap();
    let wrap = EphemeralKeystore.store(&secret).unwrap();
    let salt = [0x5Au8; SALT_LEN];
    let pin_key =
        bhodl_core::derive_key_from_pin_with_params(PIN, &salt, &KdfParams::CURRENT).unwrap();
    let new_key = bhodl_core::combine_with_device_secret(&pin_key, &secret, &salt);
    seal_v3_for_test(
        &KdfScope::Wallet.pending_path(dir),
        &salt,
        &KdfParams::CURRENT,
        &KeyProtection::DeviceKey { wrap: wrap.clone() },
        &new_key,
    )
    .unwrap();
    {
        let acc = OnchainAccount::load_persistent(
            CoreNetwork::Signet,
            &dir.join("wallet.sqlite"),
            &old_key,
        )
        .unwrap();
        acc.rekey(&new_key).unwrap();
    } // "crash": old main descriptor + .next on disk.
    assert_eq!(fs::read(dir.join(KdfFile::FILE_NAME)).unwrap(), old_main);

    assert!(matches!(
        unlock(dir, "000000"),
        Err(WalletError::Storage { .. })
    ));
    assert!(
        KdfScope::Wallet.pending_path(dir).exists(),
        "kept on wrong PIN"
    );

    unlock(dir, PIN).expect("falls back to the pending descriptor");
    assert!(!KdfScope::Wallet.pending_path(dir).exists(), "promoted");
    assert_eq!(wrap_of(&read_wallet_kdf(dir)), wrap);
    assert!(EphemeralKeystore.load(&wrap).is_ok(), "live entry kept");
    unlock(dir, PIN).expect("unlock via the promoted descriptor");
}

/// A failing rekey (disk full, I/O error) must leave the store exactly as
/// openable as before: the main descriptor is never touched before the
/// rekey committed.
#[test]
fn failed_rekey_leaves_the_old_descriptor_in_charge() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    install_pin_only_wallet(dir, PIN);
    let ks = EphemeralKeystore;
    // Open (and seal) through the normal path, but WITHOUT migrating.
    let (account, unlocked): (OnchainAccount, UnlockedKdf) =
        unlock_with(dir, KdfScope::Wallet, PIN, false, &ks, |key| {
            Ok(OnchainAccount::load_persistent(
                CoreNetwork::Signet,
                &dir.join("wallet.sqlite"),
                key,
            )?)
        })
        .unwrap();
    let main_before = fs::read(dir.join(KdfFile::FILE_NAME)).unwrap();

    let res = migrate_if_stale(dir, KdfScope::Wallet, &unlocked, PIN, &ks, true, |_| {
        Err(WalletError::Storage {
            detail: "simulated rekey failure".into(),
        })
    });
    assert!(res.is_err());
    drop(account);
    assert_eq!(
        fs::read(dir.join(KdfFile::FILE_NAME)).unwrap(),
        main_before,
        "main descriptor untouched"
    );

    // Next unlock: main still opens, the stale .next goes, binding runs.
    unlock(dir, PIN).unwrap();
    wrap_of(&read_wallet_kdf(dir));
    unlock(dir, PIN).unwrap();
}

/// Rejected keystore write (e.g. keyring prompt dismissed) during an
/// opportunistic binding: the unlock still succeeds, nothing changes.
#[derive(Debug)]
struct ProbeOkStoreFails;

impl DeviceKeystore for ProbeOkStoreFails {
    fn name(&self) -> &'static str {
        "flaky"
    }
    fn probe(&self) -> Result<(), KeystoreError> {
        Ok(())
    }
    fn store(&self, _s: &[u8; 32]) -> Result<WrapRecord, KeystoreError> {
        Err(KeystoreError::Unavailable("prompt dismissed".into()))
    }
    fn load(&self, _r: &WrapRecord) -> Result<bhodl_keystore::DeviceSecret, KeystoreError> {
        Err(KeystoreError::Unavailable("prompt dismissed".into()))
    }
    fn delete(&self, _r: &WrapRecord) -> Result<(), KeystoreError> {
        Ok(())
    }
}

#[test]
fn binding_is_skipped_when_the_keystore_refuses_the_write() {
    let tmp = tempfile::tempdir().unwrap();
    let old_key = install_pin_only_wallet(tmp.path(), PIN);
    let _g = keystore::override_for_test(Arc::new(ProbeOkStoreFails));
    unlock(tmp.path(), PIN).expect("unlock must not fail because binding failed");
    assert!(read_wallet_kdf(tmp.path()).protection.is_none());
    assert!(db_opens_with(tmp.path(), &old_key));
}

// ---------------------------------------------------------------------------
// Registry + watch-state files
// ---------------------------------------------------------------------------

fn signet_xpub() -> String {
    let account = OnchainAccount::from_mnemonic(CoreNetwork::Signet, MNEMONIC, "").unwrap();
    extract_account_xpub(account.export_descriptors().external).expect("xpub")
}

/// A PIN-only registry (created on a host without keystore, passphrase
/// policy) with one watch wallet. Returns the watch wallet id.
fn pin_only_registry_with_watch(dir: &Path, secret: &str) -> String {
    let _g = unavailable();
    let handle = RegistryHandle::create_or_load(dir_str(dir), secret.into()).unwrap();
    let rec = handle
        .add_watch_xpub(
            "Cold".into(),
            signet_xpub(),
            Network::Signet,
            WatchBipFfi::Taproot,
        )
        .unwrap();
    assert!(handle.matches_device_pin(secret.into()).unwrap());
    rec.id
}

#[test]
fn pin_only_registry_migrates_and_watch_states_follow() {
    let tmp = tempfile::tempdir().unwrap();
    let id = pin_only_registry_with_watch(tmp.path(), PASSPHRASE);
    let before = read_kdf_at(&KdfScope::Registry.descriptor_path(tmp.path()));
    assert!(before.protection == Some(KeyProtection::SecretOnly));

    let handle = RegistryHandle::create_or_load(dir_str(tmp.path()), PASSPHRASE.into()).unwrap();
    let after = read_kdf_at(&KdfScope::Registry.descriptor_path(tmp.path()));
    wrap_of(&after);
    assert_eq!(after.salt().unwrap(), before.salt().unwrap(), "salt kept");
    assert!(!tmp.path().join(WATCH_REKEY_MARKER).exists(), "sweep done");

    // The watch-state file was re-encrypted under the new registry key.
    handle.open_watch(id.clone()).expect("watch state readable");
    assert!(
        handle
            .aggregate_watch_balance(Network::Signet)
            .unwrap()
            .stale_wallet_ids
            .is_empty()
    );
    // Device-PIN re-auth compares against the Argon2id output.
    assert!(handle.matches_device_pin(PASSPHRASE.into()).unwrap());
    assert!(
        !handle
            .matches_device_pin("Wrong-passphrase-1".into())
            .unwrap()
    );
    drop(handle);

    let again = RegistryHandle::create_or_load(dir_str(tmp.path()), PASSPHRASE.into()).unwrap();
    again.open_watch(id).unwrap();
}

/// Crash after the registry rekey but before the watch-state sweep:
/// the next unlock (through the pending descriptor) must finish the
/// sweep, using the PIN-derived key as the old key.
#[test]
fn interrupted_registry_migration_resumes_the_watch_sweep() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let id = pin_only_registry_with_watch(dir, PASSPHRASE);

    let desc = read_kdf_at(&KdfScope::Registry.descriptor_path(dir));
    let salt = desc.salt().unwrap();
    let pin_key =
        bhodl_core::derive_key_from_pin_with_params(PASSPHRASE, &salt, &desc.kdf).unwrap();
    let secret = generate_device_secret().unwrap();
    let wrap = EphemeralKeystore.store(&secret).unwrap();
    let new_key = bhodl_core::combine_with_device_secret(&pin_key, &secret, &salt);
    seal_v3_for_test(
        &KdfScope::Registry.pending_path(dir),
        &salt,
        &desc.kdf,
        &KeyProtection::DeviceKey { wrap },
        &new_key,
    )
    .unwrap();
    fs::write(dir.join(WATCH_REKEY_MARKER), b"pending\n").unwrap();
    open_registry(
        &dir.join(bhodl_wallet_onchain::REGISTRY_FILE_NAME),
        &pin_key,
    )
    .unwrap()
    .rekey(&new_key)
    .unwrap();
    // "crash": registry on the new key, watch file still on the old one.

    let handle = RegistryHandle::create_or_load(dir_str(dir), PASSPHRASE.into()).unwrap();
    assert!(!KdfScope::Registry.pending_path(dir).exists(), "promoted");
    assert!(!dir.join(WATCH_REKEY_MARKER).exists(), "sweep completed");
    handle
        .open_watch(id)
        .expect("watch state follows the new key");
}

#[test]
fn fresh_registry_is_device_bound_and_new_watch_files_use_its_key() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = RegistryHandle::create_or_load(dir_str(tmp.path()), PIN.into()).unwrap();
    wrap_of(&read_kdf_at(
        &KdfScope::Registry.descriptor_path(tmp.path()),
    ));
    let rec = handle
        .add_watch_xpub(
            "Cold".into(),
            signet_xpub(),
            Network::Signet,
            WatchBipFfi::Taproot,
        )
        .unwrap();
    drop(handle);
    let handle = RegistryHandle::create_or_load(dir_str(tmp.path()), PIN.into()).unwrap();
    handle.open_watch(rec.id).unwrap();
    assert!(handle.matches_device_pin(PIN.into()).unwrap());
    assert!(!handle.matches_device_pin("654321".into()).unwrap());
}

// ---------------------------------------------------------------------------
// Real OS keystore (manual: `cargo test -p bhodl-uniffi real_os_keystore -- --ignored`)
// ---------------------------------------------------------------------------

/// End to end against the REAL platform keystore (DPAPI / Keychain /
/// Secret Service): create, unlock, migrate a PIN-only wallet, and check
/// that a damaged device-key record is reported as missing. Ignored in
/// CI (no keyring on Linux runners, no unlocked keychain on macOS ones);
/// see test-book §54.
#[test]
#[ignore = "touches the real OS keystore"]
fn real_os_keystore_end_to_end() {
    let real: Arc<dyn DeviceKeystore> = Arc::from(bhodl_keystore::platform_keystore());
    real.probe()
        .expect("an OS keystore must be available for this test");
    let _g = keystore::override_for_test(real.clone());

    // Fresh device-bound wallet.
    let tmp = tempfile::tempdir().unwrap();
    create_wallet(tmp.path(), PIN).unwrap();
    let f = read_wallet_kdf(tmp.path());
    let wrap = wrap_of(&f);
    assert_ne!(wrap.backend, bhodl_keystore::KeystoreBackend::EphemeralTest);
    unlock(tmp.path(), PIN).unwrap();
    assert!(matches!(
        unlock(tmp.path(), "654321"),
        Err(WalletError::Storage { .. })
    ));

    // PIN-only wallet migrated through the real backend.
    let legacy = tempfile::tempdir().unwrap();
    let old_key = install_pin_only_wallet(legacy.path(), PIN);
    unlock(legacy.path(), PIN).unwrap();
    let migrated_wrap = wrap_of(&read_wallet_kdf(legacy.path()));
    assert!(!db_opens_with(legacy.path(), &old_key));
    unlock(legacy.path(), PIN).unwrap();

    // Simulate a lost OS entry: corrupt the handle (DPAPI: blob no longer
    // decrypts; item backends: unknown id). Must read "missing", not
    // "wrong PIN".
    let path = tmp.path().join(KdfFile::FILE_NAME);
    let mut broken = f.clone();
    let mut handle = wrap.handle.clone();
    let last = handle.pop().unwrap();
    handle.push(if last == '0' { '1' } else { '0' });
    broken.protection = Some(KeyProtection::DeviceKey {
        wrap: WrapRecord {
            backend: wrap.backend,
            handle,
        },
    });
    fs::write(&path, serde_json::to_vec(&broken).unwrap()).unwrap();
    assert!(matches!(
        unlock(tmp.path(), PIN),
        Err(WalletError::DeviceKeyMissing { .. })
    ));

    // Clean up item-based backends (no-op for DPAPI).
    let _ = real.delete(&wrap);
    let _ = real.delete(&migrated_wrap);
}
