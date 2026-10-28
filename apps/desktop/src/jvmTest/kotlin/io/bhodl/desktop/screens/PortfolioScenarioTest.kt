package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.computeBtcCostBasis
import io.bhodl.ffi.LotSelectionFfi
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.RegistryHandle
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end portfolio scenarios: buys and sells booked through the REAL
 * registry (SQLCipher in a temp dir, the same Rust code the app runs),
 * then read back and fed to the Kotlin aggregations the Portfolio screen
 * renders — P&L, investments totals and the shared net worth.
 *
 * Market: BTC = 50 000 EUR. Dates are UTC midnights one day apart.
 */
class PortfolioScenarioTest {

    private lateinit var tempDir: File
    private lateinit var registry: RegistryHandle

    private val day = 86_400L
    private val d1 = 1_700_006_400L
    private val d2 = d1 + day
    private val d3 = d2 + day
    private val d4 = d3 + day
    private val btcEur = 50_000.0
    private val pin = "482916"

    @BeforeTest
    fun open() {
        tempDir = kotlin.io.path.createTempDirectory("bhodl-portfolio-").toFile()
        registry = RegistryHandle.createOrLoad(tempDir.absolutePath, pin)
    }

    @AfterTest
    fun close() {
        registry.destroy()
        tempDir.deleteRecursively()
    }

    private fun etf(price: Double? = null, method: String = "fifo"): Long {
        val id = registry.addInvestment(
            name = "VWCE", assetType = "etf", ticker = "VWCE.DE", quantity = 0.0, unit = "shares",
            costBasisFiat = 0.0, costBasisCurrency = "EUR", purchaseUnix = d1,
            manualCurrentValue = price, notes = null,
        )
        registry.setInvestmentCostBasisMethod(id, method)
        return id
    }

    private fun lots(id: Long) = registry.listAllInvestmentLots().filter { it.investmentId == id }
    private fun disposals() = registry.listAllLotDisposals()
    private fun inv(id: Long) = registry.listInvestments().single { it.id == id }

    @Test
    fun `buy two lots then a partial FIFO sell books realized and leaves the right remainder`() {
        val id = etf(price = 130.0)
        registry.addInvestmentLot(id, 10.0, 100.0, 5.0, d1, null)
        registry.addInvestmentLot(id, 10.0, 120.0, 5.0, d2, null)

        // UI preview must match what the registry books.
        val preview = previewSellPnl(lots(id), CostBasisMethod.FIFO, 15.0, 140.0, 3.0)!!
        registry.recordSell(id, d3, 140.0, 15.0, 3.0, null)

        // lot1: (140 − 100.5) × 10 − 2 = 393 ; lot2: (140 − 120.5) × 5 − 1 = 96.5
        val realized = realizedPnl(disposals())
        assertEquals(489.5, realized, 1e-9)
        assertEquals(preview, realized, 1e-9)

        val remaining = lots(id)
        assertEquals(5.0, effectiveQuantity(inv(id), remaining), 1e-9)
        assertEquals(listOf(0.0, 5.0), remaining.map { it.quantity })
        // 5 units left at 120 + 0.5 fee/unit, worth 130 → +47.5
        assertEquals(47.5, unrealizedPnl(remaining, 130.0, disposals())!!, 1e-9)
    }

    @Test
    fun `after a partial sell the remaining cost basis carries only the unsold share of fees`() {
        val id = etf(price = 130.0)
        registry.addInvestmentLot(id, 10.0, 120.0, 10.0, d1, null)
        registry.recordSell(id, d2, 140.0, 5.0, 0.0, null)

        // 5 units left: cost 5 × (120 + 1) = 605; value 650; P&L 45.
        val totals = computeInvestmentsTotals(
            investments = listOf(inv(id)),
            lotsByInvestmentId = mapOf(id to lots(id)),
            disposalsByInvestmentId = emptyMap(),
            dashboardFiatCode = "EUR",
            rateFor = { btcEur },
        )
        assertEquals(650.0, totals.totalValueFiat, 1e-9)
        assertEquals(605.0, totals.totalCostFiat, 1e-9)
        assertEquals(
            unrealizedPnl(lots(id), 130.0, disposals())!!,
            totals.totalValueFiat - totals.totalCostFiat,
            1e-9,
        )
    }

