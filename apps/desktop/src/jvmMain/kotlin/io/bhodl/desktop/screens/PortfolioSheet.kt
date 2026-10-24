package io.bhodl.desktop.screens

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi

/**
 * UX review 2026-10 (item 9) — the Portfolio as a Kubera-style balance
 * sheet: sheet → section → row.
 *
 * Columns: value in the user's bitcoin unit, change over the GLOBAL
 * selected period (Copilot: the per-row change follows the period
 * selector), % of net worth, last updated. Section subtotals are the
 * components of the shared [TrackerNetWorth] (never recomputed here), so
 * the sheet's net worth is the HOME hero's net worth.
 *
 * Change over the period, per row:
 *  - wallets: exact — current balance minus the signed on-chain flows
 *    inside the window (a txid seen by two rows of the same wallet group
 *    counts once);
 *  - investments / manual assets: the value at the period start uses the
 *    SAME approximation as the net-worth history (`net_worth_history.rs`):
 *    today's fiat value of what was already held then, converted at the
 *    BTC rate at the period start. That keeps the rows consistent with
 *    the hero chart and makes the BTC move visible (volatility is the
 *    signal); there is no per-instrument price history yet;
 *  - liabilities: static in the history (current amount on every day),
 *    so their change is 0.
 * A row whose start value needs a rate that is not available shows no
 * change (never a guessed one); its section then has no change total.
 */
internal enum class SheetSection { WALLETS, INVESTMENTS, MANUAL_ASSETS, LIABILITIES }

internal data class SheetRow(
    /** Stable key (tests / test tags): `<section>:<id>`. */
    val key: String,
    val section: SheetSection,
    val name: String,
    /** Wire kind for the type badge (asset type / manual kind / liability kind / wallet kind). */
    val kind: String,
    /** Signed value in sat (liabilities negative); null = has a fiat
     *  value but no BTC rate ("no rate"). */
    val valueSat: Long?,
    /** Signed change over the selected period; null = not computable. */
    val changeSat: Long?,
    /** Share of the net worth in percent (signed); null when NW ≤ 0. */
    val pctOfNetWorth: Double?,
    /** Unix seconds of the last value update; null when unknown. */
    val updatedUnix: Long?,
    /** The registry id behind the row (null for wallet groups). */
    val recordId: Long? = null,
)

internal data class SheetSectionModel(
    val section: SheetSection,
    val rows: List<SheetRow>,
    /** From [TrackerNetWorth] (signed: liabilities negative). */
    val subtotalSat: Long,
    /** Σ row changes; null when any row's change is unknown. */
    val changeSat: Long?,
    val pctOfNetWorth: Double?,
)

internal data class PortfolioSheet(
    val sections: List<SheetSectionModel>,
    val netWorthSat: Long,
    val netWorthChangeSat: Long?,
)

/**
 * Build the sheet. [periodStartUnix] is the start of the selected window
 * (null for "All": no change column values). [rateNow] / [rateAtStart]
 * give `1 BTC = N <ccy>` now and at the period start.
 */
