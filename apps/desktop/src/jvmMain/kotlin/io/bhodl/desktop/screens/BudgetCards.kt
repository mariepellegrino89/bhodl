package io.bhodl.desktop.screens

import io.bhodl.shared.resources.bill_variable_tag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import io.bhodl.desktop.ui.isUncategorised
import io.bhodl.desktop.ui.categoryDisplayName
import io.bhodl.shared.resources.bgm_this_month
import io.bhodl.ffi.PaymentEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.HelpTip
import io.bhodl.desktop.ui.amountSign
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.bgc_add_bill
import io.bhodl.shared.resources.bgc_bills
import io.bhodl.shared.resources.bgc_bills_help
import io.bhodl.shared.resources.bgc_bills_none
import io.bhodl.shared.resources.bgc_budget
import io.bhodl.shared.resources.bgc_categories
import io.bhodl.shared.resources.bgc_categories_help
import io.bhodl.shared.resources.bgc_income_ring
import io.bhodl.shared.resources.bgc_left
import io.bhodl.shared.resources.bgc_left_center
import io.bhodl.shared.resources.bgc_mark_paid
import io.bhodl.shared.resources.bgc_no_budget
import io.bhodl.shared.resources.bgc_over
import io.bhodl.shared.resources.bgc_over_center
import io.bhodl.shared.resources.bgc_paid
import io.bhodl.shared.resources.bgc_price_effect
import io.bhodl.shared.resources.bgc_price_line
import io.bhodl.shared.resources.bgc_spent
import io.bhodl.shared.resources.bgc_spent_ring
import io.bhodl.shared.resources.bgc_summary
import io.bhodl.shared.resources.bgc_summary_help
import io.bhodl.shared.resources.bgc_unbudgeted
import io.bhodl.shared.resources.bgc_view_tx
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Budget redesign 2026-10 (proposal C) — a summary with two rings, one
 * card per category with its own ring, and a 30-day bills calendar.
 * Budgets stay prorated to the selected period ([BudgetRow]).
 */

internal object BudgetCardsTags {
    const val SUMMARY: String = "budget.summary"
    /** Same tag as the budget table's edit chip, so existing flows keep working. */
    fun category(name: String): String = BudgetTags.editChip(name)
    const val BILLS: String = "budget.bills"
    fun bill(id: Long): String = "budget.bill.$id"
}

/** Totals of the expense budgets of the period. */
internal data class BudgetSummary(
    val budgetSat: Long,
    val spentSat: Long,
    val unbudgetedSat: Long,
    val priceEffectSat: Long?,
) {
    val leftSat: Long get() = budgetSat - spentSat
    val usedFraction: Float get() = if (budgetSat > 0L) spentSat.toFloat() / budgetSat else 0f
}

internal fun budgetSummary(rows: List<BudgetRow>, moves: Map<String, BudgetMove>): BudgetSummary {
    val budgeted = rows.filter { it.budgetForPeriodSat != null }
    return BudgetSummary(
        budgetSat = budgeted.sumOf { it.budgetForPeriodSat!!.toLong() },
        spentSat = budgeted.sumOf { it.actualSat.toLong() },
        unbudgetedSat = rows.filter { it.budgetForPeriodSat == null }.sumOf { it.actualSat.toLong() },
        priceEffectSat = if (moves.isEmpty()) null else moves.values.sumOf { it.priceEffectSat },
    )
}

/** Bills due in the next [days] days (unpaid this month), soonest first. */
internal fun upcomingBills(bills: List<TrackingBillFfi>, today: LocalDate, days: Long = 30): List<Pair<TrackingBillFfi, LocalDate>> =
    bills.mapNotNull { b ->
        val day = b.dueDay.toInt().takeIf { it in 1..31 } ?: return@mapNotNull null
        val paid = b.lastPaidUnix?.let {
            val d = LocalDate.ofEpochDay(Math.floorDiv(it, 86_400L))
            d.year == today.year && d.month == today.month
        } == true
        val inc = daysUntilDayOfMonth(today, day)
        // Paid this month: show next month's occurrence instead.
        val date = if (paid && inc <= 31) {
            val next = today.plusMonths(1)
            next.withDayOfMonth(day.coerceAtMost(next.lengthOfMonth()))
        } else {
            today.plusDays(inc)
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(today, date) > days) null else b to date
    }.sortedBy { it.second }

