package io.bhodl.desktop.prices

import io.bhodl.desktop.settings.SettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Phase 5.0 — fetch BTC spot price in a given fiat currency.
 *
 * Provider chain:
 *  1. CoinGecko `/simple/price?ids=bitcoin&vs_currencies={code}` —
 *     no API key, ~10 req/min free tier, broad currency coverage.
 *  2. mempool.space `/api/v1/prices` — no key, unlimited, returns
 *     USD/EUR/GBP/CAD/CHF/AUD/JPY in a single response.
 *
 * Failover: try CoinGecko first; on failure (network, rate-limit,
 * malformed response), fall back to mempool.space. Both providers
 * are Bitcoin-friendly and routable through Tor.
 *
 * Tor enforcement (audit 2026-10, H10): when `SettingsStore.torEnabled`,
 * requests go through the SOCKS5 proxy in `SettingsStore.torProxyUrl`
 * on a socket we open ourselves ([torHttpGet]): the DESTINATION is an
 * unresolved [InetSocketAddress], so the JDK SOCKS client sends the host
 * name to Tor (ATYP=DOMAINNAME) and nothing is resolved locally. The
 * previous `HttpURLConnection.openConnection(Proxy(SOCKS, …))` path only
 * left the *proxy* address unresolved and looked the destination up via
 * the system resolver — a DNS leak of every price-provider host name.
 * TLS is layered on top with hostname verification enabled.
 *
 * All calls are blocking; callers must run on Dispatchers.IO.
 */
public object PriceFetcher {

    /**
     * Audit 2026-10 — generic User-Agent (Tor Browser's uniform UA)
     * instead of the old app-identifying "BHODL/0.1 (fiat-overlay)",
     * which fingerprinted the wallet to every price provider.
     */
    internal const val USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    private const val TIMEOUT_MS = 8_000

    /** Max response body we accept from any price provider (1 MiB). */
    internal const val MAX_BODY_BYTES: Int = 1024 * 1024

    /**
     * Overall deadline for one request (connect + all reads). The per-
     * socket timeouts alone don't bound a slow-drip server that sends a
     * byte every few seconds; a watchdog disconnects past this point.
     */
    internal const val OVERALL_DEADLINE_MS: Long = 20_000L

    /**
     * Tor circuits to mempool.space regularly stall 20-35 s before the
     * connect completes; with the clearnet 8 s timeout the fallback
     * provider failed under Tor. Same budget as the Rust rate agent
     * (generous connect, 60 s overall).
     */
    internal const val TOR_TIMEOUT_MS: Int = 45_000
    internal const val TOR_OVERALL_DEADLINE_MS: Long = 60_000L

    /**
     * Plausibility window for a BTC spot price in any supported fiat.
     * Anything outside (or NaN / ∞ / ≤ 0) is a broken or hostile
     * provider answer and triggers the fallback instead of being cached.
     */
    internal const val MIN_SANE_SPOT: Double = 100.0
    internal const val MAX_SANE_SPOT: Double = 1.0e9

