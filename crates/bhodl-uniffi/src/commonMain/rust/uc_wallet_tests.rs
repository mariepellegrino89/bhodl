//! Use-case tests for the watch-wallet FFI surface (docs/use-cases.md, WAL-*).

use std::io::Read;
use std::net::TcpListener;
use std::str::FromStr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, mpsc};
use std::time::{Duration, Instant};

use bitcoin::bip32::{ChildNumber, DerivationPath, Xpriv, Xpub};

use super::*;

const MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
     abandon abandon abandon abandon abandon about";

fn registry(dir: &Path) -> Arc<RegistryHandle> {
    RegistryHandle::create_or_load(dir.to_string_lossy().into_owned(), "123456".into()).unwrap()
}

fn account(mainnet: bool) -> Xpub {
    let secp = bitcoin::key::Secp256k1::new();
    let seed = bip39::Mnemonic::parse(MNEMONIC).unwrap().to_seed("");
    let net = if mainnet {
        bitcoin::Network::Bitcoin
    } else {
        bitcoin::Network::Signet
    };
    let master = Xpriv::new_master(net, &seed).unwrap();
    let path = DerivationPath::from_str(if mainnet {
        "m/86'/0'/0'"
    } else {
        "m/86'/1'/0'"
    })
    .unwrap();
    Xpub::from_priv(&secp, &master.derive_priv(&secp, &path).unwrap())
}

fn p2tr_address(mainnet: bool, index: u32, net: bitcoin::Network) -> String {
    let secp = bitcoin::key::Secp256k1::new();
    let child = account(mainnet)
        .derive_pub(
            &secp,
            &[
                ChildNumber::from_normal_idx(0).unwrap(),
                ChildNumber::from_normal_idx(index).unwrap(),
            ],
        )
        .unwrap();
    bitcoin::Address::p2tr(&secp, child.to_x_only_pub(), None, net).to_string()
}

fn signet_watch(handle: &RegistryHandle) -> WalletRecordFfi {
    handle
        .add_watch_xpub(
            "Watch".into(),
            account(false).to_string(),
            Network::Signet,
            WatchBipFfi::Taproot,
        )
        .unwrap()
}

fn watch_state_entries(dir: &Path) -> usize {
    fs::read_dir(dir.join("watch-state"))
        .map(|it| it.count())
        .unwrap_or(0)
}

fn poison<T: Send>(m: &Mutex<T>) {
    std::thread::scope(|s| {
        let _ = s
            .spawn(|| {
                let _g = m.lock().unwrap();
                panic!("poison the mutex on purpose");
            })
            .join();
    });
    assert!(m.is_poisoned());
}

#[test]
fn wal_015_add_watch_xpub_end_to_end_on_testnet4_mainnet_and_regtest() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    let cases = [
        (
            Network::Testnet4,
            false,
            "testnet4",
            p2tr_address(false, 0, bitcoin::Network::Testnet4),
        ),
        (
            Network::Mainnet,
            true,
            "mainnet",
            "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr".to_string(),
        ),
        (
            Network::Regtest,
            false,
            "regtest",
            p2tr_address(false, 0, bitcoin::Network::Regtest),
        ),
    ];
    for (network, mainnet, stored, first_address) in cases {
        let record = handle
            .add_watch_xpub(
                format!("W {stored}"),
                account(mainnet).to_string(),
                network,
                WatchBipFfi::Taproot,
            )
            .unwrap();
        let row = handle.get_wallet(record.id.clone()).unwrap().unwrap();
        assert_eq!(row.network, stored);
        let watch = handle.open_watch(record.id).unwrap();
        assert_eq!(watch.peek_address_at(0).unwrap(), first_address);
        assert_eq!(watch.balance_sat().unwrap(), 0);
    }
    assert_eq!(handle.list_wallets().unwrap().len(), 3);
}

