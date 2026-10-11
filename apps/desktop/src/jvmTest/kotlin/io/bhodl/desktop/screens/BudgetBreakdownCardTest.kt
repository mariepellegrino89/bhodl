package io.bhodl.desktop.screens

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBudgetFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4.3 — pure tests for [computeBudgetRows].
 *
 * The function aggregates per-category actuals from a stream of
 * [PaymentEvent]s, joins them with the registry-backed budgets, and
 * sorts by actual descending so the biggest spenders surface at the
 * top of the breakdown table. It's the back-end of the
 * BudgetBreakdownCard composable — no Compose runtime needed here.
 */
class BudgetBreakdownCardTest {

    private fun event(
        amount: ULong,
        direction: PaymentDirection,
        category: String? = null,
        txid: String = "tx-${(0..99_999).random()}",
    ): PaymentEvent = PaymentEvent(
        walletId = "w",
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = direction,
        amountSat = amount,
        feeSat = null,
        timestampUnix = 1_779_580_000UL,
        blockHeight = null,
        category = category,
    )

    private fun budget(category: String, sat: ULong): TrackingBudgetFfi =
        TrackingBudgetFfi(
            category = category,
            budgetedSat = sat,
            period = "monthly",
            updatedAtUnix = 0L,
        )

    @Test
    fun `empty inputs yield empty rows`() {
        assertEquals(
            emptyList(),
            computeBudgetRows(events = emptyList(), budgets = emptyList(), direction = TrackingDirection.EXPENSE),
        )
    }

    @Test
    fun `expense direction sums only outgoing events`() {
        val events = listOf(
            event(100UL, PaymentDirection.OUTGOING, "Cibo"),
            event(50UL, PaymentDirection.INCOMING, "Cibo"), // ignored
            event(30UL, PaymentDirection.OUTGOING, "Cibo"),
        )
        val rows = computeBudgetRows(events, emptyList(), TrackingDirection.EXPENSE)
        assertEquals(1, rows.size)
        assertEquals("Cibo", rows[0].category)
        assertEquals(130UL, rows[0].actualSat)
        assertNull(rows[0].budgetedSat)
        assertEquals(TrackingDirection.EXPENSE, rows[0].direction)
    }

    @Test
    fun `income direction sums only incoming events`() {
        val events = listOf(
            event(500UL, PaymentDirection.INCOMING, "Stipendio"),
            event(100UL, PaymentDirection.OUTGOING, "Stipendio"), // ignored
            event(200UL, PaymentDirection.INCOMING, "Stipendio"),
        )
        val rows = computeBudgetRows(events, emptyList(), TrackingDirection.INCOME)
        assertEquals(1, rows.size)
        assertEquals(700UL, rows[0].actualSat)
        assertEquals(TrackingDirection.INCOME, rows[0].direction)
    }

    @Test
    fun `null and blank categories collapse into Uncategorised`() {
        val events = listOf(
            event(10UL, PaymentDirection.OUTGOING, category = null),
            event(20UL, PaymentDirection.OUTGOING, category = ""),
            event(30UL, PaymentDirection.OUTGOING, category = "   "),
        )
        val rows = computeBudgetRows(events, emptyList(), TrackingDirection.EXPENSE)
        assertEquals(1, rows.size)
        assertEquals("Uncategorised", rows[0].category)
        assertEquals(60UL, rows[0].actualSat)
    }

    @Test
    fun `budget without matching event still surfaces with zero actual`() {
        val rows = computeBudgetRows(
            events = emptyList(),
            budgets = listOf(budget("Cibo", 500UL)),
            direction = TrackingDirection.EXPENSE,
        )
        assertEquals(1, rows.size)
        assertEquals("Cibo", rows[0].category)
        assertEquals(500UL, rows[0].budgetedSat)
        assertEquals(0UL, rows[0].actualSat)
    }

