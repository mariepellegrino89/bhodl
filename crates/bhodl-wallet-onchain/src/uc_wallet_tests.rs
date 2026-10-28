//! Use-case tests for the watch-only wallet area (docs/use-cases.md, WAL-*).
//!
//! Child module of `watch` so synthetic chain updates can reach the
//! backends; everything else goes through the public API.

use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use bdk_chain::{BlockId, ConfirmationBlockTime, TxUpdate};
use bdk_wallet::Update;
use bitcoin::hashes::Hash;
use bitcoin::{
    BlockHash, OutPoint, ScriptBuf, Sequence, Transaction, TxIn, TxOut, Txid, Witness, absolute,
    transaction,
};
use tempfile::TempDir;

use super::*;
use crate::registry::{Registry, open_registry_in_memory};
use crate::test_support::{bip86_account, bip86_address, bip86_xpub};

fn key() -> [u8; ENCRYPTION_KEY_LEN] {
    [0x42u8; ENCRYPTION_KEY_LEN]
}

fn with_version(key: &str, version: [u8; 4]) -> String {
    let mut data = bitcoin::base58::decode_check(key).unwrap();
    data[..4].copy_from_slice(&version);
    bitcoin::base58::encode_check(&data)
}

fn spk(addr: &str) -> ScriptBuf {
    addr.parse::<Address<bitcoin::address::NetworkUnchecked>>()
        .unwrap()
        .assume_checked()
        .script_pubkey()
}

fn foreign_input(seed: u8) -> TxIn {
    TxIn {
        previous_output: OutPoint::new(Txid::from_byte_array([seed; 32]), 0),
        script_sig: ScriptBuf::new(),
        sequence: Sequence::MAX,
        witness: Witness::new(),
    }
}

fn tx(inputs: Vec<TxIn>, outputs: &[(ScriptBuf, u64)]) -> Transaction {
    Transaction {
        version: transaction::Version::TWO,
        lock_time: absolute::LockTime::ZERO,
        input: inputs,
        output: outputs
            .iter()
            .map(|(s, v)| TxOut {
                value: bitcoin::Amount::from_sat(*v),
                script_pubkey: s.clone(),
            })
            .collect(),
    }
}

fn signet_xpub_wallet(path: &Path) -> WatchAccount {
    WatchAccount::create_from_xpub(
        BhodlNetwork::Signet,
        path,
        &key(),
        &bip86_xpub(BhodlNetwork::Signet),
        WatchBip::Taproot,
    )
    .unwrap()
}

/// Apply a mempool transaction to a descriptor wallet through the public
/// apply path (so it is persisted like a real sync).
fn hd_mempool(acct: &mut WatchAccount, t: Transaction, seen: u64) -> Txid {
    let txid = t.compute_txid();
    let mut tx_update = TxUpdate::default();
    tx_update.txs = vec![Arc::new(t)];
    tx_update.seen_ats = [(txid, seen)].into();
    acct.apply_esplora_scan(WatchScanUpdate::Full(Update {
        tx_update,
        ..Default::default()
    }))
    .unwrap();
    txid
}

/// Confirm `txs` in a synthetic block at `height` on top of the wallet's tip.
fn hd_confirm(acct: &mut WatchAccount, txs: Vec<Transaction>, height: u32, time: u64) {
    let block = BlockId {
        height,
        hash: BlockHash::from_byte_array([height.to_le_bytes()[0]; 32]),
    };
    let cp = acct.descriptor_wallet().latest_checkpoint().insert(block);
    let mut tx_update = TxUpdate::default();
    tx_update.anchors = txs
        .iter()
        .map(|t| {
            (
                ConfirmationBlockTime {
                    block_id: block,
                    confirmation_time: time,
                },
                t.compute_txid(),
            )
        })
        .collect();
    tx_update.txs = txs.into_iter().map(Arc::new).collect();
    acct.apply_esplora_scan(WatchScanUpdate::Full(Update {
        tx_update,
        chain: Some(cp),
        ..Default::default()
    }))
    .unwrap();
}

fn script_mempool(acct: &mut WatchAccount, t: Transaction) -> Txid {
    let txid = t.compute_txid();
    match &mut acct.backend {
        WatchBackend::Scripts(s) => {
            let _ = s.graph.insert_tx(t);
            let _ = s.graph.insert_seen_at(txid, 1_700_000_000);
        }
        WatchBackend::Descriptor(_) => panic!("address wallet expected"),
    }
    txid
}

