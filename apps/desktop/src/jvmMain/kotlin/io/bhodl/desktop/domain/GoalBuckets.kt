package io.bhodl.desktop.domain

import io.bhodl.desktop.screens.effectiveQuantity
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.trp_goal_bucket_asset
import io.bhodl.shared.resources.trp_goal_bucket_asset_missing
import io.bhodl.shared.resources.trp_goal_bucket_bitcoin_cash
import io.bhodl.shared.resources.trp_goal_bucket_investments
import io.bhodl.shared.resources.trp_goal_bucket_wallet
import io.bhodl.shared.resources.trp_goal_bucket_wallet_missing

/**
 * Phase 7.2.4 — semantic buckets for savings goals.
 *
 * Each savings goal binds to a "bucket": a slice of the user's net
 * worth from which the goal's `current_sat` is derived. Before this
 * phase `current_sat` was hand-entered; now bucket-bound goals get an
 * auto-tracked value, and the dialog's "Current progress (sat)" input
 * goes read-only when the bucket isn't [Manual].
 *
 * The enum here mirrors the `bucket_kind` string column in the
 * registry — see [io.bhodl.ffi.TrackingSavingsGoalFfi.bucketKind] for
 * the wire form. New buckets go into both places together; a value
 * persisted with a kind we don't recognise gets coerced to [Manual]
 * so an older client never silently miscomputes.
 */
internal enum class GoalBucket(public val wireValue: String) {
    /** Legacy / opt-out: `current_sat` is whatever the user typed. */
    MANUAL("manual"),

    /** Sum of `tracking_investments` converted to sat via PriceCache. */
    INVESTMENTS("investments"),

    /** Aggregate watch-only balance — every wallet in the registry. */
    BITCOIN_CASH("bitcoin_cash"),

    /** Balance of the single wallet identified by `bucket_ref`. */
    WALLET("wallet"),

    /**
     * Phase 7.4 — single `tracking_manual_assets` row identified by
     * `bucket_ref = asset.id.toString()`. Enables binding a goal to a
     * specific cash / brokerage / real-estate / etc. account, so a
     * an "Emergency fund" goal auto-tracks a cash-account balance without
     * needing UTXO-tagging on the BTC side.
     */
    MANUAL_ASSET("manual_asset");

    public companion object {
        /**
         * Parse a wire string into [GoalBucket]. Unknown values fall
         * back to [MANUAL] so a downgrade (UI older than the schema)
         * surfaces a hand-entered current_sat instead of crashing.
         */
        public fun fromWire(s: String?): GoalBucket = when (s) {
            "investments" -> INVESTMENTS
            "bitcoin_cash" -> BITCOIN_CASH
            "wallet" -> WALLET
            "manual_asset" -> MANUAL_ASSET
            else -> MANUAL
        }
    }
}

/**
 * Phase 7.2.4 — derive a savings goal's `current_sat` from its bucket
 * binding. Pure — exposed `internal` for unit tests; the production
 * call site is [SavingsGoalsCard].
 *
 * Behaviour per bucket:
 *  - [GoalBucket.MANUAL]: returns `goal.currentSat` (the value the
 *    user typed) unchanged.
 *  - [GoalBucket.INVESTMENTS]: sums every investment's current fiat
 *    value (preferring `fetchedCurrentValue` over `manualCurrentValue`,
 *    matching the policy in [InvestmentsCard]) and converts the total
 *    to sat via [fiatToSat]. Returns 0 when the price isn't cached or
 *    no investment has a current value.
 *  - [GoalBucket.BITCOIN_CASH]: returns [bitcoinAssetSat] verbatim
 *    (the aggregate watch-only balance). **NOT** the total net worth:
 *    a goal "1 BTC cash" must measure progress against BTC holdings
 *    only — Phase 7.3 renamed the parameter from `totalAssetSat` so
 *    that the semantics is explicit and so that adding investments to
 *    the NW hero doesn't inflate this bucket.
 *  - [GoalBucket.WALLET]: returns `watchBalances[bucketRef]` or 0
 *    when the ref doesn't match any current wallet (e.g. the wallet
 *    was removed after the goal was bound to it).
 *
 * Note: this does NOT touch the persisted `current_sat`. It computes
 * a view over the snapshot the caller already has. The dialog freezes
 * the persisted value only when the user explicitly switches back to
 * MANUAL, so a wallet rename or temporary Esplora outage doesn't
 * silently zero out an INVESTMENTS-bound goal at save time.
 */
