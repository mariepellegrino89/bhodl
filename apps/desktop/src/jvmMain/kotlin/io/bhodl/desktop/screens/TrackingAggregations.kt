package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.dayOfMonth
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.desktop.domain.externalSignedNetSat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.displaySignedAmount
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ui.theme.BhodlTheme
import io.github.koalaplot.core.ChartLayout
import io.github.koalaplot.core.bar.DefaultVerticalBar
import io.github.koalaplot.core.bar.GroupedVerticalBarPlot
import io.github.koalaplot.core.legend.FlowLegend
import io.github.koalaplot.core.legend.LegendLocation
import io.github.koalaplot.core.line.LinePlot
import io.github.koalaplot.core.pie.DefaultSlice
import io.github.koalaplot.core.pie.PieChart
import io.github.koalaplot.core.style.LineStyle
import io.github.koalaplot.core.util.ExperimentalKoalaPlotApi
import io.github.koalaplot.core.xygraph.CategoryAxisModel
import io.github.koalaplot.core.xygraph.DefaultPoint
import io.github.koalaplot.core.xygraph.FloatLinearAxisModel
import io.github.koalaplot.core.xygraph.LongLinearAxisModel
import io.github.koalaplot.core.xygraph.XYGraph
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.period_1y
import io.bhodl.shared.resources.period_30d
import io.bhodl.shared.resources.period_7d
import io.bhodl.shared.resources.period_90d
import io.bhodl.shared.resources.period_all
import io.bhodl.shared.resources.trk_delta_flat
import io.bhodl.shared.resources.trk_delta_up_from_zero
import io.bhodl.shared.resources.trk_delta_vs_prev
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3.5 — pure aggregation helpers + supporting data/enum types
 * consumed by the Tracking dashboard. Extracted (mechanically) from
 * `TrackingDashboard.kt`; no behaviour change.
 *
 * Pure aggregations over a `List<PaymentEvent>`. No FFI, no I/O —
 * every helper is deterministic given the same input. Lives in
 * `screens/` so the test class can reach it without a new source
 * set, but everything is `internal` to keep the surface minimal.
 */

/** Time window for the dashboard period selector. */
internal enum class TrackingPeriod(val labelRes: StringResource, val days: Int?) {
    D7(Res.string.period_7d, 7),
    D30(Res.string.period_30d, 30),
    D90(Res.string.period_90d, 90),
    Y1(Res.string.period_1y, 365),
    ALL(Res.string.period_all, null),
}

/** UX review 2026-10 (rec 3) — localized chip label ("7D" / "7g"). */
internal val TrackingPeriod.label: String
    @Composable get() = stringResource(labelRes)

/** A single point on the balance-over-time line. */
internal data class BalancePoint(
    val tsUnix: Long,
    val balanceSat: Long,
)

/** Aggregated totals for one category (or the empty bucket). */
internal data class CategorySlice(
    val category: String,
    val incomingSat: ULong,
    val outgoingSat: ULong,
    val count: Int,
) {
    /** Absolute volume passing through this category, used for ranking. */
    val volumeSat: ULong get() = incomingSat + outgoingSat
}

/** KPI block above the charts. */
internal data class DashboardKpis(
    val currentBalanceSat: ULong,
    val inflowSat: ULong,
    val outflowSat: ULong,
    val netSat: Long,
    val txCount: Int,
)

/** A horizontal bar entry — one bucket per period day/week/month. */
internal data class FlowBar(
    val bucketStartUnix: Long,
    val inflowSat: ULong,
    val outflowSat: ULong,
)

/**
 * Return the lower bound (epoch seconds) of `period` relative to `nowUnix`,
 * or `null` for `ALL`. Caller uses this as the `>=` filter on
 * `PaymentEvent.timestampUnix`. Mempool events (`timestampUnix == null`)
 * are always included regardless of period.
 */
internal fun periodLowerBound(period: TrackingPeriod, nowUnix: Long): Long? {
    val days = period.days ?: return null
    return nowUnix - days.toLong() * 86_400L
}

/** Events whose timestamp falls inside the period (mempool entries kept). */
internal fun filterByPeriod(
    events: List<PaymentEvent>,
    period: TrackingPeriod,
    nowUnix: Long,
): List<PaymentEvent> {
    val lower = periodLowerBound(period, nowUnix) ?: return events
    return events.filter { e ->
        val ts = e.timestampUnix ?: return@filter true
        ts.toLong() >= lower
    }
}

/**
 * Reconstruct balance over time from the *full* event stream, picking
 * points that fall inside the period for the line series. We always
 * walk the entire history (oldest first) so the initial point in the
 * window starts at the correct cumulative balance — periods of 7g/30g
 * etc. show real moves rather than a chart that always starts at 0.
 *
 * SelfTransfer events contribute zero (the fee is not surfaced in
 * PaymentEvent for watch wallets, so we ignore it to keep the curve
 * consistent across signing/watch sources).
 */
