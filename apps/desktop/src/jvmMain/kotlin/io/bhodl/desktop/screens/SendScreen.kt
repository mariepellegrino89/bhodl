package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.ui.ContactPickerDialog
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.ui.BroadcastContext
import io.bhodl.desktop.ui.prettifyBroadcastError
import io.bhodl.desktop.ui.groupAddressInFours
import io.bhodl.desktop.ui.rememberAmountFormatter

import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.wizard.parseBip21
import io.bhodl.ffi.ContactRecord
import io.bhodl.ffi.FeeEstimates
import io.bhodl.ffi.PayjoinReview
import io.bhodl.ffi.RecipientEntry
import io.bhodl.ffi.UtxoRecord
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wallet_send_address_field
import io.bhodl.shared.resources.wallet_send_from_book
import io.bhodl.shared.resources.wallet_send_loading
import io.bhodl.shared.resources.wallet_send_amount_field
import io.bhodl.shared.resources.wallet_send_max
import io.bhodl.shared.resources.wallet_send_max_hint
import io.bhodl.shared.resources.wallet_send_add_recipient
import io.bhodl.shared.resources.wallet_send_coin_control
import io.bhodl.shared.resources.wallet_send_coin_control_hint
import io.bhodl.shared.resources.wallet_send_contacting_payjoin
import io.bhodl.shared.resources.wallet_send_building
import io.bhodl.shared.resources.wallet_send_review_payjoin
import io.bhodl.shared.resources.wallet_send_review
import io.bhodl.shared.resources.wallet_coin_control_auto
import io.bhodl.shared.resources.wallet_fee_custom
import io.bhodl.shared.resources.wallet_fee_custom_active
import io.bhodl.shared.resources.wallet_fee_custom_field
import io.bhodl.shared.resources.wallet_fee_custom_hint
import io.bhodl.shared.resources.wallet_fee_estimate_note
import io.bhodl.shared.resources.wallet_fee_header
import io.bhodl.shared.resources.wallet_fee_total
import io.bhodl.shared.resources.wallet_send_options_header
import io.bhodl.shared.resources.wallet_send_recipient_header
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.wallet_tab_send
import io.bhodl.shared.resources.wsend_address
import io.bhodl.shared.resources.wsend_amount_sat
import io.bhodl.shared.resources.wsend_build_failed
import io.bhodl.shared.resources.wsend_category_add
import io.bhodl.shared.resources.wsend_category_field
import io.bhodl.shared.resources.wsend_category_none
import io.bhodl.shared.resources.wsend_category_placeholder
import io.bhodl.shared.resources.wsend_category_value
import io.bhodl.shared.resources.wsend_enter_positive_amount
import io.bhodl.shared.resources.wsend_enter_recipient
import io.bhodl.shared.resources.wsend_fee_source
import io.bhodl.shared.resources.wsend_fee_unavailable_manual
import io.bhodl.shared.resources.wsend_fee_unavailable_reason
import io.bhodl.shared.resources.wsend_fetch_failed
import io.bhodl.shared.resources.wsend_fetching_fees
import io.bhodl.shared.resources.wsend_invalid_uri
import io.bhodl.shared.resources.wsend_load_failed
import io.bhodl.shared.resources.wsend_loading_utxos
import io.bhodl.shared.resources.wsend_no_spendable_utxos
import io.bhodl.shared.resources.wsend_no_utxo_selected
import io.bhodl.shared.resources.wsend_not_unlocked
import io.bhodl.shared.resources.wsend_payjoin_clear
import io.bhodl.shared.resources.wsend_payjoin_detected
import io.bhodl.shared.resources.wsend_payjoin_explainer
import io.bhodl.shared.resources.wsend_payjoin_failed
import io.bhodl.shared.resources.wsend_payjoin_failure
import io.bhodl.shared.resources.wsend_payjoin_fee
import io.bhodl.shared.resources.wsend_payjoin_fee_original
import io.bhodl.shared.resources.wsend_payjoin_fee_share
import io.bhodl.shared.resources.wsend_payjoin_original_note
import io.bhodl.shared.resources.wsend_payjoin_outputs
import io.bhodl.shared.resources.wsend_payjoin_receiver_inputs
import io.bhodl.shared.resources.wsend_payjoin_review_title
import io.bhodl.shared.resources.wsend_payjoin_role_payment
import io.bhodl.shared.resources.wsend_payjoin_role_receiver
import io.bhodl.shared.resources.wsend_payjoin_send_without
import io.bhodl.shared.resources.wsend_payjoin_sign
import io.bhodl.shared.resources.wsend_payjoin_size
import io.bhodl.shared.resources.wsend_payjoin_tor_required
import io.bhodl.shared.resources.wsend_payjoin_total_leaving
import io.bhodl.shared.resources.wsend_payjoin_unavailable
import io.bhodl.shared.resources.wsend_payjoin_unknown_error
import io.bhodl.shared.resources.wsend_psbt_role_change
import io.bhodl.shared.resources.wsend_recipient_incomplete
import io.bhodl.shared.resources.wsend_recipient_n
import io.bhodl.shared.resources.wsend_remove
import io.bhodl.shared.resources.wsend_selected_n_of_m
import io.bhodl.shared.resources.wsend_send_all
import io.bhodl.shared.resources.wsend_to_label
import io.bhodl.shared.resources.wsend_utxo_change
import io.bhodl.shared.resources.wsend_utxo_confirmed
import io.bhodl.shared.resources.wsend_utxo_frozen
import io.bhodl.shared.resources.wsend_utxo_mempool
import io.bhodl.shared.resources.wsend_utxo_receive

/**
 * Step 1 of the send flow: collect the destination address + amount + fee tier.
 *
 * On first composition we kick off [`io.bhodl.ffi.WalletHandle.fetchFeeEstimates`]
 * on `Dispatchers.IO` to populate the three-tier fee selector. The user can pick
 * Economy / Normal / Priority (default Normal); whichever tier is selected feeds
 * the `feeRateSatVb` argument to `buildSendTx` on the way to the review screen.
 *
 * If the fee estimate is unavailable (network error, or Rust judged the
 * feed implausible) there is NO silent default: the screen says so and
 * asks for a manual fee rate before it builds anything.
 *
 * Builds run on `Dispatchers.IO`. A build the wallet refuses with
 * `WalletException.FeeTooHigh` opens a second-confirmation dialog;
 * only an explicit "Build anyway" rebuilds with `allowHighFee = true`.
 *
 * UX review 2026-10 (§4 item 6) — the wallet workspace's Send tab:
 * two full-width columns (recipient on the left; fee, coin control and
 * Review on the right). Fee presets show the estimated **total** fee
 * (via the app's amount formatter), the ETA and the operational fiat
 * equivalent; a custom sat/vB rate lives in a collapsible panel.
 * Progressive disclosure for beginners: Normal preset and automatic
 * coin selection by default, custom fee / coin control opt-in.
 */
