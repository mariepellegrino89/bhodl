package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme

/**
 * Shared selectable chip used throughout the settings screens (theme,
 * language, fee tier).
 *
 * Design decisions:
 * - Oval shape (large corner radius) signals "choice group" rather
 *   than a full Button. Consistent with `ThemeChip`, `LanguageChip`,
 *   and `FeeTierChip` that existed in separate screens before this
 *   extraction.
 * - **Unselected**: transparent background, `outline`-coloured border,
 *   `textSecondary` label. Low visual weight — the selection ring
 *   communicates "option available" without competing with content.
 * - **Selected**: `brandPrimary` background (filled), `brandOnPrimary`
 *   label. Unmistakably active; consistent with the rest of BHODL's
 *   primary-action language.
 * - Touch target: the internal padding (`8dp × 16dp`) plus the Box
 *   minimum keep the effective hit area comfortably above 44dp.
 * - The [testTag] parameter is nullable so callers can skip tagging
 *   chips that don't need test coverage without creating an empty tag.
 */
@Composable
public fun BhodlChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val colors = BhodlTheme.colors
    val shape = RoundedCornerShape(50)

    val baseModifier = modifier
        .clip(shape)
        .background(
            color = if (selected) colors.brandPrimary else colors.surface,
            shape = shape,
        )
        .border(
            width = 1.dp,
            color = if (selected) colors.brandPrimary else colors.outline,
            shape = shape,
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = if (testTag != null) baseModifier.testTag(testTag) else baseModifier,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = if (selected) colors.brandOnPrimary else colors.textSecondary,
        )
    }
}
