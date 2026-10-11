package io.bhodl.desktop

import io.bhodl.desktop.wizard.DevicePinSession

/**
 * Security audit 2026-10 (L-idle) — app-wide inactivity / minimise
 * lock policy, independent of whether a device-PIN session exists.
 *
 * The previous watcher only armed when the registry was open
 * (`RegistryStore.current() != null`) and only pointer events counted
 * as activity. That meant:
 *  - a legacy / registry-less signing wallet open in [WalletStore]
 *    never auto-locked;
 *  - a user typing (keyboard only) got locked out mid-form;
 *  - minimising the window kept the wallet unlocked indefinitely.
 *
 * Activity is tracked with [System.nanoTime] (monotonic: immune to
 * wall-clock changes). [touch] is called from both pointer and key
 * events at the NavHost root and also refreshes [DevicePinSession].
 */
public object IdleLock {

    /** Inactivity timeout — same as the device-PIN session timeout. */
    public const val IDLE_TIMEOUT_MS: Long = DevicePinSession.DEFAULT_IDLE_TIMEOUT_MS

    /** Grace period after the window is minimised before locking. */
    public const val MINIMIZED_GRACE_MS: Long = 60_000L

    @Volatile
    private var lastActivityNanos: Long = System.nanoTime()

    @Volatile
    private var minimizedSinceNanos: Long? = null

    /** Record user activity (pointer / key event). */
    public fun touch(nowNanos: Long = System.nanoTime()) {
        lastActivityNanos = nowNanos
        DevicePinSession.touch()
    }

    /** Window iconified / restored. */
    public fun setMinimized(minimized: Boolean, nowNanos: Long = System.nanoTime()) {
        minimizedSinceNanos = if (minimized) (minimizedSinceNanos ?: nowNanos) else null
        if (!minimized) lastActivityNanos = nowNanos
    }

    /**
     * Pure decision function.
     *
     * @param sessionOpen a signing wallet ([io.bhodl.desktop.wizard.WalletStore])
     *   or the registry ([io.bhodl.desktop.wizard.RegistryStore]) is open.
     * @param registryOpen the registry specifically is open; then a missing
     *   / expired [DevicePinSession] also forces the lock.
     * @param devicePinExpired `DevicePinSession.isExpired()`.
     */
    public fun shouldLock(
        sessionOpen: Boolean,
        registryOpen: Boolean,
        devicePinExpired: Boolean,
        nowNanos: Long = System.nanoTime(),
        lastActivity: Long = lastActivityNanos,
        minimizedSince: Long? = minimizedSinceNanos,
        idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
        minimizedGraceMs: Long = MINIMIZED_GRACE_MS,
    ): Boolean {
        if (!sessionOpen) return false
        if (registryOpen && devicePinExpired) return true
        val idleMs = (nowNanos - lastActivity) / 1_000_000L
        if (idleMs >= idleTimeoutMs) return true
        if (minimizedSince != null) {
            val minimizedMs = (nowNanos - minimizedSince) / 1_000_000L
            if (minimizedMs >= minimizedGraceMs) return true
        }
        return false
    }
}
