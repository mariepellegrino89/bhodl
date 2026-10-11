package io.bhodl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * BHODL design-system theme entry point.
 *
 * Phase 7.0 — rebuilt for a Bloomberg/Sparrow-style data-heavy aesthetic.
 * Dark mode is the canonical experience (power-user crypto tracker);
 * light mode is supported but secondary. The Neon variant (Phase 4.1)
 * stays opt-in for users who liked the cyan glow but is no longer the
 * primary visual identity.
 *
 * Three color roles, never overlapping:
 *  - **Brand** (Bitcoin orange): logo, primary CTA, active nav. Never on data values.
 *  - **Semantic data** (green/red/amber): income/expense/pending. Never on buttons.
 *  - **Categorical chart** (Okabe-Ito 8-color): donut, treemap, stacked area.
 *    Color-blind safe — ~8% of males have red-green CVD so red/green alone is
 *    discriminatory. Categorical palette excludes brand orange and the
 *    semantic green/red entirely.
 *
 * Cultural color convention: Western markets read green=positive, Eastern
 * markets read red=positive. Toggle via [LocalColorConvention] (default
 * Western). Flips ONLY the semantic data colors; brand and error stay put.
 */
@Composable
public fun BhodlTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    colorConvention: ColorConvention = ColorConvention.WESTERN,
    neonMode: Boolean = false,
    density: BhodlDensity = BhodlDensity.Compact,
    content: @Composable () -> Unit,
) {
    val base = when {
        neonMode -> BhodlNeonColors
        useDarkTheme -> BhodlDarkColors
        else -> BhodlLightColors
    }
    val colors = base.withConvention(colorConvention)
    val typography = BhodlTypography.defaults()
    val shapes = BhodlShapes.defaults()
    val spacing = BhodlSpacing.defaults()

    CompositionLocalProvider(
        LocalBhodlColors provides colors,
        LocalBhodlTypography provides typography,
        LocalBhodlShapes provides shapes,
        LocalBhodlSpacing provides spacing,
        LocalColorConvention provides colorConvention,
        LocalBhodlDensity provides density,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial3ColorScheme(),
            typography = typography.toMaterial3Typography(),
            shapes = shapes.toMaterial3Shapes(),
            content = content,
        )
    }
}

/**
 * Convenience accessor for BHODL custom tokens inside a `@Composable`.
 *
 * Usage: `BhodlTheme.colors.brandPrimary`, `BhodlTheme.typography.amountHero`,
 * `BhodlTheme.spacing.md`, `BhodlTheme.shapes.panel`.
 */
public object BhodlTheme {
    public val colors: BhodlColors
        @Composable get() = LocalBhodlColors.current
    public val typography: BhodlTypography
        @Composable get() = LocalBhodlTypography.current
    public val shapes: BhodlShapes
        @Composable get() = LocalBhodlShapes.current
    public val spacing: BhodlSpacing
        @Composable get() = LocalBhodlSpacing.current
    public val colorConvention: ColorConvention
        @Composable get() = LocalColorConvention.current
    public val density: BhodlDensity
        @Composable get() = LocalBhodlDensity.current
}

// ─── Density ─────────────────────────────────────────────────────────────

/**
 * UX review 2026-10 — user-selectable row density for dense tables and
 * lists (Settings › Appearance › Density).
 *
 *  - [Compact] (default): 32dp rows — the Sparrow / Kubera table look.
 *  - [UltraDense]: 24dp rows, opt-in for power users who want more rows
 *    per screen (Bloomberg-terminal style).
 *
 * Table / list rows read [rowHeight] through [BhodlTheme.density]
 * instead of hard-coding a height, so the whole app follows the choice.
 */
public enum class BhodlDensity(public val rowHeight: Dp) {
    Compact(32.dp),
    UltraDense(24.dp),
}

public val LocalBhodlDensity: androidx.compose.runtime.ProvidableCompositionLocal<BhodlDensity> =
    staticCompositionLocalOf { BhodlDensity.Compact }

// ─── Colors ──────────────────────────────────────────────────────────────

/**
 * Cultural color convention for financial data:
 *  - WESTERN: positive=green, negative=red (US/EU default)
 *  - EASTERN: positive=red, negative=green (CN/JP/KR convention)
 *
 * Only the SEMANTIC layer (`financialPositive` / `financialNegative`) flips.
 * The PRIMITIVE layer (`financialGreen` / `financialRed`) and brand/error
 * stay fixed so error states are recognisable across conventions.
 */
