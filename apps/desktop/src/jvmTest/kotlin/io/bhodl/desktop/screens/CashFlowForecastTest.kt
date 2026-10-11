package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.RecurringFrequency
import io.bhodl.desktop.domain.RecurringConfidence
import io.bhodl.desktop.domain.forecastCashFlow
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.domain.startOfMonthUnix
import io.bhodl.desktop.domain.shouldShowMonthlyReviewBanner

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.TrackingBillFfi
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7.6 — pure tests for [forecastCashFlow] and the banner
 * visibility helpers.
 */
class CashFlowForecastTest {

    private val day = 86_400L
    private val now = 1_700_000_000L
    private val utc = ZoneId.of("UTC")

    // ─── Horizon shape ──────────────────────────────────────────────

    @Test
    fun `empty inputs return horizon plus one flat points`() {
        val series = forecastCashFlow(
            events = emptyList(),
            bills = emptyList(),
            recurring = emptyList(),
            startBalanceSat = 1_000UL,
            nowUnix = now,
            horizonDays = 90,
        )
        assertEquals(91, series.size)
        // No inflow, no outflow → balance flat at start.
        assertTrue(series.all { it.second == 1_000L })
        // Day 0 timestamp equals now; day 90 = now + 90d.
        assertEquals(now, series.first().first)
        assertEquals(now + 90 * day, series.last().first)
    }

    @Test
    fun `zero horizon returns empty`() {
        val series = forecastCashFlow(
            emptyList(), emptyList(), emptyList(),
            startBalanceSat = 100UL,
            nowUnix = now,
            horizonDays = 0,
        )
        // Invalid horizon → empty. The UI is expected to skip the
        // forecast card entirely when there's nothing to render.
        assertTrue(series.isEmpty())
    }

    @Test
    fun `negative horizon returns empty`() {
        val series = forecastCashFlow(
            emptyList(), emptyList(), emptyList(),
            startBalanceSat = 100UL,
            nowUnix = now,
            horizonDays = -5,
        )
        assertTrue(series.isEmpty())
    }

    // ─── Inflow averaging ──────────────────────────────────────────

    @Test
    fun `daily inflow averages last 90 days of incoming`() {
        // 9_000_000 sat over 90 days = 100k sat/day.
        val events = listOf(
            incoming(amount = 3_000_000UL, ts = now - 10 * day),
            incoming(amount = 3_000_000UL, ts = now - 40 * day),
            incoming(amount = 3_000_000UL, ts = now - 70 * day),
        )
        val series = forecastCashFlow(
            events = events,
            bills = emptyList(),
            recurring = emptyList(),
            startBalanceSat = 0UL,
            nowUnix = now,
            horizonDays = 10,
        )
        // After 10 days at 100k/day, balance = 1_000_000.
        // Day 10 (last point in [0..10]) has accumulated 11 daily
        // inflows (one for each iteration including d=0).
        assertEquals(11L * 100_000L, series.last().second)
    }

    @Test
    fun `inflows outside 90 day window are ignored for the rate`() {
        // 100M sat at day-100 is far past the window. Inside-window
        // total: 0 → daily inflow 0 → balance flat.
        val events = listOf(incoming(amount = 100_000_000UL, ts = now - 100 * day))
        val series = forecastCashFlow(
            events = events,
            bills = emptyList(),
            recurring = emptyList(),
            startBalanceSat = 5UL,
            nowUnix = now,
            horizonDays = 30,
        )
        assertTrue(series.all { it.second == 5L })
    }

    // ─── Bills ──────────────────────────────────────────────────────

    @Test
    fun `bill due on day 15 produces an outflow on day 15 of every future month`() {
        // Use UTC midnight so day-of-month math is deterministic.
        val nowUtc = ZonedDateTime.of(2026, 5, 1, 0, 0, 0, 0, utc)
            .toInstant().epochSecond
        val bill = TrackingBillFfi(
            id = 1,
            name = "Rent",
            amountSat = 1_000_000UL,
            dueDay = 15u,
            lastPaidUnix = null,
            displayLabel = null,
            createdAtUnix = 0,
        )
        val series = forecastCashFlow(
            events = emptyList(),
            bills = listOf(bill),
            recurring = emptyList(),
            startBalanceSat = 5_000_000UL,
            nowUnix = nowUtc,
            horizonDays = 60,
        )
        // Day 14 (May 15) should drop by 1_000_000; same on June 15 (day 45).
        val balanceDay13 = series[13].second
        val balanceDay14 = series[14].second
        val balanceDay44 = series[44].second
        val balanceDay45 = series[45].second
        assertEquals(balanceDay13 - 1_000_000L, balanceDay14)
        assertEquals(balanceDay44 - 1_000_000L, balanceDay45)
    }

