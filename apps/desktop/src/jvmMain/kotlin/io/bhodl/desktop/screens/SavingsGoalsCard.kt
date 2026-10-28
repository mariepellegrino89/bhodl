package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.computeGoalCurrentSat
import io.bhodl.desktop.domain.goalBucketBadgeLabel
import io.bhodl.desktop.domain.GoalBucket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.DatePickerField
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.math.roundToInt
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.gls_counts
import io.bhodl.shared.resources.gls_filter_all
import io.bhodl.shared.resources.gls_filter_savings
import io.bhodl.shared.resources.gls_filter_sinking
import io.bhodl.shared.resources.gls_filter_allocation
import io.bhodl.shared.resources.gls_current
import io.bhodl.shared.resources.gls_target
import io.bhodl.shared.resources.gls_monthly
import io.bhodl.shared.resources.gls_deadline
import io.bhodl.shared.resources.gls_allocation
import io.bhodl.shared.resources.gls_h_allocation
import io.bhodl.shared.resources.gls_h_monthly
import io.bhodl.shared.resources.gls_kind_savings
import io.bhodl.shared.resources.gls_kind_sinking
import io.bhodl.shared.resources.gls_kind_allocation
import io.bhodl.shared.resources.gls_reached
import io.bhodl.shared.resources.trp_add
import io.bhodl.shared.resources.trp_goal_auto
import io.bhodl.shared.resources.trp_goal_btc_unavailable
import io.bhodl.shared.resources.trp_goal_days_to
import io.bhodl.shared.resources.trp_goal_drift
import io.bhodl.shared.resources.trp_goal_due_today
import io.bhodl.shared.resources.trp_goal_of
import io.bhodl.shared.resources.trp_goal_past_deadline
import io.bhodl.shared.resources.trp_goal_per_month_on_track
import io.bhodl.shared.resources.trp_goal_status_ahead
import io.bhodl.shared.resources.trp_goal_status_behind
import io.bhodl.shared.resources.trp_goal_status_on_track
import io.bhodl.shared.resources.trp_goal_status_overdue
import io.bhodl.shared.resources.trp_goal_target_pct_nw
import io.bhodl.shared.resources.trp_goals_empty
import io.bhodl.shared.resources.trp_goals_title
import io.bhodl.desktop.domain.rememberGoalBucketBadgeLabels

/**
 * Phase 4.5 — Savings Goals card.
 *
 * Lists user goals with a colored progress bar and percentage. Goal
 * color is keyed via the `color` field on the row (palette key string
 * — one of cyan / orange / green / red / yellow / purple); the UI
 * maps it to a concrete theme color so the goals card retains its
 * identity across the Western/Eastern color convention flip.
 *
 * Phase 7.2.4 — the card now takes the NW context it needs to derive
 * `current_sat` for goals bound to a semantic bucket
 * (`investments` / `bitcoin_cash` / `wallet`). Manual goals are
 * unchanged: their persisted `current_sat` is shown verbatim. See
 * [GoalBuckets] for the bucket model and pure compute function.
 *
 * Tap a row to edit; tap "+ Add" to create.
 */
