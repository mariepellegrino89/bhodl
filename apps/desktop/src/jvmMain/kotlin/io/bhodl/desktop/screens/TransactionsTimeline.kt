package io.bhodl.desktop.screens

import io.bhodl.desktop.AppClock
import io.bhodl.desktop.ui.categoryDisplayName
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_hist_received
import io.bhodl.shared.resources.trk_hist_self_transfer
import io.bhodl.shared.resources.trk_hist_sent
import io.bhodl.shared.resources.trk_wallet_excluded
import io.bhodl.shared.resources.trk_wallet_included
import io.bhodl.shared.resources.txc_in_out
import io.bhodl.shared.resources.txc_in_out_help
import io.bhodl.shared.resources.txc_income
import io.bhodl.shared.resources.txc_expenses
import io.bhodl.shared.resources.txc_other
import io.bhodl.shared.resources.txc_pending
import io.bhodl.shared.resources.txc_quick_more
import io.bhodl.shared.resources.txc_review
import io.bhodl.shared.resources.txc_review_help
import io.bhodl.shared.resources.txc_saved
import io.bhodl.shared.resources.txc_search
import io.bhodl.shared.resources.txc_show_more
import io.bhodl.shared.resources.txc_today
import io.bhodl.shared.resources.txc_uncategorized
import io.bhodl.shared.resources.txc_where
import io.bhodl.shared.resources.txc_where_empty
import io.bhodl.shared.resources.txc_where_help
import io.bhodl.shared.resources.txc_yesterday
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Transactions redesign 2026-10 (proposal C) — visual summaries of the
 * period, a day-grouped timeline with category tiles and a "to review"
 * lane for uncategorised payments.
 */

internal object TxTimelineTags {
    const val IN_OUT: String = "tx.in-out"
    const val WHERE: String = "tx.where"
    const val SEARCH: String = "tx.search"
    fun day(epochDay: Long): String = "tx.day.$epochDay"
    const val ROW: String = "tx.row"
    const val SHOW_MORE: String = "tx.show-more"
    const val REVIEW: String = "tx.review"
    const val QUICK: String = "tx.review.quick"
}

/** One day of the timeline; [epochDay] null for unconfirmed payments. */
internal data class TxDay(val epochDay: Long?, val events: List<PaymentEvent>, val netSat: Long)

/** Signed external flow of one payment (transfers to self: fee only). */
internal fun PaymentEvent.netExternalSat(): Long = externalInflowSat().toLong() - externalOutflowSat().toLong()

/**
 * Groups [events] by local day, newest first; unconfirmed payments (no
 * timestamp) form the first group. Each txid counts once per day.
 */
internal fun groupByDay(events: List<PaymentEvent>, zone: ZoneId = ZoneId.systemDefault()): List<TxDay> {
    val (pending, dated) = events.partition { it.timestampUnix == null }
    val out = mutableListOf<TxDay>()
    if (pending.isNotEmpty()) out += TxDay(null, pending, pending.distinctBy { it.txidOrPaymentId }.sumOf { it.netExternalSat() })
    dated.groupBy { Instant.ofEpochSecond(it.timestampUnix!!.toLong()).atZone(zone).toLocalDate().toEpochDay() }
        .toSortedMap(compareByDescending { it })
        .forEach { (day, evs) ->
            val sorted = evs.sortedByDescending { it.timestampUnix }
            out += TxDay(day, sorted, sorted.distinctBy { it.txidOrPaymentId }.sumOf { it.netExternalSat() })
        }
    return out
}

/** Outflow per category (blank = uncategorised), largest first. */
internal fun outflowByCategory(events: List<PaymentEvent>): List<Pair<String?, Long>> =
    events.distinctBy { it.txidOrPaymentId to it.walletId }
        .groupBy { it.category?.takeIf { c -> c.isNotBlank() } }
        .mapValues { (_, evs) -> evs.sumOf { it.externalOutflowSat().toLong() } }
        .filterValues { it > 0L }
        .toList()
        .sortedByDescending { it.second }

/** Categories by how often the user picked them, most used first. */
internal fun frequentCategories(events: List<PaymentEvent>, limit: Int = 3): List<String> =
    events.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }
        .groupingBy { it }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit).map { it.key }

/** Free-text search over category, wallet label, txid and amount digits. */
internal fun searchLedger(events: List<PaymentEvent>, query: String, walletLabel: (String) -> String?): List<PaymentEvent> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return events
    val digits = q.filter { it.isDigit() }
    return events.filter { e ->
        e.category?.lowercase()?.contains(q) == true ||
            // A classic category is found by its name in any app language.
            presetCategoryNamed(e.category)?.names?.any { it.contains(q) } == true ||
            walletLabel(e.walletId)?.lowercase()?.contains(q) == true ||
            e.txidOrPaymentId.lowercase().contains(q) ||
            (digits.isNotEmpty() && digits.length == q.length && e.amountSat.toString().contains(digits))
    }
}


