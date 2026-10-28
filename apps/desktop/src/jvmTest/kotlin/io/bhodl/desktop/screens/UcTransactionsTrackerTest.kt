package io.bhodl.desktop.screens

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
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
import kotlin.test.assertTrue

/**
 * Area C — the tracker's TRANSACTIONS section mounted for real
 * ([TrackingScreen] over a seeded [TrackerCache]): filters, ledger list,
 * CSV export, keyboard shortcuts, command palette, error banner.
 */
@OptIn(ExperimentalTestApi::class)
class UcTransactionsTrackerTest {

    private val defaultExport = csvExportAction
    private var exported: List<PaymentEvent>? = null
    private val now = System.currentTimeMillis() / 1000L

    private val w1 = wallet("w1", "Cold", "tb1qaaa")
    private val w2 = wallet("w2", "Hot", "tb1qbbb")

    private fun wallet(id: String, label: String, addr: String) =
        WalletRecordFfi(id, label, WalletKindFfi.WATCH_ADDRESSES, "[\"$addr\"]", "signet", 1)

    private fun ev(wallet: String, txid: String, category: String?, ts: Long? = now - 3_600, amount: ULong = 1_000UL) =
        PaymentEvent(wallet, txid, PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, amount, null, ts?.toULong(), null, category, null)

