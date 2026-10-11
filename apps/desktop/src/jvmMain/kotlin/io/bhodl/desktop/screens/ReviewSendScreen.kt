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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.ui.BroadcastContext
import io.bhodl.desktop.ui.prettifyBroadcastError
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.PsbtSummary
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wallet_review_subtitle
import io.bhodl.shared.resources.wallet_review_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.wsend_not_unlocked
import io.bhodl.shared.resources.wsend_review_broadcast_error
import io.bhodl.shared.resources.wsend_review_broadcast_failed
import io.bhodl.shared.resources.wsend_review_broadcasting
import io.bhodl.shared.resources.wsend_review_cannot_review
import io.bhodl.shared.resources.wsend_review_confirm
import io.bhodl.shared.resources.wsend_review_decode_failed
import io.bhodl.shared.resources.wsend_review_decoding
import io.bhodl.shared.resources.wsend_review_mismatch
import io.bhodl.shared.resources.wsend_review_sign_error
import io.bhodl.shared.resources.wsend_review_sign_failed
import io.bhodl.shared.resources.wsend_review_signing
import io.bhodl.shared.resources.wsend_review_wrong_address
import io.bhodl.shared.resources.wsend_review_wrong_amount

/**
 * Step 2 of the send flow: show what the PSBT actually does and ask
 * the user to confirm.
 *
 * Everything on screen comes from `WalletPort.decodePsbt` — Rust
 * decodes the exact PSBT that will be signed (every output incl.
 * batch recipients and change, the fee, the vsize, what leaves the
 * wallet). The navigation arguments [toAddress] / [amountSat] are NOT
 * rendered; they are only a cross-check: when present, the decoded
 * PSBT must contain an output to [toAddress] (of [amountSat] when
 * > 0), otherwise signing is blocked.
 *
 * On "Confirm" we sign exactly [unsignedPsbtBase64] and broadcast it —
 * each step is a separate FFI call on `Dispatchers.IO` so a partial
 * failure surfaces the specific stage that failed. A fee above the
 * wallet's absolute-fee guard needs an extra explicit acknowledgement.
 */
