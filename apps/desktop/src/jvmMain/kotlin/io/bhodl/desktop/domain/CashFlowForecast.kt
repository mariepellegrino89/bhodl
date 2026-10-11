package io.bhodl.desktop.domain

import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingBillFfi
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Phase 7.6 — 90-day cash flow projection.
 *
 * Models a forward-looking balance trajectory by integrating:
 *  - daily inflow averaged from the last 90 days (proxy for salary
 *    + ad-hoc incoming until proper "scheduled income" lands in 7.7);
 *  - daily NON-recurring outflow averaged from the last 90 days (the
 *    discretionary spending that isn't a bill or a detected recurring
 *    payment — without it the projection added average income but
 *    subtracted only scheduled costs, so it always drifted upward).
 *    Outgoing events that belong to a recurring candidate are left out
 *    of this average (they are projected individually below). Bills
 *    paid on-chain can't be matched to events, so they may be partially
 *    double-counted — the projection errs on the conservative side;
 *  - active bills (one outflow at `due_day` each future month);
 *  - HIGH or MEDIUM confidence recurring candidates (one outflow at
 *    `nextEstimatedUnix + k × period` for each upcoming period).
 *
 * Output: `[(timestamp_unix, projected_balance_sat), ...]` with one
 * point per day from `nowUnix` (day 0 = current balance) through day
 * `horizonDays` inclusive. The balance is allowed to go below zero —
 * that's a signal the user needs (e.g. "you'll run out around
 * June 12"), not an error to clamp away.
 */
internal fun forecastCashFlow(
    events: List<PaymentEvent>,
    bills: List<TrackingBillFfi>,
    recurring: List<RecurringCandidate>,
    startBalanceSat: ULong,
    nowUnix: Long,
    horizonDays: Int = 90,
): List<Pair<Long, Long>> {
    if (horizonDays <= 0) return emptyList()

    // Daily inflow rate from the last 90d of confirmed INCOMING events.
    val cutoff90 = nowUnix - 90L * DAY_SEC
    // Internal transfers (send_to_self) are excluded via externalInflowSat.
    val inWindow = events.filter {
        val ts = it.timestampUnix?.toLong() ?: return@filter false
        ts in cutoff90..nowUnix
    }
    val income90: Long = inWindow.sumOf { it.externalInflowSat().toLong() }
    val dailyInflow: Long = income90 / 90L

    // Daily non-recurring outflow (see the KDoc). Recurring samples are
    // matched by txid; send_to_self contributes only its fee.
    val recurringTxids: Set<String> = recurring
        .filter { it.confidence != RecurringConfidence.LOW }
        .flatMap { c -> c.sampleEvents.map { it.txidOrPaymentId } }
        .toSet()
    val nonRecurringOut90: Long = inWindow
        .filter { it.txidOrPaymentId !in recurringTxids }
        .sumOf { it.externalOutflowSat().toLong() }
    val dailyDiscretionaryOutflow: Long = nonRecurringOut90 / 90L

    // Pre-compute scheduled outflows by day-offset for the horizon.
    val outflowByDay = LongArray(horizonDays + 1)

    // 1) Bills: each active bill contributes once per month at its
    // `due_day`. We scan forward over the horizon and project a hit
    // for every month boundary the day-of-month falls into. Bills
    // with `last_paid_unix` already in the current period are skipped
    // for *this* month so we don't double-count what was already paid.
    val nowZdt = Instant.ofEpochSecond(nowUnix).atZone(ZoneId.systemDefault())
    for (bill in bills) {
        val dueDay = bill.dueDay.toInt().coerceIn(1, 31)
        var probe = nowZdt
        while (probe.toInstant().epochSecond <= nowUnix + horizonDays * DAY_SEC) {
            val lastDayOfMonth = probe.toLocalDate().lengthOfMonth()
            val clamped = dueDay.coerceAtMost(lastDayOfMonth)
            val dueZdt = probe.withDayOfMonth(clamped)
            val dueUnix = dueZdt.toInstant().epochSecond
            if (dueUnix in nowUnix..(nowUnix + horizonDays * DAY_SEC)) {
                // Skip if already paid this exact month (rough proxy:
                // last_paid_unix falls in [first_of_month, last_of_month]).
                val paidThisMonth = bill.lastPaidUnix?.let { lp ->
                    val firstOfMonth = dueZdt.withDayOfMonth(1)
                        .toInstant().epochSecond
                    val firstOfNext = dueZdt.plusMonths(1).withDayOfMonth(1)
                        .toInstant().epochSecond
                    lp in firstOfMonth until firstOfNext
                } ?: false
                if (!paidThisMonth) {
                    val offset = ((dueUnix - nowUnix) / DAY_SEC).toInt()
                        .coerceIn(0, horizonDays)
                    outflowByDay[offset] += bill.amountSat.toLong()
                }
            }
            probe = probe.plusMonths(1).withDayOfMonth(1)
        }
    }

    // 2) Recurring HIGH/MEDIUM: walk forward from nextEstimatedUnix,
    // one period at a time, dropping hits onto the day grid.
    for (c in recurring) {
        if (c.confidence == RecurringConfidence.LOW) continue
        var ts = c.nextEstimatedUnix
        val period = c.frequency.periodSec
        while (ts <= nowUnix + horizonDays * DAY_SEC) {
            if (ts >= nowUnix) {
                val offset = ((ts - nowUnix) / DAY_SEC).toInt()
                    .coerceIn(0, horizonDays)
                outflowByDay[offset] += c.avgAmountSat.toLong()
            }
            ts += period
        }
    }

    // 3) Walk forward, accumulate.
    val out = ArrayList<Pair<Long, Long>>(horizonDays + 1)
    var balance: Long = startBalanceSat.toLong()
    for (d in 0..horizonDays) {
        balance += dailyInflow
        balance -= dailyDiscretionaryOutflow
        balance -= outflowByDay[d]
        out += (nowUnix + d * DAY_SEC) to balance
    }
    return out
}

