package io.bhodl.desktop.screens

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.WalletRecordFfi

/**
 * UX review 2026-10 (B1/B8) — the ONE net-worth computation shared by
 * the tracker HOME hero, the HOME breakdown donut and the PORTFOLIO
 * asset rollup + snapshot card.
 *
 * Before this, the hero rendered `TrackerCache.totalAssetSat` (assets
 * only, liabilities ignored) while Portfolio subtracted liabilities on
 * its own, so the two "net worth" numbers disagreed.
 *
 * Components:
 *  - [walletSat] — on-chain BTC of the tracked wallets (see
 *    [trackedWalletGroups]); exclusion-aware.
 *  - [investmentsSat] / [manualAssetsSat] — the live recompute
 *    against the current BTC rate when one is available; the cached
 *    aggregate (computed at refresh time) only when there is no live
 *    rate. (Code review 2026-10: the earlier `maxOf(cached, live)`
 *    biased the figure upward after a BTC rally.)
 *  - [liabilitiesSat] — sum of the liability rows.
 */
internal data class TrackerNetWorth(
    val walletSat: ULong,
    val investmentsSat: ULong,
    val manualAssetsSat: ULong,
    val liabilitiesSat: ULong,
) {
    /** Gross assets (BTC + investments + manual assets). */
    val assetsSat: ULong get() = walletSat + investmentsSat + manualAssetsSat

    /** Net worth = assets − liabilities. Can be negative. */
    val netWorthSat: Long get() = assetsSat.toLong() - liabilitiesSat.toLong()

    /**
     * Base for "% of net worth" goal targets and allocation drift: the
     * TRUE net worth (liabilities subtracted), floored at zero because a
     * percentage of a negative net worth has no meaningful target.
     */
    val goalBaseSat: ULong get() = netWorthSat.coerceAtLeast(0L).toULong()
}

/**
 * Pure builder for [TrackerNetWorth]. [liveInvestmentsSat] /
 * [liveManualAssetsSat] are the live recomputes
 * ([computeLiveInvestmentsSat] / [computeLiveManualAssetsSat]), null
 * when no live BTC rate is available; the cached values come from
 * `TrackerCache` and are used only in that case.
 */
internal fun computeTrackerNetWorth(
    walletSat: ULong,
    cachedInvestmentsSat: ULong,
    liveInvestmentsSat: ULong?,
    cachedManualAssetsSat: ULong,
    liveManualAssetsSat: ULong?,
    liabilities: List<TrackingLiabilityFfi>,
): TrackerNetWorth = TrackerNetWorth(
    walletSat = walletSat,
    investmentsSat = liveInvestmentsSat ?: cachedInvestmentsSat,
    manualAssetsSat = liveManualAssetsSat ?: cachedManualAssetsSat,
    liabilitiesSat = liabilities.fold(0UL) { acc, l -> acc + l.amountSat },
)

/**
 * Convenience overload that runs the live recomputes itself from the
 * raw rows + current BTC rate. Both HOME and PORTFOLIO call this one.
 */
internal fun computeTrackerNetWorth(
    walletSat: ULong,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    cachedInvestmentsSat: ULong,
    manualAssets: List<TrackingManualAssetFfi>,
    cachedManualAssetsSat: ULong,
    liabilities: List<TrackingLiabilityFfi>,
    liveBtcRate: Double?,
    dashboardFiatCode: String,
): TrackerNetWorth {
    val hasLiveRate = liveBtcRate != null && liveBtcRate.isFinite() && liveBtcRate > 0.0
    return computeTrackerNetWorth(
        walletSat = walletSat,
        cachedInvestmentsSat = cachedInvestmentsSat,
        liveInvestmentsSat = if (hasLiveRate) {
            computeLiveInvestmentsSat(investments, lotsByInvestmentId, liveBtcRate, dashboardFiatCode)
        } else {
            null
        },
        cachedManualAssetsSat = cachedManualAssetsSat,
        liveManualAssetsSat = if (hasLiveRate) {
            computeLiveManualAssetsSat(manualAssets, liveBtcRate, dashboardFiatCode)
        } else {
            null
        },
        liabilities = liabilities,
    )
}

/**
 * Composable wrapper: the shared [TrackerNetWorth] from the tracker
 * cache rows + the live BTC rate / dashboard currency. Used by the HOME
 * hero and by the goal dialogs' "% of net worth" base.
 */
@androidx.compose.runtime.Composable
internal fun rememberTrackerNetWorth(
    walletSat: ULong,
    investments: List<TrackingInvestmentFfi>,
    lotsByInvestmentId: Map<Long, List<TrackingInvestmentLotFfi>>,
    cachedInvestmentsSat: ULong,
    manualAssets: List<TrackingManualAssetFfi>,
    cachedManualAssetsSat: ULong,
    liabilities: List<TrackingLiabilityFfi>,
): TrackerNetWorth {
    val liveBtcRate = io.bhodl.desktop.prices.PriceCache.price.value
    val fiat = io.bhodl.desktop.settings.SettingsStore.fiatCurrency.value
    return androidx.compose.runtime.remember(
        walletSat, investments, lotsByInvestmentId, cachedInvestmentsSat,
        manualAssets, cachedManualAssetsSat, liabilities, liveBtcRate, fiat,
    ) {
        computeTrackerNetWorth(
            walletSat = walletSat,
            investments = investments,
            lotsByInvestmentId = lotsByInvestmentId,
            cachedInvestmentsSat = cachedInvestmentsSat,
            manualAssets = manualAssets,
            cachedManualAssetsSat = cachedManualAssetsSat,
            liabilities = liabilities,
            liveBtcRate = liveBtcRate,
            dashboardFiatCode = fiat.code,
        )
    }
}

/**
 * UX review 2026-10 (B8) — the tracked wallets, one entry per logical
 * wallet: a signing wallet and its watch-only mirror collapse into one
 * group, an unpaired signing wallet is not tracked at all. The HOME
 * wallet table, the PORTFOLIO "BTC Wallets (n)" row and the footer
 * count all derive from this list, so the counts agree.
 *
 * [excludedWalletIds] drops groups the user excluded from totals
 * (a group is kept while any of its members is still included).
 */
internal fun trackedWalletGroups(
    wallets: List<WalletRecordFfi>,
    watchBalances: Map<String, ULong>,
    excludedWalletIds: Set<String> = emptySet(),
): List<GroupedWallet> = groupWalletsByXpub(
    wallets = wallets,
    watchBalances = watchBalances,
    signingBalance = null,
    activeSigningId = null,
).filter { g -> g.memberIds.any { it !in excludedWalletIds } }

/** On-chain BTC of the tracked (and included) wallets. */
internal fun trackedWalletSat(groups: List<GroupedWallet>): ULong =
    groups.fold(0UL) { acc, g -> acc + (g.balanceSat ?: 0UL) }
