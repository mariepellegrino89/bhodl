package io.bhodl.desktop.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (rec 3) — EN and IT string resources stay in lock
 * step: same key set, no duplicates, no empty values, same format
 * placeholders. A key missing in IT silently falls back to English at
 * runtime, which is how English text leaked into the Italian app.
 */
class StringsParityTest {

    private val en = I18nResources.entries("values")
    private val it = I18nResources.entries("values-it")

    @Test
    fun `EN and IT have exactly the same keys`() {
        val enKeys = en.map { it.key }.toSet()
        val itKeys = it.map { it.key }.toSet()
        val missingInIt = (enKeys - itKeys).sorted()
        val missingInEn = (itKeys - enKeys).sorted()
        assertTrue(
            missingInIt.isEmpty() && missingInEn.isEmpty(),
            "Missing in values-it: $missingInIt\nMissing in values: $missingInEn",
        )
    }

    @Test
    fun `no key is defined twice`() {
        for ((dir, list) in listOf("values" to en, "values-it" to it)) {
            val dupes = list.groupBy { it.key }.filterValues { it.size > 1 }
                .map { (k, v) -> "$k in ${v.map { e -> e.file }}" }
            assertTrue(dupes.isEmpty(), "Duplicate keys in $dir: $dupes")
        }
    }

    @Test
    fun `no empty values`() {
        val empty = (en + it).filter { it.value.isBlank() }.map { "${it.file}: ${it.key}" }
        assertTrue(empty.isEmpty(), "Empty values: $empty")
    }

    @Test
    fun `format placeholders match between EN and IT`() {
        val itByKey = it.associateBy { it.key }
        val mismatches = en.mapNotNull { e ->
            val other = itByKey[e.key] ?: return@mapNotNull null
            val a = I18nResources.formatArgs(e.value)
            val b = I18nResources.formatArgs(other.value)
            if (a == b) null else "${e.key}: EN $a vs IT $b"
        }
        assertTrue(mismatches.isEmpty(), "Placeholder mismatches:\n" + mismatches.joinToString("\n"))
    }

    @Test
    fun `multiple placeholders are positional`() {
        // "%s %s" cannot be reordered by a translation; "%1$s %2$s" can.
        val bad = (en + it).filter { e ->
            val args = I18nResources.formatArgs(e.value)
            args.size > 1 && args.any { !it.contains('$') }
        }.map { "${it.file}: ${it.key}" }
        assertTrue(bad.isEmpty(), "Use positional %1\$s args: $bad")
    }
}