// ---------------------------------------------------------------------------
// A. Adding a watch wallet
// ---------------------------------------------------------------------------

#[test]
fn wal_004_every_multisig_slip132_prefix_and_broken_keys_are_rejected() {
    let tpub = bip86_xpub(BhodlNetwork::Signet);
    let xpub = bip86_xpub(BhodlNetwork::Mainnet);
    for (base, version, prefix) in [
        (&xpub, [0x02, 0x95, 0xB4, 0x3F], "Ypub"),
        (&tpub, [0x02, 0x57, 0x54, 0x83], "Vpub"),
        (&tpub, [0x02, 0x42, 0x89, 0xEF], "Upub"),
        (&xpub, [0x02, 0xAA, 0x7E, 0xD3], "Zpub"),
    ] {
        let key = with_version(base, version);
        assert!(key.starts_with(prefix), "{key}");
        assert!(
            matches!(normalize_xpub(&key), Err(OnchainError::Descriptor(_))),
            "{prefix} must be rejected"
        );
    }
    let vpub = with_version(&tpub, [0x04, 0x5F, 0x1C, 0xF6]);
    let mut broken = vpub.clone();
    let last = broken.pop().unwrap();
    broken.push(if last == 'a' { 'b' } else { 'a' });
    assert!(matches!(
        normalize_xpub(&broken),
        Err(OnchainError::Descriptor(m)) if m.contains("invalid extended public key")
    ));
    // Extend the base58 string and re-checksum: same "vpub" prefix, wrong length.
    let raw = bitcoin::base58::decode(&format!("{vpub}zz")).unwrap();
    let long = bitcoin::base58::encode_check(&raw[..raw.len() - 4]);
    assert!(long.starts_with("vpub"), "{long}");
    assert_ne!(bitcoin::base58::decode_check(&long).unwrap().len(), 78);
    assert!(matches!(
        normalize_xpub(&long),
        Err(OnchainError::Descriptor(m)) if m.contains("78 bytes")
    ));
}

#[test]
fn wal_005_tpub_is_rejected_on_mainnet() {
    let dir = TempDir::new().unwrap();
    let res = WatchAccount::create_from_xpub(
        BhodlNetwork::Mainnet,
        &dir.path().join("w.sqlite"),
        &key(),
        &bip86_xpub(BhodlNetwork::Signet),
        WatchBip::Taproot,
    );
    assert!(matches!(res, Err(OnchainError::Descriptor(_))));
    assert!(matches!(
        Registry::build_watch_xpub_record(
            "x",
            &bip86_xpub(BhodlNetwork::Testnet4),
            BhodlNetwork::Mainnet,
            WatchBip::Taproot
        ),
        Err(OnchainError::Descriptor(_))
    ));
}

#[test]
fn wal_007_empty_label_is_rejected_for_every_kind_but_blank_is_not_trimmed() {
    let tpub = bip86_xpub(BhodlNetwork::Signet);
    let (fp, _) = bip86_account(BhodlNetwork::Signet, "");
    let addr = bip86_address(BhodlNetwork::Signet, 0);
    assert!(matches!(
        Registry::build_watch_xpub_record("", &tpub, BhodlNetwork::Signet, WatchBip::Taproot),
        Err(OnchainError::Descriptor(_))
    ));
    assert!(matches!(
        Registry::build_watch_hd_record("", &fp, "m/86'/1'/0'", &tpub, BhodlNetwork::Signet),
        Err(OnchainError::Descriptor(_))
    ));
    assert!(matches!(
        Registry::build_watch_addresses_record("", &[addr], BhodlNetwork::Signet),
        Err(OnchainError::Descriptor(_))
    ));
    // The core does not trim: a blank label passes (the UI trims first).
    let rec =
        Registry::build_watch_xpub_record("   ", &tpub, BhodlNetwork::Signet, WatchBip::Taproot)
            .unwrap();
    assert_eq!(rec.label, "   ");
}

#[test]
fn wal_009_unhardened_purpose_and_master_path_are_rejected() {
    let tpub = bip86_xpub(BhodlNetwork::Signet);
    let (fp, _) = bip86_account(BhodlNetwork::Signet, "");
    assert!(matches!(
        hd_descriptors(&fp, "m/86/1'/0'", &tpub),
        Err(OnchainError::Descriptor(m)) if m.contains("hardened")
    ));
    assert!(matches!(
        hd_descriptors(&fp, "m", &tpub),
        Err(OnchainError::Descriptor(m)) if m.contains("master")
    ));
}

