package io.bhodl.desktop.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * L3 tests for the BIP-78 PayJoin sender guard rails, against the real
 * cdylib.
 *
 * The sender now refuses loopback / private endpoints (SSRF guard) and
 * plain `http://` to clearnet hosts (BIP-78 requires https or .onion),
 * so the old "loopback no-op receiver" happy-path test can no longer
 * run against a local `HttpServer`: these tests instead assert that
 * such endpoints are refused BEFORE any request is sent and before the
 * wallet builds or signs anything. The adversarial proposal checks
 * live in the Rust unit tests (`payjoin.rs::proposal_tests`).
 *
 * The happy path (and malicious-receiver end-to-end cases) is covered in
 * Rust by `crates/bhodl-wallet-onchain/src/payjoin_e2e_tests.rs`: the
 * real negotiate -> validate -> sign -> finalize pipeline against an
 * in-process BIP-78 receiver wallet, with endpoint vetting still active.
 * A JVM/regtest variant is not needed: it would need an https receiver
 * on a public-looking address, which the SSRF guard makes impossible
 * locally by design.
 */
class PayjoinIntegrationTest : RegtestFixture() {

    private val servers = mutableListOf<HttpServer>()

    @AfterEach
    fun stopServers() {
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    @Test
    fun `loopback endpoints are refused and never contacted`() {
        val wallet = newRegtestWallet(BIP86_TEST_MNEMONIC)
        val captured = AtomicReference<String?>(null)
        val server = startMockServer { exchange ->
            captured.set(readBody(exchange))
            respondText(exchange, status = 200, body = "should never be reached")
        }
        val recipient = wallet.peekAddressAt(5u)
        val port = server.address.port
        for (pjUrl in listOf(
            "http://127.0.0.1:$port/payjoin",
            "https://127.0.0.1:$port/payjoin",
            "https://127.1:$port/payjoin",
            "https://[::ffff:127.0.0.1]:$port/payjoin",
            "https://localhost:$port/payjoin",
        )) {
            val uri = "bitcoin:$recipient?amount=0.001&pj=$pjUrl"
            val ex = runCatching {
                wallet.payjoinNegotiate(uri, recipient, 100_000UL, 2UL, null)
            }.exceptionOrNull()
            assertNotNull(ex, "expected $pjUrl to be refused")
        }
        assertNull(captured.get(), "the loopback receiver must never be contacted")
    }

    @Test
    fun `URI amount that differs from the displayed amount is refused`() {
        val wallet = newRegtestWallet(BIP86_TEST_MNEMONIC)
        val recipient = wallet.peekAddressAt(6u)
        val uri = "bitcoin:$recipient?amount=1&pj=https://pj.example.com/btc"
        val ex = runCatching {
            wallet.payjoinNegotiate(uri, recipient, 1_000UL, 2UL, null)
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(
            (ex!!.message ?: "").contains("differs"),
            "expected an amount-mismatch error, got: ${ex.message}",
        )
    }

    @Test
    fun `onion endpoint without a proxy is refused`() {
        val wallet = newRegtestWallet(BIP86_TEST_MNEMONIC)
        val recipient = wallet.peekAddressAt(7u)
        val uri = "bitcoin:$recipient?amount=0.001&pj=http://abcdefghij.onion/pj"
        val ex = runCatching {
            wallet.payjoinNegotiate(uri, recipient, 100_000UL, 2UL, null)
        }.exceptionOrNull()
        assertNotNull(ex)
        assertTrue((ex!!.message ?: "").contains("Tor"), "got: ${ex.message}")
    }

    // ---------------------------------------------------------------
    // Mock-server helpers — pure JDK, no new test dependencies.
    // ---------------------------------------------------------------

    private fun startMockServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                runCatching { handler(exchange) }
                    .getOrElse { e ->
                        respondText(exchange, status = 500, body = "mock handler threw: $e")
                    }
                exchange.close()
            }
            executor = null // default: caller thread (per the JDK docs that's a single thread pool)
        }
        server.start()
        servers += server
        return server
    }

    private fun readBody(exchange: HttpExchange): String =
        exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

    private fun respondText(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
