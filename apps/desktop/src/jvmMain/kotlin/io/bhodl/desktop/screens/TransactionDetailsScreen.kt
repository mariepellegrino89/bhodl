package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.ui.BhodlChip
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.TransactionRecord
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_err_clear_failed
import io.bhodl.shared.resources.wview_err_load_tx
import io.bhodl.shared.resources.wview_err_save_failed
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.common_loading
import io.bhodl.shared.resources.wview_wallet_not_unlocked_period
import io.bhodl.shared.resources.wallet_txd_block
import io.bhodl.shared.resources.wallet_txd_category
import io.bhodl.shared.resources.wallet_txd_clear
import io.bhodl.shared.resources.wallet_txd_close
import io.bhodl.shared.resources.wallet_txd_confirmations
import io.bhodl.shared.resources.wallet_txd_copy
import io.bhodl.shared.resources.wallet_txd_fee
import io.bhodl.shared.resources.wallet_txd_label
import io.bhodl.shared.resources.wallet_txd_mempool
import io.bhodl.shared.resources.wallet_txd_not_found
import io.bhodl.shared.resources.wallet_txd_received
import io.bhodl.shared.resources.wallet_txd_save
import io.bhodl.shared.resources.wallet_txd_saving
import io.bhodl.shared.resources.wallet_txd_sent
import io.bhodl.shared.resources.wallet_txd_title
import io.bhodl.shared.resources.wallet_txd_txid
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

/**
 * Stand-alone transaction detail page — kept for deep links and the
 * legacy `TransactionDetails` route. Inside the wallet workspace the
 * same content renders as the Transactions tab's side pane
 * ([TransactionDetailsPane]).
 */
@Composable
public fun TransactionDetailsScreen(
    txid: String,
    onBack: () -> Unit,
    // Audit 2026-10 (L6) — follows the wallet's network (null on
    // regtest → no link); was hard-coded to signet.
    explorerBaseUrl: String? = ExplorerLinks.txBaseUrl(WalletStore.network()),
    openExplorer: (String) -> Unit = ::openInSystemBrowser,
    copyToClipboard: (String) -> Unit = ::copyTextToDesktopClipboard,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 640.dp)
                .padding(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BhodlTopBar(title = stringResource(Res.string.wallet_txd_title), onBack = onBack)
            TransactionDetailsPane(
                txid = txid,
                onClose = null,
                explorerBaseUrl = explorerBaseUrl,
                openExplorer = openExplorer,
                copyToClipboard = copyToClipboard,
                scrollable = false,
            )
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TransactionDetailsTags.BACK),
            ) { Text(stringResource(Res.string.action_back)) }
        }
    }
}

/**
 * Detail content for one transaction: status, amount, fee,
 * confirmations, full txid (copy + explorer) and the BIP-329 label
 * editor.
 */
