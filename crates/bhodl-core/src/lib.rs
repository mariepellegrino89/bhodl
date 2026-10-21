//! Shared primitives for BHODL.
//!
//! Minimal dependencies, no async runtime: this crate is meant to be embedded
//! in the `UniFFI` exports and pulled by every other BHODL crate without
//! propagating chain-specific transitive dependencies.

#![deny(unsafe_code)]

mod amount;
mod direction;
mod error;
mod key_derivation;
mod layer;
mod network;

pub use amount::{Amount, AmountError};
pub use direction::Direction;
pub use error::BhodlError;
pub use key_derivation::{
    DERIVED_KEY_LEN, DEVICE_SECRET_LEN, KDF_MAX_M_COST_KIB, KDF_MAX_P_COST, KDF_MAX_T_COST,
    KdfParams, KeyDerivationError, MIN_NEW_PIN_LEN, MIN_PASSPHRASE_LEN, SALT_LEN,
    combine_with_device_secret, derive_key_from_pin, derive_key_from_pin_with_params,
    generate_salt, validate_new_pin, validate_new_secret,
};
pub use layer::Layer;
pub use network::Network;
