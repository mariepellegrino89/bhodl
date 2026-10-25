package io.bhodl.desktop.screens

import io.bhodl.shared.resources.trk_cat_make_rule
import io.bhodl.desktop.ui.tableRowClickable
import io.bhodl.desktop.ui.tableCell
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.domain.RuleDraft
import io.bhodl.desktop.domain.RulePatternKind
import io.bhodl.desktop.domain.isAmountField
import io.bhodl.desktop.domain.ruleDraftFromEdit
import io.bhodl.desktop.domain.ruleDraftMatches
import io.bhodl.desktop.domain.ruleDraftPreviewCount
import io.bhodl.desktop.domain.rulesInEvaluationOrder
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.TrackingRuleFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.tracker_rule_apply_existing
import io.bhodl.shared.resources.tracker_rule_by_amount
import io.bhodl.shared.resources.tracker_rule_by_wallet
import io.bhodl.shared.resources.tracker_rule_match_by
import io.bhodl.shared.resources.tracker_rule_move_down
import io.bhodl.shared.resources.tracker_rule_move_up
import io.bhodl.shared.resources.tracker_rule_order_hint
import io.bhodl.shared.resources.tracker_rule_preview
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.trp_add
import io.bhodl.shared.resources.trp_field_name
import io.bhodl.shared.resources.trp_rule_category_hint
import io.bhodl.shared.resources.trp_rule_condition
import io.bhodl.shared.resources.trp_rule_disabled
import io.bhodl.shared.resources.trp_rule_edit
import io.bhodl.shared.resources.trp_rule_enabled
import io.bhodl.shared.resources.trp_rule_field_amount
import io.bhodl.shared.resources.trp_rule_field_amount_in
import io.bhodl.shared.resources.trp_rule_field_amount_out
import io.bhodl.shared.resources.trp_rule_field_description
import io.bhodl.shared.resources.trp_rule_field_payee
import io.bhodl.shared.resources.trp_rule_name_hint
import io.bhodl.shared.resources.trp_rule_new
import io.bhodl.shared.resources.trp_rule_off
import io.bhodl.shared.resources.trp_rule_on
import io.bhodl.shared.resources.trp_rule_op_contains
import io.bhodl.shared.resources.trp_rule_op_ends_with
import io.bhodl.shared.resources.trp_rule_op_equals
import io.bhodl.shared.resources.trp_rule_op_starts_with
import io.bhodl.shared.resources.trp_rule_then_category
import io.bhodl.shared.resources.trp_rule_value
import io.bhodl.shared.resources.trp_rule_value_hint
import io.bhodl.shared.resources.trp_rule_value_sat
import io.bhodl.shared.resources.trp_rule_value_sat_hint
import io.bhodl.shared.resources.trp_rule_when
import io.bhodl.shared.resources.trp_rules_empty
import io.bhodl.shared.resources.trp_rules_reapply
import io.bhodl.shared.resources.trp_rules_title

/**
 * Phase 7.5 — Categorization rules card.
 *
 * Lives on the TrackingDashboard above the History tab. Each row is a
 * compact "name | condition | → action | enabled toggle" line; tap to
 * edit, "+ Add" opens [RuleEditDialog].
 *
 * The card also exposes a "Reapply to history" action that asks the
 * registry to walk every uncategorized PaymentEvent and re-attempt
 * matching with the current rule set. Useful after editing a rule or
 * importing new rules — the user gets one-click recategorization
 * without having to wait for the next refresh tick.
 *
 * UX review 2026-10 (item 11) — explicit ordering: rows are listed in
 * evaluation order (first match wins) and each has Move up / Move down
 * controls; the old 0–10 priority slider (ties made the order
 * ambiguous) is gone. See [moveRule].
 */
