package io.bhodl.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 7.1 — pure-math tests for the [nearestPoint] helper. The
 * Compose [Sparkline] composable cannot be tested without a
 * composition rule, so we focus on the mapping function that does
 * all the non-trivial work (scrub-x → nearest data point).
 */
class SparklineTest {

    @Test
    fun `nearestPoint returns null on empty input`() {
        assertNull(nearestPoint(emptyList(), scrubX = 50f, width = 100f))
    }

    @Test
    fun `nearestPoint returns the only point when the list has one entry`() {
        val pts = listOf(1000L to 42.0)
        assertEquals(pts.first(), nearestPoint(pts, scrubX = 0f, width = 100f))
        assertEquals(pts.first(), nearestPoint(pts, scrubX = 100f, width = 100f))
    }

    @Test
    fun `nearestPoint maps scrubX proportionally to the timestamp range`() {
        // Evenly-spaced points: 1000ms, 1100ms, 1200ms.
        val pts = listOf(
            1000L to 10.0,
            1100L to 20.0,
            1200L to 30.0,
        )
        // x=0 → start of range → first point.
        assertEquals(pts[0], nearestPoint(pts, scrubX = 0f, width = 100f))
        // x=width → end of range → last point.
        assertEquals(pts[2], nearestPoint(pts, scrubX = 100f, width = 100f))
        // x=50 → midpoint → second point.
        assertEquals(pts[1], nearestPoint(pts, scrubX = 50f, width = 100f))
    }

    @Test
    fun `nearestPoint clamps scrubX to width bounds`() {
        val pts = listOf(0L to 1.0, 100L to 2.0)
        // Negative x → clamped to 0 → first point.
        assertEquals(pts[0], nearestPoint(pts, scrubX = -50f, width = 100f))
        // Past width → clamped to width → last point.
        assertEquals(pts[1], nearestPoint(pts, scrubX = 999f, width = 100f))
    }

    @Test
    fun `nearestPoint picks the closer of two adjacent points`() {
        // Non-uniform spacing — gap from 0-1000ms is larger than
        // 1000-1100ms. A scrubX in the second segment should snap to
        // 1000 or 1100 depending on which is closer in *timestamp*
        // space (the function maps scrubX to a target timestamp).
        val pts = listOf(0L to 0.0, 1000L to 1.0, 1100L to 2.0)
        // x=95% of width → 0 + 0.95 * 1100 = 1045 → closer to 1000.
        // But abs(1045-1000)=45 vs abs(1045-1100)=55, so 1000 wins.
        val picked = nearestPoint(pts, scrubX = 95f, width = 100f)
        assertEquals(1000L, picked!!.first)
    }
}
