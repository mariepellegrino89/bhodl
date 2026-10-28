package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.data.ManualAccount
import io.bhodl.desktop.data.ManualTxns
import io.bhodl.desktop.data.btcRateOn
import io.bhodl.desktop.data.fiatToSatAtRate
import io.bhodl.desktop.data.manualAccounts
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.ManualTxnFfi
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ui.theme.BhodlTheme
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Registry schema v8 — off-wallet entries (cash, bank account, card)
 * and bills paid by a transaction.
 */
@OptIn(ExperimentalTestApi::class)
class ManualTxnTest {

    private val cash = listOf(ManualAccount(null, io.bhodl.ffi.manualTxnWalletId(null), "Cash", null))

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
    }

    @AfterTest
    fun tearDown() = SettingsStore.reset()

    @Test
    fun `fiat converts to sat at the rate`() {
        // 1 BTC = 50,000 EUR → 30 EUR = 0.0006 BTC.
        assertEquals(60_000UL, fiatToSatAtRate(30.0, 50_000.0))
        assertNull(fiatToSatAtRate(30.0, null), "no rate, no amount")
    }

    @Test
    fun `an amount in euro is saved in sat at the day's rate`() = runDesktopComposeUiTest {
        var saved: ManualTxnDraft? = null
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = null, editingCategory = null, accounts = cash, knownCategories = listOf("Cibo"),
                    onDismiss = {}, onSave = { saved = it }, nowUnix = DAY, rateOn = { _, _ -> 50_000.0 },
                )
            }
        }
        onNodeWithTag(ManualTxnTags.SAVE).assertIsNotEnabled()
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("30")
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag(ManualTxnTags.PREVIEW), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ManualTxnTags.PREVIEW, useUnmergedTree = true).assertTextContains("60,000", substring = true)
        onNodeWithTag(ManualTxnTags.CATEGORY).performTextInput("Cibo")
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        val d = saved!!
        assertEquals(ManualTxns.DIRECTION_OUT, d.direction)
        assertEquals(60_000UL, d.amountSat)
        assertEquals(30.0, d.amountFiat)
        assertEquals("EUR", d.currency)
        assertEquals(DAY, d.occurredUnix)
        assertEquals("Cibo", d.category)
        assertNull(d.accountRef)
    }

    @Test
    fun `without a price for that day a euro amount cannot be saved, the same amount in sat can`() = runDesktopComposeUiTest {
        var saved: ManualTxnDraft? = null
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = null, editingCategory = null, accounts = cash, knownCategories = emptyList(),
                    onDismiss = {}, onSave = { saved = it }, nowUnix = DAY, rateOn = { _, _ -> null },
                )
            }
        }
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("30")
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag(ManualTxnTags.PREVIEW), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ManualTxnTags.PREVIEW, useUnmergedTree = true).assertTextContains("No BTC price for that day", substring = true)
        onNodeWithTag(ManualTxnTags.SAVE).assertIsNotEnabled()

        onNodeWithTag(ManualTxnTags.currency("sat")).performClick()
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("60000")
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        assertEquals(60_000UL, saved!!.amountSat)
        assertNull(saved!!.amountFiat)
    }

    @Test
    fun `offline, a past day missing from the cache has no price`() = kotlinx.coroutines.runBlocking {
        io.bhodl.desktop.wizard.RegistryStore.clear()
        io.bhodl.desktop.prices.PriceCache.seedForTest(price = null)
        io.bhodl.desktop.prices.PriceCache.clearSessionCaches()
        val lastYear = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusYears(1).atStartOfDay().toEpochSecond(java.time.ZoneOffset.UTC)
        assertNull(btcRateOn(lastYear, "EUR"))
    }

    @Test
    fun `spending can pay a planned mortgage as an extra repayment, in the plan's currency`() = runDesktopComposeUiTest {
        var saved: ManualTxnDraft? = null
        val debts = listOf(
            DebtOption(4L, "Mutuo", planned = true, planCurrency = "CHF"),
            DebtOption(5L, "Visa", planned = false),
        )
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = null, editingCategory = null, accounts = cash, knownCategories = emptyList(),
                    onDismiss = {}, onSave = { saved = it }, nowUnix = DAY, rateOn = { _, _ -> 50_000.0 }, debts = debts,
                )
            }
        }
        onNodeWithTag(ManualTxnTags.debt(4L)).performClick()
        // The plan counts in CHF: the amount is typed in CHF only.
        onNodeWithTag(ManualTxnTags.currency("CHF")).assertExists()
        onNodeWithTag(ManualTxnTags.currency("EUR")).assertDoesNotExist()
        onNodeWithTag(ManualTxnTags.debtKind(io.bhodl.desktop.data.LoanPlans.EXTRA_LOWER_PAYMENT)).performClick()
        onNodeWithTag(ManualTxnTags.DEBT_HINT, useUnmergedTree = true).assertTextContains("installment gets lower", substring = true)
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("5000")
        waitUntil(timeoutMillis = 3_000) { onAllNodes(hasTestTag(ManualTxnTags.PREVIEW), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        val d = saved!!
        assertEquals(4L, d.debtId)
        assertEquals(io.bhodl.desktop.data.LoanPlans.EXTRA_LOWER_PAYMENT, d.debtPayment)
        assertEquals("CHF", d.currency)
        assertEquals(5_000.0, d.amountFiat)
    }

    @Test
    fun `income pays no debt`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = null, editingCategory = null, accounts = cash, knownCategories = emptyList(),
                    onDismiss = {}, onSave = {}, nowUnix = DAY, rateOn = { _, _ -> null },
                    debts = listOf(DebtOption(5L, "Visa", planned = false)),
                )
            }
        }
        onNodeWithTag(ManualTxnTags.DEBT_NONE).assertExists()
        onNodeWithTag(ManualTxnTags.DIR_IN).performClick()
        onNodeWithTag(ManualTxnTags.DEBT_NONE).assertDoesNotExist()
    }

    @Test
    fun `an amount in sat needs no rate and income is income`() = runDesktopComposeUiTest {
        var saved: ManualTxnDraft? = null
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = null, editingCategory = null, accounts = cash, knownCategories = emptyList(),
                    onDismiss = {}, onSave = { saved = it }, nowUnix = DAY, rateOn = { _, _ -> null },
                )
            }
        }
        onNodeWithTag(ManualTxnTags.DIR_IN).performClick()
        onNodeWithTag(ManualTxnTags.currency("sat")).performClick()
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("12500")
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        val d = saved!!
        assertEquals(ManualTxns.DIRECTION_IN, d.direction)
        assertEquals(12_500UL, d.amountSat)
        assertNull(d.amountFiat)
        assertNull(d.currency)
    }

    @Test
    fun `editing keeps the stored sat value until the amount changes`() = runDesktopComposeUiTest {
        val row = ManualTxnFfi(7, "out", 61_000UL, 42.5, "EUR", DAY, null, "Bolletta", 0)
        val saves = mutableListOf<ManualTxnDraft>()
        var deleted = false
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = row, editingCategory = "Bollette", accounts = cash, knownCategories = emptyList(),
                    onDismiss = {}, onSave = { saves += it }, onDelete = { deleted = true },
                    rateOn = { _, _ -> 100_000.0 },
                )
            }
        }
        waitForIdle()
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        assertEquals(61_000UL, saves.last().amountSat, "not repriced at today's rate")
        assertEquals("Bollette", saves.last().category)
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextClearance()
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("50")
        waitForIdle()
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        assertEquals(50_000UL, saves.last().amountSat)
        onNodeWithTag(ManualTxnTags.DELETE).performClick()
        assertTrue(deleted)
    }

    @Test
    fun `the accounts are cash, the cash accounts and the cards`() {
        val card = TrackingLiabilityFfi(3, "Visa", 0UL, "credit-card", null, 0)
        val loan = TrackingLiabilityFfi(4, "Mutuo", 0UL, "mortgage", null, 0)
        val accounts = manualAccounts(emptyList(), listOf(card, loan), "Cash", "Other")
        assertEquals(listOf("Cash", "Visa", "Other"), accounts.map { it.label })
        assertEquals(ManualTxns.ACCOUNT_OTHER, accounts[2].ref)
        assertEquals("liability:3", accounts[1].ref)
        assertEquals(io.bhodl.ffi.manualTxnWalletId("liability:3"), accounts[1].walletId)
        assertTrue(ManualTxns.isManual(accounts[1].walletId))
    }

    private val bank = ManualAccount(
        "asset:5", io.bhodl.ffi.manualTxnWalletId("asset:5"), "Conto", "CHF", io.bhodl.desktop.data.ManualAccountKind.ACCOUNT,
    )

    @Test
    fun `an account entry is typed in the account currency and the wallets are shown but not selectable`() = runDesktopComposeUiTest {
        var saved: ManualTxnDraft? = null
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = null, editingCategory = null, accounts = cash + bank, knownCategories = emptyList(),
                    onDismiss = {}, onSave = { saved = it }, nowUnix = DAY, rateOn = { _, _ -> 80_000.0 },
                    walletLabels = listOf("Main"),
                )
            }
        }
        onNodeWithTag(ManualTxnTags.WALLETS_NOTE, useUnmergedTree = true).assertExists()
        onNodeWithTag("manual-txn.wallet.Main", useUnmergedTree = true).assertIsNotEnabled()
        onNodeWithTag(ManualTxnTags.account(1)).performClick()
        waitForIdle()
        onNodeWithTag(ManualTxnTags.BALANCE_HINT, useUnmergedTree = true).assertTextContains("Conto", substring = true)
        onNodeWithTag(ManualTxnTags.currency("CHF")).assertExists()
        assertTrue(onAllNodes(hasTestTag(ManualTxnTags.currency("EUR"))).fetchSemanticsNodes().isEmpty(), "only the account's currency")
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("40")
        waitForIdle()
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        val d = saved!!
        assertEquals("asset:5", d.accountRef)
        assertEquals("CHF", d.currency)
        assertEquals(50_000UL, d.amountSat)
    }

    @Test
    fun `plus account creates an account and selects it`() = runDesktopComposeUiTest {
        var saved: ManualTxnDraft? = null
        var created: Triple<String, String, Double>? = null
        setContent {
            BhodlTheme {
                ManualTxnDialog(
                    editing = null, editingCategory = null, accounts = cash, knownCategories = emptyList(),
                    onDismiss = {}, onSave = { saved = it }, nowUnix = DAY, rateOn = { _, _ -> 50_000.0 },
                    onAddAccount = { n, c, b -> created = Triple(n, c, b); bank.copy(label = n, currency = c) },
                )
            }
        }
        onNodeWithTag(ManualTxnTags.ADD_ACCOUNT).performClick()
        onNodeWithTag(ManualTxnTags.NEW_ACCOUNT_NAME).performTextInput("Conto corrente")
        onNodeWithTag("manual-txn.new-account.currency.CHF").performClick()
        onNodeWithTag(ManualTxnTags.NEW_ACCOUNT_BALANCE).performTextInput("1200")
        onNodeWithTag(ManualTxnTags.NEW_ACCOUNT_CREATE).performClick()
        waitForIdle()
        assertEquals(Triple("Conto corrente", "CHF", 1200.0), created)
        onNodeWithTag(ManualTxnTags.AMOUNT).performTextInput("10")
        waitForIdle()
        onNodeWithTag(ManualTxnTags.SAVE).performClick()
        assertEquals("asset:5", saved!!.accountRef)
        assertEquals("CHF", saved!!.currency)
    }

    @Test
    fun `the channel filter keeps the wallets or one channel`() {
        val cashId = io.bhodl.ffi.manualTxnWalletId(null)
        val w = out("chain", 1_000UL, 3)
        val m = out("manual-1", 2_000UL, 4).copy(walletId = cashId)
        val all = listOf(w, m)
        assertEquals(all, io.bhodl.desktop.data.filterByChannel(all, io.bhodl.desktop.data.ChannelFilter.All))
        assertEquals(listOf(w), io.bhodl.desktop.data.filterByChannel(all, io.bhodl.desktop.data.ChannelFilter.Wallets))
        assertEquals(listOf(m), io.bhodl.desktop.data.filterByChannel(all, io.bhodl.desktop.data.ChannelFilter.Channel(cashId)))
    }

    // ─── Bills paid by a transaction ────────────────────────────────

    private val today: LocalDate = LocalDate.of(2026, 10, 20)

    private fun out(id: String, sat: ULong, day: Int, category: String? = null, month: Int = 10) = PaymentEvent(
        "w", id, PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, sat, null,
        LocalDate.of(2026, month, day).atTime(12, 0).toEpochSecond(ZoneOffset.UTC).toULong(), 1u, category, null,
    )

    @Test
    fun `bill candidates put the bill's category and the closest amount first`() {
        val bill = TrackingBillFfi(1, "Luce e gas", 140_000UL, 15u, null, null, 0, variable = true, category = "Bollette")
        val other = TrackingBillFfi(2, "Internet", 25_000UL, 10u, null, null, 0, paidRef = "w|internet")
        val events = listOf(
            out("food", 139_000UL, 12, "Cibo"),
            out("power", 163_500UL, 15, "Bollette"),
            out("unknown", 150_000UL, 14),
            out("internet", 25_000UL, 10),
            out("old", 140_000UL, 2, month = 8),
            PaymentEvent("w", "in", PaymentKind.ON_CHAIN, PaymentDirection.INCOMING, 140_000UL, null, null, null, null, null),
        )
        val c = billPaymentCandidates(bill, events, listOf(bill, other), today, ZoneOffset.UTC)
        assertEquals(listOf("power", "unknown", "food"), c.map { it.event.txidOrPaymentId })
        assertEquals(listOf(true, true, false), c.map { it.likely }, "another category is never likely")
    }

    @Test
    fun `the pay dialog links the picked transaction or goes off-wallet`() = runDesktopComposeUiTest {
        val bill = TrackingBillFfi(1, "Luce e gas", 140_000UL, 15u, null, null, 0, category = "Bollette")
        val power = out("power", 150_000UL, 15, "Bollette")
        var linked: PaymentEvent? = null
        var outside = false
        setContent {
            BhodlTheme {
                BillPayDialog(
                    bill = bill, candidates = listOf(BillCandidate(power, likely = true)), walletLabel = { "Main" },
                    onDismiss = {}, onLink = { linked = it }, onPayOutside = { outside = true }, onOnlyMark = {},
                )
            }
        }
        onNodeWithTag(BillPayTags.candidate(0)).performClick()
        assertEquals(power, linked)
        onNodeWithTag(BillPayTags.OUTSIDE).performClick()
        assertTrue(outside)
    }

    @Test
    fun `mark paid on the bills strip opens the pay flow`() = runDesktopComposeUiTest {
        val bill = TrackingBillFfi(1, "Luce e gas", 140_000UL, 28u, null, null, 0)
        var paying: TrackingBillFfi? = null
        setContent {
            BhodlTheme { BillsCalendarStrip(listOf(bill), LocalDate.now(), { _, _ -> }, {}, {}, onPayBill = { paying = it }) }
        }
        onNodeWithTag(BillTags.paidChip(1)).performClick()
        assertEquals(bill, paying)
    }

    private companion object {
        val DAY: Long = LocalDate.of(2026, 10, 20).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)
    }
}
