package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingSavingsGoalFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7.7 — pure tests for the sinking-goal helpers.
 *
 * The chip "ON TRACK / BEHIND / AHEAD" is the load-bearing UI signal
 * for goal compliance. Pin the cliff (±5% tolerance) and the edge
 * cases (deadline passed, current ≥ target) tightly here so future
 * tuning is a deliberate change.
 */
class SinkingGoalTest {

    private val day = 86_400L
    private val now = 1_700_000_000L  // anchor

    // ─── GoalKind.fromWire ──────────────────────────────────────────

    @Test
    fun `fromWire maps savings and sinking`() {
        assertEquals(GoalKind.SAVINGS, GoalKind.fromWire("savings"))
        assertEquals(GoalKind.SINKING, GoalKind.fromWire("sinking"))
    }

    @Test
    fun `fromWire falls back to SAVINGS for null and unknown`() {
        assertEquals(GoalKind.SAVINGS, GoalKind.fromWire(null))
        assertEquals(GoalKind.SAVINGS, GoalKind.fromWire(""))
        assertEquals(GoalKind.SAVINGS, GoalKind.fromWire("future_kind"))
    }

    // ─── sinkingDaysRemaining ──────────────────────────────────────

    @Test
    fun `daysRemaining null when not sinking`() {
        val g = goal(kind = "savings", deadline = now + 30 * day)
        assertNull(sinkingDaysRemaining(g, now))
    }

    @Test
    fun `daysRemaining null when no deadline`() {
        val g = goal(kind = "sinking", deadline = null)
        assertNull(sinkingDaysRemaining(g, now))
    }

    @Test
    fun `daysRemaining positive for future deadline`() {
        val g = goal(kind = "sinking", deadline = now + 30 * day)
        assertEquals(30, sinkingDaysRemaining(g, now))
    }

    @Test
    fun `daysRemaining negative for past deadline`() {
        val g = goal(kind = "sinking", deadline = now - 5 * day)
        assertEquals(-5, sinkingDaysRemaining(g, now))
    }

    // ─── sinkingMonthlyRateSat ──────────────────────────────────────

    @Test
    fun `monthlyRate null when not sinking`() {
        val g = goal(kind = "savings", deadline = now + 30 * day, target = 1_000_000UL)
        assertNull(sinkingMonthlyRateSat(g, currentSat = 0UL, nowUnix = now))
    }

    @Test
    fun `monthlyRate null when current at or above target`() {
        val g = goal(kind = "sinking", deadline = now + 30 * day, target = 1_000_000UL)
        assertNull(sinkingMonthlyRateSat(g, currentSat = 1_000_000UL, nowUnix = now))
        assertNull(sinkingMonthlyRateSat(g, currentSat = 2_000_000UL, nowUnix = now))
    }

    @Test
    fun `monthlyRate null when deadline passed`() {
        val g = goal(kind = "sinking", deadline = now - day, target = 1_000_000UL)
        assertNull(sinkingMonthlyRateSat(g, currentSat = 0UL, nowUnix = now))
    }

    @Test
    fun `monthlyRate one month equals full missing amount`() {
        // 30 days remaining → 1.0 month → rate = missing.
        val g = goal(kind = "sinking", deadline = now + 30 * day, target = 1_000_000UL)
        val rate = sinkingMonthlyRateSat(g, currentSat = 0UL, nowUnix = now)
        assertEquals(1_000_000UL, rate)
    }

    @Test
    fun `monthlyRate three months equals one third of missing`() {
        val g = goal(kind = "sinking", deadline = now + 90 * day, target = 1_000_000UL)
        val rate = sinkingMonthlyRateSat(g, currentSat = 100_000UL, nowUnix = now)
        // missing = 900_000; months = 3.0 → rate = 300_000
        assertEquals(300_000UL, rate)
    }

    // ─── sinkingScheduleStatus ──────────────────────────────────────

    @Test
    fun `scheduleStatus null when not sinking`() {
        val g = goal(kind = "savings", deadline = now + 30 * day, target = 1_000_000UL)
        assertNull(sinkingScheduleStatus(g, currentSat = 500_000UL, nowUnix = now))
    }

    @Test
    fun `scheduleStatus OVERDUE when deadline passed`() {
        val g = goal(kind = "sinking", deadline = now - day, target = 1_000_000UL)
        assertEquals(SinkingStatus.OVERDUE, sinkingScheduleStatus(g, 0UL, now))
    }

