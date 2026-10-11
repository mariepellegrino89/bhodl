package io.bhodl.desktop.prices

import io.bhodl.ffi.BackfillReportFfi
import io.bhodl.ffi.RateBackfillRequestFfi
import io.bhodl.ffi.RatePointFfi
import io.bhodl.ffi.WalletException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.3.1 — tests for the Kotlin [BtcRateHistoryClient] wrapper.
 *
 * The FFI seam is replaced with a fake [BtcRateHistoryClient.RateSource]
 * so we can assert error mapping (RateError-via-message-detail →
 * IllegalArgumentException for currency-not-supported, null for
 * network-unavailable) and the basic happy path without touching disk.
 */
class BtcRateHistoryClientTest {

    @AfterTest
    fun teardown() {
        BtcRateHistoryClient.resetSourceForTest()
    }

    private class FakeSource(
        val rateAtImpl: (Long, String) -> Double? = { _, _ -> null },
        val rangeImpl: (Long, Long, String) -> List<RatePointFfi> =
            { _, _, _ -> emptyList() },
        val backfillImpl: (List<RateBackfillRequestFfi>) -> BackfillReportFfi =
            { _ ->
                BackfillReportFfi(
                    requested = 0u,
                    cacheHits = 0u,
                    fetched = 0u,
                    failed = emptyList(),
                )
            },
    ) : BtcRateHistoryClient.RateSource {
        override fun rateAt(dateUnix: Long, currency: String): Double? =
            rateAtImpl(dateUnix, currency)

        override fun range(
            fromUnix: Long,
            toUnix: Long,
            currency: String,
        ): List<RatePointFfi> = rangeImpl(fromUnix, toUnix, currency)

        override fun backfill(
            requests: List<RateBackfillRequestFfi>,
        ): BackfillReportFfi = backfillImpl(requests)
    }

    @Test
    fun `rateAt with mocked FFI returns expected value`() = runBlocking {
        BtcRateHistoryClient.setSourceForTest(
            FakeSource(rateAtImpl = { _, _ -> 50_123.45 }),
        )
        val rate = BtcRateHistoryClient.rateAt(
            dateUnix = 1_700_000_000L,
            currency = "EUR",
        )
        assertEquals(50_123.45, rate)
    }

    @Test
    fun `rateAt on currency-not-supported throws IllegalArgumentException`() = runBlocking {
        BtcRateHistoryClient.setSourceForTest(
            FakeSource(
                rateAtImpl = { _, _ ->
                    // Simulate the Rust RateError::CurrencyNotSupported
                    // surfaced through WalletException.Storage.
                    throw WalletException.Storage("currency not supported: XYZ")
                },
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            BtcRateHistoryClient.rateAt(
                dateUnix = 1_700_000_000L,
                currency = "XYZ",
            )
        }
        Unit
    }

    @Test
    fun `rateAt on network-unavailable returns null (graceful degrade)`() = runBlocking {
        BtcRateHistoryClient.setSourceForTest(
            FakeSource(
                rateAtImpl = { _, _ ->
                    throw WalletException.Storage("network unavailable")
                },
            ),
        )
        val rate = BtcRateHistoryClient.rateAt(
            dateUnix = 1_700_000_000L,
            currency = "EUR",
        )
        assertNull(rate)
    }

    @Test
    fun `range returns the points the source provided`() = runBlocking {
        val series = listOf(
            RatePointFfi(dateUnix = 1_700_000_000L, rate = 50_000.0),
            RatePointFfi(dateUnix = 1_700_086_400L, rate = 50_100.0),
        )
        BtcRateHistoryClient.setSourceForTest(
            FakeSource(rangeImpl = { _, _, _ -> series }),
        )
        val out = BtcRateHistoryClient.range(
            fromUnix = 1_700_000_000L,
            toUnix = 1_700_086_400L,
            currency = "EUR",
        )
        assertEquals(2, out.size)
        assertTrue(out[0].dateUnix < out[1].dateUnix, "expected ASC order")
        assertEquals(50_000.0, out[0].rate)
        assertEquals(50_100.0, out[1].rate)
    }
}
