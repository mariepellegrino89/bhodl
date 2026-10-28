package io.bhodl.desktop.prices

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * GAP-014 / SEC-117 — every Kotlin call of an FFI function that takes a
 * proxy hands it the Tor setting. The client tests replace the FFI with
 * fakes that ignore the proxy, so this is checked on the source: the
 * proxy-taking functions are read from the Rust FFI surface
 * (`crates/bhodl-uniffi/.../lib.rs`), then each call site in jvmMain
 * must pass `EsploraEndpoint.currentProxy()` (directly or through a
 * local `val` assigned from it), or be a documented pass-through.
 */
class UcTorProxyWiringSecurityTest {

    private data class FfiFn(val kotlinName: String, val arity: Int)

    /** `file suffix` + `argument` → why the pass-through is safe. */
    private val PASS_THROUGH: Map<Pair<String, String>, String> = mapOf(
        ("wallet/WalletPort.kt" to "proxyUrl") to
            "WalletPort.syncWithEsplora forwards its parameter; its callers are checked by this same scan.",
        ("data/BackfillRunner.kt" to "proxyUrl") to
            "BackfillTarget.run receives proxyProvider(), checked in the test below.",
    )

    private fun projectRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "crates/bhodl-uniffi").isDirectory }
            ?: fail("project root not found from ${File("").absolutePath}")

    private fun sourceRoot(): File = File(projectRoot(), "apps/desktop/src/jvmMain/kotlin")

    private fun proxyFunctions(): List<FfiFn> {
        val lib = File(projectRoot(), "crates/bhodl-uniffi/src/commonMain/rust/lib.rs").readText()
        return Regex("""pub fn (\w+)\s*\(([^)]*)\)""").findAll(lib)
            .filter { "proxy" in it.groupValues[2] }
            .map { m ->
                val params = m.groupValues[2].split(',').map { it.trim() }
                    .filter { it.isNotEmpty() && it != "&self" && it != "&mut self" }
                FfiFn(camel(m.groupValues[1]), params.size)
            }
            .distinct()
            .toList()
    }

    private fun camel(snake: String): String {
        val parts = snake.split('_')
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    /** Argument list text after `(` at [open], split on top-level commas. */
    private fun arguments(text: String, open: Int): List<String> {
        var depth = 0
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var i = open
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '(', '[', '{' -> { depth++; if (depth > 1) current.append(c) }
                ')', ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        if (current.isNotBlank()) args += current.toString().trim()
                        return args
                    }
                    current.append(c)
                }
                ',' -> if (depth == 1) { args += current.toString().trim(); current.clear() } else current.append(c)
                else -> if (depth >= 1) current.append(c)
            }
            i++
        }
        return args
    }

    private data class CallSite(val file: String, val line: Int, val fn: String, val proxyArg: String)

    private fun callSites(fns: List<FfiFn>): List<CallSite> {
        val root = sourceRoot()
        val out = mutableListOf<CallSite>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            val rel = f.relativeTo(root).invariantSeparatorsPath
            for (fn in fns) {
                Regex("""\.${fn.kotlinName}\(""").findAll(text).forEach { m ->
                    val args = arguments(text, m.range.last)
                    // Same-named Kotlin wrappers with another shape are not the FFI call.
                    val named = args.any { Regex("""^\w+\s*=[^=]""").containsMatchIn(it) }
                    if (args.size == fn.arity && !named) {
                        val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                        out += CallSite(rel, line, fn.kotlinName, args.last())
                    }
                }
            }
        }
        return out
    }

    private fun wired(site: CallSite): Boolean {
        val arg = site.proxyArg
        if ("currentProxy()" in arg) return true
        val text = File(sourceRoot(), site.file).readText()
        if (Regex("""\bval\s+$arg\s*=\s*EsploraEndpoint\.currentProxy\(\)""").containsMatchIn(text)) return true
        return PASS_THROUGH.keys.any { (suffix, name) -> site.file.endsWith(suffix) && name == arg }
    }

    @Test
    fun `every proxy-taking FFI call passes the Tor proxy`() {
        val fns = proxyFunctions()
        val expected = setOf(
            "btcRateAt", "btcRateRange", "btcRateBackfill", "netWorthHistory", "phase82BackfillBtcAtTime",
            "recomputeAllLotBtcRates", "resolveLotBtcRate", "resolveManualAssetPurchaseBtc", "resolveSellBtc",
            "syncWithEsplora",
        )
        assertTrue(fns.map { it.kotlinName }.toSet().containsAll(expected), "FFI surface: $fns")

        val sites = callSites(fns)
        val byFn = sites.groupBy { it.fn }
        // Every rate / history / backfill / sync path is reached from Kotlin.
        for (name in expected) assertTrue(name in byFn, "no Kotlin call of $name found — scan out of date? $sites")
        val syncFiles = byFn.getValue("syncWithEsplora").map { it.file }
        for (f in listOf("data/TrackerCache.kt", "screens/AddWatchScreen.kt", "screens/HistoryScreen.kt", "screens/DashboardScreen.kt")) {
            assertTrue(syncFiles.any { it.endsWith(f) }, "sync in $f not found: $syncFiles")
        }

        val offenders = sites.filterNot(::wired)
        assertTrue(
            offenders.isEmpty(),
            "FFI calls that do not pass EsploraEndpoint.currentProxy():\n" +
                offenders.joinToString("\n") { "${it.file}:${it.line} ${it.fn}(…, ${it.proxyArg})" },
        )
    }

    @Test
    fun `the backfill pass-through is fed by the Tor proxy provider`() {
        val runner = File(sourceRoot(), "io/bhodl/desktop/data/BackfillRunner.kt").readText()
        assertTrue(
            Regex("""proxyProvider:\s*\(\)\s*->\s*String\?\s*=\s*\{\s*EsploraEndpoint\.currentProxy\(\)\s*}""")
                .containsMatchIn(runner),
            "runIfNeeded must default to EsploraEndpoint.currentProxy()",
        )
        assertTrue("target.run(registry, proxyProvider())" in runner)
        val overrides = sourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.endsWith("BackfillRunner.kt") }
            .filter { "proxyProvider =" in it.readText() }
            .map { it.name }
            .toList()
        assertTrue(overrides.isEmpty(), "production callers must not override the proxy provider: $overrides")
    }

    @Test
    fun `the scan recognises an unwired call`() {
        val site = CallSite("prices/X.kt", 1, "btcRateAt", "null")
        assertTrue(!site.proxyArg.contains("currentProxy()"))
        assertTrue(arguments("f(a, g(b, c), listOf(1, 2), null)", 1) == listOf("a", "g(b, c)", "listOf(1, 2)", "null"))
    }
}
