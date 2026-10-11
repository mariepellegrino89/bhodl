package io.bhodl.desktop.screens

import io.bhodl.desktop.prices.HistoricalPricePoint
import io.bhodl.desktop.prices.NetWorthHistoryClient.NwSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (B5) — pure tests for the tracker hero's fiat
 * figures ([heroFiat], [netWorthFiatSeries], [scrubFiatAt]).
 *
 * The old hero printed the last point of `computeNetWorthSeries`, a
 * reconstruction from the per-wallet on-chain tx snapshots only. With
 * watch wallets whose tx list wasn't synced (or any NW held in
 * investments / manual assets) that series was all zeros, so the hero
 * read "€0.00" / "+€0.00" as soon as the async price history landed —
 * and the spot value before it did (the light/dark discrepancy).
 */
class TrackerHomeAggregationsTest {

    private fun snap(t: Long, totalSat: Long, fiat: Double) = NwSnapshot(
        dateUnix = t, totalSat = totalSat, onchainSat = totalSat, investmentsSat = 0,
        manualAssetsSat = 0, liabilitiesSat = 0, fiatEquivalent = fiat, rateBtcFiat = 0.0,
    )

    @Test
    fun `B5 hero fiat is net worth times the spot rate, never a zero tx reconstruction`() {
        // 1.85 BTC at €60,000 = €111,000.00 — with NO tx history at all.
        val f = heroFiat(netWorthSat = 185_000_000L, spotRate = 60_000.0, nwHistory = emptyList())
        val now = assertNotNull(f.nowFiat)
        assertEquals(111_000.0, now, 0.01)
    }

    @Test
    fun `B5 no usable spot rate yields no fiat value`() {
        assertNull(heroFiat(100_000_000L, null, emptyList()).nowFiat)
        assertNull(heroFiat(100_000_000L, 0.0, emptyList()).nowFiat)
        assertNull(heroFiat(100_000_000L, Double.NaN, emptyList()).nowFiat)
    }

    @Test
    fun `B5 negative net worth gives a negative fiat value`() {
        assertEquals(-500.0, heroFiat(-1_000_000L, 50_000.0, emptyList()).nowFiat!!, 1e-9)
    }

    @Test
    fun `B5 fiat tail is hidden without a reconstructed history instead of +0`() {
        assertNull(heroFiat(1L, 1.0, emptyList()).periodDeltaFiat)
        assertNull(heroFiat(1L, 1.0, listOf(snap(1, 10, 5.0))).periodDeltaFiat)
    }

    @Test
    fun `B5 fiat tail is last minus first fiat equivalent of the NW history`() {
        val h = listOf(snap(1, 100, 1_000.0), snap(2, 120, 1_300.0), snap(3, 110, 1_250.0))
        assertEquals(250.0, heroFiat(110L, 10.0, h).periodDeltaFiat!!, 1e-9)
    }

    @Test
    fun `net worth fiat series values today's NW at each historical rate`() {
        val prices = listOf(
            HistoricalPricePoint(1_000L, 50_000.0),
            HistoricalPricePoint(2_000L, 55_000.0),
        )
        val series = netWorthFiatSeries(50_000_000L, prices)
        assertEquals(listOf(1_000L to 25_000.0, 2_000L to 27_500.0), series)
    }

    @Test
    fun `net worth fiat series feeds the fiat vs BTC chip consistently`() {
        val prices = listOf(
            HistoricalPricePoint(1_000L, 50_000.0),
            HistoricalPricePoint(2_000L, 55_000.0),
        )
        val change = computeFiatVsBtcChange(netWorthFiatSeries(100_000_000L, prices), 100_000_000UL)
        assertEquals(10.0, assertNotNull(change).deltaPct, 1e-9)
    }

    @Test
    fun `empty price history yields empty series`() {
        assertTrue(netWorthFiatSeries(1L, emptyList()).isEmpty())
    }

    @Test
    fun `scrubbing the sat line reports the snapshot's fiat value, not the sat count`() {
        val h = listOf(snap(100, 185_000_000, 110_000.0), snap(200, 186_000_000, 111_000.0))
        assertEquals(200L to 111_000.0, scrubFiatAt(200, h, emptyList()))
    }

    @Test
    fun `scrubbing falls back to the nearest fiat series point`() {
        val fiat = listOf(1_000L to 10.0, 2_000L to 20.0, 3_000L to 30.0)
        assertEquals(2_000L to 20.0, scrubFiatAt(2_100, emptyList(), fiat))
        assertNull(scrubFiatAt(1, emptyList(), emptyList()))
    }
}
