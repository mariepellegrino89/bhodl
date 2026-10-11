package io.bhodl.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import io.bhodl.desktop.ui.UNCATEGORISED_KEY
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.rememberCompactAmountFormatter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_uncategorised
import io.bhodl.shared.resources.tracker_sankey_income
import io.bhodl.shared.resources.tracker_sankey_out_and_savings
import io.bhodl.shared.resources.tracker_sankey_savings
import io.bhodl.shared.resources.tracker_sankey_total
import org.jetbrains.compose.resources.stringResource
import io.bhodl.desktop.domain.externalInflowSat
import io.bhodl.desktop.domain.externalOutflowSat
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ui.theme.BhodlTheme

/**
 * Phase 3.12 — Sankey diagram for income → category cash flow.
 *
 * 3 columns: income categories (left) → Total (middle, brand-tinted)
 * → expense categories + Savings (right). Savings reads in
 * semantic-positive green to signal "money you kept" rather than
 * blending with the neutral middle.
 *
 * Layout reserves horizontal pad on both sides for category labels
 * (rendered OUTSIDE the node rectangles, anchored to the chart edges)
 * so a dominant single category never crowds out the smaller ones.
 * Labels on nodes that would render below the readable threshold
 * (~14px tall) are dropped — the colored bar still shows the
 * proportion; the user can hover for full precision (future).
 *
 * Rewritten 2026-05-16 after manual testing flagged the original draft as
 * visually broken: middle node was a gray wall with unreadable inline
 * total, right-column dominant categories washed out the smaller ones,
 * and labels overlapped at the canvas edges.
 */
@Composable
internal fun SankeyDiagram(
    flow: SankeyFlow,
    modifier: Modifier = Modifier,
) {
    if (flow.isEmpty) return
    val palette = BhodlTheme.colors.chartCategorical
    val middleColor = BhodlTheme.colors.brandPrimary
    val savedColor = BhodlTheme.colors.financialPositiveMuted
    val headerColor = BhodlTheme.colors.textSecondary
    val labelColor = BhodlTheme.colors.textPrimary
    val subLabelColor = BhodlTheme.colors.textTertiary
    val textMeasurer = rememberTextMeasurer()
    val headerStyle = BhodlTheme.typography.labelMedium.copy(color = headerColor)
    val labelStyle = BhodlTheme.typography.labelMedium.copy(color = labelColor)
    val subLabelStyle = BhodlTheme.typography.bodyMedium.copy(color = subLabelColor)
    val totalLabel = displayAmount(flow.totalIncome)
    val savedLabel = if (flow.savedSat > 0UL) displayAmount(flow.savedSat) else null
    // Node amounts use the compact form (they sit next to a 16px node);
    // the total keeps the full amount. Both follow the unit setting.
    val compact = rememberCompactAmountFormatter()
    val strings = SankeyStrings(
        income = stringResource(Res.string.tracker_sankey_income),
        outAndSavings = stringResource(Res.string.tracker_sankey_out_and_savings),
        savings = stringResource(Res.string.tracker_sankey_savings),
        total = stringResource(Res.string.tracker_sankey_total),
        short = { sat -> compact(sat.toLong()) },
    )
    val uncategorised = stringResource(Res.string.common_uncategorised)
    val shownFlow = remember(flow, uncategorised) { flow.withCategoryNames(uncategorised) }
    // Phase 3.12.c — dynamic height so 6+ categories don't get crammed
    // into 260dp. Each visible row needs ~30dp for label + amount
    // legibility; the floor stays at 280dp so a tiny 2-row chart still
    // reads as a chart rather than a strip.
    val rowCount = maxOf(
        flow.incomeCategories.size,
        flow.expenseCategories.size + if (savedLabel != null) 1 else 0,
    )
    val heightDp = maxOf(280, 60 + rowCount * 36)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(heightDp.dp),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(heightDp.dp)) {
            drawSankey(
                flow = shownFlow,
                palette = palette,
                middleColor = middleColor,
                savedColor = savedColor,
                textMeasurer = textMeasurer,
                headerStyle = headerStyle,
                labelStyle = labelStyle,
                subLabelStyle = subLabelStyle,
                strings = strings,
                totalLabel = totalLabel,
                savedLabel = savedLabel,
            )
        }
    }
}

/**
 * Bundled inputs for the [SankeyDiagram] composable. Computed in
 * [buildSankeyFlow] from the period-scoped event list.
 */
