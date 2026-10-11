package io.bhodl.desktop.ui

import io.bhodl.desktop.prices.FiatCurrency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 5.0 — pure tests for [formatFiat].
 *
 * No Compose runtime: `formatFiat` is a top-level pure function so
 * the round-trips can be exercised here without standing up a
 * composition. The companion `displayAmountWithFiat` is composable
 * and is covered indirectly by the FiatCurrency / PriceCache tests.
 */
class AmountFormatTest {

    @Test
    fun `zero formats as a single 0 with two cents`() {
        assertEquals("0.00", formatFiat(0.0))
    }

    @Test
    fun `single digit whole number gets the two-decimal padding`() {
        assertEquals("1.00", formatFiat(1.0))
        assertEquals("9.00", formatFiat(9.0))
    }

    @Test
    fun `rounds half up to the nearest cent`() {
        // Code review 2026-10 — the old `(amount * 100).toLong()`
        // truncated, and IEEE 754 made it drop a cent on exact values
        // too (0.29 → "0.28"). Rounding is now HALF_UP on the decimal
        // representation of the Double.
        assertEquals("0.29", formatFiat(0.29))
        assertEquals("1.99", formatFiat(1.985))
        assertEquals("2.50", formatFiat(2.5))
        assertEquals("1.23", formatFiat(1.234))
        assertEquals("1.24", formatFiat(1.236))
        assertEquals("1.01", formatFiat(1.005))
    }

    @Test
    fun `tiny negatives that round to zero carry no minus sign`() {
        assertEquals("0.00", formatFiat(-0.004))
        assertEquals("€0.00", formatFiatWithCode(-0.004, "EUR"))
        assertEquals("-€0.01", formatFiatWithCode(-0.005, "EUR"))
    }

    @Test
    fun `inserts thousands separators every three digits`() {
        assertEquals("1,234.57", formatFiat(1234.567))
        assertEquals("12,345.00", formatFiat(12345.0))
        // 123456.78 happens to round-trip cleanly through IEEE 754.
        assertEquals("123,456.78", formatFiat(123456.78))
    }

    @Test
    fun `formats million-scale numbers without losing digits`() {
        // 1234567.89 has no exact IEEE 754 representation; rounding on
        // its decimal form keeps the cents exact.
        assertEquals("1,234,567.89", formatFiat(1234567.89))
        assertEquals("10,000,000.00", formatFiat(10_000_000.0))
    }

    @Test
    fun `cents below ten get padded to two characters`() {
        assertEquals("1.05", formatFiat(1.05))
        assertEquals("1.01", formatFiat(1.01))
        assertEquals("1.10", formatFiat(1.10))
    }

    @Test
    fun `small fractional values format with leading zero cents`() {
        assertEquals("0.42", formatFiat(0.42))
        assertEquals("1.00", formatFiat(0.999))
    }

    // ---- Phase 7.1 — new formatters + display-mode cycle -------------

    @Test
    fun `formatFiat handles negative amounts with leading minus`() {
        assertEquals("-1.50", formatFiat(-1.50))
        assertEquals("-1,234.57", formatFiat(-1234.567))
    }

    @Test
    fun `formatFiatWithCurrency prefixes the chosen symbol`() {
        assertEquals("€1,234.57", formatFiatWithCurrency(1234.567, FiatCurrency.EUR))
        assertEquals("$1,234.57", formatFiatWithCurrency(1234.567, FiatCurrency.USD))
        // CHF symbol is "Fr." per FiatCurrency.symbol — guard against
        // a future refactor that breaks the contract.
        val chf = formatFiatWithCurrency(1234.567, FiatCurrency.CHF)
        assertTrue(chf.endsWith("1,234.57"), "got: $chf")
    }

    // -- Locale (UX review 2026-10: fiat follows the app language) -----------

    private val en = java.util.Locale.US
    private val it = java.util.Locale.ITALY
    private val nbsp = " "

    @Test
    fun `formatFiat uses the locale separators`() {
        assertEquals("123,456.78", formatFiat(123_456.78, en))
        assertEquals("123.456,78", formatFiat(123_456.78, it))
        assertEquals("-1.234,57", formatFiat(-1234.567, it))
        assertEquals("1,01", formatFiat(1.005, it)) // half-up in every locale
    }

    @Test
    fun `English puts the glyph before the number`() {
        assertEquals("€123,456.78", formatFiatWithCode(123_456.78, "EUR", en))
        assertEquals("-€12.50", formatFiatWithCode(-12.5, "EUR", en))
        assertEquals("$1,234.57", formatFiatWithCode(1234.567, "USD", en))
    }

