package io.bhodl.desktop.prices

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.RegistryHandle
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 5.0 — process-lifetime cache of the BTC/fiat spot price.
 *
 * Reactive Compose state: any composable that reads `price.value`
 * recomposes when the cache refreshes. The cache survives Compose
 * remount (e.g. locale flip) because it lives outside the NavHost.
 *
 * Refresh policy:
 *  - On `start()` (called once from Main) we kick a background
 *    coroutine that fetches immediately, then every `refreshInterval`.
 *  - The coroutine respects `SettingsStore.fiatCurrency` — switching
 *    to NONE pauses fetching, switching to EUR/USD/CHF triggers an
 *    immediate refresh of the new currency.
 *  - `refreshNow()` lets the UI force an out-of-band fetch (e.g.
 *    when the user taps a refresh button next to the price).
 *
 * Persistence: in-memory only. The price is volatile by definition;
 * caching to disk would just delay the inevitable refresh on launch.
 */
public object PriceCache {

    /** [lastError] when no provider returned a price; the UI shows its localized text. */
    public const val NO_PRICE_ERROR: String = "No price available from any provider."

    private val _price = mutableStateOf<Double?>(null)
    /** Latest BTC price in `currentCurrency.value`, or null if not yet fetched or fiat disabled. */
    public val price: State<Double?> get() = _price

    private val _lastFetchUnix = mutableStateOf<Long?>(null)
    /** Wall-clock seconds when `price` was last successfully refreshed. */
    public val lastFetchUnix: State<Long?> get() = _lastFetchUnix

    private val _refreshing = mutableStateOf(false)
    /** True while a fetch is in flight. */
    public val refreshing: State<Boolean> get() = _refreshing

    private val _lastError = mutableStateOf<String?>(null)
    /** Non-null when the most recent fetch failed; cleared on success. */
    public val lastError: State<String?> get() = _lastError

    private val _currencyRates = mutableStateOf<Map<String, Double>>(emptyMap())
    public val currencyRates: State<Map<String, Double>> get() = _currencyRates

    /**
     * Wall-clock ms at which each [_currencyRates] entry was fetched.
     * Entries older than [CURRENCY_RATE_TTL_MS] are not served by
     * [rateFor] (the UI then shows "no rate") and are re-fetched by the
     * next refresh — previously a cross-currency rate, once cached, was
     * served for the whole session.
     */
    private val currencyRateFetchedAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Max age of a cached non-dashboard BTC/<ccy> rate (10 min). */
    public const val CURRENCY_RATE_TTL_MS: Long = 10L * 60L * 1000L

    /** Clock seam for the TTL (tests). */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    private fun isRateFresh(ccy: String): Boolean {
        val at = currencyRateFetchedAtMs[ccy] ?: return false
        return nowMs() - at < CURRENCY_RATE_TTL_MS
    }

    private fun putCurrencyRates(rates: Map<String, Double>) {
        if (rates.isEmpty()) return
        val now = nowMs()
        for (k in rates.keys) currencyRateFetchedAtMs[k] = now
        _currencyRates.value = _currencyRates.value + rates
    }

    /**
     * Refresh interval. 5 minutes by default — CoinGecko free tier
     * allows ~10 req/min so we're well under the limit even with a
     * burst of manual refreshes.
     */
    public var refreshIntervalMs: Long = 5 * 60 * 1000L

    /**
     * Max distinct tickers quoted per investment-price refresh. Caps the
     * Yahoo fan-out so a portfolio with dozens of holdings can't issue an
     * unbounded burst of HTTP requests on a single refresh.
     */
    public const val MAX_QUOTED_TICKERS: Int = 20

    private val scope = MainScope()
    private val started = AtomicBoolean(false)

