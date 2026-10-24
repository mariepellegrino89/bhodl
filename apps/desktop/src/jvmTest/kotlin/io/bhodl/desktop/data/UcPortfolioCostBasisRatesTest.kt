package io.bhodl.desktop.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PRT-146 — daily BTC rates for the average-cost view: one lookup per (day, currency) per session. */
class UcPortfolioCostBasisRatesTest {

    private val calls = mutableListOf<Pair<Long, String>>()
    private val d1 = 1_700_006_400L
    private val d2 = d1 + 86_400L

    @BeforeTest
    fun setup() {
        BtcCostBasisRates.clear()
        BtcCostBasisRates.lookup = { day, ccy -> calls += day to ccy; if (day == d1) 30_000.0 else 31_000.0 }
    }

    @AfterTest
    fun teardown() {
        BtcCostBasisRates.clear()
        BtcCostBasisRates.lookup = { day, ccy -> io.bhodl.desktop.prices.BtcRateHistoryClient.rateAt(day, ccy) }
        BtcCostBasisRates.lookupTimeoutMs = 15_000L
    }

    @Test
    fun `PRT-146 ensure looks each day up once and publishes the rates`() = runBlocking {
        BtcCostBasisRates.ensure(setOf(d2, d1), "EUR")
        assertEquals(listOf(d1 to "EUR", d2 to "EUR"), calls)
        assertEquals(mapOf((d1 to "EUR") to 30_000.0, (d2 to "EUR") to 31_000.0), BtcCostBasisRates.rates.value)
        BtcCostBasisRates.ensure(setOf(d1, d2), "EUR")
        assertEquals(2, calls.size)
        assertFalse(BtcCostBasisRates.loading.value)
    }

    @Test
    fun `PRT-146 another currency is a separate lookup`() = runBlocking {
        BtcCostBasisRates.ensure(setOf(d1), "EUR")
        BtcCostBasisRates.ensure(setOf(d1), "USD")
        assertEquals(listOf(d1 to "EUR", d1 to "USD"), calls)
    }

    @Test
    fun `PRT-146 misses are not retried until clear, then everything is asked again`() = runBlocking {
        BtcCostBasisRates.lookup = { day, ccy -> calls += day to ccy; null }
        BtcCostBasisRates.ensure(setOf(d1), "EUR")
        BtcCostBasisRates.ensure(setOf(d1), "EUR")
        assertEquals(1, calls.size)
        assertTrue(BtcCostBasisRates.rates.value.isEmpty())
        BtcCostBasisRates.clear()
        BtcCostBasisRates.ensure(setOf(d1), "EUR")
        assertEquals(2, calls.size)
    }

    @Test
    fun `PRT-146 failing, non-positive or hung lookups publish nothing`() = runBlocking {
        BtcCostBasisRates.lookupTimeoutMs = 50L
        BtcCostBasisRates.lookup = { day, _ ->
            when (day) {
                d1 -> throw IllegalStateException("boom")
                d2 -> 0.0
                else -> { delay(5_000); 1.0 }
            }
        }
        BtcCostBasisRates.ensure(setOf(d1, d2, d2 + 86_400L), "EUR")
        assertTrue(BtcCostBasisRates.rates.value.isEmpty())
    }

    @Test
    fun `PRT-146 blank currency asks nothing`() = runBlocking {
        BtcCostBasisRates.ensure(setOf(d1), " ")
        assertTrue(calls.isEmpty())
    }
}
