package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * UX review 2026-10 (item 17) — column priority for dense tables, so
 * every table keeps its 2–3 essential columns on a narrow window or a
 * phone (M3 canonical layouts; Kubera-style sheets) instead of clipping.
 *
 * Generalised from the Portfolio sheet (`SheetColumn`): each column
 * declares the minimum width it needs and a [hideRank] — higher ranks
 * give way first, rank 0 never hides. [visibleColumns] drops columns by
 * descending rank until the minimum widths (plus gaps) fit.
 *
 * Every dense table hides columns at runtime: it measures its width
 * (`BoxWithConstraints`), asks its [TableColumnLayout] for the visible
 * columns and renders header and rows with only those. The Portfolio
 * sheet (`SheetColumn`) and the wallet transactions table (`TxCol`)
 * predate [TableColumnLayout]; the other tables register theirs in
 * `TableColumnPriorities`, and a test pins that each one fits a 360dp
 * phone with at most three columns.
 */
public interface PrioritizedColumn {
    public val minWidthDp: Int
    public val hideRank: Int
}

/** Columns of [all] (in display order) that fit [availableWidthDp]. */
public fun <C : PrioritizedColumn> visibleColumns(
    all: List<C>,
    availableWidthDp: Int,
    gapDp: Int = 8,
): List<C> {
    val visible = all.toMutableList()
    fun needed() = visible.sumOf { it.minWidthDp } + gapDp * (visible.size - 1).coerceAtLeast(0)
    while (needed() > availableWidthDp) {
        val drop = visible.filter { it.hideRank > 0 }.maxByOrNull { it.hideRank } ?: break
        visible.remove(drop)
    }
    return visible
}

/**
 * A column that also says how it takes space in a row: a share of the
 * free width ([weight]) or, when [weight] is null, exactly [minWidthDp].
 */
public interface TableColumn : PrioritizedColumn {
    public val weight: Float?
}

/** Width modifier of [column] inside a table row ([scope]). */
public fun Modifier.tableCell(scope: RowScope, column: TableColumn): Modifier = with(scope) {
    val w = column.weight
    if (w != null) this@tableCell.weight(w) else this@tableCell.width(column.minWidthDp.dp)
}

/**
 * The columns of one table plus the width its rows spend on anything
 * that is not a column ([chromeDp]: row padding, borders, fixed action
 * slots such as a checkbox or a "Show" link), so [visible] can be fed
 * the table's measured width directly. [id] is a stable identifier,
 * not a UI label.
 */
public class TableColumnLayout<C : PrioritizedColumn>(
    public val id: String,
    public val columns: List<C>,
    public val chromeDp: Int,
    public val gapDp: Int = 0,
) {
    /** Columns (display order) that fit a table [widthDp] wide. */
    public fun visible(widthDp: Int): List<C> = visibleColumns(columns, widthDp - chromeDp, gapDp)

    /** Test tag of [column]'s header cell. */
    public fun headerTag(column: C): String = "table.$id.header.${column.toString().lowercase()}"

    /** Narrowest table that still shows every rank-0 column without clipping. */
    public val floorDp: Int
        get() {
            val kept = columns.filter { it.hideRank == 0 }
            return chromeDp + kept.sumOf { it.minWidthDp } + gapDp * (kept.size - 1).coerceAtLeast(0)
        }
}