    /**
     * R3 — dependency inversion for registry access.
     *
     * The investment-price refresh needs to read/write the open
     * registry, but `prices` must not reach across into the `wizard`
     * layer's `RegistryStore` singleton directly: that was an undeclared
     * cross-package coupling (the price subsystem silently depended on
     * the wizard's boot state). Instead the composition root (`Main.kt`)
     * wires these seams at startup via [wireRegistry]; the `prices`
     * package now declares only what it needs (a registry provider + a
     * data-changed signal) and stays ignorant of where it comes from.
     *
     * Defaults are inert — a no-registry provider and a no-op signal —
     * so unit tests and the pre-wiring window are safe without any
     * setup.
     */
    @Volatile
    private var registryProvider: () -> RegistryHandle? = { null }

    @Volatile
    private var onRegistryDataChanged: () -> Unit = {}

    /**
     * Wire the registry seams. Called once from the composition root
     * (`Main.kt`) at app start, after the FFI bridge is available.
     * [provider] returns the currently-open registry handle (or null
     * when the user is locked out); [onDataChanged] is invoked after a
     * successful write so observers reload.
     */
    public fun wireRegistry(
        provider: () -> RegistryHandle?,
        onDataChanged: () -> Unit,
    ) {
        registryProvider = provider
        onRegistryDataChanged = onDataChanged
    }

    /** Test-only: drop the wired seams back to their inert defaults. */
    internal fun resetRegistryWiringForTest() {
        registryProvider = { null }
        onRegistryDataChanged = {}
    }

    /**
     * Serializes concurrent calls to [refreshNow]. The background
     * loop fires every 5 minutes; the UI may also force-refresh via
     * a manual button. Without this lock, two refreshes can interleave
     * — both setting `_refreshing.value = true`, both calling
     * [refreshInvestmentPrices] (which would double the Yahoo Finance
     * quote-fetch count past the anti-bot cap), and both racing on
     * the writes to `_price`.
     */
    private val refreshMutex = Mutex()

