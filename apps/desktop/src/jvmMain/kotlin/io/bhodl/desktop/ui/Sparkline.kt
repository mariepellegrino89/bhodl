package io.bhodl.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme

/**
 * Phase 8.3.2 — overlay line style. The optional secondary series on
 * the [Sparkline] is rendered in one of three stroke modes. [Dashed] is
 * the Bitcoin Lens convention used by [io.bhodl.desktop.screens.NetWorthHero]
 * to surface the fiat value of the net worth alongside the (primary,
 * sat-on-y) line.
 */
public enum class OverlayStyle { Dashed, Dotted, Solid }

/**
 * Phase 7.1 — pure-Compose Canvas sparkline for net-worth, investment
 * and budget trend visualisation.
 *
 * Renders a single-stroke line over an area-fill gradient. Color is
 * picked automatically from the trend direction (financial positive
 * if the last point >= first, negative otherwise) unless [lineColor]
 * forces a specific tone.
 *
 * Scrubbing: when [onScrub] is non-null, the user can hover (desktop)
 * or drag (touch) over the canvas to inspect the value at a specific
 * x-coordinate. The callback fires with the closest point to the
 * cursor; null when the pointer leaves the canvas. The callback is
 * NOT throttled — caller is responsible for any debouncing.
 *
 * Degenerate input:
 *  - empty list → renders a blank Spacer of the requested size.
 *  - single point → renders a flat horizontal line at the midline.
 *
 * Implementation notes:
 *  - X-axis is mapped to the timestamp range linearly; non-uniform
 *    sampling (e.g. CoinGecko returning hourly for 30d but daily for
 *    365d) is rendered faithfully — gaps in time become wider segments.
 *  - Y-axis is mapped to [0, height] with the min/max of the visible
 *    series. We do NOT zero-anchor the Y-axis — for a 30-day BTC chart
 *    that lives in $90k-$110k, anchoring to zero would compress all
 *    the variation into noise.
 *  - Area fill uses a vertical gradient from [lineColor] @ 30% alpha
 *    at the top to fully transparent at the bottom.
 *
 * Phase 8.3.2 — optional dual-line support via [overlayPoints]. When
 * non-null, a second stroked path is drawn on top of the primary line.
 * The overlay is normalised on its OWN y-range (the two series will
 * typically have very different magnitudes — e.g. sat-on-y primary +
 * fiat-on-y overlay). The overlay is purely visual: scrub still returns
 * the primary point and no area fill is rendered for it (the primary
 * stays dominant). [overlayStyle] picks the stroke type — defaults to
 * [OverlayStyle.Dashed] for the Bitcoin Lens fiat overlay.
 */
