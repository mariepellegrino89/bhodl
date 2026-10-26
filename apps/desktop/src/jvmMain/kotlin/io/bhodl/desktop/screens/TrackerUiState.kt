package io.bhodl.desktop.screens

import androidx.compose.runtime.mutableStateOf

/**
 * Phase 9.0 (IA restructure) — process-lifetime UI state shared by
 * every tracker section.
 *
 * With sections promoted to navigation destinations (one route per
 * rail entry), `remember {}` state inside `TrackingScreen` would be
 * lost on every section switch. The pieces that must survive — the
 * **global period** (one persistent selector across Home / Budget /
 * Goals / Portfolio / Reports, finance-tracker convention) and the
 * **Transactions filters** (set by drill-downs from other sections:
 * lollipop category click, bar-bucket click, heatmap day click) —
 * live here instead.
 *
 * Same pattern as [io.bhodl.desktop.data.TrackerCache]: a plain
 * object holding Compose `mutableStateOf`, read by composables as
 * `by TrackerUiState.period`. NOT persisted to disk — a fresh boot
 * starts from the defaults.
 */
internal object TrackerUiState {
    /** Global aggregation window, shared by every section. */
    val period = mutableStateOf(TrackingPeriod.D30)

    /**
     * Transactions category filter. Tri-state: `null` = all,
     * `""` = uncategorised only, other = exact match.
     */
    val categoryFilter = mutableStateOf<String?>(null)

    /**
     * Transactions time-range filter `(startUnix, endUnix)`, set by
     * chart drill-downs. Composable with [categoryFilter] (AND, not
     * XOR): "Food expenses in the last 90 days" is expressible.
     */
    val timeRangeFilter = mutableStateOf<Pair<Long, Long>?>(null)

    /**
     * The calendar month shared by Budget (its month selector) and
     * Transactions (month view): a month picked in one is the month of
     * the other, so "view transactions" from a September budget lists
     * September.
     */
    val month = mutableStateOf(java.time.YearMonth.now())

    /** Transactions show [month] instead of the global period. */
    val txByMonth = mutableStateOf(false)

    fun clearFilters() {
        categoryFilter.value = null
        timeRangeFilter.value = null
        txByMonth.value = false
    }
}
