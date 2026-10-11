package io.bhodl.desktop.wizard

/**
 * Pure-functional helpers for the BIP-39 word autocomplete on
 * `RestoreMnemonicScreen`.
 *
 * The wordlist itself (2048 English entries) is loaded at runtime
 * from a Compose resource and passed in by the UI layer — not
 * embedded as a Kotlin constant — so it does not bloat the source
 * tree and stays trivially swappable when other languages land.
 */
public object Bip39Suggester {

    /**
     * Return up to [`max`] BIP-39 words that start with [`prefix`],
     * in their canonical lexicographic order. Case-insensitive on
     * the input. Empty / blank prefix returns an empty list — there
     * is no point flooding the UI with all 2048 entries.
     */
    public fun suggestionsFor(
        prefix: String,
        wordlist: List<String>,
        max: Int = 8,
    ): List<String> {
        val normalised = prefix.trim().lowercase()
        if (normalised.isEmpty()) return emptyList()
        // The wordlist is sorted lexicographically by spec, so we
        // could binary-search the lower / upper bounds for a prefix.
        // 2048 entries × 8 results × every keystroke is fast enough
        // that the linear filter stays cheap and stays obvious.
        return wordlist.asSequence()
            .filter { it.startsWith(normalised) }
            .take(max)
            .toList()
    }

    /**
     * Split [`raw`] into whitespace-separated tokens and report the
     * last incomplete word — i.e. the word whose suggestions the UI
     * should show. Returns the position (start index, exclusive end)
     * inside [`raw`] alongside the word so the caller can perform a
     * surgical replacement when the user picks a suggestion.
     *
     * Examples:
     * - `"abandon ab"` → `("ab", 8..10)` — the user is mid-typing.
     * - `"abandon ab "` → `null` — trailing space means nothing to
     *   complete; suggestions UI should disappear.
     * - `""` → `null`.
     *
     * The cursor position is intentionally NOT consulted: an MVP
     * field that suggests for the trailing word is good enough for a
     * 12-word recovery flow. We will revisit when we wire BIP-329
     * label autocomplete (which can edit mid-string).
     */
    public fun currentWord(raw: String): CurrentWord? {
        if (raw.isEmpty() || raw.endsWith(' ') || raw.endsWith('\n') || raw.endsWith('\t')) {
            return null
        }
        // Find the last whitespace before the trailing word.
        var i = raw.length
        while (i > 0 && !raw[i - 1].isWhitespace()) i -= 1
        val word = raw.substring(i)
        if (word.isEmpty()) return null
        return CurrentWord(word = word, start = i, endExclusive = raw.length)
    }

    /**
     * Replace the trailing word in [`raw`] with [`replacement`] and
     * append one trailing space so the user can immediately keep
     * typing the next word. If there is no trailing word (the field
     * is empty or ends in whitespace) the replacement is appended
     * with a leading space when the field is non-empty.
     */
    public fun replaceCurrentWord(raw: String, replacement: String): String {
        val current = currentWord(raw)
        return if (current == null) {
            if (raw.isEmpty()) {
                "$replacement "
            } else {
                "$raw$replacement "
            }
        } else {
            raw.substring(0, current.start) + replacement + " "
        }
    }

    /** The word the autocomplete UI is currently completing. */
    public data class CurrentWord(
        val word: String,
        val start: Int,
        val endExclusive: Int,
    )
}
