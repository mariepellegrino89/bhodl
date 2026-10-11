package io.bhodl.desktop.ui.money

/**
 * Phase 8.3.1 — semantic flavour of a [MoneyValue] render.
 *
 * Drives both the secondary-line interpretation (live vs at-cost vs
 * pre-computed) and the color tag picked up by the [Money] composable:
 *
 *  - [AtNow] — value is "right now". The secondary line is the live
 *    fiat conversion (via PriceCache). No sign-color.
 *  - [AtCost] — value is a historical snapshot. The secondary line
 *    shows the fiat at-purchase amount; no live conversion. No
 *    sign-color.
 *  - [Averaged] — time-weighted average. Reserved for Phase 8.4
 *    (return-by-account / portfolio attribution). No sign-color today.
 *  - [Delta] — signed difference (e.g. balance change month-over-month).
 *    Preserves sign in the rendering but stays in `textPrimary` color —
 *    a delta in isolation is not a P&L verdict.
 *  - [Pnl] — signed P&L. Same sign preservation as [Delta] plus the
 *    color verdict (`financialPositive` on non-negative, `financialNegative`
 *    on negative — flipped automatically when the user is in the
 *    Eastern color convention through [io.bhodl.ui.theme.BhodlTheme]).
 */
public enum class MoneySemantic {
    AtNow,
    AtCost,
    Averaged,
    Delta,
    Pnl,
}
