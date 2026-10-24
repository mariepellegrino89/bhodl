package io.bhodl.desktop.screens

import androidx.compose.foundation.background
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
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.TrackingLiabilityFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_note_optional
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.trk_liability_amount_sat
import io.bhodl.shared.resources.trk_liability_edit
import io.bhodl.shared.resources.trk_liability_kind
import io.bhodl.shared.resources.trk_liability_kind_credit_card
import io.bhodl.shared.resources.trk_liability_kind_loan
import io.bhodl.shared.resources.trk_liability_kind_mortgage
import io.bhodl.shared.resources.trk_liability_kind_other
import io.bhodl.shared.resources.trk_liability_name
import io.bhodl.shared.resources.trk_liability_name_hint
import io.bhodl.shared.resources.trk_liability_new
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/*
 * UX review 2026-10 (item 9) — the Phase 4.6 "Net worth snapshot" card
 * (asset / liability / net worth tiles + liabilities list) was replaced
 * by the Portfolio balance sheet ([AssetPortfolioCard]), which carries
 * the liabilities section and the net worth row. The liability editor
 * below is still the one the sheet opens.
 */

@Composable
internal fun LiabilityEditDialog(
    editing: TrackingLiabilityFfi?,
    onDismiss: () -> Unit,
    onSave: (name: String, amountSat: ULong, kind: String, displayLabel: String?) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var amountStr by remember(editing?.id) {
        mutableStateOf(editing?.amountSat?.toString() ?: "")
    }
    var kind by remember(editing?.id) { mutableStateOf(editing?.kind ?: "other") }
    var displayLabel by remember(editing?.id) {
        mutableStateOf(editing?.displayLabel.orEmpty())
    }
    val amount: ULong? = amountStr.trim().takeIf { it.isNotBlank() }?.toULongOrNull()
    val canSave = name.isNotBlank() && amount != null
    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editing == null) Res.string.trk_liability_new else Res.string.trk_liability_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trk_liability_name)) },
                    placeholder = { Text(stringResource(Res.string.trk_liability_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(LiabilityTags.INPUT_NAME),
                )
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it.filter { ch -> ch.isDigit() } },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trk_liability_amount_sat)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(LiabilityTags.INPUT_AMOUNT),
                )
                OutlinedTextField(
                    value = displayLabel,
                    onValueChange = { displayLabel = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.tracker_note_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag(LiabilityTags.INPUT_LABEL),
                )
                Text(
                    text = stringResource(Res.string.trk_liability_kind),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("mortgage", "loan", "credit-card", "other").forEach { k ->
                        val selected = kind == k
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surface,
                                )
                                .clickable { kind = k }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                                .testTag(LiabilityTags.kind(k)),
                        ) {
                            Text(
                                text = liabilityKindLabel(k),
                                style = BhodlTheme.typography.labelMedium,
                                color = if (selected) BhodlTheme.colors.accentNeon
                                else BhodlTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) onSave(
                        name.trim(),
                        amount!!,
                        kind,
                        displayLabel.trim().ifBlank { null },
                    )
                },
                enabled = canSave,
                modifier = Modifier.testTag(LiabilityTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        modifier = Modifier.testTag(LiabilityTags.DELETE),
                    ) { Text(stringResource(Res.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

internal object LiabilityTags {
    const val ADD: String = "liability.add"
    const val SAVE: String = "liability.save"
    const val DELETE: String = "liability.delete"
    const val INPUT_NAME: String = "liability.input-name"
    const val INPUT_AMOUNT: String = "liability.input-amount"
    const val INPUT_LABEL: String = "liability.input-label"
    fun amount(id: Long): String = "liability.amount.$id"
    fun kind(kind: String): String = "liability.kind.$kind"
}

/**
 * UX review 2026-10 (B6) — second line under a liability's name: its
 * kind, plus the optional free-text note (`display_label`) when set.
 * The note never stands in for the amount.
 */
internal fun liabilitySubtitle(l: TrackingLiabilityFfi): String {
    val kind = liabilityKindText(l.kind).replaceFirstChar { it.uppercaseChar() }
    val note = l.displayLabel?.trim()?.takeIf { it.isNotEmpty() }
    return if (note != null) "$kind · $note" else kind
}

/** Non-composable twin of [liabilityKindLabel]; unknown legacy kinds show as stored. */
private fun liabilityKindText(kind: String): String = when (kind) {
    "mortgage" -> localizedText(Res.string.trk_liability_kind_mortgage)
    "loan" -> localizedText(Res.string.trk_liability_kind_loan)
    "credit-card" -> localizedText(Res.string.trk_liability_kind_credit_card)
    "other" -> localizedText(Res.string.trk_liability_kind_other)
    else -> kind
}

/** Localized label of a liability kind chip (the wire value stays the key). */
@Composable
private fun liabilityKindLabel(kind: String): String = when (kind) {
    "mortgage" -> stringResource(Res.string.trk_liability_kind_mortgage)
    "loan" -> stringResource(Res.string.trk_liability_kind_loan)
    "credit-card" -> stringResource(Res.string.trk_liability_kind_credit_card)
    "other" -> stringResource(Res.string.trk_liability_kind_other)
    else -> kind
}
