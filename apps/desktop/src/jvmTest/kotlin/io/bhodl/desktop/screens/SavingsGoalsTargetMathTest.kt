package io.bhodl.desktop.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 7.2.2 — pure tests for the goal target ↔ percentage helpers.
 *
 * The dialog uses these to (a) compute the sat target when the user
 * picks the % mode and (b) show a "≈ X% NW" hint in the absolute mode.
 * Pinning the math here keeps the dialog dumb.
 */
class SavingsGoalsTargetMathTest {

    // ─── targetSatFromPct ───────────────────────────────────────────

    @Test
    fun `targetSatFromPct returns zero for zero pct`() {
        assertEquals(0UL, targetSatFromPct(netWorthSat = 100_000_000UL, pct = 0u))
    }

    @Test
    fun `targetSatFromPct returns zero when net worth is zero`() {
        // Symmetric to pct=0: no NW, no target. Avoids a divide-by-zero
        // surprise and matches the dialog's NW=0 lock-to-ABSOLUTE.
        assertEquals(0UL, targetSatFromPct(netWorthSat = 0UL, pct = 30u))
    }

    @Test
    fun `targetSatFromPct computes thirty percent of one BTC`() {
        // 1 BTC = 100_000_000 sat; 30% = 30_000_000 sat = 0.30000000 BTC.
        assertEquals(30_000_000UL, targetSatFromPct(100_000_000UL, 30u))
    }

    @Test
    fun `targetSatFromPct hundred percent equals net worth`() {
        val nw = 12_345_678UL
        assertEquals(nw, targetSatFromPct(nw, 100u))
    }

    @Test
    fun `targetSatFromPct truncates rather than rounding`() {
        // 3 sat * 50 / 100 = 1.5 → toLong = 1. We accept the truncation
        // since "1.5 sat" is meaningless; the alternative (banker's
        // rounding) would only matter for sub-sat granularity which
        // doesn't exist on-chain.
        assertEquals(1UL, targetSatFromPct(3UL, 50u))
    }

    @Test
    fun `targetSatFromPct stays within u64 for realistic supply scale`() {
        // 21M BTC * 100% should not overflow. ULong.MAX_VALUE is ~1.8e19,
        // 21M * 1e8 * 100 / 100 = 2.1e15. Massive headroom but pin it.
        val supplySat = 21_000_000UL * 100_000_000UL
        assertEquals(supplySat, targetSatFromPct(supplySat, 100u))
    }

    // ─── pctFromTargetSat ───────────────────────────────────────────

    @Test
    fun `pctFromTargetSat returns null when net worth is zero`() {
        // Undefined math — the dialog renders this as "—" instead of
        // silently producing a misleading number.
        assertNull(pctFromTargetSat(netWorthSat = 0UL, targetSat = 100UL))
    }

    @Test
    fun `pctFromTargetSat computes round percentages`() {
        assertEquals(30, pctFromTargetSat(100_000_000UL, 30_000_000UL))
        assertEquals(100, pctFromTargetSat(100_000_000UL, 100_000_000UL))
        assertEquals(0, pctFromTargetSat(100_000_000UL, 0UL))
    }

    @Test
    fun `pctFromTargetSat truncates fractional percentages`() {
        // 0.33 BTC of 1 BTC = 33.0% → truncated to 33.
        assertEquals(33, pctFromTargetSat(100_000_000UL, 33_000_000UL))
        // 0.339 BTC of 1 BTC = 33.9% → truncated to 33 (intentional,
        // matches truncation in targetSatFromPct).
        assertEquals(33, pctFromTargetSat(100_000_000UL, 33_900_000UL))
    }

    @Test
    fun `pctFromTargetSat clamps to 999 for over-target hints`() {
        // User typed a target 100× their NW — show "999% NW" instead
        // of a 5-digit number that would push the hint off-screen.
        assertEquals(999, pctFromTargetSat(1UL, 1_000_000UL))
    }

    // ─── goalAllocationDriftPp (Phase 7.3) ──────────────────────────

    @Test
    fun `drift is null when goal has no allocation target`() {
        // pctFromNW null → no target set → no chip
        val goal = goalWithPct(bucketKind = "investments", pctOfNw = null)
        assertNull(goalAllocationDriftPp(goal, liveCurrentSat = 30UL, totalNetWorthSat = 100UL))
    }

    @Test
    fun `drift is null for manual bucket regardless of pct target`() {
        // MANUAL is hand-entered; the drift would just echo what the
        // user typed — noise, not signal. Hide the chip.
        val goal = goalWithPct(bucketKind = "manual", pctOfNw = 30u)
        assertNull(goalAllocationDriftPp(goal, liveCurrentSat = 50UL, totalNetWorthSat = 100UL))
    }

    @Test
    fun `drift is null when NW is zero`() {
        val goal = goalWithPct(bucketKind = "investments", pctOfNw = 30u)
        assertNull(goalAllocationDriftPp(goal, liveCurrentSat = 50UL, totalNetWorthSat = 0UL))
    }

    @Test
    fun `drift is positive when bucket is above target`() {
        // 30% target, 50% actual → +20 pp
        val goal = goalWithPct(bucketKind = "investments", pctOfNw = 30u)
        assertEquals(20, goalAllocationDriftPp(goal, liveCurrentSat = 50UL, totalNetWorthSat = 100UL))
    }

    @Test
    fun `drift is negative when bucket is below target`() {
        // 40% target, 25% actual → -15 pp
        val goal = goalWithPct(bucketKind = "investments", pctOfNw = 40u)
        assertEquals(-15, goalAllocationDriftPp(goal, liveCurrentSat = 25UL, totalNetWorthSat = 100UL))
    }

    @Test
    fun `drift is zero when bucket sits exactly on target`() {
        val goal = goalWithPct(bucketKind = "investments", pctOfNw = 30u)
        assertEquals(0, goalAllocationDriftPp(goal, liveCurrentSat = 30UL, totalNetWorthSat = 100UL))
    }

    @Test
    fun `drift threshold pp is the Vanguard default 5`() {
        // Pinning the threshold so a stylistic change "let's make it 10pp"
        // has to update this test deliberately.
        assertEquals(5, GOAL_DRIFT_THRESHOLD_PP)
    }

    private fun goalWithPct(
        bucketKind: String,
        pctOfNw: UInt?,
    ): io.bhodl.ffi.TrackingSavingsGoalFfi =
        io.bhodl.ffi.TrackingSavingsGoalFfi(
            id = 1,
            name = "test",
            targetSat = 1_000_000UL,
            currentSat = 0UL,
            color = "cyan",
            deadlineUnix = null,
            linkedWalletId = null,
            targetPctOfNetWorth = pctOfNw,
            bucketKind = bucketKind,
            bucketRef = null,
            goalKind = "savings",
            targetDenomination = "sat",
            targetFiatAmount = null,
            targetFiatCurrency = null,
            createdAtUnix = 0,
        )
}
