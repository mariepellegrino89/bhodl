package io.bhodl.desktop.integration

import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Area B on-chain use cases against bitcoind + electrs (Docker):
 * address-list sync (WAL-049), the stop-gap of the HD full scan
 * (WAL-050) and synced state visible after a reopen (WAL-057).
 */
class UcWalletRegtestIntegrationTest : RegtestFixture() {

    private fun freshRegistry(): Pair<java.io.File, RegistryHandle> {
        val dir = kotlin.io.path.createTempDirectory("bhodl-uc-regtest-").toFile()
        return dir to RegistryHandle.createOrLoad(dir.absolutePath, TEST_DEVICE_PIN)
    }

    @Test
    fun `WAL-049 an address-list wallet picks up a payment through a real spk sync`() {
        val (dir, registry) = freshRegistry()
        val target = rpc.getNewAddress()
        val record = registry.addWatchAddresses("list", listOf(target), Network.REGTEST)
        val wallet = registry.openWatch(record.id)
        try {
            fundWallet(target, "0.0005")
            syncUntil(wallet) { it.balanceSat() >= 50_000u }
            val tx = wallet.listTransactions().single()
            assertEquals(50_000L, tx.netSat)
            assertNotNull(tx.confirmationHeight)
            assertNotNull(tx.timestamp)
            assertTrue(wallet.listUtxos().single().confirmed)
            assertTrue(wallet.chainTipHeight() > 0u)
        } finally {
            runCatching { wallet.destroy() }
            runCatching { registry.destroy() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun `WAL-050 WAL-057 stop-gap 20 hides index 25 until index 19 is used, state survives reopen`() {
        val (dir, registry) = freshRegistry()
        val record = registry.addWatchXpub("gap", BIP86_TEST_TPUB, Network.REGTEST, io.bhodl.ffi.WatchBipFfi.TAPROOT)
        var wallet = registry.openWatch(record.id)
        try {
            val far = wallet.peekAddressAt(25u)
            fundWallet(far, "0.0002")
            mineBlocks(1)
            wallet.syncWithEsplora(esploraUrl(), null)
            Thread.sleep(2_000)
            wallet.syncWithEsplora(esploraUrl(), null)
            assertEquals(0uL, wallet.balanceSat(), "index 25 is beyond the stop gap")

            fundWallet(wallet.peekAddressAt(19u), "0.0001")
            syncUntil(wallet) { it.balanceSat() >= 30_000u }
            assertEquals(30_000uL, wallet.balanceSat())
            val txCount = wallet.listTransactions().size
            val tip = wallet.chainTipHeight()

            wallet.destroy()
            wallet = registry.openWatch(record.id)
            // No sync after the reopen: everything comes from the encrypted store.
            assertEquals(30_000uL, wallet.balanceSat())
            assertEquals(txCount, wallet.listTransactions().size)
            assertEquals(tip, wallet.chainTipHeight())
        } finally {
            runCatching { wallet.destroy() }
            runCatching { registry.destroy() }
            dir.deleteRecursively()
        }
    }
}
