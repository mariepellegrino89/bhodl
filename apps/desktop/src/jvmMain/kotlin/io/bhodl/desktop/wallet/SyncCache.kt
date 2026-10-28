package io.bhodl.desktop.wallet

/**
 * Process-lifetime cache of the most-recent successful Esplora sync.
 *
 * Two pieces of state:
 * - `lastSyncAtMs` — wall-clock millis of the last sync that landed.
 * - `lastBalanceSat` — the satoshi balance returned by that sync.
 *
 * The Dashboard reads this on every entry: if the cache is "fresh"
 * (within [`DEFAULT_COOLDOWN_MS`]) it skips the auto-sync entirely
 * and renders the cached balance immediately. Outside the window,
 * the auto-sync still fires but runs in *background* mode — the
 * navigation buttons stay enabled and the user can keep moving
 * while mempool.space is fetched.
 *
 * The manual "Sync" button bypasses the cache check (an explicit
 * user action should always force a fresh round-trip) and runs in
 * *foreground* mode, where the buttons are disabled while the call
 * is in flight.
 *
 * Cleared by [`io.bhodl.desktop.wizard.WalletStore.clear`] so a
 * Logout (or wallet swap) does not leak the previous wallet's
 * balance into the next session.
 */
public object SyncCache {

    /**
     * Default cooldown window. mempool.space's signet feed updates
     * roughly per-block (~10 minutes), so a one-minute window is
     * reactive enough to catch incoming payments quickly without
     * hammering the endpoint on every back-stack pop.
     */
    public const val DEFAULT_COOLDOWN_MS: Long = 60_000L

    @Volatile
    private var lastSyncAtMs: Long? = null

    @Volatile
    private var lastBalanceSat: ULong? = null

    /** Last successful sync timestamp, in wall-clock millis. */
    public fun lastSyncAtMs(): Long? = lastSyncAtMs

    /** Balance reported by the last successful sync, or `null` if never synced. */
    public fun lastBalanceSat(): ULong? = lastBalanceSat

    /**
     * Record the result of a successful sync. `atMs` defaults to the
     * current wall-clock time; tests inject a deterministic value.
     */
    public fun record(balanceSat: ULong, atMs: Long = io.bhodl.desktop.AppClock.nowMillis()) {
        lastBalanceSat = balanceSat
        lastSyncAtMs = atMs
    }

    /**
     * `true` iff a sync has happened and is still inside the cooldown
     * window (default one minute). When this is `true` the Dashboard
     * skips the auto-sync.
     */
    public fun isFresh(
        nowMs: Long = io.bhodl.desktop.AppClock.nowMillis(),
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ): Boolean {
        val last = lastSyncAtMs ?: return false
        return nowMs - last < cooldownMs
    }

    /** Drop both timestamp and balance. Called from Logout / wallet swap. */
    public fun clear() {
        lastSyncAtMs = null
        lastBalanceSat = null
    }
}
