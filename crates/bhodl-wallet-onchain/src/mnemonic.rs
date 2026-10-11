//! BIP39 mnemonic generation.
//!
//! Wraps `bip39::Mnemonic::from_entropy` with a typed `MnemonicWordCount`
//! enum so callers cannot accidentally request an unsupported length (BIP39
//! defines exactly 12, 15, 18, 21 or 24 words).
//!
//! Entropy comes from `rand::rngs::OsRng` — a stateless ZST that forwards
//! every call to `getrandom` (Windows `ProcessPrng`, Linux `getrandom(2)`,
//! macOS `getentropy`). No userspace buffering, no reseeding, no fallback
//! generator: if the OS cannot deliver entropy the call fails closed and
//! no mnemonic is produced. Do NOT "simplify" this to `rand::rng()` —
//! see the comment in [`generate_mnemonic`] for why `ThreadRng` is unsafe
//! here.

use bip39::Mnemonic;
use zeroize::Zeroizing;

use crate::error::OnchainError;

/// Supported BIP39 word counts.
///
/// Mapped 1:1 with BIP39 §3 entropy lengths:
/// 12 words = 128 bit, 15 = 160, 18 = 192, 21 = 224, 24 = 256.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MnemonicWordCount {
    Words12,
    Words15,
    Words18,
    Words21,
    Words24,
}

impl MnemonicWordCount {
    /// Number of words this variant produces.
    #[must_use]
    pub const fn count(self) -> usize {
        match self {
            Self::Words12 => 12,
            Self::Words15 => 15,
            Self::Words18 => 18,
            Self::Words21 => 21,
            Self::Words24 => 24,
        }
    }

    /// Build a `MnemonicWordCount` from a numeric value, accepting only
    /// the five BIP39-defined sizes.
    ///
    /// # Errors
    ///
    /// Returns [`OnchainError::DerivationPath`] (re-using the existing
    /// "input parameter rejected" channel) when `count` is none of
    /// 12/15/18/21/24.
    pub fn from_count(count: u32) -> Result<Self, OnchainError> {
        match count {
            12 => Ok(Self::Words12),
            15 => Ok(Self::Words15),
            18 => Ok(Self::Words18),
            21 => Ok(Self::Words21),
            24 => Ok(Self::Words24),
            other => Err(OnchainError::DerivationPath(format!(
                "BIP39 word count must be 12, 15, 18, 21 or 24 (got {other})"
            ))),
        }
    }

    /// Bit-length of the entropy this variant maps to.
    #[must_use]
    pub const fn entropy_bits(self) -> usize {
        // BIP39 §3 — entropy_bits = (word_count * 11) - checksum_bits,
        // where checksum_bits = entropy_bits / 32. Rearranged:
        // entropy_bits = (word_count * 32) / 3 rounded down to multiples
        // of 32. Hardcoded for clarity.
        match self {
            Self::Words12 => 128,
            Self::Words15 => 160,
            Self::Words18 => 192,
            Self::Words21 => 224,
            Self::Words24 => 256,
        }
    }
}

