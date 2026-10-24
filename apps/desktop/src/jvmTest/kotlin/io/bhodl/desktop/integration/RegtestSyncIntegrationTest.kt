package io.bhodl.desktop.integration

import io.bhodl.ffi.RegistryHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * L3 smoke test: prove the cdylib + BDK + Esplora REST roundtrip works
 * end-to-end for a watch-only wallet against a real `bitcoind` regtest
 * + `electrs` container pair (no mocks). Registers the BIP-86 test
 * account tpub, funds its first receive address from the on-node miner
 * wallet and asserts the balance lands after a normal
 * [`io.bhodl.ffi.WatchHandle.syncWithEsplora`] call.
 *
 * Compared to the L2 [`io.bhodl.desktop.wallet.FakeWalletPort`] tests:
 * - The cdylib loads from the JVM classpath under the same packaging
 *   the production app uses.
 * - BDK's Esplora chain client + Core 28.x agree on the regtest
 *   block / tx wire format.
 * - The Esplora REST surface electrs exposes (`/blocks/tip/hash`,
 *   `/scripthash/.../utxo`, …) is byte-compatible with what BDK
 *   expects from mempool.space in production.
 */
class RegtestSyncIntegrationTest : RegtestFixture() {

    @Test
    fun `watch-only wallet picks up an inbound payment after esplora sync`(@TempDir tempDir: Path) {
        val registry = RegistryHandle.createOrLoad(tempDir.toFile().absolutePath, TEST_DEVICE_PIN)
        val wallet = openRegtestWatch(registry)
        try {
            val receiveAddress = wallet.peekAddressAt(0u)
            // Sanity: regtest taproot addresses use the bcrt1p prefix.
            assertTrue(
                receiveAddress.startsWith("bcrt1p"),
                "expected bcrt1p taproot address, got $receiveAddress",
            )

            fundWallet(receiveAddress, "0.001")
            syncUntil(wallet) { it.balanceSat() > 0u }

            assertTrue(
                wallet.balanceSat() >= 100_000u,
                "expected balance >= 100_000 sat (0.001 BTC), saw ${wallet.balanceSat()}",
            )
            val utxo = wallet.listUtxos().single()
            assertEquals(receiveAddress, utxo.address)
            assertTrue(utxo.confirmed)
            assertTrue(wallet.chainTipHeight() >= 102u, "tip ${wallet.chainTipHeight()}")
            assertEquals(1, wallet.listTransactions().size)
        } finally {
            runCatching { wallet.destroy() }
            runCatching { registry.destroy() }
        }
    }
}
