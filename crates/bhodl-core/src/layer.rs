use core::fmt;

/// The protocol layer a balance, transaction, or account belongs to.
///
/// New variants are added as we onboard the corresponding crate (Cashu in P1,
/// Fedimint and Ark in P2). Keep this enum the single source of truth for
/// per-layer routing in the application layer.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
#[non_exhaustive]
pub enum Layer {
    Onchain,
    Lightning,
    Liquid,
    Cashu,
    Fedimint,
    Ark,
}

impl Layer {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Onchain => "onchain",
            Self::Lightning => "lightning",
            Self::Liquid => "liquid",
            Self::Cashu => "cashu",
            Self::Fedimint => "fedimint",
            Self::Ark => "ark",
        }
    }
}

impl fmt::Display for Layer {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn display_matches_as_str() {
        assert_eq!(Layer::Onchain.to_string(), "onchain");
        assert_eq!(Layer::Lightning.to_string(), "lightning");
    }
}
