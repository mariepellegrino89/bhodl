//! PIN recovery and PIN change (docs/use-cases.md SEC-044,
//! docs/pin-recovery.md): the data key wrapped by the PIN (v3
//! descriptor) and by the recovery code (`registry.recovery.json`).

use std::{fs, path::Path, sync::Arc};

use bhodl_wallet_onchain::REGISTRY_KDF_FILE_NAME;

use crate::{
    Network, RegistryHandle, WalletError,
    kdf::{
        KdfFile, RECOVERY_CODE_LEN, RECOVERY_FILE_NAME, format_recovery_code,
        generate_recovery_code, normalize_recovery_code,
    },
    registry_has_recovery_code,
};

const PIN: &str = "482916";
const NEW_PIN: &str = "246810";
/// BIP-173 test vector (P2WPKH, `tb` HRP — valid on signet).
const SIGNET_ADDRESS: &str = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx";

fn dir_str(dir: &Path) -> String {
    dir.to_string_lossy().into_owned()
}

fn open(dir: &Path, pin: &str) -> Result<Arc<RegistryHandle>, WalletError> {
    RegistryHandle::create_or_load(dir_str(dir), pin.to_string())
}

fn recover(dir: &Path, code: &str, new_pin: &str) -> Result<Arc<RegistryHandle>, WalletError> {
    RegistryHandle::unlock_with_recovery_code(dir_str(dir), code.to_string(), new_pin.to_string())
}

/// A registry with tracker data and a watch wallet, plus its recovery
/// code. Returns (code, watch wallet id).
fn seeded(dir: &Path) -> (String, String) {
    let handle = open(dir, PIN).unwrap();
    handle
        .add_liability("Mutuo".into(), 1_000, "mortgage".into(), None)
        .unwrap();
    let watch = handle
        .add_watch_addresses("List".into(), vec![SIGNET_ADDRESS.into()], Network::Signet)
        .unwrap();
    let code = handle.create_recovery_code().unwrap();
    (code, watch.id)
}

fn assert_data_intact(handle: &RegistryHandle, watch_id: &str) {
    assert_eq!(handle.list_liabilities().unwrap()[0].name, "Mutuo");
    // The watch-state file is keyed with the same data key: it still opens.
    let watch = handle.open_watch(watch_id.to_string()).unwrap();
    assert!(watch.is_address_list().unwrap());
}

fn read_kdf(path: &Path) -> KdfFile {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}

// SEC-044 — the whole flow: forgotten PIN + recovery code → new PIN.
#[test]
fn sec_044_recovery_code_resets_the_pin_and_keeps_every_file_readable() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let (code, watch_id) = seeded(dir);
    assert!(registry_has_recovery_code(dir_str(dir)));

    // Typed loosely: lower case, spaces instead of dashes.
    let typed = code.to_lowercase().replace('-', " ");
    let handle = recover(dir, &typed, NEW_PIN).unwrap();
    assert_data_intact(&handle, &watch_id);
    drop(handle);

    // The old PIN no longer opens the store; the new one does.
    assert!(matches!(open(dir, PIN), Err(WalletError::Storage { .. })));
    assert_data_intact(&open(dir, NEW_PIN).unwrap(), &watch_id);
    // The recovery code keeps working after use.
    assert!(recover(dir, &code, "135790").is_ok());
}

#[test]
fn sec_044_a_wrong_code_changes_nothing_and_is_reported_as_rejected() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let (code, _) = seeded(dir);
    let first = if code.starts_with('0') { '1' } else { '0' };
    let wrong = format!("{first}{}", &code[1..]);
    match recover(dir, &wrong, NEW_PIN) {
        Err(WalletError::Storage { detail }) => assert_eq!(detail, "recovery code rejected"),
        other => panic!("expected rejection, got {:?}", other.err()),
    }
    // Descriptor untouched: the current PIN still works.
    assert!(open(dir, PIN).is_ok());
}

#[test]
fn sec_044_malformed_code_weak_pin_and_missing_file_fail_before_any_change() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    open(dir, PIN).unwrap();
    // No recovery code created yet.
    assert!(!registry_has_recovery_code(dir_str(dir)));
    let valid_shape = "0".repeat(RECOVERY_CODE_LEN);
    assert!(matches!(
        recover(dir, &valid_shape, NEW_PIN),
        Err(WalletError::Storage { detail }) if detail.contains("no recovery code")
    ));
    assert!(matches!(
        recover(dir, "too-short", NEW_PIN),
        Err(WalletError::InvalidArgument { .. })
    ));
    assert!(matches!(
        recover(dir, &valid_shape, "12"),
        Err(WalletError::KeyDerivation { .. })
    ));
    assert!(open(dir, PIN).is_ok());
}