#[test]
fn wal_010_slip132_contradicting_the_hd_purpose_is_rejected() {
    let tpub = bip86_xpub(BhodlNetwork::Signet);
    let (fp, _) = bip86_account(BhodlNetwork::Signet, "");
    let vpub = with_version(&tpub, [0x04, 0x5F, 0x1C, 0xF6]);
    assert!(matches!(
        hd_descriptors(&fp, "m/86'/1'/0'", &vpub),
        Err(OnchainError::Descriptor(m)) if m.contains("implies")
    ));
    // Same key on its own purpose is accepted and normalised to tpub.
    let (ext, _) = hd_descriptors(&fp, "m/84'/1'/0'", &vpub).unwrap();
    assert!(
        ext.starts_with(&format!("wpkh([{fp}/84'/1'/0']{tpub}/0/*")),
        "{ext}"
    );
}

#[test]
fn wal_011_hd_xpub_of_the_wrong_network_is_rejected() {
    let dir = TempDir::new().unwrap();
    let (fp, xpub) = bip86_account(BhodlNetwork::Mainnet, "");
    let res = WatchAccount::create_from_hd(
        BhodlNetwork::Signet,
        &dir.path().join("hd.sqlite"),
        &key(),
        &fp,
        "m/86'/0'/0'",
        &xpub.to_string(),
    );
    assert!(matches!(res, Err(OnchainError::Descriptor(_))));
    assert!(matches!(
        Registry::build_watch_hd_record(
            "x",
            &fp,
            "m/86'/1'/0'",
            &bip86_xpub(BhodlNetwork::Signet),
            BhodlNetwork::Mainnet
        ),
        Err(OnchainError::Descriptor(_))
    ));
}

#[test]
fn wal_014_address_list_appends_idempotently_and_stores_canonical_form() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("list.sqlite");
    let a0 = bip86_address(BhodlNetwork::Signet, 0);
    let a1 = bip86_address(BhodlNetwork::Signet, 1);
    drop(
        WatchAccount::create_from_addresses(
            BhodlNetwork::Signet,
            &path,
            &key(),
            &[a0.to_uppercase()],
        )
        .unwrap(),
    );
    let acct = WatchAccount::create_from_addresses(
        BhodlNetwork::Signet,
        &path,
        &key(),
        &[a0.clone(), a1.clone(), a0.clone()],
    )
    .unwrap();
    let listed: Vec<(u32, String)> = acct
        .list_addresses(false)
        .unwrap()
        .into_iter()
        .map(|a| (a.index, a.address.to_string()))
        .collect();
    assert_eq!(listed, vec![(0, a0.clone()), (1, a1.clone())]);
    assert_eq!(
        read_all_watch_addresses(&acct.db_conn).unwrap(),
        vec![(0, a0.clone()), (1, a1)]
    );
    let rec = Registry::build_watch_addresses_record(
        "x",
        &[a0.to_uppercase(), a0.clone()],
        BhodlNetwork::Signet,
    )
    .unwrap();
    assert_eq!(rec.descriptor_pub, format!("[\"{a0}\"]"));
}

// ---------------------------------------------------------------------------
// C. Registry rows
// ---------------------------------------------------------------------------

fn file_registry(dir: &TempDir) -> (Registry, rusqlite::Connection) {
    let path = dir.path().join("plain-registry.sqlite");
    let reg = open_registry_in_memory(rusqlite::Connection::open(&path).unwrap()).unwrap();
    (reg, rusqlite::Connection::open(&path).unwrap())
}

#[test]
fn wal_027_unknown_kind_or_corrupt_uuid_in_a_row_is_a_storage_error() {
    let dir = TempDir::new().unwrap();
    let (reg, raw) = file_registry(&dir);
    let id = uuid::Uuid::new_v4().to_string();
    raw.execute(
        "INSERT INTO wallets (id, label, kind, descriptor_pub, network, created_at_unix) \
         VALUES (?1, 'x', 'foo', 'd', 'signet', 1)",
        [&id],
    )
    .unwrap();
    assert!(matches!(reg.list_wallets(), Err(OnchainError::Storage(m)) if m.contains("foo")));
    assert!(matches!(
        reg.get_wallet(&uuid::Uuid::parse_str(&id).unwrap()),
        Err(OnchainError::Storage(_))
    ));

    raw.execute(
        "UPDATE wallets SET kind = 'watch_xpub', id = 'not-a-uuid'",
        [],
    )
    .unwrap();
    assert!(matches!(reg.list_wallets(), Err(OnchainError::Storage(m)) if m.contains("UUID")));
}

