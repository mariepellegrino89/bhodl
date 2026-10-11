package io.bhodl.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.UNCATEGORISED_KEY
import io.bhodl.desktop.ui.categoryDisplayName
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_lollipop_empty
import org.jetbrains.compose.resources.stringResource
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ui.theme.BhodlTheme

/**
 * Phase 4.7 — lollipop chart for "Expense by category".
 *
 * Variant of the donut/treemap that scales better when the period has
 * many small categories. Each row: category label on the left, a thin
 * horizontal "stem" extending rightward proportional to the outflow,
 * a colored circle ("candy") at the tip, and the amount text after.
 *
 * Click the row to drill down to History filtered by that category.
 * The biggest outflow anchors the top.
 *
 * Compose Canvas implementation — no Koalaplot dependency for this
 * one (Koalaplot has no native lollipop primitive and the layout is
 * a 3-cell Row per category, simpler than wrapping a custom plot).
 */
@Composable
internal fun ExpenseLollipopChart(
    events: List<PaymentEvent>,
    onCategoryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = BhodlTheme.colors.chartCategorical
    val rows = remember(events) { buildExpenseRows(events) }
    if (rows.isEmpty()) {
        Text(
            text = stringResource(Res.string.trk_lollipop_empty),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        return
    }
    val maxAmount = rows.maxOf { it.outflowSat }.toFloat().coerceAtLeast(1f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        rows.forEachIndexed { i, r ->
            LollipopRow(
                label = categoryDisplayName(r.category),
                amountSat = r.outflowSat,
                ratio = (r.outflowSat.toFloat() / maxAmount).coerceIn(0f, 1f),
                color = palette[i % palette.size],
                onClick = { onCategoryClick(r.category) },
            )
        }
    }
}

@Composable
private fun LollipopRow(
    label: String,
    amountSat: ULong,
    ratio: Float,
    color: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label column — left third, truncated for long names.
        Text(
            text = if (label.length > 18) label.take(17) + "…" else label,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier
                .padding(end = 8.dp)
                .background(BhodlTheme.colors.surface)
                .fillMaxWidth(0.3f),
        )
        // Stem + candy in the middle.
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(20.dp),
        ) {
            val w = size.width
            val h = size.height
            val candyR = 6f
            val stemEndX = (w * ratio).coerceIn(candyR + 2f, w - candyR)
            val centerY = h / 2f
            // Stem.
            drawLine(
                color = color.copy(alpha = 0.5f),
                start = Offset(0f, centerY),
                end = Offset(stemEndX, centerY),
                strokeWidth = 2.5f,
            )
            // Candy.
            drawCircle(
                color = color,
                radius = candyR,
                center = Offset(stemEndX, centerY),
            )
        }
        // Amount label — right edge.
        Text(
            text = "−${displayAmount(amountSat)}",
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.financialNegative,
            modifier = Modifier
                .padding(start = 8.dp)
                .fillMaxWidth(0.25f),
        )
    }
}

/** One row in the lollipop chart — a category and its net outflow. */
private data class LollipopRowData(
    val category: String,
    val outflowSat: ULong,
)

/**
 * Bucket events into per-category rows for the expense chart. Each
 * category's `outflowSat` is `sum(outgoing) − sum(incoming)` floored
 * at zero — i.e. only categories where the user spent MORE than they
 * received in the window appear, sorted by net outflow desc.
 */
private fun buildExpenseRows(events: List<PaymentEvent>): List<LollipopRowData> {
    if (events.isEmpty()) return emptyList()
    data class Acc(var inSat: ULong = 0UL, var outSat: ULong = 0UL)
    val acc = HashMap<String, Acc>()
    for (e in events) {
        val key = e.category?.takeIf { it.isNotBlank() } ?: UNCATEGORISED_KEY
        val a = acc.getOrPut(key) { Acc() }
        // Internal transfers (send_to_self) only contribute their fee.
        a.inSat += e.externalInflowSat()
        a.outSat += e.externalOutflowSat()
    }
    return acc
        .mapNotNull { (cat, a) ->
            if (a.outSat <= a.inSat) null
            else LollipopRowData(cat, a.outSat - a.inSat)
        }
        .sortedByDescending { it.outflowSat }
}
