package io.bhodl.desktop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * App shell: module-gated rail, section mapping, top bar back arrow,
 * footer status pills and accessible names (docs/use-cases.md SEC-131,
 * SEC-143, SEC-148, SEC-153, SEC-154, SEC-160).
 */
@OptIn(ExperimentalTestApi::class)
class UcShellSecurityTest {

    @BeforeTest
    fun setUp() = SettingsStore.reset()

    @AfterTest
    fun tearDown() = SettingsStore.reset()

    private fun androidx.compose.ui.test.ComposeUiTest.pill(tag: String, label: String) =
        onNode(androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag)) and androidx.compose.ui.test.hasText(label), useUnmergedTree = true)
            .assertExists()

    private fun androidx.compose.ui.test.ComposeUiTest.count(tag: String) =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size

    @Test
    fun `SEC-143 module-gated rail entries appear only with their module`() = runDesktopComposeUiTest {
        val selected = mutableListOf<Tab>()
        setContent { BhodlTheme { TwoTabShell(selected = Tab.Home, onTabSelected = { selected += it }) { Text("body") } } }
        waitUntilAtLeastOneExists(hasTestTag(TwoTabShellTags.RAIL))
        listOf(TwoTabShellTags.TAB_HOME, TwoTabShellTags.TAB_TRANSACTIONS, TwoTabShellTags.TAB_WALLET, TwoTabShellTags.TAB_SETTINGS)
            .forEach { assertEquals(1, count(it), it) }
        listOf(TwoTabShellTags.TAB_BUDGET, TwoTabShellTags.TAB_GOALS, TwoTabShellTags.TAB_PORTFOLIO, TwoTabShellTags.TAB_REPORTS)
            .forEach { assertEquals(0, count(it), it) }

        SettingsStore.setModuleBudgetBills(true)
        waitForIdle()
        assertEquals(1, count(TwoTabShellTags.TAB_BUDGET))
        assertEquals(1, count(TwoTabShellTags.TAB_REPORTS), "any finance module unlocks Reports")
        assertEquals(0, count(TwoTabShellTags.TAB_GOALS))
        assertEquals(0, count(TwoTabShellTags.TAB_PORTFOLIO))

        SettingsStore.setModuleSavingsGoals(true)
        SettingsStore.setModuleManualAssets(true)
        waitForIdle()
        assertEquals(1, count(TwoTabShellTags.TAB_GOALS))
        assertEquals(1, count(TwoTabShellTags.TAB_PORTFOLIO), "manual assets alone unlock Portfolio")

        onNodeWithTag(TwoTabShellTags.TAB_SETTINGS).performClick()
        onNodeWithTag(TwoTabShellTags.TAB_BUDGET).performClick()
        assertEquals(listOf(Tab.Settings, Tab.Budget), selected)
    }

    @Test
    fun `SEC-143 Analytics Labs alone unlocks only Reports`() = runDesktopComposeUiTest {
        SettingsStore.setModuleAnalyticsLabs(true)
        setContent { BhodlTheme { TwoTabShell(selected = Tab.Home, onTabSelected = {}) { Text("body") } } }
        waitUntilAtLeastOneExists(hasTestTag(TwoTabShellTags.RAIL))
        assertEquals(1, count(TwoTabShellTags.TAB_REPORTS))
        assertEquals(0, count(TwoTabShellTags.TAB_BUDGET))
        assertEquals(0, count(TwoTabShellTags.TAB_PORTFOLIO))
    }

    @Test
    fun `SEC-148 sections and rail entries map both ways, unknown wire falls back to HOME`() {
        TrackerSection.entries.forEach { s ->
            assertEquals(s, TrackerSection.fromWire(s.wire))
            assertEquals(s, Tab.fromSection(s).toSection())
        }
        assertEquals(TrackerSection.HOME, TrackerSection.fromWire(null))
        assertEquals(TrackerSection.HOME, TrackerSection.fromWire("analytics"))
        assertEquals(TrackerSection.HOME, TrackerSection.fromWire("HOME"))
        assertNull(Tab.Wallet.toSection())
        assertNull(Tab.Settings.toSection())
        assertEquals(TrackerSection.entries.size, TrackerSection.entries.map { it.wire }.toSet().size)
    }

    @Test
    fun `SEC-153 the top bar back arrow is present only with a back action and fires it`() = runDesktopComposeUiTest {
        var backs = 0
        setContent {
            BhodlTheme {
                androidx.compose.foundation.layout.Column {
                    BhodlTopBar(title = "With back", onBack = { backs++ })
                    BhodlTopBar(title = "Without back")
                }
            }
        }
        waitForIdle()
        assertEquals(1, count(BhodlBackArrowTags.ROOT))
        onNodeWithTag(BhodlBackArrowTags.ROOT).performClick()
        assertEquals(1, backs)
    }

    @Test
    fun `SEC-154 the footer shows network, connection, wallet count and last sync`() = runDesktopComposeUiTest {
        var network by androidx.compose.runtime.mutableStateOf(Network.MAINNET)
        setContent { BhodlTheme { BhodlFooterStatusBar(network = network, walletCount = 3, lastSyncUnix = null) } }
        waitUntilAtLeastOneExists(hasTestTag(FooterStatusBarTags.ROOT))
        pill(FooterStatusBarTags.NETWORK, "mainnet")
        pill(FooterStatusBarTags.CONNECTION, "Clearnet")
        onNodeWithTag(FooterStatusBarTags.WALLET_COUNT).assertTextContains("3 wallets")
        onNodeWithTag(FooterStatusBarTags.SYNC).assertTextContains("—", substring = true)
        assertEquals(0, count(FooterStatusBarTags.SECURITY_HUB), "no shield without a host action")

        SettingsStore.setTorEnabled(true)
        network = Network.SIGNET
        waitForIdle()
        pill(FooterStatusBarTags.NETWORK, "signet")
        pill(FooterStatusBarTags.CONNECTION, "Tor")
    }

    @Test
    fun `the tracker header warns about mainnet over clearnet and shows the last sync`() = runDesktopComposeUiTest {
        var mainnet by androidx.compose.runtime.mutableStateOf(true)
        var showSync by androidx.compose.runtime.mutableStateOf(true)
        setContent { BhodlTheme { TrackerHeaderStatus(mainnetWallets = mainnet, lastSyncUnix = null, showSync = showSync) } }
        waitForIdle()
        assertEquals(1, count(FooterStatusBarTags.HEADER_CLEARNET), "mainnet without Tor: warned")
        assertEquals(1, count(FooterStatusBarTags.HEADER_SYNC))
        SettingsStore.setTorEnabled(true)
        waitForIdle()
        assertEquals(0, count(FooterStatusBarTags.HEADER_CLEARNET), "Tor on: no warning")
        SettingsStore.setTorEnabled(false)
        mainnet = false
        showSync = false
        waitForIdle()
        assertEquals(0, count(FooterStatusBarTags.HEADER_CLEARNET), "signet only: no warning")
        assertEquals(0, count(FooterStatusBarTags.HEADER_SYNC))
    }

    @Test
    fun `SEC-154 one wallet is singular`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { BhodlFooterStatusBar(network = Network.TESTNET4, walletCount = 1, lastSyncUnix = 0L) } }
        waitUntilAtLeastOneExists(hasTestTag(FooterStatusBarTags.ROOT))
        onNodeWithTag(FooterStatusBarTags.WALLET_COUNT).assertTextContains("1 wallet")
        pill(FooterStatusBarTags.NETWORK, "testnet")
    }

    @Test
    fun `SEC-131 SEC-160 the footer shield and eye have accessible names and actions`() = runDesktopComposeUiTest {
        var opened = 0
        setContent {
            BhodlTheme {
                BhodlFooterStatusBar(network = Network.SIGNET, walletCount = 0, lastSyncUnix = null, onOpenSecurityHub = { opened++ })
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(FooterStatusBarTags.SECURITY_HUB))
        onNode(hasContentDescription("Open Security Hub"), useUnmergedTree = true).assertExists()
        onNode(hasContentDescription("Hide amounts"), useUnmergedTree = true).assertExists()
        onNodeWithTag(FooterStatusBarTags.SECURITY_HUB).performClick()
        assertEquals(1, opened)

        SettingsStore.setBalancesHidden(true)
        waitForIdle()
        onNode(hasContentDescription("Show amounts"), useUnmergedTree = true).assertExists()
        assertTrue(
            onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription), useUnmergedTree = true)
                .fetchSemanticsNodes().size >= 2,
        )
    }
}
