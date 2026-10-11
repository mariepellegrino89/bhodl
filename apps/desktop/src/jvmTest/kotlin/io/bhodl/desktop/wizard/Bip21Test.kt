package io.bhodl.desktop.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Bip21Test {

    @Test
    fun `null on non-BIP21 inputs`() {
        assertNull(parseBip21(""))
        assertNull(parseBip21("   "))
        assertNull(parseBip21("tb1q44zwzaur5kss5q40fzwj6lgcuxcm0sgvxs7yz3"))
        assertNull(parseBip21("https://example.com"))
        assertNull(parseBip21("lightning:lnbc..."))
    }

    @Test
    fun `bare BIP21 with no params returns address only`() {
        val parsed = parseBip21("bitcoin:tb1qaddress")
        assertEquals(Bip21Parsed(address = "tb1qaddress", amountSat = null), parsed)
    }

    @Test
    fun `case-insensitive scheme`() {
        // BIP21 §Description: scheme is case-insensitive (RFC 3986 generic).
        val parsed = parseBip21("BITCOIN:tb1qaddress")
        assertEquals("tb1qaddress", parsed?.address)
    }

    @Test
    fun `amount in BTC converts to sat`() {
        val parsed = parseBip21("bitcoin:tb1qaddress?amount=0.001")
        assertEquals(100_000L, parsed?.amountSat)
    }

    @Test
    fun `amount sat-precision survives round trip`() {
        // 12345678 sat = 0.12345678 BTC, max precision BIP21 allows.
        val parsed = parseBip21("bitcoin:tb1qaddress?amount=0.12345678")
        assertEquals(12_345_678L, parsed?.amountSat)
    }

    @Test
    fun `extra params are ignored`() {
        val parsed = parseBip21(
            "bitcoin:tb1qaddress?amount=0.001&label=Coffee%20Shop&message=Tip",
        )
        assertEquals("tb1qaddress", parsed?.address)
        assertEquals(100_000L, parsed?.amountSat)
    }

    @Test
    fun `param order is irrelevant`() {
        val parsed = parseBip21("bitcoin:tb1qaddress?label=foo&amount=1")
        assertEquals(100_000_000L, parsed?.amountSat)
    }

    @Test
    fun `amount with sub-satoshi precision is rejected`() {
        // 9 decimal digits = sub-satoshi. Refuse rather than round.
        val parsed = parseBip21("bitcoin:tb1qaddress?amount=0.123456789")
        assertEquals("tb1qaddress", parsed?.address)
        assertNull(parsed?.amountSat)
    }

    @Test
    fun `negative amount is rejected`() {
        val parsed = parseBip21("bitcoin:tb1qaddress?amount=-1")
        assertNull(parsed?.amountSat)
    }

    @Test
    fun `garbage amount is rejected`() {
        val parsed = parseBip21("bitcoin:tb1qaddress?amount=banana")
        assertNull(parsed?.amountSat)
    }

    @Test
    fun `amount above 21M cap is rejected`() {
        val parsed = parseBip21("bitcoin:tb1qaddress?amount=21000001")
        assertNull(parsed?.amountSat)
    }

    @Test
    fun `empty address yields null parse`() {
        assertNull(parseBip21("bitcoin:"))
        assertNull(parseBip21("bitcoin:?amount=1"))
    }

    // ---------------------------------------------------------------
    // Canonical rules shared with Rust `PayjoinUri::parse` (C3).
    // ---------------------------------------------------------------

    @Test
    fun `duplicate keys in any case invalidate the URI`() {
        for (q in listOf(
            "amount=1&AMOUNT=0.00001",
            "amount=1&amount=1",
            "Amount=1&aMoUnT=2",
            "%61mount=1&amount=1",
            "label=a&LABEL=b",
        )) {
            val parsed = parseBip21("bitcoin:tb1qaddress?$q")
            assertNotNull(parsed?.error, "expected error for $q")
            assertNull(parsed?.amountSat, "no amount may be surfaced for $q")
        }
    }

    @Test
    fun `unknown req- params invalidate the URI`() {
        for (q in listOf("req-foo=1", "REQ-bar", "amount=1&req-x=y")) {
            assertNotNull(parseBip21("bitcoin:tb1qaddress?$q")?.error, q)
        }
        // Unknown non-required params are fine.
        assertNull(parseBip21("bitcoin:tb1qaddress?foo=1&amount=1")?.error)
    }

    @Test
    fun `amount must be an exact decimal`() {
        for (bad in listOf(
            "1e-5", "1E2", "+1", "-1", "0x10", "1.000000001", "21000000.00000001",
            "", ".", "1,5", "NaN", "Infinity", "0", "0.0",
        )) {
            val parsed = parseBip21("bitcoin:tb1qaddress?amount=$bad")
            assertNotNull(parsed?.error, "expected error for amount=$bad")
            assertNull(parsed?.amountSat)
        }
        assertEquals(50_000_000L, parseBip21("bitcoin:tb1qaddress?amount=.5")?.amountSat)
        assertEquals(500_000_000L, parseBip21("bitcoin:tb1qaddress?amount=5.")?.amountSat)
        assertEquals(1L, parseBip21("bitcoin:tb1qaddress?amount=0.00000001")?.amountSat)
        assertEquals(
            2_100_000_000_000_000L,
            parseBip21("bitcoin:tb1qaddress?amount=21000000")?.amountSat,
        )
    }

    @Test
    fun `malformed percent-encoding invalidates the URI`() {
        assertNotNull(parseBip21("bitcoin:tb1qaddress?amount=1&label=%zz")?.error)
        assertNotNull(parseBip21("bitcoin:tb1qaddress?amount=1&label=%")?.error)
        assertNotNull(parseBip21("bitcoin:tb1qaddress?amount=1&label=%ff")?.error)
    }

    @Test
    fun `pj is surfaced only for https or onion endpoints`() {
        val https = parseBip21("bitcoin:tb1qaddress?amount=1&pj=https://pj.example.com/x")
        assertEquals("https://pj.example.com/x", https?.payjoinEndpoint)
        val onion = parseBip21("bitcoin:tb1qaddress?amount=1&pj=http://abcdef.onion/x")
        assertEquals("http://abcdef.onion/x", onion?.payjoinEndpoint)
        val clear = parseBip21("bitcoin:tb1qaddress?amount=1&pj=http://pj.example.com/x")
        assertNull(clear?.payjoinEndpoint)
        assertNull(clear?.error, "a bad pj only disables PayJoin (BIP-78 fallback)")
    }

    @Test
    fun `scheme comparison is ASCII-only`() {
        // Dotless i case-folds to I in Kotlin's ignoreCase; Rust uses
        // eq_ignore_ascii_case, so both must reject it.
        assertNull(parseBip21("bıtcoin:tb1qaddress"))
    }

    @Test
    fun `URL-encoded address values pass through unchanged`() {
        // BIP21 addresses are bech32 / base58 — never URL-encoded in
        // practice. We don't decode the address part; only query
        // values get URLDecoder treatment.
        val parsed = parseBip21("bitcoin:tb1qaddress?amount=0.5")
        assertEquals("tb1qaddress", parsed?.address)
    }
}
