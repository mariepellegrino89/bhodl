package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Area D — live net-worth conversions, the Portfolio balance sheet card,
 * the breakdown table, the investments table caret / cross-currency row
 * and the events fed to the net-worth history.
 */
@OptIn(ExperimentalTestApi::class)
class UcPortfolioNetWorthTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        PriceCache.seedForTest(null)
        PriceCache.seedCurrencyRatesForTest(emptyMap())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(null)
        PriceCache.seedCurrencyRatesForTest(emptyMap())
    }

    private fun inv(
        id: Long,
        ccy: String = "EUR",
        qty: Double = 0.0,
        cost: Double = 0.0,
        manual: Double? = null,
        fetched: Double? = null,
        ticker: String? = null,
        type: String = "etf",
    ) = TrackingInvestmentFfi(
        id = id, name = "Inv$id", assetType = type, ticker = ticker, quantity = qty, unit = "u",
        costBasisFiat = cost, costBasisCurrency = ccy, purchaseUnix = 0, manualCurrentValue = manual,
        fetchedCurrentValue = fetched, lastPricedAtUnix = null, notes = null, costBasisMethod = "fifo",
        createdAtUnix = 0,
    )

    private fun lot(id: Long, inv: Long, qty: Double, price: Double) = TrackingInvestmentLotFfi(
        id = id, investmentId = inv, quantity = qty, pricePerUnit = price, feesFiat = 0.0,
        purchaseUnix = 0, notes = null, createdAtUnix = 0, btcPerUnitAtPurchase = null, originalQuantity = qty,
    )

    private fun asset(id: Long, value: Double, ccy: String, kind: String = "cash") = TrackingManualAssetFfi(
        id = id, name = "Asset$id", kind = kind, valueFiat = value, currency = ccy, displayLabel = null,
        notes = null, lastUpdatedUnix = System.currentTimeMillis() / 1000L, createdAtUnix = 0,
        purchaseUnix = null, purchaseValueFiat = null, purchaseCurrency = null, purchaseBtcAtTime = null,
    )

    private fun liability(id: Long, sat: ULong, kind: String, label: String? = null) = TrackingLiabilityFfi(
        id = id, name = "Debt$id", amountSat = sat, kind = kind, displayLabel = label, createdAtUnix = 0,
    )

    // ─── PRT-106 live investments value ───────────────────────────

    @Test
    fun `PRT-106 fetched price wins over manual and over the cost`() {
        val priced = inv(1, manual = 90.0, fetched = 100.0)
        val sat = computeLiveInvestmentsSat(listOf(priced), mapOf(1L to listOf(lot(1, 1, 10.0, 80.0))), 50_000.0, "EUR")
        assertEquals(2_000_000UL, sat) // 1000 EUR / 50k
        val manualOnly = inv(1, manual = 90.0)
        val manualSat = computeLiveInvestmentsSat(listOf(manualOnly), mapOf(1L to listOf(lot(1, 1, 10.0, 80.0))), 50_000.0, "EUR")
        assertTrue(manualSat in 1_799_999UL..1_800_000UL, "$manualSat") // 900 EUR, truncated
    }

    @Test
    fun `PRT-106 without a price the cost basis stands in`() {
        val sat = computeLiveInvestmentsSat(listOf(inv(1)), mapOf(1L to listOf(lot(1, 1, 10.0, 80.0))), 50_000.0, "EUR")
        assertEquals(1_600_000UL, sat) // 800 EUR cost / 50k
        val legacy = inv(2, qty = 4.0, cost = 500.0)
        assertEquals(1_000_000UL, computeLiveInvestmentsSat(listOf(legacy), emptyMap(), 50_000.0, "EUR"))
    }

    @Test
    fun `PRT-106 no price and no cost contributes nothing`() {
        assertEquals(0UL, computeLiveInvestmentsSat(listOf(inv(1, qty = 3.0)), emptyMap(), 50_000.0, "EUR"))
    }

    @Test
    fun `PRT-106 a foreign-currency row without its rate is left out, never priced at another rate`() {
        val usd = inv(1, ccy = "USD", manual = 100.0)
        val eur = inv(2, manual = 100.0)
        val lots = mapOf(1L to listOf(lot(1, 1, 10.0, 50.0)), 2L to listOf(lot(2, 2, 10.0, 50.0)))
        assertEquals(2_000_000UL, computeLiveInvestmentsSat(listOf(usd, eur), lots, 50_000.0, "EUR"))
        PriceCache.seedCurrencyRatesForTest(mapOf("USD" to 40_000.0))
        assertEquals(4_500_000UL, computeLiveInvestmentsSat(listOf(usd, eur), lots, 50_000.0, "EUR"))
    }

    @Test
    fun `PRT-106 no live dashboard rate means no live value`() {
        assertEquals(0UL, computeLiveInvestmentsSat(listOf(inv(1, manual = 10.0)), mapOf(1L to listOf(lot(1, 1, 1.0, 1.0))), null, "EUR"))
    }

    // ─── PRT-090 live manual assets value ─────────────────────────

    @Test
    fun `PRT-090 GBP and JPY assets without a cached rate are excluded from the total`() {
        val assets = listOf(asset(1, 1_000.0, "EUR"), asset(2, 800.0, "GBP"), asset(3, 150_000.0, "JPY"))
        assertEquals(2_000_000UL, computeLiveManualAssetsSat(assets, 50_000.0, "EUR"))
        PriceCache.seedCurrencyRatesForTest(mapOf("GBP" to 40_000.0, "JPY" to 7_500_000.0))
        assertEquals(6_000_000UL, computeLiveManualAssetsSat(assets, 50_000.0, "EUR")) // 0.02 + 0.02 + 0.02
    }

    @Test
    fun `PRT-090 non-positive or non-finite values are skipped and currency case is ignored`() {
        val assets = listOf(asset(1, 0.0, "EUR"), asset(2, Double.NaN, "EUR"), asset(3, 500.0, " eur "))
        assertEquals(1_000_000UL, computeLiveManualAssetsSat(assets, 50_000.0, "EUR"))
    }

    @Test
    fun `PRT-090 expired cross-currency rates stop pricing the asset`() {
        var now = 1_000_000L
        PriceCache.nowMs = { now }
        try {
            PriceCache.seedCurrencyRatesForTest(mapOf("GBP" to 40_000.0))
            assertEquals(2_000_000UL, computeLiveManualAssetsSat(listOf(asset(1, 800.0, "GBP")), 50_000.0, "EUR"))
            now += PriceCache.CURRENCY_RATE_TTL_MS
            assertEquals(0UL, computeLiveManualAssetsSat(listOf(asset(1, 800.0, "GBP")), 50_000.0, "EUR"))
        } finally {
            PriceCache.resetSourcesForTest()
        }
    }

    // ─── PRT-100 liability subtitle ───────────────────────────────

    @Test
    fun `PRT-100 liability subtitle shows the kind and the optional note`() {
        assertEquals("Mortgage", liabilitySubtitle(liability(1, 1UL, "mortgage")))
        assertEquals("Credit card · Visa", liabilitySubtitle(liability(1, 1UL, "credit-card", "  Visa ")))
        assertEquals("Loan", liabilitySubtitle(liability(1, 1UL, "loan", "   ")))
        assertEquals("Boat", liabilitySubtitle(liability(1, 1UL, "boat")))
    }

    // ─── GAP-017 history events honour exclusions ─────────────────

    private fun event(wallet: String, txid: String, dir: PaymentDirection, sat: ULong, t: Long?) = PaymentEvent(
        walletId = wallet, txidOrPaymentId = txid, kind = PaymentKind.ON_CHAIN, direction = dir,
        amountSat = sat, feeSat = null, timestampUnix = t?.toULong(), blockHeight = null, category = null,
        cryptoEventKind = null,
    )

    @Test
    fun `GAP-017 events of excluded wallets do not reach the net-worth history`() {
        val events = listOf(
            event("a", "t1", PaymentDirection.INCOMING, 1_000UL, 10),
            event("b", "t2", PaymentDirection.INCOMING, 5_000UL, 20),
            event("a", "t3", PaymentDirection.OUTGOING, 300UL, 30),
        )
        assertEquals(listOf(1_000L, -300L), netWorthHistoryEvents(events, setOf("b")).map { it.amountSat })
        assertEquals(3, netWorthHistoryEvents(events, emptySet()).size)
        assertTrue(netWorthHistoryEvents(events, setOf("a", "b")).isEmpty())
        assertEquals(30L, netWorthHistoryEvents(events, setOf("b")).last().blockTimeUnix)
    }

    // ─── PRT-121 / PRT-122 Portfolio balance sheet card ───────────

    private val wallet = GroupedWallet("Cold", "Watch (xpub)", "bitcoin", 4_000_000UL, setOf("w1"))

    @Test
    fun `PRT-121 the sheet opens the add menu, edits rows and shows the type badges`() =
        runDesktopComposeUiTest(width = 1600, height = 1200) {
            PriceCache.seedForTest(50_000.0)
            val calls = mutableListOf<String>()
            val etf = inv(1, manual = 100.0, ticker = "VWCE.DE", fetched = 100.0)
            val cash = asset(2, 1_000.0, "EUR")
            val mortgage = liability(3, 1_000_000UL, "mortgage")
            val card = liability(4, 500_000UL, "credit-card")
            val other = liability(5, 100_000UL, "boat")
            val nw = TrackerNetWorth(4_000_000UL, 2_000_000UL, 2_000_000UL, 1_600_000UL)
            setContent {
                BhodlTheme {
                    AssetPortfolioCard(
                        netWorth = nw,
                        walletGroups = listOf(wallet),
                        investments = listOf(etf),
                        investmentLotsByInvestmentId = mapOf(1L to listOf(lot(1, 1, 10.0, 90.0))),
                        manualAssets = listOf(cash),
                        liabilities = listOf(mortgage, card, other),
                        onAddInvestment = { calls += "addInv" },
                        onEditInvestment = { calls += "editInv${it.id}" },
                        onAddManualAsset = { calls += "addMa" },
                        onEditManualAsset = { calls += "editMa${it.id}" },
                        onAddLiability = { calls += "addLiab" },
                        onEditLiability = { calls += "editLiab${it.id}" },
                        period = TrackingPeriod.ALL,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(AssetPortfolioTags.CARD))
            for ((item, call) in listOf(
                AssetPortfolioTags.ADD_INVESTMENT to "addInv",
                AssetPortfolioTags.ADD_MANUAL_ASSET to "addMa",
                AssetPortfolioTags.ADD_LIABILITY to "addLiab",
            )) {
                onNodeWithTag(AssetPortfolioTags.ADD_MENU).performClick()
                waitUntilAtLeastOneExists(hasTestTag(item))
                onNodeWithTag(item).performClick()
                waitUntilDoesNotExist(hasTestTag(item))
                assertEquals(call, calls.last())
            }
            onNodeWithTag(AssetPortfolioTags.row("investment:1")).performClick()
            onNodeWithTag(AssetPortfolioTags.row("manual:2")).performClick()
            onNodeWithTag(AssetPortfolioTags.row("liability:4")).performClick()
            onNodeWithTag(AssetPortfolioTags.row("wallet:w1")).performClick() // read-only row
            assertEquals(listOf("editInv1", "editMa2", "editLiab4"), calls.drop(3))

            for (badge in listOf("WATCH", "ETF", "CASH", "MORTG.", "CREDIT", "OTHER")) {
                waitUntilAtLeastOneExists(hasText(badge), timeoutMillis = 2_000)
            }
            waitUntilAtLeastOneExists(hasText("today"))
            // Every ticker has a fetched price: no "Fetch prices" chip.
            waitUntilDoesNotExist(hasText("Fetch prices"))
        }

    @Test
    fun `PRT-121 a ticker without a fetched price shows the fetch chip that calls back`() =
        runDesktopComposeUiTest(width = 1600, height = 1200) {
            var refreshed = 0
            setContent {
                BhodlTheme {
                    AssetPortfolioCard(
                        netWorth = TrackerNetWorth(0UL, 0UL, 0UL, 0UL),
                        walletGroups = emptyList(),
                        investments = listOf(inv(1, ticker = "AAPL", manual = 10.0)),
                        manualAssets = emptyList(),
                        liabilities = emptyList(),
                        onAddInvestment = {}, onEditInvestment = {}, onAddManualAsset = {},
                        onEditManualAsset = {}, onAddLiability = {}, onEditLiability = {},
                        onRefreshPrices = { refreshed++ },
                        period = TrackingPeriod.ALL,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("Fetch prices"))
            onNodeWithText("Fetch prices").performClick()
            waitUntil(timeoutMillis = 2_000) { refreshed == 1 }
            // Empty sections say so.
            assertTrue(onAllNodesWithText("Nothing here yet.").fetchSemanticsNodes().size >= 3)
        }

    @Test
    fun `PRT-122 sheet rows carry their share of net worth and no-rate rows none`() {
        val nw = TrackerNetWorth(6_000_000UL, 2_000_000UL, 2_000_000UL, 0UL)
        val sheet = buildPortfolioSheet(
            netWorth = nw,
            walletGroups = listOf(wallet.copy(balanceSat = 6_000_000UL)),
            investments = listOf(inv(1, manual = 100.0), inv(2, ccy = "USD", manual = 5.0)),
            lotsByInvestmentId = mapOf(1L to listOf(lot(1, 1, 10.0, 90.0)), 2L to listOf(lot(2, 2, 1.0, 1.0))),
            manualAssets = listOf(asset(3, 1_000.0, "EUR")),
            liabilities = emptyList(),
            events = emptyList(),
            periodStartUnix = null,
            rateNow = { if (it == "EUR") 50_000.0 else null },
            rateAtStart = { null },
            walletsUpdatedUnix = null,
        )
        val rows = sheet.sections.flatMap { it.rows }.associateBy { it.key }
        assertEquals(60.0, rows.getValue("wallet:w1").pctOfNetWorth!!, 1e-9)
        assertEquals(20.0, rows.getValue("investment:1").pctOfNetWorth!!, 1e-9)
        assertEquals(null, rows.getValue("investment:2").pctOfNetWorth)
        assertEquals(20.0, rows.getValue("manual:3").pctOfNetWorth!!, 1e-9)
    }

    // ─── PRT-095 breakdown table rows ─────────────────────────────

    @Test
    fun `PRT-095 breakdown lists priced kinds with their weight and drops zero kinds`() =
        runDesktopComposeUiTest(width = 1600, height = 1200) {
            setContent {
                BhodlTheme {
                    NetWorthBreakdownTable(
                        walletAssetSat = 5_000_000UL,
                        investmentsAssetSat = 3_000_000UL,
                        investmentsPricingAvailable = true,
                        hasInvestments = true,
                        manualAssetsByKindSat = mapOf("cash" to 2_000_000UL, "vehicle" to 0UL),
                        hasManualAssets = true,
                        liabilities = listOf(liability(1, 1_000_000UL, "loan")),
                        fiatCurrency = FiatCurrency.NONE,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(NetWorthBreakdownTags.TABLE))
            for (label in listOf("Wallets (BTC)", "Investments (fiat)", "Cash (fiat)", "Liabilities")) {
                waitUntilAtLeastOneExists(hasText(label))
            }
            waitUntilDoesNotExist(hasText("Vehicles"))
            for (pct in listOf(50.0, 30.0, 20.0, 10.0)) {
                waitUntilAtLeastOneExists(hasText("%.1f%%".format(pct)))
            }
        }

    @Test
    fun `PRT-095 unpriced investments show a dash and manual assets collapse to one row`() =
        runDesktopComposeUiTest(width = 1600, height = 1200) {
            setContent {
                BhodlTheme {
                    NetWorthBreakdownTable(
                        walletAssetSat = 5_000_000UL,
                        investmentsAssetSat = 0UL,
                        investmentsPricingAvailable = false,
                        hasInvestments = true,
                        manualAssetsByKindSat = mapOf("cash" to 2_000_000UL),
                        hasManualAssets = true,
                        liabilities = emptyList(),
                        fiatCurrency = FiatCurrency.NONE,
                    )
                }
            }
            waitUntilAtLeastOneExists(hasText("Manual assets"))
            waitUntilDoesNotExist(hasText("Cash (fiat)"))
            assertTrue(onAllNodesWithText("—").fetchSemanticsNodes().size >= 4) // 2 rows × value + weight
            waitUntilAtLeastOneExists(hasText("%.1f%%".format(100.0)))
        }

    // ─── PRT-033 / PRT-150 investments table ──────────────────────

    @Test
    fun `PRT-033 caret shows for positions with lots or an empty seed-ready row, not for legacy aggregates`() =
        runDesktopComposeUiTest(width = 1600, height = 1200) {
            val added = mutableListOf<Long>()
            setContent {
                BhodlTheme {
                    InvestmentsCard(
                        investments = listOf(inv(1), inv(2), inv(3, qty = 4.0, cost = 100.0)),
                        lotsByInvestmentId = mapOf(1L to listOf(lot(1, 1, 2.0, 10.0))),
                        onAdd = {},
                        onEdit = {},
                        onAddLot = { added += it },
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.expandTag(1)), timeoutMillis = 3_000)
            waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.expandTag(2)))
            waitUntilDoesNotExist(hasTestTag(InvestmentTags.expandTag(3)))

            onNodeWithTag(InvestmentTags.expandTag(2), useUnmergedTree = true).performClick()
            waitUntilAtLeastOneExists(hasText("No lots yet for this position."))
            waitUntilDoesNotExist(hasTestTag(InvestmentTags.sellTag(2)))
            onNodeWithTag(InvestmentTags.addLotTag(2)).performClick()
            assertEquals(listOf(2L), added)

            onNodeWithTag(InvestmentTags.expandTag(1), useUnmergedTree = true).performClick()
            waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.lotRowTag(1)))
            waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.sellTag(1)))
        }

    private fun androidx.compose.ui.test.ComposeUiTest.usdValueCell(cacheUsd: Boolean): String {
        PriceCache.seedForTest(50_000.0)
        if (cacheUsd) PriceCache.seedCurrencyRatesForTest(mapOf("USD" to 40_000.0))
        val aapl = inv(1, ccy = "USD", manual = 200.0, ticker = "AAPL", type = "stock")
        setContent {
            BhodlTheme {
                InvestmentsCard(
                    investments = listOf(aapl),
                    lotsByInvestmentId = mapOf(1L to listOf(lot(1, 1, 10.0, 150.0))),
                    onAdd = {},
                    onEdit = {},
                )
            }
        }
        waitUntil(timeoutMillis = 3_000) {
            onAllNodesWithTag(InvestmentTags.valueTag(1), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        return onNodeWithTag(InvestmentTags.valueTag(1), useUnmergedTree = true).printToString()
    }

    @Test
    fun `PRT-150 a USD position on a EUR dashboard is valued in BTC at the cached USD rate`() =
        runDesktopComposeUiTest(width = 1600, height = 1200) {
            // 10 × 200 USD = 2000 USD / 40 000 = 0.05 BTC = 5,000,000 sat.
            val cell = usdValueCell(cacheUsd = true)
            assertTrue("5,000,000" in cell, cell)
            assertTrue("2,000.00" in cell, cell)
        }

    @Test
    fun `PRT-150 without the USD rate the BTC side is not guessed from the EUR spot`() =
        runDesktopComposeUiTest(width = 1600, height = 1200) {
            val cell = usdValueCell(cacheUsd = false)
            assertTrue("5,000,000" !in cell && "4,000,000" !in cell, cell)
            assertTrue("2,000.00" in cell, cell)
        }
}
