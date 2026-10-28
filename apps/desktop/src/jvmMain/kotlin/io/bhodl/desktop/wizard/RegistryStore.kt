package io.bhodl.desktop.wizard

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.bhodl.ffi.RegistryHandle

/**
 * Process-lifetime singleton holding the open [`RegistryHandle`].
 *
 * Mirror of [`WalletStore`] for the device-PIN-scoped registry DB.
 * Populated either:
 * - right after a successful `runMigrationIfNeeded` (Migration screen
 *   re-opens the registry it just created and stashes the handle), or
 * - through the boot-time `DevicePinUnlockScreen` on a fresh process
 *   launch when `registry.sqlite` already exists from a previous
 *   session.
 *
 * Read by every screen that needs cross-wallet visibility — today the
 * Phase 3.1 debug screen, the new Welcome wallet picker in Phase 3.2,
 * the planned tracking dashboard in Phase 3.5, and the
 * "watch-only descriptor" Settings section that uses the registry to
 * dedupe the active signing wallet against tracked watch entries.
 *
 * Stays alive for the lifetime of the app session — `clear()` is
 * invoked only by Logout / Wipe paths. Holding it open across screens
 * avoids re-prompting for the device PIN every time the user navigates
 * into a tracking surface, AND keeps the SQLCipher file handle warm so
 * subsequent reads don't pay the Argon2id cost.
 */
public object RegistryStore {

    @Volatile
    private var handle: RegistryHandle? = null

    /** Inject a freshly opened registry handle. Replaces any previous
     *  handle and disposes it first to release the SQLCipher file
     *  lock — guards against a double-open if the boot flow
     *  inadvertently runs the device-PIN path twice. */
    public fun set(handle: RegistryHandle) {
        this.handle?.let { runCatching { it.destroy() } }
        this.handle = handle
    }

    /** Read the current handle. Throws if none has been opened. */
    public fun require(): RegistryHandle =
        handle ?: error("RegistryStore accessed before the registry was opened")

    /** Return the current handle or `null` if not yet opened. */
    public fun current(): RegistryHandle? = handle

    /**
     * Drop and dispose the handle. Called by Logout, Wipe, and (in
     * tests) tearDown. `destroy()` before nulling so the SQLCipher
     * Connection's file lock is released synchronously — without it,
     * a subsequent `WipeSection` delete of `registry.sqlite` can fail
     * on Windows where File.delete() returns false against a locked
     * file (same gotcha as `WalletStore.clear`, finding #14).
     */
    public fun clear() {
        handle?.let { runCatching { it.destroy() } }
        handle = null
        _dataVersion.value = 0L
        // Phase 7.2.3 — wipe the process-lifetime tracker cache so a
        // re-login doesn't briefly render the previous session's
        // wallets / bills / etc. Best-effort: reflection-style lookup
        // would couple the wizard layer to the data layer; the public
        // reset() call below is cheap and idempotent.
        io.bhodl.desktop.data.TrackerCache.reset()
        // GAP-015 — the net-worth history and price caches belong to the
        // session being closed (their keys carry no registry identity).
        kotlinx.coroutines.runBlocking { io.bhodl.desktop.prices.NetWorthHistoryClient.clearCache() }
        io.bhodl.desktop.prices.PriceCache.clearSessionCaches()
        // R5 — cancel any in-flight Phase 8.2 backfill BEFORE closing
        // the session below. Without this, the background job could still be
        // mid-run against a device PIN we are about to invalidate,
        // producing a non-deterministic Done/Failed state for a session
        // the user has already left.
        io.bhodl.desktop.data.BackfillRunner.cancel()
        // Close the device-PIN session (idle clock). The registry handle
        // destroyed above was the only holder of the derived key.
        DevicePinSession.clear()
    }

    // Phase 7.1.1 — cross-screen data-change bus. Mutating call sites
    // (addBill, addInvestment, addLiability, setBudget, deleteX…) invoke
    // [bumpDataVersion] right after a successful write. Screens that
    // render registry data key a LaunchedEffect on [dataVersion] so they
    // reload automatically — without it, opening the TrackerHome after
    // adding a bill from the Tracking dashboard shows stale data until
    // the user manually taps Refresh. Sequence-only: the value carries
    // no payload, the observer just re-fetches.
    private val _dataVersion = mutableStateOf(0L)

    /** Reactive Compose state. Use as a [LaunchedEffect] key to reload
     *  whenever any mutation touches the registry. */
    public val dataVersion: State<Long> get() = _dataVersion

    /** Bump the version counter, signalling all observers that the
     *  registry has changed. Idempotent under concurrent calls — Long
     *  arithmetic on a single MutableState write is atomic enough for
     *  this purpose; collisions just merge into a single observer wake. */
    public fun bumpDataVersion() {
        _dataVersion.value = _dataVersion.value + 1L
    }

    /** Reset the version. Used by tests and by [clear] so a logout +
     *  re-login doesn't leak the previous session's counter. */
    public fun resetDataVersionForTest() {
        _dataVersion.value = 0L
    }
}
