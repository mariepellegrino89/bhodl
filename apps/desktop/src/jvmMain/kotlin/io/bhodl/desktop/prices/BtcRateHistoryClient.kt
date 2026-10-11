package io.bhodl.desktop.prices

import io.bhodl.ffi.BackfillReportFfi
import io.bhodl.ffi.RateBackfillRequestFfi
import io.bhodl.ffi.RatePointFfi
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 8.3.1 + 8.6 — Kotlin wrapper over the Rust BTC-rate history FFI.
 *
 * Behind the singleton sits an [RateSource] seam so unit tests can
 * swap in a fake without touching the FFI / disk. Production calls
 * `setSource(FfiRateSource)` once at app bootstrap (or relies on the
 * default lazy install). All methods suspend on [Dispatchers.IO] —
 * the FFI is blocking, the suspension keeps Compose-thread call sites
 * from blocking the UI.
 *
 * Error mapping (Phase 8.6 hardening — replaces the Phase 8.3.1
 * substring inspection of `WalletException.Storage.message`):
 *  - [WalletException.RateCurrencyNotSupported] → typed catch, throws
 *    [IllegalArgumentException] with the unsupported currency code in
 *    the message (programmer error: currency hardcoded at a call site).
 *  - [WalletException.RateNetworkUnavailable] → typed catch, returns
 *    null on rateAt/current; on backfill the failure is folded into
 *    the report's `failed` list by the backend, so a thrown variant
 *    here means an exceptional condition the caller should not swallow.
 *  - [WalletException.RateAllSourcesFailed] → typed catch, returns null
 *    on rateAt/range/current (graceful degrade — UI substitutes "no data").
 *  - [WalletException.Storage] → fallback for unrelated storage errors
 *    (e.g. SQLCipher I/O); logged to stderr, returns null/empty so the
 *    UI keeps rendering. Phase 8.3.1's substring matching lived here
 *    too; the typed catches above now take precedence so this branch
 *    only sees real storage faults.
 *  - Any other [WalletException] → logged + null (rateAt/current) or
 *    rethrown unchanged for [backfill] (programmer needs to see them).
 */
public object BtcRateHistoryClient {

    /** Source seam for tests. Default delegates to the real FFI. */
    public interface RateSource {
        @Throws(WalletException::class)
        public fun rateAt(dateUnix: Long, currency: String): Double?

        @Throws(WalletException::class)
        public fun range(fromUnix: Long, toUnix: Long, currency: String): List<RatePointFfi>

        @Throws(WalletException::class)
        public fun backfill(requests: List<RateBackfillRequestFfi>): BackfillReportFfi
    }

    /**
     * Default source: the open [RegistryHandle] (audit 2026-10 — no device
     * PIN, no per-call Argon2) with the Tor proxy when Tor is on, so the
     * rate providers are never contacted over clearnet in Tor mode.
     */
    public object FfiRateSource : RateSource {
        private fun registry(): RegistryHandle =
            RegistryStore.current() ?: throw WalletException.Storage("registry not open")

        @Throws(WalletException::class)
        override fun rateAt(dateUnix: Long, currency: String): Double? =
            registry().btcRateAt(dateUnix, currency, EsploraEndpoint.currentProxy())

        @Throws(WalletException::class)
        override fun range(fromUnix: Long, toUnix: Long, currency: String): List<RatePointFfi> =
            registry().btcRateRange(fromUnix, toUnix, currency, EsploraEndpoint.currentProxy())

        @Throws(WalletException::class)
        override fun backfill(requests: List<RateBackfillRequestFfi>): BackfillReportFfi =
            registry().btcRateBackfill(requests, EsploraEndpoint.currentProxy())
    }

    @Volatile
    private var source: RateSource = FfiRateSource

    /** Inject a custom source. Used by tests; production code should
     *  rely on the default [FfiRateSource]. */
    public fun setSourceForTest(s: RateSource) {
        source = s
    }

    /** Reset to the default FFI-backed source. */
    public fun resetSourceForTest() {
        source = FfiRateSource
    }

    /**
     * BTC/{currency} rate at the given UTC midnight-normalised date.
     * Returns null on network failure or pre-genesis / future date.
     * Throws [IllegalArgumentException] when the currency is unknown
     * (programmer error — currency hardcoded in a call site).
     */
    public suspend fun rateAt(
        dateUnix: Long,
        currency: String,
    ): Double? = withContext(Dispatchers.IO) {
        try {
            source.rateAt(dateUnix, currency)
        } catch (e: WalletException.RateCurrencyNotSupported) {
            throw IllegalArgumentException(
                "BTC rate FFI: unsupported currency: ${e.currency}",
            )
        } catch (e: WalletException.RateNetworkUnavailable) {
            // Soft-fail per Phase 8.3.1 design — UI shows "no data".
            null
        } catch (e: WalletException.RateAllSourcesFailed) {
            // Also soft-fail: every configured source has refused. The
            // user already knows the network is degraded; an exception
            // here would just crash the dashboard.
            null
        } catch (e: WalletException) {
            handleRateException(e)
            null
        }
    }

