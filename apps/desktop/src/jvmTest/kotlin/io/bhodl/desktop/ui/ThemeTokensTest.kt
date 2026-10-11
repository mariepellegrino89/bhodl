package io.bhodl.desktop.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.ui.theme.BhodlColors
import io.bhodl.ui.theme.BhodlDensity
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UX review 2026-10 (B11, B15) — the Material 3 colour roles that stock
 * components read must come from the app palette in every theme:
 *  - switch / text-field / outlined-button boundaries (`outline`) reach
 *    WCAG 2.2 SC 1.4.11 non-text contrast (3:1) against the canvas and
 *    the panel surface — the off-state switches were near-invisible;
 *  - dialog / date-picker / menu containers (`surfaceContainer*`) are
 *    app surfaces, never Material's baseline lavender.
 * Also covers the density CompositionLocal default.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeTokensTest {

    private data class Captured(val scheme: ColorScheme, val colors: BhodlColors)

    private fun capture(dark: Boolean, neon: Boolean): Captured {
        var captured: Captured? = null
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme(useDarkTheme = dark, neonMode = neon) {
                    captured = Captured(MaterialTheme.colorScheme, BhodlTheme.colors)
                }
            }
            waitForIdle()
        }
        return captured!!
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val themes = listOf(
        "light" to capture(dark = false, neon = false),
        "dark" to capture(dark = true, neon = false),
        "neon" to capture(dark = true, neon = true),
    )

    @Test
    fun `control boundaries reach 3 to 1 contrast in every theme`() {
        themes.forEach { (name, c) ->
            listOf(
                "background" to c.colors.background,
                "surface" to c.colors.surface,
                "switch track (surfaceContainerHighest)" to c.scheme.surfaceContainerHighest,
            ).forEach { (bgName, bg) ->
                val ratio = contrast(c.scheme.outline, bg)
                assertTrue(ratio >= 3.0, "$name: outline vs $bgName is ${"%.2f".format(ratio)}:1")
            }
        }
    }

    @Test
    fun `dialog and container roles use app surfaces`() {
        themes.forEach { (name, c) ->
            val appSurfaces = setOf(c.colors.background, c.colors.surface, c.colors.surfaceVariant)
            listOf(
                "surfaceContainerLowest" to c.scheme.surfaceContainerLowest,
                "surfaceContainerLow" to c.scheme.surfaceContainerLow,
                "surfaceContainer" to c.scheme.surfaceContainer,
                "surfaceContainerHigh" to c.scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to c.scheme.surfaceContainerHighest,
                "secondaryContainer" to c.scheme.secondaryContainer,
            ).forEach { (role, color) ->
                assertTrue(color in appSurfaces, "$name: $role $color is not an app surface")
            }
            assertEquals(Color.Transparent, c.scheme.surfaceTint, "$name: no tonal tint")
            assertEquals(c.colors.outline, c.scheme.outlineVariant, "$name: dividers")
        }
    }

    @Test
    fun `density defaults to Compact 32dp rows`() {
        var density: BhodlDensity? = null
        runDesktopComposeUiTest {
            setContent { BhodlTheme { density = BhodlTheme.density } }
            waitForIdle()
        }
        assertEquals(BhodlDensity.Compact, density)
        assertEquals(32f, density!!.rowHeight.value)
    }

    @Test
    fun `density override reaches the tree`() {
        var density: BhodlDensity? = null
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme(density = BhodlDensity.UltraDense) { density = BhodlTheme.density }
            }
            waitForIdle()
        }
        assertEquals(24f, density!!.rowHeight.value)
    }
}
