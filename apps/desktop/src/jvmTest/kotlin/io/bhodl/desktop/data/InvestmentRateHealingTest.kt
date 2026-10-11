package io.bhodl.desktop.data

import io.bhodl.desktop.screens.realizedPnlBtc
import io.bhodl.desktop.screens.unrealizedPnlBtc
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingInvestmentSellFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (B9) — lots / sells whose historical BTC rate was
 * never resolved showed "no rate" and "—" in P&L BTC forever, even when
 * the rate for the purchase date was available.
 */
class InvestmentRateHealingTest {

    private val day = 86_400L

    private fun inv(id: Long, ccy: String) = TrackingInvestmentFfi(
        id = id, name = "i$id", assetType = "etf", ticker = null, quantity = 0.0, unit = "",
        costBasisFiat = 0.0, costBasisCurrency = ccy, purchaseUnix = 0,
        manualCurrentValue = null, fetchedCurrentValue = null, lastPricedAtUnix = null,
        notes = null, costBasisMethod = "fifo", createdAtUnix = 0,
    )

    private fun lot(id: Long, invId: Long, purchaseUnix: Long, rate: Double?) = TrackingInvestmentLotFfi(
        id = id, investmentId = invId, quantity = 10.0, pricePerUnit = 100.0, feesFiat = 0.0,
        purchaseUnix = purchaseUnix, notes = null, createdAtUnix = 0, btcPerUnitAtPurchase = rate,
    )

    private fun sell(id: Long, invId: Long, saleUnix: Long, rate: Double?) = TrackingInvestmentSellFfi(
        id = id, investmentId = invId, saleUnix = saleUnix, salePricePerUnit = 150.0, saleQty = 4.0,
        feesFiat = 0.0, notes = null, createdAtUnix = 0, btcPerUnitAtSale = rate,
    )

    private fun disposal(id: Long, sellId: Long, lotId: Long, gain: Double?) = TrackingLotDisposalFfi(
        id = id, sellId = sellId, lotId = lotId, qtyConsumed = 4.0, costBasisPerUnit = 100.0,
        gainLossFiat = 200.0, createdAtUnix = 0, gainLossBtc = gain,
    )

    @Test
    fun `B9 missing lot rate is filled from the purchase-date rate, making BTC P&L computable`() = runBlocking<Unit> {
        val lots = mapOf(1L to listOf(lot(1, 1, purchaseUnix = 10 * day + 3_600, rate = null)))
        // Before: no rate on the lot → unrealized BTC P&L is unknown.
        assertNull(unrealizedPnlBtc(lots.getValue(1L), 150.0, 60_000.0))

        val asked = mutableListOf<Pair<Long, String>>()
        val healed = fillMissingBtcRates(
            investments = listOf(inv(1, "eur")),
            lotsByInvestmentId = lots,
            sellsByInvestmentId = emptyMap(),
            disposalsByInvestmentId = emptyMap(),
            rateAt = { d, c -> asked += d to c; 40_000.0 },
        )
        // Asked for the UTC-midnight day in the investment's currency.
        assertEquals(listOf(10 * day to "EUR"), asked)
        val healedLot = healed.lotsByInvestmentId.getValue(1L).single()
        assertEquals(40_000.0, healedLot.btcPerUnitAtPurchase)
        assertEquals(listOf(1L), healed.healedLotIds)
        // After: 10 × (150/60k − 100/40k) = 0.0
        assertNotNull(unrealizedPnlBtc(listOf(healedLot), 150.0, 60_000.0))
    }

    @Test
    fun `B9 rows that already have a rate are left alone and the source is not called`() = runBlocking<Unit> {
        val lots = mapOf(1L to listOf(lot(1, 1, 0, rate = 30_000.0)))
        var calls = 0
        val healed = fillMissingBtcRates(listOf(inv(1, "EUR")), lots, emptyMap(), emptyMap()) { _, _ ->
            calls++; 1.0
        }
        assertEquals(0, calls)
        assertEquals(30_000.0, healed.lotsByInvestmentId.getValue(1L).single().btcPerUnitAtPurchase)
        assertTrue(healed.healedLotIds.isEmpty())
    }

    @Test
    fun `B9 truly unavailable rates keep the row empty (no rate stays honest)`() = runBlocking<Unit> {
        val lots = mapOf(1L to listOf(lot(1, 1, 0, rate = null)))
        val healed = fillMissingBtcRates(listOf(inv(1, "EUR")), lots, emptyMap(), emptyMap()) { _, _ ->
            throw IllegalArgumentException("unsupported currency")
        }
        assertNull(healed.lotsByInvestmentId.getValue(1L).single().btcPerUnitAtPurchase)
        assertTrue(healed.healedLotIds.isEmpty())
    }

    @Test
    fun `B9 sell rate and disposal gain in BTC are derived once both rates exist`() = runBlocking<Unit> {
        val lots = mapOf(1L to listOf(lot(1, 1, 0, rate = null)))
        val sells = mapOf(1L to listOf(sell(7, 1, 30 * day, rate = null)))
        val disposals = mapOf(1L to listOf(disposal(9, sellId = 7, lotId = 1, gain = null)))
        assertNull(realizedPnlBtc(disposals.getValue(1L)))

        val healed = fillMissingBtcRates(listOf(inv(1, "EUR")), lots, sells, disposals) { d, _ ->
            if (d == 0L) 40_000.0 else 50_000.0
        }
        assertEquals(50_000.0, healed.sellsByInvestmentId.getValue(1L).single().btcPerUnitAtSale)
        assertEquals(listOf(7L), healed.healedSellIds)
        // 4 × 150 / 50k − 4 × 100 / 40k = 0.012 − 0.010 = 0.002 BTC
        val gain = healed.disposalsByInvestmentId.getValue(1L).single().gainLossBtc
        assertEquals(0.002, assertNotNull(gain), 1e-12)
        assertEquals(0.002, realizedPnlBtc(healed.disposalsByInvestmentId.getValue(1L))!!, 1e-12)
    }

    @Test
    fun `B9 one lookup per day and currency`() = runBlocking<Unit> {
        val lots = mapOf(1L to listOf(lot(1, 1, 5, null), lot(2, 1, 500, null)))
        var calls = 0
        fillMissingBtcRates(listOf(inv(1, "EUR")), lots, emptyMap(), emptyMap()) { _, _ -> calls++; 1.0 }
        assertEquals(1, calls)
    }
}