@Composable
internal fun SavingsGoalsCard(
    goals: List<TrackingSavingsGoalFfi>,
    /**
     * Phase 7.3 — BTC-only aggregate, used by the BITCOIN_CASH bucket
     * (a goal "1 BTC cash" must not be inflated by stock holdings).
     */
    bitcoinAssetSat: ULong,
    /**
     * Phase 7.3 — full net worth in sat (= bitcoin + investments).
     * Used for `targetSatFromPct` so "30% of NW" includes investments,
     * matching the canonical NW definition (Σ Assets − Σ Liabilities)
     * and the NetWorthHero number the user sees on the dashboard.
     */
    totalNetWorthSat: ULong,
    watchBalances: Map<String, ULong>,
    investments: List<TrackingInvestmentFfi>,
    wallets: List<WalletRecordFfi>,
    /**
     * Phase 7.4 — manual asset rows from the registry. Used by the
     * `MANUAL_ASSET` bucket (live current_sat) and by the AUTO badge
     * (resolves the asset name from `bucket_ref` so a rename doesn't
     * break the binding).
     */
    manualAssets: List<TrackingManualAssetFfi>,
    /**
     * Fiat → sat conversion. In production this is
     * `io.bhodl.desktop.prices.PriceCache::fiatToSat`. Null return is
     * fine — the helper translates it into a 0-sat bucket value so
     * the UI keeps rendering without a stale crash.
     */
    fiatToSat: (Double) -> ULong?,
    onAdd: () -> Unit,
    onEdit: (TrackingSavingsGoalFfi) -> Unit,
    /** Lots per investment — INVESTMENTS buckets value lot-managed
     *  positions on their effective (lot-summed) quantity. */
    lotsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingInvestmentLotFfi>> = emptyMap(),
    /** Per-currency BTC rate (production `PriceCache::rateFor`); null =
     *  legacy dashboard-currency conversion via [fiatToSat]. */
    rateFor: ((String) -> Double?)? = null,
) {
    // Goals redesign 2026-10 (proposal A) — every goal's live numbers
    // first, so the header can count them and the filter can pick.
    val nowUnix = rememberTickingNowUnix()
    val views = goals.map { g ->
        goalView(
            goal = g,
            currentSat = computeGoalCurrentSat(
                goal = g,
                bitcoinAssetSat = bitcoinAssetSat,
                watchBalances = watchBalances,
                investments = investments,
                manualAssets = manualAssets,
                fiatToSat = fiatToSat,
                lotsByInvestmentId = lotsByInvestmentId,
                rateFor = rateFor,
            ),
            target = effectiveTargetSat(goal = g, totalNetWorthSat = totalNetWorthSat, rateFor = PriceCache::rateFor),
            totalNetWorthSat = totalNetWorthSat,
            nowUnix = nowUnix,
        )
    }
    var filter by remember { mutableStateOf(GoalFilter.ALL) }
    val badgeLabels = rememberGoalBucketBadgeLabels()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.trp_goals_title),
                    style = BhodlTheme.typography.titleMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                if (views.isNotEmpty()) {
                    val c = goalCounts(views)
                    Text(
                        stringResource(Res.string.gls_counts, c.total, c.onTrack, c.behind, c.reached),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag(GoalTags.ADD),
            ) {
                Text(stringResource(Res.string.trp_add), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.accentNeon)
            }
        }
        if (goals.isEmpty()) {
            Text(
                text = stringResource(Res.string.trp_goals_empty),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
            )
            return@Column
        }
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GoalFilter.entries.forEach { f ->
                val on = f == filter
                Text(
                    stringResource(f.label),
                    style = BhodlTheme.typography.labelMedium,
                    color = if (on) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(1.dp, if (on) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.outline, RoundedCornerShape(999.dp))
                        .clickable { filter = f }
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                        .testTag(GoalTags.filter(f)),
                )
            }
        }
        val shown = views.filter { filter == GoalFilter.ALL || it.kind == filter }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = when {
                maxWidth >= 1150.dp -> 3
                maxWidth >= 720.dp -> 2
                else -> 1
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                shown.chunked(perRow).forEach { rowViews ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min),
                    ) {
                        rowViews.forEach { v ->
                            GoalCard(
                                view = v,
                                bucketBadge = goalBucketBadgeLabel(v.goal, wallets, manualAssets, badgeLabels),
                                nowUnix = nowUnix,
                                onClick = { onEdit(v.goal) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        repeat(perRow - rowViews.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Goals redesign 2026-10 — the type filter above the cards. */
internal enum class GoalFilter(val label: org.jetbrains.compose.resources.StringResource) {
    ALL(Res.string.gls_filter_all),
    SAVINGS(Res.string.gls_filter_savings),
    SINKING(Res.string.gls_filter_sinking),
    ALLOCATION(Res.string.gls_filter_allocation),
}

/** One goal's live numbers, computed once for the counts and the card. */
internal data class GoalView(
    val goal: TrackingSavingsGoalFfi,
    val currentSat: ULong,
    val targetSat: ULong,
    val targetAvailable: Boolean,
    val ratio: Float,
    val status: SinkingStatus?,
    val driftPp: Int?,
    val kind: GoalFilter,
) {
    val pct: Int get() = (ratio * 100f).coerceAtLeast(0f).roundToInt()
    val reached: Boolean get() = targetAvailable && targetSat > 0UL && currentSat >= targetSat
}

internal fun goalView(
    goal: TrackingSavingsGoalFfi,
    currentSat: ULong,
    target: EffectiveTarget,
    totalNetWorthSat: ULong,
    nowUnix: Long,
): GoalView {
    val targetSat = target.satOrZero
    val ratio = if (targetSat > 0UL) (currentSat.toDouble() / targetSat.toDouble()).toFloat() else 0f
    val kind = when {
        goal.targetPctOfNetWorth != null -> GoalFilter.ALLOCATION
        GoalKind.fromWire(goal.goalKind) == GoalKind.SINKING -> GoalFilter.SINKING
        else -> GoalFilter.SAVINGS
    }
    return GoalView(
        goal = goal,
        currentSat = currentSat,
        targetSat = targetSat,
        targetAvailable = target.available,
        ratio = ratio,
        status = if (target.available) sinkingScheduleStatus(goal, currentSat, nowUnix, targetSat = targetSat) else null,
        driftPp = goalAllocationDriftPp(goal, currentSat, totalNetWorthSat),
        kind = kind,
    )
}

internal data class GoalCounts(val total: Int, val onTrack: Int, val behind: Int, val reached: Int)

/** Header counts: on track = sinking on/ahead of schedule; behind = behind or overdue. */
internal fun goalCounts(views: List<GoalView>): GoalCounts = GoalCounts(
    total = views.size,
    onTrack = views.count { !it.reached && (it.status == SinkingStatus.ON_TRACK || it.status == SinkingStatus.AHEAD) },
    behind = views.count { !it.reached && (it.status == SinkingStatus.BEHIND || it.status == SinkingStatus.OVERDUE) },
    reached = views.count { it.reached },
)

@Composable
private fun ProgressRing(ratio: Float, color: Color, pct: Int) {
    val track = BhodlTheme.colors.surfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(72.dp)) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 8.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            val inset = 4.dp.toPx()
            val box = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            val tl = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, -90f, 360f, false, tl, box, style = stroke)
            drawArc(color, -90f, 360f * ratio.coerceIn(0f, 1f), false, tl, box, style = stroke)
        }
        Text("$pct%", style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = BhodlTheme.colors.textPrimary)
    }
}

@Composable
private fun GoalTile(label: String, modifier: Modifier, help: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(BhodlTheme.colors.background)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textTertiary, maxLines = 1)
            help?.let { io.bhodl.desktop.ui.HelpTip(it) }
        }
        content()
    }
}

