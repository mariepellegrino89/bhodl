package io.bhodl.desktop.ui.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.3.1 — domain tests for [MoneyValue].
 *
 * Focus on the two helper constructors and the sign-preservation on
 * [MoneySemantic.Pnl], because those are the data-shape contracts the
 * `<Money>` composable relies on.
 */
class MoneyValueTest {

    @Test
    fun `fromSat populates sat field and leaves fiat unpopulated`() {
        val v = MoneyValue.fromSat(sat = 123_456L, currency = "EUR")
        assertEquals(123_456L, v.sat)
        assertNull(v.fiatAmount)
        assertEquals("EUR", v.fiatCurrency)
        assertNull(v.rateBtcFiat)
        // Default semantic is AtNow per fromSat — secondary will be
        // resolved via the live PriceCache at render time.
        assertEquals(MoneySemantic.AtNow, v.semantic)
    }

    @Test
    fun `fromFiat with rate derives sat correctly`() {
        // 50 EUR at 50_000 EUR/BTC = 0.001 BTC = 100_000 sat.
        val v = MoneyValue.fromFiat(
            amount = 50.0,
            currency = "EUR",
            rate = 50_000.0,
        )
        assertNotNull(v.sat)
        assertEquals(100_000L, v.sat!!)
        assertEquals(50.0, v.fiatAmount!!, 0.0001)
        assertEquals("EUR", v.fiatCurrency)
        assertEquals(50_000.0, v.rateBtcFiat!!, 0.0001)
        // Default semantic for fromFiat is AtCost — the rate is a
        // snapshot, not a live quote.
        assertEquals(MoneySemantic.AtCost, v.semantic)
    }

    @Test
    fun `fromFiat with zero rate produces null sat (avoids div by zero)`() {
        val v = MoneyValue.fromFiat(amount = 1.0, currency = "EUR", rate = 0.0)
        // Caller passed a degenerate rate; we don't crash — we just
        // can't compute the sat side.
        assertNull(v.sat)
    }

    @Test
    fun `Pnl semantic preserves sign on the sat field`() {
        val gain = MoneyValue(
            sat = 42_000L,
            fiatAmount = 21.0,
            fiatCurrency = "EUR",
            semantic = MoneySemantic.Pnl,
        )
        val loss = MoneyValue(
            sat = -10_000L,
            fiatAmount = -5.0,
            fiatCurrency = "EUR",
            semantic = MoneySemantic.Pnl,
        )
        assertTrue(gain.sat!! > 0L)
        assertTrue(loss.sat!! < 0L)
    }
}
