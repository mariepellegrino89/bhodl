package io.bhodl.desktop.prices

import io.bhodl.desktop.settings.SettingsStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Live Tor tests for [PriceFetcher] — run against a REAL Tor daemon by
 * the `torTest` Gradle task only (classes named `*LiveTorTest` are
 * excluded from `jvmTest`):
 *
 * ```
 * BHODL_TOR_SOCKS=socks5h://127.0.0.1:9050  # default
 * BHODL_TOR_REQUIRED=1                       # fail instead of skip without Tor
 * ./gradlew :apps:desktop:torTest
 * ```
 *
 * Covers the production SOCKS path ([PriceFetcher.torHttpGet]: manual
 * SOCKS5 with remote DNS + TLS with hostname verification): a real price
 * through Tor, an `IsTor: true` check on the same code path, and
 * fail-closed behaviour with a dead proxy.
 */
class PriceFetcherLiveTorTest {

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() = SettingsStore.reset()

    @AfterEach
    fun tearDown() = SettingsStore.reset()

    /** The configured SOCKS URL, or skip / fail when Tor is not reachable. */
    private fun torProxyUrl(): String {
        val url = System.getenv("BHODL_TOR_SOCKS")?.takeIf { it.isNotBlank() } ?: "socks5h://127.0.0.1:9050"
        val (host, port) = PriceFetcher.parseProxyHostPort(url)
        val problem = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5_000)
                s.soTimeout = 5_000
                s.getOutputStream().apply { write(byteArrayOf(5, 1, 0)); flush() }
                val ver = s.getInputStream().read()
                val method = s.getInputStream().read()
                check(ver == 5 && method == 0) { "not a no-auth SOCKS5 server ($ver/$method)" }
            }
        }.exceptionOrNull()
        if (problem != null) {
            val why = "Tor SOCKS proxy $url not reachable (${problem.message}); set BHODL_TOR_SOCKS"
            if (System.getenv("BHODL_TOR_REQUIRED") != null) throw AssertionError("BHODL_TOR_REQUIRED is set but $why")
            System.err.println("SKIP (no Tor): $why")
            assumeTrue(false, why)
        }
        return url
    }

    private fun <T> withRetries(what: String, block: () -> T?): T {
        var last: Throwable? = null
        repeat(3) { attempt ->
            val r = runCatching(block)
            r.getOrNull()?.let { return it }
            last = r.exceptionOrNull()
            System.err.println("$what: attempt ${attempt + 1} failed: ${last?.message ?: "null result"}")
            Thread.sleep(3_000)
        }
        throw AssertionError("$what failed after 3 attempts", last)
    }

    private fun deadPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun `fetchBtcPrice returns a plausible price through real Tor`() {
        val proxy = torProxyUrl()
        SettingsStore.setTorEnabled(true)
        SettingsStore.setTorProxyUrl(proxy)
        val usd = withRetries("BTC/USD via Tor") { PriceFetcher.fetchBtcPrice(FiatCurrency.USD) }
        println("BTC/USD via Tor: $usd")
        assertTrue(usd in 1_000.0..10_000_000.0, "implausible BTC/USD $usd")
    }

    @Test
    fun `mempool space fallback provider answers through real Tor`() {
        val (host, port) = PriceFetcher.parseProxyHostPort(torProxyUrl())
        val eur = withRetries("mempool.space prices via Tor") {
            PriceFetcher.parseMempoolBody(
                PriceFetcher.torHttpGet("https://mempool.space/api/v1/prices", host, port),
                FiatCurrency.EUR,
            )
        }
        assertNotNull(PriceFetcher.saneSpotPrice(eur), "implausible BTC/EUR $eur")
    }

    @Test
    fun `price SOCKS path really exits through Tor`() {
        val (host, port) = PriceFetcher.parseProxyHostPort(torProxyUrl())
        val body = withRetries("check.torproject via PriceFetcher.torHttpGet") {
            PriceFetcher.torHttpGet("https://check.torproject.org/api/ip", host, port)
        }
        val obj = json.parseToJsonElement(body).jsonObject
        println("price path exit IP: ${obj["IP"]?.jsonPrimitive?.content}")
        assertEquals(true, obj["IsTor"]?.jsonPrimitive?.booleanOrNull, "PriceFetcher Tor path did NOT exit through Tor: $body")
    }

    @Test
    fun `dead proxy fails closed and never connects directly`() {
        // A loopback "destination" that records any direct connection.
        val canary = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val hit = AtomicBoolean(false)
        thread(isDaemon = true) {
            runCatching { canary.accept().close(); hit.set(true) }
        }
        canary.use {
            val dead = deadPort()
            assertThrows(Exception::class.java) {
                PriceFetcher.torHttpGet("http://127.0.0.1:${canary.localPort}/", "127.0.0.1", dead)
            }
            SettingsStore.setTorEnabled(true)
            SettingsStore.setTorProxyUrl("socks5h://127.0.0.1:$dead")
            assertNull(PriceFetcher.fetchBtcPrice(FiatCurrency.USD), "must not get a price with the proxy down")
            Thread.sleep(300)
            assertFalse(hit.get(), "destination was contacted directly (clearnet fallback)")
        }
    }
}