@Composable
private fun GoalCard(
    view: GoalView,
    bucketBadge: String?,
    nowUnix: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val goal = view.goal
    val color = goalPaletteColor(goal.color)
    Column(
        modifier = modifier
            .bhodlCard()
            .clickable(onClick = onClick)
            .padding(16.dp)
            .testTag(GoalTags.card(goal.id)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(view.ratio, color, view.pct)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(goal.name, style = BhodlTheme.typography.titleMedium, color = BhodlTheme.colors.textPrimary)
                Text(stringResource(view.kind.kindLabel()), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (view.reached) {
                        ReachedChip()
                    } else if (view.status != null) {
                        ScheduleStatusChip(view.status)
                    }
                    val drift = view.driftPp
                    if (drift != null && kotlin.math.abs(drift) >= GOAL_DRIFT_THRESHOLD_PP) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(BhodlTheme.colors.financialPending.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag(GoalTags.DRIFT_CHIP),
                        ) {
                            val sign = io.bhodl.desktop.ui.amountSign(drift.toLong(), masked = false)
                            Text(
                                text = stringResource(Res.string.trp_goal_drift, sign + kotlin.math.abs(drift)),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.financialPending,
                            )
                        }
                    }
                    if (bucketBadge != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(color.copy(alpha = 0.18f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .testTag(GoalTags.AUTO_BADGE),
                        ) {
                            Text(stringResource(Res.string.trp_goal_auto, bucketBadge), style = BhodlTheme.typography.labelMedium, color = color)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GoalTile(stringResource(Res.string.gls_current), Modifier.weight(1f)) {
                Money(
                    value = MoneyValue.fromSat(view.currentSat.toLong(), semantic = MoneySemantic.AtNow),
                    density = MoneyDensity.Compact,
                    primary = MoneyPrimary.Btc,
                    showSecondary = false,
                    testTag = GoalTags.currentAmountTag(goal.id),
                )
            }
            GoalTile(stringResource(Res.string.gls_target), Modifier.weight(1f)) {
                if (!view.targetAvailable) {
                    Text(
                        "—",
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                        modifier = Modifier.testTag(GoalTags.targetAmountTag(goal.id)),
                    )
                } else {
                    Money(
                        value = MoneyValue.fromSat(view.targetSat.toLong(), semantic = MoneySemantic.AtNow),
                        density = MoneyDensity.Compact,
                        primary = MoneyPrimary.Btc,
                        showSecondary = false,
                        testTag = GoalTags.targetAmountTag(goal.id),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val monthly = if (view.kind == GoalFilter.SINKING && view.targetAvailable) {
                sinkingMonthlyRateSat(goal, view.currentSat, nowUnix, targetSat = view.targetSat)
            } else {
                null
            }
            GoalTile(stringResource(Res.string.gls_monthly), Modifier.weight(1f), help = stringResource(Res.string.gls_h_monthly)) {
                if (monthly != null) {
                    Money(
                        value = MoneyValue.fromSat(monthly.toLong(), semantic = MoneySemantic.AtNow),
                        density = MoneyDensity.Compact,
                        primary = MoneyPrimary.Btc,
                        showSecondary = false,
                    )
                } else {
                    Text("—", style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textTertiary)
                }
            }
            val lastLabel = if (view.kind == GoalFilter.ALLOCATION) Res.string.gls_allocation else Res.string.gls_deadline
            val lastHelp = if (view.kind == GoalFilter.ALLOCATION) stringResource(Res.string.gls_h_allocation) else null
            GoalTile(stringResource(lastLabel), Modifier.weight(1f), help = lastHelp) {
                val deadline = goal.deadlineUnix
                val text = when {
                    view.kind == GoalFilter.ALLOCATION ->
                        stringResource(Res.string.trp_goal_target_pct_nw, goal.targetPctOfNetWorth.toString())
                    deadline != null -> formatYmd(deadline)
                    else -> "—"
                }
                Text(text, style = BhodlTheme.typography.mono, color = BhodlTheme.colors.textPrimary)
            }
        }
        if (!view.targetAvailable) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(BhodlTheme.colors.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag(GoalTags.BTC_EQUIV_UNAVAILABLE_CHIP),
            ) {
                Text(
                    stringResource(Res.string.trp_goal_btc_unavailable),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
        if (view.kind == GoalFilter.SINKING) {
            val daysLeft = sinkingDaysRemaining(goal, nowUnix)
            val deadline = goal.deadlineUnix
            if (deadline != null && daysLeft != null) {
                val deadlineLabel = formatYmd(deadline)
                Text(
                    when {
                        daysLeft < 0 -> stringResource(Res.string.trp_goal_past_deadline, -daysLeft, deadlineLabel)
                        daysLeft == 0 -> stringResource(Res.string.trp_goal_due_today, deadlineLabel)
                        else -> stringResource(Res.string.trp_goal_days_to, daysLeft, deadlineLabel)
                    },
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
    }
}

private fun GoalFilter.kindLabel(): org.jetbrains.compose.resources.StringResource = when (this) {
    GoalFilter.SINKING -> Res.string.gls_kind_sinking
    GoalFilter.ALLOCATION -> Res.string.gls_kind_allocation
    else -> Res.string.gls_kind_savings
}

@Composable
private fun ReachedChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(BhodlTheme.colors.financialPositive.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(stringResource(Res.string.gls_reached), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.financialPositive)
    }
}

/**
 * Phase 7.7 — small chip rendering the [SinkingStatus] of a sinking
 * goal: ON TRACK (green), BEHIND (red), AHEAD (yellow), OVERDUE (red
 * with neutral text). Placed between the goal name and the percent
 * column.
 */
@Composable
private fun ScheduleStatusChip(status: SinkingStatus) {
    val (label, color) = when (status) {
        SinkingStatus.ON_TRACK -> stringResource(Res.string.trp_goal_status_on_track) to BhodlTheme.colors.financialPositive
        SinkingStatus.AHEAD -> stringResource(Res.string.trp_goal_status_ahead) to BhodlTheme.colors.financialPending
        SinkingStatus.BEHIND -> stringResource(Res.string.trp_goal_status_behind) to BhodlTheme.colors.financialNegative
        SinkingStatus.OVERDUE -> stringResource(Res.string.trp_goal_status_overdue) to BhodlTheme.colors.financialNegative
    }
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(GoalTags.SCHEDULE_CHIP),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * Map the palette key string to a concrete theme color. Falls back
 * to `accentNeon` for unknown keys so future palette additions don't
 * crash older builds.
 */
@Composable
internal fun goalPaletteColor(key: String): Color = when (key.lowercase()) {
    "cyan" -> BhodlTheme.colors.accentNeon
    "orange" -> BhodlTheme.colors.brandPrimary
    "green" -> BhodlTheme.colors.financialPositive
    "red" -> BhodlTheme.colors.financialNegative
    "yellow" -> BhodlTheme.colors.financialPending
    "purple" -> BhodlTheme.colors.chartCategorical.getOrElse(2) { BhodlTheme.colors.accentNeon }
    else -> BhodlTheme.colors.accentNeon
}

internal object GoalTags {
    const val ADD: String = "goal.add"
    fun card(id: Long): String = "goal.card.$id"
    fun filter(f: GoalFilter): String = "goal.filter.${f.name.lowercase()}"
    const val SAVE: String = "goal.save"
    const val DELETE: String = "goal.delete"
    const val INPUT_NAME: String = "goal.input-name"
    const val INPUT_TARGET: String = "goal.input-target"
    const val INPUT_CURRENT: String = "goal.input-current"
    /** Phase 7.2.1 — allocation target input (% of net worth). */
    const val INPUT_PCT_NW: String = "goal.input-pct-nw"
    /** Phase 7.2.2 — target-kind toggle chips. */
    const val TARGET_KIND_ABSOLUTE: String = "goal.target-kind.absolute"
    const val TARGET_KIND_PERCENT: String = "goal.target-kind.percent"
    /** Phase 7.2.4 — AUTO badge on a bucket-bound goal row. */
    const val AUTO_BADGE: String = "goal.auto-badge"
    /** Phase 7.3 — allocation drift chip (|drift| ≥ 5pp). */
    const val DRIFT_CHIP: String = "goal.drift-chip"
    /** Phase 7.2.4 — bucket picker rows. */
    const val BUCKET_MANUAL: String = "goal.bucket.manual"
    const val BUCKET_INVESTMENTS: String = "goal.bucket.investments"
    const val BUCKET_BITCOIN_CASH: String = "goal.bucket.bitcoin-cash"
    const val BUCKET_WALLET: String = "goal.bucket.wallet"
    /** Phase 7.4 — MANUAL_ASSET bucket option in the picker. */
    const val BUCKET_MANUAL_ASSET: String = "goal.bucket.manual-asset"
    /** Phase 7.2.4 — read-only mirror of the live current_sat. */
    const val CURRENT_PREVIEW: String = "goal.current-preview"
    /** Per-wallet chip in the WALLET bucket sub-picker. */
    fun bucketWalletTag(walletId: String): String = "goal.bucket.wallet.$walletId"
    /** Per-asset chip in the MANUAL_ASSET bucket sub-picker. */
    fun bucketManualAssetTag(assetId: Long): String = "goal.bucket.manual-asset.$assetId"
    /** Phase 7.7 — goal kind chips. */
    const val GOAL_KIND_SAVINGS: String = "goal.kind.savings"
    const val GOAL_KIND_SINKING: String = "goal.kind.sinking"
    /** Phase 7.7 — sinking-goal deadline input. */
    const val INPUT_DEADLINE: String = "goal.input-deadline"
    /** Phase 7.7 — ON TRACK / BEHIND / AHEAD / OVERDUE chip on the row. */
    const val SCHEDULE_CHIP: String = "goal.schedule-chip"
    /** Phase 8.3.4 — current_sat rendered via `<Money>` Medium. */
    fun currentAmountTag(id: Long): String = "goal.current.$id"
    /** Phase 8.3.4 — target_sat rendered via `<Money>` Medium. */
    fun targetAmountTag(id: Long): String = "goal.target.$id"
    /** Phase 8.6 — denomination chips (sat / fiat). */
    const val DENOM_SAT: String = "goal.denom.sat"
    const val DENOM_FIAT: String = "goal.denom.fiat"
    /** Phase 8.6 — fiat-denominated goal inputs. */
    const val INPUT_FIAT_AMOUNT: String = "goal.input-fiat-amount"
    const val INPUT_FIAT_CURRENCY: String = "goal.input-fiat-currency"
    /**
     * Phase 8.6 — chip shown next to the target column when the goal
     * is fiat-denominated and the PriceCache holds no rate for the
     * goal's currency (typically a goal in EUR while the dashboard
     * runs in USD, or vice-versa).
     */
    const val BTC_EQUIV_UNAVAILABLE_CHIP: String = "goal.btc-equiv-unavailable"
}

