package io.bhodl.desktop.wizard

/**
 * PIN-policy contract for the setup wizard and the unlock screen.
 *
 * The wallet does not put much defensive weight on the PIN itself — the
 * Argon2id KDF in `bhodl-core` provides the actual brute-force resistance.
 * The validator's job is twofold: refuse PINs whose entropy is so low
 * that even Argon2id falls inside a tractable budget (e.g. a 4-digit PIN
 * has 10_000 possibilities, brute-forceable in roughly 10 minutes per
 * `key_derivation.rs` documentation), and refuse non-numeric input until
 * we surface a proper passphrase mode.
 *
 * Pure Kotlin, no Compose. Unit-tested directly.
 */
public object PinValidator {

    /** Minimum PIN length we accept. */
    public const val MIN_LENGTH: Int = 6

    /** Maximum PIN length. Cap exists only to keep UX reasonable; anything
     *  longer should arrive via a future "passphrase" mode. */
    public const val MAX_LENGTH: Int = 32

    /** Validate a single PIN entry. */
    public fun validate(pin: String): PinValidation = when {
        pin.isEmpty() -> PinValidation.Empty
        pin.length < MIN_LENGTH -> PinValidation.TooShort(pin.length, MIN_LENGTH)
        pin.length > MAX_LENGTH -> PinValidation.TooLong(pin.length, MAX_LENGTH)
        !pin.all { it.isDigit() } -> PinValidation.NonNumeric
        else -> PinValidation.Valid
    }

    /** Validate a PIN + confirmation pair. */
    public fun validatePair(pin: String, confirm: String): PinPairValidation {
        val pinResult = validate(pin)
        if (pinResult != PinValidation.Valid) {
            return PinPairValidation.PinInvalid(pinResult)
        }
        if (pin != confirm) {
            return PinPairValidation.Mismatch
        }
        return PinPairValidation.Valid
    }

    /**
     * Length below which a valid numeric PIN earns a caution hint.
     *
     * Security review — 6 digits is 10^6 combinations: with a copy of
     * the wallet file, an offline Argon2id sweep covers that in
     * roughly 14 hours single-threaded (`key_derivation.rs` docs).
     * 8 digits pushes the same sweep into years. We keep 6 as the
     * accepted minimum (existing wallets, UX) but nudge toward 8+.
     */
    public const val STRONG_LENGTH: Int = 8

    /** Non-blocking strength grade for an already-[Valid] PIN. */
    public fun strength(pin: String): PinStrength = when {
        validate(pin) != PinValidation.Valid -> PinStrength.NotRated
        pin.length < STRONG_LENGTH -> PinStrength.Moderate
        else -> PinStrength.Strong
    }
}

/** Outcome of [`PinValidator.validate`]. */
public sealed class PinValidation {
    public data object Valid : PinValidation()
    public data object Empty : PinValidation()
    public data class TooShort(val actual: Int, val minimum: Int) : PinValidation()
    public data class TooLong(val actual: Int, val maximum: Int) : PinValidation()
    public data object NonNumeric : PinValidation()
}

/** Outcome of [`PinValidator.strength`]. */
public enum class PinStrength {
    /** PIN not yet syntactically valid — no grade to show. */
    NotRated,

    /** Valid but short (6–7 digits): tractable offline budget. */
    Moderate,

    /** 8+ digits: offline sweep cost grows past a practical budget. */
    Strong,
}

/** Outcome of [`PinValidator.validatePair`]. */
public sealed class PinPairValidation {
    public data object Valid : PinPairValidation()
    public data class PinInvalid(val reason: PinValidation) : PinPairValidation()
    public data object Mismatch : PinPairValidation()
}