    @Test
    fun `Italian follows NumberFormat it_IT - glyph after the number`() {
        assertEquals("123.456,78$nbsp€", formatFiatWithCode(123_456.78, "EUR", it))
        assertEquals("-12,50$nbsp€", formatFiatWithCode(-12.5, "EUR", it))
        assertEquals("0,00$nbsp€", formatFiatWithCode(-0.004, "EUR", it))
        assertEquals("1.234,57$nbsp£", formatFiatWithCode(1234.567, "GBP", it))
        // Same layout as the JDK's own Italian currency format.
        val jdk = java.text.NumberFormat.getCurrencyInstance(it).apply {
            currency = java.util.Currency.getInstance("EUR")
        }.format(123_456.78)
        assertEquals(jdk, formatFiatWithCode(123_456.78, "EUR", it))
    }

    @Test
    fun `ISO-code currencies keep the code prefix in every language`() {
        assertEquals("CHF 3,200.00", formatFiatWithCode(3200.0, "CHF", en))
        assertEquals("CHF 3.200,00", formatFiatWithCode(3200.0, "CHF", it))
        assertEquals("-CHF 3.200,00", formatFiatWithCode(-3200.0, "CHF", it))
    }

    @Test
    fun `formatBtcLike emits 8-decimal BTC form when asBtc is true`() {
        assertEquals("0.00000001 BTC", formatBtcLike(1UL, asBtc = true))
        assertEquals("0.00012345 BTC", formatBtcLike(12_345UL, asBtc = true))
        // 1 BTC = 100_000_000 sat
        assertEquals("1.00000000 BTC", formatBtcLike(100_000_000UL, asBtc = true))
        // Whole part comma-grouped past 999 BTC.
        assertEquals("1,234.56789012 BTC", formatBtcLike(123_456_789_012UL, asBtc = true))
    }

    @Test
    fun `formatBtcLike emits comma-grouped integer sat when asBtc is false`() {
        assertEquals("0 sat", formatBtcLike(0UL, asBtc = false))
        assertEquals("999 sat", formatBtcLike(999UL, asBtc = false))
        assertEquals("1,000 sat", formatBtcLike(1_000UL, asBtc = false))
        assertEquals("1,234,567 sat", formatBtcLike(1_234_567UL, asBtc = false))
    }

    @Test
    fun `AmountDisplayMode next cycles BTC SAT FIAT HIDDEN then back`() {
        assertEquals(AmountDisplayMode.SAT, AmountDisplayMode.BTC.next())
        assertEquals(AmountDisplayMode.FIAT, AmountDisplayMode.SAT.next())
        assertEquals(AmountDisplayMode.HIDDEN, AmountDisplayMode.FIAT.next())
        assertEquals(AmountDisplayMode.BTC, AmountDisplayMode.HIDDEN.next())
    }

    @Test
    fun `next with fiatAvailable=false skips FIAT mode`() {
        // From SAT the natural next is FIAT, but with fiat unavailable
        // we hop straight to HIDDEN — otherwise the hero would render
        // an em-dash that looks like a glitch at 56sp.
        assertEquals(
            AmountDisplayMode.HIDDEN,
            AmountDisplayMode.SAT.next(fiatAvailable = false),
        )
        // Other transitions are unaffected.
        assertEquals(
            AmountDisplayMode.SAT,
            AmountDisplayMode.BTC.next(fiatAvailable = false),
        )
        assertEquals(
            AmountDisplayMode.BTC,
            AmountDisplayMode.HIDDEN.next(fiatAvailable = false),
        )
        // Already on FIAT with fiat unavailable: the cycle moves to
        // HIDDEN as if fiat were available — symmetric with the
        // standard cycle once we're past the SAT→FIAT skip.
        assertEquals(
            AmountDisplayMode.HIDDEN,
            AmountDisplayMode.FIAT.next(fiatAvailable = false),
        )
    }

    @Test
    fun `next with fiatAvailable=true matches the default cycle`() {
        AmountDisplayMode.entries.forEach { m ->
            assertEquals(m.next(), m.next(fiatAvailable = true),
                "$m should cycle identically when fiat is available")
        }
    }

    // ── UX review 2026-10 (B7) — one ISO prefix for every currency ──

    @Test
    fun `B7 CHF renders with its code, never a bare F`() {
        assertEquals("CHF 3,200.00", formatFiatWithCode(3_200.0, "CHF"))
        assertEquals("CHF 3,200.00", formatFiatWithCurrency(3_200.0, FiatCurrency.CHF))
        assertEquals("CHF ", fiatPrefix("chf"))
    }

    @Test
    fun `B7 known symbols and arbitrary ISO codes`() {
        assertEquals("€", fiatPrefix("EUR"))
        assertEquals("$", fiatPrefix("USD"))
        assertEquals("£", fiatPrefix("GBP"))
        assertEquals("¥", fiatPrefix("JPY"))
        assertEquals("SEK ", fiatPrefix(" sek "))
        assertEquals("", fiatPrefix(null))
        assertEquals("", fiatPrefix(""))
    }

    @Test
    fun `B7 the sign goes before the currency prefix`() {
        assertEquals("-€12.50", formatFiatWithCode(-12.5, "EUR"))
        assertEquals("-CHF 1.00", formatFiatWithCode(-1.0, "CHF"))
    }
}