    /** Daemon scheduler for the per-request watchdog. */
    private val watchdog: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "price-fetch-watchdog").apply { isDaemon = true }
        }

    /** Returns [price] when it is a plausible BTC spot price, else null. */
    internal fun saneSpotPrice(price: Double?): Double? {
        if (price == null || !price.isFinite()) return null
        if (price < MIN_SANE_SPOT || price > MAX_SANE_SPOT) return null
        return price
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the current BTC price in [currency], or `null` if both
     * providers failed. Caller is responsible for caching the result.
     */
    public fun fetchBtcPrice(currency: FiatCurrency): Double? {
        if (currency == FiatCurrency.NONE) return null
        return runCatching { fetchFromCoinGecko(currency) }
            .recoverCatching { fetchFromMempoolSpace(currency) }
            .getOrNull()
    }

    /**
     * Phase 7.1 — historical BTC price series for the net-worth
     * sparkline. CoinGecko `/coins/bitcoin/market_chart` returns
     * `(timestamp_ms, price)` tuples, granularity auto-determined by
     * the [days] window:
     *  - days = 1   → 5-min intervals
     *  - days 2-90  → hourly intervals
     *  - days > 90  → daily intervals
     *
     * Returns null when [currency] is NONE, [days] is non-positive,
     * or the fetch fails. No fallback provider yet — mempool.space
     * historical-price endpoint is daily-only and currently
     * USD-locked, so CoinGecko-only for the first cut.
     */
    public fun fetchBtcPriceHistory(
        currency: FiatCurrency,
        days: Int,
    ): List<HistoricalPricePoint>? {
        if (currency == FiatCurrency.NONE || days <= 0) return null
        val url = "https://api.coingecko.com/api/v3/coins/bitcoin/market_chart?" +
            "vs_currency=${currency.code.lowercase()}&days=$days"
        return runCatching {
            val body = httpGet(url)
            parseCoinGeckoMarketChartBody(body)
        }.getOrNull()
    }

    /**
     * Phase 5.2 — fetch a stock / ETF / forex / commodity quote from
     * Yahoo Finance. No API key required.
     *
     * Endpoint: `https://query1.finance.yahoo.com/v8/finance/chart/{ticker}`
     * — returns the latest price under `chart.result[0].meta.regularMarketPrice`
     * (Double) and `meta.currency` (the currency the price is denominated
     * in, usually the exchange's home currency).
     *
     * Phase 9 fix: the legacy `/v7/finance/quote` endpoint this used to
     * call now returns `401 Unauthorized` ("User is unable to access
     * this feature") — Yahoo gated it behind a cookie + crumb handshake
     * around 2024. The `/v8/finance/chart` endpoint still answers
     * anonymously, so we read the spot price from its `meta` block. The
     * symbol moves from a query parameter to a path segment.
     *
     * Returns `null` if the ticker is not found, the network call
     * fails, or Yahoo rate-limited us. Caller is responsible for
     * caching and rate-limit-aware scheduling.
     *
     * Notes:
     *  - Yahoo's API is unofficial. May break with site changes;
     *    `Phase 5.3` will add Alpha Vantage / Twelve Data fallbacks
     *    via API key picker.
     *  - Coverage: USA (AAPL, MSFT, MSTR), European (IWDA.AS, VWCE.DE),
     *    forex (EURUSD=X), commodities (GC=F for gold), crypto
     *    (BTC-USD). Suffix conventions matter.
     */
    public fun fetchYahooQuote(ticker: String): YahooQuote? {
        val symbol = ticker.trim()
        if (symbol.isEmpty()) return null
        val url = buildYahooUrl(symbol) ?: return null
        return runCatching {
            val body = httpGet(url)
            parseYahooBody(body, fallbackSymbol = symbol)
        }.getOrNull()
    }

    /**
     * Build the Yahoo Finance chart URL for [symbol], applying full
     * percent-encoding via [URLEncoder]. Yahoo tickers include
     * characters like `=`, `-`, `.`, `^` (futures suffix); raw
     * interpolation would break URI parsing (`URI.create` throws on
     * `{`, `}`, whitespace).
     *
     * The symbol is now a *path* segment (`/v8/finance/chart/{symbol}`),
     * not a query parameter, so encoding also guards against path
     * traversal and query/fragment injection: [URLEncoder] escapes
     * `/` → `%2F`, `?` → `%3F`, `#` → `%23`, `&` → `%26`, so a malicious
     * ticker can neither climb the path nor smuggle a query string.
     *
     * Returns null when [symbol] is blank.
     */
    internal fun buildYahooUrl(symbol: String): String? {
        val trimmed = symbol.trim()
        if (trimmed.isEmpty()) return null
        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
        return "https://query1.finance.yahoo.com/v8/finance/chart/$encoded"
    }

    private fun fetchFromCoinGecko(currency: FiatCurrency): Double {
        val url = "https://api.coingecko.com/api/v3/simple/price?" +
            "ids=bitcoin&vs_currencies=${currency.code.lowercase()}"
        val body = httpGet(url)
        return saneSpotPrice(parseCoinGeckoBody(body, currency))
            ?: error("CoinGecko returned no plausible price for ${currency.code}")
    }

    private fun fetchFromMempoolSpace(currency: FiatCurrency): Double {
        val url = "https://mempool.space/api/v1/prices"
        val body = httpGet(url)
        return saneSpotPrice(parseMempoolBody(body, currency))
            ?: error("mempool.space returned no plausible price for ${currency.code}")
    }

    // -- internal parsing helpers (test surface) ------------------------
    //
    // Extracted so unit tests can verify the wire-format handling
    // without standing up an HTTP server. Each helper is a pure
    // function of the response body and (where relevant) the target
    // currency; returns null when the body does not encode a price
    // for that currency (e.g. NONE, missing field).

    internal fun parseCoinGeckoBody(body: String, currency: FiatCurrency): Double? {
        if (currency == FiatCurrency.NONE) return null
        val parsed = json.decodeFromString<CoinGeckoResponse>(body)
        return when (currency) {
            FiatCurrency.EUR -> parsed.bitcoin.eur
            FiatCurrency.USD -> parsed.bitcoin.usd
            FiatCurrency.CHF -> parsed.bitcoin.chf
            FiatCurrency.NONE -> null
        }
    }

    internal fun parseMempoolBody(body: String, currency: FiatCurrency): Double? {
        if (currency == FiatCurrency.NONE) return null
        val parsed = json.decodeFromString<MempoolSpacePrices>(body)
        return when (currency) {
            FiatCurrency.EUR -> parsed.EUR
            FiatCurrency.USD -> parsed.USD
            FiatCurrency.CHF -> parsed.CHF
            FiatCurrency.NONE -> null
        }
    }

    /**
     * Parse the CoinGecko `market_chart` response body. The wire
     * format is `{ "prices": [[ts_ms, price], ...], "market_caps": ...,
     * "total_volumes": ... }` — we only consume `prices`. Returns an
     * empty list rather than null when the array is well-formed but
     * empty; null only on parse failure (caller already wraps in
     * runCatching upstream).
     */
    internal fun parseCoinGeckoMarketChartBody(body: String): List<HistoricalPricePoint> {
        val root = json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject
        val prices = root["prices"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return prices.mapNotNull { pair ->
            val arr = pair as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
            if (arr.size < 2) return@mapNotNull null
            val ts = (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
                ?: (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
                ?: return@mapNotNull null
            val price = (arr[1] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
                ?: return@mapNotNull null
            HistoricalPricePoint(timestampMs = ts, price = price)
        }
    }

    /**
     * Parse the Yahoo `/v8/finance/chart/{symbol}` response. The spot
     * price lives in `chart.result[0].meta.regularMarketPrice`; the
     * denomination in `meta.currency`. Returns null when the chart has
     * no result, no meta, or no price (e.g. unknown ticker — Yahoo then
     * sends `{"chart":{"result":null,"error":{...}}}`).
     */
    internal fun parseYahooBody(body: String, fallbackSymbol: String): YahooQuote? {
        val parsed = json.decodeFromString<YahooChartResponse>(body)
        val meta = parsed.chart?.result?.firstOrNull()?.meta ?: return null
        val price = meta.regularMarketPrice ?: return null
        return YahooQuote(
            symbol = meta.symbol ?: fallbackSymbol,
            price = price,
            currency = meta.currency ?: "USD",
        )
    }

    private fun httpGet(url: String): String {
        if (SettingsStore.torEnabled.value) {
            val (proxyHost, proxyPort) = parseProxyHostPort(SettingsStore.torProxyUrl.value)
            return torHttpGet(url, proxyHost, proxyPort)
        }
        val conn = URI.create(url).toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        // Overall deadline: the watchdog force-closes the connection,
        // which makes any blocked read throw.
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val kill = watchdog.schedule(
            {
                timedOut.set(true)
                runCatching { conn.disconnect() }
            },
            OVERALL_DEADLINE_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code from $url")
            }
            // Bounded read: a hostile / broken endpoint can't make us
            // buffer an unbounded body.
            val bytes = conn.inputStream.use {
                io.bhodl.desktop.data.readBytesCapped(it, MAX_BODY_BYTES)
            }
            return String(bytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            if (timedOut.get()) {
                throw java.io.IOException("request exceeded ${OVERALL_DEADLINE_MS / 1000}s deadline", e)
            }
            throw e
        } finally {
            kill.cancel(false)
            conn.disconnect()
        }
    }

    /** `(host, port)` of a `socks5h://host:port` / `socks5://…` / bare setting. */
    internal fun parseProxyHostPort(raw: String): Pair<String, Int> {
        val withoutScheme = raw.trim().substringAfter("://", raw.trim()).trimEnd('/')
        val host = withoutScheme.substringBeforeLast(":").trim('[', ']')
        val port = withoutScheme.substringAfterLast(":", "9050").toIntOrNull() ?: 9050
        return host to port
    }

    /** Production TLS layer: default trust store + hostname verification. */
    internal val defaultTlsWrap: (Socket, String, Int) -> Socket = { raw, host, port ->
        val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(raw, host, port, true) as SSLSocket
        val params = ssl.sslParameters
        // Without this, JSSE checks the chain but NOT that the
        // certificate belongs to [host].
        params.endpointIdentificationAlgorithm = "HTTPS"
        ssl.sslParameters = params
        ssl
    }

    /**
     * GET [url] through the SOCKS5 proxy at [proxyHost]:[proxyPort] with
     * remote DNS (audit 2026-10, H10). The TCP connection is opened to an
     * UNRESOLVED destination address, so the JDK SOCKS client hands the
     * host name to the proxy (RFC 1928 ATYP=0x03) and no local DNS query
     * is made. Then TLS ([tlsWrap]) and a minimal HTTP/1.1 exchange
     * (`Connection: close`; `Content-Length`, chunked and read-to-EOF
     * bodies), bounded to [maxBytes] and [deadlineMs] overall.
     *
     * Never falls back to a direct connection: an unreachable proxy is an
     * exception.
     */
    internal fun torHttpGet(
        url: String,
        proxyHost: String,
        proxyPort: Int,
        timeoutMs: Int = TOR_TIMEOUT_MS,
        maxBytes: Int = MAX_BODY_BYTES,
        deadlineMs: Long = TOR_OVERALL_DEADLINE_MS,
        tlsWrap: (Socket, String, Int) -> Socket = defaultTlsWrap,
        isolationToken: String? = priceIsolationToken,
    ): String {
        val uri = URI.create(url)
        val host = uri.host ?: throw IOException("URL has no host: $url")
        val https = uri.scheme.equals("https", ignoreCase = true)
        val port = if (uri.port != -1) uri.port else if (https) 443 else 80
        val raw = Socket()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val kill = watchdog.schedule(
            {
                timedOut.set(true)
                runCatching { raw.close() }
            },
            deadlineMs,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        try {
            raw.soTimeout = timeoutMs
            // The proxy's own address may be resolved locally (it is
            // normally 127.0.0.1); the destination never is.
            raw.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            socks5Handshake(raw, host, port, isolationToken)
            val socket = if (https) tlsWrap(raw, host, port) else raw
            socket.use { s ->
                s.soTimeout = timeoutMs
                val path = buildString {
                    append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                    uri.rawQuery?.let { append('?').append(it) }
                }
                val hostHeader = if ((https && port == 443) || (!https && port == 80)) host else "$host:$port"
                val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $hostHeader\r\n" +
                    "User-Agent: $USER_AGENT\r\n" +
                    "Accept: application/json\r\n" +
                    "Accept-Encoding: identity\r\n" +
                    "Connection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.US_ASCII))
                    flush()
                }
                val (code, body) = readHttpResponse(BufferedInputStream(s.getInputStream()), maxBytes)
                if (code !in 200..299) throw IOException("HTTP $code from $host")
                return String(body, StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            if (timedOut.get()) {
                throw IOException("request exceeded ${deadlineMs / 1000}s deadline", e)
            }
            throw e
        } finally {
            kill.cancel(false)
            runCatching { raw.close() }
        }
    }

    /**
     * Random per-session SOCKS5 isolation token for all price / rate
     * traffic. Tor's default `IsolateSOCKSAuth` puts streams with
     * different credentials on different circuits, so this traffic never
     * shares a circuit with a wallet's Esplora sync (the Rust core uses
     * its own tokens). Memory only, never persisted, derived from nothing.
     */
    internal val priceIsolationToken: String by lazy { newIsolationToken() }

    /** 128 random bits as lowercase hex. */
    internal fun newIsolationToken(): String {
        val raw = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private val ipv4Literal = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * RFC 1928 / RFC 1929 handshake on [s], already connected to the
     * proxy. The JDK's built-in SOCKS client is deliberately not used: it
     * always offers username/password and, without a JVM-global
     * `Authenticator`, answers with the OS `user.name`.
     *
     * With an [isolationToken] the greeting offers no-auth (0x00) and
     * user/pass (0x02); if the proxy picks 0x02 the token is the username
     * (password `x`), if it picks 0x00 we proceed un-isolated. Without a
     * token only 0x00 is offered. The destination is sent as
     * ATYP=0x03 (domain) unless it is an IPv4 literal; never resolved here.
     */
    internal fun socks5Handshake(s: Socket, host: String, port: Int, isolationToken: String?) {
        val token = isolationToken?.takeIf { it.isNotEmpty() }
        val out = s.getOutputStream()
        val input = java.io.DataInputStream(s.getInputStream())
        out.write(if (token != null) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        if (input.read() != 5) throw IOException("bad SOCKS5 greeting reply")
        when (val method = input.read()) {
            0 -> Unit // no auth: proxy cannot isolate, carry on
            2 -> {
                if (token == null) throw IOException("SOCKS5 proxy selected an unoffered method")
                val u = token.toByteArray(StandardCharsets.US_ASCII)
                val p = "x".toByteArray(StandardCharsets.US_ASCII)
                if (u.size > 255) throw IOException("SOCKS5 credential too long")
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                if (input.read() != 1 || input.read() != 0) throw IOException("SOCKS5 username/password rejected")
            }
            else -> throw IOException("SOCKS5 proxy selected unsupported method $method")
        }
        val req = ByteArrayOutputStream()
        req.write(byteArrayOf(5, 1, 0))
        if (ipv4Literal.matches(host)) {
            req.write(1)
            host.split('.').forEach { req.write(it.toInt()) }
        } else {
            val name = host.toByteArray(StandardCharsets.US_ASCII)
            if (name.isEmpty() || name.size > 255) throw IOException("bad host length")
            req.write(3)
            req.write(name.size)
            req.write(name)
        }
        req.write(port shr 8)
        req.write(port and 0xff)
        out.write(req.toByteArray())
        out.flush()
        if (input.read() != 5) throw IOException("bad SOCKS5 reply version")
        val rep = input.read()
        if (rep != 0) throw IOException("SOCKS5 connect failed (reply $rep)")
        input.read() // RSV
        when (val atyp = input.read()) {
            1 -> input.readFully(ByteArray(4 + 2))
            4 -> input.readFully(ByteArray(16 + 2))
            3 -> input.readFully(ByteArray(input.read() + 2))
            else -> throw IOException("bad SOCKS5 bound address type $atyp")
        }
    }

    /** Max size of the status line + headers. */
    private const val MAX_HEAD_BYTES: Int = 64 * 1024

    /**
     * Parse a minimal HTTP/1.1 response: status code + body. Supports
     * `Transfer-Encoding: chunked`, `Content-Length` and read-to-EOF;
     * the body is capped at [maxBytes] (excess → [IOException]).
     */
    internal fun readHttpResponse(input: InputStream, maxBytes: Int): Pair<Int, ByteArray> {
        var headBytes = 0
        fun line(): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (out.size() == 0) throw IOException("connection closed before response")
                    break
                }
                if (++headBytes > MAX_HEAD_BYTES) throw IOException("response head too large")
                if (b == '\n'.code) break
                if (b != '\r'.code) out.write(b)
            }
            return out.toString(StandardCharsets.ISO_8859_1)
        }

        val status = line()
        val parts = status.split(' ')
        if (parts.size < 2 || !parts[0].startsWith("HTTP/1.")) throw IOException("bad status line")
        val code = parts[1].toIntOrNull() ?: throw IOException("bad status code")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val h = line()
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }

        val body = ByteArrayOutputStream()
        fun copy(n: Long) {
            if (body.size() + n > maxBytes) throw IOException("response body exceeds $maxBytes bytes")
            val buf = ByteArray(8192)
            var left = n
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r == -1) throw IOException("truncated body")
                body.write(buf, 0, r)
                left -= r
            }
        }

        val chunked = headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true
        val contentLength = headers["content-length"]?.toLongOrNull()
        when {
            chunked -> {
                while (true) {
                    val sizeLine = line().substringBefore(';').trim()
                    val size = sizeLine.toLongOrNull(16) ?: throw IOException("bad chunk size")
                    if (size < 0) throw IOException("bad chunk size")
                    if (size == 0L) break
                    copy(size)
                    line() // CRLF after each chunk
                }
            }
            contentLength != null -> {
                if (contentLength < 0) throw IOException("bad content-length")
                copy(contentLength)
            }
            else -> {
                val buf = ByteArray(8192)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    if (body.size() + r > maxBytes) throw IOException("response body exceeds $maxBytes bytes")
                    body.write(buf, 0, r)
                }
            }
        }
        return code to body.toByteArray()
    }

    /**
     * Pure proxy builder, exposed for unit tests. When [enabled] is
     * false, returns [Proxy.NO_PROXY] regardless of [raw]. Otherwise
     * parses host:port from a `socks5://`, `socks5h://`, or
     * scheme-less string, falling back to port 9050 when the port is
     * missing or unparseable. Returned proxy is always SOCKS type.
     *
     * Uses [InetSocketAddress.createUnresolved] so the JVM never
     * performs a local DNS lookup on `host`. With Java's SOCKS5
     * implementation, an unresolved address is sent to the proxy as
     * a hostname (DOMAINNAME ATYP, RFC 1928 §4) and resolution
     * happens at the Tor exit — equivalent to `socks5h://`. Without
     * this, `new InetSocketAddress(String, int)` triggers
     * `InetAddress.getByName` immediately, leaking the hostname to
     * the local DNS resolver before the SOCKS handshake.
     */
    internal fun buildProxy(enabled: Boolean, raw: String): Proxy {
        if (!enabled) return Proxy.NO_PROXY
        val withoutScheme = raw.substringAfter("://", raw)
        val host = withoutScheme.substringBefore(":")
        val port = withoutScheme.substringAfter(":", "9050").toIntOrNull() ?: 9050
        return Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
    }

    // -- response models ------------------------------------------------

    @Serializable
    private data class CoinGeckoResponse(val bitcoin: CoinGeckoPrice)

    @Serializable
    private data class CoinGeckoPrice(
        val eur: Double? = null,
        val usd: Double? = null,
        val chf: Double? = null,
    )

    @Suppress("PropertyName") // matches mempool.space JSON keys verbatim
    @Serializable
    private data class MempoolSpacePrices(
        val time: Long? = null,
        val USD: Double? = null,
        val EUR: Double? = null,
        val GBP: Double? = null,
        val CAD: Double? = null,
        val CHF: Double? = null,
        val AUD: Double? = null,
        val JPY: Double? = null,
    )

    // Phase 9 — `/v8/finance/chart` response. We only consume the
    // `meta` block of the first result; the `timestamp` / `indicators`
    // arrays (intraday OHLC) are intentionally left unparsed.
    @Serializable
    private data class YahooChartResponse(val chart: YahooChart? = null)

    @Serializable
    private data class YahooChart(val result: List<YahooChartResult>? = null)

    @Serializable
    private data class YahooChartResult(val meta: YahooChartMeta? = null)

    @Serializable
    private data class YahooChartMeta(
        val symbol: String? = null,
        val regularMarketPrice: Double? = null,
        val currency: String? = null,
    )
}

/**
 * Phase 5.2 — Yahoo Finance quote response (parsed). `price` is the
 * regular market price in the position's home currency (`currency`).
 */
public data class YahooQuote(
    val symbol: String,
    val price: Double,
    val currency: String,
)

/**
 * Phase 7.1 — a single point on the BTC price history series.
 * [timestampMs] is the wall-clock millis when the price was observed
 * (CoinGecko returns UTC). [price] is in the fiat currency requested
 * at fetch time — the caller is responsible for keeping track of
 * which currency a series corresponds to.
 */
public data class HistoricalPricePoint(
    val timestampMs: Long,
    val price: Double,
)
