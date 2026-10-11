//! Regtest integration tests.
//!
//! These tests bring up a real `bitcoind 25.0` + `electrs` esplora pair via
//! `bdk_testenv` (binaries are downloaded on first build and cached). They
//! run only on Unix hosts because the `electrsd` build script depends on
//! Unix-only filesystem APIs (and electrs publishes no Windows binary).
//! On Windows, run them in a Linux container (Docker Desktop):
//!
//! ```text
//! docker run --rm -v <repo>:/work -v bhodl-regtest-target:/target
//!   -e CARGO_TARGET_DIR=/target -w /work rust:1.88-bookworm
//!   cargo test -p bhodl-wallet-onchain --test regtest -- --ignored --test-threads=1
//! ```
//!
//! All tests in this file are marked `#[ignore]` so the standard
//! `cargo test --workspace` run stays fast and deterministic in CI. To run
//! them locally:
//!
//! ```text
//! cargo test -p bhodl-wallet-onchain --test regtest -- --ignored
//! ```
//!
//! On the first run the `bdk_testenv` cache populates `target/`, taking a
//! couple of minutes; subsequent runs reuse the cached binaries.

#![cfg(unix)]

use std::{
    str::FromStr,
    thread,
    time::{Duration, Instant},
};

use bdk_testenv::{TestEnv, bitcoincore_rpc::RpcApi as _};
use bhodl_core::{Amount, Network as BhodlNetwork};
use bhodl_wallet_onchain::OnchainAccount;
use bitcoin::Address;

/// Canonical BIP86 test mnemonic. Public, well-known, never used for funds.
const BIP86_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
                              abandon abandon abandon abandon abandon about";

/// Second well-known test mnemonic (BIP39 §test vectors). Used as a
/// distinct payee in the round-trip test.
const BOB_MNEMONIC: &str = "legal winner thank year wave sausage worth useful legal \
                            winner thank yellow";