@Composable
internal fun RulesCard(
    rules: List<TrackingRuleFfi>,
    onAdd: () -> Unit,
    onEdit: (TrackingRuleFfi) -> Unit,
    onReapply: () -> Unit,
    /** Move a rule one step up (−1) or down (+1) in evaluation order. */
    onMove: (TrackingRuleFfi, Int) -> Unit = { _, _ -> },
    /** False while a reorder is being written (one move at a time). */
    moveEnabled: Boolean = true,
) {
    val ordered = remember(rules) { rulesInEvaluationOrder(rules) }
    // Item 17 — on a narrow window the position number gives way
    // ([RULE_TABLE]; the list order still tells it); rule, category,
    // on / off and the move buttons always stay.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cols = RULE_TABLE.visible(maxWidth.value.toInt())
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BhodlTheme.shapes.panel)
                .background(BhodlTheme.colors.surface)
                .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.panel),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BhodlTheme.colors.surfaceVariant)
                    .padding(horizontal = BhodlTheme.spacing.md, vertical = BhodlTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.trp_rules_title),
                    style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                // Reapply: visible only when there are rules — no point
                // re-iterating with an empty rule set.
                if (rules.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(BhodlTheme.colors.surface)
                            .clickable(onClick = onReapply)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .testTag(RuleTags.REAPPLY),
                    ) {
                        Text(
                            text = stringResource(Res.string.trp_rules_reapply),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textSecondary,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.18f))
                        .clickable(onClick = onAdd)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag(RuleTags.ADD),
                ) {
                    Text(
                        text = stringResource(Res.string.trp_add),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.accentNeon,
                    )
                }
            }
            if (rules.isEmpty()) {
                Text(
                    text = stringResource(Res.string.trp_rules_empty),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(BhodlTheme.spacing.md),
                )
                return@Column
            }
            Text(
                text = stringResource(Res.string.tracker_rule_order_hint),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BhodlTheme.spacing.md, vertical = 4.dp),
            )
            ordered.forEachIndexed { idx, rule ->
                RuleDataRow(
                    rule = rule,
                    position = idx + 1,
                    cols = cols,
                    canMoveUp = moveEnabled && idx > 0,
                    canMoveDown = moveEnabled && idx < ordered.lastIndex,
                    isZebra = idx % 2 == 1,
                    onClick = { onEdit(rule) },
                    onMove = { delta -> onMove(rule, delta) },
                )
            }
        }
    }
}

@Composable
private fun RuleDataRow(
    rule: TrackingRuleFfi,
    position: Int,
    cols: List<RuleCol>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isZebra: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val bg = if (isZebra) BhodlTheme.colors.rowZebra else Color.Transparent
    // Item 13 (security review) — amounts in a rule (condition value,
    // legacy "21000 sat → cat" names) go through the masked formatter.
    val fmt = io.bhodl.desktop.ui.rememberAmountFormatter()
    val conditionText = stringResource(
        Res.string.trp_rule_condition,
        ruleFieldLabel(rule.conditionField),
        ruleOpLabel(rule.conditionOp),
        io.bhodl.desktop.domain.displayRuleConditionValue(rule.conditionField, rule.conditionValue, fmt),
    )
    // Two text lines need more than a single-line table row; the dense
    // row height is the floor.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = maxOf(BhodlTheme.density.rowHeight, 36.dp))
            .background(bg)
            // Item 15 — keyboard focus ring + ↑/↓ row moves.
            .tableRowClickable(onClick = onClick)
            .padding(horizontal = BhodlTheme.spacing.md)
            .testTag(RuleTags.row(rule.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (RuleCol.ORDER in cols) {
            Text(
                text = "$position.",
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier.tableCell(this, RuleCol.ORDER),
            )
        }
        Column(modifier = Modifier.tableCell(this, RuleCol.RULE)) {
            Text(
                text = io.bhodl.desktop.domain.displayRuleName(rule.name, fmt),
                style = BhodlTheme.typography.bodyMedium,
                color = if (rule.enabled) BhodlTheme.colors.textPrimary
                    else BhodlTheme.colors.textTertiary,
                maxLines = 1,
            )
            Text(
                text = conditionText,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
                maxLines = 1,
            )
        }
        Text(
            text = "→ ${rule.actionValue}",
            style = BhodlTheme.typography.amountSmall,
            color = if (rule.enabled) BhodlTheme.colors.accentNeon
                else BhodlTheme.colors.textTertiary,
            modifier = Modifier.tableCell(this, RuleCol.CATEGORY),
            maxLines = 1,
        )
        Box(modifier = Modifier.tableCell(this, RuleCol.ENABLED), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = if (rule.enabled) stringResource(Res.string.trp_rule_on) else stringResource(Res.string.trp_rule_off),
                style = BhodlTheme.typography.labelMedium,
                color = if (rule.enabled) BhodlTheme.colors.financialPositive
                    else BhodlTheme.colors.textTertiary,
            )
        }
        MoveButton(
            glyph = "▲",
            label = stringResource(Res.string.tracker_rule_move_up),
            enabled = canMoveUp,
            tag = RuleTags.moveUp(rule.id),
            onClick = { onMove(-1) },
        )
        MoveButton(
            glyph = "▼",
            label = stringResource(Res.string.tracker_rule_move_down),
            enabled = canMoveDown,
            tag = RuleTags.moveDown(rule.id),
            onClick = { onMove(+1) },
        )
    }
}

