package io.bhodl.desktop.wizard

import kotlin.random.Random

/**
 * Setup-wizard quiz built on top of a freshly generated BIP39 mnemonic.
 *
 * The challenge picks `count` distinct word positions from the mnemonic
 * (1-based, kept sorted ascending), and asks the user to type each
 * position back. The intent is purely UX: we want the user to actually
 * write the phrase down before continuing, not just click through.
 *
 * Rationale for keeping this as pure Kotlin (no Compose, no UniFFI):
 * - Trivial to unit-test with `kotlin.test` and a deterministic `Random`.
 * - The Compose screen consumes the same `MnemonicChallenge` value as
 *   any future Android / iOS screen will — the logic moves once when
 *   we promote it to `shared/nonNativeMain`.
 */
public data class MnemonicChallenge(
    /** Original mnemonic the challenge was built from. */
    val mnemonic: String,
    /** 1-based positions the user must answer, sorted ascending. */
    val positions: List<Int>,
) {
    init {
        require(positions.isNotEmpty()) { "challenge must ask for at least one word" }
        require(positions == positions.sorted()) { "positions must be sorted ascending" }
        require(positions.toSet().size == positions.size) { "positions must be distinct" }
        val totalWords = mnemonic.split(WORD_DELIMITER_REGEX).count { it.isNotEmpty() }
        require(totalWords > 0) { "mnemonic must not be empty" }
        require(positions.all { it in 1..totalWords }) {
            "positions must be in 1..$totalWords"
        }
    }

    /** Audit 2026-10 — never print the seed (logs, crash dumps). */
    override fun toString(): String = "MnemonicChallenge(mnemonic=<redacted>, positions=$positions)"

    /** Words of the mnemonic, 1-indexed for caller convenience. */
    private val wordsByPosition: Map<Int, String> by lazy {
        mnemonic.split(WORD_DELIMITER_REGEX)
            .filter { it.isNotEmpty() }
            .mapIndexed { idx, word -> (idx + 1) to word }
            .toMap()
    }

    /**
     * Expected answer for a given position. Throws if the position is
     * not part of [positions] — callers should drive the UI off
     * [positions] directly.
     */
    public fun expectedAt(position: Int): String {
        require(position in positions) { "$position is not part of this challenge" }
        return wordsByPosition.getValue(position)
    }

    /**
     * Verify the user's answers.
     *
     * `answers` is a map from challenge position to the user's typed
     * value. Comparison is case-insensitive and whitespace-trimmed —
     * BIP39 words are always lowercase ASCII, so we accept anything that
     * normalises to the right token.
     */
    public fun verify(answers: Map<Int, String>): VerificationResult {
        val missing = positions.filter { it !in answers }
        if (missing.isNotEmpty()) {
            return VerificationResult.MissingAnswers(missing)
        }
        val wrong = positions.filter { pos ->
            val expected = wordsByPosition.getValue(pos)
            val provided = answers.getValue(pos).trim().lowercase()
            provided != expected
        }
        return if (wrong.isEmpty()) {
            VerificationResult.AllCorrect
        } else {
            VerificationResult.Wrong(wrong)
        }
    }

    public companion object {
        /**
         * Default number of words the wizard quiz asks back.
         *
         * Security review — 4 of 12 words: a 3-word quiz let a user
         * who only noted the quizzed words pass while missing 9 of
         * 12; each extra position raises the cost of an incomplete
         * backup going undetected.
         */
        public const val DEFAULT_CHALLENGE_COUNT: Int = 4

        /**
         * Build a fresh [`MnemonicChallenge`] over `mnemonic`.
         *
         * `count` distinct positions are chosen uniformly at random; the
         * caller-supplied `random` is used so tests can pin the
         * selection. Throws if `count` is non-positive or larger than
         * the number of mnemonic words.
         */
        public fun create(
            mnemonic: String,
            count: Int = DEFAULT_CHALLENGE_COUNT,
            random: Random = Random.Default,
        ): MnemonicChallenge {
            require(count > 0) { "count must be positive (got $count)" }
            val totalWords = mnemonic.split(WORD_DELIMITER_REGEX)
                .count { it.isNotEmpty() }
            require(count <= totalWords) {
                "count ($count) cannot exceed mnemonic length ($totalWords)"
            }

            val positions = generateSequence { random.nextInt(1, totalWords + 1) }
                .distinct()
                .take(count)
                .sorted()
                .toList()
            return MnemonicChallenge(mnemonic, positions)
        }

        private val WORD_DELIMITER_REGEX = Regex("\\s+")
    }
}

/**
 * Outcome of [`MnemonicChallenge.verify`].
 */
public sealed class VerificationResult {
    /** Every challenged position was answered with the expected word. */
    public data object AllCorrect : VerificationResult()

    /** One or more answers were wrong; carries the offending positions. */
    public data class Wrong(val incorrectPositions: List<Int>) : VerificationResult()

    /** The caller did not provide an answer for one or more positions. */
    public data class MissingAnswers(val missingPositions: List<Int>) : VerificationResult()
}
