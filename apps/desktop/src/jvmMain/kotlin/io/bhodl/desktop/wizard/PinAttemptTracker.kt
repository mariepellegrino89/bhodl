package io.bhodl.desktop.wizard

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.max
import kotlin.math.min
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_pin_lockout

/**
 * T1-9 — persistent tracker for failed PIN attempts.
 *
 * Bridges the gap left by the Rust FFI, which does not currently
 * enforce a rate-limit on create_or_load / load_persistent: every
 * wrong-PIN call surfaces as a generic Storage error with no
 * counter, no delay, no lockout. Combined with the digit-only
 * PinValidator.MIN_LENGTH = 6 (~19.9 bits of entropy), a script
 * could exhaust the entire 10^6 space against a running instance.
 *
 * Threat model: defends against an automated online attack against
 * a process the attacker can interact with. The Argon2id KDF
 * already defends against offline brute-force of the encrypted DB.
 *
 * Lockout policy (per-keyed scope):
 *  - 0-2 fails: no delay
 *  - 3rd fail: 5 second cooldown
 *  - 5th fail: 30 second cooldown
 *  - 10th fail: 2 minute cooldown
 *  - 15th fail: 10 minute cooldown
 *
 * Security audit 2026-10 hardening:
 *  - **Per-scope deadlines.** `recordSuccess(scope)` used to zero the
 *    single GLOBAL deadline, so a locked-out device-PIN scope could be
 *    unlocked by a success on any other scope (alternate scopes →
 *    unlimited guesses). Each scope now owns its deadline; a success
 *    only clears its own.
 *  - **Clock tampering.** Persisted timestamps in the future are
 *    clamped to "now" on load, and the remaining wait is capped at the
 *    policy cooldown for the scope's count — so moving the clock
 *    backwards can never lock the user out for months. In-memory
 *    enforcement additionally uses a monotonic [System.nanoTime]
 *    deadline, so moving the clock FORWARDS cannot skip a lockout
 *    within the running process.
 *  - **Corrupt file.** A present-but-unparseable failures file is no
 *    longer silently treated as "zero failures": the in-memory state
 *    is kept, the event is logged, and a [CORRUPT_FILE_PENALTY_SECONDS]
 *    lockout is applied to every scope.
 */
@Serializable
internal data class PinScopeEntry(
    val scope: String,
    val failTimestamps: List<Long>,
)

@Serializable
internal data class PinDiskSnapshot(
    val version: Int = 1,
    val entries: List<PinScopeEntry> = emptyList(),
)

/** Well-known scope keys for [PinAttemptTracker]. Wallet scopes use the
 *  raw wallet UUID (backwards compatible with already-persisted files). */
public object PinScopes {
    /** Device PIN (registry unlock, re-auth prompts). */
    public const val DEVICE: String = "device-pin"

    /** Recovery code typed on the "forgot PIN" screen. */
    public const val RECOVERY: String = "recovery-code"

    /** Legacy (pre-3.0) wallet PIN typed on the migration screen. */
    public const val MIGRATION_LEGACY: String = "migration-legacy-pin"

    /** Scope for a signing wallet's own PIN. */
    public fun wallet(walletId: String): String = walletId
}

public object PinAttemptTracker {

    /** File name written under the data dir. Not a secret -- no PIN data. */
    public const val PIN_FAILURES_FILE_NAME: String = "pin_failures.json"

    /** Lockout applied to every scope when the persisted file is corrupt. */
    public const val CORRUPT_FILE_PENALTY_SECONDS: Long = 30L

    private val delegate: Instance = Instance()

    public fun enablePersistence(file: File) {
        delegate.enablePersistence(file)
    }

    /** Max deadline (unix seconds) across every scope. */
    public val lockedUntilUnix: State<Long> get() = delegate.lockedUntilUnix

    /** Record a failure using the real wall + monotonic clocks. */
    public fun recordFailure(scope: String): Long =
        delegate.recordFailure(scope, wallClockSeconds(), System.nanoTime())

    /** Record a failure at an explicit wall-clock time (tests). */
    public fun recordFailure(scope: String, nowUnix: Long): Long =
        delegate.recordFailure(scope, nowUnix, null)

    public fun recordSuccess(scope: String) {
        delegate.recordSuccess(scope)
    }

    /** Remaining cooldown for [scope], using wall + monotonic clocks. */
    public fun cooldownRemainingSeconds(scope: String): Long =
        delegate.cooldownRemainingSeconds(scope, wallClockSeconds(), System.nanoTime())

