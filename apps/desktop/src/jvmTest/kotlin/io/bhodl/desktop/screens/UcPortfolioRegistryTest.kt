package io.bhodl.desktop.screens

import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Area D — investment / lot flows booked through the REAL registry and read
 * back with the helpers the Portfolio screen uses.
 */
class UcPortfolioRegistryTest {

    private lateinit var tempDir: File
    private lateinit var registry: RegistryHandle

    private val d1 = 1_700_006_400L
    private val day = 86_400L

    @BeforeTest
    fun open() {
        tempDir = kotlin.io.path.createTempDirectory("bhodl-uc-portfolio-").toFile()
        registry = RegistryHandle.createOrLoad(tempDir.absolutePath, "482916")
    }

    @AfterTest
    fun close() {
        registry.destroy()
        tempDir.deleteRecursively()
    }

    private fun addInv(qty: Double, cost: Double, price: Double? = null): Long = registry.addInvestment(
        name = "ACME", assetType = "stock", ticker = null, quantity = qty, unit = "shares",
        costBasisFiat = cost, costBasisCurrency = "EUR", purchaseUnix = d1,
        manualCurrentValue = price, notes = null,
    )

    private fun inv(id: Long) = registry.listInvestments().single { it.id == id }
    private fun lots(id: Long) = registry.listAllInvestmentLots().filter { it.investmentId == id }

    /** What the dialog host does on Save of a NEW investment. */
    private fun createFromDialog(qty: Double, cost: Double): Long {
        val id = addInv(qty, cost)
        if (shouldSeedLotOnCreate(qty, cost)) registry.seedInvestmentLotFromAggregate(id)
        return id
    }

    /** What the dialog host does on Save of an EDIT (single-lot sync). */
    private fun editFromDialog(id: Long, qty: Double, cost: Double) {
        val current = inv(id)
        registry.updateInvestment(
            id, current.name, current.assetType, current.ticker, qty, current.unit, cost,
            current.costBasisCurrency, current.purchaseUnix, current.manualCurrentValue, null,
        )
        val l = lots(id)
        singleLotResync(l, qty, cost)?.let {
            val lot = l.single()
            registry.updateInvestmentLot(lot.id, it.quantity, it.pricePerUnit, lot.feesFiat, lot.purchaseUnix, lot.notes)
        }
    }

    @Test
    fun `PRT-002 seed decision needs both a quantity and a cost`() {
        assertTrue(shouldSeedLotOnCreate(10.0, 1_000.0))
        assertFalse(shouldSeedLotOnCreate(0.0, 1_000.0))
        assertFalse(shouldSeedLotOnCreate(10.0, 0.0))
        assertFalse(shouldSeedLotOnCreate(-1.0, 10.0))
        assertFalse(shouldSeedLotOnCreate(Double.NaN, 10.0))
    }

    @Test
    fun `PRT-002 creating from the dialog seeds one lot matching the aggregate`() {
        val id = createFromDialog(10.0, 1_000.0)
        val l = lots(id).single()
        assertEquals(10.0, l.quantity, 1e-9)
        assertEquals(100.0, l.pricePerUnit, 1e-9)
        assertEquals(d1, l.purchaseUnix)
        assertEquals(10.0, effectiveQuantity(inv(id), lots(id)), 1e-9)
        assertEquals(1_000.0, effectiveCostBasis(inv(id), lots(id)), 1e-9)
    }

    @Test
    fun `PRT-002 a zero-cost position is created without a lot`() {
        val id = createFromDialog(5.0, 0.0)
        assertTrue(lots(id).isEmpty())
        assertEquals(5.0, effectiveQuantity(inv(id), lots(id)), 1e-9)
    }

    @Test
    fun `PRT-009 single-lot resync returns null unless exactly one lot and positive quantity`() {
        val id = addInv(0.0, 0.0)
        assertNull(singleLotResync(emptyList(), 1.0, 10.0))
        registry.addInvestmentLot(id, 1.0, 10.0, 0.0, d1, null)
        assertNull(singleLotResync(lots(id), 0.0, 10.0))
        registry.addInvestmentLot(id, 1.0, 10.0, 0.0, d1, null)
        assertNull(singleLotResync(lots(id), 2.0, 20.0))
    }

    @Test
    fun `PRT-009 editing qty and cost rewrites the single lot so the row cost matches`() {
        val id = addInv(0.0, 0.0)
        registry.addInvestmentLot(id, 10.0, 100.0, 20.0, d1, "note")
        editFromDialog(id, 12.0, 1_500.0)
        val l = lots(id).single()
        assertEquals(12.0, l.quantity, 1e-9)
        assertEquals(20.0, l.feesFiat, 1e-9)
        assertEquals("note", l.notes)
        assertEquals(1_500.0, effectiveCostBasis(inv(id), lots(id)), 1e-9)
    }