private const val DAY_SEC: Long = 86_400L

/**
 * Phase 7.6 — convenience accessor: the predicted balance at the end
 * of the forecast horizon. Returns null when the series is empty.
 * Used by the UI footer "In 90 days: ~ X sat (Δ Y%)".
 */
internal fun CashFlowForecastEnd(series: List<Pair<Long, Long>>): Long? =
    series.lastOrNull()?.second

/**
 * Phase 7.6 — start-of-month timestamp at midnight local time, used
 * by the monthly review banner visibility check. Pure so tests can
 * pin a deterministic clock.
 */
internal fun startOfMonthUnix(nowUnix: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochSecond(nowUnix)
        .atZone(zone)
        .truncatedTo(ChronoUnit.DAYS)
        .withDayOfMonth(1)
        .toInstant()
        .epochSecond

/**
 * Phase 7.6 — day-of-month in 1..31 at `nowUnix` in the given zone.
 * Pure helper for the banner visibility predicate.
 */
internal fun dayOfMonth(nowUnix: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
    Instant.ofEpochSecond(nowUnix).atZone(zone).dayOfMonth

/**
 * Phase 7.6 — decide whether the monthly-review banner should appear.
 * The rule is: visible in the first 3 days of the month AND the user
 * hasn't dismissed it since the start of the current month. After
 * day 3, the banner stays hidden until next month.
 *
 * Defaults to the system timezone — tests inject a fixed zone.
 */
internal fun shouldShowMonthlyReviewBanner(
    nowUnix: Long,
    lastDismissedUnix: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val dom = dayOfMonth(nowUnix, zone)
    if (dom > 3) return false
    val firstOfMonth = startOfMonthUnix(nowUnix, zone)
    return lastDismissedUnix < firstOfMonth
}

/** Used by the local ZonedDateTime helper for clarity. */
@Suppress("unused")
private fun ZonedDateTime.toEpoch(): Long = this.toInstant().epochSecond
