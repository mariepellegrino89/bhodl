package io.bhodl.desktop.screens

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
 * Phase 4.4 — Bills Tracker card.
 *
 * Lists recurring payments / subscriptions. Each row shows the bill
 * name, amount (sat or display_label override), the day-of-month it's
 * due, and a "Paid" pill. A bill is considered paid for the current
 * period if `last_paid_unix` falls in the current calendar month.
 *
 * The Add button at the bottom opens [BillEditDialog] in create mode.
 * Tapping a row opens it in edit mode (also exposes Delete).
 */
@Composable
internal fun BillsTrackerCard(
    bills: List<TrackingBillFfi>,
    onAdd: () -> Unit,
    onEdit: (TrackingBillFfi) -> Unit,
    onTogglePaid: (bill: TrackingBillFfi, paidNow: Boolean) -> Unit,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    // Ticks at least at local midnight so "due this month / overdue"
    // rolls over in a long-running session.
    val now = rememberTickingNowUnix(zoneId)
    val currentMonth = remember(now) {
        YearMonth.from(java.time.Instant.ofEpochSecond(now).atZone(zoneId))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.trp_bills_title),
                style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(BillTags.ADD),
            ) {
                Text(
                    text = stringResource(Res.string.trp_add),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.accentNeon,
                )
            }
        }
        if (bills.isEmpty()) {
            Text(
                text = stringResource(Res.string.trp_bills_empty),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
            )
            return@Column
        }
        bills.forEach { b ->
            BillRow(
                bill = b,
                currentMonth = currentMonth,
                zoneId = zoneId,
                nowUnix = now,
                onEdit = { onEdit(b) },
                onTogglePaid = { paidNow -> onTogglePaid(b, paidNow) },
            )
        }
    }
}

@Composable
private fun BillRow(
    bill: TrackingBillFfi,
    currentMonth: YearMonth,
    zoneId: ZoneId,
    nowUnix: Long,
    onEdit: () -> Unit,
    onTogglePaid: (Boolean) -> Unit,
) {
    val paidThisMonth = bill.lastPaidUnix?.let {
        val date = Instant.ofEpochSecond(it).atZone(zoneId).toLocalDate()
        YearMonth.from(date) == currentMonth
    } ?: false
    val statusColor = if (paidThisMonth) BhodlTheme.colors.financialPositive
    else BhodlTheme.colors.financialPending
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Item 15 — Settings › Density row height.
            .heightIn(min = BhodlTheme.density.rowHeight)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = bill.name,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = billSubtitle(bill),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
        // UX review 2026-10 (B6) — the amount column always shows the
        // amount; the optional free-text note moved under the name.
        Text(
            text = displayAmount(bill.amountSat),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.weight(1.2f).testTag(BillTags.amount(bill.id)),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(statusColor.copy(alpha = 0.18f))
                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .clickable { onTogglePaid(!paidThisMonth) }
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .testTag(BillTags.paidChip(bill.id)),
        ) {
            Text(
                text = if (paidThisMonth) stringResource(Res.string.trp_bill_paid) else stringResource(Res.string.trp_bill_mark_paid),
                style = BhodlTheme.typography.labelMedium,
                color = statusColor,
            )
        }
    }
}

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
    onSave: (name: String, amountSat: ULong, dueDay: Int, displayLabel: String?) -> Unit,
    onDelete: (id: Long) -> Unit,
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
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_amount_sat)) },
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

internal object BillTags {
    const val ADD: String = "bill.add"
    const val SAVE: String = "bill.save"
    const val DELETE: String = "bill.delete"
    const val INPUT_NAME: String = "bill.input-name"
    const val INPUT_AMOUNT: String = "bill.input-amount"
    const val INPUT_DUEDAY: String = "bill.input-dueday"
    const val INPUT_LABEL: String = "bill.input-label"
    fun paidChip(id: Long): String = "bill.paid.$id"
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
