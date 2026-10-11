package io.bhodl.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** Compose UI tests for [`ErrorCard`] + [`prettifyNetworkError`]. */
@OptIn(ExperimentalTestApi::class)
class ErrorCardTest {

    @Test
    fun `title and message render`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ErrorCard(
                    title = "Sync failed",
                    message = "Cannot reach the Esplora endpoint.",
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ErrorCardTags.ROOT))
        onNodeWithTag(ErrorCardTags.TITLE).assertIsDisplayed()
        onNodeWithTag(ErrorCardTags.MESSAGE).assertIsDisplayed()
    }

    @Test
    fun `retry button hidden when onRetry is null`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ErrorCard(
                    title = "Title",
                    message = "Message",
                    onRetry = null,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ErrorCardTags.ROOT))
        assertEquals(0, onAllNodesWithTag(ErrorCardTags.RETRY).fetchSemanticsNodes().size)
    }

    @Test
    fun `clicking Retry fires the callback`() = runDesktopComposeUiTest {
        var retryCalled = 0
        setContent {
            BhodlTheme {
                ErrorCard(
                    title = "Title",
                    message = "Message",
                    onRetry = { retryCalled++ },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ErrorCardTags.RETRY))
        onNodeWithTag(ErrorCardTags.RETRY).performClick()
        waitUntil(timeoutMillis = 1_000) { retryCalled == 1 }
        assertEquals(1, retryCalled)
    }

    @Test
    fun `details are hidden by default and revealed by the toggle`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ErrorCard(
                    title = "Title",
                    message = "Message",
                    details = "raw stack trace blob",
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ErrorCardTags.DETAILS_TOGGLE))
        assertEquals(0, onAllNodesWithTag(ErrorCardTags.DETAILS_TEXT).fetchSemanticsNodes().size)
        onNodeWithTag(ErrorCardTags.DETAILS_TOGGLE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ErrorCardTags.DETAILS_TEXT))
        onNodeWithTag(ErrorCardTags.DETAILS_TEXT).assertIsDisplayed()
    }

    @Test
    fun `prettifyNetworkError maps known patterns to user-readable hints`() {
        // UnknownHost
        assertEquals(
            "Cannot reach the Esplora endpoint. Check your network connection or " +
                "the URL configured in Settings.",
            prettifyNetworkError(
                "java.net.UnknownHostException: signet.example.com",
            ),
        )
        // Connection refused
        assertEquals(
            "The Esplora endpoint refused the connection. Make sure your local " +
                "node / electrs is running, or pick a different URL in Settings.",
            prettifyNetworkError("Connection refused: no further information"),
        )
        // Timeout
        assertEquals(
            "Timed out reaching the Esplora endpoint. Network congestion or a slow " +
                "Tor circuit may be the cause; retry or try a different URL.",
            prettifyNetworkError("Read timed out"),
        )
        // TLS / SSL
        assertEquals(
            "TLS handshake failed against the Esplora endpoint. The certificate may " +
                "be invalid or self-signed.",
            prettifyNetworkError("PKIX path building failed: SSL certificate problem"),
        )
        // Unknown — passes through verbatim.
        assertEquals(
            "BDK refused the spend: dust-limit",
            prettifyNetworkError("BDK refused the spend: dust-limit"),
        )
    }

    @Test
    fun `prettifyNetworkError handles null and blank input`() {
        assertEquals("Unknown error.", prettifyNetworkError(null))
        assertEquals("Unknown error.", prettifyNetworkError(""))
        assertEquals("Unknown error.", prettifyNetworkError("   "))
    }

    // ----- prettifyBroadcastError ------------------------------------------

    @Test
    fun `prettifyBroadcastError maps Bitcoin Core -25 missingorspent`() {
        // Representative Bitcoin Core -25 error returned when a fee bump
        // targets a tx that confirmed mid-flow.
        val raw = """detail=HttpResponse { status: 400, message: "sendrawtransaction RPC error: """ +
            """{\"code\":-25,\"message\":\"bad-txns-inputs-missingorspent\"}" }"""
        val pretty = prettifyBroadcastError(raw)
        assertEquals(
            "The original transaction has already confirmed. " +
                "Fee bump is no longer possible — the wallet will catch up after the next sync.",
            pretty,
        )
    }

    @Test
    fun `prettifyBroadcastError maps Bitcoin Core -26 insufficient fee replacement`() {
        // Representative Bitcoin Core -26 error returned when a
        // new send conflicted with a ghost mempool tx at equal feerate.
        val raw = """detail=HttpResponse { status: 400, message: "sendrawtransaction RPC error: """ +
            """{\"code\":-26,\"message\":\"insufficient fee, rejecting replacement """ +
            """deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef; """ +
            """new feerate 0.00001004 BTC/kvB <= old feerate 0.00001004 BTC/kvB\"}" }"""
        val pretty = prettifyBroadcastError(raw)
        assertEquals(
            "This transaction would conflict with a pending one that " +
                "spends the same coins. Try a higher fee tier (Priority) to replace it, " +
                "or wait until the pending one confirms.",
            pretty,
        )
    }

    @Test
    fun `prettifyBroadcastError maps Bitcoin Core -27 already in chain`() {
        val raw = """sendrawtransaction RPC error: """ +
            """{\"code\":-27,\"message\":\"Transaction already in block chain\"}"""
        val pretty = prettifyBroadcastError(raw)
        assertEquals("This transaction is already on the chain. Nothing to do.", pretty)
    }

    @Test
    fun `prettifyBroadcastError falls back to friendly generic on unmatched 4xx`() {
        val raw = "HttpResponse { status: 400, message: \"some-new-rpc-error\" }"
        val pretty = prettifyBroadcastError(raw)
        assertEquals(
            "The network rejected the transaction. Try Sync first, then retry, " +
                "or check mempool.space with the txid for details.",
            pretty,
        )
    }

    @Test
    fun `prettifyBroadcastError maps PayJoin transport-level failure`() {
        // Representative PayJoin transport failure: DNS lookup of a
        // non-resolving endpoint (NXDOMAIN, Windows os error 11001).
        val raw = "detail=PayJoin transport: https://demo.payjoin.org/btc: " +
            "Dns Failed: resolve dns name 'demo.payjoin.org:443': " +
            "No such host is known. (os error 11001)"
        val pretty = prettifyBroadcastError(raw)
        assertEquals(
            "Could not reach the PayJoin endpoint. Check the URL " +
                "(scheme, host, port) or your network connection, then retry.",
            pretty,
        )
    }

    @Test
    fun `prettifyBroadcastError maps PayJoin endpoint HTTP error`() {
        val raw = "detail=PayJoin endpoint returned HTTP 503: backend offline"
        val pretty = prettifyBroadcastError(raw)
        assertEquals(
            "The PayJoin receiver rejected the request. The endpoint " +
                "may be retired or your tx may not meet its policy — try a " +
                "regular Send (without `pj=` in the URI) as a fallback.",
            pretty,
        )
    }

    @Test
    fun `prettifyBroadcastError returns null for null and blank and unknown`() {
        // Unknown raw → null so the caller can fall back to its own
        // raw-message rendering.
        assertEquals(null, prettifyBroadcastError(null))
        assertEquals(null, prettifyBroadcastError(""))
        assertEquals(null, prettifyBroadcastError("   "))
        assertEquals(null, prettifyBroadcastError("some entirely unrelated stack trace line"))
    }
}
