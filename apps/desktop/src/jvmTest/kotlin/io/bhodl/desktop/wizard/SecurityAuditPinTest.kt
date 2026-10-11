package io.bhodl.desktop.wizard

import io.bhodl.desktop.IdleLock
import io.bhodl.ffi.WalletException
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Audit 2026-10 — PIN lockout, error classification, setup secrets, idle lock, wipe. */
class SecurityAuditPinTest {

    private lateinit var tmp: File
    private val t0 = 1_700_000_000L

    @BeforeTest
    fun setUp() {
        tmp = kotlin.io.path.createTempDirectory("bhodl-audit-").toFile()
        PinAttemptTracker.reset()
        SetupSecrets.wipeAll()
    }

    @AfterTest
    fun tearDown() {
        tmp.deleteRecursively()
        PinAttemptTracker.reset()
        SetupSecrets.wipeAll()
    }

    @Test
    fun `success on another scope does not lift a lockout`() {
        repeat(5) { PinAttemptTracker.recordFailure("device-pin", t0) }
        assertEquals(30L, PinAttemptTracker.cooldownRemainingSeconds("device-pin", t0))
        PinAttemptTracker.recordSuccess("some-wallet")
        assertEquals(30L, PinAttemptTracker.cooldownRemainingSeconds("device-pin", t0))
        assertEquals(0L, PinAttemptTracker.cooldownRemainingSeconds("some-wallet", t0))
    }

    @Test
    fun `clock moved backwards cannot stretch the wait beyond policy`() {
        repeat(3) { PinAttemptTracker.recordFailure("s", t0) }
        assertEquals(5L, PinAttemptTracker.cooldownRemainingSeconds("s", t0 - 31_536_000L))
    }

    @Test
    fun `monotonic deadline survives a forward clock jump in-process`() {
        val tracker = PinAttemptTracker.Instance()
        val nanos = System.nanoTime()
        repeat(5) { tracker.recordFailure("s", t0, nanos) }
        assertTrue(tracker.cooldownRemainingSeconds("s", t0 + 86_400L, nanos) >= 29L)
    }

    @Test
    fun `future timestamps on disk are clamped to now`() {
        val f = File(tmp, PinAttemptTracker.PIN_FAILURES_FILE_NAME)
        val now = 2_000_000_000L
        val far = now + 10_000_000L
        f.writeText(
            """{"version":1,"entries":[{"scope":"s","failTimestamps":[$far,$far,$far]}]}""",
        )
        val tracker = PinAttemptTracker.Instance(f, wallClock = { now })
        assertEquals(3, tracker.failureCount("s"))
        assertEquals(5L, tracker.cooldownRemainingSeconds("s", now, null))
    }

    @Test
    fun `corrupt failures file applies a lockout instead of resetting`() {
        val f = File(tmp, PinAttemptTracker.PIN_FAILURES_FILE_NAME)
        f.writeText("garbage")
        val now = 2_000_000_000L
        val tracker = PinAttemptTracker.Instance(f, wallClock = { now })
        assertEquals(
            PinAttemptTracker.CORRUPT_FILE_PENALTY_SECONDS,
            tracker.cooldownRemainingSeconds("device-pin", now, null),
        )
    }

    @Test
    fun `pin error classification separates network and storage from wrong pin`() {
        assertEquals(
            PinFailureKind.WrongPin,
            PinErrors.classify(
                WalletException.Storage("kdf descriptor integrity check failed (MAC mismatch — file tampered?)"),
            ),
        )
        assertEquals(
            PinFailureKind.WrongPin,
            PinErrors.classify(WalletException.Storage("encryption key rejected: file is not a database")),
        )
        assertEquals(
            PinFailureKind.NetworkMismatch,
            PinErrors.classify(WalletException.Storage("Network mismatch: loaded bitcoin, expected signet")),
        )
        assertEquals(
            PinFailureKind.Storage,
            PinErrors.classify(WalletException.Storage("no wallet.kdf.json or wallet.salt at this data dir")),
        )
        assertEquals(PinFailureKind.Other, PinErrors.classify(IllegalStateException("x")))
    }

    @Test
    fun `downgrade refusals are classified as NewerVersion, not as a wrong PIN`() {
        // Exact details produced by the Rust core (registry.rs L9 gate,
        // kdf.rs descriptor version check).
        val registry = WalletException.Storage(
            "registry schema version 5 is newer than this app supports (4); update BHODL to open it",
        )
        val kdf = WalletException.Storage("unsupported kdf descriptor version 3")
        for (e in listOf(registry, kdf)) {
            assertEquals(PinFailureKind.NewerVersion, PinErrors.classify(e))
        }
        val msg = PinErrors.messageFor(PinFailureKind.NewerVersion)
        assertTrue("newer version of BHODL" in msg, msg)
        assertTrue("not counted" in msg, msg)
    }

    @Test
    fun `setup secrets are wiped`() {
        val id = SetupSecrets.put("abandon about", "pw")
        assertEquals("abandon about", SetupSecrets.mnemonic(id))
        assertEquals("pw", SetupSecrets.passphrase(id))
        SetupSecrets.wipeAll()
        assertNull(SetupSecrets.mnemonic(id))
        assertEquals(0, SetupSecrets.size())
    }

    @Test
    fun `idle lock arms for a wallet without registry and on minimise`() {
        val now = 10_000_000_000_000L
        assertFalse(IdleLock.shouldLock(false, false, true, now, lastActivity = 0L, minimizedSince = null))
        assertTrue(
            IdleLock.shouldLock(
                sessionOpen = true,
                registryOpen = false,
                devicePinExpired = true,
                nowNanos = now,
                lastActivity = now - (IdleLock.IDLE_TIMEOUT_MS + 1) * 1_000_000L,
                minimizedSince = null,
            ),
        )
        assertFalse(IdleLock.shouldLock(true, false, true, now, lastActivity = now, minimizedSince = null))
        assertTrue(
            IdleLock.shouldLock(
                true, false, false, now, lastActivity = now,
                minimizedSince = now - (IdleLock.MINIMIZED_GRACE_MS + 1) * 1_000_000L,
            ),
        )
    }

    @Test
    fun `local wipe deletes databases before kdf and removes pin history`() {
        File(tmp, WalletPaths.REGISTRY_FILE_NAME).writeText("db")
        File(tmp, WalletPaths.REGISTRY_KDF_FILE_NAME).writeText("{}")
        File(tmp, PinAttemptTracker.PIN_FAILURES_FILE_NAME).writeText("{}")
        val result = LocalWipe.wipeAll(tmp)
        assertTrue(result.ok, "failed: ${result.failed}")
        val names = result.deleted.map { it.name }
        assertTrue(
            names.indexOf(WalletPaths.REGISTRY_FILE_NAME) < names.indexOf(WalletPaths.REGISTRY_KDF_FILE_NAME),
        )
        assertFalse(File(tmp, PinAttemptTracker.PIN_FAILURES_FILE_NAME).exists())
        assertTrue(LocalWipe.isConfirmed(" DELETE "))
        assertFalse(LocalWipe.isConfirmed("delete"))
    }
}
