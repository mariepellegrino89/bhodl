package io.bhodl.desktop.wizard

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Property tests for [`FeeRateClassifier`].
 *
 * The classifier is a thin function but it sits on the SendScreen and
 * BumpFee/CPFP advisory paths, so a regression here would surface as
 * either spurious red banners (annoying) or NO banner where the user
 * deserves one (dangerous typo). We pin down the boundary semantics
 * exhaustively + sample the broad range with a seeded RNG.
 */
class FeeRateClassifierPropertyTest {

    @Test
    fun `every rate below HIGH_THRESHOLD is Normal`() {
        for (rate in 0L..(FeeRateClassifier.HIGH_THRESHOLD_SAT_VB - 1L)) {
            val level = FeeRateClassifier.classify(rate)
            assertEquals(
                FeeRateClassifier.Level.Normal,
                level,
                "rate $rate should be Normal but was $level",
            )
            assertNull(
                FeeRateClassifier.advisory(rate),
                "Normal rate $rate must produce no advisory",
            )
        }
    }

    @Test
    fun `every rate in High band is reported as High`() {
        for (rate in FeeRateClassifier.HIGH_THRESHOLD_SAT_VB
            ..(FeeRateClassifier.EXTREME_THRESHOLD_SAT_VB - 1L)) {
            assertEquals(FeeRateClassifier.Level.High, FeeRateClassifier.classify(rate))
            val advisory = assertNotNull(
                FeeRateClassifier.advisory(rate),
                "High rate $rate must produce an advisory",
            )
            assertTrue(
                advisory.contains("$rate sat/vB"),
                "advisory should embed the rate; got '$advisory'",
            )
            assertTrue(
                advisory.contains("higher than typical"),
                "High advisory must call out the band; got '$advisory'",
            )
        }
    }

    @Test
    fun `boundary at HIGH_THRESHOLD flips Normal to High`() {
        assertEquals(
            FeeRateClassifier.Level.Normal,
            FeeRateClassifier.classify(FeeRateClassifier.HIGH_THRESHOLD_SAT_VB - 1L),
        )
        assertEquals(
            FeeRateClassifier.Level.High,
            FeeRateClassifier.classify(FeeRateClassifier.HIGH_THRESHOLD_SAT_VB),
        )
    }

    @Test
    fun `boundary at EXTREME_THRESHOLD flips High to Extreme`() {
        assertEquals(
            FeeRateClassifier.Level.High,
            FeeRateClassifier.classify(FeeRateClassifier.EXTREME_THRESHOLD_SAT_VB - 1L),
        )
        assertEquals(
            FeeRateClassifier.Level.Extreme,
            FeeRateClassifier.classify(FeeRateClassifier.EXTREME_THRESHOLD_SAT_VB),
        )
    }

    @Test
    fun `random extreme range stays Extreme up to a generous ceiling`() {
        val rng = Random(seed = 0xFEE_DEC_ADE)
        repeat(500) {
            val rate = rng.nextLong(
                FeeRateClassifier.EXTREME_THRESHOLD_SAT_VB,
                100_000L, // sanity cap; classifier accepts any positive Long
            )
            assertEquals(
                FeeRateClassifier.Level.Extreme,
                FeeRateClassifier.classify(rate),
                "rate $rate should be Extreme",
            )
            val advisory = assertNotNull(FeeRateClassifier.advisory(rate))
            assertTrue(
                advisory.contains("extreme", ignoreCase = true),
                "Extreme advisory must call out the band; got '$advisory'",
            )
        }
    }

    @Test
    fun `negative input is treated as Normal — no upstream callers feed negatives but defensive`() {
        // The Long type allows negatives even though the wallet never
        // produces them (sat/vB is always positive). Pin the behaviour
        // so a future refactor doesn't accidentally switch to UInt and
        // alter advisory semantics for "0".
        for (rate in listOf(-1L, -100L, Long.MIN_VALUE)) {
            assertEquals(FeeRateClassifier.Level.Normal, FeeRateClassifier.classify(rate))
            assertNull(FeeRateClassifier.advisory(rate))
        }
    }

    @Test
    fun `Long_MAX_VALUE classifies as Extreme`() {
        assertEquals(
            FeeRateClassifier.Level.Extreme,
            FeeRateClassifier.classify(Long.MAX_VALUE),
        )
    }
}
