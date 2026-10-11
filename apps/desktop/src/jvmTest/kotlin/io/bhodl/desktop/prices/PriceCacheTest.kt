package io.bhodl.desktop.prices

import io.bhodl.desktop.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5.0 — sat ↔ fiat conversion tests for [PriceCache].
 *
 * The cache is a process-level singleton, so each test resets it via
 * the `seedForTest(null)` helper. We never hit the network here:
 * `refreshNow` is deliberately not exercised in unit tests (it lives
 * behind blocking HTTP and is covered manually at the end of each
 * phase). The conversion math, on the other hand, is pure once the
 * price is seeded — which is what these tests verify.
 */
class PriceCacheTest {

    @BeforeTest
    fun resetCacheAndSettings() {
        PriceCache.seedForTest(null)
        SettingsStore.reset()
    }

    @AfterTest
    fun cleanup() {
        PriceCache.seedForTest(null)
        SettingsStore.reset()
    }

    @Test
    fun `satToFiat returns null when cache is empty`() {
        assertNull(PriceCache.satToFiat(100_000UL))
    }

    @Test
    fun `fiatToSat returns null when cache is empty`() {
        assertNull(PriceCache.fiatToSat(100.0))
    }

    @Test
    fun `satToFiat converts a whole BTC at the seeded price`() {
        PriceCache.seedForTest(60_000.0)
        // 1 BTC = 100_000_000 sat → 60_000.0 fiat
        val fiat = PriceCache.satToFiat(100_000_000UL)
        assertNotNull(fiat)
        assertEquals(60_000.0, fiat, 1e-6)
    }

    @Test
    fun `satToFiat scales linearly with the sat amount`() {
        PriceCache.seedForTest(50_000.0)
        val one = PriceCache.satToFiat(100_000_000UL)!!
        val half = PriceCache.satToFiat(50_000_000UL)!!
        val quarter = PriceCache.satToFiat(25_000_000UL)!!
        assertEquals(50_000.0, one, 1e-6)
        assertEquals(25_000.0, half, 1e-6)
        assertEquals(12_500.0, quarter, 1e-6)
    }

    @Test
    fun `satToFiat handles small sat amounts without overflow`() {
        PriceCache.seedForTest(60_000.0)
        // 1 sat = 6 * 10^-4 EUR at 60k EUR/BTC
        val fiat = PriceCache.satToFiat(1UL)!!
        assertEquals(0.0006, fiat, 1e-9)
    }

    @Test
    fun `satToFiat returns zero for zero sat regardless of price`() {
        PriceCache.seedForTest(50_000.0)
        assertEquals(0.0, PriceCache.satToFiat(0UL))
    }

    @Test
    fun `fiatToSat is the inverse of satToFiat at the same price`() {
        PriceCache.seedForTest(50_000.0)
        val seedSat = 12_345_678UL
        val fiat = PriceCache.satToFiat(seedSat)!!
        val back = PriceCache.fiatToSat(fiat)!!
        // toLong() truncation can lose at most 1 sat on the round-trip.
        assertTrue(back == seedSat || back == seedSat - 1UL,
            "round-trip drifted by more than 1 sat: $seedSat → $fiat → $back")
    }

    @Test
    fun `fiatToSat converts whole fiat units to sat`() {
        PriceCache.seedForTest(50_000.0)
        // 50000 EUR @ 50000/BTC = 1 BTC = 100_000_000 sat
        assertEquals(100_000_000UL, PriceCache.fiatToSat(50_000.0))
        // 25000 EUR = 0.5 BTC
        assertEquals(50_000_000UL, PriceCache.fiatToSat(25_000.0))
    }

    @Test
    fun `fiatToSat returns null for a non positive price`() {
        PriceCache.seedForTest(0.0)
        assertNull(PriceCache.fiatToSat(100.0))
        PriceCache.seedForTest(-1.0)
        assertNull(PriceCache.fiatToSat(100.0))
    }

    @Test
    fun `fiatToSat clamps a negative fiat input to zero sat`() {
        PriceCache.seedForTest(50_000.0)
        // Negative fiat (e.g. user typed "-5" by mistake) is a UI bug
        // upstream, but the conversion should not produce a wraparound
        // ULong. coerceAtLeast(0L) before toULong() guarantees zero.
        assertEquals(0UL, PriceCache.fiatToSat(-5.0))
    }

