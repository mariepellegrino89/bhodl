package io.bhodl.desktop.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlDensity

/**
 * UX review 2026-10 (item 15) — row heights for table rows that stack
 * more than one text line (e.g. a `Money` cell with its fiat line under
 * the BTC line), derived from the user's density so EVERY dense table
 * follows Settings › Density:
 *
 *  - 1 line: [BhodlDensity.rowHeight] (Compact 32dp / Ultra-dense 24dp);
 *  - each extra line adds [EXTRA_LINE_DP] (Compact 2 lines = 46dp,
 *    Ultra-dense = 38dp).
 *
 * Callers use it as a MINIMUM (`heightIn(min = …)`) so a long label or
 * a large font never clips.
 */
public const val EXTRA_LINE_DP: Int = 14

public fun BhodlDensity.rowHeightFor(lines: Int): Dp =
    rowHeight + (EXTRA_LINE_DP * (lines - 1).coerceAtLeast(0)).dp
