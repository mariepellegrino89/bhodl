package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.heightIn
import io.bhodl.desktop.ui.tableRowFocusable
import io.bhodl.desktop.ui.tableCell
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.ProvideColumnUnit
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.displaySignedAmount
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingBudgetFfi
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_budget_col_status
import io.bhodl.shared.resources.tracker_budget_move_line
import io.bhodl.shared.resources.tracker_budget_per_month
import io.bhodl.shared.resources.tracker_budget_prorated_header
import io.bhodl.shared.resources.tracker_budget_sats_note
import io.bhodl.shared.resources.tracker_budget_status_at_risk
import io.bhodl.shared.resources.tracker_budget_status_behind
import io.bhodl.shared.resources.tracker_budget_status_over
import io.bhodl.shared.resources.tracker_budget_status_reached
import io.bhodl.shared.resources.tracker_budget_status_under
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.trp_budget_clear
import io.bhodl.shared.resources.trp_budget_col_actual
import io.bhodl.shared.resources.trp_budget_col_budget
import io.bhodl.shared.resources.trp_budget_col_progress
import io.bhodl.shared.resources.trp_budget_col_source
import io.bhodl.shared.resources.trp_budget_col_variance
import io.bhodl.shared.resources.trp_budget_dialog_body
import io.bhodl.shared.resources.trp_budget_dialog_title
import io.bhodl.shared.resources.trp_budget_no_data
import io.bhodl.shared.resources.trp_budget_set
import io.bhodl.shared.resources.trp_hint_budget
import io.bhodl.desktop.ui.UNCATEGORISED_KEY
import io.bhodl.desktop.ui.categoryDisplayName

/**
 * Phase 4.3 — Budget breakdown table for Income or Expense.
 *
 * Each row: category name, budgeted amount (clickable → edit dialog),
 * actual for the current period, inline progress bar, variance %
 * with direction arrow.
 *
 * Wealth Builder convention: progress bar GREEN when actual ≤ budgeted
 * (you're under budget), AMBER when 0.8..1.0 of budget (warning), RED
 * when actual > budgeted (overspent). Income rows invert: GREEN when
 * actual ≥ budgeted (you earned at least what you planned), AMBER /
 * RED when behind.
 */
