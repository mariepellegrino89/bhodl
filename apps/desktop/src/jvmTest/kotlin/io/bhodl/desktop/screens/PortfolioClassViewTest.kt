package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.prices.NetWorthHistoryClient.NwSnapshot
import io.bhodl.desktop.screens.PortfolioFixtures.NOW
import io.bhodl.desktop.screens.PortfolioFixtures.TODAY
import io.bhodl.desktop.screens.PortfolioFixtures.asset
import io.bhodl.desktop.screens.PortfolioFixtures.disposal
import io.bhodl.desktop.screens.PortfolioFixtures.investment
import io.bhodl.desktop.screens.PortfolioFixtures.liability
import io.bhodl.desktop.screens.PortfolioFixtures.lot
import io.bhodl.desktop.screens.PortfolioFixtures.row
import io.bhodl.desktop.screens.PortfolioFixtures.sell
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portfolio redesign 2026-10 — class cards + list + detail card
 * ([PortfolioClassView]), the details editor and the Cards / Table
 * toggle. The last test renders the page to PNG for the visual review
 * (build/screenshots/).
 */
@OptIn(ExperimentalTestApi::class)
class PortfolioClassViewTest {

    @BeforeTest fun setUp() { SettingsStore.reset(); io.bhodl.desktop.data.TrackerCache.reset() }
    @AfterTest fun tearDown() { io.bhodl.desktop.data.TrackerCache.reset(); SettingsStore.reset() }

    private val etf = investment(1, "VWCE · FTSE All-World", "etf", qty = 1180.0, cost = 116_112.0, price = 121.3, ticker = "VWCE.DE")
    private val gold = investment(2, "Oro · lingotti", "commodity", qty = 320.0, cost = 21_950.0, price = 81.2, unit = "g")
    private val usd = investment(3, "Conto in dollari", "currency", qty = 12_400.0, cost = 11_725.0, price = 0.9258, unit = "USD")
    private val lots = mapOf(
        1L to listOf(
            lot(11, 1, qty = 240.0, price = 92.1, daysAgo = 720, original = 300.0, fees = 9.0),
            lot(12, 1, qty = 320.0, price = 96.8, daysAgo = 590, fees = 9.0),
            lot(13, 1, qty = 380.0, price = 99.5, daysAgo = 480, fees = 9.0),
            lot(14, 1, qty = 240.0, price = 106.2, daysAgo = 250, fees = 9.0),
        ),
    )
    private val sells = mapOf(1L to listOf(sell(5, 1, qty = 60.0, price = 118.0, daysAgo = 43)))
    private val disposals = mapOf(1L to listOf(disposal(1, 5, 11, qty = 60.0, basis = 92.13, gain = 1552.0)))
    private val house = asset(7, "Casa", "real_estate", value = 310_000.0, purchase = 265_000.0, purchasedDaysAgo = 2400, label = "Milano")
    private val deposit = asset(8, "Conto deposito", "cash", value = 25_000.0, purchasedDaysAgo = 420)
    private val car = asset(9, "Auto", "vehicle", value = 18_000.0, purchase = 28_000.0, purchasedDaysAgo = 1400)
    private val mortgage = liability(20, "Mutuo casa", "mortgage", sat = 146_000_000, label = "tasso fisso")
    private val card = liability(21, "Carta di credito", "credit-card", sat = 1_720_000, label = "Visa")
    private val details = PortfolioDetails(
        investments = mapOf(1L to mapOf("isin" to "IE00BK5BQT80", "ter_pct" to "0.22", "distribution" to "acc", "broker" to "Fineco")),
        manualAssets = mapOf(
            7L to mapOf("city" to "Milano", "surface_m2" to "85", "use" to "primary"),
            8L to mapOf("bank" to "Banca X", "interest_pct" to "3.25", "maturity_date" to TODAY.plusDays(140).toString(), "locked" to "true"),
        ),
        liabilities = mapOf(
            20L to mapOf("original_amount_sat" to "236000000", "rate_pct" to "2.9", "rate_type" to "fixed", "monthly_payment" to "780", "end_date" to TODAY.plusMonths(215).toString(), "linked_asset_id" to "7"),
            21L to mapOf("credit_limit_sat" to "4200000", "statement_day" to "15"),
        ),
    )

