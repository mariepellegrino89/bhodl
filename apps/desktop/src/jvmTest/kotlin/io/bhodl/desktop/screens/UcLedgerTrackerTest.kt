package io.bhodl.desktop.screens

import io.bhodl.desktop.data.mergeEventLegs
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.desktop.domain.externalSignedNetSat
import io.bhodl.desktop.domain.isInternalTransfer
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentDirection.INCOMING
import io.bhodl.ffi.PaymentDirection.OUTGOING
import io.bhodl.ffi.PaymentDirection.SELF_TRANSFER
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.SavedViewFfi
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Area C — pure ledger helpers of the tracker's Transactions section:
 * leg dedupe, wallet exclusion, ledger filter, category breakdown,
 * running balance, saved views, external flows and monthly flows.
 */
class UcLedgerTrackerTest {

    private fun ev(
        wallet: String,
        txid: String,
        direction: PaymentDirection,
        amount: ULong,
        ts: Long? = 1_700_000_000L,
        fee: ULong? = null,
        category: String? = null,
        kind: String? = null,
    ) = PaymentEvent(
        walletId = wallet,
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = direction,
        amountSat = amount,
        feeSat = fee,
        timestampUnix = ts?.toULong(),
        blockHeight = null,
        category = category,
        cryptoEventKind = kind,
    )

    private fun utc(y: Int, m: Int, d: Int, h: Int = 12) =
        LocalDate.of(y, m, d).atTime(h, 0).toEpochSecond(ZoneOffset.UTC)

    // ─── TRK-022 — dedupe by txid ───────────────────────────────────

    @Test
    fun `TRK-022 the categorised leg wins over the fee leg, which wins over the first`() {
        val plain = ev("w1", "a", INCOMING, 10UL)
        val withFee = ev("w2", "a", OUTGOING, 11UL, fee = 1UL)
        val categorised = ev("w3", "a", INCOMING, 10UL, category = "Food")
        assertSame(categorised, mergeEventLegs(listOf(plain, withFee, categorised)).single())
        assertSame(withFee, mergeEventLegs(listOf(plain, withFee)).single())
        val other = ev("w2", "a", INCOMING, 10UL)
        assertSame(plain, mergeEventLegs(listOf(plain, other)).single())
    }

    @Test
    fun `TRK-022 merged list is newest first with mempool events on top`() {
        val old = ev("w1", "old", INCOMING, 1UL, ts = 100)
        val new = ev("w1", "new", INCOMING, 1UL, ts = 200)
        val mempool = ev("w1", "mem", INCOMING, 1UL, ts = null)
        assertEquals(listOf("mem", "new", "old"), mergeEventLegs(listOf(old, mempool, new)).map { it.txidOrPaymentId })
        assertTrue(mergeEventLegs(emptyList()).isEmpty())
    }

    // ─── TRK-026 — wallet exclusion (problem 4) ─────────────────────

    @Test
    fun `TRK-026 events of excluded wallets drop out of the aggregates`() {
        val a = ev("w1", "a", INCOMING, 10UL)
        val b = ev("w2", "b", INCOMING, 20UL)
        assertEquals(listOf(a), includedEvents(listOf(a, b), listOf(a, b), setOf("w2")))
        assertEquals(listOf(a, b), includedEvents(listOf(a, b), listOf(a, b), emptySet()))
    }

    @Test
    fun `TRK-026 a tx whose representative leg is on an excluded wallet stays for the included one`() {
        // w1 (excluded) pays, w2 (included) receives; the debit leg was picked as representative.
        val debit = ev("w1", "t", OUTGOING, 10_500UL, fee = 500UL, category = "Rent")
        val credit = ev("w2", "t", INCOMING, 10_000UL)
        val merged = mergeEventLegs(listOf(debit, credit))
        assertSame(debit, merged.single())

        val included = includedEvents(merged, listOf(debit, credit), setOf("w1")).single()
        assertEquals("w2", included.walletId)
        assertEquals(INCOMING, included.direction)
        assertEquals(10_000UL, included.amountSat)
        // The category the user set on the transaction is kept.
        assertEquals("Rent", included.category)
    }

    @Test
    fun `TRK-026 a tx seen only by excluded wallets disappears`() {
        val l1 = ev("w1", "t", OUTGOING, 10UL, fee = 1UL)
        val l2 = ev("w3", "t", INCOMING, 9UL)
        assertTrue(includedEvents(listOf(l1), listOf(l1, l2), setOf("w1", "w3")).isEmpty())
    }