internal fun balanceSeries(
    allEvents: List<PaymentEvent>,
    period: TrackingPeriod,
    nowUnix: Long,
): List<BalancePoint> {
    if (allEvents.isEmpty()) return emptyList()
    val sorted = allEvents
        .filter { it.timestampUnix != null }
        .sortedBy { it.timestampUnix!! }
    if (sorted.isEmpty()) return emptyList()

    val lower = periodLowerBound(period, nowUnix)
    var running = 0L
    val points = mutableListOf<BalancePoint>()
    // Track the running balance through the whole history so the first
    // in-window point is anchored to the correct cumulative value.
    var sawWindowStart = false
    for (e in sorted) {
        val ts = e.timestampUnix!!.toLong()
        running += signedNet(e)
        if (lower == null || ts >= lower) {
            // Synthesize an anchor at the lower bound so a sparse
            // window doesn't render as a single floating dot.
            if (!sawWindowStart && lower != null) {
                points += BalancePoint(lower, running - signedNet(e))
                sawWindowStart = true
            }
            points += BalancePoint(ts, running)
        }
    }
    if (points.isEmpty() && lower != null) {
        // Period contains no events — anchor a flat line at the final
        // running balance so the chart still draws something.
        points += BalancePoint(lower, running)
        points += BalancePoint(nowUnix, running)
    } else if (lower != null && points.last().tsUnix < nowUnix) {
        // Extend the line to "now" so the rightmost point matches the
        // current balance the KPI card shows.
        points += BalancePoint(nowUnix, running)
    }
    return points
}

/**
 * Phase 3.8 — previous-period balance series for the YoY overlay.
 * Returns points from the window `[now - 2*span, now - span]`,
 * shifted by `+span` so they overlay the current period on the
 * same X axis. `ALL` doesn't have a "previous" so returns empty.
 */
internal fun balanceSeriesPrevious(
    allEvents: List<PaymentEvent>,
    period: TrackingPeriod,
    nowUnix: Long,
): List<BalancePoint> {
    val days = period.days ?: return emptyList()
    val span = days.toLong() * 86_400L
    val prevLower = nowUnix - 2L * span
    val prevUpper = nowUnix - span
    val sorted = allEvents
        .filter { it.timestampUnix != null }
        .sortedBy { it.timestampUnix!! }
    if (sorted.isEmpty()) return emptyList()
    var running = 0L
    val points = mutableListOf<BalancePoint>()
    var sawWindowStart = false
    for (e in sorted) {
        val ts = e.timestampUnix!!.toLong()
        running += signedNet(e)
        if (ts in prevLower..prevUpper) {
            if (!sawWindowStart) {
                points += BalancePoint(prevLower + span, running - signedNet(e))
                sawWindowStart = true
            }
            // Shift by `+span` so the previous-period curve lands
            // on the same X-axis range as the current series.
            points += BalancePoint(ts + span, running)
        }
    }
    if (points.isNotEmpty() && points.last().tsUnix < nowUnix) {
        points += BalancePoint(nowUnix, points.last().balanceSat)
    }
    return points
}

/**
 * Phase 3.8 — daily inflow + outflow volume for the calendar
 * heatmap. Returns a map from `dayOfMonth` (1..31) to absolute
 * volume in sat for the given month. Mempool events are excluded
 * (no day to bucket them in).
 */
internal fun dailyVolumes(
    events: List<PaymentEvent>,
    yearMonth: YearMonth,
    zoneId: ZoneId,
): Map<Int, ULong> {
    val out = HashMap<Int, ULong>()
    for (e in events) {
        val ts = e.timestampUnix?.toLong() ?: continue
        val date = Instant.ofEpochSecond(ts).atZone(zoneId).toLocalDate()
        if (date.year != yearMonth.year || date.monthValue != yearMonth.monthValue) continue
        val day = date.dayOfMonth
        // send_to_self legs contribute only their fee (internal move).
        val v = e.externalInflowSat() + e.externalOutflowSat()
        out[day] = (out[day] ?: 0u) + v
    }
    return out
}

/** Per-direction signed net used by `balanceSeries`. */
// Internal transfers (SELF_TRANSFER / send_to_self) only move the fee.
private fun signedNet(e: PaymentEvent): Long = e.externalSignedNetSat()

/**
 * Roll up events by category for the period. `null` / empty string
 * categories are bucketed under `""` so the donut still gets a slice
 * for "uncategorised".
 */