    /**
     * Daily BTC/{currency} series for [fromUnix..toUnix]. Empty list
     * on network failure (we want UI to degrade gracefully into "no
     * data" rather than crash). Throws [IllegalArgumentException] on
     * unknown currency.
     */
    public suspend fun range(
        fromUnix: Long,
        toUnix: Long,
        currency: String,
    ): List<RatePointFfi> = withContext(Dispatchers.IO) {
        try {
            source.range(fromUnix, toUnix, currency)
        } catch (e: WalletException.RateCurrencyNotSupported) {
            throw IllegalArgumentException(
                "BTC rate FFI: unsupported currency: ${e.currency}",
            )
        } catch (e: WalletException.RateNetworkUnavailable) {
            emptyList<RatePointFfi>()
        } catch (e: WalletException.RateAllSourcesFailed) {
            emptyList<RatePointFfi>()
        } catch (e: WalletException) {
            handleRateException(e)
            emptyList<RatePointFfi>()
        }
    }

    /**
     * Live BTC/{currency} rate. Phase 8.3.1 stub: returns
     * `PriceCache.price.value` when the active currency matches and a
     * value has been cached; null otherwise. Phase 8.3.2+ may wire
     * the FFI `btc_rate_current` once Rust has a non-stub
     * implementation.
     */
    public suspend fun current(currency: String): Double? = withContext(Dispatchers.IO) {
        val active = io.bhodl.desktop.settings.SettingsStore.fiatCurrency.value
        if (active.name.equals(currency, ignoreCase = true)) {
            PriceCache.price.value
        } else {
            null
        }
    }

    /**
     * Batch backfill. The backend treats already-cached pairs as
     * `cacheHits`. The full report is returned for caller inspection.
     * Throws [IllegalArgumentException] only on unknown currency
     * (matches single-call semantics).
     */
    public suspend fun backfill(
        requests: List<RateBackfillRequestFfi>,
    ): BackfillReportFfi = withContext(Dispatchers.IO) {
        try {
            source.backfill(requests)
        } catch (e: WalletException.RateCurrencyNotSupported) {
            throw IllegalArgumentException(
                "BTC rate FFI: unsupported currency: ${e.currency}",
            )
        } catch (e: WalletException) {
            handleRateException(e)
            // We never reach here on plain network failure: the FFI
            // surfaces the failure through `report.failed`, not a
            // throw. If we got here it's a real exception that must
            // not be swallowed — rethrow.
            throw e
        }
    }

    /**
     * Fallback for [WalletException] variants that the typed rate-error
     * catches above didn't claim. Phase 8.6 replaced the previous
     * substring inspection of `Storage.message` (which matched
     * "currency not supported" / "network unavailable") with typed
     * sealed-class catches at every call site; what reaches this method
     * is an unrelated storage / I/O / programmer-error fault. Side
     * effect: a stderr log so the failure is loud in dev. The caller
     * substitutes `null` / `emptyList()`. Kept as a method (rather
     * than inlined) so a future logging upgrade has one place to land.
     *
     * For Phase 8.3.1 backward compatibility, the [WalletException.Storage]
     * substring fallback (rate errors that were previously routed
     * through Storage) is still recognised here so any straggler from
     * a not-yet-migrated FFI path produces the same UX (throw for
     * currency-not-supported, soft-fail for network-unavailable).
     * Once the Rust side is fully on typed errors this branch can be
     * dropped.
     */
    private fun handleRateException(e: WalletException) {
        val detail = e.message?.lowercase() ?: ""
        when {
            detail.contains("currency not supported") ->
                throw IllegalArgumentException(
                    "BTC rate FFI: unsupported currency. " +
                        "Details: ${e.message}",
                )
            detail.contains("network unavailable") -> {
                // Benign — caller substitutes null / empty.
            }
            else -> {
                // Programmer-visible degrade: log to stderr so the
                // failure is loud in dev, but still let the call
                // produce a graceful null. CrashLytics-equivalent
                // would wire here; for now stderr is enough.
                System.err.println(
                    "[BtcRateHistoryClient] unexpected: " +
                        io.bhodl.desktop.ui.sanitizeErrorDetail(e.message),
                )
            }
        }
    }
}
