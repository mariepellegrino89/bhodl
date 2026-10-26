package io.bhodl.desktop.screens

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
}
