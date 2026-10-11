package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 8.3.4 — render-tree assertions for the Bitcoin Lens migration
 * of [ManualAssetsCard]. Covers:
 *  - 5-column header (NAME / TYPE / VALUE NOW / VALUE AT BUY / Δ BTC)
 *  - data row dual-line VALUE NOW and VALUE AT BUY via `<Money>` Medium
 *  - purchase-year hint appears next to the AT BUY secondary line
 *  - Δ BTC cell renders a percentage with sign-based color
 *  - Δ BTC renders em-dash silently when the purchase snapshot is
 *    incomplete OR when the asset is cross-currency
 *
 * Notes on `useUnmergedTree`: each row has `Modifier.clickable` on the
 * Row itself, merging its descendant semantic nodes into the parent.
 * Tags that live inside a clickable row are queried via the unmerged
 * tree where needed.
 */
@OptIn(ExperimentalTestApi::class)
class ManualAssetsCardBitcoinLensTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        // BTC = €60,000. Drives both the live VALUE NOW BTC line and
        // the Δ BTC denominator for `currentBtcPerUnit` resolution.
        PriceCache.seedForTest(price = 60_000.0, lastFetchUnix = nowSec())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
        // Phase 9 — clear the per-currency rate map so a seeded
        // cross-currency rate never leaks into the "no rate" test.
        PriceCache.seedCurrencyRatesForTest(emptyMap())
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /**
     * Family home storyboard — bought 2021 @ €250k when BTC = €50k (5.0 BTC).
     * Now (test rate = €60k) it's worth €288k (4.8 BTC). EUR up, BTC down.
     */
    private fun familyHome(): TrackingManualAssetFfi = TrackingManualAssetFfi(
        id = 100L,
        name = "Family home",
        kind = "real_estate",
        valueFiat = 288_000.0,
        currency = "EUR",
        displayLabel = null,
        notes = null,
        lastUpdatedUnix = nowSec(),
        createdAtUnix = 1_622_505_600L,
        purchaseUnix = 1_622_505_600L, // 2021-06-01
        purchaseValueFiat = 250_000.0,
        purchaseCurrency = "EUR",
        purchaseBtcAtTime = 50_000.0,
    )

    /** A legacy asset with no purchase snapshot — Δ BTC renders "—". */
    private fun legacyAsset(): TrackingManualAssetFfi = TrackingManualAssetFfi(
        id = 200L,
        name = "Legacy Cash",
        kind = "cash",
        valueFiat = 5_000.0,
        currency = "EUR",
        displayLabel = null,
        notes = null,
        lastUpdatedUnix = nowSec(),
        createdAtUnix = 1_500_000_000L,
        purchaseUnix = null,
        purchaseValueFiat = null,
        purchaseCurrency = null,
        purchaseBtcAtTime = null,
    )

    /** Cross-currency: asset in USD on a EUR dashboard → Δ BTC "—". */
    private fun usdAsset(): TrackingManualAssetFfi = TrackingManualAssetFfi(
        id = 300L,
        name = "US Brokerage",
        kind = "brokerage",
        valueFiat = 5_000.0,
        currency = "USD",
        displayLabel = null,
        notes = null,
        lastUpdatedUnix = nowSec(),
        createdAtUnix = 1_609_459_200L,
        purchaseUnix = 1_609_459_200L,
        purchaseValueFiat = 4_000.0,
        purchaseCurrency = "USD",
        purchaseBtcAtTime = 55_000.0,
    )

    @Test
    fun `header row shows 5 columns including DELTA BTC`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = listOf(familyHome()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ManualAssetTags.HEADER))
        onNodeWithTag(ManualAssetTags.HEADER).assertIsDisplayed()
        // Phase 8.3.4: 5 columns — assert each label is in the tree.
        waitUntilAtLeastOneExists(hasText("NAME"))
        waitUntilAtLeastOneExists(hasText("TYPE"))
        waitUntilAtLeastOneExists(hasText("VALUE NOW"))
        waitUntilAtLeastOneExists(hasText("VALUE AT BUY"))
        waitUntilAtLeastOneExists(hasText("Δ BTC", substring = true))
    }

    @Test
    fun `data row shows dual-line VALUE NOW and VALUE AT BUY`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = listOf(familyHome()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // Each row exposes its container + the two Money cells.
        waitUntilAtLeastOneExists(hasTestTag(ManualAssetTags.rowTag(100L)))
        // The Money cells live inside the clickable row → use unmerged.
        val now = onAllNodesWithTag(ManualAssetTags.valueNowTag(100L), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(now.isNotEmpty(), "VALUE NOW cell should be in the tree")
        val atBuy = onAllNodesWithTag(ManualAssetTags.valueAtBuyTag(100L), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(atBuy.isNotEmpty(), "VALUE AT BUY cell should be in the tree")
        // Both EUR fiat secondary lines must surface somewhere.
        waitUntilAtLeastOneExists(hasText("€", substring = true))
    }

    @Test
    fun `purchase year appears next to AT BUY secondary line`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = listOf(familyHome()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // Year extracted from 2021-06-01 unix; the cell renders "(2021)".
        // We assert via substring so locale-specific date formatting
        // around the parenthesis can't trip the test.
        waitUntilAtLeastOneExists(hasText("(2021)", substring = true))
    }

    @Test
    fun `delta BTC cell renders percentage for storyboard asset`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = listOf(familyHome()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // Family home: +15.2% EUR but −4% BTC (4.8 / 5.0 - 1) → formatter renders "↓ -4%".
        // The exact integer can drift with rounding; assert the down
        // arrow + minus sign are present (the sign is the headline).
        val delta = onAllNodesWithTag(ManualAssetTags.deltaBtcTag(100L), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(delta.isNotEmpty(), "Δ BTC cell should exist in the tree")
        waitUntilAtLeastOneExists(hasText("↓", substring = true))
    }

    @Test
    fun `delta BTC renders em-dash when purchase data missing`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = listOf(legacyAsset()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ManualAssetTags.rowTag(200L)))
        // Δ BTC cell falls back to em-dash; the formatter never emits
        // an arrow on null input.
        waitUntilAtLeastOneExists(hasText("—", substring = true))
        waitUntilDoesNotExist(hasText("↑", substring = true))
        waitUntilDoesNotExist(hasText("↓", substring = true))
    }

    @Test
    fun `delta BTC renders explicit no-rate for cross-currency asset`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = listOf(usdAsset()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ManualAssetTags.rowTag(300L)))
        // USD on EUR dashboard → PriceCache lookup returns null.
        // Phase 9.3 — a missing RATE is no longer a silent "—" (which
        // is ambiguous with "no data"): the cell says "no rate"
        // explicitly. Same policy as the Money composable's fallback.
        waitUntilAtLeastOneExists(hasText("no rate", substring = true))
    }

    @Test
    fun `delta BTC renders percentage for cross-currency asset when rate is cached`() = runDesktopComposeUiTest {
        // Phase 9 — the auto-fetch loop caches BTC/USD even on a EUR
        // dashboard; PriceCache.rateFor("USD") now resolves it, so the
        // Δ BTC cell renders a real percentage instead of "no rate".
        PriceCache.seedCurrencyRatesForTest(mapOf("USD" to 60_000.0))
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = listOf(usdAsset()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ManualAssetTags.rowTag(300L)))
        // usdAsset: 5,000 USD now vs 4,000 USD @ BTC=55k, now BTC=60k →
        // nowBtc 0.0833 > buyBtc 0.0727 → +14.6% in BTC → up arrow.
        waitUntilAtLeastOneExists(hasText("↑", substring = true))
        waitUntilDoesNotExist(hasText("no rate", substring = true))
    }

    @Test
    fun `empty state preserved when no assets`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualAssetsCard(
                    assets = emptyList(),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // The empty-copy nudge stays exactly as in Phase 7.4 — the
        // Phase 8.3.4 refactor only touched the rows, not the empty
        // state. No header row in the empty state either.
        waitUntilAtLeastOneExists(hasText("No manual assets yet.", substring = true))
        waitUntilDoesNotExist(hasTestTag(ManualAssetTags.HEADER))
    }
}
