//! Regtest integration tests.
//!
//! These tests bring up a real `bitcoind 25.0` + `electrs` esplora pair via
//! `bdk_testenv`. Its `download` feature is disabled (it unpacked the
//! daemons with the vulnerable `zip` 0.6 crate), so the binaries come from
//! the `BITCOIND_EXE` / `ELECTRS_EXE` env vars. `scripts/regtest-binaries.sh`
//! downloads the same pinned artifacts, verifies their sha256 and prints
//! the exports:
//!
//! ```text
//! eval "$(scripts/regtest-binaries.sh)"
//! cargo test -p bhodl-wallet-onchain --test regtest -- --ignored --test-threads=1
//! ```
//!
//! `ELECTRS_EXE` must be an esplora build of electrs (the HTTP API is
//! used); distro `electrs` packages are the Electrum-only flavour.
//!
//! The tests run only on Unix hosts (electrsd signals electrs with SIGUSR1
//! and electrs publishes no Windows binary). On Windows, run them in a
//! Linux container (Docker Desktop):
//!
//! ```text
//! docker run --rm -v <repo>:/work -v bhodl-regtest-target:/target
//!   -e CARGO_TARGET_DIR=/target -e REGTEST_BIN_DIR=/target/regtest-bin
//!   -w /work rust:1.88-bookworm bash -c
//!   'apt-get update && apt-get install -y unzip && eval "$(scripts/regtest-binaries.sh)"
//!    && cargo test -p bhodl-wallet-onchain --test regtest -- --ignored --test-threads=1'
//! ```
//!
//! All tests in this file are marked `#[ignore]` so the standard
//! `cargo test --workspace` run stays fast, offline and deterministic in
//! CI. Without the two env vars `TestEnv::new()` fails with "you need to
//! provide an env var `BITCOIND_EXE`".

#![cfg(unix)]

use std::{
    str::FromStr,
    thread,
    time::{Duration, Instant},
};

use bdk_testenv::{TestEnv, bitcoincore_rpc::RpcApi as _};
use bhodl_core::{Amount, Network as BhodlNetwork};
use bhodl_wallet_onchain::{ENCRYPTION_KEY_LEN, LabelKind, WatchAccount, WatchBip};
use bitcoin::{
    Address,
    bip32::{DerivationPath, Xpriv, Xpub},
    key::Secp256k1,
};

/// Canonical BIP86 test mnemonic. Public, well-known, never used for funds.
const BIP86_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon                               abandon abandon abandon abandon abandon about";

/// Regtest account xpub (`m/86'/1'/0'`) of [`BIP86_MNEMONIC`]. BHODL only
/// ever sees this public key; the test derives it here.
fn regtest_xpub() -> String {
    let secp = Secp256k1::new();
    let seed = bip39::Mnemonic::parse(BIP86_MNEMONIC)
        .expect("valid mnemonic")
        .to_seed("");
    let master = Xpriv::new_master(bitcoin::Network::Regtest, &seed).expect("master key");
    let account = master
        .derive_priv(
            &secp,
            &DerivationPath::from_str("m/86'/1'/0'").expect("path"),
        )
        .expect("derive account");
    Xpub::from_priv(&secp, &account).to_string()
}

/// Watch-only wallet over [`regtest_xpub`] in a temp dir.
fn regtest_watch() -> (tempfile::TempDir, WatchAccount) {
    let dir = tempfile::TempDir::new().expect("temp dir");
    let account = WatchAccount::create_from_xpub(
        BhodlNetwork::Regtest,
        &dir.path().join("watch.sqlite"),
        &[0x42; ENCRYPTION_KEY_LEN],
        &regtest_xpub(),
        WatchBip::Taproot,
    )
    .expect("create watch wallet");
    (dir, account)
}

/// Send `sat` from the `bdk_testenv` faucet to `address`.
fn fund(env: &TestEnv, address: &Address, sat: u64) {
    let checked = Address::from_str(&address.to_string())
        .expect("parse address")
        .assume_checked();
    env.send(&checked, bitcoin::Amount::from_sat(sat))
        .expect("send to address");
}

