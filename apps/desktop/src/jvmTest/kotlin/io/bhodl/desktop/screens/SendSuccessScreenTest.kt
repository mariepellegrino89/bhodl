package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Compose UI tests for [`SendSuccessScreen`].
 *
 * The screen is intentionally minimal — the previously-uncovered
 * paths to pin down are:
 * - The txid is rendered verbatim (a copy/paste regression there
 *   would silently send users to the wrong mempool.space page or
 *   break clipboard share).
 * - The "Done" button fires the user-supplied callback.
 *
 * The "Open in mempool.space" path uses `java.awt.Desktop.browse`
 * which would actually open a browser if invoked from a test —
 * skipped intentionally to avoid the side effect.
 */
@OptIn(ExperimentalTestApi::class)
class SendSuccessScreenTest {

    @Test
    fun `renders the txid and the success copy`() = runDesktopComposeUiTest {
        val txid = "deadbeef".repeat(8) // 64 hex chars
        setContent {
            BhodlTheme {
                SendSuccessScreen(txid = txid, onDone = {})
            }
        }
        // Header + body copy.
        waitUntilAtLeastOneExists(hasText("Sent"))
        onNodeWithText("Sent").assertIsDisplayed()
        // The txid is rendered in mono. We assert via partial-match
        // because the Card layout may decorate it; the substring
        // contract is what callers (mempool.space link, clipboard)
        // depend on.
        waitUntilAtLeastOneExists(hasText(txid, substring = true))
    }

    @Test
    fun `Done fires onDone exactly once`() = runDesktopComposeUiTest {
        var doneCount = 0
        setContent {
            BhodlTheme {
                SendSuccessScreen(
                    txid = "feed".repeat(16),
                    onDone = { doneCount++ },
                )
            }
        }
        waitUntilAtLeastOneExists(hasText("Done"))
        onNodeWithText("Done").performClick()
        waitUntil(timeoutMillis = 1_000L) { doneCount == 1 }
        assertEquals(1, doneCount)
    }

    @Test
    fun `Copy txid button is rendered alongside the explorer button`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    SendSuccessScreen(
                        txid = "abcd".repeat(16),
                        onDone = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("Copy txid"))
            onNodeWithText("Copy txid").assertIsDisplayed()
            onNodeWithText("Open in mempool.space").assertIsDisplayed()
        }
}
