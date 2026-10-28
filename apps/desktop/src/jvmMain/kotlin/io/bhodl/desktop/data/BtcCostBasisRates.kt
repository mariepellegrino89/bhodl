package io.bhodl.desktop.data

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.bhodl.desktop.prices.BtcRateHistoryClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Daily BTC market rates for the BTC average-cost view: the default
 * price of each transaction is the rate of its UTC day.
 *
 * Rates come from the registry's `btc_rate_history` cache through
 * [BtcRateHistoryClient] (network only on a cache miss, over Tor when
 * Tor is on). Each (day, currency) is asked for at most once per
 * session — a miss is not retried until [clear] (lock / wipe) — so a
 * provider that is down can't turn every recomposition into a fetch.
 */
internal object BtcCostBasisRates {

    private val _rates = mutableStateOf<Map<Pair<Long, String>, Double>>(emptyMap())

    /** Known rates keyed by (UTC day, currency). */
    val rates: State<Map<Pair<Long, String>, Double>> get() = _rates

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> get() = _loading

    private val attempted = mutableSetOf<Pair<Long, String>>()
    private val mutex = Mutex()

    /** Per-lookup timeout, so a hung provider / Tor circuit can't stall the pass. */
    internal var lookupTimeoutMs: Long = 15_000L

    /** Test seam (production: the rate history FFI). */
    internal var lookup: suspend (dayUnix: Long, currency: String) -> Double? =
        { day, ccy -> BtcRateHistoryClient.rateAt(day, ccy) }

    /** Publish after this many lookups so a long backfill fills in progressively. */
    private const val PUBLISH_EVERY = 10

    /** Fetch the rates of [days] in [currency] not looked up yet. */
    suspend fun ensure(days: Set<Long>, currency: String) {
        if (currency.isBlank()) return
        mutex.withLock {
            val todo = days.sorted().map { it to currency }.filter { it !in attempted }
            if (todo.isEmpty()) return
            _loading.value = true
            try {
                val found = HashMap<Pair<Long, String>, Double>()
                todo.forEachIndexed { i, key ->
                    attempted += key
                    val rate = runCatching {
                        withTimeoutOrNull(lookupTimeoutMs) { lookup(key.first, key.second) }
                    }.getOrNull()
                    if (rate != null && rate > 0.0) found[key] = rate
                    if ((i + 1) % PUBLISH_EVERY == 0 && found.isNotEmpty()) {
                        _rates.value = _rates.value + found
                        found.clear()
                    }
                }
                if (found.isNotEmpty()) _rates.value = _rates.value + found
            } finally {
                _loading.value = false
            }
        }
    }

    /** Forget everything (lock / wipe / tests). */
    fun clear() {
        attempted.clear()
        _rates.value = emptyMap()
        _loading.value = false
    }
}
