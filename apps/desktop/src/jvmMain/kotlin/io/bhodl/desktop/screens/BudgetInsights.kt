package io.bhodl.desktop.screens

import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.desktop.prices.HistoricalPricePoint
import io.bhodl.ffi.PaymentEvent
import kotlin.math.abs
import kotlin.math.roundToLong
import io.bhodl.desktop.ui.UNCATEGORISED_KEY

/**
 * UX review 2026-10 (item 12) — budget status chips and the "why did my
 * sats budget move" note.
 *
 * Budgets are stored in sats while most spending is priced in fiat, so
 * a category can blow through its sats budget without the user buying
 * anything more: the bitcoin price fell. That is BHODL's thesis (BTC as
 * unit of account, volatility as signal, never smoothed), but a novice
 * reads it as a bug unless the screen says which part of the move is
 * price and which is spending. [explainBudgetMove] splits it.
 */

/** Text status of a budget row. Always rendered as words, never colour alone. */
internal enum class BudgetStatus { UNDER, AT_RISK, OVER, REACHED, BEHIND }

/** Expense: at risk from 80% of the budget, over above 100%. */
internal const val EXPENSE_AT_RISK_RATIO: Double = 0.8

/** Income: at risk from 70% of the target, reached at 100%. */
internal const val INCOME_AT_RISK_RATIO: Double = 0.7

/**
 * Status of [row] against its budget prorated to the window
 * ([BudgetRow.budgetForPeriodSat], the PR #8 proration). Null when the
 * category has no budget.
 *
 *  - Expense: < 80% → [BudgetStatus.UNDER], 80–100% → [BudgetStatus.AT_RISK],
 *    > 100% → [BudgetStatus.OVER]. Exactly on budget is not over.
 *  - Income: ≥ 100% → [BudgetStatus.REACHED], 70–100% →
 *    [BudgetStatus.AT_RISK], < 70% → [BudgetStatus.BEHIND].
 */
internal fun budgetStatus(row: BudgetRow): BudgetStatus? {
    val budget = row.budgetForPeriodSat ?: return null
    val actual = row.actualSat
    if (budget == 0UL) {
        return when (row.direction) {
            TrackingDirection.EXPENSE -> if (actual == 0UL) BudgetStatus.UNDER else BudgetStatus.OVER
            TrackingDirection.INCOME -> BudgetStatus.REACHED
        }
    }
    val ratio = actual.toDouble() / budget.toDouble()
    return when (row.direction) {
        TrackingDirection.EXPENSE -> when {
            ratio > 1.0 -> BudgetStatus.OVER
            ratio >= EXPENSE_AT_RISK_RATIO -> BudgetStatus.AT_RISK
            else -> BudgetStatus.UNDER
        }
        TrackingDirection.INCOME -> when {
            ratio >= 1.0 -> BudgetStatus.REACHED
            ratio >= INCOME_AT_RISK_RATIO -> BudgetStatus.AT_RISK
            else -> BudgetStatus.BEHIND
        }
    }
}

/**
 * How a category's sats flow changed between the previous window and
 * the current one, split into a PRICE effect and a SPENDING effect.
 *
 * With S = sats, F = fiat value at each event's own rate, and
 * R̄ = F·1e8 / S the sat-weighted average BTC price of the window:
 *  - spending effect = (F₁ − F₀) at the previous price = F₁·1e8/R̄₀ − S₀
 *    (what the change would have been had BTC not moved);
 *  - price effect = the rest, (S₁ − S₀) − spending effect = S₁ − F₁·1e8/R̄₀
 *    (the same fiat bought with a different number of sats).
 * The two always add up to [deltaSat] exactly.
 */
internal data class BudgetMove(
    val previousSat: Long,
    val currentSat: Long,
    val priceEffectSat: Long,
    val spendingEffectSat: Long,
    /** Change of the average BTC price between the windows, in %. */
    val btcPriceChangePct: Double,
) {
    val deltaSat: Long get() = currentSat - previousSat

    /** Worth a note: the price part is at least 1% of the current flow. */
    val isPriceMaterial: Boolean
        get() = currentSat > 0L && abs(priceEffectSat) * 100L >= currentSat
}

/**
 * [BudgetMove] for [category] in [direction] over the window of
 * [windowDays] days ending at [nowUnix], against the window before it.
 * Each event is valued at the BTC price of its own day ([rateAt], no
 * averaging or smoothing). Null when either window has no flow, or any
 * event lacks a rate (a partial split would mislead).
 */
internal fun explainBudgetMove(
    category: String,
    direction: TrackingDirection,
    events: List<PaymentEvent>,
    nowUnix: Long,
    windowDays: Int,
    rateAt: (Long) -> Double?,
): BudgetMove? {
    if (windowDays <= 0) return null
    val span = windowDays.toLong() * 86_400L
    val curStart = nowUnix - span
    val prevStart = curStart - span
    var s0 = 0L
    var s1 = 0L
    var f0 = 0.0
    var f1 = 0.0
    for (e in events) {
        val ts = e.timestampUnix?.toLong() ?: continue
        if (ts <= prevStart || ts > nowUnix) continue
        val key = e.category?.takeIf { it.isNotBlank() } ?: UNCATEGORISED_KEY
        if (key != category) continue
        val sat = when (direction) {
            TrackingDirection.INCOME -> e.externalInflowSat()
            TrackingDirection.EXPENSE -> e.externalOutflowSat()
        }.toLong()
        if (sat == 0L) continue
        val rate = rateAt(ts)?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val fiat = sat.toDouble() * rate / 1e8
        if (ts > curStart) {
            s1 += sat
            f1 += fiat
        } else {
            s0 += sat
            f0 += fiat
        }
    }
    if (s0 <= 0L || s1 <= 0L) return null
    val r0 = f0 * 1e8 / s0
    val r1 = f1 * 1e8 / s1
    val spending = (f1 * 1e8 / r0).roundToLong() - s0
    return BudgetMove(
        previousSat = s0,
        currentSat = s1,
        priceEffectSat = (s1 - s0) - spending,
        spendingEffectSat = spending,
        btcPriceChangePct = (r1 / r0 - 1.0) * 100.0,
    )
}

/**
 * Rate lookup over a price history: the last point at or before the
 * timestamp (the price the market had then), else the first point when
 * the timestamp precedes the series by at most [maxGapSec]. Null beyond
 * that — no extrapolation.
 */
internal fun rateLookup(
    points: List<HistoricalPricePoint>,
    maxGapSec: Long = 2L * 86_400L,
): (Long) -> Double? {
    val sorted = points.filter { it.price.isFinite() && it.price > 0.0 }.sortedBy { it.timestampMs }
    val ts = LongArray(sorted.size) { sorted[it].timestampMs / 1000L }
    return lookup@{ unix ->
        if (sorted.isEmpty()) return@lookup null
        var idx = java.util.Arrays.binarySearch(ts, unix)
        if (idx < 0) idx = -idx - 2 // insertion point − 1 = last ≤ unix
        when {
            idx >= 0 -> sorted[idx].price.takeIf { unix - ts[idx] <= maxGapSec }
            ts[0] - unix <= maxGapSec -> sorted[0].price
            else -> null
        }
    }
}