@Composable
public fun SendScreen(
    onReady: (
        psbtBase64: String,
        toAddress: String,
        amountSat: Long,
        /** Phase 7.2.3 — optional inline category for the resulting tx. */
        category: String?,
    ) -> Unit,
    /** `null` inside the wallet workspace (tabs replace Back). */
    onBack: (() -> Unit)? = null,
    // PayJoin lands on SendSuccess after the in-screen review dialog:
    // negotiate (Rust validates the receiver's proposal) → user reviews
    // every output → sign + broadcast. Also fired when the user falls
    // back to broadcasting the original (non-PayJoin) payment.
    onPayjoinSent: (txid: String) -> Unit = {},
    /** UTXOs tab "Send selected": open with coin control pinned to these. */
    preselectedOutpoints: List<String> = emptyList(),
    /** Called once [preselectedOutpoints] were applied (owner clears them). */
    onPreselectionConsumed: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    var address by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var state: BuildState by remember { mutableStateOf(BuildState.Idle) }

    // Phase 7.2.3 — optional inline category for the resulting tx.
    // Persisted via registry.setEventCategory on broadcast success
    // (both regular and PayJoin paths) so the History row is
    // pre-tagged with no extra step. Blank → null. The picker is
    // collapsed behind a toggle so the default send form stays
    // visually compact for users who never categorise.
    var categoryDraft by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
    val knownCategories by TrackerCache.categories
    val effectiveCategory: String? = categoryDraft.trim().ifBlank { null }

    var feeState: FeeEstimateState by remember { mutableStateOf(FeeEstimateState.Loading) }
    var selectedTier: FeeTier by remember { mutableStateOf(FeeTier.Normal) }
    // Manual fee rate (sat/vB) — required when estimates are unavailable,
    // otherwise an opt-in behind the collapsible "Custom fee rate" panel.
    var manualFeeText by remember { mutableStateOf("") }
    var customFeeOpen by remember { mutableStateOf(false) }
    // Second confirmation for a build refused with FeeTooHigh.
    var highFeePrompt: HighFeePrompt? by remember { mutableStateOf(null) }

    var coinControlOn by remember { mutableStateOf(false) }
    var utxoState: UtxoState by remember { mutableStateOf(UtxoState.Idle) }
    val selectedOutpoints = remember { mutableStateListOf<String>() }

    var sendMaxOn by remember { mutableStateOf(false) }

    // PayJoin (BIP-78) sender state. Populated by parseBip21 when
    // the pasted URI carries a `pj=` endpoint and an amount. While
    // this is non-null the Review CTA flips to "Review PayJoin" and
    // routes through `wallet.payjoinNegotiate` → review dialog →
    // `wallet.payjoinSignAndBroadcast`. The original URI is kept
    // verbatim because the FFI re-parses it; Rust additionally refuses
    // unless the URI's address/amount equal the form fields we pass.
    // ANY edit of address / amount, a contact pick, Send MAX, batch or
    // coin control disarms PayJoin (the URI would no longer describe
    // what the form shows).
    var payjoinUri: String? by remember { mutableStateOf(null) }
    var payjoinEndpoint: String? by remember { mutableStateOf(null) }
    var payjoinReview: PayjoinReview? by remember { mutableStateOf(null) }
    val clearPayjoin: () -> Unit = {
        payjoinUri = null
        payjoinEndpoint = null
    }

    // Address-book picker state. The contacts list is loaded lazily
    // the first time the user taps "From book" so the SendScreen
    // first-paint stays free of an extra IO call.
    var pickerOpen by remember { mutableStateOf(false) }
    var pickerLoading by remember { mutableStateOf(false) }
    var pickerError: String? by remember { mutableStateOf(null) }
    var pickerContacts: List<ContactRecord> by remember { mutableStateOf(emptyList()) }
    // Helper text shown under the address field when the user picks a
    // contact, so the visible bech32m string is anchored to a name.
    // Cleared when the user manually edits the address.
    var pickedLabel: String? by remember { mutableStateOf(null) }

    // Batch recipients: optional additional rows that turn this build
    // into a multi-output send. Empty list = single recipient (the
    // original `address` + `amountText` fields above are the only
    // output).
    val extraRecipients = remember { mutableStateListOf<ExtraRecipient>() }

    // Resolved up front: effects, click handlers and coroutine callbacks
    // below are not composable.
    val notUnlocked = stringResource(Res.string.wsend_not_unlocked)
    val fetchFailed = stringResource(Res.string.wsend_fetch_failed)
    val loadFailed = stringResource(Res.string.wsend_load_failed)
    val payjoinFailed = stringResource(Res.string.wsend_payjoin_failed)
    val buildFailed = stringResource(Res.string.wsend_build_failed)
    val enterRecipient = stringResource(Res.string.wsend_enter_recipient)
    val enterPositiveAmount = stringResource(Res.string.wsend_enter_positive_amount)
    val feeUnavailableMessage = stringResource(Res.string.wsend_fee_unavailable_manual)
    val noUtxoSelected = stringResource(Res.string.wsend_no_utxo_selected)

    LaunchedEffect(Unit) {
        val wallet = WalletStore.current()
        if (wallet == null) {
            feeState = FeeEstimateState.Failed(notUnlocked)
            return@LaunchedEffect
        }
        // Local read (no network): the coins feed the fee preview's
        // input-count estimate and the coin-control list.
        if (utxoState is UtxoState.Idle) {
            utxoState = runCatching {
                withContext(Dispatchers.IO) { wallet.listUtxos() }
            }.fold(
                onSuccess = { UtxoState.Ready(it) },
                onFailure = { UtxoState.Idle },
            )
        }
        feeState = runCatching {
            withContext(Dispatchers.IO) { wallet.fetchFeeEstimates(EsploraEndpoint.current(), EsploraEndpoint.currentProxy()) }
        }.fold(
            onSuccess = { FeeEstimateState.Ready(it) },
            onFailure = { e ->
                val msg = e.userMessage(fetchFailed)
                FeeEstimateState.Failed(msg)
            },
        )
    }

    // UTXOs tab "Send selected" → coin control on, exactly those coins.
    // Applied only once the coin list is loaded, so a coin frozen in the
    // meantime is really filtered out; then handed back to the owner to
    // clear, so a later visit to Send starts on automatic selection.
    val utxosReady = utxoState as? UtxoState.Ready
    LaunchedEffect(preselectedOutpoints, utxosReady) {
        if (preselectedOutpoints.isEmpty() || utxosReady == null) return@LaunchedEffect
        val spendable = utxosReady.utxos.filter { !it.frozen }.map { it.outpoint }.toSet()
        sendMaxOn = false
        coinControlOn = true
        selectedOutpoints.clear()
        selectedOutpoints.addAll(preselectedOutpoints.filter { it in spendable })
        onPreselectionConsumed()
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background)
            .padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1.2f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (onBack != null) BhodlTopBar(title = stringResource(Res.string.wallet_tab_send), onBack = onBack)
            PanelCaption(stringResource(Res.string.wallet_send_recipient_header))
            Text(
                // Audit 2026-10 (L6) — derived from the wallet network
                // and the configured endpoint (was hard-coded "Signet").
                text = stringResource(
                    Res.string.wsend_fee_source,
                    ExplorerLinks.networkLabel(io.bhodl.desktop.wizard.WalletStore.network()),
                    ExplorerLinks.endpointHost(EsploraEndpoint.current()),
                ),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )

            OutlinedTextField(
                value = address,
                onValueChange = { rawInput ->
                    // B11 — the field wraps now (no singleLine), so a
                    // pasted trailing newline must not end up in it.
                    val input = rawInput.replace("\r", "").replace("\n", "")
                    // Detect a pasted BIP-21 URI (`bitcoin:tb1...?amount=0.001`)
                    // and split it across this form's fields. Bare
                    // addresses fall straight through. The URI's
                    // payload is treated as authoritative when present:
                    //   - explicit `amount=X` → write `amountText`
                    //     unconditionally (override any prior value).
                    //   - `pj=https://...` (+ amount) → arm the PayJoin badge.
                    //   - a `bitcoin:` URI that breaks the canonical
                    //     BIP-21 rules (duplicate keys, `req-*`, bad
                    //     amount) prefills NOTHING and shows the error.
                    // Rationale: a paste like
                    //   `bitcoin:addr?amount=0.001&pj=...`
                    // expresses the user's intent ("use these values"),
                    // so silently keeping a stale `amountText` from a
                    // previous paste / Send-MAX session is confusing
                    // (the visible form ends up describing a payment
                    // that does not match the URI). Typing
                    // `bitcoin:` char by char does NOT clobber the
                    // amount because the partial URI carries no
                    // amount=... param, so `parsed.amountSat` is null
                    // and the `if` below skips the write.
                    val parsed = parseBip21(input)
                    if (parsed != null && parsed.error == null) {
                        address = parsed.address
                        if (parsed.amountSat != null) {
                            amountText = parsed.amountSat.toString()
                        }
                        if (parsed.payjoinEndpoint != null && parsed.amountSat != null) {
                            payjoinUri = input.trim()
                            payjoinEndpoint = parsed.payjoinEndpoint
                        } else {
                            clearPayjoin()
                        }
                    } else {
                        address = input.trim()
                        clearPayjoin()
                        if (parsed?.error != null) {
                            state = BuildState.Failed(parsed.error.orEmpty(), stage = Res.string.wsend_invalid_uri)
                        }
                    }
                    // The user is now typing — drop the picked-contact
                    // label so we don't leave a stale "To: Carol" line
                    // anchored to a different address.
                    pickedLabel = null
                },
                label = { Text(stringResource(Res.string.wallet_send_address_field)) },
                // UX review 2026-10 (B11) — a single-line field scrolls
                // horizontally and hides the START of a long address,
                // which is the part the user verifies. Wrap instead, in
                // monospace so every character has the same width.
                singleLine = false,
                maxLines = ADDRESS_FIELD_MAX_LINES,
                textStyle = addressFieldTextStyle(),
                supportingText = if (pickedLabel != null || groupAddressInFours(address) != null) {
                    {
                        Column {
                            // B11 — the full address in blocks of four,
                            // for a chunk-by-chunk check.
                            groupAddressInFours(address)?.let { grouped ->
                                Text(
                                    text = grouped,
                                    style = BhodlTheme.typography.mono,
                                    color = BhodlTheme.colors.textSecondary,
                                    modifier = Modifier.testTag(SendTags.ADDRESS_GROUPED),
                                )
                            }
                            if (pickedLabel != null) {
                                Text(
                                    text = stringResource(Res.string.wsend_to_label, pickedLabel!!),
                                    style = BhodlTheme.typography.labelMedium,
                                    color = BhodlTheme.colors.textSecondary,
                                    modifier = Modifier.testTag(SendTags.PICKED_LABEL),
                                )
                            }
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.ADDRESS),
            )

            // Address-book picker entry point. Sits right under the
            // address field as a secondary action — the typical send
            // flow is paste-then-go, so we keep the picker
            // unobtrusive but never more than one tap away.
            OutlinedButton(
                onClick = {
                    if (pickerOpen) return@OutlinedButton
                    val wallet = WalletStore.current()
                    if (wallet == null) {
                        pickerError = notUnlocked
                        return@OutlinedButton
                    }
                    pickerLoading = true
                    pickerError = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { wallet.listContacts() }
                        }.onSuccess { contacts ->
                            pickerContacts = contacts
                            pickerOpen = true
                        }.onFailure { e ->
                            pickerError = e.userMessage(loadFailed)
                        }
                        pickerLoading = false
                    }
                },
                enabled = !pickerLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.PICK_FROM_BOOK),
            ) {
                Text(
                    if (pickerLoading) stringResource(Res.string.wallet_send_loading)
                    else stringResource(Res.string.wallet_send_from_book),
                )
            }
            if (pickerError != null) {
                Text(
                    text = pickerError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SendTags.PICK_ERROR),
                )
            }

            if (pickerOpen) {
                ContactPickerDialog(
                    contacts = pickerContacts,
                    onSelected = { contact ->
                        address = contact.address
                        clearPayjoin()
                        pickedLabel = contact.label
                        pickerOpen = false
                    },
                    onDismiss = { pickerOpen = false },
                )
            }

            // PayJoin badge: surfaces the receiver-advertised endpoint
            // when the pasted URI carried `pj=`. Tap on Review runs
            // `payjoinNegotiate` and opens the review dialog. The
            // ORIGINAL payment is already signed and shared with the
            // receiver at that point (BIP-78 requires it; the receiver
            // may broadcast it); only the PayJoin transaction itself is
            // signed after the user confirms in the dialog. The original
            // goes through the same absolute-fee guard as a normal send
            // (FeeTooHigh -> second confirmation). Send MAX, batch recipients and coin
            // control are NOT compatible with PayJoin (BIP-78 v1
            // expects a single-recipient build): enabling any of them
            // disarms PayJoin rather than being silently ignored.
            if (payjoinEndpoint != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SendTags.PAYJOIN_BADGE),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = BhodlTheme.colors.surfaceVariant,
                        contentColor = BhodlTheme.colors.textPrimary,
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.wsend_payjoin_detected),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.brandPrimary,
                        )
                        Text(
                            text = payjoinEndpoint!!,
                            style = BhodlTheme.typography.mono,
                            color = BhodlTheme.colors.textSecondary,
                        )
                        Text(
                            text = stringResource(Res.string.wsend_payjoin_explainer),
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                        // Clearnet endpoints must be https:// (enforced
                        // by parseBip21 and again in Rust). Plain
                        // http:// is accepted only for `.onion`, which
                        // Rust refuses to reach without the Tor proxy —
                        // say so up front instead of failing on Review.
                        if (isOnionWithoutTor(
                                payjoinEndpoint!!,
                                io.bhodl.desktop.settings.SettingsStore.torEnabled.value,
                            )
                        ) {
                            Text(
                                text = stringResource(Res.string.wsend_payjoin_tor_required),
                                style = BhodlTheme.typography.bodyMedium,
                                color = BhodlTheme.colors.error,
                                modifier = Modifier.testTag(SendTags.PAYJOIN_TOR_REQUIRED),
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = clearPayjoin,
                            modifier = Modifier.testTag(SendTags.PAYJOIN_CLEAR),
                        ) {
                            Text(stringResource(Res.string.wsend_payjoin_clear))
                        }
                    }
                }
            }

            OutlinedTextField(
                value = if (sendMaxOn) stringResource(Res.string.wsend_send_all) else amountText,
                onValueChange = { input ->
                    val digits = input.filter { c -> c.isDigit() }
                    // Editing the amount disarms PayJoin: the URI's
                    // amount would no longer match what is shown.
                    if (digits != amountText) clearPayjoin()
                    amountText = digits
                },
                label = { Text(stringResource(Res.string.wallet_send_amount_field)) },
                singleLine = true,
                enabled = !sendMaxOn,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.AMOUNT),
            )

            // Send MAX: drains every spendable UTXO into a single
            // output to the recipient (no change). Mutually exclusive
            // with extra recipients and with coin control — BDK's
            // drain_wallet pattern is single-recipient by definition,
            // so we hide both extras and coin control as soon as
            // Send MAX is on.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.wallet_send_max),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textPrimary,
                    )
                    Text(
                        text = stringResource(Res.string.wallet_send_max_hint),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
                Switch(
                    checked = sendMaxOn,
                    onCheckedChange = { on ->
                        sendMaxOn = on
                        if (on) {
                            clearPayjoin()
                            // drain mode is single-recipient by
                            // definition; clear conflicting state.
                            extraRecipients.clear()
                            coinControlOn = false
                            selectedOutpoints.clear()
                        }
                    },
                    modifier = Modifier.testTag(SendTags.SEND_MAX_TOGGLE),
                )
            }

            // Optional extra outputs: each row pays an additional
            // recipient on the same PSBT. The first recipient stays in
            // the address+amount fields above so the simple "send to
            // one person" path is never burdened with a list editor.
            extraRecipients.forEachIndexed { index, draft ->
                ExtraRecipientRow(
                    index = index,
                    draft = draft,
                    onAddressChange = { newAddr ->
                        extraRecipients[index] = draft.copy(address = newAddr.trim())
                    },
                    onAmountChange = { newAmount ->
                        extraRecipients[index] = draft.copy(
                            amountText = newAmount.filter { it.isDigit() },
                        )
                    },
                    onRemove = { extraRecipients.removeAt(index) },
                )
            }
            OutlinedButton(
                onClick = {
                    clearPayjoin()
                    extraRecipients.add(ExtraRecipient.empty())
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.ADD_RECIPIENT),
            ) { Text(stringResource(Res.string.wallet_send_add_recipient)) }

            // Phase 7.2.3 — inline category picker. Collapsed by
            // default to keep the form compact (and to leave the
            // pre-existing Compose UI test viewports unchanged). When
            // expanded, the user can type a free category or tap an
            // existing chip from TrackerCache.categories. Tap toggle
            // also re-toggles the icon. Effective value still applies
            // even when collapsed (so the user can collapse after
            // typing), but the typical flow is type-then-submit.
            CategoryPickerToggle(
                expanded = categoryExpanded,
                draftPreview = effectiveCategory,
                onToggle = { categoryExpanded = !categoryExpanded },
            )
            if (categoryExpanded) {
                CategoryPicker(
                    draft = categoryDraft,
                    onDraftChange = { categoryDraft = it },
                    knownCategories = knownCategories,
                )
            }
        }

        // ── Right column: fee, coin control, Review ──────────────────
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PanelCaption(stringResource(Res.string.wallet_fee_header))
            val estimatesFailed = feeState is FeeEstimateState.Failed
            val customActive = estimatesFailed || (customFeeOpen && manualFeeText.isNotBlank())
            // Shape of the payment for the preset totals.
            val readyUtxos = (utxoState as? UtxoState.Ready)?.utxos.orEmpty()
            val quoteFor: (Long, Int?) -> FeeQuote = { rate, eta ->
                feeQuote(
                    rateSatVb = rate,
                    etaMinutes = eta,
                    recipientCount = 1 + extraRecipients.size,
                    amountSat = (amountText.toLongOrNull() ?: 0L) +
                        extraRecipients.sumOf { it.amountText.toLongOrNull() ?: 0L },
                    sendMax = sendMaxOn,
                    pinnedInputCount = if (coinControlOn) selectedOutpoints.size else 0,
                    spendableValuesSat = spendableValues(readyUtxos),
                )
            }
            FeeTierSelector(
                feeState = feeState,
                selected = if (customActive) null else selectedTier,
                onSelected = {
                    selectedTier = it
                    customFeeOpen = false
                },
                quoteFor = quoteFor,
            )
            CustomFeePanel(
                open = customFeeOpen || estimatesFailed,
                forced = estimatesFailed,
                onToggle = { customFeeOpen = !customFeeOpen },
                value = manualFeeText,
                onValueChange = { manualFeeText = it },
                active = customActive && !estimatesFailed,
                quote = manualFeeText.toLongOrNull()?.takeIf { it > 0 }?.let { quoteFor(it, null) },
            )

            // Inline advisory when the resolved fee rate looks
            // suspiciously high (>=100 sat/vB warning, >=500 sat/vB
            // hard warning). Same classifier the BumpFee screen uses.
            resolveFeeRate(feeState, selectedTier, manualFeeText, customActive)
                ?.let { FeeAdvisoryLine(rateSatVb = it) }

            PanelCaption(stringResource(Res.string.wallet_send_options_header))
            if (!coinControlOn) {
                Text(
                    text = stringResource(Res.string.wallet_coin_control_auto),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            CoinControlPanel(
                enabled = coinControlOn,
                onEnabledChange = { on ->
                    coinControlOn = on
                    if (on) clearPayjoin()
                    if (on && utxoState is UtxoState.Idle) {
                        scope.launch {
                            val wallet = WalletStore.current()
                            if (wallet == null) {
                                utxoState = UtxoState.Failed(notUnlocked)
                                return@launch
                            }
                            utxoState = UtxoState.Loading
                            utxoState = runCatching {
                                withContext(Dispatchers.IO) { wallet.listUtxos() }
                            }.fold(
                                onSuccess = { UtxoState.Ready(it) },
                                onFailure = { e ->
                                    val msg = e.userMessage(loadFailed)
                                    UtxoState.Failed(msg)
                                },
                            )
                        }
                    }
                    if (!on) selectedOutpoints.clear()
                },
                state = utxoState,
                selectedOutpoints = selectedOutpoints,
            )

            val errorText = when (val s = state) {
                is BuildState.Failed -> s.stage?.let { stringResource(it, s.reason) } ?: s.reason
                else -> null
            }
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(SendTags.ERROR),
                )
            }

            Button(
                onClick = onClick@{
                    if (state is BuildState.Loading) return@onClick
                    if (address.isEmpty()) {
                        state = BuildState.Failed(enterRecipient)
                        return@onClick
                    }
                    // PayJoin (BIP-78) step 1: negotiate. Rust builds +
                    // signs the original, POSTs it, validates the
                    // receiver's proposal and parks it; the review
                    // dialog below shows every output before step 2
                    // (`payjoinSignAndBroadcast`) is allowed to run.
                    // The address/amount the form displays are passed
                    // along and Rust refuses if the URI disagrees.
                    val pjUri = payjoinUri
                    if (pjUri != null) {
                        val wallet = WalletStore.current()
                        if (wallet == null) {
                            state = BuildState.Failed(notUnlocked)
                            return@onClick
                        }
                        val shownAmount = amountText.toLongOrNull()
                        if (shownAmount == null || shownAmount <= 0L) {
                            state = BuildState.Failed(enterPositiveAmount)
                            return@onClick
                        }
                        val shownAddress = address
                        val feeRate = resolveFeeRate(feeState, selectedTier, manualFeeText, customActive)
                        if (feeRate == null) {
                            state = BuildState.Failed(feeUnavailableMessage)
                            return@onClick
                        }
                        // Local so the FeeTooHigh dialog can re-run the exact
                        // same negotiation with the explicit confirmation.
                        fun launchNegotiate(allowHighFee: Boolean) {
                            state = BuildState.Loading
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        wallet.payjoinNegotiate(
                                            uri = pjUri,
                                            expectedAddress = shownAddress,
                                            expectedAmountSat = shownAmount.toULong(),
                                            feeRateSatVb = feeRate.toULong(),
                                            proxyUrl = EsploraEndpoint.currentProxy(),
                                            allowHighFee = allowHighFee,
                                        )
                                    }
                                }.fold(
                                    onSuccess = { review ->
                                        // Negotiation may have revealed a
                                        // change address; refresh history.
                                        WalletStore.markTxEpochAdvanced()
                                        state = BuildState.Idle
                                        payjoinReview = review
                                    },
                                    onFailure = { e ->
                                        WalletStore.markTxEpochAdvanced()
                                        if (e is WalletException.FeeTooHigh) {
                                            // Refused BEFORE signing or contacting the receiver.
                                            state = BuildState.Idle
                                            highFeePrompt = HighFeePrompt(e.feeSat, e.limitSat) {
                                                launchNegotiate(allowHighFee = true)
                                            }
                                        } else {
                                            val raw = e.userMessage(payjoinFailed)
                                            state = BuildState.Failed(
                                                prettifyBroadcastError(raw, BroadcastContext.Send) ?: raw,
                                            )
                                        }
                                    },
                                )
                            }
                        }
                        launchNegotiate(allowHighFee = false)
                        return@onClick
                    }
                    val amountSat = if (sendMaxOn) {
                        // The drain path computes the actual amount
                        // server-side; we only need a placeholder
                        // for the navigation argument so the review
                        // screen has something to render. The fee
                        // shown there comes from the PSBT itself.
                        0L
                    } else {
                        amountText.toLongOrNull().also {
                            if (it == null || it <= 0L) {
                                state = BuildState.Failed(enterPositiveAmount)
                                return@onClick
                            }
                        } ?: return@onClick
                    }
                    val wallet = WalletStore.current()
                    if (wallet == null) {
                        state = BuildState.Failed(notUnlocked)
                        return@onClick
                    }
                    val feeRate = resolveFeeRate(feeState, selectedTier, manualFeeText, customActive)
                    if (feeRate == null) {
                        state = BuildState.Failed(feeUnavailableMessage)
                        return@onClick
                    }
                    if (coinControlOn && selectedOutpoints.isEmpty()) {
                        state = BuildState.Failed(noUtxoSelected)
                        return@onClick
                    }
                    val pinnedOutpoints: List<String>? =
                        if (coinControlOn) selectedOutpoints.toList() else null

                    // Validate any batch entries up front so we don't
                    // bother BDK with garbage. Each row needs a non-blank
                    // address and a positive integer amount; empty rows
                    // get reported as a single error.
                    val batchEntries: List<RecipientEntry>? = if (extraRecipients.isEmpty()) {
                        null
                    } else {
                        val parsed = mutableListOf<RecipientEntry>()
                        parsed += RecipientEntry(address = address, amountSat = amountSat.toULong())
                        for ((rowIdx, draft) in extraRecipients.withIndex()) {
                            val parsedAmount = draft.amountText.toLongOrNull()
                            if (draft.address.isBlank() || parsedAmount == null
                                || parsedAmount <= 0L
                            ) {
                                state = BuildState.Failed(
                                    (rowIdx + 2).toString(),
                                    stage = Res.string.wsend_recipient_incomplete,
                                )
                                return@onClick
                            }
                            parsed += RecipientEntry(
                                address = draft.address,
                                amountSat = parsedAmount.toULong(),
                            )
                        }
                        parsed
                    }
                    val drain = sendMaxOn
                    val toAddress = address
                    val category = effectiveCategory
                    // Local so the FeeTooHigh dialog can re-run the exact
                    // same build with the explicit high-fee confirmation.
                    fun launchBuild(allowHighFee: Boolean) {
                        state = BuildState.Loading
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    when {
                                        drain -> wallet.buildDrainTx(
                                            toAddress = toAddress,
                                            feeRateSatVb = feeRate.toULong(),
                                            allowHighFee = allowHighFee,
                                        )
                                        batchEntries != null && pinnedOutpoints != null ->
                                            wallet.buildSendTxBatchWithUtxos(
                                                recipients = batchEntries,
                                                feeRateSatVb = feeRate.toULong(),
                                                outpoints = pinnedOutpoints,
                                                allowHighFee = allowHighFee,
                                            )
                                        batchEntries != null -> wallet.buildSendTxBatch(
                                            recipients = batchEntries,
                                            feeRateSatVb = feeRate.toULong(),
                                            allowHighFee = allowHighFee,
                                        )
                                        pinnedOutpoints != null -> wallet.buildSendTxWithUtxos(
                                            toAddress = toAddress,
                                            amountSat = amountSat.toULong(),
                                            feeRateSatVb = feeRate.toULong(),
                                            outpoints = pinnedOutpoints,
                                            allowHighFee = allowHighFee,
                                        )
                                        else -> wallet.buildSendTx(
                                            toAddress = toAddress,
                                            amountSat = amountSat.toULong(),
                                            feeRateSatVb = feeRate.toULong(),
                                            allowHighFee = allowHighFee,
                                        )
                                    }
                                }
                            }.fold(
                                onSuccess = { psbt ->
                                    state = BuildState.Idle
                                    // The Review screen renders ONLY the
                                    // Rust-decoded PSBT (all outputs, fee,
                                    // total). The amount passed here is a
                                    // cross-check hint: 0 for Send MAX,
                                    // whose payout is whatever the PSBT says.
                                    onReady(psbt, toAddress, if (drain) 0L else amountSat, category)
                                },
                                onFailure = { e ->
                                    if (e is WalletException.FeeTooHigh) {
                                        state = BuildState.Idle
                                        highFeePrompt = HighFeePrompt(e.feeSat, e.limitSat) {
                                            launchBuild(allowHighFee = true)
                                        }
                                    } else {
                                        state = BuildState.Failed(e.userMessage(buildFailed))
                                    }
                                },
                            )
                        }
                    }
                    launchBuild(allowHighFee = false)
                },
                enabled = state !is BuildState.Loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.REVIEW),
            ) {
                Text(
                    text = when {
                        state is BuildState.Loading && payjoinUri != null -> stringResource(Res.string.wallet_send_contacting_payjoin)
                        state is BuildState.Loading -> stringResource(Res.string.wallet_send_building)
                        payjoinUri != null -> stringResource(Res.string.wallet_send_review_payjoin)
                        else -> stringResource(Res.string.wallet_send_review)
                    },
                )
            }

            // PayJoin step 2: explicit review of the negotiated
            // transaction. Confirm signs ONLY our inputs of the
            // validated proposal (or, when negotiation failed,
            // broadcasts the already-signed original as a regular
            // payment). Cancel discards the Rust-side session.
            payjoinReview?.let { review ->
                PayjoinReviewDialog(
                    review = review,
                    onConfirm = {
                        val wallet = WalletStore.current()
                        payjoinReview = null
                        if (wallet == null) {
                            state = BuildState.Failed(notUnlocked)
                            return@PayjoinReviewDialog
                        }
                        state = BuildState.Loading
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val sessionId = review.sessionId
                                    if (sessionId != null) {
                                        wallet.payjoinSignAndBroadcast(
                                            sessionId = sessionId,
                                            esploraUrl = EsploraEndpoint.current(),
                                            proxyUrl = EsploraEndpoint.currentProxy(),
                                        )
                                    } else {
                                        // Fallback original: Rust already ran the
                                        // absolute-fee guard on it (and the user
                                        // confirmed any FeeTooHigh) before signing.
                                        wallet.broadcastTx(
                                            esploraUrl = EsploraEndpoint.current(),
                                            signedPsbtB64 = review.originalPsbt,
                                            proxyUrl = EsploraEndpoint.currentProxy(),
                                        )
                                    }
                                }
                            }.fold(
                                onSuccess = { txid ->
                                    state = BuildState.Idle
                                    clearPayjoin()
                                    WalletStore.markTxEpochAdvanced()
                                    // Phase 7.2.3 — pre-tag the
                                    // PaymentEvent with the inline
                                    // category if the user supplied
                                    // one. Best-effort: a failure here
                                    // shouldn't roll back the broadcast.
                                    if (effectiveCategory != null) {
                                        val activeId = WalletStore.activeWalletId()
                                        val registry = RegistryStore.current()
                                        if (activeId != null && registry != null) {
                                            // Under the rows the tracker reads (the watch-only
                                            // mirror), not the signing id it never lists.
                                            val portForCategory = WalletStore.current()
                                            if (portForCategory != null) {
                                                withContext(Dispatchers.IO) {
                                                    io.bhodl.desktop.data.writeTrackerCategory(registry, portForCategory, activeId, txid, effectiveCategory)
                                                }
                                            }
                                        }
                                    }
                                    onPayjoinSent(txid)
                                },
                                onFailure = { e ->
                                    val raw = e.userMessage(payjoinFailed)
                                    WalletStore.markTxEpochAdvanced()
                                    state = BuildState.Failed(
                                        prettifyBroadcastError(raw, BroadcastContext.Send) ?: raw,
                                    )
                                },
                            )
                        }
                    },
                    onCancel = {
                        payjoinReview = null
                        val sessionId = review.sessionId
                        val wallet = WalletStore.current()
                        if (sessionId != null && wallet != null) {
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { wallet.payjoinDiscard(sessionId) }
                                }
                            }
                        }
                    },
                )
            }

            highFeePrompt?.let { prompt ->
                HighFeeBuildDialog(
                    feeSat = prompt.feeSat,
                    limitSat = prompt.limitSat,
                    onConfirm = {
                        highFeePrompt = null
                        prompt.rebuild()
                    },
                    onCancel = { highFeePrompt = null },
                )
            }

            if (onBack != null) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SendTags.BACK),
                ) {
                    Text(stringResource(Res.string.action_back))
                }
            }
        }
    }
}

