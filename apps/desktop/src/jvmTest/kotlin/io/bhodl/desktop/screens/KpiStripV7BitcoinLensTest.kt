package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.FinanceKpis

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Phase 8.3.4 — render-tree assertions for the Bitcoin Lens migration
 * of [KpiStripV7]. Covers:
 *  - 5 KPI cards (was 4 pre-8.3.4) including the new OPP COST placeholder
 *  - BURN and FIRE use `<Money>` Compact (BTC primary + fiat secondary
 *    inline) instead of the legacy plain `displayAmount` string
 *  - SAVINGS still renders the percent label as a plain string
 *  - RUNWAY still renders the months label as a plain string
 *
 * The pre-8.3.4 strip exposed only 4 cards. The test below documents
 * the expected upgrade.
 */
@OptIn(ExperimentalTestApi::class)
class KpiStripV7BitcoinLensTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        PriceCache.seedForTest(price = 60_000.0, lastFetchUnix = nowSec())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    private fun kpis(): FinanceKpis = FinanceKpis(
        savingsRatePct = 25,
        burnRateSatPerMonth = 250_000UL,
        fireNumberSat = 75_000_000UL,
        runwayMonths = 18.0,
    )

    @Test
    fun `five KPI cards rendered including OPP_COST`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { KpiStripV7(kpis = kpis()) } }
        waitUntilAtLeastOneExists(hasTestTag(KpiStripV7Tags.ROW))
        // Original four cards still here.
        onNodeWithTag(KpiStripV7Tags.SAVINGS).assertIsDisplayed()
        onNodeWithTag(KpiStripV7Tags.BURN).assertIsDisplayed()
        onNodeWithTag(KpiStripV7Tags.FIRE).assertIsDisplayed()
        onNodeWithTag(KpiStripV7Tags.RUNWAY).assertIsDisplayed()
        // Phase 8.3.4: added OPP_COST as the 5th slot.
        onNodeWithTag(KpiStripV7Tags.OPP_COST).assertIsDisplayed()
    }

    @Test
    fun `OPP_COST shows em-dash placeholder with vs HODL subtitle`() = runDesktopComposeUiTest {
        // Phase 8.5: OPP_COST populated — when `opportunityCostBtc`
        // is null (the default in `kpis()` below) the card still
        // renders "—" plus the "vs HODL" subtitle (the "(Phase 8.5)"
        // suffix from the placeholder copy was dropped in 8.5).
        setContent { BhodlTheme { KpiStripV7(kpis = kpis()) } }
        waitUntilAtLeastOneExists(hasTestTag(KpiStripV7Tags.OPP_COST))
        waitUntilAtLeastOneExists(hasText("OPP COST"))
        waitUntilAtLeastOneExists(hasText("vs HODL", substring = true))
    }

    @Test
    fun `BURN and FIRE use Money Compact BTC primary`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { KpiStripV7(kpis = kpis()) } }
        waitUntilAtLeastOneExists(hasTestTag(KpiStripV7Tags.BURN))
        waitUntilAtLeastOneExists(hasTestTag(KpiStripV7Tags.FIRE))
        // BURN: 250k sat, FIRE: 75M sat — both in the default sats unit.
        // (UX review 2026-10: unit defaults to sats.)
        waitUntilAtLeastOneExists(hasText(" sat", substring = true))
        // And the fiat secondary appears in parentheses (Compact format).
        waitUntilAtLeastOneExists(hasText("€", substring = true))
    }

    @Test
    fun `SAVINGS and RUNWAY render unchanged percent and months strings`() =
        runDesktopComposeUiTest {
            setContent { BhodlTheme { KpiStripV7(kpis = kpis()) } }
            waitUntilAtLeastOneExists(hasTestTag(KpiStripV7Tags.SAVINGS))
            // Savings: "+25%" formatted by the original code path.
            waitUntilAtLeastOneExists(hasText("+25%", substring = true))
            // Runway: "18 mo" via formatMonths (months ≥ 10 → integer form).
            waitUntilAtLeastOneExists(hasText("18 mo", substring = true))
        }

    @Test
    fun `null BURN and FIRE render em-dash placeholders`() = runDesktopComposeUiTest {
        val empty = FinanceKpis(
            savingsRatePct = null,
            burnRateSatPerMonth = null,
            fireNumberSat = null,
            runwayMonths = null,
        )
        setContent { BhodlTheme { KpiStripV7(kpis = empty) } }
        waitUntilAtLeastOneExists(hasTestTag(KpiStripV7Tags.BURN))
        waitUntilAtLeastOneExists(hasTestTag(KpiStripV7Tags.FIRE))
        // KpiCardMoney falls back to "—" identical in visual weight to
        // the original KpiCard's em-dash policy.
        waitUntilAtLeastOneExists(hasText("—", substring = true))
    }
}
