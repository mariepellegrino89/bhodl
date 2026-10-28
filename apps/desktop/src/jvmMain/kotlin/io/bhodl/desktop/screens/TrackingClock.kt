package io.bhodl.desktop.screens

import io.bhodl.desktop.AppClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime

/** Upper bound between two ticks — keeps "now" fresh across DST / sleep. */
private const val MAX_TICK_MS: Long = 60L * 60L * 1000L

/**
 * Wall-clock "now" (epoch seconds) as Compose state that re-ticks at
 * least at every local midnight (and at most hourly), so day-dependent
 * UI — bills due "this month", the calendar's "today", sinking-goal
 * countdowns / OVERDUE — rolls over on a long-running session instead
 * of staying frozen at the moment the composable was first composed
 * (`remember { System.currentTimeMillis() }`).
 */
@Composable
internal fun rememberTickingNowUnix(zone: ZoneId = ZoneId.systemDefault()): Long {
    var now by remember { mutableStateOf(AppClock.nowMillis() / 1000L) }
    LaunchedEffect(zone) {
        while (true) {
            delay(msUntilNextTick(AppClock.nowMillis(), zone))
            now = AppClock.nowMillis() / 1000L
        }
    }
    return now
}

/**
 * Milliseconds from [nowMs] to the next tick: the next local midnight
 * (+1 s of slack so the new date is already observable), capped at
 * [MAX_TICK_MS]. Pure for tests.
 */
internal fun msUntilNextTick(nowMs: Long, zone: ZoneId): Long {
    val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
    val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(zone)
    val untilMidnight = nextMidnight.toInstant().toEpochMilli() - nowMs + 1_000L
    return untilMidnight.coerceIn(1_000L, MAX_TICK_MS)
}
