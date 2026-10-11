package io.bhodl.desktop.settings

import io.bhodl.desktop.prices.FiatCurrency
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [`SettingsStore`].
 *
 * The store is a process-lifetime singleton, so every test resets it
 * on entry and exit to keep the order of execution from changing the
 * assertions.
 */
class SettingsStoreTest {

    @BeforeTest
    fun reset() {
        SettingsStore.reset()
    }

    @AfterTest
    fun cleanup() {
        SettingsStore.reset()
    }

    @Test
    fun `default theme mode is System`() {
        assertEquals(ThemeMode.System, SettingsStore.themeMode.value)
    }

    @Test
    fun `default esplora url and locale override are null`() {
        assertNull(SettingsStore.esploraUrl.value)
        assertNull(SettingsStore.localeOverride.value)
    }

    @Test
    fun `setThemeMode updates the reactive state`() {
        SettingsStore.setThemeMode(ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, SettingsStore.themeMode.value)
        SettingsStore.setThemeMode(ThemeMode.Light)
        assertEquals(ThemeMode.Light, SettingsStore.themeMode.value)
    }

    @Test
    fun `setEsploraUrl trims whitespace and treats blank as null`() {
        SettingsStore.setEsploraUrl("  https://my.node/api  ")
        assertEquals("https://my.node/api", SettingsStore.esploraUrl.value)

        // Blank input means "go back to the default"; we store null.
        SettingsStore.setEsploraUrl("   ")
        assertNull(SettingsStore.esploraUrl.value)

        SettingsStore.setEsploraUrl(null)
        assertNull(SettingsStore.esploraUrl.value)
    }

    @Test
    fun `setLocaleOverride trims and treats blank as null`() {
        SettingsStore.setLocaleOverride("  it ")
        assertEquals("it", SettingsStore.localeOverride.value)
        SettingsStore.setLocaleOverride("")
        assertNull(SettingsStore.localeOverride.value)
    }

    @Test
    fun `reset puts everything back to defaults`() {
        SettingsStore.setThemeMode(ThemeMode.Dark)
        SettingsStore.setEsploraUrl("https://example/api")
        SettingsStore.setLocaleOverride("en")
        SettingsStore.setTorEnabled(true)
        SettingsStore.setTorProxyUrl("socks5h://other:1234")
        SettingsStore.reset()
        assertEquals(ThemeMode.System, SettingsStore.themeMode.value)
        assertNull(SettingsStore.esploraUrl.value)
        assertNull(SettingsStore.localeOverride.value)
        assertEquals(false, SettingsStore.torEnabled.value)
        assertEquals(SettingsStore.DEFAULT_TOR_PROXY_URL, SettingsStore.torProxyUrl.value)
    }

    @Test
    fun `tor is disabled by default and proxy points at the standard SOCKS port`() {
        assertEquals(false, SettingsStore.torEnabled.value)
        assertEquals("socks5h://127.0.0.1:9050", SettingsStore.torProxyUrl.value)
        assertEquals(SettingsStore.DEFAULT_TOR_PROXY_URL, SettingsStore.torProxyUrl.value)
    }

    @Test
    fun `setTorEnabled flips the reactive state`() {
        SettingsStore.setTorEnabled(true)
        assertEquals(true, SettingsStore.torEnabled.value)
        SettingsStore.setTorEnabled(false)
        assertEquals(false, SettingsStore.torEnabled.value)
    }

    @Test
    fun `setTorProxyUrl trims and falls back to default when blank`() {
        SettingsStore.setTorProxyUrl("  socks5h://my.tor:9150  ")
        assertEquals("socks5h://my.tor:9150", SettingsStore.torProxyUrl.value)
        SettingsStore.setTorProxyUrl("   ")
        assertEquals(SettingsStore.DEFAULT_TOR_PROXY_URL, SettingsStore.torProxyUrl.value)
    }

    // -- Phase 5.0 — fiatCurrency ---------------------------------------

    @Test
    fun `default fiat currency is NONE (overlay disabled out of the box)`() {
        assertEquals(FiatCurrency.NONE, SettingsStore.fiatCurrency.value)
    }

    @Test
    fun `setFiatCurrency updates the reactive state`() {
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        assertEquals(FiatCurrency.EUR, SettingsStore.fiatCurrency.value)
        SettingsStore.setFiatCurrency(FiatCurrency.USD)
        assertEquals(FiatCurrency.USD, SettingsStore.fiatCurrency.value)
        SettingsStore.setFiatCurrency(FiatCurrency.CHF)
        assertEquals(FiatCurrency.CHF, SettingsStore.fiatCurrency.value)
        // Back to disabled.
        SettingsStore.setFiatCurrency(FiatCurrency.NONE)
        assertEquals(FiatCurrency.NONE, SettingsStore.fiatCurrency.value)
    }

    // -- Phase 5.2 — autoFetchInvestmentPrices --------------------------

    @Test
    fun `default autoFetchInvestmentPrices is false (off by default)`() {
        assertEquals(false, SettingsStore.autoFetchInvestmentPrices.value)
    }