public enum class ColorConvention { WESTERN, EASTERN }

public val LocalColorConvention: androidx.compose.runtime.ProvidableCompositionLocal<ColorConvention> =
    staticCompositionLocalOf { ColorConvention.WESTERN }

/**
 * BHODL color tokens for a single light-or-dark variant.
 *
 * Phase 7.0 — palette rebuilt for Bloomberg/Sparrow look: near-black
 * background, panel-style surfaces with subtle 1dp dividers, saturated
 * financial green/red for clear positive/negative signaling on dense
 * tables. Brand orange retreats to CTAs and Bitcoin-specific affordances.
 */
public data class BhodlColors(
    val brandPrimary: Color,
    val brandOnPrimary: Color,
    val brandPrimaryContainer: Color,
    val brandOnPrimaryContainer: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val onchainAccent: Color,
    val isDark: Boolean,
    /**
     * Accent color for primary CTA borders and active states. In the
     * Bloomberg/Sparrow dark palette this is a calm electric blue
     * (#58A6FF). In the Neon variant it's the cyan #00D4FF that drives
     * the visual identity. Consumed by Modifier.neonBorder() — name
     * kept for source compatibility with Phase 4.1 callers.
     */
    val accentNeon: Color,
    /** Glow halo color (typically [accentNeon] alpha-blended). */
    val accentNeonGlow: Color,
    /** True when this token bundle is the Neon variant — gates a few flourishes. */
    val isNeon: Boolean,
    val error: Color,
    val errorContainer: Color,
    val onError: Color,
    // ─── Financial primitives (convention-independent) ──
    val financialGreen: Color,
    val financialGreenMuted: Color,
    val financialRed: Color,
    val financialRedMuted: Color,
    val financialNeutral: Color,
    val financialPending: Color,
    // ─── Semantic layer (convention-aware, swapped at theme build) ──
    val financialPositive: Color,
    val financialPositiveMuted: Color,
    val financialNegative: Color,
    val financialNegativeMuted: Color,
    // ─── Categorical chart palette (Okabe-Ito, color-blind safe) ──
    val chartCategorical: List<Color>,
    // ─── Phase 7.0 — dense-table specifics ──
    /** Subtle row-hover highlight, < 5% opacity over surface. */
    val rowHover: Color,
    /** Alternating row band for zebra-striped tables (set very low alpha). */
    val rowZebra: Color,
    /** Cell border for tables when grid lines are required (very subtle). */
    val tableGrid: Color,
    /**
     * UX review 2026-10 (B11) — boundary of interactive controls in
     * their "off" / idle state: switch thumb + border, text-field and
     * outlined-button borders. Must reach WCAG 2.2 SC 1.4.11 (3:1
     * non-text contrast) against both [background] and [surface];
     * [outline] is a divider token and deliberately much softer.
     */
    val controlBorder: Color,
) {
    // UX review 2026-10 (B11, B15) — every Material 3 role a stock
    // component can read is mapped to an app token. Roles left unset
    // fall back to Material's baseline purple palette: AlertDialog /
    // DatePicker read `surfaceContainerHigh` (the lavender dialogs in
    // light), Switch reads `surfaceContainerHighest` + `outline` for its
    // off state (the near-invisible switches in dark), FilterChip reads
    // `secondaryContainer`, tonal elevation tints with `surfaceTint`.
    internal fun toMaterial3ColorScheme() = if (isDark) {
        darkColorScheme(
            primary = brandPrimary,
            onPrimary = brandOnPrimary,
            primaryContainer = brandPrimaryContainer,
            onPrimaryContainer = brandOnPrimaryContainer,
            inversePrimary = brandPrimary,
            secondary = accentNeon,
            onSecondary = background,
            secondaryContainer = surfaceVariant,
            onSecondaryContainer = textPrimary,
            tertiary = accentNeon,
            onTertiary = background,
            tertiaryContainer = surfaceVariant,
            onTertiaryContainer = textPrimary,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = textSecondary,
            surfaceTint = Color.Transparent,
            inverseSurface = textPrimary,
            inverseOnSurface = background,
            outline = controlBorder,
            outlineVariant = outline,
            error = error,
            errorContainer = errorContainer,
            onError = onError,
            onErrorContainer = textPrimary,
            surfaceBright = surfaceVariant,
            surfaceDim = background,
            surfaceContainerLowest = background,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            // Dialogs / date pickers: one step above the panels so the
            // dialog reads as a raised layer over the dark canvas.
            surfaceContainerHigh = surfaceVariant,
            surfaceContainerHighest = surfaceVariant,
        )
    } else {
        lightColorScheme(
            primary = brandPrimary,
            onPrimary = brandOnPrimary,
            primaryContainer = brandPrimaryContainer,
            onPrimaryContainer = brandOnPrimaryContainer,
            inversePrimary = brandPrimary,
            secondary = accentNeon,
            onSecondary = background,
            secondaryContainer = surfaceVariant,
            onSecondaryContainer = textPrimary,
            tertiary = accentNeon,
            onTertiary = background,
            tertiaryContainer = surfaceVariant,
            onTertiaryContainer = textPrimary,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = textSecondary,
            surfaceTint = Color.Transparent,
            inverseSurface = textPrimary,
            inverseOnSurface = background,
            outline = controlBorder,
            outlineVariant = outline,
            error = error,
            errorContainer = errorContainer,
            onError = onError,
            onErrorContainer = textPrimary,
            surfaceBright = background,
            surfaceDim = surfaceVariant,
            surfaceContainerLowest = background,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            // Dialogs in light: the white canvas colour, lifted by the
            // dialog's shadow + scrim (Primer overlay style), never the
            // Material baseline lavender.
            surfaceContainerHigh = background,
            surfaceContainerHighest = surfaceVariant,
        )
    }

    internal fun withConvention(c: ColorConvention): BhodlColors = when (c) {
        ColorConvention.WESTERN -> copy(
            financialPositive = financialGreen,
            financialPositiveMuted = financialGreenMuted,
            financialNegative = financialRed,
            financialNegativeMuted = financialRedMuted,
        )
        ColorConvention.EASTERN -> copy(
            financialPositive = financialRed,
            financialPositiveMuted = financialRedMuted,
            financialNegative = financialGreen,
            financialNegativeMuted = financialGreenMuted,
        )
    }
}

