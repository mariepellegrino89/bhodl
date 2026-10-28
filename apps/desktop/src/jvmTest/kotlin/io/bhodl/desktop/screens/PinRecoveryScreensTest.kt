package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.data.BackfillRunner
import io.bhodl.desktop.wizard.DevicePinSession
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.registryHasRecoveryCode
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PIN recovery UI (docs/pin-recovery.md, docs/use-cases.md SEC-044,
 * SEC-140..SEC-146): recovery code panel, "forgot PIN" with a code,
 * change PIN, Security Hub row. The end-to-end cases run the real FFI.
 */
@OptIn(ExperimentalTestApi::class)
class PinRecoveryScreensTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        RegistryStore.clear()
        DevicePinSession.clear()
        PinAttemptTracker.reset()
        BackfillRunner.setTargetForTest { _, _ -> error("no backfill in tests") }
        tmp = kotlin.io.path.createTempDirectory("bhodl-pin-recovery-").toFile()
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

    private val code = "ABCD-EFGH-JKMN-PQRS-TVWX-YZ01"

    // ---- pure helpers --------------------------------------------------------

    @Test
    fun `SEC-140 code groups are matched with the core's tolerance`() {
        assertEquals("JKMN", RecoveryCodes.group(code, 3))
        assertTrue(RecoveryCodes.groupMatches(code, 3, " jkmn "))
        assertTrue(RecoveryCodes.groupMatches("0011-2222-3333-4444-5555-6666", 1, "oOiL"))
        assertFalse(RecoveryCodes.groupMatches(code, 3, "JKM"))
        assertTrue(RecoveryCodes.isRejected(WalletException.Storage("recovery code rejected")))
        assertFalse(RecoveryCodes.isRejected(WalletException.Storage("database is locked")))
    }

    // ---- recovery code panel -------------------------------------------------

    @Test
    fun `SEC-141 the code is shown once and closing needs one group typed back`() = runDesktopComposeUiTest {
        var saved = 0
        setContent {
            BhodlTheme {
                RecoveryCodePanel(
                    hasCode = false,
                    onDismiss = {},
                    onSaved = { saved++ },
                    createCode = { code },
                    pickGroup = { 3 },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RecoveryCodeTags.CREATE))
        onNodeWithTag(RecoveryCodeTags.CREATE).performClick()
        waitForUnmerged(RecoveryCodeTags.CODE)
        onNodeWithTag(RecoveryCodeTags.CODE, useUnmergedTree = true).assertTextEquals(code)
        onNodeWithTag(RecoveryCodeTags.DONE).assertIsNotEnabled()
        // The wrong group keeps the panel open.
        onNodeWithTag(RecoveryCodeTags.GROUP, useUnmergedTree = true).performTextInput("PQRS")
        onNodeWithTag(RecoveryCodeTags.DONE).assertIsNotEnabled()
        onNodeWithTag(RecoveryCodeTags.GROUP, useUnmergedTree = true).performTextClearance()
        onNodeWithTag(RecoveryCodeTags.GROUP, useUnmergedTree = true).performTextInput("jkmn")
        onNodeWithTag(RecoveryCodeTags.DONE).assertIsEnabled()
        onNodeWithTag(RecoveryCodeTags.DONE).performClick()
        assertEquals(1, saved)
    }

    @Test
    fun `SEC-141 replacing an existing code warns first and reports a failure`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                RecoveryCodePanel(
                    hasCode = true,
                    onDismiss = {},
                    onSaved = {},
                    createCode = { throw WalletException.Io("disk full") },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RecoveryCodeTags.CREATE))
        assertEquals(
            1,
            onAllNodesWithText("stops working", substring = true, useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        onNodeWithTag(RecoveryCodeTags.CREATE).assertTextEquals("Create a new code")
        onNodeWithTag(RecoveryCodeTags.CREATE).performClick()
        waitForUnmerged(RecoveryCodeTags.ERROR)
        onNodeWithTag(RecoveryCodeTags.ERROR, useUnmergedTree = true).assertTextContains("Could not create the recovery code", substring = true)
    }

    // ---- forgot PIN with a code ----------------------------------------------

    @Test
    fun `SEC-142 a wrong code is counted, a malformed one is not`() = runDesktopComposeUiTest {
        var next: Throwable = WalletException.Storage("recovery code rejected")
        setContent {
            BhodlTheme {
                DevicePinUnlockScreen(
                    onUnlocked = {},
                    onBack = {},
                    dataDir = tmp,
                    openRegistry = { _, _ -> error("x") },
                    hasRecoveryCode = { true },
                    recoverRegistry = { _, _, _ -> throw next },
                )
            }
        }
        openCodeForm()
        fillCodeForm(code, "246810")
        onNodeWithTag(ForgotPinTags.RESET).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ForgotPinTags.ERROR))
        onNodeWithTag(ForgotPinTags.ERROR).assertTextEquals("Wrong recovery code.")
        assertEquals(1, PinAttemptTracker.failureCount(PinScopes.RECOVERY))

        next = WalletException.InvalidArgument("recovery code must be 24 characters")
        onNodeWithTag(ForgotPinTags.RESET).performClick()
        waitUntilAtLeastOneExists(hasText("A recovery code has 24 characters", substring = true))
        assertEquals(1, PinAttemptTracker.failureCount(PinScopes.RECOVERY))
        assertEquals(0, PinAttemptTracker.failureCount(PinScopes.DEVICE))
    }

    @Test
    fun `SEC-142 mismatched new PINs are refused before the core is called`() = runDesktopComposeUiTest {
        var calls = 0
        setContent {
            BhodlTheme {
                DevicePinUnlockScreen(
                    onUnlocked = {},
                    onBack = {},
                    dataDir = tmp,
                    openRegistry = { _, _ -> error("x") },
                    hasRecoveryCode = { true },
                    recoverRegistry = { _, _, _ -> calls++; error("unreachable") },
                )
            }
        }
        openCodeForm()
        onNodeWithTag(ForgotPinTags.CODE).performTextInput(code)
        onNodeWithTag(ForgotPinTags.NEW_PIN).performTextInput("246810")
        onNodeWithTag(ForgotPinTags.CONFIRM).performTextInput("246811")
        onNodeWithTag(ForgotPinTags.RESET).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ForgotPinTags.ERROR))
        onNodeWithTag(ForgotPinTags.ERROR).assertTextEquals("Device PINs do not match.")
        assertEquals(0, calls)
    }

    @Test
    fun `SEC-143 end to end - recovery code sets a new PIN and opens the real registry`() = runDesktopComposeUiTest {
        val dir = File(tmp, "data")
        val created = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        created.addWatchAddresses("List", listOf(SIGNET_ADDRESS), Network.SIGNET)
        val realCode = created.createRecoveryCode()
        created.destroy()
        assertTrue(registryHasRecoveryCode(dir.absolutePath))

        var unlocked = 0
        setContent {
            BhodlTheme {
                DevicePinUnlockScreen(onUnlocked = { unlocked++ }, onBack = {}, dataDir = dir)
            }
        }
        openCodeForm()
        fillCodeForm(realCode.lowercase(), "246810")
        onNodeWithTag(ForgotPinTags.RESET).performClick()
        waitUntil(timeoutMillis = 30_000) { unlocked == 1 }
        val open = assertNotNull(RegistryStore.current())
        assertEquals(1, open.listWallets().size)
        assertTrue(DevicePinSession.isUnlocked())
        RegistryStore.clear()

        // The new PIN opens the store, the old one no longer does.
        RegistryHandle.createOrLoad(dir.absolutePath, "246810").destroy()
        val old = runCatching { RegistryHandle.createOrLoad(dir.absolutePath, "482916") }
        assertTrue(old.isFailure)
    }

    // ---- change PIN ----------------------------------------------------------

    @Test
    fun `SEC-144 a wrong current PIN is counted and a right one confirms`() = runDesktopComposeUiTest {
        var answer = false
        var changed: String? = null
        setContent {
            BhodlTheme {
                ChangePinPanel(onDismiss = {}, onChanged = { changed = it }, changePin = { _, _ -> answer })
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ChangePinTags.CURRENT))
        onNodeWithTag(ChangePinTags.CURRENT).performTextInput("000000")
        onNodeWithTag(ChangePinTags.NEW_PIN).performTextInput("246810")
        onNodeWithTag(ChangePinTags.CONFIRM).performTextInput("246810")
        onNodeWithTag(ChangePinTags.SAVE).performClick()
        waitForUnmerged(ChangePinTags.ERROR)
        onNodeWithTag(ChangePinTags.ERROR, useUnmergedTree = true).assertTextEquals("Wrong device PIN.")
        assertEquals(1, PinAttemptTracker.failureCount(PinScopes.DEVICE))

        answer = true
        onNodeWithTag(ChangePinTags.SAVE).performClick()
        waitUntil(timeoutMillis = 3_000) { changed != null }
        assertEquals("Device PIN changed.", changed)
        assertEquals(0, PinAttemptTracker.failureCount(PinScopes.DEVICE))
    }

    @Test
    fun `SEC-144 end to end - change PIN on the real registry`() {
        val dir = File(tmp, "data")
        val handle = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        assertFalse(handle.changeDevicePin("111111", "246810"))
        assertTrue(handle.changeDevicePin("482916", "246810"))
        handle.destroy()
        RegistryHandle.createOrLoad(dir.absolutePath, "246810").destroy()
    }

    // ---- Security Hub --------------------------------------------------------

    private fun facts(recovery: Boolean, pin: Boolean = true) = SecurityFacts(
        activeWalletId = null,
        labelsExportedAtUnix = null,
        devicePinSet = pin,
        torEnabled = true,
        network = Network.SIGNET,
        watchWallets = 1,
        recoveryCodeSet = recovery,
    )

    @Test
    fun `SEC-145 the hub flags a missing code and flips to OK once it is saved`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SecurityHubScreen(
                    onBack = {},
                    onOpenWallets = {},
                    onOpenSettings = {},
                    facts = facts(recovery = false),
                    createRecoveryCode = { code },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SecurityHubTags.ROOT))
        onNodeWithTag(SecurityHubTags.status(SecurityCheck.RECOVERY)).assertTextEquals("Attention")
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.RECOVERY)).assertTextEquals("Create")
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.RECOVERY)).performClick()
        waitUntilAtLeastOneExists(hasTestTag(RecoveryCodeTags.CREATE))
        onNodeWithTag(RecoveryCodeTags.CREATE).performClick()
        waitForUnmerged(RecoveryCodeTags.CODE)
        // The hub picks the group at random: read which one is asked.
        val asked = (1..RecoveryCodes.GROUPS).first { g ->
            onAllNodesWithText("type group $g of the code", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(RecoveryCodeTags.GROUP, useUnmergedTree = true).performTextInput(RecoveryCodes.group(code, asked))
        onNodeWithTag(RecoveryCodeTags.DONE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SecurityHubTags.NOTICE))
        onNodeWithTag(SecurityHubTags.status(SecurityCheck.RECOVERY)).assertTextEquals("OK")
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.RECOVERY)).assertTextEquals("Manage")
    }

    @Test
    fun `SEC-145 without a device PIN the recovery row is informative and has no action`() {
        val rows = securityChecks(facts(recovery = false, pin = false))
        assertEquals(SecurityStatus.INFO, rows.first { it.check == SecurityCheck.RECOVERY }.status)
        assertEquals(
            SecurityStatus.OK,
            securityChecks(facts(recovery = true)).first { it.check == SecurityCheck.RECOVERY }.status,
        )
    }

    // ---- wipe ----------------------------------------------------------------

    @Test
    fun `SEC-146 the factory reset also removes the recovery file`() {
        val dir = File(tmp, "data")
        RegistryHandle.createOrLoad(dir.absolutePath, "482916").apply { createRecoveryCode() }.destroy()
        val recovery = File(dir, WalletPaths.RECOVERY_FILE_NAME)
        assertTrue(recovery.isFile)
        val result = WalletPaths.from(dir).resetChecked()
        assertFalse(result.aborted)
        assertFalse(recovery.exists())
    }

    // ---- helpers -------------------------------------------------------------

    /** Panel bodies merge into the popup's node: look in the unmerged tree. */
    private fun androidx.compose.ui.test.ComposeUiTest.waitForUnmerged(tag: String) {
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.openCodeForm() {
        waitUntilAtLeastOneExists(hasTestTag(DevicePinUnlockTags.FORGOT))
        onNodeWithTag(DevicePinUnlockTags.FORGOT).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ForgotPinTags.HAVE_CODE))
        onNodeWithTag(ForgotPinTags.HAVE_CODE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ForgotPinTags.CODE))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.fillCodeForm(code: String, pin: String) {
        onNodeWithTag(ForgotPinTags.CODE).performTextInput(code)
        onNodeWithTag(ForgotPinTags.NEW_PIN).performTextInput(pin)
        onNodeWithTag(ForgotPinTags.CONFIRM).performTextInput(pin)
    }

    private companion object {
        /** BIP-173 test vector (P2WPKH, `tb` HRP — valid on signet). */
        const val SIGNET_ADDRESS = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    }
}
