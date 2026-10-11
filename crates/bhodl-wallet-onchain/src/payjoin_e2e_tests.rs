//! End-to-end BIP-78 tests: the REAL sender (`payjoin_negotiate_with` →
//! `vet_endpoint` → request build → `validate_proposal` → session store →
//! `payjoin_sign_checked` → extract) talking to an in-process receiver
//! that is itself a second BDK wallet.
//!
//! Only the socket is replaced (see `PayjoinIo`): endpoint vetting runs
//! with an injected resolver that answers a public address, so the SSRF
//! guard, https-only rule and DNS pinning are exercised exactly as in
//! production. Funding is synthetic (confirmed transactions injected into
//! the BDK graph), hence no network and no Docker.
//!
//! Signatures are verified here with BIP-341 sighash + BIP-340 Schnorr
//! verification, since `bitcoinconsensus` is not a dependency.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use bdk_chain::{BlockId, ConfirmationBlockTime, TxUpdate};
use bitcoin::{
    BlockHash, ScriptBuf, Sequence, Transaction, TxIn, TxOut, WPubkeyHash, Witness,
    absolute::LockTime,
    hashes::Hash,
    secp256k1::{Message, Secp256k1, XOnlyPublicKey},
    sighash::{Prevouts, SighashCache, TapSighashType},
    transaction::Version,
};

use super::*;
use crate::payjoin::{self as pj, PayjoinIo, PayjoinParams, PayjoinRoute};

const SENDER_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon \
                               abandon abandon abandon abandon abandon about";
const RECEIVER_MNEMONIC: &str =
    "legal winner thank year wave sausage worth useful legal winner thank yellow";

const SENDER_FUNDS: u64 = 200_000;
const AMOUNT_SAT: u64 = 50_000;
const FEE_RATE: u64 = 2;
const PJ_ENDPOINT: &str = "https://payjoin.example.org/pj";

#[allow(
    clippy::unnecessary_wraps,
    reason = "must match the resolver signature expected by vet_endpoint"
)]
fn public_dns(_: &str, port: u16) -> std::io::Result<Vec<SocketAddr>> {
    Ok(vec![SocketAddr::new(
        IpAddr::V4(Ipv4Addr::new(93, 184, 216, 34)),
        port,
    )])
}

#[allow(
    clippy::unnecessary_wraps,
    reason = "must match the resolver signature expected by vet_endpoint"
)]
fn loopback_dns(_: &str, port: u16) -> std::io::Result<Vec<SocketAddr>> {
    Ok(vec![SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port)])
}

fn attacker_spk() -> ScriptBuf {
    ScriptBuf::from_bytes([&[0x51, 0x20][..], &[0xA7u8; 32]].concat())
}

// ---------------------------------------------------------------- funding

fn confirm(acct: &mut OnchainAccount, tx: Transaction, height: u32) {
    let txid = tx.compute_txid();
    let block_id = BlockId {
        height,
        hash: BlockHash::from_byte_array([u8::try_from(height).unwrap(); 32]),
    };
    let chain = acct.wallet.latest_checkpoint().insert(block_id);
    let mut tx_update = TxUpdate::default();
    tx_update.txs = vec![Arc::new(tx)];
    tx_update.anchors = [(
        ConfirmationBlockTime {
            block_id,
            confirmation_time: 1_700_000_000,
        },
        txid,
    )]
    .into();
    acct.wallet
        .apply_update(bdk_wallet::Update {
            chain: Some(chain),
            tx_update,
            ..Default::default()
        })
        .unwrap();
}

/// Confirmed third-party transaction paying `values` to fresh receive
/// addresses of `acct`.
fn fund_confirmed(acct: &mut OnchainAccount, values: &[u64], salt: u8) -> Vec<OutPoint> {
    let output = values
        .iter()
        .map(|v| TxOut {
            value: bitcoin::Amount::from_sat(*v),
            script_pubkey: acct.reveal_next_address().unwrap().script_pubkey(),
        })
        .collect();
    let tx = Transaction {
        version: Version::TWO,
        lock_time: LockTime::ZERO,
        input: vec![TxIn {
            previous_output: OutPoint::new(bitcoin::Txid::from_byte_array([salt; 32]), 0),
            ..TxIn::default()
        }],
        output,
    };
    let txid = tx.compute_txid();
    confirm(acct, tx, u32::from(salt));
    (0..values.len())
        .map(|i| OutPoint::new(txid, u32::try_from(i).unwrap()))
        .collect()
}

