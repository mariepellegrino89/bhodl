package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.desktop.ui.ChartDataTable
import io.bhodl.desktop.ui.ChartTableToggleButton
import io.bhodl.desktop.ui.chartSemantics
import io.bhodl.shared.resources.chart_name_breakdown
import io.bhodl.shared.resources.chart_name_inflow_outflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_net_worth_label
import io.bhodl.shared.resources.trk_incoming
import io.bhodl.shared.resources.trk_inflow
import io.bhodl.shared.resources.trk_inout_title
import io.bhodl.shared.resources.trk_nw_breakdown_title
import io.bhodl.shared.resources.trk_nw_slice_investments
import io.bhodl.shared.resources.trk_nw_slice_liabilities
import io.bhodl.shared.resources.trk_nw_slice_manual
import io.bhodl.shared.resources.trk_nw_slice_wallets
import io.bhodl.shared.resources.trk_outflow
import io.bhodl.shared.resources.trk_outgoing
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Core charts always visible on the tracker home regardless of module
 * state. Lightweight Canvas-based rendering (no Koalaplot dependency).
 */

/**
 * Phase 9.1 (aesthetic unification) — theme-driven colors for the four
 * net-worth roles, shared by the breakdown donut and the Asset
 * Portfolio rollup. Replaces the hardcoded Material-Android hexes
 * (#4CAF50/#2196F3/#F44336) that bypassed both the Okabe-Ito
 * categorical palette and the financial semantic layer — the old
 * donut red for "Liabilities" was a different red from the theme's
 * loss red, a semantic collision.
 *
 * Role mapping: BTC = brand orange (the one place brand-on-data is
 * meaningful); investments / manual assets = Okabe-Ito categorical
 * entries (colorblind-safe, no green/red verdict implied by a mere
 * category); liabilities = the theme's financialNegative (it IS a
 * verdict).
 */
internal data class NwRoleColors(
    val btcWallets: Color,
    val investments: Color,
    val manualAssets: Color,
    val liabilities: Color,
)

@Composable
internal fun nwRoleColors(): NwRoleColors {
    val cat = BhodlTheme.colors.chartCategorical
    return NwRoleColors(
        btcWallets = BhodlTheme.colors.brandPrimary,
        investments = cat.getOrElse(1) { BhodlTheme.colors.financialPositive },
        manualAssets = cat.getOrElse(0) { BhodlTheme.colors.accentNeon },
        liabilities = BhodlTheme.colors.financialNegative,
    )
}

// ─────────────────────────────────────────────────────────────────────
// Inflow / Outflow Bar Chart + Totals
// ─────────────────────────────────────────────────────────────────────

internal data class MonthlyFlow(
    val label: String,
    val incomingSat: ULong,
    val outgoingSat: ULong,
    /** Short month name under its bars ("ott"). */
    val monthLabel: String = label,
    /** The year under the month, on the first bar and on every January; null otherwise. */
    val yearLabel: String? = null,
)

