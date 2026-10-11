use thiserror::Error;

use crate::amount::AmountError;

/// Top-level BHODL error. Each downstream crate may wrap this in its own typed
/// error; this enum only carries cross-cutting failure modes that can occur in
/// any crate (amount math, layer mismatch, network mismatch).
#[derive(Debug, Error)]
#[non_exhaustive]
pub enum BhodlError {
    #[error(transparent)]
    Amount(#[from] AmountError),

    #[error("layer mismatch: expected {expected}, got {actual}")]
    LayerMismatch {
        expected: crate::Layer,
        actual: crate::Layer,
    },

    #[error("network mismatch: expected {expected}, got {actual}")]
    NetworkMismatch {
        expected: crate::Network,
        actual: crate::Network,
    },
}
