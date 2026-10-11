//! Funds-safety policy for outgoing transactions.
//!
//! Every PSBT builder in [`crate::OnchainAccount`] routes through the
//! helpers in this module so the guard rails live in one place:
//!
//! - [`validated_fee_rate`] — the single constructor for a
//!   [`bitcoin::FeeRate`] coming from the UI. Rejects 0 sat/vB outside
//!   regtest and anything above [`MAX_FEE_RATE_SAT_PER_VB`]. The cap
//!   also keeps BDK's internal `FeeRate * Weight` / `Amount` additions
//!   far away from `u64` overflow, which would otherwise **panic** (and
//!   the release profile is `panic = "abort"`).
//! - [`absolute_fee_limit_sat`] — the post-build absolute-fee guard:
//!   a built PSBT whose fee exceeds `max(50_000 sat, 5% of the amount
//!   sent to third parties)` is refused with
//!   [`OnchainError::FeeTooHigh`] unless the caller passes an explicit
//!   `allow_high_fee` confirmation obtained from the user.
//! - [`fee_buckets_from_raw`] — sanity post-processing of an Esplora
//!   `/fee-estimates` response. Implausible feeds are reported as
//!   [`OnchainError::FeeEstimateUnavailable`] instead of being silently
//!   clamped to 1 sat/vB.
//! - [`estimate_signed_vsize`] — virtual size of a (possibly unsigned)
//!   PSBT once its BIP-86 key-path inputs carry their signatures.
//! - [`PsbtSummary`] — what the review screens render. Computed in Rust
//!   from the PSBT bytes, never from navigation arguments.

use std::collections::HashMap;

use bhodl_core::Network as BhodlNetwork;
use bitcoin::{FeeRate, psbt::Psbt};

use crate::account::FeeEstimateBuckets;
use crate::error::OnchainError;

/// Hard ceiling for any fee rate the wallet will build with, in sat/vB.
///
/// 1 000 sat/vB is ~2x the highest sustained mainnet fee market seen to
/// date (April 2024 halving block peaked around 500-1 000 sat/vB for a
/// few blocks). Anything above it is a typo or a poisoned estimator.
/// With the cap a 100 kvB transaction costs at most 1 BTC in fees, far
/// below any `u64` overflow inside BDK's fee arithmetic.
pub const MAX_FEE_RATE_SAT_PER_VB: u64 = 1_000;

/// Floor of the absolute-fee guard: fees up to this many sats are
/// always accepted without an explicit high-fee confirmation.
pub const ABSOLUTE_FEE_FLOOR_SAT: u64 = 50_000;

/// Proportional part of the absolute-fee guard, expressed as a divisor
/// of the amount sent to third parties (`1/20` = 5%).
const ABSOLUTE_FEE_PCT_DIVISOR: u64 = 20;

/// Witness weight (WU) of a BIP-86 key-path spend once signed:
/// 1 byte item count + 1 byte length + 64 byte Schnorr signature
/// (`SIGHASH_DEFAULT`, no sighash byte).
const P2TR_KEYPATH_WITNESS_WU: u64 = 66;

/// Segwit marker + flag bytes (counted once per transaction, 1 WU each).
const SEGWIT_MARKER_FLAG_WU: u64 = 2;

/// Validate a user/UI-supplied fee rate and build the [`FeeRate`].
///
/// - `0` is refused on every network except regtest (where zero-fee
///   test transactions are a legitimate harness need).
/// - Values above [`MAX_FEE_RATE_SAT_PER_VB`] are refused everywhere —
///   there is deliberately no override, because the cap is what keeps
///   BDK's unchecked fee arithmetic from panicking.
///
/// # Errors
///
/// [`OnchainError::InvalidFeeRate`] for out-of-range values.
pub(crate) fn validated_fee_rate(
    sat_per_vb: u64,
    network: BhodlNetwork,
) -> Result<FeeRate, OnchainError> {
    // Regtest permits 0 sat/vB; every other network needs the 1 sat/vB relay floor.
    let min = u64::from(!matches!(network, BhodlNetwork::Regtest));
    if sat_per_vb < min {
        return Err(OnchainError::InvalidFeeRate(format!(
            "{sat_per_vb} sat/vB is below the {min} sat/vB relay minimum"
        )));
    }
    if sat_per_vb > MAX_FEE_RATE_SAT_PER_VB {
        return Err(OnchainError::InvalidFeeRate(format!(
            "{sat_per_vb} sat/vB exceeds the {MAX_FEE_RATE_SAT_PER_VB} sat/vB safety cap"
        )));
    }
    FeeRate::from_sat_per_vb(sat_per_vb)
        .ok_or_else(|| OnchainError::InvalidFeeRate(format!("{sat_per_vb} sat/vB")))
}