    /** Remaining cooldown for [scope] at an explicit wall-clock time. */
    public fun cooldownRemainingSeconds(scope: String, nowUnix: Long): Long =
        delegate.cooldownRemainingSeconds(scope, nowUnix, null)

    /** Max remaining cooldown across every scope (wall + monotonic). */
    public fun cooldownRemainingSeconds(): Long =
        delegate.cooldownRemainingSecondsAny(wallClockSeconds(), System.nanoTime())

    /** Max remaining cooldown across every scope at an explicit time. */
    public fun cooldownRemainingSeconds(nowUnix: Long): Long =
        delegate.cooldownRemainingSecondsAny(nowUnix, null)

    public fun failureCount(scope: String): Int = delegate.failureCount(scope)

    /**
     * UI helper — `"Too many wrong PINs. Wait Ns before retrying."` when
     * [scope] is locked out, `null` otherwise. Every PIN prompt calls
     * this BEFORE invoking the FFI.
     */
    public fun lockoutMessage(scope: String): String? {
        val remaining = cooldownRemainingSeconds(scope)
        return if (remaining > 0L) {
            localizedText(Res.string.onb_pin_lockout, remaining)
        } else {
            null
        }
    }

    internal fun cooldownSecondsFor(failures: Int): Long =
        Instance.cooldownSecondsFor(failures)

    public fun reset() {
        delegate.reset()
    }

    private fun wallClockSeconds(): Long = System.currentTimeMillis() / 1000L