    @Test
    fun `setAutoFetchInvestmentPrices flips the reactive state`() {
        SettingsStore.setAutoFetchInvestmentPrices(true)
        assertEquals(true, SettingsStore.autoFetchInvestmentPrices.value)
        SettingsStore.setAutoFetchInvestmentPrices(false)
        assertEquals(false, SettingsStore.autoFetchInvestmentPrices.value)
    }

    // -- reset() covers Phase 5 fields too ------------------------------

    @Test
    fun `reset clears fiat currency and disables auto fetch investments`() {
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        SettingsStore.setAutoFetchInvestmentPrices(true)
        SettingsStore.reset()
        assertEquals(FiatCurrency.NONE, SettingsStore.fiatCurrency.value)
        assertEquals(false, SettingsStore.autoFetchInvestmentPrices.value)
    }

    // -- Phase 3.10 — colorConvention -----------------------------------

    @Test
    fun `default color convention is Western`() {
        assertEquals(
            io.bhodl.ui.theme.ColorConvention.WESTERN,
            SettingsStore.colorConvention.value,
        )
    }

    @Test
    fun `setColorConvention flips between Western and Eastern`() {
        SettingsStore.setColorConvention(io.bhodl.ui.theme.ColorConvention.EASTERN)
        assertEquals(
            io.bhodl.ui.theme.ColorConvention.EASTERN,
            SettingsStore.colorConvention.value,
        )
        SettingsStore.setColorConvention(io.bhodl.ui.theme.ColorConvention.WESTERN)
        assertEquals(
            io.bhodl.ui.theme.ColorConvention.WESTERN,
            SettingsStore.colorConvention.value,
        )
    }

    // -- Phase 3.11 — btcUnit -------------------------------------------

    @Test
    fun `default btc unit is SAT`() {
        assertEquals(io.bhodl.shared.BtcUnit.SAT, SettingsStore.btcUnit.value)
    }

    @Test
    fun `setBtcUnit toggles between SAT and BTC`() {
        SettingsStore.setBtcUnit(io.bhodl.shared.BtcUnit.BTC)
        assertEquals(io.bhodl.shared.BtcUnit.BTC, SettingsStore.btcUnit.value)
        SettingsStore.setBtcUnit(io.bhodl.shared.BtcUnit.SAT)
        assertEquals(io.bhodl.shared.BtcUnit.SAT, SettingsStore.btcUnit.value)
    }

    // -- UX review 2026-10 — unified unit, BIP177, density, theme ------

