package io.bhodl.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime coroutine scopes.
 *
 * Security audit 2026-10 — background work that must outlive the
 * screen that started it (the Phase 8.2 BTC-at-time backfill kicked
 * from the unlock screens, the xpub clipboard auto-clear timer) used
 * to run on `rememberCoroutineScope()` of the originating composable,
 * which is cancelled the moment the user navigates away. That silently
 * killed the backfill right after unlock and, worse, left an xpub on
 * the clipboard forever when the user left Settings before the timer
 * fired.
 *
 * [SupervisorJob] so one failing child never cancels its siblings.
 */
public object AppScope {
    /** Background I/O scope for fire-and-forget, app-lifetime jobs. */
    public val io: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
