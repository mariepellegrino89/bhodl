package io.bhodl.desktop.ui.money

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Formatter tests for [UnitFormat.render] (a shortcut to
 * [BitcoinAmountFormat]; the exhaustive cases live in
 * [BitcoinAmountFormatTest]).
 *
 * UX review 2026-10 — the Auto threshold is 1 BTC (100_000_000 sat):
 * below renders as integer sat, at or above as 8-decimal BTC.
 */
class UnitFormatTest {

    private val en = Locale.US

    @Test
    fun `AUTO_THRESHOLD switches at 1 BTC`() {
        val below = UnitFormat.AUTO_THRESHOLD.render(99_999_999L, locale = en)
        val at = UnitFormat.AUTO_THRESHOLD.render(100_000_000L, locale = en)
        val above = UnitFormat.AUTO_THRESHOLD.render(150_000_000L, locale = en)
        assertEquals("99,999,999 sat", below)
        assertEquals("1.00000000 BTC", at)
        assertEquals("1.50000000 BTC", above)
    }

    @Test
    fun `ALWAYS_SAT renders sat regardless of magnitude`() {
        assertTrue(UnitFormat.ALWAYS_SAT.render(1L, locale = en).endsWith("sat"))
        assertEquals("100,000,000 sat", UnitFormat.ALWAYS_SAT.render(100_000_000L, locale = en))
    }

    @Test
    fun `ALWAYS_BTC renders 8-decimal BTC`() {
        assertEquals("0.00000001 BTC", UnitFormat.ALWAYS_BTC.render(1L, locale = en))
        assertEquals("1.00000000 BTC", UnitFormat.ALWAYS_BTC.render(100_000_000L, locale = en))
    }

    @Test
    fun `negative values render with leading minus`() {
        assertEquals("-50,000 sat", UnitFormat.AUTO_THRESHOLD.render(-50_000L, locale = en))
        assertEquals("-1.00000000 BTC", UnitFormat.ALWAYS_BTC.render(-100_000_000L, locale = en))
    }

    @Test
    fun `bip177 flag is forwarded`() {
        assertEquals("₿50,000", UnitFormat.ALWAYS_SAT.render(50_000L, bip177 = true, locale = en))
    }
}
