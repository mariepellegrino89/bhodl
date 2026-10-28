use thiserror::Error;

/// Failure modes of the watch-only wallet primitives and the registry.
///
/// `bitcoin` BIP-32 errors are wrapped with `#[from]` so callers can use
/// `?` directly. The enum is `#[non_exhaustive]` so adding new failure
/// cases does not break downstream `match` arms.
#[derive(Debug, Error)]
#[non_exhaustive]
pub enum OnchainError {
    #[error("BIP32 derivation failed: {0}")]
    Bip32(#[from] bitcoin::bip32::Error),

    #[error("derivation path parse failed: {0}")]
    DerivationPath(String),

    #[error("descriptor error: {0}")]
    Descriptor(String),

    #[error("wallet creation failed: {0}")]
    WalletCreate(String),

    #[error("Esplora sync failed: {0}")]
    EsploraSync(String),

    #[error("storage error: {0}")]
    Storage(String),

    #[error("invalid address: {0}")]
    InvalidAddress(String),

    /// Caller supplied a value that violates a domain constraint
    /// (e.g. `due_day` outside 1..=31, an unrecognised `asset_type`,
    /// a NaN/Infinity float bound for a numeric column). Phase 5 raised
    /// these checks from the UI layer down to the registry so cross-FFI
    /// callers (and a future bulk-import path) can never persist garbage.
    #[error("invalid argument: {0}")]
    InvalidArgument(String),

    /// The OS CSPRNG refused to produce entropy (`getrandom` returned an
    /// error). Its own variant rather than the `DerivationPath` channel
    /// it used to borrow: a caller matching on `DerivationPath` treats
    /// the failure as bad user input and may offer a retry, whereas this
    /// is a system-level fault where retrying is pointless and no key
    /// material must be produced.
    #[error("OS RNG unavailable: {0}")]
    Rng(String),
}
