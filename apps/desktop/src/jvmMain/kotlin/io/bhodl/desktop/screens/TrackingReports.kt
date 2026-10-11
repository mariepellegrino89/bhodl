package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import io.bhodl.desktop.ui.ColumnAmountFormat
import io.bhodl.desktop.ui.headerWithUnit
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_uncategorised
import io.bhodl.shared.resources.period_all
import io.bhodl.shared.resources.tracker_reports_by_category
import io.bhodl.shared.resources.tracker_reports_by_wallet
import io.bhodl.shared.resources.tracker_reports_col_balance
import io.bhodl.shared.resources.tracker_reports_col_category
import io.bhodl.shared.resources.tracker_reports_col_in
import io.bhodl.shared.resources.tracker_reports_col_last
import io.bhodl.shared.resources.tracker_reports_col_net
import io.bhodl.shared.resources.tracker_reports_col_out
import io.bhodl.shared.resources.tracker_reports_col_wallet
import io.bhodl.shared.resources.tracker_reports_counterparties_note
import io.bhodl.shared.resources.tracker_reports_empty_period
import io.bhodl.shared.resources.tracker_reports_empty_wallets
import io.bhodl.shared.resources.tracker_reports_top_counterparties
import org.jetbrains.compose.resources.stringResource
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ui.theme.BhodlTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Phase 3.7 — Reports tab. Two sortable tables:
 *
 * 1. By category: count, in, out, net, % of total, last activity.
 * 2. By wallet:   count, balance, in, out, last activity.
 *
 * The category table is the same data the donut shows but in
 * tabular form, sortable on every column. The wallet table is the
 * cross-wallet equivalent — a quick way to spot which wallet has
 * been active.
 */