internal fun computeGoalCurrentSat(
    goal: TrackingSavingsGoalFfi,
    bitcoinAssetSat: ULong,
    watchBalances: Map<String, ULong>,
    investments: List<TrackingInvestmentFfi>,
    /**
     * Phase 7.4 — manual asset rows from the registry. Used only when
     * the goal's bucket is [GoalBucket.MANUAL_ASSET]; passing an empty
     * list when no manual asset is registered is fine — the lookup
     * just resolves to 0 sat.
     */
    manualAssets: List<TrackingManualAssetFfi> = emptyList(),
    fiatToSat: (amount: Double) -> ULong?,
    /** Lots per investment id — positions with lots are valued on
     *  [effectiveQuantity] (the summary row's quantity is zeroed once
     *  lots exist and never reduced by sells). */
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>> = emptyMap(),
    /** `1 BTC = N <currency>` lookup (normally `PriceCache::rateFor`).
     *  When provided, each row is converted at ITS OWN currency's rate;
     *  null keeps the legacy dashboard-currency [fiatToSat] path. */
    rateFor: ((currency: String) -> Double?)? = null,
): ULong = when (GoalBucket.fromWire(goal.bucketKind)) {
    GoalBucket.MANUAL -> goal.currentSat
    GoalBucket.INVESTMENTS -> investmentsTotalSat(investments, fiatToSat, lotsByInvestmentId, rateFor)
    GoalBucket.BITCOIN_CASH -> bitcoinAssetSat
    GoalBucket.WALLET -> goal.bucketRef
        ?.let { watchBalances[it] }
        ?: 0UL
    GoalBucket.MANUAL_ASSET -> {
        val ref = goal.bucketRef
        val asset = if (ref != null) {
            manualAssets.firstOrNull { it.id.toString() == ref }
        } else null
        if (asset == null) 0UL
        else manualAssetsTotalSat(listOf(asset), fiatToSat, rateFor)
    }
}

/**
 * Convert [fiat] denominated in [currency] to sat at that currency's own
 * BTC rate. Null when the rate is missing / non-positive / non-finite —
 * the caller then leaves the row out ("no rate" policy) instead of
 * pricing it at the wrong currency.
 */
internal fun fiatToSatInCurrency(
    fiat: Double,
    currency: String,
    rateFor: (currency: String) -> Double?,
): ULong? {
    if (!fiat.isFinite() || fiat <= 0.0) return null
    val rate = rateFor(currency.trim().uppercase()) ?: return null
    if (!rate.isFinite() || rate <= 0.0) return null
    val sat = fiat / rate * 100_000_000.0
    if (!sat.isFinite()) return null
    return sat.toLong().coerceAtLeast(0L).toULong()
}

/**
 * Phase 7.2.4 + 7.3 — Sum every investment's "effective" current fiat
 * value (auto-fetched preferred, manual fallback — same policy as
 * [InvestmentsCardData]) and convert to sat. Investments without any
 * current value contribute 0 rather than blocking the aggregate; if
 * [fiatToSat] returns null (no BTC/fiat price cached yet) the whole
 * function returns 0 — the UI is expected to render "—" in that case
 * via a separate `investmentsPricingAvailable` boolean.
 *
 * Promoted from `private` to `internal` in Phase 7.3 because the
 * TrackerCache also needs to aggregate this number outside of the
 * goal-bucket compute path (the NetWorth hero and the breakdown table
 * use the same investment total).
 *
 * Quantity: [effectiveQuantity] over [lotsByInvestmentId] — once a
 * position has lots, `seed_investment_lot_from_aggregate` zeroes the
 * summary row's `quantity` and sells only reduce lot quantities, so
 * reading `inv.quantity` would value every lot-managed position at 0.
 *
 * Cross-currency: when [rateFor] is provided each investment is
 * converted at the BTC rate of its own `costBasisCurrency` (rows
 * without a rate are left out). Without [rateFor] the legacy path sums
 * the fiat values and converts once via [fiatToSat] (dashboard fiat).
 */
