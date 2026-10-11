package io.bhodl.desktop.wizard

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wallet.UniffiWalletPort
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletHandle

/**
 * Process-lifetime singleton holding the unlocked [`WalletPort`].
 *
 * The port is set the first time the wizard finishes (PinSetup) or
 * the user successfully unlocks an existing wallet, and read by every
 * post-wizard screen (Dashboard, Send, History, AddressBook, …).
 *
 * Holding it in a singleton avoids piping the dependency through every
 * navigation route while the app does not yet have a proper DI graph
 * (Koin lands in a follow-up). The screens depend on the
 * [`WalletPort`] interface rather than the concrete `WalletHandle` so
 * Compose UI tests can swap in a fake.
 *
 * Replace with `LocalWalletPort` CompositionLocal or Koin once a
 * second target (Android, iOS) needs the same bridge.
 */
public object WalletStore {

    @Volatile
    private var port: WalletPort? = null

    /**
     * Network the currently-loaded wallet was created against. Set
     * alongside [`port`] at unlock / wizard-completion time, read by
     * Dashboard for its label and by Settings for the read-only
     * indicator.
     *
     * Defaults to [`Network.SIGNET`] for tests that pre-populate
     * [`port`] without going through the wizard / unlock flow — they
     * are network-agnostic at the assertion level.
     */
    @Volatile
    private var loadedNetwork: Network = Network.SIGNET

    /**
     * UUID of the currently-loaded signing wallet. Set alongside
     * [`port`] at unlock / wizard time. Read by Settings to know
     * which `registry.wallets` row to rename, and by the Phase 3.5
     * tracking dashboard to highlight the active wallet across
     * cross-wallet listings.
     *
     * `null` until `set(port, network, walletId)` is called with a
     * non-null id — covers the legacy-test path that bypasses the
     * registry entirely.
     */
    @Volatile
    private var loadedWalletId: String? = null

    /**
     * Flag set immediately after a Restore (mnemonic or backup)
     * succeeds so the Dashboard can render a "Restoring wallet…"
     * banner during the first sync — that one is a full chain scan
     * that can take seconds to a few minutes on signet, depending on
     * how many addresses BDK has to walk before it hits `stop_gap`
     * consecutive empty ones. Cleared once the first successful
     * background sync lands.
     */
    private val _justRestored = mutableStateOf(false)
    public val justRestored: State<Boolean> get() = _justRestored

    public fun markJustRestored() {
        _justRestored.value = true
    }

    public fun markRestoreComplete() {
        _justRestored.value = false
    }

    /**
     * Monotonic counter bumped after every broadcast attempt (success
     * or failure). Screens that render mempool-sensitive state can
     * observe it via `LaunchedEffect(WalletStore.txEpoch.value)` to
     * force a re-sync + re-list when the user returns from an action
     * screen.
     *
     * Why this is needed: BDK's `listTransactions()` reads the local
     * persisted store; it does NOT re-derive from the network. After
     * a tx is RBF-replaced or dropped by the mempool while the user
     * is on Send / Bump / CPFP / PayJoin, the local store stays
     * stale until the next explicit `sync_with_esplora`. Bumping the
     * epoch on every broadcast lets the History screen know it must
     * refresh, without forcing a sync on every screen change.
     */
    private val _txEpoch = mutableStateOf(0L)
    public val txEpoch: State<Long> get() = _txEpoch

    /**
     * Increment [`txEpoch`]. Called from every action screen's
     * onSuccess AND onFailure paths (a failed broadcast can also
     * mean the mempool state shifted — e.g. a `-25 missingorspent`
     * or `-26 replacement` rejection both tell us the local view
     * lagged the node's view).
     */
    public fun markTxEpochAdvanced() {
        // The wallet workspace syncs only when the cache is stale; a
        // broadcast must force that re-sync even inside the cooldown.
        SyncCache.markStale()
        _txEpoch.value = _txEpoch.value + 1L
    }

    /** Inject a freshly unlocked wallet port. Network defaults to Signet for tests. */
    public fun set(port: WalletPort, network: Network = Network.SIGNET, walletId: String? = null) {
        this.port = port
        this.loadedNetwork = network
        this.loadedWalletId = walletId
    }

    /**
     * Convenience overload for the production code paths that come
     * straight out of the FFI: wraps the raw `WalletHandle` in
     * [`UniffiWalletPort`] before storing it.
     */
    public fun set(
        handle: WalletHandle,
        network: Network = Network.SIGNET,
        walletId: String? = null,
    ) {
        this.port = UniffiWalletPort(handle)
        this.loadedNetwork = network
        this.loadedWalletId = walletId
    }

    /** Network the currently-loaded wallet was created against. */
    public fun network(): Network = loadedNetwork

    /** UUID of the currently-loaded signing wallet, or `null`. */
    public fun activeWalletId(): String? = loadedWalletId

    /** Read the current port. Throws if none has been set. */
    public fun require(): WalletPort =
        port ?: error("WalletStore accessed before any wallet was unlocked")

    /** Return the current port or `null` if no wallet is unlocked. */
    public fun current(): WalletPort? = port

    /**
     * Clear the port. Used by tests and by Logout on the Dashboard.
     *
     * Also drops the [`SyncCache`] so the next wallet that lands here
     * starts with a clean slate — leaking the previous wallet's
     * balance into a new session would be both confusing and
     * privacy-leaky.
     */
    public fun clear() {
        // `close()` BEFORE nulling so the underlying SQLCipher file
        // lock is released synchronously. Without this, the JVM GC
        // can hold onto the Arc<WalletHandle> for an arbitrary
        // amount of time and a subsequent wipe of `wallet.sqlite`
        // silently fails on Windows (File.delete() returns false
        // when the file is locked). See finding #14 in
        // `SMOKE_TEST_FINDINGS.md` — the orphan-sqlite state poisons
        // the next Restore attempt because the new PIN-derived key
        // cannot decrypt the previous wallet's DB.
        port?.let { runCatching { it.close() } }
        port = null
        loadedNetwork = Network.SIGNET
        loadedWalletId = null
        SyncCache.clear()
        io.bhodl.desktop.wallet.FeeBumpLog.clear()
        _justRestored.value = false
        _txEpoch.value = 0L
    }
}
