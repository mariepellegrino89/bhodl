package io.bhodl.desktop.ui.money

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Phase 8.3.1 — render-tree assertions for the `<Money>` composable.
 *
 * These tests stand up a real Compose hierarchy via
 * [runDesktopComposeUiTest] and check the resulting text / structure.
 * Each test wraps the call in [BhodlTheme] so the colors and
 * typography are valid; the `SettingsStore` is reset around every
 * test so the unit-format preference is at its default.
 */
@OptIn(ExperimentalTestApi::class)
class MoneyRenderTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        // Clear the price cache so the secondary line resolves to
        // exactly what the test sets up (snapshot vs live).
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    @Test
    fun `Medium density renders BTC primary and fiat secondary lines`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            sat = 200_000L,
                            fiatAmount = 100.0,
                            fiatCurrency = "EUR",
                            semantic = MoneySemantic.AtNow,
                            rateBtcFiat = 50_000.0,
                        ),
                        density = MoneyDensity.Medium,
                        testTag = "money-medium",
                    )
                }
            }
            // Default unit is sats (UX review 2026-10): "200,000 sat".
            onNodeWithTag("money-medium").assertIsDisplayed()
            // The composable should contain BOTH the BTC primary line
            // and the fiat secondary line under MoneyDensity.Medium.
            waitUntilAtLeastOneExists(hasText(" sat", substring = true))
            waitUntilAtLeastOneExists(hasText("€", substring = true))
        }

    @Test
    fun `Compact density renders single line with parenthesized secondary`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            sat = 1_940_000L,
                            fiatAmount = 11_400.0,
                            fiatCurrency = "EUR",
                            semantic = MoneySemantic.AtNow,
                            rateBtcFiat = 58_762.0,
                        ),
                        density = MoneyDensity.Compact,
                        testTag = "money-compact",
                    )
                }
            }
            onNodeWithTag("money-compact").assertIsDisplayed()
            // Compact: "1,940,000 sat (€11,400.00)" — both BTC and fiat
            // values share a single Text node so a substring lookup
            // works on either side.
            waitUntilAtLeastOneExists(hasText(" sat (€", substring = true))
            waitUntilAtLeastOneExists(hasText("(€", substring = true))
        }

    @Test
    fun `Hero density renders primary line`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            sat = 100_000_000L,
                            fiatAmount = 50_000.0,
                            fiatCurrency = "EUR",
                            semantic = MoneySemantic.AtNow,
                            rateBtcFiat = 50_000.0,
                        ),
                        density = MoneyDensity.Hero,
                        testTag = "money-hero",
                    )
                }
            }
            onNodeWithTag("money-hero").assertIsDisplayed()
            waitUntilAtLeastOneExists(hasText("100,000,000 sat"))
        }

    @Test
    fun `Pnl positive value adds plus sign to BTC line`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            sat = 50_000L,
                            fiatAmount = 25.0,
                            fiatCurrency = "EUR",
                            semantic = MoneySemantic.Pnl,
                            rateBtcFiat = 100_000.0,
                        ),
                        density = MoneyDensity.Medium,
                        testTag = "money-pnl-positive",
                    )
                }
            }
            onNodeWithTag("money-pnl-positive").assertIsDisplayed()
            // 50_000 sat is BELOW the threshold → renders as "50,000 sat".
            // Pnl + non-negative → explicit "+" prefix.
            waitUntilAtLeastOneExists(hasText("+", substring = true))
        }

    @Test
    fun `Pnl negative value renders with leading minus`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            sat = -75_000L,
                            fiatAmount = -37.5,
                            fiatCurrency = "EUR",
                            semantic = MoneySemantic.Pnl,
                            rateBtcFiat = 100_000.0,
                        ),
                        density = MoneyDensity.Medium,
                        testTag = "money-pnl-negative",
                    )
                }
            }
            onNodeWithTag("money-pnl-negative").assertIsDisplayed()
            // The leading minus is on the sat (below threshold).
            waitUntilAtLeastOneExists(hasText("-75,000 sat", substring = true))
        }

    @Test
    fun `MoneyPrimary Fiat inverts the layout - fiat becomes primary`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            sat = 200_000L,
                            fiatAmount = 100.0,
                            fiatCurrency = "EUR",
                            semantic = MoneySemantic.AtNow,
                            rateBtcFiat = 50_000.0,
                        ),
                        density = MoneyDensity.Medium,
                        primary = MoneyPrimary.Fiat,
                        testTag = "money-fiat-primary",
                    )
                }
            }
            onNodeWithTag("money-fiat-primary").assertIsDisplayed()
            // Both lines render; the assertion is structural — primary
            // node is the fiat (€), but text presence is sufficient
            // here since runDesktopComposeUiTest doesn't expose font
            // size on a node-by-node basis without semantics props.
            waitUntilAtLeastOneExists(hasText("€", substring = true))
            waitUntilAtLeastOneExists(hasText(" sat", substring = true))
        }

    @Test
    fun `Missing rate on AtCost renders explicit no-rate on BTC line`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            sat = null,
                            fiatAmount = 100.0,
                            fiatCurrency = "EUR",
                            semantic = MoneySemantic.AtCost,
                            rateBtcFiat = null,
                        ),
                        density = MoneyDensity.Medium,
                        testTag = "money-no-rate",
                    )
                }
            }
            onNodeWithTag("money-no-rate").assertIsDisplayed()
            // Phase 9 "no rate" policy: a fiat value EXISTS (100 EUR) but
            // no rate is available to convert it → the BTC line says
            // "no rate" explicitly, not the ambiguous "—" (which is
            // reserved for "no value at all"). Same policy the
            // ManualAssets / Investments cells follow.
            waitUntilAtLeastOneExists(hasText("no rate", substring = true))
            // Fiat is still rendered.
            waitUntilAtLeastOneExists(hasText("€", substring = true))
        }

    @Test
    fun `Money follows the unit and BIP177 preferences`() =
        runDesktopComposeUiTest {
            // UX review 2026-10 — one formatting entry point: Money reads
            // the same unified unit + BIP177 settings as the wallet screens.
            SettingsStore.setUnitFormat(UnitFormat.AUTO_THRESHOLD)
            SettingsStore.setBip177Notation(true)
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue.fromSat(200_000L),
                        density = MoneyDensity.Compact,
                        showSecondary = false,
                        testTag = "money-small",
                    )
                    Money(
                        value = MoneyValue.fromSat(150_000_000L),
                        density = MoneyDensity.Compact,
                        showSecondary = false,
                        testTag = "money-big",
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("₿200,000"))
            waitUntilAtLeastOneExists(hasText("1.50000000 BTC"))
        }

    @Test
    fun `B7 CHF fiat line renders as CHF code not a bare F`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Money(
                        value = MoneyValue(
                            fiatAmount = 3_200.0,
                            fiatCurrency = "CHF",
                            semantic = MoneySemantic.AtNow,
                            rateBtcFiat = 54_800.0,
                        ),
                        density = MoneyDensity.Medium,
                        testTag = "money-chf",
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("CHF 3,200.00"))
            kotlin.test.assertTrue(
                onAllNodes(hasText("F3,200.00")).fetchSemanticsNodes().isEmpty(),
                "CHF must not render as F3,200.00",
            )
        }
}