#[test]
fn wal_028_renaming_an_unknown_wallet_returns_false_and_core_accepts_any_label() {
    let reg = open_registry_in_memory(rusqlite::Connection::open_in_memory().unwrap()).unwrap();
    assert!(!reg.rename_wallet(&uuid::Uuid::new_v4(), "x").unwrap());
    let a = reg
        .add_watch_xpub(
            "A",
            &bip86_xpub(BhodlNetwork::Signet),
            BhodlNetwork::Signet,
            WatchBip::Taproot,
        )
        .unwrap();
    reg.add_watch_addresses(
        "B",
        &[bip86_address(BhodlNetwork::Signet, 9)],
        BhodlNetwork::Signet,
    )
    .unwrap();
    // Empty and duplicate labels are only refused by the UI.
    assert!(reg.rename_wallet(&a.id, "").unwrap());
    assert!(reg.rename_wallet(&a.id, "B").unwrap());
    assert_eq!(reg.get_wallet(&a.id).unwrap().unwrap().label, "B");
}

// ---------------------------------------------------------------------------
// D. Opening
// ---------------------------------------------------------------------------

#[test]
fn wal_039_loading_an_empty_or_missing_db_is_a_storage_error() {
    let dir = TempDir::new().unwrap();
    let missing = dir.path().join("missing.sqlite");
    assert!(matches!(
        WatchAccount::load(BhodlNetwork::Signet, &missing, &key()),
        Err(OnchainError::Storage(m)) if m.contains("no watch wallet")
    ));
    // …without leaving an empty encrypted file behind.
    assert!(!missing.exists());
    // An existing but empty encrypted DB behaves the same.
    drop(open_encrypted_connection(&dir.path().join("empty.sqlite"), &key()).unwrap());
    assert!(matches!(
        WatchAccount::load(
            BhodlNetwork::Signet,
            &dir.path().join("empty.sqlite"),
            &key()
        ),
        Err(OnchainError::Storage(_))
    ));
    // A file that is not a database at all.
    std::fs::write(dir.path().join("junk.sqlite"), b"definitely not sqlite").unwrap();
    assert!(matches!(
        WatchAccount::load(
            BhodlNetwork::Signet,
            &dir.path().join("junk.sqlite"),
            &key()
        ),
        Err(OnchainError::Storage(_))
    ));
}

#[test]
fn wal_041_creating_a_different_xpub_on_an_existing_db_is_refused() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("w.sqlite");
    let first = signet_xpub_wallet(&path);
    let addr0 = first.peek_address(0).unwrap();
    drop(first);
    let other_xpub = bip86_account(BhodlNetwork::Signet, "other passphrase")
        .1
        .to_string();
    let res = WatchAccount::create_from_xpub(
        BhodlNetwork::Signet,
        &path,
        &key(),
        &other_xpub,
        WatchBip::Taproot,
    );
    assert!(matches!(res, Err(OnchainError::Storage(_))));
    // A different script type on the same xpub is a mismatch too.
    assert!(matches!(
        WatchAccount::create_from_xpub(
            BhodlNetwork::Signet,
            &path,
            &key(),
            &bip86_xpub(BhodlNetwork::Signet),
            WatchBip::NativeSegwit,
        ),
        Err(OnchainError::Storage(_))
    ));
    let reloaded = WatchAccount::load(BhodlNetwork::Signet, &path, &key()).unwrap();
    assert_eq!(reloaded.peek_address(0).unwrap(), addr0);
}

// ---------------------------------------------------------------------------
// E. Sync
// ---------------------------------------------------------------------------

#[test]
fn wal_052_scan_update_of_the_wrong_kind_is_refused() {
    let dir = TempDir::new().unwrap();
    let mut hd = signet_xpub_wallet(&dir.path().join("hd.sqlite"));
    assert!(matches!(
        hd.apply_esplora_scan(WatchScanUpdate::Spks(SyncResponse::default())),
        Err(OnchainError::EsploraSync(_))
    ));
    let mut list = WatchAccount::create_from_addresses(
        BhodlNetwork::Signet,
        &dir.path().join("list.sqlite"),
        &key(),
        &[bip86_address(BhodlNetwork::Signet, 0)],
    )
    .unwrap();
    assert!(matches!(
        list.apply_esplora_scan(WatchScanUpdate::Full(Update::default())),
        Err(OnchainError::EsploraSync(_))
    ));
}

