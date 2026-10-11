package io.bhodl.desktop.wizard

import io.bhodl.ffi.WalletException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DevicePinSession] idle-timeout behaviour.
 *
 * Tests pass explicit [now] parameters so the assertions are
 * deterministic regardless of wall-clock speed on the test machine.
 */
class DevicePinSessionTest {

    @BeforeTest
    fun reset() {
        DevicePinSession.clear()
    }

    @AfterTest
    fun cleanup() {
        DevicePinSession.clear()
    }

    private val t0 = 1_700_000_000L * 1_000L

    @Test
    fun `markUnlocked opens the session`() {
        DevicePinSession.markUnlocked(now = t0)
        assertTrue(DevicePinSession.isUnlocked(now = t0 + 1_000L))
    }

    @Test
    fun `clear closes the session`() {
        DevicePinSession.markUnlocked(now = t0)
        DevicePinSession.clear()
        assertFalse(DevicePinSession.isUnlocked(now = t0))
        assertTrue(DevicePinSession.isExpired(now = t0))
    }

    @Test
    fun `not expired within the idle timeout`() {
        DevicePinSession.markUnlocked(now = t0)
        assertFalse(
            DevicePinSession.isExpired(
                now = t0 + DevicePinSession.DEFAULT_IDLE_TIMEOUT_MS - 1L,
            ),
        )
    }

    @Test
    fun `expired once the idle timeout has elapsed`() {
        DevicePinSession.markUnlocked(now = t0)
        assertTrue(
            DevicePinSession.isExpired(now = t0 + DevicePinSession.DEFAULT_IDLE_TIMEOUT_MS),
        )
    }

    @Test
    fun `isExpired returns true when no session is open`() {
        assertEquals(true, DevicePinSession.isExpired())
    }

    @Test
    fun `touch keeps the session alive beyond the original timeout`() {
        DevicePinSession.markUnlocked(now = t0)
        DevicePinSession.touch(now = t0 + 1_000L)
        // touched at +1000ms, checked at +1200ms, timeout 500ms → not expired
        assertFalse(DevicePinSession.isExpired(idleTimeoutMs = 500L, now = t0 + 1_200L))
    }

    @Test
    fun `touch without an open session does not open one`() {
        DevicePinSession.touch(now = t0)
        assertTrue(DevicePinSession.isExpired(now = t0))
    }

    // ─── verify(): typed re-auth outcome ───────────────────────────

    @Test
    fun `verify maps true to Match and false to WrongPin`() {
        assertEquals(DevicePinCheck.Match, DevicePinSession.verify("1234") { it == "1234" })
        assertEquals(DevicePinCheck.WrongPin, DevicePinSession.verify("0000") { it == "1234" })
    }

    @Test
    fun `verify turns a storage failure into StorageError with a proper message`() {
        val check = DevicePinSession.verify("1234") {
            throw WalletException.Storage("no wallet.kdf.json or wallet.salt at this data dir")
        }
        assertIs<DevicePinCheck.StorageError>(check)
        assertEquals(PinErrors.messageFor(PinFailureKind.Storage), check.message)
    }

    @Test
    fun `verify never reports an unrecognised error as a wrong PIN`() {
        // PinErrors.classify is fail-closed for unlock errors (unknown
        // detail = WrongPin); for re-auth an exception is never a verdict.
        val check = DevicePinSession.verify("1234") {
            throw WalletException.Storage(
                "kdf descriptor integrity check failed (MAC mismatch — file tampered?)",
            )
        }
        assertIs<DevicePinCheck.StorageError>(check)
        assertTrue("MAC mismatch" in check.message, check.message)
        assertTrue("not counted" in check.message, check.message)

        val closed = DevicePinSession.verify("1234") { error("Registry not open") }
        assertIs<DevicePinCheck.StorageError>(closed)
        assertTrue("Registry not open" in closed.message, closed.message)
    }

    @Test
    fun `only WrongPin outcomes count towards the device lockout`() {
        // Drive the real tracker the way PromoteWatchScreen does.
        PinAttemptTracker.reset()
        try {
            repeat(10) {
                val check = DevicePinSession.verify("1234") {
                    throw WalletException.Storage("database is locked")
                }
                if (check == DevicePinCheck.WrongPin) {
                    PinAttemptTracker.recordFailure(PinScopes.DEVICE)
                }
            }
            assertNull(PinAttemptTracker.lockoutMessage(PinScopes.DEVICE))
        } finally {
            PinAttemptTracker.reset()
        }
    }

    @Test
    fun `reading the session state does not refresh the idle clock`() {
        // Audit 2026-10: background reads must not keep an unattended
        // session alive.
        DevicePinSession.markUnlocked(now = t0)
        repeat(5) { DevicePinSession.isUnlocked(now = t0 + 400L) }
        assertTrue(DevicePinSession.isExpired(idleTimeoutMs = 500L, now = t0 + 600L))
    }
}
