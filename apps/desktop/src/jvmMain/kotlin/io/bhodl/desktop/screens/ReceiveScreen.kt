package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.ui.Bip21
import io.bhodl.desktop.ui.ErrorCard
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.AddressRecord
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.dashboard_button_receive
import io.bhodl.shared.resources.wview_unknown_error
import io.bhodl.shared.resources.wallet_rcv_address_title
import io.bhodl.shared.resources.wallet_rcv_amount
import io.bhodl.shared.resources.wallet_rcv_categorised
import io.bhodl.shared.resources.wallet_rcv_category
import io.bhodl.shared.resources.wallet_rcv_continue
import io.bhodl.shared.resources.wallet_rcv_copied_address
import io.bhodl.shared.resources.wallet_rcv_copied_uri
import io.bhodl.shared.resources.wallet_rcv_copy_address
import io.bhodl.shared.resources.wallet_rcv_copy_uri
import io.bhodl.shared.resources.wallet_rcv_edit_label
import io.bhodl.shared.resources.wallet_rcv_error
import io.bhodl.shared.resources.wallet_rcv_generating
import io.bhodl.shared.resources.wallet_rcv_label
import io.bhodl.shared.resources.wallet_rcv_labelled
import io.bhodl.shared.resources.wallet_rcv_new_address
import io.bhodl.shared.resources.wallet_rcv_not_unlocked
import io.bhodl.shared.resources.wallet_rcv_payer_message
import io.bhodl.shared.resources.wallet_rcv_payer_message_hint
import io.bhodl.shared.resources.wallet_rcv_qr_description
import io.bhodl.shared.resources.wallet_rcv_reuse_warning
import io.bhodl.shared.resources.wallet_rcv_skip
import io.bhodl.shared.resources.wallet_rcv_step1_body
import io.bhodl.shared.resources.wallet_rcv_step1_title
import io.bhodl.ui.theme.BhodlTheme
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private const val COPY_FEEDBACK_MS = 1_500L

/**
 * The wallet's **Receive** tab — label-first (UX review 2026-10, §4
 * item 7; Liana v15, Bitcoin Design Guide "requesting").
 *
 * Step 1 asks — optionally, skippable — for a label, a tracker
 * category, an amount and a message for the payer *before* any address
 * is shown. The label and the category are stored on the address
 * (BIP-329 `addr` record + BHODL category); after the next sync they are
 * copied onto the incoming transaction, so the payment lands already
 * categorised in the tracker (`WalletPort.applyReceiveLabels`). They are
 * private bookkeeping and never go into the URI; only the optional payer
 * message does (BIP-21 `message`).
 *
 * Step 2 shows the lowest unused receive address (monospace, blocks of
 * four, never truncated), its QR and the copy actions. Opening the tab,
 * continuing or editing the request never reveals a new index; only
 * "New address" does. When the shown address already received funds —
 * e.g. opened from the Addresses tab — a reuse warning explains the
 * privacy
 * cost. The balance is deliberately not shown here (BDG: it would leak
 * to onlookers).
 */
