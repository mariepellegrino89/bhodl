package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.dayOfMonth

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
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
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.categoryDisplayName
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.rememberCompactAmountFormatter
import io.bhodl.desktop.ui.displaySignedAmount
import io.bhodl.desktop.ui.money.AmountMask
import io.bhodl.desktop.ui.ChartDataTable
import io.bhodl.desktop.ui.ChartTableData
import io.bhodl.desktop.ui.ChartTableToggleButton
import io.bhodl.desktop.ui.chartSemantics
import io.bhodl.desktop.ui.money.amountsMasked
import io.bhodl.ffi.PaymentEvent
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_n_categories
import io.bhodl.shared.resources.trk_cat_view_donut
import io.bhodl.shared.resources.trk_cat_view_treemap
import io.bhodl.shared.resources.trk_chart_no_flows
import io.bhodl.shared.resources.trk_chart_not_enough_points
import io.bhodl.shared.resources.trk_chart_nothing_categorised
import io.bhodl.shared.resources.trk_line_smoothed
import io.bhodl.shared.resources.trk_line_stepped
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
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

/**
 * Phase 3.x — chart composables for the Tracking dashboard and their
 * private rendering helpers. Extracted (mechanically) from
 * `TrackingDashboard.kt`; no behaviour change.
 */

/**
 * Phase 3.8 — calendar heatmap for the current month. Each cell is
 * the day of the month; cell saturation scales with the day's
 * absolute volume (in + out). Click a non-zero cell to drill into
 * the History tab pre-filtered to that day's [00:00, 23:59:59]
 * range.
 */
