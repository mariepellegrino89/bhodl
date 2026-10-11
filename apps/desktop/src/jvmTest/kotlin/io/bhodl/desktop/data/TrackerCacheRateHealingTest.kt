package io.bhodl.desktop.data

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Code review 2026-10 — the B9 rate healing must stay off the refresh
 * critical path: bounded number of lookups per pass, each with a
 * timeout, results republished into the cache and persisted.
 */
class TrackerCacheRateHealingTest {

    private val day = 86_400L
    private val defaultLookup = TrackerCache.rateLookup
    private val defaultPersist = TrackerCache.persistHealedRates
    private val defaultTimeout = TrackerCache.rateLookupTimeoutMs

    @BeforeTest
    fun setUp() = TrackerCache.reset()

    @AfterTest
    fun tearDown() {
        TrackerCache.rateLookup = defaultLookup
        TrackerCache.persistHealedRates = defaultPersist
        TrackerCache.rateLookupTimeoutMs = defaultTimeout
        TrackerCache.reset()
    }

    private fun inv() = TrackingInvestmentFfi(
        id = 1, name = "i", assetType = "etf", ticker = null, quantity = 0.0, unit = "",
        costBasisFiat = 0.0, costBasisCurrency = "EUR", purchaseUnix = 0,
        manualCurrentValue = null, fetchedCurrentValue = null, lastPricedAtUnix = null,
        notes = null, costBasisMethod = "fifo", createdAtUnix = 0,
    )

    private fun lot(id: Long, dayIndex: Long) = TrackingInvestmentLotFfi(
        id = id, investmentId = 1, quantity = 1.0, pricePerUnit = 100.0, feesFiat = 0.0,
        purchaseUnix = dayIndex * day, notes = null, createdAtUnix = 0, btcPerUnitAtPurchase = null,
    )

    @Test
    fun `healing republishes found rates and persists the healed rows`() = runBlocking<Unit> {
        TrackerCache.seedForTest(investments = listOf(inv()), investmentLotsByInvestmentId = mapOf(1L to listOf(lot(7, 3))))
        var persisted: List<Long> = emptyList()
        TrackerCache.rateLookup = { _, _ -> 40_000.0 }
        TrackerCache.persistHealedRates = { lots, _ -> persisted = lots }

        TrackerCache.healMissingRates()

        val healed = TrackerCache.investmentLotsByInvestmentId.value.getValue(1L).single()
        assertEquals(40_000.0, healed.btcPerUnitAtPurchase)
        assertEquals(listOf(7L), persisted)
    }

    @Test
    fun `a hung rate provider cannot stall the pass beyond the timeout`() = runBlocking<Unit> {
        TrackerCache.seedForTest(investments = listOf(inv()), investmentLotsByInvestmentId = mapOf(1L to listOf(lot(7, 3))))
        TrackerCache.rateLookupTimeoutMs = 150
        TrackerCache.rateLookup = { _, _ -> Thread.sleep(5_000); 40_000.0 }
        TrackerCache.persistHealedRates = { _, _ -> error("nothing to persist") }

        val started = System.currentTimeMillis()
        TrackerCache.healMissingRates()
        val elapsed = System.currentTimeMillis() - started

        assertTrue(elapsed < 2_000, "pass took ${elapsed}ms")
        assertNull(TrackerCache.investmentLotsByInvestmentId.value.getValue(1L).single().btcPerUnitAtPurchase)
    }

    @Test
    fun `lookups per pass are capped`() = runBlocking<Unit> {
        // 20 lots on 20 different days → 20 distinct (day, ccy) keys.
        val lots = (1L..20L).map { lot(it, it) }
        TrackerCache.seedForTest(investments = listOf(inv()), investmentLotsByInvestmentId = mapOf(1L to lots))
        val calls = AtomicInteger()
        TrackerCache.rateLookup = { _, _ -> calls.incrementAndGet(); 40_000.0 }
        TrackerCache.persistHealedRates = { _, _ -> }

        TrackerCache.healMissingRates()

        assertEquals(TrackerCache.MAX_RATE_LOOKUPS_PER_PASS, calls.get())
        val withRate = TrackerCache.investmentLotsByInvestmentId.value.getValue(1L)
            .count { it.btcPerUnitAtPurchase != null }
        assertEquals(TrackerCache.MAX_RATE_LOOKUPS_PER_PASS, withRate)
    }
}