// --------------------------------------------------------------- receiver

/// How the in-process receiver behaves.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Behavior {
    /// BIP-78 receiver doing everything right.
    Honest,
    /// Takes the whole `maxadditionalfeecontribution` from the sender's
    /// change but keeps it for itself (payment output) instead of fee.
    PocketsFeeAllowance,
    /// Lowers the sender's change by more than the declared allowance and
    /// routes the surplus into the payment output.
    OverdrawsChange,
    /// Contributes a (finalized-looking) P2WPKH input next to the
    /// sender's P2TR one.
    MixedInputType,
    /// Re-scripts the payment output to an attacker script
    /// (`disableoutputsubstitution=true` forbids that).
    SwapsPaymentScript,
    /// Adds its input but forgets to sign it.
    LeavesInputUnsigned,
}

/// What the receiver did, for the assertions.
#[derive(Debug, Clone)]
struct Reception {
    proposal_b64: String,
    rx_outpoint: OutPoint,
    rx_value: u64,
    contribution: u64,
    request_url: url::Url,
}

fn query_map(url: &url::Url) -> HashMap<String, String> {
    url.query_pairs()
        .map(|(k, v)| (k.into_owned(), v.into_owned()))
        .collect()
}

/// BIP-78 receiver. Everything it knows about the request comes from the
/// URL the sender actually built and the PSBT it actually posted.
#[allow(clippy::too_many_lines, reason = "one linear BIP-78 receiver script")]
fn receive(
    rx: &OnchainAccount,
    behavior: Behavior,
    route: &PayjoinRoute,
    original_b64: &str,
    params: &PayjoinParams,
) -> Reception {
    let PayjoinRoute::Direct { url, .. } = route else {
        panic!("clearnet https endpoint must vet to a Direct route");
    };
    let request_url = pj::request_url(url, params);
    let q = query_map(&request_url);
    assert_eq!(q.get("v").map(String::as_str), Some("1"));
    let min_fee_rate: u64 = q["minfeerate"].parse().unwrap();
    let fee_output: Option<usize> = q
        .get("additionalfeeoutputindex")
        .map(|v| v.parse().unwrap());
    let max_contribution: u64 = q
        .get("maxadditionalfeecontribution")
        .map_or(0, |v| v.parse().unwrap());
    assert_eq!(
        q.get("disableoutputsubstitution").map(String::as_str),
        Some("true")
    );

    // Check the original like a receiver must: parseable, fully signed.
    let original: Psbt = original_b64.parse().unwrap();
    assert!(
        original.inputs.iter().all(|i| i
            .final_script_witness
            .as_ref()
            .is_some_and(|w| !w.is_empty())),
        "the original must be signed + finalized"
    );

    let mut psbt = original.clone();
    // Strip the sender's signatures and key material (BIP-78 receiver duty).
    for input in &mut psbt.inputs {
        input.final_script_witness = None;
        input.final_script_sig = None;
        input.tap_key_sig = None;
        input.tap_internal_key = None;
        input.tap_key_origins.clear();
        input.tap_merkle_root = None;
        input.bip32_derivation.clear();
        input.partial_sigs.clear();
    }
    for output in &mut psbt.outputs {
        output.tap_internal_key = None;
        output.tap_key_origins.clear();
        output.bip32_derivation.clear();
    }

    // The receiver's own coin (or a synthetic one for the mixed-type attack).
    let (rx_outpoint, rx_value, mut rx_input) = if behavior == Behavior::MixedInputType {
        let utxo = TxOut {
            value: bitcoin::Amount::from_sat(80_000),
            script_pubkey: ScriptBuf::new_p2wpkh(&WPubkeyHash::from_byte_array([1; 20])),
        };
        let input = bitcoin::psbt::Input {
            witness_utxo: Some(utxo),
            final_script_witness: Some(Witness::from_slice(&[vec![1u8; 71], vec![2u8; 33]])),
            ..Default::default()
        };
        (
            OutPoint::new(bitcoin::Txid::from_byte_array([0x77; 32]), 0),
            80_000,
            input,
        )
    } else {
        let utxo = rx
            .wallet
            .list_unspent()
            .find(|u| u.chain_position.is_confirmed())
            .expect("receiver has a confirmed coin");
        let input = rx.wallet.get_psbt_input(utxo.clone(), None, true).unwrap();
        (utxo.outpoint, utxo.txout.value.to_sat(), input)
    };
    assert!(
        !original
            .unsigned_tx
            .input
            .iter()
            .any(|i| i.previous_output == rx_outpoint),
        "receiver must not reuse a sender input"
    );

    // Receiver input goes FIRST, so the sender's input indices shift.
    let sequence = original.unsigned_tx.input[0].sequence;
    psbt.unsigned_tx.input.insert(
        0,
        TxIn {
            previous_output: rx_outpoint,
            sequence,
            ..TxIn::default()
        },
    );
    if behavior == Behavior::LeavesInputUnsigned {
        rx_input.final_script_witness = None;
    }
    psbt.inputs.insert(0, rx_input);

    // Outputs.
    let payment = psbt
        .unsigned_tx
        .output
        .iter()
        .position(|o| rx.wallet.is_mine(o.script_pubkey.clone()))
        .expect("original pays one of our addresses");
    let contribution = match behavior {
        Behavior::Honest
        | Behavior::MixedInputType
        | Behavior::LeavesInputUnsigned
        | Behavior::SwapsPaymentScript => {
            // Sender covers exactly the extra input's cost, never above the allowance.
            max_contribution.min(min_fee_rate * pj::P2TR_KEYPATH_INPUT_VSIZE)
        }
        Behavior::PocketsFeeAllowance => max_contribution,
        Behavior::OverdrawsChange => max_contribution + 10_000,
    };
    let mut payment_gain = rx_value;
    if matches!(
        behavior,
        Behavior::PocketsFeeAllowance | Behavior::OverdrawsChange
    ) {
        // The value taken from the change is NOT left as fee.
        payment_gain += contribution;
    }
    let fee_output = fee_output.expect("sender declared a change output");
    {
        let out = &mut psbt.unsigned_tx.output[fee_output];
        out.value -= bitcoin::Amount::from_sat(contribution);
    }
    {
        let out = &mut psbt.unsigned_tx.output[payment];
        out.value += bitcoin::Amount::from_sat(payment_gain);
        if behavior == Behavior::SwapsPaymentScript {
            out.script_pubkey = attacker_spk();
        }
    }

    // Sign ONLY the receiver's input (it is the only one it holds keys for).
    if !matches!(
        behavior,
        Behavior::MixedInputType | Behavior::LeavesInputUnsigned
    ) {
        let signed_all = rx.wallet.sign(&mut psbt, payjoin_sign_options()).unwrap();
        assert!(!signed_all, "the sender's inputs must remain unsigned");
        assert!(
            psbt.inputs[0]
                .final_script_witness
                .as_ref()
                .is_some_and(|w| w.len() == 1 && w.iter().next().unwrap().len() == 64),
            "receiver input must carry a 64-byte key-path signature"
        );
        for sender_in in &psbt.inputs[1..] {
            assert!(sender_in.final_script_witness.is_none());
        }
    }
    // BDK's signer re-annotates the outputs it owns (our payment output)
    // with key origins; a BIP-78 receiver must strip them again, and the
    // sender rejects the proposal if it does not.
    for output in &mut psbt.outputs {
        output.tap_internal_key = None;
        output.tap_key_origins.clear();
        output.bip32_derivation.clear();
    }
    // Hand back a clean input: no key paths for the sender to see.
    let rx_in = &mut psbt.inputs[0];
    rx_in.tap_internal_key = None;
    rx_in.tap_key_origins.clear();
    rx_in.tap_merkle_root = None;
    rx_in.tap_key_sig = None;
    rx_in.bip32_derivation.clear();

    Reception {
        proposal_b64: psbt.to_string(),
        rx_outpoint,
        rx_value,
        contribution,
        request_url,
    }
}

