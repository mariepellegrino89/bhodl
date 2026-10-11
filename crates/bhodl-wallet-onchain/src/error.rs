use thiserror::Error;

/// Failure modes of the on-chain wallet primitives.
///
/// `bip39` and `bitcoin` errors are wrapped with `#[from]` so callers can
/// use `?` directly. The variants are `#[non_exhaustive]` so adding new
/// failure cases (e.g. for PSBT building in future commits) does not break
/// downstream `match` arms.
#[derive(Debug, Error)]
#[non_exhaustive]
pub enum OnchainError {
    #[error("invalid BIP39 mnemonic: {0}")]
    Mnemonic(#[from] bip39::Error),

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

    #[error("invalid PSBT: {0}")]
    InvalidPsbt(String),

    #[error("invalid fee rate: {0}")]
    InvalidFeeRate(String),

    #[error("transaction build failed: {0}")]
    TxBuild(String),

    #[error("PSBT signing failed: {0}")]
    Sign(String),

    #[error("broadcast failed: {0}")]
    Broadcast(String),

    /// The built transaction pays a fee above the absolute-fee guard
    /// (`max(50 000 sat, 5% of the amount sent)`). Rebuild with the
    /// explicit `allow_high_fee` confirmation only after the user
    /// confirmed the fee a second time.
    #[error("fee of {fee_sat} sat exceeds the {limit_sat} sat safety limit")]
    FeeTooHigh { fee_sat: u64, limit_sat: u64 },

    /// The fee estimator returned nothing usable (empty, garbage or
    /// implausibly high). The UI must ask for a manual fee rate.
    #[error("fee estimate unavailable: {0}")]
    FeeEstimateUnavailable(String),

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