/// Absolute-fee limit for a transaction that sends `sent_external_sat`
/// to third parties: `max(50_000 sat, 5% of sent_external_sat)`.
#[must_use]
pub fn absolute_fee_limit_sat(sent_external_sat: u64) -> u64 {
    ABSOLUTE_FEE_FLOOR_SAT.max(sent_external_sat / ABSOLUTE_FEE_PCT_DIVISOR)
}

/// Refuse `fee_sat` above [`absolute_fee_limit_sat`] unless the user
/// explicitly confirmed a high fee.
///
/// # Errors
///
/// [`OnchainError::FeeTooHigh`] when the fee exceeds the limit and
/// `allow_high_fee` is `false`.
pub(crate) fn enforce_absolute_fee_limit(
    fee_sat: u64,
    sent_external_sat: u64,
    allow_high_fee: bool,
) -> Result<(), OnchainError> {
    let limit_sat = absolute_fee_limit_sat(sent_external_sat);
    if fee_sat > limit_sat && !allow_high_fee {
        return Err(OnchainError::FeeTooHigh { fee_sat, limit_sat });
    }
    Ok(())
}

/// Estimated virtual size (vB, rounded up) of `psbt` once every input
/// is finalized. Inputs that already carry a `final_script_witness` are
/// measured exactly; the rest are assumed to be BIP-86 key-path spends
/// (the only descriptor this wallet signs for).
#[must_use]
pub(crate) fn estimate_signed_vsize(psbt: &Psbt) -> u64 {
    // With every witness empty the unsigned tx serialises in the legacy
    // format, so `weight()` is exactly 4 * non-witness size.
    let base_wu = psbt.unsigned_tx.weight().to_wu();
    let witness_wu = psbt
        .inputs
        .iter()
        .map(|input| {
            input
                .final_script_witness
                .as_ref()
                .map_or(P2TR_KEYPATH_WITNESS_WU, |w| w.size() as u64)
        })
        .fold(SEGWIT_MARKER_FLAG_WU, u64::saturating_add);
    base_wu.saturating_add(witness_wu).div_ceil(4)
}

/// One output of a decoded PSBT, as rendered on the review screens.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PsbtOutputInfo {
    /// Address, or `script:<hex>` for a script with no address form.
    pub address: String,
    pub amount_sat: u64,
    /// Pays back to this wallet (either keychain).
    pub is_mine: bool,
    /// Pays to this wallet's internal (change) keychain.
    pub is_change: bool,
}

/// Review-screen summary of a PSBT, computed from the PSBT itself and
/// the wallet's script index. The review UI renders **only** this.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PsbtSummary {
    /// Every output, in transaction order.
    pub outputs: Vec<PsbtOutputInfo>,
    pub input_count: u32,
    /// Inputs whose previous output is not owned by this wallet (they
    /// will not be signed by us). 0 for every PSBT this wallet builds.
    pub foreign_input_count: u32,
    pub inputs_total_sat: u64,
    pub fee_sat: u64,
    /// Estimated virtual size once signed (see [`estimate_signed_vsize`]).
    pub vsize: u64,
    /// Sum of outputs NOT owned by this wallet — what third parties get.
    pub sent_external_sat: u64,
    /// Own inputs minus own outputs: the real decrease of the wallet
    /// balance once this transaction confirms (external + fee for a
    /// normal payment).
    pub wallet_net_debit_sat: u64,
    /// The absolute-fee guard limit for this transaction.
    pub fee_limit_sat: u64,
    /// `fee_sat > fee_limit_sat` — the review must ask for a second,
    /// explicit confirmation before signing.
    pub fee_exceeds_limit: bool,
    /// At least one input spends an unconfirmed output received from a
    /// third party (can be double-spent away by the sender). Only
    /// possible through manual coin control.
    pub spends_unconfirmed_foreign: bool,
}

/// Child-Pays-For-Parent build result: the child PSBT plus the package
/// arithmetic the review screen shows.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CpfpPlan {
    pub psbt: String,
    pub parent_txid: String,
    pub parent_fee_sat: u64,
    pub parent_vsize: u64,
    /// Number of unconfirmed ancestors of the parent included in the
    /// package (0 when the parent's inputs are all confirmed).
    pub ancestor_count: u32,
    /// Combined fee of those ancestors (excludes the parent).
    pub ancestors_fee_sat: u64,
    /// Combined vsize of those ancestors (excludes the parent).
    pub ancestors_vsize: u64,
    pub child_fee_sat: u64,
    pub child_vsize: u64,
    /// Target package fee rate the child was sized for (sat/vB).
    pub target_fee_rate_sat_vb: u64,
}

