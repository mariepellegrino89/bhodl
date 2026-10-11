package io.bhodl.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.privacy_hide_amounts
import io.bhodl.shared.resources.privacy_show_amounts
import io.bhodl.shared.resources.privacy_toggle_tooltip
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/**
 * UX review 2026-10 (item 13) — the privacy-mode shortcut and the
 * status-bar toggle. The masking itself lives in the central
 * formatters (`ui/money/AmountMask.kt`).
 */
public object PrivacyShortcut {

    /**
     * `true` for Ctrl+Shift+H (Cmd+Shift+H on macOS) on key DOWN.
     * Alt is excluded so AltGr layouts (Ctrl+Alt) never trigger it.
     */
    public fun matches(
        key: Key,
        type: KeyEventType,
        ctrlOrMeta: Boolean,
        shift: Boolean,
        alt: Boolean,
    ): Boolean = type == KeyEventType.KeyDown && key == Key.H && ctrlOrMeta && shift && !alt

    public fun matches(event: KeyEvent): Boolean = matches(
        key = event.key,
        type = event.type,
        ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    )

    /**
     * Window-level handler (Main.kt `onPreviewKeyEvent`): toggles the
     * privacy mode and consumes the event, from any screen and even
     * while a text field has focus.
     */
    public fun handle(event: KeyEvent): Boolean = onKey(
        key = event.key,
        type = event.type,
        ctrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
        shift = event.isShiftPressed,
        alt = event.isAltPressed,
    )

    // Security review — holding the chord auto-repeats KeyDown events;
    // without this latch the mode would flicker on / off and could end
    // in the wrong state. Released by the KeyUp of H.
    @Volatile
    private var held: Boolean = false

    /** Pure-ish state machine behind [handle]; returns `true` when consumed. */
    public fun onKey(key: Key, type: KeyEventType, ctrlOrMeta: Boolean, shift: Boolean, alt: Boolean): Boolean {
        if (key == Key.H && type == KeyEventType.KeyUp) {
            held = false
            return false
        }
        if (!matches(key, type, ctrlOrMeta, shift, alt)) return false
        if (held) return true // auto-repeat: swallow, do not toggle again
        held = true
        SettingsStore.toggleBalancesHidden()
        return true
    }

    /** Test seam: forget a latched chord. */
    internal fun resetForTest() {
        held = false
    }
}

/**
 * Eye icon that toggles the privacy mode. The icon shows the CURRENT
 * state (eye-off = amounts hidden); the accessible name is the action
 * the click performs and the tooltip adds the keyboard shortcut.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun PrivacyToggleButton(modifier: Modifier = Modifier) {
    val hidden by SettingsStore.balancesHidden
    val action = stringResource(
        if (hidden) Res.string.privacy_show_amounts else Res.string.privacy_hide_amounts,
    )
    StatusBarIconButton(
        icon = if (hidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
        label = action,
        tooltip = stringResource(Res.string.privacy_toggle_tooltip, action),
        onClick = { SettingsStore.toggleBalancesHidden() },
        active = hidden,
        modifier = modifier.testTag(PrivacyUiTags.TOGGLE),
    )
}

/**
 * Small square icon button for the footer status bar: a 24dp visual
 * inside a 32dp hit target (WCAG 2.5.8), a tooltip and a
 * content description (the icon alone is not an accessible name).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusBarIconButton(
    icon: ImageVector,
    label: String,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.surfaceVariant)
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = tooltip,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
            }
        },
        delayMillis = 400,
    ) {
        IconButton(
            onClick = onClick,
            modifier = modifier
                .size(32.dp)
                .semantics { role = Role.Button },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

public object PrivacyUiTags {
    public const val TOGGLE: String = "privacy.toggle"
}
