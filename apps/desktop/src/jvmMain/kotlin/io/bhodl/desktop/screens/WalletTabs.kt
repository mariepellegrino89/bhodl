package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.tableRowFocusable
import io.bhodl.desktop.ui.tableRowFocusRing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import io.bhodl.desktop.ui.BhodlChip
import io.bhodl.desktop.ui.ColumnAmountFormat
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.desktop.ui.tableCell
import io.bhodl.desktop.ui.headerWithUnit
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.UtxoRecord
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_err_load_failed
import io.bhodl.shared.resources.wallet_addr_change
import io.bhodl.shared.resources.wallet_addr_col_address
import io.bhodl.shared.resources.wallet_addr_col_index
import io.bhodl.shared.resources.wallet_addr_col_label
import io.bhodl.shared.resources.wallet_addr_col_status
import io.bhodl.shared.resources.wallet_addr_count
import io.bhodl.shared.resources.wallet_addr_empty
import io.bhodl.shared.resources.wallet_addr_receive
import io.bhodl.shared.resources.wallet_addr_show
import io.bhodl.shared.resources.wallet_addr_unused
import io.bhodl.shared.resources.wallet_addr_used
import io.bhodl.shared.resources.wallet_utxo_change
import io.bhodl.shared.resources.wallet_utxo_col_address
import io.bhodl.shared.resources.wallet_utxo_col_amount
import io.bhodl.shared.resources.wallet_utxo_col_outpoint
import io.bhodl.shared.resources.wallet_utxo_col_status
import io.bhodl.shared.resources.wallet_utxo_col_type
import io.bhodl.shared.resources.wallet_utxo_confirmed
import io.bhodl.shared.resources.wallet_utxo_empty
import io.bhodl.shared.resources.wallet_utxo_failed
import io.bhodl.shared.resources.wallet_utxo_receive
import io.bhodl.shared.resources.wallet_utxo_summary
import io.bhodl.shared.resources.wallet_utxo_unconfirmed
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

// ─── Addresses ──────────────────────────────────────────────────────────

/**
 * The wallet's **Addresses** tab (Sparrow): every revealed receive or
 * change address with its index, BIP-329 label / tracker category and
 * used / unused state. "Show" opens it in the Receive tab, which warns
 * when the address was already used.
 */
