package io.bhodl.desktop.screens

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import java.io.File
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Budget redesign 2026-10 (proposal C) — summary maths, the bills
 * window, the category cards and a dark / light render.
 */
@OptIn(ExperimentalTestApi::class)
class BudgetCardsTest {

    @BeforeTest fun setUp() { SettingsStore.reset(); TrackerCache.reset(); TrackerUiState.clearFilters(); TrackerUiState.period.value = TrackingPeriod.D30 }
    @AfterTest fun tearDown() { TrackerUiState.clearFilters(); TrackerCache.reset(); SettingsStore.reset() }

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

    private val now = System.currentTimeMillis() / 1000L
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
        bills = listOf(bill(1, "Luce", LocalDate.now().plusDays(3).dayOfMonth), bill(2, "Internet", LocalDate.now().plusDays(11).dayOfMonth)),
        categories = listOf("Abbonamenti", "Regali", "Ristoranti", "Spesa", "Stipendio", "Trasporti"),
    )

    @Test
    fun `category cards drill into transactions and render dark and light`() = runDesktopComposeUiTest(width = 1440, height = 1250) {
        seed()
        for ((dark, name) in listOf(true to "budget-dark", false to "budget-light")) {
            setContent { BhodlTheme(useDarkTheme = dark) { TrackingScreen(section = TrackerSection.BUDGET) } }
            waitUntilAtLeastOneExists(hasTestTag(BudgetCardsTags.SUMMARY), timeoutMillis = 5_000)
            waitUntilAtLeastOneExists(hasTestTag(BudgetCardsTags.category("Ristoranti")))
            onNodeWithTag(BudgetCardsTags.BILLS).assertExists()
            waitForIdle()
            shot(name)
        }
    }

    private fun SemanticsNodeInteractionsProvider.shot(name: String) {
        val dir = File(System.getProperty("bhodl.screenshots", "build/screenshots")).apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
