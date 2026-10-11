package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.ffi.PsbtSummary
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.wsend_high_fee_body
import io.bhodl.shared.resources.wsend_high_fee_build_anyway
import io.bhodl.shared.resources.wsend_high_fee_title
import io.bhodl.shared.resources.wsend_psbt_fee_above_limit
import io.bhodl.shared.resources.wsend_psbt_foreign_inputs
import io.bhodl.shared.resources.wsend_psbt_high_fee_ack
import io.bhodl.shared.resources.wsend_psbt_network_fee
import io.bhodl.shared.resources.wsend_psbt_outputs
import io.bhodl.shared.resources.wsend_psbt_role_change
import io.bhodl.shared.resources.wsend_psbt_role_recipient
import io.bhodl.shared.resources.wsend_psbt_role_self
import io.bhodl.shared.resources.wsend_psbt_sent_others
import io.bhodl.shared.resources.wsend_psbt_size
import io.bhodl.shared.resources.wsend_psbt_total_leaving
import io.bhodl.shared.resources.wsend_psbt_unconfirmed_warning

/**
 * Shared review surface for every outgoing transaction (send, batch,
 * Send MAX, RBF bump, CPFP child).
 *
 * Everything shown here comes from [PsbtSummary], which Rust computes
 * from the PSBT bytes and the wallet's script index
 * (`WalletPort.decodePsbt`) — never from navigation arguments or form
 * state. The caller must sign exactly the PSBT string it decoded.
 *
 * Amounts are integer satoshis, rendered through the app-wide unit
 * formatter (UX review 2026-10: Sats / BTC / Auto, BIP177, locale
 * separators); the fee rate is derived with integer arithmetic
 * ([formatFeeRate]) — no floating point in amount paths.
 */
@Composable
internal fun PsbtSummaryCard(
    summary: PsbtSummary,
    extraRows: List<Pair<String, String>> = emptyList(),
) {
    val fmt = rememberAmountFormatter()
    Card(
        modifier = Modifier.fillMaxWidth().testTag(PsbtReviewTags.CARD),
        colors = CardDefaults.cardColors(
            containerColor = BhodlTheme.colors.surface,
            contentColor = BhodlTheme.colors.textPrimary,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(Res.string.wsend_psbt_outputs, summary.outputs.size),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            summary.outputs.forEachIndexed { i, out ->
                val role = when {
                    out.isChange -> stringResource(Res.string.wsend_psbt_role_change)
                    out.isMine -> stringResource(Res.string.wsend_psbt_role_self)
                    else -> stringResource(Res.string.wsend_psbt_role_recipient)
                }
                Column(modifier = Modifier.fillMaxWidth().testTag(PsbtReviewTags.output(i))) {
                    Text(
                        text = "${fmt(out.amountSat.toLong())} · $role",
                        style = BhodlTheme.typography.bodyMedium,
                        color = if (out.isMine) BhodlTheme.colors.textSecondary
                        else BhodlTheme.colors.textPrimary,
                    )
                    // Verify-by-copy: addresses are selectable; blocks of
                    // four, never truncated (UX review 2026-10).
                    SelectionContainer {
                        Text(
                            text = io.bhodl.desktop.ui.groupAddressInFours(out.address) ?: out.address,
                            style = BhodlTheme.typography.mono,
                            color = BhodlTheme.colors.textSecondary,
                        )
                    }
                }
            }
            SummaryRow(
                stringResource(Res.string.wsend_psbt_network_fee),
                "${fmt(summary.feeSat.toLong())} (≈ ${formatFeeRate(summary.feeSat, summary.vsize)})",
                PsbtReviewTags.FEE,
            )
            SummaryRow(stringResource(Res.string.wsend_psbt_size), "~${summary.vsize} vB")
            SummaryRow(stringResource(Res.string.wsend_psbt_sent_others), fmt(summary.sentExternalSat.toLong()), PsbtReviewTags.SENT)
            SummaryRow(
                stringResource(Res.string.wsend_psbt_total_leaving),
                fmt(summary.walletNetDebitSat.toLong()),
                PsbtReviewTags.TOTAL,
            )
            extraRows.forEach { (label, value) -> SummaryRow(label, value) }
            if (summary.spendsUnconfirmedForeign) {
                Warning(
                    stringResource(Res.string.wsend_psbt_unconfirmed_warning),
                    PsbtReviewTags.UNCONFIRMED_WARNING,
                )
            }
            if (summary.foreignInputCount > 0u) {
                Warning(
                    stringResource(Res.string.wsend_psbt_foreign_inputs, summary.foreignInputCount.toInt()),
                    PsbtReviewTags.FOREIGN_INPUT_WARNING,
                )
            }
        }
    }
}

/**
 * Second confirmation required when the fee exceeds the absolute-fee
 * guard. Renders nothing (and reports acknowledged) when it does not.
 */
