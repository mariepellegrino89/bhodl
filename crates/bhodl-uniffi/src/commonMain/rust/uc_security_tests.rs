//! Device-PIN KDF edge cases (docs/use-cases.md SEC-042, SEC-051..SEC-054).

use std::{fs, path::Path, time::Duration};

use bhodl_core::{KdfParams, generate_salt};
use bhodl_wallet_onchain::{ENCRYPTION_KEY_LEN, REGISTRY_KDF_FILE_NAME};

use crate::{
    RegistryHandle, WalletError,
    kdf::{KdfFile, KdfScope, KdfSource, rename_with_retry, seal_kdf_file_with_mac, unlock_with},
};

const PIN: &str = "482916";

fn create_err(dir: &Path, pin: &str) -> WalletError {
    match RegistryHandle::create_or_load(dir.to_string_lossy().into_owned(), pin.to_string()) {
        Ok(_) => panic!("create_or_load must fail"),
        Err(e) => e,
    }
}

fn key_for(salt: &[u8; bhodl_core::SALT_LEN]) -> [u8; ENCRYPTION_KEY_LEN] {
    bhodl_core::derive_key_from_pin_with_params(PIN, salt, &KdfParams::CURRENT).unwrap()
}

fn read_kdf(path: &Path) -> KdfFile {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}

// SEC-042
#[test]
fn empty_pin_is_refused_before_any_file_is_written() {
    let tmp = tempfile::tempdir().unwrap();
    let err = create_err(tmp.path(), "");
    assert!(matches!(err, WalletError::KeyDerivation { .. }), "{err:?}");
    assert!(!tmp.path().join(REGISTRY_KDF_FILE_NAME).exists());
    assert!(!tmp.path().join("registry.sqlite").exists());

    let direct = unlock_with(tmp.path(), KdfScope::Registry, "", true, |_| Ok(()));
    assert!(matches!(direct, Err(WalletError::KeyDerivation { .. })));
}

// SEC-051
#[test]
fn unknown_kdf_descriptor_version_is_refused_as_newer() {
    let tmp = tempfile::tempdir().unwrap();
    let salt = generate_salt().unwrap();
    let file = KdfFile {
        version: 9,
        salt_hex: hex::encode(salt),
        kdf: KdfParams::CURRENT,
        mac_hex: Some("00".repeat(32)),
        nonce_hex: None,
        wrapped_key_hex: None,
    };
    fs::write(
        tmp.path().join(REGISTRY_KDF_FILE_NAME),
        serde_json::to_vec(&file).unwrap(),
    )
    .unwrap();
    let err = create_err(tmp.path(), PIN);
    match err {
        WalletError::Storage { detail } => {
            assert!(
                detail.contains("unsupported kdf descriptor version 9"),
                "{detail}"
            );
        }
        other => panic!("expected Storage, got {other:?}"),
    }
    assert!(!tmp.path().join("registry.sqlite").exists());
}

/// The data key the main (v3) descriptor wraps under [`PIN`].
fn unwrap_main(dir: &Path) -> crate::kdf::SecretKey {
    crate::kdf::unwrap_file(
        &KdfScope::Registry.descriptor_path(dir),
        PIN,
        crate::kdf::WrapPurpose::DevicePin,
    )
    .unwrap()
}

// SEC-052
#[test]
fn interrupted_migration_falls_back_to_next_and_promotes_it() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let old_salt = generate_salt().unwrap();
    let new_salt = generate_salt().unwrap();
    // Main descriptor still describes the pre-rekey key...
    seal_kdf_file_with_mac(
        &KdfScope::Registry.descriptor_path(dir),
        &old_salt,
        &KdfParams::CURRENT,
        &key_for(&old_salt),
    )
    .unwrap();
    // ...but the rekey committed: only the `.next` key opens the DB.
    let new_key = key_for(&new_salt);
    seal_kdf_file_with_mac(
        &KdfScope::Registry.pending_path(dir),
        &new_salt,
        &KdfParams::CURRENT,
        &new_key,
    )
    .unwrap();

    let mut opens = 0;
    let (_, unlocked) = unlock_with(dir, KdfScope::Registry, PIN, true, |k| {
        opens += 1;
        if *k == new_key {
            Ok(())
        } else {
            Err(WalletError::Storage {
                detail: "encryption key rejected".into(),
            })
        }
    })
    .unwrap();

    assert_eq!(unlocked.source, KdfSource::Pending);
    assert_eq!(opens, 2, "main descriptor first, then `.next`");
    assert!(
        !KdfScope::Registry.pending_path(dir).exists(),
        "`.next` promoted"
    );
    // The v2 `.next` was superseded by a v3 main descriptor wrapping
    // the key that opened the DB.
    let main = read_kdf(&KdfScope::Registry.descriptor_path(dir));
    assert_eq!(main.version, KdfFile::WRAPPED_VERSION);
    assert_eq!(*unwrap_main(dir), new_key);
    assert!(KdfScope::Registry.marker_path(dir).exists());

    // The promoted descriptor is the one used from now on.
    let (_, again) = unlock_with(dir, KdfScope::Registry, PIN, true, |k| {
        if *k == new_key {
            Ok(())
        } else {
            Err(WalletError::Storage {
                detail: "rejected".into(),
            })
        }
    })
    .unwrap();
    assert_eq!(again.source, KdfSource::Descriptor);
}

