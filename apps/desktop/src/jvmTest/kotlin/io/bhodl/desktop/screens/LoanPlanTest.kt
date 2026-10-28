package io.bhodl.desktop.screens

import io.bhodl.desktop.data.LoanPlans
import io.bhodl.desktop.data.withPlannedBalances
import io.bhodl.ffi.LoanScheduleFfi
import io.bhodl.ffi.RegistryHandle
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Schema v10 — a mortgage with an amortization plan, through the REAL
 * registry: its terms saved as the details dialog saves them, payments
 * entered as the off-wallet dialog saves them, the plan read back the
 * way TrackerCache reads it.
 *
 * Plan: 12,000 € at 0 % disbursed on 15 January 2024, 1,000 € a month
 * (first installment 15 February). "Today" is 20 April 2024.
 */
class LoanPlanTest {

    private lateinit var tempDir: File
    private lateinit var registry: RegistryHandle
    private val pin = "482916"

    private fun unix(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    private val today = unix("2024-04-20")

    @BeforeTest
    fun open() {
        tempDir = kotlin.io.path.createTempDirectory("bhodl-loan-").toFile()
        registry = RegistryHandle.createOrLoad(tempDir.absolutePath, pin)
    }

    @AfterTest
    fun close() {
        registry.destroy()
        tempDir.deleteRecursively()
    }

    private fun mortgage(): Long {
        val id = registry.addLiability("Mutuo casa", 1UL, "mortgage", null)
        val json = detailsToJson(
            DetailCategory.MORTGAGE,
            mapOf(
                "original_amount_fiat" to "12000",
                "start_date" to "2024-01-15",
                "rate_pct" to "0",
                "monthly_payment" to "1000",
            ),
            currency = "EUR",
        )
        registry.setPortfolioDetails("liability", id, json)
        return id
    }

    private fun pay(debt: Long, date: String, eur: Double, kind: String) = saveManualTxn(
        registry,
        ManualDialogState(),
        ManualTxnDraft(
            direction = "out", amountSat = 1_000_000UL, amountFiat = eur, currency = "EUR",
            occurredUnix = unix(date), accountRef = null, category = null, note = null,
            debtId = debt, debtPayment = kind,
        ),
    )

    private fun plan(id: Long): LoanScheduleFfi = registry.loanSchedules("EUR", today).single { it.liabilityId == id }

    @Test
    fun `the details editor names the currency of the money fields`() {
        val json = detailsToJson(DetailCategory.MORTGAGE, mapOf("monthly_payment" to "690"), currency = "eur")
        assertEquals("EUR", parseDetailsJson(json)[DETAILS_CURRENCY_KEY])
        // A currency already stored wins over the dashboard's.
        val kept = detailsToJson(DetailCategory.MORTGAGE, mapOf("monthly_payment" to "690", "currency" to "CHF"), currency = "EUR")
        assertEquals("CHF", parseDetailsJson(kept)[DETAILS_CURRENCY_KEY])
        // No money, no currency.
        assertNull(parseDetailsJson(detailsToJson(DetailCategory.MORTGAGE, mapOf("rate_pct" to "2"), "EUR"))[DETAILS_CURRENCY_KEY])
    }

    @Test
    fun `the plan alone takes the debt down month by month`() {
        val id = mortgage()
        val p = plan(id)
        assertEquals("EUR", p.currency)
        assertEquals(9_000.0, p.balanceNow, 1e-6) // February, March, April
        assertEquals(1_000.0, p.paymentNow, 1e-9)
        assertEquals(9u, p.installmentsLeft)
        assertEquals(unix("2024-05-15"), p.nextDueUnix)
        assertEquals(unix("2025-01-15"), p.payoffUnix)
    }

    @Test
    fun `an extra repayment shortens the term or lowers the installment as chosen`() {
        val shorter = mortgage()
        pay(shorter, "2024-04-01", 3_000.0, LoanPlans.EXTRA_SHORTEN_TERM)
        plan(shorter).let { p ->
            assertEquals(6_000.0, p.balanceNow, 1e-6)
            assertEquals(1_000.0, p.paymentNow, 1e-9)
            assertEquals(unix("2024-10-15"), p.payoffUnix)
        }
        val lower = mortgage()
        pay(lower, "2024-04-01", 3_000.0, LoanPlans.EXTRA_LOWER_PAYMENT)
        plan(lower).let { p ->
            assertEquals(700.0, p.paymentNow, 1e-6)
            assertEquals(6_300.0, p.balanceNow, 1e-6)
            assertEquals(unix("2025-01-15"), p.payoffUnix)
        }
    }

    @Test
    fun `a recorded installment different from the plan adjusts it`() {
        val id = mortgage()
        pay(id, "2024-03-16", 2_500.0, LoanPlans.INSTALLMENT)
        assertEquals(7_500.0, plan(id).balanceNow, 1e-6)
        // The row keeps how it paid the debt; an edit can change it.
        val row = registry.listManualTxns().single()
        assertEquals(id, row.liabilityId)
        assertEquals(LoanPlans.INSTALLMENT, row.liabilityPayment)
        saveManualTxn(
            registry,
            ManualDialogState(editing = row),
            ManualTxnDraft("out", 1_000_000UL, 2_500.0, "EUR", unix("2024-03-16"), null, null, null, id, LoanPlans.EXTRA_SHORTEN_TERM),
        )
        assertEquals(LoanPlans.EXTRA_SHORTEN_TERM, registry.listManualTxns().single().liabilityPayment)
    }

    @Test
    fun `a payment in another currency than the plan is refused`() {
        val id = mortgage()
        assertFailsWith<Exception> {
            saveManualTxn(
                registry,
                ManualDialogState(),
                ManualTxnDraft("out", 1_000UL, 10.0, "USD", unix("2024-03-16"), null, null, null, id, LoanPlans.INSTALLMENT),
            )
        }
    }

    @Test
    fun `net worth counts what the plan leaves at today's rate`() {
        val id = mortgage()
        val stored = registry.listLiabilities()
        val planned = withPlannedBalances(stored, mapOf(id to plan(id))) { ccy -> if (ccy == "EUR") 50_000.0 else null }
        // 9,000 € at 50,000 €/BTC.
        assertEquals(18_000_000UL, planned.single().amountSat)
        // Without a rate the stored balance stays.
        assertEquals(stored, withPlannedBalances(stored, mapOf(id to plan(id))) { null })
    }

    @Test
    fun `the mortgage card shows the plan`() {
        val id = mortgage()
        val l = withPlannedBalances(registry.listLiabilities(), mapOf(id to plan(id))) { 50_000.0 }.single()
        val m = liabilityCardModel(l, mapOf("rate_pct" to "0"), today, "EUR", plan = plan(id))
        assertEquals(CardValue.Sat(18_000_000L), m.kpis[0].value)
        assertEquals(CardValue.Fiat(1_000.0, "EUR"), m.kpis[1].value)
        assertEquals(CardValue.Months(9L), m.kpis[3].value)
        assertEquals(0.25, m.meter!!.fraction, 1e-9) // 3,000 of 12,000 repaid
        assertTrue(m.attrs.any { it.value == CardValue.Date(LocalDate.parse("2024-05-15")) }, "next installment")
        assertTrue(m.attrs.any { it.value == CardValue.Date(LocalDate.parse("2025-01-15")) }, "payoff")
    }
}
