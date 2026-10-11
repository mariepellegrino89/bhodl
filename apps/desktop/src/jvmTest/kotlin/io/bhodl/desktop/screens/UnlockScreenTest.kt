package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletFactory
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`UnlockScreen`].
 *
 * Five scenarios:
 * - Empty PIN keeps the Unlock button disabled.
 * - Whitespace gets stripped from PIN input on the way in.
 * - Successful unlock hands a [`WalletPort`] to [`WalletStore`] and
 *   fires `onUnlocked`.
 * - `WalletException.Storage` (the FFI error for wrong PIN /
 *   corrupted DB) leaves the store empty and `onUnlocked` un-fired.
 * - Back fires `onBack`.
 */
@OptIn(ExperimentalTestApi::class)
class UnlockScreenTest {

    @AfterTest
    fun teardownStore() {
        WalletStore.clear()
        // The wrong-PIN case records a failure in the process-wide
        // tracker; don't leak it into later test classes.
        io.bhodl.desktop.wizard.PinAttemptTracker.reset()
    }

    @Test
    fun `Unlock is disabled when PIN is empty`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                UnlockScreen(
                    onUnlocked = {},
                    onBack = {},
                    factory = FakeWalletFactory(),
                    dbDirOverride = "/tmp/test-bhodl",
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.UNLOCK))
        onNodeWithTag(UnlockTags.UNLOCK).assertIsNotEnabled()
    }

    @Test
    fun `successful unlock stores port and fires onUnlocked`() = runDesktopComposeUiTest {
        val expectedPort = FakeWalletPort()
        val factory = FakeWalletFactory(port = expectedPort)
        var unlockedCalled = 0
        setContent {
            BhodlTheme {
                UnlockScreen(
                    onUnlocked = { unlockedCalled++ },
                    onBack = {},
                    factory = factory,
                    dbDirOverride = TEST_DB_DIR,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))

        onNodeWithTag(UnlockTags.PIN).performTextInput("123456")
        onNodeWithTag(UnlockTags.UNLOCK).performClick()

        waitUntil(timeoutMillis = 2_000) { unlockedCalled == 1 }
        assertEquals(1, unlockedCalled)

        assertEquals(1, factory.unlockCalls.size)
        val call = factory.unlockCalls.single()
        assertEquals(TEST_DB_DIR, call.dbDir)
        assertEquals(Network.SIGNET, call.network)
        assertEquals("123456", call.pin)

        // The store was populated with the port the factory returned.
        val stored = WalletStore.current()
        assertNotNull(stored)
        assertTrue(stored === expectedPort, "store should hold the exact port the factory returned")
    }

    @Test
    fun `wrong PIN keeps store empty and does not fire onUnlocked`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory(
            failUnlock = WalletException.Storage("file is encrypted or is not a database"),
        )
        var unlockedCalled = 0
        setContent {
            BhodlTheme {
                UnlockScreen(
                    onUnlocked = { unlockedCalled++ },
                    onBack = {},
                    factory = factory,
                    dbDirOverride = TEST_DB_DIR,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))

        onNodeWithTag(UnlockTags.PIN).performTextInput("wrongpin")
        onNodeWithTag(UnlockTags.UNLOCK).performClick()

        waitUntil(timeoutMillis = 2_000) { factory.unlockCalls.isNotEmpty() }
        waitForIdle()
        waitForIdle()
        assertEquals(0, unlockedCalled)
        assertNull(WalletStore.current())
    }

    @Test
    fun `whitespace in PIN input is stripped before factory call`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory()
        setContent {
            BhodlTheme {
                UnlockScreen(
                    onUnlocked = {},
                    onBack = {},
                    factory = factory,
                    dbDirOverride = TEST_DB_DIR,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))

        // The OutlinedTextField has an `onValueChange` filter that
        // drops whitespace; what reaches the factory is the cleaned
        // string. We can't type real whitespace through performTextInput
        // because it filters per-keystroke, but a fast type of "12 34"
        // covers the strip-as-you-type code path.
        onNodeWithTag(UnlockTags.PIN).performTextInput("12 34")
        onNodeWithTag(UnlockTags.UNLOCK).performClick()

        waitUntil(timeoutMillis = 2_000) { factory.unlockCalls.isNotEmpty() }
        assertEquals("1234", factory.unlockCalls.single().pin)
    }

    @Test
    fun `Back fires onBack`() = runDesktopComposeUiTest {
        var backCalled = 0
        setContent {
            BhodlTheme {
                UnlockScreen(
                    onUnlocked = {},
                    onBack = { backCalled++ },
                    factory = FakeWalletFactory(),
                    dbDirOverride = TEST_DB_DIR,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.BACK))
        onNodeWithTag(UnlockTags.BACK).performClick()
        waitUntil(timeoutMillis = 1_000) { backCalled == 1 }
        assertEquals(1, backCalled)
    }

    private companion object {
        const val TEST_DB_DIR: String = "/tmp/bhodl-unlock-test"
    }
}
