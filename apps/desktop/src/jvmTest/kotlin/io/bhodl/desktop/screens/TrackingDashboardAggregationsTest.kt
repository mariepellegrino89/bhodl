package io.bhodl.desktop.screens

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3.5 — pure tests for the dashboard aggregations.
 *
 * No Compose, no FFI loaded — `PaymentEvent` is a `uniffi::Record`
 * and constructs as a plain data class on the JVM, so we can build
 * fixtures here without standing up the real wallet stack. The
 * functions under test live in `TrackingDashboard.kt` (same
 * package, hence direct access to the `internal` API).
 */
class TrackingDashboardAggregationsTest {

    /** Anchor `now` so the period windows are deterministic.
     *  2026-05-15T00:00:00Z = 1779580800. */
    private val nowUnix: Long = 1779580800L

    private fun event(
        wallet: String = "w",
        txid: String = "tx-${(0..9999).random()}",
        amount: ULong,
        direction: PaymentDirection,
        ts: Long?,
        category: String? = null,
    ): PaymentEvent = PaymentEvent(
        walletId = wallet,
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = direction,
        amountSat = amount,
        feeSat = null,
        timestampUnix = ts?.toULong(),
        blockHeight = null,
        category = category,
    )

    @Test
    fun `periodLowerBound — ALL returns null`() {
        assertNull(periodLowerBound(TrackingPeriod.ALL, nowUnix))
    }

    @Test
    fun `periodLowerBound — D7 is now minus 7 days`() {
        val lower = periodLowerBound(TrackingPeriod.D7, nowUnix)
        assertEquals(nowUnix - 7L * 86_400L, lower)
    }

    @Test
    fun `filterByPeriod keeps mempool events regardless of period`() {
        val mempool = event(amount = 1_000u, direction = PaymentDirection.INCOMING, ts = null)
        val recent = event(
            amount = 500u,
            direction = PaymentDirection.OUTGOING,
            ts = nowUnix - 86_400L, // 1 day ago — inside D7
        )
        val ancient = event(
            amount = 9999u,
            direction = PaymentDirection.INCOMING,
            ts = nowUnix - 30L * 86_400L, // 30 days ago — outside D7
        )
        val out = filterByPeriod(listOf(mempool, recent, ancient), TrackingPeriod.D7, nowUnix)
        assertEquals(2, out.size)
        assertTrue(out.contains(mempool))
        assertTrue(out.contains(recent))
    }

    @Test
    fun `balanceSeries empty input returns empty`() {
        assertTrue(balanceSeries(emptyList(), TrackingPeriod.D30, nowUnix).isEmpty())
    }

    @Test
    fun `balanceSeries cumulates and extends to now`() {
        val events = listOf(
            event(
                amount = 1_000u,
                direction = PaymentDirection.INCOMING,
                ts = nowUnix - 5L * 86_400L,
            ),
            event(
                amount = 200u,
                direction = PaymentDirection.OUTGOING,
                ts = nowUnix - 2L * 86_400L,
            ),
        )
        val series = balanceSeries(events, TrackingPeriod.D30, nowUnix)
        // Should produce at least the two real points plus the
        // extended `now` anchor; balances should be cumulative.
        assertTrue(series.size >= 3)
        assertEquals(nowUnix, series.last().tsUnix)
        assertEquals(800L, series.last().balanceSat) // +1000 then -200
    }

    @Test
    fun `balanceSeries flat window still draws a baseline`() {
        // No events in the last 7 days — series should anchor a flat
        // line at the current cumulative balance so the chart isn't
        // empty.
        val events = listOf(
            event(
                amount = 5_000u,
                direction = PaymentDirection.INCOMING,
                ts = nowUnix - 60L * 86_400L,
            ),
        )
        val series = balanceSeries(events, TrackingPeriod.D7, nowUnix)
        assertEquals(2, series.size)
        assertEquals(5_000L, series.first().balanceSat)
        assertEquals(5_000L, series.last().balanceSat)
    }

    @Test
    fun `categorySlices buckets by category and ranks by volume`() {
        val events = listOf(
            event(
                amount = 100u,
                direction = PaymentDirection.INCOMING,
                ts = nowUnix - 3600L,
                category = "Food",
            ),
            event(
                amount = 50u,
                direction = PaymentDirection.OUTGOING,
                ts = nowUnix - 7200L,
                category = "Food",
            ),
            event(
                amount = 1_000u,
                direction = PaymentDirection.OUTGOING,
                ts = nowUnix - 5_000L,
                category = "Rent",
            ),
            event(
                amount = 25u,
                direction = PaymentDirection.OUTGOING,
                ts = nowUnix - 9_000L,
                category = null,
            ),
        )
        val slices = categorySlices(events)
        assertEquals(3, slices.size)
        // Largest volume first.
        assertEquals("Rent", slices[0].category)
        assertEquals(1_000u, slices[0].outgoingSat)
        assertEquals(0u, slices[0].incomingSat)
        // null/blank bucket lands as "".
        val empty = slices.first { it.category == "" }
        assertEquals(25u, empty.outgoingSat)
        assertEquals(1, empty.count)
        // Food sums in + out into volume.
        val food = slices.first { it.category == "Food" }
        assertEquals(100u, food.incomingSat)
        assertEquals(50u, food.outgoingSat)
        assertEquals(150u, food.volumeSat)
    }

