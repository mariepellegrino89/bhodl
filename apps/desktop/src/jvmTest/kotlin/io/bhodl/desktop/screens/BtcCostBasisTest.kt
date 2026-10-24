package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.CostBasisPriceSource
import io.bhodl.desktop.domain.CostBasisStatus
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.domain.computeBtcCostBasis
import io.bhodl.desktop.domain.costBasisDays
import io.bhodl.desktop.domain.utcDay
import io.bhodl.ffi.EventCostBasisFfi
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure tests for [computeBtcCostBasis] — weighted average cost over the
 * tracked wallets' transactions, market price of the day by default.
 */
class BtcCostBasisTest {

    private val day = 86_400L
    private val d1 = 1_700_006_400L // a UTC midnight
    private val d2 = d1 + day
    private val d3 = d2 + day

    /** Market: 20k on d1, 60k on d2, 50k on d3. */
    private val market = mapOf(d1 to 20_000.0, d2 to 60_000.0, d3 to 50_000.0)

    private fun compute(
        legs: List<PaymentEvent>,
        inputs: Map<String, EventCostBasisFfi> = emptyMap(),
        current: Double? = 40_000.0,
        currency: String = "EUR",
    ) = computeBtcCostBasis(legs, inputs, currency, { market[it] }, current)

    @Test
    fun `inflows average at the market price of their day`() {
        val r = compute(
            listOf(
                leg("w", "a", PaymentDirection.INCOMING, 100_000_000UL, d1 + 3_600),
                leg("w", "b", PaymentDirection.INCOMING, 100_000_000UL, d2 + 7_200),
            ),
        )
        assertEquals(200_000_000L, r.heldSat)
        assertEquals(80_000.0, r.costFiat, 1e-6)
        assertEquals(40_000.0, r.averagePrice!!, 1e-6)
        assertEquals(80_000.0, r.currentValueFiat!!, 1e-6)
        assertEquals(0.0, r.unrealizedFiat!!, 1e-6)
        assertEquals(0.0, r.realizedFiat, 1e-6)
    }

    @Test
    fun `an outflow is a disposal at average cost and keeps the average`() {
        val r = compute(
            listOf(
                leg("w", "a", PaymentDirection.INCOMING, 100_000_000UL, d1),
                leg("w", "b", PaymentDirection.INCOMING, 100_000_000UL, d2),
                // Spend 0.5 BTC on d3 at 50k: cost out 0.5 × 40k = 20k.
                leg("w", "c", PaymentDirection.OUTGOING, 50_000_000UL, d3),
            ),
        )
        assertEquals(150_000_000L, r.heldSat)
        assertEquals(40_000.0, r.averagePrice!!, 1e-6)
        assertEquals(60_000.0, r.costFiat, 1e-6)
        assertEquals(5_000.0, r.realizedFiat, 1e-6) // 25k proceeds − 20k cost
        assertEquals(0.0, r.unrealizedFiat!!, 1e-6) // 1.5 × 40k − 60k
        assertEquals(50_000_000L, r.disposedSat)
    }

    @Test
    fun `user price replaces the market price and exclusion drops the tx`() {
        val r = compute(
            legs = listOf(
                leg("w", "a", PaymentDirection.INCOMING, 100_000_000UL, d1),
                leg("w", "b", PaymentDirection.INCOMING, 100_000_000UL, d2),
            ),
            inputs = mapOf(
                "a" to input("w", "a", price = 10_000.0, currency = "EUR"),
                "b" to input("w", "b", excluded = true),
            ),
        )
        assertEquals(100_000_000L, r.heldSat)
        assertEquals(10_000.0, r.averagePrice!!, 1e-6)
        assertEquals(1, r.excludedCount)
        val a = r.rows.single { it.event.txidOrPaymentId == "a" }
        assertEquals(CostBasisPriceSource.USER, a.priceSource)
        assertEquals(CostBasisStatus.EXCLUDED, r.rows.single { it.event.txidOrPaymentId == "b" }.status)
    }

    @Test
    fun `a user price in another currency is ignored`() {
        val r = compute(
            legs = listOf(leg("w", "a", PaymentDirection.INCOMING, 100_000_000UL, d1)),
            inputs = mapOf("a" to input("w", "a", price = 1.0, currency = "USD")),
        )
        assertEquals(20_000.0, r.averagePrice!!, 1e-6)
        assertEquals(CostBasisPriceSource.MARKET, r.rows.single().priceSource)
    }

