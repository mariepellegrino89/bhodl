package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.SensitiveClipboard
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WatchBipFfi
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
 * Wallet settings on a real registry in a temp dir: rename, remove
 * (with the excluded-id cleanup) and the clipboard auto-clear
 * (docs/use-cases.md SEC-161, SEC-163, SEC-164, GAP-024).
 */
@OptIn(ExperimentalTestApi::class)
class UcWalletSettingsSecurityTest {

    private lateinit var tmp: File
    private lateinit var registry: RegistryHandle
    private lateinit var walletId: String

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        tmp = kotlin.io.path.createTempDirectory("bhodl-uc-wallet-settings-").toFile()
        registry = RegistryHandle.createOrLoad(tmp.absolutePath, "482916")
        RegistryStore.set(registry)
        walletId = registry.addWatchXpub("Savings", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT).id
        registry.addWatchAddresses("Spending", listOf(ADDRESS), Network.SIGNET)
        WalletStore.set(FakeWalletPort(), Network.SIGNET, walletId, "Savings")
    }

    @AfterTest
    fun tearDown() {
        WalletStore.clear()
        RegistryStore.clear()
        SettingsStore.reset()
        tmp.deleteRecursively()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.mount(onRemoved: () -> Unit = {}) {
        setContent { BhodlTheme { WalletSettingsTab(onWalletRemoved = onRemoved) } }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WALLET_NAME_FIELD))
        waitUntil(timeoutMillis = 3_000) {
            runCatching { onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).assertIsEnabled() }.isSuccess
        }
    }

    @Test
    fun `SEC-161 a new name is saved to the registry and bumps the data version`() = runDesktopComposeUiTest {
        mount()
        val before = RegistryStore.dataVersion.value
        onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performScrollTo().performTextClearance()
        onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextInput("  Cold storage ")
        onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).performClick()
        waitUntilAtLeastOneExists(hasText("Saved."), timeoutMillis = 3_000)
        assertEquals("Cold storage", registry.getWallet(walletId)?.label)
        assertTrue(RegistryStore.dataVersion.value > before)
    }

    @Test
    fun `SEC-161 an empty name cannot be saved`() = runDesktopComposeUiTest {
        mount()
        onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performScrollTo().performTextClearance()
        onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).assertIsNotEnabled()
        onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextInput("   ")
        onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).assertIsNotEnabled()
        assertEquals("Savings", registry.getWallet(walletId)?.label)
    }

    @Test
    fun `SEC-161 a name used by another wallet is refused`() = runDesktopComposeUiTest {
        mount()
        onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performScrollTo().performTextClearance()
        onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextInput("spending")
        onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).performClick()
        waitUntilAtLeastOneExists(hasText("Another wallet already uses this name."), timeoutMillis = 3_000)
        assertEquals("Savings", registry.getWallet(walletId)?.label)
    }

    @Test
    fun `SEC-164 GAP-024 the second tap removes the wallet and forgets its exclusion`() = runDesktopComposeUiTest {
        SettingsStore.setWalletIncluded(walletId, false)
        SettingsStore.setWalletIncluded("other-wallet", false)
        var removed = 0
        mount(onRemoved = { removed++ })
        onNodeWithTag(SettingsTags.DELETE_THIS_WALLET).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasText("Tap again", substring = true))
        assertEquals(0, removed)
        onNodeWithTag(SettingsTags.DELETE_THIS_WALLET).performScrollTo().performClick()
        waitUntil(timeoutMillis = 3_000) { removed == 1 }
        assertNull(registry.getWallet(walletId))
        assertNull(WalletStore.current())
        assertFalse(File(tmp, "${WalletPaths.WATCH_STATE_SUBDIR}/$walletId.sqlite").exists())
        assertEquals(setOf("other-wallet"), SettingsStore.excludedWalletIds.value)
        assertNotNull(registry.listWallets().singleOrNull { it.label == "Spending" })
    }

    @Test
    fun `SEC-163 the clipboard is cleared after the delay only if it still holds the value`() {
        val clipboard = java.awt.datatransfer.Clipboard("sec-163")
        fun text() = clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as String
        SensitiveClipboard.copy("xpub-secret", autoClearMs = 100, clipboard = clipboard)
        assertEquals("xpub-secret", text())
        waitFor { text() == "" }

        SensitiveClipboard.copy("xpub-secret", autoClearMs = 100, clipboard = clipboard)
        clipboard.setContents(java.awt.datatransfer.StringSelection("something the user copied"), null)
        Thread.sleep(400)
        assertEquals("something the user copied", text(), "unrelated content is never clobbered")
        assertEquals(60_000L, SensitiveClipboard.AUTO_CLEAR_DELAY_MS)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private companion object {
        const val TPUB =
            "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"
        const val ADDRESS = "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
    }
}
