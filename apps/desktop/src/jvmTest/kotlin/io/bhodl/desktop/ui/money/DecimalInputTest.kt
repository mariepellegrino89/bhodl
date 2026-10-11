package io.bhodl.desktop.ui.money

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the locale-aware decimal parser used by the tracker dialogs
 * (investments, sells, lots, manual assets, savings goals).
 */
class DecimalInputTest {

    private val it = Locale.ITALY
    private val us = Locale.US

    @Test
    fun `plain integers and single unambiguous separators`() {
        for (loc in listOf(it, us)) {
            assertEquals(42.0, parseDecimalInput("42", loc))
            assertEquals(12.5, parseDecimalInput("12,5", loc))
            assertEquals(12.5, parseDecimalInput("12.5", loc))
            assertEquals(0.5, parseDecimalInput("0.500", loc), "leading 0 can't be a group")
            assertEquals(0.5, parseDecimalInput("0,500", loc))
            assertEquals(1234.567, parseDecimalInput("1234.567", loc), "4-digit head can't be grouped")
            assertEquals(1.23, parseDecimalInput("1,23", loc))
            assertEquals(0.5, parseDecimalInput(".5", loc))
        }
    }

    @Test
    fun `ambiguous three-digit tail resolved by it_IT locale`() {
        assertEquals(350_000.0, parseDecimalInput("350.000", it))
        assertEquals(350.0, parseDecimalInput("350,000", it))
        assertEquals(1_500.0, parseDecimalInput("1.500", it))
    }

    @Test
    fun `ambiguous three-digit tail resolved by en_US locale`() {
        assertEquals(350.0, parseDecimalInput("350.000", us))
        assertEquals(350_000.0, parseDecimalInput("350,000", us))
        assertEquals(1_500.0, parseDecimalInput("1,500", us))
    }

    @Test
    fun `both separators - last one is decimal regardless of locale`() {
        for (loc in listOf(it, us)) {
            assertEquals(1234.56, parseDecimalInput("1.234,56", loc))
            assertEquals(1234.56, parseDecimalInput("1,234.56", loc))
            assertEquals(1_234_567.89, parseDecimalInput("1.234.567,89", loc))
        }
    }

    @Test
    fun `repeated separator is grouping`() {
        for (loc in listOf(it, us)) {
            assertEquals(1_234_567.0, parseDecimalInput("1.234.567", loc))
            assertEquals(1_234_567.0, parseDecimalInput("1,234,567", loc))
        }
    }

    @Test
    fun `malformed input is rejected`() {
        for (loc in listOf(it, us)) {
            assertNull(parseDecimalInput("", loc))
            assertNull(parseDecimalInput("   ", loc))
            assertNull(parseDecimalInput("abc", loc))
            assertNull(parseDecimalInput("1e5", loc))
            assertNull(parseDecimalInput("NaN", loc))
            assertNull(parseDecimalInput("Infinity", loc))
            assertNull(parseDecimalInput("1.2.3", loc), "bad grouping")
            assertNull(parseDecimalInput("1,23,456", loc), "bad grouping")
            assertNull(parseDecimalInput("1,234.5,6", loc), "two decimal marks")
            assertNull(parseDecimalInput(".", loc))
            assertNull(parseDecimalInput(",", loc))
        }
    }

    @Test
    fun `whitespace and sign handling`() {
        assertEquals(1234.5, parseDecimalInput(" 1 234,5 ", it))
        assertEquals(-12.5, parseDecimalInput("-12,5", it))
    }

    @Test
    fun `filterDecimalChars keeps digits and both separators`() {
        assertEquals("1.234,5", filterDecimalChars("1.234,5abc€ "))
    }

    @Test
    fun `formatDecimalInput round-trips through the parser`() {
        for (loc in listOf(it, us)) {
            for (v in listOf(350.125, 1.0E7, 0.5, 1234.5678, 42.0)) {
                assertEquals(v, parseDecimalInput(formatDecimalInput(v, locale = loc), loc), "$v in $loc")
            }
        }
        assertEquals("10000000", formatDecimalInput(1.0E7, locale = us))
        assertEquals("350,125", formatDecimalInput(350.125, locale = it))
    }
}