    @Test
    fun `HIFO sells the most expensive lot first`() {
        val id = etf(price = 100.0, method = "hifo")
        val cheap = registry.addInvestmentLot(id, 10.0, 80.0, 0.0, d1, null)
        val dear = registry.addInvestmentLot(id, 10.0, 150.0, 0.0, d2, null)
        registry.recordSell(id, d3, 100.0, 10.0, 0.0, null)

        val d = disposals().single()
        assertEquals(dear, d.lotId)
        assertEquals(-500.0, d.gainLossFiat, 1e-9) // loss harvested
        assertEquals(10.0, lots(id).single { it.id == cheap }.quantity, 1e-9)
    }

    @Test
    fun `specific-id sell consumes exactly the picked lots`() {
        val id = etf(price = 100.0, method = "specific_id")
        val a = registry.addInvestmentLot(id, 10.0, 90.0, 0.0, d1, null)
        val b = registry.addInvestmentLot(id, 10.0, 110.0, 0.0, d2, null)
        registry.recordSellWithLots(
            id, d3, 120.0, 6.0, 0.0, null,
            listOf(LotSelectionFfi(a, 2.0), LotSelectionFfi(b, 4.0)),
        )
        val byLot = disposals().associateBy { it.lotId }
        assertEquals(60.0, byLot.getValue(a).gainLossFiat, 1e-9) // 2 × 30
        assertEquals(40.0, byLot.getValue(b).gainLossFiat, 1e-9) // 4 × 10
        assertEquals(listOf(8.0, 6.0), lots(id).map { it.quantity })
    }

    @Test
    fun `overselling and selling before the purchase date are refused`() {
        val id = etf(price = 100.0)
        registry.addInvestmentLot(id, 10.0, 100.0, 0.0, d2, null)
        assertFailsWith<Exception> { registry.recordSell(id, d3, 100.0, 11.0, 0.0, null) }
        assertFailsWith<Exception> { registry.recordSell(id, d1, 100.0, 1.0, 0.0, null) }
        assertTrue(disposals().isEmpty())
        assertEquals(10.0, lots(id).single().quantity, 1e-9)
    }

    @Test
    fun `deleting a sell restores the lots and removes the realized P&L`() {
        val id = etf(price = 100.0)
        registry.addInvestmentLot(id, 10.0, 100.0, 0.0, d1, null)
        val sell = registry.recordSell(id, d2, 150.0, 4.0, 0.0, null)
        assertEquals(200.0, realizedPnl(disposals()), 1e-9)

        assertTrue(registry.deleteInvestmentSell(sell))
        assertTrue(disposals().isEmpty())
        assertEquals(10.0, lots(id).single().quantity, 1e-9)
    }

    @Test
    fun `spending 100 euro from a bank account lowers net worth by 100 euro in sat`() {
        val bank = registry.addManualAsset("Conto", "cash", 1_000.0, "EUR", null, null, null, null, null)
        fun netWorth() = computeTrackerNetWorth(
            walletSat = 0UL,
            investments = emptyList(),
            lotsByInvestmentId = emptyMap(),
            cachedInvestmentsSat = 0UL,
            manualAssets = registry.listManualAssets(),
            cachedManualAssetsSat = 0UL,
            liabilities = registry.listLiabilities(),
            liveBtcRate = btcEur,
            dashboardFiatCode = "EUR",
        ).netWorthSat
        val before = netWorth()
        registry.addManualTxn("out", 200_000UL, 100.0, "EUR", d2, "asset:$bank", "Spesa", null, null)
        // 100 EUR at 50,000 EUR/BTC; fiat values are truncated to the sat, as in every aggregate.
        assertTrue(kotlin.math.abs(before - 200_000L - netWorth()) <= 1L, "before $before, after ${netWorth()}")
    }

