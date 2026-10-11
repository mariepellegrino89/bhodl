package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (B2) — the investments "Total" row summed BTC over
 * the dashboard-currency rows only (₿0.1175 for the 2 EUR rows while
 * the 4 rows were worth ₿0.2421).
 */
class InvestmentsTotalsTest {

    private fun inv(id: Long, ccy: String, qty: Double, cost: Double, price: Double?) = TrackingInvestmentFfi(
        id = id, name = "i$id", assetType = "stock", ticker = null, quantity = qty, unit = "",
        costBasisFiat = cost, costBasisCurrency = ccy, purchaseUnix = 0,
        manualCurrentValue = price, fetchedCurrentValue = null, lastPricedAtUnix = null,
        notes = null, costBasisMethod = "fifo", createdAtUnix = 0,
    )

    private fun lot(id: Long, invId: Long, qty: Double, price: Double, rate: Double?) = TrackingInvestmentLotFfi(
        id = id, investmentId = invId, quantity = qty, pricePerUnit = price, feesFiat = 0.0,
        purchaseUnix = 0, notes = null, createdAtUnix = 0, btcPerUnitAtPurchase = rate,
    )

    private val rates = mapOf("EUR" to 50_000.0, "USD" to 55_000.0)

    @Test
    fun `B2 BTC value total covers every row at its own currency rate`() {
        val investments = listOf(
            inv(1, "EUR", 10.0, 1_000.0, 150.0), // €1,500 → 0.03 BTC
            inv(2, "USD", 5.0, 1_000.0, 220.0),  // $1,100 → 0.02 BTC
            inv(3, "EUR", 1.0, 400.0, 500.0),    // €500   → 0.01 BTC
            inv(4, "USD", 2.0, 500.0, 1_375.0),  // $2,750 → 0.05 BTC
        )
        val t = computeInvestmentsTotals(investments, emptyMap(), emptyMap(), "EUR") { rates[it] }
        assertEquals(0.11, assertNotNull(t.totalValueBtc), 1e-9)
        // Fiat side: dashboard-currency rows only (no forex).
        assertEquals(2, t.fiatRows.size)
        assertEquals(2_000.0, t.totalValueFiat, 1e-9)
        assertEquals(1_400.0, t.totalCostFiat, 1e-9)
        assertFalse(t.valueRateMissing)
    }

    @Test
    fun `B2 BTC cost total covers every row's lots at their purchase rates`() {
        val investments = listOf(inv(1, "EUR", 10.0, 1_000.0, 150.0), inv(2, "USD", 5.0, 1_000.0, 220.0))
        val lots = mapOf(
            1L to listOf(lot(1, 1, 10.0, 100.0, 40_000.0)), // 0.025 BTC
            2L to listOf(lot(2, 2, 5.0, 200.0, 50_000.0)),  // 0.020 BTC
        )
        val t = computeInvestmentsTotals(investments, lots, emptyMap(), "EUR") { rates[it] }
        assertEquals(0.045, assertNotNull(t.totalCostBtc), 1e-12)
        // P&L BTC: (0.03 − 0.025) + (0.02 − 0.02) = 0.005 → 11% of 0.045.
        assertEquals(0.005, assertNotNull(t.totalPnlBtc), 1e-12)
        assertEquals(11, t.totalPnlBtcPct)
    }

    @Test
    fun `B2 a missing current rate makes the BTC value unknown and says why`() {
        val investments = listOf(inv(1, "EUR", 1.0, 1.0, 10.0), inv(2, "CHF", 1.0, 1.0, 10.0))
        val t = computeInvestmentsTotals(investments, emptyMap(), emptyMap(), "EUR") { rates[it] }
        assertNull(t.totalValueBtc)
        assertTrue(t.valueRateMissing)
    }

    @Test
    fun `B2 a missing purchase rate keeps the BTC cost unknown`() {
        val investments = listOf(inv(1, "EUR", 1.0, 100.0, 150.0))
        val lots = mapOf(1L to listOf(lot(1, 1, 1.0, 100.0, null)))
        val t = computeInvestmentsTotals(investments, lots, emptyMap(), "EUR") { rates[it] }
        assertNull(t.totalCostBtc)
        assertFalse(t.valueRateMissing)
    }

    // ── Code review 2026-10 — BTC cost is a per-lot sum, not an averaged rate ──

    @Test
    fun `BTC cost sums each lot at its own purchase rate`() {
        // €100 @ 10k + €100 @ 50k = 0.01 + 0.002 = 0.012 BTC. The old
        // cost-weighted arithmetic mean (30k) gave 200 / 30k = 0.00667.
        val lots = listOf(lot(1, 1, 1.0, 100.0, 10_000.0), lot(2, 1, 1.0, 100.0, 50_000.0))
        assertEquals(0.012, assertNotNull(lotsCostBtc(lots)), 1e-12)
    }

    @Test
    fun `BTC cost includes fees at the lot's rate`() {
        val lots = listOf(lot(1, 1, 1.0, 100.0, 10_000.0).copy(feesFiat = 10.0))
        assertEquals(0.011, assertNotNull(lotsCostBtc(lots)), 1e-12)
    }

    @Test
    fun `BTC cost is unknown when any lot lacks a rate or there are no lots`() {
        assertNull(lotsCostBtc(listOf(lot(1, 1, 1.0, 100.0, 10_000.0), lot(2, 1, 1.0, 100.0, null))))
        assertNull(lotsCostBtc(emptyList()))
    }

    @Test
    fun `totals BTC cost matches the per-row BTC cost`() {
        val investments = listOf(inv(1, "EUR", 2.0, 200.0, 150.0))
        val lots = mapOf(1L to listOf(lot(1, 1, 1.0, 100.0, 10_000.0), lot(2, 1, 1.0, 100.0, 50_000.0)))
        val t = computeInvestmentsTotals(investments, lots, emptyMap(), "EUR") { 50_000.0 }
        assertEquals(lotsCostBtc(lots.getValue(1L))!!, t.totalCostBtc!!, 1e-15)
    }
}
