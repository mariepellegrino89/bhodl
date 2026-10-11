package io.bhodl.desktop.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.3.2 — pure tests for [computeFiatVsBtcChange].
 *
 * Pure math, no Compose runtime. Validates the rate-derivation
 * approximation and the [Direction] thresholding (±0.5%).
 */
class FiatVsBtcChangeTest {

    /** Convenience: 1 BTC in sat. */
    private val oneBtc: ULong = 100_000_000UL

    @Test
    fun `returns null for empty history`() {
        assertNull(computeFiatVsBtcChange(emptyList(), oneBtc))
    }

    @Test
    fun `returns null for single-point history`() {
        val history = listOf(1_000L to 50_000.0)
        assertNull(computeFiatVsBtcChange(history, oneBtc))
    }

    @Test
    fun `returns null when currentSat is 0`() {
        val history = listOf(
            1_000L to 50_000.0,
            2_000L to 55_000.0,
        )
        assertNull(computeFiatVsBtcChange(history, 0UL))
    }

    @Test
    fun `flat direction within 0_5 percent threshold`() {
        // With 1 BTC currentSat, fiat history = rate history.
        // Rate moves from 50,000 → 50,100 → +0.2% → flat.
        val history = listOf(
            1_000L to 50_000.0,
            2_000L to 50_100.0,
        )
        val out = computeFiatVsBtcChange(history, oneBtc)
        assertNotNull(out)
        assertEquals(Direction.Flat, out.direction)
        assertTrue(out.deltaPct in -0.5..0.5, "expected flat band, got ${out.deltaPct}")
    }

    @Test
    fun `FiatDown direction when rate end is greater than rate start`() {
        // 50,000 → 55,000 → +10% → BTC up → fiat lost vs BTC.
        val history = listOf(
            1_000L to 50_000.0,
            2_000L to 55_000.0,
        )
        val out = computeFiatVsBtcChange(history, oneBtc)
        assertNotNull(out)
        assertEquals(Direction.FiatDown, out.direction)
        // (55000/50000 - 1) * 100 = +10.0
        assertEquals(10.0, out.deltaPct, 1e-6)
    }

    @Test
    fun `FiatUp direction when rate end is less than rate start`() {
        // 50,000 → 45,000 → -10% → BTC down → fiat gained vs BTC.
        val history = listOf(
            1_000L to 50_000.0,
            2_000L to 45_000.0,
        )
        val out = computeFiatVsBtcChange(history, oneBtc)
        assertNotNull(out)
        assertEquals(Direction.FiatUp, out.direction)
        assertEquals(-10.0, out.deltaPct, 1e-6)
    }

    @Test
    fun `returns null when rateStart is zero or negative`() {
        // A 0-fiat first sample would imply rate=0 → undefined ratio.
        val history = listOf(
            1_000L to 0.0,
            2_000L to 50_000.0,
        )
        assertNull(computeFiatVsBtcChange(history, oneBtc))
    }

    @Test
    fun `approximation honors non-unit currentSat`() {
        // 0.5 BTC = 50_000_000 sat. Fiat history is therefore half of
        // the implied rate. 25_000 → 27_500 fiat-of-nw means rate
        // moved 50_000 → 55_000 → +10% → FiatDown.
        val halfBtc: ULong = 50_000_000UL
        val history = listOf(
            1_000L to 25_000.0,
            2_000L to 27_500.0,
        )
        val out = computeFiatVsBtcChange(history, halfBtc)
        assertNotNull(out)
        assertEquals(Direction.FiatDown, out.direction)
        assertEquals(10.0, out.deltaPct, 1e-6)
    }
}
