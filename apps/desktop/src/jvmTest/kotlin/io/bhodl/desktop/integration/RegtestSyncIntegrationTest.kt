package io.bhodl.desktop.integration

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * L3 smoke test: prove the cdylib + BDK 3.0 + Esplora REST roundtrip
 * works end-to-end against a real `bitcoind` regtest + `electrs`
 * container pair (no mocks). Funds an address from the on-node miner
 * wallet and asserts the balance lands after a normal
 * [`io.bhodl.ffi.WalletHandle.syncWithEsplora`] call.
 *
 * Compared to the L2 [`io.bhodl.desktop.wallet.FakeWalletPort`] tests:
 * - The cdylib loads from the JVM classpath under the same packaging
 *   the production app uses.
 * - BDK 3.0's Esplora chain client + Core 28.x agree on the regtest
 *   block / tx wire format.
 * - The Esplora REST surface electrs exposes (`/blocks/tip/hash`,
 *   `/scripthash/.../utxo`, …) is byte-compatible with what BDK
 *   expects from mempool.space in production.
 */
class RegtestSyncIntegrationTest : RegtestFixture() {

    @Test
    fun `wallet picks up an inbound payment after esplora sync`() {
        val wallet = newRegtestWallet(BIP86_TEST_MNEMONIC)
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
    }
}