@Composable
internal fun HighFeeAcknowledgement(
    summary: PsbtSummary,
    acknowledged: Boolean,
    onAcknowledgedChange: (Boolean) -> Unit,
) {
    if (!summary.feeExceedsLimit) return
    val fmt = rememberAmountFormatter()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Warning(
            stringResource(
                Res.string.wsend_psbt_fee_above_limit,
                fmt(summary.feeSat.toLong()),
                fmt(summary.feeLimitSat.toLong()),
            ),
            PsbtReviewTags.HIGH_FEE_WARNING,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PsbtReviewTags.HIGH_FEE_ACK)
                .toggleable(
                    value = acknowledged,
                    role = Role.Checkbox,
                    onValueChange = onAcknowledgedChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = acknowledged, onCheckedChange = null)
            Text(
                text = stringResource(Res.string.wsend_psbt_high_fee_ack),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
            )
        }
    }
}

/**
 * Dialog shown when the wallet refused to build because the fee is
 * above the absolute-fee guard (`WalletException.FeeTooHigh`).
 * Confirming rebuilds with `allowHighFee = true`; the review then asks
 * once more ([HighFeeAcknowledgement]).
 */
@Composable
internal fun HighFeeBuildDialog(
    feeSat: ULong,
    limitSat: ULong,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val fmt = rememberAmountFormatter()
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(PsbtReviewTags.HIGH_FEE_DIALOG),
        title = { Text(stringResource(Res.string.wsend_high_fee_title)) },
        text = {
            Text(
                text = stringResource(
                    Res.string.wsend_high_fee_body,
                    fmt(feeSat.toLong()),
                    fmt(limitSat.toLong()),
                ),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag(PsbtReviewTags.HIGH_FEE_DIALOG_CONFIRM),
            ) { Text(stringResource(Res.string.wsend_high_fee_build_anyway)) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag(PsbtReviewTags.HIGH_FEE_DIALOG_CANCEL),
            ) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/**
 * Review dialog for the RBF / CPFP flows: shows the decoded PSBT plus
 * flow-specific rows; Confirm is the only path to sign + broadcast.
 */
@Composable
internal fun PsbtReviewDialog(
    title: String,
    summary: PsbtSummary,
    extraRows: List<Pair<String, String>>,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var highFeeAck by remember(summary) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag(PsbtReviewTags.DIALOG),
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PsbtSummaryCard(summary = summary, extraRows = extraRows)
                HighFeeAcknowledgement(summary, highFeeAck) { highFeeAck = it }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !summary.feeExceedsLimit || highFeeAck,
                modifier = Modifier.testTag(PsbtReviewTags.DIALOG_CONFIRM),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.testTag(PsbtReviewTags.DIALOG_CANCEL),
            ) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

@Composable
private fun SummaryRow(label: String, value: String, tag: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().let { if (tag != null) it.testTag(tag) else it },
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

@Composable
private fun Warning(text: String, tag: String) {
    Text(
        text = "⚠ $text",
        style = BhodlTheme.typography.bodyMedium,
        color = BhodlTheme.colors.error,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * `fee / vsize` as "X.Y sat/vB" using integer arithmetic only (tenths,
 * truncated). `vsize == 0` renders "—".
 */
internal fun formatFeeRate(feeSat: ULong, vsize: ULong): String {
    if (vsize == 0uL) return "—"
    val tenths = feeSat * 10uL / vsize
    return "${tenths / 10uL}.${tenths % 10uL} sat/vB"
}

/** Test tags of the shared PSBT review surface. */
public object PsbtReviewTags {
    public const val CARD: String = "psbt-review.card"
    public const val FEE: String = "psbt-review.fee"
    public const val SENT: String = "psbt-review.sent"
    public const val TOTAL: String = "psbt-review.total"
    public const val UNCONFIRMED_WARNING: String = "psbt-review.unconfirmed-warning"
    public const val FOREIGN_INPUT_WARNING: String = "psbt-review.foreign-input-warning"
    public const val HIGH_FEE_WARNING: String = "psbt-review.high-fee-warning"
    public const val HIGH_FEE_ACK: String = "psbt-review.high-fee-ack"
    public const val HIGH_FEE_DIALOG: String = "psbt-review.high-fee-dialog"
    public const val HIGH_FEE_DIALOG_CONFIRM: String = "psbt-review.high-fee-dialog.confirm"
    public const val HIGH_FEE_DIALOG_CANCEL: String = "psbt-review.high-fee-dialog.cancel"
    public const val DIALOG: String = "psbt-review.dialog"
    public const val DIALOG_CONFIRM: String = "psbt-review.dialog.confirm"
    public const val DIALOG_CANCEL: String = "psbt-review.dialog.cancel"

    /** Row of the Nth output (0-indexed, transaction order). */
    public fun output(index: Int): String = "psbt-review.output.$index"
}
