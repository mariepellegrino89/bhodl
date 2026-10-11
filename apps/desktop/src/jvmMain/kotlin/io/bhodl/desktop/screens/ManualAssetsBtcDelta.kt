package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingManualAssetFfi

/**
 * Phase 8.3.4 — pure compute for the Δ BTC cell of [ManualAssetsCard].
 *
 * Returns the percentage variation of the asset's BTC-equivalent value
 * between the purchase event (snapshot stored on the FFI struct via the
 * Phase 8.2 backfill) and now (live PriceCache rate). Used by the
 * dual-line "VALUE NOW / VALUE AT BUY / Δ BTC" layout to surface the
 * canonical Bitcoin Lens story for non-investment holdings (car / house
 * / etc): even when a real-estate row gains in fiat, it usually shrinks
 * in BTC terms because the BTC/fiat rate has appreciated faster.
 *
 * Formula (plan §2.3):
 * ```
 * buyBtc   = purchaseValueFiat / purchaseBtcAtTime
 * nowBtc   = asset.valueFiat   / currentBtcPerUnit
 * deltaPct = (nowBtc / buyBtc - 1) * 100
 * ```
 *
 * Returns null (rendered as "—" in the cell) when any input is missing
 * or non-finite:
 *  - `purchaseBtcAtTime` null → legacy asset never reached by the
 *    Phase 8.2 backfill (rare; the backfill fallbacks to created_at_unix).
 *  - `purchaseValueFiat` null → backfill couldn't infer a sensible
 *    starting fiat amount.
 *  - `currentBtcPerUnit` null → cross-currency (asset.currency !=
 *    dashboard fiat); PriceCache only stores BTC/dashboard-fiat. Same
 *    null-policy as Phase 8.3.3's investments P&L BTC cell.
 *  - any rate / fiat ≤ 0 → would otherwise divide by zero or invert the
 *    sign.
 *
 * The result is deliberately unbounded; the UI applies its color +
 * formatter ([formatPnlPercent]) on top.
 */
internal fun computeManualAssetDeltaBtcPct(
    asset: TrackingManualAssetFfi,
    /** 1 BTC = N units of `asset.currency`, NOW. Null on cross-currency. */
    currentBtcPerUnit: Double?,
): Double? {
    val pBtcRate = asset.purchaseBtcAtTime ?: return null
    val pFiat = asset.purchaseValueFiat ?: return null
    if (!pBtcRate.isFinite() || pBtcRate <= 0.0) return null
    if (!pFiat.isFinite() || pFiat <= 0.0) return null
    if (currentBtcPerUnit == null || !currentBtcPerUnit.isFinite() || currentBtcPerUnit <= 0.0) {
        return null
    }
    if (!asset.valueFiat.isFinite()) return null
    val buyBtc = pFiat / pBtcRate
    val nowBtc = asset.valueFiat / currentBtcPerUnit
    if (buyBtc <= 0.0) return null
    return (nowBtc / buyBtc - 1.0) * 100.0
}
