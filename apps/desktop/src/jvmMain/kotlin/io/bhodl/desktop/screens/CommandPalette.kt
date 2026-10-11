package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_palette_no_match
import io.bhodl.shared.resources.trk_palette_placeholder
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3.13 — Cmd+K / Ctrl+K command palette.
 *
 * Pattern: Linear / Notion / Raycast — assent from every Bitcoin /
 * finance app inspected during UX research (2026-05-16). Lets the user
 * jump directly to any action by typing 2-3 letters and pressing Enter.
 *
 * The palette is a single composable that renders as a Material 3
 * dialog overlay; it owns its own filter state and dismisses on action
 * select / Escape / outside-click. Actions are passed in by the host so
 * the same palette renders Tracking-scoped or wallet-scoped action sets.
 */
@Composable
internal fun CommandPalette(
    actions: List<CommandAction>,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val filtered = remember(query, actions) {
        if (query.isBlank()) actions
        else actions.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.keywords.any { kw -> kw.contains(query, ignoreCase = true) }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 480.dp, max = 640.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BhodlTheme.colors.surface)
                .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(12.dp))
                .padding(12.dp)
                .testTag(CommandPaletteTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(Res.string.trk_palette_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag(CommandPaletteTags.INPUT),
            )
            if (filtered.isEmpty()) {
                Text(
                    text = stringResource(Res.string.trk_palette_no_match, query),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(items = filtered, key = { it.id }) { action ->
                        CommandRow(
                            action = action,
                            onClick = {
                                action.invoke()
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    action: CommandAction,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (action.glyph != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = action.glyph,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = action.label,
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            if (action.hint != null) {
                Text(
                    text = action.hint,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
        if (action.shortcutHint != null) {
            Text(
                text = action.shortcutHint,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
    }
}

/**
 * One row in the command palette. `label` is the primary searchable
 * text; `keywords` adds extra search anchors (Italian + English so
 * "search" and "cerca" both surface the same action).
 *
 * `glyph` is a single-character / short emoji prefix that reads as a
 * category icon; `shortcutHint` shows the keybinding on the right.
 */
internal data class CommandAction(
    val id: String,
    val label: String,
    val hint: String? = null,
    val glyph: String? = null,
    val keywords: List<String> = emptyList(),
    val shortcutHint: String? = null,
    val invoke: () -> Unit,
)

public object CommandPaletteTags {
    public const val ROOT: String = "cmdk.root"
    public const val INPUT: String = "cmdk.input"
}
