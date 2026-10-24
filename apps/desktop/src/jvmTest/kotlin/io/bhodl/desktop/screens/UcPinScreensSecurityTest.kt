package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.data.BackfillRunner
import io.bhodl.desktop.wizard.DevicePinSession
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Device-PIN setup and unlock screens driven through their FFI seams
 * (`createRegistry` / `openRegistry`): docs/use-cases.md §2 and §3.
 * No Argon2 runs except where a real handle is needed for success.
 */
@OptIn(ExperimentalTestApi::class)
class UcPinScreensSecurityTest {

    private lateinit var tmp: File
    private lateinit var dataDir: File

    @BeforeTest
    fun setUp() {
        RegistryStore.clear()
        DevicePinSession.clear()
        PinAttemptTracker.reset()
        BackfillRunner.setTargetForTest { _, _ -> error("no backfill in tests") }
        tmp = kotlin.io.path.createTempDirectory("bhodl-uc-pin-").toFile()
        dataDir = File(tmp, "data")
    }

    @AfterTest
    fun tearDown() {
        RegistryStore.clear()
        DevicePinSession.clear()
        PinAttemptTracker.reset()
        BackfillRunner.cancel()
        BackfillRunner.resetTargetForTest()
        tmp.deleteRecursively()
    }

    private fun realHandle(pin: String = "482916"): RegistryHandle =
        RegistryHandle.createOrLoad(File(tmp, "real").absolutePath, pin)

    // ---- Setup ---------------------------------------------------------------

