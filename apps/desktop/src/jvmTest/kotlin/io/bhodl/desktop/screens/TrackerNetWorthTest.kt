package io.bhodl.desktop.screens

import io.bhodl.ffi.Network
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.ffi.WalletHandle
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UX review 2026-10 — B1 (hero net worth ignored liabilities) and B8
 * (Portfolio "BTC Wallets (4)" vs HOME "3 wallets").
 */
class TrackerNetWorthTest {

    private fun liability(id: Long, sat: ULong) = TrackingLiabilityFfi(
        id = id, name = "l$id", amountSat = sat, kind = "loan", displayLabel = null, createdAtUnix = 0,
    )

    @Test
    fun `B1 net worth subtracts liabilities from assets`() {
        // Synthetic numbers: assets 2.00 BTC, liabilities
        // 0.15 BTC → net worth 1.85 BTC (what Portfolio shows).
        val nw = computeTrackerNetWorth(
            walletSat = 55_000_000UL,
            cachedInvestmentsSat = 20_000_000UL,
            liveInvestmentsSat = null,
            cachedManualAssetsSat = 125_000_000UL,
            liveManualAssetsSat = null,
            liabilities = listOf(liability(1, 10_000_000UL), liability(2, 4_000_000UL), liability(3, 1_000_000UL)),
        )
        assertEquals(200_000_000UL, nw.assetsSat)
        assertEquals(15_000_000UL, nw.liabilitiesSat)
        assertEquals(185_000_000L, nw.netWorthSat)
    }

    @Test
    fun `B1 net worth can go negative`() {
        val nw = computeTrackerNetWorth(
            walletSat = 1_000UL, cachedInvestmentsSat = 0UL, liveInvestmentsSat = null,
            cachedManualAssetsSat = 0UL, liveManualAssetsSat = null,
            liabilities = listOf(liability(1, 5_000UL)),
        )
        assertEquals(-4_000L, nw.netWorthSat)
    }

    @Test
    fun `live recompute is used whenever a live rate exists, even when lower`() {
        // Code review 2026-10 — maxOf(cached, live) biased the NW upward
        // (e.g. after a BTC rally the cached sat value of fiat assets is
        // stale-high). Live wins in both directions.
        val nw = computeTrackerNetWorth(
            walletSat = 0UL,
            cachedInvestmentsSat = 100UL, liveInvestmentsSat = 150UL,
            cachedManualAssetsSat = 300UL, liveManualAssetsSat = 200UL,
            liabilities = emptyList(),
        )
        assertEquals(150UL, nw.investmentsSat)
        assertEquals(200UL, nw.manualAssetsSat)
        assertEquals(350L, nw.netWorthSat)
    }

    @Test
    fun `cached aggregates are used only without a live rate`() {
        val nw = computeTrackerNetWorth(
            walletSat = 0UL,
            investments = emptyList(), lotsByInvestmentId = emptyMap(), cachedInvestmentsSat = 100UL,
            manualAssets = emptyList(), cachedManualAssetsSat = 300UL,
            liabilities = emptyList(), liveBtcRate = null, dashboardFiatCode = "EUR",
        )
        assertEquals(100UL, nw.investmentsSat)
        assertEquals(300UL, nw.manualAssetsSat)
        // With a live rate the (empty) live recompute wins over the cache.
        val live = computeTrackerNetWorth(
            walletSat = 0UL,
            investments = emptyList(), lotsByInvestmentId = emptyMap(), cachedInvestmentsSat = 100UL,
            manualAssets = emptyList(), cachedManualAssetsSat = 300UL,
            liabilities = emptyList(), liveBtcRate = 50_000.0, dashboardFiatCode = "EUR",
        )
        assertEquals(0UL, live.investmentsSat)
    }

    @Test
    fun `percent-of-net-worth goal base subtracts liabilities and floors at zero`() {
        val nw = computeTrackerNetWorth(
            walletSat = 1_000_000UL, cachedInvestmentsSat = 0UL, liveInvestmentsSat = null,
            cachedManualAssetsSat = 0UL, liveManualAssetsSat = null,
            liabilities = listOf(liability(1, 200_000UL)),
        )
        assertEquals(800_000UL, nw.goalBaseSat)
        // "30% of NW" = 240,000 sat, not 30% of the 1,000,000 gross assets.
        assertEquals(240_000UL, targetSatFromPct(nw.goalBaseSat, 30u))
        val underwater = nw.copy(liabilitiesSat = 5_000_000UL)
        assertEquals(0UL, underwater.goalBaseSat)
    }

    private fun externalDescriptor(mnemonic: String): String {
        val w = WalletHandle.fromMnemonic(Network.SIGNET, mnemonic, "")
        try {
            return w.exportPublicDescriptor().externalDescriptor
        } finally {
            runCatching { w.destroy() }
        }
    }

    private fun record(id: String, kind: WalletKindFfi, descriptor: String) = WalletRecordFfi(
        id = id, label = id, kind = kind, descriptorPub = descriptor,
        network = "signet", createdAtUnix = 0, pinsWarned = false,
    )

    @Test
    fun `B8 a signing wallet and its watch mirror count as ONE tracked wallet`() {
        val a = externalDescriptor("legal winner thank year wave sausage worth useful legal winner thank yellow")
        val b = externalDescriptor("letter advice cage absurd amount doctor acoustic avoid letter advice cage above")
        val c = externalDescriptor("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        // Typical shape: 4 registry rows, 3 wallets.
        val wallets = listOf(
            record("signing", WalletKindFfi.SIGNING, a),
            record("mirror", WalletKindFfi.WATCH_XPUB, a),
            record("cold", WalletKindFfi.WATCH_XPUB, b),
            record("savings", WalletKindFfi.WATCH_XPUB, c),
        )
        val balances = mapOf("mirror" to 3_000_000UL, "cold" to 50_000_000UL, "savings" to 2_000_000UL)

        val groups = trackedWalletGroups(wallets, balances)

        assertEquals(3, groups.size, "Portfolio used wallets.size (= 4)")
        assertEquals(55_000_000UL, trackedWalletSat(groups))
        assertTrue(groups.single { it.displayLabel == "signing" }.isSigning)
    }

    @Test
    fun `B8 excluded wallets drop out of the tracked count and total`() {
        val a = externalDescriptor("legal winner thank year wave sausage worth useful legal winner thank yellow")
        val b = externalDescriptor("letter advice cage absurd amount doctor acoustic avoid letter advice cage above")
        val wallets = listOf(
            record("signing", WalletKindFfi.SIGNING, a),
            record("mirror", WalletKindFfi.WATCH_XPUB, a),
            record("cold", WalletKindFfi.WATCH_XPUB, b),
        )
        val balances = mapOf("mirror" to 10UL, "cold" to 20UL)
        val groups = trackedWalletGroups(wallets, balances, excludedWalletIds = setOf("cold"))
        assertEquals(1, groups.size)
        assertEquals(10UL, trackedWalletSat(groups))
    }
}