/** A build refused with `FeeTooHigh`, waiting for the user's decision. */
private class HighFeePrompt(
    val feeSat: ULong,
    val limitSat: ULong,
    val rebuild: () -> Unit,
)

/** Test tags exposed by [`SendScreen`]. */
public object SendTags {
    public const val ADDRESS: String = "send.address"
    public const val AMOUNT: String = "send.amount"
    public const val REVIEW: String = "send.review"
    public const val BACK: String = "send.back"
    /** Inline validation / build error line above the Review button. */
    public const val ERROR: String = "send.error"
    /** Fee tier chip: `economy`, `normal` or `priority`. */
    public fun feeTier(tier: String): String = "send.fee-tier.$tier"
    public const val ADD_RECIPIENT: String = "send.add-recipient"
    public const val COIN_CONTROL_TOGGLE: String = "send.coin-control.toggle"
    public const val FEE_ADVISORY: String = "send.fee-advisory"
    /** "Fee estimate unavailable" notice shown instead of a silent default. */
    public const val FEE_UNAVAILABLE: String = "send.fee-unavailable"
    /** Manual fee-rate field (shown when estimates are unavailable). */
    public const val MANUAL_FEE: String = "send.manual-fee"
    /** Expander of the collapsible custom fee panel. */
    public const val CUSTOM_FEE_TOGGLE: String = "send.custom-fee.toggle"
    public const val SEND_MAX_TOGGLE: String = "send.send-max.toggle"
    public const val PICK_FROM_BOOK: String = "send.pick-from-book"
    public const val PICK_ERROR: String = "send.pick-error"
    public const val PICKED_LABEL: String = "send.picked-label"
    /** B11 — the recipient address rendered in blocks of four. */
    public const val ADDRESS_GROUPED: String = "send.address-grouped"
    public const val PAYJOIN_BADGE: String = "send.payjoin-badge"
    /** `.onion` PayJoin endpoint while Tor is off (Rust would refuse). */
    public const val PAYJOIN_TOR_REQUIRED: String = "send.payjoin-tor-required"
    /** Badge action that disarms PayJoin and pays normally. */
    public const val PAYJOIN_CLEAR: String = "send.payjoin-clear"
    /** Review dialog shown after `payjoinNegotiate`. */
    public const val PAYJOIN_REVIEW_DIALOG: String = "send.payjoin-review"
    public const val PAYJOIN_CONFIRM: String = "send.payjoin-confirm"
    public const val PAYJOIN_CANCEL: String = "send.payjoin-cancel"
    /** Failure explanation when negotiation fell back to the original. */
    public const val PAYJOIN_FAILURE: String = "send.payjoin-failure"
    /** Row for the Nth output of the reviewed PayJoin transaction. */
    public fun payjoinOutput(index: Int): String = "send.payjoin-output.$index"
    /** Phase 7.2.3 — inline category text field on the Send form. */
    public const val CATEGORY_INPUT: String = "send.category.input"
    /** Phase 7.2.3 — toggle to reveal the category picker. */
    public const val CATEGORY_TOGGLE: String = "send.category.toggle"
    /** Phase 7.2.3 — chip for a quick-pick suggested category. */
    public fun categoryChip(name: String): String = "send.category.chip.$name"