@Composable
internal fun TransactionDetailsPane(
    txid: String,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tipHeight: UInt? = null,
    category: String? = null,
    onLabelChanged: () -> Unit = {},
    explorerBaseUrl: String? = ExplorerLinks.txBaseUrl(WalletStore.network()),
    openExplorer: (String) -> Unit = ::openInSystemBrowser,
    copyToClipboard: (String) -> Unit = ::copyTextToDesktopClipboard,
    scrollable: Boolean = true,
) {
    var record by remember(txid) { mutableStateOf<TransactionRecord?>(null) }
    var tip by remember(txid) { mutableStateOf(tipHeight) }
    var loadError: String? by remember(txid) { mutableStateOf(null) }
    var labelText by remember(txid) { mutableStateOf("") }
    var labelCommitted by remember(txid) { mutableStateOf("") }
    var labelSaving by remember(txid) { mutableStateOf(false) }
    var labelError: String? by remember(txid) { mutableStateOf(null) }
    val scope = rememberCoroutineScope()
    val notFound = stringResource(Res.string.wallet_txd_not_found)
    val notUnlocked = stringResource(Res.string.wview_wallet_not_unlocked_period)

    LaunchedEffect(txid) {
        val wallet: WalletPort? = WalletStore.current()
        if (wallet == null) {
            loadError = notUnlocked
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val tx = wallet.listTransactions().firstOrNull { it.txid == txid }
                val label = wallet.getTxLabel(txid) ?: ""
                val t = tipHeight ?: runCatching { wallet.chainTipHeight() }.getOrNull()
                Triple(tx, label, t)
            }
        }.fold(
            onSuccess = { (found, label, t) ->
                if (found == null) {
                    loadError = notFound
                } else {
                    record = found
                    labelText = label
                    labelCommitted = label
                    tip = t
                }
            },
            onFailure = { e -> loadError = e.userMessage(getString(Res.string.wview_err_load_tx)) },
        )
    }

    val scroll = rememberScrollState()
    TablePanel(modifier = modifier.testTag(TransactionDetailsTags.PANE)) {
        Column(
            modifier = Modifier
                .let { if (scrollable) it.verticalScroll(scroll) else it }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(Res.string.wallet_txd_title),
                    style = BhodlTheme.typography.titleMedium,
                    color = BhodlTheme.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                if (onClose != null) {
                    TextButton(
                        onClick = onClose,
                        modifier = Modifier.testTag(TransactionDetailsTags.CLOSE),
                    ) { Text(stringResource(Res.string.wallet_txd_close)) }
                }
            }

            val current = record
            when {
                current != null -> {
                    DetailsBody(
                        tx = current,
                        tipHeight = tip,
                        category = category,
                        explorerBaseUrl = explorerBaseUrl,
                        openExplorer = openExplorer,
                        copyToClipboard = copyToClipboard,
                    )
                    LabelEditor(
                        text = labelText,
                        committed = labelCommitted,
                        saving = labelSaving,
                        error = labelError,
                        onTextChange = { labelText = it; labelError = null },
                        onSave = {
                            val wallet = WalletStore.current() ?: return@LabelEditor
                            labelSaving = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        if (labelText.isBlank()) {
                                            wallet.removeTxLabel(current.txid)
                                            ""
                                        } else {
                                            wallet.setTxLabel(current.txid, labelText)
                                            labelText
                                        }
                                    }
                                }.fold(
                                    onSuccess = { committed ->
                                        labelCommitted = committed
                                        labelText = committed
                                        labelError = null
                                        onLabelChanged()
                                    },
                                    onFailure = { e -> labelError = e.userMessage(getString(Res.string.wview_err_save_failed)) },
                                )
                                labelSaving = false
                            }
                        },
                        onClear = {
                            val wallet = WalletStore.current() ?: return@LabelEditor
                            labelSaving = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { wallet.removeTxLabel(current.txid) }
                                }.fold(
                                    onSuccess = {
                                        labelText = ""
                                        labelCommitted = ""
                                        labelError = null
                                        onLabelChanged()
                                    },
                                    onFailure = { e -> labelError = e.userMessage(getString(Res.string.wview_err_clear_failed)) },
                                )
                                labelSaving = false
                            }
                        },
                    )
                }
                loadError != null -> Text(
                    text = loadError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(TransactionDetailsTags.ERROR),
                )
                else -> Text(
                    text = stringResource(Res.string.common_loading),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.testTag(TransactionDetailsTags.LOADING),
                )
            }
        }
    }
}

