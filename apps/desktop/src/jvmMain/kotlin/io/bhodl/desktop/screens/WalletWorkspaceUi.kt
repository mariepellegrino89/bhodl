package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import io.bhodl.desktop.ui.ColumnAmountFormat
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wallet_tab_addresses
import io.bhodl.shared.resources.wallet_tab_receive
import io.bhodl.shared.resources.wallet_tab_settings
import io.bhodl.shared.resources.wallet_tab_transactions
import io.bhodl.shared.resources.wallet_tab_utxos
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * UX review 2026-10 (§4 item 5) — building blocks of the full-width,
 * Sparrow-style wallet workspace: the sub-tab strip, dense table cells
 * and the semantic amount / status renderers shared by the
 * Transactions, Addresses and UTXOs tabs.
 */

/**
 * The wallet's sub-tabs, in Sparrow order. [wire] is the stable name
 * carried by deep links / saved state.
 */
public enum class WalletTab(public val wire: String, internal val title: StringResource) {
    TRANSACTIONS("transactions", Res.string.wallet_tab_transactions),
    RECEIVE("receive", Res.string.wallet_tab_receive),
    ADDRESSES("addresses", Res.string.wallet_tab_addresses),
    UTXOS("utxos", Res.string.wallet_tab_utxos),
    SETTINGS("settings", Res.string.wallet_tab_settings),
    ;

    public companion object {
        public fun fromWire(wire: String?): WalletTab =
            entries.firstOrNull { it.wire == wire } ?: TRANSACTIONS
    }
}

/** Underlined text tabs, left-aligned, with a hairline below the strip. */
@Composable
internal fun WalletTabStrip(
    selected: WalletTab,
    onSelect: (WalletTab) -> Unit,
    tabs: List<WalletTab> = WalletTab.entries,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .clickable { onSelect(tab) }
                        .semantics {
                            this.selected = isSelected
                            role = Role.Tab
                        }
                        .testTag(DashboardTags.tab(tab)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(tab.title),
                        style = BhodlTheme.typography.bodyMedium,
                        color = if (isSelected) BhodlTheme.colors.textPrimary
                        else BhodlTheme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .width(if (isSelected) 40.dp else 0.dp)
                            .background(BhodlTheme.colors.brandPrimary),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BhodlTheme.colors.outline),
        )
    }
}

/** Bordered surface panel that hosts a dense table. */
@Composable
internal fun TablePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(BhodlTheme.shapes.panel)
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel),
        content = content,
    )
}

/** Header strip of a dense table (surfaceVariant band). */
@Composable
internal fun TableHeaderRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surfaceVariant)
            .padding(horizontal = BhodlTheme.spacing.md, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Column header. When [onSort] is set the header is clickable and
 * shows ▲ / ▼ while it is the active sort column.
 */
@Composable
internal fun TableHeaderCell(
    label: String,
    modifier: Modifier,
    align: TextAlign = TextAlign.Start,
    sortAscending: Boolean? = null,
    onSort: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val arrow = when (sortAscending) {
        true -> " ▲"
        false -> " ▼"
        null -> ""
    }
    var m = modifier
    if (onSort != null) m = m.clickable(onClick = onSort)
    if (testTag != null) m = m.testTag(testTag)
    Text(
        text = label + arrow,
        modifier = m,
        style = BhodlTheme.typography.labelMedium,
        color = if (sortAscending != null) BhodlTheme.colors.textPrimary
        else BhodlTheme.colors.textSecondary,
        textAlign = align,
        maxLines = 1,
    )
}

/** Background of a data row: selection beats zebra striping. */
@Composable
internal fun rowBackground(index: Int, selected: Boolean): Color = when {
    selected -> BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    index % 2 == 1 -> BhodlTheme.colors.rowZebra
    else -> Color.Transparent
}

/**
 * UX review 2026-10 (rec 2) — the amount column format of the enclosing
 * transactions table (History, watch dashboard). When set, the header
 * shows the unit ([TxTableHeader]) and [SignedAmountText] renders bare
 * numbers; null outside a table (detail panes keep the full amount).
 */
internal val LocalTxAmountColumn = androidx.compose.runtime.compositionLocalOf<ColumnAmountFormat?> { null }

/**
 * Signed on-chain amount in the wallet's unit, tabular figures.
 * Bitcoin Design Guide activity rules, aligned with the tracker:
 * incoming = "+" in `financialPositive` (the tracker's income colour),
 * outgoing = "−" in the neutral text colour — red is reserved for
 * errors, never for an ordinary payment.
 */
@Composable
internal fun SignedAmountText(
    netSat: Long,
    modifier: Modifier = Modifier,
    strikethrough: Boolean = false,
) {
    val fmt = rememberAmountFormatter()
    // Inside a transactions table the column provides its own format
    // (unit in the header, bare numbers, one unit per column).
    val column = LocalTxAmountColumn.current
    val abs = if (netSat < 0) -netSat else netSat
    // Item 13 — privacy mode hides the direction too (sign and colour).
    val masked = io.bhodl.desktop.ui.money.amountsMasked()
    val sign = io.bhodl.desktop.ui.amountSign(netSat, masked = masked)
    Text(
        text = sign + (column?.bare(abs) ?: fmt(abs)),
        modifier = modifier,
        style = BhodlTheme.typography.amountSmall.let {
            if (strikethrough) {
                it.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
            } else {
                it
            }
        },
        color = if (strikethrough) {
            BhodlTheme.colors.textTertiary
        } else {
            // BDG: incoming green, outgoing neutral; neutral while masked.
            io.bhodl.desktop.ui.signedAmountColor(
                value = netSat.toDouble(),
                positive = BhodlTheme.colors.financialPositive,
                negative = BhodlTheme.colors.textPrimary,
                neutral = BhodlTheme.colors.textPrimary,
            )
        },
        textAlign = TextAlign.End,
        maxLines = 1,
    )
}

/** Small rounded label chip (status / "Fee bumped" / "Used" …). */
@Composable
internal fun WalletChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    var m = modifier
        .clip(shape)
        .background(color.copy(alpha = 0.14f))
        .border(1.dp, color.copy(alpha = 0.45f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Text(
        text = text,
        modifier = m.padding(horizontal = 8.dp, vertical = 1.dp),
        style = BhodlTheme.typography.labelMedium,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Full address in monospace blocks of four, never truncated at the
 * start (UX review B11). Wraps instead of eliding when the column is
 * narrow, unless [singleLine] asks for an end ellipsis (dense tables).
 */
@Composable
internal fun GroupedAddressText(
    address: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = false,
    color: Color = BhodlTheme.colors.textPrimary,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    Text(
        text = io.bhodl.desktop.ui.groupAddressInFours(address) ?: address,
        modifier = modifier,
        style = BhodlTheme.typography.mono.let { if (fontSize.isSpecified) it.copy(fontSize = fontSize) else it },
        color = color,
        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        overflow = if (singleLine) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

/** Section caption above a panel ("RECIPIENT", "FEE" …). */
@Composable
internal fun PanelCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = BhodlTheme.typography.labelMedium,
        color = BhodlTheme.colors.textSecondary,
    )
}