@Composable
private fun Ring(
    fraction: Float,
    color: Color,
    size: Dp,
    stroke: Dp,
    modifier: Modifier = Modifier,
) {
    val track = BhodlTheme.colors.surfaceVariant
    Canvas(modifier.size(size)) {
        val s = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
        val inset = stroke.toPx() / 2
        val box = Size(this.size.width - inset * 2, this.size.height - inset * 2)
        drawArc(track, -90f, 360f, false, Offset(inset, inset), box, style = s)
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), box, style = s)
    }
}

@Composable
private fun SectionTitle(text: String, help: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text.uppercase(), style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = BhodlTheme.colors.textSecondary)
        HelpTip(help)
    }
}

@Composable
private fun usageColor(fraction: Float): Color = when {
    fraction > 1f -> BhodlTheme.colors.financialNegative
    fraction > 0.85f -> BhodlTheme.colors.financialPending
    else -> BhodlTheme.colors.chartCategorical.getOrElse(1) { BhodlTheme.colors.brandPrimary }
}

/** Summary: outer ring = expenses vs budget, inner ring = income vs expected income. */
@Composable
internal fun BudgetSummaryCard(
    summary: BudgetSummary,
    incomeFraction: Float?,
    periodLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(BudgetCardsTags.SUMMARY),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
            Ring(summary.usedFraction, usageColor(summary.usedFraction), 150.dp, 14.dp)
            if (incomeFraction != null) Ring(incomeFraction, BhodlTheme.colors.financialPositive, 112.dp, 10.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    displayAmount(kotlin.math.abs(summary.leftSat)),
                    style = BhodlTheme.typography.amountSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = io.bhodl.desktop.ui.signedAmountColor(
                        summary.leftSat.toDouble(),
                        positive = BhodlTheme.colors.textPrimary,
                        negative = BhodlTheme.colors.financialNegative,
                        neutral = BhodlTheme.colors.textPrimary,
                    ),
                )
                Text(
                    stringResource(if (summary.leftSat < 0) Res.string.bgc_over_center else Res.string.bgc_left_center),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(Res.string.bgc_summary, periodLabel), stringResource(Res.string.bgc_summary_help))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat(stringResource(Res.string.bgc_budget), displayAmount(summary.budgetSat), Modifier.weight(1f))
                MiniStat(stringResource(Res.string.bgc_spent), displayAmount(summary.spentSat), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat(stringResource(Res.string.bgc_unbudgeted), displayAmount(summary.unbudgetedSat), Modifier.weight(1f))
                MiniStat(
                    stringResource(Res.string.bgc_price_effect),
                    summary.priceEffectSat?.let { amountSign(it) + displayAmount(kotlin.math.abs(it)) } ?: "—",
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(usageColor(summary.usedFraction), stringResource(Res.string.bgc_spent_ring))
                if (incomeFraction != null) Legend(BhodlTheme.colors.financialPositive, stringResource(Res.string.bgc_income_ring))
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(6.dp)).background(BhodlTheme.colors.background).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label.uppercase(), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary, maxLines = 1)
        Text(value, style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        Text(label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
    }
}

/** One card per expense category with a budget; the others as add chips. */
@Composable
internal fun BudgetCategoryGrid(
    rows: List<BudgetRow>,
    moves: Map<String, BudgetMove>,
    onEditBudget: (String, ULong?) -> Unit,
    onViewTransactions: (String) -> Unit,
) {
    val budgeted = rows.filter { it.budgetForPeriodSat != null }
        .sortedByDescending { it.actualSat.toDouble() / it.budgetForPeriodSat!!.toDouble().coerceAtLeast(1.0) }
    val unbudgeted = rows.filter { it.budgetForPeriodSat == null && it.actualSat > 0UL }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(stringResource(Res.string.bgc_categories), stringResource(Res.string.bgc_categories_help))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = when {
                maxWidth >= 1200.dp -> 4
                maxWidth >= 860.dp -> 3
                maxWidth >= 560.dp -> 2
                else -> 1
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                budgeted.chunked(perRow).forEach { chunk ->
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        chunk.forEach { r ->
                            CategoryCard(r, moves[r.category], onEditBudget, onViewTransactions, Modifier.weight(1f).fillMaxHeight())
                        }
                        repeat(perRow - chunk.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (unbudgeted.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.bgc_no_budget), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary, modifier = Modifier.padding(top = 6.dp))
                unbudgeted.forEach { r ->
                    // "No category" gets no budget: its chip opens those
                    // transactions, to categorise them.
                    val none = isUncategorised(r.category)
                    Text(
                        "${categoryDisplayName(r.category)} · ${displayAmount(r.actualSat.toLong())}" + if (none) "" else "  +",
                        style = BhodlTheme.typography.labelMedium,
                        color = if (none) BhodlTheme.colors.financialPending else BhodlTheme.colors.textPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(999.dp))
                            .clickable(role = Role.Button) { if (none) onViewTransactions("") else onEditBudget(r.category, null) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag(BudgetCardsTags.category(r.category)),
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    r: BudgetRow,
    move: BudgetMove?,
    onEditBudget: (String, ULong?) -> Unit,
    onViewTransactions: (String) -> Unit,
    modifier: Modifier,
) {
    val budget = r.budgetForPeriodSat!!.toLong()
    val spent = r.actualSat.toLong()
    val fraction = if (budget > 0L) spent.toFloat() / budget else 0f
    val color = usageColor(fraction)
    val left = budget - spent
    Column(
        modifier = modifier
            .bhodlCard()
            .clickable(role = Role.Button) { onEditBudget(r.category, r.budgetedSat) }
            .padding(14.dp)
            .testTag(BudgetCardsTags.category(r.category)),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Ring(fraction, color, 56.dp, 7.dp)
                Text("${Math.round(fraction * 100)}%", style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = BhodlTheme.colors.textPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(r.category, style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${displayAmount(spent)} / ${displayAmount(budget)}",
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        Text(
            if (left >= 0) stringResource(Res.string.bgc_left, displayAmount(left)) else stringResource(Res.string.bgc_over, displayAmount(-left)),
            style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (left >= 0) BhodlTheme.colors.textPrimary else BhodlTheme.colors.financialNegative,
        )
        if (move != null && move.isPriceMaterial) {
            Text(
                stringResource(
                    Res.string.bgc_price_line,
                    amountSign(move.priceEffectSat) + displayAmount(kotlin.math.abs(move.priceEffectSat)),
                    amountSign(move.spendingEffectSat) + displayAmount(kotlin.math.abs(move.spendingEffectSat)),
                ),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
        TextButton(onClick = { onViewTransactions(r.category) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(Res.string.bgc_view_tx), style = BhodlTheme.typography.labelMedium)
        }
    }
}

private val BILL_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d")
private val BILL_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM")

/** The next 30 days of bills as a horizontal strip of date chips. */
@Composable
internal fun BillsCalendarStrip(
    bills: List<TrackingBillFfi>,
    today: LocalDate,
    onToggleBillPaid: (TrackingBillFfi, Boolean) -> Unit,
    onEditBill: (TrackingBillFfi) -> Unit,
    onAddBill: () -> Unit,
    modifier: Modifier = Modifier,
    /** Schema v7 — a bill marked paid with the amount actually paid. */
    onMarkPaidAmount: (TrackingBillFfi, ULong) -> Unit = { b, _ -> onToggleBillPaid(b, true) },
    /** Schema v8 — when set, "mark paid" opens the pay flow instead. */
    onPayBill: ((TrackingBillFfi) -> Unit)? = null,
) {
    val upcoming = upcomingBills(bills, today)
    var askAmount by remember { mutableStateOf<TrackingBillFfi?>(null) }
    askAmount?.let { b ->
        BillPaidAmountDialog(b, onDismiss = { askAmount = null }, onSave = { sat -> askAmount = null; onMarkPaidAmount(b, sat) })
    }
    Column(modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(BudgetCardsTags.BILLS), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle(stringResource(Res.string.bgc_bills), stringResource(Res.string.bgc_bills_help)) }
            TextButton(onClick = onAddBill, modifier = Modifier.testTag(BillTags.ADD)) { Text(stringResource(Res.string.bgc_add_bill)) }
        }
        if (upcoming.isEmpty()) {
            Text(stringResource(Res.string.bgc_bills_none), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textTertiary)
            return@Column
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            upcoming.forEach { (b, date) ->
                val paid = b.lastPaidUnix?.let {
                    val d = LocalDate.ofEpochDay(Math.floorDiv(it, 86_400L))
                    d.year == today.year && d.month == today.month
                } == true
                val soon = !paid && java.time.temporal.ChronoUnit.DAYS.between(today, date) <= 3
                Column(
                    modifier = Modifier
                        .width(150.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BhodlTheme.colors.background)
                        .border(1.dp, if (soon) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.outline, RoundedCornerShape(10.dp))
                        .clickable { onEditBill(b) }
                        .padding(10.dp)
                        .testTag(BudgetCardsTags.bill(b.id)),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(date.format(BILL_DAY), style = BhodlTheme.typography.amountSmall.copy(fontWeight = FontWeight.Bold), color = if (soon) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.textPrimary)
                        Text(date.format(BILL_MONTH.withLocale(Locale.getDefault())), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
                    }
                    Text(b.name, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // A variable bill shows its estimate ("≈"), then what was paid.
                    val shownAmount = when {
                        paid && b.lastPaidAmountSat != null -> displayAmount(b.lastPaidAmountSat!!.toLong())
                        b.variable -> "≈ " + displayAmount(b.amountSat.toLong())
                        else -> b.displayLabel ?: displayAmount(b.amountSat.toLong())
                    }
                    Text(shownAmount, style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textSecondary, maxLines = 1, modifier = Modifier.testTag(BillTags.amount(b.id)))
                    if (b.variable) {
                        Text(stringResource(Res.string.bill_variable_tag), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary)
                    }
                    Text(
                        stringResource(if (paid) Res.string.bgc_paid else Res.string.bgc_mark_paid),
                        style = BhodlTheme.typography.labelMedium,
                        color = if (paid) BhodlTheme.colors.financialPositive else BhodlTheme.colors.brandPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Checkbox) {
                                when {
                                    paid -> onToggleBillPaid(b, false)
                                    onPayBill != null -> onPayBill(b)
                                    b.variable -> askAmount = b
                                    else -> onMarkPaidAmount(b, b.amountSat)
                                }
                            }
                            .padding(vertical = 2.dp)
                            .testTag(BillTags.paidChip(b.id)),
                    )
                }
            }
        }
    }
}

/** Today in the system zone (test seam friendly). */
internal fun todayLocal(): LocalDate = LocalDate.now(ZoneId.systemDefault())

/**
 * Budget redesign 2026-10 — budgets are monthly and follow the calendar
 * month: the events from the 1st of [today]'s month (local time) on.
 */
internal fun eventsThisMonth(events: List<PaymentEvent>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<PaymentEvent> =
    eventsInMonth(events, java.time.YearMonth.from(today), zone)

/**
 * The events of [month] (local time). Unconfirmed events (no timestamp)
 * belong to the current month only.
 */
internal fun eventsInMonth(events: List<PaymentEvent>, month: java.time.YearMonth, zone: ZoneId = ZoneId.systemDefault()): List<PaymentEvent> {
    val start = month.atDay(1).atStartOfDay(zone).toEpochSecond()
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()
    val current = month == java.time.YearMonth.now(zone)
    return events.filter { e ->
        val ts = e.timestampUnix?.toLong() ?: return@filter current
        ts in start until end
    }
}

internal object BudgetMonthTags {
    const val PREV: String = "budget-month.prev"
    const val NEXT: String = "budget-month.next"
    const val LABEL: String = "budget-month.label"
}

/** Budget redesign 2026-10 — "‹ October 2026 ›": earlier months, never past the current one. */
@Composable
internal fun BudgetMonthSelector(
    month: java.time.YearMonth,
    current: java.time.YearMonth,
    onChange: (java.time.YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = java.util.Locale.getDefault()
    val label = month.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale)
        .replaceFirstChar { it.titlecase(locale) } + " " + month.year
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.IconButton(onClick = { onChange(month.minusMonths(1)) }, modifier = Modifier.testTag(BudgetMonthTags.PREV)) {
            Text("‹", style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textPrimary)
        }
        Text(
            label,
            style = BhodlTheme.typography.titleMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.testTag(BudgetMonthTags.LABEL),
        )
        androidx.compose.material3.IconButton(
            onClick = { onChange(month.plusMonths(1)) },
            enabled = month < current,
            modifier = Modifier.testTag(BudgetMonthTags.NEXT),
        ) {
            Text("›", style = BhodlTheme.typography.titleMedium, color = if (month < current) BhodlTheme.colors.textPrimary else BhodlTheme.colors.textTertiary)
        }
        if (month != current) {
            androidx.compose.material3.TextButton(onClick = { onChange(current) }) {
                Text(stringResource(Res.string.bgm_this_month), color = BhodlTheme.colors.brandPrimary)
            }
        }
    }
}

/** Days left in [today]'s month, today included. */
internal fun daysLeftInMonth(today: LocalDate): Int = today.lengthOfMonth() - today.dayOfMonth + 1

/** A monthly budget as a weekly figure (52 weeks / 12 months). */
internal fun weeklyFromMonthly(monthlySat: Long): Long = kotlin.math.round(monthlySat * 12.0 / 52.0).toLong()