// ---------------------------------------------------------------- harness

struct Harness {
    sender: OnchainAccount,
    receiver: OnchainAccount,
    sender_utxo: OutPoint,
    uri: String,
    payment_address: String,
}

/// Outcome of one negotiation round.
struct Round {
    result: Result<pj::PayjoinNegotiation, OnchainError>,
    /// `None` when the receiver was never contacted.
    reception: Option<Reception>,
    post_calls: u32,
}

impl Harness {
    fn new() -> Self {
        let mut sender =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, SENDER_MNEMONIC, "").unwrap();
        let mut receiver =
            OnchainAccount::from_mnemonic(BhodlNetwork::Signet, RECEIVER_MNEMONIC, "").unwrap();
        let sender_utxo = fund_confirmed(&mut sender, &[SENDER_FUNDS], 1)[0];
        fund_confirmed(&mut receiver, &[90_000], 2);
        let payment_address = receiver.reveal_next_address().unwrap().to_string();
        let uri = format!("bitcoin:{payment_address}?amount=0.0005&pj={PJ_ENDPOINT}");
        assert_eq!(AMOUNT_SAT, 50_000, "URI amount above must match");
        Self {
            sender,
            receiver,
            sender_utxo,
            uri,
            payment_address,
        }
    }

    fn negotiate(&mut self, behavior: Behavior) -> Round {
        let seen: RefCell<Option<Reception>> = RefCell::new(None);
        let calls = Cell::new(0u32);
        let receiver = &self.receiver;
        let post = |route: &PayjoinRoute, body: &str, params: &PayjoinParams| {
            calls.set(calls.get() + 1);
            let r = receive(receiver, behavior, route, body, params);
            let wire = r.proposal_b64.clone();
            *seen.borrow_mut() = Some(r);
            Ok(wire)
        };
        let io = PayjoinIo {
            resolve: &public_dns,
            post: &post,
        };
        let result = self.sender.payjoin_negotiate_with(
            &self.uri,
            &self.payment_address,
            AMOUNT_SAT,
            FEE_RATE,
            None,
            &io,
        );
        Round {
            result,
            reception: seen.into_inner(),
            post_calls: calls.get(),
        }
    }

    fn wallet_state(&self) -> (usize, u64, usize) {
        (
            self.sender.wallet.transactions().count(),
            self.sender.balance().to_sat(),
            self.sender.wallet.list_unspent().count(),
        )
    }
}