    @Test
    fun `PRT-009 with a partly sold lot only the unsold share of fees stays in the cost`() {
        val id = addInv(0.0, 0.0, price = 130.0)
        registry.addInvestmentLot(id, 10.0, 100.0, 10.0, d1, null)
        registry.recordSell(id, d1 + day, 140.0, 4.0, 0.0, null)
        // 6 left; the user re-states them as 8 units costing 880 in total.
        editFromDialog(id, 8.0, 880.0)
        val l = lots(id).single()
        assertEquals(8.0, l.quantity, 1e-9)
        assertEquals(12.0, l.originalQuantity!!, 1e-9) // 8 held + 4 sold
        assertEquals(880.0, effectiveCostBasis(inv(id), lots(id)), 1e-9)
    }

    @Test
    fun `PRT-009 a cost below the fees clamps the lot price at zero`() {
        val id = addInv(0.0, 0.0)
        registry.addInvestmentLot(id, 2.0, 10.0, 50.0, d1, null)
        val r = singleLotResync(lots(id), 2.0, 10.0)!!
        assertEquals(0.0, r.pricePerUnit, 1e-12)
    }

    @Test
    fun `PRT-024 first lot on a legacy aggregate seeds it first so nothing is double counted`() {
        val id = addInv(4.0, 400.0)
        assertTrue(lots(id).isEmpty())
        // LotEditDialog.onSave host: seed the aggregate, then add the new lot.
        registry.seedInvestmentLotFromAggregate(id)
        registry.addInvestmentLot(id, 2.0, 150.0, 0.0, d1 + day, null)
        assertEquals(2, lots(id).size)
        assertEquals(6.0, effectiveQuantity(inv(id), lots(id)), 1e-9)
        assertEquals(700.0, effectiveCostBasis(inv(id), lots(id)), 1e-9)
    }

    @Test
    fun `PRT-008 metadata edits are visible on the next read`() {
        val id = addInv(1.0, 10.0)
        assertTrue(
            registry.updateInvestment(id, "ACME Corp", "etf", "ACM", 1.0, "units", 10.0, "USD", d1, 12.5, "n"),
        )
        val r = inv(id)
        assertEquals(listOf("ACME Corp", "etf", "ACM", "units", "USD"), listOf(r.name, r.assetType, r.ticker, r.unit, r.costBasisCurrency))
        assertEquals(12.5, r.manualCurrentValue)
        assertFalse(registry.updateInvestment(id + 99, "x", "etf", null, 1.0, "u", 1.0, "EUR", d1, null, null))
    }

    @Test
    fun `PRT-034 the FFI writes a lot BTC rate and rejects invalid ones`() {
        val id = addInv(0.0, 0.0)
        val lot = registry.addInvestmentLot(id, 1.0, 100.0, 0.0, d1, null)
        assertTrue(registry.setInvestmentLotBtcRate(lot, 38_000.0))
        assertEquals(38_000.0, lots(id).single().btcPerUnitAtPurchase)
        assertFalse(registry.setInvestmentLotBtcRate(lot + 50, 38_000.0))
        assertFailsWith<WalletException> { registry.setInvestmentLotBtcRate(lot, 0.0) }
        assertFailsWith<WalletException> { registry.setInvestmentLotBtcRate(lot, Double.NaN) }
        assertEquals(38_000.0, lots(id).single().btcPerUnitAtPurchase)
    }

    @Test
    fun `GAP-032 per-investment lot and sell lists through the FFI`() {
        val a = addInv(0.0, 0.0)
        val b = addInv(0.0, 0.0)
        registry.addInvestmentLot(a, 3.0, 10.0, 0.0, d1, null)
        registry.addInvestmentLot(b, 5.0, 10.0, 0.0, d1, null)
        val sell = registry.recordSell(b, d1 + day, 12.0, 2.0, 0.0, null)
        assertEquals(listOf(3.0), registry.listInvestmentLots(a).map { it.quantity })
        assertEquals(listOf(3.0), registry.listInvestmentLots(b).map { it.quantity })
        assertTrue(registry.listInvestmentSells(a).isEmpty())
        assertEquals(listOf(sell), registry.listInvestmentSells(b).map { it.id })
        assertTrue(registry.listInvestmentLots(999).isEmpty())
    }
}
