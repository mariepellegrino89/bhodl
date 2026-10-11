package io.bhodl.desktop.wizard

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Persistence tests for [PinAttemptTracker.Instance].
 *
 * Uses [PinAttemptTracker.Instance] directly so each test runs with
 * an isolated temporary file — no shared singleton state, no reset()
 * calls needed. Simulates a process restart by constructing a second
 * [PinAttemptTracker.Instance] that reads the same file.
 */
class PinAttemptTrackerPersistenceTest {

    private lateinit var tmpDir: File
    private lateinit var failuresFile: File

    @BeforeTest
    fun setUp() {
        tmpDir = createTempDir("pin_tracker_test_")
        failuresFile = File(tmpDir, PinAttemptTracker.PIN_FAILURES_FILE_NAME)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `failures persist across tracker recreation (process restart simulation)`() {
        val t0 = 1_700_000_000L
        val tracker1 = PinAttemptTracker.Instance(failuresFile)

        // Record 3 failures → should trigger the 5s lockout.
        repeat(3) { tracker1.recordFailure("device-pin", t0) }
        assertEquals(3, tracker1.failureCount("device-pin"))
        assertTrue(failuresFile.isFile, "failures file must be written to disk")

        // Simulate process restart: create a new instance reading the same file.
        val tracker2 = PinAttemptTracker.Instance(failuresFile)
        assertEquals(3, tracker2.failureCount("device-pin"),
            "failure count must survive across tracker recreation")
    }

    @Test
    fun `lockout deadline is honoured after restart`() {
        val t0 = 1_700_000_000L
        val tracker1 = PinAttemptTracker.Instance(failuresFile)
        repeat(3) { tracker1.recordFailure("device-pin", t0) }
        // After 3 fails, cooldown is 5s. At t0+2 there should be 3s remaining.
        assertEquals(3L, tracker1.cooldownRemainingSeconds(t0 + 2L))

        // New instance (process restart).
        val tracker2 = PinAttemptTracker.Instance(failuresFile)
        // The deadline is recomputed from stored timestamps, so the
        // remaining cooldown should be the same (we query at t0+2).
        assertEquals(3L, tracker2.cooldownRemainingSeconds(t0 + 2L),
            "cooldown must be honoured after restart")
    }

    @Test
    fun `success wipes the persisted counter`() {
        val t0 = 1_700_000_000L
        val tracker1 = PinAttemptTracker.Instance(failuresFile)
        repeat(3) { tracker1.recordFailure("device-pin", t0) }
        tracker1.recordSuccess("device-pin")
        assertEquals(0, tracker1.failureCount("device-pin"))

        // After restart, counter is still 0.
        val tracker2 = PinAttemptTracker.Instance(failuresFile)
        assertEquals(0, tracker2.failureCount("device-pin"),
            "success must wipe the persisted counter")
    }

    @Test
    fun `multiple scopes are persisted and reloaded independently`() {
        val t0 = 1_700_000_000L
        val tracker1 = PinAttemptTracker.Instance(failuresFile)
        repeat(2) { tracker1.recordFailure("scope-a", t0) }
        repeat(4) { tracker1.recordFailure("scope-b", t0) }

        val tracker2 = PinAttemptTracker.Instance(failuresFile)
        assertEquals(2, tracker2.failureCount("scope-a"))
        assertEquals(4, tracker2.failureCount("scope-b"))
    }

    @Test
    fun `missing file is silently ignored on load (first launch)`() {
        // File does not exist yet — no exception expected.
        val tracker = PinAttemptTracker.Instance(failuresFile)
        assertEquals(0, tracker.failureCount("device-pin"))
        assertEquals(0L, tracker.cooldownRemainingSeconds(1_700_000_000L))
    }

    @Test
    fun `corrupted file does not crash the tracker`() {
        failuresFile.writeText("not valid json", Charsets.UTF_8)
        // Should not throw — failures are swallowed and the tracker
        // boots with an empty state.
        val tracker = PinAttemptTracker.Instance(failuresFile)
        assertEquals(0, tracker.failureCount("device-pin"))
    }

    @Test
    fun `reset() writes an empty snapshot to disk`() {
        val t0 = 1_700_000_000L
        val tracker1 = PinAttemptTracker.Instance(failuresFile)
        repeat(3) { tracker1.recordFailure("device-pin", t0) }
        tracker1.reset()

        // After restart, should be clean.
        val tracker2 = PinAttemptTracker.Instance(failuresFile)
        assertEquals(0, tracker2.failureCount("device-pin"))
        assertEquals(0L, tracker2.lockedUntilUnix.value)
    }

    
}
