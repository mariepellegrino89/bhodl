package io.bhodl.desktop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.ffi.PsbtOutput
import io.bhodl.ffi.PsbtSummary
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wallet_review_diagram
import io.bhodl.shared.resources.wallet_review_diagram_a11y
import io.bhodl.shared.resources.wallet_review_diagram_hide
import io.bhodl.shared.resources.wallet_review_diagram_show
import io.bhodl.shared.resources.wallet_review_fee_node
import io.bhodl.shared.resources.wallet_review_inputs
import io.bhodl.shared.resources.wallet_review_output_a11y
import io.bhodl.shared.resources.wallet_review_role_change
import io.bhodl.shared.resources.wallet_review_role_recipient
import io.bhodl.shared.resources.wallet_review_role_self
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/** What an output of the reviewed transaction is, for the diagram. */
internal enum class OutputRole { Recipient, Change, Self }

internal fun outputRole(output: PsbtOutput): OutputRole = when {
    output.isChange -> OutputRole.Change
    output.isMine -> OutputRole.Self
    else -> OutputRole.Recipient
}

/**
 * UX review 2026-10 (§4 item 6) — simple Sparrow-style input→output
 * diagram for the Review screen: one node for the inputs (count +
 * total), a connector to every output (amount, role, address) and to
 * the network fee. Collapsible (progressive disclosure); the whole
 * drawing is exposed to accessibility services as one text description.
 */
@Composable
internal fun TransactionDiagram(summary: PsbtSummary, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(true) }
    val fmt = rememberAmountFormatter()
    val recipient = stringResource(Res.string.wallet_review_role_recipient)
    val change = stringResource(Res.string.wallet_review_role_change)
    val self = stringResource(Res.string.wallet_review_role_self)
    val roleLabel: (OutputRole) -> String = {
        when (it) {
            OutputRole.Recipient -> recipient
            OutputRole.Change -> change
            OutputRole.Self -> self
        }
    }
    // Text alternative (screen readers): "N inputs totalling X flow to
    // M outputs: A to addr (recipient); …. Network fee F."
    val outputPhrases = summary.outputs.map { o ->
        stringResource(
            Res.string.wallet_review_output_a11y,
            fmt(o.amountSat.toLong()),
            o.address,
            roleLabel(outputRole(o)),
        )
    }
    val description = stringResource(
        Res.string.wallet_review_diagram_a11y,
        summary.inputCount.toInt(),
        fmt(summary.inputsTotalSat.toLong()),
        summary.outputs.size,
        outputPhrases.joinToString("; "),
        fmt(summary.feeSat.toLong()),
    )

    TablePanel(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag(TransactionDiagramTags.TOGGLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelCaption(stringResource(Res.string.wallet_review_diagram), Modifier.weight(1f))
            Text(
                text = stringResource(
                    if (expanded) Res.string.wallet_review_diagram_hide else Res.string.wallet_review_diagram_show,
                ),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
        }
        if (!expanded) return@TablePanel
        val rowHeight = 52.dp
        val rows = summary.outputs.size + 1 // + fee
        val totalHeight = rowHeight * rows
        val lineColor = BhodlTheme.colors.controlBorder
        val feeColor = BhodlTheme.colors.textTertiary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clearAndSetSemantics {
                    contentDescription = description
                    testTag = TransactionDiagramTags.DIAGRAM
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Inputs node.
            Column(
                modifier = Modifier
                    .width(150.dp)
                    .border(1.dp, BhodlTheme.colors.controlBorder, RoundedCornerShape(6.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(Res.string.wallet_review_inputs, summary.inputCount.toInt()),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Text(
                    text = fmt(summary.inputsTotalSat.toLong()),
                    style = BhodlTheme.typography.amountSmall,
                    color = BhodlTheme.colors.textPrimary,
                )
            }
            // Connectors: one curve per output, a dashed one to the fee.
            Canvas(modifier = Modifier.width(56.dp).height(totalHeight)) {
                val start = Offset(0f, size.height / 2f)
                val step = size.height / rows
                for (i in 0 until rows) {
                    val end = Offset(size.width, step * i + step / 2f)
                    val path = Path().apply {
                        moveTo(start.x, start.y)
                        cubicTo(size.width / 2f, start.y, size.width / 2f, end.y, end.x, end.y)
                    }
                    val isFee = i == rows - 1
                    drawPath(
                        path = path,
                        color = if (isFee) feeColor else lineColor,
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = if (isFee) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
                        ),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                summary.outputs.forEach { o ->
                    DiagramNode(height = rowHeight) {
                        Text(
                            text = "${fmt(o.amountSat.toLong())} · ${roleLabel(outputRole(o))}",
                            style = BhodlTheme.typography.amountSmall,
                            color = if (o.isMine) BhodlTheme.colors.textSecondary else BhodlTheme.colors.textPrimary,
                            maxLines = 1,
                        )
                        // The full address sits in the summary card; here
                        // the start (the part users verify) stays visible.
                        GroupedAddressText(
                            address = o.address,
                            singleLine = true,
                            color = BhodlTheme.colors.textTertiary,
                        )
                    }
                }
                DiagramNode(height = rowHeight) {
                    Text(
                        text = "${fmt(summary.feeSat.toLong())} · ${stringResource(Res.string.wallet_review_fee_node)}",
                        style = BhodlTheme.typography.amountSmall,
                        color = BhodlTheme.colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagramNode(height: Dp, content: @Composable () -> Unit) {
    Box(modifier = Modifier.height(height).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BhodlTheme.colors.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) { content() }
    }
}

/** Test tags of [TransactionDiagram]. */
public object TransactionDiagramTags {
    public const val TOGGLE: String = "tx-diagram.toggle"
    public const val DIAGRAM: String = "tx-diagram.diagram"
}
