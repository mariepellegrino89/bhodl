package io.bhodl.desktop.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7.1.1 — pure-helper tests for the new Kubera-style Investments
 * table. The full table requires a Compose composition rule (not
 * available in `jvmTest`), so we test the deterministic helpers that
 * drive how each row is rendered:
 *  - [formatPnlPercent] — P&L cell text contract
 *  - [assetTypeIndex] — asset-type → palette-index mapping (the badge
 *    color picker reads from the same lookup, so pinning the index is
 *    enough to guarantee colour stability across refactors)
 *  - [formatQuantity] — trailing-zero handling
 */
class InvestmentsCardTest {

    // ─── formatPnlPercent ───────────────────────────────────────────

    @Test
    fun `formatPnlPercent renders dash for unknown P&L`() {
        assertEquals("—", formatPnlPercent(null))
    }

    @Test
    fun `formatPnlPercent renders flat zero with equals sign`() {
        assertEquals("= 0%", formatPnlPercent(0))
    }

    @Test
    fun `formatPnlPercent renders positive with up arrow and plus sign`() {
        assertEquals("↑ +5%", formatPnlPercent(5))
        assertEquals("↑ +123%", formatPnlPercent(123))
    }

    @Test
    fun `formatPnlPercent renders negative with down arrow and embedded minus`() {
        // Negative numbers carry their own minus when toString'd, so we
        // intentionally don't prepend another sign — guard against a
        // future refactor that would produce "↓ --5%".
        assertEquals("↓ -5%", formatPnlPercent(-5))
        assertEquals("↓ -42%", formatPnlPercent(-42))
    }

    // ─── assetTypeIndex ─────────────────────────────────────────────
    //
    // The badge color is picked from BhodlTheme.colors.chartCategorical
    // by index. Pinning the index here means a palette reorder would
    // need an explicit test update — the alternative (reading the
    // resolved Color directly) would require a composition runtime,
    // which jvmTest doesn't have.

    @Test
    fun `assetTypeIndex resolves the 6 known asset classes to distinct slots`() {
        val known = listOf("stock", "etf", "fund", "currency", "commodity", "manual")
        val indices = known.map { assetTypeIndex(it).first }.toSet()
        assertEquals(known.size, indices.size, "known types must map to distinct palette slots")
        // Every known type marks the second flag true.
        known.forEach { assertTrue(assetTypeIndex(it).second, "$it should be marked known") }
    }

    @Test
    fun `assetTypeIndex is case insensitive`() {
        assertEquals(assetTypeIndex("stock"), assetTypeIndex("Stock"))
        assertEquals(assetTypeIndex("etf"), assetTypeIndex("ETF"))
        assertEquals(assetTypeIndex("manual"), assetTypeIndex("MANUAL"))
    }

    @Test
    fun `assetTypeIndex falls back to neutral slot for unknown types`() {
        val (idx, known) = assetTypeIndex("nft")
        assertEquals(7, idx, "unknown types should fall back to the neutral grey slot")
        assertFalse(known)
    }

    // ─── formatQuantity ─────────────────────────────────────────────

    @Test
    fun `formatQuantity drops trailing zeros on integer quantities`() {
        assertEquals("100", formatQuantity(100.0))
        assertEquals("1", formatQuantity(1.0))
        assertEquals("0", formatQuantity(0.0))
    }

    @Test
    fun `formatQuantity preserves significant fractional digits`() {
        assertEquals("0.5", formatQuantity(0.5))
        assertEquals("1.25", formatQuantity(1.25))
        // The implementation prints up to 4 decimals then trims zeros —
        // 0.1234 round-trips clean; 0.12345 rounds (toward nearest) at
        // the 4th place — pin the behaviour so a future switch to
        // BigDecimal is intentional.
        assertEquals("0.1234", formatQuantity(0.1234))
    }

    @Test
    fun `formatQuantity drops decimal point when fractional part is zero`() {
        // 100.0000 → "100", not "100." (the second trimEnd strips the
        // dangling dot once the zeros are gone).
        assertEquals("100", formatQuantity(100.0000))
    }
}