/// HTTP base URL of the local esplora (electrs) for `env`.
///
/// `electrsd` reports a bare `host:port` (bound to `0.0.0.0`), which the
/// blocking Esplora client rejects ("redirected to an absolute url with
/// an invalid protocol") and which is not connectable on Windows. Build
/// an explicit loopback `http://` URL from the port instead.
fn esplora_base_url(env: &TestEnv) -> String {
    let raw = env.electrsd.esplora_url.as_deref().expect(
        "TestEnv was started without an esplora endpoint — feature `download` should enable it",
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

/// End-to-end smoke: send `50_000` sat to our regtest taproot address via
/// bitcoind, confirm, sync via Esplora, assert the wallet sees the balance.
#[test]
#[ignore = "regtest harness — run with `cargo test -- --ignored`"]
fn regtest_send_increments_wallet_balance() {
    // 1. Start the regtest stack (bitcoind + electrs esplora).
    let env = TestEnv::new().expect("start regtest stack");

    // 2. Build our wallet on Regtest.
    let mut account = OnchainAccount::from_mnemonic(BhodlNetwork::Regtest, BIP86_MNEMONIC, "")
        .expect("build account from BIP86 mnemonic");

    // 3. Compute our first receive address.
    let our_address = account
        .peek_taproot_address(0)
        .expect("derive index-0 taproot address");

    // 4. Mine the maturity period (101 blocks) so the test wallet has spendable coins.
    env.mine_blocks(101, None).expect("mine maturity blocks");

    // 5. Send 50_000 sat from the bdk_testenv internal faucet to our address.
    //    `Address::send_to` returns an `Address<NetworkUnchecked>`-friendly
    //    representation; we go through the canonical string round-trip so
    //    the test does not depend on rust-bitcoin private API quirks.
    let our_address_unchecked = Address::from_str(&our_address.to_string())
        .expect("parse our address back from string")
        .assume_checked();
    let amount_sat: u64 = 50_000;
    env.send(
        &our_address_unchecked,
        bitcoin::Amount::from_sat(amount_sat),
    )
    .expect("send to our address");

    // 6. Confirm the send.
    env.mine_blocks(1, None).expect("mine confirmation block");

    // 7. Give electrs a moment to index the new block.
    wait_for_esplora(&env);

    // 8. Sync via the production code path (Esplora HTTP).
    let esplora_url = esplora_base_url(&env);
    account
        .sync_with_esplora(&esplora_url, None)
        .expect("sync against the local esplora");

    // 9. Wallet must see the balance.
    assert_eq!(
        account.balance(),
        Amount::from_sat(amount_sat),
        "wallet balance must equal the amount we sent ourselves on regtest"
    );
}

/// End-to-end outgoing flow: fund wallet A, then build + sign + broadcast a
/// PSBT that pays wallet B. Verify wallet B receives the amount after sync.
#[test]
#[ignore = "regtest harness — run with `cargo test -- --ignored`"]
fn regtest_psbt_round_trip_pays_recipient() {
    let env = TestEnv::new().expect("start regtest stack");
    env.mine_blocks(101, None).expect("mine maturity blocks");

    // --- Wallet A: payer.
    let mut alice = OnchainAccount::from_mnemonic(BhodlNetwork::Regtest, BIP86_MNEMONIC, "")
        .expect("build alice wallet");
    let alice_addr = alice.peek_taproot_address(0).unwrap();
    let alice_addr_unchecked = Address::from_str(&alice_addr.to_string())
        .unwrap()
        .assume_checked();

    // Fund alice with 100_000 sat.
    let funding_sat: u64 = 100_000;
    env.send(
        &alice_addr_unchecked,
        bitcoin::Amount::from_sat(funding_sat),
    )
    .expect("fund alice");
    env.mine_blocks(1, None).expect("confirm funding");
    wait_for_esplora(&env);

    let esplora_url = esplora_base_url(&env);
    alice
        .sync_with_esplora(&esplora_url, None)
        .expect("sync alice");
    assert_eq!(alice.balance(), Amount::from_sat(funding_sat));

    // --- Wallet B: payee. Different mnemonic, same regtest network.
    let mut bob = OnchainAccount::from_mnemonic(BhodlNetwork::Regtest, BOB_MNEMONIC, "")
        .expect("build bob wallet");
    let bob_addr = bob.peek_taproot_address(0).unwrap().to_string();

    // --- Alice builds + signs + broadcasts a 30_000 sat payment to bob.
    let pay_sat: u64 = 30_000;
    let unsigned = alice
        .build_send_tx(&bob_addr, Amount::from_sat(pay_sat), 2, false)
        .expect("alice builds psbt");
    let signed = alice.sign_psbt(&unsigned).expect("alice signs psbt");
    let txid = alice
        .broadcast_tx(&esplora_url, &signed, None)
        .expect("alice broadcasts");
    eprintln!("broadcast txid: {txid}");

    // --- Confirm and let bob sync.
    env.mine_blocks(1, None).expect("confirm payment");
    wait_for_esplora(&env);
    bob.sync_with_esplora(&esplora_url, None).expect("sync bob");

    assert_eq!(
        bob.balance(),
        Amount::from_sat(pay_sat),
        "bob must observe the 30_000 sat payment alice sent"
    );
}

/// Coin-control flow: fund alice on two distinct outputs (different
/// addresses), enumerate UTXOs, build a send that consumes exactly one of
/// them, broadcast, and verify only the selected outpoint disappears from
/// the spendable set.
#[test]
#[ignore = "regtest harness — run with `cargo test -- --ignored`"]
fn regtest_coin_control_spends_only_selected_utxo() {
    let env = TestEnv::new().expect("start regtest stack");
    env.mine_blocks(101, None).expect("mine maturity blocks");

    let mut alice = OnchainAccount::from_mnemonic(BhodlNetwork::Regtest, BIP86_MNEMONIC, "")
        .expect("build alice wallet");

    // Fund alice on TWO distinct receive addresses (index 0 and index 1).
    let addr_0 = alice.peek_taproot_address(0).unwrap();
    let addr_1 = alice.peek_taproot_address(1).unwrap();
    let addr_0_unchecked = Address::from_str(&addr_0.to_string())
        .unwrap()
        .assume_checked();
    let addr_1_unchecked = Address::from_str(&addr_1.to_string())
        .unwrap()
        .assume_checked();

    env.send(&addr_0_unchecked, bitcoin::Amount::from_sat(40_000))
        .expect("fund addr 0");
    env.send(&addr_1_unchecked, bitcoin::Amount::from_sat(60_000))
        .expect("fund addr 1");
    env.mine_blocks(1, None).expect("confirm fundings");
    wait_for_esplora(&env);

    let esplora_url = esplora_base_url(&env);
    alice
        .sync_with_esplora(&esplora_url, None)
        .expect("sync alice");
    assert_eq!(alice.balance(), Amount::from_sat(100_000));

    // Two UTXOs in the spendable set.
    let utxos_before = alice.list_utxos().expect("list_utxos must not fail");
    assert_eq!(
        utxos_before.len(),
        2,
        "expected exactly two utxos after dual funding"
    );

    // Pick the smaller (40_000) one and spend it to bob.
    let selected = utxos_before
        .iter()
        .min_by_key(|u| u.value.to_sat())
        .expect("at least one utxo");
    assert_eq!(selected.value, Amount::from_sat(40_000));
    let selected_outpoint = selected.outpoint;

    let mut bob = OnchainAccount::from_mnemonic(BhodlNetwork::Regtest, BOB_MNEMONIC, "")
        .expect("build bob wallet");
    let bob_addr = bob.peek_taproot_address(0).unwrap().to_string();

    let unsigned = alice
        .build_send_tx_with_utxos(
            &bob_addr,
            Amount::from_sat(20_000),
            2,
            &[selected_outpoint],
            false,
        )
        .expect("build with manual utxo selection");
    let signed = alice.sign_psbt(&unsigned).expect("sign");
    let _txid = alice
        .broadcast_tx(&esplora_url, &signed, None)
        .expect("broadcast");

    env.mine_blocks(1, None).expect("confirm spend");
    wait_for_esplora(&env);
    alice
        .sync_with_esplora(&esplora_url, None)
        .expect("re-sync alice");
    bob.sync_with_esplora(&esplora_url, None).expect("sync bob");

    // The selected UTXO must be gone from alice's spendable set.
    let utxos_after = alice.list_utxos().expect("list_utxos must not fail");
    assert!(
        utxos_after.iter().all(|u| u.outpoint != selected_outpoint),
        "the manually selected outpoint should no longer be spendable"
    );

    // Bob received the 20_000 sat output.
    assert_eq!(bob.balance(), Amount::from_sat(20_000));
}
