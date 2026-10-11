use core::fmt;

/// Bitcoin network selector. Mirrors `bitcoin::Network` but lives in `bhodl-core`
/// so non-bitcoin layers (Liquid, Cashu, Fedimint) can also dispatch on it without
/// pulling rust-bitcoin transitively.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Network {
    Mainnet,
    Testnet4,
    Signet,
    Regtest,
}

impl Network {
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Mainnet => "mainnet",
            Self::Testnet4 => "testnet4",
            Self::Signet => "signet",
            Self::Regtest => "regtest",
        }
    }
}

impl fmt::Display for Network {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn as_str_matches_canonical_lowercase_names() {
        assert_eq!(Network::Mainnet.as_str(), "mainnet");
        assert_eq!(Network::Testnet4.as_str(), "testnet4");
        assert_eq!(Network::Signet.as_str(), "signet");
        assert_eq!(Network::Regtest.as_str(), "regtest");
    }

    #[test]
    fn display_matches_as_str() {
        assert_eq!(Network::Mainnet.to_string(), "mainnet");
        assert_eq!(Network::Signet.to_string(), "signet");
    }
}
