package io.bhodl.desktop.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletPathsView
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
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
 * Test-book §14b / §15 / §40 — the sensitive and destructive actions,
 * driven through the UI:
 *  - the watch-only descriptor (account xpub) is exported only on
 *    request; address-list wallets report "no descriptor";
 *  - "Remove this wallet" is two-tap armed;
 *  - typed "DELETE" wipe: wrong text keeps the button disabled, Cancel
 *    touches nothing, the confirmed wipe empties the (temp) data dir.
 *
 * Every wipe targets a temp dir.
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
        io.bhodl.desktop.wizard.LocalWipe.defaultTrashRoot(tmp).deleteRecursively()
        tmp.deleteRecursively()
    }

    // ---- Wallet settings: descriptor + remove ---------------------------------

    @Test
    fun `watch-only descriptor is exported only on request`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                WalletSettingsTab(onWalletRemoved = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_REVEAL))
        waitForIdle()
        assertTrue(fake.exportPublicDescriptorCalls.isEmpty(), "nothing is exported on open")
        assertEquals(0, onAllNodesWithTag(SettingsTags.WATCH_DESC_XPUB).fetchSemanticsNodes().size)

        onNodeWithTag(SettingsTags.WATCH_DESC_REVEAL).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_XPUB), timeoutMillis = 2_000)
        assertEquals(1, fake.exportPublicDescriptorCalls.size)
    }

    @Test
    fun `address-list wallet reports that it has no descriptor`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort(addressList = true).apply { exportPublicDescriptorResult = null }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                WalletSettingsTab(onWalletRemoved = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_REVEAL))
        onNodeWithTag(SettingsTags.WATCH_DESC_REVEAL).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasText("no descriptor", substring = true), timeoutMillis = 2_000)
        assertEquals(0, onAllNodesWithTag(SettingsTags.WATCH_DESC_XPUB).fetchSemanticsNodes().size)
    }

    @Test
    fun `remove this wallet needs a second tap`() = runDesktopComposeUiTest {
        // Real registry in the temp dir; only the first (arming) tap is
        // driven, so nothing on disk is ever deleted.
        val registry = RegistryHandle.createOrLoad(tmp.absolutePath, "482916")
        RegistryStore.set(registry)
        try {
            val fake = FakeWalletPort()
            WalletStore.set(fake, Network.SIGNET, "00000000-0000-4000-8000-000000000001")
            var removed = 0
            setContent {
                BhodlTheme {
                    WalletSettingsTab(onWalletRemoved = { removed++ })
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.DELETE_THIS_WALLET))
            onNodeWithTag(SettingsTags.DELETE_THIS_WALLET).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("Tap again", substring = true))
            waitForIdle()
            assertEquals(0, removed)
            assertNotNull(WalletStore.current(), "the wallet stays open after the first tap")
            assertEquals(0, fake.closeCalls)
        } finally {
            RegistryStore.clear()
        }
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
    fun `confirmed wipe empties the data dir and drops PIN history`() =
        runDesktopComposeUiTest {
            val files = seedDataDir()
            PinAttemptTracker.recordFailure(SCOPE)
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
        }

    @Test
    fun `Welcome reset without a registry moves the files to trash after the seed acknowledgement`() =
        runDesktopComposeUiTest {
            // Legacy files only: without a registry there is no device PIN.
            val registryFiles = setOf(WalletPaths.REGISTRY_FILE_NAME, WalletPaths.REGISTRY_KDF_FILE_NAME)
            val files = seedDataDir().filter { f -> (f.name !in registryFiles).also { keep -> if (!keep) f.delete() } }
            var resetComplete = 0
            setContent {
                BhodlTheme {
                    WelcomeScreen(
                        onGetStarted = {},
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

    /** Creates a minimal data-dir layout in [tmp]. */
    private fun seedDataDir(): List<File> = listOf(
        File(tmp, WalletPaths.REGISTRY_FILE_NAME),
        File(tmp, WalletPaths.REGISTRY_KDF_FILE_NAME),
        File(tmp, WalletPaths.WALLET_DB_FILE_NAME),
        File(tmp, WalletPaths.KDF_FILE_NAME),
        File(tmp, WalletPaths.NETWORK_FILE_NAME),
    ).onEach { it.writeText("x") }

    /** Registry + one legacy per-wallet directory. */
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
