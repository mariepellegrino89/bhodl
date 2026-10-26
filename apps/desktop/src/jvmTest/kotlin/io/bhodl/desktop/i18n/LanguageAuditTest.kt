package io.bhodl.desktop.i18n

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.screens.TrackerUiState
import io.bhodl.desktop.screens.TrackingPeriod
import io.bhodl.desktop.screens.TrackingScreen
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every tracker section (with the rail), Settings, Modules and the
 * Security Hub, opened in one language and switched to the other with
 * the screen on show (as App.kt does), with data in every card, with data in every
 * card: no visible text may be copy of the OTHER language (a resource
 * value that differs between `values` and `values-it`, or a hardcoded
 * literal duplicating one). User data (names typed by the user) is kept
 * language-neutral in the fixture so it never counts — except classic
 * categories, stored in either language and shown in the app's.
 */
@OptIn(ExperimentalTestApi::class)
class LanguageAuditTest {

    private lateinit var dir: File
    private lateinit var registry: RegistryHandle
    private val defaultSource = TrackerCache.watchEventsSource
    private val defaultDataDir = TrackerCache.dataDirProvider
    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        TrackerCache.reset()
        TrackerUiState.clearFilters()
        TrackerUiState.period.value = TrackingPeriod.Y1
        SettingsStore.setModuleBudgetBills(true)
        SettingsStore.setModuleSavingsGoals(true)
        SettingsStore.setModuleInvestments(true)
        SettingsStore.setModuleManualAssets(true)
        dir = kotlin.io.path.createTempDirectory("bhodl-lang-audit-").toFile()
        registry = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        RegistryStore.set(registry)
        TrackerCache.dataDirProvider = { File(dir, "marker") }
        seed()
    }

    @AfterTest
    fun tearDown() {
        TrackerCache.watchEventsSource = defaultSource
        TrackerCache.dataDirProvider = defaultDataDir
        TrackerUiState.clearFilters()
        TrackerUiState.period.value = TrackingPeriod.D30
        RegistryStore.clear()
        SettingsStore.reset()
        dir.deleteRecursively()
    }

    private fun seed() {
        val w = registry.addWatchAddresses("Zeta", listOf(SIGNET_A), Network.SIGNET)
        val legs = mutableListOf<PaymentEvent>()
        fun leg(id: String, dir: PaymentDirection, sat: ULong, daysAgo: Long) {
            legs += PaymentEvent(w.id, id, PaymentKind.ON_CHAIN, dir, sat, null, (now - daysAgo * 86_400).toULong(), 1u, null, null)
        }
        for (m in 0L..5L) {
            leg("in$m", PaymentDirection.INCOMING, 3_000_000UL, m * 30 + 2)
            leg("gym$m", PaymentDirection.OUTGOING, 40_000UL, m * 30 + 3)
            leg("food$m", PaymentDirection.OUTGOING, 90_000UL, m * 30 + 5)
        }
        leg("loose", PaymentDirection.OUTGOING, 12_000UL, 1)
        for (m in 0L..5L) {
            registry.setEventCategory(w.id, "in$m", "Qwop")
            // Classic categories stored in each language: shown in the app's.
            registry.setEventCategory(w.id, "gym$m", "Cibo")
            registry.setEventCategory(w.id, "food$m", "Transport")
        }
        TrackerCache.watchEventsSource = { reg ->
            legs.map { it.copy(category = reg.getEventCategory(it.walletId, it.txidOrPaymentId)) }
        }
        registry.setBudget("Cibo", 30_000UL, "monthly")
        registry.setBudget("Transport", 200_000UL, "monthly")
        val bill = registry.addBill("Zz", 40_000UL, 28u, null)
        registry.setBillVariable(bill, true)
        registry.addSavingsGoal("Gg", 10_000_000UL, 2_000_000UL, "#3FB950", null, null, null, "manual", null, "savings")
        registry.addLiability("Ll", 500_000UL, "credit-card", null)
        registry.addManualAsset("Aa", "cash", 1_000.0, "EUR", null, null, null, null, null)
        registry.addManualTxn("out", 50_000UL, 30.0, "EUR", now - 86_400, null, "Nn")
        registry.addRule("Rr", "amount_out", "eq", "40000", "category", "Cibo", 0, true)
        runBlocking { TrackerCache.refresh(syncEsplora = false) }
    }

    private fun texts(node: SemanticsNode, out: MutableList<String>) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
        node.config.getOrNull(SemanticsProperties.EditableText)?.let { out += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { out += it }
        node.children.forEach { texts(it, out) }
    }

    /** Values of [other] that differ from the same key in [mine] (copy that only [other] has). */
    private fun foreignCopy(mine: String, other: String): Set<String> {
        val m = I18nResources.entries(mine).associate { it.key to it.value }
        return I18nResources.entries(other)
            .filter { m[it.key] != null && m[it.key] != it.value }
            .map { it.value.trim() }
            // Only copy without placeholders, long enough to be unambiguous.
            .filter { '%' !in it && it.length >= 4 && it.any(Char::isLetter) }
            .toSet() - I18nResources.entries(mine).map { it.value.trim() }.toSet()
    }

    private fun audit(lang: String, other: String): List<String> {
        val foreign = foreignCopy(if (lang == "it") "values-it" else "values", other)
        val found = mutableListOf<String>()
        val screens: List<Pair<String, @androidx.compose.runtime.Composable () -> Unit>> =
            TrackerSection.entries.map { section ->
                section.name to @androidx.compose.runtime.Composable {
                    io.bhodl.desktop.ui.TwoTabShell(selected = io.bhodl.desktop.ui.Tab.fromSection(section), onTabSelected = {}) {
                        TrackingScreen(section = section)
                    }
                }
            } + listOf(
                "SETTINGS" to @androidx.compose.runtime.Composable { io.bhodl.desktop.screens.SettingsScreen(onBack = {}) },
                "MODULES" to @androidx.compose.runtime.Composable { io.bhodl.desktop.screens.ModulesScreen(onBack = {}) },
                "SECURITY" to @androidx.compose.runtime.Composable {
                    io.bhodl.desktop.screens.SecurityHubScreen(onBack = {}, onOpenWallets = {}, onOpenSettings = {})
                },
            )
        for ((name, screen) in screens) {
            runDesktopComposeUiTest(width = 1600, height = 4000) {
                // Open in the other language, then switch with the screen
                // on show — like App.kt, which keys the NavHost on it.
                SettingsStore.setLocaleOverride(if (lang == "it") "en" else "it")
                setContent {
                    val override by SettingsStore.localeOverride
                    androidx.compose.runtime.key(override) { BhodlTheme { screen() } }
                }
                waitForIdle()
                SettingsStore.setLocaleOverride(lang)
                waitForIdle()
                val shown = mutableListOf<String>()
                texts(onRoot(useUnmergedTree = true).fetchSemanticsNode(), shown)
                for (t in shown.map { it.trim() }.distinct()) {
                    val hit = foreign.firstOrNull { f -> t == f || (f.length >= 10 && t.contains(f)) }
                    if (hit != null) found += "[$lang] $name: \"$t\"" + if (hit != t) "  (contains \"$hit\")" else ""
                }
            }
        }
        return found
    }

    @Test
    fun `no English copy on the Italian tracker and no Italian copy on the English one`() {
        val found = audit("it", "values") + audit("en", "values-it")
        found.forEach { println("LANGUAGE-AUDIT $it") }
        assertTrue(found.isEmpty(), "copy of the other language:\n" + found.joinToString("\n"))
    }

    private companion object {
        const val SIGNET_A = "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
    }
}
