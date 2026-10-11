package io.bhodl.desktop.ui

import kotlin.math.absoluteValue
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_time_days_ago
import io.bhodl.shared.resources.wview_time_hours_ago
import io.bhodl.shared.resources.wview_time_just_now
import io.bhodl.shared.resources.wview_time_minutes_ago
import io.bhodl.shared.resources.wview_time_seconds_ago
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * Phase 7.2 — pure relative-time formatter for trust signals on the
 * dashboard ("Last refreshed 2m ago", "Price stale — 12m ago").
 *
 * Intentionally short. UX review 2026-10 (rec 3): localized through the
 * app language (`wview_time_*` resources). Buckets (EN):
 *  - < 5 s              → "just now"
 *  - < 60 s             → "Ns ago"
 *  - < 60 min           → "Nm ago"
 *  - < 24 h             → "Nh ago"
 *  - otherwise          → "Nd ago"
 *
 * `nowSec` is exposed as a parameter so tests can pin the clock and
 * the production callsites still get System.currentTimeMillis()/1000
 * by default.
 *
 * Future timestamps (negative deltas) render as "just now" rather than
 * "in 2m" — the trust-signal context never needs the "in future" case
 * (a server clock skew shouldn't yell "in 5h" at the user), and that
 * makes the function safe to drop into any panel without adding a
 * guard at every callsite.
 */
public fun relativeTimeAgo(
    timestampSec: Long,
    nowSec: Long = System.currentTimeMillis() / 1000L,
): String {
    val deltaSec = (nowSec - timestampSec).coerceAtLeast(0L)
    return when {
        deltaSec < 5L -> timeText(Res.string.wview_time_just_now)
        deltaSec < 60L -> timeText(Res.string.wview_time_seconds_ago, deltaSec)
        deltaSec < 3600L -> timeText(Res.string.wview_time_minutes_ago, deltaSec / 60L)
        deltaSec < 86_400L -> timeText(Res.string.wview_time_hours_ago, deltaSec / 3600L)
        else -> timeText(Res.string.wview_time_days_ago, deltaSec / 86_400L)
    }
}

/**
 * Convenience: minutes elapsed since [timestampSec], used by the
 * stale-data threshold checks. Returns 0 when [timestampSec] is in the
 * future — matches the rule in [relativeTimeAgo] above.
 *
 * Exposed separately so callers can ask "is this stale?" without
 * round-tripping through string parsing.
 */
public fun minutesAgo(
    timestampSec: Long,
    nowSec: Long = System.currentTimeMillis() / 1000L,
): Long {
    val deltaSec = (nowSec - timestampSec).coerceAtLeast(0L)
    return deltaSec / 60L
}

/**
 * Phase 7.2 — defaultiest stale threshold. 10 minutes balances "the
 * spot price moves frequently enough that >10 min looks suspicious"
 * with "the user didn't just open the app and see a yellow chip
 * because the launch sync hadn't completed". Centralised so the
 * thresholds for fiat overlay + tracker stay aligned.
 */
public const val STALE_THRESHOLD_MINUTES: Long = 10L

/** True when [timestampSec] is older than [STALE_THRESHOLD_MINUTES]. */
public fun isStale(
    timestampSec: Long?,
    nowSec: Long = System.currentTimeMillis() / 1000L,
    thresholdMinutes: Long = STALE_THRESHOLD_MINUTES,
): Boolean {
    if (timestampSec == null) return false
    return minutesAgo(timestampSec, nowSec).absoluteValue >= thresholdMinutes
}

/** Resolve [resource] in the app language outside composition (cached by Compose resources). */
private fun timeText(resource: StringResource, vararg args: Any): String =
    runBlocking { getString(resource, *args) }