#[test]
fn sec_044_regenerating_the_code_revokes_the_previous_one() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let (old_code, _) = seeded(dir);
    let new_code = open(dir, PIN).unwrap().create_recovery_code().unwrap();
    assert_ne!(old_code, new_code);
    assert!(matches!(
        recover(dir, &old_code, NEW_PIN),
        Err(WalletError::Storage { .. })
    ));
    assert!(recover(dir, &new_code, NEW_PIN).is_ok());
}

#[test]
fn sec_044_the_recovery_file_cannot_stand_in_for_the_pin_descriptor() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let (code, _) = seeded(dir);
    // Same shape, different purpose in the AEAD associated data: copying
    // it over the descriptor and "unlocking" with the code must fail.
    fs::copy(
        dir.join(RECOVERY_FILE_NAME),
        dir.join(REGISTRY_KDF_FILE_NAME),
    )
    .unwrap();
    let canonical = normalize_recovery_code(&code).unwrap();
    assert!(matches!(
        open(dir, &canonical),
        Err(WalletError::Storage { .. })
    ));
}

#[test]
fn change_pin_rewraps_only_and_refuses_a_wrong_old_pin() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    let (code, watch_id) = seeded(dir);
    let handle = open(dir, PIN).unwrap();
    let before = fs::read(dir.join("registry.sqlite")).unwrap();

    assert!(
        !handle
            .change_device_pin("000000".into(), NEW_PIN.into())
            .unwrap()
    );
    assert!(matches!(
        handle.change_device_pin(PIN.into(), "1".into()),
        Err(WalletError::KeyDerivation { .. })
    ));
    assert!(
        handle
            .change_device_pin(PIN.into(), NEW_PIN.into())
            .unwrap()
    );
    drop(handle);

    // No database was re-encrypted.
    assert_eq!(before, fs::read(dir.join("registry.sqlite")).unwrap());
    assert!(open(dir, PIN).is_err());
    assert_data_intact(&open(dir, NEW_PIN).unwrap(), &watch_id);
    // The recovery code is independent of the PIN.
    assert!(recover(dir, &code, PIN).is_ok());
}

#[test]
fn every_unlock_leaves_a_v3_descriptor() {
    let tmp = tempfile::tempdir().unwrap();
    let dir = tmp.path();
    open(dir, PIN).unwrap();
    let file = read_kdf(&dir.join(REGISTRY_KDF_FILE_NAME));
    assert_eq!(file.version, KdfFile::WRAPPED_VERSION);
    assert!(file.mac_hex.is_none());
    // The recovery file has the same shape and never holds the code.
    let code = open(dir, PIN).unwrap().create_recovery_code().unwrap();
    let raw = fs::read_to_string(dir.join(RECOVERY_FILE_NAME)).unwrap();
    assert!(!raw.contains(&normalize_recovery_code(&code).unwrap()[..]));
    assert_eq!(
        read_kdf(&dir.join(RECOVERY_FILE_NAME)).version,
        KdfFile::WRAPPED_VERSION
    );
}

#[test]
fn recovery_codes_are_24_crockford_symbols_in_groups_of_four() {
    let a = generate_recovery_code().unwrap();
    let b = generate_recovery_code().unwrap();
    assert_ne!(*a, *b);
    assert_eq!(a.len(), RECOVERY_CODE_LEN);
    assert!(
        a.chars()
            .all(|c| "0123456789ABCDEFGHJKMNPQRSTVWXYZ".contains(c))
    );
    let formatted = format_recovery_code(&a);
    assert_eq!(formatted.len(), 24 + 5);
    assert_eq!(formatted.split('-').count(), 6);
}

#[test]
fn typed_codes_are_normalized_for_look_alikes_and_separators() {
    let canonical = "0123456789ABCDEFGHJKMNPQ";
    assert_eq!(
        &normalize_recovery_code("o123-4567-89ab-cdef-ghjk-mnpq").unwrap()[..],
        canonical
    );
    assert_eq!(
        &normalize_recovery_code(" 0I23 4567 89AB CDEF GHJK MNPQ\n").unwrap()[..],
        "0123456789ABCDEFGHJKMNPQ"
    );
    assert_eq!(
        &normalize_recovery_code("0l23456789abcdefghjkmnpq").unwrap()[..],
        canonical
    );
    // U is not a Crockford symbol; wrong lengths are refused.
    assert!(normalize_recovery_code("U123456789ABCDEFGHJKMNPQ").is_none());
    assert!(normalize_recovery_code("0123456789ABCDEFGHJKMNP").is_none());
    assert!(normalize_recovery_code("0123456789ABCDEFGHJKMNPQR").is_none());
    assert!(normalize_recovery_code("0123456789ABCDEFGHJKMNPé").is_none());
}
