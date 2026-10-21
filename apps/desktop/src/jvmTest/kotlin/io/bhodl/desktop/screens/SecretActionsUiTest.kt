package io.bhodl.desktop.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.SetupSecrets
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletPathsView
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test-book §14b / §15 / §40 — the secret-revealing and destructive
 * actions, driven through the UI:
 *  - wallet-PIN re-auth (seed reveal, backup export): wrong PIN denied
 *    and counted, lockout after 3, storage errors not counted, Cancel
 *    reveals nothing;
 *  - typed "DELETE" wipe: wrong text keeps the button disabled, Cancel
 *    touches nothing, the confirmed wipe empties the (temp) data dir.
 *
 * No real Argon2 / SQLCipher: the PIN check goes through the
 * [LocalWalletPinCheck] seam and every wipe targets a temp dir.
 */
@OptIn(ExperimentalTestApi::class)
class SecretActionsUiTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        PinAttemptTracker.reset()
        tmp = kotlin.io.path.createTempDirectory("bhodl-secret-actions-").toFile()
    }

    @AfterTest
    fun tearDown() {
        SettingsStore.reset()
        PinAttemptTracker.reset()
        WalletStore.clear()
        SetupSecrets.wipeAll()
        io.bhodl.desktop.wizard.LocalWipe.defaultTrashRoot(tmp).deleteRecursively()
        tmp.deleteRecursively()
    }

    /** "123456" is the right PIN; counts every check. */
    private class CountingPinCheck(var failure: Throwable? = null) : WalletPinCheck {
        val checks = mutableListOf<String>()
        override fun scope(): String = SCOPE
        override fun check(pin: String): Boolean {
            checks += pin
            failure?.let { throw it }
            return pin == "123456"
        }
    }

    // ---- WalletPinReauthDialog ------------------------------------------------

    @Test
    fun `re-auth locks out after three wrong PINs and stops calling the check`() =
        runDesktopComposeUiTest {
            val check = CountingPinCheck()
            var verified = 0
            setContent {
                BhodlTheme {
                    CompositionLocalProvider(LocalWalletPinCheck provides check) {
                        WalletPinReauthDialog(
                            purpose = "reveal the recovery phrase",
                            onVerified = { verified++ },
                            onDismiss = {},
                        )
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.PIN))
            for (attempt in 1..3) {
                onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("000000")
                onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
                waitUntil(timeoutMillis = 2_000) { check.checks.size == attempt }
                waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.ERROR))
            }
            assertEquals(3, PinAttemptTracker.failureCount(SCOPE))
            onNodeWithTag(WalletPinReauthTags.ERROR)
                .assertTextContains("Too many wrong PINs", substring = true)

            // Even the right PIN is refused while the cooldown runs.
            onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("123456")
            onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
            waitForIdle()
            assertEquals(3, check.checks.size, "no check during the lockout")
            assertEquals(0, verified)
        }

    @Test
    fun `re-auth storage failure is reported and not counted`() = runDesktopComposeUiTest {
        val check = CountingPinCheck(
            failure = WalletException.Storage(detail = "database is locked"),
        )
        var verified = 0
        setContent {
            BhodlTheme {
                CompositionLocalProvider(LocalWalletPinCheck provides check) {
                    WalletPinReauthDialog(
                        purpose = "export an encrypted backup",
                        onVerified = { verified++ },
                        onDismiss = {},
                    )
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.PIN))
        onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("123456")
        onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasText("could not be read", substring = true))
        assertEquals(0, PinAttemptTracker.failureCount(SCOPE))
        assertEquals(0, verified)
    }

    @Test
    fun `re-auth Cancel dismisses without verifying`() = runDesktopComposeUiTest {
        val check = CountingPinCheck()
        var verified = 0
        var dismissed = 0
        setContent {
            BhodlTheme {
                CompositionLocalProvider(LocalWalletPinCheck provides check) {
                    WalletPinReauthDialog(
                        purpose = "reveal the recovery phrase",
                        onVerified = { verified++ },
                        onDismiss = { dismissed++ },
                    )
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.CANCEL))
        onNodeWithTag(WalletPinReauthTags.CONFIRM).assertIsNotEnabled()
        onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("123456")
        onNodeWithTag(WalletPinReauthTags.CANCEL).performClick()
        waitUntil(timeoutMillis = 1_000) { dismissed == 1 }
        assertEquals(0, verified)
        assertTrue(check.checks.isEmpty())
    }

    // ---- Settings: seed reveal / backup export --------------------------------

    @Test
    fun `backup export with a wrong wallet PIN exports nothing and counts the failure`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply { exportBackupBlobBuilder = { "blob" } }
            WalletStore.set(fake)
            val check = CountingPinCheck()
            setContent {
                BhodlTheme {
                    CompositionLocalProvider(LocalWalletPinCheck provides check) {
                        WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {})
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.BACKUP_PASSWORD))
            onNodeWithTag(SettingsTags.BACKUP_PASSWORD).performScrollTo()
                .performTextInput("backup-password-long")
            onNodeWithTag(SettingsTags.BACKUP_PASSWORD_CONFIRM).performScrollTo()
                .performTextInput("backup-password-long")
            onNodeWithTag(SettingsTags.BACKUP_EXPORT).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.PIN))
            onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("654321")
            onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
            waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.ERROR))
            assertEquals(1, PinAttemptTracker.failureCount(SCOPE))
            assertTrue(fake.exportBackupCalls.isEmpty(), "wrong PIN must not export")
            assertEquals(0, onAllNodesWithTag(SettingsTags.BACKUP_BLOB).fetchSemanticsNodes().size)
        }

    @Test
    fun `backup export is blocked while the password is empty or unconfirmed`() =
        runDesktopComposeUiTest {
            WalletStore.set(FakeWalletPort())
            setContent {
                BhodlTheme { WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {}) }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.BACKUP_EXPORT))
            onNodeWithTag(SettingsTags.BACKUP_EXPORT).performScrollTo().assertIsNotEnabled()
            onNodeWithTag(SettingsTags.BACKUP_PASSWORD_HINT, useUnmergedTree = true).assertExists()
            onNodeWithTag(SettingsTags.BACKUP_PASSWORD).performScrollTo()
                .performTextInput("backup-password-long")
            onNodeWithTag(SettingsTags.BACKUP_PASSWORD_CONFIRM).performScrollTo()
                .performTextInput("backup-password-lonG")
            onNodeWithTag(SettingsTags.BACKUP_EXPORT).performScrollTo().assertIsNotEnabled()
        }

    @Test
    fun `cancelling the seed-reveal re-auth reveals nothing`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        val check = CountingPinCheck()
        setContent {
            BhodlTheme {
                CompositionLocalProvider(LocalWalletPinCheck provides check) {
                    WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {})
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.RECOVERY_REVEAL))
        onNodeWithTag(SettingsTags.RECOVERY_REVEAL).performScrollTo().performClick()
        onNodeWithTag(SettingsTags.RECOVERY_REVEAL).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.CANCEL))
        onNodeWithTag(WalletPinReauthTags.CANCEL).performClick()
        waitUntilDoesNotExist(hasTestTag(WalletPinReauthTags.PIN))
        assertTrue(fake.peekMnemonicCalls.isEmpty())
        assertTrue(check.checks.isEmpty())
        assertEquals(0, onAllNodesWithTag(SettingsTags.RECOVERY_PHRASE).fetchSemanticsNodes().size)
    }

    @Test
    fun `watch-only descriptor is shown only after the wallet PIN`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        val check = CountingPinCheck()
        setContent {
            BhodlTheme {
                CompositionLocalProvider(LocalWalletPinCheck provides check) {
                    WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {})
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_REVEAL))
        onNodeWithTag(SettingsTags.WATCH_DESC_REVEAL).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.CANCEL))
        onNodeWithTag(WalletPinReauthTags.CANCEL).performClick()
        waitUntilDoesNotExist(hasTestTag(WalletPinReauthTags.PIN))
        assertTrue(fake.exportPublicDescriptorCalls.isEmpty(), "cancel exports nothing")

        onNodeWithTag(SettingsTags.WATCH_DESC_REVEAL).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.PIN))
        onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("123456")
        onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_XPUB), timeoutMillis = 2_000)
        assertEquals(1, fake.exportPublicDescriptorCalls.size)
    }

    @Test
    fun `delete this wallet asks for the wallet PIN after the two-tap arm`() = runDesktopComposeUiTest {
        // Real registry in the temp dir; only the CANCEL path is driven,
        // so nothing on disk is ever deleted.
        val registry = io.bhodl.ffi.RegistryHandle.createOrLoad(tmp.absolutePath, "482916")
        io.bhodl.desktop.wizard.RegistryStore.set(registry)
        try {
            val fake = FakeWalletPort()
            WalletStore.set(fake, io.bhodl.ffi.Network.SIGNET, "00000000-0000-4000-8000-000000000001")
            var deleted = 0
            setContent {
                BhodlTheme {
                    CompositionLocalProvider(LocalWalletPinCheck provides CountingPinCheck()) {
                        WalletSettingsTab(onWalletWiped = { deleted++ }, onSwitchSigningWallet = {})
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.DELETE_THIS_WALLET))
            onNodeWithTag(SettingsTags.DELETE_THIS_WALLET).performScrollTo().performClick()
            onNodeWithTag(SettingsTags.DELETE_THIS_WALLET).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.CANCEL))
            onNodeWithTag(WalletPinReauthTags.CANCEL).performClick()
            waitUntilDoesNotExist(hasTestTag(WalletPinReauthTags.PIN))
            waitForIdle()
            assertEquals(0, deleted)
            assertNotNull(WalletStore.current(), "the wallet stays open after cancel")
        } finally {
            io.bhodl.desktop.wizard.RegistryStore.clear()
        }
    }

    @Test
    fun `revealed seed and passphrase are hidden again by Hide`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            peekMnemonicResult = io.bhodl.ffi.MnemonicReveal(
                mnemonic = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu",
                passphrase = "thirteenth",
            )
        }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                CompositionLocalProvider(LocalWalletPinCheck provides CountingPinCheck()) {
                    WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {})
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.RECOVERY_REVEAL))
        onNodeWithTag(SettingsTags.RECOVERY_REVEAL).performScrollTo().performClick()
        onNodeWithTag(SettingsTags.RECOVERY_REVEAL).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.PIN))
        onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("123456")
        onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.RECOVERY_PHRASE), timeoutMillis = 2_000)
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.RECOVERY_PASSPHRASE))
        onNodeWithTag(SettingsTags.RECOVERY_HIDE).performScrollTo().performClick()
        waitUntilDoesNotExist(hasTestTag(SettingsTags.RECOVERY_PHRASE))
        assertEquals(0, onAllNodesWithTag(SettingsTags.RECOVERY_PASSPHRASE).fetchSemanticsNodes().size)
        onNodeWithTag(SettingsTags.RECOVERY_REVEAL).performScrollTo()
    }

    // ---- Typed DELETE wipe -----------------------------------------------------

    @Test
    fun `wipe dialog stays disabled for anything but DELETE`() = runDesktopComposeUiTest {
        var finished: WalletPathsView.ResetResult? = null
        setContent {
            BhodlTheme {
                ConfirmWipeDialog(dataDir = tmp, onDismiss = {}, onFinished = { finished = it })
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.FIELD))
        onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsNotEnabled()
        for (wrong in listOf("delete", "DELET", "DELETE NOW", "Delete", "DE LETE")) {
            onNodeWithTag(ConfirmWipeTags.FIELD).performTextClearance()
            onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput(wrong)
            onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsNotEnabled()
        }
        // Surrounding whitespace is tolerated (trimmed), case is not.
        onNodeWithTag(ConfirmWipeTags.FIELD).performTextClearance()
        onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput(" DELETE ")
        onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsEnabled()
        assertNull(finished, "typing alone never wipes")
    }

    @Test
    fun `wipe Cancel keeps every file and the unlocked wallet`() = runDesktopComposeUiTest {
        val files = seedDataDir()
        WalletStore.set(FakeWalletPort())
        var wiped = 0
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = {}, onWalletWiped = { wiped++ }, dataDir = tmp)
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WIPE))
        onNodeWithTag(SettingsTags.WIPE).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.FIELD))
        onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput("DELETE")
        onNodeWithTag(ConfirmWipeTags.CANCEL).performClick()
        waitUntilDoesNotExist(hasTestTag(ConfirmWipeTags.FIELD))
        assertEquals(0, wiped)
        assertNotNull(WalletStore.current())
        files.forEach { assertTrue(it.exists(), "${it.name} must survive Cancel") }
    }

    @Test
    fun `confirmed wipe empties the data dir and drops PIN history and parked seeds`() =
        runDesktopComposeUiTest {
            val files = seedDataDir()
            PinAttemptTracker.recordFailure(SCOPE)
            SetupSecrets.put("abandon ".repeat(11) + "about")
            var finished: WalletPathsView.ResetResult? = null
            setContent {
                BhodlTheme {
                    ConfirmWipeDialog(dataDir = tmp, onDismiss = {}, onFinished = { finished = it })
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.FIELD))
            onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput("DELETE")
            onNodeWithTag(ConfirmWipeTags.CONFIRM).performClick()
            waitUntil(timeoutMillis = 3_000) { finished != null }
            val result = finished!!
            assertFalse(result.aborted)
            assertTrue(result.failed.isEmpty(), "failed: ${result.failed}")
            files.forEach { assertFalse(it.exists(), "${it.name} must be wiped") }
            assertEquals(0, PinAttemptTracker.failureCount(SCOPE))
            assertEquals(0, SetupSecrets.size())
        }

    @Test
    fun `Welcome reset without a registry moves the files to trash after the seed acknowledgement`() =
        runDesktopComposeUiTest {
            val files = seedDataDir()
            var resetComplete = 0
            setContent {
                BhodlTheme {
                    WelcomeScreen(
                        onCreateWallet = {},
                        onUnlock = {},
                        onRestore = {},
                        onResetComplete = { resetComplete++ },
                        dataDir = tmp,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(WelcomeTags.RESET))
            onNodeWithTag(WelcomeTags.RESET).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.TRASH_BODY))
            // No registry → no PIN field and no "forgot PIN" toggle.
            assertEquals(0, onAllNodesWithTag(ConfirmWipeTags.PIN).fetchSemanticsNodes().size)
            assertEquals(0, onAllNodesWithTag(ConfirmWipeTags.FORGOT_PIN).fetchSemanticsNodes().size)
            onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput("DELETE")
            // The typed phrase alone is not enough: the seed acknowledgement is required.
            onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsNotEnabled()
            onNodeWithTag(ConfirmWipeTags.SEED_ACK).performClick()
            onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsEnabled().performClick()
            waitUntil(timeoutMillis = 3_000) { resetComplete == 1 }
            waitUntilAtLeastOneExists(hasText("Moved", substring = true))
            files.forEach { assertFalse(it.exists(), "${it.name} must leave the data dir") }
            // …but it is recoverable: same names under <trashRoot>/<stamp>/.
            val stamps = trashRoot().listFiles()!!.toList()
            assertEquals(1, stamps.size)
            files.forEach { assertTrue(File(stamps[0], it.name).isFile, "${it.name} must be in the trash") }
        }

    @Test
    fun `Welcome reset with a registry requires the device PIN and counts wrong attempts`() =
        runDesktopComposeUiTest {
            val files = seedRegistryDataDir()
            val check = FakeDevicePinCheck()
            var finished: WalletPathsView.ResetResult? = null
            setContent {
                CompositionLocalProvider(LocalDevicePinCheck provides check) {
                    BhodlTheme {
                        ConfirmWipeDialog(dataDir = tmp, preAuth = true, onDismiss = {}, onFinished = { finished = it })
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.PIN))
            onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput("DELETE")
            // DELETE without a PIN is not enough.
            onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsNotEnabled()
            onNodeWithTag(ConfirmWipeTags.PIN).performTextInput("000000")
            onNodeWithTag(ConfirmWipeTags.CONFIRM).performClick()
            waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.ERROR))
            assertNull(finished)
            assertEquals(listOf("000000"), check.checks)
            assertEquals(1, PinAttemptTracker.failureCount(io.bhodl.desktop.wizard.PinScopes.DEVICE))
            files.forEach { assertTrue(it.exists(), "${it.name} must survive a wrong PIN") }

            onNodeWithTag(ConfirmWipeTags.PIN).performTextInput("123456")
            onNodeWithTag(ConfirmWipeTags.CONFIRM).performClick()
            waitUntil(timeoutMillis = 3_000) { finished != null }
            val result = finished!!
            assertTrue(result.ok, "failed: ${result.failed}")
            assertNull(result.movedTo, "a PIN-verified wipe deletes, it does not move")
            files.forEach { assertFalse(it.exists(), "${it.name} must be wiped") }
            assertFalse(trashRoot().exists())
        }

    @Test
    fun `forgot device PIN switches to the trash path`() = runDesktopComposeUiTest {
        val files = seedRegistryDataDir()
        val check = FakeDevicePinCheck()
        var finished: WalletPathsView.ResetResult? = null
        setContent {
            CompositionLocalProvider(LocalDevicePinCheck provides check) {
                BhodlTheme {
                    ConfirmWipeDialog(dataDir = tmp, preAuth = true, onDismiss = {}, onFinished = { finished = it })
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.FORGOT_PIN))
        onNodeWithTag(ConfirmWipeTags.FORGOT_PIN).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.TRASH_BODY))
        assertEquals(0, onAllNodesWithTag(ConfirmWipeTags.PIN).fetchSemanticsNodes().size)
        onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput("DELETE")
        onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsNotEnabled()
        onNodeWithTag(ConfirmWipeTags.SEED_ACK).performClick()
        onNodeWithTag(ConfirmWipeTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 3_000) { finished != null }
        val result = finished!!
        assertTrue(result.ok, "failed: ${result.failed}")
        assertNotNull(result.movedTo)
        assertTrue(check.checks.isEmpty(), "the forgot-PIN path never probes a PIN")
        files.forEach { f ->
            assertFalse(f.exists())
            assertTrue(File(result.movedTo, f.relativeTo(tmp).path).isFile, "${f.name} must be in the trash")
        }
    }

    /** Creates a minimal legacy single-wallet layout in [tmp]. */
    private fun seedDataDir(): List<File> = listOf(
        File(tmp, WalletPaths.WALLET_DB_FILE_NAME),
        File(tmp, WalletPaths.KDF_FILE_NAME),
        File(tmp, WalletPaths.NETWORK_FILE_NAME),
    ).onEach { it.writeText("x") }

    /** Registry + one migrated signing wallet. */
    private fun seedRegistryDataDir(): List<File> {
        val wallet = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/w1").apply { mkdirs() }
        return listOf(
            File(tmp, WalletPaths.REGISTRY_FILE_NAME),
            File(tmp, WalletPaths.REGISTRY_KDF_FILE_NAME),
            File(wallet, WalletPaths.WALLET_DB_FILE_NAME),
            File(wallet, WalletPaths.KDF_FILE_NAME),
        ).onEach { it.writeText("x") }
    }

    private fun trashRoot(): File = io.bhodl.desktop.wizard.LocalWipe.defaultTrashRoot(tmp)

    /** "123456" is the right device PIN; records every probe. */
    private class FakeDevicePinCheck : DevicePinCheck {
        val checks = mutableListOf<String>()
        override fun check(dataDir: File, pin: String): Boolean {
            checks += pin
            return pin == "123456"
        }
    }

    private companion object {
        const val SCOPE: String = "test-wallet"
    }
}