    /** Tag for the address field of the Nth extra recipient (0-indexed). */
    public fun extraAddress(index: Int): String = "send.extra-address.$index"

    /** Tag for the amount field of the Nth extra recipient (0-indexed). */
    public fun extraAmount(index: Int): String = "send.extra-amount.$index"

    /** Tag for the checkbox of the UTXO with the given outpoint. */
    public fun utxoCheckbox(outpoint: String): String = "send.utxo.$outpoint"

    /** Tag for the FROZEN badge of the UTXO with the given outpoint. */
    public fun utxoFrozenBadge(outpoint: String): String = "send.utxo.frozen.$outpoint"
}

/**
 * `true` when [endpoint] targets a `.onion` host while Tor is off —
 * the Rust sender refuses that (no system-DNS lookups of onion names).
 */
internal fun isOnionWithoutTor(endpoint: String, torEnabled: Boolean): Boolean {
    if (torEnabled) return false
    val host = runCatching { java.net.URI(endpoint).host }.getOrNull() ?: return false
    return host.lowercase().removeSuffix(".").endsWith(".onion")
}

/**
 * PayJoin review: every output of the negotiated transaction (from the
 * Rust summary, not from the form), the fee and its delta vs. the
 * original. When negotiation failed, explains why and offers to send
 * the original as a regular payment.
 */