    @Test
    fun `TRK-026 the send_to_self tag follows the replacement leg`() {
        val debit = ev("w1", "t", OUTGOING, 10_500UL, fee = 500UL, kind = SEND_TO_SELF)
        val credit = ev("w2", "t", INCOMING, 10_000UL)
        val out = includedEvents(listOf(debit), listOf(debit, credit), setOf("w1")).single()
        assertEquals(SEND_TO_SELF, out.cryptoEventKind)
        assertEquals(0UL, out.externalInflowSat())
    }

    @Test
    fun `TRK-026 without legs the old per-event filter still applies`() {
        val a = ev("w1", "a", INCOMING, 10UL)
        assertTrue(includedEvents(listOf(a), emptyList(), setOf("w1")).isEmpty())
    }

    // ─── TRK-140 — ledger filter ────────────────────────────────────

    private val food = ev("w", "f", OUTGOING, 1UL, ts = 1_000, category = "Food")
    private val rent = ev("w", "r", OUTGOING, 1UL, ts = 2_000, category = "Rent")
    private val none = ev("w", "n", INCOMING, 1UL, ts = 3_000)
    private val blank = ev("w", "b", INCOMING, 1UL, ts = 4_000, category = " ")
    private val pending = ev("w", "p", INCOMING, 1UL, ts = null, category = "Food")
    private val ledger = listOf(food, rent, none, blank, pending)

    @Test
    fun `TRK-140 category filter is tri-state`() {
        assertEquals(ledger, filterLedger(ledger, null, null))
        assertEquals(listOf(none, blank), filterLedger(ledger, "", null))
        assertEquals(listOf(food, pending), filterLedger(ledger, "Food", null))
        assertTrue(filterLedger(ledger, "food", null).isEmpty())
    }

    @Test
    fun `TRK-140 time range is inclusive, drops mempool events and ANDs with the category`() {
        assertEquals(listOf(food, rent), filterLedger(ledger, null, 1_000L to 2_000L))
        assertEquals(listOf(food), filterLedger(ledger, "Food", 0L to 10_000L))
        assertEquals(listOf(blank), filterLedger(ledger, "", 3_500L to 4_000L))
        assertTrue(filterLedger(ledger, "Rent", 3_000L to 4_000L).isEmpty())
    }

    // ─── TRK-144 — category breakdown (problem 3) ───────────────────

    @Test
    fun `TRK-144 breakdown sums external flows per category ranked by volume`() {
        val rows = categoryBreakdown(
            listOf(
                ev("w", "1", INCOMING, 100UL, category = "Salary"),
                ev("w", "2", OUTGOING, 30UL, category = "Food"),
                ev("w", "3", OUTGOING, 20UL, category = "Food"),
                ev("w", "4", INCOMING, 5UL),
                ev("w", "5", OUTGOING, 7UL, category = ""),
            ),
        )
        assertEquals(listOf("Salary", "Food", null), rows.map { it.first })
        assertEquals(CategoryTotals(0UL, 50UL, 2), rows[1].second)
        assertEquals(CategoryTotals(5UL, 7UL, 2), rows[2].second)
    }

    @Test
    fun `TRK-144 a send_to_self OUTGOING counts only its fee, a self transfer nothing`() {
        val rows = categoryBreakdown(
            listOf(
                ev("w", "s", OUTGOING, 100_500UL, fee = 500UL, category = "Move", kind = SEND_TO_SELF),
                ev("w", "x", SELF_TRANSFER, 300UL, category = "Move"),
            ),
        )
        assertEquals(CategoryTotals(0UL, 500UL, 2), rows.single().second)
        assertTrue(categoryBreakdown(emptyList()).isEmpty())
    }

    // ─── TRK-143 — running balance ──────────────────────────────────

    @Test
    fun `TRK-143 running balance walks confirmed events oldest first and mempool takes the tail`() {
        val events = listOf(
            ev("w", "c", OUTGOING, 30UL, ts = 300),
            ev("w", "a", INCOMING, 100UL, ts = 100),
            ev("w", "m", INCOMING, 7UL, ts = null),
            ev("w", "s", OUTGOING, 50_200UL, ts = 200, fee = 200UL, kind = SEND_TO_SELF),
        )
        val map = runningBalances(events)
        assertEquals(100L, map["a"])
        assertEquals(-100L, map["s"])
        assertEquals(-130L, map["c"])
        assertEquals(-130L, map["m"])
        assertTrue(runningBalances(emptyList()).isEmpty())
    }

    // ─── TRK-146 — apply a saved view ───────────────────────────────

    private fun view(period: String, cat: String?, start: Long?, end: Long?) =
        SavedViewFfi(1, "v", period, cat, start, end, 0)

    @Test
    fun `TRK-146 applying a view restores period, category and range`() {
        assertEquals(
            SavedViewSelection(TrackingPeriod.D90, "Food", 10L to 20L),
            applySavedView(view("D90", "Food", 10, 20)),
        )
        assertEquals(SavedViewSelection(TrackingPeriod.ALL, "", null), applySavedView(view("ALL", "", null, null)))
    }