#[test]
fn wal_016_registry_insert_failure_after_the_file_exists_removes_the_file() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    poison(&handle.inner);
    let res = handle.add_watch_xpub(
        "Orphan".into(),
        account(false).to_string(),
        Network::Signet,
        WatchBipFfi::Taproot,
    );
    assert!(matches!(res, Err(WalletError::Storage { .. })));
    assert!(tmp.path().join("watch-state").is_dir());
    assert_eq!(watch_state_entries(tmp.path()), 0);
}

#[test]
fn wal_026_wal_038_non_uuid_ids_are_storage_errors() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    for bad in ["", "not-a-uuid", "1234"] {
        assert!(matches!(
            handle.get_wallet(bad.into()),
            Err(WalletError::Storage { detail }) if detail.contains("invalid wallet id")
        ));
        assert!(matches!(
            handle.open_watch(bad.into()),
            Err(WalletError::Storage { detail }) if detail.contains("invalid wallet id")
        ));
        assert!(matches!(
            handle.rename_wallet(bad.into(), "x".into()),
            Err(WalletError::Storage { .. })
        ));
        assert!(matches!(
            handle.remove_wallet(bad.into()),
            Err(WalletError::Storage { .. })
        ));
    }
    assert!(
        handle
            .get_wallet(Uuid::new_v4().to_string())
            .unwrap()
            .is_none()
    );
}

#[test]
fn wal_039_registry_row_with_a_deleted_state_file_fails_to_open() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    let record = signet_watch(&handle);
    let path = tmp
        .path()
        .join("watch-state")
        .join(format!("{}.sqlite", record.id));
    fs::remove_file(&path).unwrap();
    assert!(matches!(
        handle.open_watch(record.id.clone()),
        Err(WalletError::Storage { .. })
    ));
    // A corrupted file is refused the same way.
    fs::write(&path, b"garbage").unwrap();
    assert!(matches!(
        handle.open_watch(record.id),
        Err(WalletError::Storage { .. })
    ));
}

#[test]
fn wal_051_network_phase_of_a_sync_does_not_hold_the_wallet_mutex() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let port = listener.local_addr().unwrap().port();
    let stop = Arc::new(AtomicBool::new(false));
    let (connected_tx, connected_rx) = mpsc::channel::<()>();
    let server_stop = Arc::clone(&stop);
    let server = std::thread::spawn(move || {
        let mut held = Vec::new();
        while !server_stop.load(Ordering::SeqCst) {
            match listener.accept() {
                Ok((mut s, _)) => {
                    let mut buf = [0u8; 1024];
                    let _ = s.set_nonblocking(false);
                    let _ = s.set_read_timeout(Some(Duration::from_millis(200)));
                    let _ = s.read(&mut buf);
                    held.push(s);
                    let _ = connected_tx.send(());
                }
                Err(_) => std::thread::sleep(Duration::from_millis(10)),
            }
        }
        drop(held);
    });

    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    let record = signet_watch(&handle);
    let watch = handle.open_watch(record.id).unwrap();
    let syncing = Arc::clone(&watch);
    let sync = std::thread::spawn(move || {
        syncing.sync_with_esplora(format!("http://127.0.0.1:{port}"), None)
    });

    connected_rx
        .recv_timeout(Duration::from_secs(10))
        .expect("the sync reached the stalled server");
    let started = Instant::now();
    assert_eq!(watch.balance_sat().unwrap(), 0);
    assert!(watch.list_transactions().unwrap().is_empty());
    assert!(
        started.elapsed() < Duration::from_secs(2),
        "reads blocked for {:?} during the sync",
        started.elapsed()
    );
    assert!(
        !sync.is_finished(),
        "the sync is still stuck on the network"
    );

    stop.store(true, Ordering::SeqCst);
    server.join().unwrap();
    let res = sync.join().unwrap();
    assert!(
        matches!(res, Err(WalletError::EsploraSync { .. })),
        "{res:?}"
    );
}