    @Test
    fun `bill already paid this month is skipped for the current cycle`() {
        // Bill due day 15, paid on May 10 → no May outflow projected.
        val nowUtc = ZonedDateTime.of(2026, 5, 1, 0, 0, 0, 0, utc)
            .toInstant().epochSecond
        val paidOnMay10 = ZonedDateTime.of(2026, 5, 10, 0, 0, 0, 0, utc)
            .toInstant().epochSecond
        val bill = TrackingBillFfi(
            id = 1,
            name = "Rent",
            amountSat = 1_000_000UL,
            dueDay = 15u,
            lastPaidUnix = paidOnMay10,
            displayLabel = null,
            createdAtUnix = 0,
        )
        val series = forecastCashFlow(
            events = emptyList(),
            bills = listOf(bill),
            recurring = emptyList(),
            startBalanceSat = 5_000_000UL,
            nowUnix = nowUtc,
            horizonDays = 60,
        )
        // May 15 → no drop. June 15 → drop, since last_paid is May.
        val balanceDay13 = series[13].second
        val balanceDay14 = series[14].second
        val balanceDay44 = series[44].second
        val balanceDay45 = series[45].second
        assertEquals(balanceDay13, balanceDay14)  // skipped
        assertEquals(balanceDay44 - 1_000_000L, balanceDay45)  // applied
    }

    // ─── Recurring ──────────────────────────────────────────────────

    @Test
    fun `weekly recurring drops once per week`() {
        val rec = RecurringCandidate(
            payeeKey = "grocery",
            displayName = "grocery",
            sampleEvents = emptyList(),
            frequency = RecurringFrequency.WEEKLY,
            avgAmountSat = 50_000UL,
            nextEstimatedUnix = now + 7 * day,
            confidence = RecurringConfidence.HIGH,
        )
        val series = forecastCashFlow(
            events = emptyList(),
            bills = emptyList(),
            recurring = listOf(rec),
            startBalanceSat = 1_000_000UL,
            nowUnix = now,
            horizonDays = 28,
        )
        // Days 7, 14, 21, 28 each drop 50k. Final balance = 1M - 4×50k.
        assertEquals(1_000_000L - 4L * 50_000L, series.last().second)
    }

    @Test
    fun `LOW confidence recurring is ignored`() {
        val rec = RecurringCandidate(
            payeeKey = "noisy",
            displayName = "noisy",
            sampleEvents = emptyList(),
            frequency = RecurringFrequency.WEEKLY,
            avgAmountSat = 50_000UL,
            nextEstimatedUnix = now + 7 * day,
            confidence = RecurringConfidence.LOW,
        )
        val series = forecastCashFlow(
            events = emptyList(),
            bills = emptyList(),
            recurring = listOf(rec),
            startBalanceSat = 1_000_000UL,
            nowUnix = now,
            horizonDays = 28,
        )
        // LOW → no impact, balance stays at start.
        assertTrue(series.all { it.second == 1_000_000L })
    }

    @Test
    fun `balance can go negative when outflow exceeds inflow`() {
        // No inflow, 4 weekly recurring of 500k from a 1M start.
        val rec = RecurringCandidate(
            payeeKey = "huge",
            displayName = "huge",
            sampleEvents = emptyList(),
            frequency = RecurringFrequency.WEEKLY,
            avgAmountSat = 500_000UL,
            nextEstimatedUnix = now + 7 * day,
            confidence = RecurringConfidence.HIGH,
        )
        val series = forecastCashFlow(
            events = emptyList(),
            bills = emptyList(),
            recurring = listOf(rec),
            startBalanceSat = 1_000_000UL,
            nowUnix = now,
            horizonDays = 28,
        )
        // 1M - 4×500k = -1M. Signal, not error — UI renders red.
        assertEquals(-1_000_000L, series.last().second)
    }

    @Test
    fun `average non-recurring outflow is subtracted daily`() {
        // 900k income and 450k discretionary spending over the last 90d
        // → +10k/day in, −5k/day out → net +5k/day.
        val events = listOf(
            incoming(900_000UL, now - 10 * day),
            outgoingEv("spend", 450_000UL, now - 20 * day),
        )
        val series = forecastCashFlow(
            events = events, bills = emptyList(), recurring = emptyList(),
            startBalanceSat = 0UL, nowUnix = now, horizonDays = 10,
        )
        assertEquals(55_000L, series.last().second) // 11 points × 5k
    }

