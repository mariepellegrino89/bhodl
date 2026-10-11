package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.chart_a11y_description
import io.bhodl.shared.resources.chart_show_chart
import io.bhodl.shared.resources.chart_show_table
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/**
 * UX review 2026-10 (item 15) — chart ↔ table toggle (NN/g: a table
 * view for every chart; WCAG: charts drawn on a Canvas are invisible to
 * screen readers and unreachable by keyboard).
 *
 * Every chart supplies a [ChartTableData] built by a pure builder (see
 * `screens/ChartTables.kt`) from the same data it draws, formatted
 * through the central amount formatters — so the table view is also
 * masked by the privacy mode.
 */
public data class ChartTableColumn(val title: String, val numeric: Boolean = false)

public data class ChartTableData(
    val columns: List<ChartTableColumn>,
    val rows: List<List<String>>,
)

/** The icon button that flips a chart to its table and back. */
@Composable
public fun ChartTableToggleButton(
    showTable: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(if (showTable) Res.string.chart_show_chart else Res.string.chart_show_table)
    IconButton(
        onClick = onToggle,
        modifier = modifier.size(32.dp).testTag(ChartTableTags.TOGGLE),
    ) {
        Icon(
            imageVector = if (showTable) Icons.Filled.ShowChart else Icons.Filled.TableChart,
            contentDescription = label,
            tint = BhodlTheme.colors.textSecondary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Accessible name for a chart surface: "<name> chart. Switch to the
 * table view to read the values." Applied to the Canvas / plot box.
 */
@Composable
public fun Modifier.chartSemantics(name: String): Modifier {
    val description = stringResource(Res.string.chart_a11y_description, name)
    return this.semantics { contentDescription = description }
}

/**
 * Dense, read-only data table for a chart. Rows use the theme density
 * ([BhodlTheme.density]) and are keyboard-focusable one by one, with a
 * focus ring distinct from any selection colour (WCAG 2.4.7).
 */
@Composable
public fun ChartDataTable(
    data: ChartTableData,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
            .testTag(ChartTableTags.TABLE),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BhodlTheme.colors.surfaceVariant)
                .height(BhodlTheme.density.rowHeight)
                .padding(horizontal = 8.dp)
                .semantics { heading() },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            data.columns.forEach { c ->
                Text(
                    text = c.title,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = if (c.numeric) TextAlign.End else TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        data.rows.forEachIndexed { i, row ->
            ChartDataRow(columns = data.columns, cells = row, zebra = i % 2 == 1, index = i)
        }
    }
}

@Composable
private fun ChartDataRow(
    columns: List<ChartTableColumn>,
    cells: List<String>,
    zebra: Boolean,
    index: Int,
) {
    // Screen readers read the whole row as one sentence ("Jan 26, 1,000 sat, 200 sat").
    val rowDescription = cells.joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (zebra) BhodlTheme.colors.rowZebra else Color.Transparent)
            .tableRowFocusable()
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }
            .height(BhodlTheme.density.rowHeight)
            .padding(horizontal = 8.dp)
            .testTag(ChartTableTags.row(index)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        columns.forEachIndexed { c, col ->
            Text(
                text = cells.getOrElse(c) { "" },
                style = if (col.numeric) BhodlTheme.typography.amountSmall else BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                textAlign = if (col.numeric) TextAlign.End else TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * UX review 2026-10 (item 15) — keyboard focus indicator for table
 * rows: a 2dp brand-colour outline, deliberately different from the
 * filled selection background so "focused" and "selected" never look
 * the same (WCAG 2.4.7 / 1.4.11).
 */
@Composable
public fun Modifier.focusRing(focused: Boolean): Modifier =
    if (focused) this.border(2.dp, BhodlTheme.colors.brandPrimary, RoundedCornerShape(2.dp)) else this

public object ChartTableTags {
    public const val TOGGLE: String = "chart.table-toggle"
    public const val TABLE: String = "chart.table"
    public fun row(i: Int): String = "chart.table.row.$i"
}
