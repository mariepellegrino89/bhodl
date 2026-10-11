package io.bhodl.desktop.screens

import io.bhodl.ffi.LotSelectionFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * UX review 2026-10 (item 10) — [compareLotMethods]: FIFO / HIFO /
 * Specific-ID side by side, each with the lots it consumes and its
 * realized P&L (fiat + BTC). The numbers must be the ones the registry
 * books (`record_sell` / `record_sell_with_lots`).
 */
class LotMethodComparisonTest {

    // Lot A: oldest, cheapest. Lot B: middle, priciest. Lot C: newest.
    private val a = lot(id = 1, qty = 10.0, price = 100.0, purchaseUnix = 1_000, btcRate = 20_000.0)
    private val b = lot(id = 2, qty = 10.0, price = 300.0, purchaseUnix = 2_000, btcRate = 40_000.0)
    private val c = lot(id = 3, qty = 10.0, price = 200.0, purchaseUnix = 3_000, btcRate = 30_000.0)
    private val lots = listOf(c, a, b) // registry order is not assumed

    @Test
    fun `FIFO consumes the oldest lots first`() {
        val r = compareLotMethods(lots, 15.0, 250.0, 0.0, 10_000, null)!!
        val fifo = r.getValue(CostBasisMethod.FIFO)!!
        assertEquals(listOf(1L to 10.0, 2L to 5.0), fifo.consumptions.map { it.lot.id to it.qty })
        // (250-100)*10 + (250-300)*5 = 1500 - 250
        assertEquals(1_250.0, fifo.realizedFiat, 1e-9)
    }

    @Test
    fun `HIFO consumes the highest unit cost first`() {
        val r = compareLotMethods(lots, 15.0, 250.0, 0.0, 10_000, null)!!
        val hifo = r.getValue(CostBasisMethod.HIFO)!!
        assertEquals(listOf(2L to 10.0, 3L to 5.0), hifo.consumptions.map { it.lot.id to it.qty })
        // (250-300)*10 + (250-200)*5 = -500 + 250
        assertEquals(-250.0, hifo.realizedFiat, 1e-9)
    }

    @Test
    fun `HIFO ranks by price plus the purchase fee per unit, like the registry`() {
        // x: price 100 + fee 50 over 1 unit → unit cost 150; y: price 120, no fee.
        val x = lot(id = 7, qty = 1.0, price = 100.0, purchaseUnix = 1, fees = 50.0, original = 1.0)
        val y = lot(id = 8, qty = 1.0, price = 120.0, purchaseUnix = 2)
        val r = compareLotMethods(listOf(y, x), 1.0, 200.0, 0.0, 10, null)!!
        assertEquals(7L, r.getValue(CostBasisMethod.HIFO)!!.consumptions.single().lot.id)
    }

    @Test
    fun `Specific-ID is null until the user picks and then uses the picks`() {
        val none = compareLotMethods(lots, 5.0, 250.0, 0.0, 10_000, null)!!
        assertNull(none[CostBasisMethod.SPECIFIC_ID])
        val picked = compareLotMethods(
            lots, 5.0, 250.0, 0.0, 10_000, null, specificPicks = mapOf(3L to 5.0),
        )!!
        val spec = picked.getValue(CostBasisMethod.SPECIFIC_ID)!!
        assertEquals(listOf(3L to 5.0), spec.consumptions.map { it.lot.id to it.qty })
        assertEquals(250.0, spec.realizedFiat, 1e-9)
    }

    @Test
    fun `an invalid Specific-ID pick yields no outcome`() {
        val r = compareLotMethods(
            lots, 50.0, 250.0, 0.0, 10_000, null, specificPicks = mapOf(1L to 11.0),
        )!!
        assertNull(r[CostBasisMethod.SPECIFIC_ID])
    }

    @Test
    fun `sale fees are allocated pro-rata to the consumed quantities`() {
        val r = compareLotMethods(lots, 15.0, 250.0, 30.0, 10_000, null)!!
        val fifo = r.getValue(CostBasisMethod.FIFO)!!
        // 30 € of fees: 20 on lot A (10/15), 10 on lot B (5/15).
        assertEquals(1_500.0 - 20.0, fifo.consumptions[0].pnlFiat, 1e-9)
        assertEquals(-250.0 - 10.0, fifo.consumptions[1].pnlFiat, 1e-9)
        assertEquals(1_220.0, fifo.realizedFiat, 1e-9)
    }

