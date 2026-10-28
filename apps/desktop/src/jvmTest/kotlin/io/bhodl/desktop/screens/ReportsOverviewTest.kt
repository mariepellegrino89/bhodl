package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reports redesign 2026-10 (proposal "A + C pies") — period comparison,
 * totals, insights, the report list and a dark / light render.
 */
@OptIn(ExperimentalTestApi::class)
class ReportsOverviewTest {

    // Fixed "now" (ScreenshotCheck): the render shows the same dates every day.
    private val now = ScreenshotCheck.NOW_UNIX
    private val day = 86_400L

    private fun ev(id: String, dir: PaymentDirection, sat: ULong, ts: Long, cat: String?) =
        PaymentEvent("w1", id, PaymentKind.ON_CHAIN, dir, sat, null, ts.toULong(), null, cat, null)

    private val wallets = listOf(WalletRecordFfi("w1", "Main", WalletKindFfi.WATCH_ADDRESSES, "[\"tb1qaaa\"]", "signet", 1))

    private val current = listOf(
        ev("s1", PaymentDirection.INCOMING, 3_000_000UL, now - 2 * day, "Stipendio"),
        ev("o1", PaymentDirection.OUTGOING, 600_000UL, now - 3 * day, "Spesa"),
        ev("o2", PaymentDirection.OUTGOING, 300_000UL, now - 4 * day, "Ristoranti"),
        ev("o3", PaymentDirection.OUTGOING, 100_000UL, now - 5 * day, null),
    )
    private val previous = listOf(
        ev("p0", PaymentDirection.INCOMING, 2_000_000UL, now - 40 * day, "Stipendio"),
        ev("p1", PaymentDirection.OUTGOING, 400_000UL, now - 40 * day, "Spesa"),
        ev("p2", PaymentDirection.OUTGOING, 600_000UL, now - 41 * day, "Ristoranti"),
    )

    @BeforeTest fun setUp() { SettingsStore.reset(); TrackerCache.reset(); TrackerUiState.clearFilters(); TrackerUiState.period.value = TrackingPeriod.D30; ScreenshotCheck.pinClock() }
    @AfterTest fun tearDown() { ScreenshotCheck.releaseClock(); TrackerUiState.clearFilters(); TrackerCache.reset(); SettingsStore.reset() }

    @Test
    fun `categories compare with the previous window`() {
        val rows = compareCategories(current, previous).associateBy { it.category }
        assertEquals(600_000L, rows["Spesa"]!!.nowSat)
        assertEquals(50.0, rows["Spesa"]!!.changePct!!, 0.001)
        assertEquals(-50.0, rows["Ristoranti"]!!.changePct!!, 0.001)
        assertNull(rows[null]!!.changePct, "nothing to compare with")
        assertNull(compareCategories(current, null).first().prevSat)
    }

    @Test
    fun `totals and insights`() {
        val t = periodTotals(current)
        assertEquals(3_000_000L, t.inSat)
        assertEquals(1_000_000L, t.outSat)
        assertEquals(2_000_000L, t.netSat)
        assertEquals(66.666, t.savedPct!!, 0.01)
        val i = reportInsights(compareCategories(current, previous))
        assertEquals("Spesa", i.grew!!.category)
        assertEquals("Ristoranti", i.dropped!!.category)
        assertEquals("Spesa", i.top!!.category)
        assertEquals(60.0, i.topSharePct!!, 0.001)
    }

    @Test
    fun `the report list switches between categories and wallets`() = runDesktopComposeUiTest(width = 1440, height = 1100) {
        TrackerCache.seedForTest(wallets = wallets, events = current + previous)
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.REPORTS) } }
        waitUntilAtLeastOneExists(hasTestTag(ReportsOverviewTags.DONUT), timeoutMillis = 5_000)
        onNodeWithTag(ReportsOverviewTags.TOTALS).assertExists()
        onNodeWithTag(ReportsOverviewTags.BARS).assertExists()
        onNodeWithTag(ReportsTags.kind(ReportKind.WALLETS)).performClick()
        waitUntilDoesNotExist(hasTestTag(ReportsOverviewTags.DONUT), timeoutMillis = 3_000)
    }

    @Test
    fun `the period All has no previous window - no change and no thin bar`() = runDesktopComposeUiTest(width = 1440, height = 1100) {
        TrackerCache.seedForTest(wallets = wallets, events = current + previous)
        TrackerUiState.period.value = TrackingPeriod.D30
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.REPORTS) } }
        waitUntilAtLeastOneExists(hasTestTag(ReportsOverviewTags.DONUT), timeoutMillis = 5_000)
        onNodeWithTag(ReportsOverviewTags.previousBar("Spesa"), useUnmergedTree = true).assertExists()

        TrackerUiState.period.value = TrackingPeriod.ALL
        waitUntilDoesNotExist(hasTestTag(ReportsOverviewTags.previousBar("Spesa")), timeoutMillis = 3_000)
        onNodeWithTag(ReportsOverviewTags.bar("Spesa"), useUnmergedTree = true).assertExists()
        onAllNodes(hasTestTag(ReportsOverviewTags.previousBar("Ristoranti")), useUnmergedTree = true).assertCountEquals(0)
        onNodeWithTag(ReportsOverviewTags.bar("Spesa")).assert(hasAnyChild(hasText("—")))
    }

    @Test
    fun `a legend click highlights that category in the pie and the bars, a second click clears it`() = runDesktopComposeUiTest(width = 1440, height = 1100) {
        TrackerCache.seedForTest(wallets = wallets, events = current + previous)
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.REPORTS) } }
        waitUntilAtLeastOneExists(hasTestTag(ReportsOverviewTags.DONUT), timeoutMillis = 5_000)
        onNodeWithTag(ReportsOverviewTags.legend("Spesa")).assertIsNotSelected()

        onNodeWithTag(ReportsOverviewTags.legend("Spesa")).performClick()
        onNodeWithTag(ReportsOverviewTags.legend("Spesa")).assertIsSelected()
        onNodeWithTag(ReportsOverviewTags.legend("Ristoranti")).assertIsNotSelected()
        onNodeWithTag(ReportsOverviewTags.bar("Spesa")).assertIsSelected()
        onNodeWithTag(ReportsOverviewTags.bar("Ristoranti")).assertIsNotSelected()

        onNodeWithTag(ReportsOverviewTags.legend("Spesa")).performClick()
        onNodeWithTag(ReportsOverviewTags.legend("Spesa")).assertIsNotSelected()
        onNodeWithTag(ReportsOverviewTags.bar("Spesa")).assertIsNotSelected()
    }

    @Test
    fun `render the reports for the visual review`() = runDesktopComposeUiTest(width = 1440, height = 1300) {
        TrackerCache.seedForTest(wallets = wallets, events = current + previous)
        for ((dark, name) in listOf(true to "reports-dark", false to "reports-light")) {
            setContent { BhodlTheme(useDarkTheme = dark) { TrackingScreen(section = TrackerSection.REPORTS) } }
            waitUntilAtLeastOneExists(hasTestTag(ReportsOverviewTags.DONUT), timeoutMillis = 5_000)
            waitForIdle()
            screenshot(name)
        }
    }
}
