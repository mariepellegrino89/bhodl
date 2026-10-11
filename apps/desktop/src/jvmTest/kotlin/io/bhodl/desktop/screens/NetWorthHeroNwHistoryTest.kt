package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.NetWorthHistoryClient.NwSnapshot
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 8.4 — coverage for the reconstructed NW-in-sat history path
 * in [NetWorthHero].
 *
 * Three scenarios:
 *  1. `sparkline primary uses reconstructed sat history when available`
 *     — when `nwHistory` is non-empty, the sparkline still renders
 *     (testTag present) AND the BTC delta row displays a non-zero
 *     figure derived from `first.totalSat` vs `last.totalSat`.
 *  2. `falls back to flat line when reconstruction returns empty`
 *     — when `nwHistory` is empty but a fiat history exists, the
 *     hero still renders the sparkline (using the 8.3.2 flat fallback)
 *     and the BTC delta is the 0 / 0.00% placeholder.
 *  3. `delta BTC primary computed from first and last snapshot when
 *     history non-empty` — exercises the pure helper
 *     [computeNwBtcDelta] across edge cases (positive, negative,
 *     zero-base, single point, empty) plus an integration check that
 *     the DELTA_BTC node text reflects the helper's output.
 */
@OptIn(ExperimentalTestApi::class)
class NetWorthHeroNwHistoryTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        PriceCache.seedForTest(price = 60_000.0, lastFetchUnix = nowSec())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /** Convenience constructor — only the fields we care about for
     *  delta math; the rest are zeroed.  */
    private fun snap(dateUnix: Long, totalSat: Long): NwSnapshot =
        NwSnapshot(
            dateUnix = dateUnix,
            totalSat = totalSat,
            onchainSat = totalSat,
            investmentsSat = 0L,
            manualAssetsSat = 0L,
            liabilitiesSat = 0L,
            fiatEquivalent = totalSat.toDouble() / 1e8 * 60_000.0,
            rateBtcFiat = 60_000.0,
        )

    private val historyFiat: List<Pair<Long, Double>> = listOf(
        1_000L to 50_000.0,
        2_000L to 55_000.0,
    )

    /** Net worth grew from 0.9 BTC to 1.0 BTC over the period. */
    private val nwGrew: List<NwSnapshot> = listOf(
        snap(1_000L, 90_000_000L),  // 0.9 BTC
        snap(1_500L, 95_000_000L),  // 0.95 BTC
        snap(2_000L, 100_000_000L), // 1.0 BTC
    )

    // ---------------------------------------------------------------------
    // Test 1 — sparkline primary uses reconstructed sat history when available
    // ---------------------------------------------------------------------

    @Test
    fun `sparkline primary uses reconstructed sat history when available`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    NetWorthHero(
                        currentSat = 100_000_000L,
                        historySatFiat = historyFiat,
                        fiatCurrency = FiatCurrency.EUR,
                        period = NetWorthPeriod.D90,
                        onPeriodChange = {},
                        nwHistory = nwGrew,
                    )
                }
            }
            // Sparkline node still rendered.
            waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.SPARKLINE))
            onNodeWithTag(NetWorthHeroTags.SPARKLINE).assertIsDisplayed()

            // BTC delta row shows a NON-ZERO figure: +10_000_000 sat
            // (+11.11%) computed from first=90M to last=100M.
            waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.DELTA_BTC))
            onNodeWithTag(NetWorthHeroTags.DELTA_BTC).assertIsDisplayed()
            // The delta text contains "+10,000,000 sat" — the
            // placeholder (Phase 8.3.2) would have been "±0 sat".
            // formatBtcLike uses literal "," grouping (locale-independent)
            // so this assertion is stable across CI locales.
            waitUntilAtLeastOneExists(hasText("+10,000,000 sat", substring = true))
        }

    // ---------------------------------------------------------------------
    // Test 2 — falls back to flat line when reconstruction returns empty
    // ---------------------------------------------------------------------

    @Test
    fun `falls back to flat line when reconstruction returns empty`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    NetWorthHero(
                        currentSat = 100_000_000L,
                        historySatFiat = historyFiat,
                        fiatCurrency = FiatCurrency.EUR,
                        period = NetWorthPeriod.D90,
                        onPeriodChange = {},
                        nwHistory = emptyList(), // <-- empty, FFI failed / fresh DB
                    )
                }
            }
            // Sparkline is still present (uses the flat fallback built
            // from the fiat history x-range).
            waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.SPARKLINE))
            onNodeWithTag(NetWorthHeroTags.SPARKLINE).assertIsDisplayed()

            // BTC delta is the 0/0% placeholder (matches Phase 8.3.2
            // behavior). The placeholder formats as "±0 sat" — the
            // sign-symbol "±" pinpoints the deltaSat==0 branch in
            // DeltaRow, distinct from "+" / "-" used for non-zero
            // deltas. formatBtcLike uses literal "," grouping so this
            // assertion is locale-independent.
            waitUntilAtLeastOneExists(hasTestTag(NetWorthHeroTags.DELTA_BTC))
            waitUntilAtLeastOneExists(hasText("±0 sat", substring = true))
        }

    // ---------------------------------------------------------------------
    // Test 3 — delta BTC primary computed from first and last snapshot
    // ---------------------------------------------------------------------

    @Test
    fun `delta BTC primary computed from first and last snapshot when history non-empty`() {
        // Positive growth: 0.9 → 1.0 BTC.
        val growDelta = computeNwBtcDelta(nwGrew)
        assertEquals(10_000_000L, growDelta.deltaSat, "deltaSat = last - first = 100M - 90M")
        // 10M / 90M * 100 = 11.111...
        assertTrue(
            growDelta.deltaPct in 11.10..11.12,
            "deltaPct ≈ +11.11%, got ${growDelta.deltaPct}",
        )

        // Negative drift: 1.0 → 0.8 BTC.
        val drift = listOf(
            snap(1_000L, 100_000_000L),
            snap(2_000L, 80_000_000L),
        )
        val driftDelta = computeNwBtcDelta(drift)
        assertEquals(-20_000_000L, driftDelta.deltaSat)
        assertEquals(-20.0, driftDelta.deltaPct, 0.001)

        // Empty history → 0 / 0.0% placeholder.
        val empty = computeNwBtcDelta(emptyList())
        assertEquals(0L, empty.deltaSat)
        assertEquals(0.0, empty.deltaPct, 0.0)

        // Single point → 0 / 0.0% (no "delta" possible).
        val single = computeNwBtcDelta(listOf(snap(1_000L, 50_000_000L)))
        assertEquals(0L, single.deltaSat)
        assertEquals(0.0, single.deltaPct, 0.0)

        // Zero first → guarded against /0; deltaPct must be 0.0
        // (the sat value alone carries the change).
        val fromZero = listOf(
            snap(1_000L, 0L),
            snap(2_000L, 50_000_000L),
        )
        val fromZeroDelta = computeNwBtcDelta(fromZero)
        assertEquals(50_000_000L, fromZeroDelta.deltaSat)
        assertEquals(0.0, fromZeroDelta.deltaPct, 0.0)
    }
}