/// BIP-341 key-path verification of every input of `tx` against the
/// prevouts recorded in `psbt`.
fn assert_keypath_signatures_valid(tx: &Transaction, psbt: &Psbt) {
    let secp = Secp256k1::verification_only();
    let prevouts: Vec<TxOut> = psbt
        .inputs
        .iter()
        .map(|i| i.witness_utxo.clone().unwrap())
        .collect();
    let mut cache = SighashCache::new(tx);
    for (i, prevout) in prevouts.iter().enumerate() {
        assert!(prevout.script_pubkey.is_p2tr());
        let witness = &tx.input[i].witness;
        assert_eq!(witness.len(), 1, "input {i}: key-path spend expected");
        let sig = bitcoin::taproot::Signature::from_slice(witness.iter().next().unwrap())
            .unwrap_or_else(|e| panic!("input {i}: bad signature encoding: {e}"));
        assert_eq!(sig.sighash_type, TapSighashType::Default);
        let digest = cache
            .taproot_key_spend_signature_hash(i, &Prevouts::All(&prevouts), TapSighashType::Default)
            .unwrap();
        let key = XOnlyPublicKey::from_slice(&prevout.script_pubkey.as_bytes()[2..34]).unwrap();
        secp.verify_schnorr(
            &sig.signature,
            &Message::from_digest(digest.to_byte_array()),
            &key,
        )
        .unwrap_or_else(|e| panic!("input {i}: invalid Schnorr signature: {e}"));
    }
}

