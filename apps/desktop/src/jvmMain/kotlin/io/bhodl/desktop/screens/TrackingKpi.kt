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
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_last_sync
import io.bhodl.shared.resources.common_never_synced
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.trk_compare_prev
import io.bhodl.shared.resources.trk_kpi_balance
import io.bhodl.shared.resources.trk_kpi_balance_cross_wallet
import io.bhodl.shared.resources.trk_kpi_balance_split
import io.bhodl.shared.resources.trk_kpi_expenses
import io.bhodl.shared.resources.trk_kpi_income
import io.bhodl.shared.resources.trk_kpi_kept
import io.bhodl.shared.resources.trk_kpi_net_savings
import io.bhodl.shared.resources.trk_kpi_no_income
import io.bhodl.shared.resources.trk_kpi_overspent
import io.bhodl.shared.resources.trk_kpi_savings_rate
import io.bhodl.shared.resources.trk_kpi_tx_in_window
import io.bhodl.shared.resources.trk_strip_refresh
import io.bhodl.shared.resources.trk_strip_stale
import io.bhodl.shared.resources.trk_strip_syncing
import io.bhodl.shared.resources.trk_views_none
import io.bhodl.shared.resources.trk_views_save_body
import io.bhodl.shared.resources.trk_views_save_chip
import io.bhodl.shared.resources.trk_views_save_hint
import io.bhodl.shared.resources.trk_views_save_title
import io.bhodl.shared.resources.tracker_kpi_inflow_exceeds
import io.bhodl.shared.resources.tracker_kpi_outflow_exceeds
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
 * Phase 3.x — KPI strip, period/compare controls, drill-down chips,
 * skeleton placeholders, data-state strip and saved-views chips for
 * the Tracking dashboard. Extracted (mechanically) from
 * `TrackingDashboard.kt`; no behaviour change.
 */

/**
 * Phase 3.8 — saved-views bar. Drop-down chip strip with one chip
 * per saved view + a "+ Salva vista" call-to-action that opens an
 * AlertDialog asking for a name. Click a saved-view chip to apply
 * its (period + categoryFilter + timeRangeFilter) snapshot to the
 * current Tracking surface.
 */
@Composable
internal fun SavedViewsBar(
    views: List<io.bhodl.ffi.SavedViewFfi>,
    onApply: (io.bhodl.ffi.SavedViewFfi) -> Unit,
    onSave: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    if (views.isEmpty()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.trk_views_none),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier.weight(1f),
            )
            SaveViewChip(onClick = onSave)
        }
    } else {
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            views.forEach { v ->
                SavedViewChip(view = v, onApply = { onApply(v) }, onDelete = { onDelete(v.id) })
            }
            SaveViewChip(onClick = onSave)
        }
    }
}

@Composable
private fun SavedViewChip(
    view: io.bhodl.ffi.SavedViewFfi,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(14.dp))
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = view.name,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.clickable { onApply() },
        )
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { onDelete() }
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "✕",
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun SaveViewChip(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BhodlTheme.colors.brandPrimary.copy(alpha = 0.18f))
            .border(
                1.dp,
                BhodlTheme.colors.brandPrimary.copy(alpha = 0.4f),
                RoundedCornerShape(14.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(TrackingTags.VIEW_SAVE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.trk_views_save_chip),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.brandPrimary,
        )
    }
}

/** Inline name-prompt dialog used by SaveViewChip. Returns either
 *  the trimmed name on save or null on cancel. */
@Composable
internal fun SaveViewDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) onSave(trimmed)
                },
                enabled = name.trim().isNotEmpty(),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
        title = { Text(stringResource(Res.string.trk_views_save_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.trk_views_save_body),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text(stringResource(Res.string.trk_views_save_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TrackingTags.VIEW_NAME_INPUT),
                )
            }
        },
    )
}

/**
 * Phase 3.6 — Data-state strip shown above the Dashboard / History
 * tab content. Two pieces of info: when did the user last see fresh
 * chain data ("12:34"), and how many of their watch wallets are
 * currently stale (Esplora sync failed during the last reload). A
 * stale count of zero hides the warning chip entirely so the strip
 * stays unobtrusive on the happy path.
 */
@Composable
internal fun DataStateStrip(
    lastSyncUnix: Long?,
    staleCount: Int,
    totalWatchCount: Int,
    onRefresh: () -> Unit,
    refreshing: Boolean,
) {
    val timeFmt = remember {
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    }
    val syncLabel = lastSyncUnix
        ?.let { stringResource(Res.string.common_last_sync, timeFmt.format(Instant.ofEpochSecond(it))) }
        ?: stringResource(Res.string.common_never_synced)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = syncLabel,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (staleCount > 0) {
            StaleChip(staleCount = staleCount, totalWatchCount = totalWatchCount)
        }
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(BhodlTheme.colors.brandPrimary)
                .clickable(enabled = !refreshing) { onRefresh() }
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(TrackingTags.REFRESH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(if (refreshing) Res.string.trk_strip_syncing else Res.string.trk_strip_refresh),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.brandOnPrimary,
            )
        }
    }
}

