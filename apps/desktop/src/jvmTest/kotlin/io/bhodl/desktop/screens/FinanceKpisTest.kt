package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.computeFinanceKpis
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.domain.illiquidManualAssetsSat
import io.bhodl.ffi.TrackingManualAssetFfi

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7.6 — pure tests for [computeFinanceKpis].
 *
 * KPIs are the most "exposed" metric on the dashboard — wrong math
 * here means the user sees a misleading FIRE number. Pin every
 * formula tightly.
 */
class FinanceKpisTest {

    private val day = 86_400L
    private val now = 1_700_000_000L

    // ─── Empty / edge ───────────────────────────────────────────────

    @Test
    fun `empty events yields all null KPIs`() {
        val k = computeFinanceKpis(emptyList(), totalAssetSat = 100UL, nowUnix = now)
        assertNull(k.savingsRatePct)
        assertNull(k.burnRateSatPerMonth)
        assertNull(k.fireNumberSat)
        assertNull(k.runwayMonths)
    }

    @Test
    fun `events without timestamp are ignored`() {
        // Pending mempool tx — direction is known but date isn't.
        // Mustn't contribute to any window.
        val events = listOf(
            outgoing(amount = 1_000_000UL, ts = null),
            incoming(amount = 2_000_000UL, ts = null),
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertNull(k.savingsRatePct)
        assertNull(k.burnRateSatPerMonth)
    }

    @Test
    fun `future-dated events are ignored`() {
        // Defensive: a clock skew shouldn't let "future" outflows
        // inflate the burn rate window.
        val events = listOf(
            outgoing(amount = 1_000_000UL, ts = now + 10 * day),
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertNull(k.burnRateSatPerMonth)
    }

    @Test
    fun `self transfer events are ignored`() {
        // Internal moves don't change the NW; including them would
        // double-count both as income and expense.
        val events = listOf(
            PaymentEvent(
                walletId = "w1",
                txidOrPaymentId = "self",
                kind = PaymentKind.ON_CHAIN,
                direction = PaymentDirection.SELF_TRANSFER,
                amountSat = 5_000_000UL,
                feeSat = null,
                timestampUnix = (now - 5L * day).toULong(),
                blockHeight = null,
                category = null,
            ),
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertNull(k.savingsRatePct)
        assertNull(k.burnRateSatPerMonth)
    }

    // ─── Savings rate ──────────────────────────────────────────────

    @Test
    fun `savings rate 50 percent when expense is half of income`() {
        // €4000 income, €2000 expense in last 30d → 50% savings.
        val events = listOf(
            incoming(amount = 4_000_000UL, ts = now - 5 * day),
            outgoing(amount = 2_000_000UL, ts = now - 4 * day),
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertEquals(50, k.savingsRatePct)
    }

    @Test
    fun `savings rate negative when expense exceeds income`() {
        val events = listOf(
            incoming(amount = 1_000_000UL, ts = now - 5 * day),
            outgoing(amount = 3_000_000UL, ts = now - 4 * day),
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        // (1 - 3) / 1 × 100 = -200%
        assertEquals(-200, k.savingsRatePct)
    }

    @Test
    fun `savings rate null when income is zero`() {
        // Outflow only — savings rate undefined (would divide by 0).
        val events = listOf(outgoing(amount = 1_000_000UL, ts = now - 5 * day))
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertNull(k.savingsRatePct)
    }

    @Test
    fun `savings rate uses only last 30 days window`() {
        val events = listOf(
            // Outside the 30d window → ignored for savings rate.
            incoming(amount = 10_000_000UL, ts = now - 40 * day),
            // Inside.
            incoming(amount = 1_000_000UL, ts = now - 5 * day),
            outgoing(amount = 500_000UL, ts = now - 3 * day),
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        // Only the in-window ones: 50% savings.
        assertEquals(50, k.savingsRatePct)
    }

    // ─── Burn rate ─────────────────────────────────────────────────

    @Test
    fun `burn rate is daily outflow times 30 over 90d window`() {
        // 9M sat outflow spread across 90d → 100k sat/day → 3M /month.
        // Formula: expense90 / 3 = 9_000_000 / 3 = 3_000_000
        val events = listOf(
            outgoing(amount = 3_000_000UL, ts = now - 10 * day),
            outgoing(amount = 3_000_000UL, ts = now - 40 * day),
            outgoing(amount = 3_000_000UL, ts = now - 70 * day),
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertEquals(3_000_000UL, k.burnRateSatPerMonth)
    }

    @Test
    fun `burn rate excludes outflows older than 90 days`() {
        val events = listOf(
            outgoing(amount = 100_000_000UL, ts = now - 100 * day),  // ignored
            outgoing(amount = 3_000_000UL, ts = now - 10 * day),     // in window
        )
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        // expense90 = 3M; / 3 = 1M
        assertEquals(1_000_000UL, k.burnRateSatPerMonth)
    }

    @Test
    fun `burn rate null when zero outflow in 90 days`() {
        val events = listOf(incoming(amount = 5_000_000UL, ts = now - 5 * day))
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertNull(k.burnRateSatPerMonth)
    }

    // ─── FIRE number ───────────────────────────────────────────────

    @Test
    fun `FIRE number is 300 times burn rate`() {
        // burn = 1M sat/month → FIRE = 300M sat
        val events = listOf(outgoing(amount = 3_000_000UL, ts = now - 5 * day))
        val k = computeFinanceKpis(events, totalAssetSat = 0UL, nowUnix = now)
        assertEquals(1_000_000UL * 300UL, k.fireNumberSat)
    }

    @Test
    fun `FIRE number null when burn rate null`() {
        val k = computeFinanceKpis(emptyList(), totalAssetSat = 0UL, nowUnix = now)
        assertNull(k.fireNumberSat)
    }

    // ─── Runway ────────────────────────────────────────────────────

    @Test
    fun `runway is total asset over burn rate`() {
        // burn = 1M sat/month, NW = 12M sat → 12 months runway.
        val events = listOf(outgoing(amount = 3_000_000UL, ts = now - 5 * day))
        val k = computeFinanceKpis(events, totalAssetSat = 12_000_000UL, nowUnix = now)
        assertNotNull(k.runwayMonths)
        assertEquals(12.0, k.runwayMonths!!, 0.001)
    }

    @Test
    fun `runway null when burn rate null`() {
        // No outflow → unlimited runway. We surface null so the UI
        // can render "∞" or "—" rather than a misleading huge number.
        val events = listOf(incoming(amount = 5_000_000UL, ts = now - 5 * day))
        val k = computeFinanceKpis(events, totalAssetSat = 12_000_000UL, nowUnix = now)
        assertNull(k.runwayMonths)
    }

    @Test
    fun `runway can be fractional`() {
        val events = listOf(outgoing(amount = 3_000_000UL, ts = now - 5 * day))
        val k = computeFinanceKpis(events, totalAssetSat = 1_500_000UL, nowUnix = now)
        // burn = 1_000_000/month, NW = 1_500_000 → 1.5 months.
        assertNotNull(k.runwayMonths)
        assertTrue(kotlin.math.abs(k.runwayMonths!! - 1.5) < 0.001)
    }

    // ─── Internal transfers / liquidity (M5 + runway fix) ───────────

    @Test
    fun `send_to_self counts only its fee as outflow`() {
        val transfer = outgoing(amount = 3_000_000UL, ts = now - 5 * day).also {
            it.cryptoEventKind = SEND_TO_SELF
            it.feeSat = 3_000UL
        }
        val income = incoming(amount = 10_000_000UL, ts = now - 5 * day).also {
            // An incoming leg of an internal transfer is not income.
        }
        val selfIn = incoming(amount = 2_997_000UL, ts = now - 4 * day).also {
            it.cryptoEventKind = SEND_TO_SELF
        }
        val k = computeFinanceKpis(listOf(transfer, income, selfIn), totalAssetSat = 1UL, nowUnix = now)
        // expense90 = 3_000 → burn = 1_000/month; income30 = 10M only.
        assertEquals(1_000UL, k.burnRateSatPerMonth)
        assertEquals(99, k.savingsRatePct)
    }

    @Test
    fun `runway excludes illiquid assets`() {
        // burn = 1M/month, NW 12M of which 9M is a house → 3 months.
        val events = listOf(outgoing(amount = 3_000_000UL, ts = now - 5 * day))
        val k = computeFinanceKpis(
            events, totalAssetSat = 12_000_000UL, nowUnix = now, illiquidAssetSat = 9_000_000UL,
        )
        assertEquals(3.0, k.runwayMonths!!, 0.001)
        // Illiquid > total clamps to 0 instead of underflowing.
        val k0 = computeFinanceKpis(events, totalAssetSat = 1UL, nowUnix = now, illiquidAssetSat = 5UL)
        assertEquals(0.0, k0.runwayMonths!!, 0.001)
    }

    @Test
    fun `illiquidManualAssetsSat skips cash and brokerage`() {
        val assets = listOf("cash", "brokerage", "real_estate", "vehicle", "collectible").mapIndexed { i, k ->
            TrackingManualAssetFfi(
                id = i.toLong(), name = k, kind = k, valueFiat = 1.0, currency = "EUR",
                displayLabel = null, notes = null, lastUpdatedUnix = 0, createdAtUnix = 0,
            )
        }
        assertEquals(300UL, illiquidManualAssetsSat(assets) { 100UL })
    }

    // ─── Test fixtures ─────────────────────────────────────────────

    private fun outgoing(amount: ULong, ts: Long?): PaymentEvent = PaymentEvent(
        walletId = "w1",
        txidOrPaymentId = "out-${ts ?: "pending"}-$amount",
        kind = PaymentKind.ON_CHAIN,
        direction = PaymentDirection.OUTGOING,
        amountSat = amount,
        feeSat = null,
        timestampUnix = ts?.toULong(),
        blockHeight = null,
        category = null,
    )

    private fun incoming(amount: ULong, ts: Long?): PaymentEvent = PaymentEvent(
        walletId = "w1",
        txidOrPaymentId = "in-${ts ?: "pending"}-$amount",
        kind = PaymentKind.ON_CHAIN,
        direction = PaymentDirection.INCOMING,
        amountSat = amount,
        feeSat = null,
        timestampUnix = ts?.toULong(),
        blockHeight = null,
        category = null,
    )
}
