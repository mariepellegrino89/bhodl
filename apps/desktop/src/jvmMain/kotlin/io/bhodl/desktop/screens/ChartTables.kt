package io.bhodl.desktop.screens

import androidx.compose.runtime.Composable
import io.bhodl.desktop.ui.ChartTableColumn
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.chart_col_amount
import io.bhodl.shared.resources.chart_col_balance
import io.bhodl.shared.resources.chart_col_category
import io.bhodl.shared.resources.chart_col_date
import io.bhodl.shared.resources.chart_col_direction
import io.bhodl.shared.resources.chart_col_from
import io.bhodl.shared.resources.chart_col_inflow
import io.bhodl.shared.resources.chart_col_net_worth
import io.bhodl.shared.resources.chart_col_outflow
import io.bhodl.shared.resources.chart_col_period
import io.bhodl.shared.resources.chart_col_share
import io.bhodl.shared.resources.chart_col_to
import io.bhodl.shared.resources.chart_dir_incoming
import io.bhodl.shared.resources.chart_dir_outgoing
import org.jetbrains.compose.resources.stringResource
import io.bhodl.desktop.ui.ChartTableData
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * UX review 2026-10 (item 15) — pure builders of the table view of
 * every chart. They take the chart's own data plus an amount formatter
 * (`rememberAmountFormatter()` at the call site, so unit, BIP177 and
 * the privacy mask apply) and the localized column titles.
 */

/** Localized column titles, resolved once by the caller. */
internal data class ChartColumnTitles(
    val period: String,
    val inflow: String,
    val outflow: String,
    val category: String,
    val amount: String,
    val share: String,
    val date: String,
    val balance: String,
    val netWorth: String,
    val from: String,
    val to: String,
    val direction: String,
    val incoming: String,
    val outgoing: String,
)

@Composable
internal fun chartColumnTitles(): ChartColumnTitles = ChartColumnTitles(
    period = stringResource(Res.string.chart_col_period),
    inflow = stringResource(Res.string.chart_col_inflow),
    outflow = stringResource(Res.string.chart_col_outflow),
    category = stringResource(Res.string.chart_col_category),
    amount = stringResource(Res.string.chart_col_amount),
    share = stringResource(Res.string.chart_col_share),
    date = stringResource(Res.string.chart_col_date),
    balance = stringResource(Res.string.chart_col_balance),
    netWorth = stringResource(Res.string.chart_col_net_worth),
    from = stringResource(Res.string.chart_col_from),
    to = stringResource(Res.string.chart_col_to),
    direction = stringResource(Res.string.chart_col_direction),
    incoming = stringResource(Res.string.chart_dir_incoming),
    outgoing = stringResource(Res.string.chart_dir_outgoing),
)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

private fun day(unix: Long): String = DATE.format(Instant.ofEpochSecond(unix))

private fun pct(part: Long, total: Long): String =
    if (total <= 0L) "—" else "${(part.toDouble() / total * 100).toInt()}%"

/** Inflow / outflow per period (home chart and the Reports bar chart). */
internal fun inflowOutflowTable(
    rows: List<Triple<String, Long, Long>>,
    t: ChartColumnTitles,
    fmt: (Long) -> String,
): ChartTableData = ChartTableData(
    columns = listOf(
        ChartTableColumn(t.period),
        ChartTableColumn(t.inflow, numeric = true),
        ChartTableColumn(t.outflow, numeric = true),
    ),
    rows = rows.map { (label, inSat, outSat) -> listOf(label, fmt(inSat), fmt(outSat)) },
)

/** Composition charts (net-worth donut, category donut / treemap). */
internal fun shareTable(
    slices: List<Pair<String, Long>>,
    t: ChartColumnTitles,
    fmt: (Long) -> String,
): ChartTableData {
    val total = slices.sumOf { it.second }
    return ChartTableData(
        columns = listOf(
            ChartTableColumn(t.category),
            ChartTableColumn(t.amount, numeric = true),
            ChartTableColumn(t.share, numeric = true),
        ),
        rows = slices.map { (label, sat) -> listOf(label, fmt(sat), pct(sat, total)) },
    )
}

/** Sankey: one row per ribbon (income category → total, total → expense / saved). */
internal fun sankeyTable(
    flow: SankeyFlow,
    centerLabel: String,
    savedLabel: String,
    t: ChartColumnTitles,
    fmt: (Long) -> String,
): ChartTableData = ChartTableData(
    columns = listOf(
        ChartTableColumn(t.from),
        ChartTableColumn(t.to),
        ChartTableColumn(t.amount, numeric = true),
    ),
    rows = flow.incomeCategories.map { listOf(it.label, centerLabel, fmt(it.amountSat.toLong())) } +
        flow.expenseCategories.map { listOf(centerLabel, it.label, fmt(it.amountSat.toLong())) } +
        (if (flow.savedSat > 0UL) listOf(listOf(centerLabel, savedLabel, fmt(flow.savedSat.toLong()))) else emptyList()),
)

/** Activity scatter: every confirmed event, newest first. */
internal fun activityTable(
    events: List<PaymentEvent>,
    t: ChartColumnTitles,
    fmt: (Long) -> String,
): ChartTableData = ChartTableData(
    columns = listOf(
        ChartTableColumn(t.date),
        ChartTableColumn(t.direction),
        ChartTableColumn(t.amount, numeric = true),
    ),
    rows = events
        .filter { it.timestampUnix != null && it.amountSat > 0UL }
        .sortedByDescending { it.timestampUnix!!.toLong() }
        .map { e ->
            listOf(
                day(e.timestampUnix!!.toLong()),
                when (e.direction) {
                    PaymentDirection.INCOMING -> t.incoming
                    PaymentDirection.OUTGOING -> t.outgoing
                    PaymentDirection.SELF_TRANSFER -> "↔"
                },
                fmt(e.amountSat.toLong()),
            )
        },
)

/** Time series (balance line, net-worth sparkline): one row per point. */
internal fun seriesTable(
    points: List<Pair<Long, Long>>,
    valueTitle: String,
    t: ChartColumnTitles,
    fmt: (Long) -> String,
): ChartTableData = ChartTableData(
    columns = listOf(ChartTableColumn(t.date), ChartTableColumn(valueTitle, numeric = true)),
    rows = points.map { (ts, sat) -> listOf(day(ts), fmt(sat)) },
)
