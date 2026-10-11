package io.bhodl.desktop.prices

import io.bhodl.ffi.BackfillReportFfi
import io.bhodl.ffi.RateBackfillRequestFfi
import io.bhodl.ffi.RatePointFfi
import io.bhodl.ffi.WalletException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.6 — typed-error hardening for [BtcRateHistoryClient].
 *
 * Phase 8.3.1 routed `RateError::CurrencyNotSupported` and
 * `NetworkUnavailable` through [WalletException.Storage] with a
 * substring-detectable message. Phase 8.6 promotes them to dedicated
 * variants ([WalletException.RateCurrencyNotSupported],
 * [WalletException.RateNetworkUnavailable], and the catch-all
 * [WalletException.RateAllSourcesFailed]). These tests pin the
 * wrapper's behaviour for each typed catch.
 *
 * The legacy `Storage("currency not supported: …")` /
 * `Storage("network unavailable")` shapes are exercised in the
 * sibling [BtcRateHistoryClientTest] — we still tolerate them via the
 * `handleRateException` fallback so a not-yet-migrated FFI path
 * doesn't degrade to a hard crash.
 */
class BtcRateHistoryClientErrorHardeningTest {

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
    fun `rateAt with typed RateCurrencyNotSupported throws IllegalArgumentException`() =
        runBlocking {
            BtcRateHistoryClient.setSourceForTest(
                FakeSource(
                    rateAtImpl = { _, _ ->
                        // The typed variant carries the offending
                        // currency code as a structured field — no
                        // more parsing a free-form message.
                        throw WalletException.RateCurrencyNotSupported("XYZ")
                    },
                ),
            )
            val ex = assertFailsWith<IllegalArgumentException> {
                BtcRateHistoryClient.rateAt(
                    dateUnix = 1_700_000_000L,
                    currency = "XYZ",
                )
            }
            // The currency code must propagate into the
            // IllegalArgumentException so a developer reading the
            // crash log can spot the offending hardcoded value.
            assertTrue(
                ex.message?.contains("XYZ") == true,
                "expected currency code in the exception message, got: ${ex.message}",
            )
        }

    @Test
    fun `rateAt with typed RateNetworkUnavailable returns null (soft-fail)`() = runBlocking {
        BtcRateHistoryClient.setSourceForTest(
            FakeSource(
                rateAtImpl = { _, _ ->
                    throw WalletException.RateNetworkUnavailable()
                },
            ),
        )
        // Soft-fail is the contract — UI substitutes "no data" rather
        // than crashing, matches the Phase 8.3.1 design.
        assertNull(
            BtcRateHistoryClient.rateAt(
                dateUnix = 1_700_000_000L,
                currency = "EUR",
            ),
        )
    }

    @Test
    fun `rateAt with typed RateAllSourcesFailed also returns null (soft-fail)`() =
        runBlocking {
            BtcRateHistoryClient.setSourceForTest(
                FakeSource(
                    rateAtImpl = { _, _ ->
                        throw WalletException.RateAllSourcesFailed("CoinGecko 429, Kraken 503")
                    },
                ),
            )
            // Distinct typed catch from network-unavailable: every
            // configured source has refused, not just the first one.
            // Treated as a soft-fail too — the UI already shows a
            // last-good value, so don't crash on top of it.
            assertNull(
                BtcRateHistoryClient.rateAt(
                    dateUnix = 1_700_000_000L,
                    currency = "EUR",
                ),
            )
        }
}