internal fun investmentsTotalSat(
    investments: List<TrackingInvestmentFfi>,
    fiatToSat: (amount: Double) -> ULong?,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>> = emptyMap(),
    rateFor: ((currency: String) -> Double?)? = null,
): ULong {
    var totalFiat = 0.0
    var totalSat = 0UL
    for (inv in investments) {
        val perUnit = inv.fetchedCurrentValue ?: inv.manualCurrentValue ?: continue
        val qty = effectiveQuantity(inv, lotsByInvestmentId[inv.id].orEmpty())
        val value = perUnit * qty
        if (!value.isFinite() || value <= 0.0) continue
        if (rateFor != null) {
            totalSat += fiatToSatInCurrency(value, inv.costBasisCurrency, rateFor) ?: 0UL
        } else {
            totalFiat += value
        }
    }
    if (rateFor != null) return totalSat
    if (totalFiat <= 0.0) return 0UL
    return fiatToSat(totalFiat) ?: 0UL
}

/**
 * Phase 7.2.4 — UI label for a goal's bucket binding, used by the
 * "AUTO · <label>" badge above the progress bar. Pure: looks up the
 * wallet name from [wallets] when the bucket is WALLET so the badge
 * stays meaningful after the user renames the wallet, without
 * persisting a stale copy of the name on the goal row.
 */
internal fun goalBucketBadgeLabel(
    goal: TrackingSavingsGoalFfi,
    wallets: List<WalletRecordFfi>,
    /**
     * Phase 7.4 — passed alongside [wallets] so the `MANUAL_ASSET`
     * badge can show the current asset name (which the user can
     * rename without affecting the goal binding — `bucket_ref` stays
     * stable on the asset id).
     */
    manualAssets: List<TrackingManualAssetFfi> = emptyList(),
    /** UX review 2026-10 (rec 3) — the UI passes the localized labels
     *  ([rememberGoalBucketBadgeLabels]); the default keeps the pure
     *  function testable without a Compose runtime. */
    labels: GoalBucketBadgeLabels = GoalBucketBadgeLabels(),
): String? = when (GoalBucket.fromWire(goal.bucketKind)) {
    GoalBucket.MANUAL -> null
    GoalBucket.INVESTMENTS -> labels.investments
    GoalBucket.BITCOIN_CASH -> labels.bitcoinCash
    GoalBucket.WALLET -> {
        val ref = goal.bucketRef
        val name = if (ref != null) wallets.firstOrNull { it.id == ref }?.label else null
        name?.let { labels.wallet(it) } ?: labels.walletMissing
    }
    GoalBucket.MANUAL_ASSET -> {
        val ref = goal.bucketRef
        val name = if (ref != null) manualAssets.firstOrNull { it.id.toString() == ref }?.name else null
        name?.let { labels.asset(it) } ?: labels.assetMissing
    }
}

/** Labels for [goalBucketBadgeLabel]; English defaults for tests. */
internal class GoalBucketBadgeLabels(
    val investments: String = "Investments",
    val bitcoinCash: String = "Bitcoin cash",
    val wallet: (String) -> String = { "Wallet · $it" },
    val walletMissing: String = "Wallet (missing)",
    val asset: (String) -> String = { "Asset · $it" },
    val assetMissing: String = "Asset (missing)",
)

