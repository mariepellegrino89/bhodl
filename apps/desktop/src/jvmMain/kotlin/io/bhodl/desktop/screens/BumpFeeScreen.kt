package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.ffi.PsbtSummary
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.wsend_bump_failed
import io.bhodl.shared.resources.wsend_bump_fee_increase
import io.bhodl.shared.resources.wsend_bump_intro
import io.bhodl.shared.resources.wsend_bump_new_rate
import io.bhodl.shared.resources.wsend_bump_original_fee
import io.bhodl.shared.resources.wsend_bump_review
import io.bhodl.shared.resources.wsend_bump_sign_replace
import io.bhodl.shared.resources.wsend_bump_title
import io.bhodl.shared.resources.wsend_enter_positive_rate
import io.bhodl.shared.resources.wsend_wallet_not_unlocked
import io.bhodl.shared.resources.wsend_working

/**
 * BIP-125 RBF: bump the fee of a still-pending transaction.
 *
 * Three explicit steps, never one click:
 * 1. **Build** — `buildFeeBumpPsbt` at the typed rate (must strictly
 *    exceed the current rate, otherwise BDK rejects the no-op bump).
 *    A fee above the wallet's absolute-fee guard first asks for a
 *    second confirmation ([HighFeeBuildDialog]).
 * 2. **Review** — the replacement is decoded by Rust (`decodePsbt`)
 *    and shown in a dialog: every output, the new fee and the delta vs.
 *    the original.
 * 3. **Sign + broadcast** exactly the reviewed PSBT, then route to
 *    [`Route.SendSuccess`] with the new transaction id.
 */