/// Generate a fresh BIP39 mnemonic of the requested length.
///
/// Returns the canonical space-separated string (lowercase, English wordlist).
/// Entropy comes from the OS CSPRNG; calling this twice with the same input
/// will, with overwhelming probability, return two different mnemonics.
///
/// # Errors
///
/// - [`OnchainError::Rng`] when the OS CSPRNG refuses to produce entropy.
///   Only reachable on a catastrophically misconfigured system; we surface
///   it as a typed error instead of panicking, and no partial mnemonic is
///   ever returned.
/// - [`OnchainError::Mnemonic`] if the underlying `bip39` crate rejects the
///   freshly generated entropy. Statically unreachable — `entropy_bits`
///   only ever yields BIP39-legal lengths — but kept so a future change to
///   the table cannot turn into a panic.
pub fn generate_mnemonic(words: MnemonicWordCount) -> Result<String, OnchainError> {
    let entropy_bytes = words.entropy_bits() / 8;
    // `Zeroizing` so the raw entropy is wiped when this function returns.
    // Without it the 16-32 seed bytes stay in freed heap memory and can
    // resurface in a crash dump, in pagefile.sys, or in hiberfil.sys —
    // and whoever recovers them recomputes the checksum themselves and
    // owns the wallet. Defence in depth: the mnemonic still crosses the
    // FFI boundary as a String we cannot wipe, but this copy is free to
    // eliminate.
    let mut entropy = Zeroizing::new(vec![0u8; entropy_bytes]);
    // Use OsRng directly rather than `rand::rng()`'s ThreadRng wrapper.
    // ThreadRng relies on Rust thread-local storage that the JVM threads
    // crossing the UniFFI boundary do not always have initialised — the
    // first invocation through JNI can manifest as "invalid memory
    // access". OsRng is stateless and goes straight to getrandom.
    rand::TryRngCore::try_fill_bytes(&mut rand::rngs::OsRng, entropy.as_mut_slice()).map_err(
        |e| OnchainError::Rng(format!("OS RNG unavailable while generating mnemonic: {e}")),
    )?;

    let mnemonic = Mnemonic::from_entropy(&entropy)?;
    Ok(mnemonic.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn word_count_round_trips_through_from_count() {
        for &n in &[12_u32, 15, 18, 21, 24] {
            let parsed = MnemonicWordCount::from_count(n).unwrap();
            assert_eq!(u32::try_from(parsed.count()).unwrap(), n);
        }
    }

    #[test]
    fn from_count_rejects_unsupported_lengths() {
        for &bad in &[0_u32, 11, 13, 23, 25, 100] {
            let result = MnemonicWordCount::from_count(bad);
            assert!(matches!(result, Err(OnchainError::DerivationPath(_))));
        }
    }

    #[test]
    fn entropy_bits_match_bip39_table() {
        assert_eq!(MnemonicWordCount::Words12.entropy_bits(), 128);
        assert_eq!(MnemonicWordCount::Words15.entropy_bits(), 160);
        assert_eq!(MnemonicWordCount::Words18.entropy_bits(), 192);
        assert_eq!(MnemonicWordCount::Words21.entropy_bits(), 224);
        assert_eq!(MnemonicWordCount::Words24.entropy_bits(), 256);
    }

    #[test]
    fn generated_12_word_mnemonic_has_12_words() {
        let phrase = generate_mnemonic(MnemonicWordCount::Words12).unwrap();
        assert_eq!(phrase.split_whitespace().count(), 12);
    }

    #[test]
    fn generated_24_word_mnemonic_has_24_words() {
        let phrase = generate_mnemonic(MnemonicWordCount::Words24).unwrap();
        assert_eq!(phrase.split_whitespace().count(), 24);
    }

    #[test]
    fn generated_mnemonic_is_valid_bip39() {
        // Round-trip: a freshly generated phrase must parse back as a
        // valid BIP39 mnemonic (checksum included).
        for words in [
            MnemonicWordCount::Words12,
            MnemonicWordCount::Words15,
            MnemonicWordCount::Words18,
            MnemonicWordCount::Words21,
            MnemonicWordCount::Words24,
        ] {
            let phrase = generate_mnemonic(words).unwrap();
            let parsed = Mnemonic::parse_normalized(&phrase).unwrap();
            assert_eq!(parsed.word_count(), words.count());
        }
    }

    #[test]
    fn generated_words_are_lowercase_ascii() {
        let phrase = generate_mnemonic(MnemonicWordCount::Words12).unwrap();
        for word in phrase.split_whitespace() {
            assert!(
                word.chars().all(|c| c.is_ascii_lowercase()),
                "non-lowercase token in mnemonic: {word:?}"
            );
        }
    }

    #[test]
    fn many_calls_produce_distinct_mnemonics() {
        // Birthday-bound on 128-bit entropy is astronomically far from
        // anything we can hit in 100 calls; collisions here would
        // indicate `OsRng` is broken or constant-seeded.
        let mut seen = HashSet::new();
        for _ in 0..100 {
            let phrase = generate_mnemonic(MnemonicWordCount::Words12).unwrap();
            assert!(
                seen.insert(phrase),
                "two identical 12-word mnemonics in 100 calls"
            );
        }
    }

    #[test]
    fn entropy_round_trips_without_bit_loss() {
        // Deterministic (zero flakiness): the entropy recovered from a
        // generated phrase must be EXACTLY entropy_bits/8 bytes — no
        // truncation — and re-encoding it must reproduce the same
        // phrase, which is only true if every entropy bit and the
        // checksum survived the round trip.
        for words in [
            MnemonicWordCount::Words12,
            MnemonicWordCount::Words15,
            MnemonicWordCount::Words18,
            MnemonicWordCount::Words21,
            MnemonicWordCount::Words24,
        ] {
            let phrase = generate_mnemonic(words).unwrap();
            let entropy = Mnemonic::parse_normalized(&phrase).unwrap().to_entropy();
            assert_eq!(entropy.len(), words.entropy_bits() / 8);
            assert_eq!(
                Mnemonic::from_entropy(&entropy).unwrap().to_string(),
                phrase
            );
        }
    }

    #[test]
    fn bip39_official_test_vector_still_holds() {
        // Pins the `bip39` crate's entropy -> phrase mapping (checksum
        // included) against the canonical trezor/python-mnemonic vector.
        // Watches the library, not the RNG: a dependency bump that
        // changed the mapping would silently produce phrases that other
        // wallets cannot restore.
        assert_eq!(
            Mnemonic::from_entropy(&[0x7f; 16]).unwrap().to_string(),
            "legal winner thank year wave sausage worth useful legal winner thank yellow"
        );
    }

    #[allow(clippy::cast_precision_loss)]
    #[test]
    fn entropy_bit_balance_is_sane() {
        // 512 x 128 bit = 65_536 bits. Expected fraction of ones 0.5,
        // binomial sd = sqrt(0.25/65536) ~ 0.00195, so the [0.45, 0.55]
        // window sits ~25 sigma out: P(spurious failure) is far below
        // 1e-100. Only a catastrophically biased RNG (stuck bits, a
        // constant, a truncated fill) can trip this.
        let mut ones = 0u64;
        let mut total_bits = 0u64;
        for _ in 0..512 {
            let phrase = generate_mnemonic(MnemonicWordCount::Words12).unwrap();
            let entropy = Mnemonic::parse_normalized(&phrase).unwrap().to_entropy();
            ones += entropy
                .iter()
                .map(|b| u64::from(b.count_ones()))
                .sum::<u64>();
            total_bits += entropy.len() as u64 * 8;
        }
        let fraction = ones as f64 / total_bits as f64;
        assert!(
            (0.45..=0.55).contains(&fraction),
            "bit balance {fraction} outside [0.45, 0.55] — RNG is biased"
        );
    }

    #[allow(clippy::cast_precision_loss)]
    #[test]
    fn entropy_byte_distribution_is_sane() {
        // 1024 x 16 = 16_384 bytes over 256 bins, 64 expected per bin.
        // Under a uniform RNG the statistic follows chi2(255): mean 255,
        // sd ~22.6, so the 500 threshold is ~11 sigma (P < 1e-20). It
        // catches dead bins / stuck bytes, not micro-bias — deliberately
        // loose so CI never flakes.
        let mut bins = [0u64; 256];
        for _ in 0..1024 {
            let phrase = generate_mnemonic(MnemonicWordCount::Words12).unwrap();
            for b in Mnemonic::parse_normalized(&phrase).unwrap().to_entropy() {
                bins[b as usize] += 1;
            }
        }
        let expected = 16_384.0 / 256.0;
        let chi_square: f64 = bins
            .iter()
            .map(|&observed| {
                let delta = observed as f64 - expected;
                delta * delta / expected
            })
            .sum();
        assert!(
            chi_square < 500.0,
            "byte-distribution chi-square {chi_square} too high — RNG output is not uniform"
        );
    }

    #[test]
    fn generated_mnemonic_seeds_a_working_account() {
        // Integration-shaped sanity: the freshly generated phrase must
        // be acceptable to OnchainAccount::from_mnemonic and produce
        // a derivable taproot address.
        use crate::OnchainAccount;
        use bhodl_core::Network;

        let phrase = generate_mnemonic(MnemonicWordCount::Words12).unwrap();
        let account = OnchainAccount::from_mnemonic(Network::Signet, &phrase, "").unwrap();
        let _ = account.peek_taproot_address(0).unwrap();
    }
}