#[test]
fn wal_053_clearnet_sync_to_a_closed_port_fails_with_esplora_sync() {
    let dir = TempDir::new().unwrap();
    let mut acct = signet_xpub_wallet(&dir.path().join("w.sqlite"));
    let res = acct.sync_with_esplora("http://127.0.0.1:1", None);
    assert!(matches!(res, Err(OnchainError::EsploraSync(_))), "{res:?}");
    assert_eq!(acct.balance().to_sat(), 0);
}

#[test]
fn wal_053_http_500_is_retried_then_surfaces_as_esplora_sync() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let hits = Arc::new(AtomicUsize::new(0));
    let counter = Arc::clone(&hits);
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut s) = stream else { break };
            counter.fetch_add(1, Ordering::SeqCst);
            let mut buf = [0u8; 4096];
            let _ = s.read(&mut buf);
            let _ = s.write_all(
                b"HTTP/1.1 500 Internal Server Error\r\nContent-Length: 4\r\nConnection: close\r\n\r\nboom",
            );
        }
    });
    let dir = TempDir::new().unwrap();
    let mut acct = WatchAccount::create_from_addresses(
        BhodlNetwork::Signet,
        &dir.path().join("list.sqlite"),
        &key(),
        &[bip86_address(BhodlNetwork::Signet, 0)],
    )
    .unwrap();
    let res = acct.sync_with_esplora(&format!("http://127.0.0.1:{port}"), None);
    assert!(matches!(res, Err(OnchainError::EsploraSync(_))), "{res:?}");
    assert!(
        hits.load(Ordering::SeqCst) >= 2,
        "a 500 must be retried, saw {} request(s)",
        hits.load(Ordering::SeqCst)
    );
}

#[test]
fn wal_054_esplora_url_without_host_is_invalid_before_any_socket_with_tor() {
    let dir = TempDir::new().unwrap();
    let acct = signet_xpub_wallet(&dir.path().join("w.sqlite"));
    let proxy = Some("socks5h://127.0.0.1:9");
    for url in ["unix:/var/run/esplora", "not a url"] {
        let res = acct.start_esplora_scan().fetch(url, proxy);
        assert!(
            matches!(res, Err(OnchainError::InvalidArgument(_))),
            "{url}: {res:?}"
        );
    }
    assert!(matches!(
        acct.start_esplora_scan()
            .fetch("https://mempool.space/api", Some("http://127.0.0.1:9050")),
        Err(OnchainError::InvalidArgument(_))
    ));
}

#[test]
fn wal_057_synced_state_is_visible_after_reopen_without_a_new_scan() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("w.sqlite");
    let addr0 = bip86_address(BhodlNetwork::Signet, 0);
    let funding = tx(vec![foreign_input(1)], &[(spk(&addr0), 50_000)]);
    let txid = funding.compute_txid().to_string();
    {
        let mut acct = signet_xpub_wallet(&path);
        hd_confirm(&mut acct, vec![funding], 120, 1_700_000_100);
        assert_eq!(acct.balance().to_sat(), 50_000);
    }
    let acct = WatchAccount::load(BhodlNetwork::Signet, &path, &key()).unwrap();
    assert_eq!(acct.balance().to_sat(), 50_000);
    assert_eq!(acct.chain_tip_height(), 120);
    let txs = acct.list_transactions();
    assert_eq!(txs.len(), 1);
    assert_eq!(txs[0].txid, txid);
}

// ---------------------------------------------------------------------------
// G / H / I. History, receive, addresses, UTXOs (synthetic chain)
// ---------------------------------------------------------------------------

