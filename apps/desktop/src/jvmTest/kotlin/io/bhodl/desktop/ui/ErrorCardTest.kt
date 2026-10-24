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
}
