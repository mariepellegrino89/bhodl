package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlFooterStatusBar
import io.bhodl.desktop.ui.PrivacyShortcut
import io.bhodl.desktop.ui.PrivacyUiTags
import io.bhodl.desktop.ui.money.AmountMask
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.desktop.ui.money.RevealAmounts
import io.bhodl.ffi.Network
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (item 13) — privacy mode end to end:
 *  - representative composables (Money, wallet header, net-worth
 *    donut, inflow/outflow, category donut, a chart's table view)
 *    render NO amount while the mode is on, and do when it's off;
 *  - RevealAmounts (payment confirmation) wins over the mode;
 *  - the footer eye toggle and Ctrl+Shift+H flip the mode;
 *  - a source scan keeps new code on the masked formatters.
 */
@OptIn(ExperimentalTestApi::class)
class PrivacyModeTest {

    @BeforeTest fun setup() = SettingsStore.reset()

    @AfterTest fun teardown() = SettingsStore.reset()

    /** "3,141,592 sat", "0.03141592 BTC", "₿3,141", "€12.00", "CHF 3,200". */
    private val amountPattern = Regex("""\d[\d,.]*\s?(sat|BTC)\b|₿\d|[€$£]\s?\d|\b[A-Z]{3} \d""")

    private fun ComposeUiTest.allTexts(): List<String> =
        onAllNodesMatching().flatMap { node ->
            val c = node.config
            c.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                c.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }

    private fun ComposeUiTest.onAllNodesMatching() =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun event(dir: PaymentDirection, sat: ULong, ts: Long, category: String?) = PaymentEvent(
        walletId = "w",
        txidOrPaymentId = "tx$sat$ts",
        kind = PaymentKind.ON_CHAIN,
        direction = dir,
        amountSat = sat,
        feeSat = null,
        timestampUnix = ts.toULong(),
        blockHeight = null,
        category = category,
    )

    @Composable
    private fun Representative() {
        val now = 1_790_000_000L
        val events = listOf(
            event(PaymentDirection.INCOMING, 3_141_592UL, now - 40 * 86_400L, "Salary"),
            event(PaymentDirection.OUTGOING, 1_234_567UL, now - 5 * 86_400L, "Food"),
        )
        Column {
            Money(MoneyValue(sat = 3_141_592L, fiatAmount = 1234.5, fiatCurrency = "EUR"))
            WalletHeader(
                walletName = "Main",
                kindLabel = "signing",
                network = Network.SIGNET,
                balanceSat = 3_141_592UL,
                syncMode = SyncMode.Idle,
                lastSyncAtMs = null,
                syncFailed = false,
                onSync = {},
            )
            NwBreakdownDonut(TrackerNetWorth(3_141_592UL, 1_000_000UL, 0UL, 500_000UL))
            InflowOutflowChart(events)
            CategoryDonut(
                listOf(
                    CategorySlice("Salary", 3_141_592UL, 0UL, 1),
                    CategorySlice("Food", 0UL, 1_234_567UL, 1),
                ),
            )
        }
    }

    @Test
    fun `privacy mode hides every amount in representative composables`() = runDesktopComposeUiTest {
        SettingsStore.setBalancesHidden(true)
        setContent { BhodlTheme { Representative() } }
        waitForIdle()
        val texts = allTexts()
        val leaks = texts.filter { amountPattern.containsMatchIn(it) }
        assertTrue(leaks.isEmpty(), "amounts visible in privacy mode: $leaks")
        assertTrue(texts.any { AmountMask.GLYPHS in it }, "expected masked amounts, got: $texts")
    }

    @Test
    fun `the same composables show amounts when privacy mode is off`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { Representative() } }
        waitForIdle()
        assertTrue(allTexts().any { amountPattern.containsMatchIn(it) })
        assertFalse(allTexts().any { AmountMask.GLYPHS in it })
    }

    @Test
    fun `chart table view is masked too`() = runDesktopComposeUiTest {
        SettingsStore.setBalancesHidden(true)
        setContent {
            BhodlTheme {
                InflowOutflowChart(
                    listOf(event(PaymentDirection.INCOMING, 3_141_592UL, 1_790_000_000L, null)),
                )
            }
        }
        onNodeWithTag(io.bhodl.desktop.ui.ChartTableTags.TOGGLE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(io.bhodl.desktop.ui.ChartTableTags.TABLE))
        assertTrue(allTexts().none { amountPattern.containsMatchIn(it) })
    }

    @Test
    fun `payment confirmation surfaces reveal amounts`() = runDesktopComposeUiTest {
        SettingsStore.setBalancesHidden(true)
        setContent {
            BhodlTheme {
                RevealAmounts { Money(MoneyValue(sat = 3_141_592L)) }
            }
        }
        waitForIdle()
        assertTrue(allTexts().any { "3,141,592" in it })
    }

    @Test
    fun `footer eye icon toggles the privacy mode`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme { BhodlFooterStatusBar(network = Network.SIGNET, walletCount = 1, lastSyncUnix = null) }
        }
        onNodeWithTag(PrivacyUiTags.TOGGLE).performClick()
        waitForIdle()
        assertTrue(SettingsStore.balancesHidden.value)
        onNodeWithTag(PrivacyUiTags.TOGGLE).performClick()
        waitForIdle()
        assertFalse(SettingsStore.balancesHidden.value)
    }

    @Test
    fun `ctrl shift H is the privacy shortcut`() {
        assertTrue(PrivacyShortcut.matches(Key.H, KeyEventType.KeyDown, ctrlOrMeta = true, shift = true, alt = false))
        assertFalse(PrivacyShortcut.matches(Key.H, KeyEventType.KeyUp, ctrlOrMeta = true, shift = true, alt = false))
        assertFalse(PrivacyShortcut.matches(Key.H, KeyEventType.KeyDown, ctrlOrMeta = true, shift = false, alt = false))
        // AltGr (Ctrl+Alt) layouts never trigger it.
        assertFalse(PrivacyShortcut.matches(Key.H, KeyEventType.KeyDown, ctrlOrMeta = true, shift = true, alt = true))
        assertFalse(PrivacyShortcut.matches(Key.J, KeyEventType.KeyDown, ctrlOrMeta = true, shift = true, alt = false))
    }

    @Test
    fun `export asks for confirmation only while privacy mode is on`() = kotlinx.coroutines.runBlocking {
        var asked = 0
        assertTrue(confirmExportWhilePrivate(privacyOn = false, ask = { asked++; false }))
        assertEquals(0, asked)
        assertFalse(confirmExportWhilePrivate(privacyOn = true, ask = { asked++; false }))
        assertTrue(confirmExportWhilePrivate(privacyOn = true, ask = { asked++; true }))
        assertEquals(2, asked)
    }

    /**
     * Scan: amounts on screen must go through the masked formatters.
     * The Rust-backed `io.bhodl.shared` formatters know nothing about
     * the privacy mode, so new UI code may not use them; the allowlist
     * holds the debug screen and the chart axes (masked at the call site).
     */
    @Test
    fun `screens never format amounts with the unmasked shared formatters`() {
        val root = File("src/jvmMain/kotlin/io/bhodl/desktop")
        val allow = setOf("DebugWatchScreen.kt", "TrackingCharts.kt")
        val forbidden = Regex("""import io\.bhodl\.shared\.(formatSat|formatAmount|formatBtc)\b""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allow }
            .filter { f -> forbidden.containsMatchIn(f.readText()) }
            .map { it.name }
            .toList()
        assertTrue(offenders.isEmpty(), "unmasked amount formatters used in: $offenders")
    }

    @Test
    fun `holding the shortcut toggles once, not on every key repeat`() {
        PrivacyShortcut.resetForTest()
        fun down() = PrivacyShortcut.onKey(Key.H, KeyEventType.KeyDown, ctrlOrMeta = true, shift = true, alt = false)
        assertTrue(down())
        assertTrue(down()) // auto-repeat: consumed
        assertTrue(down())
        assertTrue(SettingsStore.balancesHidden.value, "three KeyDowns of one press = one toggle")
        assertFalse(PrivacyShortcut.onKey(Key.H, KeyEventType.KeyUp, ctrlOrMeta = true, shift = true, alt = false))
        assertTrue(down())
        assertFalse(SettingsStore.balancesHidden.value, "a second press toggles back")
        PrivacyShortcut.resetForTest()
    }

    @Test
    fun `masked columns keep sats in the header and neutral sign colours`() = runDesktopComposeUiTest {
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.AUTO_THRESHOLD)
        SettingsStore.setBalancesHidden(true)
        var label = ""
        var colour = androidx.compose.ui.graphics.Color.Unspecified
        var neutral = androidx.compose.ui.graphics.Color.Unspecified
        setContent {
            BhodlTheme {
                label = io.bhodl.desktop.ui.rememberColumnAmountFormat(listOf(250_000_000L)).unitLabel
                neutral = BhodlTheme.colors.textTertiary
                colour = io.bhodl.desktop.ui.signedAmountColor(
                    value = -5.0,
                    positive = BhodlTheme.colors.financialPositive,
                    negative = BhodlTheme.colors.financialNegative,
                    neutral = neutral,
                )
            }
        }
        waitForIdle()
        assertEquals("sat", label)
        assertEquals(neutral, colour)
    }

    @Test
    fun `an unreadable settings file starts with amounts hidden`() {
        val dir = java.nio.file.Files.createTempDirectory("privacy-corrupt").toFile()
        try {
            val file = File(dir, "settings.json")
            file.writeText("{ not json")
            io.bhodl.desktop.settings.SettingsPersistence.rehydrate(file)
            assertTrue(SettingsStore.balancesHidden.value, "privacy fails closed on a corrupt file")
            assertFalse(SettingsStore.privacyAutoHide.value, "the stored preference is not invented")
        } finally {
            dir.deleteRecursively()
        }
    }
}