@Composable
internal fun BudgetBreakdownCard(
    title: String,
    rows: List<BudgetRow>,
    onEditBudget: (category: String, currentBudget: ULong?) -> Unit,
    /** UX review 2026-10 (B4) — label of the period the budgets are
     *  prorated to (e.g. "90g"); null when not prorated. */
    proratedToLabel: String? = null,
    /** UX review 2026-10 (item 12) — price-vs-spending split per
     *  category ([explainBudgetMove]); a row shows its note when the
     *  price part is material. Empty without rate history. */
    moves: Map<String, BudgetMove> = emptyMap(),
    /** Show the "budgets are in sats" explanation under the title. */
    showSatsNote: Boolean = false,
) {
    // Item 17 — on a narrow window progress, variance, then status give
    // way ([BUDGET_TABLE]); category, budget (the edit control) and
    // actual always stay, so the verdict can be read off the numbers.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cols = BUDGET_TABLE.visible(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .bhodlCard()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textSecondary,
            )
            if (showSatsNote) {
                Text(
                    text = stringResource(Res.string.tracker_budget_sats_note),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.testTag(BudgetTags.SATS_NOTE),
                )
            }
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(Res.string.trp_budget_no_data),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                return@Column
            }
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (c in cols) {
                    val label = when (c) {
                        BudgetCol.SOURCE -> stringResource(Res.string.trp_budget_col_source)
                        BudgetCol.BUDGET -> if (proratedToLabel != null) {
                            stringResource(Res.string.tracker_budget_prorated_header, proratedToLabel)
                        } else {
                            stringResource(Res.string.trp_budget_col_budget)
                        }
                        BudgetCol.ACTUAL -> stringResource(Res.string.trp_budget_col_actual)
                        BudgetCol.PROGRESS -> stringResource(Res.string.trp_budget_col_progress)
                        BudgetCol.STATUS -> stringResource(Res.string.tracker_budget_col_status)
                        BudgetCol.VARIANCE -> stringResource(Res.string.trp_budget_col_variance)
                    }
                    HeaderCell(label, c)
                }
            }
            // UX review 2026-10 (rec 2) — Budget / Actual / Variance share one unit.
            ProvideColumnUnit(rows.flatMap { listOfNotNull(it.budgetForPeriodSat?.toLong(), it.actualSat.toLong()) }) {
                rows.forEach { r ->
                    BudgetRowView(
                        row = r,
                        cols = cols,
                        onEditBudget = onEditBudget,
                        move = moves[r.category]?.takeIf { it.isPriceMaterial && r.budgetForPeriodSat != null },
                        periodLabel = proratedToLabel,
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetRowView(
    row: BudgetRow,
    cols: List<BudgetCol>,
    onEditBudget: (category: String, currentBudget: ULong?) -> Unit,
    move: BudgetMove? = null,
    periodLabel: String? = null,
) {
    // B4 — everything compares against the budget prorated to the
    // selected window, not the raw monthly amount.
    val budget: ULong? = row.budgetForPeriodSat
    val ratio: Float = budget?.takeIf { it > 0UL }
        ?.let { row.actualSat.toFloat() / it.toFloat() } ?: 0f
    val status = budgetStatus(row)
    val progressColor = statusColor(status)
    val varianceSat: Long = (row.actualSat.toLong()) - (budget?.toLong() ?: 0L)
    val variancePct: Int = if (budget != null && budget > 0UL) {
        ((row.actualSat.toLong() - budget.toLong()) * 100.0 / budget.toLong())
            .roundToInt()
    } else 0
    val varianceColor: androidx.compose.ui.graphics.Color = when {
        budget == null -> BhodlTheme.colors.textTertiary
        varianceSat == 0L -> BhodlTheme.colors.financialNeutral
        // Income: above budget = good; expense: below = good
        (row.direction == TrackingDirection.INCOME && varianceSat > 0L) ||
            (row.direction == TrackingDirection.EXPENSE && varianceSat < 0L) ->
            BhodlTheme.colors.financialPositive
        else -> BhodlTheme.colors.financialNegative
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            // Item 15 — Settings › Density row height + keyboard focus.
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BhodlTheme.density.rowHeight)
                .tableRowFocusable(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (c in cols) when (c) {
                BudgetCol.SOURCE -> Text(
                    text = categoryDisplayName(row.category),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                    modifier = Modifier.tableCell(this, c),
                )
                BudgetCol.BUDGET -> Column(
                    modifier = Modifier
                        .tableCell(this, c)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onEditBudget(row.category, row.budgetedSat) }
                        .padding(2.dp)
                        .testTag(BudgetTags.editChip(row.category)),
                ) {
                    Text(
                        text = budget?.let { displayAmount(it) } ?: stringResource(Res.string.trp_budget_set),
                        style = BhodlTheme.typography.bodyMedium,
                        color = if (budget != null) BhodlTheme.colors.textPrimary
                        else BhodlTheme.colors.accentNeon,
                    )
                    // The stored (per-month) amount stays visible when prorated.
                    if (row.budgetedSat != null && budget != row.budgetedSat) {
                        Text(
                            text = stringResource(Res.string.tracker_budget_per_month, displayAmount(row.budgetedSat)),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                    }
                }
                BudgetCol.ACTUAL -> Text(
                    text = displayAmount(row.actualSat),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                    modifier = Modifier.tableCell(this, c),
                )
                BudgetCol.PROGRESS -> InlineProgressBar(
                    ratio = ratio.coerceIn(0f, 1.25f),
                    color = progressColor,
                    modifier = Modifier
                        .tableCell(this, c)
                        .height(8.dp),
                )
                BudgetCol.STATUS -> Box(modifier = Modifier.tableCell(this, c)) {
                    if (status != null) StatusChip(status, row.category)
                }
                BudgetCol.VARIANCE -> Text(
                    text = if (budget == null) "—" else budgetVarianceLabel(varianceSat, variancePct),
                    style = BhodlTheme.typography.bodyMedium,
                    color = varianceColor,
                    modifier = Modifier.tableCell(this, c),
                )
            }
        }
        if (move != null && periodLabel != null) {
            // UX review 2026-10 (item 12) — why the sats figure moved:
            // price vs spending, never smoothed.
            val pct = move.btcPriceChangePct
            Text(
                text = stringResource(
                    Res.string.tracker_budget_move_line,
                    periodLabel,
                    displaySignedAmount(move.deltaSat),
                    displaySignedAmount(move.priceEffectSat),
                    (if (pct >= 0) "+" else "−") + "%.1f%%".format(kotlin.math.abs(pct)),
                    displaySignedAmount(move.spendingEffectSat),
                ),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .testTag(BudgetTags.moveNote(row.category)),
            )
        }
    }
}

/**
 * Status chip: the word is the signal (WCAG 1.4.1, never colour
 * alone); the tint only reinforces it.
 */
@Composable
private fun StatusChip(status: BudgetStatus, category: String) {
    val color = statusColor(status)
    val label = when (status) {
        BudgetStatus.UNDER -> stringResource(Res.string.tracker_budget_status_under)
        BudgetStatus.AT_RISK -> stringResource(Res.string.tracker_budget_status_at_risk)
        BudgetStatus.OVER -> stringResource(Res.string.tracker_budget_status_over)
        BudgetStatus.REACHED -> stringResource(Res.string.tracker_budget_status_reached)
        BudgetStatus.BEHIND -> stringResource(Res.string.tracker_budget_status_behind)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(BudgetTags.statusChip(category)),
    ) {
        Text(text = label, style = BhodlTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: String, column: BudgetCol) {
    Text(
        text = text,
        style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
        color = BhodlTheme.colors.textTertiary,
        modifier = Modifier.tableCell(this, column).testTag(BUDGET_TABLE.headerTag(column)),
    )
}

@Composable
private fun InlineProgressBar(
    ratio: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(BhodlTheme.colors.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(ratio.coerceAtMost(1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

/** Bar / chip tint, derived from the same [budgetStatus] as the chip text. */
@Composable
private fun statusColor(status: BudgetStatus?): androidx.compose.ui.graphics.Color = when (status) {
    BudgetStatus.UNDER, BudgetStatus.REACHED -> BhodlTheme.colors.financialPositive
    BudgetStatus.AT_RISK -> BhodlTheme.colors.financialPending
    BudgetStatus.OVER, BudgetStatus.BEHIND -> BhodlTheme.colors.financialNegative
    null -> BhodlTheme.colors.textTertiary
}

/**
 * Phase 4.3 — inline dialog to set / clear a budget for a category.
 * Numeric input (sat), Clear button to delete the budget, Save to
 * upsert.
 */
@Composable
internal fun BudgetEditDialog(
    category: String,
    currentBudgetSat: ULong?,
    onDismiss: () -> Unit,
    onSave: (sat: ULong) -> Unit,
    onClear: () -> Unit,
) {
    var input by remember(category) {
        mutableStateOf(currentBudgetSat?.toString() ?: "")
    }
    val parsed: ULong? = input.trim().takeIf { it.isNotBlank() }?.toULongOrNull()
    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.trp_budget_dialog_title, categoryDisplayName(category))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.trp_budget_dialog_body),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.trp_hint_budget)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(BudgetTags.INPUT),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onSave) },
                enabled = parsed != null,
                modifier = Modifier.testTag(BudgetTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentBudgetSat != null) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag(BudgetTags.CLEAR),
                    ) { Text(stringResource(Res.string.trp_budget_clear)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

/** Flow direction the budget table represents. */
internal enum class TrackingDirection { INCOME, EXPENSE }

/** Computed row for the budget breakdown table. */
internal data class BudgetRow(
    val category: String,
    /** The budget as stored (per its own period, e.g. monthly). Fed to
     *  the edit dialog. Null when the category has no budget, or when
     *  the budget belongs to the other direction (B3). */
    val budgetedSat: ULong?,
    val actualSat: ULong,
    val direction: TrackingDirection,
    /** UX review 2026-10 (B4) — [budgetedSat] prorated to the length of
     *  the selected period; this is what [actualSat] is compared to. */
    val budgetForPeriodSat: ULong? = budgetedSat,
)

/** Average month length in days (365.25 / 12). */
internal const val DAYS_PER_MONTH: Double = 365.25 / 12.0

/** Length in days of a budget's own period ("monthly" by default). */
internal fun budgetPeriodDays(period: String): Double = when (period.trim().lowercase()) {
    "weekly" -> 7.0
    "yearly", "annual", "annually" -> 365.25
    else -> DAYS_PER_MONTH
}

/**
 * UX review 2026-10 (B4) — scale a budget to a window of [periodDays]
 * days: a 1,000,000 sat/month Rent budget is 2,956,879 sat over 90
 * days. Null [periodDays] → no scaling.
 */
internal fun prorateBudget(budgetedSat: ULong, budgetPeriod: String, periodDays: Double?): ULong {
    if (periodDays == null || !periodDays.isFinite() || periodDays <= 0.0) return budgetedSat
    val scaled = budgetedSat.toDouble() * periodDays / budgetPeriodDays(budgetPeriod)
    return kotlin.math.round(scaled).toLong().coerceAtLeast(0L).toULong()
}

/**
 * Days covered by the period window. Fixed windows use their length;
 * ALL spans from the oldest confirmed event to [nowUnix] (at least one
 * day). Null when ALL has no dated events.
 */
internal fun budgetWindowDays(period: TrackingPeriod, events: List<PaymentEvent>, nowUnix: Long): Double? {
    period.days?.let { return it.toDouble() }
    val oldest = events.mapNotNull { it.timestampUnix?.toLong() }.minOrNull() ?: return null
    return ((nowUnix - oldest).toDouble() / 86_400.0).coerceAtLeast(1.0)
}

/**
 * Compute the per-category rows from the period event stream + the
 * registry's budget rows. For each direction (income / expense):
 *
 *  - take categories with an external flow > 0 for that direction
 *    (internal transfers / send_to_self never count — same rule as the
 *    KPI strip)
 *  - merge with budgeted categories that BELONG to this direction
 *  - sort by actual desc so the biggest entries surface first
 *
 * UX review 2026-10 (B3) — budgets carry no direction, so the old code
 * listed every budgeted spending category in the Income table with
 * "−100%". A category now belongs to the direction that dominates its
 * external volume in [classificationEvents] (pass the full history so
 * a quiet period doesn't flip it); a category with no history is a
 * spending budget. A budget is only attached in its own direction.
 *
 * B4 — [periodDays] prorates each budget to the window length (see
 * [prorateBudget]); null keeps the stored amount.
 */
internal fun computeBudgetRows(
    events: List<PaymentEvent>,
    budgets: List<TrackingBudgetFfi>,
    direction: TrackingDirection,
    periodDays: Double? = null,
    classificationEvents: List<PaymentEvent> = events,
): List<BudgetRow> {
    fun flowFor(e: PaymentEvent, dir: TrackingDirection): ULong = when (dir) {
        TrackingDirection.INCOME -> e.externalInflowSat()
        TrackingDirection.EXPENSE -> e.externalOutflowSat()
    }
    fun keyOf(e: PaymentEvent): String = e.category?.takeIf { it.isNotBlank() } ?: UNCATEGORISED_KEY

    val actualByCategory = HashMap<String, ULong>()
    for (e in events) {
        val v = flowFor(e, direction)
        if (v == 0UL) continue
        val key = keyOf(e)
        actualByCategory[key] = (actualByCategory[key] ?: 0UL) + v
    }
    val inByCategory = HashMap<String, ULong>()
    val outByCategory = HashMap<String, ULong>()
    for (e in classificationEvents) {
        val key = keyOf(e)
        inByCategory[key] = (inByCategory[key] ?: 0UL) + e.externalInflowSat()
        outByCategory[key] = (outByCategory[key] ?: 0UL) + e.externalOutflowSat()
    }
    fun directionOf(category: String): TrackingDirection {
        val inSat = inByCategory[category] ?: 0UL
        val outSat = outByCategory[category] ?: 0UL
        return if (inSat > outSat) TrackingDirection.INCOME else TrackingDirection.EXPENSE
    }
    val ownBudgets = budgets.filter { directionOf(it.category) == direction }
    val budgetByCategory = ownBudgets.associateBy { it.category }
    val allCategories = (actualByCategory.keys + budgetByCategory.keys).distinct()
    return allCategories
        .map { cat ->
            val b = budgetByCategory[cat]
            BudgetRow(
                category = cat,
                budgetedSat = b?.budgetedSat,
                actualSat = actualByCategory[cat] ?: 0UL,
                direction = direction,
                budgetForPeriodSat = b?.let { prorateBudget(it.budgetedSat, it.period, periodDays) },
            )
        }
        .sortedByDescending { it.actualSat }
}

internal object BudgetTags {
    const val SAVE: String = "budget.save"
    const val CLEAR: String = "budget.clear"
    const val INPUT: String = "budget.input"
    const val SATS_NOTE: String = "budget.sats-note"
    fun statusChip(category: String): String = "budget.status.${category.ifBlank { "uncategorised" }}"
    fun moveNote(category: String): String = "budget.move.${category.ifBlank { "uncategorised" }}"
    fun editChip(category: String): String = "budget.edit.${category.ifBlank { "uncategorised" }}"
}

/**
 * Variance cell text: the arrow and the sign follow actual vs budget
 * (↑ +6% = above budget), whatever the direction; good / bad is the
 * colour's job, so an over-budget expense reads "↑ +6%" in red.
 */
internal fun budgetVarianceLabel(varianceSat: Long, variancePct: Int): String = when {
    varianceSat == 0L -> "= 0%"
    varianceSat > 0L -> "↑ +$variancePct%"
    else -> "↓ −${-variancePct}%"
}