    @Test
    fun `event without matching budget surfaces with null budgetedSat`() {
        val rows = computeBudgetRows(
            events = listOf(event(100UL, PaymentDirection.OUTGOING, "Cibo")),
            budgets = emptyList(),
            direction = TrackingDirection.EXPENSE,
        )
        assertEquals(1, rows.size)
        assertNull(rows[0].budgetedSat)
        assertEquals(100UL, rows[0].actualSat)
    }

    @Test
    fun `event and budget on same category merge into one row`() {
        val rows = computeBudgetRows(
            events = listOf(event(120UL, PaymentDirection.OUTGOING, "Cibo")),
            budgets = listOf(budget("Cibo", 500UL)),
            direction = TrackingDirection.EXPENSE,
        )
        assertEquals(1, rows.size)
        assertEquals(120UL, rows[0].actualSat)
        assertEquals(500UL, rows[0].budgetedSat)
    }

    @Test
    fun `rows sort by actual descending`() {
        val events = listOf(
            event(10UL, PaymentDirection.OUTGOING, "Small"),
            event(500UL, PaymentDirection.OUTGOING, "Big"),
            event(100UL, PaymentDirection.OUTGOING, "Mid"),
        )
        val names = computeBudgetRows(events, emptyList(), TrackingDirection.EXPENSE)
            .map { it.category }
        assertEquals(listOf("Big", "Mid", "Small"), names)
    }

    @Test
    fun `multiple distinct categories aggregate independently`() {
        val events = listOf(
            event(100UL, PaymentDirection.OUTGOING, "Cibo"),
            event(50UL, PaymentDirection.OUTGOING, "Trasporti"),
            event(200UL, PaymentDirection.OUTGOING, "Cibo"),
            event(75UL, PaymentDirection.OUTGOING, "Affitto"),
        )
        val rows = computeBudgetRows(events, emptyList(), TrackingDirection.EXPENSE)
        val byCat = rows.associate { it.category to it.actualSat }
        assertEquals(300UL, byCat["Cibo"])
        assertEquals(50UL, byCat["Trasporti"])
        assertEquals(75UL, byCat["Affitto"])
    }

    @Test
    fun `category present only in budgets and category present only in events both appear`() {
        val events = listOf(event(100UL, PaymentDirection.OUTGOING, "OnlyActual"))
        val budgets = listOf(budget("OnlyBudget", 500UL))
        val rows = computeBudgetRows(events, budgets, TrackingDirection.EXPENSE)
        val cats = rows.map { it.category }.toSet()
        assertTrue("OnlyActual" in cats)
        assertTrue("OnlyBudget" in cats)
        assertEquals(2, rows.size)
    }