@Composable
private fun CardTitle(text: String, help: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text.uppercase(), style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = BhodlTheme.colors.textSecondary)
        HelpTip(help)
    }
}

/** Income vs expenses of the period as two bars, plus what was kept. */
@Composable
internal fun TxInOutCard(periodEvents: List<PaymentEvent>, modifier: Modifier = Modifier) {
    val flows = periodFlows(periodEvents.distinctBy { it.txidOrPaymentId to it.walletId })
    val max = maxOf(flows.inflowSat, flows.outflowSat).toLong().coerceAtLeast(1L)
    val saved = flows.inflowSat.toLong() - flows.outflowSat.toLong()
    Column(modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(TxTimelineTags.IN_OUT), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(stringResource(Res.string.txc_in_out), stringResource(Res.string.txc_in_out_help))
        FlowBar(stringResource(Res.string.txc_income), flows.inflowSat.toLong(), max, BhodlTheme.colors.financialPositive)
        FlowBar(stringResource(Res.string.txc_expenses), flows.outflowSat.toLong(), max, BhodlTheme.colors.financialNegative)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(Res.string.txc_saved), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textSecondary, modifier = Modifier.weight(1f))
            Text(
                amountSign(saved) + displayAmount(kotlin.math.abs(saved)),
                style = BhodlTheme.typography.amountMedium,
                color = signedAmountColor(saved.toDouble(), BhodlTheme.colors.financialPositive, BhodlTheme.colors.financialNegative, BhodlTheme.colors.textPrimary),
            )
        }
    }
}

@Composable
private fun FlowBar(label: String, sat: Long, max: Long, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            Text(label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary, modifier = Modifier.weight(1f))
            Text(displayAmount(sat), style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(BhodlTheme.colors.surfaceVariant)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((sat.toFloat() / max).coerceIn(0f, 1f)).background(color))
        }
    }
}

/** Where the sats went: one segmented bar of outflows by category. */
@Composable
internal fun TxWhereCard(periodEvents: List<PaymentEvent>, modifier: Modifier = Modifier) {
    val all = outflowByCategory(periodEvents)
    val top = all.take(5)
    val rest = all.drop(5).sumOf { it.second }
    val parts = top + if (rest > 0L) listOf<Pair<String?, Long>>("\u0000other" to rest) else emptyList()
    val total = parts.sumOf { it.second }.coerceAtLeast(1L)
    val otherLabel = stringResource(Res.string.txc_other)
    val noneLabel = stringResource(Res.string.txc_uncategorized)
    Column(modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(TxTimelineTags.WHERE), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitle(stringResource(Res.string.txc_where), stringResource(Res.string.txc_where_help))
        if (parts.isEmpty()) {
            Text(stringResource(Res.string.txc_where_empty), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textTertiary)
            return@Column
        }
        val colors = parts.map { (c, _) -> if (c == "\u0000other") BhodlTheme.colors.textTertiary else categoryColor(c) }
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp))) {
            parts.forEachIndexed { i, (_, v) -> Box(Modifier.weight(v.toFloat() / total).fillMaxHeight().background(colors[i])) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            parts.forEachIndexed { i, (c, v) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(colors[i]))
                    Text(
                        when (c) { "\u0000other" -> otherLabel; null -> noneLabel; else -> categoryDisplayName(c) },
                        style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textPrimary,
                    )
                    Text("${Math.round(v * 100.0 / total)}%", style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
                }
            }
        }
    }
}

/** Search box above the timeline. */
@Composable
internal fun TxSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.txc_search)) },
        modifier = modifier.testTag(TxTimelineTags.SEARCH),
    )
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM")