@Composable
public fun ReviewSendScreen(
    unsignedPsbtBase64: String,
    toAddress: String,
    amountSat: Long,
    /** Phase 7.2.3 — optional inline category set on the Send form.
     *  Persisted via [io.bhodl.ffi.RegistryHandle.setEventCategory]
     *  after a successful broadcast so the resulting History row is
     *  pre-tagged. Null = no category provided. */
    category: String? = null,
    onSent: (txid: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var decodeState: DecodeState by remember { mutableStateOf(DecodeState.Loading) }
    var sendState: SendState by remember { mutableStateOf(SendState.Idle) }
    var highFeeAck by remember { mutableStateOf(false) }
    // Resolved up front: the effect and the coroutine callbacks are not composable.
    val notUnlocked = stringResource(Res.string.wsend_not_unlocked)
    val decodeFailed = stringResource(Res.string.wsend_review_decode_failed)
    val signFailed = stringResource(Res.string.wsend_review_sign_failed)
    val broadcastFailed = stringResource(Res.string.wsend_review_broadcast_failed)

    LaunchedEffect(unsignedPsbtBase64) {
        val wallet = WalletStore.current()
        if (wallet == null) {
            decodeState = DecodeState.Failed(notUnlocked)
            return@LaunchedEffect
        }
        decodeState = runCatching {
            withContext(Dispatchers.IO) { wallet.decodePsbt(unsignedPsbtBase64) }
        }.fold(
            onSuccess = { summary ->
                val mismatch = crossCheck(summary, toAddress, amountSat)
                if (mismatch != null) DecodeState.Mismatch(summary, mismatch)
                else DecodeState.Ready(summary)
            },
            onFailure = { e -> DecodeState.Failed(e.userMessage(decodeFailed)) },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        // UX review 2026-10 (§4 item 6) — wide two-column review: the
        // decoded summary + Confirm on the left, the input→output
        // diagram on the right.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 1100.dp)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BhodlTopBar(title = stringResource(Res.string.wallet_review_title), onBack = onBack)
            Text(
                text = stringResource(Res.string.wallet_review_subtitle),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (val d = decodeState) {
                        DecodeState.Loading -> Text(
                            text = stringResource(Res.string.wsend_review_decoding),
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                        is DecodeState.Failed -> Text(
                            text = stringResource(Res.string.wsend_review_cannot_review, d.reason),
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.testTag(ReviewSendTags.DECODE_ERROR),
                        )
                        is DecodeState.Mismatch -> {
                            PsbtSummaryCard(d.summary)
                            Text(
                                text = stringResource(Res.string.wsend_review_mismatch, stringResource(d.reason)),
                                style = BhodlTheme.typography.bodyMedium,
                                color = BhodlTheme.colors.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.testTag(ReviewSendTags.MISMATCH),
                            )
                        }
                        is DecodeState.Ready -> {
                            PsbtSummaryCard(d.summary)
                            HighFeeAcknowledgement(d.summary, highFeeAck) { highFeeAck = it }
                        }
                    }

                    val errorText = (sendState as? SendState.Failed)?.let { f ->
                        f.stage?.let { stringResource(it, f.reason) } ?: f.reason
                    }
                    if (errorText != null) {
                        Text(
                            text = errorText,
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }

                    val ready = decodeState as? DecodeState.Ready
                    val busy = sendState is SendState.Signing || sendState is SendState.Broadcasting
                    Button(
                        onClick = onClick@{
                            if (busy || ready == null) return@onClick
                            if (ready.summary.feeExceedsLimit && !highFeeAck) return@onClick
                            val wallet = WalletStore.current()
                            if (wallet == null) {
                                sendState = SendState.Failed(notUnlocked)
                                return@onClick
                            }
                            sendState = SendState.Signing
                            scope.launch {
                                // Sign exactly the PSBT that was decoded above;
                                // only flip to Broadcasting after sign succeeds
                                // so the status text is honest.
                                val signed = runCatching {
                                    withContext(Dispatchers.IO) { wallet.signPsbt(unsignedPsbtBase64) }
                                }.getOrElse { e ->
                                    val msg = e.userMessage(signFailed)
                                    sendState = SendState.Failed(msg, stage = Res.string.wsend_review_sign_error)
                                    return@launch
                                }
                                sendState = SendState.Broadcasting
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        wallet.broadcastTx(EsploraEndpoint.current(), signed, EsploraEndpoint.currentProxy())
                                    }
                                }.fold(
                                    onSuccess = { txid ->
                                        sendState = SendState.Idle
                                        // Broadcast committed; History needs
                                        // to re-reconcile next time it opens.
                                        WalletStore.markTxEpochAdvanced()
                                        // Phase 7.2.3 — pre-tag the
                                        // PaymentEvent with the inline
                                        // category from the Send form. Best-
                                        // effort: a failure here shouldn't
                                        // roll back the broadcast.
                                        if (category != null) {
                                            val activeId = WalletStore.activeWalletId()
                                            val registry = RegistryStore.current()
                                            if (activeId != null && registry != null) {
                                                // Under the rows the tracker reads (the watch-only
                                                // mirror), not the signing id it never lists.
                                                val portForCategory = WalletStore.current()
                                                if (portForCategory != null) {
                                                    withContext(Dispatchers.IO) {
                                                        io.bhodl.desktop.data.writeTrackerCategory(registry, portForCategory, activeId, txid, category)
                                                    }
                                                }
                                            }
                                        }
                                        onSent(txid)
                                    },
                                    onFailure = { e ->
                                        val raw = e.userMessage(broadcastFailed)
                                        // Failure may still have shifted the
                                        // wallet's mempool view (esp. with
                                        // `-26 rejecting replacement`). Force
                                        // a reconcile so we don't leave
                                        // stale entries lingering.
                                        WalletStore.markTxEpochAdvanced()
                                        sendState = prettifyBroadcastError(raw, BroadcastContext.Send)
                                            ?.let { SendState.Failed(it) }
                                            ?: SendState.Failed(raw, stage = Res.string.wsend_review_broadcast_error)
                                    },
                                )
                            }
                        },
                        enabled = ready != null && !busy &&
                            (!ready.summary.feeExceedsLimit || highFeeAck),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ReviewSendTags.CONFIRM),
                    ) {
                        Text(
                            text = when (sendState) {
                                SendState.Signing -> stringResource(Res.string.wsend_review_signing)
                                SendState.Broadcasting -> stringResource(Res.string.wsend_review_broadcasting)
                                else -> stringResource(Res.string.wsend_review_confirm)
                            },
                        )
                    }

                    OutlinedButton(
                        onClick = onBack,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ReviewSendTags.BACK),
                    ) {
                        Text(stringResource(Res.string.action_back))
                    }
                }
                val diagramSummary = when (val d = decodeState) {
                    is DecodeState.Ready -> d.summary
                    is DecodeState.Mismatch -> d.summary
                    else -> null
                }
                if (diagramSummary != null) {
                    TransactionDiagram(summary = diagramSummary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Cross-check the decoded PSBT against what the Send form asked for.
 * Returns the (localized) reason when the PSBT does not pay
 * [expectedAddress] (and [expectedAmountSat], when > 0), else `null`.
 * A blank [expectedAddress] skips the check.
 */
internal fun crossCheck(
    summary: PsbtSummary,
    expectedAddress: String,
    expectedAmountSat: Long,
): StringResource? {
    val address = expectedAddress.trim()
    if (address.isEmpty()) return null
    val matches = summary.outputs.filter { it.address.equals(address, ignoreCase = true) }
    if (matches.isEmpty()) {
        return Res.string.wsend_review_wrong_address
    }
    if (expectedAmountSat > 0 && matches.none { it.amountSat == expectedAmountSat.toULong() }) {
        return Res.string.wsend_review_wrong_amount
    }
    return null
}

private sealed class DecodeState {
    data object Loading : DecodeState()
    data class Ready(val summary: PsbtSummary) : DecodeState()
    data class Mismatch(val summary: PsbtSummary, val reason: StringResource) : DecodeState()
    data class Failed(val reason: String) : DecodeState()
}

private sealed class SendState {
    data object Idle : SendState()
    data object Signing : SendState()
    data object Broadcasting : SendState()
    /** [stage] (one `%1$s` arg) prefixes [reason] at render time ("Sign: …"). */
    data class Failed(val reason: String, val stage: StringResource? = null) : SendState()
}


/** Test tags exposed by [`ReviewSendScreen`]. */
public object ReviewSendTags {
    public const val CONFIRM: String = "review-send.confirm"
    public const val BACK: String = "review-send.back"
    public const val DECODE_ERROR: String = "review-send.decode-error"
    public const val MISMATCH: String = "review-send.mismatch"
}
