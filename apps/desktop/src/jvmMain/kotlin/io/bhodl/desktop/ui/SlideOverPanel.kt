package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.bhodl.ui.theme.BhodlTheme

/**
 * Phase 9.2 (interaction modernisation) — right-anchored slide-over
 * panel, the desktop replacement for the CRUD `AlertDialog`s.
 *
 * Finance trackers moved away from centered modals for create/edit
 * flows (Lunch Money / Monarch use side panels): the user keeps the
 * table they were editing in view, the panel has room for sectioned
 * forms (no cramped 7-decision modal), and the scroll axis matches
 * the form's length.
 *
 * The API mirrors `AlertDialog` 1:1 (`onDismissRequest` / `title` /
 * `text` / `confirmButton` / `dismissButton`) so call sites migrate
 * by renaming the composable — no rewiring.
 *
 * Behavior:
 *  - scrim click and Esc both dismiss
 *  - content area scrolls; the action row is pinned at the bottom
 *  - 440dp wide, full height, left border + scrim for depth (flat
 *    language — no glow, theme-consistent)
 */
@Composable
public fun SlideOverPanel(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    /** Panel width; 440dp fits a form, wider panels host side-by-side
     *  comparisons (the sell dialog's lot-method view). */
    width: Dp = 440.dp,
) {
    Popup(
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(BhodlTheme.colors.background.copy(alpha = 0.6f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                )
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onDismissRequest()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(width)
                    .background(BhodlTheme.colors.surface)
                    .border(1.dp, BhodlTheme.colors.outline)
                    // Consume clicks so taps inside the panel don't
                    // bubble to the scrim's dismiss handler.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(20.dp)
                    .testTag(SlideOverPanelTags.PANEL),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ProvideTextStyle(BhodlTheme.typography.titleMedium) {
                            title()
                        }
                    }
                    Text(
                        text = "✕",
                        style = BhodlTheme.typography.titleMedium,
                        color = BhodlTheme.colors.textTertiary,
                        modifier = Modifier
                            .clickable(onClick = onDismissRequest)
                            .padding(4.dp)
                            .testTag(SlideOverPanelTags.CLOSE),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    text()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

public object SlideOverPanelTags {
    public const val PANEL: String = "slideover.panel"
    public const val CLOSE: String = "slideover.close"
}