// ------------------------------------------------------------- happy path

#[test]
fn payjoin_happy_path_negotiate_validate_sign_finalize() {
    let mut h = Harness::new();
    let before_balance = h.sender.balance().to_sat();
    assert_eq!(before_balance, SENDER_FUNDS);

    let round = h.negotiate(Behavior::Honest);
    assert_eq!(round.post_calls, 1);
    let rx = round.reception.expect("receiver was contacted");
    let neg = round.result.unwrap();
    assert_eq!(neg.failure_reason, None, "proposal must be accepted");
    let session_id = neg.session_id.clone().expect("session parked for review");

    // Request: the sender asked for exactly what BIP-78 / the policy says.
    let q = query_map(&rx.request_url);
    assert_eq!(rx.request_url.scheme(), "https");
    assert_eq!(rx.request_url.host_str(), Some("payjoin.example.org"));
    assert_eq!(q["minfeerate"], FEE_RATE.to_string());
    assert_eq!(
        q["maxadditionalfeecontribution"],
        (FEE_RATE * pj::P2TR_KEYPATH_INPUT_VSIZE).to_string()
    );

    // Review summary: the receiver's money lands in the payment output.
    assert_eq!(neg.receiver_input_count, 1);
    assert_eq!(rx.contribution, FEE_RATE * pj::P2TR_KEYPATH_INPUT_VSIZE);
    assert_eq!(neg.sender_fee_contribution_sat, rx.contribution);
    assert_eq!(neg.proposal_fee_sat, neg.original_fee_sat + rx.contribution);
    let payment: Vec<_> = neg.outputs.iter().filter(|o| o.is_payment).collect();
    assert_eq!(payment.len(), 1);
    assert_eq!(payment[0].amount_sat, AMOUNT_SAT + rx.rx_value);
    assert_eq!(payment[0].address, h.payment_address);
    // Sender spends amount + fee, and the fee increase stays inside the allowance.
    assert_eq!(neg.sender_net_spend_sat, AMOUNT_SAT + neg.proposal_fee_sat);
    assert!(
        neg.sender_net_spend_sat
            <= AMOUNT_SAT + neg.original_fee_sat + FEE_RATE * pj::P2TR_KEYPATH_INPUT_VSIZE
    );
    // Nothing was signed for the proposal or recorded yet.
    assert_eq!(h.sender.wallet.transactions().count(), 1);

    // Sign + finalize (everything `payjoin_sign_and_broadcast` does pre-network).
    let signed = h.sender.payjoin_sign_checked(&session_id).unwrap();
    let tx = signed.clone().extract_tx().expect("fully finalized");

    // Shape: receiver input first (as the test receiver ordered it), then ours.
    assert_eq!(tx.input.len(), 2);
    assert_eq!(tx.input[0].previous_output, rx.rx_outpoint);
    assert_eq!(tx.input[1].previous_output, h.sender_utxo);
    assert!(tx.input.iter().all(|i| !i.witness.is_empty()));

    // The sender signed only its own input; the receiver's came back untouched.
    let proposal: Psbt = rx.proposal_b64.parse().unwrap();
    assert_eq!(signed.inputs[0], proposal.inputs[0]);
    assert!(proposal.inputs[1].final_script_witness.is_none());
    assert!(signed.inputs[1].final_script_witness.is_some());
    assert_eq!(signed.unsigned_tx, proposal.unsigned_tx);

    // Money: payment grew by the receiver's input; fee matches the review.
    let total_in = SENDER_FUNDS + rx.rx_value;
    let total_out: u64 = tx.output.iter().map(|o| o.value.to_sat()).sum();
    let fee = total_in - total_out;
    assert_eq!(fee, neg.proposal_fee_sat);
    let pay_out = tx
        .output
        .iter()
        .find(|o| o.script_pubkey == h.receiver_payment_spk())
        .unwrap();
    assert_eq!(pay_out.value.to_sat(), AMOUNT_SAT + rx.rx_value);
    let change: u64 = tx
        .output
        .iter()
        .filter(|o| h.sender.wallet.is_mine(o.script_pubkey.clone()))
        .map(|o| o.value.to_sat())
        .sum();
    assert_eq!(change, SENDER_FUNDS - AMOUNT_SAT - fee);
    assert!(
        fee >= FEE_RATE * tx.vsize() as u64,
        "fee {fee} below {FEE_RATE} sat/vB for {} vB",
        tx.vsize()
    );

    // Consensus-level signature check of both inputs.
    assert_keypath_signatures_valid(&tx, &signed);

    // Session is single-use.
    assert!(h.sender.payjoin_sign_checked(&session_id).is_err());

    // Local view after "broadcast": we are down exactly amount + fee.
    h.sender.record_broadcast(tx);
    assert_eq!(h.sender.balance().to_sat(), SENDER_FUNDS - AMOUNT_SAT - fee);
}

