package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.computeOpportunityCostBtc

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.5 — pure tests for [computeOpportunityCostBtc].
 *
 * The OPP COST KPI is the storyboard's most viscerally educational
 * number: a single BTC delta saying "this is what diversification
 * cost you vs HODL". Pinning the math here is load-bearing for the
 * dashboard's trust signal.
 */
class OpportunityCostTest {

    // ─── Storyboard scenarios ─────────────────────────────────────────

    @Test
    fun `apple lot example - bought 1500 EUR of apple, apple +5pct, BTC +50pct`() {
        // Storyboard: bought 10 AAPL @ €150 when 1 BTC = €60k.
        // Now AAPL = €157.50 (+5%) and 1 BTC = €90k (+50%).
        //   btc_if_hodl = 10 * 150 / 60000 = 0.025 BTC
        //   btc_now     = 10 * 157.50 / 90000 = 0.0175 BTC
        //   opp_cost    = 0.025 - 0.0175 = 0.0075 BTC (positive → BTC won)
        val inv = sampleInv(
            id = 1,
            currency = "EUR",
            currentPrice = 157.50,
        )
        val lot = sampleLot(
            qty = 10.0,
            price = 150.0,
            btcAtPurchase = 60_000.0,
        )
        val out = computeOpportunityCostBtc(
            investments = listOf(inv),
            lotsByInvestment = mapOf(1L to listOf(lot)),
            manualAssets = emptyList(),
            currentBtcPerUnit = { if (it == "EUR") 90_000.0 else null },
        )
        assertNotNull(out.totalBtc)
        assertEquals(0.0075, out.totalBtc!!, 1e-9)
        assertEquals(1, out.consideredPositions)
        assertEquals(0, out.excludedPositions)
        assertEquals(0.0075, out.investmentsBtc, 1e-9)
        assertEquals(0.0, out.manualAssetsBtc, 1e-9)
    }

    @Test
    fun `house - purchase 250k EUR in 2021 at BTC=30k, now 300k EUR at BTC=90k`() {
        // Storyboard: bought a house for €250k when 1 BTC = €30k.
        // Now the house is worth €300k and 1 BTC = €90k.
        //   btc_if_hodl = 250000 / 30000 = 8.333... BTC
        //   btc_now     = 300000 / 90000 = 3.333... BTC
        //   opp_cost    = 5.0 BTC (positive → BTC blew the house away)
        val asset = sampleManualAsset(
            valueFiat = 300_000.0,
            currency = "EUR",
            purchaseValueFiat = 250_000.0,
            purchaseBtcAtTime = 30_000.0,
        )
        val out = computeOpportunityCostBtc(
            investments = emptyList(),
            lotsByInvestment = emptyMap(),
            manualAssets = listOf(asset),
            currentBtcPerUnit = { if (it == "EUR") 90_000.0 else null },
        )
        assertNotNull(out.totalBtc)
        assertEquals((250_000.0 / 30_000.0) - (300_000.0 / 90_000.0), out.totalBtc!!, 1e-9)
        assertEquals(1, out.consideredPositions)
        assertTrue(out.totalBtc!! > 4.9)
        assertTrue(out.totalBtc!! < 5.1)
    }

    // ─── Null / empty edge cases ──────────────────────────────────────

    @Test
    fun `totalBtc is null when no positions considered`() {
        // Empty inputs → no contribution, no exclusions either.
        val out = computeOpportunityCostBtc(
            investments = emptyList(),
            lotsByInvestment = emptyMap(),
            manualAssets = emptyList(),
            currentBtcPerUnit = { 60_000.0 },
        )
        assertNull(out.totalBtc)
        assertEquals(0, out.consideredPositions)
        assertEquals(0, out.excludedPositions)
    }

    @Test
    fun `excluded when btc_per_unit_at_purchase is null on a lot`() {
        // Pre-Phase-8.2 lot: btcPerUnitAtPurchase null → lot excluded.
        val inv = sampleInv(id = 1, currency = "EUR", currentPrice = 100.0)
        val lotMissing = sampleLot(qty = 1.0, price = 100.0, btcAtPurchase = null)
        val out = computeOpportunityCostBtc(
            investments = listOf(inv),
            lotsByInvestment = mapOf(1L to listOf(lotMissing)),
            manualAssets = emptyList(),
            currentBtcPerUnit = { 60_000.0 },
        )
        assertNull(out.totalBtc)
        assertEquals(0, out.consideredPositions)
        assertEquals(1, out.excludedPositions)
    }

