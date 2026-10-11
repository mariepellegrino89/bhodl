package io.bhodl.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.semantics.SemanticsProperties
import io.bhodl.desktop.screens.UnlockScreen
import io.bhodl.desktop.screens.UnlockTags
import io.bhodl.desktop.wallet.FakeWalletFactory
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Test-book §40.10 — user-facing errors never leak absolute paths and
 * are length-capped ([sanitizeErrorDetail] / [userMessage]).
 */
@OptIn(ExperimentalTestApi::class)
class ErrorMessagesTest {

    @AfterTest
    fun tearDown() {
        PinAttemptTracker.reset()
        WalletStore.clear()
    }

    @Test
    fun `windows and unix absolute paths are redacted`() {
        assertEquals(
            "cannot open <path> (os error 32)",
            sanitizeErrorDetail("cannot open C:\\Users\\alice\\.bhodl\\wallet.sqlite (os error 32)"),
        )
        assertEquals(
            "cannot open <path>",
            sanitizeErrorDetail("cannot open /home/alice/.bhodl/wallet.sqlite"),
        )
    }

    @Test
    fun `URLs are not mistaken for paths`() {
        val msg = "Esplora sync failed: https://mempool.space/signet/api returned 502"
        assertEquals(msg, sanitizeErrorDetail(msg))
    }

    @Test
    fun `long messages are truncated and blank ones fall back`() {
        val out = sanitizeErrorDetail("x".repeat(5_000))
        assertEquals(201, out.length)
        assertTrue(out.endsWith("…"))
        assertEquals("fallback", sanitizeErrorDetail("  ", "fallback"))
        assertEquals("fallback", sanitizeErrorDetail(null, "fallback"))
    }

    @Test
    fun `an FFI error with a path reaches the Unlock screen redacted`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory(
            failUnlock = WalletException.WalletCreate(
                detail = "failed to open C:\\Users\\alice\\.bhodl\\wallets\\abc\\wallet.sqlite",
            ),
        )
        setContent {
            BhodlTheme {
                UnlockScreen(onUnlocked = {}, onBack = {}, factory = factory, dbDirOverride = "/tmp/x")
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))
        onNodeWithTag(UnlockTags.PIN).performTextInput("123456")
        onNodeWithTag(UnlockTags.UNLOCK).performClick()
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.ERROR), timeoutMillis = 2_000)
        val text = onNodeWithTag(UnlockTags.ERROR).fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString(" ") { it.text }
        assertTrue("<path>" in text, text)
        assertFalse("alice" in text, text)
    }
}
