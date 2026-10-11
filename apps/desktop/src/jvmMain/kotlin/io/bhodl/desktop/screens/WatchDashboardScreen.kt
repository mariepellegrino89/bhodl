package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.ui.rememberColumnAmountFormat
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WatchStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.TransactionRecord
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_err_open_watch
import io.bhodl.shared.resources.wview_err_sync
import io.bhodl.shared.resources.wallet_header_watch
import io.bhodl.shared.resources.wallet_tx_empty
import io.bhodl.shared.resources.wallet_tx_loading
import io.bhodl.shared.resources.wallet_watch_back
import io.bhodl.shared.resources.wallet_watch_promote
import io.bhodl.shared.resources.wallet_watch_readonly
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3.2 sub-D — read-only view of an activated watch wallet,
 * rebuilt in the wallet workspace's dense full-width language (UX
 * review 2026-10, §4 item 5): the shared wallet header (name, balance,
 * sync status) above the same transactions table as the signing
 * wallet's Transactions tab, read-only.
 *
 * Opens the wallet's `WatchHandle` via `RegistryHandle.openWatch` and
 * stashes it in [`WatchStore`]; the handle is released when the screen
 * leaves composition.
 *
 * **Deliberately no Send / Receive.** Watch wallets have no signing
 * keys — Send is impossible, and handing out addresses the user could
 * not spend from this device is a footgun. "Promote to signing (add
 * seed)" is the header action that unlocks those.
 */
@Composable
public fun WatchDashboardScreen(
    walletId: String,
    onBack: () -> Unit,
    onPromote: (walletId: String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf<String?>(null) }
    var network by remember { mutableStateOf(Network.SIGNET) }
    var balance by remember { mutableStateOf<ULong?>(null) }
    var transactions by remember { mutableStateOf<List<TransactionRecord>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var syncing by remember { mutableStateOf(false) }
    var lastSyncAtMs by remember { mutableStateOf<Long?>(null) }
    var lastError: String? by remember { mutableStateOf(null) }
    var sortColumn by remember { mutableStateOf(TxSortColumn.DATE) }
    var sortAscending by remember { mutableStateOf(false) }

    LaunchedEffect(walletId) {
        runCatching {
            withContext(Dispatchers.IO) {
                val registry = RegistryStore.current() ?: error("RegistryStore not populated")
                val record = registry.getWallet(walletId) ?: error("Wallet $walletId not found in registry")
                val handle = registry.openWatch(walletId = walletId)
                WatchStore.set(handle, walletId, record.label)
                WatchSnapshot(
                    label = record.label,
                    network = EsploraEndpoint.networkOf(record.network) ?: Network.SIGNET,
                    balance = handle.balanceSat(),
                    txs = handle.listTransactions(),
                )
            }
        }.fold(
            onSuccess = { snap ->
                label = snap.label
                network = snap.network
                balance = snap.balance
                transactions = snap.txs
                loading = false
            },
            onFailure = { e ->
                loading = false
                lastError = e.userMessage(getString(Res.string.wview_err_open_watch))
            },
        )
    }

    // Release the handle when the screen leaves composition.
    DisposableEffect(walletId) {
        onDispose { WatchStore.clear() }
    }

    fun sync() {
        if (syncing) return
        val handle = WatchStore.current() ?: return
        syncing = true
        lastError = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Sync against the watch wallet's OWN network.
                    handle.syncWithEsplora(EsploraEndpoint.current(network), EsploraEndpoint.currentProxy())
                    handle.balanceSat() to handle.listTransactions()
                }
            }.fold(
                onSuccess = { (sat, txs) ->
                    balance = sat
                    transactions = txs
                    lastSyncAtMs = System.currentTimeMillis()
                },
                onFailure = { e -> lastError = e.userMessage(getString(Res.string.wview_err_sync)) },
            )
            syncing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WalletHeader(
            walletName = label ?: stringResource(Res.string.wallet_header_watch),
            kindLabel = stringResource(Res.string.wallet_header_watch),
            network = network,
            balanceSat = if (loading) null else balance,
            syncMode = if (syncing) SyncMode.Foreground else SyncMode.Idle,
            lastSyncAtMs = lastSyncAtMs,
            syncFailed = lastError != null,
            onSync = { sync() },
            syncTestTag = WatchDashboardTags.SYNC,
            actions = {
                TextButton(
                    onClick = { onPromote(walletId) },
                    modifier = Modifier.testTag(WatchDashboardTags.PROMOTE),
                ) { Text(stringResource(Res.string.wallet_watch_promote)) }
                TextButton(
                    onClick = {
                        WatchStore.clear()
                        onBack()
                    },
                    modifier = Modifier.testTag(WatchDashboardTags.BACK),
                ) {
                    Text(
                        text = stringResource(Res.string.wallet_watch_back),
                        color = BhodlTheme.colors.textSecondary,
                    )
                }
            },
        )
        Text(
            text = stringResource(Res.string.wallet_watch_readonly),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        lastError?.let {
            Text(text = it, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.error)
        }

        // UX review 2026-10 (rec 2) — one unit for the amount column, in its header.
        val txAmounts = rememberColumnAmountFormat(transactions.map { it.netSat })
        TablePanel(modifier = Modifier.weight(1f).fillMaxWidth().testTag(WatchDashboardTags.TABLE)) {
            CompositionLocalProvider(LocalTxAmountColumn provides txAmounts) {
                TxTableHeader(
                    sortColumn = sortColumn,
                    sortAscending = sortAscending,
                    onSort = { col ->
                        if (sortColumn == col) sortAscending = !sortAscending
                        else {
                            sortColumn = col
                            sortAscending = col == TxSortColumn.LABEL
                        }
                    },
                )
                when {
                    loading -> TableMessage(stringResource(Res.string.wallet_tx_loading))
                    transactions.isEmpty() -> TableMessage(stringResource(Res.string.wallet_tx_empty))
                    else -> {
                        // No synced tip for watch wallets: the highest block
                        // seen in its own history is a LOWER bound of the tip,
                        // so counts are never overstated (recent rows read
                        // "Confirming", never a premature "Confirmed").
                        val tipLowerBound = transactions.mapNotNull { it.confirmationHeight }.maxOrNull()
                        val rows = sortTxGroups(
                            groups = transactions.map { TxGroup(it) },
                            column = sortColumn,
                            ascending = sortAscending,
                            tipHeight = tipLowerBound,
                        )
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(rows, key = { _, g -> g.tx.txid }) { index, g ->
                                TxTableRow(
                                    tx = g.tx,
                                    index = index,
                                    label = "",
                                    category = null,
                                    tipHeight = tipLowerBound,
                                    feeBumped = false,
                                    selected = false,
                                    onClick = {},
                                    onSpeedUp = null,
                                )
                            }
                        }
                    }
                }
            }
        }

        io.bhodl.desktop.ui.BhodlFooterStatusBar(
            network = network,
            walletCount = 1,
            lastSyncUnix = lastSyncAtMs?.let { it / 1_000L },
        )
    }
}

private data class WatchSnapshot(
    val label: String,
    val network: Network,
    val balance: ULong,
    val txs: List<TransactionRecord>,
)

public object WatchDashboardTags {
    public const val SYNC: String = "watch-dashboard.sync"
    public const val BACK: String = "watch-dashboard.back"
    public const val PROMOTE: String = "watch-dashboard.promote"
    public const val TABLE: String = "watch-dashboard.table"
}
