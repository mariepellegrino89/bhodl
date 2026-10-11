package io.bhodl.desktop.ui.money

/**
 * Phase 8.3.1 — visual density choice for the [Money] composable.
 *
 *  - [Hero] — the big net-worth display at the top of the dashboard.
 *    56sp primary + 16sp secondary, stacked vertically.
 *  - [Medium] — table cells with two rows: 14sp primary, 11sp secondary.
 *    The default for most surfaces.
 *  - [Compact] — single inline line, e.g. `₿0.0194 (€11.4k)`. Used in
 *    list rows where vertical space is scarce.
 */
public enum class MoneyDensity {
    Hero,
    Medium,
    Compact,
}
