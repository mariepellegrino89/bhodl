package io.bhodl.desktop.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** UX review 2026-10 (item 14) — Security Hub rows, score, reminders, persistence. */
@OptIn(ExperimentalTestApi::class)
class SecurityHubTest {

    @BeforeTest fun setup() = SettingsStore.reset()

    @AfterTest fun teardown() = SettingsStore.reset()

    private fun facts(
        wallet: String? = "w1",
        verified: Long? = null,
        exported: Long? = null,
        labels: Long? = null,
        devicePin: Boolean = true,
        tor: Boolean = false,
        network: Network = Network.MAINNET,
    ) = SecurityFacts(wallet, verified, exported, labels, devicePin, tor, network, 1, 2)

    private fun status(f: SecurityFacts, c: SecurityCheck) = securityChecks(f).first { it.check == c }.status

    @Test
    fun `unverified backup and clearnet mainnet need attention`() {
        val f = facts()
        assertEquals(SecurityStatus.ATTENTION, status(f, SecurityCheck.BACKUP))
        assertEquals(SecurityStatus.ATTENTION, status(f, SecurityCheck.NETWORK))
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.PIN))
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.AUTO_LOCK))
        // OK: pin + auto-lock; counted: + backup + network.
        assertEquals(2 to 4, securityScore(securityChecks(f)))
    }

    @Test
    fun `verified backup and Tor make every counted check OK`() {
        val f = facts(verified = 1_700_000_000L, exported = 1L, labels = 2L, tor = true)
        val (ok, counted) = securityScore(securityChecks(f))
        assertEquals(counted, ok)
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.LABELS))
        assertEquals(SecurityStatus.OK, status(f, SecurityCheck.ENCRYPTED_BACKUP))
    }

    @Test
    fun `clearnet on signet is only informative and no wallet makes backup informative`() {
        val f = facts(wallet = null, network = Network.SIGNET)
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.NETWORK))
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.BACKUP))
        assertEquals(SecurityStatus.INFO, status(f, SecurityCheck.LABELS))
    }

    @Test
    fun `reminders are spaced and never shown during a send`() {
        val now = 1_800_000_000L
        assertTrue(SecurityReminderPolicy.shouldShow("w", false, inSendFlow = false, nowUnix = now, snoozedUntilUnix = 0L))
        assertFalse(SecurityReminderPolicy.shouldShow("w", false, inSendFlow = true, nowUnix = now, snoozedUntilUnix = 0L))
        assertFalse(SecurityReminderPolicy.shouldShow("w", true, inSendFlow = false, nowUnix = now, snoozedUntilUnix = 0L))
        assertFalse(SecurityReminderPolicy.shouldShow(null, false, inSendFlow = false, nowUnix = now, snoozedUntilUnix = 0L))
        assertFalse(SecurityReminderPolicy.shouldShow("w", false, inSendFlow = false, nowUnix = now, snoozedUntilUnix = now + 1))
        assertEquals(listOf(1L, 3L, 7L, 30L, 30L), (0..4).map { SecurityReminderPolicy.snoozeSeconds(it) / 86_400L })
    }

    @Test
    fun `hub renders rows and routes the backup action`() = runDesktopComposeUiTest {
        var verifyClicks = 0
        setContent {
            BhodlTheme {
                SecurityHubScreen(
                    onBack = {},
                    onVerifyBackup = { verifyClicks++ },
                    onOpenWalletSettings = {},
                    onOpenSettings = {},
                    facts = facts(labels = 1_700_000_000L),
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SecurityHubTags.ROOT))
        SecurityCheck.entries.forEach { onNodeWithTag(SecurityHubTags.row(it)).assertExists() }
        onNodeWithTag(SecurityHubTags.SCORE).assertTextContains("3 of 5", substring = true)
        onNodeWithTag(SecurityHubTags.value(SecurityCheck.LABELS)).assertTextContains(formatSecurityDate(1_700_000_000L), substring = true)
        onNodeWithTag(SecurityHubTags.action(SecurityCheck.BACKUP)).performClick()
        assertEquals(1, verifyClicks)
    }

    @Test
    fun `reminder banner hides on the send tab and snoozes`() = runDesktopComposeUiTest {
        io.bhodl.desktop.wizard.WalletStore.set(
            io.bhodl.desktop.wallet.FakeWalletPort(),
            Network.SIGNET,
            walletId = "w-reminder",
        )
        var inSend by androidx.compose.runtime.mutableStateOf(true)
        try {
            setContent { BhodlTheme { SecurityReminderBanner(inSendFlow = inSend, onOpenHub = {}, nowUnix = 1_000L) } }
            waitForIdle()
            onNodeWithTag(SecurityHubTags.REMINDER).assertDoesNotExist()
            inSend = false
            waitUntilAtLeastOneExists(hasTestTag(SecurityHubTags.REMINDER))
            onNodeWithTag(SecurityHubTags.REMINDER_LATER).performClick()
            waitForIdle()
            onNodeWithTag(SecurityHubTags.REMINDER).assertDoesNotExist()
            assertEquals(1_000L + 86_400L, SettingsStore.securityReminderSnoozedUntil.value["w-reminder"])
        } finally {
            io.bhodl.desktop.wizard.WalletStore.clear()
        }
    }

    @Test
    fun `security timestamps round-trip through settings json`() {
        val dir = Files.createTempDirectory("sec-hub").toFile()
        val file = File(dir, "settings.json")
        SettingsStore.markBackupVerified("w1", 100L)
        SettingsStore.markBackupExported("w1", 200L)
        SettingsStore.markLabelsExported("w1", 300L)
        SettingsStore.snoozeSecurityReminder("w1", 400L)
        SettingsStore.markOnboardingUnitsSeen()
        SettingsPersistence.save(file).getOrThrow()
        val text = file.readText()
        assertFalse("mnemonic" in text.lowercase(), "only dates may be stored")
        SettingsStore.reset()
        SettingsPersistence.rehydrate(file)
        assertEquals(100L, SettingsStore.backupVerifiedAt.value["w1"])
        assertEquals(200L, SettingsStore.backupExportedAt.value["w1"])
        assertEquals(300L, SettingsStore.labelsExportedAt.value["w1"])
        assertEquals(400L, SettingsStore.securityReminderSnoozedUntil.value["w1"])
        assertEquals(1, SettingsStore.securityReminderDismissCount.value["w1"])
        assertTrue(SettingsStore.onboardingUnitsSeen.value)
        dir.deleteRecursively()
    }

    @Test
    fun `snoozing one wallet's reminder leaves the others alone`() {
        SettingsStore.snoozeSecurityReminder("w1", 500L)
        SettingsStore.snoozeSecurityReminder("w1", 900L)
        assertEquals(900L, SettingsStore.securityReminderSnoozedUntil.value["w1"])
        assertEquals(2, SettingsStore.securityReminderDismissCount.value["w1"])
        assertEquals(null, SettingsStore.securityReminderSnoozedUntil.value["w2"])
        assertTrue(
            SecurityReminderPolicy.shouldShow(
                "w2", backupVerified = false, inSendFlow = false, nowUnix = 600L,
                snoozedUntilUnix = SettingsStore.securityReminderSnoozedUntil.value["w2"] ?: 0L,
            ),
        )
    }

    @Test
    fun `a wallet that finishes setup, created or restored, counts as verified`() {
        io.bhodl.desktop.recordSetupBackupVerified("restored-wallet", nowUnix = 1_234L)
        assertEquals(1_234L, SettingsStore.backupVerifiedAt.value["restored-wallet"])
        io.bhodl.desktop.recordSetupBackupVerified(null)
        assertEquals(1, SettingsStore.backupVerifiedAt.value.size)
    }

    @Test
    fun `the encrypted backup date is recorded only once the file is written`() {
        val dir = Files.createTempDirectory("sec-backup").toFile()
        try {
            // A directory cannot be written as a file: no date recorded.
            assertTrue(writeBackupBlob(dir, "age-blob", "w1", nowUnix = 10L).isFailure)
            assertEquals(null, SettingsStore.backupExportedAt.value["w1"])
            val target = File(dir, "backup.txt")
            assertTrue(writeBackupBlob(target, "age-blob", "w1", nowUnix = 20L).isSuccess)
            assertEquals("age-blob", target.readText())
            assertEquals(20L, SettingsStore.backupExportedAt.value["w1"])
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `local wipe forgets the Security Hub dates and snoozes`() {
        val dir = Files.createTempDirectory("sec-wipe").toFile()
        try {
            SettingsStore.markBackupVerified("w1", 1L)
            SettingsStore.markBackupExported("w1", 2L)
            SettingsStore.markLabelsExported("w1", 3L)
            SettingsStore.snoozeSecurityReminder("w1", 4L)
            val result = io.bhodl.desktop.wizard.LocalWipe.wipeAll(dir)
            assertFalse(result.aborted)
            assertTrue(SettingsStore.backupVerifiedAt.value.isEmpty())
            assertTrue(SettingsStore.backupExportedAt.value.isEmpty())
            assertTrue(SettingsStore.labelsExportedAt.value.isEmpty())
            assertTrue(SettingsStore.securityReminderSnoozedUntil.value.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
