package io.bhodl.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [`Bip21`]. The BIP-21 spec is small but the
 * encoding details (RFC-3986 vs `URLEncoder`'s `+`-for-space, BTC
 * formatting without `Double`) are easy to get wrong, so they get
 * pinned down here.
 */
class Bip21Test {

    private val ADDR = "tb1pkqkj0qmrjxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"

    @Test
    fun `address-only returns bare bitcoin URI without query string`() {
        assertEquals("bitcoin:$ADDR", Bip21.build(ADDR))
    }

    @Test
    fun `null and blank optionals are dropped`() {
        assertEquals("bitcoin:$ADDR", Bip21.build(ADDR, amountSat = null, label = "", message = "  "))
    }

    @Test
    fun `zero or negative amount is dropped`() {
        assertEquals("bitcoin:$ADDR", Bip21.build(ADDR, amountSat = 0))
        assertEquals("bitcoin:$ADDR", Bip21.build(ADDR, amountSat = -1))
    }

    @Test
    fun `amount is rendered in BTC with no trailing zeros`() {
        assertEquals("bitcoin:$ADDR?amount=1", Bip21.build(ADDR, amountSat = 100_000_000))
        assertEquals(
            "bitcoin:$ADDR?amount=0.12345678",
            Bip21.build(ADDR, amountSat = 12_345_678),
        )
        assertEquals(
            "bitcoin:$ADDR?amount=0.00000001",
            Bip21.build(ADDR, amountSat = 1),
        )
        assertEquals(
            "bitcoin:$ADDR?amount=1.5",
            Bip21.build(ADDR, amountSat = 150_000_000),
        )
    }

    @Test
    fun `label uses percent-encoded spaces, not plus signs`() {
        // URLEncoder defaults to '+' for space; BIP-21 wallets in the
        // wild expect '%20' and some break on '+'. Bip21 must
        // substitute the encoding.
        assertEquals(
            "bitcoin:$ADDR?label=Coffee%20with%20Alice",
            Bip21.build(ADDR, label = "Coffee with Alice"),
        )
    }

    @Test
    fun `unicode label is UTF-8 percent-encoded`() {
        // 'ø' = 0xC3 0xB8 in UTF-8.
        assertEquals(
            "bitcoin:$ADDR?label=B%C3%B8rk",
            Bip21.build(ADDR, label = "Børk"),
        )
    }

    @Test
    fun `reserved characters in label are escaped`() {
        // '?' '&' '=' '#' would corrupt the query if left raw.
        assertEquals(
            "bitcoin:$ADDR?label=a%26b%3Dc%3Fd%23e",
            Bip21.build(ADDR, label = "a&b=c?d#e"),
        )
    }

    @Test
    fun `amount label and message together preserve order`() {
        // Order is fixed: amount → label → message. Wallets treat
        // these as a set, but a stable order makes the result
        // deterministic for tests and easier to eyeball.
        assertEquals(
            "bitcoin:$ADDR?amount=0.001&label=Donation&message=Thanks",
            Bip21.build(
                ADDR,
                amountSat = 100_000,
                label = "Donation",
                message = "Thanks",
            ),
        )
    }

    @Test
    fun `formatBtc rejects negatives`() {
        assertFailsWith<IllegalArgumentException> { Bip21.formatBtc(-1) }
    }

    @Test
    fun `formatBtc edge cases`() {
        assertEquals("0", Bip21.formatBtc(0))
        assertEquals("21000000", Bip21.formatBtc(21_000_000L * 100_000_000L))
    }
}
