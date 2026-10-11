package io.bhodl.desktop.wizard

import io.bhodl.ffi.WatchHandle

/**
 * Process-lifetime singleton for the currently-active watch wallet.
 *
 * Parallel to [`WalletStore`] (which holds the signing wallet port).
 * Set when the user taps a watch wallet row in the Welcome picker and
 * the boot path opens its `WatchHandle` via
 * `RegistryHandle.openWatch(walletId)`. Read by the
 * [`io.bhodl.desktop.screens.WatchDashboardScreen`] to render balance
 * + history.
 *
 * Phase 3.2 sub-D split:
 * - Watch and signing wallets share the Welcome picker and Tracking
 *   surfaces; they diverge at activation time. A signing tap opens
 *   [`io.bhodl.desktop.screens.UnlockScreen`] (per-wallet PIN), a
 *   watch tap opens [`io.bhodl.desktop.screens.WatchDashboardScreen`]
 *   (no PIN — the device key already in `RegistryStore` is enough).
 *
 * Holding it in a singleton mirrors the existing `WalletStore`
 * pattern. Cleared on logout / wipe.
 */
public object WatchStore {

    @Volatile
    private var handle: WatchHandle? = null

    @Volatile
    private var loadedWalletId: String? = null

    @Volatile
    private var loadedLabel: String? = null

    /** Inject the freshly-opened watch handle. */
    public fun set(handle: WatchHandle, walletId: String, label: String) {
        // Release any previous handle first so the SQLCipher file
        // lock drops before we replace it — finding #14 pattern
        // applied to watch-state files.
        this.handle?.let { runCatching { it.destroy() } }
        this.handle = handle
        this.loadedWalletId = walletId
        this.loadedLabel = label
    }

    /** Read the current handle. Throws if none has been set. */
    public fun require(): WatchHandle =
        handle ?: error("WatchStore accessed before any watch wallet was opened")

    /** Return the current handle or `null` if not yet opened. */
    public fun current(): WatchHandle? = handle

    /** UUID of the currently-active watch wallet, or `null`. */
    public fun activeWatchId(): String? = loadedWalletId

    /** Display label of the currently-active watch wallet, or `null`. */
    public fun activeWatchLabel(): String? = loadedLabel

    /**
     * Drop and dispose the handle. Called by Logout, Wipe, and when
     * the user navigates back out of the watch wallet view.
     * Releases the SQLCipher file lock synchronously so future
     * registry-side operations (remove, factory reset) do not race
     * against a still-open Connection.
     */
    public fun clear() {
        handle?.let { runCatching { it.destroy() } }
        handle = null
        loadedWalletId = null
        loadedLabel = null
    }
}
