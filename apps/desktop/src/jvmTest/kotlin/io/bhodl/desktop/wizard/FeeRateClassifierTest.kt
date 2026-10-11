package io.bhodl.desktop.wizard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Unit tests for [`FeeRateClassifier`]. */
class FeeRateClassifierTest {

    @Test
    fun `rates below 100 sat per vB are Normal`() {
        assertEquals(FeeRateClassifier.Level.Normal, FeeRateClassifier.classify(1))
        assertEquals(FeeRateClassifier.Level.Normal, FeeRateClassifier.classify(50))
        assertEquals(FeeRateClassifier.Level.Normal, FeeRateClassifier.classify(99))
    }

    @Test
    fun `rates from 100 to 499 sat per vB are High`() {
        assertEquals(FeeRateClassifier.Level.High, FeeRateClassifier.classify(100))
        assertEquals(FeeRateClassifier.Level.High, FeeRateClassifier.classify(250))
        assertEquals(FeeRateClassifier.Level.High, FeeRateClassifier.classify(499))
    }

    @Test
    fun `rates from 500 sat per vB up are Extreme`() {
        assertEquals(FeeRateClassifier.Level.Extreme, FeeRateClassifier.classify(500))
        assertEquals(FeeRateClassifier.Level.Extreme, FeeRateClassifier.classify(10_000))
    }

    @Test
    fun `advisory is null for Normal rates`() {
        assertNull(FeeRateClassifier.advisory(50))
    }

    @Test
    fun `advisory mentions the rate for High and Extreme`() {
        val highAdvisory = assertNotNull(FeeRateClassifier.advisory(150))
        assert(highAdvisory.contains("150 sat/vB")) {
            "advisory must mention the rate verbatim, got '$highAdvisory'"
        }
        val extremeAdvisory = assertNotNull(FeeRateClassifier.advisory(2_000))
        assert(extremeAdvisory.contains("2000 sat/vB")) {
            "extreme advisory must mention the rate verbatim, got '$extremeAdvisory'"
        }
    }

    @Test
    fun `thresholds match the OWASP-style 100 and 500 marks`() {
        assertEquals(100L, FeeRateClassifier.HIGH_THRESHOLD_SAT_VB)
        assertEquals(500L, FeeRateClassifier.EXTREME_THRESHOLD_SAT_VB)
    }
}