@Composable
public fun ReceiveScreen(
    onBack: (() -> Unit)? = null,
    /** Addresses tab "Show": open step 2 directly on this address. */
    presetAddress: String? = null,
    onPresetConsumed: () -> Unit = {},
) {
    val wallet: WalletPort? = remember { WalletStore.current() }
    val scope = rememberCoroutineScope()
    val knownCategories by TrackerCache.categories

    var address by remember { mutableStateOf<String?>(null) }
    var addresses by remember { mutableStateOf<List<AddressRecord>>(emptyList()) }
    var loadError: String? by remember { mutableStateOf(null) }
    var working by remember { mutableStateOf(false) }

    var amountText by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var copiedKind by remember { mutableStateOf<CopyKind?>(null) }

    LaunchedEffect(copiedKind) {
        if (copiedKind != null) {
            delay(COPY_FEEDBACK_MS)
            copiedKind = null
        }
    }

    // Address handed out by this request. Kept across "New request"
    // round-trips so editing the label updates the SAME address instead
    // of revealing another one (gap limit on restore).
    var showAddress by remember { mutableStateOf(false) }
    var payerMessage by remember { mutableStateOf("") }

    /**
     * Resolve the address for this request and store the label /
     * category on it. [fresh] = the user asked for a new address
     * (`nextReceiveAddress`); otherwise the current address is reused
     * and, the first time, the lowest unused revealed one
     * (`nextUnusedReceiveAddress`) — opening the tab or re-labelling
     * never burns an index.
     */
    fun resolve(fresh: Boolean) {
        if (working || wallet == null) return
        working = true
        val l = label.trim()
        val c = category.trim().ifBlank { null }
        val current = address
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = when {
                        fresh -> wallet.nextReceiveAddress()
                        current != null -> current
                        else -> wallet.nextUnusedReceiveAddress()
                    }
                    // Editing an already-shown address writes even a blank
                    // label (clears it); a newly handed-out one only gets a
                    // label the user actually typed.
                    if (current == target || l.isNotEmpty() || c != null) {
                        runCatching { wallet.setAddressLabel(target, l, c) }
                    }
                    target to runCatching { wallet.listAddresses(change = false) }.getOrDefault(emptyList())
                }
            }.onSuccess { (target, list) ->
                address = target
                addresses = list
                loadError = null
                showAddress = true
            }.onFailure {
                loadError = it.userMessage(it.javaClass.simpleName)
            }
            working = false
        }
    }

    LaunchedEffect(presetAddress) {
        val preset = presetAddress ?: return@LaunchedEffect
        val w = wallet ?: return@LaunchedEffect
        val list = runCatching {
            withContext(Dispatchers.IO) { w.listAddresses(change = false) }
        }.getOrDefault(emptyList())
        addresses = list
        val record = list.firstOrNull { it.address == preset }
        label = record?.label.orEmpty()
        category = record?.category.orEmpty()
        address = preset
        showAddress = true
        onPresetConsumed()
    }

    val amountSat: Long? = amountText.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
    val uri: String = address?.let { addr ->
        // The label / category are private bookkeeping: never put them in
        // the URI the payer sees. Only an explicit payer message goes in.
        Bip21.build(address = addr, amountSat = amountSat, message = payerMessage.takeIf { it.isNotBlank() })
    } ?: ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background)
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (onBack != null) BhodlTopBar(title = stringResource(Res.string.dashboard_button_receive), onBack = onBack)

        if (wallet == null) {
            ErrorCard(
                title = stringResource(Res.string.wallet_rcv_error),
                message = stringResource(Res.string.wallet_rcv_not_unlocked),
                modifier = Modifier.testTag(ReceiveTags.ERROR),
            )
        } else if (loadError != null && address == null) {
            ErrorCard(
                title = stringResource(Res.string.wallet_rcv_error),
                message = loadError ?: stringResource(Res.string.wview_unknown_error),
                modifier = Modifier.testTag(ReceiveTags.ERROR),
            )
        }

        val shown = address
        if (shown == null || !showAddress) {
            RequestStep(
                label = label,
                onLabel = { label = it },
                category = category,
                onCategory = { category = it },
                knownCategories = knownCategories,
                amountText = amountText,
                onAmount = { amountText = it.filter { c -> c.isDigit() } },
                working = working,
                enabled = wallet != null,
                payerMessage = payerMessage,
                onPayerMessage = { payerMessage = it },
                onContinue = { resolve(fresh = false) },
            )
        } else {
            AddressStep(
                address = shown,
                reused = isReusedAddress(shown, addresses),
                uri = uri,
                label = label,
                category = category,
                copiedKind = copiedKind,
                working = working,
                onCopyAddress = {
                    io.bhodl.desktop.ui.SensitiveClipboard.copy(shown)
                    copiedKind = CopyKind.Address
                },
                onCopyUri = {
                    io.bhodl.desktop.ui.SensitiveClipboard.copy(uri)
                    copiedKind = CopyKind.Uri
                },
                onNewAddress = { resolve(fresh = true) },
                onNewRequest = { showAddress = false },
            )
        }

        if (onBack != null) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().testTag(ReceiveTags.BACK),
            ) { Text(stringResource(Res.string.action_back)) }
        }
    }
}