    @Test
    fun `TRK-146 unknown period falls back to D30 and a half range is dropped`() {
        val sel = applySavedView(view("QUARTER", null, 10, null))
        assertEquals(TrackingPeriod.D30, sel.period)
        assertNull(sel.timeRange)
        assertNull(sel.categoryFilter)
    }

    // ─── TRK-050 — external flows ───────────────────────────────────

    @Test
    fun `TRK-050 regular events flow in full`() {
        val inc = ev("w", "i", INCOMING, 100UL)
        val out = ev("w", "o", OUTGOING, 40UL, fee = 2UL)
        assertFalse(inc.isInternalTransfer())
        assertEquals(100UL, inc.externalInflowSat())
        assertEquals(0UL, inc.externalOutflowSat())
        assertEquals(40UL, out.externalOutflowSat())
        assertEquals(0UL, out.externalInflowSat())
        assertEquals(-40L, out.externalSignedNetSat())
        assertEquals(100L, inc.externalSignedNetSat())
    }

    @Test
    fun `TRK-050 send_to_self counts only its fee, zero when the fee is unknown`() {
        val known = ev("w", "s", OUTGOING, 100_500UL, fee = 500UL, kind = SEND_TO_SELF)
        val unknown = ev("w", "s", OUTGOING, 100_500UL, kind = SEND_TO_SELF)
        val credit = ev("w", "s", INCOMING, 100_000UL, kind = SEND_TO_SELF)
        assertTrue(known.isInternalTransfer())
        assertEquals(500UL, known.externalOutflowSat())
        assertEquals(-500L, known.externalSignedNetSat())
        assertEquals(0UL, unknown.externalOutflowSat())
        assertEquals(0UL, credit.externalInflowSat())
        assertEquals(0L, credit.externalSignedNetSat())
    }

    @Test
    fun `TRK-050 SELF_TRANSFER is internal in both directions and has no flow`() {
        val self = ev("w", "x", SELF_TRANSFER, 300UL, fee = 300UL)
        assertTrue(self.isInternalTransfer())
        assertEquals(0UL, self.externalInflowSat())
        assertEquals(0UL, self.externalOutflowSat())
        assertEquals(0L, self.externalSignedNetSat())
    }

    // ─── GAP-010 — monthly inflow / outflow chart ───────────────────

    @Test
    fun `GAP-010 monthly flows split events at calendar month boundaries and drop mempool`() {
        val flows = computeMonthlyFlows(
            listOf(
                ev("w", "1", INCOMING, 100UL, ts = utc(2026, 1, 31, 23)),
                ev("w", "2", OUTGOING, 40UL, ts = utc(2026, 2, 1, 0)),
                ev("w", "3", OUTGOING, 10UL, ts = utc(2026, 2, 15)),
                ev("w", "4", INCOMING, 999UL, ts = null),
            ),
        )
        assertEquals(2, flows.size)
        assertEquals(100UL to 0UL, flows[0].incomingSat to flows[0].outgoingSat)
        assertEquals(0UL to 50UL, flows[1].incomingSat to flows[1].outgoingSat)
        assertTrue(computeMonthlyFlows(listOf(ev("w", "m", INCOMING, 1UL, ts = null))).isEmpty())
        assertTrue(computeMonthlyFlows(emptyList()).isEmpty())
    }

    @Test
    fun `GAP-010 send_to_self is not spending, only its fee is`() {
        val flows = computeMonthlyFlows(
            listOf(
                ev("w", "s", OUTGOING, 100_500UL, ts = utc(2026, 3, 3), fee = 500UL, kind = SEND_TO_SELF),
                ev("w", "x", SELF_TRANSFER, 300UL, ts = utc(2026, 3, 4)),
            ),
        )
        assertEquals(0UL to 500UL, flows.single().incomingSat to flows.single().outgoingSat)
    }

    @Test
    fun `GAP-010 months are UTC months whatever the system time zone`() {
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Los_Angeles"))
            // 2026-03-01 02:00 UTC is still February in Los Angeles.
            val flows = computeMonthlyFlows(listOf(ev("w", "1", INCOMING, 5UL, ts = utc(2026, 3, 1, 2))))
            val expected = YearMonth.of(2026, 3).month
                .getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
            assertEquals("$expected 26", flows.single().label)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `GAP-010 an explicit zone still groups in that zone`() {
        val flows = computeMonthlyFlows(
            listOf(ev("w", "1", INCOMING, 5UL, ts = utc(2026, 3, 1, 2))),
            zone = ZoneId.of("America/Los_Angeles"),
        )
        assertTrue(flows.single().label.endsWith("26"))
        assertEquals(1, flows.size)
    }
}