#[test]
fn wal_100_clearing_a_tx_category_keeps_the_label_text() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    let watch = handle.open_watch(signet_watch(&handle).id).unwrap();
    let txid = "ab".repeat(32);
    watch.set_tx_label(txid.clone(), "Rent".into()).unwrap();
    watch
        .set_tx_category(txid.clone(), Some("housing".into()))
        .unwrap();
    watch.set_tx_category(txid.clone(), None).unwrap();
    assert_eq!(
        watch.get_tx_label(txid.clone()).unwrap().as_deref(),
        Some("Rent")
    );
    assert_eq!(watch.get_tx_category(txid.clone()).unwrap(), None);
    // Category on a tx without a label creates a row with empty text.
    let other = "cd".repeat(32);
    watch
        .set_tx_category(other.clone(), Some("food".into()))
        .unwrap();
    assert_eq!(
        watch.get_tx_label(other.clone()).unwrap().as_deref(),
        Some("")
    );
    assert_eq!(
        watch.get_tx_category(other).unwrap().as_deref(),
        Some("food")
    );
}

#[test]
fn wal_108_wal_119_labels_and_contacts_survive_closing_and_reopening_the_wallet() {
    let tmp = tempfile::tempdir().unwrap();
    let txid = "ef".repeat(32);
    let contact = p2tr_address(false, 3, bitcoin::Network::Signet);
    let id = {
        let handle = registry(tmp.path());
        let id = signet_watch(&handle).id;
        let watch = handle.open_watch(id.clone()).unwrap();
        watch.set_tx_label(txid.clone(), "Coffee".into()).unwrap();
        watch
            .set_tx_category(txid.clone(), Some("food".into()))
            .unwrap();
        watch.add_contact("Bob".into(), contact.clone()).unwrap();
        id
    };
    // Lock + unlock: a brand-new registry handle and watch handle.
    let handle = registry(tmp.path());
    let watch = handle.open_watch(id).unwrap();
    assert_eq!(
        watch.get_tx_label(txid.clone()).unwrap().as_deref(),
        Some("Coffee")
    );
    assert_eq!(
        watch.get_tx_category(txid).unwrap().as_deref(),
        Some("food")
    );
    let contacts = watch.list_contacts().unwrap();
    assert_eq!(contacts.len(), 1);
    assert_eq!(contacts[0].label, "Bob");
    assert_eq!(contacts[0].address, contact);
}

#[test]
fn wal_128_every_onchain_error_maps_to_its_typed_ffi_variant() {
    let detail = |e: &WalletError| e.to_string();
    type Matches = fn(&WalletError) -> bool;
    let cases: Vec<(OnchainError, Matches)> = vec![
        (
            OnchainError::Descriptor("d".into()),
            |e| matches!(e, WalletError::WalletCreate { detail } if detail == "d"),
        ),
        (
            OnchainError::WalletCreate("w".into()),
            |e| matches!(e, WalletError::WalletCreate { detail } if detail == "w"),
        ),
        (
            OnchainError::EsploraSync("s".into()),
            |e| matches!(e, WalletError::EsploraSync { detail } if detail == "s"),
        ),
        (
            OnchainError::Storage("st".into()),
            |e| matches!(e, WalletError::Storage { detail } if detail == "st"),
        ),
        (
            OnchainError::InvalidAddress("a".into()),
            |e| matches!(e, WalletError::InvalidAddress { detail } if detail == "a"),
        ),
        (
            OnchainError::DerivationPath("p".into()),
            |e| matches!(e, WalletError::DerivationPath { detail } if detail == "p"),
        ),
        (
            OnchainError::Rng("r".into()),
            |e| matches!(e, WalletError::KeyDerivation { detail } if detail == "r"),
        ),
        (
            OnchainError::InvalidArgument("i".into()),
            |e| matches!(e, WalletError::InvalidArgument { detail } if detail == "i"),
        ),
        (
            OnchainError::Bip32(bitcoin::bip32::Error::CannotDeriveFromHardenedKey),
            |e| matches!(e, WalletError::Bip32 { .. }),
        ),
    ];
    for (onchain, check) in cases {
        let label = onchain.to_string();
        let mapped = WalletError::from(onchain);
        assert!(check(&mapped), "{label} -> {}", detail(&mapped));
    }
}

