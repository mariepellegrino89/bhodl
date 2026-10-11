package io.bhodl.desktop.ui.money

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UX review 2026-10 — the single bitcoin-amount formatter, every mode:
 * Sats (default) / BTC / Auto (1 BTC threshold), the opt-in BIP177 `₿`
 * prefix, locale-aware separators and signs.
 */
class BitcoinAmountFormatTest {

    private val en = Locale.US
    private val it = Locale.ITALY

    private fun f(
        sat: Long,
        unit: UnitFormat,
        bip177: Boolean = false,
        locale: Locale = en,
        signed: Boolean = false,
    ) = BitcoinAmountFormat.format(sat, unit, bip177 = bip177, locale = locale, signed = signed)

    // -- Sats ---------------------------------------------------------------

    @Test
    fun `sats mode groups thousands with the sat suffix`() {
        assertEquals("0 sat", f(0, UnitFormat.ALWAYS_SAT))
        assertEquals("999 sat", f(999, UnitFormat.ALWAYS_SAT))
        assertEquals("1,000 sat", f(1_000, UnitFormat.ALWAYS_SAT))
        assertEquals("3,141,592 sat", f(3_141_592, UnitFormat.ALWAYS_SAT))
        assertEquals("2,100,000,000,000,000 sat", f(2_100_000_000_000_000, UnitFormat.ALWAYS_SAT))
    }

    @Test
    fun `sats mode follows the locale grouping separator`() {
        assertEquals("3.141.592 sat", f(3_141_592, UnitFormat.ALWAYS_SAT, locale = it))
    }

    @Test
    fun `BIP177 replaces the sat suffix with the bitcoin sign prefix`() {
        assertEquals("₿3,141,592", f(3_141_592, UnitFormat.ALWAYS_SAT, bip177 = true))
        assertEquals("₿3.141.592", f(3_141_592, UnitFormat.ALWAYS_SAT, bip177 = true, locale = it))
        assertEquals("₿0", f(0, UnitFormat.ALWAYS_SAT, bip177 = true))
        assertEquals("-₿1,500", f(-1_500, UnitFormat.ALWAYS_SAT, bip177 = true))
    }

    // -- BTC ----------------------------------------------------------------

    @Test
    fun `BTC mode always shows 8 decimals`() {
        assertEquals("0.00000001 BTC", f(1, UnitFormat.ALWAYS_BTC))
        assertEquals("0.03141592 BTC", f(3_141_592, UnitFormat.ALWAYS_BTC))
        assertEquals("1.00000000 BTC", f(100_000_000, UnitFormat.ALWAYS_BTC))
        assertEquals("1,234.50000000 BTC", f(123_450_000_000, UnitFormat.ALWAYS_BTC))
    }

    @Test
    fun `BTC mode follows the locale decimal and grouping separators`() {
        assertEquals("0,03141592 BTC", f(3_141_592, UnitFormat.ALWAYS_BTC, locale = it))
        assertEquals("1.234,50000000 BTC", f(123_450_000_000, UnitFormat.ALWAYS_BTC, locale = it))
    }

    @Test
    fun `BIP177 does not change BTC-denominated amounts`() {
        assertEquals("0.03141592 BTC", f(3_141_592, UnitFormat.ALWAYS_BTC, bip177 = true))
    }

    // -- Auto ---------------------------------------------------------------

    @Test
    fun `auto mode uses sats below 1 BTC and BTC at or above`() {
        assertEquals("99,999,999 sat", f(99_999_999, UnitFormat.AUTO_THRESHOLD))
        assertEquals("1.00000000 BTC", f(100_000_000, UnitFormat.AUTO_THRESHOLD))
        assertEquals("3.14159265 BTC", f(314_159_265, UnitFormat.AUTO_THRESHOLD))
        assertEquals(100_000_000L, BitcoinAmountFormat.AUTO_THRESHOLD_SAT)
    }

    @Test
    fun `auto mode threshold uses the magnitude for negative values`() {
        assertEquals("-99,999,999 sat", f(-99_999_999, UnitFormat.AUTO_THRESHOLD))
        assertEquals("-1.00000000 BTC", f(-100_000_000, UnitFormat.AUTO_THRESHOLD))
    }

    @Test
    fun `auto mode with BIP177 prefixes only the sats side`() {
        assertEquals("₿3,141,592", f(3_141_592, UnitFormat.AUTO_THRESHOLD, bip177 = true))
        assertEquals("1.50000000 BTC", f(150_000_000, UnitFormat.AUTO_THRESHOLD, bip177 = true))
    }

    // -- Signs --------------------------------------------------------------

    @Test
    fun `signed adds plus to positive values only`() {
        assertEquals("+1,000 sat", f(1_000, UnitFormat.ALWAYS_SAT, signed = true))
        assertEquals("-1,000 sat", f(-1_000, UnitFormat.ALWAYS_SAT, signed = true))
        assertEquals("0 sat", f(0, UnitFormat.ALWAYS_SAT, signed = true))
    }

    @Test
    fun `unsigned ULong overload matches the Long one`() {
        assertEquals(
            "3,141,592 sat",
            BitcoinAmountFormat.format(3_141_592uL, UnitFormat.ALWAYS_SAT, locale = en),
        )
    }

