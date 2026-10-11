package io.bhodl.desktop.wizard

import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.ffi.Network
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for [`WalletStore`].
 *
 * Most paths are exercised end-to-end by the *ScreenTest suites, but
 * the `txEpoch` reconciliation counter (introduced for the History
 * refresh-on-resume fix) earns its own dedicated coverage so a future
 * refactor can't silently break the History → action-screen
 * coordination.
 */
class WalletStoreTest {

    @BeforeTest
    fun reset() {
        WalletStore.clear()
    }

    @AfterTest
    fun cleanup() {
        WalletStore.clear()
    }

    @Test
    fun `clear leaves no port and resets state`() {
        WalletStore.set(FakeWalletPort(), Network.REGTEST)
        WalletStore.markJustRestored()
        WalletStore.markTxEpochAdvanced()
        WalletStore.markTxEpochAdvanced()

        WalletStore.clear()

        assertNull(WalletStore.current())
        assertEquals(false, WalletStore.justRestored.value)
        assertEquals(
            0L,
            WalletStore.txEpoch.value,
            "txEpoch must reset to 0 so subsequent runs start clean",
        )
        // Default network bounces back to Signet (matches the
        // production fallback when no NetworkPersistence file exists).
        assertEquals(Network.SIGNET, WalletStore.network())
    }

    @Test
    fun `set with network records it`() {
        val fake = FakeWalletPort()
        WalletStore.set(fake, Network.REGTEST)
        assertEquals(Network.REGTEST, WalletStore.network())
        assertEquals(fake, WalletStore.current())
    }

    @Test
    fun `set without network defaults to Signet`() {
        WalletStore.set(FakeWalletPort())
        assertEquals(Network.SIGNET, WalletStore.network())
    }

    // ----- txEpoch ----------------------------------------------------------

    @Test
    fun `txEpoch starts at zero on a fresh store`() {
        assertEquals(0L, WalletStore.txEpoch.value)
    }

    @Test
    fun `markTxEpochAdvanced increments monotonically`() {
        assertEquals(0L, WalletStore.txEpoch.value)
        WalletStore.markTxEpochAdvanced()
        assertEquals(1L, WalletStore.txEpoch.value)
        WalletStore.markTxEpochAdvanced()
        WalletStore.markTxEpochAdvanced()
        assertEquals(3L, WalletStore.txEpoch.value)
    }

    @Test
    fun `txEpoch is a State observable for Compose`() {
        // Compose screens read via `val x by WalletStore.txEpoch`. The
        // type must be `State<Long>` so the LaunchedEffect key actually
        // triggers recompositions when bumped. The simplest guard is
        // to assert the value flips identity on bump — a plain Long
        // assignment wouldn't notify Compose, and the test fixture
        // would still pass but `LaunchedEffect(epoch)` would silently
        // never re-fire in production.
        val state = WalletStore.txEpoch
        assertNotNull(state)
        val before = state.value
        WalletStore.markTxEpochAdvanced()
        assertEquals(before + 1L, state.value)
    }

    @Test
    fun `markJustRestored and markRestoreComplete flip the banner flag`() {
        assertEquals(false, WalletStore.justRestored.value)
        WalletStore.markJustRestored()
        assertEquals(true, WalletStore.justRestored.value)
        WalletStore.markRestoreComplete()
        assertEquals(false, WalletStore.justRestored.value)
    }
}
