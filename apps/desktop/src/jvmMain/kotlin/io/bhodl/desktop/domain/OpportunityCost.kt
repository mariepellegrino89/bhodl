package io.bhodl.desktop.domain

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingManualAssetFfi

/**
 * Phase 8.5 — shadow-portfolio "vs HODL" opportunity cost.
 *
 * Aggregate "if every fiat unit that ended up in a non-BTC position
 * had been converted to BTC at the rate of the day, how many sat
 * would I have today vs the sat-value of those positions right now?".
 *
 * `totalBtc > 0` → BTC outperformed: I "lost" that many BTC by not
 * HODLing. `totalBtc < 0` → diversification beat BTC (rare; can
 * happen in bear markets when fiat assets held value while BTC
 * dumped). `null` when no position has the data required to compute
 * (the whole portfolio is brand-new or pre-Phase-8.2 backfill).
 *
 * The helper is purely additive — every excluded position increments
 * the `excludedPositions` counter so the UI (8.6 drill-down) can
 * surface "12 of 30 positions counted; 18 missing rate data".
 */
internal data class OpportunityCostBreakdown(
    /** Net opp cost in BTC. Null only when `consideredPositions == 0`. */
    val totalBtc: Double?,
    /** Sum of per-lot opp cost contributions across all investments. */
    val investmentsBtc: Double,
    /** Sum of per-asset opp cost contributions across all manual assets. */
    val manualAssetsBtc: Double,
    /** Lots + manual assets that contributed to the total. */
    val consideredPositions: Int,
    /** Lots + manual assets skipped because of missing/invalid data. */
    val excludedPositions: Int,
)

/**
 * Phase 8.5 — pure derivation of the opportunity-cost breakdown.
 *
 * Algorithm (per spec §2.2):
 *   For each investment lot:
 *     btc_if_hodl = quantity × price_per_unit / btc_per_unit_at_purchase
 *     btc_now     = quantity × current_price_per_unit / current_btc_per_unit
 *     opp_lot     = btc_if_hodl − btc_now
 *   For each manual asset:
 *     btc_if_hodl = purchase_value_fiat / purchase_btc_at_time
 *     btc_now     = value_fiat / current_btc_per_unit
 *     opp_asset   = btc_if_hodl − btc_now
 *
 * The [currentBtcPerUnit] closure lets the caller decide which
 * currencies have a live rate. Today only `BTC/dashboardFiat` is
 * available via PriceCache; everything else returns null and is
 * counted as excluded. Future phases may wire more rates.
 *
 * Inputs:
 *  - [investments]: full investment list (one row per ticker / asset).
 *  - [lotsByInvestment]: per-investment lot map from TrackerCache. An
 *    investment with no lots contributes nothing (pre-7.8 single-row
 *    legacy positions never have btc_per_unit_at_purchase).
 *  - [manualAssets]: full manual-asset list.
 *  - [currentBtcPerUnit]: `(currency: String) -> Double?` returning the
 *    current "1 BTC = X currency" rate, or null when not available.
 *
 * Pure: no Compose, no dispatchers, no I/O. Safe to call in a
 * `remember(...)` block.
 */
internal fun computeOpportunityCostBtc(
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestment: Map<Long, List<TrackingInvestmentLotFfi>>,
    manualAssets: List<TrackingManualAssetFfi>,
    currentBtcPerUnit: (currency: String) -> Double?,
): OpportunityCostBreakdown {
    var invest = 0.0
    var manual = 0.0
    var considered = 0
    var excluded = 0

    for (inv in investments) {
        val invCurrency = inv.costBasisCurrency
        val rateCurrent = currentBtcPerUnit(invCurrency)
        // The current price per unit can come from either the
        // auto-fetcher (`fetchedCurrentValue`) or the user-entered
        // manual override; the Phase 5.x policy is "prefer fetched".
        val currentPricePerUnit = inv.fetchedCurrentValue ?: inv.manualCurrentValue
        val lots = lotsByInvestment[inv.id].orEmpty()

        if (lots.isEmpty()) {
            // Pre-7.8 legacy investments have no lots and therefore no
            // btc_per_unit_at_purchase: the whole investment is one
            // exclusion regardless of why the rate is missing.
            excluded++
            continue
        }

        if (rateCurrent == null || currentPricePerUnit == null) {
            // Investment-wide blocker (cross-currency dashboard, no
            // current price): every lot under it is excluded.
            excluded += lots.size
            continue
        }

        for (lot in lots) {
            val rateAtCost = lot.btcPerUnitAtPurchase
            if (rateAtCost == null) {
                excluded++
                continue
            }
            if (lot.quantity <= 0.0 ||
                !lot.quantity.isFinite() ||
                !lot.pricePerUnit.isFinite() ||
                rateAtCost <= 0.0 ||
                rateCurrent <= 0.0 ||
                !currentPricePerUnit.isFinite()
            ) {
                excluded++
                continue
            }
            val btcIfHodl = lot.quantity * lot.pricePerUnit / rateAtCost
            val btcNow = lot.quantity * currentPricePerUnit / rateCurrent
            invest += btcIfHodl - btcNow
            considered++
        }
    }

    for (asset in manualAssets) {
        val rateCurrent = currentBtcPerUnit(asset.currency)
        if (rateCurrent == null) {
            excluded++
            continue
        }
        val purchaseFiat = asset.purchaseValueFiat
        val purchaseRate = asset.purchaseBtcAtTime
        if (purchaseFiat == null || purchaseRate == null) {
            excluded++
            continue
        }
        if (purchaseRate <= 0.0 ||
            rateCurrent <= 0.0 ||
            !purchaseFiat.isFinite() ||
            !asset.valueFiat.isFinite()
        ) {
            excluded++
            continue
        }
        val btcIfHodl = purchaseFiat / purchaseRate
        val btcNow = asset.valueFiat / rateCurrent
        manual += btcIfHodl - btcNow
        considered++
    }

    val total = if (considered == 0) null else invest + manual
    return OpportunityCostBreakdown(
        totalBtc = total,
        investmentsBtc = invest,
        manualAssetsBtc = manual,
        consideredPositions = considered,
        excludedPositions = excluded,
    )
}
