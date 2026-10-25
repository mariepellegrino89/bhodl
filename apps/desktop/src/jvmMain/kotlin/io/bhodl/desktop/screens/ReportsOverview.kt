package io.bhodl.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.desktop.ui.HelpTip
import io.bhodl.desktop.ui.amountSign
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.signedAmountColor
import io.bhodl.ffi.PaymentEvent
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.rpo_bars
import io.bhodl.shared.resources.rpo_bars_help
import io.bhodl.shared.resources.rpo_current
import io.bhodl.shared.resources.rpo_donut
import io.bhodl.shared.resources.rpo_donut_help
import io.bhodl.shared.resources.rpo_dropped
import io.bhodl.shared.resources.rpo_expenses
import io.bhodl.shared.resources.rpo_grew
import io.bhodl.shared.resources.rpo_h_expenses
import io.bhodl.shared.resources.rpo_h_income
import io.bhodl.shared.resources.rpo_h_net
import io.bhodl.shared.resources.rpo_h_saved
import io.bhodl.shared.resources.rpo_income
import io.bhodl.shared.resources.rpo_insight_none
import io.bhodl.shared.resources.rpo_net
import io.bhodl.shared.resources.rpo_prev_value
import io.bhodl.shared.resources.rpo_previous
import io.bhodl.shared.resources.rpo_saved
import io.bhodl.shared.resources.rpo_top
import io.bhodl.shared.resources.rpo_total
import io.bhodl.shared.resources.rpo_vs_previous
import io.bhodl.shared.resources.txc_uncategorized
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/*
 * Reports redesign 2026-10 (proposal "A + C") — totals, three insights,
 * the outflows donut next to paired bars (this period vs the previous
 * one), all from one list of category outflows so the numbers agree.
 */

internal object ReportsOverviewTags {
    const val TOTALS: String = "reports.totals"
    const val INSIGHTS: String = "reports.insights"
    const val DONUT: String = "reports.donut"
    const val BARS: String = "reports.bars"
    fun legend(category: String?): String = "reports.legend.${category ?: "none"}"
}

/** Outflow of one category now and in the previous window. */
internal data class CategoryCompare(val category: String?, val nowSat: Long, val prevSat: Long?) {
    /** Change in percent; null without a previous window or from zero. */
    val changePct: Double? get() = prevSat?.takeIf { it > 0L }?.let { (nowSat - it) * 100.0 / it }
}

/** Outflows by category for [events], compared with [previous] (null = no previous window). */
internal fun compareCategories(events: List<PaymentEvent>, previous: List<PaymentEvent>?): List<CategoryCompare> {
    val now = outflowByCategory(events).toMap()
    val prev = previous?.let { outflowByCategory(it).toMap() }
    return now.keys.map { c -> CategoryCompare(c, now.getValue(c), prev?.let { it[c] ?: 0L }) }
        .sortedByDescending { it.nowSat }
}

/** Income, expenses, net and kept share of a period. */
internal data class PeriodTotals(val inSat: Long, val outSat: Long) {
    val netSat: Long get() = inSat - outSat
    val savedPct: Double? get() = if (inSat > 0L) netSat * 100.0 / inSat else null
}

internal fun periodTotals(events: List<PaymentEvent>): PeriodTotals {
    val unique = events.distinctBy { it.txidOrPaymentId to it.walletId }
    return PeriodTotals(unique.sumOf { it.externalInflowSat().toLong() }, unique.sumOf { it.externalOutflowSat().toLong() })
}

/** The three insights: biggest rise, biggest drop (vs previous), top category share. */
internal data class ReportInsights(val grew: CategoryCompare?, val dropped: CategoryCompare?, val top: CategoryCompare?, val topSharePct: Double?)

internal fun reportInsights(rows: List<CategoryCompare>): ReportInsights {
    val total = rows.sumOf { it.nowSat }
    val withChange = rows.filter { it.changePct != null }
    return ReportInsights(
        grew = withChange.filter { it.changePct!! > 0.0 }.maxByOrNull { it.changePct!! },
        dropped = withChange.filter { it.changePct!! < 0.0 }.minByOrNull { it.changePct!! },
        top = rows.firstOrNull(),
        topSharePct = rows.firstOrNull()?.takeIf { total > 0L }?.let { it.nowSat * 100.0 / total },
    )
}

