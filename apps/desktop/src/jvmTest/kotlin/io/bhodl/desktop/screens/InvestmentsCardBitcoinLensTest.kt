package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phase 8.3.3 — render-tree assertions for the Bitcoin Lens migration
 * of [InvestmentsCard]. Covers:
 *  - the header now exposes 7 columns including the new `P&L BTC`
 *  - COST / VALUE cells render BTC primary + fiat secondary (via [io.bhodl.desktop.ui.money.Money])
 *  - the ⚠ chip appears when fiat / BTC P&L signs disagree
 *  - the chip is omitted when both sides agree or pnlBtc is null
 *  - the totals row is the new 60dp stacked layout (BTC over fiat)
 *  - legacy investments (no lots, no BTC backfill) gracefully degrade
 *    to "—" on the BTC column without the chip
 *
 * Note on `useUnmergedTree`: each data row has a `Modifier.clickable`
 * on the Row itself (the original "click anywhere to edit" UX). That
 * merges every descendant semantic node into the row's parent, so
 * inner testTags can't be queried via the default merged tree. The
 * tests below use `useUnmergedTree = true` whenever they pick a tag
 * that lives inside a clickable row.
 */
@OptIn(ExperimentalTestApi::class)
class InvestmentsCardBitcoinLensTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        // BTC = €60,000. Drives both the live PriceCache lookup in the
        // VALUE cell and the BTC P&L denominator for divergence detection.
        PriceCache.seedForTest(price = 60_000.0, lastFetchUnix = nowSec())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /**
     * Investment seeded with lots; fiat-flat by default (manualCurrentValue
     * = 100, lots @ €100 each → fiat P&L = 0). Tests override
     * `manualCurrentValue` to trigger divergence.
     */
    private fun divergentInvestment(): TrackingInvestmentFfi = TrackingInvestmentFfi(
        id = 1L,
        name = "VWCE",
        assetType = "etf",
        ticker = "VWCE",
        quantity = 0.0,
        unit = "shares",
        costBasisFiat = 0.0,
        costBasisCurrency = "EUR",
        purchaseUnix = 1_000L,
        manualCurrentValue = 100.0,
        fetchedCurrentValue = null,
        lastPricedAtUnix = null,
        notes = null,
        costBasisMethod = "fifo",
        createdAtUnix = 0L,
    )

    /**
     * Two units bought at €100 each when BTC=€50,000 → 200/50,000 =
     * 0.004 BTC cost basis. Used together with [divergentInvestment]
     * to construct rows whose fiat / BTC signs disagree.
     */
    private fun divergentLots(): List<TrackingInvestmentLotFfi> = listOf(
        TrackingInvestmentLotFfi(
            id = 10L,
            investmentId = 1L,
            quantity = 2.0,
            pricePerUnit = 100.0,
            feesFiat = 0.0,
            purchaseUnix = 1_000L,
            notes = null,
            createdAtUnix = 0L,
            btcPerUnitAtPurchase = 50_000.0,
        ),
    )

    /** Both fiat and BTC positive — used to assert NO chip is rendered. */
    private fun alignedPositiveInvestment(): TrackingInvestmentFfi =
        divergentInvestment().copy(
            id = 2L,
            name = "AlignedUp",
            manualCurrentValue = 200.0, // value €400 vs cost €200 → +100% fiat
        )

    private fun alignedPositiveLots(): List<TrackingInvestmentLotFfi> = listOf(
        TrackingInvestmentLotFfi(
            id = 20L,
            investmentId = 2L,
            quantity = 2.0,
            pricePerUnit = 100.0,
            feesFiat = 0.0,
            purchaseUnix = 1_000L,
            notes = null,
            createdAtUnix = 0L,
            // BTC at purchase = €80k, now = €60k → BTC fell → asset
            // also gained in BTC terms. Cost: 200/80k = 0.0025 BTC;
            // value: 400/60k = 0.00666… BTC → +0.00417 BTC. Aligned.
            btcPerUnitAtPurchase = 80_000.0,
        ),
    )

    /** Legacy investment: no lots backfilled → BTC P&L unknown. */
    private fun legacyInvestment(): TrackingInvestmentFfi = TrackingInvestmentFfi(
        id = 3L,
        name = "LegacyCommodity",
        assetType = "commodity",
        ticker = null,
        quantity = 5.0,
        unit = "g",
        costBasisFiat = 100.0,
        costBasisCurrency = "EUR",
        purchaseUnix = 0L,
        manualCurrentValue = 22.0,
        fetchedCurrentValue = null,
        lastPricedAtUnix = null,
        notes = null,
        costBasisMethod = "fifo",
        createdAtUnix = 0L,
    )

    @Test
    fun `header row shows 7 columns including P_AND_L BTC`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(divergentInvestment()),
                    lotsByInvestmentId = mapOf(1L to divergentLots()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.HEADER))
        onNodeWithTag(InvestmentTags.HEADER).assertIsDisplayed()
        // Phase 8.3.3: the previous header had a single "P&L" column;
        // now it's split into "P&L EUR" + "P&L BTC".
        waitUntilAtLeastOneExists(hasText("P&L EUR"))
        waitUntilAtLeastOneExists(hasText("P&L BTC"))
    }

    @Test
    fun `data row dual-line cost shows BTC primary and fiat secondary`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(divergentInvestment()),
                    lotsByInvestmentId = mapOf(1L to divergentLots()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // Cost basis: 200 EUR / 50_000 EUR/BTC = 0.004 BTC. Auto-format
        // (≥100k sat → BTC) renders "0.00400000 BTC". Plus the €200
        // fiat secondary on the same cell.
        waitUntilAtLeastOneExists(hasText("BTC", substring = true))
        waitUntilAtLeastOneExists(hasText("€", substring = true))
    }

    @Test
    fun `data row dual-line value shows BTC primary and fiat secondary`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(divergentInvestment()),
                    lotsByInvestmentId = mapOf(1L to divergentLots()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // Current value: 2 * €100 = €200; at BTC=€60k → 0.003333… BTC ≈
        // 333_333 sat → ≥100k threshold → rendered as BTC.
        waitUntilAtLeastOneExists(hasText("BTC", substring = true))
        waitUntilAtLeastOneExists(hasText("€200", substring = true))
    }

    @Test
    fun `divergent signs fiat positive btc negative renders warning chip`() = runDesktopComposeUiTest {
        // Bump value to €110/unit → fiat +10%; BTC P&L stays negative
        // because BTC appreciated faster than the asset. Divergent.
        val inv = divergentInvestment().copy(manualCurrentValue = 110.0)
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(inv),
                    lotsByInvestmentId = mapOf(1L to divergentLots()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // The chip lives inside the clickable data row → query the
        // unmerged tree.
        waitUntilAtLeastOneExists(hasText("↓ vs ₿", substring = true))
        // The chip exists in the unmerged tree under its tag.
        val nodes = onAllNodesWithTag(InvestmentTags.pnlBtcChipTag(1L), useUnmergedTree = true)
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(nodes.isNotEmpty(), "divergence chip should exist")
    }

    @Test
    fun `non-divergent both positive does NOT render the warning chip`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(alignedPositiveInvestment()),
                    lotsByInvestmentId = mapOf(2L to alignedPositiveLots()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // The card has rendered (the totals row appears).
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.TOTALS))
        // No divergence chip text in the tree.
        waitUntilDoesNotExist(hasText("↓ vs ₿", substring = true))
        waitUntilDoesNotExist(hasText("↑ vs ₿", substring = true))
    }

    @Test
    fun `null pnlBtc renders em-dash without chip`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(legacyInvestment()),
                    lotsByInvestmentId = emptyMap(),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.TOTALS))
        // No divergence chip on a row where pnlBtc is null.
        waitUntilDoesNotExist(hasText("↓ vs ₿", substring = true))
        waitUntilDoesNotExist(hasText("↑ vs ₿", substring = true))
        // The em-dash is in the tree (P&L BTC cell falls back to "—").
        waitUntilAtLeastOneExists(hasText("—", substring = true))
    }

    @Test
    fun `totals row stacked two lines BTC over fiat`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(divergentInvestment()),
                    lotsByInvestmentId = mapOf(1L to divergentLots()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.TOTALS))
        onNodeWithTag(InvestmentTags.TOTALS).assertIsDisplayed()
        // Totals expose dedicated cost / value cells (stacked BTC+fiat).
        onNodeWithTag(InvestmentTags.TOTALS_COST).assertIsDisplayed()
        onNodeWithTag(InvestmentTags.TOTALS_VALUE).assertIsDisplayed()
        onNodeWithTag(InvestmentTags.TOTALS_PNL_BTC).assertIsDisplayed()
    }

    @Test
    fun `legacy investment without lots renders pnlBtc as em-dash`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(legacyInvestment()),
                    lotsByInvestmentId = emptyMap(),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.TOTALS))
        // The em-dash is in the tree (P&L BTC cell can't be computed).
        waitUntilAtLeastOneExists(hasText("—", substring = true))
        // No chip rendered for an unknown P&L BTC.
        waitUntilDoesNotExist(hasText("↓ vs ₿", substring = true))
    }

    @Test
    fun `lot row shows BTC at purchase secondary when backfilled`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(divergentInvestment()),
                    lotsByInvestmentId = mapOf(1L to divergentLots()),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // Expand the investment row to surface its lots. Use the
        // unmerged tree to find the caret which lives inside the
        // clickable data row.
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.expandTag(1L)))
        onNodeWithTag(InvestmentTags.expandTag(1L), useUnmergedTree = true).performClick()
        // The lot's BTC secondary line carries "at purchase".
        waitUntilAtLeastOneExists(hasText("at purchase", substring = true))
    }

    @Test
    fun `sell row shows dual signed pnl when btc backfilled`() = runDesktopComposeUiTest {
        val sell = io.bhodl.ffi.TrackingInvestmentSellFfi(
            id = 100L,
            investmentId = 1L,
            saleUnix = 2_000L,
            salePricePerUnit = 150.0,
            saleQty = 1.0,
            feesFiat = 0.0,
            notes = null,
            createdAtUnix = 0L,
            btcPerUnitAtSale = 60_000.0,
        )
        val disposal = TrackingLotDisposalFfi(
            id = 1000L,
            sellId = 100L,
            lotId = 10L,
            qtyConsumed = 1.0,
            costBasisPerUnit = 100.0,
            gainLossFiat = 50.0,
            createdAtUnix = 0L,
            gainLossBtc = -0.0005,
        )
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(divergentInvestment()),
                    lotsByInvestmentId = mapOf(1L to divergentLots()),
                    sellsByInvestmentId = mapOf(1L to listOf(sell)),
                    disposalsByInvestmentId = mapOf(1L to listOf(disposal)),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.expandTag(1L)))
        onNodeWithTag(InvestmentTags.expandTag(1L), useUnmergedTree = true).performClick()
        // The dual P&L line carries the BTC side (-0.0005 BTC) in the user unit.
        waitUntilAtLeastOneExists(hasText("50,000 sat", substring = true))
    }

    @Test
    fun `cost cell BTC line is the per-lot sum, not an averaged rate`() = runDesktopComposeUiTest {
        // Code review 2026-10 — €100 @ 10k + €100 @ 50k = 0.012 BTC.
        val lots = listOf(
            divergentLots().single().copy(id = 30L, quantity = 1.0, btcPerUnitAtPurchase = 10_000.0),
            divergentLots().single().copy(id = 31L, quantity = 1.0, btcPerUnitAtPurchase = 50_000.0),
        )
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(divergentInvestment()),
                    lotsByInvestmentId = mapOf(1L to lots),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        // Unit-format agnostic: BTC ("0.01200000 BTC") or sat ("1,200,000 sat").
        waitUntilAtLeastOneExists(hasText("0.01200000 BTC") or hasText("1,200,000 sat"))
    }
}
