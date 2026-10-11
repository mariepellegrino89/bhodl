package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7.8 — pure tests for the lot aggregation helpers + the
 * disposal-order policy. The aggregates are what the UI shows on
 * every row; the disposal order is the contract for Phase 7.9 sells.
 */
class InvestmentLotsTest {

    // ─── CostBasisMethod.fromWire ──────────────────────────────────

    @Test
    fun `fromWire maps each known method`() {
        assertEquals(CostBasisMethod.FIFO, CostBasisMethod.fromWire("fifo"))
        assertEquals(CostBasisMethod.HIFO, CostBasisMethod.fromWire("hifo"))
        assertEquals(CostBasisMethod.SPECIFIC_ID, CostBasisMethod.fromWire("specific_id"))
    }

    @Test
    fun `fromWire falls back to FIFO for null and unknown`() {
        assertEquals(CostBasisMethod.FIFO, CostBasisMethod.fromWire(null))
        assertEquals(CostBasisMethod.FIFO, CostBasisMethod.fromWire(""))
        assertEquals(CostBasisMethod.FIFO, CostBasisMethod.fromWire("lifo"))
    }

    // ─── effectiveCostBasis ────────────────────────────────────────

    @Test
    fun `effectiveCostBasis empty lots falls back to legacy aggregate`() {
        val inv = sampleInv(quantity = 10.0, costBasisFiat = 1_500.0)
        assertEquals(1_500.0, effectiveCostBasis(inv, emptyList()), 0.001)
    }

    @Test
    fun `effectiveCostBasis sums quantity times price plus fees`() {
        val inv = sampleInv(quantity = 0.0, costBasisFiat = 0.0)  // post-seed
        val lots = listOf(
            sampleLot(qty = 10.0, price = 100.0, fees = 5.0),
            sampleLot(qty = 5.0, price = 200.0, fees = 3.0),
        )
        // (10*100 + 5) + (5*200 + 3) = 1005 + 1003 = 2008
        assertEquals(2_008.0, effectiveCostBasis(inv, lots), 0.001)
    }

    @Test
    fun `effectiveCostBasis skips lots with non-finite numbers`() {
        val inv = sampleInv(quantity = 0.0, costBasisFiat = 0.0)
        val lots = listOf(
            sampleLot(qty = Double.NaN, price = 100.0, fees = 0.0),
            sampleLot(qty = 1.0, price = Double.POSITIVE_INFINITY, fees = 0.0),
            sampleLot(qty = 10.0, price = 100.0, fees = 0.0),  // only this counts
        )
        assertEquals(1_000.0, effectiveCostBasis(inv, lots), 0.001)
    }

    // ─── effectiveQuantity ─────────────────────────────────────────

    @Test
    fun `effectiveQuantity empty lots falls back to legacy field`() {
        val inv = sampleInv(quantity = 7.5, costBasisFiat = 100.0)
        assertEquals(7.5, effectiveQuantity(inv, emptyList()), 0.001)
    }

    @Test
    fun `effectiveQuantity sums lot quantities`() {
        val inv = sampleInv(quantity = 0.0, costBasisFiat = 0.0)
        val lots = listOf(
            sampleLot(qty = 10.0, price = 1.0, fees = 0.0),
            sampleLot(qty = 5.5, price = 1.0, fees = 0.0),
        )
        assertEquals(15.5, effectiveQuantity(inv, lots), 0.001)
    }

    // ─── lotsSortedForDisposal ─────────────────────────────────────

