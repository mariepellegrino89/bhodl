package io.bhodl.desktop.screens

import io.bhodl.desktop.screens.PortfolioFixtures.NOW
import io.bhodl.desktop.screens.PortfolioFixtures.TODAY
import io.bhodl.desktop.screens.PortfolioFixtures.asset
import io.bhodl.desktop.screens.PortfolioFixtures.disposal
import io.bhodl.desktop.screens.PortfolioFixtures.investment
import io.bhodl.desktop.screens.PortfolioFixtures.liability
import io.bhodl.desktop.screens.PortfolioFixtures.lot
import io.bhodl.desktop.screens.PortfolioFixtures.row
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.pfc_f_linked_asset_id
import io.bhodl.shared.resources.pfc_k_annual_cost
import io.bhodl.shared.resources.pfc_k_estimated_value
import io.bhodl.shared.resources.pfc_k_gross_yield
import io.bhodl.shared.resources.pfc_k_linked_debt
import io.bhodl.shared.resources.pfc_k_ltv
import io.bhodl.shared.resources.pfc_k_realized
import io.bhodl.shared.resources.pfc_k_repaid
import io.bhodl.shared.resources.pfc_k_term_elapsed
import io.bhodl.shared.resources.pfc_k_utilization
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portfolio redesign 2026-10 — the per-category card models
 * (`PortfolioDetails.kt`): category mapping, the details JSON round
 * trip and the numbers each card derives.
 */
class PortfolioDetailsTest {

    private fun DetailCardModel.attr(label: org.jetbrains.compose.resources.StringResource): CardValue? =
        attrs.firstOrNull { it.label == label }?.value

    private fun assertClose(expected: Double, actual: Double, eps: Double = 0.01) =
        assertTrue(kotlin.math.abs(expected - actual) < eps, "expected $expected, got $actual")

    @Test
    fun `every stored kind maps to a category of the right class`() {
        assertEquals(DetailCategory.ETF, DetailCategory.ofInvestment("ETF"))
        assertEquals(DetailCategory.OTHER_INVESTMENT, DetailCategory.ofInvestment("manual"))
        assertEquals(DetailCategory.LOAN_TO_OTHERS, DetailCategory.ofManualAsset("loans_to_others"))
        assertEquals(DetailCategory.OTHER_ASSET, DetailCategory.ofManualAsset("something-new"))
        assertEquals(DetailCategory.CREDIT_CARD, DetailCategory.ofLiability("credit-card"))
        listOf("stock", "etf", "fund", "currency", "commodity", "manual").forEach {
            assertEquals(PortfolioClass.INVESTMENTS, DetailCategory.ofInvestment(it).cls)
        }
        listOf("cash", "brokerage", "real_estate", "vehicle", "collectible", "business_equity", "loans_to_others", "other")
            .forEach { assertEquals(PortfolioClass.MANUAL_ASSETS, DetailCategory.ofManualAsset(it).cls) }
        listOf("mortgage", "loan", "credit-card", "other")
            .forEach { assertEquals(PortfolioClass.LIABILITIES, DetailCategory.ofLiability(it).cls) }
        assertNull(DetailCategory.WALLET.detailsTable)
        assertEquals("liability", DetailCategory.MORTGAGE.detailsTable)
    }

    @Test
    fun `details JSON keeps typed values and drops blanks, junk and foreign keys`() {
        val json = detailsToJson(
            DetailCategory.MORTGAGE,
            mapOf(
                "original_amount_sat" to "1.000.000",
                "rate_pct" to "2,9",
                "rate_type" to "fixed",
                "monthly_payment" to "780",
                "end_date" to "2044-09-01",
                "linked_asset_id" to "7",
                "isin" to "IE00",
            ),
        )
        assertEquals(
            """{"original_amount_sat":1000000,"rate_pct":2.9,"rate_type":"fixed","monthly_payment":780.0,"end_date":"2044-09-01","linked_asset_id":"7"}""",
            json,
        )
        val dropped = detailsToJson(
            DetailCategory.CASH,
            mapOf("interest_pct" to "abc", "maturity_date" to "14/03/2027", "bank" to "  ", "locked" to "true"),
        )
        assertEquals("""{"locked":true}""", dropped)
        assertEquals("{}", detailsToJson(DetailCategory.ETF, mapOf("distribution" to "monthly")))
        assertEquals(
            mapOf("rate_pct" to "2.9", "linked_asset_id" to "7", "locked" to "true"),
            parseDetailsJson("""{"rate_pct":2.9,"linked_asset_id":"7","locked":true,"x":{"nested":1}}"""),
        )
        assertTrue(parseDetailsJson("not json").isEmpty())
    }