internal data class SankeyFlow(
    val incomeCategories: List<SankeySegment>,
    val expenseCategories: List<SankeySegment>,
    val savedSat: ULong,
) {
    val totalIncome: ULong get() = incomeCategories.sumOf { it.amountSat }
    val totalExpense: ULong get() = expenseCategories.sumOf { it.amountSat }
    val isEmpty: Boolean get() =
        incomeCategories.isEmpty() && expenseCategories.isEmpty()
}

/** Same flow with the internal "Uncategorised" bucket renamed for display. */
internal fun SankeyFlow.withCategoryNames(uncategorised: String): SankeyFlow {
    fun rename(list: List<SankeySegment>) =
        list.map { if (it.label == UNCATEGORISED_KEY) it.copy(label = uncategorised) else it }
    return copy(incomeCategories = rename(incomeCategories), expenseCategories = rename(expenseCategories))
}

/** Localized labels + compact amount formatter for the (non-composable) draw pass. */
internal class SankeyStrings(
    val income: String,
    val outAndSavings: String,
    val savings: String,
    val total: String,
    val short: (ULong) -> String,
)

/** One column entry in a Sankey diagram. */
internal data class SankeySegment(
    val label: String,
    val amountSat: ULong,
)

/**
 * Phase 3.12 — derive the Sankey flow from a period-scoped event list.
 *
 * Algorithm:
 *  - For each category, compute period inflow and outflow.
 *  - A category is "income" if inflow > outflow (net positive flow IN).
 *    It appears on the LEFT with value = inflow - outflow.
 *  - A category is "expense" if outflow > inflow. It appears on the
 *    RIGHT with value = outflow - inflow.
 *  - The leftover (totalIncome - totalExpense, floored at 0) is the
 *    "Savings" sink, rendered as the last right-column entry.
 *
 * Buckets are sorted by absolute volume so the largest blocks anchor
 * the top of each column.
 */
internal fun buildSankeyFlow(events: List<PaymentEvent>): SankeyFlow {
    if (events.isEmpty()) return SankeyFlow(emptyList(), emptyList(), 0UL)
    data class Acc(var inSat: ULong = 0UL, var outSat: ULong = 0UL)
    val acc = HashMap<String, Acc>()
    for (e in events) {
        val key = e.category?.takeIf { it.isNotBlank() } ?: UNCATEGORISED_KEY
        val a = acc.getOrPut(key) { Acc() }
        // Internal transfers (send_to_self) only contribute their fee.
        a.inSat += e.externalInflowSat()
        a.outSat += e.externalOutflowSat()
    }
    val income = mutableListOf<SankeySegment>()
    val expense = mutableListOf<SankeySegment>()
    for ((label, a) in acc) {
        when {
            a.inSat > a.outSat -> income += SankeySegment(label, a.inSat - a.outSat)
            a.outSat > a.inSat -> expense += SankeySegment(label, a.outSat - a.inSat)
            else -> Unit // perfectly balanced bucket — drop
        }
    }
    val sortedIncome = income.sortedByDescending { it.amountSat }
    val sortedExpense = expense.sortedByDescending { it.amountSat }
    val totalIncome = sortedIncome.sumOf { it.amountSat }
    val totalExpense = sortedExpense.sumOf { it.amountSat }
    val saved = if (totalIncome > totalExpense) totalIncome - totalExpense else 0UL
    return SankeyFlow(
        incomeCategories = sortedIncome,
        expenseCategories = sortedExpense,
        savedSat = saved,
    )
}

/** Layout primitive used by [drawSankey] for column nodes. */
private data class SankeyNodeBox(
    val xStart: Float,
    val yStart: Float,
    val height: Float,
    val color: Color,
    val label: String,
    val displayAmountText: String,
)

private enum class NodeLabelAnchor { LEFT_OF_NODE, ABOVE_AND_BELOW_NODE, RIGHT_OF_NODE }

