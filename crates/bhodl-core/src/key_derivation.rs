//! PIN-based key derivation for the on-disk wallet database.
//!
//! Bridges human-typed PINs to the 32-byte AES-256 key `SQLCipher` consumes
//! through `PRAGMA key`. Argon2id is the modern KDF of choice (winner of
//! the 2015 Password Hashing Competition, recommended by RFC 9106 and
//! OWASP 2024). The output is a raw 32-byte buffer ready to be passed to
//! the persistence layer — it is never serialised, logged, or written to
//! disk. The only artifact that ever leaves memory is the salt.
//!
//! ## Threat model in scope
//!
//! - An attacker who has read access to the wallet DB file (including the
//!   salt) but does not know the PIN must run a full Argon2id attack on
//!   each guess to test it. With the parameters below (m = 64 MiB, t = 3,
//!   p = 4) this means ~0.5–0.8 s per attempt on a 2024 desktop core set,
//!   plus 64 MiB of RAM per parallel attempt — i.e. exhausting a 6-digit
//!   PIN (10^6 candidates) costs on the order of a few CPU-days, trivially
//!   parallelisable on a GPU/cloud budget. PINs
//!   alone are not protection against a determined offline attacker; the
//!   wallet UX must communicate this and pair the PIN with a hardware
//!   keystore wrap (Keystore on Android, Secure Enclave on iOS, OS
//!   keyring on desktop) in the production unlock flow.
//! - An attacker who can read process memory while the wallet is unlocked
//!   bypasses this layer entirely. That is the responsibility of the
//!   `OnchainAccount::Drop` zeroize logic and platform memory hygiene.
//!
//! ## Parameters
//!
//! Argon2id with `m_cost = 65_536 KiB` (64 MiB), `t_cost = 3`, `p_cost = 4`,
//! 32-byte output. Matches the OWASP 2024 SECOND option (the higher one) —
//! the recommended baseline when memory is not constrained. ~500-800 ms
//! unlock time on a 2024 desktop, comfortably below the patience threshold
//! and roughly 4× more expensive to brute-force per attempt than the
//! previous m=19 MiB / t=2 / p=1 baseline we shipped during the spike.
//!
//! Mobile re-evaluation: the same parameters take ~1.5 s on a low-end
//! Android device. We will introduce a runtime cost-class detection
//! (cheap / strong) when the Android target lands.

use argon2::{Algorithm, Argon2, Params, Version};
use rand::TryRngCore;
use thiserror::Error;
use zeroize::Zeroizing;

/// Length of the salt the caller must persist alongside the wallet DB
/// (next to it, not encrypted by it — the salt is required *before* the
/// key can be derived). 16 bytes is the standard Argon2 salt length.
pub const SALT_LEN: usize = 16;

/// Length of the derived key. Matches `bhodl_wallet_onchain::ENCRYPTION_KEY_LEN`
/// — the `SQLCipher` AES-256 key.
pub const DERIVED_KEY_LEN: usize = 32;

/// Argon2id memory cost (kibibytes). 64 MiB. OWASP 2024 strong baseline.
const ARGON2_M_COST_KIB: u32 = 65_536;
/// Argon2id time cost (iterations).
const ARGON2_T_COST: u32 = 3;
/// Argon2id parallelism (lanes).
const ARGON2_P_COST: u32 = 4;

/// Tunable Argon2id parameters used by [`derive_key_from_pin_with_params`].
///
/// Persisted alongside the salt in `wallet.kdf.json` so a future
/// build can re-derive the SAME key for an existing wallet even if
/// the in-source defaults have moved on. The migration path
/// (`PRAGMA rekey` + write fresh params) lives at the FFI layer,
/// which knows about disk paths.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct KdfParams {
    /// Memory cost in kibibytes.
    pub m_cost_kib: u32,
    /// Time cost (iteration count).
    pub t_cost: u32,
    /// Parallelism (lanes).
    pub p_cost: u32,
}

