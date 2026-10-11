package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLotDisposalFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trp_lot_err_invalid_qty
import io.bhodl.shared.resources.trp_lot_err_not_open
import io.bhodl.shared.resources.trp_lot_err_too_much
import org.jetbrains.compose.resources.StringResource

/**
 * Phase 7.8 — cost basis method enum mirroring the Rust whitelist.
 *
 * Used by the UI picker in [InvestmentEditDialog] and by the (future
 * Phase 7.9) sell flow's lot-consumption order.
 */
internal enum class CostBasisMethod(public val wireValue: String) {
    /** First-In-First-Out: oldest lot consumed first. Broker default IT/US. */
    FIFO("fifo"),

    /** Highest-In-First-Out: highest-priced lot consumed first. Minimizes taxable gain. */
    HIFO("hifo"),

    /** Specific identification: caller picks per disposal. */
    SPECIFIC_ID("specific_id");

    public companion object {
        public fun fromWire(s: String?): CostBasisMethod = when (s) {
            "hifo" -> HIFO
            "specific_id" -> SPECIFIC_ID
            else -> FIFO
        }
    }
}

/**
 * Phase 7.8 — aggregate cost basis. When the investment has at least
 * one lot, the lots are the canonical source; otherwise we fall back
 * to the legacy single-row `costBasisFiat` from Phase 5.1.
 *
 * Σ(quantity × pricePerUnit + feesFiat) — fees are added in because
 * they are part of the cost basis (broker spreads, commissions etc.)
 * which is what gets compared to the sell price at disposal time.
 */
internal fun effectiveCostBasis(
    inv: TrackingInvestmentFfi,
    lots: List<TrackingInvestmentLotFfi>,
): Double {
    if (lots.isEmpty()) return inv.costBasisFiat
    var total = 0.0
    for (l in lots) {
        if (!l.quantity.isFinite() || !l.pricePerUnit.isFinite()) continue
        total += l.quantity * l.pricePerUnit + (l.feesFiat.takeIf { it.isFinite() } ?: 0.0)
    }
    return total
}

/**
 * Phase 7.8 — aggregate quantity. Same fallback policy as
 * [effectiveCostBasis]: lots win when present, else legacy field.
 */
internal fun effectiveQuantity(
    inv: TrackingInvestmentFfi,
    lots: List<TrackingInvestmentLotFfi>,
): Double {
    if (lots.isEmpty()) return inv.quantity
    var total = 0.0
    for (l in lots) {
        if (l.quantity.isFinite()) total += l.quantity
    }
    return total
}

/**
 * Phase 7.8 — order lots for disposal under the given method. Used
 * by the (future Phase 7.9) sell flow. Pure; exposed `internal` so
 * unit tests can pin the iteration order.
 *
 *  - [CostBasisMethod.FIFO]: oldest first (sorted by purchaseUnix ASC).
 *  - [CostBasisMethod.HIFO]: highest unit cost first — price plus the
 *    lot's purchase fee per unit ([lotFeePerUnit]), the same key the
 *    registry's `record_sell` sorts on (a price-only sort disagreed with
 *    the booked sale whenever two lots' fees differed).
 *  - [CostBasisMethod.SPECIFIC_ID]: oldest first — only the DISPLAY
 *    order of the lot picker. A specific-ID sale never derives the
 *    consumption order: the user picks lots and quantities in
 *    [SellDialog] and the registry's `record_sell_with_lots` books
 *    exactly that ([validateLotSelection] / [previewSelectionPnl]).
 */
internal fun lotsSortedForDisposal(
    lots: List<TrackingInvestmentLotFfi>,
    method: CostBasisMethod,
): List<TrackingInvestmentLotFfi> = when (method) {
    CostBasisMethod.FIFO -> lots.sortedBy { it.purchaseUnix }
    CostBasisMethod.HIFO -> lots.sortedByDescending { it.pricePerUnit + lotFeePerUnit(it) }
    CostBasisMethod.SPECIFIC_ID -> lots.sortedBy { it.purchaseUnix }
}

