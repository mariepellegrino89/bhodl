package io.bhodl.desktop.screens

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.RecurringConfidence
import io.bhodl.desktop.domain.RecurringFrequency
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.money.AmountMask
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentDirection.INCOMING
import io.bhodl.ffi.PaymentDirection.OUTGOING
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ffi.TrackingBudgetFfi
import io.bhodl.ffi.TrackingRuleFfi
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Area C — Reports tables (rows, sort, empty states, Analytics Labs
 * gating), the analytics empty / skeleton states and privacy masking
 * in the tracker cards.
 */
@OptIn(ExperimentalTestApi::class)
class UcReportsTrackerTest {

    private val now = System.currentTimeMillis() / 1000L

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        TrackerCache.reset()
        TrackerUiState.clearFilters()
        TrackerUiState.period.value = TrackingPeriod.D30
    }

    @AfterTest
    fun tearDown() {
        TrackerCache.reset()
        SettingsStore.reset()
        TrackerUiState.period.value = TrackingPeriod.D30
    }

    private fun ev(
        wallet: String,
        txid: String,
        dir: PaymentDirection,
        amount: ULong,
        category: String? = null,
        ts: Long = now - 3_600,
        fee: ULong? = null,
        kind: String? = null,
    ) = PaymentEvent(wallet, txid, PaymentKind.ON_CHAIN, dir, amount, fee, ts.toULong(), null, category, kind)

    private fun group(label: String, vararg ids: String, balance: ULong? = 0UL) =
        GroupedWallet(label, "Watch", "signet", balance, ids.toSet())

    // ─── TRK-138 — rows ─────────────────────────────────────────────

    @Test
    fun `TRK-138 category rows count external flows and the last activity`() {
        val rows = categoryReportRows(
            listOf(
                ev("w", "1", INCOMING, 1_000UL, "Salary", ts = 100),
                ev("w", "2", OUTGOING, 300UL, "Food", ts = 200),
                ev("w", "3", OUTGOING, 200UL, "Food", ts = 300),
                ev("w", "4", OUTGOING, 50_500UL, "Move", ts = 400, fee = 500UL, kind = SEND_TO_SELF),
                ev("w", "5", INCOMING, 9UL, " ", ts = 50),
            ),
        ).associateBy { it.category }
        assertEquals(CatRow("Food", 2, 0UL, 500UL, 300), rows["Food"])
        assertEquals(-500L, rows.getValue("Food").netSat)
        assertEquals(CatRow("Move", 1, 0UL, 500UL, 400), rows["Move"])
        assertEquals(CatRow("", 1, 9UL, 0UL, 50), rows[""])
        assertEquals(1_000UL, rows.getValue("Salary").volumeSat)
        assertTrue(categoryReportRows(emptyList()).isEmpty())
    }

    @Test
    fun `TRK-138 wallet rows aggregate every member of a group`() {
        val rows = walletReportRows(
            listOf(
                ev("a1", "1", INCOMING, 1_000UL, ts = 100),
                ev("a2", "2", OUTGOING, 400UL, ts = 300),
                ev("b", "3", INCOMING, 7UL, ts = 200),
            ),
            listOf(group("Cold", "a1", "a2", balance = 600UL), group("Empty", "z", balance = null)),
        )
        assertEquals(WalRow("a1,a2", "Cold", "Watch", 2, 600UL, 1_000UL, 400UL, 300), rows[0])
        assertEquals(WalRow("z", "Empty", "Watch", 0, null, 0UL, 0UL, null), rows[1])
    }

    // ─── TRK-138 — table UI ─────────────────────────────────────────

    private fun ComposeUiTest.topOf(text: String) =
        onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot.top

    @Test
    fun `TRK-138 category table sorts by net and flips on a header click`() = runDesktopComposeUiTest {
        val events = listOf(ev("w", "1", INCOMING, 1_000UL, "Salary"), ev("w", "2", OUTGOING, 300UL, "Food"))
        setContent { BhodlTheme { ReportsTabBody(events, listOf(group("Cold", "w")), periodLabel = "30D") } }
        waitUntilAtLeastOneExists(hasText("Food"))
        assertTrue(topOf("Salary") < topOf("Food"), "net descending by default")
        onNode(hasText("Net", substring = true) and hasText("▼", substring = true)).performClick()
        waitForIdle()
        assertTrue(topOf("Food") < topOf("Salary"), "second click sorts ascending")
        onNodeWithText("Category").performClick()
        waitForIdle()
        assertTrue(topOf("Salary") < topOf("Food"), "category sort starts descending")
    }

    @Test
    fun `TRK-138 empty states for no events and no wallets`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { ReportsTabBody(emptyList(), emptyList(), periodLabel = "30D") } }
        waitUntilAtLeastOneExists(hasText("No transactions in the current period."))
        waitUntilAtLeastOneExists(hasText("No wallets in the registry."))
    }

    // ─── TRK-139 / TRK-137 — reports section and analytics states ───

    private val w1 = WalletRecordFfi("w1", "Cold", WalletKindFfi.WATCH_ADDRESSES, "[\"tb1qaaa\"]", "signet", 1)

    @Test
    fun `TRK-139 reports use only the period and included wallets, labs charts only with the module`() = runDesktopComposeUiTest {
        TrackerCache.seedForTest(
            wallets = listOf(w1, w1.copy(id = "w2", label = "Hot", descriptorPub = "[\"tb1qbbb\"]")),
            events = listOf(
                ev("w1", "in", OUTGOING, 1_000UL, "Food"),
                ev("w1", "old", OUTGOING, 1_000UL, "Ancient", ts = now - 400L * 86_400L),
                ev("w2", "ex", OUTGOING, 1_000UL, "Hidden"),
            ),
        )
        SettingsStore.setWalletIncluded("w2", false)
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.REPORTS) } }
        waitUntilAtLeastOneExists(hasText("Food"))
        assertEquals(0, onAllNodes(hasText("Ancient")).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasText("Hidden")).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodes(hasTestTag(TrackingTags.COMPARE_TOGGLE)).fetchSemanticsNodes().size)
        SettingsStore.setModuleAnalyticsLabs(true)
        waitUntilAtLeastOneExists(hasTestTag(TrackingTags.COMPARE_TOGGLE))
    }

    @Test
    fun `TRK-137 analytics shows the empty text, or skeletons while refreshing`() = runDesktopComposeUiTest {
        SettingsStore.setModuleAnalyticsLabs(true)
        TrackerCache.seedForTest(wallets = listOf(w1), events = emptyList())
        setContent { BhodlTheme { TrackingScreen(section = TrackerSection.REPORTS) } }
        waitUntilAtLeastOneExists(hasText("No transactions yet", substring = true))
        TrackerCache.seedForTest(wallets = listOf(w1), events = emptyList(), refreshing = true)
        waitUntilAtLeastOneExists(hasText("Balance ·", substring = true))
        assertEquals(0, onAllNodes(hasText("No transactions yet", substring = true)).fetchSemanticsNodes().size)
    }

    // ─── TRK-166 — privacy masking in the tracker cards ─────────────

    private val amountPattern = Regex("""\d[\d,.]*\s?(sat|BTC)\b|₿\d|[€$£]\s?\d|31,415|27,182|31415|27182""")

    private fun ComposeUiTest.allTexts(): List<String> =
        onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes().flatMap { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }

    @Test
    fun `TRK-166 budget, bills, recurring, rules and ledger amounts are masked`() = runDesktopComposeUiTest {
        SettingsStore.setBalancesHidden(true)
        val events = listOf(ev("w1", "t", OUTGOING, 31_415UL, "Food"))
        val rows = computeBudgetRows(events, listOf(TrackingBudgetFfi("Food", 27_182UL, "monthly", 0)), TrackingDirection.EXPENSE)
        val candidate = RecurringCandidate("k", "Gym", emptyList(), RecurringFrequency.MONTHLY, 31_415UL, now + 86_400, RecurringConfidence.HIGH)
        setContent {
            BhodlTheme {
                androidx.compose.foundation.layout.Column {
                    BudgetBreakdownCard("Spending", rows, onEditBudget = { _, _ -> })
                    BillsCalendarStrip(listOf(TrackingBillFfi(1, "Rent", 27_182UL, 5u, null, null, 0)), todayLocal(), { _, _ -> }, {}, {})
                    RecurringCard(listOf(candidate), {}, {})
                    RulesCard(listOf(TrackingRuleFfi(1, "Big", "amount", "gt", "31415", "category", "Big", 0, true, 0)), {}, {}, {})
                    HistoryTabBody(
                        wallets = listOf(w1), refreshing = false, groupedWallets = listOf(group("Cold", "w1", balance = 31_415UL)),
                        excludedWalletIds = emptySet(), onToggleInclude = { _, _ -> }, events = events, filteredEvents = events,
                        categories = listOf("Food"), categoryFilter = null,
                        timeRangeFilter = null, onClearTimeRange = {}, onSelectCategory = {}, onEditCategory = {},
                    )
                }
            }
        }
        waitForIdle()
        val texts = allTexts()
        val leaks = texts.filter { amountPattern.containsMatchIn(it) }
        assertTrue(leaks.isEmpty(), "amounts visible in privacy mode: $leaks")
        assertTrue(texts.any { AmountMask.GLYPHS in it })
    }
}
