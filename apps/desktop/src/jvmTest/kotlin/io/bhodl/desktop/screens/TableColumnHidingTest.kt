package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.TableColumnLayout
import io.bhodl.desktop.ui.TableColumn
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.TrackingRuleFfi
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UX review 2026-10 (item 17) — every dense table hides its
 * low-priority columns on a narrow window instead of clipping them:
 * the visible-column sets per width, and each table rendered wide
 * (every column) and narrow (only the essentials).
 */
@OptIn(ExperimentalTestApi::class)
class TableColumnHidingTest {

    @BeforeTest fun setup() = SettingsStore.reset()

    @AfterTest fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    // ─── Visible-column sets ────────────────────────────────────────

    @Test
    fun `addresses drop index then label`() {
        assertEquals(AddressCol.entries, ADDRESS_TABLE.visible(1_000))
        assertEquals(AddressCol.entries - AddressCol.INDEX, ADDRESS_TABLE.visible(430))
        assertEquals(listOf(AddressCol.ADDRESS, AddressCol.STATUS), ADDRESS_TABLE.visible(400))
        assertEquals(listOf(AddressCol.ADDRESS, AddressCol.STATUS), ADDRESS_TABLE.visible(360))
    }

    @Test
    fun `utxos drop type, address, then outpoint`() {
        assertEquals(UtxoCol.entries, UTXO_TABLE.visible(1_000))
        assertEquals(UtxoCol.entries - UtxoCol.TYPE, UTXO_TABLE.visible(620))
        assertEquals(listOf(UtxoCol.OUTPOINT, UtxoCol.STATUS, UtxoCol.AMOUNT), UTXO_TABLE.visible(500))
        assertEquals(listOf(UtxoCol.STATUS, UtxoCol.AMOUNT), UTXO_TABLE.visible(360))
    }

    @Test
    fun `wallet list drops network then type`() {
        assertEquals(WalletListCol.entries, WALLET_LIST_TABLE.visible(1_000))
        assertEquals(WalletListCol.entries - WalletListCol.NETWORK, WALLET_LIST_TABLE.visible(400))
        assertEquals(listOf(WalletListCol.NAME, WalletListCol.BALANCE), WALLET_LIST_TABLE.visible(360))
    }

    @Test
    fun `investments keep name, value and BTC P&L`() {
        assertEquals(InvestmentCol.entries, INVESTMENT_TABLE.visible(1_000))
        assertEquals(InvestmentCol.entries - InvestmentCol.TYPE, INVESTMENT_TABLE.visible(640))
        assertEquals(
            listOf(InvestmentCol.NAME, InvestmentCol.VALUE, InvestmentCol.PNL_BTC),
            INVESTMENT_TABLE.visible(400),
        )
        assertEquals(INVESTMENT_TABLE.visible(400), INVESTMENT_TABLE.visible(360))
    }

    @Test
    fun `manual assets keep name, value now and delta BTC`() {
        assertEquals(ManualAssetCol.entries, MANUAL_ASSET_TABLE.visible(1_000))
        assertEquals(
            listOf(ManualAssetCol.NAME, ManualAssetCol.VALUE_NOW, ManualAssetCol.DELTA_BTC),
            MANUAL_ASSET_TABLE.visible(400),
        )
    }

    @Test
    fun `rules drop only the position number`() {
        assertEquals(RuleCol.entries, RULE_TABLE.visible(400))
        assertEquals(RuleCol.entries - RuleCol.ORDER, RULE_TABLE.visible(360))
    }

    @Test
    fun `budget keeps category, budget and actual`() {
        assertEquals(BudgetCol.entries, BUDGET_TABLE.visible(1_000))
        assertEquals(BudgetCol.entries - BudgetCol.PROGRESS, BUDGET_TABLE.visible(560))
        assertEquals(
            listOf(BudgetCol.SOURCE, BudgetCol.BUDGET, BudgetCol.ACTUAL),
            BUDGET_TABLE.visible(400),
        )
    }

    @Test
    fun `net worth breakdown drops the share last`() {
        assertEquals(NetWorthBreakdownCol.entries, NET_WORTH_BREAKDOWN_TABLE.visible(360))
        assertEquals(
            listOf(NetWorthBreakdownCol.CATEGORY, NetWorthBreakdownCol.VALUE),
            NET_WORTH_BREAKDOWN_TABLE.visible(300),
        )
    }

    // ─── Rendered tables ────────────────────────────────────────────

    private fun ComposeUiTest.renderAt(widthDp: Int, content: @Composable () -> Unit) {
        setContent { BhodlTheme { Box(Modifier.width(widthDp.dp)) { content() } } }
    }

    /** The header shows exactly [visible] of [table]'s columns. */
    private fun <C : TableColumn> ComposeUiTest.assertHeader(table: TableColumnLayout<C>, visible: List<C>) {
        waitUntilAtLeastOneExists(hasTestTag(table.headerTag(visible.first())), timeoutMillis = 5_000)
        for (c in table.columns) {
            val node = onNodeWithTag(table.headerTag(c), useUnmergedTree = true)
            if (c in visible) node.assertExists() else node.assertDoesNotExist()
        }
    }