private fun pctText(p: Double, signed: Boolean = true): String =
    (if (signed) amountSign(p, masked = false) else "") + "${Math.round(kotlin.math.abs(p))}%"

@Composable
private fun Title(text: String, help: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text.uppercase(), style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = BhodlTheme.colors.textSecondary)
        HelpTip(help)
    }
}

@Composable
private fun Tile(label: String, help: String, value: String, color: Color, sub: String?, modifier: Modifier) {
    Column(modifier.bhodlCard().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Title(label, help)
        Text(value, style = BhodlTheme.typography.amountMedium, color = color, maxLines = 1)
        sub?.let { Text(it, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary) }
    }
}

/** Four totals with the change vs the previous window. */
@Composable
internal fun ReportTotalsRow(now: PeriodTotals, prev: PeriodTotals?) {
    val prevLabel = stringResource(Res.string.rpo_vs_previous)
    fun change(a: Long, b: Long?): String? = b?.takeIf { it > 0L }?.let { pctText((a - it) * 100.0 / it) + " " + prevLabel }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag(ReportsOverviewTags.TOTALS), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile(stringResource(Res.string.rpo_expenses), stringResource(Res.string.rpo_h_expenses), displayAmount(now.outSat), BhodlTheme.colors.financialNegative,
            change(now.outSat, prev?.outSat), Modifier.weight(1f).fillMaxHeight())
        Tile(stringResource(Res.string.rpo_income), stringResource(Res.string.rpo_h_income), displayAmount(now.inSat), BhodlTheme.colors.financialPositive,
            change(now.inSat, prev?.inSat), Modifier.weight(1f).fillMaxHeight())
        Tile(stringResource(Res.string.rpo_net), stringResource(Res.string.rpo_h_net), amountSign(now.netSat) + displayAmount(kotlin.math.abs(now.netSat)),
            signedAmountColor(now.netSat.toDouble(), BhodlTheme.colors.financialPositive, BhodlTheme.colors.financialNegative, BhodlTheme.colors.textPrimary),
            null, Modifier.weight(1f).fillMaxHeight())
        Tile(stringResource(Res.string.rpo_saved), stringResource(Res.string.rpo_h_saved), now.savedPct?.let { pctText(it, signed = false) } ?: "—", BhodlTheme.colors.textPrimary,
            prev?.savedPct?.let { stringResource(Res.string.rpo_prev_value, pctText(it, signed = false)) }, Modifier.weight(1f).fillMaxHeight())
    }
}

/** One-line insights strip. */
@Composable
internal fun ReportInsightsRow(insights: ReportInsights) {
    val none = stringResource(Res.string.txc_uncategorized)
    fun name(c: CategoryCompare) = c.category ?: none
    Row(Modifier.fillMaxWidth().testTag(ReportsOverviewTags.INSIGHTS), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Insight(stringResource(Res.string.rpo_grew), insights.grew?.let { "${name(it)} ${pctText(it.changePct!!)}" }, BhodlTheme.colors.financialNegative, Modifier.weight(1f))
        Insight(stringResource(Res.string.rpo_dropped), insights.dropped?.let { "${name(it)} ${pctText(it.changePct!!)}" }, BhodlTheme.colors.financialPositive, Modifier.weight(1f))
        Insight(stringResource(Res.string.rpo_top), insights.top?.let { "${name(it)} · ${pctText(insights.topSharePct ?: 0.0, signed = false)}" }, BhodlTheme.colors.textPrimary, Modifier.weight(1f))
    }
}

@Composable
private fun Insight(label: String, value: String?, color: Color, modifier: Modifier) {
    Column(modifier.bhodlCard().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
        Text(value ?: stringResource(Res.string.rpo_insight_none), style = BhodlTheme.typography.bodyMedium, color = if (value == null) BhodlTheme.colors.textTertiary else color)
    }
}

