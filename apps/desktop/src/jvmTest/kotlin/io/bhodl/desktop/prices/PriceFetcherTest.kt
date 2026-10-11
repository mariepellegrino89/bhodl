package io.bhodl.desktop.prices

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Phase 5.0 / 5.2 — wire-format and proxy tests for [PriceFetcher].
 *
 * No network: each test feeds a canned response body or proxy URL
 * to the internal pure helpers and asserts on the parsed result.
 * This keeps CI offline-friendly while still catching JSON-shape
 * drift in the upstream providers (CoinGecko, mempool.space, Yahoo
 * Finance).
 */
class PriceFetcherTest {

    // ---- CoinGecko ----------------------------------------------------

    @Test
    fun `parseCoinGeckoBody extracts EUR price`() {
        val body = """{"bitcoin":{"eur":58200.42,"usd":62500.10}}"""
        val price = PriceFetcher.parseCoinGeckoBody(body, FiatCurrency.EUR)
        assertEquals(58200.42, price)
    }

    @Test
    fun `parseCoinGeckoBody extracts USD price`() {
        val body = """{"bitcoin":{"eur":58200.42,"usd":62500.10}}"""
        val price = PriceFetcher.parseCoinGeckoBody(body, FiatCurrency.USD)
        assertEquals(62500.10, price)
    }

    @Test
    fun `parseCoinGeckoBody extracts CHF price`() {
        val body = """{"bitcoin":{"chf":55400.0}}"""
        val price = PriceFetcher.parseCoinGeckoBody(body, FiatCurrency.CHF)
        assertEquals(55400.0, price)
    }

    @Test
    fun `parseCoinGeckoBody returns null for NONE currency`() {
        val body = """{"bitcoin":{"eur":58200.42}}"""
        assertNull(PriceFetcher.parseCoinGeckoBody(body, FiatCurrency.NONE))
    }

    @Test
    fun `parseCoinGeckoBody returns null when the requested currency field is absent`() {
        val body = """{"bitcoin":{"eur":58200.42}}"""
        // We asked for USD but the response only carries EUR.
        assertNull(PriceFetcher.parseCoinGeckoBody(body, FiatCurrency.USD))
    }

    @Test
    fun `parseCoinGeckoBody ignores extra unknown fields`() {
        val body = """{"bitcoin":{"eur":58200.42,"gbp":50000.0,"jpy":9000000.0}}"""
        val price = PriceFetcher.parseCoinGeckoBody(body, FiatCurrency.EUR)
        assertEquals(58200.42, price)
    }

    // ---- mempool.space ------------------------------------------------

    @Test
    fun `parseMempoolBody extracts EUR USD CHF from the prices payload`() {
        val body = """
            {"time":1734567890,"USD":62500,"EUR":58200,"GBP":50000,"CAD":85000,"CHF":55400,"AUD":94000,"JPY":9000000}
        """.trimIndent()
        assertEquals(58200.0, PriceFetcher.parseMempoolBody(body, FiatCurrency.EUR))
        assertEquals(62500.0, PriceFetcher.parseMempoolBody(body, FiatCurrency.USD))
        assertEquals(55400.0, PriceFetcher.parseMempoolBody(body, FiatCurrency.CHF))
    }

    @Test
    fun `parseMempoolBody returns null for NONE`() {
        val body = """{"USD":62500}"""
        assertNull(PriceFetcher.parseMempoolBody(body, FiatCurrency.NONE))
    }

    @Test
    fun `parseMempoolBody returns null when the requested currency is missing`() {
        val body = """{"USD":62500}"""
        assertNull(PriceFetcher.parseMempoolBody(body, FiatCurrency.EUR))
    }

    @Test
    fun `parseMempoolBody parses a partial payload without throwing`() {
        // Provider sometimes returns a subset of currencies; missing
        // ones should not break the parser, just return null.
        val body = """{"time":1,"USD":62500}"""
        assertEquals(62500.0, PriceFetcher.parseMempoolBody(body, FiatCurrency.USD))
        assertNull(PriceFetcher.parseMempoolBody(body, FiatCurrency.CHF))
    }

