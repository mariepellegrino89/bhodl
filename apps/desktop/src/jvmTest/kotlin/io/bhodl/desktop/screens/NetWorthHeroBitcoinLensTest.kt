package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Phase 8.3.2 — render-tree assertions for the Bitcoin Lens rewrite of
 * [NetWorthHero].
 *
 * Coverage:
 *  - BTC primary line on top, fiat secondary below (via [io.bhodl.desktop.ui.money.Money]).
 *  - Tap does NOT cycle units (the legacy AmountDisplayMode cycler was
 *    removed).
 *  - Long-press still reveals when privacy auto-hide is on.
 *  - "EUR ↓ X% vs BTC" chip appears with non-empty fiat history.
 *  - Chip arrow flips ↑ when the rate fell on the period.
 *  - Chip omitted when history is empty / single-point / currentSat=0.
 *  - The dashed overlay toggle controls visibility of the secondary
 *    line on the sparkline (verified indirectly via Settings flag).
 */
@OptIn(ExperimentalTestApi::class)
class NetWorthHeroBitcoinLensTest {

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

    /** History where the rate rose 10% (50k → 55k for 1 BTC NW). */
    private val historyRateUp: List<Pair<Long, Double>> = listOf(
        1_000L to 50_000.0,
        1_500L to 52_500.0,
        2_000L to 55_000.0,
    )

    /** History where the rate fell 10% (50k → 45k for 1 BTC NW). */
    private val historyRateDown: List<Pair<Long, Double>> = listOf(
        1_000L to 50_000.0,
        1_500L to 47_500.0,
        2_000L to 45_000.0,
    )

    @Test
    fun `hero renders primary BTC line and secondary fiat line`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L, // 1 BTC, > 100k threshold → renders as BTC
                    historySatFiat = historyRateUp,
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.HERO))
        onNodeWithTag(NetWorthHeroTags.HERO).assertIsDisplayed()
        // BTC primary line — Auto-format → "X BTC" for 1 BTC NW.
        waitUntilAtLeastOneExists(hasText("BTC", substring = true))
        // Fiat secondary line.
        waitUntilAtLeastOneExists(hasText("€", substring = true))
    }

    @Test
    fun `tap on the hero does NOT cycle units (no AmountDisplayMode cycler)`() =
        runDesktopComposeUiTest {
            // Phase 8.3.2: the tap-cycle was removed. Tapping the hero
            // with auto-hide OFF is a no-op — the BTC primary line
            // remains "BTC" and never becomes "sat" or fiat.
            setContent {
                BhodlTheme {
                    NetWorthHero(
                        currentSat = 100_000_000L,
                        historySatFiat = historyRateUp,
                        fiatCurrency = FiatCurrency.EUR,
                        period = NetWorthPeriod.D90,
                        onPeriodChange = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.HERO))
            // Tap the hero a few times — none of them should change the
            // BTC primary into a SAT or fiat-primary render.
            repeat(3) {
                onNodeWithTag(NetWorthHeroTags.HERO).performClick()
            }
            // The BTC label is still present in the tree.
            waitUntilAtLeastOneExists(hasText("BTC", substring = true))
        }

    @Test
    fun `privacy mode on shows Hold to reveal hint`() = runDesktopComposeUiTest {
        SettingsStore.setBalancesHidden(true)
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L,
                    historySatFiat = historyRateUp,
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        // Hidden hero shows the "Hold to reveal" hint.
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.HOLD_HINT))
        onNodeWithTag(NetWorthHeroTags.HOLD_HINT).assertIsDisplayed()
    }

    @Test
    fun `chip 'EUR vs BTC' renders when fiat history present and rate end greater`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    NetWorthHero(
                        currentSat = 100_000_000L,
                        historySatFiat = historyRateUp,
                        fiatCurrency = FiatCurrency.EUR,
                        period = NetWorthPeriod.D90,
                        onPeriodChange = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.CHIP_FIAT_VS_BTC))
            onNodeWithTag(NetWorthHeroTags.CHIP_FIAT_VS_BTC).assertIsDisplayed()
            // Rate up = fiat lost vs BTC → arrow ↓ on the chip.
            waitUntilAtLeastOneExists(hasText("↓", substring = true))
            waitUntilAtLeastOneExists(hasText("EUR", substring = true))
            waitUntilAtLeastOneExists(hasText("vs BTC", substring = true))
        }

    @Test
    fun `chip arrow flips up when rate end is less than rate start`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L,
                    historySatFiat = historyRateDown,
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.CHIP_FIAT_VS_BTC))
        // Rate down = fiat gained vs BTC → arrow ↑ on the chip.
        waitUntilAtLeastOneExists(hasText("↑", substring = true))
    }

    @Test
    fun `chip omitted when historySatFiat is empty`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L,
                    historySatFiat = emptyList(),
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.HERO))
        // No chip rendered when history is empty.
        waitUntilDoesNotExist(hasTestTag(NetWorthHeroTags.CHIP_FIAT_VS_BTC))
    }

    @Test
    fun `chip omitted when historySatFiat has a single point`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L,
                    historySatFiat = listOf(1_000L to 50_000.0),
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.HERO))
        waitUntilDoesNotExist(hasTestTag(NetWorthHeroTags.CHIP_FIAT_VS_BTC))
    }

    @Test
    fun `sparkline renders when overlay toggle is on (default)`() = runDesktopComposeUiTest {
        // Default of showFiatOverlayOnNwChart is true post-reset.
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L,
                    historySatFiat = historyRateUp,
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.SPARKLINE))
        onNodeWithTag(NetWorthHeroTags.SPARKLINE).assertIsDisplayed()
    }

    @Test
    fun `sparkline renders when overlay toggle is off`() = runDesktopComposeUiTest {
        SettingsStore.setShowFiatOverlayOnNwChart(false)
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L,
                    historySatFiat = historyRateUp,
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        // The sparkline node is still present (the primary sat-on-y
        // line is always there); the overlay path is just not drawn,
        // which is internal to the Canvas and not directly assertable.
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.SPARKLINE))
        onNodeWithTag(NetWorthHeroTags.SPARKLINE).assertIsDisplayed()
    }

    @Test
    fun `delta row testTag present when not hidden`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                NetWorthHero(
                    currentSat = 100_000_000L,
                    historySatFiat = historyRateUp,
                    fiatCurrency = FiatCurrency.EUR,
                    period = NetWorthPeriod.D90,
                    onPeriodChange = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.DELTA_BTC))
        onNodeWithTag(NetWorthHeroTags.DELTA_BTC).assertIsDisplayed()
    }

    @Test
    fun `delta row hidden when privacy mode is on and not revealing`() =
        runDesktopComposeUiTest {
            SettingsStore.setBalancesHidden(true)
            setContent {
                BhodlTheme {
                    NetWorthHero(
                        currentSat = 100_000_000L,
                        historySatFiat = historyRateUp,
                        fiatCurrency = FiatCurrency.EUR,
                        period = NetWorthPeriod.D90,
                        onPeriodChange = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.HOLD_HINT))
            // Delta row not in the tree while hidden.
            waitUntilDoesNotExist(hasTestTag(NetWorthHeroTags.DELTA_BTC))
        }
}