impl Harness {
    fn receiver_payment_spk(&self) -> ScriptBuf {
        self.payment_address
            .parse::<Address<bitcoin::address::NetworkUnchecked>>()
            .unwrap()
            .assume_checked()
            .script_pubkey()
    }
}

// --------------------------------------------------- adversarial receivers

/// Run a malicious receiver and assert: rejected with `needle`, fallback
/// is the untouched signed original, nothing parked / signed / recorded.
fn assert_attack_rejected(behavior: Behavior, needle: &str) {
    let mut h = Harness::new();
    let state_before = h.wallet_state();
    let round = h.negotiate(behavior);
    assert_eq!(round.post_calls, 1);
    let neg = round.result.expect("failure is reported in-band");

    let reason = neg
        .failure_reason
        .clone()
        .expect("proposal must be rejected");
    assert!(
        reason.contains(needle),
        "{behavior:?}: expected {needle:?} in {reason:?}"
    );
    assert_eq!(neg.session_id, None, "{behavior:?}: nothing may be parked");
    assert_eq!(neg.receiver_input_count, 0);
    assert_eq!(neg.sender_fee_contribution_sat, 0);

    // Fallback = our original, signed, spending only our coin, paying only
    // the intended amount to the intended script.
    let original: Psbt = neg.original_psbt.parse().unwrap();
    let tx = original
        .clone()
        .extract_tx()
        .expect("fallback is broadcastable");
    assert_eq!(tx.input.len(), 1);
    assert_eq!(tx.input[0].previous_output, h.sender_utxo);
    let pay = tx
        .output
        .iter()
        .find(|o| o.script_pubkey == h.receiver_payment_spk())
        .expect("original pays the URI address");
    assert_eq!(pay.value.to_sat(), AMOUNT_SAT);
    assert_keypath_signatures_valid(&tx, &original);
    assert_eq!(neg.sender_net_spend_sat, AMOUNT_SAT + neg.original_fee_sat);

    // No session means nothing can be signed or broadcast; the wallet's
    // tx graph, balance and UTXO set are untouched.
    assert!(h.sender.payjoin_sign_checked("deadbeef").is_err());
    assert_eq!(
        h.wallet_state(),
        state_before,
        "{behavior:?}: wallet mutated"
    );
}

#[test]
fn receiver_pocketing_the_fee_allowance_is_rejected() {
    // Within maxadditionalfeecontribution, but the fee does not grow:
    // the sender's change just moved into the receiver's output.
    assert_attack_rejected(Behavior::PocketsFeeAllowance, "fee only grew by 0");
}

#[test]
fn receiver_lowering_change_beyond_allowance_is_rejected() {
    assert_attack_rejected(Behavior::OverdrawsChange, "maxadditionalfeecontribution");
}

#[test]
fn receiver_adding_a_different_script_type_input_is_rejected() {
    assert_attack_rejected(Behavior::MixedInputType, "not P2TR");
}

#[test]
fn receiver_changing_the_payment_script_is_rejected() {
    assert_attack_rejected(Behavior::SwapsPaymentScript, "re-scripted");
}

#[test]
fn receiver_returning_an_unsigned_input_is_rejected() {
    assert_attack_rejected(Behavior::LeavesInputUnsigned, "not finalized");
}