/** 24dp move target with an accessible label (the glyph alone is not). */
@Composable
private fun MoveButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = BhodlTheme.typography.labelMedium,
            color = if (enabled) BhodlTheme.colors.textSecondary
                else BhodlTheme.colors.textTertiary.copy(alpha = 0.4f),
        )
    }
}

@Composable
internal fun RuleEditDialog(
    editing: TrackingRuleFfi?,
    /**
     * Categories the user already has (from the registry's
     * [io.bhodl.ffi.RegistryHandle.listCategories]) — drives the
     * action picker. Empty list is acceptable: the dialog falls back
     * to free-text entry so the user can create a category on the
     * fly.
     */
    knownCategories: List<String>,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        conditionField: String,
        conditionOp: String,
        conditionValue: String,
        actionValue: String,
        priority: Long,
        enabled: Boolean,
    ) -> Unit,
    onDelete: (id: Long) -> Unit,
    /** Priority for a NEW rule — the top of the list ([topRulePriority]).
     *  An edited rule keeps its position; order changes go through the
     *  list's Move up / Move down controls. */
    newRulePriority: Long = 0L,
) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var field by remember(editing?.id) { mutableStateOf(editing?.conditionField ?: "description") }
    var op by remember(editing?.id) { mutableStateOf(editing?.conditionOp ?: "contains") }
    var value by remember(editing?.id) { mutableStateOf(editing?.conditionValue ?: "") }
    var category by remember(editing?.id) { mutableStateOf(editing?.actionValue ?: "") }
    val priority: Long = editing?.priority ?: newRulePriority
    var enabled by remember(editing?.id) { mutableStateOf(editing?.enabled ?: true) }

    // When field changes between text and amount, normalize op into
    // a valid set so the user doesn't end up with "description gt".
    fun ensureValidOp() {
        val valid = if (isAmountField(field)) {
            listOf("gt", "lt", "eq")
        } else {
            listOf("contains", "equals", "starts_with", "ends_with")
        }
        if (op !in valid) op = valid.first()
    }

    val canSave = name.isNotBlank() && value.isNotBlank() && category.isNotBlank() &&
        // amount field also requires a numeric value
        (!isAmountField(field) || value.trim().toULongOrNull() != null)

    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) stringResource(Res.string.trp_rule_new) else stringResource(Res.string.trp_rule_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_field_name)) },
                    placeholder = { Text(stringResource(Res.string.trp_rule_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(RuleTags.INPUT_NAME),
                )

                Text(
                    text = stringResource(Res.string.trp_rule_when),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                // Five fields: wrap instead of overflowing the panel.
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    RULE_FIELDS.map { it to ruleFieldLabel(it) }.forEach { (k, label) ->
                        val selected = field == k
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surfaceVariant,
                                )
                                .clickable {
                                    field = k
                                    ensureValidOp()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag(RuleTags.fieldTag(k)),
                        ) {
                            Text(
                                text = label,
                                style = BhodlTheme.typography.labelMedium,
                                color = if (selected) BhodlTheme.colors.accentNeon
                                else BhodlTheme.colors.textSecondary,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val ops = (if (isAmountField(field)) AMOUNT_OPS else TEXT_OPS).map { it to ruleOpLabel(it) }
                    ops.forEach { (k, label) ->
                        val selected = op == k
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surfaceVariant,
                                )
                                .clickable { op = k }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag(RuleTags.opTag(k)),
                        ) {
                            Text(
                                text = label,
                                style = BhodlTheme.typography.labelMedium,
                                color = if (selected) BhodlTheme.colors.accentNeon
                                else BhodlTheme.colors.textSecondary,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = value,
                    onValueChange = { raw ->
                        value = if (isAmountField(field)) {
                            raw.filter { ch -> ch.isDigit() }
                        } else {
                            raw
                        }
                    },
                    singleLine = true,
                    label = { Text(if (isAmountField(field)) stringResource(Res.string.trp_rule_value_sat) else stringResource(Res.string.trp_rule_value)) },
                    placeholder = { Text(if (isAmountField(field)) stringResource(Res.string.trp_rule_value_sat_hint) else stringResource(Res.string.trp_rule_value_hint)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = if (isAmountField(field)) KeyboardType.Number else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag(RuleTags.INPUT_VALUE),
                )

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_rule_then_category)) },
                    placeholder = {
                        Text(knownCategories.firstOrNull() ?: stringResource(Res.string.trp_rule_category_hint))
                    },
                    modifier = Modifier.fillMaxWidth().testTag(RuleTags.INPUT_CATEGORY),
                )
                if (knownCategories.isNotEmpty()) {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        knownCategories.take(8).forEach { cat ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(BhodlTheme.colors.surfaceVariant)
                                    .clickable { category = cat }
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = cat,
                                    style = BhodlTheme.typography.labelMedium,
                                    color = BhodlTheme.colors.textSecondary,
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(Res.string.tracker_rule_order_hint),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it },
                        modifier = Modifier.testTag(RuleTags.TOGGLE_ENABLED),
                    )
                    Text(
                        text = if (enabled) stringResource(Res.string.trp_rule_enabled) else stringResource(Res.string.trp_rule_disabled),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        onSave(
                            name.trim(),
                            field,
                            op,
                            value.trim(),
                            category.trim(),
                            priority,
                            enabled,
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.testTag(RuleTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        modifier = Modifier.testTag(RuleTags.DELETE),
                    ) { Text(stringResource(Res.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

/** Rule condition fields (wire values), in picker order. */
private val RULE_FIELDS = listOf("description", "payee", "amount", "amount_out", "amount_in")

/** Wire operators for amount fields and for text fields. */
private val AMOUNT_OPS = listOf("gt", "lt", "eq")
private val TEXT_OPS = listOf("contains", "equals", "starts_with", "ends_with")

/** UX review 2026-10 (rec 3) — localized label of a condition field; wire value as fallback. */
@Composable
private fun ruleFieldLabel(field: String): String = when (field) {
    "description" -> stringResource(Res.string.trp_rule_field_description)
    "payee" -> stringResource(Res.string.trp_rule_field_payee)
    "amount" -> stringResource(Res.string.trp_rule_field_amount)
    "amount_out" -> stringResource(Res.string.trp_rule_field_amount_out)
    "amount_in" -> stringResource(Res.string.trp_rule_field_amount_in)
    else -> field
}

/** Localized operator label; the amount comparisons stay symbols. */
@Composable
private fun ruleOpLabel(op: String): String = when (op) {
    "gt" -> ">"
    "lt" -> "<"
    "eq" -> "="
    "contains" -> stringResource(Res.string.trp_rule_op_contains)
    "equals" -> stringResource(Res.string.trp_rule_op_equals)
    "starts_with" -> stringResource(Res.string.trp_rule_op_starts_with)
    "ends_with" -> stringResource(Res.string.trp_rule_op_ends_with)
    else -> op
}

internal object RuleTags {
    const val ADD: String = "rule.add"
    const val SAVE: String = "rule.save"
    const val DELETE: String = "rule.delete"
    const val REAPPLY: String = "rule.reapply"
    const val INPUT_NAME: String = "rule.input-name"
    const val INPUT_VALUE: String = "rule.input-value"
    const val INPUT_CATEGORY: String = "rule.input-category"
    const val TOGGLE_ENABLED: String = "rule.toggle-enabled"
    fun fieldTag(field: String): String = "rule.field.$field"
    fun opTag(op: String): String = "rule.op.$op"
    fun row(id: Long): String = "rule.row.$id"
    fun moveUp(id: Long): String = "rule.move-up.$id"
    fun moveDown(id: Long): String = "rule.move-down.$id"
    const val FROM_EDIT_PANEL: String = "rule.from-edit"
    const val FROM_EDIT_PREVIEW: String = "rule.from-edit.preview"
    const val FROM_EDIT_APPLY_EXISTING: String = "rule.from-edit.apply-existing"
    /** Redesign 2026-10 — "also create a rule" in the category editor. */
    const val MAKE_RULE: String = "rule.from-edit.make-rule"
    fun fromEditPattern(kind: RulePatternKind): String = "rule.from-edit.pattern.${kind.name.lowercase()}"
}

/**
 * What [CategoryEditDialog] needs to offer "also create a rule": the
 * edited transaction's wallet label (the payee pattern), the history the
 * preview counts and how to read a payee from an event.
 */
internal data class RuleOptionContext(
    val walletLabel: String?,
    val events: List<PaymentEvent>,
    val payeeOf: (PaymentEvent) -> String?,
)

/**
 * Redesign 2026-10 — the rule as an option of the category editor
 * (off by default: most expenses are one-offs) instead of a prompt after
 * every re-categorisation. The user picks the pattern (wallet / exact
 * amount, see [RulePatternKind]); the preview counts the existing
 * uncategorised transactions it would fill, and a checkbox applies it to
 * them on Save. The new rule goes to the top of the list.
 */
@Composable
internal fun RuleOptionSection(
    event: PaymentEvent,
    category: String,
    context: RuleOptionContext,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    kind: RulePatternKind?,
    onKindChange: (RulePatternKind) -> Unit,
    matches: List<PaymentEvent>,
    applyExisting: Boolean,
    onApplyExistingChange: (Boolean) -> Unit,
) {
    val kinds = RulePatternKind.entries.filter {
        ruleDraftFromEdit(event, category, context.walletLabel, it) != null
    }
    if (kinds.isEmpty()) return
    val formatAmount = io.bhodl.desktop.ui.rememberAmountFormatter()
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(RuleTags.FROM_EDIT_PANEL),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(role = Role.Checkbox) { onEnabledChange(!enabled) }
                .testTag(RuleTags.MAKE_RULE),
        ) {
            androidx.compose.material3.Checkbox(checked = enabled, onCheckedChange = null)
            Text(
                text = stringResource(Res.string.trk_cat_make_rule),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textPrimary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (!enabled) return@Column
        Text(
            text = stringResource(Res.string.tracker_rule_match_by),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            kinds.forEach { k ->
                val selected = k == (kind ?: kinds.first())
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                            else BhodlTheme.colors.surfaceVariant,
                        )
                        .clickable(role = Role.RadioButton) { onKindChange(k) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag(RuleTags.fromEditPattern(k)),
                ) {
                    Text(
                        text = (if (selected) "✓ " else "") + when (k) {
                            RulePatternKind.WALLET -> stringResource(Res.string.tracker_rule_by_wallet) + " · " + context.walletLabel.orEmpty()
                            RulePatternKind.AMOUNT ->
                                stringResource(Res.string.tracker_rule_by_amount) + " · " + formatAmount(event.amountSat.toLong())
                        },
                        style = BhodlTheme.typography.labelMedium,
                        color = if (selected) BhodlTheme.colors.accentNeon else BhodlTheme.colors.textSecondary,
                    )
                }
            }
        }
        Text(
            text = stringResource(Res.string.tracker_rule_preview, ruleDraftPreviewCount(matches)),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.testTag(RuleTags.FROM_EDIT_PREVIEW),
        )
        if (matches.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(role = Role.Checkbox) { onApplyExistingChange(!applyExisting) }
                    .testTag(RuleTags.FROM_EDIT_APPLY_EXISTING),
            ) {
                androidx.compose.material3.Checkbox(checked = applyExisting, onCheckedChange = null)
                Text(
                    text = stringResource(Res.string.tracker_rule_apply_existing),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