    @Test
    fun `FIFO sorts by purchase unix ascending`() {
        val a = sampleLot(qty = 1.0, price = 100.0, fees = 0.0, purchaseUnix = 3_000)
        val b = sampleLot(qty = 1.0, price = 200.0, fees = 0.0, purchaseUnix = 1_000)
        val c = sampleLot(qty = 1.0, price = 300.0, fees = 0.0, purchaseUnix = 2_000)
        val sorted = lotsSortedForDisposal(listOf(a, b, c), CostBasisMethod.FIFO)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), sorted.map { it.purchaseUnix })
    }

    @Test
    fun `HIFO sorts by price per unit descending`() {
        val a = sampleLot(qty = 1.0, price = 100.0, fees = 0.0, purchaseUnix = 3_000)
        val b = sampleLot(qty = 1.0, price = 300.0, fees = 0.0, purchaseUnix = 1_000)
        val c = sampleLot(qty = 1.0, price = 200.0, fees = 0.0, purchaseUnix = 2_000)
        val sorted = lotsSortedForDisposal(listOf(a, b, c), CostBasisMethod.HIFO)
        assertEquals(listOf(300.0, 200.0, 100.0), sorted.map { it.pricePerUnit })
    }

    @Test
    fun `SPECIFIC_ID lists lots oldest first in the picker`() {
        // Specific-ID sales are booked from the user's explicit picks;
        // the ordering only drives the lot picker's display order.
        val a = sampleLot(qty = 1.0, price = 100.0, fees = 0.0, purchaseUnix = 3_000)
        val b = sampleLot(qty = 1.0, price = 300.0, fees = 0.0, purchaseUnix = 1_000)
        val c = sampleLot(qty = 1.0, price = 200.0, fees = 0.0, purchaseUnix = 2_000)
        val sorted = lotsSortedForDisposal(listOf(a, b, c), CostBasisMethod.SPECIFIC_ID)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), sorted.map { it.purchaseUnix })
    }

    // ─── Fee-inclusive basis (M4) ──────────────────────────────────

    @Test
    fun `lotFeePerUnit spreads fees over the original quantity`() {
        val lot = sampleLot(qty = 6.0, price = 100.0, fees = 20.0, id = 7)
        // Never sold: original == remaining.
        assertEquals(20.0 / 6.0, lotFeePerUnit(lot), 1e-9)
        // 4 units were sold from this lot earlier → original = 10.
        val disposals = listOf(sampleDisposal(gainLoss = 0.0, lotId = 7, qty = 4.0))
        assertEquals(2.0, lotFeePerUnit(lot, disposals), 1e-9)
        // Disposals of other lots don't count.
        assertEquals(20.0 / 6.0, lotFeePerUnit(lot, listOf(sampleDisposal(0.0, lotId = 8, qty = 4.0))), 1e-9)
    }

    @Test
    fun `lotFeePerUnit prefers the registry originalQuantity over reconstruction`() {
        // Registry schema v2 exposes the frozen purchased size; it wins
        // even when the disposal list is incomplete / absent.
        val lot = sampleLot(qty = 6.0, price = 100.0, fees = 20.0, id = 7).copy(originalQuantity = 10.0)
        assertEquals(2.0, lotFeePerUnit(lot), 1e-9)
        val disposals = listOf(sampleDisposal(gainLoss = 0.0, lotId = 7, qty = 1.0))
        assertEquals(2.0, lotFeePerUnit(lot, disposals), 1e-9)
    }

    @Test
    fun `lotFeePerUnit is zero for non-finite or zero fees`() {
        assertEquals(0.0, lotFeePerUnit(sampleLot(qty = 1.0, price = 1.0, fees = Double.NaN)))
        assertEquals(0.0, lotFeePerUnit(sampleLot(qty = 1.0, price = 1.0, fees = 0.0)))
        assertEquals(0.0, lotFeePerUnit(sampleLot(qty = 0.0, price = 1.0, fees = 5.0)))
    }

    @Test
    fun `unrealizedPnl includes purchase fees in the basis`() {
        // (150 - (100 + 10/10)) * 10 = 490
        val lots = listOf(sampleLot(qty = 10.0, price = 100.0, fees = 10.0))
        assertEquals(490.0, unrealizedPnl(lots, currentValuePerUnit = 150.0)!!, 1e-9)
    }

    @Test
    fun `previewSellPnl mirrors backend FIFO with lot and sale fees`() {
        val old = sampleLot(qty = 10.0, price = 100.0, fees = 10.0, purchaseUnix = 1_000, id = 1)
        val new = sampleLot(qty = 10.0, price = 200.0, fees = 0.0, purchaseUnix = 2_000, id = 2)
        // Sell 15 @ 300 with 30 fees. FIFO: 10 from old, 5 from new.
        // old: (300 - 101) * 10 - 30*(10/15) = 1990 - 20 = 1970
        // new: (300 - 200) * 5  - 30*(5/15)  = 500 - 10  = 490
        val pnl = previewSellPnl(listOf(new, old), CostBasisMethod.FIFO, 15.0, 300.0, 30.0)
        assertEquals(2460.0, pnl!!, 1e-9)
    }

    // ─── Specific-ID lot picker ────────────────────────────────────

    @Test
    fun `validateLotSelection accepts picks within the remaining quantities`() {
        val a = sampleLot(qty = 10.0, price = 100.0, fees = 0.0, purchaseUnix = 1_000, id = 1)
        val b = sampleLot(qty = 5.0, price = 200.0, fees = 0.0, purchaseUnix = 2_000, id = 2)
        val check = validateLotSelection(listOf(a, b), mapOf(2L to 5.0, 1L to 0.0), saleUnix = 3_000)
        assertEquals(LotSelectionCheck.Valid(total = 5.0, picks = mapOf(2L to 5.0)), check)
    }

    @Test
    fun `validateLotSelection is Empty when nothing is picked`() {
        val a = sampleLot(qty = 10.0, price = 100.0, fees = 0.0, id = 1)
        assertEquals(LotSelectionCheck.Empty, validateLotSelection(listOf(a), emptyMap(), 1_000))
        assertEquals(LotSelectionCheck.Empty, validateLotSelection(listOf(a), mapOf(1L to 0.0), 1_000))
    }

    @Test
    fun `validateLotSelection rejects over-picks, bad numbers, unknown and future lots`() {
        val a = sampleLot(qty = 10.0, price = 100.0, fees = 0.0, purchaseUnix = 1_000, id = 1)
        val future = sampleLot(qty = 10.0, price = 100.0, fees = 0.0, purchaseUnix = 9_000, id = 2)
        val lots = listOf(a, future)
        for (inputs in listOf(
            mapOf(1L to 10.5),          // more than the lot holds
            mapOf(1L to null),          // unparseable
            mapOf(1L to -1.0),          // negative
            mapOf(1L to Double.NaN),    // non-finite
            mapOf(3L to 1.0),           // not a lot of this investment
            mapOf(2L to 1.0),           // bought after the sale date
        )) {
            assertTrue(
                validateLotSelection(lots, inputs, saleUnix = 5_000) is LotSelectionCheck.Invalid,
                "expected Invalid for $inputs",
            )
        }
    }

    @Test
    fun `previewSelectionPnl books exactly the picked lots with fees`() {
        val old = sampleLot(qty = 10.0, price = 100.0, fees = 10.0, purchaseUnix = 1_000, id = 1)
        val new = sampleLot(qty = 10.0, price = 200.0, fees = 0.0, purchaseUnix = 2_000, id = 2)
        // Pick 5 from the NEWER lot and 1 from the older, sell @ 300 with 6 fees.
        // new: (300 - 200) * 5 - 6*(5/6) = 500 - 5 = 495
        // old: (300 - 101) * 1 - 6*(1/6) = 199 - 1 = 198
        val pnl = previewSelectionPnl(listOf(old, new), mapOf(2L to 5.0, 1L to 1.0), 300.0, 6.0)
        assertEquals(693.0, pnl!!, 1e-9)
        assertNull(previewSelectionPnl(listOf(old), mapOf(1L to 1.0), Double.NaN, 0.0))
        assertNull(previewSelectionPnl(listOf(old), emptyMap(), 300.0, 0.0))
        assertNull(previewSelectionPnl(listOf(old), mapOf(9L to 1.0), 300.0, 0.0))
    }

    @Test
    fun `previewSellPnl rejects invalid inputs`() {
        val lots = listOf(sampleLot(qty = 1.0, price = 1.0, fees = 0.0))
        assertNull(previewSellPnl(lots, CostBasisMethod.FIFO, 0.0, 1.0, 0.0))
        assertNull(previewSellPnl(lots, CostBasisMethod.FIFO, 1.0, Double.NaN, 0.0))
        assertNull(previewSellPnl(lots, CostBasisMethod.FIFO, 1.0, 1.0, -1.0))
    }

    @Test
    fun `lotsSortedForDisposal empty input returns empty for all methods`() {
        for (m in CostBasisMethod.values()) {
            assertEquals(emptyList(), lotsSortedForDisposal(emptyList(), m))
        }
    }

    // ─── P&L helpers (Phase 7.9) ───────────────────────────────────

    @Test
    fun `realizedPnl empty disposals returns zero`() {
        assertEquals(0.0, realizedPnl(emptyList()), 0.001)
    }

    @Test
    fun `realizedPnl sums gain_loss correctly`() {
        val disposals = listOf(
            sampleDisposal(gainLoss = 150.0),
            sampleDisposal(gainLoss = -50.0),
            sampleDisposal(gainLoss = 200.0),
        )
        assertEquals(300.0, realizedPnl(disposals), 0.001)
    }

    @Test
    fun `realizedPnl skips NaN gain_loss`() {
        // Defensive: a malformed disposal row shouldn't poison the sum.
        val disposals = listOf(
            sampleDisposal(gainLoss = 100.0),
            sampleDisposal(gainLoss = Double.NaN),
        )
        assertEquals(100.0, realizedPnl(disposals), 0.001)
    }

    @Test
    fun `unrealizedPnl null when no current price`() {
        val lots = listOf(sampleLot(qty = 10.0, price = 100.0, fees = 0.0))
        assertNull(unrealizedPnl(lots, currentValuePerUnit = null))
    }

    @Test
    fun `unrealizedPnl null when current price not finite`() {
        val lots = listOf(sampleLot(qty = 10.0, price = 100.0, fees = 0.0))
        assertNull(unrealizedPnl(lots, currentValuePerUnit = Double.NaN))
    }

    @Test
    fun `unrealizedPnl computes price minus cost times qty`() {
        val lots = listOf(
            sampleLot(qty = 10.0, price = 100.0, fees = 0.0),   // (150 - 100) * 10 = 500
            sampleLot(qty = 5.0, price = 200.0, fees = 0.0),    // (150 - 200) * 5 = -250
        )
        val pnl = unrealizedPnl(lots, currentValuePerUnit = 150.0)
        assertNotNull(pnl)
        assertEquals(250.0, pnl, 0.001)
    }

    @Test
    fun `unrealizedPnl skips lots with zero quantity`() {
        // Lots fully consumed by past sells (qty = 0) shouldn't
        // contribute to unrealized — only their disposals are.
        val lots = listOf(
            sampleLot(qty = 0.0, price = 100.0, fees = 0.0),
            sampleLot(qty = 5.0, price = 200.0, fees = 0.0),
        )
        val pnl = unrealizedPnl(lots, currentValuePerUnit = 250.0)
        assertNotNull(pnl)
        // Only the 5-unit lot counts: (250 - 200) * 5 = 250.
        assertEquals(250.0, pnl, 0.001)
    }

    @Test
    fun `totalPnl is realized plus unrealized`() {
        assertEquals(150.0, totalPnl(realized = 100.0, unrealized = 50.0), 0.001)
    }

    @Test
    fun `totalPnl returns realized when unrealized is null`() {
        // Null current price → unrealized is null. We don't want the
        // whole P&L number to disappear — the realized portion is
        // still a real number, surface it.
        assertEquals(100.0, totalPnl(realized = 100.0, unrealized = null), 0.001)
    }

    // ─── Test fixtures ─────────────────────────────────────────────

    private fun sampleInv(
        quantity: Double,
        costBasisFiat: Double,
    ): TrackingInvestmentFfi = TrackingInvestmentFfi(
        id = 1,
        name = "test",
        assetType = "stock",
        ticker = "TST",
        quantity = quantity,
        unit = "",
        costBasisFiat = costBasisFiat,
        costBasisCurrency = "EUR",
        purchaseUnix = 0,
        manualCurrentValue = null,
        fetchedCurrentValue = null,
        lastPricedAtUnix = null,
        notes = null,
        costBasisMethod = "fifo",
        createdAtUnix = 0,
    )

    private fun sampleDisposal(
        gainLoss: Double,
        lotId: Long = 1,
        qty: Double = 1.0,
    ): TrackingLotDisposalFfi =
        TrackingLotDisposalFfi(
            id = 1,
            sellId = 1,
            lotId = lotId,
            qtyConsumed = qty,
            costBasisPerUnit = 100.0,
            gainLossFiat = gainLoss,
            createdAtUnix = 0,
        )

    private fun sampleLot(
        qty: Double,
        price: Double,
        fees: Double,
        purchaseUnix: Long = 0L,
        id: Long = 1,
    ): TrackingInvestmentLotFfi = TrackingInvestmentLotFfi(
        id = id,
        investmentId = 1,
        quantity = qty,
        pricePerUnit = price,
        feesFiat = fees,
        purchaseUnix = purchaseUnix,
        notes = null,
        createdAtUnix = 0,
    )
}
