package io.bhodl.desktop.screens

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.test.waitUntilDoesNotExist
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.EventCostBasisFfi
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** PRT-147 — the BTC average-cost card rendered from a fixed summary input. */
@OptIn(ExperimentalTestApi::class)
class UcPortfolioCostBasisCardTest {

    private val d1 = 1_700_006_400L
    private val d2 = d1 + 86_400L
    private val d3 = d2 + 86_400L

    @BeforeTest
    fun setup() = SettingsStore.reset()

    @AfterTest
    fun teardown() = SettingsStore.reset()

    private fun wallet(id: String, network: String) = WalletRecordFfi(
        id = id, label = id, kind = WalletKindFfi.WATCH_XPUB, descriptorPub = "", network = network, createdAtUnix = 0,
    )

    private fun leg(wallet: String, txid: String, dir: PaymentDirection, sat: ULong, t: Long?) = PaymentEvent(
        walletId = wallet, txidOrPaymentId = txid, kind = PaymentKind.ON_CHAIN, direction = dir, amountSat = sat,
        feeSat = null, timestampUnix = t?.toULong(), blockHeight = null, category = null, cryptoEventKind = null,
    )

    private val legs = listOf(
        leg("m", "a", PaymentDirection.INCOMING, 100_000_000UL, d1), // market 20k
        leg("m", "b", PaymentDirection.INCOMING, 100_000_000UL, d2), // user price 40k
        leg("m", "c", PaymentDirection.INCOMING, 50_000_000UL, d3), // excluded
        leg("m", "d", PaymentDirection.INCOMING, 10_000UL, null), // mempool
        leg("s", "e", PaymentDirection.INCOMING, 100_000_000UL, d1), // signet: not counted
    )
    private val inputs = mapOf(
        "b" to EventCostBasisFfi("m", "b", 40_000.0, "EUR", false),
        "c" to EventCostBasisFfi("m", "c", null, null, true),
    )

    private fun ComposeUiTest.card(
        fiat: FiatCurrency = FiatCurrency.EUR,
        wallets: List<WalletRecordFfi> = listOf(wallet("m", "mainnet"), wallet("s", "signet")),
        allLegs: List<PaymentEvent> = legs,
        rates: Map<Pair<Long, String>, Double> = mapOf((d1 to "EUR") to 20_000.0),
        ensured: MutableList<Set<Long>> = mutableListOf(),
        onEdit: (PaymentEvent) -> Unit = {},
    ) {
        setContent {
            BhodlTheme {
                BtcCostBasisCardContent(
                    wallets = wallets,
                    excludedWalletIds = emptySet(),
                    fiat = fiat,
                    allLegs = allLegs,
                    inputs = inputs,
                    rates = rates,
                    loadingRates = false,
                    livePrice = 40_000.0,
                    onEditEvent = onEdit,
                    ensureRates = { days, _ -> ensured += days },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(BtcCostBasisTags.CARD))
    }

    @Test
    fun `PRT-147 metrics, notes and transaction rows with their status`() = runDesktopComposeUiTest(width = 1400, height = 1400) {
        val ensured = mutableListOf<Set<Long>>()
        val edited = mutableListOf<String>()
        card(ensured = ensured, onEdit = { edited += it.txidOrPaymentId })
        // cost 20k + 40k over 2 BTC → avg 30k; value 80k → +20k (+33.3%)
        onNodeWithTag(BtcCostBasisTags.UNREALIZED).assert(hasText("+", substring = true))
        onNodeWithTag(BtcCostBasisTags.UNREALIZED).assert(hasText("33.3%", substring = true))
        onNodeWithTag(BtcCostBasisTags.AVG_PRICE).assert(hasText("30", substring = true))
        waitUntilAtLeastOneExists(hasText("1 transactions excluded", substring = true))
        waitUntilAtLeastOneExists(hasText("1 test-network wallets left out", substring = true))
        assertEquals(listOf(setOf(d1, d2, d3)), ensured)

        waitUntilDoesNotExist(hasTestTag(BtcCostBasisTags.ROW_PREFIX + "a"))
        onNodeWithTag(BtcCostBasisTags.TOGGLE_TXS).assert(hasText("Show transactions (4)"))
        onNodeWithTag(BtcCostBasisTags.TOGGLE_TXS).performClick()
        onNodeWithTag(BtcCostBasisTags.TOGGLE_TXS).assert(hasText("Hide transactions (4)"))
        for ((txid, status) in listOf("a" to "Market price", "b" to "Your price", "c" to "Excluded", "d" to "Unconfirmed")) {
            onNodeWithTag(BtcCostBasisTags.ROW_PREFIX + txid).assert(hasText(status, substring = true))
        }
        waitUntilDoesNotExist(hasTestTag(BtcCostBasisTags.ROW_PREFIX + "e"))
        onNodeWithTag(BtcCostBasisTags.ROW_PREFIX + "b").performClick()
        assertEquals(listOf("b"), edited)
    }

    @Test
    fun `PRT-147 a day without market rate is a missing price`() = runDesktopComposeUiTest(width = 1400, height = 1400) {
        card(rates = emptyMap())
        waitUntilAtLeastOneExists(hasText("1 transactions without a price", substring = true))
        onNodeWithTag(BtcCostBasisTags.TOGGLE_TXS).performClick()
        onNodeWithTag(BtcCostBasisTags.ROW_PREFIX + "a").assert(hasText("No price", substring = true))
    }

    @Test
    fun `PRT-147 without a fiat currency the card asks to choose one and fetches nothing`() = runDesktopComposeUiTest {
        val ensured = mutableListOf<Set<Long>>()
        card(fiat = FiatCurrency.NONE, ensured = ensured)
        waitUntilAtLeastOneExists(hasText("Choose a fiat currency", substring = true))
        waitUntilDoesNotExist(hasTestTag(BtcCostBasisTags.TOGGLE_TXS))
        assertEquals(emptyList(), ensured)
    }

    @Test
    fun `PRT-147 only test-network wallets get their own message`() = runDesktopComposeUiTest {
        card(wallets = listOf(wallet("s", "signet")), allLegs = legs.filter { it.walletId == "s" })
        waitUntilAtLeastOneExists(hasText("Your 1 tracked wallets are on a test network", substring = true))
    }

    @Test
    fun `PRT-147 no transactions shows the empty note`() = runDesktopComposeUiTest {
        card(wallets = listOf(wallet("m", "mainnet")), allLegs = emptyList())
        waitUntilAtLeastOneExists(hasText("No mainnet transactions yet", substring = true))
    }
}