    // ---- Yahoo Finance ------------------------------------------------

    // Phase 9 — the parser now reads the `/v8/finance/chart` shape:
    // the price lives in `chart.result[0].meta.regularMarketPrice`.
    // (The legacy `/v7/finance/quote` endpoint started returning 401
    // Unauthorized — see PriceFetcher.fetchYahooQuote docstring.)

    @Test
    fun `parseYahooBody extracts the regular market price`() {
        val body = """
            {"chart":{"result":[
              {"meta":{"symbol":"MSTR","regularMarketPrice":123.97,"currency":"USD"}}
            ],"error":null}}
        """.trimIndent()
        val quote = PriceFetcher.parseYahooBody(body, fallbackSymbol = "MSTR")
        assertNotNull(quote)
        assertEquals("MSTR", quote.symbol)
        assertEquals(123.97, quote.price)
        assertEquals("USD", quote.currency)
    }

    @Test
    fun `parseYahooBody falls back to USD when currency field absent`() {
        val body = """
            {"chart":{"result":[
              {"meta":{"symbol":"X","regularMarketPrice":1.0}}
            ]}}
        """.trimIndent()
        val quote = PriceFetcher.parseYahooBody(body, fallbackSymbol = "X")
        assertNotNull(quote)
        assertEquals("USD", quote.currency)
    }

    @Test
    fun `parseYahooBody uses the fallback symbol when result lacks one`() {
        val body = """
            {"chart":{"result":[
              {"meta":{"regularMarketPrice":42.0,"currency":"USD"}}
            ]}}
        """.trimIndent()
        val quote = PriceFetcher.parseYahooBody(body, fallbackSymbol = "FOO.AS")
        assertNotNull(quote)
        assertEquals("FOO.AS", quote.symbol)
    }

    @Test
    fun `parseYahooBody returns null on empty result list`() {
        val body = """{"chart":{"result":[]}}"""
        assertNull(PriceFetcher.parseYahooBody(body, fallbackSymbol = "X"))
    }

    @Test
    fun `parseYahooBody returns null when result is null (unknown ticker)`() {
        // Yahoo's real "ticker not found" payload: result null + error.
        val body = """{"chart":{"result":null,"error":{"code":"Not Found"}}}"""
        assertNull(PriceFetcher.parseYahooBody(body, fallbackSymbol = "X"))
    }

    @Test
    fun `parseYahooBody returns null when price is missing`() {
        val body = """
            {"chart":{"result":[
              {"meta":{"symbol":"X","currency":"USD"}}
            ]}}
        """.trimIndent()
        assertNull(PriceFetcher.parseYahooBody(body, fallbackSymbol = "X"))
    }

    @Test
    fun `parseYahooBody ignores extra unknown fields in the meta block`() {
        val body = """
            {"chart":{"result":[
              {"meta":{"symbol":"MSTR","regularMarketPrice":123.97,"currency":"USD",
               "exchangeName":"NMS","fiftyTwoWeekHigh":457.22,"longName":"Strategy Inc"},
               "timestamp":[1781294401],"indicators":{"quote":[{}]}}
            ]}}
        """.trimIndent()
        val quote = PriceFetcher.parseYahooBody(body, fallbackSymbol = "MSTR")
        assertNotNull(quote)
        assertEquals(123.97, quote.price)
    }

    // ---- buildProxy ---------------------------------------------------

    @Test
    fun `buildProxy returns NO_PROXY when disabled`() {
        val p = PriceFetcher.buildProxy(enabled = false, raw = "socks5h://127.0.0.1:9050")
        assertSame(Proxy.NO_PROXY, p)
    }

    @Test
    fun `buildProxy returns an UNRESOLVED InetSocketAddress (no local DNS leak)`() {
        // Regression for the DNS leak fix: with the old impl
        // (`InetSocketAddress(host, port)` with a hostname argument),
        // the JVM eagerly resolves DNS before the SOCKS handshake.
        // The fixed impl uses createUnresolved, which sends the
        // hostname as DOMAINNAME ATYP through the SOCKS5 proxy.
        val p = PriceFetcher.buildProxy(enabled = true, raw = "socks5h://invalid.example.tor:9050")
        val addr = p.address() as InetSocketAddress
        assertTrue(
            addr.isUnresolved,
            "Proxy address must be unresolved to avoid leaking DNS to local resolver",
        )
        assertEquals("invalid.example.tor", addr.hostString)
        assertEquals(9050, addr.port)
    }