    @Test
    fun `legs of one txid are netted so change to an own wallet is not a disposal`() {
        // w1 spends 1 BTC; 0.6 BTC change lands in w2 → 0.4 BTC left custody.
        val r = compute(
            listOf(
                leg("w1", "a", PaymentDirection.INCOMING, 200_000_000UL, d1),
                leg("w1", "b", PaymentDirection.OUTGOING, 100_000_000UL, d3),
                leg("w2", "b", PaymentDirection.INCOMING, 60_000_000UL, d3),
            ),
        )
        assertEquals(160_000_000L, r.heldSat)
        assertEquals(40_000_000L, r.disposedSat)
        assertEquals(12_000.0, r.realizedFiat, 1e-6) // 0.4 × (50k − 20k)
    }

    @Test
    fun `send_to_self transfers are excluded automatically`() {
        val r = compute(
            listOf(
                leg("w1", "a", PaymentDirection.INCOMING, 100_000_000UL, d1),
                leg("w1", "b", PaymentDirection.OUTGOING, 100_000_000UL, d2, kind = SEND_TO_SELF),
            ),
        )
        assertEquals(100_000_000L, r.heldSat)
        assertEquals(CostBasisStatus.SEND_TO_SELF, r.rows.first().status)
    }

    @Test
    fun `outflows beyond the counted stack are reported, not costed`() {
        val r = compute(
            listOf(
                leg("w", "a", PaymentDirection.INCOMING, 50_000_000UL, d1),
                leg("w", "b", PaymentDirection.OUTGOING, 80_000_000UL, d3),
            ),
        )
        assertEquals(0L, r.heldSat)
        assertEquals(0.0, r.costFiat, 1e-9)
        assertNull(r.averagePrice)
        assertEquals(30_000_000L, r.uncoveredOutflowSat)
        assertEquals(15_000.0, r.realizedFiat, 1e-6) // 0.5 × (50k − 20k)
    }

    @Test
    fun `missing prices skip inflows, pending txs wait, rows are newest first`() {
        val unknownDay = d3 + 10 * day
        val r = compute(
            listOf(
                leg("w", "a", PaymentDirection.INCOMING, 100_000_000UL, d1),
                leg("w", "b", PaymentDirection.INCOMING, 100_000_000UL, unknownDay),
                leg("w", "c", PaymentDirection.INCOMING, 100_000_000UL, null),
            ),
        )
        assertEquals(100_000_000L, r.heldSat)
        assertEquals(1, r.missingPriceCount)
        assertEquals(listOf("c", "b", "a"), r.rows.map { it.event.txidOrPaymentId })
        assertEquals(CostBasisStatus.PENDING, r.rows.first().status)
    }

    @Test
    fun `no current price leaves the unrealized figures empty`() {
        val r = compute(listOf(leg("w", "a", PaymentDirection.INCOMING, 1_000UL, d1)), current = null)
        assertNull(r.currentValueFiat)
        assertNull(r.unrealizedFiat)
        assertNull(r.unrealizedRatio)
    }

    @Test
    fun `days are UTC midnights of confirmed txs`() {
        val legs = listOf(
            leg("w", "a", PaymentDirection.INCOMING, 1UL, d1 + 86_399),
            leg("w", "b", PaymentDirection.INCOMING, 1UL, d2),
            leg("w", "c", PaymentDirection.INCOMING, 1UL, null),
        )
        assertEquals(setOf(d1, d2), costBasisDays(legs))
        assertEquals(d1, utcDay(d1 + 86_399))
    }

    @Test
    fun `only mainnet wallets count unless the dev switch includes test networks`() {
        val wallets = listOf(
            walletOn("main", "mainnet"),
            walletOn("sig", "signet"),
            walletOn("off", "mainnet"),
        )
        assertEquals(setOf("main"), costBasisWalletIds(wallets, setOf("off"), includeTestNetworks = false))
        assertEquals(setOf("main", "sig"), costBasisWalletIds(wallets, setOf("off"), includeTestNetworks = true))
    }

    private fun walletOn(id: String, network: String) = WalletRecordFfi(
        id = id,
        label = id,
        kind = WalletKindFfi.WATCH_XPUB,
        descriptorPub = "",
        network = network,
        createdAtUnix = 0L,
    )

    private fun leg(
        wallet: String,
        txid: String,
        direction: PaymentDirection,
        amount: ULong,
        timestamp: Long?,
        kind: String? = null,
    ): PaymentEvent = PaymentEvent(
        walletId = wallet,
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = direction,
        amountSat = amount,
        feeSat = null,
        timestampUnix = timestamp?.toULong(),
        blockHeight = null,
        category = null,
        cryptoEventKind = kind,
    )

    private fun input(
        wallet: String,
        txid: String,
        price: Double? = null,
        currency: String? = null,
        excluded: Boolean = false,
    ) = EventCostBasisFfi(wallet, txid, price, currency, excluded)
}
