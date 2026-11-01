//! TEST-ONLY probe for the release-profile panic policy (see the
//! `[profile.release]` comment in the workspace `Cargo.toml`).
//!
//! [`probe_panic`] deliberately panics across the FFI. UniFFI's
//! scaffolding wraps every exported call in `std::panic::catch_unwind`,
//! so with `panic = "unwind"` the panic surfaces on the Kotlin side as a
//! recoverable `InternalException`; with `panic = "abort"` (the previous
//! profile) it would abort the whole JVM process. The Kotlin regression
//! test `PanicContainmentTest` (shared/src/jvmTest) asserts the former —
//! if it ever "passes" by crashing the test JVM, the profile regressed.

/// TEST-ONLY probe for the panic policy: deliberately panics. Not part of the product surface.
#[uniffi::export]
pub(crate) fn probe_panic() {
    panic!("bhodl panic-probe: intentional, testing FFI panic containment");
}