internal fun categorySlices(
    eventsInPeriod: List<PaymentEvent>,
): List<CategorySlice> {
    if (eventsInPeriod.isEmpty()) return emptyList()
    data class Acc(var inSat: ULong = 0u, var outSat: ULong = 0u, var count: Int = 0)
    val buckets = linkedMapOf<String, Acc>()
    for (e in eventsInPeriod) {
        val key = e.category?.takeIf { it.isNotBlank() } ?: ""
        val acc = buckets.getOrPut(key) { Acc() }
        acc.inSat += e.externalInflowSat()
        acc.outSat += e.externalOutflowSat()
        acc.count += 1
    }
    return buckets
        .map { (k, a) -> CategorySlice(k, a.inSat, a.outSat, a.count) }
        .sortedByDescending { it.volumeSat }
}

/** KPI block driven by the period slice plus the externally-known total. */
internal fun computeKpis(
    eventsInPeriod: List<PaymentEvent>,
    currentBalanceSat: ULong,
): DashboardKpis {
    var inflow: ULong = 0u
    var outflow: ULong = 0u
    for (e in eventsInPeriod) {
        // send_to_self is an internal move: only its fee is an outflow.
        inflow += e.externalInflowSat()
        outflow += e.externalOutflowSat()
    }
    val net = inflow.toLong() - outflow.toLong()
    return DashboardKpis(
        currentBalanceSat = currentBalanceSat,
        inflowSat = inflow,
        outflowSat = outflow,
        netSat = net,
        txCount = eventsInPeriod.size,
    )
}

/**
 * Phase 3.11 — split the wallet balance into confirmed vs pending
 * from the event stream alone. The wallet FFI `balance_sat()`
 * returns `confirmed + trusted_pending + untrusted_pending + immature`
 * as a single sum (see `bhodl-wallet-onchain::account::balance`),
 * so we reconstruct the split from the events we already have:
 *  - `pendingNetSat` = signed net of every event with `timestampUnix == null`
 *    (those are the mempool entries — they will land on a future block
 *    and either add to or subtract from the confirmed balance)
 *  - `confirmedBalanceSat` = total minus the pending net, floored at 0
 *
 * This is approximate: a mempool event the indexer hasn't surfaced
 * yet won't appear in `events`, so the confirmed half can drift
 * upward briefly. But for the user-facing "spendable now vs in flight"
 * split shown on the Balance KPI subtitle, this matches the Sparrow /
 * Wasabi convention well enough until the FFI exposes the split
 * natively (TODO Phase 3.11.b).
 */
internal data class BalanceSplit(
    val confirmedSat: ULong,
    val pendingNetSat: Long,
) {
    val hasPending: Boolean get() = pendingNetSat != 0L
}

internal fun splitBalance(
    totalBalanceSat: ULong,
    allEvents: List<PaymentEvent>,
): BalanceSplit {
    var pendingNet = 0L
    for (e in allEvents) {
        if (e.timestampUnix != null) continue
        pendingNet += e.externalSignedNetSat()
    }
    val confirmed = (totalBalanceSat.toLong() - pendingNet).coerceAtLeast(0L).toULong()
    return BalanceSplit(confirmedSat = confirmed, pendingNetSat = pendingNet)
}

/**
 * Granularity of a bar-chart bucket. Picked from the visible span so
 * the bars are always readable: a 5-day window doesn't get sliced
 * into 12 sub-day buckets, and a multi-year ALL window doesn't
 * pretend each bar is a day.
 */
internal enum class FlowBucketUnit(val seconds: Long) {
    DAY(86_400L),
    WEEK(7L * 86_400L),
    MONTH(30L * 86_400L),
    QUARTER(90L * 86_400L),
}

/**
 * Pick the bucket unit for the requested period given the observed
 * event range. Goal: 4 ≤ bucket count ≤ 16, with bars wide enough
 * that adjacent groups don't visually collapse on a 720dp-wide chart.
 *
 * Fixed-window periods (D7/D30/D90/Y1) snap to their natural unit.
 * ALL inspects the actual event span and steps DAY → WEEK → MONTH →
 * QUARTER as the span grows past those thresholds. This is the fix
 * for the "ALL flat" symptom found in manual testing on 2026-05-15: a wallet
 * with one month of activity now produces ~30 daily bars instead of
 * 12 fixed buckets that all end up collapsed on the leftmost slot.
 */
internal fun pickBucketUnit(period: TrackingPeriod, spanSeconds: Long): FlowBucketUnit =
    when (period) {
        TrackingPeriod.D7 -> FlowBucketUnit.DAY
        TrackingPeriod.D30 -> FlowBucketUnit.DAY
        TrackingPeriod.D90 -> FlowBucketUnit.WEEK
        TrackingPeriod.Y1 -> FlowBucketUnit.MONTH
        TrackingPeriod.ALL -> when {
            spanSeconds <= 14L * 86_400L -> FlowBucketUnit.DAY
            spanSeconds <= 90L * 86_400L -> FlowBucketUnit.WEEK
            spanSeconds <= 365L * 86_400L -> FlowBucketUnit.MONTH
            else -> FlowBucketUnit.QUARTER
        }
    }

