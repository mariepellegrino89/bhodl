package io.bhodl.desktop.integration

import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletHandle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.Network as TcNetwork

/**
 * Shared fixture for the L3 integration tests.
 *
 * Each subclass gets a fresh network, a `bitcoind` regtest with the
 * coinbase chain matured (101 blocks paid to an on-node miner), and
 * an `electrs` indexer pointed at it. Container startup is paid once
 * per subclass via `@BeforeAll` and tear-down happens on `@AfterAll`.
 *
 * The `@TestInstance(PER_CLASS)` lifecycle is required for the
 * non-static `@BeforeAll` / `@AfterAll` to fire on a Kotlin class
 * without companion-object boilerplate.
 *
 * Subclasses use [`fundWallet`] to push satoshis to a wallet address
 * and have the resulting UTXO confirmed in one call. They build their
 * own [`WalletHandle`] (each test class gets its own mnemonic so
 * containers stay isolated and we never interleave wallet state
 * across tests).
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RegtestFixture {

    protected lateinit var network: TcNetwork
    protected lateinit var bitcoind: BitcoindRegtestContainer
    protected lateinit var electrs: ElectrsContainer
    protected lateinit var rpc: BitcoinRpcClient

    @BeforeAll
    fun bootRegtestStack() {
        network = TcNetwork.newNetwork()
        bitcoind = BitcoindRegtestContainer(network).apply { start() }
        rpc = BitcoinRpcClient(bitcoind.rpcBaseUrl())

        // 101 blocks make the genesis-style coinbase mature, which is
        // what `sendtoaddress` needs to fund anything else without
        // separate UTXO juggling.
        rpc.createWalletIfMissing("miner")
        val minerAddr = rpc.getNewAddress()
        rpc.generateToAddress(101, minerAddr)

        electrs = ElectrsContainer(network).apply { start() }
    }

    @AfterAll
    fun tearDownRegtestStack() {
        if (::electrs.isInitialized) electrs.stop()
        if (::bitcoind.isInitialized) bitcoind.stop()
        if (::network.isInitialized) network.close()
    }

    /** Esplora REST endpoint for `wallet.syncWithEsplora` / `broadcastTx`. */
    protected fun esploraUrl(): String = electrs.esploraApiUrl()

    /**
     * Send `amountBtc` BTC from the on-node miner wallet to `address`
     * and mine one block to confirm. Returns the funding txid.
     */
    protected fun fundWallet(address: String, amountBtc: String = "0.001"): String {
        val txid = rpc.sendToAddress(address, amountBtc)
        val sinkAddr = rpc.getNewAddress()
        rpc.generateToAddress(1, sinkAddr)
        return txid
    }

    /** Mine `count` extra blocks to a throwaway address. */
    protected fun mineBlocks(count: Int) {
        val sinkAddr = rpc.getNewAddress()
        rpc.generateToAddress(count, sinkAddr)
    }

    /**
     * Repeatedly call `wallet.syncWithEsplora` until [`predicate`]
     * returns `true` or [`timeoutMs`] elapses. Returns once the
     * predicate is satisfied; throws [`AssertionError`] on timeout.
     *
     * Used in place of a fixed sleep — electrs takes a variable amount
     * of time to index a new block depending on host load, so we poll
     * instead of guessing a delay.
     */
    protected fun syncUntil(
        wallet: WalletHandle,
        timeoutMs: Long = 30_000L,
        intervalMs: Long = 500L,
        predicate: (WalletHandle) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSyncFailure: Throwable? = null
        while (System.currentTimeMillis() < deadline) {
            // Swallow transient EsploraSync failures during the polling
            // window — electrs intermittently returns 5xx while it is
            // catching up to a freshly-mined block, especially on a
            // slow CI runner. We only let the timeout fire; if the
            // last sync DID throw and the predicate also did not
            // converge, surface the underlying cause.
            try {
                wallet.syncWithEsplora(esploraUrl(), null)
                lastSyncFailure = null
                if (predicate(wallet)) return
            } catch (e: io.bhodl.ffi.WalletException) {
                lastSyncFailure = e
            }
            Thread.sleep(intervalMs)
        }
        throw AssertionError(
            "syncUntil predicate never returned true within ${timeoutMs}ms" +
                (lastSyncFailure?.let { " (last sync error: ${it.message})" } ?: ""),
            lastSyncFailure,
        )
    }

    protected fun newRegtestWallet(mnemonic: String): WalletHandle =
        WalletHandle.fromMnemonic(
            network = Network.REGTEST,
            mnemonic = mnemonic,
            passphrase = "",
        )

    protected companion object {
        // Standard BIP86 test mnemonic. Different subclasses use the
        // same one because the bitcoind container is per-class so
        // their on-chain histories never collide.
        const val BIP86_TEST_MNEMONIC: String =
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
    }
}