/** Step 1 — label, tracker category and amount first; all optional. */
@Composable
private fun RequestStep(
    label: String,
    onLabel: (String) -> Unit,
    category: String,
    onCategory: (String) -> Unit,
    knownCategories: List<String>,
    amountText: String,
    onAmount: (String) -> Unit,
    payerMessage: String,
    onPayerMessage: (String) -> Unit,
    working: Boolean,
    enabled: Boolean,
    onContinue: () -> Unit,
) {
    TablePanel(modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag(ReceiveTags.REQUEST_STEP)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(Res.string.wallet_rcv_step1_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.wallet_rcv_step1_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            OutlinedTextField(
                value = label,
                onValueChange = onLabel,
                label = { Text(stringResource(Res.string.wallet_rcv_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag(ReceiveTags.LABEL),
            )
            OutlinedTextField(
                value = category,
                onValueChange = onCategory,
                label = { Text(stringResource(Res.string.wallet_rcv_category)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ReceiveTags.CATEGORY),
            )
            if (knownCategories.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    knownCategories.forEach { c ->
                        val selected = category.trim().equals(c, ignoreCase = true)
                        WalletChip(
                            text = c,
                            color = if (selected) BhodlTheme.colors.accentNeon else BhodlTheme.colors.textSecondary,
                            onClick = { onCategory(c) },
                            modifier = Modifier.testTag(ReceiveTags.categoryChip(c)),
                        )
                    }
                }
            }
            OutlinedTextField(
                value = amountText,
                // Sat-only: digits, so a stray decimal point can't sneak in.
                onValueChange = onAmount,
                label = { Text(stringResource(Res.string.wallet_rcv_amount)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth().testTag(ReceiveTags.AMOUNT),
            )
            OutlinedTextField(
                value = payerMessage,
                onValueChange = onPayerMessage,
                label = { Text(stringResource(Res.string.wallet_rcv_payer_message)) },
                supportingText = { Text(stringResource(Res.string.wallet_rcv_payer_message_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(ReceiveTags.PAYER_MESSAGE),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val hasDetails = label.isNotBlank() || category.isNotBlank() ||
                    amountText.isNotBlank() || payerMessage.isNotBlank()
                Button(
                    onClick = onContinue,
                    enabled = enabled && !working,
                    modifier = Modifier.testTag(ReceiveTags.CONTINUE),
                ) {
                    Text(
                        if (working) stringResource(Res.string.wallet_rcv_generating)
                        else stringResource(Res.string.wallet_rcv_continue),
                    )
                }
                if (!hasDetails) {
                    TextButton(
                        onClick = onContinue,
                        enabled = enabled && !working,
                        modifier = Modifier.testTag(ReceiveTags.SKIP),
                    ) { Text(stringResource(Res.string.wallet_rcv_skip)) }
                }
            }
        }
    }
}

/** Step 2 — QR + grouped address + copy actions, reuse warning. */
@Composable
private fun AddressStep(
    address: String,
    reused: Boolean,
    uri: String,
    label: String,
    category: String,
    copiedKind: CopyKind?,
    working: Boolean,
    onCopyAddress: () -> Unit,
    onCopyUri: () -> Unit,
    onNewAddress: () -> Unit,
    onNewRequest: () -> Unit,
) {
    val qrDescription = stringResource(Res.string.wallet_rcv_qr_description)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // QR pane — white regardless of theme so scanners get contrast.
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White)
                .padding(10.dp)
                .testTag(ReceiveTags.QR),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Image(
                painter = rememberQrCodePainter(data = uri.ifEmpty { address }),
                contentDescription = qrDescription,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PanelCaption(stringResource(Res.string.wallet_rcv_address_title))
            SelectionContainer {
                GroupedAddressText(
                    address = address,
                    fontSize = 18.sp,
                    modifier = Modifier.testTag(ReceiveTags.ADDRESS),
                )
            }
            if (reused) {
                Text(
                    text = "⚠ " + stringResource(Res.string.wallet_rcv_reuse_warning),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.financialPending,
                    modifier = Modifier.testTag(ReceiveTags.REUSE_WARNING),
                )
            }
            if (label.isNotBlank()) {
                Text(
                    text = stringResource(Res.string.wallet_rcv_labelled, label.trim()),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
            if (category.isNotBlank()) {
                Text(
                    text = stringResource(Res.string.wallet_rcv_categorised, category.trim()),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
            // What the QR encodes, selectable for BIP-21-aware tools.
            SelectionContainer {
                Text(
                    text = uri,
                    style = BhodlTheme.typography.mono,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().testTag(ReceiveTags.URI),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopyAddress, modifier = Modifier.testTag(ReceiveTags.COPY_ADDRESS)) {
                    Text(
                        if (copiedKind == CopyKind.Address) stringResource(Res.string.wallet_rcv_copied_address)
                        else stringResource(Res.string.wallet_rcv_copy_address),
                    )
                }
                OutlinedButton(onClick = onCopyUri, modifier = Modifier.testTag(ReceiveTags.COPY_URI)) {
                    Text(
                        if (copiedKind == CopyKind.Uri) stringResource(Res.string.wallet_rcv_copied_uri)
                        else stringResource(Res.string.wallet_rcv_copy_uri),
                    )
                }
                OutlinedButton(
                    onClick = onNewAddress,
                    enabled = !working,
                    modifier = Modifier.testTag(ReceiveTags.NEW_ADDRESS),
                ) {
                    Text(
                        if (working) stringResource(Res.string.wallet_rcv_generating)
                        else stringResource(Res.string.wallet_rcv_new_address),
                    )
                }
                TextButton(onClick = onNewRequest, modifier = Modifier.testTag(ReceiveTags.NEW_REQUEST)) {
                    Text(stringResource(Res.string.wallet_rcv_edit_label))
                }
            }
        }
    }
}

private enum class CopyKind { Uri, Address }

/** Test tags exposed by [`ReceiveScreen`]. */
public object ReceiveTags {
    public const val REQUEST_STEP: String = "receive.request-step"
    public const val CONTINUE: String = "receive.continue"
    public const val SKIP: String = "receive.skip"
    public const val CATEGORY: String = "receive.category"
    public fun categoryChip(name: String): String = "receive.category.chip.$name"
    public const val QR: String = "receive.qr"
    public const val ADDRESS: String = "receive.address"
    public const val AMOUNT: String = "receive.amount"
    /** Optional BIP-21 `message` shown to the payer (never the private label). */
    public const val PAYER_MESSAGE: String = "receive.payer-message"
    public const val LABEL: String = "receive.label"
    public const val URI: String = "receive.uri"
    public const val REUSE_WARNING: String = "receive.reuse-warning"
    public const val COPY_URI: String = "receive.copy-uri"
    public const val COPY_ADDRESS: String = "receive.copy-address"
    public const val NEW_ADDRESS: String = "receive.new-address"
    public const val NEW_REQUEST: String = "receive.new-request"
    public const val BACK: String = "receive.back"
    public const val ERROR: String = "receive.error"
}