    @Test
    fun `seedForTest exposes lastFetchUnix`() {
        PriceCache.seedForTest(60_000.0, lastFetchUnix = 1_700_000_000L)
        assertEquals(60_000.0, PriceCache.price.value)
        assertEquals(1_700_000_000L, PriceCache.lastFetchUnix.value)
    }

    // ---- T0-5 — concurrency safety ------------------------------------

    @Test
    fun `start is idempotent under concurrent invocation`() {
        // Two threads racing on start() must result in exactly one
        // started loop. We approximate by spinning many short-lived
        // calls; the AtomicBoolean.compareAndSet under the hood
        // guarantees at most one transition false -> true.
        // We don't directly observe the loop count, but we verify
        // that subsequent start() calls return without launching
        // any new coroutines (the test would otherwise leak threads
        // and the cleanup stop() call would still settle the state).
        try {
            val results = (1..16).map {
                Thread {
                    PriceCache.start()
                }.also { it.start() }
            }
            results.forEach { it.join() }
            // No assertion error means no double-init crash; the
            // AtomicBoolean is now true. A second start is a no-op.
            PriceCache.start()
        } finally {
            PriceCache.stop()
        }
    }

    @Test
    fun `refreshNow serializes concurrent calls via mutex`() = runBlocking {
        // With currency=NONE, refreshNow only flips _price to null
        // and does no HTTP — safe to invoke 100 times in parallel.
        // Before the T0-5 fix, two concurrent calls could both set
        // _refreshing=true and execute the IO block; with the mutex,
        // they're serialized and the test simply must not deadlock
        // or throw a ConcurrentModificationException.
        SettingsStore.setFiatCurrency(FiatCurrency.NONE)
        val jobs = (1..16).map {
            async { PriceCache.refreshNow() }
        }
        jobs.awaitAll()
        // After all calls settle: state is consistent.
        assertNull(PriceCache.price.value)
        assertEquals(false, PriceCache.refreshing.value)
    }

    // ─── Phase 9 — convertViaBtc (quote-currency FX bridge) ───────────

    @Test
    fun `convertViaBtc returns price unchanged when currencies match`() {
        // Rates are irrelevant when quote and target agree.
        assertEquals(
            123.97,
            PriceCache.convertViaBtc("123.97".toDouble(), "USD", "USD", rateTarget = null, rateQuote = null),
        )
    }

    @Test
    fun `convertViaBtc bridges USD quote to EUR via BTC`() {
        // 1 BTC = 60,000 USD and 55,000 EUR → 1 USD = 55/60 EUR.
        // 123.97 USD → 123.97 * 55000/60000 = 113.639… EUR.
        val eur = PriceCache.convertViaBtc(
            price = 123.97,
            quoteCurrency = "USD",
            targetCurrency = "EUR",
            rateTarget = 55_000.0,
            rateQuote = 60_000.0,
        )
        assertNotNull(eur)
        assertEquals(123.97 * 55_000.0 / 60_000.0, eur, 1e-9)
    }

    @Test
    fun `convertViaBtc returns null when a rate is missing`() {
        assertNull(
            PriceCache.convertViaBtc(100.0, "USD", "EUR", rateTarget = null, rateQuote = 60_000.0),
        )
        assertNull(
            PriceCache.convertViaBtc(100.0, "USD", "EUR", rateTarget = 55_000.0, rateQuote = null),
        )
    }

    @Test
    fun `convertViaBtc returns null on non-positive or non-finite rates`() {
        assertNull(PriceCache.convertViaBtc(100.0, "USD", "EUR", 0.0, 60_000.0))
        assertNull(PriceCache.convertViaBtc(100.0, "USD", "EUR", 55_000.0, -1.0))
        assertNull(PriceCache.convertViaBtc(100.0, "USD", "EUR", Double.NaN, 60_000.0))
        assertNull(PriceCache.convertViaBtc(Double.NaN, "USD", "EUR", 55_000.0, 60_000.0))
    }

    @Test
    fun `convertViaBtc match check is case-insensitive`() {
        assertEquals(50.0, PriceCache.convertViaBtc(50.0, "eur", "EUR", null, null))
    }
}
