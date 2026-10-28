package io.bhodl.desktop

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test-book §46.7 / audit 2026-10 (L-idle) — the idle / minimise lock
 * policy that `App()` polls. The 15-minute wall-clock wait of the
 * manual scenario is replaced by explicit monotonic timestamps; the
 * App-level watcher itself (10 s poll → `lockAndRouteToGate`) stays a
 * manual check.
 */
class IdleLockTest {

    private val ms = 1_000_000L
    private val t0 = 50_000_000_000_000L

    @AfterTest
    fun tearDown() {
        IdleLock.setMinimized(false)
        IdleLock.touch()
    }

    @Test
    fun `nothing open never locks`() {
        assertFalse(
            IdleLock.shouldLock(
                sessionOpen = false, registryOpen = false, devicePinExpired = true,
                nowNanos = t0, lastActivity = 0L, minimizedSince = 0L,
            ),
        )
    }

    @Test
    fun `idle just under the timeout stays open, at the timeout it locks`() {
        val under = t0 - (IdleLock.IDLE_TIMEOUT_MS - 1) * ms
        val at = t0 - IdleLock.IDLE_TIMEOUT_MS * ms
        assertFalse(IdleLock.shouldLock(true, false, false, t0, lastActivity = under, minimizedSince = null))
        assertTrue(IdleLock.shouldLock(true, false, false, t0, lastActivity = at, minimizedSince = null))
    }

    @Test
    fun `an expired device PIN session locks an open registry even while active`() {
        assertTrue(IdleLock.shouldLock(true, true, true, t0, lastActivity = t0, minimizedSince = null))
        // Without the registry the device-PIN session is irrelevant.
        assertFalse(IdleLock.shouldLock(true, false, true, t0, lastActivity = t0, minimizedSince = null))
    }

    @Test
    fun `minimised within the grace period stays open`() {
        val within = t0 - (IdleLock.MINIMIZED_GRACE_MS - 1) * ms
        val past = t0 - IdleLock.MINIMIZED_GRACE_MS * ms
        assertFalse(IdleLock.shouldLock(true, false, false, t0, lastActivity = t0, minimizedSince = within))
        assertTrue(IdleLock.shouldLock(true, false, false, t0, lastActivity = t0, minimizedSince = past))
    }

    @Test
    fun `activity and restore reset the stateful clocks`() {
        val longAgo = t0 - (IdleLock.IDLE_TIMEOUT_MS + 5_000) * ms
        IdleLock.touch(nowNanos = longAgo)
        assertTrue(IdleLock.shouldLock(true, false, false, nowNanos = t0, minimizedSince = null))
        IdleLock.touch(nowNanos = t0)
        assertFalse(IdleLock.shouldLock(true, false, false, nowNanos = t0, minimizedSince = null))

        // Minimised long enough → lock; restoring counts as activity.
        IdleLock.setMinimized(true, nowNanos = t0)
        val later = t0 + (IdleLock.MINIMIZED_GRACE_MS + 1) * ms
        assertTrue(IdleLock.shouldLock(true, false, false, nowNanos = later))
        IdleLock.setMinimized(false, nowNanos = later)
        assertFalse(IdleLock.shouldLock(true, false, false, nowNanos = later))
    }

    @Test
    fun `repeated minimise events keep the first timestamp`() {
        IdleLock.touch(nowNanos = t0)
        IdleLock.setMinimized(true, nowNanos = t0)
        // A second iconify notification must not restart the grace period.
        IdleLock.setMinimized(true, nowNanos = t0 + 30_000 * ms)
        IdleLock.touch(nowNanos = t0 + IdleLock.MINIMIZED_GRACE_MS * ms)
        assertTrue(
            IdleLock.shouldLock(true, false, false, nowNanos = t0 + IdleLock.MINIMIZED_GRACE_MS * ms),
        )
    }
}