    @Test
    fun `SEC-017 the strength hint shows for 6 and 7 digits and disappears from 8`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { DevicePinSetupScreen(onCreated = {}, onBack = {}, dataDir = dataDir) } }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("48291")
        waitForIdle()
        assertEquals(0, onAllNodesWithText("moderate protection", substring = true).fetchSemanticsNodes().size)
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("6")
        waitUntilAtLeastOneExists(hasText("6-digit PIN: moderate protection", substring = true))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("3")
        waitUntilAtLeastOneExists(hasText("7-digit PIN: moderate protection", substring = true))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("7")
        waitUntilDoesNotExist(hasText("moderate protection", substring = true))
    }

    @Test
    fun `SEC-021 spaces typed in the setup fields are dropped`() = runDesktopComposeUiTest {
        var seenPin: String? = null
        setContent {
            BhodlTheme {
                DevicePinSetupScreen(
                    onCreated = {},
                    onBack = {},
                    dataDir = dataDir,
                    createRegistry = { _, pin -> seenPin = pin; throw WalletException.Io("stop") },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("48 29 16")
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("482 916")
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitUntil(timeoutMillis = 3_000) { seenPin != null }
        assertEquals("482916", seenPin)
    }

    @Test
    fun `SEC-022 a PIN over 32 digits is rejected in the UI without touching disk`() = runDesktopComposeUiTest {
        var calls = 0
        setContent {
            BhodlTheme {
                DevicePinSetupScreen(
                    onCreated = {},
                    onBack = {},
                    dataDir = dataDir,
                    createRegistry = { _, _ -> calls++; error("unreachable") },
                )
            }
        }
        val long = "1".repeat(33)
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput(long)
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput(long)
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.ERROR))
        onNodeWithTag(DevicePinSetupTags.ERROR).assertTextContains("PIN is too long (33 / 32 maximum).")
        assertEquals(0, calls)
        assertFalse(dataDir.exists())
    }

    @Test
    fun `SEC-023 a successful setup parks the registry, opens the session and continues`() = runDesktopComposeUiTest {
        val handle = realHandle()
        var created = 0
        var seenDir: String? = null
        setContent {
            BhodlTheme {
                DevicePinSetupScreen(
                    onCreated = { created++ },
                    onBack = {},
                    dataDir = dataDir,
                    createRegistry = { dir, _ -> seenDir = dir; handle },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitUntil(timeoutMillis = 3_000) { created == 1 }
        assertSame(handle, RegistryStore.current())
        assertTrue(DevicePinSession.isUnlocked())
        assertEquals(dataDir.absolutePath, seenDir)
        assertTrue(dataDir.isDirectory, "the data dir is created before the FFI call")
    }

    @Test
    fun `SEC-024 an FFI failure shows a path-free message and re-enables the button`() = runDesktopComposeUiTest {
        var created = 0
        setContent {
            BhodlTheme {
                DevicePinSetupScreen(
                    onCreated = { created++ },
                    onBack = {},
                    dataDir = dataDir,
                    createRegistry = { _, _ ->
                        throw WalletException.Io("permission denied: C:\\Users\\alice\\.bhodl\\registry.sqlite")
                    },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.PIN))
        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.ERROR), timeoutMillis = 3_000)
        val shown = onNodeWithTag(DevicePinSetupTags.ERROR).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
        assertFalse("alice" in shown, shown)
        assertTrue("permission denied" in shown, shown)
        onNodeWithTag(DevicePinSetupTags.CREATE).assertIsEnabled()
        assertEquals(0, created)
        assertNull(RegistryStore.current())
    }

    @Test
    fun `SEC-026 Back returns to Welcome and is disabled while creating`() = runDesktopComposeUiTest {
        val release = CountDownLatch(1)
        var backs = 0
        setContent {
            BhodlTheme {
                DevicePinSetupScreen(
                    onCreated = {},
                    onBack = { backs++ },
                    dataDir = dataDir,
                    createRegistry = { _, _ ->
                        release.await(5, TimeUnit.SECONDS)
                        throw WalletException.Io("stop")
                    },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.BACK))
        onNodeWithTag(DevicePinSetupTags.BACK).performClick()
        assertEquals(1, backs)

        onNodeWithTag(DevicePinSetupTags.PIN).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CONFIRM).performTextInput("48291637")
        onNodeWithTag(DevicePinSetupTags.CREATE).performClick()
        waitForIdle()
        onNodeWithTag(DevicePinSetupTags.BACK).assertIsNotEnabled()
        onNodeWithTag(DevicePinSetupTags.CREATE).assertIsNotEnabled()
        release.countDown()
        waitUntilAtLeastOneExists(hasTestTag(DevicePinSetupTags.ERROR), timeoutMillis = 3_000)
        onNodeWithTag(DevicePinSetupTags.BACK).assertIsEnabled()
    }

    // ---- Unlock --------------------------------------------------------------

    private fun androidx.compose.ui.test.ComposeUiTest.unlock(
        open: (String, String) -> RegistryHandle,
        onUnlocked: () -> Unit = {},
    ) {
        setContent {
            BhodlTheme {
                DevicePinUnlockScreen(
                    onUnlocked = onUnlocked,
                    onBack = {},
                    dataDir = dataDir,
                    openRegistry = open,
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinUnlockTags.PIN))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.submit(pin: String) {
        onNodeWithTag(DevicePinUnlockTags.PIN).performTextInput(pin)
        onNodeWithTag(DevicePinUnlockTags.UNLOCK).performClick()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.errorText(): String {
        waitUntilAtLeastOneExists(hasTestTag(DevicePinUnlockTags.ERROR), timeoutMillis = 3_000)
        return onNodeWithTag(DevicePinUnlockTags.ERROR).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString()
    }

    @Test
    fun `SEC-021 spaces typed in the unlock field are dropped`() = runDesktopComposeUiTest {
        var seen: String? = null
        unlock({ _, pin -> seen = pin; throw WalletException.Io("stop") })
        submit("48 29 16")
        waitUntil(timeoutMillis = 3_000) { seen != null }
        assertEquals("482916", seen)
    }

    @Test
    fun `SEC-027 the right PIN opens the registry, clears the attempts and continues`() = runDesktopComposeUiTest {
        val handle = realHandle()
        PinAttemptTracker.recordFailure(PinScopes.DEVICE)
        PinAttemptTracker.recordFailure(PinScopes.DEVICE)
        var unlocked = 0
        var seenDir: String? = null
        unlock({ dir, _ -> seenDir = dir; handle }, onUnlocked = { unlocked++ })
        submit("482916")
        waitUntil(timeoutMillis = 3_000) { unlocked == 1 }
        assertSame(handle, RegistryStore.current())
        assertEquals(0, PinAttemptTracker.failureCount(PinScopes.DEVICE))
        assertTrue(DevicePinSession.isUnlocked())
        assertEquals(dataDir.absolutePath, seenDir)
    }

    @Test
    fun `SEC-028 a wrong PIN shows Wrong device PIN and counts one attempt`() = runDesktopComposeUiTest {
        unlock({ _, _ -> throw WalletException.Storage("encryption key rejected: file is not a database") })
        submit("111111")
        assertEquals("Wrong device PIN.", errorText())
        assertEquals(1, PinAttemptTracker.failureCount(PinScopes.DEVICE))
        assertNull(RegistryStore.current())
    }

    @Test
    fun `SEC-031 the wrong PIN that triggers the cooldown names the wait`() = runDesktopComposeUiTest {
        PinAttemptTracker.recordFailure(PinScopes.DEVICE, nowUnix = 0L)
        PinAttemptTracker.recordFailure(PinScopes.DEVICE, nowUnix = 0L)
        unlock({ _, _ -> throw WalletException.Storage("encryption key rejected") })
        submit("111111")
        val shown = errorText()
        assertTrue(Regex("""Wrong device PIN\. Wait [1-5]s before retrying\.""").matches(shown), shown)
        assertEquals(3, PinAttemptTracker.failureCount(PinScopes.DEVICE))
    }

    @Test
    fun `SEC-030 an active lockout refuses the PIN before calling the FFI`() = runDesktopComposeUiTest {
        repeat(3) { PinAttemptTracker.recordFailure(PinScopes.DEVICE) }
        var calls = 0
        unlock({ _, _ -> calls++; error("unreachable") })
        submit("482916")
        assertTrue(errorText().startsWith("Too many wrong PINs. Wait"))
        assertEquals(0, calls)
        assertEquals(3, PinAttemptTracker.failureCount(PinScopes.DEVICE))
    }

    @Test
    fun `SEC-037 non-PIN failures get their own message and are not counted`() = runDesktopComposeUiTest {
        unlock({ _, _ -> throw WalletException.Storage("database is locked") })
        submit("482916")
        assertTrue(errorText().startsWith("The wallet files could not be read"))
        assertEquals(0, PinAttemptTracker.failureCount(PinScopes.DEVICE))
    }

    @Test
    fun `SEC-037 a registry from a newer build is not a wrong PIN`() = runDesktopComposeUiTest {
        unlock({ _, _ ->
            throw WalletException.Storage("registry schema version 9 is newer than this app supports (5)")
        })
        submit("482916")
        assertTrue(errorText().startsWith("This data was written by a newer version"))
        assertEquals(0, PinAttemptTracker.failureCount(PinScopes.DEVICE))
    }

    @Test
    fun `SEC-038 an unknown error shows a sanitized message and is not counted`() = runDesktopComposeUiTest {
        unlock({ _, _ -> throw IllegalStateException("crash in /home/alice/.bhodl/registry.sqlite") })
        submit("482916")
        val shown = errorText()
        assertFalse("alice" in shown, shown)
        assertEquals(0, PinAttemptTracker.failureCount(PinScopes.DEVICE))
    }

    @Test
    fun `SEC-039 unlock is disabled while empty or opening and the PIN field has focus`() = runDesktopComposeUiTest {
        val release = CountDownLatch(1)
        unlock({ _, _ -> release.await(5, TimeUnit.SECONDS); throw WalletException.Io("stop") })
        waitForIdle()
        onNodeWithTag(DevicePinUnlockTags.PIN).assertIsFocused()
        onNodeWithTag(DevicePinUnlockTags.UNLOCK).assertIsNotEnabled()
        onNodeWithTag(DevicePinUnlockTags.PIN).performTextInput("482916")
        onNodeWithTag(DevicePinUnlockTags.UNLOCK).assertIsEnabled()
        onNodeWithTag(DevicePinUnlockTags.UNLOCK).performClick()
        waitForIdle()
        onNodeWithTag(DevicePinUnlockTags.UNLOCK).assertIsNotEnabled()
        release.countDown()
        errorText()
        onNodeWithTag(DevicePinUnlockTags.UNLOCK).assertIsEnabled()
    }

    @Test
    fun `SEC-044 a forgotten PIN without a code reaches the explained local reset`() = runDesktopComposeUiTest {
        var backs = 0
        var wiped: File? = null
        setContent {
            BhodlTheme {
                DevicePinUnlockScreen(
                    onUnlocked = {},
                    onBack = { backs++ },
                    dataDir = dataDir,
                    openRegistry = { _, _ -> error("x") },
                    hasRecoveryCode = { false },
                    wipeLocalData = { dir ->
                        wiped = dir
                        io.bhodl.desktop.wizard.WalletPathsView.ResetResult(
                            deleted = listOf(File(dir, "registry.sqlite")),
                            failed = emptyList(),
                            aborted = false,
                        )
                    },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinUnlockTags.FORGOT))
        onNodeWithTag(DevicePinUnlockTags.FORGOT).performClick()
        // No code on this device: the branch is explained, not offered.
        waitUntilAtLeastOneExists(hasText("No recovery code was created on this device."))
        assertEquals(0, onAllNodesWithTag(ForgotPinTags.HAVE_CODE).fetchSemanticsNodes().size)
        onNodeWithTag(ForgotPinTags.NO_CODE).performClick()
        onNodeWithTag(ForgotPinTags.NO_CODE_BODY).assertTextContains("Lost unless you remember the PIN:", substring = true)
        onNodeWithTag(ForgotPinTags.WIPE).assertIsNotEnabled()
        onNodeWithTag(ForgotPinTags.WIPE_FIELD).performTextInput("DELETE")
        onNodeWithTag(ForgotPinTags.WIPE).performClick()
        waitUntil(timeoutMillis = 3_000) { backs == 1 }
        assertEquals(dataDir, wiped)
    }

    @Test
    fun `SEC-044 a refused wipe is reported and does not leave the screen`() = runDesktopComposeUiTest {
        var backs = 0
        setContent {
            BhodlTheme {
                DevicePinUnlockScreen(
                    onUnlocked = {},
                    onBack = { backs++ },
                    dataDir = dataDir,
                    openRegistry = { _, _ -> error("x") },
                    hasRecoveryCode = { true },
                    wipeLocalData = { dir ->
                        io.bhodl.desktop.wizard.WalletPathsView.ResetResult(
                            deleted = emptyList(),
                            failed = listOf(File(dir, "registry.sqlite")),
                            aborted = true,
                        )
                    },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DevicePinUnlockTags.FORGOT))
        onNodeWithTag(DevicePinUnlockTags.FORGOT).performClick()
        onNodeWithTag(ForgotPinTags.NO_CODE).performClick()
        onNodeWithTag(ForgotPinTags.WIPE_FIELD).performTextInput("DELETE")
        onNodeWithTag(ForgotPinTags.WIPE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ForgotPinTags.ERROR))
        onNodeWithTag(ForgotPinTags.ERROR).assertTextContains("Wipe stopped", substring = true)
        assertEquals(0, backs)
        // Back twice returns to the PIN form.
        onNodeWithTag(ForgotPinTags.BACK).performClick()
        onNodeWithTag(ForgotPinTags.BACK).performClick()
        waitUntilAtLeastOneExists(hasTestTag(DevicePinUnlockTags.PIN))
    }
}
