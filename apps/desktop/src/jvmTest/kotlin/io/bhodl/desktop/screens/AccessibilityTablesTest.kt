package io.bhodl.desktop.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.WindowSizing
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.ChartDataTable
import io.bhodl.desktop.ui.ChartTableColumn
import io.bhodl.desktop.ui.ChartTableData
import io.bhodl.desktop.ui.ChartTableTags
import io.bhodl.desktop.ui.COMPACT_NAV_MAX_WIDTH_DP
import io.bhodl.desktop.ui.NavigationLayout
import io.bhodl.desktop.ui.navigationLayoutFor
import io.bhodl.desktop.ui.rowHeightFor
import io.bhodl.desktop.ui.tableRowFocusDirection
import io.bhodl.ui.theme.BhodlDensity
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (items 15 and 17) — chart ↔ table toggle,
 * semantics, density, keyboard rows, column priority, navigation
 * abstraction and window minimum.
 */
@OptIn(ExperimentalTestApi::class)
class AccessibilityTablesTest {

    @BeforeTest fun setup() = SettingsStore.reset()

    @AfterTest fun teardown() = SettingsStore.reset()

    private val titles = ChartColumnTitles(
        "Period", "Inflow", "Outflow", "Category", "Amount", "Share", "Date",
        "Balance", "Net worth", "From", "To", "Direction", "Incoming", "Outgoing",
    )
    private val fmt: (Long) -> String = { "$it sat" }

    @Test
    fun `table builders mirror the chart data`() {
        val share = shareTable(listOf("A" to 300L, "B" to 100L), titles, fmt)
        assertEquals(listOf("A", "300 sat", "75%"), share.rows[0])
        assertEquals(listOf("B", "100 sat", "25%"), share.rows[1])

        val flows = inflowOutflowTable(listOf(Triple("Jan", 5L, 2L)), titles, fmt)
        assertEquals(listOf("Jan", "5 sat", "2 sat"), flows.rows.single())
        assertTrue(flows.columns[1].numeric && !flows.columns[0].numeric)

        val sankey = sankeyTable(
            SankeyFlow(listOf(SankeySegment("Salary", 10UL)), listOf(SankeySegment("Food", 4UL)), 6UL),
            "Total", "Saved", titles, fmt,
        )
        assertEquals(
            listOf(listOf("Salary", "Total", "10 sat"), listOf("Total", "Food", "4 sat"), listOf("Total", "Saved", "6 sat")),
            sankey.rows,
        )

        val series = seriesTable(listOf(0L to 1L), "Balance", titles, fmt)
        assertEquals(2, series.columns.size)
    }

    @Test
    fun `chart card toggle swaps the chart for its table`() = runDesktopComposeUiTest {
        val table = ChartTableData(listOf(ChartTableColumn("Category"), ChartTableColumn("Amount", true)), listOf(listOf("Food", "1 sat")))
        setContent {
            BhodlTheme {
                ChartCard(title = "By category", table = table) {
                    androidx.compose.material3.Text("chart-body")
                }
            }
        }
        // The chart surface carries an accessible description.
        onNode(SemanticsMatcher("chart description") { n ->
            n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .any { "By category" in it }
        }).assertExists()
        // The toggle is an icon button with an accessible name.
        onNode(
            hasTestTag(ChartTableTags.TOGGLE) and SemanticsMatcher("named") { n ->
                n.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.isNotEmpty()
            },
            useUnmergedTree = false,
        ).assertExists()
        onNodeWithTag(ChartTableTags.TOGGLE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ChartTableTags.TABLE))
        onNodeWithTag(ChartTableTags.row(0)).assertExists()
    }

    @Test
    fun `chart table rows follow the density setting`() = runDesktopComposeUiTest {
        val table = ChartTableData(listOf(ChartTableColumn("A")), listOf(listOf("x")))
        var density by androidx.compose.runtime.mutableStateOf(BhodlDensity.Compact)
        setContent { BhodlTheme(density = density) { ChartDataTable(table) } }
        onNodeWithTag(ChartTableTags.row(0)).assertHeightIsEqualTo(BhodlDensity.Compact.rowHeight)
        density = BhodlDensity.UltraDense
        waitForIdle()
        onNodeWithTag(ChartTableTags.row(0)).assertHeightIsEqualTo(BhodlDensity.UltraDense.rowHeight)
    }

    @Test
    fun `multi-line rows scale with density`() {
        assertEquals(BhodlDensity.Compact.rowHeight, BhodlDensity.Compact.rowHeightFor(1))
        assertEquals(BhodlDensity.Compact.rowHeight + 14.dp, BhodlDensity.Compact.rowHeightFor(2))
        assertTrue(BhodlDensity.UltraDense.rowHeightFor(2) < BhodlDensity.Compact.rowHeightFor(2))
    }

    @Test
    fun `arrow keys move row focus, other keys do not`() {
        assertEquals(FocusDirection.Down, tableRowFocusDirection(Key.DirectionDown, KeyEventType.KeyDown))
        assertEquals(FocusDirection.Up, tableRowFocusDirection(Key.DirectionUp, KeyEventType.KeyDown))
        assertNull(tableRowFocusDirection(Key.DirectionDown, KeyEventType.KeyUp))
        assertNull(tableRowFocusDirection(Key.Enter, KeyEventType.KeyDown))
    }

    @Test
    fun `every table keeps at most three columns on a 360dp phone`() {
        assertEquals(8, TableColumnPriorities.ALL.size)
        TableColumnPriorities.ALL.forEach { (name, table) ->
            val visible = table.visible(360)
            assertTrue(visible.size in 2..3, "$name keeps $visible at 360dp")
            // Rank-0 columns are never dropped...
            assertTrue(table.columns.filter { it.hideRank == 0 }.all { it in visible }, name)
            // ...and they fit 360dp with the row chrome, so nothing clips.
            assertTrue(table.floorDp <= 360, "$name needs ${table.floorDp}dp")
            // A wide window shows every column.
            assertEquals(table.columns, table.visible(2_000), name)
        }
        assertEquals(cols(SheetColumn.entries), visibleSheetColumns(10_000).map { it.name })
    }

    private fun cols(e: List<SheetColumn>) = e.map { it.name }

    @Test
    fun `transactions table drops confirmations then status then date`() {
        assertEquals(TxCol.entries.toSet(), visibleTxColumns(1_200))
        val narrow = visibleTxColumns(600)
        assertTrue(TxCol.LABEL in narrow && TxCol.AMOUNT in narrow)
        assertTrue(TxCol.CONFS !in narrow)
        assertEquals(setOf(TxCol.LABEL, TxCol.AMOUNT), visibleTxColumns(340))
    }

    @Test
    fun `navigation maps the rail to a bottom bar only on compact hosts`() {
        assertEquals(NavigationLayout.Rail, navigationLayoutFor(400, allowBottomBar = false))
        assertEquals(NavigationLayout.BottomBar, navigationLayoutFor(COMPACT_NAV_MAX_WIDTH_DP - 1, allowBottomBar = true))
        assertEquals(NavigationLayout.Rail, navigationLayoutFor(COMPACT_NAV_MAX_WIDTH_DP, allowBottomBar = true))
        // The desktop minimum is never compact.
        assertTrue(WindowSizing.MIN_WIDTH_DP > COMPACT_NAV_MAX_WIDTH_DP)
        assertTrue(WindowSizing.DEFAULT_WIDTH_DP >= WindowSizing.MIN_WIDTH_DP)
        assertTrue(WindowSizing.DEFAULT_HEIGHT_DP >= WindowSizing.MIN_HEIGHT_DP)
    }
}