internal fun buildPortfolioSheet(
    netWorth: TrackerNetWorth,
    walletGroups: List<GroupedWallet>,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    manualAssets: List<TrackingManualAssetFfi>,
    liabilities: List<TrackingLiabilityFfi>,
    events: List<PaymentEvent>,
    periodStartUnix: Long?,
    rateNow: (String) -> Double?,
    rateAtStart: (String) -> Double?,
    walletsUpdatedUnix: Long?,
): PortfolioSheet {
    val nw = netWorth.netWorthSat
    fun pct(v: Long?): Double? = if (v != null && nw > 0L) v.toDouble() * 100.0 / nw.toDouble() else null

    val walletRows = walletGroups.map { g ->
        val value = (g.balanceSat ?: 0UL).toLong()
        SheetRow(
            key = "wallet:${g.memberIds.sorted().joinToString(",")}",
            section = SheetSection.WALLETS,
            name = g.displayLabel,
            kind = g.kindLabel,
            valueSat = value,
            changeSat = periodStartUnix?.let { walletFlowSince(g.memberIds, events, it) },
            pctOfNetWorth = pct(value),
            updatedUnix = walletsUpdatedUnix,
        )
    }

    val investmentRows = investments.map { inv ->
        val lots = lotsByInvestmentId[inv.id].orEmpty()
        val nowSat = investmentValueSat(inv, lots, rateNow(inv.costBasisCurrency.trim().uppercase()))
        val change = if (periodStartUnix == null || nowSat == null) {
            null
        } else {
            investmentValueSatAt(inv, lots, periodStartUnix) { rateAtStart(inv.costBasisCurrency.trim().uppercase()) }
                ?.let { start -> nowSat - start }
        }
        SheetRow(
            key = "investment:${inv.id}",
            section = SheetSection.INVESTMENTS,
            name = inv.name,
            kind = inv.assetType,
            valueSat = nowSat,
            changeSat = change,
            pctOfNetWorth = pct(nowSat),
            updatedUnix = inv.lastPricedAtUnix
                ?: (lots.map { it.createdAtUnix } + inv.createdAtUnix).maxOrNull(),
            recordId = inv.id,
        )
    }

    val manualRows = manualAssets.map { a ->
        val ccy = a.currency.trim().uppercase()
        val nowSat = fiatToSat(a.valueFiat, rateNow(ccy))
        val change = if (periodStartUnix == null || nowSat == null) {
            null
        } else {
            val heldAtStart = (a.purchaseUnix ?: a.createdAtUnix) <= periodStartUnix
            if (!heldAtStart) nowSat else fiatToSat(a.valueFiat, rateAtStart(ccy))?.let { nowSat - it }
        }
        SheetRow(
            key = "manual:${a.id}",
            section = SheetSection.MANUAL_ASSETS,
            name = a.name,
            kind = a.kind,
            valueSat = nowSat,
            changeSat = change,
            pctOfNetWorth = pct(nowSat),
            updatedUnix = a.lastUpdatedUnix,
            recordId = a.id,
        )
    }

    val liabilityRows = liabilities.map { l ->
        val value = -l.amountSat.toLong()
        SheetRow(
            key = "liability:${l.id}",
            section = SheetSection.LIABILITIES,
            name = l.name,
            kind = l.kind,
            valueSat = value,
            changeSat = if (periodStartUnix == null) null else 0L,
            pctOfNetWorth = pct(value),
            updatedUnix = l.createdAtUnix,
            recordId = l.id,
        )
    }

    fun section(s: SheetSection, rows: List<SheetRow>, subtotal: Long): SheetSectionModel {
        val change = if (rows.any { it.changeSat == null }) null else rows.sumOf { it.changeSat!! }
        return SheetSectionModel(s, rows, subtotal, change, pct(subtotal))
    }
    val sections = listOf(
        section(SheetSection.WALLETS, walletRows, netWorth.walletSat.toLong()),
        section(SheetSection.INVESTMENTS, investmentRows, netWorth.investmentsSat.toLong()),
        section(SheetSection.MANUAL_ASSETS, manualRows, netWorth.manualAssetsSat.toLong()),
        section(SheetSection.LIABILITIES, liabilityRows, -netWorth.liabilitiesSat.toLong()),
    )
    val nwChange = if (sections.any { it.changeSat == null }) null else sections.sumOf { it.changeSat!! }
    return PortfolioSheet(sections, nw, nwChange)
}

/**
 * Signed on-chain flow of a wallet group after [sinceUnix] (= current
 * balance − balance at the start). Unconfirmed events count as inside
 * the window (they are happening now). A txid reported by more than one
 * member of the group counts once.
 */
internal fun walletFlowSince(memberIds: Set<String>, events: List<PaymentEvent>, sinceUnix: Long): Long =
    events.asSequence()
        .filter { it.walletId in memberIds }
        .filter { (it.timestampUnix?.toLong() ?: Long.MAX_VALUE) > sinceUnix }
        .distinctBy { it.txidOrPaymentId }
        .sumOf { e ->
            when (e.direction) {
                PaymentDirection.INCOMING -> e.amountSat.toLong()
                else -> -e.amountSat.toLong()
            }
        }

/** `fiat / rate` in sat, truncated like the net-worth aggregates so the
 *  rows add up to the subtotal; null without a usable rate or value. */
private fun fiatToSat(fiat: Double, rate: Double?): Long? {
    if (!fiat.isFinite() || fiat <= 0.0) return null
    val r = rate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return (fiat / r * 100_000_000.0).toLong().coerceAtLeast(0L)
}