/** Outflows donut (one slice per category) with a clickable legend. */
@Composable
internal fun OutflowDonutCard(rows: List<CategoryCompare>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier) {
    val total = rows.sumOf { it.nowSat }.coerceAtLeast(1L)
    val none = stringResource(Res.string.txc_uncategorized)
    val colors = rows.map { categoryColor(it.category) }
    Column(modifier.bhodlCard().padding(16.dp).testTag(ReportsOverviewTags.DONUT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Title(stringResource(Res.string.rpo_donut), stringResource(Res.string.rpo_donut_help))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            val desc = rows.joinToString { "${it.category ?: none} ${Math.round(it.nowSat * 100.0 / total)}%" }
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(170.dp).semantics { contentDescription = desc }) {
                Canvas(Modifier.size(170.dp)) {
                    val stroke = 26.dp.toPx()
                    val inset = stroke / 2
                    val box = Size(size.width - stroke, size.height - stroke)
                    var start = -90f
                    rows.forEachIndexed { i, r ->
                        val sweep = 360f * r.nowSat / total
                        val dim = selected != null && r.category != selected
                        drawArc(colors[i].copy(alpha = if (dim) 0.25f else 1f), start, (sweep - 1.2f).coerceAtLeast(0.5f), false, Offset(inset, inset), box, style = Stroke(stroke))
                        start += sweep
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(displayAmount(rows.sumOf { it.nowSat }), style = BhodlTheme.typography.amountSmall.copy(fontWeight = FontWeight.SemiBold), color = BhodlTheme.colors.textPrimary)
                    Text(stringResource(Res.string.rpo_total), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                rows.forEachIndexed { i, r ->
                    val on = selected == r.category
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) colors[i].copy(alpha = 0.14f) else Color.Transparent)
                            .clickable(role = Role.Button) { onSelect(if (on) null else r.category) }
                            .padding(horizontal = 6.dp)
                            .alpha(if (selected != null && !on) 0.55f else 1f)
                            .testTag(ReportsOverviewTags.legend(r.category)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(colors[i]))
                        Text(r.category ?: none, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(displayAmount(r.nowSat), style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textSecondary)
                        Text("${Math.round(r.nowSat * 100.0 / total)}%", style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textTertiary, modifier = Modifier.width(40.dp))
                    }
                }
            }
        }
    }
}

/** Paired bars: this period (thick) vs the previous one (thin), with the change. */
@Composable
internal fun PairedBarsCard(rows: List<CategoryCompare>, selected: String?, modifier: Modifier) {
    val max = rows.maxOfOrNull { maxOf(it.nowSat, it.prevSat ?: 0L) }?.coerceAtLeast(1L) ?: 1L
    val none = stringResource(Res.string.txc_uncategorized)
    Column(modifier.bhodlCard().padding(16.dp).testTag(ReportsOverviewTags.BARS), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title(stringResource(Res.string.rpo_bars), stringResource(Res.string.rpo_bars_help))
        rows.forEach { r ->
            val color = categoryColor(r.category)
            val dim = selected != null && r.category != selected
            Row(Modifier.fillMaxWidth().alpha(if (dim) 0.4f else 1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(r.category ?: none, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
                        Box(Modifier.width(maxWidth * (r.nowSat.toFloat() / max)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
                    }
                    r.prevSat?.let { p ->
                        BoxWithConstraints(Modifier.fillMaxWidth().height(5.dp)) {
                            Box(Modifier.width(maxWidth * (p.toFloat() / max)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(BhodlTheme.colors.textTertiary))
                        }
                    }
                }
                Text(
                    r.changePct?.let { pctText(it) } ?: "—",
                    style = BhodlTheme.typography.mono,
                    color = when {
                        r.changePct == null -> BhodlTheme.colors.textTertiary
                        else -> signedAmountColor(-r.changePct!!, BhodlTheme.colors.financialPositive, BhodlTheme.colors.financialNegative, BhodlTheme.colors.textSecondary)
                    },
                    modifier = Modifier.width(56.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(14.dp).height(8.dp).clip(RoundedCornerShape(2.dp)).background(BhodlTheme.colors.brandPrimary))
                Text(stringResource(Res.string.rpo_current), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(14.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(BhodlTheme.colors.textTertiary))
                Text(stringResource(Res.string.rpo_previous), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
            }
        }
    }
}