/// Hard ceiling on the Argon2id memory cost accepted from disk (1 GiB).
/// A tampered descriptor asking for `u32::MAX` KiB would otherwise make
/// the allocator abort the process (4 TiB request).
pub const KDF_MAX_M_COST_KIB: u32 = 1_048_576;
/// Hard ceiling on the Argon2id iteration count accepted from disk.
pub const KDF_MAX_T_COST: u32 = 10;
/// Hard ceiling on the Argon2id lane count accepted from disk.
pub const KDF_MAX_P_COST: u32 = 16;

/// Minimum length of a NEW PIN (creation paths only — unlock of an
/// existing wallet only requires a non-empty PIN so that wallets created
/// under an older, laxer policy stay openable).
pub const MIN_NEW_PIN_LEN: usize = 6;

impl KdfParams {
    /// Reject parameter sets read from an untrusted on-disk descriptor
    /// that fall outside `[LEGACY_OWASP_MIN, caps]`.
    ///
    /// The floor stops a downgrade below anything this app ever shipped;
    /// the caps stop a tampered descriptor from turning the next unlock
    /// into an allocation abort (`m_cost = u32::MAX`) or a multi-hour
    /// hang (`t_cost = u32::MAX`).
    ///
    /// # Errors
    ///
    /// [`KeyDerivationError::InvalidParams`] when any field is out of bounds.
    pub fn validate_bounds(&self) -> Result<(), KeyDerivationError> {
        let floor = Self::LEGACY_OWASP_MIN;
        let ok = (floor.m_cost_kib..=KDF_MAX_M_COST_KIB).contains(&self.m_cost_kib)
            && (floor.t_cost..=KDF_MAX_T_COST).contains(&self.t_cost)
            && (floor.p_cost..=KDF_MAX_P_COST).contains(&self.p_cost);
        if ok {
            Ok(())
        } else {
            Err(KeyDerivationError::InvalidParams(format!(
                "m_cost_kib={} t_cost={} p_cost={} outside accepted bounds",
                self.m_cost_kib, self.t_cost, self.p_cost
            )))
        }
    }

    /// What this build emits for every fresh wallet.
    /// Matches OWASP 2024 strong recommendation.
    pub const CURRENT: KdfParams = KdfParams {
        m_cost_kib: ARGON2_M_COST_KIB,
        t_cost: ARGON2_T_COST,
        p_cost: ARGON2_P_COST,
    };

    /// What every wallet created before the OWASP-strong bump used
    /// (m = 19 MiB, t = 2, p = 1). Wallets without a `wallet.kdf.json`
    /// file are assumed to have been created with these.
    pub const LEGACY_OWASP_MIN: KdfParams = KdfParams {
        m_cost_kib: 19_456,
        t_cost: 2,
        p_cost: 1,
    };
}

/// Failures of the key-derivation pipeline.
#[derive(Debug, Error)]
#[non_exhaustive]
pub enum KeyDerivationError {
    /// The PIN was empty. We refuse to derive a key from an empty input
    /// because the resulting key is fully predictable from the salt
    /// alone — same effect as no encryption at all.
    #[error("PIN must not be empty")]
    EmptyPin,
    /// A NEW PIN (wallet / registry creation) does not meet the policy:
    /// ASCII digits only, at least [`MIN_NEW_PIN_LEN`] long.
    #[error("new PIN must be at least {MIN_NEW_PIN_LEN} ASCII digits")]
    WeakPin,
    /// KDF parameters (typically read back from disk) are outside the
    /// accepted bounds — see [`KdfParams::validate_bounds`].
    #[error("invalid KDF parameters: {0}")]
    InvalidParams(String),
    /// `Argon2::hash_password_into` returned an error. In practice this
    /// only happens if the parameter tuning is invalid (statically
    /// unreachable here because the constants are checked at construction)
    /// or the output buffer is the wrong size — also statically known.
    #[error("Argon2id hashing failed: {0}")]
    HashFailed(String),
    /// `getrandom` could not produce entropy. Almost only seen on
    /// catastrophic OS misconfigurations.
    #[error("OS RNG unavailable: {0}")]
    Rng(String),
}

