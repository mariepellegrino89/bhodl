package io.bhodl.desktop.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * UX review 2026-10 (item 15) — keyboard navigation for dense table
 * rows (WCAG 2.1.1 / 2.4.7):
 *
 *  - Tab reaches the row; ↑ / ↓ move to the previous / next row;
 *    Enter or Space activates a clickable row.
 *  - The focused row gets [focusRing] (a brand-colour outline), which
 *    is never the same as the filled selection background, so a row
 *    can be focused, selected, or both, and the user can tell which.
 */
@Composable
public fun Modifier.tableRowClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    return this
        .focusRing(focused)
        .arrowKeysMoveFocus(focusManager)
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClick = onClick,
        )
}

/** Same focus behaviour for a read-only row (no click action). */
@Composable
public fun Modifier.tableRowFocusable(): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    return this
        .focusRing(focused)
        .arrowKeysMoveFocus(focusManager)
        .focusable(interactionSource = interaction)
}

/**
 * Focus ring for a row driven by an [interaction] the caller already
 * passes to its own `toggleable` / `selectable` (e.g. the UTXO table).
 */
@Composable
public fun Modifier.tableRowFocusRing(interaction: MutableInteractionSource): Modifier {
    val focused by interaction.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    return this.focusRing(focused).arrowKeysMoveFocus(focusManager)
}

/** Pure mapping of the row-navigation keys (exposed for tests). */
public fun tableRowFocusDirection(key: Key, type: KeyEventType): FocusDirection? {
    if (type != KeyEventType.KeyDown) return null
    return when (key) {
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionUp -> FocusDirection.Up
        else -> null
    }
}

private fun Modifier.arrowKeysMoveFocus(focusManager: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        val direction = tableRowFocusDirection(event.key, event.type) ?: return@onPreviewKeyEvent false
        focusManager.moveFocus(direction)
    }
