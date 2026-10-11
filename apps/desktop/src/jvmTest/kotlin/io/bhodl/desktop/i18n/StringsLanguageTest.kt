package io.bhodl.desktop.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (rec 3) — heuristics against mixed language: the
 * review found Italian in the English app ("Ultimo sync", "Categoria",
 * "Entrate/Uscite") and English in the Italian one (untranslated
 * copies). Not a grammar check — it catches the two real failure modes:
 *  - an IT value that is a verbatim copy of a long EN sentence;
 *  - common words of the other language inside a value.
 */
class StringsLanguageTest {

    private val en = I18nResources.entries("values")
    private val it = I18nResources.entries("values-it")

    /**
     * Keys whose long IT value may legitimately equal the EN one (proper
     * nouns, technical identifiers). Each needs a reason.
     */
    private val identicalAllowed: Map<String, String> = mapOf(
        "onb_add_watch_xpub" to "Only key-format names (xpub / tpub / vpub / upub), same in every language.",
    )

    /** Italian words that never appear in English UI copy. */
    private val italianMarkers = listOf(
        // Not "per" / "non": both are also English ("per month", "non-zero").
        " della ", " delle ", " degli ", " dello ", " il ", " gli ", " sono ",
        " con ", " nel ", " nella ", " che ", " questo ", " questa ",
        "Ultimo", "Ultima", "Entrate", "Uscite", "Categoria", "Risparmio", "Totale", "Periodo",
        "Saldo", "Impostazioni", "Annulla", "Salva", "Elimina", "oggi", "Nessun",
    )

    /** English words that never appear in Italian UI copy. */
    private val englishMarkers = listOf(
        " the ", " and ", " with ", " your ", " you ", " this ", " is ", " are ", " of ",
        " to ", " from ", " will ", " has ", " have ",
        "Settings", "Cancel", "Delete", "Income", "Expenses", "Savings", "Balance", "Last sync",
    )

    /**
     * Exact (key, marker) pairs that are fine, with the reason —
     * e.g. an English proper noun quoted in an Italian sentence.
     */
    private val markerAllowed: Map<Pair<String, String>, String> = mapOf()

    /** [marker] (trimmed) as a whole word / phrase of [text], case-sensitive. */
    private fun hasWord(text: String, marker: String): Boolean =
        Regex("(?<!\\p{L})" + Regex.escape(marker.trim()) + "(?!\\p{L})").containsMatchIn(text)

    private fun words(s: String) = s.split(Regex("\\s+")).count { w -> w.any(Char::isLetter) }

    /** Strip placeholders so "%1$s" never counts as a word. */
    private fun plain(s: String) = " " + s.replace(Regex("%\\d*\\$?[sdf]"), "X") + " "

    @Test
    fun `long IT values are translated, not copied from EN`() {
        val enByKey = en.associate { it.key to it.value }
        val copies = it.filter { e ->
            val source = enByKey[e.key] ?: return@filter false
            e.key !in identicalAllowed && words(source) >= 4 && source.trim() == e.value.trim()
        }.map { "${it.key}: “${it.value}”" }
        assertTrue(copies.isEmpty(), "IT values identical to EN:\n" + copies.joinToString("\n"))
    }

    @Test
    fun `EN values contain no Italian words`() {
        val hits = en.flatMap { e ->
            val v = plain(e.value)
            italianMarkers.filter { m -> hasWord(v, m) && (e.key to m) !in markerAllowed }
                .map { m -> "${e.key}: “${e.value}” contains “${m.trim()}”" }
        }
        assertTrue(hits.isEmpty(), "Italian in EN strings:\n" + hits.joinToString("\n"))
    }

    @Test
    fun `IT values contain no English words`() {
        val hits = it.flatMap { e ->
            val v = plain(e.value)
            englishMarkers.filter { m -> hasWord(v, m) && (e.key to m) !in markerAllowed }
                .map { m -> "${e.key}: “${e.value}” contains “${m.trim()}”" }
        }
        assertTrue(hits.isEmpty(), "English in IT strings:\n" + hits.joinToString("\n"))
    }
}