    @Test
    fun `buildProxy parses socks5h URL with default Tor port`() {
        val p = PriceFetcher.buildProxy(enabled = true, raw = "socks5h://127.0.0.1:9050")
        assertEquals(Proxy.Type.SOCKS, p.type())
        val addr = p.address() as InetSocketAddress
        assertEquals("127.0.0.1", addr.hostString)
        assertEquals(9050, addr.port)
    }

    @Test
    fun `buildProxy parses socks5 URL (no h suffix) identically`() {
        val p = PriceFetcher.buildProxy(enabled = true, raw = "socks5://10.0.0.5:9150")
        val addr = p.address() as InetSocketAddress
        assertEquals("10.0.0.5", addr.hostString)
        assertEquals(9150, addr.port)
    }

    @Test
    fun `buildProxy parses bare host_port without a scheme`() {
        val p = PriceFetcher.buildProxy(enabled = true, raw = "my.tor:9050")
        val addr = p.address() as InetSocketAddress
        assertEquals("my.tor", addr.hostString)
        assertEquals(9050, addr.port)
    }

    @Test
    fun `buildProxy falls back to 9050 when port is missing`() {
        val p = PriceFetcher.buildProxy(enabled = true, raw = "socks5h://127.0.0.1")
        val addr = p.address() as InetSocketAddress
        assertEquals(9050, addr.port)
    }

    @Test
    fun `buildProxy falls back to 9050 when port is unparseable`() {
        val p = PriceFetcher.buildProxy(enabled = true, raw = "socks5h://127.0.0.1:not-a-number")
        val addr = p.address() as InetSocketAddress
        assertEquals(9050, addr.port)
    }

    @Test
    fun `buildProxy accepts non standard Tor Browser port`() {
        // Tor Browser bundle uses 9150 by default; some users keep that
        // alongside a standalone Tor at 9050.
        val p = PriceFetcher.buildProxy(enabled = true, raw = "socks5h://127.0.0.1:9150")
        val addr = p.address() as InetSocketAddress
        assertEquals(9150, addr.port)
    }

    // ---- buildYahooUrl (URL encoding) ---------------------------------

    @Test
    fun `buildYahooUrl returns null for blank ticker`() {
        assertNull(PriceFetcher.buildYahooUrl(""))
        assertNull(PriceFetcher.buildYahooUrl("   "))
    }

    @Test
    fun `buildYahooUrl trims whitespace before encoding`() {
        val url = PriceFetcher.buildYahooUrl("  AAPL  ")
        assertEquals("https://query1.finance.yahoo.com/v8/finance/chart/AAPL", url)
    }

