package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.AppClock
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.desktop.ui.HelpTip
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.hmc_composition
import io.bhodl.shared.resources.hmc_composition_help
import io.bhodl.shared.resources.hmc_open_portfolio
import io.bhodl.shared.resources.hmc_todo
import io.bhodl.shared.resources.hmc_todo_action_categorize
import io.bhodl.shared.resources.hmc_todo_action_confirm
import io.bhodl.shared.resources.hmc_todo_action_open
import io.bhodl.shared.resources.hmc_todo_action_update
import io.bhodl.shared.resources.hmc_todo_action_view
import io.bhodl.shared.resources.hmc_todo_bill_due
import io.bhodl.shared.resources.hmc_todo_bill_today
import io.bhodl.shared.resources.hmc_todo_help
import io.bhodl.shared.resources.hmc_todo_none
import io.bhodl.shared.resources.hmc_todo_recurring
import io.bhodl.shared.resources.hmc_todo_review
import io.bhodl.shared.resources.hmc_todo_stale_asset
import io.bhodl.shared.resources.hmc_todo_uncategorized
import io.bhodl.shared.resources.pfc_class_bitcoin
import io.bhodl.shared.resources.pfc_class_investments
import io.bhodl.shared.resources.pfc_class_liabilities
import io.bhodl.shared.resources.pfc_class_manual
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/*
 * Home redesign 2026-10 (proposal A) — the right rail next to the net
 * worth hero: the composition of the net worth (links to Portfolio) and
 * a short "to do" list that replaces the separate review / stale banners.
 */

internal object HomeCockpitTags {
    const val COMPOSITION: String = "home.composition"
    const val TODO: String = "home.todo"
    fun todo(kind: HomeTodoKind): String = "home.todo.${kind.name.lowercase()}"
    const val TODO_NONE: String = "home.todo.none"
}

/** What a "to do" row is about; drives its text, dot colour and action. */
internal enum class HomeTodoKind { REVIEW, BILL_DUE, RECURRING, UNCATEGORIZED, STALE_ASSET }

/** One row of the "to do" list. [count] / [name] / [days] fill its text. */
internal data class HomeTodo(
    val kind: HomeTodoKind,
    val count: Int = 0,
    val name: String = "",
    val days: Long = 0,
)

/** Bills due within this many days show up. */
internal const val HOME_BILL_WINDOW_DAYS: Long = 7

/** A manual asset value older than this is flagged. */
internal const val HOME_STALE_ASSET_DAYS: Long = 180

/**
 * The "to do" list, most urgent first, at most [limit] rows:
 *  - the monthly review, while its banner would show;
 *  - the next unpaid bill due within [HOME_BILL_WINDOW_DAYS];
 *  - recurring payments waiting to be confirmed;
 *  - uncategorised transactions in [periodEvents];
 *  - the oldest manual asset value not updated in [HOME_STALE_ASSET_DAYS].
 */
internal fun homeTodos(
    nowUnix: Long,
    reviewDue: Boolean,
    bills: List<TrackingBillFfi>,
    recurringCount: Int,
    periodEvents: List<PaymentEvent>,
    manualAssets: List<TrackingManualAssetFfi>,
    limit: Int = 4,
): List<HomeTodo> {
    val today = LocalDate.ofEpochDay(Math.floorDiv(nowUnix, 86_400L))
    val out = mutableListOf<HomeTodo>()
    if (reviewDue) out += HomeTodo(HomeTodoKind.REVIEW)
    bills.mapNotNull { b ->
        if (b.dueDay.toInt() !in 1..31) return@mapNotNull null
        val paidThisMonth = b.lastPaidUnix?.let {
            val d = LocalDate.ofEpochDay(Math.floorDiv(it, 86_400L))
            d.year == today.year && d.month == today.month
        } == true
        if (paidThisMonth) return@mapNotNull null
        val days = daysUntilDayOfMonth(today, b.dueDay.toInt())
        if (days > HOME_BILL_WINDOW_DAYS) null else b to days
    }.minByOrNull { it.second }?.let { (b, d) -> out += HomeTodo(HomeTodoKind.BILL_DUE, name = b.name, days = d) }
    if (recurringCount > 0) out += HomeTodo(HomeTodoKind.RECURRING, count = recurringCount)
    val uncategorized = periodEvents.distinctBy { it.txidOrPaymentId }.count { it.category.isNullOrBlank() }
    if (uncategorized > 0) out += HomeTodo(HomeTodoKind.UNCATEGORIZED, count = uncategorized)
    manualAssets
        .map { it to ChronoUnit.DAYS.between(LocalDate.ofEpochDay(Math.floorDiv(it.lastUpdatedUnix, 86_400L)), today) }
        .filter { it.second >= HOME_STALE_ASSET_DAYS }
        .maxByOrNull { it.second }
        ?.let { (a, d) -> out += HomeTodo(HomeTodoKind.STALE_ASSET, name = a.name, days = d / 30) }
    return out.take(limit)
}

/** Today at midnight UTC, in unix seconds (test seam friendly). */
internal fun todayUnixUtc(): Long = AppClock.today(ZoneOffset.UTC).toEpochDay() * 86_400L

