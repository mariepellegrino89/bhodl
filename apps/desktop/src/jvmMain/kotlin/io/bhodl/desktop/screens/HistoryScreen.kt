package io.bhodl.desktop.screens

import io.bhodl.desktop.ui.tableRowClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.ui.BhodlChip
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.ui.headerWithUnit
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.TransactionRecord
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_err_load_failed
import io.bhodl.shared.resources.wview_history_title
import io.bhodl.shared.resources.wview_wallet_not_unlocked
import io.bhodl.shared.resources.wallet_tx_col_amount
import io.bhodl.shared.resources.wallet_tx_col_confs
import io.bhodl.shared.resources.wallet_tx_col_date
import io.bhodl.shared.resources.wallet_tx_col_label
import io.bhodl.shared.resources.wallet_tx_col_status
import io.bhodl.shared.resources.wallet_tx_count
import io.bhodl.shared.resources.wallet_tx_empty
import io.bhodl.shared.resources.wallet_tx_filter_all
import io.bhodl.shared.resources.wallet_tx_filter_incoming
import io.bhodl.shared.resources.wallet_tx_filter_outgoing
import io.bhodl.shared.resources.wallet_tx_filter_pending
import io.bhodl.shared.resources.wallet_tx_loading
import io.bhodl.shared.resources.wallet_tx_no_match
import io.bhodl.shared.resources.wallet_tx_refreshing
import io.bhodl.shared.resources.wallet_tx_search
import io.bhodl.shared.resources.wallet_tx_status_confirming
import io.bhodl.shared.resources.wallet_tx_status_confirming_unknown
import io.bhodl.shared.resources.wallet_tx_status_pending
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The wallet's **Transactions** tab (UX review 2026-10, §4 items 5 + 8).
 *
 * A dense, sortable table — date, label · category, amount (right
 * aligned, tabular), status, confirmations — with direction / pending
 * filter chips and a free-text search, rows sized by
 * [BhodlTheme.density]. Selecting a row opens a side detail pane
 * (list-detail) instead of navigating to a separate page; the old
 * `TransactionDetails` route still exists for deep links.
 *
 * Transaction states follow the Bitcoin Design Guide: Pending →
 * Confirming n/6 → Confirmed (no badge).
 *
 * Inside the wallet workspace [syncOnEnter] is `false`: the workspace
 * owns the network sync and bumps [refreshKey] after it. Stand-alone
 * (tests / legacy route) the tab reconciles with Esplora itself on
 * entry.
 */
