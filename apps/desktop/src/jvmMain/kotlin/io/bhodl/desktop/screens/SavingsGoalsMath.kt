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
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.math.roundToInt

/**
 * Phase 7.2.2 — discriminator for how the user expressed the savings
 * goal target. Stored implicitly via the presence of
 * `targetPctOfNetWorth` on the row: when non-null, the user picked
 * PERCENT; when null, ABSOLUTE. The enum keeps the UI explicit so the
 * dialog doesn't have to constantly derive the kind from the data
 * shape — important when the user toggles back and forth before saving.
 */
internal enum class TargetKind { ABSOLUTE, PERCENT }

/**
 * Compute the absolute sat target that corresponds to [pct] % of the
 * given [netWorthSat]. Pure — exposed for tests. Saturates rather
 * than overflowing at the u64 ceiling, which is fine because the
 * inputs are bounded (pct ≤ 100 by FFI clamping, netWorthSat ≤ supply).
 */
internal fun targetSatFromPct(netWorthSat: ULong, pct: UInt): ULong {
    if (pct == 0u) return 0UL
    // Order matters: divide LAST to keep precision on a 1 BTC * 30 / 100
    // calc, but we also need to avoid u128-style overflow. ULong * UInt
    // in Kotlin is ULong if we cast the UInt; the product of a realistic
    // sat balance (< 21M * 1e8 = 2.1e15) and 100 is ~2.1e17, well under
    // ULong.MAX_VALUE (~1.8e19). Safe.
    val product = netWorthSat * pct.toULong()
    return product / 100UL
}

/**
 * Inverse: compute the % of [netWorthSat] that [targetSat] represents.
 * Returns null when net worth is 0 (% undefined) — callers render
 * that as "—" rather than silently divide-by-zero.
 *
 * Clamped to [0, 999] so the hint text stays compact even when the
 * user types a target larger than their current NW.
 */
internal fun pctFromTargetSat(netWorthSat: ULong, targetSat: ULong): Int? {
    if (netWorthSat == 0UL) return null
    val raw = (targetSat.toDouble() / netWorthSat.toDouble()) * 100.0
    return raw.toInt().coerceIn(0, 999)
}

/**
 * Phase 7.7 — format a Unix timestamp as YYYY-MM-DD in local time,
 * for the sinking-goal countdown label on the row.
 */
internal fun formatYmd(unix: Long): String =
    java.time.Instant.ofEpochSecond(unix)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)

/**
 * Phase 7.3 — allocation drift in percentage points (signed). Returns
 * `current_pct - target_pct` where current_pct is what the goal
 * actually represents (live current_sat / total NW × 100) and target_pct
 * is what the user wanted (`target_pct_of_net_worth`). The result can
 * be negative when the bucket is below target. Returns null when:
 *
 *  - the goal has no allocation target (`target_pct_of_net_worth` null)
 *  - the goal is MANUAL (the user updates `current_sat` by hand, so
 *    the drift would just reflect their last manual entry — noise)
 *  - total NW is 0 (% undefined)
 *
 * Pure — exposed `internal` for unit tests.
 *
 * Why ±5pp triggers the chip: that's the canonical Vanguard rebalance
 * threshold (see `docs/research-finance-tracking.md` §3.7). The chip
 * shows up at the first ≥5pp deviation, so a 30%-target goal pings
 * at 25% or 35% actual.
 */
internal fun goalAllocationDriftPp(
    goal: TrackingSavingsGoalFfi,
    liveCurrentSat: ULong,
    totalNetWorthSat: ULong,
): Int? {
    val targetPct = goal.targetPctOfNetWorth?.toInt() ?: return null
    if (GoalBucket.fromWire(goal.bucketKind) == GoalBucket.MANUAL) return null
    if (totalNetWorthSat == 0UL) return null
    val currentPct = (liveCurrentSat.toDouble() / totalNetWorthSat.toDouble() * 100.0)
        .toInt()
        .coerceIn(0, 999)
    return currentPct - targetPct
}

/** Phase 7.3 — drift threshold (in pp) below which the chip is hidden. */
internal const val GOAL_DRIFT_THRESHOLD_PP: Int = 5

