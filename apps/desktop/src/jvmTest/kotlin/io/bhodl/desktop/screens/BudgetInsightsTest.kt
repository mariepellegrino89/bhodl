package io.bhodl.desktop.screens

import io.bhodl.desktop.prices.HistoricalPricePoint
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (item 12) — budget status chips and the
 * price-vs-spending split of a sats budget's move.
 */
class BudgetInsightsTest {

    private val day = 86_400L
    private val now = 1_800_000_000L

    // ─── status ──────────────────────────────────────────────────

    @Test
    fun `expense status thresholds`() {
        assertEquals(BudgetStatus.UNDER, budgetStatus(expense(actual = 79, budget = 100)))
        assertEquals(BudgetStatus.AT_RISK, budgetStatus(expense(actual = 80, budget = 100)))
        // Exactly on budget is not over.
        assertEquals(BudgetStatus.AT_RISK, budgetStatus(expense(actual = 100, budget = 100)))
        assertEquals(BudgetStatus.OVER, budgetStatus(expense(actual = 101, budget = 100)))
    }

    @Test
    fun `income status thresholds`() {
        assertEquals(BudgetStatus.BEHIND, budgetStatus(income(actual = 69, budget = 100)))
        assertEquals(BudgetStatus.AT_RISK, budgetStatus(income(actual = 70, budget = 100)))
        assertEquals(BudgetStatus.REACHED, budgetStatus(income(actual = 100, budget = 100)))
    }

    @Test
    fun `status compares against the prorated budget, not the monthly one`() {
        // 100/month over a 90-day window ≈ 296: 250 actual is at risk, not over.
        val row = BudgetRow(
            category = "Rent", budgetedSat = 100UL, actualSat = 250UL,
            direction = TrackingDirection.EXPENSE,
            budgetForPeriodSat = prorateBudget(100UL, "monthly", 90.0),
        )
        assertEquals(BudgetStatus.AT_RISK, budgetStatus(row))
    }

    @Test
    fun `no budget, no status`() {
        assertNull(budgetStatus(BudgetRow("Food", null, 10UL, TrackingDirection.EXPENSE, null)))
    }

    @Test
    fun `zero budget is over as soon as anything is spent`() {
        assertEquals(BudgetStatus.UNDER, budgetStatus(expense(actual = 0, budget = 0)))
        assertEquals(BudgetStatus.OVER, budgetStatus(expense(actual = 1, budget = 0)))
    }

    // ─── price vs spending ───────────────────────────────────────

    @Test
    fun `same fiat spending after a BTC drop is all price effect`() {
        // 50 € in each 30-day window. BTC 50k → 25k: 100k sat → 200k sat.
        val events = listOf(
            out("Food", 100_000, now - 45 * day),
            out("Food", 200_000, now - 10 * day),
        )
        val rate = { ts: Long -> if (ts < now - 30 * day) 50_000.0 else 25_000.0 }
        val m = assertNotNull(explainBudgetMove("Food", TrackingDirection.EXPENSE, events, now, 30, rate))
        assertEquals(100_000L, m.deltaSat)
        assertEquals(100_000L, m.priceEffectSat)
        assertEquals(0L, m.spendingEffectSat)
        assertEquals(-50.0, m.btcPriceChangePct, 1e-9)
        assertTrue(m.isPriceMaterial)
    }

    @Test
    fun `more fiat spending at a flat price is all spending effect`() {
        val events = listOf(
            out("Food", 100_000, now - 45 * day),
            out("Food", 150_000, now - 10 * day),
        )
        val m = explainBudgetMove("Food", TrackingDirection.EXPENSE, events, now, 30) { 40_000.0 }!!
        assertEquals(0L, m.priceEffectSat)
        assertEquals(50_000L, m.spendingEffectSat)
        assertFalse(m.isPriceMaterial)
    }

    @Test
    fun `mixed move splits and always adds up`() {
        // Prev: 100k sat at 50k = 50 €. Cur: 300k sat at 25k = 75 €.
        // Spending +25 € at the old price = +50k sat; price = the rest.
        val events = listOf(
            out("Food", 100_000, now - 40 * day),
            out("Food", 300_000, now - 5 * day),
        )
        val rate = { ts: Long -> if (ts < now - 30 * day) 50_000.0 else 25_000.0 }
        val m = explainBudgetMove("Food", TrackingDirection.EXPENSE, events, now, 30, rate)!!
        assertEquals(50_000L, m.spendingEffectSat)
        assertEquals(150_000L, m.priceEffectSat)
        assertEquals(m.deltaSat, m.priceEffectSat + m.spendingEffectSat)
    }