    @Test
    fun `excluded when current rate not available for currency`() {
        // Cross-currency dashboard (USD lot, EUR dashboard) — caller
        // returns null for the USD rate, so the whole investment's
        // lots are excluded en bloc (the parent rate is the blocker).
        val inv = sampleInv(id = 1, currency = "USD", currentPrice = 100.0)
        val lot = sampleLot(qty = 1.0, price = 100.0, btcAtPurchase = 50_000.0)
        val out = computeOpportunityCostBtc(
            investments = listOf(inv),
            lotsByInvestment = mapOf(1L to listOf(lot)),
            manualAssets = emptyList(),
            currentBtcPerUnit = { if (it == "EUR") 60_000.0 else null },
        )
        assertNull(out.totalBtc)
        assertEquals(0, out.consideredPositions)
        assertEquals(1, out.excludedPositions)
    }

    @Test
    fun `excluded when quantity is zero or negative on a lot`() {
        val inv = sampleInv(id = 1, currency = "EUR", currentPrice = 100.0)
        val lots = listOf(
            sampleLot(qty = 0.0, price = 100.0, btcAtPurchase = 50_000.0),
            sampleLot(qty = -1.0, price = 100.0, btcAtPurchase = 50_000.0),
        )
        val out = computeOpportunityCostBtc(
            investments = listOf(inv),
            lotsByInvestment = mapOf(1L to lots),
            manualAssets = emptyList(),
            currentBtcPerUnit = { 60_000.0 },
        )
        assertNull(out.totalBtc)
        assertEquals(0, out.consideredPositions)
        assertEquals(2, out.excludedPositions)
    }

    @Test
    fun `excluded when purchase_value_fiat null on manual asset`() {
        // Pre-Phase-8.2 manual asset: snapshot fields are still null
        // because the backfill hasn't run for this row.
        val assetMissingFiat = sampleManualAsset(
            valueFiat = 100.0,
            currency = "EUR",
            purchaseValueFiat = null,
            purchaseBtcAtTime = 60_000.0,
        )
        val out = computeOpportunityCostBtc(
            investments = emptyList(),
            lotsByInvestment = emptyMap(),
            manualAssets = listOf(assetMissingFiat),
            currentBtcPerUnit = { 60_000.0 },
        )
        assertNull(out.totalBtc)
        assertEquals(0, out.consideredPositions)
        assertEquals(1, out.excludedPositions)
    }

    @Test
    fun `excluded when purchase_btc_at_time null on manual asset`() {
        val assetMissingRate = sampleManualAsset(
            valueFiat = 100.0,
            currency = "EUR",
            purchaseValueFiat = 80.0,
            purchaseBtcAtTime = null,
        )
        val out = computeOpportunityCostBtc(
            investments = emptyList(),
            lotsByInvestment = emptyMap(),
            manualAssets = listOf(assetMissingRate),
            currentBtcPerUnit = { 60_000.0 },
        )
        assertNull(out.totalBtc)
        assertEquals(0, out.consideredPositions)
        assertEquals(1, out.excludedPositions)
    }

    // ─── Aggregation ──────────────────────────────────────────────────

    @Test
    fun `aggregates investments plus manual assets correctly`() {
        // One investment lot at +0.005 BTC and one manual asset at
        // +0.010 BTC → total 0.015 BTC, considered = 2.
        val inv = sampleInv(id = 1, currency = "EUR", currentPrice = 100.0)
        // 1 unit at 100 EUR when 1 BTC = 50k → 0.002 BTC at hodl.
        // Now 100 EUR / 50k = still 0.002 BTC → opp = 0.0
        // Make BTC win: now 1 BTC = 100k → btc_now = 0.001 BTC → opp = +0.001
        val lot = sampleLot(qty = 1.0, price = 100.0, btcAtPurchase = 50_000.0)
        val asset = sampleManualAsset(
            valueFiat = 100.0,
            currency = "EUR",
            purchaseValueFiat = 100.0,
            purchaseBtcAtTime = 50_000.0,
        )
        val out = computeOpportunityCostBtc(
            investments = listOf(inv),
            lotsByInvestment = mapOf(1L to listOf(lot)),
            manualAssets = listOf(asset),
            currentBtcPerUnit = { 100_000.0 },
        )
        assertNotNull(out.totalBtc)
        // Each side: 100 / 50k − 100 / 100k = 0.002 − 0.001 = 0.001 BTC.
        assertEquals(0.001, out.investmentsBtc, 1e-9)
        assertEquals(0.001, out.manualAssetsBtc, 1e-9)
        assertEquals(0.002, out.totalBtc!!, 1e-9)
        assertEquals(2, out.consideredPositions)
        assertEquals(0, out.excludedPositions)
    }

