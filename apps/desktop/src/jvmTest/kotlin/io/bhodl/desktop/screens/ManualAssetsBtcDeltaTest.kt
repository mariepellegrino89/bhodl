package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingManualAssetFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.3.4 — pure helper coverage for [computeManualAssetDeltaBtcPct].
 *
 * Captures the canonical "asset gained in EUR but lost in BTC" story
 * (storyboard #4 — Family home @ €250k in 2021 → €288k in 2026, but
 * BTC went 50k → 60k in the meantime so the BTC equivalent dropped).
 */
class ManualAssetsBtcDeltaTest {

    private fun asset(
        valueFiat: Double,
        currency: String = "EUR",
        purchaseValueFiat: Double? = null,
        purchaseCurrency: String? = null,
        purchaseBtcAtTime: Double? = null,
        purchaseUnix: Long? = null,
    ): TrackingManualAssetFfi = TrackingManualAssetFfi(
        id = 1L,
        name = "Test",
        kind = "real_estate",
        valueFiat = valueFiat,
        currency = currency,
        displayLabel = null,
        notes = null,
        lastUpdatedUnix = 0L,
        createdAtUnix = 0L,
        purchaseUnix = purchaseUnix,
        purchaseValueFiat = purchaseValueFiat,
        purchaseCurrency = purchaseCurrency,
        purchaseBtcAtTime = purchaseBtcAtTime,
    )

    @Test
    fun `apple-storyboard real estate plus EUR minus BTC`() {
        // Family home: bought 2021 for €250k when 1 BTC = €50k → 5.0 BTC.
        // Now valued €288k with 1 BTC = €60k → 4.8 BTC. EUR up ~15.2%,
        // BTC down exactly −4.0%. Negative delta is the headline insight.
        val a = asset(
            valueFiat = 288_000.0,
            currency = "EUR",
            purchaseValueFiat = 250_000.0,
            purchaseCurrency = "EUR",
            purchaseBtcAtTime = 50_000.0,
            purchaseUnix = 1_622_505_600L, // 2021-06-01
        )
        val delta = computeManualAssetDeltaBtcPct(a, currentBtcPerUnit = 60_000.0)
        assertNotNull(delta)
        // Expected: (4.8 / 5.0 - 1) * 100 = −4.0
        assertTrue(delta < 0.0, "delta should be negative for storyboard scenario, got $delta")
        assertTrue(delta > -5.0 && delta < -3.5, "delta in expected range, got $delta")
    }

    @Test
    fun `delta null when purchaseBtcAtTime null`() {
        // Legacy / un-backfilled row: backfill couldn't fetch a rate
        // at created_at_unix. Cell renders "—".
        val a = asset(
            valueFiat = 1_000.0,
            purchaseValueFiat = 1_000.0,
            purchaseBtcAtTime = null,
        )
        assertNull(computeManualAssetDeltaBtcPct(a, currentBtcPerUnit = 60_000.0))
    }

    @Test
    fun `delta null when purchaseValueFiat null`() {
        // Rare: backfill couldn't infer a starting fiat amount.
        val a = asset(
            valueFiat = 1_000.0,
            purchaseValueFiat = null,
            purchaseBtcAtTime = 50_000.0,
        )
        assertNull(computeManualAssetDeltaBtcPct(a, currentBtcPerUnit = 60_000.0))
    }

    @Test
    fun `delta null when currentBtcPerUnit null cross-currency`() {
        // USD-denominated asset on a EUR dashboard → PriceCache lookup
        // returns null → cell falls back to em-dash.
        val a = asset(
            valueFiat = 1_000.0,
            currency = "USD",
            purchaseValueFiat = 800.0,
            purchaseCurrency = "USD",
            purchaseBtcAtTime = 50_000.0,
        )
        assertNull(computeManualAssetDeltaBtcPct(a, currentBtcPerUnit = null))
    }

    @Test
    fun `delta zero when current equals purchase and rate unchanged`() {
        // Sanity: a "no-op" snapshot must report a zero delta, not a
        // float-noise drift. We assert near-zero rather than == 0.0
        // because IEEE round-trip on the same numerator/denominator
        // can introduce tiny epsilons.
        val a = asset(
            valueFiat = 1_000.0,
            purchaseValueFiat = 1_000.0,
            purchaseBtcAtTime = 50_000.0,
        )
        val delta = computeManualAssetDeltaBtcPct(a, currentBtcPerUnit = 50_000.0)
        assertNotNull(delta)
        assertEquals(0.0, delta, absoluteTolerance = 0.0001)
    }

    @Test
    fun `delta sign tracks rate move with constant fiat value`() {
        // Same fiat amount before and after; rate goes UP from 50k → 60k
        // → BTC equivalent goes DOWN → delta negative. This is the
        // canonical Phase 8.2 backfill case (legacy asset gets
        // purchase_value_fiat = value_fiat, so the "fiat delta" is 0
        // but the BTC delta is non-zero — exactly the story to tell).
        val a = asset(
            valueFiat = 1_000.0,
            purchaseValueFiat = 1_000.0,
            purchaseBtcAtTime = 50_000.0,
        )
        val rateUp = computeManualAssetDeltaBtcPct(a, currentBtcPerUnit = 60_000.0)
        assertNotNull(rateUp)
        assertTrue(rateUp < 0.0, "rate up → BTC value down, got $rateUp")

        val rateDown = computeManualAssetDeltaBtcPct(a, currentBtcPerUnit = 40_000.0)
        assertNotNull(rateDown)
        assertTrue(rateDown > 0.0, "rate down → BTC value up, got $rateDown")
    }

    @Test
    fun `delta null when any input is non-finite or non-positive`() {
        // Defensive: NaN / Infinity / zero / negative inputs must not
        // crash the cell — they degrade to "—". Mirrors the same
        // null-policy applied in InvestmentsCard's BTC P&L cell.
        val zeroRate = asset(
            valueFiat = 1_000.0,
            purchaseValueFiat = 1_000.0,
            purchaseBtcAtTime = 0.0,
        )
        assertNull(computeManualAssetDeltaBtcPct(zeroRate, currentBtcPerUnit = 60_000.0))

        val nanRate = asset(
            valueFiat = 1_000.0,
            purchaseValueFiat = 1_000.0,
            purchaseBtcAtTime = Double.NaN,
        )
        assertNull(computeManualAssetDeltaBtcPct(nanRate, currentBtcPerUnit = 60_000.0))

        val good = asset(
            valueFiat = 1_000.0,
            purchaseValueFiat = 1_000.0,
            purchaseBtcAtTime = 50_000.0,
        )
        assertNull(computeManualAssetDeltaBtcPct(good, currentBtcPerUnit = 0.0))
        assertNull(computeManualAssetDeltaBtcPct(good, currentBtcPerUnit = Double.POSITIVE_INFINITY))
    }
}
