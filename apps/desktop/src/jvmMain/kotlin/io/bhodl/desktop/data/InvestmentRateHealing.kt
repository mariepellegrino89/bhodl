package io.bhodl.desktop.data

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingInvestmentSellFfi
import io.bhodl.ffi.TrackingLotDisposalFfi

/**
 * UX review 2026-10 (B9) — fill the historical BTC rates that the
 * registry rows are still missing, so the Bitcoin Lens P&L can render.
 *
 * The registry resolves `btc_per_unit_at_purchase` (lots) and
 * `btc_per_unit_at_sale` (sells) once — when the row is saved and in
 * the one-shot Phase 8.2 backfill. If that single attempt failed
 * (offline, Tor down, provider error) the column stayed NULL forever
 * and every investment showed "no rate" as cost and "—" as P&L BTC,
 * even though the rate for the purchase date was available later.
 *
 * This pure step runs on every tracker refresh: for each row still
 * missing a rate it asks [rateAt] for the BTC/<investment currency>
 * rate at the row's UTC-midnight date (the same day key the registry
 * resolver uses) and returns copies with the rate filled in. Disposals
 * whose `gain_loss_btc` is NULL get it re-derived with the registry's
 * formula once both their lot rate and their sell rate are known.
 *
 * A rate that cannot be found leaves the row untouched — the UI keeps
 * saying "no rate" only when the rate is truly unavailable. We never
 * substitute the current rate for a historical one.
 *
 * [healedLotIds] / [healedSellIds] tell the caller which rows to
 * persist through the registry resolvers; [resolvedRates] are the
 * (UTC day, currency) → rate pairs found, so the caller can keep them
 * and re-apply them without another lookup.
 *
 * [maxLookups] caps how many times [rateAt] is called in one pass
 * (memo hits are free); rows beyond the cap are left for a later pass.
 */
internal data class HealedInvestmentRates(
    val lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    val sellsByInvestmentId: Map<Long, List<TrackingInvestmentSellFfi>>,
    val disposalsByInvestmentId: Map<Long, List<TrackingLotDisposalFfi>>,
    val healedLotIds: List<Long>,
    val healedSellIds: List<Long>,
    val resolvedRates: Map<Pair<Long, String>, Double> = emptyMap(),
)

private const val DAY_SECONDS: Long = 86_400L

/** UTC-midnight day key, matching the registry's `normalize_to_utc_midnight`. */
internal fun utcMidnight(unix: Long): Long = Math.floorDiv(unix, DAY_SECONDS) * DAY_SECONDS

/** Same formula as the registry's `compute_gain_loss_btc`. */
internal fun gainLossBtc(
    qtyConsumed: Double,
    costBasisPerUnit: Double,
    btcPerUnitAtPurchase: Double,
    salePricePerUnit: Double,
    btcPerUnitAtSale: Double,
): Double {
    val costBtc = qtyConsumed * costBasisPerUnit / btcPerUnitAtPurchase
    val saleBtc = qtyConsumed * salePricePerUnit / btcPerUnitAtSale
    return saleBtc - costBtc
}

internal suspend fun fillMissingBtcRates(
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    sellsByInvestmentId: Map<Long, List<TrackingInvestmentSellFfi>>,
    disposalsByInvestmentId: Map<Long, List<TrackingLotDisposalFfi>>,
    maxLookups: Int = Int.MAX_VALUE,
    rateAt: suspend (dayUnix: Long, currency: String) -> Double?,
): HealedInvestmentRates {
    val currencyOf: Map<Long, String> = investments.associate { it.id to it.costBasisCurrency.uppercase() }
    // One lookup per (day, currency), hits and misses alike.
    val memo = HashMap<Pair<Long, String>, Double?>()
    var lookups = 0
    suspend fun lookup(unix: Long, ccy: String): Double? {
        val key = utcMidnight(unix) to ccy
        if (memo.containsKey(key)) return memo[key]
        if (lookups >= maxLookups) return null
        lookups++
        val r = try {
            rateAt(key.first, ccy)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unsupported currency / provider failure: treat as missing.
            null
        }?.takeIf { it.isFinite() && it > 0.0 }
        memo[key] = r
        return r
    }

    val healedLots = mutableListOf<Long>()
    val lotsOut = lotsByInvestmentId.mapValues { (invId, lots) ->
        val ccy = currencyOf[invId] ?: return@mapValues lots
        lots.map { l ->
            if (l.btcPerUnitAtPurchase != null) return@map l
            val r = lookup(l.purchaseUnix, ccy) ?: return@map l
            healedLots += l.id
            l.copy(btcPerUnitAtPurchase = r)
        }
    }
    val healedSells = mutableListOf<Long>()
    val sellsOut = sellsByInvestmentId.mapValues { (invId, sells) ->
        val ccy = currencyOf[invId] ?: return@mapValues sells
        sells.map { s ->
            if (s.btcPerUnitAtSale != null) return@map s
            val r = lookup(s.saleUnix, ccy) ?: return@map s
            healedSells += s.id
            s.copy(btcPerUnitAtSale = r)
        }
    }
    val lotRate: Map<Long, Double?> = lotsOut.values.flatten().associate { it.id to it.btcPerUnitAtPurchase }
    val sellById: Map<Long, TrackingInvestmentSellFfi> = sellsOut.values.flatten().associateBy { it.id }
    val disposalsOut = disposalsByInvestmentId.mapValues { (_, disposals) ->
        disposals.map { d ->
            if (d.gainLossBtc != null) return@map d
            val lr = lotRate[d.lotId] ?: return@map d
            val sell = sellById[d.sellId] ?: return@map d
            val sr = sell.btcPerUnitAtSale ?: return@map d
            if (lr <= 0.0 || sr <= 0.0) return@map d
            d.copy(
                gainLossBtc = gainLossBtc(
                    d.qtyConsumed, d.costBasisPerUnit, lr, sell.salePricePerUnit, sr,
                ),
            )
        }
    }
    return HealedInvestmentRates(
        lotsByInvestmentId = lotsOut,
        sellsByInvestmentId = sellsOut,
        disposalsByInvestmentId = disposalsOut,
        healedLotIds = healedLots,
        healedSellIds = healedSells,
        resolvedRates = memo.entries.mapNotNull { (k, v) -> v?.let { k to it } }.toMap(),
    )
}
