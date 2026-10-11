package io.bhodl.desktop.screens

import io.bhodl.ffi.CpfpPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class CpfpPackageRowsTest {
    private val labels = CpfpRowLabels(
        parentFee = "Parent fee",
        ancestors = "Unconfirmed ancestors",
        childFee = "Child fee",
        packageRate = "Package rate",
        target = "target",
    )
    private val fmt: (Long) -> String = { sat ->
        io.bhodl.desktop.ui.money.BitcoinAmountFormat.format(
            sat, io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT, locale = java.util.Locale.US,
        )
    }

    private fun plan(ancestorCount: UInt, ancestorsFee: ULong, ancestorsVsize: ULong) = CpfpPlan(
        psbt = "p",
        parentTxid = "t",
        parentFeeSat = 200uL,
        parentVsize = 200uL,
        ancestorCount = ancestorCount,
        ancestorsFeeSat = ancestorsFee,
        ancestorsVsize = ancestorsVsize,
        childFeeSat = 3_640uL,
        childVsize = 120uL,
        targetFeeRateSatVb = 12uL,
    )

    @Test
    fun `no ancestors keeps the three-row layout`() {
        val rows = cpfpPackageRows(plan(0u, 0uL, 0uL), fmt, labels).toMap()
        assertEquals(setOf("Parent fee", "Child fee", "Package rate"), rows.keys)
        assertEquals("12.0 sat/vB (target 12 sat/vB)", rows.getValue("Package rate"))
    }

    @Test
    fun `ancestors are listed and counted in the package rate`() {
        // (200 + 100 + 3640) / (200 + 100 + 120) = 9.38 -> 9.3 sat/vB
        val rows = cpfpPackageRows(plan(1u, 100uL, 100uL), fmt, labels).toMap()
        assertEquals("1 tx · 100 sat · 100 vB (1.0 sat/vB)", rows.getValue("Unconfirmed ancestors"))
        assertEquals("9.3 sat/vB (target 12 sat/vB)", rows.getValue("Package rate"))
    }
}
