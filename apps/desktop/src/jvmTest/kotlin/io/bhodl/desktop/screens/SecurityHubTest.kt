package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsPersistence
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** UX review 2026-10 (item 14) — Security Hub rows, score, persistence. */
@OptIn(ExperimentalTestApi::class)
class SecurityHubTest {

    @BeforeTest fun setup() = SettingsStore.reset()

    @AfterTest fun teardown() = SettingsStore.reset()

    private fun facts(
        wallet: String? = "w1",
        labels: Long? = null,
        devicePin: Boolean = true,
        tor: Boolean = false,
        network: Network = Network.MAINNET,
        watchWallets: Int = 2,
        recovery: Boolean = true,
    ) = SecurityFacts(wallet, labels, devicePin, tor, network, watchWallets, recovery)

    private fun status(f: SecurityFacts, c: SecurityCheck) = securityChecks(f).first { it.check == c }.status

    @Test
    fun `clearnet mainnet needs attention`() {
        val f = facts()
        assertEquals(SecurityStatus.ATTENTION, status(f, SecurityCheck.NETWORK))
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.PIN))
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.AUTO_LOCK))
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.WALLETS))
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.LABELS))
        // OK: pin + recovery code + auto-lock; counted: + network.
        assertEquals(3 to 4, securityScore(securityChecks(f)))
    }

    @Test
    fun `exported labels and Tor make every counted check OK`() {
        val f = facts(labels = 2L, tor = true)
        val (ok, counted) = securityScore(securityChecks(f))
        assertEquals(counted, ok)
        assertEquals(5, counted)
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.LABELS))
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.NETWORK))
    }

    @Test
    fun `clearnet on signet is only informative and no wallet makes labels informative`() {
        val f = facts(wallet = null, labels = 5L, network = Network.SIGNET)
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.NETWORK))
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.LABELS))
    }

    @Test
    fun `missing device PIN is informative, not counted`() {
        val f = facts(devicePin = false)
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.PIN))
        assertEquals(1 to 2, securityScore(securityChecks(f)))
    }

    @Test
    fun `there is one row per check, in display order`() {
        assertEquals(
            listOf(
                SecurityCheck.PIN,
                SecurityCheck.RECOVERY,
                SecurityCheck.AUTO_LOCK,
                SecurityCheck.NETWORK,
                SecurityCheck.WALLETS,
                SecurityCheck.LABELS,
            ),
            securityChecks(facts()).map { it.check },
        )
    }

    @Test
    fun `hub renders rows and routes the actions`() = runDesktopComposeUiTest {
        var walletsClicks = 0
        var walletSettingsClicks = 0
        var settingsClicks = 0
        setContent {
            BhodlTheme {
                SecurityHubScreen(
                    onBack = {},
                    onOpenWallets = { walletsClicks++ },
                    onOpenSettings = { settingsClicks++ },
                    onOpenWalletSettings = { walletSettingsClicks++ },
                    facts = facts(labels = 1_700_000_000L, watchWallets = 3),
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SecurityHubTags.ROOT))
        SecurityCheck.entries.forEach { onNodeWithTag(SecurityHubTags.row(it)).assertExists() }
        onNodeWithTag(SecurityHubTags.SCORE).assertTextContains("4 of 5", substring = true)
        onNodeWithTag(SecurityHubTags.value(SecurityCheck.LABELS))
            .assertTextContains(formatSecurityDate(1_700_000_000L), substring = true)
        onNodeWithTag(SecurityHubTags.value(SecurityCheck.WALLETS)).assertTextContains("3 watch-only", substring = true)
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.WALLETS)).performClick()
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.LABELS)).performClick()
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.NETWORK)).performClick()
        assertEquals(1, walletsClicks)
        assertEquals(1, walletSettingsClicks)
        assertEquals(1, settingsClicks)
    }

    @Test
    fun `labels row has no action without an open wallet`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SecurityHubScreen(
                    onBack = {},
                    onOpenWallets = {},
                    onOpenSettings = {},
                    onOpenWalletSettings = {},
                    facts = facts(wallet = null),
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SecurityHubTags.ROOT))
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.LABELS)).assertDoesNotExist()
        onNodeWithTag(SecurityHubTags.value(SecurityCheck.LABELS))
            .assertTextContains("Open a wallet", substring = true)
    }

    @Test
    fun `labels export timestamps round-trip through settings json`() {
        val dir = Files.createTempDirectory("sec-hub").toFile()
        val file = File(dir, "settings.json")
        SettingsStore.markLabelsExported("w1", 300L)
        SettingsStore.markOnboardingUnitsSeen()
        SettingsPersistence.save(file).getOrThrow()
        val text = file.readText()
        assertFalse("mnemonic" in text.lowercase(), "only dates may be stored")
        SettingsStore.reset()
        SettingsPersistence.rehydrate(file)
        assertEquals(300L, SettingsStore.labelsExportedAt.value["w1"])
        assertTrue(SettingsStore.onboardingUnitsSeen.value)
        dir.deleteRecursively()
    }

    @Test
    fun `local wipe forgets the Security Hub dates`() {
        val dir = Files.createTempDirectory("sec-wipe").toFile()
        try {
            SettingsStore.markLabelsExported("w1", 3L)
            val result = io.bhodl.desktop.wizard.LocalWipe.wipeAll(dir)
            assertFalse(result.aborted)
            assertTrue(SettingsStore.labelsExportedAt.value.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