    /**
     * Boot the background refresh loop. Idempotent — calling twice is
     * a no-op. Call once from Main.kt at app start.
     *
     * Uses [AtomicBoolean.compareAndSet] so two threads calling
     * [start] concurrently (e.g. a test harness or a future hot-reload
     * path) cannot both pass the gate and launch two refresh loops.
     */
    public fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                refreshNow()
                delay(refreshIntervalMs)
            }
        }
    }

    /** Stop the background loop. Used by tests; production rarely needs this. */
    public fun stop() {
        scope.cancel()
        started.set(false)
    }

    /**
     * Force-refresh the cached BTC/fiat price AND (when enabled, or
     * when [forceInvestmentPrices] is set) refresh the Yahoo Finance
     * quote for every investment that has a ticker. The investment
     * prices are written back to the registry's
     * `tracking_investments.fetched_current_value` so the UI picks
     * them up on the next reload.
     *
     * [forceInvestmentPrices] — when true, the investment-price refresh
     * runs even if `SettingsStore.autoFetchInvestmentPrices` is off.
     * This is the contract for a *manual* refresh affordance in the UI
     * (the net-worth hero's price chip): an explicit tap is an explicit
     * request to re-price everything, regardless of the background
     * auto-fetch preference. The 5-min background loop calls this with
     * the default (false), so it still honours the auto-fetch toggle.
     *
     * Suspends on Dispatchers.IO for the HTTP calls; updates the
     * reactive state on completion.
     */
    public suspend fun refreshNow(forceInvestmentPrices: Boolean = false) {
        // Serialize concurrent calls — without the mutex, the timer
        // loop + a user-triggered refresh can run simultaneously,
        // doubling the Yahoo Finance fetch rate past the anti-bot cap.
        refreshMutex.withLock {
            val currency = SettingsStore.fiatCurrency.value
            if (currency == FiatCurrency.NONE) {
                _price.value = null
            } else {
                _refreshing.value = true
                val result = withContext(Dispatchers.IO) {
                    runCatching { PriceFetcher.fetchBtcPrice(currency) }
                }
                _refreshing.value = false
                result.fold(
                    onSuccess = { v ->
                        if (v != null) {
                            _price.value = v
                            _lastFetchUnix.value = System.currentTimeMillis() / 1000L
                            _lastError.value = null
                        } else {
                            _lastError.value = NO_PRICE_ERROR
                        }
                    },
                    onFailure = { e ->
                        _lastError.value = e.message ?: e.javaClass.simpleName
                    },
                )
            }
            // Keep already-known cross-currency rates alive: re-fetch the
            // ones past their TTL (no new currencies are added here).
            refreshExpiredCurrencyRates()
            // Phase 5.2 — refresh investment quotes when auto-fetch is on
            // (or the caller forced it). Each ticker is one Yahoo call;
            // we cap at 20 per refresh to stay well under Yahoo's
            // anti-bot threshold. Already inside the mutex → call the
            // un-locked body (the kotlinx Mutex is NOT reentrant, so
            // calling the public wrapper here would deadlock).
            if (forceInvestmentPrices || SettingsStore.autoFetchInvestmentPrices.value) {
                refreshInvestmentPricesLocked()
            }
        }
    }

    /**
     * Public entry point for a standalone investment-price refresh
     * (e.g. the portfolio card's "stale prices" chip). Serialized via
     * the same [refreshMutex] as [refreshNow] so a manual tap can never
     * run concurrently with the background loop and double the Yahoo
     * fetch rate. Callers that already hold the mutex must use
     * [refreshInvestmentPricesLocked] instead.
     */
    public suspend fun refreshInvestmentPrices() {
        refreshMutex.withLock {
            refreshInvestmentPricesLocked()
        }
    }

    private suspend fun refreshExpiredCurrencyRates() {
        val expired = _currencyRates.value.keys.filter { !isRateFresh(it) }
        if (expired.isEmpty()) return
        val fresh = withContext(Dispatchers.IO) {
            val out = mutableMapOf<String, Double>()
            for (ccy in expired) {
                val fiat = FiatCurrency.entries.firstOrNull { it.code.equals(ccy, ignoreCase = true) }
                    ?: continue
                val r = runCatching { PriceFetcher.fetchBtcPrice(fiat) }.getOrNull()
                if (r != null && r > 0.0 && r.isFinite()) out[ccy] = r
            }
            out
        }
        putCurrencyRates(fresh)
    }

    private suspend fun refreshInvestmentPricesLocked() {
        val registry = registryProvider() ?: return
        val nowUnix = System.currentTimeMillis() / 1000L
        var anyUpdated = false
        withContext(Dispatchers.IO) {
            val investments = runCatching { registry.listInvestments() }.getOrNull() ?: return@withContext

            val currencies = investments.map { it.costBasisCurrency.uppercase().trim() }.toSet()
            val newRates = mutableMapOf<String, Double>()
            for (ccy in currencies) {
                if (isRateFresh(ccy) && _price.value != null) continue
                val fiat = FiatCurrency.entries.firstOrNull { it.code.equals(ccy, ignoreCase = true) }
                    ?: continue
                val rate = runCatching { PriceFetcher.fetchBtcPrice(fiat) }.getOrNull()
                if (rate != null && rate > 0.0) {
                    newRates[ccy] = rate
                }
            }
            putCurrencyRates(newRates)

            // Ensure a BTC/<ccy> rate is available, fetching it once on
            // demand (used to convert a quote into the investment's
            // currency below). Returns null when the currency isn't in
            // FiatCurrency or no provider answered.
            fun ensureRate(ccy: String): Double? {
                rateFor(ccy)?.let { if (it > 0.0) return it } // fresh only (TTL)
                val fiat = FiatCurrency.entries.firstOrNull { it.code.equals(ccy, ignoreCase = true) }
                    ?: return null
                val r = runCatching { PriceFetcher.fetchBtcPrice(fiat) }.getOrNull()
                if (r != null && r > 0.0) {
                    putCurrencyRates(mapOf(ccy.trim().uppercase() to r))
                    return r
                }
                return null
            }

            val candidates = investments.filter { !it.ticker.isNullOrBlank() }.take(MAX_QUOTED_TICKERS)
            for (inv in candidates) {
                val ticker = inv.ticker ?: continue
                val quote = runCatching { PriceFetcher.fetchYahooQuote(ticker) }.getOrNull() ?: continue
                // Phase 9 — the Yahoo quote is denominated in the
                // exchange's home currency (quote.currency, e.g. USD for
                // MSTR), which may differ from the investment's cost-basis
                // currency. Convert via BTC so we never persist a USD
                // figure under a EUR label. When the quote currency can't
                // be priced (not in FiatCurrency / no rate), skip the
                // write rather than store a mislabelled number.
                val target = inv.costBasisCurrency.trim().uppercase()
                val priceInTarget: Double? =
                    if (quote.currency.equals(target, ignoreCase = true)) {
                        quote.price.takeIf { it.isFinite() }
                    } else {
                        convertViaBtc(
                            price = quote.price,
                            quoteCurrency = quote.currency,
                            targetCurrency = target,
                            rateTarget = ensureRate(target),
                            rateQuote = ensureRate(quote.currency),
                        )
                    }
                if (priceInTarget != null) {
                    runCatching {
                        registry.setInvestmentFetchedPrice(inv.id, priceInTarget, nowUnix)
                        anyUpdated = true
                    }
                }
            }
        }
        if (anyUpdated) onRegistryDataChanged()
    }

    /**
     * Phase 9 — convert a quote [price] denominated in [quoteCurrency]
     * into [targetCurrency], bridging through BTC: 1 BTC = [rateTarget]
     * units of target and [rateQuote] units of quote, so
     * `price_target = price * rateTarget / rateQuote`.
     *
     * Returns [price] unchanged when the currencies match (rates
     * ignored). Returns null when a needed rate is missing / non-finite
     * / non-positive, or the result is non-finite — the caller then
     * skips the write rather than persisting a mislabelled figure.
     *
     * Pure; exposed `internal` so the FX math is unit-tested without
     * the network.
     */
    internal fun convertViaBtc(
        price: Double,
        quoteCurrency: String,
        targetCurrency: String,
        rateTarget: Double?,
        rateQuote: Double?,
    ): Double? {
        if (!price.isFinite()) return null
        if (quoteCurrency.equals(targetCurrency, ignoreCase = true)) return price
        if (rateTarget == null || rateQuote == null) return null
        if (!rateTarget.isFinite() || !rateQuote.isFinite()) return null
        if (rateTarget <= 0.0 || rateQuote <= 0.0) return null
        return (price * rateTarget / rateQuote).takeIf { it.isFinite() }
    }

    /**
     * Test-only seed for the cached BTC/fiat price. Lets unit tests
     * exercise [satToFiat] / [fiatToSat] without hitting the network.
     * Production code never calls this — [refreshNow] is the only
     * legitimate path that writes [_price].
     */
    internal fun seedForTest(price: Double?, lastFetchUnix: Long? = null) {
        _price.value = price
        _lastFetchUnix.value = lastFetchUnix
        _lastError.value = null
    }

    /**
     * Test-only seed for the per-currency BTC rate map that [rateFor]
     * consults for non-dashboard currencies. Production populates it
     * via [refreshInvestmentPrices] (one BTC/<ccy> fetch per distinct
     * cost-basis currency). Pass `emptyMap()` to clear between tests.
     */
    internal fun seedCurrencyRatesForTest(rates: Map<String, Double>) {
        currencyRateFetchedAtMs.clear()
        val now = nowMs()
        for (k in rates.keys) currencyRateFetchedAtMs[k] = now
        _currencyRates.value = rates
    }

    /** Convert a sat amount to its current fiat value, or null if no price cached. */
    public fun satToFiat(sat: ULong): Double? {
        val priceBtcInFiat = _price.value ?: return null
        return (sat.toDouble() / 100_000_000.0) * priceBtcInFiat
    }

    /**
     * Phase 8.6 — return the cached `1 BTC = N [currency]` rate for the
     * requested ISO 4217 currency code, or null when the cache cannot
     * answer.
     *
     * The dashboard currency ([SettingsStore.fiatCurrency]) is served
     * from the main spot price. Other currencies come from the per-
     * currency map populated by the investment-price refresh, and only
     * while fresh ([CURRENCY_RATE_TTL_MS]); otherwise null (the UI is
     * expected to render "no rate" / "—").
     *
     * The match is case-insensitive: callers pass either "EUR" / "USD"
     * etc., or whatever string the goal's `target_fiat_currency` carries
     * (whitespace already trimmed at save time).
     */
    public fun rateFor(currency: String): Double? {
        val ccy = currency.trim().uppercase()
        val active = SettingsStore.fiatCurrency.value
        if (active != FiatCurrency.NONE && active.code.equals(ccy, ignoreCase = true)) {
            return _price.value
        }
        // Non-dashboard rates expire after CURRENCY_RATE_TTL_MS.
        if (!isRateFresh(ccy)) return null
        return _currencyRates.value[ccy]
    }

    /** Convert a fiat amount to sat, or null if no price cached. */
    public fun fiatToSat(amount: Double): ULong? {
        val priceBtcInFiat = _price.value ?: return null
        if (priceBtcInFiat <= 0.0) return null
        val btc = amount / priceBtcInFiat
        return (btc * 100_000_000.0).toLong().coerceAtLeast(0L).toULong()
    }

    // ─── Phase 7.1 — historical BTC price for the net-worth sparkline ──

    /**
     * In-memory cache keyed by (currency, days). Each entry holds the
     * fetched series + the wall-clock ms when it was retrieved so we
     * can honour the per-range TTL without re-hitting CoinGecko on
     * every Tracker open.
     *
     * Not persisted to disk: the series gets stale anyway (hourly /
     * daily granularity), and the BHODL data dir is intentionally lean.
     * Phase 7.5 may revisit if rate-limit pressure shows up.
     */
    private data class HistoryCacheEntry(
        val fetchedAtMs: Long,
        val series: List<HistoricalPricePoint>,
    )

    private val historyCache: MutableMap<Pair<FiatCurrency, Int>, HistoryCacheEntry> = mutableMapOf()
    private val historyMutex = Mutex()

    /**
     * Per-range TTL. CoinGecko's market_chart granularity (5-min for
     * 1d, hourly for 2-90d, daily for >90d) drives the freshness
     * window we should honour — there's no value in re-fetching daily
     * data more often than every few hours.
     */
    private fun ttlMsFor(days: Int): Long = when {
        days <= 1 -> 5 * 60 * 1000L                    // 5 min for 1-day window
        days <= 90 -> 60 * 60 * 1000L                  // 1 hour for hourly granularity
        else -> 6 * 60 * 60 * 1000L                    // 6 hours for daily granularity
    }

    /**
     * Return the BTC price history for the given window, fetching on
     * cache miss / TTL expiry. Suspends — caller must be in a
     * coroutine. Returns null when [currency] is NONE or the network
     * fetch fails.
     */
    public suspend fun priceHistory(
        currency: FiatCurrency,
        days: Int,
    ): List<HistoricalPricePoint>? {
        if (currency == FiatCurrency.NONE || days <= 0) return null
        val key = currency to days
        val nowMs = System.currentTimeMillis()
        // Cheap optimistic read outside the mutex — if the entry is
        // fresh, no need to take the lock at all.
        historyCache[key]?.let {
            if (nowMs - it.fetchedAtMs < ttlMsFor(days)) return it.series
        }
        return historyMutex.withLock {
            historyCache[key]?.let {
                if (nowMs - it.fetchedAtMs < ttlMsFor(days)) return@withLock it.series
            }
            val series = withContext(Dispatchers.IO) {
                runCatching { PriceFetcher.fetchBtcPriceHistory(currency, days) }
                    .getOrNull()
            }
            if (series != null) {
                historyCache[key] = HistoryCacheEntry(fetchedAtMs = nowMs, series = series)
            }
            series
        }
    }

    /** Test-only: clear the history cache so tests get deterministic results. */
    internal fun clearHistoryCacheForTest() {
        historyCache.clear()
    }

    /** Test-only: pre-load a fresh history series so [priceHistory]
     *  answers without the network. */
    internal fun seedHistoryForTest(currency: FiatCurrency, days: Int, series: List<HistoricalPricePoint>) {
        historyCache[currency to days] = HistoryCacheEntry(System.currentTimeMillis(), series)
    }
}