    private fun sheet(): PortfolioSheet {
        val w = listOf(
            row(SheetSection.WALLETS, null, "Cold storage", "xpub", 120_000_000, pct = 19.6, change = 0),
            row(SheetSection.WALLETS, null, "Main", "xpub", 26_300_000, pct = 4.3, change = 1_560_000),
        )
        val i = listOf(
            row(SheetSection.INVESTMENTS, 1, etf.name, "etf", 199_000_000, pct = 32.6, change = 5_180_000),
            row(SheetSection.INVESTMENTS, 2, gold.name, "commodity", 36_100_000, pct = 5.9, change = -310_000),
            row(SheetSection.INVESTMENTS, 3, usd.name, "currency", 15_900_000, pct = 2.6, change = -120_000),
        )
        val m = listOf(
            row(SheetSection.MANUAL_ASSETS, 7, house.name, "real_estate", 431_000_000, pct = 70.6, change = -2_580_000),
            row(SheetSection.MANUAL_ASSETS, 8, deposit.name, "cash", 34_800_000, pct = 5.7, change = -110_000),
            row(SheetSection.MANUAL_ASSETS, 9, car.name, "vehicle", 25_000_000, pct = 4.1, change = -80_000),
        )
        val l = listOf(
            row(SheetSection.LIABILITIES, 20, mortgage.name, "mortgage", -146_000_000, pct = -23.9, change = 0),
            row(SheetSection.LIABILITIES, 21, card.name, "credit-card", -1_720_000, pct = -0.3, change = 0),
        )
        fun s(sec: SheetSection, rows: List<SheetRow>) =
            SheetSectionModel(sec, rows, rows.sumOf { it.valueSat ?: 0L }, rows.sumOf { it.changeSat ?: 0L }, rows.sumOf { it.pctOfNetWorth ?: 0.0 })
        val sections = listOf(s(SheetSection.WALLETS, w), s(SheetSection.INVESTMENTS, i), s(SheetSection.MANUAL_ASSETS, m), s(SheetSection.LIABILITIES, l))
        return PortfolioSheet(sections, sections.sumOf { it.subtotalSat }, sections.sumOf { it.changeSat ?: 0L })
    }

    private fun history(): List<NwSnapshot> = (0 until 30).map { d ->
        val t = NOW - (29 - d) * 86_400L
        val wobble = kotlin.math.sin(d / 3.0)
        NwSnapshot(
            dateUnix = t,
            totalSat = 0,
            onchainSat = (140_000_000 + d * 200_000 + wobble * 1_000_000).toLong(),
            investmentsSat = (240_000_000 + d * 300_000 + wobble * 2_000_000).toLong(),
            manualAssetsSat = (500_000_000 - d * 250_000 + wobble * 1_500_000).toLong(),
            liabilitiesSat = (149_000_000 - d * 90_000).toLong(),
            fiatEquivalent = 0.0,
            rateBtcFiat = 70_000.0,
        )
    }

    @Composable
    private fun View(
        onEditDetails: (DetailsEditRequest) -> Unit = {},
        onSell: (Long) -> Unit = {},
        onAddLot: (Long) -> Unit = {},
        sheet: PortfolioSheet = sheet(),
    ) {
        PortfolioClassView(
            sheet = sheet,
            walletGroups = emptyList(),
            investments = listOf(etf, gold, usd),
            lotsByInvestmentId = lots,
            sellsByInvestmentId = sells,
            disposalsByInvestmentId = disposals,
            manualAssets = listOf(house, deposit, car),
            liabilities = listOf(mortgage, card),
            details = details,
            satToFiat = { sat, _ -> sat / 100_000_000.0 * 71_900.0 },
            onEditInvestment = {},
            onEditManualAsset = {},
            onEditLiability = {},
            onEditDetails = onEditDetails,
            onAddLot = onAddLot,
            onEditLot = {},
            onSell = onSell,
            onDeleteSell = {},
            history = history(),
            nowUnix = NOW,
        )
    }