/// Child fee so that `(package_fee + child_fee) / (package_vsize +
/// child_vsize) >= target`, where the package is the parent plus all
/// its unconfirmed ancestors, never below `child_vsize * min_relay`.
///
/// # Errors
///
/// [`OnchainError::InvalidFeeRate`] on arithmetic overflow (impossible
/// with a validated rate and real transaction sizes, but checked so a
/// corrupt input can never panic).
pub(crate) fn cpfp_child_fee(
    target_sat_vb: u64,
    parent_fee_sat: u64,
    parent_vsize: u64,
    child_vsize: u64,
    min_relay_sat_vb: u64,
) -> Result<u64, OnchainError> {
    let overflow = || OnchainError::InvalidFeeRate("CPFP package fee overflow".into());
    let package_vsize = parent_vsize.checked_add(child_vsize).ok_or_else(overflow)?;
    let package_fee = target_sat_vb
        .checked_mul(package_vsize)
        .ok_or_else(overflow)?;
    let child_min = min_relay_sat_vb
        .checked_mul(child_vsize)
        .ok_or_else(overflow)?;
    Ok(package_fee.saturating_sub(parent_fee_sat).max(child_min))
}

/// Convert one Esplora estimate to whole sat/vB. `None` for values that
/// cannot be a fee rate (NaN, infinite, negative). Values above the hard
/// cap map to `u64::MAX` so the caller can flag the feed as implausible.
#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss,
    clippy::cast_precision_loss,
    reason = "range is checked against the cap before the cast"
)]
fn estimate_to_sat_vb(rate: f64) -> Option<u64> {
    if !rate.is_finite() || rate < 0.0 {
        return None;
    }
    if rate > MAX_FEE_RATE_SAT_PER_VB as f64 {
        return Some(u64::MAX);
    }
    Some((rate.ceil() as u64).max(1))
}