    @Test
    fun `lots bought after the sale date are not eligible`() {
        // Sale at t=2_500: only A and B are open.
        val r = compareLotMethods(lots, 15.0, 250.0, 0.0, 2_500, null)!!
        val hifo = r.getValue(CostBasisMethod.HIFO)!!
        assertEquals(listOf(2L to 10.0, 1L to 5.0), hifo.consumptions.map { it.lot.id to it.qty })
    }

    @Test
    fun `an over-sell leaves FIFO and HIFO without an outcome`() {
        val r = compareLotMethods(lots, 31.0, 250.0, 0.0, 10_000, null)!!
        assertNull(r[CostBasisMethod.FIFO])
        assertNull(r[CostBasisMethod.HIFO])
    }

    @Test
    fun `BTC P&L follows the backfill formula and needs both rates`() {
        // Sale rate 50k: sale_btc − cost_btc per consumed lot.
        val r = compareLotMethods(lots, 15.0, 250.0, 0.0, 10_000, saleBtcRate = 50_000.0)!!
        val fifo = r.getValue(CostBasisMethod.FIFO)!!
        val expected = (10.0 * 250.0 / 50_000.0 - 10.0 * 100.0 / 20_000.0) +
            (5.0 * 250.0 / 50_000.0 - 5.0 * 300.0 / 40_000.0)
        assertEquals(expected, assertNotNull(fifo.realizedBtc), 1e-12)

        // A consumed lot without its purchase-time rate → null, never partial.
        val noRate = a.copy(btcPerUnitAtPurchase = null)
        val r2 = compareLotMethods(listOf(noRate, b), 15.0, 250.0, 0.0, 10_000, 50_000.0)!!
        assertNull(r2.getValue(CostBasisMethod.FIFO)!!.realizedBtc)
        // No sale rate → null too.
        assertNull(compareLotMethods(lots, 15.0, 250.0, 0.0, 10_000, null)!!
            .getValue(CostBasisMethod.FIFO)!!.realizedBtc)
    }

    @Test
    fun `invalid shared inputs return null`() {
        assertNull(compareLotMethods(lots, 1.0, 0.0, 0.0, 10_000, null))
        assertNull(compareLotMethods(lots, 1.0, 10.0, -1.0, 10_000, null))
        assertNull(compareLotMethods(lots, -1.0, 10.0, 0.0, 10_000, null))
    }

    @Test
    fun `the comparison agrees with the single-method preview`() {
        val r = compareLotMethods(lots, 12.0, 250.0, 5.0, 10_000, null)!!
        for (m in listOf(CostBasisMethod.FIFO, CostBasisMethod.HIFO)) {
            assertEquals(
                previewSellPnl(lots, m, 12.0, 250.0, 5.0)!!,
                r.getValue(m)!!.realizedFiat,
                1e-9,
            )
        }
    }

    // ─── sellSelectionFor ──────────────────────────────────────────

    @Test
    fun `keeping the configured FIFO or HIFO sends no explicit selection`() {
        val r = compareLotMethods(lots, 5.0, 250.0, 0.0, 10_000, null)!!
        assertNull(sellSelectionFor(CostBasisMethod.FIFO, CostBasisMethod.FIFO, r.getValue(CostBasisMethod.FIFO)!!))
        assertNull(sellSelectionFor(CostBasisMethod.HIFO, CostBasisMethod.HIFO, r.getValue(CostBasisMethod.HIFO)!!))
    }

    @Test
    fun `overriding the configured method sends that method's lots`() {
        val r = compareLotMethods(lots, 5.0, 250.0, 0.0, 10_000, null)!!
        assertEquals(
            listOf(LotSelectionFfi(lotId = 2, qty = 5.0)),
            sellSelectionFor(CostBasisMethod.HIFO, CostBasisMethod.FIFO, r.getValue(CostBasisMethod.HIFO)!!),
        )
        // A specific-ID investment always books explicit lots, even FIFO's.
        assertEquals(
            listOf(LotSelectionFfi(lotId = 1, qty = 5.0)),
            sellSelectionFor(CostBasisMethod.FIFO, CostBasisMethod.SPECIFIC_ID, r.getValue(CostBasisMethod.FIFO)!!),
        )
    }

    private fun lot(
        id: Long,
        qty: Double,
        price: Double,
        purchaseUnix: Long,
        fees: Double = 0.0,
        original: Double? = qty,
        btcRate: Double? = null,
    ) = TrackingInvestmentLotFfi(
        id = id,
        investmentId = 1,
        quantity = qty,
        pricePerUnit = price,
        feesFiat = fees,
        purchaseUnix = purchaseUnix,
        notes = null,
        createdAtUnix = 0,
        btcPerUnitAtPurchase = btcRate,
        originalQuantity = original,
    )
}