@Composable
public fun Sparkline(
    points: List<Pair<Long, Double>>,
    modifier: Modifier = Modifier,
    lineColor: Color? = null,
    strokeWidth: Dp = 1.5.dp,
    onScrub: ((Pair<Long, Double>?) -> Unit)? = null,
    overlayPoints: List<Pair<Long, Double>>? = null,
    overlayStyle: OverlayStyle = OverlayStyle.Dashed,
) {
    if (points.isEmpty()) {
        Spacer(modifier)
        return
    }
    val resolvedColor = lineColor ?: run {
        val first = points.first().second
        val last = points.last().second
        if (last >= first) BhodlTheme.colors.financialPositive
        else BhodlTheme.colors.financialNegative
    }
    // Read theme colors here (in @Composable scope) and capture them
    // for the Canvas lambda which runs in DrawScope, not Composable
    // scope.
    val gridColor = BhodlTheme.colors.textTertiary
    val backgroundColor = BhodlTheme.colors.background

    // Scrubbing state — held inside the composable to keep callers
    // declarative. The marker dot is painted from this value too.
    var scrubX by remember { mutableStateOf<Float?>(null) }

    val gestureModifier = if (onScrub != null) {
        Modifier.pointerInput(points.size) {
            awaitEachGesture {
                // Capture both hover-on-desktop and drag-on-touch in
                // one loop. PointerEventType.Move fires for both;
                // Release / Exit clears the marker.
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Move,
                        PointerEventType.Press,
                        PointerEventType.Enter -> {
                            val pos = event.changes.firstOrNull()?.position ?: continue
                            scrubX = pos.x.coerceIn(0f, size.width.toFloat())
                            val closest = nearestPoint(points, scrubX!!, size.width.toFloat())
                            onScrub(closest)
                        }
                        PointerEventType.Release,
                        PointerEventType.Exit -> {
                            scrubX = null
                            onScrub(null)
                        }
                    }
                    if (event.changes.all { it.changedToUp() }) {
                        // Stop the gesture loop on full release so the
                        // next gesture restarts cleanly.
                        try { awaitFirstDown() } catch (_: Throwable) { break }
                    }
                }
            }
        }
    } else Modifier

    Canvas(modifier = modifier.then(gestureModifier)) {
        if (points.size == 1) {
            // Degenerate flat line at midline.
            val y = size.height / 2f
            drawLine(
                color = resolvedColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = strokeWidth.toPx(),
                cap = StrokeCap.Round,
            )
            return@Canvas
        }
        val minTs = points.first().first
        val maxTs = points.last().first
        val tsRange = (maxTs - minTs).coerceAtLeast(1L).toFloat()
        val minY = points.minOf { it.second }
        val maxY = points.maxOf { it.second }
        // Avoid a degenerate flat line if all values are identical:
        // give a 1-unit y-range so the path renders at midline.
        val yRange = (maxY - minY).let { if (it == 0.0) 1.0 else it }

        fun xFor(ts: Long): Float = ((ts - minTs).toFloat() / tsRange) * size.width
        fun yFor(value: Double): Float {
            // Invert: y=0 at the top in Canvas coords.
            val norm = ((value - minY) / yRange).toFloat()
            // Leave 6% headroom top and bottom so the stroke doesn't
            // touch the canvas edges.
            val padded = 0.06f + norm * 0.88f
            return size.height - (padded * size.height)
        }

        val linePath = Path().apply {
            moveTo(xFor(points.first().first), yFor(points.first().second))
            points.drop(1).forEach { (ts, v) -> lineTo(xFor(ts), yFor(v)) }
        }
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(xFor(points.last().first), size.height)
            lineTo(xFor(points.first().first), size.height)
            close()
        }

        // Area fill (gradient).
        drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    resolvedColor.copy(alpha = 0.25f),
                    resolvedColor.copy(alpha = 0.0f),
                ),
            ),
        )
        // Line stroke.
        drawPath(
            path = linePath,
            color = resolvedColor,
            style = Stroke(
                width = strokeWidth.toPx(),
                cap = StrokeCap.Round,
            ),
        )

        // Phase 8.3.2 — optional dual-line overlay. The overlay is
        // normalised on its OWN y-range (the two series will typically
        // have very different magnitudes — sat-on-y primary + fiat-on-y
        // overlay for the Bitcoin Lens net-worth hero). The overlay
        // shares the x-axis (a common timeline) so non-overlapping
        // timestamps still align on the time axis.
        if (overlayPoints != null && overlayPoints.size >= 2) {
            val oMinTs = overlayPoints.first().first
            val oMaxTs = overlayPoints.last().first
            val oTsRange = (oMaxTs - oMinTs).coerceAtLeast(1L).toFloat()
            val oMinY = overlayPoints.minOf { it.second }
            val oMaxY = overlayPoints.maxOf { it.second }
            val oYRange = (oMaxY - oMinY).let { if (it == 0.0) 1.0 else it }

            fun oxFor(ts: Long): Float =
                ((ts - oMinTs).toFloat() / oTsRange) * size.width
            fun oyFor(value: Double): Float {
                val norm = ((value - oMinY) / oYRange).toFloat()
                val padded = 0.06f + norm * 0.88f
                return size.height - (padded * size.height)
            }

            val overlayPath = Path().apply {
                moveTo(oxFor(overlayPoints.first().first), oyFor(overlayPoints.first().second))
                overlayPoints.drop(1).forEach { (ts, v) -> lineTo(oxFor(ts), oyFor(v)) }
            }
            val overlayPathEffect = when (overlayStyle) {
                OverlayStyle.Dashed -> PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                OverlayStyle.Dotted -> PathEffect.dashPathEffect(floatArrayOf(2f, 4f), 0f)
                OverlayStyle.Solid -> null
            }
            // Tenuous tone so the primary line stays dominant — same
            // tertiary text color used for the hover grid line, at
            // ~55% alpha (visible but recessive).
            val overlayColor = gridColor.copy(alpha = 0.55f)
            drawPath(
                path = overlayPath,
                color = overlayColor,
                style = Stroke(
                    width = strokeWidth.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = overlayPathEffect,
                ),
            )
        }

        // Hover marker — vertical dashed line + dot at the closest
        // data point. Painted last so it sits above the line.
        scrubX?.let { x ->
            val closest = nearestPoint(points, x, size.width.toFloat())
            if (closest != null) {
                val cx = xFor(closest.first)
                val cy = yFor(closest.second)
                drawLine(
                    color = gridColor,
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
                )
                drawCircle(
                    color = resolvedColor,
                    radius = 4f,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = backgroundColor,
                    radius = 2f,
                    center = Offset(cx, cy),
                )
            }
        }
    }
}

/**
 * Find the data point whose x-coordinate (after mapping ts → canvas
 * width) is closest to the cursor's [scrubX]. Pure helper, exposed
 * `internal` so unit tests can verify the mapping math.
 */
internal fun nearestPoint(
    points: List<Pair<Long, Double>>,
    scrubX: Float,
    width: Float,
): Pair<Long, Double>? {
    if (points.isEmpty()) return null
    if (points.size == 1) return points.first()
    val minTs = points.first().first
    val maxTs = points.last().first
    val tsRange = (maxTs - minTs).coerceAtLeast(1L).toFloat()
    val normalised = scrubX.coerceIn(0f, width) / width
    val targetTs = minTs + (normalised * tsRange).toLong()
    return points.minByOrNull { kotlin.math.abs(it.first - targetTs) }
}
