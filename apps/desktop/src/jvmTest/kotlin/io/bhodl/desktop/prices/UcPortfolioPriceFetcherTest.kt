package io.bhodl.desktop.prices

import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.NwSnapshotFfi
import io.bhodl.ffi.PaymentEventLiteFfi
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Area D — spot price provider chain and plausibility filter (offline, via
 * the HTTP seam) and the net-worth history cache invalidation.
 */
class UcPortfolioPriceFetcherTest {

    private val urls = mutableListOf<String>()
    private val coinGecko = "api.coingecko.com"
    private val mempool = "mempool.space"

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        NetWorthHistoryClient.clearCacheForTest()
    }

    @AfterTest
    fun teardown() {
        PriceFetcher.httpGetOverride = null
        NetWorthHistoryClient.resetSourceForTest()
        NetWorthHistoryClient.clearCacheForTest()
        SettingsStore.reset()
    }

    private fun serve(cg: () -> String, mp: () -> String) {
        PriceFetcher.httpGetOverride = { url ->
            urls += url
            when {
                coinGecko in url -> cg()
                mempool in url -> mp()
                else -> error("unexpected $url")
            }
        }
    }

    // ─── PRT-078 provider chain ───────────────────────────────────

    @Test
    fun `PRT-078 CoinGecko answers and mempool is not asked`() {
        serve({ """{"bitcoin":{"eur":61000.5}}""" }, { error("not expected") })
        assertEquals(61_000.5, PriceFetcher.fetchBtcPrice(FiatCurrency.EUR))
        assertEquals(1, urls.size)
        assertEquals(true, urls.single().contains("vs_currencies=eur"))
    }

    @Test
    fun `PRT-078 a CoinGecko failure falls back to mempool space`() {
        serve({ throw java.io.IOException("HTTP 500") }, { """{"time":1,"USD":65000,"EUR":60000,"CHF":58000}""" })
        assertEquals(58_000.0, PriceFetcher.fetchBtcPrice(FiatCurrency.CHF))
        assertEquals(2, urls.size)
    }

    @Test
    fun `PRT-078 a CoinGecko body without the currency falls back too`() {
        serve({ """{"bitcoin":{"usd":65000}}""" }, { """{"EUR":60000}""" })
        assertEquals(60_000.0, PriceFetcher.fetchBtcPrice(FiatCurrency.EUR))
    }

    @Test
    fun `PRT-078 both providers down gives null, NONE asks nobody`() {
        serve({ throw java.io.IOException("down") }, { "not json" })
        assertNull(PriceFetcher.fetchBtcPrice(FiatCurrency.USD))
        urls.clear()
        assertNull(PriceFetcher.fetchBtcPrice(FiatCurrency.NONE))
        assertEquals(0, urls.size)
    }

    // ─── PRT-079 plausibility ─────────────────────────────────────

    @Test
    fun `PRT-079 saneSpotPrice keeps the window and rejects the rest`() {
        assertEquals(PriceFetcher.MIN_SANE_SPOT, PriceFetcher.saneSpotPrice(PriceFetcher.MIN_SANE_SPOT))
        assertEquals(PriceFetcher.MAX_SANE_SPOT, PriceFetcher.saneSpotPrice(PriceFetcher.MAX_SANE_SPOT))
        assertEquals(60_000.0, PriceFetcher.saneSpotPrice(60_000.0))
        for (bad in listOf(null, 99.99, 1.0e9 + 1, 0.0, -5.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(PriceFetcher.saneSpotPrice(bad), "$bad")
        }
    }

    @Test
    fun `PRT-079 an implausible CoinGecko price triggers the fallback, two implausible give null`() {
        serve({ """{"bitcoin":{"eur":0.5}}""" }, { """{"EUR":59000}""" })
        assertEquals(59_000.0, PriceFetcher.fetchBtcPrice(FiatCurrency.EUR))
        serve({ """{"bitcoin":{"eur":5.0e12}}""" }, { """{"EUR":-1}""" })
        assertNull(PriceFetcher.fetchBtcPrice(FiatCurrency.EUR))
    }

    // ─── PRT-085 market chart fetch ───────────────────────────────

    @Test
    fun `PRT-085 market chart parses points and returns null on failure`() {
        serve({ """{"prices":[[1,2.5]]}""" }, { error("no") })
        assertEquals(listOf(HistoricalPricePoint(1, 2.5)), PriceFetcher.fetchBtcPriceHistory(FiatCurrency.USD, 7))
        assertNull(PriceFetcher.fetchBtcPriceHistory(FiatCurrency.USD, 0))
        serve({ throw java.io.IOException("x") }, { error("no") })
        assertNull(PriceFetcher.fetchBtcPriceHistory(FiatCurrency.USD, 7))
    }

    // ─── GAP-015 / GAP-016 net-worth history cache ────────────────

    private var nwCalls = 0
    private fun fakeNwSource() = object : NetWorthHistoryClient.NwHistorySource {
        override fun netWorthHistory(
            fromUnix: Long, toUnix: Long, currency: String, events: List<PaymentEventLiteFfi>,
        ): List<NwSnapshotFfi> {
            nwCalls++
            return listOf(NwSnapshotFfi(fromUnix, nwCalls.toLong(), nwCalls.toLong(), 0, 0, 0, 0.0, 0.0))
        }
    }

    @Test
    fun `GAP-016 a new data version refetches the same window`() = runBlocking {
        NetWorthHistoryClient.setSourceForTest(fakeNwSource())
        val from = 1_700_000_000L
        val a = NetWorthHistoryClient.netWorthHistory(from, from + 30 * 86_400L, FiatCurrency.EUR, emptyList(), dataVersion = 1)
        NetWorthHistoryClient.netWorthHistory(from, from + 30 * 86_400L, FiatCurrency.EUR, emptyList(), dataVersion = 1)
        assertEquals(1, nwCalls)
        val b = NetWorthHistoryClient.netWorthHistory(from, from + 30 * 86_400L, FiatCurrency.EUR, emptyList(), dataVersion = 2)
        assertEquals(2, nwCalls)
        assertEquals(1L, a.single().totalSat)
        assertEquals(2L, b.single().totalSat)
    }

    @Test
    fun `GAP-015 clearCache forgets every window`() = runBlocking {
        NetWorthHistoryClient.setSourceForTest(fakeNwSource())
        val from = 1_700_000_000L
        NetWorthHistoryClient.netWorthHistory(from, from + 86_400L, FiatCurrency.EUR, emptyList(), dataVersion = 0)
        assertEquals(1, NetWorthHistoryClient.cacheSizeForTest())
        NetWorthHistoryClient.clearCache()
        assertEquals(0, NetWorthHistoryClient.cacheSizeForTest())
        NetWorthHistoryClient.netWorthHistory(from, from + 86_400L, FiatCurrency.EUR, emptyList(), dataVersion = 0)
        assertEquals(2, nwCalls)
    }
}
