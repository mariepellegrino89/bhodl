package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme

/**
 * Unified top-bar for every post-wizard screen.
 *
 * Replaces the previous ad-hoc pattern of combining a standalone
 * `BhodlBackArrow` at `Alignment.TopStart` with a `Text(title)` inside
 * the content `Column`. The old pattern made screen titles visually
 * inconsistent — some screens showed "BHODL" + subtitle, others only a
 * title, and the back arrow was a sibling of the content Column rather
 * than part of the same layout row.
 *
 * ## Layout
 *
 * ```
 * [ ← back (48dp) | title (H2, fills space) | trailing slot ]
 * ```
 *
 * - The back arrow is rendered only when [onBack] is non-null. This
 *   lets wizard screens or top-level entry points reuse the component
 *   without an arrow. The arrow preserves [BhodlBackArrowTags.ROOT] so
 *   existing "back arrow present on every screen" tests keep passing.
 * - The title uses `headlineMedium` (28sp SemiBold), coloured with
 *   `textPrimary` — never orange. Brand orange is reserved for the
 *   "BHODL" logotype on the Dashboard, not for navigation chrome.
 * - The [trailing] slot is a `RowScope` lambda so callers can inject
 *   icon buttons, sync indicators, or pill badges without coupling
 *   the top-bar to any specific trailing control.
 *
 * ## Touch target
 *
 * The back button is `size(48.dp)` — up from the original 42dp — so it
 * satisfies the recommended 48×48dp minimum on touch-capable form
 * factors (the app is designed to port to mobile).
 */
@Composable
public fun BhodlTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag(BhodlBackArrowTags.ROOT),
            ) {
                Text(
                    text = "←",
                    style = MaterialTheme.typography.titleLarge,
                    color = BhodlTheme.colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Reserve the same width so the title stays centred
            // regardless of whether a back arrow is present.
            Spacer(modifier = Modifier.size(48.dp))
        }

        Text(
            text = title,
            style = BhodlTheme.typography.headlineMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Start,
        )

        trailing()
    }
}