    @Test
    fun `buildYahooUrl preserves common Yahoo ticker characters`() {
        // Hyphen, dot, equals are part of valid Yahoo tickers (BTC-USD,
        // IWDA.AS, GC=F). URLEncoder leaves '-' and '.' alone; '=' is
        // encoded as %3D but Yahoo accepts both forms in practice.
        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/BTC-USD",
            PriceFetcher.buildYahooUrl("BTC-USD"),
        )
        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/IWDA.AS",
            PriceFetcher.buildYahooUrl("IWDA.AS"),
        )
        assertEquals(
            "https://query1.finance.yahoo.com/v8/finance/chart/GC%3DF",
            PriceFetcher.buildYahooUrl("GC=F"),
        )
    }

    @Test
    fun `buildYahooUrl escapes ampersand and slash to prevent injection`() {
        // Regression: a malicious / typo'd ticker "FOO&bar=injected"
        // must NOT inject a query string onto the chart path. The impl
        // encodes '&' as %26 and '=' as %3D so the whole thing stays a
        // single path segment.
        val url = PriceFetcher.buildYahooUrl("FOO&bar=injected")
        assertNotNull(url)
        assertTrue(url.endsWith("/chart/FOO%26bar%3Dinjected"),
            "ampersand/equals must be encoded; got: $url")
        // And a '/' in the ticker must not climb the path: the segment
        // after "/chart/" must contain no raw slash.
        val traversal = PriceFetcher.buildYahooUrl("../../etc")
        assertNotNull(traversal)
        val segment = traversal.substringAfter("/chart/")
        assertTrue("%2F" in segment && "/" !in segment,
            "slash must be encoded to block path traversal; got: $traversal")
    }

    @Test
    fun `buildYahooUrl escapes hash and plus and percent`() {
        // Reserved characters that could either break URI parsing
        // (#, %) or be silently swallowed (+ becomes space in
        // x-www-form-urlencoded).
        val url = PriceFetcher.buildYahooUrl("FOO#BAR+%QUX")
        assertNotNull(url)
        // The "#" must be encoded — otherwise the URI parser would
        // treat what follows as a fragment.
        assertTrue("%23" in url, "# must be encoded; got: $url")
        // The "+" must be encoded as %2B (not as space).
        assertTrue("%2B" in url, "+ must be encoded; got: $url")
        // The "%" must be encoded as %25.
        assertTrue("%25" in url, "% must be encoded; got: $url")
    }

    @Test
    fun `buildYahooUrl escapes whitespace inside the symbol`() {
        // The pre-fix impl handled only spaces (replace " " with %20).
        // The fixed impl handles spaces as '+' (URLEncoder default for
        // form-encoded space). Both are accepted by Yahoo; the
        // important property is that no URISyntaxException is thrown.
        val url = PriceFetcher.buildYahooUrl("FOO BAR")
        assertNotNull(url)
        // Either %20 or '+' is acceptable encoding for a space.
        assertTrue("%20" in url || "+" in url, "space must be encoded; got: $url")
    }

    @Test
    fun `buildYahooUrl never throws for any input`() {
        // Defensive regression: the pre-fix impl let URI.create()
        // throw URISyntaxException for tickers containing '{', '}',
        // raw '%' followed by non-hex, etc. The encoded URL must
        // always be parseable.
        val nasty = listOf("FOO{BAR}", "%NOT_HEX", " ", "\n\r")
        for (n in nasty) {
            val url = PriceFetcher.buildYahooUrl(n)
            // null is OK only for trimmed-empty; everything else must
            // produce a URL we can construct a URI from.
            if (url != null) {
                URI.create(url) // must not throw
            }
        }
    }

    // ---- Phase 7.1 — CoinGecko market_chart parser -------------------

    @Test
    fun `parseCoinGeckoMarketChartBody returns the price points in order`() {
        val body = """
            {
              "prices": [
                [1700000000000, 35000.5],
                [1700003600000, 35100.0],
                [1700007200000, 35050.25]
              ],
              "market_caps": [],
              "total_volumes": []
            }
        """.trimIndent()
        val pts = PriceFetcher.parseCoinGeckoMarketChartBody(body)
        assertEquals(3, pts.size)
        assertEquals(1700000000000L, pts[0].timestampMs)
        assertEquals(35000.5, pts[0].price)
        assertEquals(35050.25, pts[2].price)
    }

    @Test
    fun `parseCoinGeckoMarketChartBody returns empty when prices array is empty`() {
        val body = """{"prices":[],"market_caps":[],"total_volumes":[]}"""
        assertTrue(PriceFetcher.parseCoinGeckoMarketChartBody(body).isEmpty())
    }

    @Test
    fun `parseCoinGeckoMarketChartBody skips malformed entries silently`() {
        val body = """
            {"prices":[
              [1700000000000, 35000.0],
              [1700003600000],
              ["not-a-number", 35100.0],
              [1700007200000, 35050.0]
            ]}
        """.trimIndent()
        val pts = PriceFetcher.parseCoinGeckoMarketChartBody(body)
        // Two well-formed entries survive; the partial and the
        // non-numeric timestamp are dropped.
        assertEquals(2, pts.size)
        assertEquals(1700000000000L, pts[0].timestampMs)
        assertEquals(1700007200000L, pts[1].timestampMs)
    }
}