#[test]
fn wal_128_core_errors_reach_kotlin_typed_through_real_calls() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    assert!(matches!(
        handle.add_watch_addresses("x".into(), vec!["nope".into()], Network::Signet),
        Err(WalletError::InvalidAddress { .. })
    ));
    let list = handle
        .add_watch_addresses(
            "list".into(),
            vec![p2tr_address(false, 0, bitcoin::Network::Signet)],
            Network::Signet,
        )
        .unwrap();
    let watch = handle.open_watch(list.id).unwrap();
    // An address list has no derivation: a caller mistake, not storage.
    assert!(matches!(
        watch.next_receive_address(),
        Err(WalletError::InvalidArgument { .. })
    ));
    assert!(matches!(
        watch.sync_with_esplora("http://127.0.0.1:1".into(), None),
        Err(WalletError::EsploraSync { .. })
    ));
}

#[test]
fn wal_129_poisoned_watch_mutex_returns_storage_instead_of_panicking() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    let watch = handle.open_watch(signet_watch(&handle).id).unwrap();
    poison(&watch.inner);
    let poisoned = |r: Result<(), WalletError>| {
        assert!(
            matches!(&r, Err(WalletError::Storage { detail }) if detail.contains("poisoned")),
            "{r:?}"
        );
    };
    poisoned(watch.balance_sat().map(drop));
    poisoned(watch.list_transactions().map(drop));
    poisoned(watch.next_unused_receive_address().map(drop));
    poisoned(watch.list_addresses(false).map(drop));
    poisoned(watch.set_tx_label("aa".into(), "x".into()));
    poisoned(watch.list_contacts().map(drop));
    poisoned(watch.export_labels_jsonl().map(drop));
    poisoned(watch.sync_with_esplora("http://127.0.0.1:1".into(), None));
}

#[test]
fn gap_031_generate_wallet_id_returns_distinct_hyphenated_uuid_v4() {
    let a = generate_wallet_id();
    let b = generate_wallet_id();
    assert_ne!(a, b);
    for id in [a, b] {
        assert_eq!(id.len(), 36);
        let parsed = Uuid::parse_str(&id).unwrap();
        assert_eq!(parsed.get_version_num(), 4);
        assert_eq!(parsed.hyphenated().to_string(), id);
    }
}

#[test]
fn gap_033_aggregate_struct_matches_the_sat_wrapper_and_lists_stale_ids() {
    let tmp = tempfile::tempdir().unwrap();
    let handle = registry(tmp.path());
    let ok = signet_watch(&handle);
    let broken = handle
        .add_watch_addresses(
            "broken".into(),
            vec![p2tr_address(false, 1, bitcoin::Network::Signet)],
            Network::Signet,
        )
        .unwrap();
    fs::write(
        tmp.path()
            .join("watch-state")
            .join(format!("{}.sqlite", broken.id)),
        b"garbage",
    )
    .unwrap();
    let agg = handle.aggregate_watch_balance(Network::Signet).unwrap();
    assert_eq!(agg.total_sat, 0);
    assert_eq!(agg.stale_wallet_ids, vec![broken.id]);
    assert_eq!(
        handle.aggregate_watch_balance_sat(Network::Signet).unwrap(),
        agg.total_sat
    );
    assert!(
        handle
            .aggregate_watch_balance(Network::Mainnet)
            .unwrap()
            .stale_wallet_ids
            .is_empty()
    );
    let _ = ok;
}