    @Test
    fun `each event is valued at its own day, never an average`() {
        // Two current-window events at different prices: the split uses
        // each event's own rate (volatility kept, not smoothed).
        val events = listOf(
            out("Food", 100_000, now - 40 * day), // 50 € at 50k
            out("Food", 100_000, now - 20 * day), // 40 € at 40k
            out("Food", 100_000, now - 2 * day), // 20 € at 20k
        )
        val rate = { ts: Long ->
            when {
                ts < now - 30 * day -> 50_000.0
                ts < now - 10 * day -> 40_000.0
                else -> 20_000.0
            }
        }
        val m = explainBudgetMove("Food", TrackingDirection.EXPENSE, events, now, 30, rate)!!
        // F1 = 60 € → at the previous price (50k) = 120k sat; S0 = 100k.
        assertEquals(20_000L, m.spendingEffectSat)
        assertEquals(80_000L, m.priceEffectSat)
        // Average price 60 € / 0.002 BTC = 30k vs 50k → −40%.
        assertEquals(-40.0, m.btcPriceChangePct, 1e-9)
    }

    @Test
    fun `only the category and direction count, internal transfers excluded`() {
        val events = listOf(
            out("Food", 100_000, now - 45 * day),
            out("Food", 100_000, now - 10 * day),
            out("Rent", 999_999, now - 10 * day),
            inc("Food", 555_555, now - 10 * day),
            out("Food", 777_777, now - 10 * day).apply { cryptoEventKind = "send_to_self" },
        )
        val m = explainBudgetMove("Food", TrackingDirection.EXPENSE, events, now, 30) { 30_000.0 }!!
        assertEquals(100_000L, m.previousSat)
        assertEquals(100_000L, m.currentSat)
    }

    @Test
    fun `no split without a previous window or with a missing rate`() {
        val onlyCurrent = listOf(out("Food", 100_000, now - 10 * day))
        assertNull(explainBudgetMove("Food", TrackingDirection.EXPENSE, onlyCurrent, now, 30) { 30_000.0 })
        val both = listOf(out("Food", 100_000, now - 45 * day), out("Food", 100_000, now - 10 * day))
        assertNull(
            explainBudgetMove("Food", TrackingDirection.EXPENSE, both, now, 30) { ts ->
                if (ts < now - 30 * day) null else 30_000.0
            },
        )
    }

    // ─── rate lookup ─────────────────────────────────────────────

    @Test
    fun `rate lookup takes the last price at or before the timestamp`() {
        // Daily points, given out of order.
        val t0 = 1_000_000L
        val lookup = rateLookup(
            listOf(
                HistoricalPricePoint(timestampMs = (t0 + day) * 1000, price = 20.0),
                HistoricalPricePoint(timestampMs = t0 * 1000, price = 10.0),
            ),
        )
        assertEquals(10.0, lookup(t0 + day / 2))
        assertEquals(20.0, lookup(t0 + day))
        assertEquals(20.0, lookup(t0 + 2 * day))
        // Before the series: the first price within the gap, else null.
        assertEquals(10.0, lookup(t0 - day))
        assertNull(lookup(t0 - 3 * day))
        // Far after the last point: no extrapolation.
        assertNull(lookup(t0 + 4 * day))
        assertNull(rateLookup(emptyList())(1L))
    }

    // ─── fixtures ────────────────────────────────────────────────

    private fun expense(actual: Long, budget: Long) = BudgetRow(
        "Food", budget.toULong(), actual.toULong(), TrackingDirection.EXPENSE, budget.toULong(),
    )

    private fun income(actual: Long, budget: Long) = BudgetRow(
        "Salary", budget.toULong(), actual.toULong(), TrackingDirection.INCOME, budget.toULong(),
    )

    private fun out(category: String, sat: Long, ts: Long) = event(category, sat, ts, PaymentDirection.OUTGOING)

    private fun inc(category: String, sat: Long, ts: Long) = event(category, sat, ts, PaymentDirection.INCOMING)

    private var seq = 0

    private fun event(category: String, sat: Long, ts: Long, dir: PaymentDirection) = PaymentEvent(
        walletId = "w",
        txidOrPaymentId = "tx${seq++}",
        kind = PaymentKind.ON_CHAIN,
        direction = dir,
        amountSat = sat.toULong(),
        feeSat = null,
        timestampUnix = ts.toULong(),
        blockHeight = null,
        category = category,
    )
}
