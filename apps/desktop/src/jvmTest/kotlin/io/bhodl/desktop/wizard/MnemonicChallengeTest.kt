package io.bhodl.desktop.wizard

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TWELVE_WORDS =
    "abandon abandon abandon abandon abandon abandon " +
        "abandon abandon abandon abandon abandon about"

class MnemonicChallengeTest {

    // ─── create() ────────────────────────────────────────────────────

    @Test
    fun create_picks_the_requested_number_of_positions() {
        val challenge = MnemonicChallenge.create(TWELVE_WORDS, count = 3, random = Random(42))
        assertEquals(3, challenge.positions.size)
    }

    @Test
    fun create_defaults_to_four_positions() {
        // Security review — the default quiz asks 4 of 12 words back
        // so an incomplete backup is harder to slip past unnoticed.
        assertEquals(4, MnemonicChallenge.DEFAULT_CHALLENGE_COUNT)
        val challenge = MnemonicChallenge.create(TWELVE_WORDS, random = Random(42))
        assertEquals(4, challenge.positions.size)
    }

    @Test
    fun create_yields_distinct_positions() {
        val challenge = MnemonicChallenge.create(TWELVE_WORDS, count = 5, random = Random(7))
        assertEquals(challenge.positions.size, challenge.positions.toSet().size)
    }

    @Test
    fun create_yields_sorted_ascending_positions() {
        val challenge = MnemonicChallenge.create(TWELVE_WORDS, count = 5, random = Random(7))
        assertEquals(challenge.positions.sorted(), challenge.positions)
    }

    @Test
    fun create_yields_positions_in_1_based_range() {
        val challenge = MnemonicChallenge.create(TWELVE_WORDS, count = 5, random = Random(99))
        assertTrue(challenge.positions.all { it in 1..12 })
    }

    @Test
    fun create_can_cover_every_position_when_count_equals_length() {
        val challenge = MnemonicChallenge.create(TWELVE_WORDS, count = 12, random = Random(0))
        assertEquals((1..12).toList(), challenge.positions)
    }

    @Test
    fun create_rejects_zero_count() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge.create(TWELVE_WORDS, count = 0)
        }
    }

    @Test
    fun create_rejects_negative_count() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge.create(TWELVE_WORDS, count = -1)
        }
    }

    @Test
    fun create_rejects_count_larger_than_mnemonic_length() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge.create(TWELVE_WORDS, count = 13)
        }
    }

    @Test
    fun create_with_seeded_rng_is_reproducible() {
        // Determinism contract: same RNG seed must give the same
        // challenge across independent calls. The wizard relies on this
        // to keep the challenge stable across recompositions.
        val a = MnemonicChallenge.create(TWELVE_WORDS, count = 4, random = Random(123))
        val b = MnemonicChallenge.create(TWELVE_WORDS, count = 4, random = Random(123))
        assertEquals(a, b)
    }

    // ─── ctor invariants ─────────────────────────────────────────────

    @Test
    fun direct_ctor_rejects_empty_mnemonic() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge(mnemonic = "", positions = listOf(1))
        }
    }

    @Test
    fun direct_ctor_rejects_empty_positions() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge(mnemonic = TWELVE_WORDS, positions = emptyList())
        }
    }

    @Test
    fun direct_ctor_rejects_unsorted_positions() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge(mnemonic = TWELVE_WORDS, positions = listOf(3, 1, 2))
        }
    }

    @Test
    fun direct_ctor_rejects_duplicate_positions() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge(mnemonic = TWELVE_WORDS, positions = listOf(1, 1, 2))
        }
    }

    @Test
    fun direct_ctor_rejects_position_zero() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge(mnemonic = TWELVE_WORDS, positions = listOf(0, 5))
        }
    }

    @Test
    fun direct_ctor_rejects_position_above_word_count() {
        assertFailsWith<IllegalArgumentException> {
            MnemonicChallenge(mnemonic = TWELVE_WORDS, positions = listOf(1, 13))
        }
    }

    // ─── expectedAt() ────────────────────────────────────────────────

    @Test
    fun expected_at_returns_the_word_at_position() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        assertEquals("abandon", challenge.expectedAt(1))
        assertEquals("about", challenge.expectedAt(12))
    }

    @Test
    fun expected_at_rejects_unchallenged_position() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        assertFailsWith<IllegalArgumentException> {
            challenge.expectedAt(5)
        }
    }

    // ─── verify() ────────────────────────────────────────────────────

    @Test
    fun verify_all_correct_returns_AllCorrect() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        val answers = mapOf(1 to "abandon", 12 to "about")
        assertEquals(VerificationResult.AllCorrect, challenge.verify(answers))
    }

    @Test
    fun verify_one_wrong_returns_Wrong_with_offending_position() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        val answers = mapOf(1 to "abandon", 12 to "ABROAD")
        val result = challenge.verify(answers)
        assertEquals(VerificationResult.Wrong(listOf(12)), result)
    }

    @Test
    fun verify_all_wrong_lists_every_position() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        val result = challenge.verify(mapOf(1 to "wrong1", 12 to "wrong2"))
        assertEquals(VerificationResult.Wrong(listOf(1, 12)), result)
    }

    @Test
    fun verify_is_case_insensitive() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        val answers = mapOf(1 to "ABANDON", 12 to "About")
        assertEquals(VerificationResult.AllCorrect, challenge.verify(answers))
    }

    @Test
    fun verify_trims_surrounding_whitespace() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        val answers = mapOf(1 to "  abandon  ", 12 to "\tabout\n")
        assertEquals(VerificationResult.AllCorrect, challenge.verify(answers))
    }

    @Test
    fun verify_missing_answer_returns_MissingAnswers() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        val result = challenge.verify(mapOf(1 to "abandon"))
        assertEquals(VerificationResult.MissingAnswers(listOf(12)), result)
    }

    @Test
    fun verify_no_answers_at_all_lists_every_position_as_missing() {
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 7, 12),
        )
        val result = challenge.verify(emptyMap())
        assertEquals(VerificationResult.MissingAnswers(listOf(1, 7, 12)), result)
    }

    @Test
    fun verify_extra_answers_are_ignored() {
        // The user typed an answer for position 5 even though the
        // challenge did not ask for it. The verifier must not reward
        // (or penalise) extras.
        val challenge = MnemonicChallenge(
            mnemonic = TWELVE_WORDS,
            positions = listOf(1, 12),
        )
        val answers = mapOf(1 to "abandon", 5 to "abandon", 12 to "about")
        assertEquals(VerificationResult.AllCorrect, challenge.verify(answers))
    }

    @Test
    fun mnemonic_with_extra_whitespace_is_tolerated() {
        // Defence-in-depth: the upstream generator emits single-space-
        // separated phrases, but if a future caller passes a multi-space
        // phrase we should still tokenise correctly.
        val sloppy = "  abandon\tabandon  abandon abandon abandon abandon " +
            "abandon abandon abandon abandon abandon about\n"
        val challenge = MnemonicChallenge(mnemonic = sloppy, positions = listOf(12))
        assertEquals("about", challenge.expectedAt(12))
    }
}