private fun DrawScope.drawSankey(
    flow: SankeyFlow,
    palette: List<Color>,
    middleColor: Color,
    savedColor: Color,
    textMeasurer: TextMeasurer,
    headerStyle: TextStyle,
    labelStyle: TextStyle,
    subLabelStyle: TextStyle,
    strings: SankeyStrings,
    totalLabel: String,
    savedLabel: String?,
) {
    val w = size.width
    val h = size.height
    val nodeWidthPx = 16f
    val gapPx = 4f
    val minNodePx = 6f
    // Phase 3.12.d — dynamic label padding. Measure the actual width
    // of every label + amount string up-front, then reserve exactly
    // enough horizontal space on each side for the WIDEST one (plus a
    // 12px buffer). This is the fix for the "Uncategorised escapes
    // the left edge" bug found in manual testing: the previous 92px fixed pad was
    // narrower than the rendered "Uncategorised" label (~98px at
    // 12sp), so the right-aligned label clipped into negative X.
    val padBuffer = 12f
    val leftLabels = flow.incomeCategories.map { truncateLabel(it.label) }
    val leftAmounts = flow.incomeCategories.map { strings.short(it.amountSat) }
    val rightLabels = flow.expenseCategories.map { truncateLabel(it.label) } +
        (if (savedLabel != null) listOf(strings.savings) else emptyList())
    val rightAmounts = flow.expenseCategories.map { strings.short(it.amountSat) } +
        (if (savedLabel != null) listOf(strings.short(flow.savedSat)) else emptyList())
    val leftPad = computeLabelPad(
        labels = leftLabels, amounts = leftAmounts,
        labelStyle = labelStyle, subLabelStyle = subLabelStyle,
        textMeasurer = textMeasurer, buffer = padBuffer,
    )
    val rightPad = computeLabelPad(
        labels = rightLabels, amounts = rightAmounts,
        labelStyle = labelStyle, subLabelStyle = subLabelStyle,
        textMeasurer = textMeasurer, buffer = padBuffer,
    )
    val headerYBaseline = 14f
    // "Total" + amount stacked above the middle node: reserve their
    // measured height (B14 — a fixed 28px let the amount fall onto the
    // ribbons).
    val midLabelHeightPx =
        textMeasurer.measure(AnnotatedString(strings.total), labelStyle).size.height +
            textMeasurer.measure(AnnotatedString(totalLabel), subLabelStyle).size.height + 4f
    val chartTop = headerYBaseline + midLabelHeightPx
    val chartH = (h - chartTop).coerceAtLeast(40f)

    val leftX = leftPad
    val rightX = (w - rightPad - nodeWidthPx).coerceAtLeast(leftX + nodeWidthPx + 40f)
    val midX = (leftX + nodeWidthPx + rightX) / 2f - nodeWidthPx / 2f

    val totalGapsLeft = (flow.incomeCategories.size - 1).coerceAtLeast(0)
    val rightCount = flow.expenseCategories.size + (if (savedLabel != null) 1 else 0)
    val totalGapsRight = (rightCount - 1).coerceAtLeast(0)
    val availLeft = (chartH - totalGapsLeft * gapPx).coerceAtLeast(0f)
    val availRight = (chartH - totalGapsRight * gapPx).coerceAtLeast(0f)

    val leftValues = flow.incomeCategories.map { it.amountSat }
    val rightValues = flow.expenseCategories.map { it.amountSat } +
        (if (savedLabel != null) listOf(flow.savedSat) else emptyList())
    val leftHeights = computeNodeHeights(leftValues, availLeft, minNodePx)
    val rightHeights = computeNodeHeights(rightValues, availRight, minNodePx)

    // ── Column headers ─────────────────────────────────────────────
    drawColumnHeader(
        text = strings.income,
        textStyle = headerStyle,
        textMeasurer = textMeasurer,
        anchorX = leftX + nodeWidthPx / 2f,
        baselineY = headerYBaseline,
        anchor = NodeLabelAnchor.RIGHT_OF_NODE,
    )
    drawColumnHeader(
        text = strings.outAndSavings,
        textStyle = headerStyle,
        textMeasurer = textMeasurer,
        anchorX = rightX + nodeWidthPx / 2f,
        baselineY = headerYBaseline,
        anchor = NodeLabelAnchor.LEFT_OF_NODE,
    )

    val leftNodes = mutableListOf<SankeyNodeBox>()
    var cursorY = chartTop
    flow.incomeCategories.forEachIndexed { i, seg ->
        val nh = leftHeights[i]
        leftNodes += SankeyNodeBox(
            xStart = leftX, yStart = cursorY, height = nh,
            color = palette[i % palette.size],
            label = truncateLabel(seg.label),
            displayAmountText = strings.short(seg.amountSat),
        )
        cursorY += nh + gapPx
    }
    val rightNodes = mutableListOf<SankeyNodeBox>()
    cursorY = chartTop
    flow.expenseCategories.forEachIndexed { i, seg ->
        val nh = rightHeights[i]
        // Offset the palette index for the right column so income and
        // expense for the same category don't share a colour (e.g.
        // "Food" income vs "Food" expense would read as two unrelated
        // flows in the Sankey otherwise).
        rightNodes += SankeyNodeBox(
            xStart = rightX, yStart = cursorY, height = nh,
            color = palette[(i + 3) % palette.size],
            label = truncateLabel(seg.label),
            displayAmountText = strings.short(seg.amountSat),
        )
        cursorY += nh + gapPx
    }
    if (savedLabel != null) {
        val nh = rightHeights.last()
        rightNodes += SankeyNodeBox(
            xStart = rightX, yStart = cursorY, height = nh,
            // Phase 3.12.c — Savings uses semantic positive instead
            // of neutral grey: it's a good thing financially, and the
            // grey was blending into the middle node visually.
            color = savedColor,
            label = strings.savings,
            displayAmountText = strings.short(flow.savedSat),
        )
    }
    // Middle node: same total stacked height as either column (flow
    // conservation). Brand-tinted so it reads as "your money pool"
    // rather than a separator.
    val midStacked = maxOf(
        leftNodes.sumOf { it.height.toDouble() }.toFloat(),
        rightNodes.sumOf { it.height.toDouble() }.toFloat(),
    ).coerceAtLeast(minNodePx)

    // ── Ribbons ───────────────────────────────────────────────────
    var midLeftCursor = chartTop
    leftNodes.forEach { node ->
        val ribbonH = node.height
        drawSankeyRibbon(
            sourceTopLeft = Offset(node.xStart + nodeWidthPx, node.yStart),
            sourceHeight = ribbonH,
            targetTopLeft = Offset(midX, midLeftCursor),
            targetHeight = ribbonH,
            color = node.color.copy(alpha = 0.45f),
        )
        midLeftCursor += ribbonH
    }
    var midRightCursor = chartTop
    rightNodes.forEach { node ->
        val ribbonH = node.height
        drawSankeyRibbon(
            sourceTopLeft = Offset(midX + nodeWidthPx, midRightCursor),
            sourceHeight = ribbonH,
            targetTopLeft = Offset(node.xStart, node.yStart),
            targetHeight = ribbonH,
            color = node.color.copy(alpha = 0.45f),
        )
        midRightCursor += ribbonH
    }

    // ── Nodes ─────────────────────────────────────────────────────
    leftNodes.forEach {
        drawSankeyNode(
            node = it, nodeWidthPx = nodeWidthPx,
            textMeasurer = textMeasurer,
            labelStyle = labelStyle, subLabelStyle = subLabelStyle,
            anchor = NodeLabelAnchor.LEFT_OF_NODE,
        )
    }
    val midNode = SankeyNodeBox(
        xStart = midX, yStart = chartTop, height = midStacked,
        color = middleColor, label = strings.total, displayAmountText = totalLabel,
    )
    drawSankeyNode(
        node = midNode, nodeWidthPx = nodeWidthPx,
        textMeasurer = textMeasurer,
        labelStyle = labelStyle, subLabelStyle = subLabelStyle,
        anchor = NodeLabelAnchor.ABOVE_AND_BELOW_NODE,
        midLabelGapPx = midLabelHeightPx,
    )
    rightNodes.forEach {
        drawSankeyNode(
            node = it, nodeWidthPx = nodeWidthPx,
            textMeasurer = textMeasurer,
            labelStyle = labelStyle, subLabelStyle = subLabelStyle,
            anchor = NodeLabelAnchor.RIGHT_OF_NODE,
        )
    }
}