    @Test
    fun `recurring samples and send_to_self are not double counted`() {
        val sample = outgoingEv("netflix-1", 90_000UL, now - 30 * day)
        val rec = RecurringCandidate(
            payeeKey = "subs~1", displayName = "subs", sampleEvents = listOf(sample),
            frequency = RecurringFrequency.MONTHLY, avgAmountSat = 90_000UL,
            nextEstimatedUnix = now + 100 * day, confidence = RecurringConfidence.HIGH,
        )
        val transfer = outgoingEv("self", 9_000_000UL, now - 5 * day).also {
            it.cryptoEventKind = SEND_TO_SELF
            it.feeSat = 9_000UL
        }
        val series = forecastCashFlow(
            events = listOf(sample, transfer), bills = emptyList(), recurring = listOf(rec),
            startBalanceSat = 1_000_000UL, nowUnix = now, horizonDays = 1,
        )
        // Only the transfer fee (9_000 / 90 = 100/day) is discretionary;
        // two points (day 0 and day 1).
        assertEquals(999_800L, series.last().second)
    }

    private fun outgoingEv(txid: String, amount: ULong, ts: Long): PaymentEvent = PaymentEvent(
        walletId = "w1",
        txidOrPaymentId = txid,
        kind = PaymentKind.ON_CHAIN,
        direction = PaymentDirection.OUTGOING,
        amountSat = amount,
        feeSat = null,
        timestampUnix = ts.toULong(),
        blockHeight = null,
        category = null,
    )

    // ─── Banner visibility helpers ──────────────────────────────────

    @Test
    fun `banner shows on day 1 when never dismissed`() {
        // 2026-05-01 in UTC → first of month.
        val may1 = ZonedDateTime.of(2026, 5, 1, 12, 0, 0, 0, utc)
            .toInstant().epochSecond
        assertTrue(shouldShowMonthlyReviewBanner(may1, lastDismissedUnix = 0L, zone = utc))
    }

    @Test
    fun `banner hidden after day 3`() {
        val may5 = ZonedDateTime.of(2026, 5, 5, 12, 0, 0, 0, utc)
            .toInstant().epochSecond
        assertFalse(shouldShowMonthlyReviewBanner(may5, lastDismissedUnix = 0L, zone = utc))
    }

    @Test
    fun `banner hidden if dismissed this month`() {
        val may1 = ZonedDateTime.of(2026, 5, 1, 12, 0, 0, 0, utc).toInstant().epochSecond
        val may2 = ZonedDateTime.of(2026, 5, 2, 10, 0, 0, 0, utc).toInstant().epochSecond
        assertFalse(shouldShowMonthlyReviewBanner(may2, lastDismissedUnix = may1, zone = utc))
    }

    @Test
    fun `banner shows again next month after dismiss`() {
        // Dismissed April 1, today is May 1 → new month, banner is back.
        val apr1 = ZonedDateTime.of(2026, 4, 1, 12, 0, 0, 0, utc).toInstant().epochSecond
        val may1 = ZonedDateTime.of(2026, 5, 1, 12, 0, 0, 0, utc).toInstant().epochSecond
        assertTrue(shouldShowMonthlyReviewBanner(may1, lastDismissedUnix = apr1, zone = utc))
    }

    @Test
    fun `startOfMonthUnix returns midnight UTC of day 1`() {
        val may15 = ZonedDateTime.of(2026, 5, 15, 8, 30, 0, 0, utc).toInstant().epochSecond
        val firstOfMonth = startOfMonthUnix(may15, zone = utc)
        val expected = ZonedDateTime.of(2026, 5, 1, 0, 0, 0, 0, utc).toInstant().epochSecond
        assertEquals(expected, firstOfMonth)
    }

    // ─── Test fixtures ──────────────────────────────────────────────

    private fun incoming(amount: ULong, ts: Long): PaymentEvent = PaymentEvent(
        walletId = "w1",
        txidOrPaymentId = "in-$ts-$amount",
        kind = PaymentKind.ON_CHAIN,
        direction = PaymentDirection.INCOMING,
        amountSat = amount,
        feeSat = null,
        timestampUnix = ts.toULong(),
        blockHeight = null,
        category = null,
    )
}
