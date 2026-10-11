package io.bhodl.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** UX review 2026-10 (B11) — addresses in blocks of four, never truncated. */
class AddressFormatTest {

    @Test
    fun `groups a full address in blocks of four keeping both ends`() {
        val address = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
        val grouped = groupAddressInFours(address)!!
        assertEquals("tb1q w508 d6qe jxtd g4y5 r3za rvar y0c5 xw7k xpjz sx", grouped)
        assertEquals(address, grouped.replace(" ", ""))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("bc1q abcd efgh ijkl", groupAddressInFours("  bc1qabcdefghijkl \n"))
    }

    @Test
    fun `non-address input is not grouped`() {
        assertNull(groupAddressInFours(""))
        assertNull(groupAddressInFours("tb1q"))
        assertNull(groupAddressInFours("bitcoin:tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertNull(groupAddressInFours("tb1qw508d6qe jxtdg4y5r3zarvary"))
    }
}