/**
 * Okabe-Ito 8-color CUD palette (dark mode variant). The categorical
 * palette must never collide with the brand color or with the semantic
 * financial green/red.
 */
private val OkabeItoDark: List<Color> = listOf(
    Color(0xFF56B4E9), // sky blue
    Color(0xFF009E73), // bluish green
    Color(0xFFCC79A7), // reddish purple
    Color(0xFF0072B2), // blue
    Color(0xFFE69F00), // orange-adjacent (distinct from brand)
    Color(0xFFD55E00), // vermillion
    Color(0xFFF0E442), // yellow
    Color(0xFF8B8B8B), // neutral grey — "Other" bucket
)

private val OkabeItoLight: List<Color> = listOf(
    Color(0xFF1F78B4),
    Color(0xFF00875A),
    Color(0xFFA63A7A),
    Color(0xFF003F77),
    Color(0xFFB17500),
    Color(0xFFA0440A),
    Color(0xFF7A7000),
    Color(0xFF52525B),
)

/**
 * Phase 7.0 — Bloomberg/Sparrow dark palette. Anchored on GitHub
 * Primer dark (battle-tested for dense data UIs) with Bitcoin-orange
 * brand color and saturated financial green/red.
 *
 * Background tokens:
 *  - background `#0D1117` — primary canvas (Primer dark `canvas.default`)
 *  - surface    `#161B22` — panel surface (Primer dark `canvas.subtle`)
 *  - surfaceVariant `#21262D` — elevated chip / hover state
 *  - outline    `#30363D` — divider / panel border (1dp)
 *
 * Text tokens:
 *  - textPrimary   `#E6EDF3` — body (Primer `fg.default`)
 *  - textSecondary `#8B949E` — labels / metadata (Primer `fg.muted`)
 *  - textTertiary  `#6E7681` — placeholder / disabled (Primer `fg.subtle`)
 *
 * Financial tokens:
 *  - green  `#3FB950` (Primer `success.fg`) — gain / positive
 *  - red    `#F85149` (Primer `danger.fg`) — loss / negative
 *  - amber  `#D29922` (Primer `attention.fg`) — pending / mempool
 *
 * Accent (CTA primary): `#58A6FF` (Primer `accent.fg`) — calm electric
 * blue for focus rings and primary buttons. Brand orange stays on
 * Bitcoin-specific UI (wallet icon, sat/BTC pill).
 */
