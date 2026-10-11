package io.bhodl.desktop.domain

import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingManualAssetFfi

/**
 * Phase 7.6 — derived KPIs for the dashboard "insights" strip.
 * Phase 8.5 — extended with the shadow-portfolio opportunity cost
 * (BTC delta vs "what if I'd HODLed instead of diversifying").
 *
 * All fields nullable: when the dataset doesn't support a metric
 * (zero income → savings rate undefined; zero outflow → burn /
 * FIRE / runway undefined; no quotable position → opp cost
 * undefined) the UI renders "—" rather than a misleading number.
 * Saving callers from defensive `?:` everywhere.
 */
internal data class FinanceKpis(
    /** Last-30-days savings rate as percent (-999..999). Null when income=0. */
    val savingsRatePct: Int?,
    /** Average monthly outflow in sat, baselined on last 90 days. Null when no outflow. */
    val burnRateSatPerMonth: ULong?,
    /** 25× annual expenses (4% rule) in sat. Null when burn rate is null. */
    val fireNumberSat: ULong?,
    /** Months of runway at current burn rate. Null when burn rate is null. */
    val runwayMonths: Double?,
    /**
     * Phase 8.5 — opportunity cost in BTC vs HODL. Positive means
     * BTC outperformed (the user "lost" that many BTC by diversifying);
     * negative means diversification won (rare). Null when no
     * position has the data required to compute (new portfolio,
     * pre-Phase-8.2 backfill, or only cross-currency positions).
     */
    val opportunityCostBtc: Double? = null,
)

private const val DAY_SEC: Long = 86_400L
private const val WINDOW_30D_SEC: Long = 30L * DAY_SEC
private const val WINDOW_90D_SEC: Long = 90L * DAY_SEC

/**
 * Phase 7.6 — pure derivation of the dashboard KPIs.
 *
 * Inputs:
 *  - [events]: full PaymentEvent stream; only confirmed events with a
 *    `timestampUnix` participate. Mempool/pending tx are excluded
 *    because their direction is reliable but the date isn't anchored.
 *  - [totalAssetSat]: total NW in sat (BTC + investments + manual).
 *    Used only for the runway computation.
 *  - [illiquidAssetSat]: the part of [totalAssetSat] that can't fund
 *    living expenses (real estate, vehicles, collectibles, business
 *    equity, loans to others — see [illiquidManualAssetsSat]). It is
 *    subtracted before computing the runway: you can't pay groceries
 *    with your house.
 *  - [nowUnix]: caller-provided clock so tests can pin the window
 *    boundaries deterministically.
 */
internal fun computeFinanceKpis(
    events: List<PaymentEvent>,
    totalAssetSat: ULong,
    nowUnix: Long,
    illiquidAssetSat: ULong = 0UL,
): FinanceKpis {
    val cutoff30 = nowUnix - WINDOW_30D_SEC
    val cutoff90 = nowUnix - WINDOW_90D_SEC

    var income30 = 0UL
    var expense30 = 0UL
    var expense90 = 0UL

    for (ev in events) {
        val ts = ev.timestampUnix?.toLong() ?: continue
        if (ts > nowUnix) continue
        // Transfers between the user's own wallets (SELF_TRANSFER or
        // crypto kind send_to_self) don't move the NW: only their fee,
        // when known, counts as an outflow.
        val inflow = ev.externalInflowSat()
        val outflow = ev.externalOutflowSat()
        if (ts >= cutoff30) {
            income30 += inflow
            expense30 += outflow
        }
        if (ts >= cutoff90) expense90 += outflow
    }

    val savingsRatePct: Int? = if (income30 == 0UL) null
    else {
        // Use signed long math to allow negative rates (expense > income).
        val numerator = income30.toLong() - expense30.toLong()
        val pct = (numerator.toDouble() / income30.toDouble() * 100.0).toInt()
        pct.coerceIn(-999, 999)
    }

    // burn = avg daily outflow over 90d × 30 days
    val burnRate: ULong? = if (expense90 == 0UL) null
    else {
        // (expense90 / 90) * 30  ==  expense90 / 3
        // Order matters: divide last to avoid losing precision on
        // small balances, but here the inputs are already large.
        val perMonth = expense90.toLong() / 3L
        perMonth.coerceAtLeast(0L).toULong()
    }

    val fireNumber: ULong? = burnRate?.let {
        // 300 × burn_rate; saturates at ULong ceiling well above
        // realistic sat values (300 × 21M BTC × 1e8 ≈ 6.3e17, fits u64).
        it * 300UL
    }

    val liquidAssetSat: ULong =
        if (illiquidAssetSat >= totalAssetSat) 0UL else totalAssetSat - illiquidAssetSat
    val runwayMonths: Double? = burnRate?.let {
        if (it == 0UL) null
        else liquidAssetSat.toDouble() / it.toDouble()
    }

    return FinanceKpis(
        savingsRatePct = savingsRatePct,
        burnRateSatPerMonth = burnRate,
        fireNumberSat = fireNumber,
        runwayMonths = runwayMonths,
    )
}

/**
 * Phase 8.5 — extended KPI compute that also folds in the
 * shadow-portfolio "vs HODL" opportunity cost. The Phase-7.6 helper
 * above stays for callers (and tests) that don't have the
 * investments / manual-assets snapshot — those keep getting a
 * [FinanceKpis] with `opportunityCostBtc = null` (the default).
 *
 * The new dependencies are explicit on this overload rather than
 * defaulted on the original so the type system catches every call
 * site that needs to opt-in.
 */
internal fun computeFinanceKpis(
    events: List<PaymentEvent>,
    totalAssetSat: ULong,
    nowUnix: Long,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestment: Map<Long, List<TrackingInvestmentLotFfi>>,
    manualAssets: List<TrackingManualAssetFfi>,
    currentBtcPerUnit: (currency: String) -> Double?,
    illiquidAssetSat: ULong = 0UL,
): FinanceKpis {
    val base = computeFinanceKpis(events, totalAssetSat, nowUnix, illiquidAssetSat)
    val opp = computeOpportunityCostBtc(
        investments = investments,
        lotsByInvestment = lotsByInvestment,
        manualAssets = manualAssets,
        currentBtcPerUnit = currentBtcPerUnit,
    )
    return base.copy(opportunityCostBtc = opp.totalBtc)
}

/**
 * Manual-asset kinds that can fund expenses at short notice. Everything
 * else (real_estate, vehicle, collectible, business_equity,
 * loans_to_others, other) is treated as illiquid for the runway.
 */
internal val LIQUID_MANUAL_ASSET_KINDS: Set<String> = setOf("cash", "brokerage")

/**
 * Sat value of the ILLIQUID manual assets — the amount [computeFinanceKpis]
 * removes from the NW before computing the runway. [satFor] converts one
 * row to sat (null = no rate → the row is not in the NW either).
 */
internal fun illiquidManualAssetsSat(
    assets: List<TrackingManualAssetFfi>,
    satFor: (TrackingManualAssetFfi) -> ULong?,
): ULong {
    var total = 0UL
    for (a in assets) {
        if (a.kind in LIQUID_MANUAL_ASSET_KINDS) continue
        total += satFor(a) ?: 0UL
    }
    return total
}
