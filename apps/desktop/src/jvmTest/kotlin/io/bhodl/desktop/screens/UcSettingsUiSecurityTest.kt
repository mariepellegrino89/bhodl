package io.bhodl.desktop.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.settings.ThemeMode
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.ui.theme.ColorConvention
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings controls that only had store-level tests: Neon theme, colour
 * convention, fiat, auto-fetch, Bitcoin Lens, modules, privacy, network
 * line, Security Hub entry and the price error line
 * (docs/use-cases.md SEC-086, SEC-090..SEC-095, SEC-107, SEC-131, GAP-012).
 */
@OptIn(ExperimentalTestApi::class)
class UcSettingsUiSecurityTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-settings-ui-").toFile()
    }

    @AfterTest
    fun tearDown() {
        SettingsStore.reset()
        WalletStore.clear()
        PriceCache.resetSourcesForTest()
        PriceCache.seedForTest(null)
        dir.deleteRecursively()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.settings(onOpenSecurityHub: (() -> Unit)? = null) {
        setContent { BhodlTheme { SettingsScreen(onBack = {}, onOpenSecurityHub = onOpenSecurityHub, dataDir = dir) } }
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.APPEARANCE_SECTION))
    }

    private fun androidx.compose.ui.test.ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
        waitForIdle()
    }

    @Test
    fun `SEC-086 the Neon chip selects the neon theme`() = runDesktopComposeUiTest {
        settings()
        tap(SettingsTags.themeChip(ThemeMode.Neon))
        assertEquals(ThemeMode.Neon, SettingsStore.themeMode.value)
        tap(SettingsTags.themeChip(ThemeMode.System))
        assertEquals(ThemeMode.System, SettingsStore.themeMode.value)
    }

    @Test
    fun `SEC-086 every theme mode maps to the right palette`() {
        assertTrue(ThemeMode.Neon.usesDarkPalette(systemDark = false))
        assertTrue(ThemeMode.Dark.usesDarkPalette(systemDark = false))
        assertFalse(ThemeMode.Light.usesDarkPalette(systemDark = true))
        assertTrue(ThemeMode.System.usesDarkPalette(systemDark = true))
        assertFalse(ThemeMode.System.usesDarkPalette(systemDark = false))
    }

    @Test
    fun `SEC-090 the colour convention chips write the store`() = runDesktopComposeUiTest {
        settings()
        tap(SettingsTags.colorConventionChip(ColorConvention.EASTERN))
        assertEquals(ColorConvention.EASTERN, SettingsStore.colorConvention.value)
        tap(SettingsTags.colorConventionChip(ColorConvention.WESTERN))
        assertEquals(ColorConvention.WESTERN, SettingsStore.colorConvention.value)
    }

    @Test
    fun `SEC-090 Eastern swaps the positive and negative colours of the theme`() = runDesktopComposeUiTest {
        var western = Pair(Color.Unspecified, Color.Unspecified)
        var eastern = Pair(Color.Unspecified, Color.Unspecified)
        setContent {
            BhodlTheme(colorConvention = ColorConvention.WESTERN) {
                western = BhodlTheme.colors.financialPositive to BhodlTheme.colors.financialNegative
            }
            BhodlTheme(colorConvention = ColorConvention.EASTERN) {
                eastern = BhodlTheme.colors.financialPositive to BhodlTheme.colors.financialNegative
            }
        }
        waitForIdle()
        assertTrue(western.first != western.second)
        assertEquals(western.first, eastern.second)
        assertEquals(western.second, eastern.first)
    }

    @Test
    fun `SEC-091 the fiat chips switch the overlay currency`() = runDesktopComposeUiTest {
        settings()
        assertEquals(FiatCurrency.NONE, SettingsStore.fiatCurrency.value)
        tap(SettingsTags.fiatCurrencyChip(FiatCurrency.EUR))
        assertEquals(FiatCurrency.EUR, SettingsStore.fiatCurrency.value)
        tap(SettingsTags.fiatCurrencyChip(FiatCurrency.NONE))
        assertEquals(FiatCurrency.NONE, SettingsStore.fiatCurrency.value)
    }

    @Test
    fun `SEC-092 the investment auto-fetch switch is off by default and toggles`() = runDesktopComposeUiTest {
        settings()
        onNodeWithTag("settings.auto-fetch-investments").performScrollTo().assertIsOff()
        tap("settings.auto-fetch-investments")
        assertTrue(SettingsStore.autoFetchInvestmentPrices.value)
        onNodeWithTag("settings.auto-fetch-investments").assertIsOn()
        tap("settings.auto-fetch-investments")
        assertFalse(SettingsStore.autoFetchInvestmentPrices.value)
    }

    @Test
    fun `SEC-093 each Bitcoin Lens fiat switch flips only its own surface`() = runDesktopComposeUiTest {
        settings()
        val switches = listOf(
            SettingsTags.BITCOIN_LENS_FIAT_BANK_CSV to { SettingsStore.showFiatInBankCsv.value },
            SettingsTags.BITCOIN_LENS_FIAT_CURRENT_MONTH to { SettingsStore.showFiatInCurrentMonthFiatTx.value },
            SettingsTags.BITCOIN_LENS_FIAT_TAX to { SettingsStore.showFiatInTaxExports.value },
            SettingsTags.BITCOIN_LENS_FIAT_INVOICES to { SettingsStore.showFiatInInvoiceReceipts.value },
            SettingsTags.BITCOIN_LENS_FIAT_NW_CHART to { SettingsStore.showFiatOverlayOnNwChart.value },
        )
        switches.forEachIndexed { i, (tag, _) ->
            tap(tag)
            switches.forEachIndexed { j, (_, read) ->
                assertEquals(j > i, read(), "after flipping #$i, switch #$j")
            }
        }
    }

    @Test
    fun `SEC-094 the module switches in Settings drive the store`() = runDesktopComposeUiTest {
        settings()
        val modules = listOf(
            SettingsTags.MODULE_INVESTMENTS to { SettingsStore.moduleInvestments.value },
            SettingsTags.MODULE_BUDGET_BILLS to { SettingsStore.moduleBudgetBills.value },
            SettingsTags.MODULE_SAVINGS_GOALS to { SettingsStore.moduleSavingsGoals.value },
            SettingsTags.MODULE_MANUAL_ASSETS to { SettingsStore.moduleManualAssets.value },
            SettingsTags.MODULE_ANALYTICS_LABS to { SettingsStore.moduleAnalyticsLabs.value },
        )
        modules.forEach { (tag, read) ->
            assertFalse(read())
            tap(tag)
            assertTrue(read(), tag)
        }
        tap(SettingsTags.MODULE_BUDGET_BILLS)
        assertFalse(SettingsStore.moduleBudgetBills.value)
    }

    @Test
    fun `SEC-095 the start-hidden switch is saved as a preference`() = runDesktopComposeUiTest {
        settings()
        onNodeWithTag(SettingsTags.PRIVACY_AUTO_HIDE).performScrollTo().assertIsOff()
        tap(SettingsTags.PRIVACY_AUTO_HIDE)
        assertTrue(SettingsStore.privacyAutoHide.value)
        onNodeWithTag(SettingsTags.PRIVACY_AUTO_HIDE).assertIsOn()
    }

    @Test
    fun `SEC-107 Settings names the network of the open wallet`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort(), Network.MAINNET, "00000000-0000-4000-8000-0000000000aa")
        settings()
        onNodeWithTag(SettingsTags.NETWORK_VALUE).assertTextContains("Mainnet")
        assertTrue(onAllNodesWithText("To switch", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `SEC-107 without a wallet the network line shows the signet default`() = runDesktopComposeUiTest {
        settings()
        onNodeWithTag(SettingsTags.NETWORK_VALUE).assertTextContains("Signet")
    }

    @Test
    fun `SEC-131 the Security Hub entry in Settings opens the hub`() = runDesktopComposeUiTest {
        var opened = 0
        settings(onOpenSecurityHub = { opened++ })
        tap(SecurityHubTags.SETTINGS_ENTRY)
        assertEquals(1, opened)
    }

    @Test
    fun `GAP-012 a failing price fetch never shows the raw error in Settings`() = runDesktopComposeUiTest {
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        PriceCache.btcPriceSource = {
            throw java.io.IOException("connect to https://secret.example.onion/api failed: C:\\Users\\alice\\x")
        }
        runBlocking { PriceCache.refreshNow() }
        assertEquals(PriceCache.NO_PRICE_ERROR, PriceCache.lastError.value)
        settings()
        waitUntilAtLeastOneExists(hasText("No price available from any provider.", substring = true))
        assertEquals(0, onAllNodesWithText("secret.example", substring = true).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("alice", substring = true).fetchSemanticsNodes().size)
    }
}