    @Test
    fun `considered and excluded counters track each contribution`() {
        // 2 considered lots + 1 excluded lot + 1 considered asset
        // + 1 excluded asset = (3 considered, 2 excluded).
        val inv = sampleInv(id = 1, currency = "EUR", currentPrice = 100.0)
        val lots = listOf(
            sampleLot(qty = 1.0, price = 100.0, btcAtPurchase = 50_000.0),
            sampleLot(qty = 2.0, price = 50.0, btcAtPurchase = 50_000.0),
            sampleLot(qty = 1.0, price = 100.0, btcAtPurchase = null), // excluded
        )
        val assets = listOf(
            sampleManualAsset(
                valueFiat = 100.0,
                currency = "EUR",
                purchaseValueFiat = 100.0,
                purchaseBtcAtTime = 50_000.0,
            ),
            sampleManualAsset(
                valueFiat = 100.0,
                currency = "EUR",
                purchaseValueFiat = null, // excluded
                purchaseBtcAtTime = 50_000.0,
            ),
        )
        val out = computeOpportunityCostBtc(
            investments = listOf(inv),
            lotsByInvestment = mapOf(1L to lots),
            manualAssets = assets,
            currentBtcPerUnit = { 100_000.0 },
        )
        assertEquals(3, out.consideredPositions)
        assertEquals(2, out.excludedPositions)
        assertNotNull(out.totalBtc)
    }

    @Test
    fun `negative total when diversification beat BTC`() {
        // BTC dropped between purchase and now → diversification won.
        // Bought 1 unit at 100 EUR when 1 BTC = 100k → 0.001 BTC.
        // Now AAPL = 200 EUR and 1 BTC = 50k → 200/50k = 0.004 BTC.
        // opp = 0.001 − 0.004 = −0.003 BTC (diversification won by 0.003)
        val inv = sampleInv(id = 1, currency = "EUR", currentPrice = 200.0)
        val lot = sampleLot(qty = 1.0, price = 100.0, btcAtPurchase = 100_000.0)
        val out = computeOpportunityCostBtc(
            investments = listOf(inv),
            lotsByInvestment = mapOf(1L to listOf(lot)),
            manualAssets = emptyList(),
            currentBtcPerUnit = { 50_000.0 },
        )
        assertNotNull(out.totalBtc)
        assertEquals(-0.003, out.totalBtc!!, 1e-9)
        assertTrue(out.totalBtc!! < 0.0)
        assertEquals(1, out.consideredPositions)
    }

    // ─── Sample helpers ───────────────────────────────────────────────

    private fun sampleInv(
        id: Long,
        currency: String,
        currentPrice: Double?,
    ): TrackingInvestmentFfi = TrackingInvestmentFfi(
        id = id,
        name = "TEST $id",
        assetType = "stock",
        ticker = "TST",
        quantity = 1.0,
        unit = "",
        costBasisFiat = 100.0,
        costBasisCurrency = currency,
        purchaseUnix = 0,
        manualCurrentValue = currentPrice,
        fetchedCurrentValue = null,
        lastPricedAtUnix = null,
        notes = null,
        costBasisMethod = "fifo",
        createdAtUnix = 0,
    )

    private fun sampleLot(
        qty: Double,
        price: Double,
        btcAtPurchase: Double?,
    ): TrackingInvestmentLotFfi = TrackingInvestmentLotFfi(
        id = 1,
        investmentId = 1,
        quantity = qty,
        pricePerUnit = price,
        feesFiat = 0.0,
        purchaseUnix = 0,
        notes = null,
        createdAtUnix = 0,
        btcPerUnitAtPurchase = btcAtPurchase,
    )

    private fun sampleManualAsset(
        valueFiat: Double,
        currency: String,
        purchaseValueFiat: Double?,
        purchaseBtcAtTime: Double?,
    ): TrackingManualAssetFfi = TrackingManualAssetFfi(
        id = 1,
        name = "test",
        kind = "cash",
        valueFiat = valueFiat,
        currency = currency,
        displayLabel = null,
        notes = null,
        lastUpdatedUnix = 0,
        createdAtUnix = 0,
        purchaseUnix = 0,
        purchaseValueFiat = purchaseValueFiat,
        purchaseCurrency = currency,
        purchaseBtcAtTime = purchaseBtcAtTime,
    )
}