    private val address = AddressRecord(
        index = 7u,
        address = "tb1qexampleaddressxxxxxxxxxxxxxxxxxxxxxxxx",
        isChange = false,
        used = false,
        label = "Savings",
        category = null,
    )

    @Composable
    private fun AddressFixture() = AddressTable(listOf(address), error = null, onShowInReceive = {})

    @Test
    fun `addresses table shows every column when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { AddressFixture() }
        assertHeader(ADDRESS_TABLE, AddressCol.entries)
        onNodeWithText("Savings", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `addresses table hides index and label when narrow`() = runDesktopComposeUiTest {
        renderAt(400) { AddressFixture() }
        assertHeader(ADDRESS_TABLE, listOf(AddressCol.ADDRESS, AddressCol.STATUS))
        onNodeWithText("Savings", useUnmergedTree = true).assertDoesNotExist()
        // Used / unused and the Show link stay.
        onNodeWithTag(WalletTabTags.addressStatus(address.address), useUnmergedTree = true).assertExists()
        onNodeWithTag(WalletTabTags.addressShow(address.address), useUnmergedTree = true).assertExists()
    }

    private val utxo = UtxoRecord(
        outpoint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:1",
        valueSat = 21_000uL,
        address = "tb1qexampleaddressxxxxxxxxxxxxxxxxxxxxxxxx",
        isChange = true,
        confirmed = true,
    )

    @Composable
    private fun UtxoFixture() = UtxoTable(
        utxos = listOf(utxo),
        amounts = rememberColumnAmountFormat(listOf(21_000L)),
    )

    @Test
    fun `utxo table shows every column when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { UtxoFixture() }
        assertHeader(UTXO_TABLE, UtxoCol.entries)
    }

    @Test
    fun `utxo table keeps status and amount when narrow`() = runDesktopComposeUiTest {
        renderAt(400) { UtxoFixture() }
        assertHeader(UTXO_TABLE, listOf(UtxoCol.STATUS, UtxoCol.AMOUNT))
        onNodeWithTag(WalletTabTags.utxoRow(utxo.outpoint), useUnmergedTree = true).assertExists()
    }

    @Composable
    private fun WalletListFixture() = WalletListTable(
        wallets = listOf(
            WalletRecordFfi(
                id = "w1",
                label = "Cold storage",
                kind = WalletKindFfi.WATCH_XPUB,
                descriptorPub = "wpkh(xpub.../0/*)",
                network = "signet",
                createdAtUnix = 0,
            ),
        ),
        watchBalances = mapOf("w1" to 50_000uL),
        onOpenWallet = {},
    )

    @Test
    fun `wallet list shows every column when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { WalletListFixture() }
        assertHeader(WALLET_LIST_TABLE, WalletListCol.entries)
    }

    @Test
    fun `wallet list hides network then type when narrow`() = runDesktopComposeUiTest {
        renderAt(400) { WalletListFixture() }
        assertHeader(WALLET_LIST_TABLE, WalletListCol.entries - WalletListCol.NETWORK)
        onNodeWithText("signet", useUnmergedTree = true).assertDoesNotExist()
    }

    private val investment = TrackingInvestmentFfi(
        id = 1L,
        name = "VWCE",
        assetType = "etf",
        ticker = "VWCE",
        quantity = 2.0,
        unit = "shares",
        costBasisFiat = 200.0,
        costBasisCurrency = "EUR",
        purchaseUnix = 1_000L,
        manualCurrentValue = 120.0,
        fetchedCurrentValue = null,
        lastPricedAtUnix = null,
        notes = null,
        costBasisMethod = "fifo",
        createdAtUnix = 0L,
    )

    @Composable
    private fun InvestmentsFixture() = InvestmentsCard(investments = listOf(investment), onAdd = {}, onEdit = {})

    @Test
    fun `investments show every column when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { InvestmentsFixture() }
        assertHeader(INVESTMENT_TABLE, InvestmentCol.entries)
        onNodeWithTag(InvestmentTags.costTag(1L), useUnmergedTree = true).assertExists()
        onNodeWithTag(InvestmentTags.TOTALS_COST, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `investments keep name, value and BTC P&L when narrow`() = runDesktopComposeUiTest {
        renderAt(400) { InvestmentsFixture() }
        assertHeader(INVESTMENT_TABLE, listOf(InvestmentCol.NAME, InvestmentCol.VALUE, InvestmentCol.PNL_BTC))
        // Rows and the totals row drop the same cells as the header.
        onNodeWithTag(InvestmentTags.costTag(1L), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(InvestmentTags.TOTALS_COST, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(InvestmentTags.valueTag(1L), useUnmergedTree = true).assertExists()
        onNodeWithTag(InvestmentTags.pnlBtcTag(1L), useUnmergedTree = true).assertExists()
        onNodeWithTag(InvestmentTags.TOTALS_VALUE, useUnmergedTree = true).assertExists()
    }

    private val asset = TrackingManualAssetFfi(
        id = 100L,
        name = "Family home",
        kind = "real_estate",
        valueFiat = 288_000.0,
        currency = "EUR",
        displayLabel = null,
        notes = null,
        lastUpdatedUnix = 0L,
        createdAtUnix = 0L,
        purchaseUnix = 1_622_505_600L,
        purchaseValueFiat = 250_000.0,
        purchaseCurrency = "EUR",
        purchaseBtcAtTime = 50_000.0,
    )

    @Composable
    private fun ManualAssetsFixture() = ManualAssetsCard(assets = listOf(asset), onAdd = {}, onEdit = {})

    @Test
    fun `manual assets show every column when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { ManualAssetsFixture() }
        assertHeader(MANUAL_ASSET_TABLE, ManualAssetCol.entries)
        onNodeWithTag(ManualAssetTags.valueAtBuyTag(100L), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `manual assets hide type and value at buy when narrow`() = runDesktopComposeUiTest {
        renderAt(400) { ManualAssetsFixture() }
        assertHeader(
            MANUAL_ASSET_TABLE,
            listOf(ManualAssetCol.NAME, ManualAssetCol.VALUE_NOW, ManualAssetCol.DELTA_BTC),
        )
        onNodeWithTag(ManualAssetTags.valueAtBuyTag(100L), useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ManualAssetTags.valueNowTag(100L), useUnmergedTree = true).assertExists()
        onNodeWithTag(ManualAssetTags.deltaBtcTag(100L), useUnmergedTree = true).assertExists()
    }

    private val rule = TrackingRuleFfi(
        id = 5L,
        name = "Groceries",
        conditionField = "label",
        conditionOp = "contains",
        conditionValue = "market",
        actionField = "category",
        actionValue = "food",
        priority = 0,
        enabled = true,
        createdAtUnix = 0L,
    )

    @Composable
    private fun RulesFixture() = RulesCard(rules = listOf(rule), onAdd = {}, onEdit = {}, onReapply = {})

    @Test
    fun `rules show the position when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { RulesFixture() }
        waitUntilAtLeastOneExists(hasTestTag(RuleTags.row(5L)), timeoutMillis = 5_000)
        onNodeWithText("1.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `rules hide only the position when narrow`() = runDesktopComposeUiTest {
        renderAt(360) { RulesFixture() }
        waitUntilAtLeastOneExists(hasTestTag(RuleTags.row(5L)), timeoutMillis = 5_000)
        onNodeWithText("1.", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("→ food", useUnmergedTree = true).assertExists()
        onNodeWithTag(RuleTags.moveUp(5L), useUnmergedTree = true).assertExists()
    }

    private val budgetRow = BudgetRow(
        category = "food",
        budgetedSat = 100_000uL,
        actualSat = 120_000uL,
        direction = TrackingDirection.EXPENSE,
    )

    @Composable
    private fun BudgetFixture() = BudgetBreakdownCard(title = "Expenses", rows = listOf(budgetRow), onEditBudget = { _, _ -> })

    @Test
    fun `budget shows every column when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { BudgetFixture() }
        assertHeader(BUDGET_TABLE, BudgetCol.entries)
        onNodeWithTag(BudgetTags.statusChip("food"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `budget keeps category, budget and actual when narrow`() = runDesktopComposeUiTest {
        renderAt(400) { BudgetFixture() }
        assertHeader(BUDGET_TABLE, listOf(BudgetCol.SOURCE, BudgetCol.BUDGET, BudgetCol.ACTUAL))
        onNodeWithTag(BudgetTags.statusChip("food"), useUnmergedTree = true).assertDoesNotExist()
        // The budget cell is the edit control: it never hides.
        onNodeWithTag(BudgetTags.editChip("food"), useUnmergedTree = true).assertExists()
    }

    @Composable
    private fun BreakdownFixture() = NetWorthBreakdownTable(
        walletAssetSat = 100_000uL,
        investmentsAssetSat = 0uL,
        investmentsPricingAvailable = false,
        hasInvestments = false,
        manualAssetsByKindSat = emptyMap(),
        hasManualAssets = false,
        liabilities = emptyList(),
        fiatCurrency = io.bhodl.desktop.prices.FiatCurrency.NONE,
    )

    @Test
    fun `net worth breakdown shows the share when wide`() = runDesktopComposeUiTest {
        renderAt(1_000) { BreakdownFixture() }
        assertHeader(NET_WORTH_BREAKDOWN_TABLE, NetWorthBreakdownCol.entries)
        onAllNodesWithText("100.0%", useUnmergedTree = true).onFirst().assertExists()
    }

    @Test
    fun `net worth breakdown hides the share when very narrow`() = runDesktopComposeUiTest {
        renderAt(300) { BreakdownFixture() }
        assertHeader(
            NET_WORTH_BREAKDOWN_TABLE,
            listOf(NetWorthBreakdownCol.CATEGORY, NetWorthBreakdownCol.VALUE),
        )
        onNodeWithText("100.0%", useUnmergedTree = true).assertDoesNotExist()
    }
}