@Composable
public fun BumpFeeScreen(
    originalTxid: String,
    onSent: (newTxid: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var feeRateText by remember { mutableStateOf("") }
    var state: BumpState by remember { mutableStateOf(BumpState.Idle) }
    var highFeePrompt: WalletException.FeeTooHigh? by remember { mutableStateOf(null) }
    // Resolved up front: the coroutine callbacks below are not composable.
    val notUnlocked = stringResource(Res.string.wsend_wallet_not_unlocked)
    val bumpFailed = stringResource(Res.string.wsend_bump_failed)

    fun build(rate: Long, allowHighFee: Boolean) {
        val wallet = WalletStore.current()
        if (wallet == null) {
            state = BumpState.Failed(notUnlocked)
            return
        }
        state = BumpState.Working
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val psbt = wallet.buildFeeBumpPsbt(
                        txid = originalTxid,
                        newFeeRateSatVb = rate.toULong(),
                        allowHighFee = allowHighFee,
                    )
                    val summary = wallet.decodePsbt(psbt)
                    val originalFee = runCatching {
                        wallet.listTransactions().firstOrNull { it.txid == originalTxid }?.feeSat
                    }.getOrNull()
                    BumpState.Reviewing(psbt, summary, originalFee)
                }
            }.fold(
                onSuccess = { state = it },
                onFailure = { e ->
                    if (e is WalletException.FeeTooHigh) {
                        state = BumpState.Idle
                        highFeePrompt = e
                    } else {
                        state = BumpState.Failed(e.userMessage(bumpFailed))
                    }
                },
            )
        }
    }

    fun signAndBroadcast(psbt: String) {
        val wallet = WalletStore.current()
        if (wallet == null) {
            state = BumpState.Failed(notUnlocked)
            return
        }
        state = BumpState.Working
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    // Snapshot the original first: once the replacement
                    // is in, BDK drops it from the canonical history.
                    val original = runCatching {
                        wallet.listTransactions().firstOrNull { it.txid == originalTxid }
                    }.getOrNull()
                    val signed = wallet.signPsbt(psbt)
                    val txid = wallet.broadcastTx(EsploraEndpoint.current(), signed, EsploraEndpoint.currentProxy())
                    original to txid
                }
            }.fold(
                onSuccess = { (original, newTxid) ->
                    // UX review 2026-10 (§4 item 8) — the Transactions
                    // tab collapses the original under its replacement.
                    io.bhodl.desktop.wallet.FeeBumpLog.record(original, originalTxid, newTxid)
                    state = BumpState.Idle
                    // The broadcast may have replaced or dropped pending
                    // txs in the wallet's mempool view; flag
                    // mempool-sensitive screens (History) to reconcile.
                    WalletStore.markTxEpochAdvanced()
                    onSent(newTxid)
                },
                onFailure = { e ->
                    val raw = e.userMessage(bumpFailed)
                    // Even a failure can mean mempool state shifted
                    // (`-25` / `-26`) — force a refresh next time.
                    WalletStore.markTxEpochAdvanced()
                    state = BumpState.Failed(
                        prettifyBroadcastError(raw, BroadcastContext.Rbf) ?: raw,
                    )
                },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 480.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BhodlTopBar(title = stringResource(Res.string.wsend_bump_title), onBack = onBack)
            Text(
                text = stringResource(Res.string.wsend_bump_intro),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = formatTruncatedTxid(originalTxid),
                style = BhodlTheme.typography.mono,
                color = BhodlTheme.colors.textTertiary,
            )

            OutlinedTextField(
                value = feeRateText,
                onValueChange = { input -> feeRateText = input.filter { it.isDigit() } },
                label = { Text(stringResource(Res.string.wsend_bump_new_rate)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(BumpFeeTags.FEE_RATE),
            )

            // Inline advisory if the typed rate is unusually high.
            // Same classifier the SendScreen uses; identical thresholds.
            val parsedRate = feeRateText.toLongOrNull()
            if (parsedRate != null && parsedRate > 0L) {
                val advisory = io.bhodl.desktop.wizard.FeeRateClassifier.advisory(parsedRate)
                if (advisory != null) {
                    val level = io.bhodl.desktop.wizard.FeeRateClassifier.classify(parsedRate)
                    val color = if (level == io.bhodl.desktop.wizard.FeeRateClassifier.Level.Extreme) {
                        BhodlTheme.colors.brandPrimary
                    } else {
                        BhodlTheme.colors.textSecondary
                    }
                    Text(
                        text = advisory,
                        style = BhodlTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(BumpFeeTags.FEE_ADVISORY),
                    )
                }
            }

            val errorText = (state as? BumpState.Failed)?.reason
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(BumpFeeTags.ERROR),
                )
            }

            val enterPositiveRate = stringResource(Res.string.wsend_enter_positive_rate)
            Button(
                onClick = onClick@{
                    if (state is BumpState.Working) return@onClick
                    val rate = feeRateText.toLongOrNull()
                    if (rate == null || rate <= 0L) {
                        state = BumpState.Failed(enterPositiveRate)
                        return@onClick
                    }
                    build(rate, allowHighFee = false)
                },
                enabled = state !is BumpState.Working && feeRateText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(BumpFeeTags.CONFIRM),
            ) {
                Text(
                    text = when (state) {
                        BumpState.Working -> stringResource(Res.string.wsend_working)
                        else -> stringResource(Res.string.wsend_bump_review)
                    },
                )
            }
            OutlinedButton(
                onClick = onBack,
                enabled = state !is BumpState.Working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(BumpFeeTags.BACK),
            ) { Text(stringResource(Res.string.action_back)) }
        }
    }

    highFeePrompt?.let { prompt ->
        HighFeeBuildDialog(
            feeSat = prompt.feeSat,
            limitSat = prompt.limitSat,
            onConfirm = {
                highFeePrompt = null
                feeRateText.toLongOrNull()?.let { build(it, allowHighFee = true) }
            },
            onCancel = { highFeePrompt = null },
        )
    }

    val fmtAmount = rememberAmountFormatter()
    val fmtSigned: (Long) -> String = { d -> if (d >= 0) "+${fmtAmount(d)}" else fmtAmount(d) }
    val originalFeeLabel = stringResource(Res.string.wsend_bump_original_fee)
    val feeIncreaseLabel = stringResource(Res.string.wsend_bump_fee_increase)
    (state as? BumpState.Reviewing)?.let { review ->
        val rows = buildList {
            review.originalFeeSat?.let { orig ->
                add(originalFeeLabel to fmtAmount(orig.toLong()))
                val delta = review.summary.feeSat.toLong() - orig.toLong()
                add(feeIncreaseLabel to fmtSigned(delta))
            }
        }
        PsbtReviewDialog(
            title = stringResource(Res.string.wsend_bump_review),
            summary = review.summary,
            extraRows = rows,
            confirmLabel = stringResource(Res.string.wsend_bump_sign_replace),
            onConfirm = { signAndBroadcast(review.psbt) },
            onCancel = { state = BumpState.Idle },
        )
    }
}

private fun formatTruncatedTxid(txid: String): String =
    if (txid.length <= 16) txid else "${txid.take(8)}…${txid.takeLast(8)}"

private sealed class BumpState {
    data object Idle : BumpState()
    data object Working : BumpState()
    data class Reviewing(
        val psbt: String,
        val summary: PsbtSummary,
        val originalFeeSat: ULong?,
    ) : BumpState()
    data class Failed(val reason: String) : BumpState()
}


/** Test tags exposed by [`BumpFeeScreen`]. */
public object BumpFeeTags {
    public const val FEE_RATE: String = "bump-fee.fee-rate"
    public const val FEE_ADVISORY: String = "bump-fee.fee-advisory"
    /** Builds the replacement and opens the review (does NOT sign). */
    public const val CONFIRM: String = "bump-fee.confirm"
    public const val BACK: String = "bump-fee.back"
    public const val ERROR: String = "bump-fee.error"
}
