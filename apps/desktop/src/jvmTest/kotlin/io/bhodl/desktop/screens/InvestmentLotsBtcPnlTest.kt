package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Phase 8.3.3 — pure tests for the BTC-side P&L helpers added in
 * [InvestmentLots]. The fiat counterparts ([realizedPnl], [unrealizedPnl],
 * [totalPnl]) are exercised by [InvestmentLotsTest]; this file focuses
 * on the stricter null-policy of the BTC variants:
 *  - any NULL contributing field → result is NULL (the UI then shows "—"
 *    rather than a partial / misleading sum)
 *  - [totalPnlBtc] is permissive: returns the non-null side as-is when
 *    only one side is available
 */
class InvestmentLotsBtcPnlTest {

    // ─── realizedPnlBtc ─────────────────────────────────────────────

    @Test
    fun `realizedPnlBtc empty disposals returns zero`() {
        assertEquals(0.0, realizedPnlBtc(emptyList()))
    }

    @Test
    fun `realizedPnlBtc sums all disposals`() {
        val disposals = listOf(
            sampleDisposal(gainBtc = 0.001),
            sampleDisposal(gainBtc = -0.0005),
            sampleDisposal(gainBtc = 0.002),
        )
        val result = realizedPnlBtc(disposals)
        assertNotNull(result)
        assertEquals(0.0025, result, 1e-9)
    }

    @Test
    fun `realizedPnlBtc with any null gainLossBtc returns null`() {
        // Partial Phase 8.2 backfill — one disposal has BTC, one doesn't.
        // We prefer surfacing "unknown" over a misleading partial sum.
        val disposals = listOf(
            sampleDisposal(gainBtc = 0.001),
            sampleDisposal(gainBtc = null),
        )
        assertNull(realizedPnlBtc(disposals))
    }

    @Test
    fun `realizedPnlBtc with non-finite gainLossBtc returns null`() {
        val disposals = listOf(
            sampleDisposal(gainBtc = 0.001),
            sampleDisposal(gainBtc = Double.NaN),
        )
        assertNull(realizedPnlBtc(disposals))
    }

    // ─── unrealizedPnlBtc ───────────────────────────────────────────

    @Test
    fun `unrealizedPnlBtc apple example matches storyboard`() {
        // Storyboard from Phase 8.2 §4.3:
        // Bought 10 AAPL @ $150 when BTC = $50,000 → cost basis 0.03 BTC.
        // Now AAPL = $180 with BTC = $90,000 → 10 * 180 / 90000 = 0.02 BTC.
        // Unrealized BTC P&L = 0.02 - 0.03 = -0.01 BTC.
        val lot = sampleLot(qty = 10.0, price = 150.0, btcAtPurchase = 50_000.0)
        val pnl = unrealizedPnlBtc(
            remainingLots = listOf(lot),
            currentValuePerUnit = 180.0,
            currentBtcPerUnit = 90_000.0,
        )
        assertNotNull(pnl)
        assertEquals(-0.01, pnl, 1e-9)
    }

    @Test
    fun `unrealizedPnlBtc with null current rate returns null`() {
        // The dashboard fiat doesn't match the investment's currency
        // (e.g. AAPL/USD on a EUR dashboard) → caller passes null for
        // currentBtcPerUnit. We must not extrapolate.
        val lot = sampleLot(qty = 10.0, price = 150.0, btcAtPurchase = 50_000.0)
        val pnl = unrealizedPnlBtc(
            remainingLots = listOf(lot),
            currentValuePerUnit = 180.0,
            currentBtcPerUnit = null,
        )
        assertNull(pnl)
    }

    @Test
    fun `unrealizedPnlBtc with null lot btc rate returns null`() {
        // A legacy lot (pre-Phase 8.2 backfill) has no rate → we can't
        // compute the lot's BTC cost basis → null per spec §3.2.
        val lot = sampleLot(qty = 10.0, price = 150.0, btcAtPurchase = null)
        val pnl = unrealizedPnlBtc(
            remainingLots = listOf(lot),
            currentValuePerUnit = 180.0,
            currentBtcPerUnit = 90_000.0,
        )
        assertNull(pnl)
    }

    @Test
    fun `unrealizedPnlBtc skips lots with non-positive quantity`() {
        // Fully consumed lots (qty=0) carry no remaining BTC exposure.
        // Their historical disposal is what tracks their P&L.
        val consumed = sampleLot(qty = 0.0, price = 150.0, btcAtPurchase = 50_000.0)
        val active = sampleLot(qty = 5.0, price = 150.0, btcAtPurchase = 50_000.0)
        val pnl = unrealizedPnlBtc(
            remainingLots = listOf(consumed, active),
            currentValuePerUnit = 180.0,
            currentBtcPerUnit = 90_000.0,
        )
        assertNotNull(pnl)
        // Only the active lot contributes: 5 * (180/90000 - 150/50000) = -0.005.
        assertEquals(-0.005, pnl, 1e-9)
    }

    @Test
    fun `unrealizedPnlBtc with non-finite current price returns null`() {
        val lot = sampleLot(qty = 10.0, price = 150.0, btcAtPurchase = 50_000.0)
        assertNull(
            unrealizedPnlBtc(
                remainingLots = listOf(lot),
                currentValuePerUnit = Double.NaN,
                currentBtcPerUnit = 90_000.0,
            ),
        )
    }

    // ─── totalPnlBtc ────────────────────────────────────────────────

    @Test
    fun `totalPnlBtc with both null returns null`() {
        // Caller has nothing to render → UI shows "—".
        assertNull(totalPnlBtc(realized = null, unrealized = null))
    }

    @Test
    fun `totalPnlBtc with one null returns the other`() {
        // Permissive policy: a legacy investment with backfilled
        // disposals (realized BTC available) but no remaining lots
        // (unrealized is null) still surfaces its realized BTC.
        assertEquals(0.001, totalPnlBtc(realized = 0.001, unrealized = null))
        assertEquals(0.001, totalPnlBtc(realized = null, unrealized = 0.001))
    }

    @Test
    fun `totalPnlBtc with both available sums them`() {
        assertEquals(0.003, totalPnlBtc(realized = 0.001, unrealized = 0.002)!!, 1e-9)
    }

    // ─── Fixtures ───────────────────────────────────────────────────

    private fun sampleDisposal(gainBtc: Double?): TrackingLotDisposalFfi =
        TrackingLotDisposalFfi(
            id = 1,
            sellId = 1,
            lotId = 1,
            qtyConsumed = 1.0,
            costBasisPerUnit = 100.0,
            gainLossFiat = 0.0,
            createdAtUnix = 0,
            gainLossBtc = gainBtc,
        )

    private fun sampleLot(
        qty: Double,
        price: Double,
        btcAtPurchase: Double?,
        purchaseUnix: Long = 0L,
    ): TrackingInvestmentLotFfi = TrackingInvestmentLotFfi(
        id = 1,
        investmentId = 1,
        quantity = qty,
        pricePerUnit = price,
        feesFiat = 0.0,
        purchaseUnix = purchaseUnix,
        notes = null,
        createdAtUnix = 0,
        btcPerUnitAtPurchase = btcAtPurchase,
    )
}