#[test]
fn receiver_http_error_falls_back_to_the_original() {
    let mut h = Harness::new();
    let state_before = h.wallet_state();
    let post = |_: &PayjoinRoute, _: &str, _: &PayjoinParams| -> Result<String, OnchainError> {
        Err(OnchainError::Broadcast(
            "PayJoin endpoint returned HTTP 400: {\"errorCode\":\"original-psbt-rejected\"}".into(),
        ))
    };
    let io = PayjoinIo {
        resolve: &public_dns,
        post: &post,
    };
    let neg = h
        .sender
        .payjoin_negotiate_with(&h.uri, &h.payment_address, AMOUNT_SAT, FEE_RATE, None, &io)
        .unwrap();
    assert!(neg.session_id.is_none());
    assert!(
        neg.failure_reason
            .unwrap()
            .contains("original-psbt-rejected")
    );
    assert!(neg.original_psbt.parse::<Psbt>().is_ok());
    assert_eq!(h.wallet_state(), state_before);
}

// ------------------------------------------- vetting still runs on the seam

#[test]
fn injected_io_does_not_bypass_endpoint_vetting() {
    let post_calls = Cell::new(0u32);
    let post = |_: &PayjoinRoute, _: &str, _: &PayjoinParams| -> Result<String, OnchainError> {
        post_calls.set(post_calls.get() + 1);
        Err(OnchainError::Broadcast("must never be reached".into()))
    };

    // DNS answering loopback → refused by the SSRF guard.
    let mut h = Harness::new();
    let state_before = h.wallet_state();
    let io = PayjoinIo {
        resolve: &loopback_dns,
        post: &post,
    };
    let err = h
        .sender
        .payjoin_negotiate_with(&h.uri, &h.payment_address, AMOUNT_SAT, FEE_RATE, None, &io)
        .unwrap_err();
    assert!(
        matches!(err, OnchainError::InvalidAddress(ref m) if m.contains("SSRF")),
        "{err:?}"
    );

    // Plain http to a clearnet host, loopback literal, localhost name.
    let io = PayjoinIo {
        resolve: &public_dns,
        post: &post,
    };
    for pj_url in [
        "http://payjoin.example.org/pj",
        "https://127.0.0.1/pj",
        "https://localhost/pj",
        "https://[::1]/pj",
    ] {
        let uri = format!("bitcoin:{}?amount=0.0005&pj={pj_url}", h.payment_address);
        let r = h.sender.payjoin_negotiate_with(
            &uri,
            &h.payment_address,
            AMOUNT_SAT,
            FEE_RATE,
            None,
            &io,
        );
        assert!(r.is_err(), "{pj_url} must be refused");
    }
    assert_eq!(
        post_calls.get(),
        0,
        "transport reached despite failed vetting"
    );
    assert_eq!(h.wallet_state(), state_before);
}

#[test]
fn production_io_resolves_with_the_system_resolver_and_pins_the_route() {
    // The seam's default is the real thing, and a vetted clearnet route
    // is pinned to the resolver's answer with https only.
    let route = pj::vet_endpoint(PJ_ENDPOINT, None, public_dns).unwrap();
    match route {
        PayjoinRoute::Direct { url, addrs } => {
            assert_eq!(url.scheme(), "https");
            assert_eq!(addrs, public_dns("", 443).unwrap());
        }
        PayjoinRoute::Proxied { .. } => panic!("no proxy configured"),
    }
    let _: &PayjoinIo<'static> = &PayjoinIo::PRODUCTION;
}

#[test]
fn sequence_is_shared_by_all_inputs_in_the_proposal() {
    // Guard for the test receiver itself: it copies the sender's nSequence.
    let mut h = Harness::new();
    let round = h.negotiate(Behavior::Honest);
    let rx = round.reception.unwrap();
    let proposal: Psbt = rx.proposal_b64.parse().unwrap();
    let seqs: Vec<Sequence> = proposal
        .unsigned_tx
        .input
        .iter()
        .map(|i| i.sequence)
        .collect();
    assert!(seqs.windows(2).all(|w| w[0] == w[1]));
}
