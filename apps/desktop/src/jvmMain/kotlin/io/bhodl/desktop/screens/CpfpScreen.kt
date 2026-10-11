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
import io.bhodl.ffi.CpfpPlan
import io.bhodl.ffi.PsbtSummary
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.wsend_cpfp_ancestors
import io.bhodl.shared.resources.wsend_cpfp_change_body
import io.bhodl.shared.resources.wsend_cpfp_change_review
import io.bhodl.shared.resources.wsend_cpfp_change_title
import io.bhodl.shared.resources.wsend_cpfp_child_fee
import io.bhodl.shared.resources.wsend_cpfp_failed
import io.bhodl.shared.resources.wsend_cpfp_incoming_body
import io.bhodl.shared.resources.wsend_cpfp_incoming_review
import io.bhodl.shared.resources.wsend_cpfp_incoming_title
import io.bhodl.shared.resources.wsend_cpfp_package_rate
import io.bhodl.shared.resources.wsend_cpfp_parent_fee
import io.bhodl.shared.resources.wsend_cpfp_review_title
import io.bhodl.shared.resources.wsend_cpfp_target
import io.bhodl.shared.resources.wsend_cpfp_target_rate
import io.bhodl.shared.resources.wsend_enter_positive_rate
import io.bhodl.shared.resources.wsend_sign_broadcast
import io.bhodl.shared.resources.wsend_wallet_not_unlocked
import io.bhodl.shared.resources.wsend_working

/**
 * Child-Pays-For-Parent (CPFP) speed-up screen.
 *
 * Three explicit steps, never one click:
 * 1. **Build** — `buildCpfpPsbt` creates a child spending **only** the
 *    wallet's outputs of the (unconfirmed) `parentTxid` into a fresh
 *    change address. The child fee is sized so the parent+child
 *    PACKAGE reaches the typed rate. Confirmed parents are refused.
 * 2. **Review** — the child is decoded by Rust (`decodePsbt`) and shown
 *    with the package arithmetic (parent fee / size, child fee / size,
 *    resulting package rate).
 * 3. **Sign + broadcast** exactly the reviewed PSBT.
 *
 * Two surfaces lead here:
 * - History row of an **incoming** pending tx → "Speed up".
 * - TransactionDetails of an **outgoing** pending tx with a stuck
 *   change UTXO → "Speed up via change".
 *
 * The variant is purely cosmetic (copy + button label).
 */
@Composable
public fun CpfpScreen(
    parentTxid: String,
    kind: CpfpKind,
    onSent: (newTxid: String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var feeRateText by remember { mutableStateOf("") }
    var state: CpfpState by remember { mutableStateOf(CpfpState.Idle) }
    var highFeePrompt: WalletException.FeeTooHigh? by remember { mutableStateOf(null) }

    val (title, body, primaryIdle, primaryWorking) = when (kind) {
        CpfpKind.IncomingSpeedUp -> CopyBundle(
            title = stringResource(Res.string.wsend_cpfp_incoming_title),
            body = stringResource(Res.string.wsend_cpfp_incoming_body),
            primaryIdle = stringResource(Res.string.wsend_cpfp_incoming_review),
            primaryWorking = stringResource(Res.string.wsend_working),
        )
        CpfpKind.ChangeRescue -> CopyBundle(
            title = stringResource(Res.string.wsend_cpfp_change_title),
            body = stringResource(Res.string.wsend_cpfp_change_body),
            primaryIdle = stringResource(Res.string.wsend_cpfp_change_review),
            primaryWorking = stringResource(Res.string.wsend_working),
        )
    }
    // Resolved up front: the coroutine callbacks below are not composable.
    val notUnlocked = stringResource(Res.string.wsend_wallet_not_unlocked)
    val speedUpFailed = stringResource(Res.string.wsend_cpfp_failed)

    fun build(rate: Long, allowHighFee: Boolean) {
        val wallet = WalletStore.current()
        if (wallet == null) {
            state = CpfpState.Failed(notUnlocked)
            return
        }
        state = CpfpState.Working
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val plan = wallet.buildCpfpPsbt(
                        parentTxid = parentTxid,
                        feeRateSatVb = rate.toULong(),
                        allowHighFee = allowHighFee,
                    )
                    CpfpState.Reviewing(plan, wallet.decodePsbt(plan.psbt))
                }
            }.fold(
                onSuccess = { state = it },
                onFailure = { e ->
                    if (e is WalletException.FeeTooHigh) {
                        state = CpfpState.Idle
                        highFeePrompt = e
                    } else {
                        state = CpfpState.Failed(e.userMessage(speedUpFailed))
                    }
                },
            )
        }
    }

    fun signAndBroadcast(psbt: String) {
        val wallet = WalletStore.current()
        if (wallet == null) {
            state = CpfpState.Failed(notUnlocked)
            return
        }
        state = CpfpState.Working
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val signed = wallet.signPsbt(psbt)
                    wallet.broadcastTx(
                        EsploraEndpoint.current(),
                        signed,
                        EsploraEndpoint.currentProxy(),
                    )
                }
            }.fold(
                onSuccess = { newTxid ->
                    state = CpfpState.Idle
                    // Broadcast introduced a new child / consumed parent
                    // UTXOs — force History to reconcile.
                    WalletStore.markTxEpochAdvanced()
                    onSent(newTxid)
                },
                onFailure = { e ->
                    val raw = e.userMessage(speedUpFailed)
                    // A `-25` rejection means the parent is no longer in
                    // the mempool we expected; flag a reconcile.
                    WalletStore.markTxEpochAdvanced()
                    state = CpfpState.Failed(
                        prettifyBroadcastError(raw, BroadcastContext.Cpfp) ?: raw,
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
            BhodlTopBar(title = title, onBack = onBack)
            Text(
                text = body,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = formatTruncatedTxid(parentTxid),
                style = BhodlTheme.typography.mono,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier.testTag(CpfpTags.PARENT_TXID),
            )

            OutlinedTextField(
                value = feeRateText,
                onValueChange = { input -> feeRateText = input.filter { it.isDigit() } },
                label = { Text(stringResource(Res.string.wsend_cpfp_target_rate)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CpfpTags.FEE_RATE),
            )

            // Same fee-rate advisory pattern the BumpFee + Send screens
            // use, so a "100 sat/vB" typo gets the same nudge here.
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
                            .testTag(CpfpTags.FEE_ADVISORY),
                    )
                }
            }

            val errorText = (state as? CpfpState.Failed)?.reason
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(CpfpTags.ERROR),
                )
            }

            val enterPositiveRate = stringResource(Res.string.wsend_enter_positive_rate)
            Button(
                onClick = onClick@{
                    if (state is CpfpState.Working) return@onClick
                    val rate = feeRateText.toLongOrNull()
                    if (rate == null || rate <= 0L) {
                        state = CpfpState.Failed(enterPositiveRate)
                        return@onClick
                    }
                    build(rate, allowHighFee = false)
                },
                enabled = state !is CpfpState.Working && feeRateText.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CpfpTags.CONFIRM),
            ) {
                Text(
                    text = if (state is CpfpState.Working) primaryWorking else primaryIdle,
                )
            }
            OutlinedButton(
                onClick = onBack,
                enabled = state !is CpfpState.Working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CpfpTags.BACK),
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
    val rowLabels = CpfpRowLabels(
        parentFee = stringResource(Res.string.wsend_cpfp_parent_fee),
        ancestors = stringResource(Res.string.wsend_cpfp_ancestors),
        childFee = stringResource(Res.string.wsend_cpfp_child_fee),
        packageRate = stringResource(Res.string.wsend_cpfp_package_rate),
        target = stringResource(Res.string.wsend_cpfp_target),
    )
    (state as? CpfpState.Reviewing)?.let { review ->
        PsbtReviewDialog(
            title = stringResource(Res.string.wsend_cpfp_review_title),
            summary = review.summary,
            extraRows = cpfpPackageRows(review.plan, fmtAmount, rowLabels),
            confirmLabel = stringResource(Res.string.wsend_sign_broadcast),
            onConfirm = { signAndBroadcast(review.plan.psbt) },
            onCancel = { state = CpfpState.Idle },
        )
    }
}