    @Test
    fun `numbers accept both decimal conventions`() {
        assertEquals(1234.5, parseNumber("1.234,5"))
        assertEquals(1234.5, parseNumber("1,234.5"))
        assertEquals(12.5, parseNumber("12,5%"))
        assertNull(parseNumber(""))
        assertNull(parseNumber("12a"))
    }

    @Test
    fun `ETF card shows the unrealized gain and the yearly fund cost from the TER`() {
        val inv = investment(1, "VWCE", "etf", qty = 10.0, cost = 1000.0, price = 120.0)
        val lots = listOf(lot(11, 1, qty = 10.0, price = 100.0, daysAgo = 300, original = 12.0))
        val disposals = listOf(disposal(1, 5, 11, qty = 2.0, basis = 100.0, gain = 30.0))
        val m = investmentCardModel(inv, lots, disposals, mapOf("ter_pct" to "0.22", "isin" to "IE00BK5BQT80"), row(SheetSection.INVESTMENTS, 1, "VWCE", "etf", 2_000_000, pct = 12.0))
        assertEquals(DetailCategory.ETF, m.category)
        assertEquals(CardValue.Sat(2_000_000), m.kpis[0].value)
        assertEquals(CardValue.Qty(10.0, "shares"), m.kpis[1].value)
        assertEquals(CardValue.Fiat(100.0, "EUR"), m.kpis[2].value)
        assertEquals(CardValue.Fiat(200.0, "EUR", signed = true), m.kpis[3].value)
        assertEquals(Tone.POSITIVE, m.kpis[3].tone)
        assertClose(2.64, (m.attr(Res.string.pfc_k_annual_cost) as CardValue.Fiat).amount)
        assertEquals(CardValue.Fiat(30.0, "EUR", signed = true), m.attr(Res.string.pfc_k_realized))
        assertEquals(0.12, m.meter!!.fraction)
    }

    @Test
    fun `currency card shows the FX effect against the purchase rate`() {
        val inv = investment(2, "Dollari", "currency", qty = 1000.0, cost = 950.0, price = 0.90, unit = "USD")
        val m = investmentCardModel(inv, emptyList(), emptyList(), mapOf("interest_pct" to "3"), row(SheetSection.INVESTMENTS, 2, "Dollari", "currency", null))
        assertEquals(CardValue.Qty(1000.0, "USD"), m.kpis[0].value)
        val fx = m.kpis[3].value as CardValue.Pct
        assertClose(-5.26, fx.pct)
        assertEquals(Tone.NEGATIVE, m.kpis[3].tone)
        // No BTC rate: the value falls back to fiat.
        assertEquals(CardValue.Fiat(900.0, "EUR"), m.kpis[1].value)
    }

    @Test
    fun `deposit account card shows interest and how much of the term has run`() {
        val a = asset(3, "Conto deposito", "cash", value = 25_000.0, purchasedDaysAgo = 365)
        val maturity = TODAY.plusDays(365).toString()
        val m = manualAssetCardModel(a, mapOf("interest_pct" to "3.25", "maturity_date" to maturity), row(SheetSection.MANUAL_ASSETS, 3, "Conto deposito", "cash", 1), NOW)
        assertEquals(CardValue.Fiat(812.5, "EUR", signed = true), m.kpis[2].value)
        assertEquals(Res.string.pfc_k_term_elapsed, m.meter!!.label)
        assertClose(0.5, m.meter!!.fraction)
    }

    @Test
    fun `real estate card computes equity, LTV and gross yield from the linked mortgage`() {
        val house = asset(7, "Casa", "real_estate", value = 300_000.0, purchase = 250_000.0, purchasedDaysAgo = 2000)
        val m = manualAssetCardModel(house, mapOf("monthly_rent" to "1000"), row(SheetSection.MANUAL_ASSETS, 7, "Casa", "real_estate", 1), NOW, linkedDebtFiat = 120_000.0)
        assertEquals(CardValue.Fiat(250_000.0, "EUR"), m.kpis[1].value)
        assertClose(20.0, (m.kpis[2].value as CardValue.Pct).pct)
        assertEquals(CardValue.Fiat(180_000.0, "EUR"), m.kpis[3].value)
        assertEquals(Res.string.pfc_k_ltv, m.meter!!.label)
        assertClose(0.4, m.meter!!.fraction)
        assertEquals(CardValue.Fiat(120_000.0, "EUR"), m.attr(Res.string.pfc_k_linked_debt))
        assertClose(4.0, (m.attr(Res.string.pfc_k_gross_yield) as CardValue.Pct).pct)
    }