/** [GoalBucketBadgeLabels] in the app language. */
@androidx.compose.runtime.Composable
internal fun rememberGoalBucketBadgeLabels(): GoalBucketBadgeLabels {
    val investments = stringResource(Res.string.trp_goal_bucket_investments)
    val bitcoinCash = stringResource(Res.string.trp_goal_bucket_bitcoin_cash)
    val wallet = stringResource(Res.string.trp_goal_bucket_wallet, "\u0000")
    val walletMissing = stringResource(Res.string.trp_goal_bucket_wallet_missing)
    val asset = stringResource(Res.string.trp_goal_bucket_asset, "\u0000")
    val assetMissing = stringResource(Res.string.trp_goal_bucket_asset_missing)
    // The named variants are templates: the NUL placeholder is swapped
    // for the wallet / asset name, which is only known per goal.
    return GoalBucketBadgeLabels(
        investments = investments,
        bitcoinCash = bitcoinCash,
        wallet = { wallet.replace("\u0000", it) },
        walletMissing = walletMissing,
        asset = { asset.replace("\u0000", it) },
        assetMissing = assetMissing,
    )
}

/**
 * Phase 7.4 — aggregate fiat value of every manual asset row,
 * converted to sat via [fiatToSat]. Mirror of [investmentsTotalSat]
 * for the manual side. Skips rows with non-finite or non-positive
 * `valueFiat` so a stray typo doesn't poison the sum.
 *
 * Returns 0 when:
 *  - the list is empty
 *  - no row has a usable value
 *  - [fiatToSat] returns null (no BTC/fiat price cached yet) — the
 *    UI is expected to fall back to a "—" placeholder via the
 *    `investmentsPricingAvailable` flag, which doubles as a manual-
 *    asset pricing flag (same PriceCache path).
 */
internal fun manualAssetsTotalSat(
    assets: List<TrackingManualAssetFfi>,
    fiatToSat: (amount: Double) -> ULong?,
    /** Per-currency BTC rate — see [investmentsTotalSat]. */
    rateFor: ((currency: String) -> Double?)? = null,
): ULong {
    if (rateFor != null) {
        var totalSat = 0UL
        for (a in assets) {
            totalSat += fiatToSatInCurrency(a.valueFiat, a.currency, rateFor) ?: 0UL
        }
        return totalSat
    }
    var totalFiat = 0.0
    for (a in assets) {
        val v = a.valueFiat
        if (v.isFinite() && v > 0.0) {
            totalFiat += v
        }
    }
    if (totalFiat <= 0.0) return 0UL
    return fiatToSat(totalFiat) ?: 0UL
}

/**
 * Phase 7.4 — same aggregation as [manualAssetsTotalSat], but bucketed
 * by `kind` so the NetWorthBreakdownTable can render a row per kind.
 * Keys are the wire `kind` strings (`"cash"`, `"real_estate"`, ...);
 * values are the per-kind aggregate in sat. Missing kinds simply
 * aren't in the map (the table treats them as 0 / hidden row).
 *
 * Why a separate function instead of a Map filter: we want the sum-
 * then-convert ordering — the rounding error of `Σ fiat → sat` is
 * smaller than `Σ (fiat → sat per row)` on small balances, and the
 * table totals will agree with [manualAssetsTotalSat] up to ≤ 1 sat.
 */
internal fun manualAssetsByKindSat(
    assets: List<TrackingManualAssetFfi>,
    fiatToSat: (amount: Double) -> ULong?,
    /** Per-currency BTC rate — see [investmentsTotalSat]. */
    rateFor: ((currency: String) -> Double?)? = null,
): Map<String, ULong> {
    if (rateFor != null) {
        return assets
            .groupBy { it.kind }
            .mapValues { (_, rows) -> manualAssetsTotalSat(rows, fiatToSat, rateFor) }
            .filterValues { it > 0UL }
    }
    val perKindFiat = mutableMapOf<String, Double>()
    for (a in assets) {
        val v = a.valueFiat
        if (v.isFinite() && v > 0.0) {
            perKindFiat[a.kind] = (perKindFiat[a.kind] ?: 0.0) + v
        }
    }
    return perKindFiat
        .mapValues { (_, fiat) -> fiatToSat(fiat) ?: 0UL }
        .filterValues { it > 0UL }
}
