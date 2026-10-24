package io.bhodl.desktop.wizard

import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wallet.UniffiWalletPort
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.ffi.Network
import io.bhodl.ffi.WatchHandle

/**
 * Process-lifetime singleton holding the active (watch-only) wallet.
 *
 * Set when the user opens a wallet from the tracker's wallet list
 * (`RegistryHandle.openWatch(walletId)` — no per-wallet PIN: the device
 * key held by [RegistryStore] is enough) and read by the wallet
 * workspace (Transactions, Receive, Addresses, UTXOs, Contacts,
 * Wallet settings).
 *
 * Holding it in a singleton avoids piping the dependency through every
 * navigation route; screens depend on the [`WalletPort`] interface so
 * Compose UI tests can swap in a fake.
 */
public object WalletStore {

    @Volatile
    private var port: WalletPort? = null

    /**
     * Network of the loaded wallet. Defaults to [`Network.SIGNET`] for
     * tests that pre-populate the port without a registry row.
     */
    @Volatile
    private var loadedNetwork: Network = Network.SIGNET

    /** Registry UUID of the loaded wallet, `null` for test-only ports. */
    @Volatile
    private var loadedWalletId: String? = null

    /** Display label of the loaded wallet. */
    @Volatile
    private var loadedLabel: String? = null

    /** Inject a wallet port. Network defaults to Signet for tests. */
    public fun set(
        port: WalletPort,
        network: Network = Network.SIGNET,
        walletId: String? = null,
        label: String? = null,
    ) {
        // Release any previous handle first so its SQLCipher file lock
        // drops before we replace it (Windows).
        if (this.port !== port) this.port?.let { runCatching { it.close() } }
        if (walletId != loadedWalletId) SyncCache.clear()
        this.port = port
        this.loadedNetwork = network
        this.loadedWalletId = walletId
        this.loadedLabel = label
    }

    /**
     * Convenience overload for production: wraps the `WatchHandle`
     * returned by `RegistryHandle.openWatch` in [`UniffiWalletPort`].
     */
    public fun set(handle: WatchHandle, network: Network, walletId: String, label: String) {
        set(UniffiWalletPort(handle), network, walletId, label)
    }

    /** Network the currently-loaded wallet lives on. */
    public fun network(): Network = loadedNetwork

    /** UUID of the currently-loaded wallet, or `null`. */
    public fun activeWalletId(): String? = loadedWalletId

    /** Display label of the currently-loaded wallet, or `null`. */
    public fun activeWalletLabel(): String? = loadedLabel

    /** Read the current port. Throws if no wallet is open. */
    public fun require(): WalletPort =
        port ?: error("WalletStore accessed before any wallet was opened")

    /** Return the current port or `null` if no wallet is open. */
    public fun current(): WalletPort? = port

    /**
     * Close and drop the port. Used by Lock, Wipe and wallet removal.
     *
     * `close()` runs BEFORE nulling so the SQLCipher file lock is
     * released synchronously — otherwise a subsequent delete of the
     * watch-state file silently fails on Windows. Also drops the
     * [`SyncCache`] so the next wallet starts with a clean slate.
     */
    public fun clear() {
        port?.let { runCatching { it.close() } }
        port = null
        loadedNetwork = Network.SIGNET
        loadedWalletId = null
        loadedLabel = null
        SyncCache.clear()
    }
}
