package io.bhodl.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import io.bhodl.ui.theme.BhodlTheme

/** Test tag of every [HelpTip] glyph. */
public const val HELP_TIP_TAG: String = "help-tip"

/**
 * Contextual help (PLAN §14.9): a small "?" next to a number, field or
 * setting; hovering it shows [text]. It is focusable, so keyboard users
 * reach it with Tab and see the same text while it has focus, and screen
 * readers read [text] as its description.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
public fun HelpTip(text: String, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    TooltipArea(
        tooltip = { HelpTipBubble(text) },
        delayMillis = 250,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
        modifier = modifier,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .border(1.dp, if (focused) BhodlTheme.colors.brandPrimary else BhodlTheme.colors.outline, CircleShape)
                    .focusable(interactionSource = interaction)
                    .semantics { contentDescription = text }
                    .testTag(HELP_TIP_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text("?", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = BhodlTheme.colors.textSecondary)
            }
            if (focused) {
                Popup(alignment = Alignment.TopStart, offset = androidx.compose.ui.unit.IntOffset(0, 40)) {
                    HelpTipBubble(text)
                }
            }
        }
    }
}

@Composable
private fun HelpTipBubble(text: String) {
    Box(
        modifier = Modifier
            .widthIn(max = 320.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BhodlTheme.colors.surfaceVariant)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text = text, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textPrimary)
    }
}
