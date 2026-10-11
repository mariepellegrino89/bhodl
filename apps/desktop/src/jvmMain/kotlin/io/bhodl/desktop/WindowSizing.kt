package io.bhodl.desktop

/**
 * UX review 2026-10 (item 17) — desktop window sizing.
 *
 * The minimum is what the densest desktop layouts need: the 80dp
 * navigation rail plus the wallet transactions table, whose fixed
 * columns (date, amount, status, confirmations) take ~550dp before the
 * label column gets any room; the detail pane goes single-pane below
 * 1000dp of content. Tracker tables drop their low-priority columns on
 * their own (see `ColumnPriority`), and 640dp of height keeps the
 * wallet header, sub-tabs, a page of rows and the footer status bar
 * visible together. Narrower form factors are a mobile concern: the navigation
 * abstraction (`ui/AdaptiveNavigation.kt`) maps the rail to a bottom
 * bar there instead of shrinking the desktop window further.
 */
public object WindowSizing {
    public const val MIN_WIDTH_DP: Int = 960
    public const val MIN_HEIGHT_DP: Int = 640

    /** First-launch size: a common laptop viewport minus window chrome. */
    public const val DEFAULT_WIDTH_DP: Int = 1280
    public const val DEFAULT_HEIGHT_DP: Int = 800
}
