package io.bhodl.desktop.prices

import io.bhodl.desktop.AppScope
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.NwSnapshotFfi
import io.bhodl.ffi.PaymentEventLiteFfi
import io.bhodl.ffi.WalletException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase 8.4 — Kotlin wrapper over the Rust net-worth history FFI
 * (`RegistryHandle.netWorthHistory`).
 *
 * Mirrors the [BtcRateHistoryClient] singleton pattern. Behind the object
 * sits an [NwHistorySource] seam so unit tests can inject a fake without
 * touching the FFI or disk. Production code relies on the default
 * [FfiNwHistorySource], which uses the open registry handle (no device
 * PIN, no per-call Argon2) and the Tor proxy when Tor is on.
 *
 * ## Cache (audit 2026-10, M2)
 *
 * Results are cached in-memory. The key is
 * `(currency, fromDay, toDay, events)` with the window aligned to UTC
 * days: the old key used the caller's second-resolution "now", so every
 * recomposition produced a brand-new key and a brand-new (slow, possibly
 * Tor-routed) JNI call. The map is bounded ([MAX_CACHE_ENTRIES], LRU).
 * TTL rules follow the Phase 8.4 spec §3.5:
 *  - ≤1 day window  → 5 min
 *  - ≤90 days       → 1 h
 *  - otherwise      → 6 h
 *
 * ## In-flight dedupe
 *
 * At most ONE FFI call runs per key: concurrent callers (e.g. the
 * period switch re-launching the effect) await the same [Deferred]. The
 * call itself runs on [AppScope.io], so a caller that gets cancelled
 * mid-flight does not strand the (uncancellable) JNI call — its result
 * still lands in the cache for the next request.
 *
 * ## Error policy
 *
 * Any [WalletException] is caught, logged to stderr (dev visibility),
 * and an empty list is returned. The UI caller must fall back to the
 * flat placeholder sparkline when an empty list is received.
 */
public object NetWorthHistoryClient {

    private const val SECS_PER_DAY: Long = 86_400L

    /** Upper bound on cached windows (period × currency × event sets). */
    private const val MAX_CACHE_ENTRIES: Int = 32

    // -----------------------------------------------------------------------
    // Domain type
    // -----------------------------------------------------------------------

    /**
     * Kotlin-side mirror of `NwSnapshotFfi`.
     *
     * All `*Sat` fields can be negative (e.g. [totalSat] when liabilities
     * exceed assets). [fiatEquivalent] is `totalSat × rateBtcFiat / 1e8`.
     */
    public data class NwSnapshot(
        val dateUnix: Long,
        val totalSat: Long,
        val onchainSat: Long,
        val investmentsSat: Long,
        val manualAssetsSat: Long,
        val liabilitiesSat: Long,
        val fiatEquivalent: Double,
        val rateBtcFiat: Double,
    )

    // -----------------------------------------------------------------------
    // Source seam
    // -----------------------------------------------------------------------

    /** Source interface — swappable for tests. */
    public interface NwHistorySource {
        @Throws(WalletException::class)
        public fun netWorthHistory(
            fromUnix: Long,
            toUnix: Long,
            currency: String,
            events: List<PaymentEventLiteFfi>,
        ): List<NwSnapshotFfi>
    }

    /** Default: the open registry handle + the Tor proxy when enabled. */
    public object FfiNwHistorySource : NwHistorySource {
        @Throws(WalletException::class)
        override fun netWorthHistory(
            fromUnix: Long,
            toUnix: Long,
            currency: String,
            events: List<PaymentEventLiteFfi>,
        ): List<NwSnapshotFfi> {
            val registry = RegistryStore.current()
                ?: throw WalletException.Storage("registry not open")
            return registry.netWorthHistory(
                fromUnix,
                toUnix,
                currency,
                events,
                EsploraEndpoint.currentProxy(),
            )
        }
    }

    @Volatile
    private var source: NwHistorySource = FfiNwHistorySource

    /** Inject a custom source. Only for tests. */
    public fun setSourceForTest(s: NwHistorySource) {
        source = s
    }

    /** Reset to the default FFI-backed source. */
    public fun resetSourceForTest() {
        source = FfiNwHistorySource
    }

    // -----------------------------------------------------------------------
    // In-memory cache + in-flight table
    // -----------------------------------------------------------------------