    @Test
    fun `record buy asks which holding when there are several and opens the lot straight away with one`() = runDesktopComposeUiTest(width = 1440, height = 1100) {
        var picked: Long? = null
        var several by androidx.compose.runtime.mutableStateOf(true)
        val one = sheet().let { s ->
            s.copy(sections = s.sections.map { if (it.section == SheetSection.INVESTMENTS) it.copy(rows = it.rows.filter { r -> r.recordId == 2L }) else it })
        }
        setContent { BhodlTheme { if (several) View(onAddLot = { picked = it }) else View(onAddLot = { picked = it }, sheet = one) } }
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.INVESTMENTS)).performClick()
        onNodeWithTag(PortfolioCardsTags.PANEL_BUY).performClick()
        assertNull(picked, "several holdings: a choice first")
        onNode(hasText(gold.name) and hasAnyAncestor(androidx.compose.ui.test.isPopup())).performClick()
        assertEquals(2L, picked)

        picked = null
        several = false
        waitForIdle()
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.INVESTMENTS)).performClick()
        onNodeWithTag(PortfolioCardsTags.PANEL_BUY).performClick()
        assertEquals(2L, picked, "one holding: no question")
    }

    @Test
    fun `the dashed add buttons open the investment, asset, debt and wallet forms`() = runDesktopComposeUiTest(width = 1440, height = 1300) {
        io.bhodl.desktop.data.TrackerCache.seedForTest(investments = listOf(etf))
        var addWallet = 0
        for ((button, form) in listOf(
            PortfolioCardsTags.ADD_INVESTMENT to InvestmentTags.INPUT_NAME,
            PortfolioCardsTags.ADD_MANUAL to ManualAssetTags.INPUT_NAME,
            PortfolioCardsTags.ADD_LIABILITY to LiabilityTags.INPUT_NAME,
        )) {
            setContent { BhodlTheme { TrackingScreen(section = io.bhodl.desktop.navigation.TrackerSection.PORTFOLIO, onAddWatch = { addWallet++ }) } }
            waitUntilAtLeastOneExists(hasTestTag(button), timeoutMillis = 5_000)
            onNodeWithTag(button).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasTestTag(form), timeoutMillis = 3_000)
        }
        setContent { BhodlTheme { TrackingScreen(section = io.bhodl.desktop.navigation.TrackerSection.PORTFOLIO, onAddWatch = { addWallet++ }) } }
        waitUntilAtLeastOneExists(hasTestTag(PortfolioCardsTags.ADD_WALLET), timeoutMillis = 5_000)
        onNodeWithTag(PortfolioCardsTags.ADD_WALLET).performScrollTo().performClick()
        assertEquals(1, addWallet)
    }

    @Test
    fun `class panel numbers - cost, unrealized, vs HODL and per-row P&L`() {
        val inv = classSummary(PortfolioClass.INVESTMENTS, sheet().sections[1].rows, listOf(etf, gold, usd), lots, emptyList())
        assertEquals("EUR", inv.costCurrency)
        assertEquals(18.378, inv.rowPl["investments:2"]!!, 0.01, "gold: 320 g × 81.2 vs 21,950")
        assertEquals(-2.09, inv.rowPl["investments:3"]!!, 0.01)
        assertTrue(inv.unrealizedPct!! > 0.0)
        assertNull(inv.vsHodlPct, "no lot has a BTC rate")

        val rated = investment(4, "Rated", "stock", qty = 10.0, cost = 1_000.0, price = 150.0)
        val ratedLot = lot(41, 4, qty = 10.0, price = 100.0, daysAgo = 100).copy(btcPerUnitAtPurchase = 50_000.0)
        val hodl = classSummary(
            PortfolioClass.INVESTMENTS,
            listOf(row(SheetSection.INVESTMENTS, 4, "Rated", "stock", 1_500_000)),
            listOf(rated), mapOf(4L to listOf(ratedLot)), emptyList(),
        )
        assertEquals(-25.0, hodl.vsHodlPct!!, 0.001, "1,000 EUR at 50,000 = 2,000,000 sat, worth 1,500,000 now")

        val man = classSummary(PortfolioClass.MANUAL_ASSETS, sheet().sections[2].rows, emptyList(), emptyMap(), listOf(house, deposit, car))
        assertEquals(16.98, man.rowPl["manual_assets:7"]!!, 0.01)
        assertEquals(-35.71, man.rowPl["manual_assets:9"]!!, 0.01)
        assertEquals(-0.315, man.rowPl["manual_assets:8"]!!, 0.01, "no purchase value: change over the period")
        assertEquals(11.945, man.unrealizedPct!!, 0.01)
    }

    @Test
    fun `the first class with items is open and its biggest item is shown`() = runDesktopComposeUiTest(width = 1440, height = 2400) {
        setContent { BhodlTheme(useDarkTheme = true) { View() } }
        PortfolioClass.entries.forEach { onNodeWithTag(PortfolioCardsTags.classCard(it)).assertExists() }
        onNodeWithTag(PortfolioCardsTags.PANEL).assertExists()
        onNodeWithTag(PortfolioCardsTags.item("wallets:Cold storage")).assertExists()
        assertEquals(0, onAllNodes(hasTestTag(PortfolioCardsTags.DETAIL)).fetchSemanticsNodes().size, "proposal B: no item card under the panel")
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.INVESTMENTS)).performClick()
        onNodeWithTag(PortfolioCardsTags.item("investments:2")).performClick()
        onNodeWithTag(PortfolioCardsTags.DETAIL_TITLE).assertTextContains("Oro", substring = true)
        onNodeWithTag(PortfolioCardsTags.BACK).performClick()
        onNodeWithTag(PortfolioCardsTags.PANEL).assertExists()
        onNodeWithTag(PortfolioCardsTags.PANEL_CLOSE).performClick()
        assertEquals(0, onAllNodes(hasTestTag(PortfolioCardsTags.PANEL)).fetchSemanticsNodes().size)
    }

    @Test
    fun `an ETF card shows its lots, sales and TER cost and offers Sell`() = runDesktopComposeUiTest(width = 1440, height = 2400) {
        var sold: Long? = null
        setContent { BhodlTheme(useDarkTheme = true) { View(onSell = { sold = it }) } }
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.INVESTMENTS)).performClick()
        onNodeWithTag(PortfolioCardsTags.item("investments:1")).performClick()
        onNode(hasText("IE00BK5BQT80", substring = true) and hasAnyAncestor(hasTestTag(PortfolioCardsTags.ATTRS))).assertExists()
        onNode(hasText("Yearly fund cost", substring = true) and hasAnyAncestor(hasTestTag(PortfolioCardsTags.ATTRS))).assertExists()
        waitUntilAtLeastOneExists(hasText("Sales"))
        onNodeWithTag(PortfolioCardsTags.SELL).performClick()
        assertEquals(1L, sold)
    }

    @Test
    fun `a mortgage card resolves its linked house and the house shows the LTV`() = runDesktopComposeUiTest(width = 1440, height = 2400) {
        setContent { BhodlTheme(useDarkTheme = true) { View() } }
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.LIABILITIES)).performClick()
        onNodeWithTag(PortfolioCardsTags.item("liabilities:20")).performClick()
        onNode(hasText("Casa", substring = true) and hasAnyAncestor(hasTestTag(PortfolioCardsTags.ATTRS))).assertExists()
        onNode(hasText("Repaid", substring = true) and hasAnyAncestor(hasTestTag(PortfolioCardsTags.METER))).assertExists()
        onNodeWithTag(PortfolioCardsTags.BACK).performClick()
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.MANUAL_ASSETS)).performClick()
        onNodeWithTag(PortfolioCardsTags.item("manual_assets:7")).performClick()
        onNode(hasText("LTV", substring = true) and hasAnyAncestor(hasTestTag(PortfolioCardsTags.METER))).assertExists()
    }

    @Test
    fun `Details opens the editor with the row's current values`() = runDesktopComposeUiTest(width = 1440, height = 2400) {
        var request: DetailsEditRequest? = null
        setContent { BhodlTheme(useDarkTheme = true) { View(onEditDetails = { request = it }) } }
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.MANUAL_ASSETS)).performClick()
        onNodeWithTag(PortfolioCardsTags.item("manual_assets:8")).performClick()
        onNodeWithTag(PortfolioCardsTags.EDIT_DETAILS).performClick()
        val r = assertNotNull(request)
        assertEquals(DetailCategory.CASH, r.category)
        assertEquals(8L, r.recordId)
        assertEquals("Banca X", r.current["bank"])
    }

    @Test
    fun `the details editor saves typed values as canonical JSON`() = runDesktopComposeUiTest(width = 1440, height = 2400) {
        var saved: String? = null
        val req = DetailsEditRequest(DetailCategory.MORTGAGE, 20, "Mutuo", mapOf("rate_pct" to "2.9"))
        setContent {
            BhodlTheme(useDarkTheme = true) {
                PortfolioDetailsDialog(req, listOf(house, deposit), onDismiss = {}, onSave = { saved = it })
            }
        }
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag(PortfolioCardsTags.DETAILS_DIALOG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(PortfolioCardsTags.detailsField("monthly_payment"), useUnmergedTree = true).performTextInput("780,50")
        onNodeWithTag(PortfolioCardsTags.detailsField("end_date"), useUnmergedTree = true).performTextInput("2044-09-01")
        onNode(hasText("Fixed"), useUnmergedTree = true).performClick()
        onNode(hasText("Casa"), useUnmergedTree = true).performClick()
        onNodeWithTag(PortfolioCardsTags.DETAILS_SAVE, useUnmergedTree = true).performClick()
        assertEquals(
            """{"rate_pct":2.9,"rate_type":"fixed","monthly_payment":780.5,"end_date":"2044-09-01","linked_asset_id":"7"}""",
            saved,
        )
    }

    @Test
    fun `the Cards and Table toggle switches the portfolio view`() = runDesktopComposeUiTest(width = 1440, height = 2400) {
        var view = PortfolioView.CARDS
        setContent {
            BhodlTheme(useDarkTheme = true) {
                AssetPortfolioCard(
                    netWorth = TrackerNetWorth(0UL, 0UL, 0UL, 0UL),
                    walletGroups = emptyList(),
                    investments = emptyList(),
                    manualAssets = emptyList(),
                    liabilities = emptyList(),
                    onAddInvestment = {}, onEditInvestment = {}, onAddManualAsset = {}, onEditManualAsset = {},
                    onAddLiability = {}, onEditLiability = {},
                    view = view,
                    onViewChange = { view = it },
                )
            }
        }
        onNodeWithTag(PortfolioCardsTags.VIEW).assertExists()
        onNodeWithTag(PortfolioCardsTags.EMPTY_CLASS).assertExists()
        onNodeWithTag(AssetPortfolioTags.view(PortfolioView.TABLE)).performClick()
        assertEquals(PortfolioView.TABLE, view)
    }

    @Test
    fun `render the portfolio cards for the visual review`() = runDesktopComposeUiTest(width = 1440, height = 1500) {
        setContent {
            BhodlTheme(useDarkTheme = true) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier
                        .fillMaxSize()
                        .background(BhodlTheme.colors.background)
                        .padding(24.dp),
                ) { View() }
            }
        }
        waitForIdle()
        shot("portfolio-bitcoin")
        listOf(PortfolioClass.INVESTMENTS, PortfolioClass.MANUAL_ASSETS, PortfolioClass.LIABILITIES).forEach { c ->
            onNodeWithTag(PortfolioCardsTags.classCard(c)).performClick()
            waitForIdle()
            shot("portfolio-${c.name.lowercase()}")
        }
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.INVESTMENTS)).performClick()
        onNodeWithTag(PortfolioCardsTags.item("investments:1")).performClick()
        waitForIdle()
        shot("portfolio-drilldown-etf")
        onNodeWithTag(PortfolioCardsTags.BACK).performClick()
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.MANUAL_ASSETS)).performClick()
        onNodeWithTag(PortfolioCardsTags.item("manual_assets:8")).performClick()
        waitForIdle()
        shot("portfolio-deposit")
    }

    @Test
    fun `render the portfolio cards in the light theme`() = runDesktopComposeUiTest(width = 1440, height = 1100) {
        setContent {
            BhodlTheme(useDarkTheme = false) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.fillMaxSize().background(BhodlTheme.colors.background).padding(24.dp),
                ) { View() }
            }
        }
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.INVESTMENTS)).performClick()
        waitForIdle()
        shot("portfolio-light-investments")
        onNodeWithTag(PortfolioCardsTags.classCard(PortfolioClass.LIABILITIES)).performClick()
        waitForIdle()
        shot("portfolio-light-liabilities")
    }

    private fun SemanticsNodeInteractionsProvider.shot(name: String) {
        val dir = File(System.getProperty("bhodl.screenshots", "build/screenshots")).apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}

