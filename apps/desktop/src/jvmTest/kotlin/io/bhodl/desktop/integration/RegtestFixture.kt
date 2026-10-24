package io.bhodl.desktop.integration

import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WatchBipFfi
import io.bhodl.ffi.WatchHandle
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
 * and have the resulting UTXO confirmed in one call. They open their
 * own watch-only [`WatchHandle`] through a [`RegistryHandle`] rooted in
 * a temp dir ([`openRegtestWatch`]): BHODL never holds keys, so the
 * wallet is registered from the BIP-86 test account tpub.
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

    /** Esplora REST endpoint for `wallet.syncWithEsplora`. */
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
        wallet: WatchHandle,
        timeoutMs: Long = 30_000L,
        intervalMs: Long = 500L,
        predicate: (WatchHandle) -> Boolean,
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

    /**
     * Register the BIP-86 test account as a regtest watch-only xpub
     * wallet in [registry] and open it.
     */
    protected fun openRegtestWatch(registry: RegistryHandle, label: String = "regtest"): WatchHandle {
        val record = registry.addWatchXpub(label, BIP86_TEST_TPUB, Network.REGTEST, WatchBipFfi.TAPROOT)
        return registry.openWatch(record.id)
    }

    protected companion object {
        /**
         * Account tpub at `m/86'/1'/0'` of the BIP-86 test mnemonic
         * ("abandon … about"). Different subclasses use the same one
         * because the bitcoind container is per-class so their on-chain
         * histories never collide.
         */
        const val BIP86_TEST_TPUB: String =
            "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"

        /** Device PIN of the temp registries. */
        const val TEST_DEVICE_PIN: String = "482916"
    }
}