@Composable
internal fun CalendarHeatmap(
    events: List<PaymentEvent>,
    onDayClick: (Pair<Long, Long>) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    // Derived from a clock that ticks at midnight so "today" rolls over.
    val nowUnix = rememberTickingNowUnix(zone)
    val today = remember(nowUnix) {
        java.time.Instant.ofEpochSecond(nowUnix).atZone(zone).toLocalDate()
    }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    val volumes = remember(events, month) { dailyVolumes(events, month, zone) }
    val maxVolume = volumes.values.maxOrNull()?.toFloat() ?: 1f

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CalendarNavButton("◀") { month = month.minusMonths(1) }
            Text(
                text = month.format(
                    DateTimeFormatter.ofPattern("MMMM yyyy")
                        .withLocale(Locale.getDefault()),
                ).replaceFirstChar { it.uppercaseChar() },
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            CalendarNavButton("▶") { month = month.plusMonths(1) }
        }
        // Weekday header — Monday-first (`firstDayOfWeek` = Monday in ISO).
        // Monday-first narrow names in the app language ("M T W …" / "L M M …").
        val weekdays = java.time.DayOfWeek.entries.map {
            it.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            weekdays.forEach { d ->
                Text(
                    text = d,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        // Build the 6×7 grid. Pre-pad with blanks so the 1st of the
        // month aligns under the right weekday column (ISO Monday=0).
        val firstOfMonth = month.atDay(1)
        val leadingBlanks = (firstOfMonth.dayOfWeek.value - 1).coerceAtLeast(0)
        val daysInMonth = month.lengthOfMonth()
        val cells = MutableList<Int?>(leadingBlanks) { null }
        for (d in 1..daysInMonth) cells += d
        while (cells.size % 7 != 0) cells += null
        cells.chunked(7).forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                week.forEach { d ->
                    if (d == null) {
                        Box(modifier = Modifier.weight(1f).height(36.dp))
                    } else {
                        val v = volumes[d] ?: 0u
                        val intensity = if (maxVolume > 0f)
                            v.toFloat() / maxVolume
                        else 0f
                        DayCell(
                            day = d,
                            intensity = intensity,
                            volume = v,
                            isToday = d == today.dayOfMonth &&
                                month == YearMonth.from(today),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (v == 0uL) return@DayCell
                                val date = month.atDay(d)
                                val start = date.atStartOfDay(zone).toEpochSecond()
                                val end = date.atTime(23, 59, 59).atZone(zone).toEpochSecond()
                                onDayClick(start to end)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarNavButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

@Composable
private fun DayCell(
    day: Int,
    intensity: Float,
    volume: ULong,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // Floor at 0.06 alpha so the day cell stays visible even with
    // zero activity; cap at 0.85 so the day number stays readable
    // on the brightest cells.
    val alpha = if (volume == 0uL) 0.04f else (0.15f + intensity * 0.7f)
    val bg = BhodlTheme.colors.brandPrimary.copy(alpha = alpha)
    val borderColor = if (isToday) BhodlTheme.colors.brandPrimary
    else BhodlTheme.colors.outline
    Column(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(
                if (isToday) 1.5.dp else 0.5.dp,
                borderColor,
                RoundedCornerShape(6.dp),
            )
            .clickable(enabled = volume > 0u) { onClick() }
            .padding(2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = day.toString(),
            style = BhodlTheme.typography.labelMedium,
            color = if (intensity > 0.5f) BhodlTheme.colors.brandOnPrimary
            else BhodlTheme.colors.textPrimary,
        )
    }
}

@Composable
internal fun ChartCard(
    title: String,
    height: androidx.compose.ui.unit.Dp? = 220.dp,
    headerTrailing: (@Composable () -> Unit)? = null,
    /** UX review 2026-10 (item 15) — table view of the chart; non-null adds the toggle. */
    table: ChartTableData? = null,
    /** Accessible chart name ("Cash flow"); defaults to [title]. */
    chartName: String? = null,
    content: @Composable () -> Unit,
) {
    var showTable by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (!showTable) headerTrailing?.invoke()
            if (table != null) {
                ChartTableToggleButton(showTable = showTable, onToggle = { showTable = !showTable })
            }
        }
        if (table != null && showTable) {
            ChartDataTable(data = table)
        } else {
            val chartModifier = Modifier.fillMaxWidth().chartSemantics(chartName ?: title)
            if (height != null) {
                Box(modifier = chartModifier.height(height)) {
                    content()
                }
            } else {
                Box(modifier = chartModifier) {
                    content()
                }
            }
        }
    }
}

/**
 * Phase 3.12 — line renderer choice for the Balance chart.
 *  - SMOOTHED: straight segments between samples (Koalaplot default).
 *  - STEPPED: horizontal-then-vertical "stairs" — more honest for
 *    ledger-based balance data which only changes at transaction
 *    events and is constant between them. Sparrow's StackTrack plugin
 *    uses stepped; TradingView OHLC uses stepped; both because a
 *    smoothed segment implies wallet balance interpolated over time,
 *    which is misleading.
 */
internal enum class BalanceLineMode { SMOOTHED, STEPPED }

@Composable
internal fun BalanceLineModeToggle(
    mode: BalanceLineMode,
    onChange: (BalanceLineMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            BalanceLineMode.SMOOTHED to stringResource(Res.string.trk_line_smoothed),
            BalanceLineMode.STEPPED to stringResource(Res.string.trk_line_stepped),
        ).forEach { (m, label) ->
            val sel = m == mode
            val bg = if (sel) BhodlTheme.colors.brandPrimary
            else BhodlTheme.colors.surface
            val fg = if (sel) BhodlTheme.colors.brandOnPrimary
            else BhodlTheme.colors.textSecondary
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
                    .clickable { onChange(m) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = label,
                    style = BhodlTheme.typography.labelMedium,
                    color = fg,
                )
            }
        }
    }
}

@OptIn(ExperimentalKoalaPlotApi::class)
@Composable
internal fun BalanceLineChart(
    series: List<BalancePoint>,
    period: TrackingPeriod,
    previousSeries: List<BalancePoint> = emptyList(),
    mode: BalanceLineMode = BalanceLineMode.SMOOTHED,
) {
    if (series.size < 2) {
        Text(
            text = stringResource(Res.string.trk_chart_not_enough_points),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        return
    }
    val rawPoints = series.map { DefaultPoint(it.tsUnix, it.balanceSat) }
    val rawPrevPoints = previousSeries.map { DefaultPoint(it.tsUnix, it.balanceSat) }
    val points = if (mode == BalanceLineMode.STEPPED) steppedPoints(rawPoints) else rawPoints
    val prevPoints = if (mode == BalanceLineMode.STEPPED) steppedPoints(rawPrevPoints) else rawPrevPoints
    val combined = points + prevPoints
    val xMin = combined.minOf { it.x }
    val xMax = combined.maxOf { it.x }
    val yMinRaw = combined.minOf { it.y }
    val yMaxRaw = combined.maxOf { it.y }
    val xRange = xMin..(if (xMax > xMin) xMax else xMin + 1L)
    val yRange = yMinRaw..(if (yMaxRaw > yMinRaw) yMaxRaw else yMaxRaw + 1L)
    val color = BhodlTheme.colors.brandPrimary
    val prevColor = BhodlTheme.colors.textTertiary
    val xSpan = (xMax - xMin).coerceAtLeast(1L)
    val xTickUnit = pickBucketUnit(period, xSpan)
    // Phase 3.11 — Y axis labels formatted in the user's chosen unit
    // (Sats / BTC / Auto, compact; UX review 2026-10: one unit for the
    // whole axis, never mixed). Captured once at the composable boundary so the
    // axis-label lambda (called per-tick during the draw pass) can
    // stay free of @Composable annotations.
    val axisFmt = rememberCompactAmountFormatter(axisSats = listOf(yMinRaw, yMaxRaw))
    XYGraph<Long, Long>(
        xAxisModel = LongLinearAxisModel(xRange),
        yAxisModel = LongLinearAxisModel(yRange),
        xAxisLabels = { ts: Long -> formatTimestampTick(ts, xTickUnit) },
        xAxisTitle = "",
        yAxisLabels = { sat: Long ->
            axisFmt(if (sat < 0L) 0L else sat)
        },
        yAxisTitle = "",
    ) {
        // Previous-period overlay first so the current series wins
        // visually on overlap. Stroke at half-width with tertiary
        // colour to read as "ghost / historical".
        if (prevPoints.size >= 2) {
            LinePlot(
                data = prevPoints,
                lineStyle = LineStyle(SolidColor(prevColor), 1.dp),
            )
        }
        LinePlot(
            data = points,
            lineStyle = LineStyle(SolidColor(color), 2.dp),
        )
    }
}

/**
 * Recover the bar bucket unit from a populated `flowBars` result.
 * The chart is downstream of [flowBars] but Koalaplot's category
 * axis only sees the labels, so we reconstruct the unit from the
 * gap between two consecutive bucket starts.
 */
internal fun deriveBarUnit(bars: List<FlowBar>): FlowBucketUnit {
    if (bars.size < 2) return FlowBucketUnit.DAY
    val diff = bars[1].bucketStartUnix - bars[0].bucketStartUnix
    return when {
        diff <= 86_400L -> FlowBucketUnit.DAY
        diff <= 7L * 86_400L -> FlowBucketUnit.WEEK
        diff <= 30L * 86_400L -> FlowBucketUnit.MONTH
        else -> FlowBucketUnit.QUARTER
    }
}

@OptIn(ExperimentalKoalaPlotApi::class)
@Composable
internal fun FlowsBarChart(bars: List<FlowBar>, period: TrackingPeriod) {
    if (bars.isEmpty()) {
        Text(
            text = stringResource(Res.string.trk_chart_no_flows),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        return
    }
    val unit = deriveBarUnit(bars)
    val labels = bars.map { formatTimestampTick(it.bucketStartUnix, unit) }
    val maxY = bars.maxOf { maxOf(it.inflowSat, it.outflowSat) }.toFloat()
    if (maxY <= 0f) {
        Text(
            text = stringResource(Res.string.trk_chart_no_flows),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        return
    }
    // Phase 3.10 — semantic colors so the bar pair reads "+ income /
    // − expense" rather than "brand orange / error red". Western
    // users see green/red; Eastern users see red/green (color
    // convention is wired through BhodlTheme).
    val inflowColor = BhodlTheme.colors.financialPositiveMuted
    val outflowColor = BhodlTheme.colors.financialNegativeMuted
    val axisFmt = rememberCompactAmountFormatter(axisSats = listOf(maxY.toLong()))
    XYGraph<String, Float>(
        xAxisModel = CategoryAxisModel(labels),
        yAxisModel = FloatLinearAxisModel(0f..(maxY * 1.1f)),
        xAxisLabels = { it: String -> it },
        xAxisTitle = "",
        yAxisLabels = { sat: Float ->
            axisFmt(sat.toLong().coerceAtLeast(0L))
        },
        yAxisTitle = "",
    ) {
        GroupedVerticalBarPlot<String, Float>(
            maxBarGroupWidth = 0.9f,
        ) {
            // Inflow series — semantic positive, left bar in each group.
            series(
                {
                    DefaultVerticalBar(
                        brush = SolidColor(inflowColor),
                        modifier = Modifier.fillMaxWidth(0.9f),
                    )
                },
            ) {
                bars.forEach { b ->
                    item(
                        formatTimestampTick(b.bucketStartUnix, unit),
                        0f,
                        b.inflowSat.toFloat(),
                    )
                }
            }
            // Outflow series — semantic negative, right bar in each group.
            series(
                {
                    DefaultVerticalBar(
                        brush = SolidColor(outflowColor),
                        modifier = Modifier.fillMaxWidth(0.9f),
                    )
                },
            ) {
                bars.forEach { b ->
                    item(
                        formatTimestampTick(b.bucketStartUnix, unit),
                        0f,
                        b.outflowSat.toFloat(),
                    )
                }
            }
        }
    }
}

/**
 * Phase 3.9 — alternative renderer for the category breakdown.
 * Treemap is more useful than a donut once you have many small
 * categories (the donut's slim slices become unreadable below ~5%
 * each). The user toggles between them with the chip in the
 * chart card title.
 */
internal enum class CategoryViewMode { DONUT, TREEMAP }

@Composable
internal fun CategoryViewToggle(
    mode: CategoryViewMode,
    onChange: (CategoryViewMode) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            CategoryViewMode.DONUT to stringResource(Res.string.trk_cat_view_donut),
            CategoryViewMode.TREEMAP to stringResource(Res.string.trk_cat_view_treemap),
        ).forEach { (m, label) ->
            val sel = m == mode
            val bg = if (sel) BhodlTheme.colors.brandPrimary
            else BhodlTheme.colors.surface
            val fg = if (sel) BhodlTheme.colors.brandOnPrimary
            else BhodlTheme.colors.textSecondary
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
                    .clickable { onChange(m) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = label,
                    style = BhodlTheme.typography.labelMedium,
                    color = fg,
                )
            }
        }
    }
}

/**
 * Slice-and-dice treemap rendered with nested Compose layouts. We
 * split the top-N slices into two columns (round-robin assignment
 * to balance area) and within each column stack rectangles whose
 * height is proportional to volume. Click on any tile drills down
 * to History (same callback the donut uses).
 */
@Composable
internal fun CategoryTreemap(
    slices: List<CategorySlice>,
    onTileClick: (String) -> Unit,
) {
    val nonZero = slices.filter { it.volumeSat > 0u }
    if (nonZero.isEmpty()) {
        Text(
            text = stringResource(Res.string.trk_chart_nothing_categorised),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        return
    }
    val palette = donutPalette(nonZero.size)
    // Round-robin into two columns so the visually-largest slice
    // doesn't always end up in column 0.
    val columnA = nonZero.filterIndexed { i, _ -> i % 2 == 0 }
    val columnB = nonZero.filterIndexed { i, _ -> i % 2 == 1 }
    val sumA = columnA.sumOf { it.volumeSat.toLong() }.coerceAtLeast(1L)
    val sumB = columnB.sumOf { it.volumeSat.toLong() }.coerceAtLeast(1L)
    val total = sumA + sumB
    Row(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().weight(sumA.toFloat() / total.toFloat()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            columnA.forEachIndexed { i, slice ->
                TreemapTile(
                    slice = slice,
                    color = palette[nonZero.indexOf(slice)],
                    weight = slice.volumeSat.toFloat().coerceAtLeast(1f),
                    onClick = { onTileClick(slice.category) },
                )
            }
        }
        if (columnB.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(sumB.toFloat() / total.toFloat()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                columnB.forEachIndexed { i, slice ->
                    TreemapTile(
                        slice = slice,
                        color = palette[nonZero.indexOf(slice)],
                        weight = slice.volumeSat.toFloat().coerceAtLeast(1f),
                        onClick = { onTileClick(slice.category) },
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.TreemapTile(
    slice: CategorySlice,
    color: Color,
    weight: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(weight)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .clickable { onClick() }
            .padding(6.dp),
    ) {
        Column {
            Text(
                text = categoryDisplayName(slice.category),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.brandOnPrimary,
            )
            Text(
                text = displayAmount(slice.volumeSat),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.brandOnPrimary.copy(alpha = 0.85f),
            )
        }
    }
}

@OptIn(ExperimentalKoalaPlotApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun CategoryDonut(
    slices: List<CategorySlice>,
    onSliceClick: (String) -> Unit = {},
) {
    val nonZero = slices.filter { it.volumeSat > 0u }
    if (nonZero.isEmpty()) {
        Text(
            text = stringResource(Res.string.trk_chart_nothing_categorised),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        return
    }
    val palette = donutPalette(nonZero.size)
    val labels = nonZero.map { categoryDisplayName(it.category) }
    val values = nonZero.map { it.volumeSat.toFloat() }
    // Phase 3.12 — donut centre-text. Total volume + period descriptor
    // so the user reads the donut as "X moved across N categories"
    // rather than needing the legend to confirm scale.
    val totalSat = nonZero.sumOf { it.volumeSat }
    val centerTotalText = displayAmount(totalSat)
    val compactTotalText = rememberCompactAmountFormatter()(totalSat.toLong())
    val centerSubtitle = stringResource(Res.string.tracker_n_categories, nonZero.size)
    ChartLayout(
        legend = {
            FlowLegend(
                itemCount = nonZero.size,
                symbol = { i ->
                    Box(
                        Modifier.size(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette[i]),
                    )
                },
                label = { i ->
                    Text(
                        text = labels[i],
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                },
            )
        },
        legendLocation = LegendLocation.BOTTOM,
    ) {
        Box(contentAlignment = Alignment.Center) {
            PieChart(
                values = values,
                slice = { i ->
                    DefaultSlice(
                        color = palette[i],
                        onClick = { onSliceClick(nonZero[i].category) },
                    )
                },
                holeSize = 0.55f,
            )
            // UX review 2026-10 (B14) — the centre label must stay inside
            // the hole: at narrow widths the full "12345678 sat" spilled
            // over the ring. Below ~150dp of hole the amount is shown in
            // compact form ("25.4M sat"); the tooltip always carries the
            // full amount.
            BoxWithConstraints(contentAlignment = Alignment.Center) {
                val holeDp = minOf(maxWidth, maxHeight) * 0.55f
                val fitsFull = holeDp >= 150.dp
                TooltipArea(
                    tooltip = { HomeTooltip("$centerTotalText · $centerSubtitle") },
                    delayMillis = 300,
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = holeDp * 0.9f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (fitsFull) centerTotalText else compactTotalText,
                            style = if (fitsFull) BhodlTheme.typography.titleMedium
                            else BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textPrimary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = centerSubtitle,
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Phase 3.10 — Okabe-Ito 8-color CUD palette pulled from the theme.
 * The categorical palette is intentionally disjoint from the brand
 * orange and from the semantic green/red so a donut slice never
 * collides visually with the "income" or "expense" semantic colors.
 * Index 7 is the neutral grey reserved for the "Other" rollup; the
 * cycle restarts at index 0 for the rare > 8 categories case.
 */
@Composable
private fun donutPalette(n: Int): List<Color> {
    val base = BhodlTheme.colors.chartCategorical
    return List(n) { base[it % base.size] }
}

/**
 * Phase 3.7 — minimal Compose Canvas sparkline. No axes, no labels,
 * no animation — just a stroked polyline scaled to fit the box. We
 * deliberately avoid Koalaplot here because the surrounding KPI
 * card is tiny (≤24dp tall) and Koalaplot's axis machinery would
 * dominate the pixel budget.
 */
@Composable
internal fun Sparkline(
    values: List<Float>,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return
    val minV = values.min()
    val maxV = values.max()
    val range = (maxV - minV).coerceAtLeast(1f)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val step = w / (values.size - 1).toFloat()
        val path = Path().apply {
            values.forEachIndexed { i, v ->
                val x = i * step
                val y = h - ((v - minV) / range) * h
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}