/** Current value of a position in fiat: the same fallback as the
 *  investments aggregate (current price × quantity, else cost basis). */
private fun investmentFiatNow(inv: TrackingInvestmentFfi, lots: List<TrackingInvestmentLotFfi>): Double? {
    val perUnit = inv.fetchedCurrentValue ?: inv.manualCurrentValue
    return (perUnit?.let { it * effectiveQuantity(inv, lots) } ?: effectiveCostBasis(inv, lots))
        .takeIf { it.isFinite() && it > 0.0 }
}

/** Current value in sat — same per-row figure the investments aggregate
 *  of [TrackerNetWorth] sums ([computeLiveInvestmentsSat]). */
internal fun investmentValueSat(
    inv: TrackingInvestmentFfi,
    lots: List<TrackingInvestmentLotFfi>,
    rateNow: Double?,
): Long? = investmentFiatNow(inv, lots)?.let { fiatToSat(it, rateNow) }

/**
 * Value in sat at [atUnix] under the net-worth-history approximation:
 * today's per-unit value × the quantity already held then, at the
 * BTC rate of that moment. 0 when nothing was held yet; null when
 * something was held but [rateAt] has no rate.
 */
internal fun investmentValueSatAt(
    inv: TrackingInvestmentFfi,
    lots: List<TrackingInvestmentLotFfi>,
    atUnix: Long,
    rateAt: () -> Double?,
): Long? {
    val nowFiat = investmentFiatNow(inv, lots) ?: return 0L
    val qtyNow = effectiveQuantity(inv, lots)
    val heldQty = if (lots.isEmpty()) {
        if (inv.purchaseUnix <= atUnix) inv.quantity else 0.0
    } else {
        lots.filter { it.purchaseUnix <= atUnix && it.quantity.isFinite() }.sumOf { it.quantity }
    }
    if (heldQty <= 0.0 || qtyNow <= 0.0) return 0L
    return fiatToSat(nowFiat * (heldQty / qtyNow), rateAt())
}

// ─── Start-of-period rates ───────────────────────────────────────────

/** UTC day (midnight, unix seconds) the start-of-period rates are fetched for. */
internal fun sheetStartDay(periodStartUnix: Long): Long =
    periodStartUnix - Math.floorMod(periodStartUnix, 86_400L)

/**
 * BTC/<ccy> rates fetched for one start day. The sheet asks with the
 * day it currently needs: after a period change the previous period's
 * rates are still in memory until the new fetch lands, and must not be
 * used for the new window — [rateFor] answers null (the change cell
 * shows "—") until rates for the right day arrive.
 */
internal data class SheetStartRates(val dayUnix: Long, val rates: Map<String, Double>) {
    fun rateFor(currency: String, wantedDayUnix: Long): Double? =
        if (dayUnix == wantedDayUnix) rates[currency] else null
}

// ─── Column priority (narrow widths) ─────────────────────────────────

/**
 * Sheet columns with the minimum width they need and the order in which
 * they give way on narrow windows: higher [hideRank] hides first; 0
 * never hides. Name and value are the floor; change and % of net worth
 * are the essentials kept at 1024 px; type and last-updated go first.
 */
internal enum class SheetColumn(
    override val minWidthDp: Int,
    override val hideRank: Int,
) : io.bhodl.desktop.ui.PrioritizedColumn {
    NAME(180, 0),
    TYPE(88, 3),
    VALUE(140, 0),
    CHANGE(130, 1),
    PCT_NW(76, 2),
    UPDATED(92, 4),
}

/** Horizontal gap between sheet cells, in dp. */
internal const val SHEET_COLUMN_GAP_DP: Int = 8

/**
 * Columns that fit in [availableWidthDp], in display order. Drops the
 * column with the highest [SheetColumn.hideRank] until the minimum
 * widths (plus gaps) fit; never drops rank-0 columns.
 */
internal fun visibleSheetColumns(availableWidthDp: Int): List<SheetColumn> =
    // UX review 2026-10 (item 17) — the shared column-priority rule.
    io.bhodl.desktop.ui.visibleColumns(SheetColumn.entries, availableWidthDp, SHEET_COLUMN_GAP_DP)
