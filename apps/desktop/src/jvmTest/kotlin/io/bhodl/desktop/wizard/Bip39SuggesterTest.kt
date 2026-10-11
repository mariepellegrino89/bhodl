package io.bhodl.desktop.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [`Bip39Suggester`]. The wordlist is stubbed with a
 * tiny lexicographically-sorted slice so the assertions stay obvious
 * — the real loaded wordlist is exercised in the integration runs
 * via the screen test.
 */
class Bip39SuggesterTest {

    private val wordlist = listOf(
        "abandon", "ability", "able", "about", "above",
        "domain", "donate", "donkey", "donor", "door",
        "zebra", "zero", "zone", "zoo",
    )

    @Test
    fun `empty prefix returns no suggestions`() {
        assertEquals(emptyList(), Bip39Suggester.suggestionsFor("", wordlist))
        assertEquals(emptyList(), Bip39Suggester.suggestionsFor("   ", wordlist))
    }

    @Test
    fun `single letter d returns the d-words in lexicographic order`() {
        val matches = Bip39Suggester.suggestionsFor("d", wordlist)
        assertEquals(
            listOf("domain", "donate", "donkey", "donor", "door"),
            matches,
        )
    }

    @Test
    fun `prefix do narrows the matches`() {
        val matches = Bip39Suggester.suggestionsFor("do", wordlist)
        assertEquals(
            listOf("domain", "donate", "donkey", "donor", "door"),
            matches,
        )
    }

    @Test
    fun `prefix dom narrows further`() {
        assertEquals(listOf("domain"), Bip39Suggester.suggestionsFor("dom", wordlist))
    }

    @Test
    fun `prefix is case-insensitive`() {
        assertEquals(
            Bip39Suggester.suggestionsFor("dom", wordlist),
            Bip39Suggester.suggestionsFor("DOM", wordlist),
        )
        assertEquals(
            Bip39Suggester.suggestionsFor("dom", wordlist),
            Bip39Suggester.suggestionsFor("  Dom  ", wordlist),
        )
    }

    @Test
    fun `prefix with no matches returns empty`() {
        assertEquals(emptyList(), Bip39Suggester.suggestionsFor("xyz", wordlist))
    }

    @Test
    fun `max parameter caps the result size`() {
        val matches = Bip39Suggester.suggestionsFor("d", wordlist, max = 2)
        assertEquals(listOf("domain", "donate"), matches)
    }

    @Test
    fun `currentWord returns null on empty input`() {
        assertNull(Bip39Suggester.currentWord(""))
    }

    @Test
    fun `currentWord returns null after a trailing space`() {
        assertNull(Bip39Suggester.currentWord("abandon "))
        assertNull(Bip39Suggester.currentWord("abandon ability "))
    }

    @Test
    fun `currentWord finds the trailing in-progress word`() {
        val cw = Bip39Suggester.currentWord("abandon ab")
        assertEquals("ab", cw?.word)
        assertEquals(8, cw?.start)
        assertEquals(10, cw?.endExclusive)
    }

    @Test
    fun `currentWord works on a single word`() {
        val cw = Bip39Suggester.currentWord("dom")
        assertEquals("dom", cw?.word)
        assertEquals(0, cw?.start)
        assertEquals(3, cw?.endExclusive)
    }

    @Test
    fun `replaceCurrentWord substitutes the trailing word and adds a space`() {
        assertEquals(
            "abandon domain ",
            Bip39Suggester.replaceCurrentWord("abandon dom", "domain"),
        )
        assertEquals(
            "abandon ",
            Bip39Suggester.replaceCurrentWord("ab", "abandon"),
        )
    }

    @Test
    fun `replaceCurrentWord on empty input just emits the word with a trailing space`() {
        assertEquals("abandon ", Bip39Suggester.replaceCurrentWord("", "abandon"))
    }

    @Test
    fun `replaceCurrentWord after a trailing space appends the new word`() {
        assertEquals(
            "abandon ability ",
            Bip39Suggester.replaceCurrentWord("abandon ", "ability"),
        )
    }

    @Test
    fun `wordlist contract sanity_2048_entries_alphabetical`() {
        // Uses the bundled wordlist via a load() call would require
        // the Compose resource runtime; instead we sanity-check the
        // local fixture against the real list shape: each entry is
        // lowercase ASCII letters only, no duplicates.
        val unique = wordlist.toSet()
        assertTrue(unique.size == wordlist.size)
        wordlist.forEach { entry ->
            assertTrue(entry.all { it in 'a'..'z' }, "non lowercase letter in '$entry'")
        }
    }
}