/**
 * Purchase fee attributable to ONE unit of [lot], in the lot's currency.
 *
 * The backend allocates a lot's purchase fees over its ORIGINAL quantity
 * (fees / original_quantity) so that partial sells don't inflate the
 * per-unit fee of what remains. The registry (schema v2) exposes the
 * frozen purchased size as [TrackingInvestmentLotFfi.originalQuantity];
 * that value is authoritative.
 *
 * Fallback for records without it (hand-built test fixtures — the
 * registry always populates it): reconstruct as
 * `remaining quantity + Σ qty_consumed of this lot's disposals`.
 */
internal fun lotFeePerUnit(
    lot: TrackingInvestmentLotFfi,
    disposals: List<TrackingLotDisposalFfi> = emptyList(),
): Double {
    val fees = lot.feesFiat.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
    val original = lot.originalQuantity?.takeIf { it.isFinite() && it > 0.0 }
        ?: run {
            val consumed = disposals
                .filter { it.lotId == lot.id && it.qtyConsumed.isFinite() && it.qtyConsumed > 0.0 }
                .sumOf { it.qtyConsumed }
            (lot.quantity.takeIf { it.isFinite() && it > 0.0 } ?: 0.0) + consumed
        }
    if (original <= 0.0) return 0.0
    return fees / original
}

/**
 * Phase 7.9 — realized P&L preview for a prospective sell, mirroring
 * the registry's `record_sell`: lots are consumed in
 * [lotsSortedForDisposal] order, the cost basis of each consumed unit
 * is `price_per_unit + lot fee per unit` ([lotFeePerUnit]), and the
 * sale [fees] are allocated pro-rata to each lot's consumed share.
 *
 * Returns null when the inputs are not valid (non-positive / non-finite
 * quantity or price, negative / non-finite fees).
 */
internal fun previewSellPnl(
    lots: List<TrackingInvestmentLotFfi>,
    method: CostBasisMethod,
    saleQty: Double,
    salePricePerUnit: Double,
    fees: Double,
    disposals: List<TrackingLotDisposalFfi> = emptyList(),
): Double? {
    if (!saleQty.isFinite() || saleQty <= 0.0) return null
    if (!salePricePerUnit.isFinite() || salePricePerUnit <= 0.0) return null
    if (!fees.isFinite() || fees < 0.0) return null
    var remaining = saleQty
    var pnl = 0.0
    for (l in lotsSortedForDisposal(lots.filter { it.quantity > 0.0 && it.quantity.isFinite() }, method)) {
        if (remaining <= 0.0) break
        val consumed = kotlin.math.min(remaining, l.quantity)
        val feeAlloc = fees * (consumed / saleQty)
        val basisPerUnit = l.pricePerUnit + lotFeePerUnit(l, disposals)
        pnl += (salePricePerUnit - basisPerUnit) * consumed - feeAlloc
        remaining -= consumed
    }
    return pnl
}

/**
 * Lots a sale dated [saleUnix] may dispose: units left and bought at or
 * before the sale date — the same filter the registry applies.
 */
internal fun openLotsAt(
    lots: List<TrackingInvestmentLotFfi>,
    saleUnix: Long,
): List<TrackingInvestmentLotFfi> = lots.filter {
    it.quantity.isFinite() && it.quantity > 0.0 && it.purchaseUnix <= saleUnix
}

/** Outcome of [validateLotSelection]. */
internal sealed interface LotSelectionCheck {
    /** Valid: [total] units over [picks] (lot id → quantity, > 0 only). */
    data class Valid(val total: Double, val picks: Map<Long, Double>) : LotSelectionCheck

    /** Nothing picked yet (not an error to show). */
    data object Empty : LotSelectionCheck

    /**
     * A picked quantity is wrong; [message] + [args] is the user-facing
     * text (resolved at render time, UX review 2026-10 rec 3).
     */
    data class Invalid(val message: StringResource, val args: List<String> = emptyList()) : LotSelectionCheck
}

/**
 * Specific-ID sell — validate the per-lot quantities typed in the lot
 * picker ([inputs]: lot id → raw parsed value, `null` = unparseable).
 * Mirrors the registry checks in `record_sell_with_lots` so the Save
 * button is only enabled for a selection the backend will accept:
 * every lot must be open on [saleUnix] (see [openLotsAt]) and each
 * quantity must be finite, ≥ 0 and ≤ the lot's remaining quantity.
 * Zero / blank entries are simply not picked.
 */
