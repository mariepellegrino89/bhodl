use core::fmt;

use thiserror::Error;

/// Bitcoin amount expressed exclusively in satoshis.
///
/// All BHODL math is sat-integer. Fiat conversion happens at the rendering layer
/// via `bhodl-price-oracle` + `rust_decimal`; the wallet core never holds floats.
///
/// `u64` covers the entire 21M BTC supply (2.1e15 sat) with two orders of magnitude
/// of headroom, so overflow only matters at the API boundary (sums of many amounts).
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash, Default)]
pub struct Amount(u64);

impl Amount {
    pub const ZERO: Self = Self(0);

    pub const MAX_MONEY_SAT: u64 = 21_000_000 * 100_000_000;
    pub const MAX_MONEY: Self = Self(Self::MAX_MONEY_SAT);

    #[must_use]
    pub const fn from_sat(sat: u64) -> Self {
        Self(sat)
    }

    #[must_use]
    pub const fn to_sat(self) -> u64 {
        self.0
    }

    /// Construct an amount, rejecting values above the 21M BTC ceiling.
    ///
    /// Use this at trust boundaries (RPC, JSON, `UniFFI` inputs); use
    /// [`from_sat`](Self::from_sat) for already-validated values.
    pub fn checked_from_sat(sat: u64) -> Result<Self, AmountError> {
        if sat > Self::MAX_MONEY_SAT {
            Err(AmountError::AboveMaxMoney(sat))
        } else {
            Ok(Self(sat))
        }
    }

    pub fn checked_add(self, rhs: Self) -> Result<Self, AmountError> {
        self.0
            .checked_add(rhs.0)
            .ok_or(AmountError::Overflow)
            .and_then(Self::checked_from_sat)
    }

    pub fn checked_sub(self, rhs: Self) -> Result<Self, AmountError> {
        self.0
            .checked_sub(rhs.0)
            .map(Self)
            .ok_or(AmountError::Underflow)
    }
}

impl fmt::Display for Amount {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} sat", self.0)
    }
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum AmountError {
    #[error("amount {0} sat exceeds 21M BTC ceiling")]
    AboveMaxMoney(u64),
    #[error("amount overflow")]
    Overflow,
    #[error("amount underflow")]
    Underflow,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_uses_sat_suffix() {
        assert_eq!(Amount::from_sat(123).to_string(), "123 sat");
    }

    #[test]
    fn rejects_above_max_money() {
        assert!(Amount::checked_from_sat(Amount::MAX_MONEY_SAT).is_ok());
        assert!(matches!(
            Amount::checked_from_sat(Amount::MAX_MONEY_SAT + 1),
            Err(AmountError::AboveMaxMoney(_))
        ));
    }

    #[test]
    fn checked_add_caps_at_max_money() {
        let half = Amount::from_sat(Amount::MAX_MONEY_SAT / 2 + 1);
        assert!(matches!(
            half.checked_add(half),
            Err(AmountError::AboveMaxMoney(_))
        ));
    }

    #[test]
    fn checked_add_overflow() {
        let big = Amount::from_sat(u64::MAX);
        assert_eq!(
            big.checked_add(Amount::from_sat(1)),
            Err(AmountError::Overflow)
        );
    }

    #[test]
    fn checked_sub_underflow() {
        assert_eq!(
            Amount::ZERO.checked_sub(Amount::from_sat(1)),
            Err(AmountError::Underflow)
        );
    }

    #[test]
    fn ordering_is_numeric() {
        assert!(Amount::from_sat(100) < Amount::from_sat(101));
    }
}