@Composable
private fun StaleChip(staleCount: Int, totalWatchCount: Int) {
    val errorBg = BhodlTheme.colors.error.copy(alpha = 0.16f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(errorBg)
            .border(1.dp, BhodlTheme.colors.error.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(TrackingTags.STALE_CHIP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.trk_strip_stale, staleCount, totalWatchCount),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.error,
        )
    }
}

/**
 * Phase 3.6 — bar bucket drill-down. Koalaplot 0.9.0 doesn't expose
 * an onClick on `DefaultVerticalBar` (the DSL binds bar appearance
 * once for the whole series, so per-bar callbacks aren't possible
 * without reimplementing the bar layout). Instead we render a
 * tappable chip strip beneath the chart — same labels, with each
 * chip carrying the bucket's `[start, end]` range. Clicking a chip
 * jumps to the History tab pre-filtered by that range.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun BucketDrillRow(
    bars: List<FlowBar>,
    onSelect: (Pair<Long, Long>) -> Unit,
) {
    val unit = deriveBarUnit(bars)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        bars.forEachIndexed { i, b ->
            val end = if (i == bars.lastIndex) {
                System.currentTimeMillis() / 1000L
            } else {
                bars[i + 1].bucketStartUnix - 1L
            }
            val hasFlow = b.inflowSat > 0u || b.outflowSat > 0u
            val bg = if (hasFlow) BhodlTheme.colors.surface
            else BhodlTheme.colors.surface.copy(alpha = 0.5f)
            val fg = if (hasFlow) BhodlTheme.colors.textPrimary
            else BhodlTheme.colors.textTertiary
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(bg)
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(14.dp))
                    .clickable(enabled = hasFlow) {
                        onSelect(b.bucketStartUnix to end)
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimestampTick(b.bucketStartUnix, unit),
                    style = BhodlTheme.typography.labelMedium,
                    color = fg,
                )
            }
        }
    }
}

/**
 * Phase 3.6 — shimmer-style skeleton block. Used as a placeholder
 * while the registry reload is in flight, so the layout doesn't
 * jump from "Loading…" text to populated cards in one frame.
 */
@Composable
internal fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 16.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 6.dp,
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton-alpha",
    )
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(cornerRadius))
            .background(BhodlTheme.colors.outline.copy(alpha = alpha)),
    )
}

/** Stacked skeleton mimicking a row in the History list. */
@Composable
internal fun SkeletonRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.6f), height = 14.dp)
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.4f), height = 12.dp)
        SkeletonBlock(modifier = Modifier.fillMaxWidth(0.8f), height = 12.dp)
    }
}

@Composable
internal fun PeriodSelector(
    selected: TrackingPeriod,
    onSelect: (TrackingPeriod) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackingPeriod.entries.forEach { p ->
            val isSel = p == selected
            val bg = if (isSel) BhodlTheme.colors.brandPrimary
            else BhodlTheme.colors.surface
            val fg = if (isSel) BhodlTheme.colors.brandOnPrimary
            else BhodlTheme.colors.textPrimary
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(bg)
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(20.dp))
                    .clickable { onSelect(p) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .testTag("${TrackingTags.PERIOD_PREFIX}${p.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = p.label,
                    style = BhodlTheme.typography.labelMedium,
                    color = fg,
                )
            }
        }
    }
}

@Composable
internal fun CompareToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val bg = if (checked) BhodlTheme.colors.brandPrimary.copy(alpha = 0.15f)
    else BhodlTheme.colors.surface
    val fg = if (checked) BhodlTheme.colors.brandPrimary
    else BhodlTheme.colors.textSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(16.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(TrackingTags.COMPARE_TOGGLE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (checked) "✓ " + stringResource(Res.string.trk_compare_prev)
            else stringResource(Res.string.trk_compare_prev),
            style = BhodlTheme.typography.labelMedium,
            color = fg,
        )
    }
}

/**
 * UX review 2026-10 (B14) — text that ellipsizes when its slot is too
 * narrow and shows the full string in a hover tooltip, so a clipped KPI
 * label is still readable.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EllipsizedWithTooltip(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    maxLines: Int = 1,
    modifier: Modifier = Modifier,
) {
    TooltipArea(
        tooltip = { HomeTooltip(text) },
        delayMillis = 400,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
