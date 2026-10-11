package io.bhodl.desktop.wallet

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [`SyncCache`].
 *
 * Since [`SyncCache`] is a process-lifetime singleton, every test
 * clears it on entry and exit so the order of execution does not
 * shift the assertions.
 */
class SyncCacheTest {

    @BeforeTest
    fun reset() {
        SyncCache.clear()
    }

    @AfterTest
    fun cleanup() {
        SyncCache.clear()
    }

    @Test
    fun `initial state has no balance and no timestamp`() {
        assertNull(SyncCache.lastBalanceSat())
        assertNull(SyncCache.lastSyncAtMs())
    }

    @Test
    fun `isFresh returns false when no sync has been recorded`() {
        assertFalse(SyncCache.isFresh(nowMs = 1_000_000L))
    }

    @Test
    fun `record stores both balance and timestamp`() {
        SyncCache.record(balanceSat = 12_345u, atMs = 1_000L)
        assertEquals(12_345u, SyncCache.lastBalanceSat())
        assertEquals(1_000L, SyncCache.lastSyncAtMs())
    }

    @Test
    fun `isFresh returns true within the cooldown window`() {
        SyncCache.record(balanceSat = 1u, atMs = 0L)
        // 30 seconds after a sync should still be fresh under the
        // 60 second default.
        assertTrue(SyncCache.isFresh(nowMs = 30_000L))
    }

    @Test
    fun `isFresh returns false outside the cooldown window`() {
        SyncCache.record(balanceSat = 1u, atMs = 0L)
        // 61 seconds after a sync — past the default 60 second window.
        assertFalse(SyncCache.isFresh(nowMs = 61_000L))
    }

    @Test
    fun `isFresh respects a custom cooldown`() {
        SyncCache.record(balanceSat = 1u, atMs = 0L)
        // With a 10s window, even 15s is stale.
        assertFalse(SyncCache.isFresh(nowMs = 15_000L, cooldownMs = 10_000L))
        // Same record, with a 30s window, 15s is still fresh.
        assertTrue(SyncCache.isFresh(nowMs = 15_000L, cooldownMs = 30_000L))
    }

    @Test
    fun `clear drops both balance and timestamp`() {
        SyncCache.record(balanceSat = 999u, atMs = 1_000L)
        SyncCache.clear()
        assertNull(SyncCache.lastBalanceSat())
        assertNull(SyncCache.lastSyncAtMs())
        assertFalse(SyncCache.isFresh())
    }

    @Test
    fun `record overwrites the previous balance and timestamp`() {
        SyncCache.record(balanceSat = 111u, atMs = 1_000L)
        SyncCache.record(balanceSat = 222u, atMs = 2_000L)
        assertEquals(222u, SyncCache.lastBalanceSat())
        assertEquals(2_000L, SyncCache.lastSyncAtMs())
    }

    @Test
    fun `default cooldown is 60 seconds`() {
        // Pin the constant — if a future change reduces it (or
        // grows it dramatically) this test will fail and force the
        // author to think about the UX implications.
        assertEquals(60_000L, SyncCache.DEFAULT_COOLDOWN_MS)
    }
}
