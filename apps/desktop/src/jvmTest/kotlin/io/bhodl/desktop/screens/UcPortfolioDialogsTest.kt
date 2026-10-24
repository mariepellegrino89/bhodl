package io.bhodl.desktop.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Area D — Save validation of the portfolio edit dialogs (investment, lot,
 * sell, manual asset, liability, goal) and the arguments they hand to
 * `onSave`. Use-case ids from docs/use-cases.md in each test name.
 */
@OptIn(ExperimentalTestApi::class)
class UcPortfolioDialogsTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
    }

    @AfterTest
    fun teardown() = SettingsStore.reset()

    private fun ComposeUiTest.type(tag: String, text: String) {
        onNodeWithTag(tag).performScrollTo().performTextInput(text)
    }

    private fun ComposeUiTest.retype(tag: String, text: String) {
        onNodeWithTag(tag).performScrollTo().performTextClearance()
        if (text.isNotEmpty()) onNodeWithTag(tag).performTextInput(text)
    }

    private fun ComposeUiTest.click(tag: String) {
        onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun ComposeUiTest.saveEnabled(tag: String, enabled: Boolean) {
        val n = onNodeWithTag(tag).performScrollTo()
        if (enabled) n.assertIsEnabled() else n.assertIsNotEnabled()
    }

    // ─── Investments ───────────────────────────────────────────────

    private data class InvSaved(
        val name: String,
        val type: String,
        val ticker: String?,
        val qty: Double,
        val unit: String,
        val cost: Double,
        val ccy: String,
        val current: Double?,
        val method: String,
    )

    private fun investment(id: Long = 7, qty: Double = 0.0, cost: Double = 0.0) = TrackingInvestmentFfi(
        id = id, name = "ACME", assetType = "stock", ticker = "ACME", quantity = qty, unit = "shares",
        costBasisFiat = cost, costBasisCurrency = "EUR", purchaseUnix = 1_700_000_000L,
        manualCurrentValue = null, fetchedCurrentValue = null, lastPricedAtUnix = null, notes = null,
        costBasisMethod = "fifo", createdAtUnix = 0,
    )

    private fun lot(id: Long, qty: Double, price: Double, fees: Double = 0.0, inv: Long = 7) =
        TrackingInvestmentLotFfi(
            id = id, investmentId = inv, quantity = qty, pricePerUnit = price, feesFiat = fees,
            purchaseUnix = 1_700_000_000L + id, notes = null, createdAtUnix = 0,
            btcPerUnitAtPurchase = null, originalQuantity = qty,
        )

    private fun ComposeUiTest.investmentDialog(
        editing: TrackingInvestmentFfi? = null,
        lots: List<TrackingInvestmentLotFfi> = emptyList(),
        onSave: (InvSaved) -> Unit = {},
    ) {
        setContent {
            BhodlTheme {
                InvestmentEditDialog(
                    editing = editing,
                    lots = lots,
                    onDismiss = {},
                    onSave = { n, t, tk, q, u, c, ccy, cur, m, _ -> onSave(InvSaved(n, t, tk, q, u, c, ccy, cur, m)) },
                    onDelete = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(InvestmentTags.INPUT_NAME))
    }

    @Test
    fun `PRT-001 a filled investment dialog saves every field`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: InvSaved? = null
        investmentDialog(onSave = { saved = it })
        type(InvestmentTags.INPUT_NAME, "  Vanguard All-World ")
        click(InvestmentTags.assetTypeTag("etf"))
        type(InvestmentTags.INPUT_TICKER, " VWCE.DE ")
        type(InvestmentTags.INPUT_QUANTITY, "12")
        type(InvestmentTags.INPUT_COST, "1200")
        retype(InvestmentTags.INPUT_CCY, "usd")
        type(InvestmentTags.INPUT_CURRENT, "110")
        click(InvestmentTags.costBasisMethodTag("hifo"))
        click(InvestmentTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(
            InvSaved("Vanguard All-World", "etf", "VWCE.DE", 12.0, "shares", 1200.0, "USD", 110.0, "hifo"),
            saved,
        )
    }

    @Test
    fun `PRT-001 blank ticker and blank current value save as null`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: InvSaved? = null
        investmentDialog(onSave = { saved = it })
        type(InvestmentTags.INPUT_NAME, "Gold")
        type(InvestmentTags.INPUT_QUANTITY, "2")
        type(InvestmentTags.INPUT_COST, "0")
        click(InvestmentTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertNull(saved!!.ticker)
        assertNull(saved!!.current)
        assertEquals(0.0, saved!!.cost)
        assertEquals("fifo", saved!!.method)
    }

    @Test
    fun `PRT-007 blank name, zero or missing quantity and missing cost disable Save`() = runDesktopComposeUiTest(1400, 1600) {
        investmentDialog()
        saveEnabled(InvestmentTags.SAVE, false)
        type(InvestmentTags.INPUT_QUANTITY, "3")
        type(InvestmentTags.INPUT_COST, "30")
        saveEnabled(InvestmentTags.SAVE, false) // no name
        type(InvestmentTags.INPUT_NAME, "   ")
        saveEnabled(InvestmentTags.SAVE, false) // blank name
        retype(InvestmentTags.INPUT_NAME, "ACME")
        saveEnabled(InvestmentTags.SAVE, true)
        retype(InvestmentTags.INPUT_QUANTITY, "0")
        saveEnabled(InvestmentTags.SAVE, false)
        retype(InvestmentTags.INPUT_QUANTITY, "abc") // filtered to empty
        saveEnabled(InvestmentTags.SAVE, false)
        retype(InvestmentTags.INPUT_QUANTITY, "3")
        retype(InvestmentTags.INPUT_COST, "")
        saveEnabled(InvestmentTags.SAVE, false)
        retype(InvestmentTags.INPUT_COST, "-5") // the minus is filtered: 5
        saveEnabled(InvestmentTags.SAVE, true)
    }

    @Test
    fun `PRT-007 Italian grouping parses 350 dot 000 as three hundred fifty thousand`() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.ITALY)
        try {
            runDesktopComposeUiTest(1400, 1600) {
                var saved: InvSaved? = null
                investmentDialog(onSave = { saved = it })
                type(InvestmentTags.INPUT_NAME, "Casa")
                type(InvestmentTags.INPUT_QUANTITY, "1")
                type(InvestmentTags.INPUT_COST, "350.000")
                click(InvestmentTags.SAVE)
                waitUntil(timeoutMillis = 2_000) { saved != null }
                assertEquals(350_000.0, saved!!.cost)
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `PRT-010 with two lots quantity and cost are the lot sums and read-only`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: InvSaved? = null
        investmentDialog(
            editing = investment(qty = 99.0, cost = 9_999.0),
            lots = listOf(lot(1, 10.0, 100.0), lot(2, 5.0, 200.0)),
            onSave = { saved = it },
        )
        onNodeWithTag(InvestmentTags.INPUT_QUANTITY).assert(hasText("15"))
        onNodeWithTag(InvestmentTags.INPUT_COST).assert(hasText("2000"))
        waitUntilAtLeastOneExists(hasText("sum of the 2 lots", substring = true))
        onNodeWithTag(InvestmentTags.INPUT_QUANTITY)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        onNodeWithTag(InvestmentTags.INPUT_COST)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        click(InvestmentTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(15.0, saved!!.qty)
        assertEquals(2_000.0, saved!!.cost)
    }

    @Test
    fun `PRT-010 with one lot the fields stay editable and no lot hint is shown`() = runDesktopComposeUiTest(1400, 1600) {
        investmentDialog(editing = investment(), lots = listOf(lot(1, 4.0, 50.0, fees = 2.0)))
        onNodeWithTag(InvestmentTags.INPUT_QUANTITY).assert(hasText("4"))
        onNodeWithTag(InvestmentTags.INPUT_COST).assert(hasText("202"))
        waitUntilDoesNotExist(hasText("sum of the", substring = true))
        retype(InvestmentTags.INPUT_QUANTITY, "6")
        onNodeWithTag(InvestmentTags.INPUT_QUANTITY).assert(hasText("6"))
    }

    // ─── Lots ──────────────────────────────────────────────────────

    private data class LotSaved(val qty: Double, val price: Double, val fees: Double, val unix: Long, val notes: String?)

    private fun ComposeUiTest.lotDialog(editing: TrackingInvestmentLotFfi? = null, onSave: (LotSaved) -> Unit = {}) {
        setContent {
            BhodlTheme {
                LotEditDialog(
                    editing = editing,
                    parentInvestmentName = "ACME",
                    parentCurrencySymbol = "€",
                    onDismiss = {},
                    onSave = { q, p, f, u, n -> onSave(LotSaved(q, p, f, u, n)) },
                    onDelete = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(LotEditTags.INPUT_QTY))
    }

    @Test
    fun `PRT-023 adding a lot passes quantity, price, fees and notes`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: LotSaved? = null
        lotDialog(onSave = { saved = it })
        type(LotEditTags.INPUT_QTY, "3")
        type(LotEditTags.INPUT_PRICE, "101")
        type(LotEditTags.INPUT_FEES, "2")
        type(LotEditTags.INPUT_NOTES, "broker A")
        click(LotEditTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(LotSaved(3.0, 101.0, 2.0, saved!!.unix, "broker A"), saved)
        assertTrue(kotlin.math.abs(saved!!.unix - System.currentTimeMillis() / 1000L) < 120)
    }

    @Test
    fun `PRT-023 editing a lot pre-fills it and keeps its date`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: LotSaved? = null
        lotDialog(editing = lot(5, 2.0, 40.0, fees = 1.5), onSave = { saved = it })
        onNodeWithTag(LotEditTags.INPUT_QTY).assert(hasText("2"))
        onNodeWithTag(LotEditTags.INPUT_FEES).assert(hasText("1.5"))
        retype(LotEditTags.INPUT_PRICE, "45")
        click(LotEditTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(LotSaved(2.0, 45.0, 1.5, 1_700_000_005L, null), saved)
    }

    @Test
    fun `PRT-026 lot Save needs positive quantity and price, blank fee means zero`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: LotSaved? = null
        lotDialog(onSave = { saved = it })
        saveEnabled(LotEditTags.SAVE, false)
        type(LotEditTags.INPUT_QTY, "0")
        type(LotEditTags.INPUT_PRICE, "10")
        saveEnabled(LotEditTags.SAVE, false)
        retype(LotEditTags.INPUT_QTY, "1")
        retype(LotEditTags.INPUT_PRICE, "0")
        saveEnabled(LotEditTags.SAVE, false)
        retype(LotEditTags.INPUT_PRICE, "10")
        saveEnabled(LotEditTags.SAVE, true)
        click(LotEditTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(0.0, saved!!.fees)
    }

    @Test
    fun `PRT-026 an unparseable fee blocks Save and shows the error`() = runDesktopComposeUiTest(1400, 1600) {
        lotDialog()
        type(LotEditTags.INPUT_QTY, "1")
        type(LotEditTags.INPUT_PRICE, "10")
        type(LotEditTags.INPUT_FEES, ".")
        saveEnabled(LotEditTags.SAVE, false)
        waitUntilAtLeastOneExists(hasText("Invalid fees amount."))
        retype(LotEditTags.INPUT_FEES, "0.5")
        saveEnabled(LotEditTags.SAVE, true)
        waitUntilDoesNotExist(hasText("Invalid fees amount."))
    }

    // ─── Sells ─────────────────────────────────────────────────────

    private fun ComposeUiTest.sellDialog(
        rate: Double? = null,
        lotRate: Double? = null,
        onSave: (Double) -> Unit = {},
    ) {
        setContent {
            BhodlTheme {
                SellDialog(
                    parentInvestment = investment().copy(costBasisMethod = "fifo"),
                    parentLots = listOf(lot(1, 10.0, 100.0), lot(2, 5.0, 200.0))
                        .map { it.copy(btcPerUnitAtPurchase = lotRate) },
                    parentCurrencySymbol = "€",
                    saleBtcRateAt = { _, _ -> rate },
                    onDismiss = {},
                    onSave = { _, qty, _, _, _, _ -> onSave(qty) },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SellDialogTags.INPUT_QTY))
    }

    @Test
    fun `PRT-040 FIFO quantity above the available units disables Save`() = runDesktopComposeUiTest(1400, 1600) {
        sellDialog()
        type(SellDialogTags.INPUT_PRICE, "250")
        type(SellDialogTags.INPUT_QTY, "15.5")
        saveEnabled(SellDialogTags.SAVE, false)
        retype(SellDialogTags.INPUT_QTY, "15")
        saveEnabled(SellDialogTags.SAVE, true)
    }

    @Test
    fun `PRT-051 zero price or a bad fee disables Save`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: Double? = null
        sellDialog(onSave = { saved = it })
        type(SellDialogTags.INPUT_QTY, "2")
        type(SellDialogTags.INPUT_PRICE, "0")
        saveEnabled(SellDialogTags.SAVE, false)
        retype(SellDialogTags.INPUT_PRICE, "120")
        type(SellDialogTags.INPUT_FEES, ".")
        saveEnabled(SellDialogTags.SAVE, false)
        waitUntilAtLeastOneExists(hasText("Invalid fees amount."))
        retype(SellDialogTags.INPUT_FEES, "1")
        saveEnabled(SellDialogTags.SAVE, true)
        click(SellDialogTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(2.0, saved)
    }

    @Test
    fun `PRT-050 without a historical rate the BTC P&L reads no rate`() = runDesktopComposeUiTest(1400, 1600) {
        sellDialog(rate = null, lotRate = 40_000.0)
        type(SellDialogTags.INPUT_QTY, "3")
        type(SellDialogTags.INPUT_PRICE, "250")
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithTag(SellDialogTags.realizedBtc(CostBasisMethod.FIFO), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag(SellDialogTags.realizedBtc(CostBasisMethod.FIFO), useUnmergedTree = true)
            .assert(hasText("no rate"))
    }

    @Test
    fun `PRT-050 with the sale-date rate the BTC P&L is a signed amount`() = runDesktopComposeUiTest(1400, 1600) {
        sellDialog(rate = 50_000.0, lotRate = 40_000.0)
        type(SellDialogTags.INPUT_QTY, "3")
        type(SellDialogTags.INPUT_PRICE, "250")
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithTag(SellDialogTags.realizedBtc(CostBasisMethod.FIFO), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val node = onNodeWithTag(SellDialogTags.realizedBtc(CostBasisMethod.FIFO), useUnmergedTree = true)
        node.assert(hasText("no rate").not())
    }

    // ─── Manual assets ────────────────────────────────────────────

    private data class MaSaved(
        val name: String, val kind: String, val value: Double, val ccy: String,
        val notes: String?, val purchaseUnix: Long?, val purchaseValue: Double?,
    )

    private fun ComposeUiTest.manualDialog(editing: TrackingManualAssetFfi? = null, onSave: (MaSaved) -> Unit = {}) {
        setContent {
            BhodlTheme {
                ManualAssetEditDialog(
                    editing = editing,
                    onDismiss = {},
                    onSave = { n, k, v, c, no, pu, pv -> onSave(MaSaved(n, k, v, c, no, pu, pv)) },
                    onDelete = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(ManualAssetTags.INPUT_NAME))
    }

    private fun asset(purchaseUnix: Long? = null, purchaseValue: Double? = null) = TrackingManualAssetFfi(
        id = 3, name = "Flat", kind = "real_estate", valueFiat = 250_000.0, currency = "CHF",
        displayLabel = null, notes = "Zurich", lastUpdatedUnix = 0, createdAtUnix = 0,
        purchaseUnix = purchaseUnix, purchaseValueFiat = purchaseValue, purchaseCurrency = "CHF",
        purchaseBtcAtTime = null,
    )

    @Test
    fun `PRT-087 a new manual asset saves kind, currency and notes without purchase snapshot`() =
        runDesktopComposeUiTest(1400, 1600) {
            var saved: MaSaved? = null
            manualDialog(onSave = { saved = it })
            type(ManualAssetTags.INPUT_NAME, " Car ")
            click(ManualAssetTags.kindTag("vehicle"))
            type(ManualAssetTags.INPUT_VALUE, "18000")
            click(ManualAssetTags.currencyTag("GBP"))
            type(ManualAssetTags.INPUT_NOTES, "hatchback")
            click(ManualAssetTags.SAVE)
            waitUntil(timeoutMillis = 2_000) { saved != null }
            assertEquals(MaSaved("Car", "vehicle", 18_000.0, "GBP", "hatchback", null, null), saved)
        }

    @Test
    fun `PRT-087 a purchase value without a date is not saved as a snapshot`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: MaSaved? = null
        manualDialog(onSave = { saved = it })
        type(ManualAssetTags.INPUT_NAME, "Cash")
        type(ManualAssetTags.INPUT_VALUE, "100")
        type(ManualAssetTags.INPUT_PURCHASE_VALUE, "80")
        click(ManualAssetTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals("cash", saved!!.kind)
        assertEquals("EUR", saved!!.ccy)
        assertNull(saved!!.purchaseUnix)
        assertNull(saved!!.purchaseValue)
    }

    @Test
    fun `PRT-087 editing keeps the stored purchase date and value`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: MaSaved? = null
        manualDialog(editing = asset(1_600_000_000L, 200_000.0), onSave = { saved = it })
        retype(ManualAssetTags.INPUT_VALUE, "260000")
        click(ManualAssetTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(MaSaved("Flat", "real_estate", 260_000.0, "CHF", "Zurich", 1_600_000_000L, 200_000.0), saved)
    }

    @Test
    fun `PRT-089 blank name, non-positive value and invalid purchase value disable Save`() =
        runDesktopComposeUiTest(1400, 1600) {
            manualDialog()
            saveEnabled(ManualAssetTags.SAVE, false)
            type(ManualAssetTags.INPUT_VALUE, "100")
            saveEnabled(ManualAssetTags.SAVE, false)
            type(ManualAssetTags.INPUT_NAME, "Savings")
            saveEnabled(ManualAssetTags.SAVE, true)
            retype(ManualAssetTags.INPUT_VALUE, "0")
            saveEnabled(ManualAssetTags.SAVE, false)
            retype(ManualAssetTags.INPUT_VALUE, "100")
            type(ManualAssetTags.INPUT_PURCHASE_VALUE, ".")
            saveEnabled(ManualAssetTags.SAVE, false)
            waitUntilAtLeastOneExists(hasText("Invalid purchase value."))
            retype(ManualAssetTags.INPUT_PURCHASE_VALUE, "0")
            saveEnabled(ManualAssetTags.SAVE, false)
            retype(ManualAssetTags.INPUT_PURCHASE_VALUE, "")
            saveEnabled(ManualAssetTags.SAVE, true)
        }

    // ─── Liabilities ──────────────────────────────────────────────

    private data class LiabSaved(val name: String, val sat: ULong, val kind: String, val label: String?)

    private fun ComposeUiTest.liabilityDialog(editing: TrackingLiabilityFfi? = null, onSave: (LiabSaved) -> Unit = {}) {
        setContent {
            BhodlTheme {
                LiabilityEditDialog(
                    editing = editing,
                    onDismiss = {},
                    onSave = { n, a, k, l -> onSave(LiabSaved(n, a, k, l)) },
                    onDelete = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(LiabilityTags.INPUT_NAME))
    }

    @Test
    fun `PRT-096 a liability saves name, sat amount, kind and trimmed label`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: LiabSaved? = null
        liabilityDialog(onSave = { saved = it })
        type(LiabilityTags.INPUT_NAME, " Mortgage ")
        type(LiabilityTags.INPUT_AMOUNT, "25000000")
        click(LiabilityTags.kind("mortgage"))
        type(LiabilityTags.INPUT_LABEL, "  bank X ")
        click(LiabilityTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(LiabSaved("Mortgage", 25_000_000UL, "mortgage", "bank X"), saved)
    }

    @Test
    fun `PRT-096 defaults to kind other and a blank label is null`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: LiabSaved? = null
        liabilityDialog(onSave = { saved = it })
        type(LiabilityTags.INPUT_NAME, "Card")
        type(LiabilityTags.INPUT_AMOUNT, "1000")
        click(LiabilityTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(LiabSaved("Card", 1_000UL, "other", null), saved)
    }

    @Test
    fun `PRT-099 amount must be a whole non-negative sat count`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: LiabSaved? = null
        liabilityDialog(onSave = { saved = it })
        type(LiabilityTags.INPUT_NAME, "Loan")
        saveEnabled(LiabilityTags.SAVE, false) // empty amount
        type(LiabilityTags.INPUT_AMOUNT, "-1.5") // only digits survive: "15"
        onNodeWithTag(LiabilityTags.INPUT_AMOUNT).assert(hasText("15"))
        retype(LiabilityTags.INPUT_AMOUNT, "99999999999999999999999") // > ULong.MAX
        saveEnabled(LiabilityTags.SAVE, false)
        retype(LiabilityTags.INPUT_AMOUNT, "0")
        saveEnabled(LiabilityTags.SAVE, true)
        retype(LiabilityTags.INPUT_NAME, " ")
        saveEnabled(LiabilityTags.SAVE, false)
    }

    // ─── Savings goals ────────────────────────────────────────────

    private data class GoalSaved(
        val name: String, val targetSat: ULong, val pct: UInt?, val bucket: String,
        val ref: String?, val goalKind: String, val deadline: Long?,
    )

    private val wallet = WalletRecordFfi(
        id = "w1", label = "Cold", kind = WalletKindFfi.WATCH_XPUB, descriptorPub = "xpub",
        network = "bitcoin", createdAtUnix = 0,
    )

    private fun ComposeUiTest.goalDialog(
        netWorth: ULong = 10_000_000UL,
        wallets: List<WalletRecordFfi> = emptyList(),
        assets: List<TrackingManualAssetFfi> = emptyList(),
        editing: TrackingSavingsGoalFfi? = null,
        onSave: (GoalSaved) -> Unit = {},
    ) {
        setContent {
            BhodlTheme {
                GoalEditDialog(
                    editing = editing,
                    totalNetWorthSat = netWorth,
                    bitcoinAssetSat = netWorth,
                    wallets = wallets,
                    watchBalances = emptyMap(),
                    investments = emptyList(),
                    manualAssets = assets,
                    fiatToSat = { null },
                    onDismiss = {},
                    onSave = { n, t, _, _, p, b, r, k, d, _, _, _ -> onSave(GoalSaved(n, t, p, b, r, k, d)) },
                    onDelete = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(GoalTags.INPUT_NAME))
    }

    @Test
    fun `PRT-123 absolute target needs a name and a positive sat target`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: GoalSaved? = null
        goalDialog(onSave = { saved = it })
        saveEnabled(GoalTags.SAVE, false)
        type(GoalTags.INPUT_NAME, "House")
        saveEnabled(GoalTags.SAVE, false)
        type(GoalTags.INPUT_TARGET, "0")
        saveEnabled(GoalTags.SAVE, false)
        retype(GoalTags.INPUT_TARGET, "5000000")
        click(GoalTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(GoalSaved("House", 5_000_000UL, null, "manual", null, "savings", null), saved)
    }

    @Test
    fun `PRT-123 percent target saves the pct and its net-worth snapshot`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: GoalSaved? = null
        goalDialog(onSave = { saved = it })
        type(GoalTags.INPUT_NAME, "Allocation")
        click(GoalTags.TARGET_KIND_PERCENT)
        type(GoalTags.INPUT_PCT_NW, "0")
        saveEnabled(GoalTags.SAVE, false)
        retype(GoalTags.INPUT_PCT_NW, "25")
        click(GoalTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals(25u, saved!!.pct)
        assertEquals(2_500_000UL, saved!!.targetSat)
    }

    @Test
    fun `PRT-123 wallet and manual-asset buckets need a reference`() = runDesktopComposeUiTest(1400, 1600) {
        goalDialog()
        type(GoalTags.INPUT_NAME, "Bucketed")
        type(GoalTags.INPUT_TARGET, "1000")
        saveEnabled(GoalTags.SAVE, true)
        click(GoalTags.BUCKET_WALLET)
        saveEnabled(GoalTags.SAVE, false) // no wallet to bind
        click(GoalTags.BUCKET_MANUAL_ASSET)
        saveEnabled(GoalTags.SAVE, false) // no asset to bind
        click(GoalTags.BUCKET_BITCOIN_CASH)
        saveEnabled(GoalTags.SAVE, true)
    }

    @Test
    fun `PRT-123 a wallet bucket binds the first wallet by default`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: GoalSaved? = null
        goalDialog(wallets = listOf(wallet), assets = listOf(asset()), onSave = { saved = it })
        type(GoalTags.INPUT_NAME, "Cold stack")
        type(GoalTags.INPUT_TARGET, "1000")
        click(GoalTags.BUCKET_WALLET)
        click(GoalTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals("wallet", saved!!.bucket)
        assertEquals("w1", saved!!.ref)
    }

    @Test
    fun `PRT-123 a manual-asset bucket binds the first asset by default`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: GoalSaved? = null
        goalDialog(assets = listOf(asset()), onSave = { saved = it })
        type(GoalTags.INPUT_NAME, "Flat")
        type(GoalTags.INPUT_TARGET, "1000")
        click(GoalTags.BUCKET_MANUAL_ASSET)
        waitUntilAtLeastOneExists(hasTestTag(GoalTags.bucketManualAssetTag(3)))
        click(GoalTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals("manual_asset", saved!!.bucket)
        assertEquals("3", saved!!.ref)
    }

    @Test
    fun `PRT-123 with zero net worth the percent mode cannot be saved`() = runDesktopComposeUiTest(1400, 1600) {
        goalDialog(netWorth = 0UL)
        type(GoalTags.INPUT_NAME, "Pct")
        onNodeWithTag(GoalTags.TARGET_KIND_PERCENT, useUnmergedTree = true).performClick()
        waitUntilDoesNotExist(hasTestTag(GoalTags.INPUT_PCT_NW))
        saveEnabled(GoalTags.SAVE, false)
    }

    @Test
    fun `PRT-129 a sinking fund without a deadline cannot be saved`() = runDesktopComposeUiTest(1400, 1600) {
        goalDialog()
        type(GoalTags.INPUT_NAME, "Holiday")
        type(GoalTags.INPUT_TARGET, "300000")
        saveEnabled(GoalTags.SAVE, true)
        click(GoalTags.GOAL_KIND_SINKING)
        saveEnabled(GoalTags.SAVE, false)
        click(GoalTags.GOAL_KIND_SAVINGS)
        saveEnabled(GoalTags.SAVE, true)
    }

    @Test
    fun `PRT-129 an existing sinking fund with its deadline saves it`() = runDesktopComposeUiTest(1400, 1600) {
        var saved: GoalSaved? = null
        val goal = TrackingSavingsGoalFfi(
            id = 9, name = "Car", targetSat = 900_000UL, currentSat = 0UL, color = "cyan",
            deadlineUnix = 1_900_000_000L, linkedWalletId = null, targetPctOfNetWorth = null,
            bucketKind = "manual", bucketRef = null, goalKind = "sinking", targetDenomination = "sat",
            targetFiatAmount = null, targetFiatCurrency = null, createdAtUnix = 0,
        )
        goalDialog(editing = goal, onSave = { saved = it })
        click(GoalTags.SAVE)
        waitUntil(timeoutMillis = 2_000) { saved != null }
        assertEquals("sinking", saved!!.goalKind)
        assertEquals(1_900_000_000L, saved!!.deadline)
    }
}
