package io.bhodl.desktop.prices

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit 2026-10 (H10) — the Tor path of [PriceFetcher] must hand the
 * destination host name to the SOCKS proxy unresolved (RFC 1928
 * ATYP=0x03), i.e. no local DNS lookup, and must parse the HTTP reply
 * robustly with bounded reads.
 */
class PriceFetcherTorTest {

    private data class SocksRequest(
        val atyp: Int,
        val host: String,
        val port: Int,
        val httpHead: String,
        val methods: List<Int> = emptyList(),
        val creds: Pair<String, String>? = null,
    )

    /**
     * One-shot fake SOCKS5 server. After the handshake it reads the HTTP
     * request head and answers with [httpResponse] (plain HTTP — the test
     * injects an identity TLS layer).
     */
    private fun fakeSocks(
        httpResponse: ByteArray,
        noAuthOnly: Boolean = false,
    ): Pair<Int, ArrayBlockingQueue<SocksRequest>> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val seen = ArrayBlockingQueue<SocksRequest>(1)
        thread(isDaemon = true) {
            server.use { ss ->
                ss.accept().use { s ->
                    val input = DataInputStream(s.getInputStream())
                    val out = s.getOutputStream()
                    assertEquals(5, input.read())
                    val nMethods = input.read()
                    val methods = ByteArray(nMethods).also { input.readFully(it) }.map { it.toInt() }
                    var creds: Pair<String, String>? = null
                    if (2 in methods && !noAuthOnly) {
                        // Like Tor: user/pass wins when offered.
                        out.write(byteArrayOf(5, 2))
                        out.flush()
                        assertEquals(1, input.read())
                        val u = ByteArray(input.read()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                        val p = ByteArray(input.read()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                        creds = u to p
                        out.write(byteArrayOf(1, 0))
                    } else {
                        out.write(byteArrayOf(5, 0))
                    }
                    out.flush()
                    input.read() // ver
                    input.read() // cmd
                    input.read() // rsv
                    val atyp = input.read()
                    val host = when (atyp) {
                        3 -> ByteArray(input.read()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                        1 -> ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xff).toString() }
                        else -> ByteArray(16).also { input.readFully(it) }.toString()
                    }
                    val port = input.readUnsignedShort()
                    out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    out.flush()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b == -1) break
                        head.append(b.toChar())
                    }
                    seen.offer(SocksRequest(atyp, host, port, head.toString(), methods, creds))
                    out.write(httpResponse)
                    out.flush()
                }
            }
        }
        return server.localPort to seen
    }

    private val identityTls: (Socket, String, Int) -> Socket = { raw, _, _ -> raw }

    @Test
    fun `tor path sends the destination as an unresolved domain name`() {
        val body = """{"bitcoin":{"eur":61234.5}}"""
        val (port, seen) = fakeSocks(
            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray(),
        )
        val out = PriceFetcher.torHttpGet(
            url = "https://api.coingecko.example/api/v3/simple/price?ids=bitcoin&vs_currencies=eur",
            proxyHost = "127.0.0.1",
            proxyPort = port,
            tlsWrap = identityTls,
        )
        assertEquals(body, out)
        val req = assertNotNull(seen.poll(5, TimeUnit.SECONDS))
        assertEquals(0x03, req.atyp, "destination must reach the proxy as ATYP=DOMAINNAME")
        assertEquals("api.coingecko.example", req.host)
        assertEquals(443, req.port)
        assertTrue(req.httpHead.startsWith("GET /api/v3/simple/price?ids=bitcoin&vs_currencies=eur HTTP/1.1\r\n"))
        assertTrue("Host: api.coingecko.example\r\n" in req.httpHead)
        assertTrue("Connection: close\r\n" in req.httpHead)
        assertFalse("BHODL" in req.httpHead, "no app-identifying User-Agent")
    }

    @Test
    fun `tor path authenticates with a random per-session isolation token`() {
        val body = "{}"
        val resp = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray()
        val tokens = (1..2).map {
            val (port, seen) = fakeSocks(resp)
            PriceFetcher.torHttpGet("https://mempool.example/p", "127.0.0.1", port, tlsWrap = identityTls)
            val req = assertNotNull(seen.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(0, 2), req.methods, "offer no-auth and user/pass")
            val (user, pass) = assertNotNull(req.creds)
            assertEquals("x", pass)
            assertEquals(32, user.length)
            assertTrue(user.all { it in '0'..'9' || it in 'a'..'f' })
            user
        }
        assertEquals(tokens[0], tokens[1], "stable within the session")
        assertEquals(PriceFetcher.priceIsolationToken, tokens[0])
        assertTrue(PriceFetcher.newIsolationToken() != PriceFetcher.newIsolationToken())
    }

    @Test
    fun `tor path proceeds without isolation when the proxy picks no-auth`() {
        val (port, seen) = fakeSocks("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray(), noAuthOnly = true)
        assertEquals("{}", PriceFetcher.torHttpGet("https://mempool.example/p", "127.0.0.1", port, tlsWrap = identityTls))
        val req = assertNotNull(seen.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf(0, 2), req.methods)
        assertEquals(null, req.creds)
    }

    @Test
    fun `tor path without a token offers only no-auth`() {
        val (port, seen) = fakeSocks("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
        PriceFetcher.torHttpGet("https://mempool.example/p", "127.0.0.1", port, tlsWrap = identityTls, isolationToken = null)
        val req = assertNotNull(seen.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf(0), req.methods)
        assertEquals(null, req.creds)
    }

    @Test
    fun `tor path decodes chunked bodies`() {
        val (port, _) = fakeSocks(
            ("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5\r\nhello\r\n6;ext=1\r\n world\r\n0\r\n\r\n").toByteArray(),
        )
        val out = PriceFetcher.torHttpGet(
            url = "https://mempool.example/api/v1/prices",
            proxyHost = "127.0.0.1",
            proxyPort = port,
            tlsWrap = identityTls,
        )
        assertEquals("hello world", out)
    }

    @Test
    fun `tor path fails on non-2xx and never falls back`() {
        val (port, _) = fakeSocks("HTTP/1.1 503 Busy\r\nContent-Length: 0\r\n\r\n".toByteArray())
        assertFailsWith<IOException> {
            PriceFetcher.torHttpGet(
                url = "https://mempool.example/api/v1/prices",
                proxyHost = "127.0.0.1",
                proxyPort = port,
                tlsWrap = identityTls,
            )
        }
    }

    @Test
    fun `tor path fails when the proxy is unreachable`() {
        val deadPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertFailsWith<IOException> {
            PriceFetcher.torHttpGet(
                url = "https://mempool.example/api/v1/prices",
                proxyHost = "127.0.0.1",
                proxyPort = deadPort,
                timeoutMs = 2_000,
                tlsWrap = identityTls,
            )
        }
    }

    @Test
    fun `response bodies are capped`() {
        val big = "x".repeat(2_000)
        val input = ByteArrayInputStream(
            "HTTP/1.1 200 OK\r\nContent-Length: ${big.length}\r\n\r\n$big".toByteArray(),
        )
        assertFailsWith<IOException> { PriceFetcher.readHttpResponse(input, maxBytes = 1_000) }
        val eof = ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\n$big".toByteArray())
        assertFailsWith<IOException> { PriceFetcher.readHttpResponse(eof, maxBytes = 1_000) }
    }

    @Test
    fun `production TLS layer enables hostname verification`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { ss ->
            Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { raw ->
                val wrapped = PriceFetcher.defaultTlsWrap(raw, "mempool.space", 443) as SSLSocket
                assertEquals("HTTPS", wrapped.sslParameters.endpointIdentificationAlgorithm)
                wrapped.close()
            }
        }
    }

    @Test
    fun `proxy host and port are parsed from the Tor setting`() {
        assertEquals("127.0.0.1" to 9050, PriceFetcher.parseProxyHostPort("socks5h://127.0.0.1:9050"))
        assertEquals("tor.local" to 9150, PriceFetcher.parseProxyHostPort("socks5://tor.local:9150"))
        assertEquals("127.0.0.1" to 9050, PriceFetcher.parseProxyHostPort("127.0.0.1"))
    }
}
