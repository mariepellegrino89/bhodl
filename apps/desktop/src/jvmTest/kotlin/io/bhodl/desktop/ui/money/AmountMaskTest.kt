package io.bhodl.desktop.ui.money

import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.formatBtcLike
import io.bhodl.desktop.ui.formatFiat
import io.bhodl.desktop.ui.formatFiatWithCode
import io.bhodl.desktop.ui.formatFiatWithCurrency
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (item 13) — the privacy mask lives INSIDE the
 * central formatters, so every surface that formats through them is
 * covered without per-screen code.
 */
class AmountMaskTest {

    @BeforeTest fun setup() = SettingsStore.reset()

    @AfterTest fun teardown() = SettingsStore.reset()

    private fun hide() = SettingsStore.setBalancesHidden(true)

    private fun assertNoDigits(s: String) =
        assertFalse(s.any { it.isDigit() }, "digits leaked through the mask: '$s'")

    @Test
    fun `bitcoin formatter masks every unit with a fixed width and no sign`() {
        hide()
        assertEquals("••••• sat", BitcoinAmountFormat.format(3_141_592L, UnitFormat.ALWAYS_SAT, locale = Locale.US))
        assertEquals("••••• BTC", BitcoinAmountFormat.format(3_141_592L, UnitFormat.ALWAYS_BTC, locale = Locale.US))
        assertEquals("₿•••••", BitcoinAmountFormat.format(-5L, UnitFormat.ALWAYS_SAT, bip177 = true, signed = true))
        // Same text for 1 sat and 21M BTC: the magnitude does not leak.
        assertEquals(
            BitcoinAmountFormat.format(1L, UnitFormat.AUTO_THRESHOLD),
            BitcoinAmountFormat.format(2_100_000_000_000_000L, UnitFormat.AUTO_THRESHOLD),
        )
    }

    @Test
    fun `privacy off leaves the formatters untouched`() {
        assertEquals("3,141,592 sat", BitcoinAmountFormat.format(3_141_592L, UnitFormat.ALWAYS_SAT, locale = Locale.US))
        assertEquals("1,234.57", formatFiat(1234.567, Locale.US))
        assertEquals("€12.00", formatFiatWithCode(12.0, "EUR", Locale.US))
    }

    @Test
    fun `explicit masked false wins over the global flag`() {
        hide()
        assertEquals(
            "3,141,592 sat",
            BitcoinAmountFormat.format(3_141_592L, UnitFormat.ALWAYS_SAT, locale = Locale.US, masked = false),
        )
        // The BTC body is built from formatBare: it must not re-read the global flag.
        assertEquals(
            "0.03141592 BTC",
            BitcoinAmountFormat.format(3_141_592L, UnitFormat.ALWAYS_BTC, locale = Locale.US, masked = false),
        )
        assertEquals("CHF 3,200.00", formatFiatWithCode(3200.0, "CHF", Locale.US, masked = false))
    }

    @Test
    fun `fiat helpers keep the currency prefix and hide the value`() {
        hide()
        assertEquals("•••••", formatFiat(1234.5))
        assertEquals("€•••••", formatFiatWithCode(-12.0, "EUR", Locale.US))
        // Italian puts the symbol after the number: "••••• €".
        assertEquals("••••• €", formatFiatWithCode(-12.0, "EUR", Locale.ITALY))
        assertEquals("CHF •••••", formatFiatWithCode(3200.0, "CHF", Locale.US))
        assertEquals("$•••••", formatFiatWithCurrency(99.0, FiatCurrency.USD))
    }

    @Test
    fun `chart and legacy helpers are masked too`() {
        hide()
        assertNoDigits(formatBtcLike(123_456UL, asBtc = true))
        assertNoDigits(formatBtcLike(123_456UL, asBtc = false))
        // Chart labels / axis ticks and bare table cells (unit in the header).
        assertEquals("••••• sat", BitcoinAmountFormat.formatCompact(4_200_000L, UnitFormat.ALWAYS_SAT))
        assertEquals("••••• BTC", BitcoinAmountFormat.formatCompact(4_200_000L, UnitFormat.ALWAYS_BTC))
        assertEquals(AmountMask.GLYPHS, BitcoinAmountFormat.formatBare(-3_141_592L, UnitFormat.ALWAYS_SAT, signed = true))
        val column = io.bhodl.desktop.ui.ColumnAmountFormat(UnitFormat.ALWAYS_SAT, false, Locale.US)
        assertEquals(AmountMask.GLYPHS, column.bare(5L))
    }

    @Test
    fun `start hidden arms the session flag but a lock never reveals`() {
        SettingsStore.applyStartHidden()
        assertFalse(SettingsStore.balancesHidden.value)
        SettingsStore.setPrivacyAutoHide(true)
        SettingsStore.applyStartHidden()
        assertTrue(SettingsStore.balancesHidden.value)
        // Start-hidden off + hidden by hand: re-arming keeps it hidden.
        SettingsStore.setPrivacyAutoHide(false)
        SettingsStore.applyStartHidden()
        assertTrue(SettingsStore.balancesHidden.value)
    }

    @Test
    fun `amount signs disappear while masked`() {
        assertEquals("+", io.bhodl.desktop.ui.amountSign(5L, masked = false))
        assertEquals("−", io.bhodl.desktop.ui.amountSign(-5L, masked = false))
        assertEquals("", io.bhodl.desktop.ui.amountSign(0L, masked = false))
        assertEquals("", io.bhodl.desktop.ui.amountSign(5L, masked = true))
        assertEquals("", io.bhodl.desktop.ui.amountSign(-5.0, masked = true))
        hide()
        // Default follows the app-wide flag.
        assertEquals("", io.bhodl.desktop.ui.amountSign(-5L))
        // KPI opportunity cost: neither sign nor value.
        assertEquals("•••••", io.bhodl.desktop.screens.formatOppCost(0.5, { AmountMask.GLYPHS }))
        assertEquals("−1", io.bhodl.desktop.screens.formatOppCost(0.5, { "1" }, masked = false))
    }

    @Test
    fun `masked Auto never switches a column, axis or header to BTC`() {
        val big = listOf(5L, 250_000_000L)
        assertEquals(UnitFormat.ALWAYS_BTC, BitcoinAmountFormat.resolveColumnUnit(UnitFormat.AUTO_THRESHOLD, big))
        assertEquals(
            UnitFormat.ALWAYS_SAT,
            BitcoinAmountFormat.resolveColumnUnit(UnitFormat.AUTO_THRESHOLD, big, masked = true),
        )
        // An explicit BTC preference is not a leak and is kept.
        assertEquals(
            UnitFormat.ALWAYS_BTC,
            BitcoinAmountFormat.resolveColumnUnit(UnitFormat.ALWAYS_BTC, listOf(5L), masked = true),
        )
        assertEquals("sat", BitcoinAmountFormat.unitLabel(
            BitcoinAmountFormat.resolveColumnUnit(UnitFormat.AUTO_THRESHOLD, big, masked = true),
        ))
        hide()
        // Single values in Auto: "••••• sat" for 1 sat and for 21M BTC alike.
        assertEquals("••••• sat", BitcoinAmountFormat.format(2_100_000_000_000_000L, UnitFormat.AUTO_THRESHOLD))
        assertEquals("••••• sat", BitcoinAmountFormat.formatCompact(2_100_000_000_000_000L, UnitFormat.AUTO_THRESHOLD))
    }
}