/**
 * Package arithmetic shown on the CPFP review (integer sats only). The
 * package is the parent plus every unconfirmed ancestor of it.
 */
internal fun cpfpPackageRows(
    plan: CpfpPlan,
    /** Amount formatter (the user unit; see `rememberAmountFormatter`). */
    fmt: (Long) -> String,
    /** Localized row labels, resolved by the composable caller. */
    labels: CpfpRowLabels,
): List<Pair<String, String>> {
    val packageFee = plan.parentFeeSat + plan.ancestorsFeeSat + plan.childFeeSat
    val packageVsize = plan.parentVsize + plan.ancestorsVsize + plan.childVsize
    return buildList {
        add(
            labels.parentFee to "${fmt(plan.parentFeeSat.toLong())} · ${plan.parentVsize} vB " +
                "(${formatFeeRate(plan.parentFeeSat, plan.parentVsize)})",
        )
        if (plan.ancestorCount > 0u) {
            add(
                labels.ancestors to "${plan.ancestorCount} tx · " +
                    "${fmt(plan.ancestorsFeeSat.toLong())} · ${plan.ancestorsVsize} vB " +
                    "(${formatFeeRate(plan.ancestorsFeeSat, plan.ancestorsVsize)})",
            )
        }
        add(labels.childFee to "${fmt(plan.childFeeSat.toLong())} · ${plan.childVsize} vB")
        add(
            labels.packageRate to "${formatFeeRate(packageFee, packageVsize)} " +
                "(${labels.target} ${plan.targetFeeRateSatVb} sat/vB)",
        )
    }
}

/** Localized labels of [cpfpPackageRows] ([target] is the word before the target rate). */
internal data class CpfpRowLabels(
    val parentFee: String,
    val ancestors: String,
    val childFee: String,
    val packageRate: String,
    val target: String,
)

/** Caller-supplied flavour: drives copy + button label only. */
public enum class CpfpKind { IncomingSpeedUp, ChangeRescue }

private data class CopyBundle(
    val title: String,
    val body: String,
    val primaryIdle: String,
    val primaryWorking: String,
)

private sealed class CpfpState {
    data object Idle : CpfpState()
    data object Working : CpfpState()
    data class Reviewing(val plan: CpfpPlan, val summary: PsbtSummary) : CpfpState()
    data class Failed(val reason: String) : CpfpState()
}

private fun formatTruncatedTxid(txid: String): String =
    if (txid.length <= 16) txid else "${txid.take(8)}…${txid.takeLast(8)}"

/** Test tags exposed by [`CpfpScreen`]. */
public object CpfpTags {
    public const val PARENT_TXID: String = "cpfp.parent-txid"
    public const val FEE_RATE: String = "cpfp.fee-rate"
    public const val FEE_ADVISORY: String = "cpfp.fee-advisory"
    /** Builds the child and opens the review (does NOT sign). */
    public const val CONFIRM: String = "cpfp.confirm"
    public const val BACK: String = "cpfp.back"
    public const val ERROR: String = "cpfp.error"
}