private fun DrawScope.drawColumnHeader(
    text: String,
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
    anchorX: Float,
    baselineY: Float,
    anchor: NodeLabelAnchor,
) {
    val layout = textMeasurer.measure(AnnotatedString(text), textStyle)
    val tx = when (anchor) {
        NodeLabelAnchor.LEFT_OF_NODE -> anchorX - layout.size.width
        NodeLabelAnchor.RIGHT_OF_NODE -> anchorX
        NodeLabelAnchor.ABOVE_AND_BELOW_NODE -> anchorX - layout.size.width / 2f
    }
    drawText(layout, topLeft = Offset(tx, baselineY - layout.size.height / 2f))
}

private fun DrawScope.drawSankeyNode(
    node: SankeyNodeBox,
    nodeWidthPx: Float,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    subLabelStyle: TextStyle,
    anchor: NodeLabelAnchor,
    midLabelGapPx: Float = 0f,
) {
    drawRect(
        color = node.color,
        topLeft = Offset(node.xStart, node.yStart),
        size = Size(nodeWidthPx, node.height),
    )
    // Phase 3.12.c — skip labels for nodes that render below the legible
    // threshold. The colored bar is still visible; the user can hover
    // for full precision (future enhancement).
    if (anchor != NodeLabelAnchor.ABOVE_AND_BELOW_NODE && node.height < 14f) return
    val labelLayout = textMeasurer.measure(AnnotatedString(node.label), labelStyle)
    val amountLayout = textMeasurer.measure(AnnotatedString(node.displayAmountText), subLabelStyle)
    val padding = 6f
    when (anchor) {
        NodeLabelAnchor.LEFT_OF_NODE -> {
            // Right-align label/amount to the LEFT edge of the node.
            // Two-line stack: label on top, amount underneath.
            val tx = node.xStart - padding
            val cy = node.yStart + node.height / 2f
            val labelX = tx - labelLayout.size.width
            val amountX = tx - amountLayout.size.width
            val labelY = (cy - labelLayout.size.height - 1f).coerceAtLeast(0f)
            val amountY = (cy + 1f).coerceAtMost(size.height - amountLayout.size.height)
            drawText(labelLayout, topLeft = Offset(labelX, labelY))
            drawText(amountLayout, topLeft = Offset(amountX, amountY))
        }
        NodeLabelAnchor.RIGHT_OF_NODE -> {
            // Left-align label/amount to the RIGHT edge of the node.
            val tx = node.xStart + nodeWidthPx + padding
            val cy = node.yStart + node.height / 2f
            val labelY = (cy - labelLayout.size.height - 1f).coerceAtLeast(0f)
            val amountY = (cy + 1f).coerceAtMost(size.height - amountLayout.size.height)
            drawText(labelLayout, topLeft = Offset(tx, labelY))
            drawText(amountLayout, topLeft = Offset(tx, amountY))
        }
        NodeLabelAnchor.ABOVE_AND_BELOW_NODE -> {
            // Middle column: label centered ABOVE the node, amount
            // centered BELOW so the brand-orange rect doesn't have
            // text drawn on top of it (which was the original
            // legibility problem).
            // UX review 2026-10 (B14) — the amount used to sit BELOW the
            // node, clamped to the canvas bottom, where it landed on top
            // of the income ribbons and became unreadable. Label and
            // amount now stack ABOVE the node, in the reserved band.
            val cx = node.xStart + nodeWidthPx / 2f
            val labelX = cx - labelLayout.size.width / 2f
            val amountX = cx - amountLayout.size.width / 2f
            val amountY = (node.yStart - amountLayout.size.height - 2f).coerceAtLeast(0f)
            val labelY = (amountY - labelLayout.size.height).coerceAtLeast(0f)
            drawText(labelLayout, topLeft = Offset(labelX, labelY))
            drawText(amountLayout, topLeft = Offset(amountX, amountY))
        }
    }
}

