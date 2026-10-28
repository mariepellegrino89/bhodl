package io.bhodl.desktop.screens

import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Classic categories stored in one language are recognised in every one. */
class CategoryNamesTest {

    @Test
    fun `a classic category is recognised by its label in either language only`() {
        assertEquals(presetCategoryNamed("Food"), presetCategoryNamed(" cibo "))
        assertEquals(presetCategoryNamed("Restaurants"), presetCategoryNamed("Ristoranti"))
        // Aliases are for matching a search, not for renaming the user's own category.
        assertNull(presetCategoryNamed("Affitto"))
        assertNull(presetCategoryNamed("Freelance"))
        assertNull(presetCategoryNamed(null))
    }

    @Test
    fun `a picked classic category keeps the spelling the data already uses`() {
        val known = listOf("Cibo", "Freelance")
        assertEquals("Cibo", storedCategoryName("Food", known), "budget and totals still match")
        assertEquals("Transport", storedCategoryName("Transport", known), "first use: as picked")
        assertEquals("Gym", storedCategoryName("Gym", known))
    }

    @Test
    fun `a search finds a classic category by its name in either language`() {
        fun ev(id: String, cat: String?) =
            PaymentEvent("w", id, PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, 1_000UL, null, 1UL, null, cat, null)
        val events = listOf(ev("a", "Cibo"), ev("b", "Food"), ev("c", "Transport"), ev("d", null))
        val label = { _: String -> "Main" }
        assertEquals(listOf("a", "b"), searchLedger(events, "food", label).map { it.txidOrPaymentId })
        assertEquals(listOf("a", "b"), searchLedger(events, "CIBO", label).map { it.txidOrPaymentId })
        assertEquals(listOf("c"), searchLedger(events, "traspor", label).map { it.txidOrPaymentId })
    }
}