/// Generate a fresh random salt suitable for [`derive_key_from_pin`].
///
/// Uses `rand::rngs::OsRng::try_fill_bytes`, which forwards straight to
/// `getrandom` — the OS CSPRNG (`getrandom(2)` syscall on Linux and
/// Android, `ProcessPrng` on Windows, `getentropy` on macOS/iOS). No
/// userspace buffering and no fallback generator: a failure is returned,
/// never papered over with weak bytes.
///
/// The salt MUST be persisted alongside the wallet DB and supplied as
/// the same value on every subsequent unlock; it is not a secret in
/// itself, but losing it is equivalent to losing the wallet.
pub fn generate_salt() -> Result<[u8; SALT_LEN], KeyDerivationError> {
    random_bytes()
}

/// `N` bytes from the OS CSPRNG (data keys, nonces, recovery codes).
///
/// `OsRng` instead of `rand::rng()` so the call works from JVM threads
/// crossing the `UniFFI` boundary (a `ThreadRng` lives in thread-local
/// storage).
pub fn random_bytes<const N: usize>() -> Result<[u8; N], KeyDerivationError> {
    let mut out = [0u8; N];
    rand::rngs::OsRng
        .try_fill_bytes(&mut out)
        .map_err(|e| KeyDerivationError::Rng(e.to_string()))?;
    Ok(out)
}

/// Enforce the PIN policy for paths that CREATE a new secret store
/// (fresh wallet, fresh device registry). Unlock paths must not call
/// this — they only require a non-empty PIN so that wallets created
/// before the policy existed stay openable.
///
/// # Errors
///
/// - [`KeyDerivationError::EmptyPin`] when `pin` is empty.
/// - [`KeyDerivationError::WeakPin`] when `pin` contains anything but
///   ASCII digits or is shorter than [`MIN_NEW_PIN_LEN`].
pub fn validate_new_pin(pin: &str) -> Result<(), KeyDerivationError> {
    if pin.is_empty() {
        return Err(KeyDerivationError::EmptyPin);
    }
    if pin.len() < MIN_NEW_PIN_LEN || !pin.bytes().all(|b| b.is_ascii_digit()) {
        return Err(KeyDerivationError::WeakPin);
    }
    Ok(())
}

/// Derive a 32-byte AES-256 key from a PIN and a salt using Argon2id.
///
/// The output is deterministic in `(pin, salt)`: the same pair always
/// produces the same key. This is the contract `SQLCipher` relies on —
/// the user types their PIN at unlock, the wallet reads the persisted
/// salt, and the resulting key matches the one used at first creation.
///
/// # Errors
///
/// - [`KeyDerivationError::EmptyPin`] when `pin` is empty.
/// - [`KeyDerivationError::HashFailed`] if Argon2 itself rejects the
///   request (only reachable if the constants here are mis-tuned, which
///   is statically guarded — keep this branch for forward-compatibility).
pub fn derive_key_from_pin(
    pin: &str,
    salt: &[u8; SALT_LEN],
) -> Result<Zeroizing<[u8; DERIVED_KEY_LEN]>, KeyDerivationError> {
    derive_key_from_pin_with_params(pin, salt, &KdfParams::CURRENT)
}