/**
 * Phase 3.12.b — distribute `avail` pixels across N value-weighted
 * nodes so the stack fits exactly inside `avail`. Iterative pinning:
 *
 *  1. Compute the naïve `value * scale` height for every node.
 *  2. Any node below `minPx` gets pinned at `minPx`.
 *  3. The remaining budget (`avail - sum(pinned)`) is redistributed
 *     across the unpinned nodes proportional to their value.
 *  4. New pinned nodes may emerge in step 3 — repeat until the set
 *     stabilises (bounded by N iterations).
 *
 * This is the fixed-point that the original single-pass version
 * skipped: with many small categories, the cumulative "lifted to 2px"
 * overhead could push the stack past `avail`, ribbons would spill
 * outside the Canvas. After this, sum-of-heights = avail exactly.
 */
internal fun computeNodeHeights(
    values: List<ULong>,
    avail: Float,
    minPx: Float,
): FloatArray {
    val n = values.size
    if (n == 0) return FloatArray(0)
    if (avail <= 0f) return FloatArray(n) { 0f }
    // Cap on the per-node minimum: if `avail / n < minPx` (extreme
    // case, say 20 nodes in 30px), we can't honour minPx at all —
    // fall back to equal slices so at least every node is visible.
    val effectiveMin = minOf(minPx, avail / n.toFloat().coerceAtLeast(1f))
    val pinned = BooleanArray(n)
    val out = FloatArray(n)
    var iterations = 0
    while (iterations <= n) {
        iterations++
        val pinnedTotal = (0 until n).sumOf { i -> if (pinned[i]) out[i].toDouble() else 0.0 }
        val remainingAvail = (avail - pinnedTotal).coerceAtLeast(0.0)
        val unpinnedValueSum = (0 until n)
            .filter { !pinned[it] }
            .sumOf { values[it].toDouble() }
        if (unpinnedValueSum <= 0.0) {
            // All remaining nodes are zero-value — fill with minPx and exit.
            for (i in 0 until n) {
                if (!pinned[i]) out[i] = effectiveMin
            }
            break
        }
        val scale = remainingAvail / unpinnedValueSum
        var newlyPinned = false
        for (i in 0 until n) {
            if (pinned[i]) continue
            val raw = (values[i].toDouble() * scale).toFloat()
            if (raw < effectiveMin) {
                out[i] = effectiveMin
                pinned[i] = true
                newlyPinned = true
            } else {
                out[i] = raw
            }
        }
        if (!newlyPinned) break
    }
    return out
}