/// HTTP base URL of the local esplora (electrs) for `env`.
///
/// `electrsd` reports a bare `host:port` (bound to `0.0.0.0`), which the
/// blocking Esplora client rejects ("redirected to an absolute url with
/// an invalid protocol") and which is not connectable on Windows. Build
/// an explicit loopback `http://` URL from the port instead.
fn esplora_base_url(env: &TestEnv) -> String {
    let raw = env.electrsd.esplora_url.as_deref().expect(
        "TestEnv was started without an esplora endpoint — ELECTRS_EXE must be an esplora build",
    );
    let port = raw
        .rsplit(':')
        .next()
        .expect("esplora url has a port")
        .trim_end_matches('/');
    format!("http://127.0.0.1:{port}")
}

/// Block until the local esplora reports the same tip height as bitcoind.
///
/// electrs only re-indexes when poked (`electrsd.trigger()`, a SIGUSR1),
/// so a fixed sleep after mining is not enough: the wallet then syncs
/// against a stale index and sees a zero balance.
fn wait_for_esplora(env: &TestEnv) {
    let url = esplora_base_url(env);
    let want = env.rpc_client().get_block_count().expect("bitcoind height");
    let deadline = Instant::now() + Duration::from_secs(60);
    while Instant::now() < deadline {
        env.electrsd.trigger().expect("trigger electrs re-index");
        let got = ureq::get(&format!("{url}/blocks/tip/height"))
            .call()
            .ok()
            .and_then(|r| r.into_string().ok())
            .and_then(|b| b.trim().parse::<u64>().ok());
        if got == Some(want) {
            return;
        }
        thread::sleep(Duration::from_millis(200));
    }
    panic!("esplora did not reach bitcoind tip {want} within 60 s");
}

/// End-to-end smoke: fund the watch wallet's first receive address via
/// bitcoind, confirm, sync via Esplora, assert balance / history / UTXOs.
#[test]
#[ignore = "regtest harness — run with `cargo test -- --ignored`"]
fn regtest_watch_wallet_sees_incoming_funds() {
    let env = TestEnv::new().expect("start regtest stack");
    let (_dir, mut account) = regtest_watch();
    let address = account
        .next_unused_receive_address()
        .expect("derive receive address");

    env.mine_blocks(101, None).expect("mine maturity blocks");
    let amount_sat: u64 = 50_000;
    fund(&env, &address, amount_sat);
    env.mine_blocks(1, None).expect("mine confirmation block");
    wait_for_esplora(&env);

    account
        .sync_with_esplora(&esplora_base_url(&env), None)
        .expect("sync against the local esplora");

    assert_eq!(account.balance(), Amount::from_sat(amount_sat));
    let txs = account.list_transactions();
    assert_eq!(txs.len(), 1);
    assert_eq!(txs[0].net_sat, 50_000);
    assert!(txs[0].confirmation_height.is_some());
    let utxos = account.list_utxos().expect("list utxos");
    assert_eq!(utxos.len(), 1);
    assert!(utxos[0].confirmed);
    assert_eq!(utxos[0].address, address);
    // The address is now used: the next unused one is index 1.
    assert_ne!(
        account.next_unused_receive_address().expect("next address"),
        address
    );
}

/// Label-first receive: a label set on the address before funds arrive is
/// copied onto the paying transaction after sync.
#[test]
#[ignore = "regtest harness — run with `cargo test -- --ignored`"]
fn regtest_receive_label_lands_on_the_incoming_transaction() {
    let env = TestEnv::new().expect("start regtest stack");
    let (_dir, mut account) = regtest_watch();
    let address = account
        .next_unused_receive_address()
        .expect("derive receive address");
    account
        .set_address_label(&address.to_string(), "Invoice 7", Some("income".into()))
        .expect("label address");

    env.mine_blocks(101, None).expect("mine maturity blocks");
    fund(&env, &address, 25_000);
    env.mine_blocks(1, None).expect("mine confirmation block");
    wait_for_esplora(&env);
    account
        .sync_with_esplora(&esplora_base_url(&env), None)
        .expect("sync against the local esplora");

    let applied = account.apply_receive_labels().expect("apply labels");
    assert_eq!(applied.len(), 1);
    let txid = &account.list_transactions()[0].txid;
    assert_eq!(&applied[0].txid, txid);
    let label = account
        .get_label(LabelKind::Tx, txid)
        .expect("read label")
        .expect("label copied");
    assert_eq!(label.text, "Invoice 7");
    assert_eq!(label.category.as_deref(), Some("income"));
}
