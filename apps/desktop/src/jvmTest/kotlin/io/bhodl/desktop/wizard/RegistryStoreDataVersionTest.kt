package io.bhodl.desktop.wizard

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7.1.1 — tests for [RegistryStore.dataVersion] and
 * [RegistryStore.bumpDataVersion].
 *
 * The data-version bus is what makes the dashboards reactive across
 * screens — a mutation anywhere bumps the counter, and every observer
 * (a `LaunchedEffect(dataVersion)` on TrackerHome or TrackingScreen)
 * reloads its snapshot. The contract pinned here:
 *  - starts at 0
 *  - bumps strictly increase the value
 *  - reset returns to 0
 *  - clear() also resets (a session change must not leak the previous
 *    counter into the next session's observers)
 */
class RegistryStoreDataVersionTest {

    @AfterTest
    fun cleanup() {
        // Tests run in the same JVM; reset between tests so ordering
        // doesn't matter and an earlier failure can't poison the rest.
        RegistryStore.resetDataVersionForTest()
    }

    @Test
    fun `dataVersion starts at zero`() {
        RegistryStore.resetDataVersionForTest()
        assertEquals(0L, RegistryStore.dataVersion.value)
    }

    @Test
    fun `bump increments the counter`() {
        RegistryStore.resetDataVersionForTest()
        RegistryStore.bumpDataVersion()
        assertEquals(1L, RegistryStore.dataVersion.value)
        RegistryStore.bumpDataVersion()
        RegistryStore.bumpDataVersion()
        assertEquals(3L, RegistryStore.dataVersion.value)
    }

    @Test
    fun `bumps are strictly monotonic — never resets implicitly`() {
        RegistryStore.resetDataVersionForTest()
        var previous = RegistryStore.dataVersion.value
        repeat(50) {
            RegistryStore.bumpDataVersion()
            val current = RegistryStore.dataVersion.value
            assertTrue(current > previous, "bump $it: $current must be > $previous")
            previous = current
        }
    }

    @Test
    fun `reset returns the counter to zero`() {
        RegistryStore.resetDataVersionForTest()
        repeat(7) { RegistryStore.bumpDataVersion() }
        assertEquals(7L, RegistryStore.dataVersion.value)
        RegistryStore.resetDataVersionForTest()
        assertEquals(0L, RegistryStore.dataVersion.value)
    }

    @Test
    fun `clear resets the counter`() {
        // clear() also disposes the handle; with no handle set the
        // destroy() runCatching no-ops, so we can exercise the reset
        // side-effect in isolation from registry-handle plumbing.
        RegistryStore.resetDataVersionForTest()
        repeat(4) { RegistryStore.bumpDataVersion() }
        assertEquals(4L, RegistryStore.dataVersion.value)
        RegistryStore.clear()
        assertEquals(
            0L,
            RegistryStore.dataVersion.value,
            "clear() must reset so a re-login starts a fresh observer cycle",
        )
    }
}
