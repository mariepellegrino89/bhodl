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
// NW Breakdown Donut
// ─────────────────────────────────────────────────────────────────────

data class NwSlice(
    val label: String,
    val sat: ULong,
    val color: Color,
)

@Composable
internal fun NwBreakdownDonut(
    /** UX review 2026-10 (B1) — the shared [TrackerNetWorth]. Slices
     *  show the gross composition (assets and liabilities); the centre
     *  shows the NET worth, the same number as the hero. */
    netWorth: TrackerNetWorth,
    modifier: Modifier = Modifier,
) {
    val walletSat = netWorth.walletSat
    val investmentsSat = netWorth.investmentsSat
    val manualAssetsSat = netWorth.manualAssetsSat
    val liabilitiesSat = netWorth.liabilitiesSat
    val roles = nwRoleColors()
    val fmtAmount = rememberAmountFormatter()
    val lblWallets = stringResource(Res.string.trk_nw_slice_wallets)
    val lblInvestments = stringResource(Res.string.trk_nw_slice_investments)
    val lblManual = stringResource(Res.string.trk_nw_slice_manual)
    val lblLiabilities = stringResource(Res.string.trk_nw_slice_liabilities)
    val slices = remember(walletSat, investmentsSat, manualAssetsSat, liabilitiesSat, roles, lblWallets) {
        buildList {
            if (walletSat > 0UL) add(NwSlice(lblWallets, walletSat, roles.btcWallets))
            if (investmentsSat > 0UL) add(NwSlice(lblInvestments, investmentsSat, roles.investments))
            if (manualAssetsSat > 0UL) add(NwSlice(lblManual, manualAssetsSat, roles.manualAssets))
            if (liabilitiesSat > 0UL) add(NwSlice(lblLiabilities, liabilitiesSat, roles.liabilities))
        }
    }

    if (slices.isEmpty()) return

    val totalSat = slices.sumOf { it.sat.toLong() }.coerceAtLeast(1L)
    // UX review 2026-10 (item 15) — table view of the donut.
    var showTable by remember { mutableStateOf(false) }
    val fmt = rememberAmountFormatter()
    val table = shareTable(slices.map { it.label to it.sat.toLong() }, chartColumnTitles(), fmt)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(16.dp)
            .testTag(CoreChartsTags.NW_DONUT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChartHeader(title = stringResource(Res.string.trk_nw_breakdown_title), showTable = showTable, onToggle = { showTable = !showTable })
        if (showTable) {
            ChartDataTable(table)
            return@Column
        }

        // B14 — ring sized so the centre label (up to "-21.00000000 BTC")
        // fits inside the hole instead of spilling over the slices.
        Box(
            modifier = Modifier.size(200.dp).chartSemantics(stringResource(Res.string.chart_name_breakdown)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(200.dp)) {
                val strokeWidth = 22.dp.toPx()
                val diameter = size.minDimension - strokeWidth
                val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)
                val arcSize = Size(diameter, diameter)

                var startAngle = -90f
                for (slice in slices) {
                    val sweep = (slice.sat.toFloat() / totalSat.toFloat()) * 360f
                    drawArc(
                        color = slice.color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    )
                    startAngle += sweep
                }
            }
            Column(
                modifier = Modifier.widthIn(max = 148.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val nw = netWorth.netWorthSat
                val nwText = io.bhodl.desktop.ui.amountSign(nw, plusForPositive = false) + fmtAmount(kotlin.math.abs(nw))
                Text(
                    text = nwText,
                    style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag(CoreChartsTags.NW_DONUT_CENTER),
                )
                Text(
                    text = stringResource(Res.string.tracker_net_worth_label),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
        }

        // Legend
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (slice in slices) {
                val pct = (slice.sat.toDouble() / totalSat.toDouble() * 100).toInt()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(slice.color),
                    )
                    Text(
                        text = "${slice.label} ($pct%)",
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────
// Inflow / Outflow Bar Chart + Totals
// ─────────────────────────────────────────────────────────────────────

internal data class MonthlyFlow(
    val label: String,
    val incomingSat: ULong,
    val outgoingSat: ULong,
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

        // Month labels (first, middle, last)
        if (months.size >= 2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(months.first().label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
                if (months.size >= 3) {
                    Text(months[months.size / 2].label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
                }
                Text(months.last().label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
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
    return sorted.map { (ym, evs) ->
        val incoming = evs.sumOf { it.externalInflowSat() }
        val outgoing = evs.sumOf { it.externalOutflowSat() }
        MonthlyFlow(
            label = "${ym.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())} ${ym.year % 100}",
            incomingSat = incoming,
            outgoingSat = outgoing,
        )
    }
}

internal object CoreChartsTags {
    const val NW_DONUT: String = "core-charts.nw-donut"
    const val NW_DONUT_CENTER: String = "core-charts.nw-donut.center"
    const val INFLOW_OUTFLOW: String = "core-charts.inflow-outflow"
}