    private data class CacheKey(
        val currency: FiatCurrency,
        val fromDay: Long,
        val toDay: Long,
        val events: List<PaymentEventLiteFfi>,
    )

    private data class CacheEntry(
        val fetchedAtMs: Long,
        val snapshots: List<NwSnapshot>,
    )

    // Access-ordered LinkedHashMap = LRU; guarded by [mutex].
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private val inFlight = mutableMapOf<CacheKey, Deferred<List<NwSnapshot>>>()
    private val mutex = Mutex()

    /**
     * Cache TTL for a window of [rangeDays] days.
     *
     * - ≤1 day  → 5 min (300 000 ms)
     * - ≤90 days → 1 h (3 600 000 ms)
     * - otherwise → 6 h (21 600 000 ms)
     */
    private fun ttlMsFor(rangeDays: Int): Long = when {
        rangeDays <= 1 -> 5L * 60 * 1000
        rangeDays <= 90 -> 60L * 60 * 1000
        else -> 6L * 60 * 60 * 1000
    }

    /** Clear the in-memory cache. Used only by tests for isolation. */
    internal fun clearCacheForTest() {
        synchronized(cache) { cache.clear() }
        inFlight.clear()
    }

    /** Number of cached windows. Test-only. */
    internal fun cacheSizeForTest(): Int = cache.size

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Reconstruct the daily BTC net-worth history for the window
     * [fromUnix, toUnix] expressed in the given [currency]. The window is
     * aligned to UTC days (the backend works at day resolution anyway).
     *
     * [events] must be the caller-aggregated list of on-chain payment
     * events (watch wallets + signing wallets). Build each entry via
     * `PaymentEventLiteFfi(amountSat = ..., blockTimeUnix = ...)` where
     * `amountSat` is **signed** (positive for incoming, negative for
     * outgoing).
     *
     * Returns an empty list on any error so the UI can fall back to the
     * flat placeholder sparkline without an exception propagating to
     * Compose. Errors are logged to stderr for dev visibility.
     */
    public suspend fun netWorthHistory(
        fromUnix: Long,
        toUnix: Long,
        currency: FiatCurrency,
        events: List<PaymentEventLiteFfi> = emptyList(),
    ): List<NwSnapshot> {
        val fromDay = Math.floorDiv(fromUnix, SECS_PER_DAY)
        val toDay = Math.floorDiv(toUnix, SECS_PER_DAY)
        val key = CacheKey(currency, fromDay, toDay, events)
        val rangeDays = (toDay - fromDay).toInt().coerceAtLeast(1)
        val ttlMs = ttlMsFor(rangeDays)

        val deferred = mutex.withLock {
            val cached = cache[key]
            if (cached != null && (System.currentTimeMillis() - cached.fetchedAtMs) < ttlMs) {
                return cached.snapshots
            }
            inFlight[key] ?: AppScope.io.async {
                val snapshots = fetch(fromDay * SECS_PER_DAY, toDay * SECS_PER_DAY, currency, events)
                mutex.withLock {
                    // Store even an empty result, so a failed call is not
                    // immediately retried on the next recompose.
                    cache[key] = CacheEntry(System.currentTimeMillis(), snapshots)
                    inFlight.remove(key)
                }
                snapshots
            }.also { inFlight[key] = it }
        }
        return deferred.await()
    }

    private fun fetch(
        fromUnix: Long,
        toUnix: Long,
        currency: FiatCurrency,
        events: List<PaymentEventLiteFfi>,
    ): List<NwSnapshot> = try {
        source.netWorthHistory(
            fromUnix = fromUnix,
            toUnix = toUnix,
            currency = currency.code,
            events = events,
        ).map { ffi ->
            NwSnapshot(
                dateUnix = ffi.dateUnix,
                totalSat = ffi.totalSat,
                onchainSat = ffi.onchainSat,
                investmentsSat = ffi.investmentsSat,
                manualAssetsSat = ffi.manualAssetsSat,
                liabilitiesSat = ffi.liabilitiesSat,
                fiatEquivalent = ffi.fiatEquivalent,
                rateBtcFiat = ffi.rateBtcFiat,
            )
        }
    } catch (e: Exception) {
        // WalletException in practice; any other failure must not leave
        // the in-flight entry dangling either.
        System.err.println(
            "[NetWorthHistoryClient] FFI error: " +
                io.bhodl.desktop.ui.sanitizeErrorDetail(e.message),
        )
        emptyList()
    }
}
