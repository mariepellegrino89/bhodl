package io.bhodl.desktop.prices

import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.RegistryHandle
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Area D — [PriceCache] orchestration with fake price / quote sources and
 * a real registry: spot refresh errors, investment price loop, ticker cap,
 * currency-rate TTL, price-history cache, session clear.
 */
class UcPortfolioPriceCacheTest {

    private lateinit var tempDir: File
    private lateinit var registry: RegistryHandle
    private val spotCalls = mutableListOf<FiatCurrency>()
    private val quoteCalls = mutableListOf<String>()
    private val dataChanged = AtomicInteger()
    private var now = 10_000_000L

    private val spot = mapOf(FiatCurrency.EUR to 50_000.0, FiatCurrency.USD to 40_000.0, FiatCurrency.CHF to 45_000.0)
    private var quotes: Map<String, YahooQuote> = emptyMap()

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        PriceCache.seedForTest(null)
        PriceCache.seedCurrencyRatesForTest(emptyMap())
        PriceCache.clearHistoryCacheForTest()
        PriceCache.nowMs = { now }
        PriceCache.btcPriceSource = { c -> synchronized(spotCalls) { spotCalls += c }; spot[c] }
        PriceCache.quoteSource = { t -> synchronized(quoteCalls) { quoteCalls += t }; quotes[t] }
        tempDir = kotlin.io.path.createTempDirectory("bhodl-uc-prices-").toFile()
        registry = RegistryHandle.createOrLoad(tempDir.absolutePath, "482916")
        PriceCache.wireRegistry({ registry }, { dataChanged.incrementAndGet() })
    }

    @AfterTest
    fun teardown() {
        PriceCache.resetRegistryWiringForTest()
        PriceCache.resetSourcesForTest()
        PriceCache.seedForTest(null)
        PriceCache.seedCurrencyRatesForTest(emptyMap())
        PriceCache.clearHistoryCacheForTest()
        PriceFetcher.httpGetOverride = null
        SettingsStore.reset()
        registry.destroy()
        tempDir.deleteRecursively()
    }

    private fun addInv(ticker: String?, ccy: String = "EUR", manual: Double? = null): Long = registry.addInvestment(
        name = ticker ?: "manual", assetType = "stock", ticker = ticker, quantity = 1.0, unit = "u",
        costBasisFiat = 10.0, costBasisCurrency = ccy, purchaseUnix = 1_700_000_000L,
        manualCurrentValue = manual, notes = null,
    )

    private fun inv(id: Long) = registry.listInvestments().single { it.id == id }

    // ─── PRT-083 spot refresh ─────────────────────────────────────

    @Test
    fun `PRT-083 a successful refresh stores price and fetch time and clears the error`() = runBlocking {
        PriceCache.btcPriceSource = { null }
        PriceCache.refreshNow()
        assertEquals(PriceCache.NO_PRICE_ERROR, PriceCache.lastError.value)
        PriceCache.btcPriceSource = { 61_000.0 }
        val before = System.currentTimeMillis() / 1000L
        PriceCache.refreshNow()
        assertEquals(61_000.0, PriceCache.price.value)
        assertTrue(PriceCache.lastFetchUnix.value!! >= before)
        assertNull(PriceCache.lastError.value)
    }

    @Test
    fun `PRT-083 no price from any provider keeps the previous price and flags the error`() = runBlocking {
        PriceCache.seedForTest(55_000.0, lastFetchUnix = 123L)
        PriceCache.btcPriceSource = { null }
        PriceCache.refreshNow()
        assertEquals(55_000.0, PriceCache.price.value)
        assertEquals(123L, PriceCache.lastFetchUnix.value)
        assertEquals(PriceCache.NO_PRICE_ERROR, PriceCache.lastError.value)
    }

    @Test
    fun `PRT-083 currency NONE clears the price and asks no provider`() = runBlocking {
        PriceCache.seedForTest(55_000.0)
        SettingsStore.setFiatCurrency(FiatCurrency.NONE)
        PriceCache.refreshNow()
        assertNull(PriceCache.price.value)
        assertTrue(spotCalls.isEmpty())
    }

    @Test
    fun `GAP-012 a thrown fetch error never reaches the UI raw`() = runBlocking {
        PriceCache.seedForTest(55_000.0)
        PriceCache.btcPriceSource = {
            throw java.io.IOException("connect to https://api.coingecko.com/x failed at C:\\Users\\mario\\x")
        }
        PriceCache.refreshNow()
        val err = PriceCache.lastError.value
        assertEquals(PriceCache.NO_PRICE_ERROR, err)
        assertFalse(err!!.contains("coingecko") || err.contains("mario"))
        assertEquals(55_000.0, PriceCache.price.value)
    }

    // ─── PRT-020 / PRT-021 auto-fetch and forced refresh ──────────

    @Test
    fun `PRT-020 auto-fetch off asks no quote`() = runBlocking {
        addInv("AAPL")
        quotes = mapOf("AAPL" to YahooQuote("AAPL", 180.0, "EUR"))
        PriceCache.refreshNow()
        assertTrue(quoteCalls.isEmpty())
        assertEquals(0, dataChanged.get())
    }

    @Test
    fun `PRT-020 auto-fetch on quotes and writes the fetched price`() = runBlocking {
        val id = addInv("AAPL", manual = 150.0)
        quotes = mapOf("AAPL" to YahooQuote("AAPL", 180.0, "EUR"))
        SettingsStore.setAutoFetchInvestmentPrices(true)
        PriceCache.refreshNow()
        assertEquals(listOf("AAPL"), quoteCalls)
        assertEquals(180.0, inv(id).fetchedCurrentValue)
        assertEquals(150.0, inv(id).manualCurrentValue, "PRT-016: manual value untouched")
        assertNotNull(inv(id).lastPricedAtUnix)
        assertEquals(1, dataChanged.get())
    }

    @Test
    fun `PRT-021 a forced refresh quotes even with auto-fetch off`() = runBlocking {
        val id = addInv("AAPL")
        quotes = mapOf("AAPL" to YahooQuote("AAPL", 180.0, "EUR"))
        PriceCache.refreshNow(forceInvestmentPrices = true)
        assertEquals(180.0, inv(id).fetchedCurrentValue)
    }

    @Test
    fun `PRT-021 the standalone investment refresh quotes without a spot fetch`() = runBlocking {
        val id = addInv("AAPL")
        quotes = mapOf("AAPL" to YahooQuote("AAPL", 180.0, "EUR"))
        PriceCache.refreshInvestmentPrices()
        assertEquals(180.0, inv(id).fetchedCurrentValue)
    }

    // ─── PRT-016 orchestration details ────────────────────────────

    @Test
    fun `PRT-016 a quote in another currency is converted through BTC`() = runBlocking {
        val id = addInv("MSTR", ccy = "EUR")
        quotes = mapOf("MSTR" to YahooQuote("MSTR", 400.0, "USD"))
        PriceCache.refreshInvestmentPrices()
        // 400 USD × 50 000 / 40 000 = 500 EUR
        assertEquals(500.0, inv(id).fetchedCurrentValue!!, 1e-9)
        assertEquals(40_000.0, PriceCache.rateFor("USD"))
    }

    @Test
    fun `PRT-016 an unpriceable quote currency skips the write`() = runBlocking {
        val id = addInv("7203.T", ccy = "EUR")
        quotes = mapOf("7203.T" to YahooQuote("7203.T", 2_500.0, "JPY"))
        PriceCache.refreshInvestmentPrices()
        assertNull(inv(id).fetchedCurrentValue)
        assertEquals(0, dataChanged.get())
    }

    @Test
    fun `PRT-016 missing quotes and manual positions are skipped, others still written`() = runBlocking {
        val missing = addInv("NOPE")
        val manual = addInv(null, manual = 5.0)
        val ok = addInv("VWCE.DE")
        quotes = mapOf("VWCE.DE" to YahooQuote("VWCE.DE", 120.0, "EUR"))
        PriceCache.refreshInvestmentPrices()
        assertEquals(setOf("NOPE", "VWCE.DE"), quoteCalls.toSet())
        assertNull(inv(missing).fetchedCurrentValue)
        assertNull(inv(manual).fetchedCurrentValue)
        assertEquals(120.0, inv(ok).fetchedCurrentValue)
    }

    @Test
    fun `PRT-016 no open registry means no quote at all`() = runBlocking {
        PriceCache.resetRegistryWiringForTest()
        PriceCache.refreshInvestmentPrices()
        assertTrue(quoteCalls.isEmpty())
    }

    // ─── PRT-022 ticker cap ───────────────────────────────────────

    @Test
    fun `PRT-022 at most MAX_QUOTED_TICKERS quotes per pass`() = runBlocking {
        repeat(PriceCache.MAX_QUOTED_TICKERS + 5) { addInv("T$it") }
        PriceCache.refreshInvestmentPrices()
        assertEquals(PriceCache.MAX_QUOTED_TICKERS, quoteCalls.size)
        assertEquals(PriceCache.MAX_QUOTED_TICKERS, quoteCalls.toSet().size)
    }

    // ─── PRT-084 currency rate TTL ────────────────────────────────

    @Test
    fun `PRT-084 rateFor serves fresh cross-currency rates and the dashboard spot`() {
        PriceCache.seedForTest(50_000.0)
        PriceCache.seedCurrencyRatesForTest(mapOf("USD" to 40_000.0))
        assertEquals(50_000.0, PriceCache.rateFor(" eur "))
        assertEquals(40_000.0, PriceCache.rateFor("usd"))
        assertNull(PriceCache.rateFor("GBP"))
        now += PriceCache.CURRENCY_RATE_TTL_MS - 1
        assertEquals(40_000.0, PriceCache.rateFor("USD"))
        now += 1
        assertNull(PriceCache.rateFor("USD"), "expired")
        assertEquals(50_000.0, PriceCache.rateFor("EUR"), "dashboard spot has no TTL")
    }

    @Test
    fun `PRT-084 refresh re-fetches only the expired rates`() = runBlocking {
        PriceCache.seedCurrencyRatesForTest(mapOf("USD" to 1.0))
        PriceCache.refreshNow()
        assertEquals(listOf(FiatCurrency.EUR), spotCalls, "USD still fresh")
        spotCalls.clear()
        now += PriceCache.CURRENCY_RATE_TTL_MS
        PriceCache.refreshNow()
        assertEquals(listOf(FiatCurrency.EUR, FiatCurrency.USD), spotCalls)
        assertEquals(40_000.0, PriceCache.rateFor("USD"))
    }

    // ─── PRT-085 price history cache ──────────────────────────────

    private val chartBody = """{"prices":[[1700000000000,50000.0],[1700003600000,50500.5]]}"""

    @Test
    fun `PRT-085 price history is fetched once per TTL and refetched after`() = runBlocking {
        val urls = mutableListOf<String>()
        PriceFetcher.httpGetOverride = { url -> urls += url; chartBody }
        val first = PriceCache.priceHistory(FiatCurrency.EUR, 30)!!
        assertEquals(listOf(50_000.0, 50_500.5), first.map { it.price })
        PriceCache.priceHistory(FiatCurrency.EUR, 30)
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("vs_currency=eur") && urls.single().contains("days=30"))
        now += 60L * 60 * 1000
        PriceCache.priceHistory(FiatCurrency.EUR, 30)
        assertEquals(2, urls.size)
        PriceCache.priceHistory(FiatCurrency.USD, 30)
        assertEquals(3, urls.size, "currency is part of the key")
    }

    @Test
    fun `PRT-085 NONE, non-positive days and failures return null without caching`() = runBlocking {
        var calls = 0
        PriceFetcher.httpGetOverride = { calls++; throw java.io.IOException("down") }
        assertNull(PriceCache.priceHistory(FiatCurrency.NONE, 7))
        assertNull(PriceCache.priceHistory(FiatCurrency.EUR, 0))
        assertEquals(0, calls)
        assertNull(PriceCache.priceHistory(FiatCurrency.EUR, 7))
        assertNull(PriceCache.priceHistory(FiatCurrency.EUR, 7))
        assertEquals(2, calls, "a failure is not cached")
    }

    @Test
    fun `PRT-085 the 1-day window expires after 5 minutes, long windows after 6 hours`() = runBlocking {
        var calls = 0
        PriceFetcher.httpGetOverride = { calls++; chartBody }
        PriceCache.priceHistory(FiatCurrency.EUR, 1)
        PriceCache.priceHistory(FiatCurrency.EUR, 365)
        now += 5L * 60 * 1000
        PriceCache.priceHistory(FiatCurrency.EUR, 1)
        PriceCache.priceHistory(FiatCurrency.EUR, 365)
        assertEquals(3, calls)
        now += 6L * 60 * 60 * 1000
        PriceCache.priceHistory(FiatCurrency.EUR, 365)
        assertEquals(4, calls)
    }

    // ─── GAP-015 session caches cleared on lock ───────────────────

    @Test
    fun `GAP-015 clearSessionCaches drops history and cross-currency rates`() = runBlocking {
        var calls = 0
        PriceFetcher.httpGetOverride = { calls++; chartBody }
        PriceCache.seedForTest(50_000.0)
        PriceCache.seedCurrencyRatesForTest(mapOf("USD" to 40_000.0))
        PriceCache.priceHistory(FiatCurrency.EUR, 30)
        PriceCache.clearSessionCaches()
        assertNull(PriceCache.rateFor("USD"))
        assertTrue(PriceCache.currencyRates.value.isEmpty())
        assertEquals(50_000.0, PriceCache.price.value)
        PriceCache.priceHistory(FiatCurrency.EUR, 30)
        assertEquals(2, calls)
    }
}
