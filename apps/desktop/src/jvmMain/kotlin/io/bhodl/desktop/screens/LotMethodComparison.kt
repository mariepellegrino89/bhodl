package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLotDisposalFfi

/**
 * UX review 2026-10 (item 10) — lot-selection view of the sell flow.
 *
 * Before confirming a sale the user sees FIFO, HIFO and Specific-ID
 * side by side: which lots each method consumes and the realized P&L it
 * books. No competitor shows this (CoinTracker hides the matched lots,
 * Koinly has no true Spec-ID, Monarch has lots but no method), so it is
 * the clearest place where BHODL can make cost basis legible.
 *
 * Everything here mirrors the registry's `record_sell` /
 * `record_sell_with_lots` so the preview is the number that gets booked:
 *  - only lots open on the sale date are eligible ([openLotsAt]);
 *  - FIFO = oldest purchase first, HIFO = highest unit cost (price +
 *    purchase fee per unit) first ([lotsSortedForDisposal]);
 *  - basis per unit = price + [lotFeePerUnit]; sale fees are allocated
 *    pro-rata to each consumed quantity;
 *  - the BTC P&L uses the backfill formula
 *    (`qty × sale_price / btc_at_sale − qty × basis / btc_at_purchase`,
 *    sale fees excluded, as `compute_gain_loss_btc` does).
 *
 * Future: a lot ↔ UTXO link (a UTXO is the natural Specific-ID unit for
 * bitcoin itself) would let the Specific-ID column pre-select the coins
 * the wallet is actually spending. Not modelled yet — lots carry no
 * outpoint.
 */

/** One lot consumed by a method. */
internal data class LotConsumption(
    val lot: TrackingInvestmentLotFfi,
    val qty: Double,
    /** Price + purchase fee per unit, in the investment's currency. */
    val basisPerUnit: Double,
    /** Realized fiat P&L of this slice, sale fees already allocated. */
    val pnlFiat: Double,
    /** Realized P&L in BTC; null when either BTC rate is missing. */
    val pnlBtc: Double?,
)

/** Outcome of one cost-basis method for the prospective sale. */
internal data class LotMethodOutcome(
    val method: CostBasisMethod,
    val consumptions: List<LotConsumption>,
    val realizedFiat: Double,
    /** Null when any consumed lot (or the sale) lacks a BTC rate — an
     *  em-dash beats a partial sum. */
    val realizedBtc: Double?,
) {
    /** Explicit picks (lot id → qty) — what the registry books. */
    val picks: Map<Long, Double>
        get() = consumptions.associate { it.lot.id to it.qty }
}

/**
 * Compare FIFO, HIFO and Specific-ID for a prospective sale.
 *
 * Returns one entry per method, in [CostBasisMethod] order. An entry is
 * null when that method cannot produce an outcome:
 *  - FIFO / HIFO: the open lots hold less than [saleQty];
 *  - Specific-ID: [specificPicks] is null/empty or does not validate
 *    ([validateLotSelection]) — the user has not picked lots yet.
 *
 * The whole result is null when the shared inputs are invalid
 * (non-positive / non-finite quantity or price, negative fees).
 *
 * [saleBtcRate] is `1 BTC = N <investment currency>` on the sale date.
 */
internal fun compareLotMethods(
    lots: List<TrackingInvestmentLotFfi>,
    saleQty: Double,
    salePricePerUnit: Double,
    fees: Double,
    saleUnix: Long,
    saleBtcRate: Double?,
    specificPicks: Map<Long, Double>? = null,
    disposals: List<TrackingLotDisposalFfi> = emptyList(),
): Map<CostBasisMethod, LotMethodOutcome?>? {
    if (!salePricePerUnit.isFinite() || salePricePerUnit <= 0.0) return null
    if (!fees.isFinite() || fees < 0.0) return null
    val open = openLotsAt(lots, saleUnix)
    val out = LinkedHashMap<CostBasisMethod, LotMethodOutcome?>()
    for (method in CostBasisMethod.entries) {
        out[method] = when (method) {
            CostBasisMethod.SPECIFIC_ID -> {
                val picks = specificPicks?.takeIf { it.isNotEmpty() }
                val check = picks?.let { validateLotSelection(lots, it, saleUnix) }
                if (check is LotSelectionCheck.Valid) {
                    outcomeFor(method, open, check.picks, salePricePerUnit, fees, saleBtcRate, disposals)
                } else {
                    null
                }
            }
            else -> {
                if (!saleQty.isFinite() || saleQty <= 0.0) return null
                val picks = planOrderedConsumption(lotsSortedForDisposal(open, method), saleQty)
                picks?.let {
                    outcomeFor(method, open, it, salePricePerUnit, fees, saleBtcRate, disposals)
                }
            }
        }
    }
    return out
}

/**
 * Walk [ordered] taking `min(remaining, lot.quantity)` from each lot.
 * Null when the lots cannot cover [saleQty] (over-sell). Uses the same
 * relative tolerance idea as the registry so float dust does not turn a
 * full sale into an over-sell.
 */
internal fun planOrderedConsumption(
    ordered: List<TrackingInvestmentLotFfi>,
    saleQty: Double,
): Map<Long, Double>? {
    val tol = saleQty * 1e-9
    val picks = LinkedHashMap<Long, Double>()
    var remaining = saleQty
    for (l in ordered) {
        if (remaining <= tol) break
        val take = kotlin.math.min(remaining, l.quantity)
        if (take <= 0.0) continue
        picks[l.id] = take
        remaining -= take
    }
    return if (remaining > tol) null else picks
}

private fun outcomeFor(
    method: CostBasisMethod,
    open: List<TrackingInvestmentLotFfi>,
    picks: Map<Long, Double>,
    salePricePerUnit: Double,
    fees: Double,
    saleBtcRate: Double?,
    disposals: List<TrackingLotDisposalFfi>,
): LotMethodOutcome? {
    val total = picks.values.sum()
    if (!total.isFinite() || total <= 0.0) return null
    val byId = open.associateBy { it.id }
    val saleRate = saleBtcRate?.takeIf { it.isFinite() && it > 0.0 }
    val rows = picks.map { (lotId, qty) ->
        val lot = byId[lotId] ?: return null
        val basis = lot.pricePerUnit + lotFeePerUnit(lot, disposals)
        val pnlFiat = (salePricePerUnit - basis) * qty - fees * (qty / total)
        val lotRate = lot.btcPerUnitAtPurchase?.takeIf { it.isFinite() && it > 0.0 }
        val pnlBtc = if (saleRate != null && lotRate != null) {
            qty * salePricePerUnit / saleRate - qty * basis / lotRate
        } else {
            null
        }
        LotConsumption(lot = lot, qty = qty, basisPerUnit = basis, pnlFiat = pnlFiat, pnlBtc = pnlBtc)
    }
    val btcParts = rows.map { it.pnlBtc }
    return LotMethodOutcome(
        method = method,
        consumptions = rows,
        realizedFiat = rows.sumOf { it.pnlFiat },
        realizedBtc = if (btcParts.any { it == null }) null else btcParts.sumOf { it!! },
    )
}