/**
 * Bucket period events into time slices sized by [pickBucketUnit].
 * For non-ALL periods the window is `[lowerBound, nowUnix]`; for
 * ALL it spans `[firstEventTs, nowUnix]`. Bucket count is derived
 * (`ceil(span / unit)`) and capped at 24 to keep dense histories
 * readable.
 */
internal fun flowBars(
    eventsInPeriod: List<PaymentEvent>,
    period: TrackingPeriod,
    nowUnix: Long,
): List<FlowBar> {
    if (eventsInPeriod.isEmpty()) return emptyList()
    val (start, end) = when (period) {
        TrackingPeriod.ALL -> {
            val ts = eventsInPeriod.mapNotNull { it.timestampUnix?.toLong() }
            val first = ts.minOrNull() ?: nowUnix
            first to nowUnix
        }
        else -> {
            val lower = periodLowerBound(period, nowUnix)!!
            lower to nowUnix
        }
    }
    val span = (end - start).coerceAtLeast(1L)
    val unit = pickBucketUnit(period, span)
    val rawCount = ((span + unit.seconds - 1) / unit.seconds).toInt()
    val buckets = rawCount.coerceIn(2, 24)
    val width = span / buckets
    val result = ArrayList<FlowBar>(buckets)
    repeat(buckets) { i ->
        val s = start + i * width
        val e = if (i == buckets - 1) end else s + width
        var inflow: ULong = 0u
        var outflow: ULong = 0u
        for (ev in eventsInPeriod) {
            val ts = ev.timestampUnix?.toLong() ?: continue
            if (ts in s until e || (i == buckets - 1 && ts == end)) {
                inflow += ev.externalInflowSat()
                outflow += ev.externalOutflowSat()
            }
        }
        result += FlowBar(s, inflow, outflow)
    }
    return result
}

/**
 * Convert a polyline into stair-step polyline. For each consecutive
 * pair (Ax,Ay) → (Bx,By), insert the intermediate corner (Bx,Ay) so
 * the resulting path reads as "constant at Ay until Bx, then jump to
 * By". This is what `LinePlot` would do natively if Koalaplot 0.9
 * exposed a step variant; for now we inject the corners ourselves.
 */
internal fun steppedPoints(points: List<DefaultPoint<Long, Long>>): List<DefaultPoint<Long, Long>> {
    if (points.size < 2) return points
    val out = mutableListOf<DefaultPoint<Long, Long>>()
    for (i in points.indices) {
        if (i == 0) {
            out += points[i]
        } else {
            // Hold the previous Y across to the new X, then jump to
            // the new Y at the new X (right-handed step).
            out += DefaultPoint(points[i].x, points[i - 1].y)
            out += points[i]
        }
    }
    return out
}

/**
 * Format a tick label proportional to the actual bucket size, not
 * to the requested period. ALL with daily buckets must read `dd/MM`,
 * the same ALL with quarterly buckets reads `MM/yy` — fixed mapping
 * by period would collapse buckets to identical labels.
 */
internal fun formatTimestampTick(ts: Long, unit: FlowBucketUnit): String {
    val pattern = when (unit) {
        FlowBucketUnit.DAY -> "dd/MM"
        FlowBucketUnit.WEEK -> "dd/MM"
        FlowBucketUnit.MONTH -> "MM/yy"
        FlowBucketUnit.QUARTER -> "MM/yy"
    }
    val fmt = DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
    return fmt.format(Instant.ofEpochSecond(ts))
}

/**
 * Phase 3.8 / 3.9 — render a "Δ +12% vs prev" hint for the KPI
 * cards' subtitle line when the YoY toggle is on. Returns "—" when
 * the previous period had zero (no meaningful percentage), and an
 * unsigned percent otherwise (the sign lives in the arrow).
 */
@Composable
internal fun deltaLabel(current: Long, previous: Long): String {
    if (previous == 0L) {
        return if (current == 0L) stringResource(Res.string.trk_delta_flat) else stringResource(Res.string.trk_delta_up_from_zero)
    }
    val pct = ((current - previous).toDouble() / kotlin.math.abs(previous).toDouble()) * 100.0
    val arrow = when {
        pct > 0.5 -> "↑"
        pct < -0.5 -> "↓"
        else -> "="
    }
    val absPct = kotlin.math.abs(pct).toInt()
    return stringResource(Res.string.trk_delta_vs_prev, arrow, "$absPct%")
}