// SEC-052
#[test]
fn stale_next_from_an_uncommitted_rekey_is_removed() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let salt = generate_salt().unwrap();
    let key = key_for(&salt);
    seal_kdf_file_with_mac(
        &KdfScope::Registry.descriptor_path(dir),
        &salt,
        &KdfParams::CURRENT,
        &key,
    )
    .unwrap();
    let other_salt = generate_salt().unwrap();
    seal_kdf_file_with_mac(
        &KdfScope::Registry.pending_path(dir),
        &other_salt,
        &KdfParams::CURRENT,
        &key_for(&other_salt),
    )
    .unwrap();

    let (_, unlocked) = unlock_with(dir, KdfScope::Registry, PIN, true, |k| {
        if *k == key {
            Ok(())
        } else {
            Err(WalletError::Storage {
                detail: "rejected".into(),
            })
        }
    })
    .unwrap();
    assert_eq!(unlocked.source, KdfSource::Descriptor);
    assert!(!KdfScope::Registry.pending_path(dir).exists());
    assert_eq!(*unwrap_main(dir), key);
}

// SEC-052 — a wrong PIN with both descriptors reports the main one's error.
#[test]
fn wrong_pin_with_a_pending_descriptor_keeps_both_files() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let salt = generate_salt().unwrap();
    seal_kdf_file_with_mac(
        &KdfScope::Registry.descriptor_path(dir),
        &salt,
        &KdfParams::CURRENT,
        &key_for(&salt),
    )
    .unwrap();
    let next_salt = generate_salt().unwrap();
    seal_kdf_file_with_mac(
        &KdfScope::Registry.pending_path(dir),
        &next_salt,
        &KdfParams::CURRENT,
        &key_for(&next_salt),
    )
    .unwrap();
    let result = unlock_with(dir, KdfScope::Registry, "999999", true, |_| Ok(()));
    assert!(matches!(result, Err(WalletError::Storage { .. })));
    assert!(KdfScope::Registry.descriptor_path(dir).exists());
    assert!(KdfScope::Registry.pending_path(dir).exists());
}

// SEC-053
#[test]
fn seal_marker_left_by_a_wipe_does_not_block_a_new_registry() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    fs::write(KdfScope::Registry.marker_path(dir), b"sealed\n").unwrap();
    assert!(!KdfScope::Registry.descriptor_path(dir).exists());

    let handle =
        RegistryHandle::create_or_load(dir.to_string_lossy().into_owned(), PIN.to_string());
    assert!(handle.is_ok(), "{:?}", handle.err());
    drop(handle);

    let sealed = read_kdf(&KdfScope::Registry.descriptor_path(dir));
    assert_eq!(sealed.version, KdfFile::WRAPPED_VERSION);
    assert!(sealed.wrapped_key_hex.is_some());
    assert!(KdfScope::Registry.marker_path(dir).exists());
    // And it reopens with the same PIN.
    assert!(
        RegistryHandle::create_or_load(dir.to_string_lossy().into_owned(), PIN.to_string()).is_ok()
    );
}

// SEC-054
#[test]
fn rename_with_retry_gives_up_at_once_on_a_missing_source() {
    let tmp = tempfile::tempdir().unwrap();
    let started = std::time::Instant::now();
    let err = rename_with_retry(&tmp.path().join("missing"), &tmp.path().join("to")).unwrap_err();
    assert_eq!(err.kind(), std::io::ErrorKind::NotFound);
    assert!(
        started.elapsed() < Duration::from_millis(40),
        "no back-off on NotFound"
    );
}

// SEC-054 — a transient sharing violation (AV / indexer) is retried.
#[cfg(windows)]
#[test]
fn rename_with_retry_survives_a_transient_sharing_violation() {
    use std::os::windows::fs::OpenOptionsExt;
    let tmp = tempfile::tempdir().unwrap();
    let from = tmp.path().join("descriptor.tmp");
    let to = tmp.path().join("descriptor.json");
    fs::write(&from, b"new").unwrap();
    fs::write(&to, b"old").unwrap();
    // share_mode(0): nobody may open, delete or replace the file.
    let lock = fs::OpenOptions::new()
        .read(true)
        .share_mode(0)
        .open(&to)
        .unwrap();
    let releaser = std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(120));
        drop(lock);
    });
    rename_with_retry(&from, &to).unwrap();
    releaser.join().unwrap();
    assert_eq!(fs::read(&to).unwrap(), b"new");
    assert!(!from.exists());
}

// SEC-054 — a file that stays locked fails after the bounded retries.
#[cfg(windows)]
#[test]
fn rename_with_retry_fails_when_the_target_stays_locked() {
    use std::os::windows::fs::OpenOptionsExt;
    let tmp = tempfile::tempdir().unwrap();
    let from = tmp.path().join("descriptor.tmp");
    let to = tmp.path().join("descriptor.json");
    fs::write(&from, b"new").unwrap();
    fs::write(&to, b"old").unwrap();
    let _lock = fs::OpenOptions::new()
        .read(true)
        .share_mode(0)
        .open(&to)
        .unwrap();
    assert!(rename_with_retry(&from, &to).is_err());
    assert!(from.exists(), "the source survives a failed rename");
}
