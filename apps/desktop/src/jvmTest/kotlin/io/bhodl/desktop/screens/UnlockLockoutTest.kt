package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wallet.FakeWalletFactory
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinErrors
import io.bhodl.desktop.wizard.PinFailureKind
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test-book §3 / audit 2026-10 (T1-9, M3, M4) — the Unlock screen's
 * PIN lockout, end to end through the UI with a [FakeWalletFactory]
 * (no Argon2, no SQLCipher):
 *  - wrong PINs are counted and the 3rd one starts a cooldown;
 *  - during the cooldown the FFI is NOT called at all;
 *  - storage / network-mismatch failures get their own message and are
 *    NOT counted against the PIN;
 *  - a successful unlock clears the counter.
 *
 * The tracker's scope key is derived from the on-disk wallet dir, so
 * the assertions use the scope-agnostic "any scope" cooldown.
 */
@OptIn(ExperimentalTestApi::class)
class UnlockLockoutTest {

    @BeforeTest
    fun setUp() {
        PinAttemptTracker.reset()
    }

    @AfterTest
    fun tearDown() {
        PinAttemptTracker.reset()
        WalletStore.clear()
    }

    private fun wrongPin() = WalletException.Storage(detail = "file is not a database")

    @Test
    fun `third wrong PIN starts a cooldown and further attempts never reach the FFI`() =
        runDesktopComposeUiTest {
            val factory = FakeWalletFactory(failUnlock = wrongPin())
            var unlocked = 0
            setContent {
                BhodlTheme {
                    UnlockScreen(
                        onUnlocked = { unlocked++ },
                        onBack = {},
                        factory = factory,
                        dbDirOverride = TEST_DB_DIR,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))
            onNodeWithTag(UnlockTags.PIN).performTextInput("999999")

            // Attempts 1 and 2: plain "Wrong PIN.", no cooldown.
            for (attempt in 1..2) {
                onNodeWithTag(UnlockTags.UNLOCK).performClick()
                waitUntil(timeoutMillis = 2_000) { factory.unlockCalls.size == attempt }
                waitUntilAtLeastOneExists(hasText("Wrong PIN."), timeoutMillis = 2_000)
                assertEquals(0L, PinAttemptTracker.cooldownRemainingSeconds())
            }

            // Attempt 3: counted, cooldown starts and is announced.
            onNodeWithTag(UnlockTags.UNLOCK).performClick()
            waitUntil(timeoutMillis = 2_000) { factory.unlockCalls.size == 3 }
            waitUntilAtLeastOneExists(
                hasText("Wrong PIN. Wait", substring = true),
                timeoutMillis = 2_000,
            )
            assertTrue(PinAttemptTracker.cooldownRemainingSeconds() > 0L)

            // UX review 2026-10 (B10) — during the cooldown the Unlock
            // button is disabled, so a 4th attempt cannot even start.
            onNodeWithTag(UnlockTags.UNLOCK).assertIsNotEnabled()
            onNodeWithTag(UnlockTags.UNLOCK).performClick()
            waitForIdle()
            assertEquals(3, factory.unlockCalls.size, "locked out: no FFI call")
            assertEquals(0, unlocked)
            assertNull(WalletStore.current())
        }

    @Test
    fun `an active lockout blocks the very first attempt`() = runDesktopComposeUiTest {
        // Simulate three earlier failures (e.g. persisted from a previous
        // process) on EVERY scope the screen could resolve to — the
        // tracker's "any scope" deadline is what we check for, and the
        // screen consults its own scope; using the computed scope keeps
        // the test independent of the developer's ~/.bhodl layout.
        val scope = unlockScope()
        repeat(3) { PinAttemptTracker.recordFailure(scope) }
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
        onNodeWithTag(UnlockTags.PIN).performTextInput("123456")
        // B10 — the inherited lockout is announced on entry and the
        // button is disabled before the user even tries.
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.ERROR), timeoutMillis = 2_000)
        onNodeWithTag(UnlockTags.ERROR).assertTextContains("Too many wrong PINs", substring = true)
        onNodeWithTag(UnlockTags.UNLOCK).assertIsNotEnabled()
        onNodeWithTag(UnlockTags.UNLOCK).performClick()
        waitForIdle()
        assertTrue(factory.unlockCalls.isEmpty(), "even the right PIN is refused while locked")
    }

    @Test
    fun `unlock button is disabled during the countdown and re-enabled when it ends`() =
        runDesktopComposeUiTest {
            val scope = unlockScope()
            repeat(3) { PinAttemptTracker.recordFailure(scope) }
            val factory = FakeWalletFactory()
            var unlocked = 0
            setContent {
                BhodlTheme {
                    UnlockScreen(
                        onUnlocked = { unlocked++ },
                        onBack = {},
                        factory = factory,
                        dbDirOverride = TEST_DB_DIR,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))
            onNodeWithTag(UnlockTags.PIN).performTextInput("123456")
            onNodeWithTag(UnlockTags.UNLOCK).assertIsNotEnabled()
            onNodeWithTag(UnlockTags.UNLOCK).assertTextContains("s)", substring = true)

            // The cooldown expires (simulated by clearing the tracker):
            // the countdown notices on its next tick, the error line goes
            // away and the button becomes clickable again.
            PinAttemptTracker.reset()
            waitUntil(timeoutMillis = 3_000) {
                runCatching { onNodeWithTag(UnlockTags.UNLOCK).assertIsEnabled() }.isSuccess
            }
            assertTrue(onAllNodes(hasTestTag(UnlockTags.ERROR)).fetchSemanticsNodes().isEmpty())
            onNodeWithTag(UnlockTags.UNLOCK).performClick()
            waitUntil(timeoutMillis = 2_000) { unlocked == 1 }
            assertEquals(1, factory.unlockCalls.size)
        }

    @Test
    fun `storage errors show their own message and are not counted`() = runDesktopComposeUiTest {
        val storageError = WalletException.Storage(detail = "database is locked")
        assertEquals(PinFailureKind.Storage, PinErrors.classify(storageError))
        val factory = FakeWalletFactory(failUnlock = storageError)
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
        onNodeWithTag(UnlockTags.PIN).performTextInput("123456")
        // Five storage failures in a row would lock a wrong PIN out.
        for (attempt in 1..5) {
            onNodeWithTag(UnlockTags.UNLOCK).performClick()
            waitUntil(timeoutMillis = 2_000) { factory.unlockCalls.size == attempt }
        }
        waitUntilAtLeastOneExists(
            hasText("could not be read", substring = true),
            timeoutMillis = 2_000,
        )
        onNodeWithTag(UnlockTags.ERROR).assertTextContains("not counted", substring = true)
        assertEquals(0L, PinAttemptTracker.cooldownRemainingSeconds())
        assertEquals(0, PinAttemptTracker.failureCount(unlockScope()))
    }

    @Test
    fun `network mismatch is reported as such and not counted`() = runDesktopComposeUiTest {
        val mismatch = WalletException.Storage(
            detail = "network mismatch: loaded wallet is on bitcoin, expected signet",
        )
        assertEquals(PinFailureKind.NetworkMismatch, PinErrors.classify(mismatch))
        val factory = FakeWalletFactory(failUnlock = mismatch)
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
        onNodeWithTag(UnlockTags.PIN).performTextInput("123456")
        onNodeWithTag(UnlockTags.UNLOCK).performClick()
        waitUntilAtLeastOneExists(
            hasText("different network", substring = true),
            timeoutMillis = 2_000,
        )
        assertEquals(0, PinAttemptTracker.failureCount(unlockScope()))
    }

    @Test
    fun `successful unlock clears earlier wrong-PIN failures`() = runDesktopComposeUiTest {
        val factory = FakeWalletFactory(failUnlock = wrongPin())
        var unlocked = 0
        setContent {
            BhodlTheme {
                UnlockScreen(
                    onUnlocked = { unlocked++ },
                    onBack = {},
                    factory = factory,
                    dbDirOverride = TEST_DB_DIR,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(UnlockTags.PIN))
        onNodeWithTag(UnlockTags.PIN).performTextInput("111111")
        onNodeWithTag(UnlockTags.UNLOCK).performClick()
        waitUntil(timeoutMillis = 2_000) { factory.unlockCalls.size == 1 }
        waitUntilAtLeastOneExists(hasText("Wrong PIN."), timeoutMillis = 2_000)
        assertEquals(1, PinAttemptTracker.failureCount(unlockScope()))

        factory.failUnlock = null
        onNodeWithTag(UnlockTags.UNLOCK).performClick()
        waitUntil(timeoutMillis = 2_000) { unlocked == 1 }
        assertEquals(0, PinAttemptTracker.failureCount(unlockScope()))
    }

    /** Mirrors `UnlockScreen.resolveUnlockDir(null)` → scope key. */
    private fun unlockScope(): String {
        val view = io.bhodl.desktop.wizard.WalletPaths.from(
            io.bhodl.desktop.wizard.WalletPaths.defaultDataDir(),
        )
        return (view.activeWalletDir() ?: view.dir).name
    }

    private companion object {
        const val TEST_DB_DIR: String = "/tmp/bhodl-unlock-lockout-test"
    }
}
