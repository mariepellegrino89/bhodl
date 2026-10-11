package io.bhodl.desktop.ui.money

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Security review (item 13) — signed amounts may not bypass the privacy
 * mode. A hand-rolled sign (`if (x > 0) "+"`, `"+${fmt(x)}"`) or a
 * green / red colour picked from the value reveals the direction of a
 * masked amount; UI code must use `amountSign` / `signedAmountColor`
 * (ui/AmountFormat.kt), which go neutral while amounts are hidden.
 *
 * Percentages are not amounts and are skipped. Exceptions live in
 * [ALLOWLIST] with the reason.
 */
class SignedAmountScanTest {

    private val skippedFiles = setOf(
        "ui/AmountFormat.kt",
        "ui/money/BitcoinAmountFormat.kt",
        "ui/money/Money.kt",
        "ui/money/AmountMask.kt",
    )

    /** `file suffix` + `line fragment` → why the line is fine. */
    private val ALLOWLIST: Map<Pair<String, String>, String> = mapOf(
        ("screens/BumpFeeScreen.kt" to "fmtSigned") to
            "Fee-bump confirmation: rendered inside RevealAmounts (real amounts by design).",
        ("screens/SendScreen.kt" to "feeDelta") to
            "Send tab: rendered inside RevealAmounts (real amounts by design).",
        ("screens/CoreCharts.kt" to "totalIn") to
            "Constant '+' on the inflow total: the sign is the label, not data-dependent.",
        ("screens/CoreCharts.kt" to "totalOut") to
            "Constant '−' on the outflow total: not data-dependent.",
        ("screens/LollipopChart.kt" to "amountSat") to
            "Expense chart: every row is an outflow, constant '−'.",
        ("screens/TrackingHistoryTab.kt" to "totals.outgoingSat") to
            "Constant '−' on the outgoing total: not data-dependent.",
        ("screens/TrackingKpi.kt" to "kpis.outflowSat") to
            "Constant '−' on the outflow KPI: not data-dependent.",
        ("screens/TrackingHistoryTab.kt" to "PaymentDirection.OUTGOING -> \"−\"") to
            "Direction glyph next to the 'Received / Sent' text label that already states it.",
        ("screens/TrackingHistoryTab.kt" to "totals.incomingSat") to
            "Constant '+' on the incoming total: not data-dependent.",
        ("screens/TrackingKpi.kt" to "kpis.inflowSat") to
            "Constant '+' on the inflow KPI: not data-dependent.",
        ("screens/TrackingHistoryTab.kt" to "PaymentDirection.INCOMING -> \"+\"") to
            "Direction glyph next to the 'Received / Sent' text label that already states it.",
        ("screens/TransactionDetailsScreen.kt" to "net > 0") to
            "Colour of the 'Received / Sent' line whose words already state the direction.",
    )

    private val patterns = listOf(
        // when-branch / if-else picking a sign glyph: -> "+", ) "−", else "-"
        Regex("""(->|\)|else)\s*"[+−-]""""),
        // a sign glued to a formatted value: "+${fmt(x)}", "−${...}"
        Regex(""""[+−]\$\{"""),
        // a green / red colour chosen by comparing a value with zero
        Regex("""[<>]=?\s*0(L|\.0|u|UL)?\b[^"]*(->|\))\s*BhodlTheme\.colors\.financial(Positive|Negative)"""),
    )

    private val percentage = Regex("""%|[pP]ct|Pp\b|[pP]ercent""")

    private fun sourceRoot(): File =
        listOf(File("src/jvmMain/kotlin"), File("apps/desktop/src/jvmMain/kotlin"))
            .firstOrNull { it.isDirectory } ?: fail("jvmMain sources not found")

    @Test
    fun `signed amounts take their sign and colour from the privacy-aware helpers`() {
        val root = sourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (skippedFiles.any { rel.endsWith(it) }) return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                if (percentage.containsMatchIn(line)) return@forEachIndexed
                if (patterns.none { it.containsMatchIn(line) }) return@forEachIndexed
                val allowed = ALLOWLIST.keys.any { (suffix, fragment) -> rel.endsWith(suffix) && fragment in line }
                if (!allowed) offenders += "$rel:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "Signed amounts must use amountSign / signedAmountColor (privacy mode), " +
                "or add an ALLOWLIST entry with a reason:\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the scan catches the patterns it is meant to catch`() {
        val bad = listOf(
            """    sat > 0L -> "+${'$'}{column.bare(sat)}"""",
            """    val sign = if (diff >= 0.0) "+" else """"",
            """        netSat > 0L -> BhodlTheme.colors.financialPositive""",
        )
        bad.forEach { line -> assertTrue(patterns.any { it.containsMatchIn(line) }, line) }
        val ok = """    val sign = io.bhodl.desktop.ui.amountSign(netSat)"""
        assertTrue(patterns.none { it.containsMatchIn(ok) })
    }
}
