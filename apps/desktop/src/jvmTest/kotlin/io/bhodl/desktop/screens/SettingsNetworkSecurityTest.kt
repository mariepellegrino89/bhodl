package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsPersistence
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.SettingsWarningBanner
import io.bhodl.desktop.ui.SettingsWarningBannerTags
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test-book §14b / §40 / §44 / §46 — network-privacy settings through
 * the real Settings UI:
 *  - Esplora URL validation (invalid rejected inline, store untouched);
 *  - plaintext-http warning (remote http only; Tor-on copy differs);
 *  - per-network default endpoint (mainnet wallet ≠ signet default);
 *  - Tor proxy validation + socks5 → socks5h normalisation;
 *  - corrupt settings.json banner (Tor fails closed, Dismiss clears);
 *  - debug-only entry hidden outside a dev build.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsNetworkSecurityTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        tmp = kotlin.io.path.createTempDirectory("bhodl-settings-sec-").toFile()
    }

    @AfterTest
    fun tearDown() {
        SettingsStore.reset()
        WalletStore.clear()
        tmp.deleteRecursively()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.showSettings() {
        setContent { BhodlTheme { SettingsScreen(onBack = {}, dataDir = tmp) } }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.ESPLORA_URL_FIELD))
    }

    @Test
    fun `invalid Esplora URLs are rejected inline and never saved`() = runDesktopComposeUiTest {
        showSettings()
        for (bad in listOf("not a url", "ftp://x.example/api", "https://")) {
            onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD).performScrollTo().performTextReplacement(bad)
            onNodeWithTag(SettingsTags.ESPLORA_URL_SAVE).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.ESPLORA_URL_ERROR))
            assertNull(SettingsStore.esploraUrl.value, "\"$bad\" must not be saved")
        }
        assertEquals(EsploraEndpoint.DEFAULT_SIGNET, EsploraEndpoint.current())

        // A valid URL clears the error and is saved.
        onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD).performScrollTo()
            .performTextReplacement("https://esplora.example.com/api")
        onNodeWithTag(SettingsTags.ESPLORA_URL_SAVE).performScrollTo().performClick()
        waitUntilDoesNotExist(hasTestTag(SettingsTags.ESPLORA_URL_ERROR))
        assertEquals("https://esplora.example.com/api", SettingsStore.esploraUrl.value)
    }

    @Test
    fun `plaintext http warning appears for remote hosts only`() = runDesktopComposeUiTest {
        showSettings()
        assertTrue(onAllNodesWithTag(SettingsTags.ESPLORA_PLAINTEXT_WARNING).fetchSemanticsNodes().isEmpty())

        onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD).performScrollTo()
            .performTextReplacement("http://example.com/api")
        onNodeWithTag(SettingsTags.ESPLORA_URL_SAVE).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.ESPLORA_PLAINTEXT_WARNING))
        onNodeWithTag(SettingsTags.ESPLORA_PLAINTEXT_WARNING)
            .assertTextContains("without Tor", substring = true)

        // Same endpoint with Tor on: the exit relay can still read it.
        SettingsStore.setTorEnabled(true)
        waitUntilAtLeastOneExists(hasText("Tor does not encrypt the last hop", substring = true))

        for (safe in listOf("http://127.0.0.1:3002", "http://abcdefexample.onion/api", "https://example.com/api")) {
            onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD).performScrollTo().performTextReplacement(safe)
            onNodeWithTag(SettingsTags.ESPLORA_URL_SAVE).performScrollTo().performClick()
            waitUntilDoesNotExist(hasTestTag(SettingsTags.ESPLORA_PLAINTEXT_WARNING))
        }
    }

    @Test
    fun `a mainnet wallet shows the mainnet default and its own override only`() =
        runDesktopComposeUiTest {
            SettingsStore.setEsploraUrl(Network.SIGNET, "https://signet-only.example.com/api")
            WalletStore.set(FakeWalletPort(), Network.MAINNET)
            showSettings()
            onNodeWithTag(SettingsTags.NETWORK_VALUE).assertTextEquals("Mainnet")
            waitUntilAtLeastOneExists(hasText(EsploraEndpoint.DEFAULT_MAINNET, substring = true))
            onNodeWithTag(SettingsTags.ESPLORA_URL_ACTIVE)
                .assertTextContains(EsploraEndpoint.DEFAULT_MAINNET, substring = true)
            assertEquals(EsploraEndpoint.DEFAULT_MAINNET, EsploraEndpoint.current())

            onNodeWithTag(SettingsTags.ESPLORA_URL_FIELD).performScrollTo()
                .performTextReplacement("https://main.example.com/api")
            onNodeWithTag(SettingsTags.ESPLORA_URL_SAVE).performScrollTo().performClick()
            waitUntil(timeoutMillis = 1_000) {
                SettingsStore.esploraUrlFor(Network.MAINNET) == "https://main.example.com/api"
            }
            assertEquals(
                "https://signet-only.example.com/api",
                SettingsStore.esploraUrlFor(Network.SIGNET),
                "the signet override is untouched",
            )
        }

    @Test
    fun `Tor proxy rejects invalid URLs and stores socks5 as socks5h`() = runDesktopComposeUiTest {
        showSettings()
        onNodeWithTag(SettingsTags.TOR_TOGGLE).performScrollTo().performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.TOR_PROXY_FIELD))
        val before = SettingsStore.torProxyUrl.value

        for (bad in listOf("socks5://127.0.0.1:99999", "http://127.0.0.1:9050", "socks5://:9050")) {
            onNodeWithTag(SettingsTags.TOR_PROXY_FIELD).performScrollTo().performTextReplacement(bad)
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.TOR_PROXY_ERROR))
            assertEquals(before, SettingsStore.torProxyUrl.value, "\"$bad\" must not be stored")
        }

        onNodeWithTag(SettingsTags.TOR_PROXY_FIELD).performScrollTo()
            .performTextReplacement("socks5://127.0.0.1:9150")
        waitUntil(timeoutMillis = 1_000) {
            SettingsStore.torProxyUrl.value == "socks5h://127.0.0.1:9150"
        }
        waitUntilDoesNotExist(hasTestTag(SettingsTags.TOR_PROXY_ERROR))
        assertEquals("socks5h://127.0.0.1:9150", EsploraEndpoint.currentProxy())
    }

    @Test
    fun `turning Tor off hides the proxy field and drops the proxy`() = runDesktopComposeUiTest {
        SettingsStore.setTorEnabled(true)
        showSettings()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.TOR_PROXY_FIELD))
        assertEquals(SettingsStore.torProxyUrl.value, EsploraEndpoint.currentProxy())
        onNodeWithTag(SettingsTags.TOR_TOGGLE).performScrollTo().performClick()
        waitUntilDoesNotExist(hasTestTag(SettingsTags.TOR_PROXY_FIELD))
        assertFalse(SettingsStore.torEnabled.value)
        assertNull(EsploraEndpoint.currentProxy())
    }

    @Test
    fun `corrupt settings file raises a dismissable banner and keeps Tor on`() =
        runDesktopComposeUiTest {
            val file = File(tmp, SettingsPersistence.FILE_NAME)
            file.writeText("""{"version": 1, "torEnabled": true, "themeMode": """, Charsets.UTF_8)
            assertTrue(SettingsPersistence.rehydrate(file).isFailure)
            assertTrue(SettingsStore.torEnabled.value, "fail closed: Tor stays on")

            setContent { BhodlTheme { SettingsWarningBanner() } }
            waitUntilAtLeastOneExists(hasTestTag(SettingsWarningBannerTags.BANNER))
            onNodeWithTag(SettingsWarningBannerTags.DISMISS).performClick()
            waitUntilDoesNotExist(hasTestTag(SettingsWarningBannerTags.BANNER))
            assertNull(SettingsStore.persistenceWarning.value)
        }

    @Test
    fun `no banner when settings loaded fine`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { SettingsWarningBanner() } }
        waitForIdle()
        assertTrue(onAllNodesWithTag(SettingsWarningBannerTags.BANNER).fetchSemanticsNodes().isEmpty())
    }
}