#[test]
fn wal_069_wal_096_wal_098_confirmed_incoming_and_outgoing_with_change() {
    let dir = TempDir::new().unwrap();
    let mut acct = signet_xpub_wallet(&dir.path().join("w.sqlite"));
    let addr0 = bip86_address(BhodlNetwork::Signet, 0);
    let change0 = acct
        .descriptor_wallet()
        .peek_address(KeychainKind::Internal, 0)
        .address;
    let funding = tx(vec![foreign_input(1)], &[(spk(&addr0), 100_000)]);
    let funding_id = funding.compute_txid();
    let stranger = ScriptBuf::new_op_return([1u8; 4]);
    let spend = tx(
        vec![TxIn {
            previous_output: OutPoint::new(funding_id, 0),
            ..foreign_input(0)
        }],
        &[(stranger, 30_000), (change0.script_pubkey(), 69_000)],
    );
    let spend_id = spend.compute_txid();
    hd_confirm(&mut acct, vec![funding], 100, 1_700_000_000);
    hd_confirm(&mut acct, vec![spend], 101, 1_700_000_600);

    assert_eq!(acct.chain_tip_height(), 101);
    assert_eq!(acct.balance().to_sat(), 69_000);
    let txs = acct.list_transactions();
    let incoming = txs
        .iter()
        .find(|t| t.txid == funding_id.to_string())
        .unwrap();
    assert_eq!(incoming.net_sat, 100_000);
    assert_eq!(incoming.fee_sat, None);
    assert_eq!(incoming.confirmation_height, Some(100));
    assert_eq!(incoming.timestamp, Some(1_700_000_000));
    let outgoing = txs.iter().find(|t| t.txid == spend_id.to_string()).unwrap();
    assert_eq!(outgoing.net_sat, -31_000);
    assert_eq!(outgoing.fee_sat, Some(1_000));
    assert_eq!(outgoing.confirmation_height, Some(101));

    let utxos = acct.list_utxos().unwrap();
    assert_eq!(utxos.len(), 1);
    assert_eq!(utxos[0].outpoint, OutPoint::new(spend_id, 1));
    assert!(utxos[0].is_change);
    assert!(utxos[0].confirmed);
    assert_eq!(utxos[0].address, change0);
}

#[test]
fn wal_081_next_unused_skips_an_address_that_received_funds() {
    let dir = TempDir::new().unwrap();
    let mut acct = signet_xpub_wallet(&dir.path().join("w.sqlite"));
    let first = acct.next_unused_receive_address().unwrap();
    assert_eq!(first.to_string(), bip86_address(BhodlNetwork::Signet, 0));
    hd_mempool(
        &mut acct,
        tx(vec![foreign_input(2)], &[(first.script_pubkey(), 5_000)]),
        1_700_000_000,
    );
    assert_eq!(
        acct.next_unused_receive_address().unwrap().to_string(),
        bip86_address(BhodlNetwork::Signet, 1)
    );
    // Stable until index 1 is used too.
    assert_eq!(
        acct.next_unused_receive_address().unwrap().to_string(),
        bip86_address(BhodlNetwork::Signet, 1)
    );
}

#[test]
fn wal_094_change_addresses_are_listed_once_revealed() {
    let dir = TempDir::new().unwrap();
    let mut acct = signet_xpub_wallet(&dir.path().join("w.sqlite"));
    assert!(acct.list_addresses(true).unwrap().is_empty());
    let change0 = acct
        .descriptor_wallet()
        .peek_address(KeychainKind::Internal, 0)
        .address;
    hd_mempool(
        &mut acct,
        tx(vec![foreign_input(3)], &[(change0.script_pubkey(), 7_000)]),
        1_700_000_000,
    );
    let change = acct.list_addresses(true).unwrap();
    assert_eq!(change.len(), 1);
    assert_eq!(change[0].address, change0);
    assert!(change[0].is_change);
    assert!(change[0].used);
    assert!(acct.list_addresses(false).unwrap().is_empty());
}

