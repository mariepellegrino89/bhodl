package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.OpenWalletRoute
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlBackArrowTags
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Security Hub rows, command palette, back arrows on full-screen pages
 * and the open-wallet error path (docs/use-cases.md SEC-108, SEC-133,
 * SEC-134, SEC-151, SEC-153).
 */
@OptIn(ExperimentalTestApi::class)
class UcHubPaletteSecurityTest {

    @BeforeTest
    fun setUp() = SettingsStore.reset()

    @AfterTest
    fun tearDown() {
        SettingsStore.reset()
        WalletStore.clear()
        RegistryStore.clear()
    }

    private fun facts(tor: Boolean, network: Network, pin: Boolean = true, wallets: Int = 2) = SecurityFacts(
        activeWalletId = null,
        labelsExportedAtUnix = null,
        devicePinSet = pin,
        torEnabled = tor,
        network = network,
        watchWallets = wallets,
        recoveryCodeSet = true,
    )

    private fun androidx.compose.ui.test.ComposeUiTest.hub(f: SecurityFacts) {
        setContent {
            BhodlTheme {
                SecurityHubScreen(onBack = {}, onOpenWallets = {}, onOpenSettings = {}, facts = f)
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SecurityHubTags.ROOT))
    }

    @Test
    fun `SEC-133 the wallets row says watch-only and that BHODL holds no private keys`() = runDesktopComposeUiTest {
        hub(facts(tor = true, network = Network.SIGNET, wallets = 3))
        onNodeWithTag(SecurityHubTags.value(SecurityCheck.WALLETS))
            .assertTextEquals("3 watch-only (BHODL never holds private keys)")
    }

    @Test
    fun `SEC-134 every status is spelled out next to its icon`() = runDesktopComposeUiTest {
        hub(facts(tor = false, network = Network.MAINNET, pin = false))
        onNodeWithTag(SecurityHubTags.status(SecurityCheck.AUTO_LOCK)).assertTextEquals("OK")
        onNodeWithTag(SecurityHubTags.status(SecurityCheck.NETWORK)).assertTextEquals("Attention")
        onNodeWithTag(SecurityHubTags.status(SecurityCheck.PIN)).assertTextEquals("Info")
        onNodeWithTag(SecurityHubTags.status(SecurityCheck.WALLETS)).assertTextEquals("Info")
    }

    @Test
    fun `SEC-153 the Security Hub and Modules pages have the back arrow`() = runDesktopComposeUiTest {
        var backs = 0
        setContent {
            BhodlTheme {
                androidx.compose.foundation.layout.Row {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                        SecurityHubScreen(
                            onBack = { backs++ },
                            onOpenWallets = {},
                            onOpenSettings = {},
                            facts = facts(tor = true, network = Network.SIGNET),
                        )
                    }
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f)) {
                        ModulesScreen(onBack = { backs += 10 })
                    }
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(BhodlBackArrowTags.ROOT))
        val arrows = onAllNodes(hasTestTag(BhodlBackArrowTags.ROOT))
        assertEquals(2, arrows.fetchSemanticsNodes().size)
        arrows[0].performClick()
        arrows[1].performClick()
        assertEquals(11, backs)
    }

    private val ran = mutableListOf<String>()
    private val actions = listOf(
        CommandAction(id = "a", label = "Open settings", keywords = listOf("preferences", "impostazioni")) { ran += "a" },
        CommandAction(id = "b", label = "Add wallet", keywords = listOf("xpub")) { ran += "b" },
    )

    @Test
    fun `SEC-151 the palette filters on label and keyword and runs the action`() = runDesktopComposeUiTest {
        var dismissed = 0
        setContent { BhodlTheme { CommandPalette(actions = actions, onDismiss = { dismissed++ }) } }
        waitUntilAtLeastOneExists(hasTestTag(CommandPaletteTags.INPUT))
        onNodeWithText("Open settings").assertExists()
        onNodeWithText("Add wallet").assertExists()

        onNodeWithTag(CommandPaletteTags.INPUT).performTextInput("impost")
        waitForIdle()
        onNodeWithText("Open settings").assertExists()
        assertEquals(0, onAllNodesWithText("Add wallet").fetchSemanticsNodes().size)

        onNodeWithText("Open settings").performClick()
        assertEquals(listOf("a"), ran)
        assertEquals(1, dismissed)
    }

    @Test
    fun `SEC-151 no match shows a message`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { CommandPalette(actions = actions, onDismiss = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(CommandPaletteTags.INPUT))
        onNodeWithTag(CommandPaletteTags.INPUT).performTextInput("zzz")
        waitUntilAtLeastOneExists(hasText("No command matches “zzz”."))
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `SEC-108 opening an unknown wallet shows an error and Back`() = runDesktopComposeUiTest {
        val tmp = kotlin.io.path.createTempDirectory("bhodl-uc-open-").toFile()
        try {
            RegistryStore.set(RegistryHandle.createOrLoad(tmp.absolutePath, "482916"))
            var opened = 0
            var backs = 0
            setContent {
                BhodlTheme {
                    OpenWalletRoute(
                        walletId = "00000000-0000-4000-8000-00000000dead",
                        onOpened = { opened++ },
                        onBack = { backs++ },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("not found", substring = true), timeoutMillis = 3_000)
            onNodeWithText("Back").performClick()
            assertEquals(1, backs)
            assertEquals(0, opened)
            assertNull(WalletStore.current())
        } finally {
            RegistryStore.clear()
            File(tmp.path).deleteRecursively()
        }
    }

    @Test
    fun `SEC-108 opening a wallet without an unlocked registry shows an error`() = runDesktopComposeUiTest {
        RegistryStore.clear()
        var opened = 0
        setContent { BhodlTheme { OpenWalletRoute(walletId = "x", onOpened = { opened++ }, onBack = {}) } }
        waitUntilAtLeastOneExists(hasText("Back"), timeoutMillis = 3_000)
        assertEquals(0, opened)
    }
}
