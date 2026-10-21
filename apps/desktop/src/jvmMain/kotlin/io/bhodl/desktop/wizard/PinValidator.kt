package io.bhodl.desktop.wizard

/**
 * Unlock-secret policy for the setup wizard. Mirrors
 * `bhodl_core::validate_new_secret` (Rust), which enforces the same rules
 * again when the store is created.
 *
 * Two kinds of secret:
 *  - [SecretMode.PIN] — 6+ ASCII digits. Only acceptable because the
 *    database key also mixes in a 256-bit device secret held by the OS
 *    keystore (Windows DPAPI, macOS Keychain, Linux Secret Service): a
 *    copy of the data directory alone cannot be used to brute-force it.
 *  - [SecretMode.PASSPHRASE] — 12+ characters, letters / digits /
 *    symbols. Optional when a keystore is available; MANDATORY when none
 *    is (headless Linux without a Secret Service), because then the
 *    Argon2id-hashed secret is the only barrier against an offline
 *    attacker — see [modeFor].
 *
 * Whitespace is never part of a secret: the PIN / passphrase fields on
 * every screen drop it as it is typed.
 *
 * Pure Kotlin, no Compose. Unit-tested directly.
 */
public object PinValidator {

    /** Minimum PIN length we accept. Mirrors `MIN_NEW_PIN_LEN`. */
    public const val MIN_LENGTH: Int = 6

    /** Maximum PIN length. Cap exists only to keep UX reasonable. */
    public const val MAX_LENGTH: Int = 32

    /** Minimum passphrase length (Unicode code points). Mirrors `MIN_PASSPHRASE_LEN`. */
    public const val MIN_PASSPHRASE_LENGTH: Int = 12

    /** Maximum passphrase length (Unicode code points). */
    public const val MAX_PASSPHRASE_LENGTH: Int = 128

    /**
     * Secret mode the setup screen must use.
     *
     * No OS keystore → passphrase, whatever the user prefers: never fall
     * back silently to a 6-digit PIN with nothing behind it.
     */
    public fun modeFor(keystoreAvailable: Boolean, preferPassphrase: Boolean): SecretMode =
        if (!keystoreAvailable || preferPassphrase) SecretMode.PASSPHRASE else SecretMode.PIN

    /**
     * Mode inferred from what the user typed, for screens without an
     * explicit PIN / passphrase switch: anything that is not all ASCII
     * digits is a passphrase — exactly how the Rust core classifies it.
     */
    public fun inferMode(secret: String, keystoreAvailable: Boolean): SecretMode =
        modeFor(keystoreAvailable, preferPassphrase = secret.any { it !in '0'..'9' })

    /** Validate a single secret entry in [mode]. */
    public fun validate(pin: String, mode: SecretMode = SecretMode.PIN): PinValidation =
        when (mode) {
            SecretMode.PIN -> validatePin(pin)
            SecretMode.PASSPHRASE -> validatePassphrase(pin)
        }

    private fun validatePin(pin: String): PinValidation = when {
        pin.isEmpty() -> PinValidation.Empty
        pin.length < MIN_LENGTH -> PinValidation.TooShort(pin.length, MIN_LENGTH)
        pin.length > MAX_LENGTH -> PinValidation.TooLong(pin.length, MAX_LENGTH)
        // ASCII only, exactly like the Rust policy: a "PIN" of e.g.
        // Arabic-Indic digits would be classified as a (too short)
        // passphrase by the core and rejected at creation.
        !pin.all { it in '0'..'9' } -> PinValidation.NonNumeric
        else -> PinValidation.Valid
    }

    private fun validatePassphrase(passphrase: String): PinValidation {
        if (passphrase.isEmpty()) return PinValidation.Empty
        if (passphrase.any { it.isWhitespace() }) return PinValidation.ContainsWhitespace
        val length = passphrase.codePointCount(0, passphrase.length)
        return when {
            length < MIN_PASSPHRASE_LENGTH -> PinValidation.TooShort(length, MIN_PASSPHRASE_LENGTH)
            length > MAX_PASSPHRASE_LENGTH -> PinValidation.TooLong(length, MAX_PASSPHRASE_LENGTH)
            else -> PinValidation.Valid
        }
    }

    /** Validate a secret + confirmation pair in [mode]. */
    public fun validatePair(
        pin: String,
        confirm: String,
        mode: SecretMode = SecretMode.PIN,
    ): PinPairValidation {
        val pinResult = validate(pin, mode)
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
     * With the OS-keystore binding a copy of the wallet files is not
     * enough to brute-force the PIN; the hint covers the remaining case
     * (attacker with the files AND access to the user's OS account /
     * keyring), where 10^6 Argon2id guesses are tractable and 8+ digits
     * push the sweep into years.
     */
    public const val STRONG_LENGTH: Int = 8

    /** Non-blocking strength grade for an already-[PinValidation.Valid] secret. */
    public fun strength(pin: String, mode: SecretMode = SecretMode.PIN): PinStrength = when {
        validate(pin, mode) != PinValidation.Valid -> PinStrength.NotRated
        mode == SecretMode.PASSPHRASE -> PinStrength.Strong
        pin.length < STRONG_LENGTH -> PinStrength.Moderate
        else -> PinStrength.Strong
    }
}

/** Which kind of unlock secret a new wallet / registry gets. */
public enum class SecretMode {
    /** 6+ ASCII digits; requires an OS keystore. */
    PIN,

    /** 12+ characters, any non-whitespace. */
    PASSPHRASE,
}

/** Outcome of [`PinValidator.validate`]. */
public sealed class PinValidation {
    public data object Valid : PinValidation()
    public data object Empty : PinValidation()
    public data class TooShort(val actual: Int, val minimum: Int) : PinValidation()
    public data class TooLong(val actual: Int, val maximum: Int) : PinValidation()
    public data object NonNumeric : PinValidation()

    /** Passphrase mode only: spaces / tabs are not allowed. */
    public data object ContainsWhitespace : PinValidation()
}

/** Outcome of [`PinValidator.strength`]. */
public enum class PinStrength {
    /** PIN not yet syntactically valid — no grade to show. */
    NotRated,

    /** Valid but short (6–7 digits). */
    Moderate,

    /** 8+ digits, or any valid passphrase. */
    Strong,
}

/** Outcome of [`PinValidator.validatePair`]. */
public sealed class PinPairValidation {
    public data object Valid : PinPairValidation()
    public data class PinInvalid(val reason: PinValidation) : PinPairValidation()
    public data object Mismatch : PinPairValidation()
}