/// Collapse Esplora's `target -> sat/vB` map into the three UI buckets
/// (1 / 3 / 6 blocks).
///
/// - A missing target falls back to the closest larger target returned.
/// - Buckets are made monotonic (`economy <= normal <= priority`) by
///   lifting the more urgent bucket, never by lowering one.
/// - An empty / unusable response, or one where any bucket exceeds
///   [`MAX_FEE_RATE_SAT_PER_VB`], is **unavailable**: the UI must ask
///   the user for a manual rate rather than silently using 1 sat/vB.
///
/// # Errors
///
/// [`OnchainError::FeeEstimateUnavailable`] as described above.
pub(crate) fn fee_buckets_from_raw<S: std::hash::BuildHasher>(
    raw: &HashMap<u16, f64, S>,
) -> Result<FeeEstimateBuckets, OnchainError> {
    let usable: Vec<(u16, u64)> = raw
        .iter()
        .filter_map(|(t, r)| estimate_to_sat_vb(*r).map(|v| (*t, v)))
        .collect();
    let pick = |target: u16| -> Option<u64> {
        usable
            .iter()
            .filter(|(t, _)| *t >= target)
            .min_by_key(|(t, _)| *t)
            .map(|(_, v)| *v)
    };
    let (Some(priority), Some(normal), Some(economy)) = (pick(1), pick(3), pick(6)) else {
        return Err(OnchainError::FeeEstimateUnavailable(
            "the fee estimator returned no usable estimates".into(),
        ));
    };
    if [priority, normal, economy]
        .iter()
        .any(|v| *v > MAX_FEE_RATE_SAT_PER_VB)
    {
        return Err(OnchainError::FeeEstimateUnavailable(format!(
            "the fee estimator returned an implausible rate (> {MAX_FEE_RATE_SAT_PER_VB} sat/vB)"
        )));
    }
    let normal = normal.max(economy);
    let priority = priority.max(normal);
    Ok(FeeEstimateBuckets {
        economy_sat_per_vb: economy,
        normal_sat_per_vb: normal,
        priority_sat_per_vb: priority,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fee_rate_zero_rejected_outside_regtest() {
        for n in [
            BhodlNetwork::Mainnet,
            BhodlNetwork::Signet,
            BhodlNetwork::Testnet4,
        ] {
            assert!(matches!(
                validated_fee_rate(0, n),
                Err(OnchainError::InvalidFeeRate(_))
            ));
        }
        assert!(validated_fee_rate(0, BhodlNetwork::Regtest).is_ok());
    }

    #[test]
    fn fee_rate_cap_is_inclusive_and_absurd_values_are_refused() {
        assert!(validated_fee_rate(1, BhodlNetwork::Mainnet).is_ok());
        assert!(validated_fee_rate(MAX_FEE_RATE_SAT_PER_VB, BhodlNetwork::Mainnet).is_ok());
        for absurd in [
            MAX_FEE_RATE_SAT_PER_VB + 1,
            70_000_000_000_000_000,
            u64::MAX,
        ] {
            for n in [BhodlNetwork::Mainnet, BhodlNetwork::Regtest] {
                assert!(matches!(
                    validated_fee_rate(absurd, n),
                    Err(OnchainError::InvalidFeeRate(_))
                ));
            }
        }
    }

    #[test]
    fn absolute_limit_is_max_of_floor_and_five_percent() {
        assert_eq!(absolute_fee_limit_sat(0), ABSOLUTE_FEE_FLOOR_SAT);
        assert_eq!(absolute_fee_limit_sat(1_000_000), ABSOLUTE_FEE_FLOOR_SAT);
        assert_eq!(absolute_fee_limit_sat(10_000_000), 500_000);
        assert_eq!(absolute_fee_limit_sat(u64::MAX), u64::MAX / 20);
    }

    #[test]
    fn absolute_guard_requires_explicit_override() {
        assert!(enforce_absolute_fee_limit(50_000, 0, false).is_ok());
        let err = enforce_absolute_fee_limit(50_001, 0, false).unwrap_err();
        assert!(matches!(
            err,
            OnchainError::FeeTooHigh {
                fee_sat: 50_001,
                limit_sat: 50_000
            }
        ));
        assert!(enforce_absolute_fee_limit(50_001, 0, true).is_ok());
    }

    #[test]
    fn cpfp_child_fee_covers_package() {
        // Parent 200 vB paying 200 sat (1 sat/vB); child 100 vB; target 10.
        // Package needs 3_000; child pays 2_800.
        assert_eq!(cpfp_child_fee(10, 200, 200, 100, 1).unwrap(), 2_800);
        // Parent already over-pays: child still pays min relay.
        assert_eq!(cpfp_child_fee(2, 10_000, 200, 100, 1).unwrap(), 100);
        assert!(cpfp_child_fee(u64::MAX, 0, 2, 2, 1).is_err());
    }

    fn raw(pairs: &[(u16, f64)]) -> HashMap<u16, f64> {
        pairs.iter().copied().collect()
    }

    #[test]
    fn estimates_typical_values_ceil_and_floor_to_one() {
        let b = fee_buckets_from_raw(&raw(&[(1, 12.3), (3, 8.0), (6, 0.4)])).unwrap();
        assert_eq!(
            (
                b.priority_sat_per_vb,
                b.normal_sat_per_vb,
                b.economy_sat_per_vb
            ),
            (13, 8, 1)
        );
    }

    #[test]
    fn estimates_fall_back_to_closest_larger_target() {
        let b = fee_buckets_from_raw(&raw(&[(2, 20.0), (5, 10.0), (144, 2.0)])).unwrap();
        assert_eq!(
            (
                b.priority_sat_per_vb,
                b.normal_sat_per_vb,
                b.economy_sat_per_vb
            ),
            (20, 10, 2)
        );
    }

    #[test]
    fn estimates_are_made_monotonic_by_lifting() {
        let b = fee_buckets_from_raw(&raw(&[(1, 5.0), (3, 9.0), (6, 7.0)])).unwrap();
        assert_eq!(
            (
                b.priority_sat_per_vb,
                b.normal_sat_per_vb,
                b.economy_sat_per_vb
            ),
            (9, 9, 7)
        );
    }

    #[test]
    fn empty_or_garbage_estimates_are_unavailable_not_one() {
        for r in [
            raw(&[]),
            raw(&[(1, f64::NAN), (3, -1.0), (6, f64::INFINITY)]),
        ] {
            assert!(matches!(
                fee_buckets_from_raw(&r),
                Err(OnchainError::FeeEstimateUnavailable(_))
            ));
        }
    }

    #[test]
    fn implausible_estimates_are_unavailable() {
        // The signet "ghost mempool" symptom: 65 946 sat/vB.
        let r = raw(&[(1, 65_946.17), (3, 65_946.17), (6, 65_946.17)]);
        assert!(matches!(
            fee_buckets_from_raw(&r),
            Err(OnchainError::FeeEstimateUnavailable(_))
        ));
        // A single bogus urgent bucket poisons the snapshot too.
        let r = raw(&[(1, 5_000.0), (3, 4.0), (6, 2.0)]);
        assert!(fee_buckets_from_raw(&r).is_err());
        // At the cap is still accepted.
        let r = raw(&[(1, 1_000.0), (3, 4.0), (6, 2.0)]);
        assert_eq!(fee_buckets_from_raw(&r).unwrap().priority_sat_per_vb, 1_000);
    }
}