@Composable
internal fun ReportsTabBody(
    events: List<PaymentEvent>,
    /** Phase 3.x — pre-grouped wallets so the "By wallet" table
     *  collapses each signing wallet with its mirror into a single
     *  row (manual testing, 2026-05-16: a wallet that's both signing and
     *  tracked must not appear twice). */
    groupedWallets: List<GroupedWallet>,
    /** Phase 9.0 — the global period now scopes the report tables
     *  too (the caller pre-filters [events]); the label makes the
     *  active window explicit in each table title. */
    periodLabel: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1200.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionTitle(
            stringResource(
                Res.string.tracker_reports_by_category,
                periodLabel ?: stringResource(Res.string.period_all),
            ),
        )
        CategoryReportTable(events = events)

        SectionTitle(stringResource(Res.string.tracker_reports_by_wallet))
        WalletReportTable(
            events = events,
            groupedWallets = groupedWallets,
        )

        // Counterparties placeholder — labels are wallet-PIN
        // encrypted (BIP-329 lives behind the per-wallet password),
        // so a true cross-wallet counterparty rollup would need
        // either a registry-side label store (Phase 3.9+) or each
        // wallet to be unlocked first. Stub kept here to prime
        // the user's expectation.
        SectionTitle(stringResource(Res.string.tracker_reports_top_counterparties))
        Text(
            text = stringResource(Res.string.tracker_reports_counterparties_note),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = BhodlTheme.typography.labelMedium,
        color = BhodlTheme.colors.textSecondary,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

private enum class CatSort { CATEGORY, COUNT, IN, OUT, NET, PERCENT, LAST }
private enum class WalSort { LABEL, COUNT, BALANCE, IN, OUT, LAST }

private data class CatRow(
    val category: String,
    val count: Int,
    val inSat: ULong,
    val outSat: ULong,
    val lastTs: Long?,
) {
    val netSat: Long get() = inSat.toLong() - outSat.toLong()
    val volumeSat: ULong get() = inSat + outSat
}

private data class WalRow(
    val groupKey: String,
    val label: String,
    val kindLabel: String,
    val count: Int,
    val balanceSat: ULong?,
    val inSat: ULong,
    val outSat: ULong,
    val lastTs: Long?,
)

@Composable
private fun CategoryReportTable(events: List<PaymentEvent>) {
    var sort by remember { mutableStateOf(CatSort.NET) }
    var asc by remember { mutableStateOf(false) }

    val rows = remember(events) {
        events.groupBy { it.category?.takeIf(String::isNotBlank) ?: "" }
            .map { (cat, evs) ->
                val inSat = evs.filter { it.direction == PaymentDirection.INCOMING }
                    .sumOf { it.amountSat }
                val outSat = evs.filter { it.direction == PaymentDirection.OUTGOING }
                    .sumOf { it.amountSat }
                val last = evs.mapNotNull { it.timestampUnix?.toLong() }.maxOrNull()
                CatRow(
                    category = cat,
                    count = evs.size,
                    inSat = inSat,
                    outSat = outSat,
                    lastTs = last,
                )
            }
    }
    val totalVolume = rows.sumOf { it.volumeSat.toLong() }.coerceAtLeast(1L)

    val sorted = remember(rows, sort, asc) {
        val cmp = when (sort) {
            CatSort.CATEGORY -> compareBy<CatRow> { it.category.lowercase() }
            CatSort.COUNT -> compareBy { it.count }
            CatSort.IN -> compareBy { it.inSat }
            CatSort.OUT -> compareBy { it.outSat }
            CatSort.NET -> compareBy { it.netSat }
            CatSort.PERCENT -> compareBy { it.volumeSat }
            CatSort.LAST -> compareBy { it.lastTs ?: 0L }
        }
        if (asc) rows.sortedWith(cmp) else rows.sortedWith(cmp.reversed())
    }

    if (rows.isEmpty()) {
        EmptyTableHint(stringResource(Res.string.tracker_reports_empty_period))
        return
    }

    // UX review 2026-10 (rec 2) — one unit for every amount column of
    // the table, named in the header; cells carry the bare number.
    val amounts = rememberColumnAmountFormat(rows.flatMap { listOf(it.inSat.toLong(), it.outSat.toLong()) })
    val unit = amounts.unitLabel
    val uncategorised = stringResource(Res.string.common_uncategorised)
    SortableTable(
        headers = listOf(
            stringResource(Res.string.tracker_reports_col_category) to CatSort.CATEGORY,
            "#" to CatSort.COUNT,
            headerWithUnit(stringResource(Res.string.tracker_reports_col_in), unit) to CatSort.IN,
            headerWithUnit(stringResource(Res.string.tracker_reports_col_out), unit) to CatSort.OUT,
            headerWithUnit(stringResource(Res.string.tracker_reports_col_net), unit) to CatSort.NET,
            "%" to CatSort.PERCENT,
            stringResource(Res.string.tracker_reports_col_last) to CatSort.LAST,
        ),
        weights = listOf(2.4f, 0.6f, 1.4f, 1.4f, 1.4f, 0.8f, 1.2f),
        endAligned = setOf(1, 2, 3, 4, 5),
        sort = sort,
        asc = asc,
        onSort = { newSort ->
            if (newSort == sort) asc = !asc else { sort = newSort; asc = false }
        },
    ) {
        sorted.forEach { r ->
            val pct = (r.volumeSat.toLong() * 100.0 / totalVolume).toInt()
            TableRow(
                weights = listOf(2.4f, 0.6f, 1.4f, 1.4f, 1.4f, 0.8f, 1.2f),
                endAligned = setOf(1, 2, 3, 4, 5),
                cells = listOf(
                    r.category.ifBlank { uncategorised },
                    r.count.toString(),
                    signedBare(amounts, r.inSat.toLong()),
                    signedBare(amounts, -r.outSat.toLong()),
                    signedBare(amounts, r.netSat),
                    "$pct%",
                    formatLastTs(r.lastTs),
                ),
                cellColors = listOf(
                    BhodlTheme.colors.textPrimary,
                    BhodlTheme.colors.textSecondary,
                    BhodlTheme.colors.financialPositive,
                    BhodlTheme.colors.financialNegative,
                    io.bhodl.desktop.ui.signedAmountColor(
                        value = r.netSat.toDouble(),
                        positive = BhodlTheme.colors.financialPositive,
                        negative = BhodlTheme.colors.financialNegative,
                        neutral = BhodlTheme.colors.financialPositive,
                    ).let { if (io.bhodl.desktop.ui.money.amountsMasked()) BhodlTheme.colors.textPrimary else it },
                    BhodlTheme.colors.textSecondary,
                    BhodlTheme.colors.textTertiary,
                ),
            )
        }
    }
}

@Composable
private fun WalletReportTable(
    events: List<PaymentEvent>,
    groupedWallets: List<GroupedWallet>,
) {
    var sort by remember { mutableStateOf(WalSort.BALANCE) }
    var asc by remember { mutableStateOf(false) }

    val rows = remember(events, groupedWallets) {
        groupedWallets.map { group ->
            // Phase 3.x — aggregate events across every registry row
            // in the group (signing + its mirror), so the row count
            // reflects the logical wallet's activity rather than only
            // the events emitted under one of the two UUIDs.
            val evs = events.filter { it.walletId in group.memberIds }
            val inSat = evs.filter { it.direction == PaymentDirection.INCOMING }
                .sumOf { it.amountSat }
            val outSat = evs.filter { it.direction == PaymentDirection.OUTGOING }
                .sumOf { it.amountSat }
            val last = evs.mapNotNull { it.timestampUnix?.toLong() }.maxOrNull()
            WalRow(
                // Stable key for sort ties — sorted view of the
                // member UUIDs joined; cheap and unique.
                groupKey = group.memberIds.sorted().joinToString(","),
                label = group.displayLabel,
                kindLabel = group.kindLabel,
                count = evs.size,
                balanceSat = group.balanceSat,
                inSat = inSat,
                outSat = outSat,
                lastTs = last,
            )
        }
    }

    val sorted = remember(rows, sort, asc) {
        val cmp = when (sort) {
            WalSort.LABEL -> compareBy<WalRow> { it.label.lowercase() }
            WalSort.COUNT -> compareBy { it.count }
            WalSort.BALANCE -> compareBy { it.balanceSat?.toLong() ?: -1L }
            WalSort.IN -> compareBy { it.inSat }
            WalSort.OUT -> compareBy { it.outSat }
            WalSort.LAST -> compareBy { it.lastTs ?: 0L }
        }
        if (asc) rows.sortedWith(cmp) else rows.sortedWith(cmp.reversed())
    }

    if (rows.isEmpty()) {
        EmptyTableHint(stringResource(Res.string.tracker_reports_empty_wallets))
        return
    }

    val amounts = rememberColumnAmountFormat(
        rows.flatMap { listOfNotNull(it.balanceSat?.toLong(), it.inSat.toLong(), it.outSat.toLong()) },
    )
    val unit = amounts.unitLabel
    SortableTable(
        headers = listOf(
            stringResource(Res.string.tracker_reports_col_wallet) to WalSort.LABEL,
            "#" to WalSort.COUNT,
            headerWithUnit(stringResource(Res.string.tracker_reports_col_balance), unit) to WalSort.BALANCE,
            headerWithUnit(stringResource(Res.string.tracker_reports_col_in), unit) to WalSort.IN,
            headerWithUnit(stringResource(Res.string.tracker_reports_col_out), unit) to WalSort.OUT,
            stringResource(Res.string.tracker_reports_col_last) to WalSort.LAST,
        ),
        weights = listOf(2.4f, 0.6f, 1.6f, 1.4f, 1.4f, 1.2f),
        endAligned = setOf(1, 2, 3, 4),
        sort = sort,
        asc = asc,
        onSort = { newSort ->
            if (newSort == sort) asc = !asc else { sort = newSort; asc = false }
        },
    ) {
        sorted.forEach { r ->
            TableRow(
                weights = listOf(2.4f, 0.6f, 1.6f, 1.4f, 1.4f, 1.2f),
                endAligned = setOf(1, 2, 3, 4),
                cells = listOf(
                    r.label,
                    r.count.toString(),
                    r.balanceSat?.let { amounts.bare(it.toLong()) } ?: "—",
                    signedBare(amounts, r.inSat.toLong()),
                    signedBare(amounts, -r.outSat.toLong()),
                    formatLastTs(r.lastTs),
                ),
                cellColors = listOf(
                    BhodlTheme.colors.textPrimary,
                    BhodlTheme.colors.textSecondary,
                    BhodlTheme.colors.textPrimary,
                    BhodlTheme.colors.financialPositive,
                    BhodlTheme.colors.financialNegative,
                    BhodlTheme.colors.textTertiary,
                ),
            )
        }
    }
}

@Composable
private fun <K> SortableTable(
    headers: List<Pair<String, K>>,
    weights: List<Float>,
    sort: K,
    asc: Boolean,
    onSort: (K) -> Unit,
    /** Indices of the numeric columns, right-aligned. */
    endAligned: Set<Int> = emptySet(),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(8.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BhodlTheme.colors.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            headers.forEachIndexed { i, (label, key) ->
                val arrow = if (key == sort) (if (asc) " ▲" else " ▼") else ""
                Text(
                    text = label + arrow,
                    style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = if (i in endAligned) TextAlign.End else TextAlign.Start,
                    modifier = Modifier
                        .weight(weights[i])
                        .clickable { onSort(key) }
                        .padding(horizontal = 4.dp),
                )
            }
        }
        content()
    }
}

@Composable
private fun TableRow(
    weights: List<Float>,
    cells: List<String>,
    cellColors: List<androidx.compose.ui.graphics.Color>,
    endAligned: Set<Int> = emptySet(),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, c ->
            Text(
                text = c,
                // Numeric cells: tabular figures, right-aligned.
                style = if (i in endAligned) BhodlTheme.typography.amountSmall else BhodlTheme.typography.bodyMedium,
                textAlign = if (i in endAligned) TextAlign.End else TextAlign.Start,
                color = cellColors[i],
                modifier = Modifier
                    .weight(weights[i])
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyTableHint(text: String) {
    Text(
        text = text,
        style = BhodlTheme.typography.bodyMedium,
        color = BhodlTheme.colors.textTertiary,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    )
}

/** Bare signed cell: "+1,234" / "−1,234" / "0" (no sign on zero, never "−0"; none while masked). */
private fun signedBare(amounts: ColumnAmountFormat, net: Long): String =
    io.bhodl.desktop.ui.amountSign(net) + amounts.bare(kotlin.math.abs(net))

private fun formatLastTs(ts: Long?): String {
    if (ts == null) return "—"
    val fmt = DateTimeFormatter.ofPattern("dd/MM").withZone(ZoneId.systemDefault())
    return fmt.format(Instant.ofEpochSecond(ts))
}