    internal class Instance(
        persistFile: File? = null,
        private val wallClock: () -> Long = { System.currentTimeMillis() / 1000L },
    ) {

        private var file: File? = persistFile

        private val failuresByScope: MutableMap<String, MutableList<Long>> = mutableMapOf()

        /** Monotonic (nanoTime) deadlines, in-memory only. */
        private val monoDeadlineByScope: MutableMap<String, Long> = mutableMapOf()

        /** Corrupt-file penalty: wall deadline + monotonic deadline. */
        private var penaltyUntilUnix: Long = 0L
        private var penaltyMonoDeadline: Long? = null

        private val _lockedUntilUnix = mutableStateOf(0L)

        val lockedUntilUnix: State<Long> get() = _lockedUntilUnix

        init {
            persistFile?.let { loadFromDisk(it) }
            _lockedUntilUnix.value = recomputeLockedUntil()
        }

        @Synchronized
        fun enablePersistence(f: File) {
            file = f
            loadFromDisk(f)
            _lockedUntilUnix.value = recomputeLockedUntil()
        }

        @Synchronized
        fun recordFailure(scope: String, nowUnix: Long, nowNanos: Long?): Long {
            val list = failuresByScope.getOrPut(scope) { mutableListOf() }
            list += nowUnix
            val delay = cooldownSecondsFor(list.size)
            val deadline = if (delay > 0L) nowUnix + delay else 0L
            if (nowNanos != null && delay > 0L) {
                monoDeadlineByScope[scope] = nowNanos + delay * NANOS_PER_SECOND
            }
            _lockedUntilUnix.value = recomputeLockedUntil()
            persistToDisk()
            return deadline
        }

        /** Wall-clock-only variant (tests / deterministic callers). */
        fun recordFailure(scope: String, nowUnix: Long): Long =
            recordFailure(scope, nowUnix, null)

        /** Wall-clock-only, max across every scope. */
        fun cooldownRemainingSeconds(nowUnix: Long): Long =
            cooldownRemainingSecondsAny(nowUnix, null)

        @Synchronized
        fun recordSuccess(scope: String) {
            // Only THIS scope's counter + deadline are cleared — a success
            // on another scope must never lift a lockout elsewhere.
            failuresByScope.remove(scope)
            monoDeadlineByScope.remove(scope)
            _lockedUntilUnix.value = recomputeLockedUntil()
            persistToDisk()
        }

        @Synchronized
        fun cooldownRemainingSeconds(scope: String, nowUnix: Long, nowNanos: Long?): Long {
            val timestamps = failuresByScope[scope]
            var remaining = 0L
            if (!timestamps.isNullOrEmpty()) {
                val policy = cooldownSecondsFor(timestamps.size)
                val wallDeadline = if (policy > 0L) timestamps.last() + policy else 0L
                // Cap at the policy cooldown: a clock moved backwards
                // must not stretch the wait beyond the policy maximum.
                remaining = min(max(0L, wallDeadline - nowUnix), policy)
            }
            if (nowNanos != null) {
                monoDeadlineByScope[scope]?.let { mono ->
                    remaining = max(remaining, ceilSeconds(mono - nowNanos))
                }
            }
            return max(remaining, penaltyRemaining(nowUnix, nowNanos))
        }

        @Synchronized
        fun cooldownRemainingSecondsAny(nowUnix: Long, nowNanos: Long?): Long {
            var best = penaltyRemaining(nowUnix, nowNanos)
            val scopes = failuresByScope.keys + monoDeadlineByScope.keys
            for (s in scopes) {
                best = max(best, cooldownRemainingSeconds(s, nowUnix, nowNanos))
            }
            return best
        }

        @Synchronized
        fun failureCount(scope: String): Int =
            failuresByScope[scope]?.size ?: 0

        @Synchronized
        fun reset() {
            failuresByScope.clear()
            monoDeadlineByScope.clear()
            penaltyUntilUnix = 0L
            penaltyMonoDeadline = null
            _lockedUntilUnix.value = 0L
            persistToDisk()
        }

        private fun penaltyRemaining(nowUnix: Long, nowNanos: Long?): Long {
            var remaining = min(
                max(0L, penaltyUntilUnix - nowUnix),
                CORRUPT_FILE_PENALTY_SECONDS,
            )
            if (nowNanos != null) {
                penaltyMonoDeadline?.let { mono ->
                    remaining = max(remaining, ceilSeconds(mono - nowNanos))
                }
            }
            return remaining
        }

        private fun loadFromDisk(f: File) {
            if (!f.isFile) return
            val now = wallClock()
            val snapshot = runCatching {
                JSON.decodeFromString(PinDiskSnapshot.serializer(), f.readText(Charsets.UTF_8))
            }.getOrElse { ex ->
                // Corrupt / unreadable: do NOT reset to zero failures.
                // Keep whatever is already in memory and apply a
                // lockout to every scope so a deliberately corrupted
                // file never grants free attempts.
                System.err.println(
                    "[PinAttemptTracker] failures file unreadable " +
                        "(${ex::class.simpleName}); applying " +
                        "${CORRUPT_FILE_PENALTY_SECONDS}s lockout",
                )
                penaltyUntilUnix = now + CORRUPT_FILE_PENALTY_SECONDS
                penaltyMonoDeadline = System.nanoTime() +
                    CORRUPT_FILE_PENALTY_SECONDS * NANOS_PER_SECOND
                return
            }
            for (entry in snapshot.entries) {
                // Clamp timestamps from the future (clock moved back
                // after the failure, or a tampered file) to "now".
                val clamped = entry.failTimestamps.map { ts -> min(ts, now) }
                val existing = failuresByScope[entry.scope]
                // Max failures seen so far wins (memory vs disk).
                if (existing == null || existing.size < clamped.size) {
                    failuresByScope[entry.scope] = clamped.toMutableList()
                }
            }
        }

        private fun persistToDisk() {
            val f = file ?: return
            runCatching {
                f.parentFile?.mkdirs()
                val entries = failuresByScope.map { (scope, timestamps) ->
                    PinScopeEntry(scope, timestamps.toList())
                }
                val text = JSON.encodeToString(PinDiskSnapshot.serializer(), PinDiskSnapshot(entries = entries))
                val tmp = File(f.parentFile ?: f.canonicalFile.parentFile, "${f.name}.tmp")
                tmp.writeText(text, Charsets.UTF_8)
                // Use Files.move with REPLACE_EXISTING for atomic rename on
                // both POSIX (rename syscall) and Windows (MoveFileExW).
                Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { ex ->
                System.err.println("[PinAttemptTracker] persistToDisk failed: ${ex::class.simpleName}: ${ex.message}")
            }
        }

        private fun recomputeLockedUntil(): Long {
            var maxDeadline = penaltyUntilUnix
            for ((_, timestamps) in failuresByScope) {
                if (timestamps.isEmpty()) continue
                val lastTs = timestamps.last()
                val delay = cooldownSecondsFor(timestamps.size)
                val deadline = if (delay > 0L) lastTs + delay else 0L
                if (deadline > maxDeadline) maxDeadline = deadline
            }
            return maxDeadline
        }

        companion object {
            val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

            private const val NANOS_PER_SECOND: Long = 1_000_000_000L

            private fun ceilSeconds(nanos: Long): Long =
                if (nanos <= 0L) 0L else (nanos + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND

            fun cooldownSecondsFor(failures: Int): Long = when {
                failures < 3 -> 0L
                failures < 5 -> 5L
                failures < 10 -> 30L
                failures < 15 -> 120L
                else -> 600L
            }
        }
    }
}
