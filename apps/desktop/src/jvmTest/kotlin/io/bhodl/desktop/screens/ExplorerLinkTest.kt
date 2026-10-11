package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Audit 2026-10 — explorer links follow the network and warn before
 * leaving Tor (L6); the plaintext-HTTP warning also applies with Tor on (M7).
 */
@OptIn(ExperimentalTestApi::class)
class ExplorerLinkTest {

    @Test
    fun `explorer base url follows the network`() {
        assertEquals("https://mempool.space/tx", ExplorerLinks.txBaseUrl(Network.MAINNET))
        assertEquals("https://mempool.space/signet/tx", ExplorerLinks.txBaseUrl(Network.SIGNET))
        assertEquals("https://mempool.space/testnet4/tx", ExplorerLinks.txBaseUrl(Network.TESTNET4))
        assertNull(ExplorerLinks.txBaseUrl(Network.REGTEST))
        assertEquals("mempool.space", ExplorerLinks.endpointHost("https://mempool.space/api"))
    }

    @Test
    fun `without Tor the link opens directly`() = runDesktopComposeUiTest {
        var opened: String? = null
        setContent {
            BhodlTheme {
                ExplorerLinkButton(
                    txid = "ab".repeat(32),
                    explorerBaseUrl = "https://mempool.space/tx",
                    openExplorer = { opened = it },
                    onCopyTxid = {},
                    torEnabled = false,
                )
            }
        }
        waitUntilAtLeastOneExists(hasText("Open in mempool.space"))
        onNodeWithText("Open in mempool.space").performClick()
        waitForIdle()
        assertEquals("https://mempool.space/tx/" + "ab".repeat(32), opened)
    }

    @Test
    fun `with Tor the link asks first and offers copying the txid`() = runDesktopComposeUiTest {
        var opened: String? = null
        var copied = false
        setContent {
            BhodlTheme {
                ExplorerLinkButton(
                    txid = "cd".repeat(32),
                    explorerBaseUrl = "https://mempool.space/tx",
                    openExplorer = { opened = it },
                    onCopyTxid = { copied = true },
                    torEnabled = true,
                )
            }
        }
        waitUntilAtLeastOneExists(hasText("Open in mempool.space"))
        onNodeWithText("Open in mempool.space").performClick()
        waitUntilAtLeastOneExists(hasTestTag(ExplorerLinkTags.TOR_DIALOG_COPY))
        assertNull(opened, "must not open the system browser without confirmation")
        onNodeWithTag(ExplorerLinkTags.TOR_DIALOG_COPY).performClick()
        waitForIdle()
        assertTrue(copied)
        assertNull(opened)
    }

    @Test
    fun `plaintext http warning applies with Tor on too`() {
        assertTrue(isPlaintextHttpWithoutTor("http://esplora.example/api", torEnabled = false))
        assertTrue(isPlaintextHttpWithoutTor("http://esplora.example/api", torEnabled = true))
        assertFalse(isPlaintextHttpWithoutTor("http://abcdef.onion/api", torEnabled = true))
        assertFalse(isPlaintextHttpWithoutTor("http://127.0.0.1:3000", torEnabled = false))
        assertFalse(isPlaintextHttpWithoutTor("http://[::1]:3000", torEnabled = false))
        assertFalse(isPlaintextHttpWithoutTor("https://mempool.space/api", torEnabled = false))
    }
}
