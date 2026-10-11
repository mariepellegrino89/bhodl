package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.detectRecurring
import io.bhodl.desktop.domain.RecurringFrequency
import io.bhodl.desktop.domain.RecurringConfidence
import io.bhodl.desktop.domain.normalizePayeeKey
import io.bhodl.desktop.domain.MIN_OCCURRENCES
import io.bhodl.desktop.domain.recurringGroupKey
import io.bhodl.desktop.domain.SEND_TO_SELF

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 7.5 — Pure tests for [detectRecurring].
 *
 * The detector lives between the watch-event stream and the UI's
 * "Accept as Bill" flow. Pinning its classification cliff here makes
 * future tuning (e.g. relaxing the amount-variance threshold) a
 * deliberate, reviewed change.
 */
class RecurringDetectorTest {

    private val day = 86_400L
    private val now = 1_700_000_000L
    private var txSeq = 0

    // ─── Filtering ─────────────────────────────────────────────────

    @Test
    fun `empty events return empty`() {
        assertEquals(emptyList(), detectRecurring(emptyList(), now))
    }

    @Test
    fun `incoming events are ignored`() {
        // 3 incoming events with monthly cadence shouldn't trigger
        // recurring (we want OUTGOING subscriptions/bills only).
        val events = monthlyEvents("netflix", PaymentDirection.INCOMING, 3)
        assertTrue(detectRecurring(events, now).isEmpty())
    }

