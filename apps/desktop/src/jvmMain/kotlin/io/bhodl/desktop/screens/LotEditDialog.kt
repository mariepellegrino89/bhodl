package io.bhodl.desktop.screens

import io.bhodl.desktop.AppClock
import io.bhodl.desktop.ui.money.filterDecimalChars
import io.bhodl.desktop.ui.money.formatDecimalInput
import io.bhodl.desktop.ui.money.parseDecimalInput
import io.bhodl.ui.theme.BhodlTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.DatePickerField
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.trp_fees_invalid
import io.bhodl.shared.resources.trp_fees_optional
import io.bhodl.shared.resources.trp_field_purchase_date
import io.bhodl.shared.resources.trp_hint_fees
import io.bhodl.shared.resources.trp_hint_price
import io.bhodl.shared.resources.trp_hint_qty
import io.bhodl.shared.resources.trp_inv_quantity
import io.bhodl.shared.resources.trp_lot_add_title
import io.bhodl.shared.resources.trp_lot_edit_title
import io.bhodl.shared.resources.trp_lot_notes_hint
import io.bhodl.shared.resources.trp_lot_price
import io.bhodl.shared.resources.trp_notes_optional

/**
 * Phase 7.8 — Add/Edit/Delete a single investment lot.
 *
 * Keeps the same shape as ManualAssetEditDialog: simple text inputs,
 * a calendar-backed [DatePickerField] for the purchase date, Save
 * disabled until quantity + price are positive finite numbers.
 *
 * The `parentInvestmentName` is shown read-only in the title so the
 * user always knows which position they're editing.
 */
@Composable
internal fun LotEditDialog(
    editing: TrackingInvestmentLotFfi?,
    parentInvestmentName: String,
    parentCurrencySymbol: String,
    onDismiss: () -> Unit,
    onSave: (
        quantity: Double,
        pricePerUnit: Double,
        feesFiat: Double,
        purchaseUnix: Long,
        notes: String?,
    ) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    var qtyStr by remember(editing?.id) {
        mutableStateOf(editing?.quantity?.let { formatDecimalInput(it) } ?: "")
    }
    var priceStr by remember(editing?.id) {
        mutableStateOf(editing?.pricePerUnit?.let { formatDecimalInput(it) } ?: "")
    }
    var feesStr by remember(editing?.id) {
        mutableStateOf(editing?.feesFiat?.takeIf { it > 0.0 }?.let { formatDecimalInput(it) } ?: "")
    }
    var purchaseUnix by remember(editing?.id) {
        mutableStateOf(editing?.purchaseUnix ?: AppClock.nowUnix())
    }
    var notesStr by remember(editing?.id) { mutableStateOf(editing?.notes ?: "") }

    val qty: Double? = parseDecimalInput(qtyStr)
    val price: Double? = parseDecimalInput(priceStr)
    // Blank fees = 0; typed-but-unparseable fees are a validation error
    // (never silently 0).
    val fees: Double? = if (feesStr.isBlank()) 0.0 else parseDecimalInput(feesStr)
    val feesInvalid = fees == null || !fees.isFinite() || fees < 0.0
    val canSave = qty != null && qty > 0.0 && qty.isFinite() &&
        price != null && price > 0.0 && price.isFinite() &&
        !feesInvalid

    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (editing == null) stringResource(Res.string.trp_lot_add_title, parentInvestmentName)
                else stringResource(Res.string.trp_lot_edit_title, parentInvestmentName),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = qtyStr,
                    onValueChange = { qtyStr = filterDecimalChars(it) },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_inv_quantity)) },
                    placeholder = { Text(stringResource(Res.string.trp_hint_qty)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(LotEditTags.INPUT_QTY),
                )
                OutlinedTextField(
                    value = priceStr,
                    onValueChange = { priceStr = filterDecimalChars(it) },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_lot_price, parentCurrencySymbol)) },
                    placeholder = { Text(stringResource(Res.string.trp_hint_price)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(LotEditTags.INPUT_PRICE),
                )
                OutlinedTextField(
                    value = feesStr,
                    onValueChange = { feesStr = filterDecimalChars(it) },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_fees_optional, parentCurrencySymbol)) },
                    placeholder = { Text(stringResource(Res.string.trp_hint_fees)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(LotEditTags.INPUT_FEES),
                )
                if (feesInvalid) {
                    Text(
                        text = stringResource(Res.string.trp_fees_invalid),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.financialNegative,
                    )
                }
                DatePickerField(
                    label = stringResource(Res.string.trp_field_purchase_date),
                    epochSeconds = purchaseUnix,
                    onPick = { purchaseUnix = it },
                    modifier = Modifier.fillMaxWidth(),
                    testTagId = LotEditTags.INPUT_DATE,
                )
                OutlinedTextField(
                    value = notesStr,
                    onValueChange = { notesStr = it },
                    label = { Text(stringResource(Res.string.trp_notes_optional)) },
                    placeholder = { Text(stringResource(Res.string.trp_lot_notes_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(LotEditTags.INPUT_NOTES),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        onSave(qty!!, price!!, fees!!, purchaseUnix, notesStr.takeIf { it.isNotBlank() })
                    }
                },
                enabled = canSave,
                modifier = Modifier.testTag(LotEditTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        modifier = Modifier.testTag(LotEditTags.DELETE),
                    ) { Text(stringResource(Res.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

internal object LotEditTags {
    const val SAVE: String = "lot-edit.save"
    const val DELETE: String = "lot-edit.delete"
    const val INPUT_QTY: String = "lot-edit.input-qty"
    const val INPUT_PRICE: String = "lot-edit.input-price"
    const val INPUT_FEES: String = "lot-edit.input-fees"
    const val INPUT_DATE: String = "lot-edit.input-date"
    const val INPUT_NOTES: String = "lot-edit.input-notes"
}
