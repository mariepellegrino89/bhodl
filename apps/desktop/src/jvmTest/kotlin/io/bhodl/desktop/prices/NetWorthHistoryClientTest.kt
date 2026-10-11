package io.bhodl.desktop.prices

import io.bhodl.ffi.NwSnapshotFfi
import io.bhodl.ffi.PaymentEventLiteFfi
import io.bhodl.ffi.WalletException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 8.4 — unit tests for [NetWorthHistoryClient].
 *
 * The FFI seam is replaced with a fake [NetWorthHistoryClient.NwHistorySource]
 * so these tests run fully in-process without touching disk or the Rust
 * library. Each test injects its own fake and the [AfterTest] restores the
 * default source and clears the cache.
 */
class NetWorthHistoryClientTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    companion object {
        private val CURRENCY = FiatCurrency.EUR

        // A single UTC-midnight anchor date (2024-01-01T00:00:00Z)
        private const val D0 = 1_704_067_200L

        private fun makeSnapshotFfi(dateUnix: Long, totalSat: Long): NwSnapshotFfi =
            NwSnapshotFfi(
                dateUnix = dateUnix,
                totalSat = totalSat,
                onchainSat = totalSat,
                investmentsSat = 0L,
                manualAssetsSat = 0L,
                liabilitiesSat = 0L,
                fiatEquivalent = totalSat.toDouble() / 1e8 * 40_000.0,
                rateBtcFiat = 40_000.0,
            )
    }

    private class FakeSource(
        private val impl: (Long, Long, String, List<PaymentEventLiteFfi>) -> List<NwSnapshotFfi>,
    ) : NetWorthHistoryClient.NwHistorySource {
        @Volatile
        var callCount = 0
            private set

        override fun netWorthHistory(
            fromUnix: Long,
            toUnix: Long,
            currency: String,
            events: List<PaymentEventLiteFfi>,
        ): List<NwSnapshotFfi> {
            callCount++
            return impl(fromUnix, toUnix, currency, events)
        }
    }

    @BeforeTest
    fun setup() {
        NetWorthHistoryClient.clearCacheForTest()
    }

    @AfterTest
    fun teardown() {
        NetWorthHistoryClient.resetSourceForTest()
        NetWorthHistoryClient.clearCacheForTest()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun `netWorthHistory caches result per range and currency`() = runBlocking {
        val fakeSnapshots = listOf(makeSnapshotFfi(D0, 100_000L))
        val fake = FakeSource { _, _, _, _ -> fakeSnapshots }
        NetWorthHistoryClient.setSourceForTest(fake)

        // First call: should hit the source.
        val first = NetWorthHistoryClient.netWorthHistory(
            fromUnix = D0,
            toUnix = D0 + 86_400L,
            currency = CURRENCY,
        )
        assertEquals(1, first.size, "first call should return 1 snapshot")
        assertEquals(100_000L, first[0].totalSat)
        assertEquals(1, fake.callCount, "source must be called exactly once on first request")

        // Second call with same key: cache hit, source NOT called again.
        val second = NetWorthHistoryClient.netWorthHistory(
            fromUnix = D0,
            toUnix = D0 + 86_400L,
            currency = CURRENCY,
        )
        assertEquals(1, second.size)
        assertEquals(1, fake.callCount, "source must NOT be called again on cache hit")

        // Different currency: different cache key → source called again.
        val thirdCurrency = FiatCurrency.USD
        NetWorthHistoryClient.netWorthHistory(
            fromUnix = D0,
            toUnix = D0 + 86_400L,
            currency = thirdCurrency,
        )
        assertEquals(2, fake.callCount, "source must be called for a different currency")
    }

    @Test
    fun `cache TTL respected per range`() = runBlocking {
        val fakeSnapshots = listOf(makeSnapshotFfi(D0, 50_000L))
        val fake = FakeSource { _, _, _, _ -> fakeSnapshots }
        NetWorthHistoryClient.setSourceForTest(fake)

        // Window ≤1 day → TTL should be 5 min (300 000 ms).
        // We cannot wait 5 min in a unit test — instead verify that a
        // second call within the TTL window is served from cache.
        val from = D0
        val to = D0 // same-day window (0 s → coerced to rangeDays=1 → TTL=5min)

        NetWorthHistoryClient.netWorthHistory(
            fromUnix = from,
            toUnix = to,
            currency = CURRENCY,
        )
        assertEquals(1, fake.callCount)

        NetWorthHistoryClient.netWorthHistory(
            fromUnix = from,
            toUnix = to,
            currency = CURRENCY,
        )
        // Still 1: cache has not expired within the test duration.
        assertEquals(1, fake.callCount, "second call must be served from cache within TTL")

        // A 91-day window → TTL should be 6h; same logic applies.
        val from91 = D0
        val to91 = D0 + 91L * 86_400L
        NetWorthHistoryClient.netWorthHistory(
            fromUnix = from91,
            toUnix = to91,
            currency = CURRENCY,
        )
        assertEquals(2, fake.callCount, "91-day window is a new cache key — source called")

        NetWorthHistoryClient.netWorthHistory(
            fromUnix = from91,
            toUnix = to91,
            currency = CURRENCY,
        )
        assertEquals(2, fake.callCount, "91-day window second call: still from cache")
    }

    @Test
    fun `network failure returns empty list without throwing`() = runBlocking {
        val fake = FakeSource { _, _, _, _ ->
            throw WalletException.Storage("network unavailable: rate fetch failed")
        }
        NetWorthHistoryClient.setSourceForTest(fake)

        val result = NetWorthHistoryClient.netWorthHistory(
            fromUnix = D0,
            toUnix = D0 + 30L * 86_400L,
            currency = CURRENCY,
        )

        assertTrue(result.isEmpty(), "on WalletException the client must return an empty list")
        // No exception must propagate — if it did the runBlocking would fail.
    }

    // -----------------------------------------------------------------------
    // Audit 2026-10 (M2) — day-aligned key, bounded map, in-flight dedupe
    // -----------------------------------------------------------------------

    @Test
    fun `calls within the same day share one cache entry`() = runBlocking {
        val fake = FakeSource { _, _, _, _ -> listOf(makeSnapshotFfi(D0, 1L)) }
        NetWorthHistoryClient.setSourceForTest(fake)
        // Same UTC day, different seconds (the old key used raw seconds).
        NetWorthHistoryClient.netWorthHistory(
            fromUnix = D0 - 30L * 86_400L + 17L,
            toUnix = D0 + 17L,
            currency = CURRENCY,
        )
        NetWorthHistoryClient.netWorthHistory(
            fromUnix = D0 - 30L * 86_400L + 3_600L,
            toUnix = D0 + 3_600L,
            currency = CURRENCY,
        )
        assertEquals(1, fake.callCount)
    }

    @Test
    fun `source receives the day-aligned window`() = runBlocking {
        var seen: Pair<Long, Long>? = null
        NetWorthHistoryClient.setSourceForTest(
            FakeSource { from, to, _, _ ->
                seen = from to to
                emptyList()
            },
        )
        NetWorthHistoryClient.netWorthHistory(
            fromUnix = D0 + 123L,
            toUnix = D0 + 86_400L + 456L,
            currency = CURRENCY,
        )
        assertEquals(D0 to D0 + 86_400L, seen)
    }

    @Test
    fun `concurrent requests for the same key run the FFI once`() = runBlocking {
        val release = java.util.concurrent.CountDownLatch(1)
        val fake = FakeSource { _, _, _, _ ->
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            listOf(makeSnapshotFfi(D0, 7L))
        }
        NetWorthHistoryClient.setSourceForTest(fake)
        val calls = List(5) {
            async(kotlinx.coroutines.Dispatchers.Default) {
                NetWorthHistoryClient.netWorthHistory(
                    fromUnix = D0,
                    toUnix = D0 + 7L * 86_400L,
                    currency = CURRENCY,
                )
            }
        }
        kotlinx.coroutines.delay(200)
        release.countDown()
        val results = calls.awaitAll()
        assertEquals(1, fake.callCount, "in-flight requests must be deduped")
        assertTrue(results.all { it.size == 1 && it[0].totalSat == 7L })
    }

    @Test
    fun `cache size is bounded`() = runBlocking {
        val fake = FakeSource { _, _, _, _ -> emptyList() }
        NetWorthHistoryClient.setSourceForTest(fake)
        repeat(100) { i ->
            NetWorthHistoryClient.netWorthHistory(
                fromUnix = D0 + i * 86_400L,
                toUnix = D0 + (i + 1) * 86_400L,
                currency = CURRENCY,
            )
        }
        assertTrue(NetWorthHistoryClient.cacheSizeForTest() <= 32)
    }
}
