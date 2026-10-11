package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.settings.ThemeMode
import androidx.compose.runtime.CompositionLocalProvider
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.ui.theme.BhodlDensity
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Compose UI tests for [`SettingsScreen`].
 *
 * The screen is intentionally thin — its only job is to translate
 * chip taps into [`SettingsStore`] writes — so the tests focus on
 * exactly that contract.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsScreenTest {

    @BeforeTest
    fun reset() {
        SettingsStore.reset()
    }

    @AfterTest
    fun cleanup() {
        SettingsStore.reset()
        WalletStore.clear()
        PinAttemptTracker.reset()
    }

    /** Audit 2026-10 — fake wallet-PIN check: "123456" is correct. */
    private val fakePinCheck = object : WalletPinCheck {
        override fun scope(): String = "test-wallet"
        override fun check(pin: String): Boolean = pin == "123456"
    }

    @Test
    fun `the three theme chips render`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.themeChip(ThemeMode.System)))
        onNodeWithTag(SettingsTags.themeChip(ThemeMode.Dark)).assertIsDisplayed()
        onNodeWithTag(SettingsTags.themeChip(ThemeMode.Light)).assertIsDisplayed()
        onNodeWithTag(SettingsTags.themeChip(ThemeMode.System)).assertIsDisplayed()
    }

    @Test
    fun `tapping Dark writes ThemeMode_Dark to SettingsStore`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.themeChip(ThemeMode.Dark)))
        onNodeWithTag(SettingsTags.themeChip(ThemeMode.Dark)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) {
            SettingsStore.themeMode.value == ThemeMode.Dark
        }
        assertEquals(ThemeMode.Dark, SettingsStore.themeMode.value)
    }

    @Test
    fun `tapping Light writes ThemeMode_Light`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.themeChip(ThemeMode.Light)))
        onNodeWithTag(SettingsTags.themeChip(ThemeMode.Light)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) {
            SettingsStore.themeMode.value == ThemeMode.Light
        }
        assertEquals(ThemeMode.Light, SettingsStore.themeMode.value)
    }

    @Test
    fun `Esplora field shows the default URL in the active line when no override is set`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    SettingsScreen(onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.ESPLORA_URL_ACTIVE))
            waitUntilAtLeastOneExists(hasText(EsploraEndpoint.DEFAULT_SIGNET, substring = true))
            assertNull(SettingsStore.esploraUrl.value)
        }

    @Test
    fun `typing a URL and tapping Save persists it as override`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.ESPLORA_URL_FIELD))
        onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD)
            .performScrollTo().performTextInput("https://my.onion/api")
        onNodeWithTag(SettingsTags.ESPLORA_URL_SAVE).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) {
            SettingsStore.esploraUrl.value == "https://my.onion/api"
        }
        assertEquals("https://my.onion/api", SettingsStore.esploraUrl.value)
        assertEquals("https://my.onion/api", EsploraEndpoint.current())
    }

    @Test
    fun `Reset wipes the override back to null`() = runDesktopComposeUiTest {
        SettingsStore.setEsploraUrl("https://my.onion/api")
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.ESPLORA_URL_RESET))
        onNodeWithTag(SettingsTags.ESPLORA_URL_RESET).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) { SettingsStore.esploraUrl.value == null }
        assertNull(SettingsStore.esploraUrl.value)
        assertEquals(EsploraEndpoint.DEFAULT_SIGNET, EsploraEndpoint.current())
    }

    @Test
    fun `Esplora section names its network and edits only that network`() =
        runDesktopComposeUiTest {
            WalletStore.set(FakeWalletPort(), network = Network.MAINNET)
            SettingsStore.setEsploraUrl(Network.SIGNET, "https://signet.example/api")
            setContent {
                BhodlTheme {
                    SettingsScreen(onBack = {})
                }
            }
            // The helper line and the field label name the network.
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.ESPLORA_NETWORK))
            onNodeWithTag(SettingsTags.ESPLORA_NETWORK)
                .assertTextContains("Mainnet", substring = true)
            // Locale-independent: both the EN and IT label contain the name.
            onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD)
                .assertTextContains("Mainnet", substring = true)
            // The signet override is not what the mainnet section shows.
            waitUntilAtLeastOneExists(hasText(EsploraEndpoint.DEFAULT_MAINNET, substring = true))

            onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD)
                .performScrollTo().performTextInput("https://mainnet.example/api")
            onNodeWithTag(SettingsTags.ESPLORA_URL_SAVE).performScrollTo().performClick()
            waitUntil(timeoutMillis = 1_000) {
                SettingsStore.esploraUrlFor(Network.MAINNET) == "https://mainnet.example/api"
            }
            assertEquals("https://signet.example/api", SettingsStore.esploraUrlFor(Network.SIGNET))
            assertNull(SettingsStore.esploraUrlFor(Network.TESTNET4))

            onNodeWithTag(SettingsTags.ESPLORA_URL_RESET).performScrollTo().performClick()
            waitUntil(timeoutMillis = 1_000) { SettingsStore.esploraUrlFor(Network.MAINNET) == null }
            assertEquals("https://signet.example/api", SettingsStore.esploraUrlFor(Network.SIGNET))
            assertEquals(EsploraEndpoint.DEFAULT_MAINNET, EsploraEndpoint.current(Network.MAINNET))
        }

    @Test
    fun `language picker writes the right tag to SettingsStore`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LANGUAGE_IT))
        onNodeWithTag(SettingsTags.LANGUAGE_IT).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) { SettingsStore.localeOverride.value == "it" }
        assertEquals("it", SettingsStore.localeOverride.value)

        onNodeWithTag(SettingsTags.LANGUAGE_EN).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) { SettingsStore.localeOverride.value == "en" }
        assertEquals("en", SettingsStore.localeOverride.value)

        onNodeWithTag(SettingsTags.LANGUAGE_SYSTEM).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) { SettingsStore.localeOverride.value == null }
        assertNull(SettingsStore.localeOverride.value)
    }

    @Test
    fun `Tor toggle reveals the proxy field and writes torEnabled=true`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    SettingsScreen(onBack = {})
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.TOR_TOGGLE))
            assertEquals(0, onAllNodesWithTag(SettingsTags.TOR_PROXY_FIELD).fetchSemanticsNodes().size)

            onNodeWithTag(SettingsTags.TOR_TOGGLE).performScrollTo().performClick()
            waitUntil(timeoutMillis = 1_000) { SettingsStore.torEnabled.value }
            assertEquals(true, SettingsStore.torEnabled.value)
            // Proxy field appears once Tor is on.
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.TOR_PROXY_FIELD))
            onNodeWithTag(SettingsTags.TOR_PROXY_FIELD).performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `Export backup is disabled when password is empty`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent {
            BhodlTheme {
                WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.BACKUP_EXPORT))
        onNodeWithTag(SettingsTags.BACKUP_EXPORT).assertIsNotEnabled()
    }

    @Test
    fun `Export backup with a password calls the FFI and shows the blob`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                exportBackupBlobBuilder = {
                    "-----BEGIN AGE ENCRYPTED FILE-----\nfaked-blob-content\n" +
                        "-----END AGE ENCRYPTED FILE-----\n"
                }
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    CompositionLocalProvider(LocalWalletPinCheck provides fakePinCheck) {
                        WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {})
                    }
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.BACKUP_PASSWORD))
            onNodeWithTag(SettingsTags.BACKUP_PASSWORD).performScrollTo().performTextInput("backup-password-long")
            // Audit 2026-10 — confirmation required.
            onNodeWithTag(SettingsTags.BACKUP_EXPORT).performScrollTo().assertIsNotEnabled()
            onNodeWithTag(SettingsTags.BACKUP_PASSWORD_CONFIRM).performScrollTo()
                .performScrollTo().performTextInput("backup-password-long")
            onNodeWithTag(SettingsTags.BACKUP_EXPORT).performScrollTo().performClick()
            // Audit 2026-10 — wallet PIN re-entry before the export.
            waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.PIN))
            assertEquals(0, fake.exportBackupCalls.size)
            onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("123456")
            onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
            waitUntil(timeoutMillis = 2_000) { fake.exportBackupCalls.isNotEmpty() }
            assertEquals(1, fake.exportBackupCalls.size)
            assertEquals("backup-password-long", fake.exportBackupCalls.single())
            // The blob output field exists once the FFI returns. It
            // sits at the bottom of the scroll column, so we scroll
            // to it before asserting visibility.
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.BACKUP_BLOB))
            onNodeWithTag(SettingsTags.BACKUP_BLOB).performScrollTo().assertIsDisplayed()
        }

    @Test
    fun `Show recovery phrase is two-tap and renders the mnemonic`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply {
            peekMnemonicResult = io.bhodl.ffi.MnemonicReveal(
                mnemonic = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu",
                passphrase = "",
            )
        }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                CompositionLocalProvider(LocalWalletPinCheck provides fakePinCheck) {
                    WalletSettingsTab(onWalletWiped = {}, onSwitchSigningWallet = {})
                }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.RECOVERY_REVEAL))
        // First tap arms — does not call FFI yet.
        onNodeWithTag(SettingsTags.RECOVERY_REVEAL).performScrollTo().performClick()
        waitForIdle()
        assertEquals(0, fake.peekMnemonicCalls.size)
        // Second tap asks for the wallet PIN (audit 2026-10).
        onNodeWithTag(SettingsTags.RECOVERY_REVEAL).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.PIN))
        // Wrong PIN: no reveal, error shown.
        onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("000000")
        onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletPinReauthTags.ERROR))
        assertEquals(0, fake.peekMnemonicCalls.size)
        assertEquals(1, PinAttemptTracker.failureCount("test-wallet"))
        // Correct PIN reveals.
        onNodeWithTag(WalletPinReauthTags.PIN).performTextInput("123456")
        onNodeWithTag(WalletPinReauthTags.CONFIRM).performClick()
        waitUntil(timeoutMillis = 2_000) { fake.peekMnemonicCalls.isNotEmpty() }
        assertEquals(1, fake.peekMnemonicCalls.size)
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.RECOVERY_PHRASE))
        onNodeWithTag(SettingsTags.RECOVERY_PHRASE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Wipe is two-tap and clears the WalletStore on confirm`() = runDesktopComposeUiTest {
        // CRITICAL: the wipe button hits `WalletPaths.reset()` against the
        // injected `dataDir` parameter, NOT the production
        // `WalletPaths.defaultDataDir()`. Before that parameter existed,
        // this test wiped the developer's real `~/.bhodl/` wallet on every
        // `:jvmTest` run — a developer's signet wallet was once lost
        // when a test run hit this test.
        // Always pass a fresh tempdir.
        val tempDir = kotlin.io.path.createTempDirectory("bhodl-wipe-test-").toFile()
        try {
            WalletStore.set(FakeWalletPort())
            var wipedCalled = 0
            setContent {
                BhodlTheme {
                    SettingsScreen(
                        onBack = {},
                        onWalletWiped = { wipedCalled++ },
                        dataDir = tempDir,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WIPE))
            // Audit 2026-10 — opens a typed-phrase confirmation dialog.
            onNodeWithTag(SettingsTags.WIPE).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(ConfirmWipeTags.FIELD))
            onNodeWithTag(ConfirmWipeTags.CONFIRM).assertIsNotEnabled()
            assertEquals(0, wipedCalled, "opening the dialog must not wipe")
            assertEquals(true, WalletStore.current() != null)
            onNodeWithTag(ConfirmWipeTags.FIELD).performTextInput("DELETE")
            onNodeWithTag(ConfirmWipeTags.CONFIRM).performClick()
            waitUntil(timeoutMillis = 2_000) { wipedCalled == 1 }
            assertEquals(1, wipedCalled)
            assertEquals(null, WalletStore.current())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `back button fires onBack`() = runDesktopComposeUiTest {
        var backCalled = 0
        setContent {
            BhodlTheme {
                SettingsScreen(onBack = { backCalled++ })
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.BACK))
        // Settings has grown enough that Back is below the fold —
        // performScrollTo brings it into view before the click.
        onNodeWithTag(SettingsTags.BACK).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) { backCalled == 1 }
        assertEquals(1, backCalled)
    }

    // -- UX review 2026-10 — Settings › Appearance ------------------------

    @Test
    fun `appearance shows System theme selected and sats unit by default`() =
        runDesktopComposeUiTest {
            setContent { BhodlTheme { SettingsScreen(onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.APPEARANCE_SECTION))
            onNodeWithTag(SettingsTags.themeChip(ThemeMode.Neon)).assertIsDisplayed()
            assertEquals(ThemeMode.System, SettingsStore.themeMode.value)
            onNodeWithTag(SettingsTags.UNIT_EXAMPLE).performScrollTo()
                .assertTextContains("4,230,118 sat", substring = true)
        }

    @Test
    fun `unit chips and BIP177 toggle write SettingsStore and update the example`() =
        runDesktopComposeUiTest {
            setContent { BhodlTheme { SettingsScreen(onBack = {}) } }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.unitChip(UnitFormat.ALWAYS_BTC)))
            onNodeWithTag(SettingsTags.unitChip(UnitFormat.ALWAYS_BTC)).performScrollTo().performClick()
            waitUntil(timeoutMillis = 1_000) {
                SettingsStore.unitFormat.value == UnitFormat.ALWAYS_BTC
            }
            onNodeWithTag(SettingsTags.UNIT_EXAMPLE).assertTextContains("0.04230118 BTC", substring = true)

            onNodeWithTag(SettingsTags.unitChip(UnitFormat.ALWAYS_SAT)).performScrollTo().performClick()
            onNodeWithTag(SettingsTags.BIP177_TOGGLE).performScrollTo().performClick()
            waitUntil(timeoutMillis = 1_000) { SettingsStore.bip177Notation.value }
            onNodeWithTag(SettingsTags.UNIT_EXAMPLE).assertTextContains("₿4,230,118", substring = true)
        }

    @Test
    fun `density chips write SettingsStore`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { SettingsScreen(onBack = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.densityChip(BhodlDensity.UltraDense)))
        assertEquals(BhodlDensity.Compact, SettingsStore.density.value)
        onNodeWithTag(SettingsTags.densityChip(BhodlDensity.UltraDense)).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) {
            SettingsStore.density.value == BhodlDensity.UltraDense
        }
    }

    @Test
    fun `wallet-scoped sections moved to Wallet settings, with a pointer here`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        var opened = 0
        setContent { BhodlTheme { SettingsScreen(onBack = {}, onOpenWalletSettings = { opened++ }) } }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WALLET_SETTINGS_POINTER))
        // Backup / recovery phrase are no longer on the app-wide page.
        assertEquals(0, onAllNodesWithTag(SettingsTags.BACKUP_EXPORT).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag(SettingsTags.RECOVERY_REVEAL).fetchSemanticsNodes().size)
        onNodeWithTag(SettingsTags.OPEN_WALLET_SETTINGS).performScrollTo().performClick()
        waitUntil(timeoutMillis = 1_000) { opened == 1 }
    }
}