// ───────────────────────── Phase 8.6 — denomination ─────────────────────────

/**
 * Phase 8.6 — goal target denomination. Defaults to SAT (matches the
 * Rust DEFAULT clause on `tracking_savings_goals.target_denomination`).
 * FIAT means the user committed to an absolute fiat amount (`€1000`)
 * and the sat-side target is derived from the live rate at render time.
 *
 * Stored as a wire string (`"sat"` / `"fiat"`) so the FFI surface
 * stays a primitive — easier to extend later (e.g. `"index"` for a
 * basket-pegged target) without breaking older clients.
 */
internal enum class GoalDenomination(val wireValue: String) {
    SAT("sat"),
    FIAT("fiat");

    companion object {
        /**
         * Tolerant parse — anything that doesn't match an exact wire
         * value (including null, empty, future variants) collapses to
         * SAT so a downgrade can't crash the dialog.
         */
        fun fromWire(value: String?): GoalDenomination = when (value?.lowercase()) {
            "fiat" -> FIAT
            else -> SAT
        }
    }
}

/**
 * Phase 8.6 — result of resolving a goal's target into a sat figure.
 *
 *  - For sat-denominated goals: `available = true` and `satOrZero` is
 *    the persisted target (or the live `total NW × pct` when PERCENT).
 *  - For fiat-denominated goals with a cached rate: `available = true`
 *    and `satOrZero` is `(fiat_amount / rate) * 1e8`.
 *  - For fiat-denominated goals without a cached rate (e.g. goal is in
 *    USD but the dashboard runs in EUR): `available = false` and
 *    `satOrZero = 0UL`. The row renders "—" + the
 *    "BTC equiv unavailable" chip in this branch; the progress bar
 *    also reads as 0% because the denominator is 0.
 *
 * Kept as a tiny value class so callers don't accidentally use
 * `liveTargetSat` without first checking `available`.
 */
internal data class EffectiveTarget(
    val satOrZero: ULong,
    val available: Boolean,
)

/**
 * Phase 8.6 — pure resolver from a [TrackingSavingsGoalFfi] to its
 * effective sat target. Exposed `internal` for unit tests so the
 * sat/fiat/percent matrix can be pinned without spinning up Compose.
 *
 * The [rateFor] callback is the seam that lets tests inject a fake
 * rate without touching [PriceCache]. Production callers pass
 * `PriceCache::rateFor` directly.
 *
 * Resolution order:
 *  1. `target_denomination == "fiat"` AND `target_fiat_amount` is
 *     positive AND a rate is available for `target_fiat_currency`
 *     → derive sat from the rate. Otherwise (no amount, no currency,
 *     no cached rate) the target is unavailable.
 *  2. `target_pct_of_net_worth` set → live NW × pct (Phase 7.2.4).
 *  3. Otherwise fall back to the persisted `target_sat` (Phase 7.2.2).
 */
internal fun effectiveTargetSat(
    goal: TrackingSavingsGoalFfi,
    totalNetWorthSat: ULong,
    rateFor: (String) -> Double?,
): EffectiveTarget {
    val denom = GoalDenomination.fromWire(goal.targetDenomination)
    if (denom == GoalDenomination.FIAT) {
        val amount = goal.targetFiatAmount
        val currency = goal.targetFiatCurrency
        if (amount == null || amount <= 0.0 || currency.isNullOrBlank()) {
            return EffectiveTarget(satOrZero = 0UL, available = false)
        }
        val rate = rateFor(currency)
        if (rate == null || rate <= 0.0) {
            return EffectiveTarget(satOrZero = 0UL, available = false)
        }
        val sat = ((amount / rate) * 100_000_000.0)
            .toLong()
            .coerceAtLeast(0L)
            .toULong()
        return EffectiveTarget(satOrZero = sat, available = true)
    }
    // Sat-denominated — keep the Phase 7.2.4 behaviour: PERCENT wins
    // when set, otherwise the persisted ABSOLUTE value.
    val sat = goal.targetPctOfNetWorth?.let { pct ->
        targetSatFromPct(totalNetWorthSat, pct)
    } ?: goal.targetSat
    return EffectiveTarget(satOrZero = sat, available = true)
}
