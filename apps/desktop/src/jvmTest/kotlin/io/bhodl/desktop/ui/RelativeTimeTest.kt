package io.bhodl.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7.2 — pure tests for [relativeTimeAgo], [minutesAgo], [isStale].
 *
 * All three are pure functions taking an explicit `nowSec`, so we can
 * pin the clock and exercise every bucket without time-mocking
 * frameworks.
 */
class RelativeTimeTest {

    private val now = 1_700_000_000L

    // ─── relativeTimeAgo buckets ────────────────────────────────────

    @Test
    fun `under five seconds renders just now`() {
        assertEquals("just now", relativeTimeAgo(now, now))
        assertEquals("just now", relativeTimeAgo(now - 1, now))
        assertEquals("just now", relativeTimeAgo(now - 4, now))
    }

    @Test
    fun `between five and sixty seconds renders Ns ago`() {
        assertEquals("5s ago", relativeTimeAgo(now - 5, now))
        assertEquals("30s ago", relativeTimeAgo(now - 30, now))
        assertEquals("59s ago", relativeTimeAgo(now - 59, now))
    }

    @Test
    fun `between one and sixty minutes renders Nm ago`() {
        assertEquals("1m ago", relativeTimeAgo(now - 60, now))
        assertEquals("2m ago", relativeTimeAgo(now - 120, now))
        assertEquals("12m ago", relativeTimeAgo(now - 12 * 60, now))
        assertEquals("59m ago", relativeTimeAgo(now - 59 * 60, now))
    }

    @Test
    fun `between one and twenty-four hours renders Nh ago`() {
        assertEquals("1h ago", relativeTimeAgo(now - 3600, now))
        assertEquals("3h ago", relativeTimeAgo(now - 3 * 3600, now))
        assertEquals("23h ago", relativeTimeAgo(now - 23 * 3600, now))
    }

    @Test
    fun `twenty-four hours and beyond renders Nd ago`() {
        assertEquals("1d ago", relativeTimeAgo(now - 86_400, now))
        assertEquals("7d ago", relativeTimeAgo(now - 7 * 86_400, now))
        assertEquals("365d ago", relativeTimeAgo(now - 365 * 86_400L, now))
    }

    @Test
    fun `future timestamps clamp to just now — never render in N`() {
        // Server-clock skew can produce a future timestamp; the trust
        // signal must not yell "in 5h" at the user.
        assertEquals("just now", relativeTimeAgo(now + 10_000, now))
        assertEquals("just now", relativeTimeAgo(now + 86_400, now))
    }

    // ─── minutesAgo ─────────────────────────────────────────────────

    @Test
    fun `minutesAgo rounds down on partial minutes`() {
        assertEquals(0L, minutesAgo(now, now))
        assertEquals(0L, minutesAgo(now - 59, now))
        assertEquals(1L, minutesAgo(now - 60, now))
        assertEquals(1L, minutesAgo(now - 119, now))
        assertEquals(2L, minutesAgo(now - 120, now))
    }

    @Test
    fun `minutesAgo clamps future timestamps to zero`() {
        assertEquals(0L, minutesAgo(now + 60_000, now))
    }

    // ─── isStale ────────────────────────────────────────────────────

    @Test
    fun `isStale returns false for null timestamp`() {
        // A never-fetched price isn't stale — it's missing. The hero
        // shows "—" or skips the overlay, so isStale=false avoids a
        // misleading amber chip on a fresh launch.
        assertFalse(isStale(null, now))
    }

    @Test
    fun `isStale uses default ten-minute threshold`() {
        // Just under: 9m ago → not stale.
        assertFalse(isStale(now - 9 * 60, now))
        // At threshold: 10m ago → stale (>= 10).
        assertTrue(isStale(now - 10 * 60, now))
        // Well past: 30m ago → stale.
        assertTrue(isStale(now - 30 * 60, now))
    }

    @Test
    fun `isStale honours a custom threshold`() {
        // Override for components that want a tighter freshness window
        // (e.g. a tracker that wants to flag stale at 5 min).
        assertFalse(isStale(now - 4 * 60, now, thresholdMinutes = 5))
        assertTrue(isStale(now - 5 * 60, now, thresholdMinutes = 5))
    }
}
