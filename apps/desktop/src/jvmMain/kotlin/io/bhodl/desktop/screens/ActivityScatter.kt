package io.bhodl.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.rememberCompactAmountFormatter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.period_1y
import io.bhodl.shared.resources.period_30d
import io.bhodl.shared.resources.period_7d
import io.bhodl.shared.resources.period_90d
import io.bhodl.shared.resources.period_today
import org.jetbrains.compose.resources.stringResource
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ui.theme.BhodlTheme
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Phase 3.15 — activity scatter (age × amount).
 *
 * X axis: days since the event (0 = today, increasing rightward → older).
 * Y axis: log-scaled amount in sat (so a 1k-sat dust event and a 10M-sat
 * settle land on the same chart). Each dot is one event:
 *  - INCOMING → semantic positive color (green by default)
 *  - OUTGOING → semantic negative color (red by default)
 *  - SELF_TRANSFER → neutral grey
 *  - dot radius scales mildly with amount so the visually-largest tx
 *    pops without requiring a hover tooltip.
 *
 * This is the BHODL analog of Satsigner's UTXO map / Wasabi's privacy
 * bar — a single-glance view of "where are my big-ish events clustered
 * in time?" Useful for spotting outlier transactions, dust runs, and
 * activity bursts without scrolling through History.
 *
 * Pure event-based: no FFI calls, no UTXO list, no privacy score —
 * those need wallet-side data the FFI doesn't expose today. A future
 * Phase 3.15.b should layer them in (UTXO age + value + anon-set).
 */
@Composable
internal fun ActivityScatter(
    events: List<PaymentEvent>,
    nowUnix: Long,
) {
    val positive = BhodlTheme.colors.financialPositive
    val negative = BhodlTheme.colors.financialNegative
    val neutral = BhodlTheme.colors.financialNeutral
    val axisColor = BhodlTheme.colors.outline
    val labelColor = BhodlTheme.colors.textTertiary
    val labelStyle = BhodlTheme.typography.labelMedium.copy(color = labelColor)
    val textMeasurer = rememberTextMeasurer()
    // Datapoints — drop mempool entries (no confirmed timestamp to
    // place them on the age axis) and self-transfers (zero net flow).
    val points = remember(events, nowUnix) {
        events.mapNotNull { e ->
            val ts = e.timestampUnix?.toLong() ?: return@mapNotNull null
            if (e.amountSat == 0UL) return@mapNotNull null
            ActivityPoint(
                ageDays = ((nowUnix - ts).coerceAtLeast(0L) / 86_400.0),
                amountSat = e.amountSat.toDouble(),
                color = when (e.direction) {
                    PaymentDirection.INCOMING -> positive
                    PaymentDirection.OUTGOING -> negative
                    PaymentDirection.SELF_TRANSFER -> neutral
                },
            )
        }
    }
    if (points.isEmpty()) return
    val maxAge = points.maxOf { it.ageDays }.coerceAtLeast(1.0)
    val maxAmount = points.maxOf { it.amountSat }.coerceAtLeast(1.0)
    val minAmount = points.filter { it.amountSat > 0 }.minOfOrNull { it.amountSat } ?: 1.0
    // UX review 2026-10 — Y ticks through the shared compact formatter
    // (one unit for the whole axis), X ticks localized like the chips.
    val compact = rememberCompactAmountFormatter(axisSats = listOf(maxAmount.toLong()))
    val today = stringResource(Res.string.period_today)
    val d7 = stringResource(Res.string.period_7d)
    val d30 = stringResource(Res.string.period_30d)
    val d90 = stringResource(Res.string.period_90d)
    val y1 = stringResource(Res.string.period_1y)
    val axisLabels = ScatterAxisLabels(
        yPow = { p -> if (p in 0..18) compact(10.0.pow(p).toLong()) else "10^$p" },
        xDays = { d ->
            when (d) {
                0 -> today
                7 -> d7
                30 -> d30
                90 -> d90
                365 -> y1
                else -> d.toString() // not reached: the ticks are the five above
            }
        },
    )
    Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            drawScatter(
                points = points,
                maxAge = maxAge,
                minAmount = minAmount,
                maxAmount = maxAmount,
                axisColor = axisColor,
                textMeasurer = textMeasurer,
                labelStyle = labelStyle,
                axisLabels = axisLabels,
            )
        }
    }
}

internal data class ActivityPoint(
    val ageDays: Double,
    val amountSat: Double,
    val color: Color,
)

/**
 * Phase 3.15.b — pick log-scale Y ticks for the activity scatter.
 *
 * Returns the powers of 10 that bound the data, thinned to at most 5
 * entries so the axis stays readable. A wallet whose values span 6
 * powers of 10 gets every other power; a tight 2-power wallet gets
 * every power. Always includes both endpoints so the axis labels the
 * extremes the user can see in the data.
 */