    // -- Table columns (UX review 2026-10, rec 2) ---------------------------

    @Test
    fun `auto resolves once per column, never mixing sats and BTC`() {
        val small = listOf(1_000L, 50_000L)
        val mixed = listOf(1_000L, 150_000_000L)
        assertEquals(UnitFormat.ALWAYS_SAT, BitcoinAmountFormat.resolveColumnUnit(UnitFormat.AUTO_THRESHOLD, small))
        assertEquals(UnitFormat.ALWAYS_BTC, BitcoinAmountFormat.resolveColumnUnit(UnitFormat.AUTO_THRESHOLD, mixed))
        // A large negative (liability, net outflow) also flips the column.
        assertEquals(
            UnitFormat.ALWAYS_BTC,
            BitcoinAmountFormat.resolveColumnUnit(UnitFormat.AUTO_THRESHOLD, listOf(-200_000_000L)),
        )
        assertEquals(UnitFormat.ALWAYS_SAT, BitcoinAmountFormat.resolveColumnUnit(UnitFormat.ALWAYS_SAT, mixed))
        assertEquals(UnitFormat.ALWAYS_BTC, BitcoinAmountFormat.resolveColumnUnit(UnitFormat.ALWAYS_BTC, small))
        assertEquals(UnitFormat.ALWAYS_SAT, BitcoinAmountFormat.resolveColumnUnit(UnitFormat.AUTO_THRESHOLD, emptyList()))
    }

    @Test
    fun `bare cells keep fixed decimals and drop the unit`() {
        assertEquals("3,141,592", BitcoinAmountFormat.formatBare(3_141_592, UnitFormat.ALWAYS_SAT, en))
        assertEquals("3.141.592", BitcoinAmountFormat.formatBare(3_141_592, UnitFormat.ALWAYS_SAT, it))
        assertEquals("0.00001000", BitcoinAmountFormat.formatBare(1_000, UnitFormat.ALWAYS_BTC, en))
        assertEquals("1,50000000", BitcoinAmountFormat.formatBare(150_000_000, UnitFormat.ALWAYS_BTC, it))
        assertEquals("-12", BitcoinAmountFormat.formatBare(-12, UnitFormat.ALWAYS_SAT, en))
        assertEquals("+12", BitcoinAmountFormat.formatBare(12, UnitFormat.ALWAYS_SAT, en, signed = true))
    }

    @Test
    fun `unit label for column headers`() {
        assertEquals("sat", BitcoinAmountFormat.unitLabel(UnitFormat.ALWAYS_SAT))
        assertEquals("₿", BitcoinAmountFormat.unitLabel(UnitFormat.ALWAYS_SAT, bip177 = true))
        assertEquals("BTC", BitcoinAmountFormat.unitLabel(UnitFormat.ALWAYS_BTC, bip177 = true))
    }

    @Test
    fun `compact form for chart labels`() {
        assertEquals("950 sat", BitcoinAmountFormat.formatCompact(950, UnitFormat.ALWAYS_SAT, locale = en))
        assertEquals("12,345 sat", BitcoinAmountFormat.formatCompact(12_345, UnitFormat.ALWAYS_SAT, locale = en))
        assertEquals("450k sat", BitcoinAmountFormat.formatCompact(450_200, UnitFormat.ALWAYS_SAT, locale = en))
        assertEquals("3.1M sat", BitcoinAmountFormat.formatCompact(3_141_592, UnitFormat.ALWAYS_SAT, locale = en))
        assertEquals("3,1M sat", BitcoinAmountFormat.formatCompact(3_141_592, UnitFormat.ALWAYS_SAT, locale = it))
        assertEquals("₿3.1M", BitcoinAmountFormat.formatCompact(3_141_592, UnitFormat.ALWAYS_SAT, bip177 = true, locale = en))
        assertEquals("1.25 BTC", BitcoinAmountFormat.formatCompact(125_000_000, UnitFormat.ALWAYS_BTC, locale = en))
        assertEquals("0.0314 BTC", BitcoinAmountFormat.formatCompact(3_141_592, UnitFormat.ALWAYS_BTC, locale = en))
        assertEquals("0.00000001 BTC", BitcoinAmountFormat.formatCompact(1, UnitFormat.ALWAYS_BTC, locale = en))
        assertEquals("0.000123 BTC", BitcoinAmountFormat.formatCompact(12_300, UnitFormat.ALWAYS_BTC, locale = en))
        assertEquals("1.25 BTC", BitcoinAmountFormat.formatCompact(125_000_000, UnitFormat.AUTO_THRESHOLD, locale = en))
        assertEquals("-450k sat", BitcoinAmountFormat.formatCompact(-450_000, UnitFormat.AUTO_THRESHOLD, locale = en))
    }

    @Test
    fun `groupDigits groups by three from the right`() {
        assertEquals("1", BitcoinAmountFormat.groupDigits(1, en))
        assertEquals("12,345", BitcoinAmountFormat.groupDigits(12_345, en))
        assertEquals("123,456", BitcoinAmountFormat.groupDigits(123_456, en))
        assertEquals("1.234.567", BitcoinAmountFormat.groupDigits(1_234_567, it))
    }
}
