package io.bhodl.desktop.screens

import io.bhodl.desktop.prices.FiatCurrency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7.2 — pure tests for [bulletsHeroFor].
 *
 * The privacy-mask renderer must keep the silhouette of the visible
 * amount: same length, same separators, same currency symbol — only
 * digits become bullets. This guarantees the hero text doesn't visually
 * jolt when the user toggles between revealed and hidden.
 */
class NetWorthHeroBulletsTest {

    @Test
    fun `bullets keep the fiat currency symbol intact`() {
        val out = bulletsHeroFor(
            sat = 50_000_000UL,
            fiatValue = 12_345.67,
            currency = FiatCurrency.EUR,
        )
        assertTrue(out.startsWith("€"), "expected € prefix, got: $out")
    }

    @Test
    fun `bullets keep thousands separator and decimal point`() {
        val out = bulletsHeroFor(
            sat = 50_000_000UL,
            fiatValue = 12_345.67,
            currency = FiatCurrency.EUR,
        )
        // 12,345.67 → "€●●,●●●.●●"
        assertTrue(out.contains(","), "expected thousands separator preserved: $out")
        assertTrue(out.contains("."), "expected decimal preserved: $out")
        assertEquals("€●●,●●●.●●", out)
    }

    @Test
    fun `bullets replace every digit and no non-digit characters`() {
        val out = bulletsHeroFor(
            sat = 50_000_000UL,
            fiatValue = 999.99,
            currency = FiatCurrency.USD,
        )
        // Every char that was a digit in "$999.99" should now be "●".
        for (ch in out) {
            assertFalse(ch.isDigit(), "digit leaked through bullets at '$ch' in $out")
        }
    }

    @Test
    fun `bullets fall back to BTC silhouette when fiat is unavailable`() {
        val out = bulletsHeroFor(
            sat = 12_345_678UL,
            fiatValue = null,
            currency = FiatCurrency.NONE,
        )
        // formatBtcLike for 12_345_678 sat → "0.12345678 BTC"
        // → bullets variant ends with " BTC", contains ".", no digits.
        assertTrue(out.endsWith(" BTC"), "expected BTC suffix, got: $out")
        assertTrue(out.contains("."), "expected BTC decimal preserved, got: $out")
        for (ch in out.removeSuffix(" BTC")) {
            assertFalse(ch.isDigit(), "digit leaked through BTC bullets: $out")
        }
    }

    @Test
    fun `bullets length matches the visible amount length`() {
        // Silhouette stability is the whole point — the masked string
        // must be the exact same length as the revealed string.
        val visible = io.bhodl.desktop.ui.formatFiatWithCurrency(12_345.67, FiatCurrency.EUR)
        val hidden = bulletsHeroFor(
            sat = 50_000_000UL,
            fiatValue = 12_345.67,
            currency = FiatCurrency.EUR,
        )
        assertEquals(visible.length, hidden.length,
            "silhouette length must match — visible: $visible | hidden: $hidden")
    }
}