    @Test
    fun `vehicle card estimates the value from the yearly depreciation`() {
        val car = asset(8, "Auto", "vehicle", value = 18_000.0, purchase = 28_000.0, purchasedDaysAgo = 730)
        val m = manualAssetCardModel(car, mapOf("depreciation_pct" to "12", "km" to "64000"), row(SheetSection.MANUAL_ASSETS, 8, "Auto", "vehicle", 1), NOW)
        val estimate = (m.attr(Res.string.pfc_k_estimated_value) as CardValue.Fiat).amount
        assertClose(28_000.0 * 0.88 * 0.88, estimate, eps = 30.0)
        assertEquals(CardValue.Qty(64_000.0, "km"), m.kpis[3].value)
        assertClose(18.0 / 28.0, m.meter!!.fraction)
    }

    @Test
    fun `business stake card derives the implied company value`() {
        val stake = asset(9, "Studio Srl", "business_equity", value = 60_000.0)
        val m = manualAssetCardModel(stake, mapOf("stake_pct" to "20", "valuation_date" to "2026-01-15"), row(SheetSection.MANUAL_ASSETS, 9, "Studio Srl", "business_equity", 1), NOW)
        assertEquals(CardValue.Fiat(300_000.0, "EUR"), m.kpis[2].value)
        assertClose(0.2, m.meter!!.fraction)
    }

    @Test
    fun `loan to others card shows the repaid share`() {
        val loan = asset(10, "Prestito a Luca", "loans_to_others", value = 3_000.0)
        val m = manualAssetCardModel(loan, mapOf("original_amount" to "5000"), row(SheetSection.MANUAL_ASSETS, 10, "Prestito", "loans_to_others", 1), NOW)
        assertEquals(Res.string.pfc_k_repaid, m.meter!!.label)
        assertClose(0.4, m.meter!!.fraction)
    }

    @Test
    fun `mortgage card shows repaid share, months left and the linked asset name`() {
        val l = liability(20, "Mutuo", "mortgage", sat = 600_000)
        val end = TODAY.plusMonths(215).toString()
        val m = liabilityCardModel(
            l,
            mapOf("original_amount_sat" to "1000000", "rate_pct" to "2.9", "end_date" to end, "linked_asset_id" to "7"),
            NOW,
            "EUR",
        ) { id -> if (id == "7") "Casa" else null }
        assertClose(0.4, m.meter!!.fraction)
        assertEquals(CardValue.Months(215), m.kpis[3].value)
        assertEquals(CardValue.Text("Casa"), m.attr(Res.string.pfc_f_linked_asset_id))
        assertEquals(Tone.NEGATIVE, m.kpis[0].tone)
    }

    @Test
    fun `credit card shows utilisation and the days to the next statement`() {
        val card = liability(21, "Visa", "credit-card", sat = 41_000)
        val m = liabilityCardModel(card, mapOf("credit_limit_sat" to "100000", "statement_day" to "15"), NOW, "EUR")
        assertEquals(Res.string.pfc_k_utilization, m.meter!!.label)
        assertClose(0.41, m.meter!!.fraction)
        assertEquals(Tone.NEGATIVE, m.kpis[2].tone)
        // 25 Oct → 15 Nov.
        assertEquals(CardValue.InDays(21), m.kpis[3].value)
    }

    @Test
    fun `the next statement day handles short months and today`() {
        assertEquals(0, daysUntilDayOfMonth(TODAY, 25))
        assertEquals(6, daysUntilDayOfMonth(TODAY, 31))
        // 31 in February lands on the last day.
        assertEquals(3, daysUntilDayOfMonth(java.time.LocalDate.of(2027, 2, 25), 31))
    }

    @Test
    fun `linked debt sums only the liabilities that point at the asset`() {
        val details = PortfolioDetails(liabilities = mapOf(1L to mapOf("linked_asset_id" to "7"), 2L to mapOf("linked_asset_id" to "8")))
        val ls = listOf(liability(1, "A", "mortgage", 100), liability(2, "B", "loan", 50), liability(3, "C", "loan", 25))
        assertEquals(1.0, linkedDebtFiat(7, ls, details) { it / 100.0 })
        assertNull(linkedDebtFiat(9, ls, details) { it / 100.0 })
        assertNull(linkedDebtFiat(7, ls, details) { null })
    }

    @Test
    fun `wallet card shows its share of the stack`() {
        val m = walletCardModel(row(SheetSection.WALLETS, null, "Cold", "xpub", 750, pct = 5.0, change = -10), stackSat = 1000)
        assertEquals(CardValue.Pct(75.0), m.kpis[1].value)
        assertEquals(CardValue.Sat(-10, signed = true), m.kpis[2].value)
        assertEquals(0.75, m.meter!!.fraction)
        assertTrue(m.attrs.isEmpty())
    }
}