@Composable
private fun RailTitle(text: String, help: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text.uppercase(),
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = BhodlTheme.colors.textSecondary,
        )
        HelpTip(help)
    }
}

/** Net-worth composition: one stacked bar plus a legend; opens Portfolio. */
@Composable
internal fun HomeCompositionCard(
    netWorth: TrackerNetWorth,
    onOpenPortfolio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val roles = nwRoleColors()
    val assets = netWorth.assetsSat.toLong().coerceAtLeast(0L)
    fun share(v: ULong): Float = if (assets > 0L) v.toLong().toFloat() / assets else 0f
    val parts = listOf(
        Triple(Res.string.pfc_class_bitcoin, netWorth.walletSat, roles.btcWallets),
        Triple(Res.string.pfc_class_investments, netWorth.investmentsSat, roles.investments),
        Triple(Res.string.pfc_class_manual, netWorth.manualAssetsSat, roles.manualAssets),
    )
    Column(
        modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(HomeCockpitTags.COMPOSITION),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RailTitle(stringResource(Res.string.hmc_composition), stringResource(Res.string.hmc_composition_help))
        Row(modifier = Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(BhodlTheme.colors.surfaceVariant)) {
            parts.forEach { (_, v, c) ->
                val f = share(v)
                if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(c))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            parts.forEach { (label, v, c) -> LegendRow(stringResource(label), "${pct(share(v))}", c) }
            if (netWorth.liabilitiesSat > 0UL) {
                LegendRow(
                    stringResource(Res.string.pfc_class_liabilities),
                    "−${pct(share(netWorth.liabilitiesSat))}",
                    roles.liabilities,
                )
            }
        }
        TextButton(onClick = onOpenPortfolio, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(stringResource(Res.string.hmc_open_portfolio), style = BhodlTheme.typography.labelMedium)
        }
    }
}

private fun pct(f: Float): String = "${Math.round(f * 100f)}%"

@Composable
private fun LegendRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        Text(value, style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textSecondary)
    }
}

/** The short "to do" list, or "all in order" when empty. */
@Composable
internal fun HomeTodoCard(
    todos: List<HomeTodo>,
    onAction: (HomeTodo) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.bhodlCard().padding(BhodlTheme.spacing.md).testTag(HomeCockpitTags.TODO),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RailTitle(stringResource(Res.string.hmc_todo), stringResource(Res.string.hmc_todo_help))
        if (todos.isEmpty()) {
            Text(
                stringResource(Res.string.hmc_todo_none),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 6.dp).testTag(HomeCockpitTags.TODO_NONE),
            )
        }
        todos.forEach { t ->
            val (dot, action) = when (t.kind) {
                HomeTodoKind.REVIEW -> BhodlTheme.colors.chartCategorical.getOrElse(1) { BhodlTheme.colors.brandPrimary } to Res.string.hmc_todo_action_open
                HomeTodoKind.BILL_DUE -> BhodlTheme.colors.brandPrimary to Res.string.hmc_todo_action_view
                HomeTodoKind.RECURRING -> BhodlTheme.colors.chartCategorical.getOrElse(0) { BhodlTheme.colors.accentNeon } to Res.string.hmc_todo_action_confirm
                HomeTodoKind.UNCATEGORIZED -> BhodlTheme.colors.financialPending to Res.string.hmc_todo_action_categorize
                HomeTodoKind.STALE_ASSET -> BhodlTheme.colors.financialNegative to Res.string.hmc_todo_action_update
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button) { onAction(t) }
                    .testTag(HomeCockpitTags.todo(t.kind)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                Text(todoText(t), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary, modifier = Modifier.weight(1f))
                Text(stringResource(action), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.brandPrimary)
            }
        }
    }
}

@Composable
private fun todoText(t: HomeTodo): String = when (t.kind) {
    HomeTodoKind.REVIEW -> stringResource(Res.string.hmc_todo_review)
    HomeTodoKind.BILL_DUE ->
        if (t.days == 0L) stringResource(Res.string.hmc_todo_bill_today, t.name)
        else stringResource(Res.string.hmc_todo_bill_due, t.name, t.days)
    HomeTodoKind.RECURRING -> stringResource(Res.string.hmc_todo_recurring, t.count)
    HomeTodoKind.UNCATEGORIZED -> stringResource(Res.string.hmc_todo_uncategorized, t.count)
    HomeTodoKind.STALE_ASSET -> stringResource(Res.string.hmc_todo_stale_asset, t.name, t.days)
}

/** In / out of [eventsInPeriod], with transfers to self excluded like [computeKpis]. */
internal fun periodFlows(eventsInPeriod: List<PaymentEvent>): PeriodFlows {
    var inflow = 0UL
    var outflow = 0UL
    var inCount = 0
    var outCount = 0
    for (e in eventsInPeriod) {
        val i = e.externalInflowSat()
        val o = e.externalOutflowSat()
        inflow += i
        outflow += o
        if (i > 0UL) inCount++
        if (o > 0UL) outCount++
    }
    return PeriodFlows(inflow, outflow, inCount, outCount)
}