    @Test
    fun `completed goal past its deadline is AHEAD not OVERDUE`() {
        val g = goal(kind = "sinking", deadline = now - day, target = 1_000_000UL)
        assertEquals(SinkingStatus.AHEAD, sinkingScheduleStatus(g, 1_000_000UL, now))
    }

    @Test
    fun `effective target overrides the persisted one`() {
        // Persisted 1M, but the live (pct / fiat) target is 500k: done.
        val g = goal(kind = "sinking", deadline = now + 30 * day, target = 1_000_000UL, createdAt = now - 5 * day)
        assertEquals(SinkingStatus.AHEAD, sinkingScheduleStatus(g, 500_000UL, now, targetSat = 500_000UL))
        assertEquals(null, sinkingMonthlyRateSat(g, currentSat = 500_000UL, nowUnix = now, targetSat = 500_000UL))
    }

    @Test
    fun `scheduleStatus AHEAD when current is already at target`() {
        // No matter the schedule, hitting target = AHEAD.
        val g = goal(kind = "sinking", deadline = now + 30 * day, target = 1_000_000UL, createdAt = now - 5 * day)
        assertEquals(SinkingStatus.AHEAD, sinkingScheduleStatus(g, 1_000_000UL, now))
    }

    @Test
    fun `scheduleStatus ON_TRACK at the linear expected point`() {
        // 60d total, 30d elapsed → expected 500k; current 500k → ratio = 1.0 → ON_TRACK
        val g = goal(
            kind = "sinking",
            deadline = now + 30 * day,
            createdAt = now - 30 * day,
            target = 1_000_000UL,
        )
        assertEquals(SinkingStatus.ON_TRACK, sinkingScheduleStatus(g, 500_000UL, now))
    }

    @Test
    fun `scheduleStatus BEHIND when current is well below expected`() {
        // Expected 500k, actual 200k → ratio 0.4 → BEHIND
        val g = goal(
            kind = "sinking",
            deadline = now + 30 * day,
            createdAt = now - 30 * day,
            target = 1_000_000UL,
        )
        assertEquals(SinkingStatus.BEHIND, sinkingScheduleStatus(g, 200_000UL, now))
    }

    @Test
    fun `scheduleStatus AHEAD when current well above expected`() {
        // Expected 500k, actual 800k → ratio 1.6 → AHEAD
        val g = goal(
            kind = "sinking",
            deadline = now + 30 * day,
            createdAt = now - 30 * day,
            target = 1_000_000UL,
        )
        assertEquals(SinkingStatus.AHEAD, sinkingScheduleStatus(g, 800_000UL, now))
    }

    @Test
    fun `scheduleStatus tolerates plus minus 5 percent around linear`() {
        // Expected 500k, actual 480k (-4%) → still ON_TRACK
        val g = goal(
            kind = "sinking",
            deadline = now + 30 * day,
            createdAt = now - 30 * day,
            target = 1_000_000UL,
        )
        assertEquals(SinkingStatus.ON_TRACK, sinkingScheduleStatus(g, 480_000UL, now))
        // Just over 5% below → BEHIND
        assertEquals(SinkingStatus.BEHIND, sinkingScheduleStatus(g, 470_000UL, now))
    }

    @Test
    fun `scheduleStatus null when freshly created and untouched`() {
        // <1 day elapsed AND current_sat=0 → too early to judge.
        val g = goal(
            kind = "sinking",
            deadline = now + 30 * day,
            createdAt = now - 100L,  // 100 seconds ago
            target = 1_000_000UL,
        )
        assertNull(sinkingScheduleStatus(g, 0UL, now))
    }

    // ─── Test fixture ───────────────────────────────────────────────

    private fun goal(
        kind: String,
        deadline: Long?,
        target: ULong = 1_000_000UL,
        createdAt: Long = now - 10L * day,
    ): TrackingSavingsGoalFfi = TrackingSavingsGoalFfi(
        id = 1,
        name = "test",
        targetSat = target,
        currentSat = 0UL,
        color = "cyan",
        deadlineUnix = deadline,
        linkedWalletId = null,
        targetPctOfNetWorth = null,
        bucketKind = "manual",
        bucketRef = null,
        goalKind = kind,
        targetDenomination = "sat",
        targetFiatAmount = null,
        targetFiatCurrency = null,
        createdAtUnix = createdAt,
    )
}