@Composable
internal fun WalletAddressesTab(
    refreshKey: Int,
    onShowInReceive: (address: String) -> Unit,
) {
    var change by remember { mutableStateOf(false) }
    var rows by remember { mutableStateOf<List<AddressRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey, change) {
        val wallet = WalletStore.current() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { wallet.listAddresses(change) } }
            .onSuccess {
                // Newest index first: the one just handed out is on top.
                rows = it.sortedByDescending { a -> a.index }
                error = null
            }
            .onFailure { error = it.userMessage(getString(Res.string.wview_err_load_failed)) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BhodlChip(
                label = stringResource(Res.string.wallet_addr_receive),
                selected = !change,
                onClick = { change = false },
                testTag = WalletTabTags.ADDR_RECEIVE,
            )
            BhodlChip(
                label = stringResource(Res.string.wallet_addr_change),
                selected = change,
                onClick = { change = true },
                testTag = WalletTabTags.ADDR_CHANGE,
            )
            Spacer(Modifier.weight(1f))
            rows?.let { list ->
                Text(
                    text = stringResource(Res.string.wallet_addr_count, list.size, list.count { it.used }),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
        AddressTable(
            rows = rows,
            error = error,
            onShowInReceive = onShowInReceive,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * The Addresses table. Item 17 — on a narrow window the index, then the
 * label column give way ([ADDRESS_TABLE]); address, used / unused and
 * the Show link always stay.
 */
@Composable
internal fun AddressTable(
    rows: List<AddressRecord>?,
    error: String?,
    onShowInReceive: (address: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val cols = ADDRESS_TABLE.visible(maxWidth.value.toInt())
        TablePanel(modifier = Modifier.fillMaxSize().testTag(WalletTabTags.ADDR_TABLE)) {
            TableHeaderRow {
                for (c in cols) {
                    val label = when (c) {
                        AddressCol.INDEX -> Res.string.wallet_addr_col_index
                        AddressCol.ADDRESS -> Res.string.wallet_addr_col_address
                        AddressCol.LABEL -> Res.string.wallet_addr_col_label
                        AddressCol.STATUS -> Res.string.wallet_addr_col_status
                    }
                    TableHeaderCell(stringResource(label), Modifier.tableCell(this, c), testTag = ADDRESS_TABLE.headerTag(c))
                }
                Spacer(Modifier.width(64.dp))
            }
            when {
                error != null -> EmptyTableLine(error)
                rows == null -> Unit
                rows.isEmpty() -> EmptyTableLine(stringResource(Res.string.wallet_addr_empty))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, a -> a.address }) { index, a ->
                        AddressTableRow(a, index, cols, onShowInReceive)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressTableRow(
    a: AddressRecord,
    index: Int,
    cols: List<AddressCol>,
    onShowInReceive: (address: String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BhodlTheme.density.rowHeight)
            .background(rowBackground(index, selected = false))
            // Item 15 — reachable and readable by keyboard.
            .tableRowFocusable()
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(WalletTabTags.addressRow(a.address)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in cols) when (c) {
            AddressCol.INDEX -> Text(
                text = a.index.toString(),
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.amountSmall,
                color = BhodlTheme.colors.textTertiary,
            )
            AddressCol.ADDRESS -> GroupedAddressText(
                address = a.address,
                singleLine = true,
                modifier = Modifier.tableCell(this, c),
                color = if (a.used) BhodlTheme.colors.textSecondary
                else BhodlTheme.colors.textPrimary,
            )
            AddressCol.LABEL -> Row(
                modifier = Modifier.tableCell(this, c),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                a.label?.let {
                    Text(
                        text = it,
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                a.category?.let { WalletChip(text = it, color = BhodlTheme.colors.accentNeon) }
            }
            AddressCol.STATUS -> Box(modifier = Modifier.tableCell(this, c)) {
                WalletChip(
                    text = stringResource(
                        if (a.used) Res.string.wallet_addr_used else Res.string.wallet_addr_unused,
                    ),
                    color = if (a.used) BhodlTheme.colors.textTertiary
                    else BhodlTheme.colors.financialPositive,
                    modifier = Modifier.testTag(WalletTabTags.addressStatus(a.address)),
                )
            }
        }
        Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.CenterEnd) {
            if (!a.isChange) {
                Text(
                    text = stringResource(Res.string.wallet_addr_show),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.brandPrimary,
                    modifier = Modifier
                        .clickable { onShowInReceive(a.address) }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .testTag(WalletTabTags.addressShow(a.address)),
                )
            }
        }
    }
}

// ─── UTXOs ──────────────────────────────────────────────────────────────

/**
 * The wallet's **UTXOs** tab: every coin held by the watched scripts
 * with amount, type (receive / change) and confirmation status. Read
 * only — BHODL never spends.
 */
@Composable
internal fun WalletUtxosTab(refreshKey: Int) {
    val fmt = rememberAmountFormatter()
    var utxos by remember { mutableStateOf<List<UtxoRecord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        val wallet = WalletStore.current() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { wallet.listUtxos() } }
            .onSuccess { list -> utxos = list.sortedByDescending { it.valueSat } }
            .onFailure { error = it.userMessage(getString(Res.string.wview_err_load_failed)) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val list = utxos.orEmpty()
        Text(
            text = stringResource(
                Res.string.wallet_utxo_summary,
                list.size,
                fmt(list.sumOf { it.valueSat.toLong() }),
            ),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.testTag(WalletTabTags.UTXO_SUMMARY),
        )
        error?.let {
            Text(
                text = stringResource(Res.string.wallet_utxo_failed, it),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
            )
        }
        // UX review 2026-10 (rec 2) — one unit for the amount column, in the header.
        val utxoAmounts = rememberColumnAmountFormat(list.map { it.valueSat.toLong() })
        UtxoTable(
            utxos = utxos,
            amounts = utxoAmounts,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/**
 * The UTXOs table. Item 17 — on a narrow window type, address, then
 * outpoint give way ([UTXO_TABLE]); status and amount always stay.
 */
@Composable
internal fun UtxoTable(
    utxos: List<UtxoRecord>?,
    amounts: ColumnAmountFormat,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val cols = UTXO_TABLE.visible(maxWidth.value.toInt())
        TablePanel(modifier = Modifier.fillMaxSize().testTag(WalletTabTags.UTXO_TABLE)) {
            TableHeaderRow {
                for (c in cols) {
                    val label = when (c) {
                        UtxoCol.OUTPOINT -> stringResource(Res.string.wallet_utxo_col_outpoint)
                        UtxoCol.ADDRESS -> stringResource(Res.string.wallet_utxo_col_address)
                        UtxoCol.TYPE -> stringResource(Res.string.wallet_utxo_col_type)
                        UtxoCol.STATUS -> stringResource(Res.string.wallet_utxo_col_status)
                        UtxoCol.AMOUNT ->
                            headerWithUnit(stringResource(Res.string.wallet_utxo_col_amount), amounts.unitLabel)
                    }
                    TableHeaderCell(
                        label,
                        Modifier.tableCell(this, c),
                        align = if (c == UtxoCol.AMOUNT) TextAlign.End else TextAlign.Start,
                        testTag = UTXO_TABLE.headerTag(c),
                    )
                }
            }
            val list = utxos.orEmpty()
            when {
                utxos == null -> Unit
                list.isEmpty() -> EmptyTableLine(stringResource(Res.string.wallet_utxo_empty))
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(list, key = { _, u -> u.outpoint }) { index, u ->
                        UtxoTableRow(u, index, cols, amounts)
                    }
                }
            }
        }
    }
}

@Composable
private fun UtxoTableRow(
    u: UtxoRecord,
    index: Int,
    cols: List<UtxoCol>,
    amounts: ColumnAmountFormat,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BhodlTheme.density.rowHeight)
            .background(rowBackground(index, false))
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(WalletTabTags.utxoRow(u.outpoint)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (c in cols) when (c) {
            UtxoCol.OUTPOINT -> Text(
                text = shortOutpoint(u.outpoint),
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.mono,
                color = BhodlTheme.colors.textSecondary,
                maxLines = 1,
            )
            UtxoCol.ADDRESS -> GroupedAddressText(
                address = u.address,
                singleLine = true,
                modifier = Modifier.tableCell(this, c),
                color = BhodlTheme.colors.textSecondary,
            )
            UtxoCol.TYPE -> Text(
                text = stringResource(
                    if (u.isChange) Res.string.wallet_utxo_change else Res.string.wallet_utxo_receive,
                ),
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            UtxoCol.STATUS -> Text(
                text = stringResource(
                    if (u.confirmed) Res.string.wallet_utxo_confirmed
                    else Res.string.wallet_utxo_unconfirmed,
                ),
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
            UtxoCol.AMOUNT -> Text(
                text = amounts.bare(u.valueSat.toLong()),
                modifier = Modifier.tableCell(this, c),
                style = BhodlTheme.typography.amountSmall,
                color = BhodlTheme.colors.textPrimary,
                textAlign = TextAlign.End,
            )
        }
    }
}

private fun shortOutpoint(op: String): String {
    val sep = op.indexOf(':')
    return if (sep > 16) "${op.take(8)}…${op.substring(sep)}" else op
}

@Composable
private fun EmptyTableLine(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// ─── Wallet settings ────────────────────────────────────────────────────

/**
 * The wallet's **Wallet settings** tab: rename, BIP-329 labels, public
 * descriptor export and remove — in two columns. App-wide preferences
 * stay under the rail's Settings entry.
 */
@Composable
internal fun WalletSettingsTab(onWalletRemoved: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 12.dp)
            .testTag(WalletTabTags.SETTINGS),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            RenameWalletSection()
            LabelsSection()
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            ShowWatchOnlyDescriptorSection()
            RemoveThisWalletSection(onRemoved = onWalletRemoved)
        }
    }
}

/** Test tags of the Addresses / UTXOs / Wallet settings tabs. */
public object WalletTabTags {
    public const val ADDR_TABLE: String = "wallet.addresses.table"
    public const val ADDR_RECEIVE: String = "wallet.addresses.receive"
    public const val ADDR_CHANGE: String = "wallet.addresses.change"
    public fun addressRow(address: String): String = "wallet.addresses.row.$address"
    public fun addressStatus(address: String): String = "wallet.addresses.status.$address"
    public fun addressShow(address: String): String = "wallet.addresses.show.$address"

    public const val UTXO_TABLE: String = "wallet.utxos.table"
    public const val UTXO_SUMMARY: String = "wallet.utxos.summary"
    public fun utxoRow(outpoint: String): String = "wallet.utxos.row.$outpoint"

    public const val SETTINGS: String = "wallet.settings"
}