internal val BhodlDarkColors: BhodlColors = BhodlColors(
    // Bitcoin orange — official spec #F7931A, slightly tinted for screen.
    brandPrimary = Color(0xFFF7931A),
    brandOnPrimary = Color(0xFF0D1117),
    brandPrimaryContainer = Color(0xFF3D2200),
    brandOnPrimaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    surfaceVariant = Color(0xFF21262D),
    outline = Color(0xFF30363D),
    textPrimary = Color(0xFFE6EDF3),
    textSecondary = Color(0xFF8B949E),
    textTertiary = Color(0xFF6E7681),
    onchainAccent = Color(0xFFF7931A),
    isDark = true,
    // Calm electric blue — calls CTA / focus. Not a glow halo here.
    accentNeon = Color(0xFF58A6FF),
    accentNeonGlow = Color(0x3358A6FF),
    isNeon = false,
    // Primer danger.fg — 4.9:1 contrast on #0D1117
    error = Color(0xFFF85149),
    errorContainer = Color(0x4D7F1D1D),
    onError = Color(0xFFFFFFFF),
    // Saturated financial colors — Primer dark spec, clearly readable
    // on near-black background without halation.
    financialGreen = Color(0xFF3FB950),
    financialGreenMuted = Color(0xFF2EA043),
    financialRed = Color(0xFFF85149),
    financialRedMuted = Color(0xFFDA3633),
    financialNeutral = Color(0xFF8B949E),
    financialPending = Color(0xFFD29922),
    // Semantic placeholders — overwritten by withConvention() at theme build.
    financialPositive = Color(0xFF3FB950),
    financialPositiveMuted = Color(0xFF2EA043),
    financialNegative = Color(0xFFF85149),
    financialNegativeMuted = Color(0xFFDA3633),
    chartCategorical = OkabeItoDark,
    rowHover = Color(0x14FFFFFF),         // white 8% on dark
    rowZebra = Color(0x08FFFFFF),         // white 3% on dark
    tableGrid = Color(0xFF21262D),
    // Primer fg.subtle — 4.1:1 on background, 3.8:1 on surface, 3.3:1 on surfaceVariant.
    controlBorder = Color(0xFF6E7681),
)

/**
 * Phase 7.0 — light mode consistent with the dark palette. Kept for
 * accessibility / user preference, but the primary design system is
 * dark mode (power-user crypto tracker).
 *
 * Tokens based on Primer light + Bitcoin orange brand.
 */
internal val BhodlLightColors: BhodlColors = BhodlColors(
    brandPrimary = Color(0xFFB5690A),
    brandOnPrimary = Color(0xFFFFFFFF),
    brandPrimaryContainer = Color(0xFFFFE0B0),
    brandOnPrimaryContainer = Color(0xFF2D1900),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F8FA),                // Primer light canvas.subtle
    surfaceVariant = Color(0xFFEAEEF2),
    outline = Color(0xFFD0D7DE),                // Primer light border.default
    textPrimary = Color(0xFF1F2328),            // Primer light fg.default
    textSecondary = Color(0xFF656D76),
    textTertiary = Color(0xFF8C959F),
    onchainAccent = Color(0xFFB5690A),
    isDark = false,
    accentNeon = Color(0xFF0969DA),             // Primer light accent.fg
    accentNeonGlow = Color(0x330969DA),
    isNeon = false,
    error = Color(0xFFCF222E),                  // Primer light danger.fg
    errorContainer = Color(0xFFFFEBE9),
    onError = Color(0xFFFFFFFF),
    financialGreen = Color(0xFF1A7F37),         // Primer light success.fg
    financialGreenMuted = Color(0xFF2C8B45),
    financialRed = Color(0xFFCF222E),
    financialRedMuted = Color(0xFFA40E26),
    financialNeutral = Color(0xFF656D76),
    financialPending = Color(0xFF9A6700),       // Primer light attention.fg
    financialPositive = Color(0xFF1A7F37),
    financialPositiveMuted = Color(0xFF2C8B45),
    financialNegative = Color(0xFFCF222E),
    financialNegativeMuted = Color(0xFFA40E26),
    chartCategorical = OkabeItoLight,
    rowHover = Color(0x0F000000),
    rowZebra = Color(0x05000000),
    tableGrid = Color(0xFFEAEEF2),
    // Primer light fg.subtle — 4.6:1 on white, 4.3:1 on surface, 3.9:1 on surfaceVariant.
    controlBorder = Color(0xFF6E7781),
)