    @Test
    fun `net worth adds wallet BTC, investments and manual assets and subtracts liabilities`() {
        val id = etf(price = 125.0)
        registry.addInvestmentLot(id, 20.0, 100.0, 0.0, d1, null)
        registry.recordSell(id, d2, 125.0, 4.0, 0.0, null) // 16 left × 125 = 2 000 EUR
        registry.addManualAsset("Conto", "cash", 5_000.0, "EUR", null, null, null, null, null)
        registry.addLiability("Mutuo", 10_000_000UL, "mortgage", null) // 0.1 BTC

        val walletSat = 50_000_000UL // 0.5 BTC on-chain
        val nw = computeTrackerNetWorth(
            walletSat = walletSat,
            investments = registry.listInvestments(),
            lotsByInvestmentId = registry.listAllInvestmentLots().groupBy { it.investmentId },
            cachedInvestmentsSat = 0UL,
            manualAssets = registry.listManualAssets(),
            cachedManualAssetsSat = 0UL,
            liabilities = registry.listLiabilities(),
            liveBtcRate = btcEur,
            dashboardFiatCode = "EUR",
        )
        assertEquals(4_000_000UL, nw.investmentsSat) // 2 000 / 50 000
        assertEquals(10_000_000UL, nw.manualAssetsSat) // 5 000 / 50 000
        assertEquals(64_000_000UL, nw.assetsSat)
        assertEquals(54_000_000L, nw.netWorthSat)

        // Selling the rest moves the value out of investments: the
        // investments bucket drops to 0 (proceeds are not auto-booked).
        registry.recordSell(id, d3, 125.0, 16.0, 0.0, null)
        val after = computeTrackerNetWorth(
            walletSat = walletSat,
            investments = registry.listInvestments(),
            lotsByInvestmentId = registry.listAllInvestmentLots().groupBy { it.investmentId },
            cachedInvestmentsSat = 0UL,
            manualAssets = registry.listManualAssets(),
            cachedManualAssetsSat = 0UL,
            liabilities = registry.listLiabilities(),
            liveBtcRate = btcEur,
            dashboardFiatCode = "EUR",
        )
        assertEquals(0UL, after.investmentsSat)
        assertEquals(500.0, realizedPnl(disposals()), 1e-9) // 20 × 25
    }

    @Test
    fun `BTC bought, partly spent and sold flows into average cost and net worth`() {
        // On-chain history of a tracked wallet, average cost at the
        // day's market price (20k, 60k, then a 50k sale).
        val market = mapOf(d1 to 20_000.0, d2 to 60_000.0, d4 to 50_000.0)
        val legs = listOf(
            leg("a", PaymentDirection.INCOMING, 100_000_000UL, d1),
            leg("b", PaymentDirection.INCOMING, 100_000_000UL, d2),
            leg("c", PaymentDirection.OUTGOING, 150_000_000UL, d4),
        )
        val cb = computeBtcCostBasis(legs, emptyMap(), "EUR", { market[it] }, btcEur)
        assertEquals(50_000_000L, cb.heldSat)
        assertEquals(40_000.0, cb.averagePrice!!, 1e-6)
        assertEquals(15_000.0, cb.realizedFiat, 1e-6) // 1.5 × (50k − 40k)
        assertEquals(5_000.0, cb.unrealizedFiat!!, 1e-6) // 0.5 × (50k − 40k)

        val nw = computeTrackerNetWorth(
            walletSat = cb.heldSat.toULong(),
            investments = emptyList(),
            lotsByInvestmentId = emptyMap(),
            cachedInvestmentsSat = 0UL,
            manualAssets = emptyList(),
            cachedManualAssetsSat = 0UL,
            liabilities = emptyList(),
            liveBtcRate = btcEur,
            dashboardFiatCode = "EUR",
        )
        assertEquals(cb.heldSat, nw.netWorthSat)
        assertEquals(cb.currentValueFiat!!, nw.netWorthSat / 1e8 * btcEur, 1e-6)
    }

    private fun leg(txid: String, direction: PaymentDirection, amount: ULong, ts: Long) = PaymentEvent(
        walletId = "w",
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = direction,
        amountSat = amount,
        feeSat = null,
        timestampUnix = ts.toULong(),
        blockHeight = null,
        category = null,
        cryptoEventKind = null,
    )
}