    @Test
    fun `btcUnit is a projection of the unified unit format`() {
        SettingsStore.setBtcUnit(io.bhodl.shared.BtcUnit.BTC)
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC,
            SettingsStore.unitFormat.value,
        )
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.AUTO_THRESHOLD)
        assertEquals(io.bhodl.shared.BtcUnit.SAT, SettingsStore.btcUnit.value)
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC)
        assertEquals(io.bhodl.shared.BtcUnit.BTC, SettingsStore.btcUnit.value)
    }

    @Test
    fun `BIP177 notation is off by default and toggles`() {
        assertEquals(false, SettingsStore.bip177Notation.value)
        SettingsStore.setBip177Notation(true)
        assertEquals(true, SettingsStore.bip177Notation.value)
        SettingsStore.reset()
        assertEquals(false, SettingsStore.bip177Notation.value)
    }

    @Test
    fun `default density is Compact with 32dp rows`() {
        assertEquals(io.bhodl.ui.theme.BhodlDensity.Compact, SettingsStore.density.value)
        assertEquals(32f, SettingsStore.density.value.rowHeight.value)
        assertEquals(24f, io.bhodl.ui.theme.BhodlDensity.UltraDense.rowHeight.value)
    }

    @Test
    fun `density is selectable and reset restores Compact`() {
        SettingsStore.setDensity(io.bhodl.ui.theme.BhodlDensity.UltraDense)
        assertEquals(io.bhodl.ui.theme.BhodlDensity.UltraDense, SettingsStore.density.value)
        SettingsStore.reset()
        assertEquals(io.bhodl.ui.theme.BhodlDensity.Compact, SettingsStore.density.value)
    }

    @Test
    fun `default theme is System and follows the OS light-dark setting`() {
        assertEquals(ThemeMode.System, SettingsStore.themeMode.value)
        assertEquals(true, ThemeMode.System.usesDarkPalette(systemDark = true))
        assertEquals(false, ThemeMode.System.usesDarkPalette(systemDark = false))
        // Explicit picks ignore the OS.
        assertEquals(false, ThemeMode.Light.usesDarkPalette(systemDark = true))
        assertEquals(true, ThemeMode.Dark.usesDarkPalette(systemDark = false))
        assertEquals(true, ThemeMode.Neon.usesDarkPalette(systemDark = false))
    }


    // -- Phase 3.14 — excludedWalletIds ---------------------------------

    @Test
    fun `default excluded wallet ids is empty`() {
        assertEquals(emptySet(), SettingsStore.excludedWalletIds.value)
    }

    @Test
    fun `setWalletIncluded false adds the id to the exclusion set`() {
        SettingsStore.setWalletIncluded("wallet-1", included = false)
        assertEquals(setOf("wallet-1"), SettingsStore.excludedWalletIds.value)
    }

    @Test
    fun `setWalletIncluded true removes the id from the exclusion set`() {
        SettingsStore.setWalletIncluded("wallet-1", included = false)
        SettingsStore.setWalletIncluded("wallet-2", included = false)
        SettingsStore.setWalletIncluded("wallet-1", included = true)
        assertEquals(setOf("wallet-2"), SettingsStore.excludedWalletIds.value)
    }

    @Test
    fun `setWalletIncluded true on a non excluded id is a no op`() {
        SettingsStore.setWalletIncluded("wallet-1", included = true)
        assertEquals(emptySet(), SettingsStore.excludedWalletIds.value)
    }

    @Test
    fun `reset clears the excluded wallet ids set`() {
        SettingsStore.setWalletIncluded("w", included = false)
        SettingsStore.reset()
        assertEquals(emptySet(), SettingsStore.excludedWalletIds.value)
    }

    // ---- Phase 6.0 — tracker pairing dismissal -----------------------

    @Test
    fun `default tracker pairing skipped set is empty`() {
        assertEquals(emptySet(), SettingsStore.trackerPairingSkipped.value)
    }

    @Test
    fun `markTrackerPairingSkipped adds the wallet id`() {
        SettingsStore.markTrackerPairingSkipped("wallet-abc")
        assertEquals(setOf("wallet-abc"), SettingsStore.trackerPairingSkipped.value)
    }

    @Test
    fun `markTrackerPairingSkipped twice with same id is idempotent`() {
        SettingsStore.markTrackerPairingSkipped("wallet-abc")
        SettingsStore.markTrackerPairingSkipped("wallet-abc")
        assertEquals(setOf("wallet-abc"), SettingsStore.trackerPairingSkipped.value)
    }

    @Test
    fun `clearTrackerPairingSkipped removes only the matching id`() {
        SettingsStore.markTrackerPairingSkipped("wallet-a")
        SettingsStore.markTrackerPairingSkipped("wallet-b")
        SettingsStore.clearTrackerPairingSkipped("wallet-a")
        assertEquals(setOf("wallet-b"), SettingsStore.trackerPairingSkipped.value)
    }

    @Test
    fun `reset clears the tracker pairing skipped set`() {
        SettingsStore.markTrackerPairingSkipped("wallet-a")
        SettingsStore.reset()
        assertEquals(emptySet(), SettingsStore.trackerPairingSkipped.value)
    }

    // -- Phase 8.3.1 — Bitcoin Lens advanced preferences ----------------

    @Test
    fun `default unit format is always sats`() {
        // UX review 2026-10 (user decision 3) — sats by default.
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT,
            SettingsStore.unitFormat.value,
        )
    }

    @Test
    fun `setUnitFormat persists the choice in the reactive state`() {
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT)
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT,
            SettingsStore.unitFormat.value,
        )
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC)
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC,
            SettingsStore.unitFormat.value,
        )
    }

    @Test
    fun `default Bitcoin Lens fiat surfaces are all ON`() {
        assertEquals(true, SettingsStore.showFiatInBankCsv.value)
        assertEquals(true, SettingsStore.showFiatInCurrentMonthFiatTx.value)
        assertEquals(true, SettingsStore.showFiatInTaxExports.value)
        assertEquals(true, SettingsStore.showFiatInInvoiceReceipts.value)
    }

    @Test
    fun `Bitcoin Lens fiat surface toggles flip independently`() {
        SettingsStore.setShowFiatInBankCsv(false)
        assertEquals(false, SettingsStore.showFiatInBankCsv.value)
        // The other three stay true.
        assertEquals(true, SettingsStore.showFiatInCurrentMonthFiatTx.value)
        assertEquals(true, SettingsStore.showFiatInTaxExports.value)
        assertEquals(true, SettingsStore.showFiatInInvoiceReceipts.value)

        SettingsStore.setShowFiatInTaxExports(false)
        assertEquals(false, SettingsStore.showFiatInTaxExports.value)
        assertEquals(false, SettingsStore.showFiatInBankCsv.value)
        // The other two stay true.
        assertEquals(true, SettingsStore.showFiatInCurrentMonthFiatTx.value)
        assertEquals(true, SettingsStore.showFiatInInvoiceReceipts.value)
    }

    @Test
    fun `reset puts Bitcoin Lens preferences back to defaults`() {
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC)
        SettingsStore.setShowFiatInBankCsv(false)
        SettingsStore.setShowFiatInCurrentMonthFiatTx(false)
        SettingsStore.setShowFiatInTaxExports(false)
        SettingsStore.setShowFiatInInvoiceReceipts(false)
        SettingsStore.reset()
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT,
            SettingsStore.unitFormat.value,
        )
        assertEquals(true, SettingsStore.showFiatInBankCsv.value)
        assertEquals(true, SettingsStore.showFiatInCurrentMonthFiatTx.value)
        assertEquals(true, SettingsStore.showFiatInTaxExports.value)
        assertEquals(true, SettingsStore.showFiatInInvoiceReceipts.value)
    }
}
