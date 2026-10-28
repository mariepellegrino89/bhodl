package io.bhodl.desktop

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The wall clock the screens read for "now", "today" and "this month".
 *
 * Production always gets [System.currentTimeMillis]. The screenshot
 * tests pin it with [fixAt] so a render shows the same dates, month
 * names and "N days ago" labels whatever the day the tests run on —
 * otherwise every reference image would go stale at midnight.
 *
 * Security clocks (PIN lockout, idle lock, wipe) do NOT read this: they
 * keep their own monotonic / wall-clock sources.
 */
internal object AppClock {

    @Volatile
    private var fixedMillis: Long? = null

    fun nowMillis(): Long = fixedMillis ?: System.currentTimeMillis()

    fun nowUnix(): Long = nowMillis() / 1000L

    fun today(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        java.time.Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()

    fun currentMonth(zone: ZoneId = ZoneId.systemDefault()): YearMonth = YearMonth.from(today(zone))

    /** Tests only: pin "now" to [epochMillis], or back to the real clock with `null`. */
    fun fixAt(epochMillis: Long?) {
        fixedMillis = epochMillis
    }
}