@Composable
private fun dayLabel(epochDay: Long?): String {
    if (epochDay == null) return stringResource(Res.string.txc_pending)
    val today = AppClock.today().toEpochDay()
    return when (epochDay) {
        today -> stringResource(Res.string.txc_today)
        today - 1 -> stringResource(Res.string.txc_yesterday)
        else -> LocalDate.ofEpochDay(epochDay).format(DAY_FORMAT.withLocale(Locale.getDefault()))
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}

/**
 * The day-grouped timeline. [visible] caps the rows shown; "show more"
 * raises it. [chip] renders the category pill of a row (it opens the
 * category editor).
 */
@Composable
internal fun TxTimeline(
    events: List<PaymentEvent>,
    visible: Int,
    onShowMore: () -> Unit,
    walletLabel: (String) -> String,
    chip: @Composable (PaymentEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val days = groupByDay(events)
    var left = visible
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (d in days) {
            if (left <= 0) break
            val shown = d.events.take(left)
            left -= shown.size
            Column(modifier = Modifier.bhodlCard().testTag(TxTimelineTags.day(d.epochDay ?: -1L))) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(BhodlTheme.colors.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(dayLabel(d.epochDay), style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(
                        amountSign(d.netSat) + displayAmount(kotlin.math.abs(d.netSat)),
                        style = BhodlTheme.typography.mono,
                        color = signedAmountColor(d.netSat.toDouble(), BhodlTheme.colors.financialPositive, BhodlTheme.colors.financialNegative, BhodlTheme.colors.textSecondary),
                    )
                }
                shown.forEach { e -> TxRow(e, walletLabel(e.walletId), chip) }
            }
        }
        if (events.size > visible) {
            TextButton(onClick = onShowMore, modifier = Modifier.fillMaxWidth().testTag(TxTimelineTags.SHOW_MORE)) {
                Text(stringResource(Res.string.txc_show_more, visible, events.size))
            }
        }
    }
}

@Composable
private fun TxRow(e: PaymentEvent, wallet: String, chip: @Composable (PaymentEvent) -> Unit) {
    val title = e.category?.takeIf { it.isNotBlank() }?.let { categoryDisplayName(it) } ?: stringResource(
        when (e.direction) {
            PaymentDirection.INCOMING -> Res.string.trk_hist_received
            PaymentDirection.OUTGOING -> Res.string.trk_hist_sent
            PaymentDirection.SELF_TRANSFER -> Res.string.trk_hist_self_transfer
        },
    )
    val color = categoryColor(e.category)
    val net = e.netExternalSat()
    val time = e.timestampUnix?.let {
        Instant.ofEpochSecond(it.toLong()).atZone(ZoneId.systemDefault()).toLocalTime().toString().take(5)
    } ?: stringResource(Res.string.txc_pending)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp).testTag(TxTimelineTags.ROW),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = 0.22f)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.take(1).uppercase(), style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = BhodlTheme.colors.textPrimary)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$wallet · $time", style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary, maxLines = 1)
        }
        chip(e)
        Text(
            amountSign(net) + displayAmount(kotlin.math.abs(net)),
            style = BhodlTheme.typography.mono,
            color = signedAmountColor(net.toDouble(), BhodlTheme.colors.financialPositive, BhodlTheme.colors.textPrimary, BhodlTheme.colors.textSecondary),
            modifier = Modifier.width(140.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/**
 * "To review": uncategorised payments of the period, each with the most
 * used categories as one-tap choices and "Other…" for the full editor.
 */
@Composable
internal fun TxReviewLane(
    uncategorized: List<PaymentEvent>,
    quickCategories: List<String>,
    walletLabel: (String) -> String,
    onQuick: (PaymentEvent, String) -> Unit,
    onMore: (PaymentEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(TxTimelineTags.REVIEW), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle(stringResource(Res.string.txc_review, uncategorized.size), stringResource(Res.string.txc_review_help))
        uncategorized.take(8).forEach { e ->
            val net = e.netExternalSat()
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BhodlTheme.colors.background).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row {
                    Text(walletLabel(e.walletId), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                    Text(amountSign(net) + displayAmount(kotlin.math.abs(net)), style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textPrimary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    quickCategories.forEach { c ->
                        Text(
                            categoryDisplayName(c),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .border(1.dp, categoryColor(c), RoundedCornerShape(999.dp))
                                .clickable(role = Role.Button) { onQuick(e, c) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag(TxTimelineTags.QUICK),
                        )
                    }
                    Text(
                        stringResource(Res.string.txc_quick_more),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.brandPrimary,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable(role = Role.Button) { onMore(e) }.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** Two summary cards side by side (stacked when narrow). */
@Composable
internal fun TxSummaryRow(periodEvents: List<PaymentEvent>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 860.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TxInOutCard(periodEvents, Modifier.fillMaxWidth())
                TxWhereCard(periodEvents, Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TxInOutCard(periodEvents, Modifier.weight(1f).fillMaxHeight())
                TxWhereCard(periodEvents, Modifier.weight(1.4f).fillMaxHeight())
            }
        }
    }
}

/** Wallets counted in the totals, as compact toggle pills. */
@Composable
internal fun WalletIncludeChips(
    groups: List<GroupedWallet>,
    excludedWalletIds: Set<String>,
    onToggle: (GroupedWallet, Boolean) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { g ->
            val included = g.memberIds.any { it !in excludedWalletIds }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .border(1.dp, if (included) BhodlTheme.colors.outline else BhodlTheme.colors.financialPending, RoundedCornerShape(999.dp))
                    .clickable(role = Role.Switch) { onToggle(g, !included) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(TrackingTags.WALLET_INCLUDE_TOGGLE),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(g.displayLabel, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textPrimary)
                Text(
                    stringResource(if (included) Res.string.trk_wallet_included else Res.string.trk_wallet_excluded),
                    style = BhodlTheme.typography.labelMedium,
                    color = if (included) BhodlTheme.colors.textTertiary else BhodlTheme.colors.financialPending,
                )
            }
        }
    }
}
