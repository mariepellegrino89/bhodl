package io.bhodl.desktop.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (rec 3) — no user-facing text hardcoded in Kotlin.
 *
 * Scans jvmMain for string literals handed straight to the UI —
 * `Text("…")` and the usual text-bearing parameters (`text =`,
 * `label =`, `placeholder =`, `contentDescription =`, `title =`, …) —
 * and fails when the literal still contains words once template
 * expressions and technical tokens are removed. Such text bypasses
 * `stringResource` and shows English in the Italian app (or the
 * reverse), which is what the review found.
 *
 * Exceptions: [TECH_TOKENS] (units, standards, identifiers that read
 * the same in every language) and [ALLOWLIST] (file + fragment, with a
 * reason).
 */
class HardcodedUiStringScanTest {

    private val literal = """"((?:[^"\\]|\\.)*)""""
    private val patterns = listOf(
        Regex("""\bText\(\s*$literal"""),
        Regex(
            """\b(text|label|placeholder|contentDescription|title|subtitle|body|message|hint|""" +
                """confirmLabel|dismissLabel|primaryIdle|primaryWorking|supportingText|tooltip|""" +
                """emptyText|caption|headline)\s*=\s*$literal""",
        ),
    )

    /** Words that are the same in EN and IT UI copy. */
    private val TECH_TOKENS = setOf(
        "BTC", "sat", "sats", "vB", "sat/vB", "PSBT", "BIP", "BIP21", "BIP39", "BIP84", "BIP86", "BIP177",
        "BIP329", "BIP78", "xpub", "zpub", "tpub", "vpub", "ypub", "txid", "UTXO", "UTXOs", "RBF", "CPFP",
        "OK", "BHODL", "Tor", "Esplora", "URL", "URI", "ID", "PIN", "QR", "CSV", "JSON", "FIFO", "HIFO",
        "FIRE", "NW", "EUR", "USD", "CHF", "GBP", "JPY", "ISO", "HODL", "PayJoin", "Payjoin", "SegWit",
        "Taproot", "Electrum", "mempool", "space", "Clearnet", "Signet", "Testnet", "Regtest", "Mainnet",
        "BTC/", "kB", "KiB", "MiB", "vs",
    )

    /** `file suffix` + `literal fragment` → reason. */
    private val ALLOWLIST: Map<Pair<String, String>, String> = mapOf(
        ("screens/TrackingKpi.kt" to "skeleton") to
            "`label =` of an infinite-transition animation: a Compose tooling name, never rendered.",
        ("screens/SettingsWalletSections.kt" to "My Wallet") to
            "Fallback wallet NAME stored in the registry (data, not UI copy).",
    )

    private fun hasWords(raw: String): Boolean {
        val stripped = raw
            .replace(Regex("""\$\{[^}]*\}"""), " ")
            .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_.]*"""), " ")
            .replace(Regex("""\\[nt"\\$]"""), " ")
        val words = Regex("""[A-Za-zÀ-ÿ][A-Za-zÀ-ÿ/]+""").findAll(stripped).map { it.value }
        return words.any { it !in TECH_TOKENS && it.trimEnd('/') !in TECH_TOKENS }
    }

    @Test
    fun `no hardcoded user-facing literals in UI code`() {
        val root = I18nResources.sourceRoot()
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val rel = file.relativeTo(root).invariantSeparatorsPath
            file.readLines().forEachIndexed { i, raw ->
                // Collapse template expressions first: `"Δ ${if (x) "+" else ""}…"`
                // must not split into fake literals at the inner quotes.
                var line = raw.trim()
                while (true) {
                    val next = line.replace(Regex("""\$\{[^{}]*\}"""), "\\\$x")
                    if (next == line) break
                    line = next
                }
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                if (line.contains("testTag") && !line.contains("Text(")) return@forEachIndexed
                for (p in patterns) {
                    for (m in p.findAll(line)) {
                        val lit = m.groupValues.last()
                        if (!hasWords(lit)) continue
                        val allowed = ALLOWLIST.keys.any { (suffix, fragment) ->
                            rel.endsWith(suffix) && lit.contains(fragment)
                        }
                        if (!allowed) offenders += "$rel:${i + 1}: \"$lit\""
                    }
                }
            }
        }
        assertTrue(
            offenders.isEmpty(),
            "Hardcoded UI text — move it to composeResources (EN + IT) and use stringResource " +
                "(or add an ALLOWLIST entry with a reason):\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `the scan flags prose and passes technical tokens`() {
        assertTrue(hasWords("Review replacement"))
        assertTrue(hasWords("Bal: \${x}"))
        assertTrue(hasWords("Ultimo sync"))
        assertTrue(!hasWords("\${fmt(x)} · \$role"))
        assertTrue(!hasWords("sat/vB"))
        assertTrue(!hasWords("BTC"))
        assertTrue(!hasWords("—"))
        assertTrue(!hasWords("#"))
    }
}