/// Derive a 32-byte AES-256 key with explicit Argon2id parameters.
///
/// The 3-arg [`derive_key_from_pin`] forwards here with [`KdfParams::CURRENT`].
/// Callers that need to reproduce an older derivation (e.g. to unlock a
/// wallet whose `wallet.kdf.json` records [`KdfParams::LEGACY_OWASP_MIN`])
/// pass the persisted params explicitly.
///
/// # Errors
///
/// Same as [`derive_key_from_pin`].
pub fn derive_key_from_pin_with_params(
    pin: &str,
    salt: &[u8; SALT_LEN],
    params: &KdfParams,
) -> Result<Zeroizing<[u8; DERIVED_KEY_LEN]>, KeyDerivationError> {
    if pin.is_empty() {
        return Err(KeyDerivationError::EmptyPin);
    }
    // Defence in depth: callers validate descriptor params on parse,
    // but never hand Argon2 an unbounded cost request.
    params.validate_bounds()?;

    let argon_params = Params::new(
        params.m_cost_kib,
        params.t_cost,
        params.p_cost,
        Some(DERIVED_KEY_LEN),
    )
    .map_err(|e| KeyDerivationError::HashFailed(e.to_string()))?;

    let argon = Argon2::new(Algorithm::Argon2id, Version::V0x13, argon_params);

    let mut output = Zeroizing::new([0u8; DERIVED_KEY_LEN]);
    argon
        .hash_password_into(pin.as_bytes(), salt, output.as_mut())
        .map_err(|e| KeyDerivationError::HashFailed(e.to_string()))?;

    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_SALT: [u8; SALT_LEN] = [
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f,
        0x10,
    ];

    #[test]
    fn derive_is_deterministic_for_same_inputs() {
        let k1 = derive_key_from_pin("123456", &TEST_SALT).unwrap();
        let k2 = derive_key_from_pin("123456", &TEST_SALT).unwrap();
        assert_eq!(k1, k2);
    }

    #[test]
    fn derive_differs_when_pin_differs() {
        let k1 = derive_key_from_pin("123456", &TEST_SALT).unwrap();
        let k2 = derive_key_from_pin("123457", &TEST_SALT).unwrap();
        assert_ne!(k1, k2);
    }

    #[test]
    fn derive_differs_when_salt_differs() {
        let salt2 = [0xff; SALT_LEN];
        let k1 = derive_key_from_pin("123456", &TEST_SALT).unwrap();
        let k2 = derive_key_from_pin("123456", &salt2).unwrap();
        assert_ne!(k1, k2);
    }

    #[test]
    fn empty_pin_is_rejected() {
        let result = derive_key_from_pin("", &TEST_SALT);
        assert!(matches!(result, Err(KeyDerivationError::EmptyPin)));
    }

    #[test]
    fn derived_key_is_not_all_zeros() {
        // Sanity check: the KDF actually fills the output buffer.
        let k = derive_key_from_pin("123456", &TEST_SALT).unwrap();
        assert_ne!(*k, [0u8; DERIVED_KEY_LEN]);
    }

    #[test]
    fn salt_generator_does_not_emit_all_zeros() {
        // Basic smoke test on the OS RNG path. Two consecutive calls
        // must not collide and must not be all-zero.
        let s1 = generate_salt().unwrap();
        let s2 = generate_salt().unwrap();
        assert_ne!(s1, [0u8; SALT_LEN]);
        assert_ne!(s2, [0u8; SALT_LEN]);
        assert_ne!(s1, s2);
    }

    #[test]
    fn legacy_and_current_params_derive_different_keys() {
        // Backward compat invariant: the LEGACY KdfParams must
        // reproduce a wallet created with the old constants. CURRENT
        // is what we use today. They MUST differ — otherwise the
        // migration framework is a no-op and the OWASP-strong bump
        // gave us nothing.
        let legacy =
            derive_key_from_pin_with_params("123456", &TEST_SALT, &KdfParams::LEGACY_OWASP_MIN)
                .unwrap();
        let current =
            derive_key_from_pin_with_params("123456", &TEST_SALT, &KdfParams::CURRENT).unwrap();
        assert_ne!(legacy, current);
    }

    #[test]
    fn derive_key_from_pin_matches_current_params() {
        // The 2-arg wrapper MUST be equivalent to calling the 3-arg
        // form with KdfParams::CURRENT — otherwise existing wallets
        // created via the wrapper would be unrecoverable through the
        // explicit-params path used by the migration framework.
        let via_wrapper = derive_key_from_pin("123456", &TEST_SALT).unwrap();
        let via_explicit =
            derive_key_from_pin_with_params("123456", &TEST_SALT, &KdfParams::CURRENT).unwrap();
        assert_eq!(via_wrapper, via_explicit);
    }

    #[test]
    fn legacy_params_match_pre_hardening_constants() {
        // Pin the LEGACY values so a future refactor cannot
        // accidentally renumber them — wallets that pre-date the
        // hardening commit assume m=19_456, t=2, p=1.
        assert_eq!(KdfParams::LEGACY_OWASP_MIN.m_cost_kib, 19_456);
        assert_eq!(KdfParams::LEGACY_OWASP_MIN.t_cost, 2);
        assert_eq!(KdfParams::LEGACY_OWASP_MIN.p_cost, 1);
    }

    #[test]
    fn argon2_parameters_match_owasp_2024_strong_baseline() {
        // Pin the constants so an accidental downgrade is loud. OWASP
        // Password Storage Cheat Sheet (2024) recommends Argon2id with
        // m >= 64 MiB, t >= 3, p >= 4 when memory is not constrained.
        // Compared exactly so a downward change fails CI loudly even
        // though clippy would otherwise flag a `>=` against a const
        // as `assert!(true)`.
        assert_eq!(ARGON2_M_COST_KIB, 65_536, "memory cost must be 64 MiB");
        assert_eq!(ARGON2_T_COST, 3, "iteration count must be exactly 3");
        assert_eq!(ARGON2_P_COST, 4, "parallelism must be exactly 4");
    }

    #[test]
    fn validate_bounds_accepts_shipped_param_sets() {
        KdfParams::CURRENT.validate_bounds().unwrap();
        KdfParams::LEGACY_OWASP_MIN.validate_bounds().unwrap();
    }

    #[test]
    fn validate_bounds_rejects_dos_and_downgrade_params() {
        let huge_m = KdfParams {
            m_cost_kib: u32::MAX,
            ..KdfParams::CURRENT
        };
        let huge_t = KdfParams {
            t_cost: u32::MAX,
            ..KdfParams::CURRENT
        };
        let huge_p = KdfParams {
            p_cost: 1_000,
            ..KdfParams::CURRENT
        };
        let weak = KdfParams {
            m_cost_kib: 8,
            t_cost: 1,
            p_cost: 1,
        };
        for bad in [huge_m, huge_t, huge_p, weak] {
            assert!(matches!(
                bad.validate_bounds(),
                Err(KeyDerivationError::InvalidParams(_))
            ));
            // derive refuses before touching Argon2 (no 4 TiB alloc).
            assert!(matches!(
                derive_key_from_pin_with_params("123456", &TEST_SALT, &bad),
                Err(KeyDerivationError::InvalidParams(_))
            ));
        }
    }

    #[test]
    fn new_pin_policy_requires_six_ascii_digits() {
        validate_new_pin("123456").unwrap();
        validate_new_pin("12345678901234").unwrap();
        assert!(matches!(
            validate_new_pin(""),
            Err(KeyDerivationError::EmptyPin)
        ));
        assert!(matches!(
            validate_new_pin("12345"),
            Err(KeyDerivationError::WeakPin)
        ));
        assert!(matches!(
            validate_new_pin("12345a"),
            Err(KeyDerivationError::WeakPin)
        ));
        // Non-ASCII digits (Arabic-Indic) are rejected too.
        assert!(matches!(
            validate_new_pin("١٢٣٤٥٦"),
            Err(KeyDerivationError::WeakPin)
        ));
    }

    #[test]
    fn long_unicode_pin_works() {
        // Unicode passphrases must derive a key — Argon2 takes raw
        // bytes, so any UTF-8 string is acceptable.
        let pin = "Très-Sécurisé-😎-1234";
        let _ = derive_key_from_pin(pin, &TEST_SALT).unwrap();
    }
}