    @Test
    fun `computeKpis splits inflow and outflow ignoring self-transfer`() {
        val events = listOf(
            event(amount = 1_000u, direction = PaymentDirection.INCOMING, ts = nowUnix - 100L),
            event(amount = 250u, direction = PaymentDirection.OUTGOING, ts = nowUnix - 200L),
            event(amount = 50u, direction = PaymentDirection.SELF_TRANSFER, ts = nowUnix - 300L),
        )
        val kpis = computeKpis(events, currentBalanceSat = 9_999u)
        assertEquals(9_999u, kpis.currentBalanceSat)
        assertEquals(1_000u, kpis.inflowSat)
        assertEquals(250u, kpis.outflowSat)
        assertEquals(750L, kpis.netSat)
        assertEquals(3, kpis.txCount)
    }

    @Test
    fun `flowBars D7 buckets daily and totals match raw events`() {
        val events = listOf(
            event(amount = 100u, direction = PaymentDirection.INCOMING, ts = nowUnix - 86_400L),
            event(amount = 50u, direction = PaymentDirection.OUTGOING, ts = nowUnix - 3 * 86_400L),
            event(amount = 200u, direction = PaymentDirection.INCOMING, ts = nowUnix - 5 * 86_400L),
        )
        val bars = flowBars(events, TrackingPeriod.D7, nowUnix)
        // D7 → 7-day span / DAY unit → 7 buckets.
        assertEquals(7, bars.size)
        val totalIn = bars.sumOf { it.inflowSat.toLong() }
        val totalOut = bars.sumOf { it.outflowSat.toLong() }
        assertEquals(300L, totalIn)
        assertEquals(50L, totalOut)
    }

    @Test
    fun `flowBars empty input returns empty`() {
        assertTrue(flowBars(emptyList(), TrackingPeriod.D30, nowUnix).isEmpty())
    }

    @Test
    fun `flowBars D90 buckets weekly`() {
        val events = listOf(
            event(
                amount = 5u,
                direction = PaymentDirection.OUTGOING,
                ts = nowUnix - 30L * 86_400L,
            ),
        )
        val bars = flowBars(events, TrackingPeriod.D90, nowUnix)
        // 90-day span / 7-day unit → ~13 buckets, capped at 24.
        assertTrue(bars.size in 12..14)
    }

    @Test
    fun `pickBucketUnit ALL adapts to event span`() {
        // Span of one week → daily buckets.
        assertEquals(FlowBucketUnit.DAY, pickBucketUnit(TrackingPeriod.ALL, 7L * 86_400L))
        // Span of 60 days → weekly buckets.
        assertEquals(FlowBucketUnit.WEEK, pickBucketUnit(TrackingPeriod.ALL, 60L * 86_400L))
        // Span of one year → monthly buckets.
        assertEquals(FlowBucketUnit.MONTH, pickBucketUnit(TrackingPeriod.ALL, 300L * 86_400L))
        // Span of 5 years → quarterly buckets.
        assertEquals(FlowBucketUnit.QUARTER, pickBucketUnit(TrackingPeriod.ALL, 5L * 365L * 86_400L))
    }

    @Test
    fun `flowBars ALL with concentrated events produces readable buckets`() {
        // Regression: ALL with a single month of activity used to
        // collapse into 12 fixed buckets, leaving 11 empty. Now the
        // ALL window auto-picks DAY and produces ~30 daily buckets.
        val events = listOf(
            event(amount = 100u, direction = PaymentDirection.INCOMING, ts = nowUnix - 5L * 86_400L),
            event(amount = 200u, direction = PaymentDirection.INCOMING, ts = nowUnix - 3L * 86_400L),
            event(amount = 50u, direction = PaymentDirection.OUTGOING, ts = nowUnix - 1L * 86_400L),
        )
        val bars = flowBars(events, TrackingPeriod.ALL, nowUnix)
        // Span = 5 days → DAY unit → 5..6 buckets.
        assertTrue(bars.size in 4..7, "expected ~5 daily buckets, got ${bars.size}")
        // At least one bucket has inflow and one has outflow.
        assertTrue(bars.any { it.inflowSat > 0u })
        assertTrue(bars.any { it.outflowSat > 0u })
    }

    @Test
    fun `categorySlices empty input returns empty`() {
        assertTrue(categorySlices(emptyList()).isEmpty())
    }

    @Test
    fun `balanceSeries handles flat series across the whole history`() {
        val events = listOf(
            event(
                amount = 100u,
                direction = PaymentDirection.SELF_TRANSFER,
                ts = nowUnix - 100L,
            ),
            event(
                amount = 100u,
                direction = PaymentDirection.SELF_TRANSFER,
                ts = nowUnix - 50L,
            ),
        )
        val series = balanceSeries(events, TrackingPeriod.ALL, nowUnix)
        assertNotNull(series)
        // Self-transfers contribute zero — running balance stays at 0
        // throughout, plus optional `now` extension.
        assertTrue(series.all { it.balanceSat == 0L })
    }
}
