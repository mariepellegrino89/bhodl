package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.GoalBucket

import io.bhodl.ffi.TrackingSavingsGoalFfi

/**
 * Phase 7.7 — goal kind enum (savings vs sinking) and the pure helpers
 * that compute "ON TRACK" status, monthly funding rate, and countdown
 * days to deadline.
 *
 * `goal_kind` is orthogonal to [GoalBucket]: a sinking goal can bind
 * to the `investments` bucket, etc.
 */
internal enum class GoalKind(public val wireValue: String) {
    /** Legacy default — open-ended target, no deadline contract. */
    SAVINGS("savings"),

    /** Target with a hard deadline + suggested monthly funding rate. */
    SINKING("sinking");

    public companion object {
        public fun fromWire(s: String?): GoalKind = when (s) {
            "sinking" -> SINKING
            else -> SAVINGS
        }
    }
}

internal enum class SinkingStatus { ON_TRACK, AHEAD, BEHIND, OVERDUE }

private const val DAY_SEC: Long = 86_400L
private const val SCHEDULE_TOLERANCE: Double = 0.05  // ±5%

/**
 * Phase 7.7 — days remaining until the goal's deadline. Negative when
 * the deadline has passed; null when the goal isn't a sinking goal or
 * has no deadline.
 */
internal fun sinkingDaysRemaining(goal: TrackingSavingsGoalFfi, nowUnix: Long): Int? {
    if (GoalKind.fromWire(goal.goalKind) != GoalKind.SINKING) return null
    val deadline = goal.deadlineUnix ?: return null
    return ((deadline - nowUnix) / DAY_SEC).toInt()
}

/**
 * Phase 7.7 — suggested monthly funding rate to reach the target by
 * the deadline. Returns null when:
 *  - goal isn't sinking, OR no deadline
 *  - current ≥ target (you're done — nothing to fund)
 *  - deadline already passed (rate is undefined; UI renders OVERDUE)
 */
internal fun sinkingMonthlyRateSat(
    goal: TrackingSavingsGoalFfi,
    currentSat: ULong,
    nowUnix: Long,
    /** The EFFECTIVE target ([effectiveTargetSat]) — differs from the
     *  persisted `targetSat` for %-of-NW and fiat-denominated goals. */
    targetSat: ULong = goal.targetSat,
): ULong? {
    if (GoalKind.fromWire(goal.goalKind) != GoalKind.SINKING) return null
    val deadline = goal.deadlineUnix ?: return null
    if (targetSat == 0UL) return null
    if (currentSat >= targetSat) return null
    val daysRemaining = (deadline - nowUnix) / DAY_SEC
    if (daysRemaining <= 0L) return null
    val missing = targetSat - currentSat
    // Min 1 day → ~0.033 months so the rate doesn't explode when the
    // deadline is right around the corner. The UI flips to "BEHIND"
    // for sub-month windows anyway.
    val monthsRemaining = (daysRemaining.toDouble() / 30.0).coerceAtLeast(1.0 / 30.0)
    val rate = (missing.toDouble() / monthsRemaining).toLong()
    return rate.coerceAtLeast(0L).toULong()
}

/**
 * Phase 7.7 — schedule status. Compares current progress against the
 * linear "expected at this point in time" curve. Tolerance ±5% keeps
 * the chip stable as the user makes small progress jumps.
 *
 * Returns null when:
 *  - goal isn't sinking, OR no deadline, OR createdAt is missing
 *  - the goal hasn't started accumulating yet (current_sat = 0 AND
 *    elapsed time < 1 day — too early to call BEHIND)
 *
 * Returns OVERDUE when the deadline has passed and the goal is NOT
 * complete. A goal that reached its target is AHEAD (done) even after
 * the deadline — flagging a completed fund as OVERDUE was misleading.
 */
internal fun sinkingScheduleStatus(
    goal: TrackingSavingsGoalFfi,
    currentSat: ULong,
    nowUnix: Long,
    /** The EFFECTIVE target ([effectiveTargetSat]) — differs from the
     *  persisted `targetSat` for %-of-NW and fiat-denominated goals. */
    targetSat: ULong = goal.targetSat,
): SinkingStatus? {
    if (GoalKind.fromWire(goal.goalKind) != GoalKind.SINKING) return null
    val deadline = goal.deadlineUnix ?: return null
    val target = targetSat
    // Already done: AHEAD regardless of schedule math or deadline.
    if (target > 0UL && currentSat >= target) return SinkingStatus.AHEAD
    if (nowUnix >= deadline) return SinkingStatus.OVERDUE

    val createdAt = goal.createdAtUnix
    val totalSec = (deadline - createdAt).coerceAtLeast(DAY_SEC)
    val elapsedSec = (nowUnix - createdAt).coerceAtLeast(0L)
    if (elapsedSec < DAY_SEC && currentSat == 0UL) return null

    if (target == 0UL) return null

    val expectedAtNow = target.toDouble() * (elapsedSec.toDouble() / totalSec.toDouble())
    if (expectedAtNow <= 0.0) return SinkingStatus.ON_TRACK

    val ratio = currentSat.toDouble() / expectedAtNow
    return when {
        ratio >= 1.0 + SCHEDULE_TOLERANCE -> SinkingStatus.AHEAD
        ratio >= 1.0 - SCHEDULE_TOLERANCE -> SinkingStatus.ON_TRACK
        else -> SinkingStatus.BEHIND
    }
}
