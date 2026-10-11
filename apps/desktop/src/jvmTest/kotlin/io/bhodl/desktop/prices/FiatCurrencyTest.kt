package io.bhodl.desktop.prices

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5.0 — sanity checks for the [FiatCurrency] enum.
 *
 * The enum is shipped as the UI's currency picker source-of-truth;
 * the codes are matched against price-provider response keys
 * (CoinGecko lowercase, mempool.space UPPERCASE) and the symbols
 * are rendered in the dashboard's amount overlay. A copy-paste error
 * on either field surfaces silently as "price never updates" — these
 * tests pin both columns.
 */
class FiatCurrencyTest {

    @Test
    fun `NONE has empty code and empty symbol`() {
        assertEquals("", FiatCurrency.NONE.code)
        assertEquals("", FiatCurrency.NONE.symbol)
    }

    @Test
    fun `EUR code matches ISO 4217 and uses the euro sign`() {
        assertEquals("EUR", FiatCurrency.EUR.code)
        assertEquals("€", FiatCurrency.EUR.symbol)
    }

    @Test
    fun `USD code matches ISO 4217 and uses the dollar sign`() {
        assertEquals("USD", FiatCurrency.USD.code)
        assertEquals("$", FiatCurrency.USD.symbol)
    }

    @Test
    fun `CHF code matches ISO 4217 and uses the franc text symbol`() {
        // CHF lacks a unique single-glyph symbol, so we render the
        // code itself — this matches the convention used in Sparrow
        // and Wasabi.
        assertEquals("CHF", FiatCurrency.CHF.code)
        assertEquals("CHF", FiatCurrency.CHF.symbol)
    }

    @Test
    fun `all entries are unique`() {
        val codes = FiatCurrency.entries.map { it.code }
        assertEquals(codes.size, codes.distinct().size)
    }

    @Test
    fun `enum exposes exactly the Phase 5_0 currencies`() {
        val names = FiatCurrency.entries.map { it.name }.sorted()
        assertEquals(listOf("CHF", "EUR", "NONE", "USD"), names)
    }

    @Test
    fun `non NONE entries all have non blank codes and symbols`() {
        for (c in FiatCurrency.entries) {
            if (c == FiatCurrency.NONE) continue
            assertTrue(c.code.isNotBlank(), "code missing for $c")
            assertTrue(c.symbol.isNotBlank(), "symbol missing for $c")
        }
    }
}