@Composable
public fun HistoryScreen(
    onTxClicked: (txid: String) -> Unit = {},
    onBack: (() -> Unit)? = null,
    syncOnEnter: Boolean = true,
    refreshKey: Int = 0,
    tipHeight: UInt? = null,
) {
    var state: HistoryState by remember { mutableStateOf(HistoryState.Loading) }
    var refreshing by remember { mutableStateOf(false) }
    val labels = remember { mutableStateMapOf<String, String>() }
    val categories = remember { mutableStateMapOf<String, String>() }
    var ownTip by remember { mutableStateOf<UInt?>(null) }
    var sortColumn by remember { mutableStateOf(TxSortColumn.DATE) }
    var sortAscending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(TxFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var selectedTxid by remember { mutableStateOf<String?>(null) }
    var labelVersion by remember { mutableStateOf(0) }

    // A label edited in the detail pane: re-read locally, no network.
    LaunchedEffect(labelVersion) {
        val wallet = WalletStore.current() ?: return@LaunchedEffect
        if (labelVersion > 0) state = readHistory(wallet, labels, categories) { ownTip = it }
    }
    val notUnlocked = stringResource(Res.string.wview_wallet_not_unlocked)
    LaunchedEffect(refreshKey) {
        val wallet = WalletStore.current()
        if (wallet == null) {
            state = HistoryState.Failed(notUnlocked)
            return@LaunchedEffect
        }
        // Paint the local BDK store first (instant, possibly stale).
        state = readHistory(wallet, labels, categories) { ownTip = it }
        if (syncOnEnter) {
            // Non-blocking reconcile: a failure keeps the local view
            // (the workspace header surfaces network errors).
            refreshing = true
            runCatching {
                withContext(Dispatchers.IO) {
                    wallet.syncWithEsplora(EsploraEndpoint.current(), EsploraEndpoint.currentProxy())
                }
            }
            state = readHistory(wallet, labels, categories) { ownTip = it }
            refreshing = false
        }
    }

    val tip = tipHeight ?: ownTip

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background)
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) BhodlTopBar(title = stringResource(Res.string.wview_history_title), onBack = onBack)

        val all = (state as? HistoryState.Ready)?.transactions.orEmpty()
        // UX review 2026-10 (rec 2) — one unit for the amount column, in its header.
        val txAmounts = rememberColumnAmountFormat(all.map { it.netSat })
        val labelOf: (String) -> String = { labels[it].orEmpty() }
        val visible = sortTransactions(
            txs = filterTransactions(all, filter, query, labelOf, { categories[it] }),
            column = sortColumn,
            ascending = sortAscending,
            labelOf = labelOf,
            tipHeight = tip,
        )

        TransactionsToolbar(
            filter = filter,
            onFilter = { filter = it },
            query = query,
            onQuery = { query = it },
            shown = visible.size,
            total = all.size,
            refreshing = refreshing,
        )

        // List-detail: table and pane side by side when there is room;
        // on narrow windows the pane replaces the table until closed.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
            val paneOnly = !twoPane && selectedTxid != null
            // Item 17 — drop low-priority columns when the table is narrow.
            val tableWidthDp = (maxWidth - (if (selectedTxid != null && twoPane) DETAIL_PANE_WIDTH + 12.dp else 0.dp)).value.toInt()
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!paneOnly) TablePanel(modifier = Modifier.weight(1f).fillMaxHeight().testTag(HistoryTags.TABLE)) {
                    CompositionLocalProvider(
                        LocalTxAmountColumn provides txAmounts,
                        LocalTxColumns provides visibleTxColumns(tableWidthDp),
                    ) {
                        TxTableHeader(
                            sortColumn = sortColumn,
                            sortAscending = sortAscending,
                            onSort = { col ->
                                if (sortColumn == col) {
                                    sortAscending = !sortAscending
                                } else {
                                    sortColumn = col
                                    // Dates / amounts read best biggest-first.
                                    sortAscending = col == TxSortColumn.LABEL
                                }
                            },
                        )
                        when (val s = state) {
                            HistoryState.Loading -> TableMessage(stringResource(Res.string.wallet_tx_loading))
                            is HistoryState.Failed -> TableMessage(s.reason)
                            HistoryState.Empty -> TableMessage(stringResource(Res.string.wallet_tx_empty))
                            is HistoryState.Ready -> if (visible.isEmpty()) {
                                TableMessage(stringResource(Res.string.wallet_tx_no_match))
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(visible, key = { _, tx -> tx.txid }) { index, tx ->
                                        TxTableRow(
                                            tx = tx,
                                            index = index,
                                            label = labels[tx.txid].orEmpty(),
                                            category = categories[tx.txid],
                                            tipHeight = tip,
                                            selected = selectedTxid == tx.txid,
                                            onClick = {
                                                selectedTxid = tx.txid
                                                onTxClicked(tx.txid)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val selected = selectedTxid
                if (selected != null) {
                    TransactionDetailsPane(
                        txid = selected,
                        tipHeight = tip,
                        category = categories[selected],
                        onClose = { selectedTxid = null },
                        onLabelChanged = { labelVersion++ },
                        modifier = (if (paneOnly) Modifier.fillMaxWidth() else Modifier.width(DETAIL_PANE_WIDTH))
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** Column headers of the transactions table; `onSort == null` = static. */
@Composable
internal fun TxTableHeader(
    sortColumn: TxSortColumn?,
    sortAscending: Boolean,
    onSort: ((TxSortColumn) -> Unit)?,
) {
    TableHeaderRow {
        @Composable
        fun header(col: TxSortColumn, label: String, column: TxCol, align: TextAlign) {
            TableHeaderCell(
                label = label,
                modifier = colMod(column),
                align = align,
                sortAscending = if (sortColumn == col) sortAscending else null,
                onSort = onSort?.let { { it(col) } },
                testTag = HistoryTags.sortHeader(col),
            )
        }
        val cols = LocalTxColumns.current
        if (TxCol.DATE in cols) {
            header(TxSortColumn.DATE, stringResource(Res.string.wallet_tx_col_date), TxCol.DATE, TextAlign.Start)
        }
        header(TxSortColumn.LABEL, stringResource(Res.string.wallet_tx_col_label), TxCol.LABEL, TextAlign.Start)
        val amountLabel = stringResource(Res.string.wallet_tx_col_amount)
        val amountColumn = LocalTxAmountColumn.current
        header(
            TxSortColumn.AMOUNT,
            amountColumn?.let { headerWithUnit(amountLabel, it.unitLabel) } ?: amountLabel,
            TxCol.AMOUNT,
            TextAlign.End,
        )
        Spacer(Modifier.width(16.dp))
        if (TxCol.STATUS in cols) {
            header(TxSortColumn.STATUS, stringResource(Res.string.wallet_tx_col_status), TxCol.STATUS, TextAlign.Start)
        }
        if (TxCol.CONFS in cols) {
            header(TxSortColumn.CONFIRMATIONS, stringResource(Res.string.wallet_tx_col_confs), TxCol.CONFS, TextAlign.End)
        }
    }
}

@Composable
private fun TransactionsToolbar(
    filter: TxFilter,
    onFilter: (TxFilter) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    shown: Int,
    total: Int,
    refreshing: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TxFilter.entries.forEach { f ->
            BhodlChip(
                label = stringResource(
                    when (f) {
                        TxFilter.ALL -> Res.string.wallet_tx_filter_all
                        TxFilter.INCOMING -> Res.string.wallet_tx_filter_incoming
                        TxFilter.OUTGOING -> Res.string.wallet_tx_filter_outgoing
                        TxFilter.PENDING -> Res.string.wallet_tx_filter_pending
                    },
                ),
                selected = filter == f,
                onClick = { onFilter(f) },
                testTag = HistoryTags.filter(f),
            )
        }
        Spacer(Modifier.width(8.dp))
        val placeholder = stringResource(Res.string.wallet_tx_search)
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(BhodlTheme.colors.surface)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = BhodlTheme.typography.bodyMedium.copy(color = BhodlTheme.colors.textPrimary),
                cursorBrush = SolidColor(BhodlTheme.colors.textPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    .testTag(HistoryTags.SEARCH),
            )
        }
        Text(
            text = if (refreshing) stringResource(Res.string.wallet_tx_refreshing)
            else stringResource(Res.string.wallet_tx_count, shown, total),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.testTag(HistoryTags.COUNT),
        )
    }
}

@Composable
internal fun TxTableRow(
    tx: TransactionRecord,
    index: Int,
    label: String,
    category: String?,
    tipHeight: UInt?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(BhodlTheme.density.rowHeight)
            .background(rowBackground(index, selected))
            // Item 15 — keyboard focus ring (≠ selection) + ↑/↓ row moves.
            .tableRowClickable(onClick = onClick)
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(HistoryTags.row(tx.txid)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cols = LocalTxColumns.current
        if (TxCol.DATE in cols) Text(
            text = tx.timestamp?.let { formatTxDate(it.toLong()) } ?: "—",
            modifier = colMod(TxCol.DATE),
            style = BhodlTheme.typography.amountSmall,
            color = BhodlTheme.colors.textSecondary,
            maxLines = 1,
        )
        Row(
            modifier = colMod(TxCol.LABEL),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (label.isNotEmpty()) {
                Text(
                    text = label,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).testTag(HistoryTags.labelField(tx.txid)),
                )
            } else {
                Text(
                    text = shortTxid(tx.txid),
                    style = BhodlTheme.typography.mono,
                    color = BhodlTheme.colors.textTertiary,
                    maxLines = 1,
                )
            }
            if (!category.isNullOrBlank()) {
                WalletChip(text = io.bhodl.desktop.ui.categoryDisplayName(category), color = BhodlTheme.colors.accentNeon)
            }
        }
        SignedAmountText(
            netSat = tx.netSat,
            modifier = colMod(TxCol.AMOUNT).testTag(HistoryTags.amount(tx.txid)),
        )
        Spacer(Modifier.width(16.dp))
        if (TxCol.STATUS in cols) Row(
            modifier = colMod(TxCol.STATUS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TxStatusChip(tx = tx, tipHeight = tipHeight)
        }
        if (TxCol.CONFS in cols) Text(
            text = confirmationsOf(tx.confirmationHeight, tipHeight)?.let { formatCount(it) } ?: "—",
            modifier = colMod(TxCol.CONFS).testTag(HistoryTags.confirmations(tx.txid)),
            style = BhodlTheme.typography.amountSmall,
            color = BhodlTheme.colors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

/** Pending / Confirming n/6 chip; nothing for a completed transaction (BDG). */
@Composable
internal fun TxStatusChip(tx: TransactionRecord, tipHeight: UInt?) {
    when (val status = txStatusOf(tx.confirmationHeight, tipHeight)) {
        TxStatus.Pending -> WalletChip(
            text = stringResource(Res.string.wallet_tx_status_pending),
            color = BhodlTheme.colors.financialPending,
            modifier = Modifier.testTag(HistoryTags.status(tx.txid)),
        )
        is TxStatus.Confirming -> WalletChip(
            text = status.confirmations
                ?.let { stringResource(Res.string.wallet_tx_status_confirming, it) }
                ?: stringResource(Res.string.wallet_tx_status_confirming_unknown),
            color = BhodlTheme.colors.accentNeon,
            modifier = Modifier.testTag(HistoryTags.status(tx.txid)),
        )
        TxStatus.Confirmed -> Unit
    }
}

@Composable
internal fun TableMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Read the local BDK store (no network): transactions, BIP-329 labels,
 * categories (wallet extension first, then the tracker's event
 * category) and the synced tip height.
 */
private suspend fun readHistory(
    wallet: WalletPort,
    labels: MutableMap<String, String>,
    categories: MutableMap<String, String>,
    onTip: (UInt?) -> Unit,
): HistoryState = runCatching {
    withContext(Dispatchers.IO) {
        val txs = wallet.listTransactions()
        val registry = RegistryStore.current()
        val walletId = WalletStore.activeWalletId()
        val labelPairs = txs.map { it.txid to (runCatching { wallet.getTxLabel(it.txid) }.getOrNull() ?: "") }
        val categoryPairs = txs.mapNotNull { tx ->
            val c = runCatching { wallet.getTxCategory(tx.txid) }.getOrNull()
                ?: if (registry != null && walletId != null) {
                    runCatching { registry.getEventCategory(walletId, tx.txid) }.getOrNull()
                } else {
                    null
                }
            c?.let { tx.txid to it }
        }
        val tip = runCatching { wallet.chainTipHeight() }.getOrNull()
        Triple(txs, labelPairs, categoryPairs) to tip
    }
}.fold(
    onSuccess = { (data, tip) ->
        val (txs, labelPairs, categoryPairs) = data
        labels.clear()
        labelPairs.forEach { (txid, text) -> if (text.isNotEmpty()) labels[txid] = text }
        categories.clear()
        categoryPairs.forEach { (txid, c) -> categories[txid] = c }
        onTip(tip)
        if (txs.isEmpty()) HistoryState.Empty else HistoryState.Ready(txs)
    },
    onFailure = { e -> HistoryState.Failed(e.userMessage(getString(Res.string.wview_err_load_failed))) },
)

internal fun formatTxDate(epochSeconds: Long): String = runCatching {
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMAT)
}.getOrDefault(epochSeconds.toString())

internal fun shortTxid(txid: String): String =
    if (txid.length <= 16) txid else "${txid.take(8)}…${txid.takeLast(8)}"

private fun formatCount(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/**
 * Column widths: fixed for the tabular columns so dates and amounts
 * never clip; the label column takes whatever is left.
 */
internal enum class TxCol(
    val width: Dp?,
    // UX review 2026-10 (item 17) — column priority: label + amount
    // never hide; confirmations go first, then status, then date.
    override val minWidthDp: Int,
    override val hideRank: Int,
) : io.bhodl.desktop.ui.PrioritizedColumn {
    DATE(128.dp, 128, 1),
    LABEL(null, 140, 0),
    AMOUNT(140.dp, 140, 0),
    STATUS(230.dp, 230, 2),
    CONFS(56.dp, 56, 3),
}

/** Visible transaction-table columns; all of them unless a host narrows it. */
internal val LocalTxColumns = androidx.compose.runtime.staticCompositionLocalOf { TxCol.entries.toSet() }

/** Columns of the transactions table that fit [widthDp] (row padding + status spacer included). */
internal fun visibleTxColumns(widthDp: Int): Set<TxCol> =
    io.bhodl.desktop.ui.visibleColumns(TxCol.entries, widthDp - 48, gapDp = 0).toSet()

private fun RowScope.colMod(c: TxCol): Modifier =
    if (c.width != null) Modifier.width(c.width) else Modifier.weight(1f)
private val DETAIL_PANE_WIDTH = 380.dp
private val TWO_PANE_MIN_WIDTH = 1000.dp

private sealed class HistoryState {
    data object Loading : HistoryState()
    data object Empty : HistoryState()
    data class Ready(val transactions: List<TransactionRecord>) : HistoryState()
    data class Failed(val reason: String) : HistoryState()
}

/** Test tags exposed by [`HistoryScreen`]. Per-tx tags are keyed by txid. */
public object HistoryTags {
    public const val TABLE: String = "history.table"
    public const val SEARCH: String = "history.search"
    public const val COUNT: String = "history.count"
    public fun row(txid: String): String = "history.row.$txid"
    public fun labelField(txid: String): String = "history.label.$txid"
    public fun amount(txid: String): String = "history.amount.$txid"
    public fun status(txid: String): String = "history.status.$txid"
    public fun confirmations(txid: String): String = "history.confs.$txid"
    public fun filter(filter: TxFilter): String = "history.filter.${filter.name.lowercase()}"
    public fun sortHeader(column: TxSortColumn): String = "history.sort.${column.name.lowercase()}"
}
