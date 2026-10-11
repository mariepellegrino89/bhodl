package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase 4.1 — neon glow border modifier.
 *
 * Renders a soft halo behind a 1.5dp stroke at the requested corner
 * radius. Three concentric strokes of decreasing alpha simulate the
 * "neon tube" glow without needing a Skia BlurEffect (which would
 * tie us to JVM-only and add per-frame cost).
 *
 * Use on KPI cards, section containers, or any element that should
 * read as a Wealth Builder glow accent. Pass a per-card color (green
 * Income, red Expenses, cyan Net, gold Rate%) so each KPI keeps its
 * semantic identity while sharing the neon visual language.
 *
 * On non-neon themes the modifier still works — the caller's `color`
 * just renders as a normal colored border without the glow layers.
 */
public fun Modifier.neonBorder(
    color: Color,
    glowColor: Color = color.copy(alpha = 0.4f),
    cornerRadius: Dp = 16.dp,
    strokeWidth: Dp = 1.5.dp,
    glowRadius: Dp = 6.dp,
    padding: Dp = 0.dp,
): Modifier = this
    .padding(padding)
    .composed {
        Modifier.drawBehind {
            val r = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
            // Glow layers: stroked rectangles in increasing width with
            // decreasing alpha simulate a tube glow halo. Three layers
            // is a good balance between visible halo and overdraw.
            val glowSteps = listOf(
                Triple(strokeWidth.toPx() + glowRadius.toPx() * 1.6f, 0.06f, 0f),
                Triple(strokeWidth.toPx() + glowRadius.toPx() * 1.0f, 0.12f, 0f),
                Triple(strokeWidth.toPx() + glowRadius.toPx() * 0.4f, 0.22f, 0f),
            )
            for ((width, alpha, _) in glowSteps) {
                drawRoundRect(
                    color = glowColor.copy(alpha = glowColor.alpha * alpha * 4f),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = r,
                    style = Stroke(width = width),
                )
            }
            // Solid edge — the visible neon tube itself.
            drawRoundRect(
                color = color,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                cornerRadius = r,
                style = Stroke(width = strokeWidth.toPx()),
            )
        }
    }

/**
 * Phase 4.1 — convenience: gradient background for Wealth Builder
 * KPI cards. Top-left to bottom-right tint of `colorTint` over the
 * surface base, so each card has its own subtle color identity
 * (green-tinted Income, red-tinted Expenses, etc.) without losing
 * legibility of the white amount text on top.
 */
public fun gradientCardBrush(
    surface: Color,
    colorTint: Color,
    tintAlpha: Float = 0.12f,
): Brush = Brush.linearGradient(
    colors = listOf(
        colorTint.copy(alpha = tintAlpha * 1.4f),
        surface,
        colorTint.copy(alpha = tintAlpha * 0.5f),
    ),
)
