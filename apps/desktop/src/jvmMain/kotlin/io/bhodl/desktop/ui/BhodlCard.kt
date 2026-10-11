package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.settings.ThemeMode
import io.bhodl.ui.theme.BhodlTheme

/**
 * Phase 9.1 (aesthetic unification) — the ONE card wrapper for every
 * tracking surface.
 *
 * BHODL's visual identity is Primer-flat (Bloomberg / Sparrow data
 * density: flat surfaces, 1dp outline, subtle radius). The Phase 4.1
 * "Wealth Builder" neon-glow language survives as an opt-in theme
 * ([ThemeMode.Neon]), not as the default dress of half the app — the
 * wallet side and the tracker side now share one grammar.
 *
 *  - **Default (Dark / Light / System)** — flat: `surface` fill,
 *    1dp `outline` border, [BhodlTheme.shapes.hero] radius (8dp).
 *    The `accent` is ignored here: in the flat language the semantic
 *    color belongs to the VALUE text, never to the chrome.
 *  - **Neon** — 16dp radius, accent-tinted gradient fill (when
 *    [gradient]) and the three-layer glow border from [neonBorder].
 *
 * Callers chain content padding themselves, exactly as they did with
 * the raw clip/background/border stack this replaces.
 */
@Composable
public fun Modifier.bhodlCard(
    accent: Color? = null,
    gradient: Boolean = false,
): Modifier {
    val neon = SettingsStore.themeMode.value == ThemeMode.Neon
    val surface = BhodlTheme.colors.surface
    return if (neon) {
        val edge = accent ?: BhodlTheme.colors.outline
        val glow = accent?.copy(alpha = 0.55f) ?: BhodlTheme.colors.accentNeonGlow
        val withBg = if (gradient && accent != null) {
            this
                .clip(RoundedCornerShape(16.dp))
                .background(gradientCardBrush(surface = surface, colorTint = accent))
        } else {
            this
                .clip(RoundedCornerShape(16.dp))
                .background(surface)
        }
        withBg.neonBorder(
            color = edge,
            glowColor = glow,
            cornerRadius = 16.dp,
        )
    } else {
        val shape = BhodlTheme.shapes.hero
        this
            .clip(shape)
            .background(surface)
            .border(1.dp, BhodlTheme.colors.outline, shape)
    }
}
