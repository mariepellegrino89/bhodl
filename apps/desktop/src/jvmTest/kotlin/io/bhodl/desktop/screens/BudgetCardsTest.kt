package io.bhodl.desktop.screens

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.waitUntilDoesNotExist
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ffi.TrackingBudgetFfi
import io.bhodl.ui.theme.BhodlTheme
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Budget redesign 2026-10 (proposal C) — summary maths, the bills
 * window, the category cards and a dark / light render.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetCardsTest {

    @BeforeTest fun setUp() { SettingsStore.reset(); TrackerCache.reset(); TrackerUiState.clearFilters(); TrackerUiState.period.value = TrackingPeriod.D30 }
    @AfterTest fun tearDown() { ScreenshotCheck.releaseClock(); TrackerUiState.clearFilters(); TrackerCache.reset(); SettingsStore.reset() }

    private fun row(cat: String, budget: ULong?, actual: ULong) = BudgetRow(cat, budget, actual, TrackingDirection.EXPENSE, budget)

    @Test
    fun `summary totals only the budgeted categories and sums the price effect`() {
        val s = budgetSummary(
            listOf(row("Spesa", 700_000UL, 620_000UL), row("Ristoranti", 350_000UL, 410_000UL), row("Regali", null, 50_000UL)),
            mapOf("Spesa" to BudgetMove(500_000, 620_000, 40_000, 80_000, -3.0)),
        )
        assertEquals(1_050_000L, s.budgetSat)
        assertEquals(1_030_000L, s.spentSat)
        assertEquals(20_000L, s.leftSat)
        assertEquals(50_000L, s.unbudgetedSat)
        assertEquals(40_000L, s.priceEffectSat)
        assertNull(budgetSummary(listOf(row("x", 1UL, 0UL)), emptyMap()).priceEffectSat)
    }

    private fun bill(id: Long, name: String, day: Int, paidUnix: Long? = null) =
        TrackingBillFfi(id, name, 100_000UL, day.toUInt(), paidUnix, null, 0)

    @Test
    fun `budgets follow the calendar month`() {
        val zone = java.time.ZoneOffset.UTC
        val today = LocalDate.of(2026, 10, 25)
        fun at(d: LocalDate) = PaymentEvent("w", d.toString(), PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, 1_000UL, null,
            d.atTime(12, 0).toEpochSecond(zone).toULong(), null, "Cibo", null)
        val events = listOf(at(LocalDate.of(2026, 9, 30)), at(LocalDate.of(2026, 10, 1)), at(LocalDate.of(2026, 10, 24)))
        assertEquals(listOf("2026-10-01", "2026-10-24"), eventsThisMonth(events, today, zone).map { it.txidOrPaymentId })
        assertEquals(7, daysLeftInMonth(today), "25 to 31 October, today included")
        assertEquals(1, daysLeftInMonth(LocalDate.of(2026, 10, 31)))
        // On the 1st the month starts over: all of it left, nothing from the month before.
        assertEquals(31, daysLeftInMonth(LocalDate.of(2026, 10, 1)))
        assertEquals(emptyList(), eventsThisMonth(events.take(1), LocalDate.of(2026, 10, 1), zone))
        // A monthly budget counts in full against the month (no proration).
        assertEquals(400_000UL, prorateBudget(400_000UL, "monthly", DAYS_PER_MONTH))
        assertEquals(27_692L, weeklyFromMonthly(120_000L), "52 weeks / 12 months")
        // An earlier month: the whole month, nothing outside it.
        assertEquals(listOf("2026-09-30"), eventsInMonth(events, java.time.YearMonth.of(2026, 9), zone).map { it.txidOrPaymentId })
    }

    @Test
    fun `no category gets no budget - its chip opens those transactions`() = runDesktopComposeUiTest {
        var opened: String? = null
        var edited: String? = null
        val rows = listOf(
            BudgetRow(io.bhodl.desktop.ui.UNCATEGORISED_KEY, null, 52_000UL, TrackingDirection.EXPENSE, null),
            BudgetRow("Regali", null, 50_000UL, TrackingDirection.EXPENSE, null),
        )
        setContent { BhodlTheme { BudgetCategoryGrid(rows, emptyMap(), onEditBudget = { c, _ -> edited = c }, onViewTransactions = { opened = it }) } }
        onNodeWithTag(BudgetCardsTags.category(io.bhodl.desktop.ui.UNCATEGORISED_KEY)).assertTextEquals("Uncategorised · 52,000 sat").performClick()
        assertEquals("", opened, "the \"no category\" filter of the ledger")
        assertNull(edited)
        onNodeWithTag(BudgetCardsTags.category("Regali")).performClick()
        assertEquals("Regali", edited)
    }

    @Test
    fun `the month selector goes back and never past the current month`() = runDesktopComposeUiTest {
        val now = java.time.YearMonth.of(2026, 10)
        var month by androidx.compose.runtime.mutableStateOf(now)
        setContent { BhodlTheme { BudgetMonthSelector(month = month, current = now, onChange = { month = it }) } }
        onNodeWithTag(BudgetMonthTags.NEXT).assertIsNotEnabled()
        onNodeWithTag(BudgetMonthTags.PREV).performClick()
        waitForIdle()
        assertEquals(java.time.YearMonth.of(2026, 9), month)
        onNodeWithTag(BudgetMonthTags.NEXT).assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(now, month)
    }

    @Test
    fun `upcoming bills are the next 30 days, paid ones move to next month`() {
        val today = LocalDate.of(2026, 10, 25)
        val paidThisMonth = LocalDate.of(2026, 10, 2).toEpochDay() * 86_400L
        val list = upcomingBills(
            listOf(bill(1, "Luce", 28), bill(2, "Affitto", 1), bill(3, "Gas", 27, paidThisMonth), bill(4, "Acqua", 20, paidThisMonth)),
            today,
        )
        // Gas is paid: its next date (27 Nov) is beyond 30 days; Acqua's (20 Nov) is not.
        assertEquals(listOf("Luce", "Affitto", "Acqua"), list.map { it.first.name })
        assertEquals(listOf(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20)), list.map { it.second })
    }

    // Fixed "now" (ScreenshotCheck): the render shows the same month and dates every day.
    private val now = ScreenshotCheck.NOW_UNIX
    private val today = PortfolioFixtures.TODAY
    private fun out(id: String, cat: String, sat: ULong, d: Long) =
        PaymentEvent("w1", id, PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, sat, null, (now - d * 86_400L).toULong(), null, cat, null)

    private fun seed() = TrackerCache.seedForTest(
        events = listOf(
            out("a", "Spesa", 620_000UL, 2), out("b", "Ristoranti", 410_000UL, 4), out("c", "Trasporti", 180_000UL, 6),
            out("d", "Abbonamenti", 95_000UL, 8), out("e", "Regali", 50_000UL, 9),
            PaymentEvent("w1", "s", PaymentKind.ON_CHAIN, PaymentDirection.INCOMING, 3_400_000UL, null, (now - 3 * 86_400L).toULong(), null, "Stipendio", null),
        ),
        budgets = listOf(
            TrackingBudgetFfi("Spesa", 700_000UL, "monthly", 0), TrackingBudgetFfi("Ristoranti", 350_000UL, "monthly", 0),
            TrackingBudgetFfi("Trasporti", 300_000UL, "monthly", 0), TrackingBudgetFfi("Abbonamenti", 120_000UL, "monthly", 0),
        ),
        bills = listOf(bill(1, "Luce", today.plusDays(3).dayOfMonth), bill(2, "Internet", today.plusDays(11).dayOfMonth)),
        categories = listOf("Abbonamenti", "Regali", "Ristoranti", "Spesa", "Stipendio", "Trasporti"),
    )

    @Test
    fun `category cards drill into transactions and render dark and light`() = runDesktopComposeUiTest(width = 1440, height = 1250) {
        ScreenshotCheck.pinClock()
        seed()
        for ((dark, name) in listOf(true to "budget-dark", false to "budget-light")) {
            setContent { BhodlTheme(useDarkTheme = dark) { TrackingScreen(section = TrackerSection.BUDGET) } }
            waitUntilAtLeastOneExists(hasTestTag(BudgetCardsTags.SUMMARY), timeoutMillis = 5_000)
            waitUntilAtLeastOneExists(hasTestTag(BudgetCardsTags.category("Ristoranti")))
            onNodeWithTag(BudgetCardsTags.BILLS).assertExists()
            waitForIdle()
            screenshot(name)
        }
    }

    @Test
    fun `budget and transactions share one month, the period selector only shows outside budget`() = runDesktopComposeUiTest(width = 1440, height = 1250) {
        seed()
        val thisMonth = io.bhodl.desktop.AppClock.currentMonth()
        TrackerUiState.month.value = thisMonth
        var section by androidx.compose.runtime.mutableStateOf(TrackerSection.BUDGET)
        setContent { BhodlTheme { TrackingScreen(section = section, onSectionChange = { section = it }) } }
        waitUntilAtLeastOneExists(hasTestTag(BudgetMonthTags.PREV), timeoutMillis = 5_000)
        onNodeWithTag("${TrackingTags.PERIOD_PREFIX}${TrackingPeriod.D90.name}").assertDoesNotExist()
        onNodeWithTag(BudgetMonthTags.THIS_MONTH).assertDoesNotExist()

        // ‹ September → "View transactions" on a category: Transactions, by month, that category.
        onNodeWithTag(BudgetMonthTags.PREV).performClick()
        waitUntilAtLeastOneExists(hasTestTag(BudgetCardsTags.viewTransactions("Ristoranti")), timeoutMillis = 5_000)
        onNodeWithTag(BudgetCardsTags.viewTransactions("Ristoranti")).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(TrackingTags.PERIOD_MONTH), timeoutMillis = 5_000)
        assertEquals(TrackerSection.TRANSACTIONS, section)
        assertTrue(TrackerUiState.txByMonth.value)
        assertEquals("Ristoranti", TrackerUiState.categoryFilter.value)
        assertEquals(thisMonth.minusMonths(1), TrackerUiState.month.value)

        // A month picked in Transactions is the month Budget opens on.
        onNodeWithTag(BudgetMonthTags.PREV).performClick()
        waitForIdle()
        section = TrackerSection.BUDGET
        waitUntilAtLeastOneExists(hasTestTag(BudgetMonthTags.THIS_MONTH), timeoutMillis = 5_000)
        assertEquals(thisMonth.minusMonths(2), TrackerUiState.month.value)

        // "This month" goes straight back to the current month.
        onNodeWithTag(BudgetMonthTags.THIS_MONTH).performClick()
        waitUntilDoesNotExist(hasTestTag(BudgetMonthTags.THIS_MONTH), timeoutMillis = 3_000)
        assertEquals(thisMonth, TrackerUiState.month.value)

        // Reports get the period selector back.
        section = TrackerSection.REPORTS
        waitUntilAtLeastOneExists(hasTestTag("${TrackingTags.PERIOD_PREFIX}${TrackingPeriod.D90.name}"), timeoutMillis = 5_000)
        onNodeWithTag(TrackingTags.PERIOD_MONTH).assertDoesNotExist()
    }

    @Test
    fun `transactions can go by calendar month`() = runDesktopComposeUiTest {
        var monthMode by androidx.compose.runtime.mutableStateOf(false)
        var month by androidx.compose.runtime.mutableStateOf(java.time.YearMonth.now())
        var period: TrackingPeriod? = null
        setContent {
            BhodlTheme {
                PeriodSelector(
                    selected = TrackingPeriod.D30,
                    onSelect = { period = it; monthMode = false },
                    month = month,
                    monthSelected = monthMode,
                    onMonthMode = { monthMode = true },
                    onMonthChange = { month = it },
                )
            }
        }
        assertTrue(onAllNodes(androidx.compose.ui.test.hasTestTag(BudgetMonthTags.PREV)).fetchSemanticsNodes().isEmpty())
        onNodeWithTag(TrackingTags.PERIOD_MONTH).performClick()
        waitForIdle()
        assertTrue(monthMode)
        onNodeWithTag(BudgetMonthTags.PREV).performClick()
        waitForIdle()
        assertEquals(java.time.YearMonth.now().minusMonths(1), month)
        onNodeWithTag("${TrackingTags.PERIOD_PREFIX}${TrackingPeriod.D90.name}").performClick()
        assertEquals(TrackingPeriod.D90, period)
        assertTrue(!monthMode, "a period chip leaves the month view")
    }
}