internal fun computeLogTicks(minAmount: Double, maxAmount: Double): List<Int> {
    val lo = floor(log10(minAmount.coerceAtLeast(1.0))).toInt()
    val hi = floor(log10(maxAmount.coerceAtLeast(1.0))).toInt()
    if (hi <= lo) return listOf(hi)
    val all = (lo..hi).toList()
    if (all.size <= 5) return all
    // Stride so the kept list has ≤5 entries; always include endpoints.
    val stride = ((all.size + 4) / 5).coerceAtLeast(1)
    val kept = all.filterIndexed { i, _ -> i % stride == 0 }.toMutableList()
    if (kept.last() != hi) kept += hi
    return kept
}

/**
 * Localized axis labels for the (non-composable) draw pass: [yPow]
 * labels a 10^p sat tick through the compact shared formatter, [xDays]
 * an age tick ("today", "7D", …).
 */
private class ScatterAxisLabels(
    val yPow: (Int) -> String,
    val xDays: (Int) -> String,
)

private fun DrawScope.drawScatter(
    points: List<ActivityPoint>,
    maxAge: Double,
    minAmount: Double,
    maxAmount: Double,
    axisColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    labelStyle: TextStyle,
    axisLabels: ScatterAxisLabels,
) {
    val padLeft = 48f
    val padBottom = 24f
    val padTop = 8f
    val padRight = 8f
    val w = size.width - padLeft - padRight
    val h = size.height - padTop - padBottom
    // Axes.
    drawLine(
        color = axisColor,
        start = Offset(padLeft, padTop),
        end = Offset(padLeft, padTop + h),
        strokeWidth = 1f,
    )
    drawLine(
        color = axisColor,
        start = Offset(padLeft, padTop + h),
        end = Offset(padLeft + w, padTop + h),
        strokeWidth = 1f,
    )
    // Log scale for Y so dust and big settles fit on the same chart.
    // Floor minAmount at 1 sat to keep ln() well-defined.
    val lnMin = ln(minAmount.coerceAtLeast(1.0))
    val lnMax = ln(maxAmount.coerceAtLeast(minAmount + 1.0))
    val lnRange = (lnMax - lnMin).coerceAtLeast(0.1)
    fun xFor(ageDays: Double): Float = padLeft + (w * (ageDays / maxAge).toFloat())
    fun yFor(amount: Double): Float {
        val lnA = ln(amount.coerceAtLeast(1.0))
        val ratio = ((lnA - lnMin) / lnRange).coerceIn(0.0, 1.0)
        return padTop + (h * (1.0 - ratio)).toFloat()
    }
    // Phase 3.15.b — dynamic Y axis ticks computed from the actual
    // data range instead of the hardcoded [1, 10k, 1M, 100M]. Walks
    // every power of ten that falls inside [minAmount, maxAmount],
    // then thins out to at most 5 labels so the axis doesn't crowd
    // on a small wallet (1 sat … 9999 sat) or a huge wallet (1 sat …
    // 21B sat). Without this fix, a wallet whose max amount is < 10k
    // would render only the "1 sat" baseline.
    val tickPows = computeLogTicks(minAmount, maxAmount)
    tickPows.forEach { p ->
        val v = 10.0.pow(p)
        val y = yFor(v)
        drawLine(
            color = axisColor.copy(alpha = 0.3f),
            start = Offset(padLeft, y),
            end = Offset(padLeft + w, y),
            strokeWidth = 1f,
        )
        val label = axisLabels.yPow(p)
        val layout = textMeasurer.measure(AnnotatedString(label), labelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(padLeft - layout.size.width - 4f, y - layout.size.height / 2f),
        )
    }
    // X axis ticks at today / 7d / 30d / 90d / 365d limited to maxAge.
    val ageTicks = listOf(0.0, 7.0, 30.0, 90.0, 365.0).filter { it <= maxAge * 1.1 }
    ageTicks.forEach { d ->
        val x = xFor(d)
        drawLine(
            color = axisColor.copy(alpha = 0.3f),
            start = Offset(x, padTop),
            end = Offset(x, padTop + h),
            strokeWidth = 1f,
        )
        val label = axisLabels.xDays(d.toInt())
        val layout = textMeasurer.measure(AnnotatedString(label), labelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(x - layout.size.width / 2f, padTop + h + 4f),
        )
    }
    // Points — radius scales mildly with amount: r = 3..7 dp ≈ 6..14
    // pixels at 2x density. Stroke the circle (not fill) at alpha 0.7
    // so overlapping dots stay readable.
    points.forEach { p ->
        val x = xFor(p.ageDays)
        val y = yFor(p.amountSat)
        val lnA = ln(p.amountSat.coerceAtLeast(1.0))
        val ratio = ((lnA - lnMin) / lnRange).coerceIn(0.0, 1.0)
        val radius = 3.dp.toPx() + (ratio * 4.dp.toPx()).toFloat()
        drawCircle(
            color = p.color.copy(alpha = 0.7f),
            radius = radius,
            center = Offset(x, y),
            style = Stroke(width = 1.5f),
        )
        drawCircle(
            color = p.color.copy(alpha = 0.15f),
            radius = radius,
            center = Offset(x, y),
        )
    }
}
