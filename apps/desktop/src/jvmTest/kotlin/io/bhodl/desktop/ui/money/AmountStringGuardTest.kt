package io.bhodl.desktop.ui.money

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UX review 2026-10 (rec 2) — guard: every bitcoin amount the UI shows
 * goes through [BitcoinAmountFormat] (`rememberAmountFormatter`,
 * `displayAmount`, `Money`, the column / compact formatters). A
 * hand-built `"$x sat"` is what produced "3141592 sat" next to
 * "3,141,592 sat" and BTC next to sats on one page.
 *
 * Scans the jvmMain sources and fails on:
 *  - a template value directly followed by a ` sat` / ` sats` / ` BTC`
 *    unit (`"$x sat"`, `"${y} BTC"`, `"${n}sat"`);
 *  - a unit literal concatenated onto something (`x + " sat"`);
 *  - a printf pattern ending in a unit (`"%,d sat"`);
 *  - a hand-built BIP177 prefix (`"₿$x"`).
 * Fee RATES (`sat/vB`) are a technical unit, not an amount, and pass.
 *
 * Exceptions live in [ALLOWLIST] with the reason; the formatter files
 * themselves are skipped.
 */
class AmountStringGuardTest {

    private val formatterFiles = setOf(
        "ui/money/BitcoinAmountFormat.kt",
        "ui/money/UnitFormat.kt",
        "ui/AmountFormat.kt",
        // Item 13 — the privacy mask ("••••• sat") is part of the formatter.
        "ui/money/AmountMask.kt",
    )

    /** `file suffix` + `line fragment` → why the hand-built string is fine. */
    private val ALLOWLIST: Map<Pair<String, String>, String> = mapOf(
        ("ui/Bip21.kt" to "") to
            "BIP21 URIs carry the amount in BTC by spec (machine format).",
        ("screens/TrackingCsvExport.kt" to "") to
            "CSV export is a machine format: raw integer sats, no grouping, fixed header names.",
    )

    private val patterns = listOf(
        // "$x sat", "${y} sats", "${z}sat", "$n BTC" (not "sat/vB")
        Regex("""\$(\{[^}]*\}|[A-Za-z_][A-Za-z0-9_.]*)\s?(sats?|BTC)\b(?!/)"""),
        // x + " sat" / " BTC"
        Regex("""\+\s*"\s?(sats?|BTC)"(?!/)"""),
        // "%,d sat", "%.8f BTC"
        Regex(""""[^"]*%[-+,.0-9]*[dfs]\s?(sats?|BTC)\b(?!/)"""),
        // "₿$x" / "₿${x}"
        Regex("""₿\$"""),
    )

    private fun sourceRoot(): File {
        val candidates = listOf(
            File("src/jvmMain/kotlin"),
            File("apps/desktop/src/jvmMain/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail("jvmMain sources not found from ${File(".").absolutePath}")
    }

    @Test
    fun `no hand-built bitcoin amount strings outside the formatter`() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (formatterFiles.any { rel.endsWith(it) }) return@forEach
            file.readLines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore(" // ")
                if (patterns.none { it.containsMatchIn(code) }) return@forEachIndexed
                val allowed = ALLOWLIST.keys.any { (suffix, fragment) ->
                    rel.endsWith(suffix) && code.contains(fragment)
                }
                if (!allowed) offenders += "$rel:${i + 1}: $line"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "Hand-built amount strings — use rememberAmountFormatter / displayAmount / Money " +
                "(or add an ALLOWLIST entry with a reason):\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the guard catches the patterns it is meant to catch`() {
        val bad = listOf(
            "text = \"\$orig sat\"",
            "text = \"\${plan.childFeeSat} sat · x\"",
            "val s = n.toString() + \" sats\"",
            "\"%,d sat\".format(x)",
            "\"\$sign₿\$rendered\"",
            "\"\${total} BTC\"",
        )
        val good = listOf(
            "text = \"\$rate sat/vB\"",
            "text = fmt(sat)",
            "Text(\"sat/vB\")",
        )
        bad.forEach { s -> assertTrue(patterns.any { it.containsMatchIn(s) }, "should flag: $s") }
        good.forEach { s -> assertTrue(patterns.none { it.containsMatchIn(s) }, "should pass: $s") }
    }
}