    private val food = ev("w1", "food", "Food")
    private val rent = ev("w1", "rent", "Rent")
    private val none = ev("w2", "none", null)

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        TrackerCache.reset()
        TrackerUiState.clearFilters()
        TrackerUiState.period.value = TrackingPeriod.D30
        exported = null
        csvExportAction = { events, _ -> exported = events; null }
    }

    @AfterTest
    fun tearDown() {
        csvExportAction = defaultExport
        TrackerUiState.clearFilters()
        TrackerUiState.period.value = TrackingPeriod.D30
        TrackerCache.reset()
        SettingsStore.reset()
    }

    private fun seed(events: List<PaymentEvent> = listOf(food, rent, none), lastError: String? = null) {
        TrackerCache.seedForTest(
            wallets = listOf(w1, w2),
            events = events,
            categories = events.mapNotNull { it.category }.distinct().sorted(),
            lastError = lastError,
        )
    }

    private fun SemanticsNodeInteractionsProvider.count(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired = false).size

    @Test
    fun `TRK-161 the CSV export follows the active filters`() = runDesktopComposeUiTest {
        seed()
        TrackerUiState.categoryFilter.value = "Food"
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitUntilAtLeastOneExists(hasText("Export 1 transactions as CSV"))
        onNodeWithTag(TrackingTags.EXPORT_CSV).performClick()
        waitForIdle()
        assertEquals(listOf(food), exported)
    }

    @Test
    fun `TRK-016 the cache error wins over a local mutation error`() = runDesktopComposeUiTest {
        csvExportAction = { _, _ -> "Local failure" }
        seed(lastError = "Sync failed for 1 wallet(s) — showing cached data. boom")
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        onNodeWithTag(TrackingTags.EXPORT_CSV).performClick()
        waitUntilAtLeastOneExists(hasText("Sync failed for 1 wallet(s)", substring = true))
        assertEquals(0, onAllNodesWithText("Local failure").size)
    }

    @Test
    fun `TRK-016 a local mutation error shows when the cache has none`() = runDesktopComposeUiTest {
        csvExportAction = { _, _ -> "Local failure" }
        seed()
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        onNodeWithTag(TrackingTags.EXPORT_CSV).performClick()
        waitUntilAtLeastOneExists(hasText("Local failure"))
    }

    private fun SemanticsNodeInteractionsProvider.onAllNodesWithText(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false)

    @Test
    fun `TRK-141 the Uncategorised chip appears only when uncategorised events exist`() = runDesktopComposeUiTest {
        seed(listOf(food, rent))
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitUntilAtLeastOneExists(hasTestTag(TrackingTags.FILTER_ALL))
        assertEquals(0, count(TrackingTags.FILTER_NONE))
        seed(listOf(food, none))
        waitUntilAtLeastOneExists(hasTestTag(TrackingTags.FILTER_NONE))
        onNodeWithTag(TrackingTags.FILTER_NONE).performScrollTo().performClick()
        waitForIdle()
        assertEquals("", TrackerUiState.categoryFilter.value)
        onNodeWithTag(TrackingTags.FILTER_ALL).performScrollTo().performClick()
        waitForIdle()
        assertNull(TrackerUiState.categoryFilter.value)
    }

    @Test
    fun `TRK-141 the active range chip clears only the range`() = runDesktopComposeUiTest {
        seed()
        TrackerUiState.categoryFilter.value = "Food"
        TrackerUiState.timeRangeFilter.value = (now - 86_400) to now
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        onNodeWithTag(TrackingTags.RANGE_CLEAR).performClick()
        waitForIdle()
        assertNull(TrackerUiState.timeRangeFilter.value)
        assertEquals("Food", TrackerUiState.categoryFilter.value)
    }

    @Test
    fun `TRK-142 the ledger shows 50 rows and a counter beyond`() = runDesktopComposeUiTest {
        seed((1..51).map { ev("w1", "t$it", "Food", ts = now - it * 60L) })
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitUntilAtLeastOneExists(hasText("Showing 50 of 51", substring = true))
        assertEquals(50, count(TrackingTags.CATEGORY_CHIP))
    }

    @Test
    fun `TRK-142 empty ledger and no-match states`() = runDesktopComposeUiTest {
        seed(emptyList())
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitUntilAtLeastOneExists(hasText("No transactions across any wallet yet", substring = true))
        seed()
        TrackerUiState.categoryFilter.value = "Travel"
        waitUntilAtLeastOneExists(hasText("No transactions match the active filter."))
    }

    @Test
    fun `TRK-037 the category chip opens the category dialog`() = runDesktopComposeUiTest {
        seed(listOf(food))
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        onNodeWithTag(TrackingTags.CATEGORY_CHIP).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(TrackingTags.CATEGORY_INPUT), timeoutMillis = 5_000)
    }

    @Test
    fun `TRK-027 the wallet row toggles inclusion in the totals`() = runDesktopComposeUiTest {
        seed()
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitUntilAtLeastOneExists(hasTestTag(TrackingTags.WALLET_INCLUDE_TOGGLE))
        onAllNodesWithTag(TrackingTags.WALLET_INCLUDE_TOGGLE)[0].performClick()
        waitForIdle()
        assertEquals(1, SettingsStore.excludedWalletIds.value.size)
        waitUntilAtLeastOneExists(hasText("Excluded"))
        onAllNodesWithTag(TrackingTags.WALLET_INCLUDE_TOGGLE)[0].performClick()
        waitForIdle()
        assertTrue(SettingsStore.excludedWalletIds.value.isEmpty())
    }

    @Test
    fun `TRK-149 digit shortcuts jump to a section`() = runDesktopComposeUiTest {
        seed()
        val visited = mutableListOf<TrackerSection>()
        setContent {
            BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS, onSectionChange = { visited += it }) }
        }
        waitForIdle()
        val root = onNodeWithTag(TrackingTags.ROOT)
        root.performKeyInput { pressKey(Key.Three) }
        root.performKeyInput { pressKey(Key.Six) }
        root.performKeyInput { pressKey(Key.One) }
        waitForIdle()
        assertEquals(listOf(TrackerSection.BUDGET, TrackerSection.REPORTS, TrackerSection.HOME), visited)
    }

    @Test
    fun `TRK-148 arrows change the period and stop at the edges`() = runDesktopComposeUiTest {
        seed()
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitForIdle()
        val root = onNodeWithTag(TrackingTags.ROOT)
        root.performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(TrackingPeriod.D90, TrackerUiState.period.value)
        repeat(4) { root.performKeyInput { pressKey(Key.DirectionRight) } }
        waitForIdle()
        assertEquals(TrackingPeriod.ALL, TrackerUiState.period.value)
        repeat(6) { root.performKeyInput { pressKey(Key.DirectionLeft) } }
        waitForIdle()
        assertEquals(TrackingPeriod.D7, TrackerUiState.period.value)
    }

    @Test
    fun `TRK-147 Esc clears the shared filters`() = runDesktopComposeUiTest {
        seed()
        TrackerUiState.categoryFilter.value = "Food"
        TrackerUiState.timeRangeFilter.value = 1L to 2L
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitForIdle()
        onNodeWithTag(TrackingTags.ROOT).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(TrackerUiState.categoryFilter.value)
        assertNull(TrackerUiState.timeRangeFilter.value)
    }

    @Test
    fun `TRK-150 TRK-165 Ctrl+K opens the palette and its actions run`() = runDesktopComposeUiTest {
        seed()
        TrackerUiState.categoryFilter.value = "Rent"
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.TRANSACTIONS) } }
        waitForIdle()
        val root = onNodeWithTag(TrackingTags.ROOT)
        root.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.K); keyUp(Key.CtrlLeft) }
        waitUntilAtLeastOneExists(hasTestTag(CommandPaletteTags.INPUT))
        onNodeWithTag(CommandPaletteTags.INPUT).performTextInput("export")
        waitUntilAtLeastOneExists(hasText("Export CSV"))
        onNodeWithText("Export CSV").performClick()
        waitForIdle()
        assertEquals(listOf(rent), exported)

        root.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.K); keyUp(Key.CtrlLeft) }
        waitUntilAtLeastOneExists(hasTestTag(CommandPaletteTags.INPUT))
        onNodeWithTag(CommandPaletteTags.INPUT).performTextInput("privacy")
        waitUntilAtLeastOneExists(hasText("Hide amounts", substring = true, ignoreCase = true))
        onNode(hasText("Hide amounts", substring = true, ignoreCase = true)).performClick()
        waitForIdle()
        assertTrue(SettingsStore.balancesHidden.value)

        root.performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.K); keyUp(Key.CtrlLeft) }
        waitUntilAtLeastOneExists(hasTestTag(CommandPaletteTags.INPUT))
        onNodeWithTag(CommandPaletteTags.INPUT).performTextInput("clear")
        waitUntilAtLeastOneExists(hasText("Clear filters"))
        onNodeWithText("Clear filters").performClick()
        waitForIdle()
        assertNull(TrackerUiState.categoryFilter.value)
    }
}
