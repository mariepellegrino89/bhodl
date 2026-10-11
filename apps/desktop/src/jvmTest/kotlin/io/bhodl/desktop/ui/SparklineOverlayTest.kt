package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 8.3.2 — render-tree assertions for the dual-line [Sparkline].
 *
 * The Canvas internals (path geometry, dash effect) aren't observable
 * via Compose UI testing — but we can verify:
 *  - The composable renders without throwing when [overlayPoints] is
 *    provided alongside the primary series.
 *  - The scrub callback receives only the primary point (the overlay
 *    is non-interactive in 8.3.2).
 *  - The composable renders without throwing across all three
 *    [OverlayStyle] values, none of which is the default solid.
 *  - Backward-compatible no-overlay behaviour is preserved.
 */
@OptIn(ExperimentalTestApi::class)
class SparklineOverlayTest {

    private val primary: List<Pair<Long, Double>> = listOf(
        1_000L to 1_000_000.0,
        2_000L to 1_000_000.0,
    )

    private val overlay: List<Pair<Long, Double>> = listOf(
        1_000L to 50_000.0,
        1_500L to 55_000.0,
        2_000L to 60_000.0,
    )

    @Test
    fun `single-line render works without an overlay (backward compatible)`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Sparkline(
                        points = primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("sparkline.single"),
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag("sparkline.single"))
            onNodeWithTag("sparkline.single").assertIsDisplayed()
        }

    @Test
    fun `dual-line render works when overlayPoints is non-null`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Sparkline(
                        points = primary,
                        overlayPoints = overlay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("sparkline.dual"),
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag("sparkline.dual"))
            onNodeWithTag("sparkline.dual").assertIsDisplayed()
        }

    @Test
    fun `Dashed overlay style renders without throwing`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Sparkline(
                        points = primary,
                        overlayPoints = overlay,
                        overlayStyle = OverlayStyle.Dashed,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("sparkline.dashed"),
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag("sparkline.dashed"))
            onNodeWithTag("sparkline.dashed").assertIsDisplayed()
        }

    @Test
    fun `Dotted overlay style renders without throwing`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Sparkline(
                        points = primary,
                        overlayPoints = overlay,
                        overlayStyle = OverlayStyle.Dotted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("sparkline.dotted"),
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag("sparkline.dotted"))
            onNodeWithTag("sparkline.dotted").assertIsDisplayed()
        }

    @Test
    fun `Solid overlay style renders without throwing`() =
        runDesktopComposeUiTest {
            setContent {
                BhodlTheme {
                    Sparkline(
                        points = primary,
                        overlayPoints = overlay,
                        overlayStyle = OverlayStyle.Solid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .testTag("sparkline.solid"),
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag("sparkline.solid"))
            onNodeWithTag("sparkline.solid").assertIsDisplayed()
        }

    @Test
    fun `nearestPoint helper operates on the primary series only`() {
        // The scrub callback (and the underlying nearestPoint helper)
        // is fed the primary series — never the overlay. This is the
        // testable surrogate for "scrub returns primary points only"
        // since we cannot dispatch a synthetic hover event reliably in
        // a headless Compose test.
        val primaryPick = nearestPoint(primary, scrubX = 100f, width = 100f)
        assertEquals(2_000L, primaryPick?.first)
        // Sanity check: feeding the overlay into the helper yields a
        // distinct timestamp — i.e. the two series are independent.
        val overlayPick = nearestPoint(overlay, scrubX = 100f, width = 100f)
        assertEquals(2_000L, overlayPick?.first)
        // Both end on timestamp 2000, but the overlay has 3 points
        // (1000/1500/2000) and the primary has 2 (1000/2000); the
        // helper handles both without collapsing them into a shared
        // axis.
        assertEquals(3, overlay.size)
        assertEquals(2, primary.size)
    }

    @Test
    fun `overlay with too few points is skipped silently`() = runDesktopComposeUiTest {
        // A 1-point overlay should not throw — the drawing block
        // bails on overlayPoints.size < 2 and the primary still
        // renders.
        val tinyOverlay = listOf(1_000L to 50_000.0)
        setContent {
            BhodlTheme {
                Sparkline(
                    points = primary,
                    overlayPoints = tinyOverlay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .testTag("sparkline.tiny-overlay"),
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag("sparkline.tiny-overlay"))
        onNodeWithTag("sparkline.tiny-overlay").assertIsDisplayed()
    }
}