    @Test
    fun `events with the wrong direction do not contribute to actuals`() {
        val events = listOf(
            event(100UL, PaymentDirection.INCOMING, "Cibo"),
            event(200UL, PaymentDirection.INCOMING, "Cibo"),
        )
        val rows = computeBudgetRows(events, emptyList(), TrackingDirection.EXPENSE)
        // No outgoing events → no rows.
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `direction field on each row matches the requested direction`() {
        val events = listOf(
            event(10UL, PaymentDirection.OUTGOING, "Cibo"),
            event(20UL, PaymentDirection.OUTGOING, "Trasporti"),
        )
        val rows = computeBudgetRows(events, emptyList(), TrackingDirection.EXPENSE)
        assertTrue(rows.all { it.direction == TrackingDirection.EXPENSE })
    }

    // ── UX review 2026-10 (B3) — spending budgets never leak into Income ──

    @Test
    fun `B3 spending budgets do not appear in the income table`() {
        val events = listOf(
            event(4_000_000UL, PaymentDirection.INCOMING, "Salary"),
            event(1_480_000UL, PaymentDirection.OUTGOING, "Rent"),
        )
        val budgets = listOf(budget("Rent", 1_500_000UL), budget("Dining", 200_000UL))
        val income = computeBudgetRows(events, budgets, TrackingDirection.INCOME)
        assertEquals(listOf("Salary"), income.map { it.category })
        val expense = computeBudgetRows(events, budgets, TrackingDirection.EXPENSE)
        assertEquals(setOf("Rent", "Dining"), expense.map { it.category }.toSet())
    }

    @Test
    fun `B3 an income-side budget stays in the income table`() {
        val events = listOf(event(4_000_000UL, PaymentDirection.INCOMING, "Salary"))
        val budgets = listOf(budget("Salary", 4_100_000UL))
        val income = computeBudgetRows(events, budgets, TrackingDirection.INCOME)
        assertEquals(4_100_000UL, income.single().budgetedSat)
        assertTrue(computeBudgetRows(events, budgets, TrackingDirection.EXPENSE).isEmpty())
    }

    @Test
    fun `B3 category direction comes from the full history, not just the window`() {
        // No Salary in this (quiet) window, but historically it's income:
        // its budget must not show up as a −100% expense.
        val history = listOf(event(4_000_000UL, PaymentDirection.INCOMING, "Salary"))
        val budgets = listOf(budget("Salary", 4_000_000UL))
        val expense = computeBudgetRows(emptyList(), budgets, TrackingDirection.EXPENSE, null, history)
        assertTrue(expense.isEmpty())
        val income = computeBudgetRows(emptyList(), budgets, TrackingDirection.INCOME, null, history)
        assertEquals("Salary", income.single().category)
    }

    @Test
    fun `B3 internal transfers are neither income nor spending`() {
        val selfIn = event(1_000_000UL, PaymentDirection.INCOMING).copy(cryptoEventKind = "send_to_self")
        val rows = computeBudgetRows(listOf(selfIn), emptyList(), TrackingDirection.INCOME)
        assertTrue(rows.isEmpty())
    }

    // ── UX review 2026-10 (B4) — budgets prorated to the window ──

    @Test
    fun `B4 a monthly budget is prorated to a 90 day window`() {
        // Three monthly rents in 90 days vs a 1.5M/month budget: ~100.1%,
        // not the 296% the raw monthly amount produced.
        val events = List(3) { event(1_480_000UL, PaymentDirection.OUTGOING, "Rent") }
        val rows = computeBudgetRows(
            events, listOf(budget("Rent", 1_500_000UL)), TrackingDirection.EXPENSE, periodDays = 90.0,
        )
        val r = rows.single()
        assertEquals(1_500_000UL, r.budgetedSat) // stored amount, for the edit dialog
        assertEquals(4_435_318UL, r.budgetForPeriodSat) // round(1.5M × 90 / 30.4375 = 4_435_318.27)
        val pct = r.actualSat.toDouble() / r.budgetForPeriodSat!!.toDouble() * 100.0
        assertTrue(pct in 100.0..102.0, "got $pct%")
    }

    @Test
    fun `B4 prorating handles weekly and yearly budgets and no window`() {
        assertEquals(700UL, prorateBudget(100UL, "weekly", 49.0))
        assertEquals(1_000UL, prorateBudget(36_525UL, "yearly", 10.0))
        assertEquals(1_234UL, prorateBudget(1_234UL, "monthly", null))
    }

    @Test
    fun `B4 window days use the period length, or the event span for All`() {
        val now = 1_779_580_000L + 10 * 86_400L
        assertEquals(90.0, budgetWindowDays(TrackingPeriod.D90, emptyList(), now))
        val ev = listOf(event(1UL, PaymentDirection.OUTGOING, "x")) // ts = now − 10 days
        assertEquals(10.0, budgetWindowDays(TrackingPeriod.ALL, ev, now))
        assertNull(budgetWindowDays(TrackingPeriod.ALL, emptyList(), now))
    }

    @Test
    fun `variance arrow and sign follow actual vs budget`() {
        // Over budget: up and positive (the red colour says it is bad).
        assertEquals("↑ +6%", budgetVarianceLabel(varianceSat = 100_000, variancePct = 6))
        // Under budget: down and negative.
        assertEquals("↓ −29%", budgetVarianceLabel(varianceSat = -200_000, variancePct = -29))
        assertEquals("= 0%", budgetVarianceLabel(varianceSat = 0, variancePct = 0))
    }
}
