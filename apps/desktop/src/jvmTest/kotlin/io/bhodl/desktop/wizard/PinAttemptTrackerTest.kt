package io.bhodl.desktop.wizard

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1-9 — unit tests for [PinAttemptTracker].
 *
 * The tracker is a process-lifetime singleton, so every test resets
 * it on entry and exit. Tests pass an explicit `nowUnix` to the
 * `recordFailure` / `cooldownRemainingSeconds` calls so the lockout
 * schedule is deterministic regardless of wall-clock skew on the
 * test machine.
 */
class PinAttemptTrackerTest {

    private val scope = "test-wallet"
    private val t0: Long = 1_700_000_000L

    @BeforeTest
    fun reset() {
        PinAttemptTracker.reset()
    }

    @AfterTest
    fun cleanup() {
        PinAttemptTracker.reset()
    }

    // ---- cooldownSecondsFor: pin the lockout schedule ----------------

    @Test
    fun `first two failures incur no cooldown`() {
        assertEquals(0L, PinAttemptTracker.cooldownSecondsFor(0))
        assertEquals(0L, PinAttemptTracker.cooldownSecondsFor(1))
        assertEquals(0L, PinAttemptTracker.cooldownSecondsFor(2))
    }

    @Test
    fun `third and fourth failures trigger 5s cooldown`() {
        assertEquals(5L, PinAttemptTracker.cooldownSecondsFor(3))
        assertEquals(5L, PinAttemptTracker.cooldownSecondsFor(4))
    }

    @Test
    fun `fifth through ninth failures trigger 30s cooldown`() {
        for (n in 5..9) {
            assertEquals(30L, PinAttemptTracker.cooldownSecondsFor(n),
                "fail count $n must trigger 30s lockout")
        }
    }

    @Test
    fun `tenth through fourteenth failures trigger 2 minute cooldown`() {
        for (n in 10..14) {
            assertEquals(120L, PinAttemptTracker.cooldownSecondsFor(n))
        }
    }

    @Test
    fun `fifteenth and beyond failures trigger 10 minute cooldown`() {
        assertEquals(600L, PinAttemptTracker.cooldownSecondsFor(15))
        assertEquals(600L, PinAttemptTracker.cooldownSecondsFor(100))
        assertEquals(600L, PinAttemptTracker.cooldownSecondsFor(10_000))
    }

    // ---- recordFailure / cooldownRemainingSeconds --------------------

    @Test
    fun `record two failures does not lock out`() {
        assertEquals(0L, PinAttemptTracker.recordFailure(scope, t0))
        assertEquals(0L, PinAttemptTracker.recordFailure(scope, t0 + 1))
        assertEquals(0L, PinAttemptTracker.cooldownRemainingSeconds(t0 + 2))
        assertEquals(2, PinAttemptTracker.failureCount(scope))
    }

    @Test
    fun `third failure locks out for 5 seconds`() {
        repeat(2) { PinAttemptTracker.recordFailure(scope, t0) }
        val deadline = PinAttemptTracker.recordFailure(scope, t0 + 1)
        assertEquals(t0 + 6, deadline)
        assertEquals(5L, PinAttemptTracker.cooldownRemainingSeconds(t0 + 1))
    }

    @Test
    fun `lockout deadline decays as wall clock advances`() {
        repeat(3) { PinAttemptTracker.recordFailure(scope, t0) }
        // 3 sec into the 5-sec lockout: 2 remain.
        assertEquals(2L, PinAttemptTracker.cooldownRemainingSeconds(t0 + 3))
        // Past the deadline: zero.
        assertEquals(0L, PinAttemptTracker.cooldownRemainingSeconds(t0 + 100))
    }

    @Test
    fun `fifth failure escalates to 30 second cooldown`() {
        // Simulate the user waiting out the first 5s lockout and
        // failing again — the policy looks at cumulative count, not
        // recent count.
        repeat(4) { PinAttemptTracker.recordFailure(scope, t0) }
        val deadline = PinAttemptTracker.recordFailure(scope, t0 + 10)
        assertEquals(t0 + 10 + 30, deadline)
    }

    @Test
    fun `tenth failure escalates to 2 minute cooldown`() {
        repeat(9) { PinAttemptTracker.recordFailure(scope, t0) }
        val deadline = PinAttemptTracker.recordFailure(scope, t0 + 100)
        assertEquals(t0 + 100 + 120, deadline)
    }

    @Test
    fun `fifteenth failure escalates to 10 minute cooldown`() {
        repeat(14) { PinAttemptTracker.recordFailure(scope, t0) }
        val deadline = PinAttemptTracker.recordFailure(scope, t0 + 1)
        assertEquals(t0 + 1 + 600, deadline)
    }

    // ---- recordSuccess clears state ----------------------------------

    @Test
    fun `recordSuccess clears the counter and lifts lockout`() {
        repeat(3) { PinAttemptTracker.recordFailure(scope, t0) }
        assertTrue(PinAttemptTracker.cooldownRemainingSeconds(t0) > 0)
        PinAttemptTracker.recordSuccess(scope)
        assertEquals(0, PinAttemptTracker.failureCount(scope))
        assertEquals(0L, PinAttemptTracker.cooldownRemainingSeconds(t0))
    }

    // ---- scope isolation ---------------------------------------------

    @Test
    fun `failures in one scope do not affect another scope`() {
        repeat(3) { PinAttemptTracker.recordFailure("wallet-a", t0) }
        // wallet-b has zero fails of its own.
        assertEquals(0, PinAttemptTracker.failureCount("wallet-b"))
    }

    @Test
    fun `lockedUntilUnix is the max across all scopes`() {
        // wallet-a triggers a 5s lockout at t0.
        repeat(3) { PinAttemptTracker.recordFailure("wallet-a", t0) }
        assertEquals(t0 + 5, PinAttemptTracker.lockedUntilUnix.value)
        // wallet-b triggers a 5s lockout 100s later — the later
        // deadline must win.
        repeat(3) { PinAttemptTracker.recordFailure("wallet-b", t0 + 100) }
        assertEquals(t0 + 105, PinAttemptTracker.lockedUntilUnix.value)
    }

    // ---- reset clears everything -------------------------------------

    @Test
    fun `reset clears all counters and lockouts`() {
        repeat(5) { PinAttemptTracker.recordFailure("a", t0) }
        repeat(5) { PinAttemptTracker.recordFailure("b", t0) }
        PinAttemptTracker.reset()
        assertEquals(0, PinAttemptTracker.failureCount("a"))
        assertEquals(0, PinAttemptTracker.failureCount("b"))
        assertEquals(0L, PinAttemptTracker.lockedUntilUnix.value)
    }
}
