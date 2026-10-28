package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Area C — chart drill-downs: a category tile hands its category to the
 * Transactions filter ("" for Uncategorised), a bucket chip its range.
 */
@OptIn(ExperimentalTestApi::class)
class UcDrillDownTrackerTest {

    @BeforeTest
    fun setUp() = SettingsStore.reset()

    @AfterTest
    fun tearDown() = SettingsStore.reset()

    @Test
    fun `TRK-135 a treemap tile drills down with its category, blank for Uncategorised`() = runDesktopComposeUiTest {
        val clicked = mutableListOf<String>()
        val slices = listOf(
            CategorySlice("Food", 0UL, 60_000UL, 3),
            CategorySlice("", 0UL, 40_000UL, 2),
            CategorySlice("Zero", 0UL, 0UL, 1),
        )
        setContent {
            BhodlTheme {
                androidx.compose.foundation.layout.Box(Modifier.width(600.dp).height(300.dp)) {
                    CategoryTreemap(slices, onTileClick = { clicked += it })
                }
            }
        }
        waitUntilAtLeastOneExists(hasText("Food"))
        assertEquals(0, onAllNodes(hasText("Zero")).fetchSemanticsNodes().size)
        onNodeWithText("Food").performClick()
        onNodeWithText("Uncategorised").performClick()
        assertEquals(listOf("Food", ""), clicked)
    }

    @Test
    fun `TRK-135 a bucket chip drills down with its range, empty buckets do nothing`() = runDesktopComposeUiTest {
        val saved = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val day = 86_400L
            val start = 1_767_225_600L // 2026-01-01T00:00Z
            val bars = listOf(
                FlowBar(start, 5_000UL, 0UL),
                FlowBar(start + day, 0UL, 0UL),
                FlowBar(start + 2 * day, 0UL, 700UL),
            )
            val picked = mutableListOf<Pair<Long, Long>>()
            setContent { BhodlTheme { BucketDrillRow(bars, onSelect = { picked += it }) } }
            waitUntilAtLeastOneExists(hasText("01/01"))
            onNodeWithText("01/01").performClick()
            onNodeWithText("02/01").performClick()
            onNodeWithText("03/01").performClick()
            assertEquals(2, picked.size)
            assertEquals(start to start + day - 1, picked[0])
            assertEquals(start + 2 * day, picked[1].first)
            assertTrue(picked[1].second >= System.currentTimeMillis() / 1000L - 5)
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }
}