#[test]
fn wal_092_receive_label_keeps_existing_text_and_lowest_output_wins() {
    let dir = TempDir::new().unwrap();
    let a0 = bip86_address(BhodlNetwork::Signet, 0);
    let a1 = bip86_address(BhodlNetwork::Signet, 1);
    let mut acct = WatchAccount::create_from_addresses(
        BhodlNetwork::Signet,
        &dir.path().join("list.sqlite"),
        &key(),
        &[a0.clone(), a1.clone()],
    )
    .unwrap();
    acct.set_address_label(&a0, "First", Some("salary".into()))
        .unwrap();
    acct.set_address_label(&a1, "Second", Some("gift".into()))
        .unwrap();
    // Output 0 pays a1, output 1 pays a0: the lowest output index wins.
    let both = script_mempool(
        &mut acct,
        tx(
            vec![foreign_input(4)],
            &[(spk(&a1), 1_000), (spk(&a0), 2_000)],
        ),
    )
    .to_string();
    // A tx the user already labelled keeps its text, gains the category.
    let labelled =
        script_mempool(&mut acct, tx(vec![foreign_input(5)], &[(spk(&a0), 3_000)])).to_string();
    acct.add_label(&Label {
        kind: LabelKind::Tx,
        target: labelled.clone(),
        text: "Mine".into(),
        spendable: None,
        category: None,
    })
    .unwrap();

    let applied = acct.apply_receive_labels().unwrap();
    let on_both = applied.iter().find(|c| c.txid == both).unwrap();
    assert_eq!(on_both.address, a1);
    assert_eq!(on_both.label.as_deref(), Some("Second"));
    let tx_label = acct.get_label(LabelKind::Tx, &both).unwrap().unwrap();
    assert_eq!(
        (tx_label.text.as_str(), tx_label.category.as_deref()),
        ("Second", Some("gift"))
    );

    let on_labelled = applied.iter().find(|c| c.txid == labelled).unwrap();
    assert_eq!(on_labelled.label, None);
    assert_eq!(on_labelled.category.as_deref(), Some("salary"));
    let kept = acct.get_label(LabelKind::Tx, &labelled).unwrap().unwrap();
    assert_eq!(
        (kept.text.as_str(), kept.category.as_deref()),
        ("Mine", Some("salary"))
    );

    // An existing category is never overwritten either.
    let categorised =
        script_mempool(&mut acct, tx(vec![foreign_input(6)], &[(spk(&a0), 4_000)])).to_string();
    acct.add_label(&Label {
        kind: LabelKind::Tx,
        target: categorised.clone(),
        text: String::new(),
        spendable: None,
        category: Some("rent".into()),
    })
    .unwrap();
    let applied = acct.apply_receive_labels().unwrap();
    assert_eq!(applied.len(), 1);
    assert_eq!(applied[0].category, None);
    let row = acct
        .get_label(LabelKind::Tx, &categorised)
        .unwrap()
        .unwrap();
    assert_eq!(
        (row.text.as_str(), row.category.as_deref()),
        ("First", Some("rent"))
    );
}

#[test]
fn wal_092_receive_label_copy_works_on_hd_wallets_and_ignores_outgoing() {
    let dir = TempDir::new().unwrap();
    let mut acct = signet_xpub_wallet(&dir.path().join("w.sqlite"));
    let addr = acct.next_unused_receive_address().unwrap();
    acct.set_address_label(&addr.to_string(), "Invoice 7", Some("income".into()))
        .unwrap();
    let funding = tx(vec![foreign_input(7)], &[(addr.script_pubkey(), 10_000)]);
    let funding_id = hd_mempool(&mut acct, funding, 1_700_000_000);
    // Spending back to the same labelled address nets negative: no copy.
    let spend = tx(
        vec![TxIn {
            previous_output: OutPoint::new(funding_id, 0),
            ..foreign_input(0)
        }],
        &[(addr.script_pubkey(), 9_000)],
    );
    let spend_id = hd_mempool(&mut acct, spend, 1_700_000_100);

    let applied = acct.apply_receive_labels().unwrap();
    assert_eq!(applied.len(), 1);
    assert_eq!(applied[0].txid, funding_id.to_string());
    assert_eq!(applied[0].label.as_deref(), Some("Invoice 7"));
    assert!(
        acct.get_label(LabelKind::Tx, &spend_id.to_string())
            .unwrap()
            .is_none()
    );
    assert!(acct.apply_receive_labels().unwrap().is_empty());
}

// ---------------------------------------------------------------------------
// J / K / L. Labels, contacts, descriptors
// ---------------------------------------------------------------------------

#[test]
fn wal_107_unknown_label_type_in_the_db_is_a_storage_error() {
    let conn = rusqlite::Connection::open_in_memory().unwrap();
    ensure_labels_table(&conn).unwrap();
    conn.execute(
        "INSERT INTO bhodl_labels (label_type, target, text) VALUES ('foo', 't', 'x')",
        [],
    )
    .unwrap();
    assert!(matches!(
        labels::list_labels(&conn),
        Err(OnchainError::Storage(m)) if m.contains("foo")
    ));
    assert!(matches!(
        labels::export_jsonl(&conn),
        Err(OnchainError::Storage(_))
    ));
}

#[test]
fn wal_113_contact_label_is_bounded_and_address_is_canonicalised() {
    let dir = TempDir::new().unwrap();
    let acct = signet_xpub_wallet(&dir.path().join("w.sqlite"));
    let addr = bip86_address(BhodlNetwork::Signet, 4);
    assert!(matches!(
        acct.add_contact(&"x".repeat(257), &addr),
        Err(OnchainError::InvalidArgument(_))
    ));
    // 256 bytes of multi-byte text is the limit, not 256 chars.
    assert!(acct.add_contact(&"é".repeat(129), &addr).is_err());
    acct.add_contact(&"x".repeat(256), &addr.to_uppercase())
        .unwrap();
    let saved = acct.list_contacts().unwrap();
    assert_eq!(saved[0].address, addr);
    assert!(matches!(
        acct.add_contact("garbage", "not-an-address"),
        Err(OnchainError::InvalidAddress(_))
    ));
    // Duplicate detection runs on the canonical form.
    assert!(matches!(
        acct.add_contact("dup", &addr),
        Err(OnchainError::Storage(_))
    ));
}

