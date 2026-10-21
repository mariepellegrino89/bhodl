package io.bhodl.desktop.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PinValidatorTest {

    // ─── validate ──────────────────────────────────────────────────────

    @Test
    fun empty_pin_is_Empty() {
        assertEquals(PinValidation.Empty, PinValidator.validate(""))
    }

    @Test
    fun pin_below_minimum_is_TooShort() {
        val result = PinValidator.validate("12345")
        assertEquals(PinValidation.TooShort(actual = 5, minimum = PinValidator.MIN_LENGTH), result)
    }

    @Test
    fun pin_at_minimum_is_Valid() {
        assertEquals(PinValidation.Valid, PinValidator.validate("123456"))
    }

    @Test
    fun pin_one_above_minimum_is_Valid() {
        assertEquals(PinValidation.Valid, PinValidator.validate("1234567"))
    }

    @Test
    fun pin_at_maximum_is_Valid() {
        val maxPin = "1".repeat(PinValidator.MAX_LENGTH)
        assertEquals(PinValidation.Valid, PinValidator.validate(maxPin))
    }

    @Test
    fun pin_above_maximum_is_TooLong() {
        val tooLong = "1".repeat(PinValidator.MAX_LENGTH + 1)
        val result = PinValidator.validate(tooLong)
        assertEquals(
            PinValidation.TooLong(actual = tooLong.length, maximum = PinValidator.MAX_LENGTH),
            result,
        )
    }

    @Test
    fun pin_with_alpha_char_is_NonNumeric() {
        assertEquals(PinValidation.NonNumeric, PinValidator.validate("12345A"))
    }

    @Test
    fun pin_with_space_is_NonNumeric() {
        assertEquals(PinValidation.NonNumeric, PinValidator.validate("12 456"))
    }

    @Test
    fun pin_with_dash_is_NonNumeric() {
        assertEquals(PinValidation.NonNumeric, PinValidator.validate("123-456"))
    }

    @Test
    fun pin_with_unicode_digit_is_NonNumeric() {
        // Deliberately tightened to ASCII-only (2026-10, device-key
        // binding): the Rust core (`validate_new_secret`) classifies
        // non-ASCII digits as a passphrase and would reject a 6-char one
        // at creation, so the UI must not accept it as a PIN.
        assertEquals(PinValidation.NonNumeric, PinValidator.validate("١٢٣٤٥٦"))
    }

    // ─── passphrase mode ───────────────────────────────────────────────

    @Test
    fun passphrase_mode_requires_twelve_code_points() {
        val p = SecretMode.PASSPHRASE
        assertEquals(PinValidation.Empty, PinValidator.validate("", p))
        assertEquals(
            PinValidation.TooShort(actual = 11, minimum = PinValidator.MIN_PASSPHRASE_LENGTH),
            PinValidator.validate("abcdefghij1", p),
        )
        assertEquals(PinValidation.Valid, PinValidator.validate("abcdefghij12", p))
        // Digits only is fine as long as it is long enough (mirrors Rust).
        assertEquals(PinValidation.Valid, PinValidator.validate("123456789012", p))
        // A 6-digit PIN is NOT a valid passphrase.
        assertTrue(PinValidator.validate("123456", p) is PinValidation.TooShort)
    }

    @Test
    fun passphrase_length_counts_code_points_not_utf16_units() {
        // 11 emoji = 22 UTF-16 units but 11 code points: too short, as in
        // Rust's `chars().count()`.
        val elevenEmoji = "😎".repeat(11)
        assertEquals(
            PinValidation.TooShort(actual = 11, minimum = PinValidator.MIN_PASSPHRASE_LENGTH),
            PinValidator.validate(elevenEmoji, SecretMode.PASSPHRASE),
        )
        assertEquals(
            PinValidation.Valid,
            PinValidator.validate("😎".repeat(12), SecretMode.PASSPHRASE),
        )
    }

    @Test
    fun passphrase_mode_rejects_whitespace_and_overlong_input() {
        val p = SecretMode.PASSPHRASE
        assertEquals(PinValidation.ContainsWhitespace, PinValidator.validate("correct horse battery", p))
        val tooLong = "a".repeat(PinValidator.MAX_PASSPHRASE_LENGTH + 1)
        assertTrue(PinValidator.validate(tooLong, p) is PinValidation.TooLong)
    }

    @Test
    fun passphrase_pair_and_strength() {
        val p = SecretMode.PASSPHRASE
        assertEquals(PinPairValidation.Valid, PinValidator.validatePair("Tr3s-Securise", "Tr3s-Securise", p))
        assertEquals(PinPairValidation.Mismatch, PinValidator.validatePair("Tr3s-Securise", "Tr3s-Securisf", p))
        assertEquals(PinStrength.Strong, PinValidator.strength("Tr3s-Securise", p))
        assertEquals(PinStrength.NotRated, PinValidator.strength("short", p))
    }

    @Test
    fun mode_is_forced_to_passphrase_without_a_keystore() {
        assertEquals(SecretMode.PIN, PinValidator.modeFor(keystoreAvailable = true, preferPassphrase = false))
        assertEquals(SecretMode.PASSPHRASE, PinValidator.modeFor(keystoreAvailable = true, preferPassphrase = true))
        // No keystore: a PIN is never offered, whatever the preference.
        assertEquals(SecretMode.PASSPHRASE, PinValidator.modeFor(keystoreAvailable = false, preferPassphrase = false))
        assertEquals(SecretMode.PASSPHRASE, PinValidator.modeFor(keystoreAvailable = false, preferPassphrase = true))
    }

    // ─── validatePair ──────────────────────────────────────────────────

    @Test
    fun pair_both_valid_and_matching_is_Valid() {
        assertEquals(PinPairValidation.Valid, PinValidator.validatePair("123456", "123456"))
    }

    @Test
    fun pair_with_mismatch_is_Mismatch() {
        assertEquals(PinPairValidation.Mismatch, PinValidator.validatePair("123456", "123457"))
    }

    @Test
    fun pair_with_short_pin_returns_PinInvalid_with_reason() {
        val result = PinValidator.validatePair("123", "123")
        assertTrue(result is PinPairValidation.PinInvalid)
        assertTrue((result as PinPairValidation.PinInvalid).reason is PinValidation.TooShort)
    }

    @Test
    fun pair_with_empty_pin_returns_PinInvalid_with_Empty() {
        val result = PinValidator.validatePair("", "")
        assertTrue(result is PinPairValidation.PinInvalid)
        assertEquals(PinValidation.Empty, (result as PinPairValidation.PinInvalid).reason)
    }

    @Test
    fun pair_with_non_numeric_pin_returns_PinInvalid_with_NonNumeric() {
        val result = PinValidator.validatePair("12345A", "12345A")
        assertTrue(result is PinPairValidation.PinInvalid)
        assertEquals(PinValidation.NonNumeric, (result as PinPairValidation.PinInvalid).reason)
    }

    @Test
    fun pair_with_too_long_pin_returns_PinInvalid_with_TooLong() {
        val tooLong = "1".repeat(PinValidator.MAX_LENGTH + 1)
        val result = PinValidator.validatePair(tooLong, tooLong)
        assertTrue(result is PinPairValidation.PinInvalid)
        assertTrue((result as PinPairValidation.PinInvalid).reason is PinValidation.TooLong)
    }

    @Test
    fun pair_validates_pin_first_then_checks_match() {
        // If the PIN itself is invalid, we report the pin reason rather
        // than masking it as "Mismatch" even when the confirm differs.
        val result = PinValidator.validatePair("123", "456")
        assertTrue(result is PinPairValidation.PinInvalid)
    }

    // ─── strength ──────────────────────────────────────────────────────

    @Test
    fun strength_grades_6_and_7_digit_pins_as_moderate() {
        assertEquals(PinStrength.Moderate, PinValidator.strength("123456"))
        assertEquals(PinStrength.Moderate, PinValidator.strength("1234567"))
    }

    @Test
    fun strength_grades_8_plus_digit_pins_as_strong() {
        assertEquals(PinStrength.Strong, PinValidator.strength("12345678"))
        assertEquals(PinStrength.Strong, PinValidator.strength("1".repeat(PinValidator.MAX_LENGTH)))
    }

    @Test
    fun strength_does_not_rate_invalid_pins() {
        assertEquals(PinStrength.NotRated, PinValidator.strength(""))
        assertEquals(PinStrength.NotRated, PinValidator.strength("123"))
        assertEquals(PinStrength.NotRated, PinValidator.strength("abcdef"))
    }
}
