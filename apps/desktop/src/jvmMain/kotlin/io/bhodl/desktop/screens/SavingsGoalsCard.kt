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
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.math.roundToInt
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.trp_goals_title),
                style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(GoalTags.ADD),
            ) {
                Text(
                    text = stringResource(Res.string.trp_add),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.accentNeon,
                )
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
        goals.forEach { g ->
            GoalRow(
                goal = g,
                bitcoinAssetSat = bitcoinAssetSat,
                totalNetWorthSat = totalNetWorthSat,
                watchBalances = watchBalances,
                investments = investments,
                wallets = wallets,
                manualAssets = manualAssets,
                fiatToSat = fiatToSat,
                lotsByInvestmentId = lotsByInvestmentId,
                rateFor = rateFor,
                onClick = { onEdit(g) },
            )
        }
    }
}

@Composable
private fun GoalRow(
    goal: TrackingSavingsGoalFfi,
    bitcoinAssetSat: ULong,
    totalNetWorthSat: ULong,
    watchBalances: Map<String, ULong>,
    investments: List<TrackingInvestmentFfi>,
    wallets: List<WalletRecordFfi>,
    manualAssets: List<TrackingManualAssetFfi>,
    fiatToSat: (Double) -> ULong?,
    lotsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingInvestmentLotFfi>>,
    rateFor: ((String) -> Double?)?,
    onClick: () -> Unit,
) {
    // Phase 7.2.4 — current_sat is auto-derived for bucket-bound
    // goals; manual goals keep their hand-entered value.
    val liveCurrentSat = computeGoalCurrentSat(
        goal = goal,
        bitcoinAssetSat = bitcoinAssetSat,
        watchBalances = watchBalances,
        investments = investments,
        manualAssets = manualAssets,
        fiatToSat = fiatToSat,
        lotsByInvestmentId = lotsByInvestmentId,
        rateFor = rateFor,
    )
    // Phase 7.2.4 + 7.3 — target_sat is recomputed live when the goal
    // uses the PERCENT mode. The persisted `target_sat` is treated as
    // the ABSOLUTE-mode fallback; in PERCENT mode the live NW × pct
    // wins so "always keep 30% in investments" tracks the
    // current NW instead of a stale snapshot taken at save time.
    // Phase 7.3: the "NW" for the % conversion is the *total* (= BTC
    // + investments), matching what the user sees on the hero — not
    // just BTC. So if the user adds €5000 of stocks, a "30% NW" goal's
    // target sat goes up accordingly.
    //
    // Phase 8.6 — when the goal is fiat-denominated, `target_sat` is
    // ignored; we derive the effective sat target live from the rate
    // for `target_fiat_currency`. When the cache has no rate for that
    // currency (multi-currency PriceCache is out of scope), the helper
    // returns null and the row renders "—" + a small "BTC equiv
    // unavailable" chip instead of an amount.
    val effectiveTarget: EffectiveTarget = effectiveTargetSat(
        goal = goal,
        totalNetWorthSat = totalNetWorthSat,
        rateFor = PriceCache::rateFor,
    )
    val liveTargetSat: ULong = effectiveTarget.satOrZero
    val targetAvailable: Boolean = effectiveTarget.available

    val ratio: Float = if (liveTargetSat > 0UL) {
        (liveCurrentSat.toDouble() / liveTargetSat.toDouble()).toFloat()
    } else 0f
    val pct = (ratio * 100.0f).coerceAtLeast(0f).roundToInt()
    val color = goalPaletteColor(goal.color)
    val bucketBadge = goalBucketBadgeLabel(goal, wallets, manualAssets, rememberGoalBucketBadgeLabels())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Phase 7.3 — drift in pp (signed); null when not applicable.
        val driftPp: Int? = goalAllocationDriftPp(goal, liveCurrentSat, totalNetWorthSat)
        // Phase 7.7 — sinking-goal schedule status (ON TRACK / BEHIND
        // / AHEAD / OVERDUE) computed live from current_sat and the
        // deadline; null when the goal isn't sinking.
        // Ticks at local midnight so countdown / OVERDUE stay current.
        val nowUnix = rememberTickingNowUnix()
        val scheduleStatus = if (targetAvailable) {
            sinkingScheduleStatus(goal, liveCurrentSat, nowUnix, targetSat = liveTargetSat)
        } else null
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = goal.name,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            // Phase 7.7 — schedule chip for sinking goals, between the
            // name and (eventually) the DRIFT chip. The colour tells
            // the user at a glance "am I on schedule?".
            if (scheduleStatus != null) {
                ScheduleStatusChip(scheduleStatus)
            }
            // Phase 7.3 — only show DRIFT chip when |drift| ≥ ±5pp.
            // Sits between the name and the % progress so the user's
            // eye lands on it before reading the bar.
            if (driftPp != null && kotlin.math.abs(driftPp) >= GOAL_DRIFT_THRESHOLD_PP) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BhodlTheme.colors.financialPending.copy(alpha = 0.18f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag(GoalTags.DRIFT_CHIP),
                ) {
                    val sign = if (driftPp > 0) "+" else ""
                    Text(
                        text = stringResource(Res.string.trp_goal_drift, "$sign$driftPp"),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.financialPending,
                    )
                }
            }
            Text(
                text = "$pct%",
                style = BhodlTheme.typography.bodyMedium,
                color = color,
            )
        }
        // Phase 7.2.4 — AUTO badge sits between the title and the bar
        // when the goal is bound to a non-manual bucket. Single chip;
        // colour follows the goal palette so it doesn't fight for
        // attention with the progress bar.
        if (bucketBadge != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(color.copy(alpha = 0.18f))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag(GoalTags.AUTO_BADGE),
            ) {
                Text(
                    text = stringResource(Res.string.trp_goal_auto, bucketBadge),
                    style = BhodlTheme.typography.labelMedium,
                    color = color,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BhodlTheme.colors.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(color),
            )
        }
        // Allocation target ("30% del net worth") surfaces alongside
        // the absolute progress line when set. With 7.2.4's live
        // recomputation this line now reflects the *current* sat
        // value of the percentage at the *current* NW, not a frozen
        // snapshot.
        //
        // Phase 8.3.4 — current and target sats now render via the
        // canonical `<Money>` Medium composable so the BTC primary +
        // fiat secondary stay consistent with NetWorthHero and the
        // Investments table. The allocation suffix stays a plain
        // [Text] because it carries metadata, not an amount.
        val allocationLine = goal.targetPctOfNetWorth?.let { " · " + stringResource(Res.string.trp_goal_target_pct_nw, it.toString()) }.orEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Money(
                value = MoneyValue.fromSat(liveCurrentSat.toLong(), semantic = MoneySemantic.AtNow),
                density = MoneyDensity.Medium,
                primary = MoneyPrimary.Btc,
                showSecondary = true,
                testTag = GoalTags.currentAmountTag(goal.id),
            )
            Text(
                text = stringResource(Res.string.trp_goal_of),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
            // Phase 8.6 — when the fiat goal can't be converted to sat
            // (no cached rate for `target_fiat_currency`), render "—"
            // followed by a small chip explaining why. The progress
            // bar above already shows 0% because `liveTargetSat = 0UL`
            // in this case; the chip prevents the user from reading
            // the row as "0 BTC progress against a 0 sat goal" — which
            // would suggest a misconfigured goal rather than a
            // transient cache miss.
            if (!targetAvailable) {
                Text(
                    text = "—",
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.testTag(GoalTags.targetAmountTag(goal.id)),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(BhodlTheme.colors.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag(GoalTags.BTC_EQUIV_UNAVAILABLE_CHIP),
                ) {
                    Text(
                        text = stringResource(Res.string.trp_goal_btc_unavailable),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
            } else {
                Money(
                    value = MoneyValue.fromSat(
                        liveTargetSat.toLong(),
                        semantic = MoneySemantic.AtNow,
                    ),
                    density = MoneyDensity.Medium,
                    primary = MoneyPrimary.Btc,
                    showSecondary = true,
                    testTag = GoalTags.targetAmountTag(goal.id),
                )
            }
            if (allocationLine.isNotEmpty()) {
                Text(
                    text = allocationLine.removePrefix(" "),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }

        // Phase 7.7 — sinking countdown + suggested monthly rate.
        // Only renders for sinking goals; the SCHEDULE_TOLERANCE chip
        // above already signals AHEAD/ON_TRACK/BEHIND/OVERDUE.
        //
        // Phase 8.3.4 — the monthly-rate line goes through the
        // canonical `<Money>` Compact composable so the BTC primary
        // stays consistent with the rest of the goal row. The split
        // into two Text + one Money in a Row keeps the countdown
        // textual context inline with the suggested rate.
        if (GoalKind.fromWire(goal.goalKind) == GoalKind.SINKING) {
            val daysLeft = sinkingDaysRemaining(goal, nowUnix)
            val monthlyRate = if (targetAvailable) {
                sinkingMonthlyRateSat(goal, liveCurrentSat, nowUnix, targetSat = liveTargetSat)
            } else null
            val deadline = goal.deadlineUnix
            if (deadline != null) {
                val deadlineLabel = formatYmd(deadline)
                val countdownLabel = when {
                    daysLeft == null -> ""
                    daysLeft < 0 -> stringResource(Res.string.trp_goal_past_deadline, -daysLeft, deadlineLabel)
                    daysLeft == 0 -> stringResource(Res.string.trp_goal_due_today, deadlineLabel)
                    else -> stringResource(Res.string.trp_goal_days_to, daysLeft, deadlineLabel)
                }
                if (monthlyRate != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "$countdownLabel · ~",
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                        Money(
                            value = MoneyValue.fromSat(
                                monthlyRate.toLong(),
                                semantic = MoneySemantic.AtNow,
                            ),
                            density = MoneyDensity.Compact,
                            primary = MoneyPrimary.Btc,
                            showSecondary = false,
                        )
                        Text(
                            text = stringResource(Res.string.trp_goal_per_month_on_track),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                    }
                } else {
                    Text(
                        text = countdownLabel,
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
            }
        }
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

