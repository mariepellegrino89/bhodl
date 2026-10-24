package io.bhodl.desktop.wizard

import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.ffi.Network
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for [`WalletStore`] — the process-lifetime holder of the
 * open watch-only wallet. Most paths are exercised end-to-end by the
 * *ScreenTest suites; this pins the handle lifecycle (close on replace /
 * clear) that the Windows file-lock fix depends on.
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
    fun `clear closes the port and resets state`() {
        val fake = FakeWalletPort()
        WalletStore.set(fake, Network.REGTEST, walletId = "w1", label = "Cold storage")

        WalletStore.clear()

        assertNull(WalletStore.current())
        assertNull(WalletStore.activeWalletId())
        assertNull(WalletStore.activeWalletLabel())
        assertEquals(1, fake.closeCalls)
        // Default network bounces back to Signet.
        assertEquals(Network.SIGNET, WalletStore.network())
    }

    @Test
    fun `set records network, id and label`() {
        val fake = FakeWalletPort()
        WalletStore.set(fake, Network.REGTEST, walletId = "w1", label = "Savings")
        assertEquals(Network.REGTEST, WalletStore.network())
        assertSame(fake, WalletStore.current())
        assertSame(fake, WalletStore.require())
        assertEquals("w1", WalletStore.activeWalletId())
        assertEquals("Savings", WalletStore.activeWalletLabel())
    }

    @Test
    fun `set without network defaults to Signet`() {
        WalletStore.set(FakeWalletPort())
        assertEquals(Network.SIGNET, WalletStore.network())
        assertNull(WalletStore.activeWalletId())
    }

    @Test
    fun `replacing the port closes the previous one exactly once`() {
        val first = FakeWalletPort()
        val second = FakeWalletPort()
        WalletStore.set(first, Network.SIGNET, walletId = "a")
        WalletStore.set(second, Network.SIGNET, walletId = "b")
        assertEquals(1, first.closeCalls)
        assertEquals(0, second.closeCalls)
        // Re-setting the same port must not close it.
        WalletStore.set(second, Network.SIGNET, walletId = "b")
        assertEquals(0, second.closeCalls)
    }

    @Test
    fun `require throws when no wallet is open`() {
        assertFailsWith<IllegalStateException> { WalletStore.require() }
    }
}