/**
 * Truncate a category label so a single pathological "Very long recurring
 * extraordinary expenses label" entry doesn't blow the label pad
 * past half the canvas. Normal labels (≤ 16 chars) pass through; the
 * dynamic [computeLabelPad] then sizes the actual pad to fit.
 */
private fun truncateLabel(s: String, maxChars: Int = 16): String =
    if (s.length <= maxChars) s else s.take(maxChars - 1) + "…"

/**
 * Phase 3.12.d — measure the widest rendered label OR amount in the
 * column and add a small buffer. The result becomes the horizontal
 * pad reserved for that column's labels so the right-aligned (left
 * column) or left-aligned (right column) text always fits inside the
 * canvas without clipping.
 */
private fun DrawScope.computeLabelPad(
    labels: List<String>,
    amounts: List<String>,
    labelStyle: TextStyle,
    subLabelStyle: TextStyle,
    textMeasurer: TextMeasurer,
    buffer: Float,
): Float {
    val labelMax = labels.maxOfOrNull {
        textMeasurer.measure(AnnotatedString(it), labelStyle).size.width.toFloat()
    } ?: 0f
    val amountMax = amounts.maxOfOrNull {
        textMeasurer.measure(AnnotatedString(it), subLabelStyle).size.width.toFloat()
    } ?: 0f
    // Floor at 40px so a single tiny label like "food" still leaves
    // visible breathing room next to the node; cap at 45% of canvas
    // width so a label storm doesn't squeeze the chart area to nothing.
    return (maxOf(labelMax, amountMax) + buffer)
        .coerceIn(40f, size.width * 0.45f)
}

/**
 * Draw a single Sankey "ribbon" between two parallel edges as a filled
 * cubic Bézier. Control points are placed at the X midpoint so the
 * ribbon eases in and out symmetrically; ribbons of different heights
 * at source vs target taper smoothly between the two.
 */
private fun DrawScope.drawSankeyRibbon(
    sourceTopLeft: Offset,
    sourceHeight: Float,
    targetTopLeft: Offset,
    targetHeight: Float,
    color: Color,
) {
    val midX = (sourceTopLeft.x + targetTopLeft.x) / 2f
    val path = Path().apply {
        moveTo(sourceTopLeft.x, sourceTopLeft.y)
        cubicTo(
            x1 = midX, y1 = sourceTopLeft.y,
            x2 = midX, y2 = targetTopLeft.y,
            x3 = targetTopLeft.x, y3 = targetTopLeft.y,
        )
        lineTo(targetTopLeft.x, targetTopLeft.y + targetHeight)
        cubicTo(
            x1 = midX, y1 = targetTopLeft.y + targetHeight,
            x2 = midX, y2 = sourceTopLeft.y + sourceHeight,
            x3 = sourceTopLeft.x, y3 = sourceTopLeft.y + sourceHeight,
        )
        close()
    }
    drawPath(path = path, color = color)
}