@Composable
private fun DetailsBody(
    tx: TransactionRecord,
    tipHeight: UInt?,
    category: String?,
    explorerBaseUrl: String?,
    openExplorer: (String) -> Unit,
    copyToClipboard: (String) -> Unit,
) {
    val fmt = rememberAmountFormatter()
    // Status line + chips.
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TransactionDetailsTags.STATUS),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TxStatusChip(tx = tx, tipHeight = tipHeight)
        val height = tx.confirmationHeight
        Text(
            text = when {
                height != null -> stringResource(Res.string.wallet_txd_block, height.toLong().toString())
                else -> stringResource(Res.string.wallet_txd_mempool)
            },
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        tx.timestamp?.let { ts ->
            Text(
                text = DateFormat.getDateTimeInstance().format(Date(ts.toLong() * 1_000L)),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
    }

    // Amount: direction word + unsigned amount; colour per BDG rules.
    val net = tx.netSat
    val abs = if (net >= 0) net else -net
    Text(
        text = (if (net >= 0) stringResource(Res.string.wallet_txd_received) else stringResource(Res.string.wallet_txd_sent)) +
            " " + displayAmount(abs),
        style = BhodlTheme.typography.amountMedium,
        color = if (net > 0) BhodlTheme.colors.financialPositive else BhodlTheme.colors.textPrimary,
        modifier = Modifier.testTag(TransactionDetailsTags.AMOUNT),
    )
    tx.feeSat?.let { fee ->
        KeyValueRow(stringResource(Res.string.wallet_txd_fee), fmt(fee.toLong()), TransactionDetailsTags.FEE)
    }
    KeyValueRow(
        stringResource(Res.string.wallet_txd_confirmations),
        confirmationsOf(tx.confirmationHeight, tipHeight)?.toString() ?: "—",
    )
    if (!category.isNullOrBlank()) {
        KeyValueRow(stringResource(Res.string.wallet_txd_category), category)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(Res.string.wallet_txd_txid),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        // Selectable so a partial range can be copied by hand too.
        SelectionContainer {
            Text(
                text = tx.txid,
                style = BhodlTheme.typography.mono,
                color = BhodlTheme.colors.textPrimary,
                modifier = Modifier.testTag(TransactionDetailsTags.TXID),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { copyToClipboard(tx.txid) },
                modifier = Modifier.testTag(TransactionDetailsTags.COPY_TXID),
            ) { Text(stringResource(Res.string.wallet_txd_copy)) }
            // With Tor on, confirms first (system browser = outside Tor)
            // and offers copying the txid instead.
            ExplorerLinkButton(
                txid = tx.txid,
                explorerBaseUrl = explorerBaseUrl,
                openExplorer = openExplorer,
                onCopyTxid = { copyToClipboard(tx.txid) },
                modifier = Modifier.testTag(TransactionDetailsTags.OPEN_EXPLORER),
            )
        }
    }
}

@Composable
private fun LabelEditor(
    text: String,
    committed: String,
    saving: Boolean,
    error: String?,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(stringResource(Res.string.wallet_txd_label)) },
            singleLine = true,
            enabled = !saving,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TransactionDetailsTags.LABEL_FIELD),
        )
        if (error != null) {
            Text(
                text = error,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
                modifier = Modifier.testTag(TransactionDetailsTags.LABEL_ERROR),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (committed.isNotEmpty()) {
                TextButton(
                    onClick = onClear,
                    enabled = !saving,
                    modifier = Modifier.testTag(TransactionDetailsTags.LABEL_CLEAR),
                ) { Text(stringResource(Res.string.wallet_txd_clear)) }
            }
            TextButton(
                onClick = onSave,
                enabled = !saving && text != committed,
                modifier = Modifier.testTag(TransactionDetailsTags.LABEL_SAVE),
            ) {
                Text(
                    if (saving) stringResource(Res.string.wallet_txd_saving)
                    else stringResource(Res.string.wallet_txd_save),
                )
            }
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String, tag: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // One node "label value" for screen readers and tests.
            .semantics(mergeDescendants = true) {}
            .let { if (tag != null) it.testTag(tag) else it },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = value,
            style = BhodlTheme.typography.amountSmall,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

private fun copyTextToDesktopClipboard(text: String) {
    io.bhodl.desktop.ui.SensitiveClipboard.copy(text)
}

/** Test tags exposed by [`TransactionDetailsScreen`] / [TransactionDetailsPane]. */
public object TransactionDetailsTags {
    /** The list-detail side pane (and the page body). */
    public const val PANE: String = "tx-details.pane"
    public const val CLOSE: String = "tx-details.close"
    public const val STATUS: String = "tx-details.status"
    public const val AMOUNT: String = "tx-details.amount"
    public const val FEE: String = "tx-details.fee"
    public const val TXID: String = "tx-details.txid"
    public const val COPY_TXID: String = "tx-details.copy-txid"
    public const val OPEN_EXPLORER: String = "tx-details.open-explorer"
    public const val BACK: String = "tx-details.back"
    public const val LOADING: String = "tx-details.loading"
    public const val ERROR: String = "tx-details.error"
    /** BIP-329 label input field. */
    public const val LABEL_FIELD: String = "tx-details.label-field"
    /** Save button for the BIP-329 label. */
    public const val LABEL_SAVE: String = "tx-details.label-save"
    /** Clear button for the BIP-329 label (only visible when a label exists). */
    public const val LABEL_CLEAR: String = "tx-details.label-clear"
    /** Inline error text below the label field. */
    public const val LABEL_ERROR: String = "tx-details.label-error"
    /** Speed-up panel (pending transactions inside the wallet workspace). */
    public const val SPEED_UP_PANEL: String = "tx-details.speed-up"
    public const val SPEED_UP_COST: String = "tx-details.speed-up.cost"
    public const val SPEED_UP_REVIEW: String = "tx-details.speed-up.review"
    public fun speedUpTier(tier: String): String = "tx-details.speed-up.tier.$tier"
}