@Composable
private fun PayjoinReviewDialog(
    review: PayjoinReview,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val negotiated = review.sessionId != null
    val fmt = rememberAmountFormatter()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(SendTags.PAYJOIN_REVIEW_DIALOG),
        title = {
            Text(
                stringResource(
                    if (negotiated) Res.string.wsend_payjoin_review_title else Res.string.wsend_payjoin_unavailable,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!negotiated) {
                    Text(
                        text = stringResource(
                            Res.string.wsend_payjoin_failure,
                            review.failureReason ?: stringResource(Res.string.wsend_payjoin_unknown_error),
                        ),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.error,
                        modifier = Modifier.testTag(SendTags.PAYJOIN_FAILURE),
                    )
                }
                Text(
                    text = stringResource(Res.string.wsend_payjoin_outputs),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                review.outputs.forEachIndexed { i, out ->
                    val role = when {
                        out.isPayment -> stringResource(Res.string.wsend_payjoin_role_payment)
                        out.isMine -> stringResource(Res.string.wsend_psbt_role_change)
                        else -> stringResource(Res.string.wsend_payjoin_role_receiver)
                    }
                    Column(modifier = Modifier.fillMaxWidth().testTag(SendTags.payjoinOutput(i))) {
                        Text(
                            text = "${fmt(out.amountSat.toLong())} · $role",
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.textPrimary,
                        )
                        Text(
                            text = out.address,
                            style = BhodlTheme.typography.mono,
                            color = BhodlTheme.colors.textSecondary,
                        )
                    }
                }
                val feeDelta = review.proposalFeeSat.toLong() - review.originalFeeSat.toLong()
                val feeLine = stringResource(Res.string.wsend_payjoin_fee, fmt(review.proposalFeeSat.toLong()))
                val feeOriginal = stringResource(
                    Res.string.wsend_payjoin_fee_original,
                    fmt(review.originalFeeSat.toLong()),
                    (if (feeDelta >= 0) "+" else "") + fmt(feeDelta),
                )
                val feeShare = stringResource(
                    Res.string.wsend_payjoin_fee_share,
                    fmt(review.senderFeeContributionSat.toLong()),
                )
                val receiverInputs = stringResource(
                    Res.string.wsend_payjoin_receiver_inputs,
                    review.receiverInputCount.toInt(),
                )
                val totalLeaving = stringResource(
                    Res.string.wsend_payjoin_total_leaving,
                    fmt(review.senderNetSpendSat.toLong()),
                )
                val sizeLine = stringResource(Res.string.wsend_payjoin_size, review.vsize.toLong())
                Text(
                    text = buildString {
                        append(feeLine)
                        if (negotiated) {
                            append(" ")
                            append(feeOriginal)
                            append("\n")
                            append(feeShare)
                            append("\n")
                            append(receiverInputs)
                        }
                        append("\n")
                        append(totalLeaving)
                        append("\n")
                        append(sizeLine)
                    },
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.wsend_payjoin_original_note),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag(SendTags.PAYJOIN_CONFIRM),
            ) {
                Text(
                    stringResource(
                        if (negotiated) Res.string.wsend_payjoin_sign else Res.string.wsend_payjoin_send_without,
                    ),
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag(SendTags.PAYJOIN_CANCEL),
            ) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

@Composable
private fun ExtraRecipientRow(
    index: Int,
    draft: ExtraRecipient,
    onAddressChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = BhodlTheme.colors.surface,
            contentColor = BhodlTheme.colors.textPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.wsend_recipient_n, index + 2),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                androidx.compose.material3.TextButton(onClick = onRemove) {
                    Text(stringResource(Res.string.wsend_remove))
                }
            }
            OutlinedTextField(
                value = draft.address,
                onValueChange = { onAddressChange(it.replace("\r", "").replace("\n", "")) },
                label = { Text(stringResource(Res.string.wsend_address)) },
                // B11 / B14 — wrap, never clip the start of the address.
                singleLine = false,
                maxLines = ADDRESS_FIELD_MAX_LINES,
                textStyle = addressFieldTextStyle(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.extraAddress(index)),
            )
            OutlinedTextField(
                value = draft.amountText,
                onValueChange = onAmountChange,
                label = { Text(stringResource(Res.string.wsend_amount_sat)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.extraAmount(index)),
            )
        }
    }
}

@Composable
private fun CoinControlPanel(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    state: UtxoState,
    selectedOutpoints: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.wallet_send_coin_control),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.wallet_send_coin_control_hint),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                modifier = Modifier.testTag(SendTags.COIN_CONTROL_TOGGLE),
            )
        }

        if (enabled) {
            when (state) {
                UtxoState.Idle, UtxoState.Loading -> Text(
                    text = stringResource(Res.string.wsend_loading_utxos),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                is UtxoState.Failed -> Text(
                    text = state.reason,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                is UtxoState.Ready -> if (state.utxos.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.wsend_no_spendable_utxos),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                } else {
                    state.utxos.forEach { utxo ->
                        UtxoRow(
                            utxo = utxo,
                            checked = selectedOutpoints.contains(utxo.outpoint),
                            onCheckedChange = { now ->
                                if (utxo.frozen) {
                                    // Frozen coins are refused by the core;
                                    // never let them into the selection.
                                    selectedOutpoints.remove(utxo.outpoint)
                                } else if (now) {
                                    if (!selectedOutpoints.contains(utxo.outpoint)) {
                                        selectedOutpoints.add(utxo.outpoint)
                                    }
                                } else {
                                    selectedOutpoints.remove(utxo.outpoint)
                                }
                            },
                        )
                    }
                    Text(
                        text = stringResource(Res.string.wsend_selected_n_of_m, selectedOutpoints.size, state.utxos.size),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun UtxoRow(
    utxo: UtxoRecord,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val fmt = rememberAmountFormatter()
    // The whole row is the click target — cleaner mobile/desktop UX
    // (bigger hit area) and makes the row reliably testable.
    // toggleable carries Role.Checkbox semantics so the test framework
    // sees the node as a checkbox toggle and routes performClick
    // straight to onValueChange.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked && !utxo.frozen) BhodlTheme.colors.brandPrimary.copy(alpha = 0.10f) else BhodlTheme.colors.surface)
            .testTag(SendTags.utxoCheckbox(utxo.outpoint))
            .toggleable(
                value = checked && !utxo.frozen,
                enabled = !utxo.frozen,
                role = androidx.compose.ui.semantics.Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // `onCheckedChange = null` makes the checkbox display-only;
        // clicks land on the row.
        Checkbox(
            checked = checked && !utxo.frozen,
            onCheckedChange = null,
            enabled = !utxo.frozen,
        )
        Text(
            // UX review 2026-10 — unit-aware, tabular figures.
            text = fmt(utxo.valueSat.toLong()),
            style = BhodlTheme.typography.amountSmall,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        val outpointShort = utxo.outpoint.let { op ->
            val sep = op.indexOf(':')
            if (sep > 16) "${op.take(8)}…${op.substring(sep)}" else op
        }
        Text(
            text = outpointShort,
            style = BhodlTheme.typography.mono,
            color = BhodlTheme.colors.textSecondary,
        )
        Text(
            text = stringResource(if (utxo.isChange) Res.string.wsend_utxo_change else Res.string.wsend_utxo_receive) +
                " · " +
                stringResource(if (utxo.confirmed) Res.string.wsend_utxo_confirmed else Res.string.wsend_utxo_mempool),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        if (utxo.frozen) {
            // Frozen is a deliberate state, not an error: amber, not red.
            Text(
                text = stringResource(Res.string.wsend_utxo_frozen),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.financialPending,
                modifier = Modifier.testTag(SendTags.utxoFrozenBadge(utxo.outpoint)),
            )
        }
    }
}

@Composable
private fun FeeTierSelector(
    feeState: FeeEstimateState,
    /** `null` while a custom rate is in use (no preset highlighted). */
    selected: FeeTier?,
    onSelected: (FeeTier) -> Unit,
    quoteFor: (rateSatVb: Long, etaMinutes: Int?) -> FeeQuote,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (feeState) {
            FeeEstimateState.Loading -> Text(
                text = stringResource(Res.string.wsend_fetching_fees),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
            )
            is FeeEstimateState.Failed -> Text(
                text = stringResource(Res.string.wsend_fee_unavailable_reason, feeState.reason),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
                modifier = Modifier.testTag(SendTags.FEE_UNAVAILABLE),
            )
            is FeeEstimateState.Ready -> Unit
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Slowest → fastest, left to right.
            FeeTier.entries.forEach { tier ->
                val rate = (feeState as? FeeEstimateState.Ready)?.estimates?.let { tier.rateOf(it) }
                FeeTierChip(
                    label = stringResource(tier.labelRes()),
                    rate = rate,
                    quote = rate?.let { quoteFor(it, tier.etaMinutes) },
                    selected = selected == tier,
                    onClick = { onSelected(tier) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SendTags.feeTier(tier.name.lowercase())),
                )
            }
        }
        if (feeState is FeeEstimateState.Ready) {
            Text(
                text = stringResource(Res.string.wallet_fee_estimate_note),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
    }
}

/**
 * One urgency preset: name, rate, estimated **total** fee (BDG: frame
 * the choice as cost + time, not sat/vB), ETA and the operational fiat
 * equivalent when a price is cached.
 */
@Composable
private fun FeeTierChip(
    label: String,
    rate: Long?,
    quote: FeeQuote?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fmt = rememberAmountFormatter()
    val borderColor = if (selected) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.controlBorder
    val containerColor = if (selected) BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f)
    else BhodlTheme.colors.surface
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            // Exposes the highlight to accessibility / UI tests.
            .semantics { this.selected = selected }
            .padding(vertical = 8.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            if (quote?.etaMinutes != null) {
                Text(
                    text = etaText(quote.etaMinutes),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
        }
        Text(
            text = quote?.let { stringResource(Res.string.wallet_fee_total, fmt(it.feeSat)) } ?: "—",
            style = BhodlTheme.typography.amountSmall,
            color = BhodlTheme.colors.textPrimary,
        )
        Text(
            text = rate?.let { "$it sat/vB" } ?: "—",
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        quote?.let { fiatEquivalent(it.feeSat) }?.let { fiat ->
            Text(
                text = fiat,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
    }
}

/** "≈ €0.42" for [sat] when a fiat currency is on and a price is cached. */
@Composable
private fun fiatEquivalent(sat: Long): String? {
    val currency by io.bhodl.desktop.settings.SettingsStore.fiatCurrency
    val price by io.bhodl.desktop.prices.PriceCache.price
    if (currency == io.bhodl.desktop.prices.FiatCurrency.NONE || price == null || sat < 0) return null
    val value = io.bhodl.desktop.prices.PriceCache.satToFiat(sat.toULong()) ?: return null
    return "≈ " + io.bhodl.desktop.ui.formatFiatWithCode(value, currency.code, masked = io.bhodl.desktop.ui.money.amountsMasked())
}

/**
 * Collapsible custom fee rate (progressive disclosure: presets first).
 * Forced open — without a collapse affordance — when estimates are
 * unavailable, because then a manual rate is the only way to build.
 */
@Composable
private fun CustomFeePanel(
    open: Boolean,
    forced: Boolean,
    onToggle: () -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    active: Boolean,
    quote: FeeQuote?,
) {
    val fmt = rememberAmountFormatter()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!forced) {
            Text(
                text = (if (open) "− " else "+ ") + stringResource(Res.string.wallet_fee_custom),
                style = BhodlTheme.typography.labelMedium,
                color = if (active) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onToggle)
                    .padding(vertical = 4.dp)
                    .testTag(SendTags.CUSTOM_FEE_TOGGLE),
            )
        }
        if (open) {
            if (!forced) {
                Text(
                    text = stringResource(Res.string.wallet_fee_custom_hint),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            OutlinedTextField(
                value = value,
                onValueChange = { input -> onValueChange(input.filter { it.isDigit() }) },
                label = { Text(stringResource(Res.string.wallet_fee_custom_field)) },
                singleLine = true,
                supportingText = if (quote != null) {
                    { Text(stringResource(Res.string.wallet_fee_total, fmt(quote.feeSat))) }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SendTags.MANUAL_FEE),
            )
            if (active) {
                Text(
                    text = stringResource(Res.string.wallet_fee_custom_active),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.brandPrimary,
                )
            }
        }
    }
}

@Composable
private fun FeeAdvisoryLine(rateSatVb: Long) {
    val advisory = io.bhodl.desktop.wizard.FeeRateClassifier.advisory(rateSatVb) ?: return
    val level = io.bhodl.desktop.wizard.FeeRateClassifier.classify(rateSatVb)
    val color = when (level) {
        io.bhodl.desktop.wizard.FeeRateClassifier.Level.Extreme ->
            BhodlTheme.colors.brandPrimary
        else -> BhodlTheme.colors.textSecondary
    }
    Text(
        text = advisory,
        style = BhodlTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SendTags.FEE_ADVISORY),
    )
}

/**
 * Fee rate to build with: the user's custom rate when [customActive]
 * (always the case when estimates are unavailable), otherwise the
 * selected preset. `null` when neither is usable — the caller must
 * refuse to build (never default to 1 sat/vB).
 */
private fun resolveFeeRate(
    state: FeeEstimateState,
    tier: FeeTier,
    manualText: String,
    customActive: Boolean,
): Long? {
    val ready = state as? FeeEstimateState.Ready
    if (ready == null || customActive) return manualText.toLongOrNull()?.takeIf { it > 0L }
    return tier.rateOf(ready.estimates)
}

private sealed class BuildState {
    data object Idle : BuildState()
    data object Loading : BuildState()
    /** [stage] (one `%1$s` arg) wraps [reason] at render time; null = [reason] as is. */
    data class Failed(val reason: String, val stage: StringResource? = null) : BuildState()
}

private sealed class FeeEstimateState {
    data object Loading : FeeEstimateState()
    data class Ready(val estimates: FeeEstimates) : FeeEstimateState()
    data class Failed(val reason: String) : FeeEstimateState()
}

private data class ExtraRecipient(
    val address: String,
    val amountText: String,
) {
    companion object {
        fun empty(): ExtraRecipient = ExtraRecipient(address = "", amountText = "")
    }
}

private sealed class UtxoState {
    /** Coin control toggle has never been switched on. */
    data object Idle : UtxoState()
    data object Loading : UtxoState()
    data class Ready(val utxos: List<UtxoRecord>) : UtxoState()
    data class Failed(val reason: String) : UtxoState()
}

/**
 * Phase 7.2.3 — collapsed-by-default toggle row that gates the
 * category picker below it. The label flips between "+ Add category"
 * and "Category: <draft>" so the user sees the chosen value at a
 * glance after collapsing the editor. Tap anywhere on the row to
 * expand / collapse.
 */
@Composable
private fun CategoryPickerToggle(
    expanded: Boolean,
    draftPreview: String?,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp)
            .testTag(SendTags.CATEGORY_TOGGLE),
    ) {
        Text(
            text = when {
                expanded && draftPreview != null -> "− " + stringResource(Res.string.wsend_category_value, draftPreview)
                expanded -> "− " + stringResource(Res.string.wsend_category_none)
                draftPreview != null -> "+ " + stringResource(Res.string.wsend_category_value, draftPreview)
                else -> "+ " + stringResource(Res.string.wsend_category_add)
            },
            style = BhodlTheme.typography.labelMedium,
            color = if (draftPreview != null) BhodlTheme.colors.accentNeon
            else BhodlTheme.colors.textSecondary,
        )
    }
}

/**
 * Phase 7.2.3 — inline category picker for the Send form.
 *
 * Two affordances:
 *  - Free-text [OutlinedTextField] for typing a new category (also
 *    handy when the autocomplete chips aren't visible because none
 *    exist yet).
 *  - A horizontal chip list of previously-used categories pulled
 *    from [TrackerCache.categories]. Tapping a chip just fills the
 *    text field; the user can still edit it before submitting.
 *
 * Keeping the chip list separate from the text field (instead of an
 * autocomplete dropdown) means the user always sees what's
 * available without typing first, which matches the Kubera /
 * Sparrow density philosophy of "show structure, don't hide it
 * behind a search".
 */
@Composable
private fun CategoryPicker(
    draft: String,
    onDraftChange: (String) -> Unit,
    knownCategories: List<String>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            singleLine = true,
            label = { Text(stringResource(Res.string.wsend_category_field)) },
            placeholder = { Text(stringResource(Res.string.wsend_category_placeholder)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SendTags.CATEGORY_INPUT),
        )
        if (knownCategories.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                knownCategories.forEach { c ->
                    val selected = draft.trim().equals(c, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                else BhodlTheme.colors.surfaceVariant,
                            )
                            .clickable { onDraftChange(c) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag(SendTags.categoryChip(c)),
                    ) {
                        Text(
                            text = c,
                            style = BhodlTheme.typography.labelMedium,
                            color = if (selected) BhodlTheme.colors.accentNeon
                            else BhodlTheme.colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * UX review 2026-10 (B11) — address inputs wrap onto at most this many
 * lines instead of scrolling horizontally (which hid the address start).
 * A 62-char P2TR / P2WSH address fits in two lines at the form width.
 */
internal const val ADDRESS_FIELD_MAX_LINES: Int = 3

/** Monospace text style for address inputs: equal-width characters. */
@Composable
internal fun addressFieldTextStyle(): androidx.compose.ui.text.TextStyle =
    BhodlTheme.typography.mono.copy(
        fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
        lineHeight = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp),
    )
