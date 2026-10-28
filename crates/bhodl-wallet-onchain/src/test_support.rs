//! Test fixtures: BIP-86 public material derived from a mnemonic.
//!
//! BHODL itself never touches private keys; tests derive the matching
//! xpub / addresses here (with `bip39` as a dev-dependency) so watch
//! wallets can be checked against the canonical BIP-86 vectors.

use std::str::FromStr;

use bhodl_core::Network as BhodlNetwork;
use bitcoin::{
    Address,
    bip32::{ChildNumber, DerivationPath, Xpriv, Xpub},
    key::Secp256k1,
};

use crate::common::map_network;

/// Canonical BIP-86 test-vector mnemonic.
/// Source: <https://github.com/bitcoin/bips/blob/master/bip-0086.mediawiki>
pub(crate) const BIP86_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
     abandon abandon abandon abandon abandon about";

fn coin_type(network: BhodlNetwork) -> u32 {
    u32::from(network != BhodlNetwork::Mainnet)
}

/// Master fingerprint (hex) and account xpub at `m/86'/coin'/0'` for
/// [`BIP86_MNEMONIC`] + `passphrase`.
pub(crate) fn bip86_account(network: BhodlNetwork, passphrase: &str) -> (String, Xpub) {
    let secp = Secp256k1::new();
    let seed = bip39::Mnemonic::parse(BIP86_MNEMONIC)
        .expect("valid test mnemonic")
        .to_seed(passphrase);
    let master = Xpriv::new_master(map_network(network), &seed).expect("master key");
    let path =
        DerivationPath::from_str(&format!("m/86'/{}'/0'", coin_type(network))).expect("valid path");
    let account = master.derive_priv(&secp, &path).expect("derive account");
    (
        master.fingerprint(&secp).to_string(),
        Xpub::from_priv(&secp, &account),
    )
}

/// Account-level xpub (`xpub` / `tpub`) for [`BIP86_MNEMONIC`].
pub(crate) fn bip86_xpub(network: BhodlNetwork) -> String {
    bip86_account(network, "").1.to_string()
}

/// BIP-86 receive address at `index` (`m/86'/coin'/0'/0/index`).
pub(crate) fn bip86_address(network: BhodlNetwork, index: u32) -> String {
    let secp = Secp256k1::new();
    let (_, xpub) = bip86_account(network, "");
    let child = xpub
        .derive_pub(
            &secp,
            &[
                ChildNumber::from_normal_idx(0).expect("normal"),
                ChildNumber::from_normal_idx(index).expect("normal"),
            ],
        )
        .expect("derive child");
    Address::p2tr(&secp, child.to_x_only_pub(), None, map_network(network)).to_string()
}

#[test]
fn bip86_fixture_matches_the_spec_vectors() {
    // BIP-86 §Test vectors, account 0, first receive address.
    assert_eq!(
        bip86_xpub(BhodlNetwork::Mainnet),
        "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
    );
    assert_eq!(
        bip86_address(BhodlNetwork::Mainnet, 0),
        "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
    );
    assert_eq!(bip86_account(BhodlNetwork::Mainnet, "").0, "73c5da0a");
}
