package io.bhodl.desktop.wizard

import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_device_pin_verify_failed
import io.bhodl.shared.resources.onb_unknown_error

/**
 * Phase 8.3.1 — process-lifetime marker of the unlocked device-PIN
 * session, plus its idle clock.
 *
 * Marked by [io.bhodl.desktop.screens.DevicePinUnlockScreen],
 * [io.bhodl.desktop.screens.MigrationScreen] and the PIN-setup flow
 * right after a successful registry open; cleared by
 * [io.bhodl.desktop.wizard.RegistryStore.clear] (logout / wipe path).
 *
 * Audit 2026-10 (KDF M3/M5) — this object used to PARK THE DEVICE PIN
 * itself, because the Phase 8 FFI surfaces (`btc_rate_*`,
 * `phase_8_2_backfill_btc_at_time`, `net_worth_history`, the
 * `resolve_*` resolvers) took `data_dir + device_pin` and re-derived the
 * SQLCipher key (Argon2id) on every call. Those are now methods on the
 * open [io.bhodl.ffi.RegistryHandle], which reuses its already-derived
 * key, so the PIN no longer needs to live in JVM memory for the whole
 * session: only the "unlocked at / last activity" state is kept.
 * A side effect of the old design is gone too — every background read
 * of the PIN (backfill, rate resolvers, the net-worth chart) used to
 * refresh the idle clock, so background work kept an unattended session
 * unlocked. Now only real user activity ([touch], wired from
 * [io.bhodl.desktop.IdleLock]) does.
 *
 * ## Idle-timeout (A07 — Authentication Failures)
 *
 * [isExpired] reports whether the session has been idle for longer than
 * the timeout (default [DEFAULT_IDLE_TIMEOUT_MS] = 15 minutes); the
 * root-level watcher in `App.kt` then locks and routes to the PIN gate.
 */
public object DevicePinSession {

    /**
     * Default idle timeout: 15 minutes. After this many milliseconds
     * without a [touch], [isExpired] returns true.
     */
    public const val DEFAULT_IDLE_TIMEOUT_MS: Long = 15L * 60L * 1_000L

    @Volatile
    private var unlocked: Boolean = false

    /**
     * Unix-millisecond timestamp of the last activity that refreshed
     * the idle clock. Zero means no session is open.
     */
    @Volatile
    private var lastActivityMs: Long = 0L

    /** Mark the device-PIN session as unlocked (the PIN is NOT retained). */
    public fun markUnlocked(now: Long = nowMs()) {
        unlocked = true
        lastActivityMs = now
    }

    /** `true` while a session is open and not idle-expired. */
    public fun isUnlocked(
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        now: Long = nowMs(),
    ): Boolean = !isExpired(idleTimeoutMs = idleTimeoutMs, now = now)

    /**
     * Reset the idle clock to [now]. Called ONLY for real user activity
     * (pointer / key events via [io.bhodl.desktop.IdleLock.touch]);
     * background jobs must never call this.
     */
    public fun touch(now: Long = nowMs()) {
        if (unlocked) lastActivityMs = now
    }

    /**
     * `true` when the session has been idle for longer than
     * [idleTimeoutMs] relative to [now], OR when no session is open.
     * Pure: does not change state.
     */
    public fun isExpired(
        idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
        now: Long = nowMs(),
    ): Boolean {
        if (!unlocked) return true
        return (now - lastActivityMs) >= idleTimeoutMs
    }

    /** Close the session. Called by logout / wipe / lock paths. */
    public fun clear() {
        unlocked = false
        lastActivityMs = 0L
    }

    /**
     * Re-authenticate [candidate] against the open registry's device key
     * via [matches] (production: `RegistryHandle::matchesDevicePin`, run
     * on `Dispatchers.IO` by the caller) and classify the outcome.
     *
     * `matchesDevicePin` reports a rejected PIN as `false`; it only
     * THROWS when the check could not be performed at all (KDF
     * descriptor missing / unreadable / tampered, files locked, registry
     * closed, …) — the candidate was never compared. So:
     *
     * - `true`  → [DevicePinCheck.Match]
     * - `false` → [DevicePinCheck.WrongPin] — the ONLY outcome the caller
     *   may count towards the device-PIN lockout;
     * - throw   → [DevicePinCheck.StorageError], never counted. The
     *   message comes from [PinErrors.classify] / [PinErrors.messageFor]
     *   for the failure kinds it recognises, else from the (path-
     *   sanitised) error detail. Not counting these is safe: an error
     *   reveals nothing about the candidate, so it is no guessing oracle.
     */
    public fun verify(candidate: String, matches: (String) -> Boolean): DevicePinCheck {
        val ok = try {
            matches(candidate)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return DevicePinCheck.StorageError(storageMessage(e))
        }
        return if (ok) DevicePinCheck.Match else DevicePinCheck.WrongPin
    }

    private fun storageMessage(e: Throwable): String = when (val kind = PinErrors.classify(e)) {
        // classify() is fail-closed for UNLOCK errors (unknown detail =
        // wrong PIN); here an exception is never a PIN verdict, so an
        // unrecognised detail is surfaced as-is instead.
        PinFailureKind.WrongPin, PinFailureKind.Other ->
            localizedText(
                Res.string.onb_device_pin_verify_failed,
                io.bhodl.desktop.ui.sanitizeErrorDetail(
                    (e as? io.bhodl.ffi.WalletException)?.message ?: e.message,
                    localizedText(Res.string.onb_unknown_error),
                ),
            )
        else -> PinErrors.messageFor(kind)
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}

/** Outcome of [DevicePinSession.verify]. */
public sealed interface DevicePinCheck {
    /** The candidate is the device PIN. */
    public data object Match : DevicePinCheck

    /** The candidate was checked and rejected — counts towards the lockout. */
    public data object WrongPin : DevicePinCheck

    /**
     * The check could not run (storage / KDF descriptor problem). Not a
     * PIN verdict: never counted. [message] is user-facing.
     */
    public data class StorageError(val message: String) : DevicePinCheck
}