/**
 * Phase 4.1 — Wealth Builder neon palette (opt-in legacy).
 *
 * Kept as a user option for those who prefer the navy + cyan +
 * gradient identity. Phase 7.0 makes it opt-in under Settings → Theme;
 * the default is now the Bloomberg/Sparrow palette.
 */
internal val BhodlNeonColors: BhodlColors = BhodlColors(
    brandPrimary = Color(0xFFFFA133),
    brandOnPrimary = Color(0xFF0A0E1F),
    brandPrimaryContainer = Color(0xFF3A2410),
    brandOnPrimaryContainer = Color(0xFFFFE0B0),
    background = Color(0xFF0A0E1F),
    surface = Color(0xFF11172B),
    surfaceVariant = Color(0xFF1A2240),
    outline = Color(0xFF2A3550),
    textPrimary = Color(0xFFE6F1FF),
    textSecondary = Color(0xFF95B5D0),
    textTertiary = Color(0xFF5A7A95),
    onchainAccent = Color(0xFFFFA133),
    isDark = true,
    error = Color(0xFFFF5C7A),
    errorContainer = Color(0x4D7F1D2D),
    onError = Color(0xFFFFFFFF),
    financialGreen = Color(0xFF3DE07A),
    financialGreenMuted = Color(0xFF2DC56A),
    financialRed = Color(0xFFFF5C7A),
    financialRedMuted = Color(0xFFE05A75),
    financialNeutral = Color(0xFF95B5D0),
    financialPending = Color(0xFFFFD166),
    financialPositive = Color(0xFF3DE07A),
    financialPositiveMuted = Color(0xFF2DC56A),
    financialNegative = Color(0xFFFF5C7A),
    financialNegativeMuted = Color(0xFFE05A75),
    chartCategorical = OkabeItoDark,
    accentNeon = Color(0xFF00D4FF),
    accentNeonGlow = Color(0x6600D4FF),
    isNeon = true,
    rowHover = Color(0x1400D4FF),
    rowZebra = Color(0x0800D4FF),
    tableGrid = Color(0xFF1A2240),
    // Neon textTertiary — 4.3:1 on background, 3.9:1 on surface, 3.5:1 on surfaceVariant.
    controlBorder = Color(0xFF5A7A95),
)

public val LocalBhodlColors: androidx.compose.runtime.ProvidableCompositionLocal<BhodlColors> =
    staticCompositionLocalOf { BhodlDarkColors }

// ─── Typography ──────────────────────────────────────────────────────────

/**
 * Phase 7.0 — typography tuned for dense Bloomberg/Sparrow tables.
 *
 * Two font families:
 *  - **Sans-serif** (system): UI text, labels, headings.
 *  - **Monospace** (system Mono): every numeric amount (BTC / sat /
 *    fiat / percentage). Required for tabular alignment in tables and
 *    consistent column widths regardless of the value.
 *
 * Size scale:
 *  - amountHero    56sp — TrackerHome net worth hero
 *  - displaySmall  36sp — secondary hero (e.g. dashboard balance)
 *  - headlineMedium 24sp — section titles
 *  - titleMedium   16sp — card/panel titles
 *  - amountMedium  18sp — inline amounts (kept compact for tables)
 *  - bodyMedium    14sp — body copy, table cell text
 *  - amountSmall   13sp — table-cell amounts (Kubera-style density)
 *  - labelMedium   11sp — table headers, metadata
 *  - mono          12sp — addresses, txids
 *
 * All amount styles use `FontFamily.Monospace` for tabular-friendly
 * digits; system mono on JVM resolves to Consolas / Menlo / DejaVu Mono
 * which all ship tabular nums by default; `tnum` is also requested
 * explicitly (UX review 2026-10) so a proportional fallback font still
 * lines digits up in columns.
 */