internal fun validateLotSelection(
    lots: List<TrackingInvestmentLotFfi>,
    inputs: Map<Long, Double?>,
    saleUnix: Long,
): LotSelectionCheck {
    val open = openLotsAt(lots, saleUnix).associateBy { it.id }
    val picks = LinkedHashMap<Long, Double>()
    for ((lotId, qty) in inputs) {
        if (qty == null || !qty.isFinite() || qty < 0.0) {
            return LotSelectionCheck.Invalid(Res.string.trp_lot_err_invalid_qty, listOf(lotId.toString()))
        }
        if (qty == 0.0) continue
        val lot = open[lotId]
            ?: return LotSelectionCheck.Invalid(Res.string.trp_lot_err_not_open, listOf(lotId.toString()))
        if (qty > lot.quantity) {
            return LotSelectionCheck.Invalid(
                Res.string.trp_lot_err_too_much,
                listOf(lotId.toString(), formatQuantity(lot.quantity)),
            )
        }
        picks[lotId] = qty
    }
    if (picks.isEmpty()) return LotSelectionCheck.Empty
    return LotSelectionCheck.Valid(picks.values.sum(), picks)
}

/**
 * Realized P&L preview of a specific-ID sale over the picked [picks]
 * (lot id → quantity): same formula as [previewSellPnl] and the
 * registry — basis = price + purchase fee per unit ([lotFeePerUnit]),
 * sale [fees] allocated pro-rata to each picked quantity.
 */
internal fun previewSelectionPnl(
    lots: List<TrackingInvestmentLotFfi>,
    picks: Map<Long, Double>,
    salePricePerUnit: Double,
    fees: Double,
    disposals: List<TrackingLotDisposalFfi> = emptyList(),
): Double? {
    if (!salePricePerUnit.isFinite() || salePricePerUnit <= 0.0) return null
    if (!fees.isFinite() || fees < 0.0) return null
    val total = picks.values.sum()
    if (!total.isFinite() || total <= 0.0) return null
    val byId = lots.associateBy { it.id }
    var pnl = 0.0
    for ((lotId, qty) in picks) {
        val lot = byId[lotId] ?: return null
        val basisPerUnit = lot.pricePerUnit + lotFeePerUnit(lot, disposals)
        pnl += (salePricePerUnit - basisPerUnit) * qty - fees * (qty / total)
    }
    return pnl
}

/**
 * Phase 7.9 — realized P&L for an investment: sum of `gain_loss_fiat`
 * across every disposal that traces back to this position's sells.
 * Caller filters the disposals to those whose `sell_id` belongs to
 * one of this investment's sells.
 *
 * Zero when no disposals.
 */
internal fun realizedPnl(disposals: List<TrackingLotDisposalFfi>): Double =
    disposals.sumOf { if (it.gainLossFiat.isFinite()) it.gainLossFiat else 0.0 }

/**
 * Phase 7.9 — unrealized P&L on the **remaining** lot quantities.
 * Returns null when [currentValuePerUnit] is null (the UI renders
 * "—" in that case to make the missing input explicit).
 *
 * Skips lots with `quantity <= 0` — those have been fully consumed
 * by past sells and only the historical disposal carries their P&L.
 *
 * The cost basis per unit includes the lot's purchase fees
 * ([lotFeePerUnit]) — same basis the registry uses when it books a
 * disposal, so "unrealized now" and "realized if sold now" agree.
 */
internal fun unrealizedPnl(
    remainingLots: List<TrackingInvestmentLotFfi>,
    currentValuePerUnit: Double?,
    disposals: List<TrackingLotDisposalFfi> = emptyList(),
): Double? {
    val price = currentValuePerUnit ?: return null
    if (!price.isFinite()) return null
    var pnl = 0.0
    for (l in remainingLots) {
        if (l.quantity <= 0.0) continue
        if (!l.quantity.isFinite() || !l.pricePerUnit.isFinite()) continue
        pnl += (price - (l.pricePerUnit + lotFeePerUnit(l, disposals))) * l.quantity
    }
    return pnl
}

/**
 * Phase 7.9 — total P&L = realized + unrealized. Null-safe on the
 * unrealized side: when current price is missing we surface the
 * realized portion only (rather than null-everything).
 */
internal fun totalPnl(realized: Double, unrealized: Double?): Double =
    realized + (unrealized ?: 0.0)