#[test]
fn wal_120_account_xpub_handles_nested_segwit_origins_and_unknown_shapes() {
    let dir = TempDir::new().unwrap();
    let (fp, _) = bip86_account(BhodlNetwork::Signet, "");
    let tpub = bip86_xpub(BhodlNetwork::Signet);
    let ypath = "m/49'/1'/0'";
    // The BIP-86 tpub has depth 3, so it can sit on a 49' path for shape tests.
    let acct = WatchAccount::create_from_hd(
        BhodlNetwork::Signet,
        &dir.path().join("hd49.sqlite"),
        &key(),
        &fp,
        ypath,
        &tpub,
    )
    .unwrap();
    let backup = acct.export_descriptors().unwrap();
    assert!(
        backup.external.starts_with("sh(wpkh(["),
        "{}",
        backup.external
    );
    assert_eq!(backup.account_xpub().as_deref(), Some(tpub.as_str()));

    for unknown in [
        format!("pkh({tpub}/0/*)"),
        format!("wsh(multi(1,{tpub}/0/*))"),
        String::new(),
    ] {
        let b = DescriptorBackup {
            network: BhodlNetwork::Signet,
            external: unknown.clone(),
            internal: String::new(),
        };
        assert_eq!(b.account_xpub(), None, "{unknown}");
    }

    // A watch DB holding a descriptor BHODL does not produce loads, but
    // yields no account xpub (the FFI turns this into Storage).
    let path = dir.path().join("pkh.sqlite");
    {
        let mut conn = open_encrypted_connection(&path, &key()).unwrap();
        let mut w = Wallet::create(format!("pkh({tpub}/0/*)"), format!("pkh({tpub}/1/*)"))
            .network(bitcoin::Network::Signet)
            .create_wallet(&mut conn)
            .unwrap();
        w.persist(&mut conn).unwrap();
    }
    let odd = WatchAccount::load(BhodlNetwork::Signet, &path, &key()).unwrap();
    assert_eq!(odd.export_descriptors().unwrap().account_xpub(), None);
}

// ---------------------------------------------------------------------------
// GAP-020. Two handles on one watch-state file
// ---------------------------------------------------------------------------

#[test]
fn gap_020_two_handles_on_one_file_neither_blocks_nor_loses_changes() {
    let dir = TempDir::new().unwrap();
    let path = dir.path().join("shared.sqlite");
    drop(signet_xpub_wallet(&path));
    let mut a = WatchAccount::load(BhodlNetwork::Signet, &path, &key()).unwrap();
    let mut b = WatchAccount::load(BhodlNetwork::Signet, &path, &key()).unwrap();

    let addr0 = bip86_address(BhodlNetwork::Signet, 0);
    let funding = tx(vec![foreign_input(8)], &[(spk(&addr0), 12_345)]);
    let txid = funding.compute_txid();
    hd_confirm(&mut a, vec![funding], 50, 1_700_000_000);

    // B keeps writing (reveal + labels + contacts) while A has persisted.
    assert_eq!(
        b.reveal_next_address().unwrap().to_string(),
        bip86_address(BhodlNetwork::Signet, 0)
    );
    b.add_contact("Alice", &bip86_address(BhodlNetwork::Signet, 5))
        .unwrap();
    // B does not see A's sync until it is reopened.
    assert_eq!(b.balance().to_sat(), 0);
    assert_eq!(a.balance().to_sat(), 12_345);
    a.set_address_label(&addr0, "from A", None).unwrap();
    drop(a);
    drop(b);

    let c = WatchAccount::load(BhodlNetwork::Signet, &path, &key()).unwrap();
    assert_eq!(c.balance().to_sat(), 12_345);
    assert_eq!(c.list_transactions()[0].txid, txid.to_string());
    assert_eq!(c.chain_tip_height(), 50);
    assert_eq!(c.list_contacts().unwrap().len(), 1);
    let receive = c.list_addresses(false).unwrap();
    assert_eq!(receive[0].label.as_deref(), Some("from A"));
}
