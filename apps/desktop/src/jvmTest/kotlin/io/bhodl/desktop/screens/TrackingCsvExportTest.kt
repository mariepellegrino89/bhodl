package io.bhodl.desktop.screens

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Audit M1 — CSV formula injection, quoting and the crypto-kind column. */
class TrackingCsvExportTest {

    @Test
    fun `formula triggers are neutralised and quoted when needed`() {
        assertEquals("'=HYPERLINK(1)", csvTextCell("=HYPERLINK(1)"))
        assertEquals("'+1", csvTextCell("+1"))
        assertEquals("'-2", csvTextCell("-2"))
        assertEquals("'@SUM(A1)", csvTextCell("@SUM(A1)"))
        assertEquals("\"'=1,2\"", csvTextCell("=1,2"))
        assertEquals("\"a \"\"b\"\"\"", csvTextCell("a \"b\""))
        assertEquals("\"x\ny\"", csvTextCell("x\ny"))
        assertEquals("plain", csvTextCell("plain"))
    }

    @Test
    fun `csv has crypto kind column and escaped text cells`() {
        val ev = PaymentEvent(
            walletId = "w", txidOrPaymentId = "tx", kind = PaymentKind.ON_CHAIN,
            direction = PaymentDirection.OUTGOING, amountSat = 5UL, feeSat = 1UL,
            timestampUnix = 0UL, blockHeight = 1u, category = "=cmd",
        ).also { it.cryptoEventKind = "send_to_self" }
        val lines = buildEventsCsv(listOf(ev), mapOf("w" to "@evil")).lines()
        assertTrue(lines[0].endsWith(",category,crypto_event_kind"))
        assertEquals("1970-01-01T00:00:00Z,1,'@evil,outgoing,5,1,tx,on_chain,'=cmd,send_to_self", lines[1])
    }

    @Test
    fun `csv extension is forced`() {
        assertEquals("a.csv", withCsvExtension(File("a")).name)
        assertEquals("a.CSV", withCsvExtension(File("a.CSV")).name)
        assertEquals("a.txt.csv", withCsvExtension(File("a.txt")).name)
    }
}