// ─────────────────────────────────────────────────────────────────────
// Phase 8.3.3 — Bitcoin Lens P&L helpers
// ─────────────────────────────────────────────────────────────────────
//
// Symmetric to the fiat helpers above but computed in BTC terms. Used
// by InvestmentsCard's new `P&L BTC` column to surface the divergence
// between "asset went up in EUR" and "asset went down in BTC".
//
// Null-policy is stricter than the fiat side: when an input is missing
// (any disposal without `gainLossBtc`, any lot without
// `btcPerUnitAtPurchase`, or the dashboard rate not aligned to the
// investment's currency), the helper returns `null`. The UI then
// renders an em-dash on that cell rather than a partial/misleading sum.

/**
 * Phase 8.3.3 — realized P&L expressed in BTC.
 *
 * Sums [TrackingLotDisposalFfi.gainLossBtc] across the supplied
 * disposals. If even one disposal has a null `gainLossBtc` (partial
 * backfill of Phase 8.2), returns null — better an em-dash than a
 * sum that silently drops disposals.
 */
internal fun realizedPnlBtc(disposals: List<TrackingLotDisposalFfi>): Double? {
    if (disposals.isEmpty()) return 0.0
    var sum = 0.0
    for (d in disposals) {
        val v = d.gainLossBtc ?: return null
        if (!v.isFinite()) return null
        sum += v
    }
    return sum
}

/**
 * Phase 8.3.3 — unrealized P&L on the remaining lot quantities, in BTC.
 *
 * Formula per lot (mirrors the fiat unrealized but routes the cost
 * basis and the current value through their respective BTC rates):
 * ```
 *   cost_btc_per_unit    = lot.pricePerUnit / lot.btcPerUnitAtPurchase
 *   current_btc_per_unit = currentValuePerUnit / currentBtcPerUnit
 *   pnl_btc             += lot.quantity * (current_btc_per_unit - cost_btc_per_unit)
 * ```
 *
 * Returns null when:
 *  - [currentValuePerUnit] is null or non-finite (no live quote)
 *  - [currentBtcPerUnit] is null, non-positive, or non-finite (we don't
 *    have a BTC rate for the investment's cost-basis currency — e.g.
 *    investment in USD on a EUR dashboard)
 *  - any contributing lot has a null or non-positive `btcPerUnitAtPurchase`
 *    (Phase 8.2 backfill didn't reach it)
 *
 * Skips lots with `quantity <= 0` (those are fully consumed and only
 * the historical disposal carries their P&L).
 */
internal fun unrealizedPnlBtc(
    remainingLots: List<TrackingInvestmentLotFfi>,
    currentValuePerUnit: Double?,
    currentBtcPerUnit: Double?,
): Double? {
    if (currentValuePerUnit == null || !currentValuePerUnit.isFinite()) return null
    if (currentBtcPerUnit == null || !currentBtcPerUnit.isFinite() ||
        currentBtcPerUnit <= 0.0
    ) {
        return null
    }
    var pnl = 0.0
    for (l in remainingLots) {
        if (l.quantity <= 0.0) continue
        if (!l.quantity.isFinite() || !l.pricePerUnit.isFinite()) continue
        val lotBtcRate = l.btcPerUnitAtPurchase ?: return null
        if (!lotBtcRate.isFinite() || lotBtcRate <= 0.0) return null
        val costBtcPerUnit = l.pricePerUnit / lotBtcRate
        val currentBtcEquiv = currentValuePerUnit / currentBtcPerUnit
        pnl += l.quantity * (currentBtcEquiv - costBtcPerUnit)
    }
    return pnl
}

/**
 * Phase 8.3.3 — total P&L in BTC, more permissive than the per-side
 * helpers above: if at least one of [realized] / [unrealized] is
 * non-null, returns their sum (treating the null side as zero). Both
 * null → null (nothing to show).
 *
 * Rationale: a legacy investment without lots will still surface its
 * realized side (if any sells have been Phase 8.2-backfilled) instead
 * of going dark just because the unrealized side can't be computed.
 */
internal fun totalPnlBtc(realized: Double?, unrealized: Double?): Double? {
    if (realized == null && unrealized == null) return null
    return (realized ?: 0.0) + (unrealized ?: 0.0)
}
