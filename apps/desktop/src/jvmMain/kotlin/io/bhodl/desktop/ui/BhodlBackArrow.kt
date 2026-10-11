package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme

/**
 * Universal back-arrow chip rendered at the top-start of every
 * post-wizard screen. Functionally identical to the bottom-of-column
 * `Back` button each screen already exposes, but visually pinned to
 * the top-left so it stays above the fold on long screens (Send with
 * batch + coin control, History with many txs, Settings).
 *
 * Why a [`TextButton`] with a Unicode arrow rather than
 * `Icons.Default.ArrowBack`: the project does not depend on
 * `compose.material:material-icons` and the alpha desktop bundle has
 * no other icon need yet. Pulling in the icons artefact for a single
 * glyph would be ~1MB of payload just to satisfy one button. The
 * Unicode `←` (`←`) is rendered by the system font on every
 * supported platform.
 *
 * Renders at `48.dp` square so it satisfies the recommended 48×48dp
 * minimum touch target on touch-capable form factors and never crowds
 * out the screen title sitting inside the inner column.
 *
 * `testTag` is set so Compose UI tests can locate it by a known tag
 * (`BhodlBackArrowTags.ROOT`) — every screen adopts the same tag so
 * a shared "every screen has a back arrow at TopStart" test does not
 * need per-screen wiring.
 */
@Composable
public fun BhodlBackArrow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onBack,
        modifier = modifier
            .padding(start = 8.dp, top = 8.dp)
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
}

public object BhodlBackArrowTags {
    /** Stable tag for the back-arrow chip on every screen. */
    public const val ROOT: String = "bhodl.backarrow"
}