@Composable
internal fun InflowOutflowChart(
    events: List<PaymentEvent>,
    modifier: Modifier = Modifier,
) {
    val months = remember(events) { computeMonthlyFlows(events) }
    if (months.isEmpty()) return

    val totalIn = months.sumOf { it.incomingSat.toLong() }.toULong()
    val totalOut = months.sumOf { it.outgoingSat.toLong() }.toULong()
    val maxVal = months.maxOf { maxOf(it.incomingSat, it.outgoingSat) }
        .toFloat().coerceAtLeast(1f)
    // Both totals sit side by side: one unit for the pair (Auto never
    // shows one in sats and the other in BTC).
    val totalsFmt = rememberColumnAmountFormat(listOf(totalIn.toLong(), totalOut.toLong()))
    var showTable by remember { mutableStateOf(false) }
    val fmt = rememberAmountFormatter()
    val table = inflowOutflowTable(
        months.map { Triple(it.label, it.incomingSat.toLong(), it.outgoingSat.toLong()) },
        chartColumnTitles(),
        fmt,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(16.dp)
            .testTag(CoreChartsTags.INFLOW_OUTFLOW),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChartHeader(title = stringResource(Res.string.trk_inout_title), showTable = showTable, onToggle = { showTable = !showTable })
        if (showTable) {
            ChartDataTable(table)
            return@Column
        }

        // Bar chart area — bars carry the same financial semantic
        // green/red as every other money surface (theme layer, not
        // hardcoded Material hexes).
        val inColor = BhodlTheme.colors.financialPositive
        val outColor = BhodlTheme.colors.financialNegative
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .chartSemantics(stringResource(Res.string.chart_name_inflow_outflow)),
        ) {
            val barCount = months.size
            val groupWidth = size.width / barCount
            val barWidth = (groupWidth * 0.35f).coerceAtMost(24.dp.toPx())
            val gap = 2.dp.toPx()

            months.forEachIndexed { idx, month ->
                val cx = groupWidth * idx + groupWidth / 2f

                // Incoming bar (left)
                val inHeight = (month.incomingSat.toFloat() / maxVal) * size.height * 0.9f
                drawRect(
                    color = inColor,
                    topLeft = Offset(cx - barWidth - gap / 2f, size.height - inHeight),
                    size = Size(barWidth, inHeight),
                )

                // Outgoing bar (right)
                val outHeight = (month.outgoingSat.toFloat() / maxVal) * size.height * 0.9f
                drawRect(
                    color = outColor,
                    topLeft = Offset(cx + gap / 2f, size.height - outHeight),
                    size = Size(barWidth, outHeight),
                )
            }
        }

        // One label under every pair of bars: the month, and the year on
        // the first bar and on each January.
        Row(modifier = Modifier.fillMaxWidth().testTag(CoreChartsTags.INFLOW_OUTFLOW_MONTHS)) {
            months.forEach { m ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        m.monthLabel,
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Clip,
                    )
                    Text(
                        m.yearLabel.orEmpty(),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        // Totals summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(Res.string.trk_inflow), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
                Text(
                    text = "+${totalsFmt.full(totalIn.toLong())}",
                    style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.financialPositive,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(Res.string.trk_outflow), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
                Text(
                    text = "−${totalsFmt.full(totalOut.toLong())}",
                    style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.financialNegative,
                )
            }
        }

        // Legend
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(inColor))
                Text(stringResource(Res.string.trk_incoming), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(outColor))
                Text(stringResource(Res.string.trk_outgoing), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
            }
        }
    }
}

/** Card title + the chart ↔ table toggle (item 15). */
@Composable
private fun ChartHeader(title: String, showTable: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ChartTableToggleButton(showTable = showTable, onToggle = onToggle)
    }
}

/**
 * Monthly external inflow / outflow (GAP-010): months are UTC calendar
 * months like every other tracker aggregate, and internal transfers are
 * neither income nor spending (a send_to_self counts only its fee).
 */
internal fun computeMonthlyFlows(
    events: List<PaymentEvent>,
    zone: ZoneId = java.time.ZoneOffset.UTC,
): List<MonthlyFlow> {
    if (events.isEmpty()) return emptyList()
    val byMonth = events
        .filter { it.timestampUnix != null }
        .groupBy { ev ->
            val instant = Instant.ofEpochSecond(ev.timestampUnix!!.toLong())
            YearMonth.from(instant.atZone(zone))
        }
    if (byMonth.isEmpty()) return emptyList()
    val sorted = byMonth.toSortedMap()
    val first = sorted.firstKey()
    // Every month from the first to the last, empty ones included: the
    // axis is continuous and each label sits under its own bars.
    val all = generateSequence(first) { it.plusMonths(1) }.takeWhile { it <= sorted.lastKey() }
        .associateWith { sorted[it].orEmpty() }
    return all.map { (ym, evs) ->
        val incoming = evs.sumOf { it.externalInflowSat() }
        val outgoing = evs.sumOf { it.externalOutflowSat() }
        val name = ym.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        MonthlyFlow(
            label = "$name ${ym.year % 100}",
            incomingSat = incoming,
            outgoingSat = outgoing,
            monthLabel = name,
            yearLabel = if (ym == first || ym.monthValue == 1) ym.year.toString() else null,
        )
    }
}

internal object CoreChartsTags {
    const val NW_DONUT: String = "core-charts.nw-donut"
    const val NW_DONUT_CENTER: String = "core-charts.nw-donut.center"
    const val INFLOW_OUTFLOW: String = "core-charts.inflow-outflow"
    const val INFLOW_OUTFLOW_MONTHS: String = "core-charts.inflow-outflow.months"
}