    @Test
    fun `events without timestamp are ignored`() {
        // A mempool tx with timestampUnix=null can't anchor an
        // interval. The detector must skip without crashing.
        val events = listOf(
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = null),
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = now - 30 * day),
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = now - 60 * day),
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = now - 90 * day),
        )
        // Only 3 timestamped (one filtered out) → still meets MIN
        val out = detectRecurring(events, now)
        assertEquals(1, out.size)
    }

    @Test
    fun `groups under min occurrences are dropped`() {
        // 2 occurrences, monthly cadence — under MIN_OCCURRENCES (3).
        val events = monthlyEvents("netflix", PaymentDirection.OUTGOING, 2)
        assertTrue(detectRecurring(events, now).isEmpty())
    }

    @Test
    fun `dismissed keys are filtered out`() {
        val events = monthlyEvents("netflix", PaymentDirection.OUTGOING, 4)
        val key = recurringGroupKey(events.first())
        val out = detectRecurring(events, now, dismissedKeys = setOf(key))
        assertTrue(out.isEmpty())
    }

    // ─── Classification ─────────────────────────────────────────────

    @Test
    fun `monthly netflix happy path is detected`() {
        val events = monthlyEvents("netflix", PaymentDirection.OUTGOING, 4)
        val out = detectRecurring(events, now)
        assertEquals(1, out.size)
        val c = out[0]
        assertEquals(RecurringFrequency.MONTHLY, c.frequency)
        assertTrue(c.payeeKey.startsWith("netflix~"), c.payeeKey)
        assertEquals("netflix", c.displayName)
        assertEquals(11_990_000UL, c.avgAmountSat)
    }

    @Test
    fun `weekly grocery happy path is detected`() {
        val events = weeklyEvents("grocery", 4)
        val out = detectRecurring(events, now)
        assertEquals(1, out.size)
        assertEquals(RecurringFrequency.WEEKLY, out[0].frequency)
    }

    @Test
    fun `irregular intervals reject the group`() {
        // Mix of monthly + 10-day intervals → neither fully monthly
        // nor weekly. Group is dropped.
        val events = listOf(
            outgoing("misc", amountSat = 1_000UL, timestamp = now - 60 * day),
            outgoing("misc", amountSat = 1_000UL, timestamp = now - 50 * day),
            outgoing("misc", amountSat = 1_000UL, timestamp = now - 20 * day),
        )
        assertTrue(detectRecurring(events, now).isEmpty())
    }

    @Test
    fun `monthly tolerance accepts plus minus 3 days drift`() {
        // 27d and 34d are within MONTHLY ± 3d tolerance (28-3..32+3).
        val events = listOf(
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = now - 90 * day),
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = now - 56 * day),  // 34d gap
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = now - 29 * day),  // 27d gap
            outgoing("netflix", amountSat = 11_990_000UL, timestamp = now - 0 * day),   // 29d gap
        )
        val out = detectRecurring(events, now)
        assertEquals(1, out.size)
        assertEquals(RecurringFrequency.MONTHLY, out[0].frequency)
    }

    // ─── Amount variance ───────────────────────────────────────────

    @Test
    fun `amount variance over 20 percent rejects the group`() {
        // 10k / 11k / 15k → 50% variance, way over hard cap.
        val events = listOf(
            outgoing("foo", amountSat = 10_000UL, timestamp = now - 90 * day),
            outgoing("foo", amountSat = 11_000UL, timestamp = now - 60 * day),
            outgoing("foo", amountSat = 15_000UL, timestamp = now - 30 * day),
        )
        assertTrue(detectRecurring(events, now).isEmpty())
    }

    @Test
    fun `amount variance under 5 percent yields HIGH confidence`() {
        val events = monthlyEvents("netflix", PaymentDirection.OUTGOING, 4)  // identical amounts
        val out = detectRecurring(events, now)
        assertEquals(RecurringConfidence.HIGH, out[0].confidence)
    }

    @Test
    fun `confidence drops to MEDIUM at 3 occurrences`() {
        val events = monthlyEvents("netflix", PaymentDirection.OUTGOING, 3)
        val out = detectRecurring(events, now)
        assertEquals(RecurringConfidence.MEDIUM, out[0].confidence)
    }

    // ─── Output shape ──────────────────────────────────────────────

    @Test
    fun `next estimated is last timestamp plus period`() {
        // The detector sorts the group by timestamp ASC, so the
        // "last" event is the most recent — which the factory plants
        // at exactly `now` (i = 0 in the loop). Adding the monthly
        // period (30 days) yields `now + 30d`.
        val events = monthlyEvents("netflix", PaymentDirection.OUTGOING, 4)
        val out = detectRecurring(events, now)
        val expected = now + 30 * day
        assertEquals(expected, out[0].nextEstimatedUnix)
    }

    @Test
    fun `multiple groups appear separately`() {
        val events = monthlyEvents("netflix", PaymentDirection.OUTGOING, 4) +
            weeklyEvents("grocery", 4)
        val out = detectRecurring(events, now)
        assertEquals(2, out.size)
        assertTrue(out.any { it.payeeKey.startsWith("netflix~") })
        assertTrue(out.any { it.payeeKey.startsWith("grocery~") })
    }

    @Test
    fun `normalizePayeeKey lowercases trims and truncates`() {
        assertEquals("netflix", normalizePayeeKey("  Netflix  "))
        assertEquals("a".repeat(30), normalizePayeeKey("a".repeat(60)))
    }

    @Test
    fun `different amounts in one category form separate groups`() {
        // Same category, a ~12k subscription and a ~400k rent: they must
        // not be merged into one (noisy, rejected) group.
        val subs = monthlyEvents("subscriptions", PaymentDirection.OUTGOING, 3)
        val rent = (0 until 3).map { i ->
            outgoing("subscriptions", amountSat = 400_000UL, timestamp = now - i * 30L * day)
        }
        val out = detectRecurring(subs + rent, now)
        assertEquals(2, out.size)
    }

    @Test
    fun `send_to_self transfers are never recurring spending`() {
        val events = monthlyEvents("savings", PaymentDirection.OUTGOING, 4)
            .onEach { it.cryptoEventKind = SEND_TO_SELF }
        assertTrue(detectRecurring(events, now).isEmpty())
    }

    @Test
    fun `uncategorized events still group by amount`() {
        val events = (0 until 3).map { i ->
            outgoing("x", amountSat = 21_000UL, timestamp = now - i * 30L * day)
                .also { it.category = null }
        }
        val out = detectRecurring(events, now)
        assertEquals(1, out.size)
        assertTrue(out[0].payeeKey.startsWith("uncategorized~"))
    }

    // ─── Test fixtures ──────────────────────────────────────────────

    private fun outgoing(
        payee: String,
        amountSat: ULong,
        timestamp: Long?,
    ): PaymentEvent = PaymentEvent(
        walletId = "w1",
        // Every on-chain tx has a unique txid: grouping must come from
        // the category (+ amount bucket), never from the txid.
        txidOrPaymentId = "tx-${txSeq++}",
        kind = PaymentKind.ON_CHAIN,
        direction = PaymentDirection.OUTGOING,
        amountSat = amountSat,
        feeSat = null,
        timestampUnix = timestamp?.toULong(),
        blockHeight = null,
        category = payee,
    )

    /** Build N monthly events ending at `now`, identical 11_990_000 sat. */
    private fun monthlyEvents(
        payee: String,
        direction: PaymentDirection,
        count: Int,
    ): List<PaymentEvent> = (0 until count).map { i ->
        PaymentEvent(
            walletId = "w1",
            txidOrPaymentId = "tx-${txSeq++}",
            kind = PaymentKind.ON_CHAIN,
            direction = direction,
            amountSat = 11_990_000UL,
            feeSat = null,
            timestampUnix = (now - i * 30L * day).toULong(),
            blockHeight = null,
            category = payee,
        )
    }

    /** Build N weekly events ending at `now`, identical 50_000 sat. */
    private fun weeklyEvents(payee: String, count: Int): List<PaymentEvent> =
        (0 until count).map { i ->
            PaymentEvent(
                walletId = "w1",
                txidOrPaymentId = "tx-${txSeq++}",
                kind = PaymentKind.ON_CHAIN,
                direction = PaymentDirection.OUTGOING,
                amountSat = 50_000UL,
                feeSat = null,
                timestampUnix = (now - i * 7L * day).toULong(),
                blockHeight = null,
                category = payee,
            )
        }
}