public data class BhodlTypography(
    val displaySmall: TextStyle,
    val headlineMedium: TextStyle,
    val titleMedium: TextStyle,
    val bodyMedium: TextStyle,
    val labelMedium: TextStyle,
    val amountHero: TextStyle,
    val amountMedium: TextStyle,
    val amountSmall: TextStyle,
    val mono: TextStyle,
) {
    internal fun toMaterial3Typography() = Typography(
        displaySmall = displaySmall,
        headlineMedium = headlineMedium,
        titleMedium = titleMedium,
        bodyMedium = bodyMedium,
        labelMedium = labelMedium,
    )

    public companion object {
        internal fun defaults(): BhodlTypography {
            val sans = FontFamily.SansSerif
            val mono = FontFamily.Monospace
            return BhodlTypography(
                displaySmall = TextStyle(
                    fontFamily = sans,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 44.sp,
                ),
                headlineMedium = TextStyle(
                    fontFamily = sans,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 32.sp,
                ),
                titleMedium = TextStyle(
                    fontFamily = sans,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                ),
                bodyMedium = TextStyle(
                    fontFamily = sans,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 20.sp,
                ),
                labelMedium = TextStyle(
                    fontFamily = sans,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 16.sp,
                    letterSpacing = 0.4.sp,
                ),
                amountHero = TextStyle(
                    fontFamily = mono,
                    fontFeatureSettings = "tnum",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 64.sp,
                ),
                amountMedium = TextStyle(
                    fontFamily = mono,
                    fontFeatureSettings = "tnum",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 24.sp,
                ),
                amountSmall = TextStyle(
                    fontFamily = mono,
                    fontFeatureSettings = "tnum",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 18.sp,
                ),
                mono = TextStyle(
                    fontFamily = mono,
                    fontFeatureSettings = "tnum",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    lineHeight = 16.sp,
                    letterSpacing = 0.2.sp,
                ),
            )
        }
    }
}

public val LocalBhodlTypography: androidx.compose.runtime.ProvidableCompositionLocal<BhodlTypography> =
    staticCompositionLocalOf { BhodlTypography.defaults() }

// ─── Shapes ──────────────────────────────────────────────────────────────

/**
 * Phase 7.0 — flatter shapes for Bloomberg/Sparrow look.
 *
 * Pre-7.0 cards used 12-16dp rounded corners (Material 3 default).
 * The new look uses 4-6dp corners — closer to "panel" than to "card",
 * which suits dense tables and reduces visual chunkiness when many
 * cards stack vertically.
 */
public data class BhodlShapes(
    /** Generic surface / card. 4dp — panel-like. */
    val panel: androidx.compose.foundation.shape.CornerBasedShape,
    /** Buttons, chips. 6dp — slightly softer than panels. */
    val button: androidx.compose.foundation.shape.CornerBasedShape,
    /** Hero / KPI surfaces where we still want a soft container. 8dp. */
    val hero: androidx.compose.foundation.shape.CornerBasedShape,
    /** Pills (Tor badge, network badge, etc.) — full round. */
    val pill: androidx.compose.foundation.shape.CornerBasedShape,
) {
    internal fun toMaterial3Shapes() = Shapes(
        extraSmall = button,
        small = button,
        medium = panel,
        large = hero,
        extraLarge = hero,
    )

    public companion object {
        internal fun defaults(): BhodlShapes = BhodlShapes(
            panel = RoundedCornerShape(4.dp),
            button = RoundedCornerShape(6.dp),
            hero = RoundedCornerShape(8.dp),
            pill = RoundedCornerShape(50),
        )
    }
}

public val LocalBhodlShapes: androidx.compose.runtime.ProvidableCompositionLocal<BhodlShapes> =
    staticCompositionLocalOf { BhodlShapes.defaults() }

// ─── Spacing ─────────────────────────────────────────────────────────────

/**
 * Phase 7.0 — explicit spacing scale (4dp grid). Replaces the ad-hoc
 * 8/12/16/24 values scattered across composables.
 *
 *  - xs  4dp  — inside a chip, between icon and label
 *  - sm  8dp  — between row items, inside a tight card
 *  - md  12dp — between cards/rows in a list (Bloomberg row height)
 *  - lg  16dp — between sections of a screen
 *  - xl  24dp — between major regions, screen padding
 *  - xxl 32dp — hero area padding
 */
public data class BhodlSpacing(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val lg: Dp,
    val xl: Dp,
    val xxl: Dp,
) {
    public companion object {
        internal fun defaults(): BhodlSpacing = BhodlSpacing(
            xs = 4.dp,
            sm = 8.dp,
            md = 12.dp,
            lg = 16.dp,
            xl = 24.dp,
            xxl = 32.dp,
        )
    }
}

public val LocalBhodlSpacing: androidx.compose.runtime.ProvidableCompositionLocal<BhodlSpacing> =
    staticCompositionLocalOf { BhodlSpacing.defaults() }
