package io.bhodl.desktop.screens

import io.bhodl.shared.resources.bill_category
import io.bhodl.shared.resources.bill_fixed
import io.bhodl.shared.resources.bill_variable
import io.bhodl.shared.resources.bill_estimate_sat
import io.bhodl.shared.resources.bill_paid_title
import io.bhodl.shared.resources.bill_paid_body
import io.bhodl.shared.resources.bgc_mark_paid
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_note_optional
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.trp_add
import io.bhodl.shared.resources.trp_amount_sat
import io.bhodl.shared.resources.trp_bill_due_day
import io.bhodl.shared.resources.trp_bill_due_day_field
import io.bhodl.shared.resources.trp_bill_edit
import io.bhodl.shared.resources.trp_bill_mark_paid
import io.bhodl.shared.resources.trp_bill_name_hint
import io.bhodl.shared.resources.trp_bill_new
import io.bhodl.shared.resources.trp_bill_paid
import io.bhodl.shared.resources.trp_bills_empty
import io.bhodl.shared.resources.trp_bills_title
import io.bhodl.shared.resources.trp_field_name

/**
 * Phase 4.4 — create / edit / delete dialog for a single bill.
 *
 * `editing` null → create mode. Save inserts a new row. Non-null →
 * edit mode; Save updates, Delete removes. Numeric fields are
 * digit-only inputs; due_day clamped to 1..31.
 */
@Composable
internal fun BillEditDialog(
    editing: TrackingBillFfi?,
    onDismiss: () -> Unit,
    onSave: (name: String, amountSat: ULong, dueDay: Int, displayLabel: String?, variable: Boolean, category: String?) -> Unit,
    onDelete: (id: Long) -> Unit,
    /** Schema v8 — categories offered for the bill's budget category. */
    knownCategories: List<String> = emptyList(),
) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var amountStr by remember(editing?.id) {
        mutableStateOf(editing?.amountSat?.toString() ?: "")
    }
    var dueDayStr by remember(editing?.id) {
        mutableStateOf(editing?.dueDay?.toString() ?: "1")
    }
    var displayLabel by remember(editing?.id) {
        mutableStateOf(editing?.displayLabel.orEmpty())
    }
    // Schema v7 — a variable bill (power, gas): the amount is an estimate,
    // the real one is asked when it is marked paid.
    var variable by remember(editing?.id) { mutableStateOf(editing?.variable ?: false) }
    // Schema v8 — the budget category its payments count against.
    var category by remember(editing?.id) { mutableStateOf(editing?.category.orEmpty()) }
    val amount: ULong? = amountStr.trim().takeIf { it.isNotBlank() }?.toULongOrNull()
    val dueDay: Int? = dueDayStr.trim().toIntOrNull()?.coerceIn(1, 31)
    val canSave = name.isNotBlank() && amount != null && dueDay != null
    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) stringResource(Res.string.trp_bill_new) else stringResource(Res.string.trp_bill_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_field_name)) },
                    placeholder = { Text(stringResource(Res.string.trp_bill_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(BillTags.INPUT_NAME),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(false to Res.string.bill_fixed, true to Res.string.bill_variable).forEach { (v, label) ->
                        val on = variable == v
                        Text(
                            stringResource(label),
                            style = BhodlTheme.typography.labelMedium,
                            color = if (on) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.textSecondary,
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(999.dp))
                                .background(if (on) BhodlTheme.colors.brandPrimary.copy(alpha = 0.14f) else BhodlTheme.colors.surfaceVariant)
                                .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) { variable = v }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag(if (v) BillTags.KIND_VARIABLE else BillTags.KIND_FIXED),
                        )
                    }
                }
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    label = { Text(stringResource(if (variable) Res.string.bill_estimate_sat else Res.string.trp_amount_sat)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(BillTags.INPUT_AMOUNT),
                )
                OutlinedTextField(
                    value = dueDayStr,
                    onValueChange = { dueDayStr = it.filter { ch -> ch.isDigit() }.take(2) },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_bill_due_day_field)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(BillTags.INPUT_DUEDAY),
                )
                CategoryField(
                    value = category,
                    onValueChange = { category = it },
                    knownCategories = knownCategories,
                    label = stringResource(Res.string.bill_category),
                    tag = BillTags.INPUT_CATEGORY,
                )
                OutlinedTextField(
                    value = displayLabel,
                    onValueChange = { displayLabel = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.tracker_note_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag(BillTags.INPUT_LABEL),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) onSave(
                        name.trim(),
                        amount!!,
                        dueDay!!,
                        displayLabel.trim().ifBlank { null },
                        variable,
                        category.trim().ifBlank { null },
                    )
                },
                enabled = canSave,
                modifier = Modifier.testTag(BillTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        modifier = Modifier.testTag(BillTags.DELETE),
                    ) { Text(stringResource(Res.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

/** A bill counts as paid when its last payment falls in [month] (in [zone]). */
internal fun isPaidInMonth(lastPaidUnix: Long?, month: YearMonth, zone: ZoneId): Boolean =
    lastPaidUnix?.let { YearMonth.from(Instant.ofEpochSecond(it).atZone(zone)) == month } ?: false

internal object BillTags {
    const val ADD: String = "bill.add"
    const val SAVE: String = "bill.save"
    const val DELETE: String = "bill.delete"
    const val INPUT_NAME: String = "bill.input-name"
    const val INPUT_AMOUNT: String = "bill.input-amount"
    const val INPUT_DUEDAY: String = "bill.input-dueday"
    const val INPUT_LABEL: String = "bill.input-label"
    const val INPUT_CATEGORY: String = "bill.input-category"
    fun paidChip(id: Long): String = "bill.paid.$id"
    const val KIND_FIXED: String = "bill.kind.fixed"
    const val KIND_VARIABLE: String = "bill.kind.variable"
    const val PAID_AMOUNT: String = "bill.paid-amount"
    const val PAID_SAVE: String = "bill.paid-save"
    fun amount(id: Long): String = "bill.amount.$id"
}

/**
 * UX review 2026-10 (B6) — second line under a bill's name: the due
 * day, plus the optional free-text note (`display_label`) when set.
 */
@Composable
internal fun billSubtitle(bill: TrackingBillFfi): String {
    val due = stringResource(Res.string.trp_bill_due_day, bill.dueDay.toInt())
    val note = bill.displayLabel?.trim()?.takeIf { it.isNotEmpty() }
    return if (note != null) "$due · $note" else due
}

/** Schema v7 — "how much did you pay?" when a variable bill is marked paid. */
@Composable
internal fun BillPaidAmountDialog(bill: TrackingBillFfi, onDismiss: () -> Unit, onSave: (ULong) -> Unit) {
    var input by remember(bill.id) { mutableStateOf(bill.amountSat.toString()) }
    val parsed = input.trim().toULongOrNull()
    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.bill_paid_title, bill.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.bill_paid_body, io.bhodl.desktop.ui.displayAmount(bill.amountSat.toLong())),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_amount_sat)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag(BillTags.PAID_AMOUNT),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null, modifier = Modifier.testTag(BillTags.PAID_SAVE)) {
                Text(stringResource(Res.string.bgc_mark_paid))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
