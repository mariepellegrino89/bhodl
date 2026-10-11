package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.tableRowClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.ColumnAmountFormat
import io.bhodl.desktop.ui.tableCell
import io.bhodl.desktop.ui.headerWithUnit
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_wallets_col_balance
import io.bhodl.shared.resources.wview_wallets_col_network
import io.bhodl.shared.resources.wview_wallets_col_type
import io.bhodl.shared.resources.wview_wallets_col_wallet
import io.bhodl.shared.resources.wview_wallets_empty
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 7.1 — dense wallet table for the TrackerHome.
 *
 * Replaces the spacious [WalletPickerRow] (kept on Welcome) with a
 * Bloomberg/Sparrow-style compact row layout. Each wallet row is
 * 36dp tall (down from ~80dp), zebra-striped, with the balance
 * right-aligned in monospace.
 *
 * Columns:
 *  - **Name** (2 weight) — wallet label.
 *  - **Type** (1 weight) — Signing / Watch (HD / xpub / addresses).
 *  - **Network** (1 weight) — signet / mainnet / etc.
 *  - **Balance** (1.4 weight, right-aligned) — sat amount or "—"
 *    when the wallet is signing and we don't yet have a watch mirror.
 *
 * Wallets are grouped by xpub via the existing [groupPickerWalletsByXpub]
 * so a signing wallet and its watch mirror render as ONE row labeled
 * "Signing (tracked)" — consistent with WelcomeScreen.
 */
@Composable
public fun WalletListTable(
    wallets: List<WalletRecordFfi>,
    watchBalances: Map<String, ULong>,
    onSigningTap: (walletId: String) -> Unit,
    onWatchTap: (walletId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = groupPickerWalletsByXpub(wallets, watchBalances)
    // UX review 2026-10 (rec 2) — one unit for the balance column, named
    // in the header; cells carry the bare number.
    val amounts = rememberColumnAmountFormat(groups.mapNotNull { it.balanceSat?.toLong() })
    // Item 17 — network, then type give way on a narrow window.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val cols = WALLET_LIST_TABLE.visible(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BhodlTheme.shapes.panel)
                .background(BhodlTheme.colors.surface)
                .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel)
                .testTag(WalletListTableTags.TABLE),
        ) {
            WalletListHeaderRow(amounts.unitLabel, cols)
            if (groups.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .padding(BhodlTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.wview_wallets_empty),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
            } else {
                groups.forEachIndexed { idx, group ->
                    WalletListDataRow(
                        group = group,
                        amounts = amounts,
                        cols = cols,
                        isZebra = idx % 2 == 1,
                        onSigningTap = onSigningTap,
                        onWatchTap = onWatchTap,
                    )
                }
            }
        }
    }
}

@Composable
private fun WalletListHeaderRow(unitLabel: String, cols: List<WalletListCol>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surfaceVariant)
            .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in cols) {
            val label = when (c) {
                WalletListCol.NAME -> stringResource(Res.string.wview_wallets_col_wallet)
                WalletListCol.TYPE -> stringResource(Res.string.wview_wallets_col_type)
                WalletListCol.NETWORK -> stringResource(Res.string.wview_wallets_col_network)
                WalletListCol.BALANCE -> headerWithUnit(stringResource(Res.string.wview_wallets_col_balance), unitLabel)
            }
            TableHeaderCell(
                label,
                Modifier.tableCell(this, c).testTag(WALLET_LIST_TABLE.headerTag(c)),
                if (c == WalletListCol.BALANCE) TextAlign.End else TextAlign.Start,
            )
        }
    }
}

@Composable
private fun TableHeaderCell(
    label: String,
    modifier: Modifier,
    align: TextAlign,
) {
    Text(
        text = label,
        modifier = modifier,
        style = BhodlTheme.typography.labelMedium,
        color = BhodlTheme.colors.textSecondary,
        textAlign = align,
    )
}

@Composable
private fun WalletListDataRow(
    group: GroupedPickerWallet,
    amounts: ColumnAmountFormat,
    cols: List<WalletListCol>,
    isZebra: Boolean,
    onSigningTap: (walletId: String) -> Unit,
    onWatchTap: (walletId: String) -> Unit,
) {
    val bg = if (isZebra) BhodlTheme.colors.rowZebra else androidx.compose.ui.graphics.Color.Transparent
    val testTagId = group.signingId ?: group.watchId ?: "?"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // UX review 2026-10 — row height follows Settings › Density
            // (Compact 32dp default, Ultra-dense 24dp).
            .height(BhodlTheme.density.rowHeight)
            .background(bg)
            // Item 15 — keyboard focus ring + ↑/↓ row moves.
            .tableRowClickable {
                if (group.signingId != null) onSigningTap(group.signingId)
                else if (group.watchId != null) onWatchTap(group.watchId)
            }
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(WalletListTableTags.row(testTagId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in cols) when (c) {
            WalletListCol.NAME -> Text(
                text = group.displayLabel,
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WalletListCol.TYPE -> Text(
                text = group.kindLabel,
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            WalletListCol.NETWORK -> Text(
                text = group.network,
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                maxLines = 1,
            )
            WalletListCol.BALANCE -> Text(
                text = group.balanceSat?.let { amounts.bare(it.toLong()) } ?: "—",
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.amountSmall,
                color = BhodlTheme.colors.textPrimary,
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

public object WalletListTableTags {
    public const val TABLE: String = "wallet-list.table"
    public fun row(walletId: String): String = "wallet-list.row.$walletId"
}
