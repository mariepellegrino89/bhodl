/**
 * BHODL Design System v0 — Compose Multiplatform Theme Reference
 *
 * NOTA: Questo file è un REFERENCE PATTERN, non è compilabile as-is.
 * Vive in design/ durante Phase 0 scaffolding.
 * In Phase 1, verrà spostato in shared/src/commonMain/kotlin/bhodl/ui/theme/
 * e adattato all'effettivo modulo KMP condiviso.
 *
 * Pattern illustrato:
 *   1. BhodlColors / BhodlTypography data class con campi tipati
 *   2. BhodlLightTokens / BhodlDarkTokens — valori estratti da tokens.json
 *   3. CompositionLocal per dependency injection dei token nel Composition tree
 *   4. BhodlTheme() wrapper che configura MaterialTheme + espone i token custom
 *   5. Esempio di utilizzo nei componenti foglia
 *
 * Dipendenze (gradle kts):
 *   implementation("org.jetbrains.compose.material3:material3:1.8+")
 *   implementation("org.jetbrains.compose.ui:ui:1.8+")
 */

package bhodl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// 1. LAYER COLORS — per-layer color set (onchain, lightning, liquid, cashu…)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Colori per un singolo layer L2/L1.
 * fill = colore principale (badge, icona, indicatore).
 * onFill = testo/icona su fill.
 * badge = variante accent per dark surface.
 * muted = superficie tinta per section background.
 * textOnSurface = label layer su superficie neutra.
 */
data class LayerColors(
    val fill: Color,
    val onFill: Color,
    val badge: Color,
    val muted: Color,
    val textOnSurface: Color,
)

// ─────────────────────────────────────────────────────────────────────────────
// 2. PRIVACY STATE COLORS
// ─────────────────────────────────────────────────────────────────────────────

data class PrivacyStateColors(
    val private: Color,
    val privateOnFill: Color,
    val privateSurface: Color,
    val tracked: Color,
    val trackedOnFill: Color,
    val trackedSurface: Color,
    val kycTainted: Color,
    val kycTaintedOnFill: Color,
    val kycTaintedSurface: Color,
    val unknown: Color,
    val unknownOnFill: Color,
)

// ─────────────────────────────────────────────────────────────────────────────
// 3. BHODL COLORS — contenitore completo dei token di colore
// ─────────────────────────────────────────────────────────────────────────────

data class BhodlColors(
    // Brand
    val brandPrimary: Color,
    val brandOnPrimary: Color,
    val brandPrimaryContainer: Color,
    val brandOnPrimaryContainer: Color,

    // Layers
    val onchain: LayerColors,
    val lightning: LayerColors,
    val liquid: LayerColors,
    val cashu: LayerColors,
    val fedimint: LayerColors,
    val ark: LayerColors,

    // Semantic
    val success: Color,
    val successSurface: Color,
    val warning: Color,
    val warningSurface: Color,
    val danger: Color,
    val dangerSurface: Color,
    val info: Color,
    val infoSurface: Color,

    // Privacy
    val privacy: PrivacyStateColors,

    // Surface
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val surfaceElevated: Color,
    val outline: Color,
    val outlineVariant: Color,

    // Text
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val textLink: Color,
    val textAmountPrimary: Color, // sempre monocromatico, usato con Typography.amountHero

    val isDark: Boolean,
)

// ─────────────────────────────────────────────────────────────────────────────
// 4. BHODL TYPOGRAPHY — stili testo tipizzati
// ─────────────────────────────────────────────────────────────────────────────

/**
 * JetBrains Mono FontFamily placeholder.
 * In Phase 1: caricato da resources/fonts/ via androidx.compose.ui.text.font.Font()
 * per ciascuna piattaforma in expect/actual.
 */
val JetBrainsMonoFontFamily: FontFamily = FontFamily.Monospace // placeholder
val InterFontFamily: FontFamily = FontFamily.SansSerif         // placeholder

data class BhodlTypography(
    // Display / Headline — Inter
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val headlineLarge: TextStyle,
    val headlineMedium: TextStyle,
    val headlineSmall: TextStyle,
    val titleLarge: TextStyle,
    val titleMedium: TextStyle,
    val titleSmall: TextStyle,
    val bodyLarge: TextStyle,
    val bodyMedium: TextStyle,
    val bodySmall: TextStyle,
    val labelLarge: TextStyle,
    val labelMedium: TextStyle,
    val labelSmall: TextStyle,

    // Amount styles — JetBrains Mono OBBLIGATORIO
    // Regola: MAI usare Inter per cifre BTC/sat. Sempre uno di questi.
    val amountHero: TextStyle,    // Dashboard hero balance — 48sp semibold mono
    val amountLarge: TextStyle,   // Transaction detail — 32sp medium mono
    val amountMedium: TextStyle,  // Lista transazioni — 20sp medium mono
    val amountSmall: TextStyle,   // Fee/secondario inline — 14sp regular mono
)

// ─────────────────────────────────────────────────────────────────────────────
// 5. TOKEN VALUES — DARK (dark-first)
// ─────────────────────────────────────────────────────────────────────────────

private val darkColors = BhodlColors(
    isDark = true,

    brandPrimary            = Color(0xFFD4820A),
    brandOnPrimary          = Color(0xFFFFFFFF),
    brandPrimaryContainer   = Color(0xFF3D2200),
    brandOnPrimaryContainer = Color(0xFFFFDDB3),

    onchain = LayerColors(
        fill           = Color(0xFFE8820C),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFFFF9A2E),
        muted          = Color(0xFF2A1A00),
        textOnSurface  = Color(0xFFE8820C),
    ),
    lightning = LayerColors(
        fill           = Color(0xFF8B5CF6),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFFA78BFA),
        muted          = Color(0xFF1E0D3D),
        textOnSurface  = Color(0xFFA78BFA),
    ),
    liquid = LayerColors(
        fill           = Color(0xFF0D9488),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFF2DD4BF),
        muted          = Color(0xFF002926),
        textOnSurface  = Color(0xFF2DD4BF),
    ),
    cashu = LayerColors(
        fill           = Color(0xFFCA8A04),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFFFACC15),
        muted          = Color(0xFF1F1400),
        textOnSurface  = Color(0xFFFACC15),
    ),
    fedimint = LayerColors(
        fill           = Color(0xFFE11D48),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFFFB7185),
        muted          = Color(0xFF2D0013),
        textOnSurface  = Color(0xFFFB7185),
    ),
    ark = LayerColors(
        fill           = Color(0xFF0284C7),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFF38BDF8),
        muted          = Color(0xFF001A2E),
        textOnSurface  = Color(0xFF38BDF8),
    ),

    success       = Color(0xFF22C55E),
    successSurface = Color(0xFF052E16),
    warning       = Color(0xFFFCD34D),
    warningSurface = Color(0xFF1A1000),
    danger        = Color(0xFFF87171),
    dangerSurface  = Color(0xFF2D0000),
    info          = Color(0xFF60A5FA),
    infoSurface    = Color(0xFF00112D),

    privacy = PrivacyStateColors(
        private           = Color(0xFF34D399),
        privateOnFill     = Color(0xFF002918),
        privateSurface    = Color(0xFF002918),
        tracked           = Color(0xFFFCD34D),
        trackedOnFill     = Color(0xFF1A0F00),
        trackedSurface    = Color(0xFF1A1000),
        kycTainted        = Color(0xFFFCA5A5),
        kycTaintedOnFill  = Color(0xFF2D0000),
        kycTaintedSurface = Color(0xFF2D0000),
        unknown           = Color(0xFFA1A1AA),
        unknownOnFill     = Color(0xFF18181B),
    ),

    background      = Color(0xFF121212),
    surface         = Color(0xFF1E1E21),
    surfaceVariant  = Color(0xFF27272A),
    surfaceElevated = Color(0xFF2A2A2E),
    outline         = Color(0xFF3F3F46),
    outlineVariant  = Color(0xFF27272A),

    textPrimary       = Color(0xFFF4F4F5),
    textSecondary     = Color(0xFFA1A1AA),
    textTertiary      = Color(0xFF71717A),
    textDisabled      = Color(0xFF52525B),
    textLink          = Color(0xFF60A5FA),
    textAmountPrimary = Color(0xFFF4F4F5),
)

// ─────────────────────────────────────────────────────────────────────────────
// 6. TOKEN VALUES — LIGHT
// ─────────────────────────────────────────────────────────────────────────────

private val lightColors = BhodlColors(
    isDark = false,

    brandPrimary            = Color(0xFFB5690A),
    brandOnPrimary          = Color(0xFFFFFFFF),
    brandPrimaryContainer   = Color(0xFFFFECD4),
    brandOnPrimaryContainer = Color(0xFF2E1700),

    onchain = LayerColors(
        fill           = Color(0xFFC96800),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFFE8820C),
        muted          = Color(0xFFFFF4E6),
        textOnSurface  = Color(0xFFC96800),
    ),
    lightning = LayerColors(
        fill           = Color(0xFF6D28D9),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFF8B5CF6),
        muted          = Color(0xFFF5F0FF),
        textOnSurface  = Color(0xFF6D28D9),
    ),
    liquid = LayerColors(
        fill           = Color(0xFF0F766E),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFF0D9488),
        muted          = Color(0xFFF0FDFB),
        textOnSurface  = Color(0xFF0F766E),
    ),
    cashu = LayerColors(
        fill           = Color(0xFFA16207),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFFCA8A04),
        muted          = Color(0xFFFEFCE8),
        textOnSurface  = Color(0xFFA16207),
    ),
    fedimint = LayerColors(
        fill           = Color(0xFFBE123C),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFFE11D48),
        muted          = Color(0xFFFFF1F3),
        textOnSurface  = Color(0xFFBE123C),
    ),
    ark = LayerColors(
        fill           = Color(0xFF0369A1),
        onFill         = Color(0xFFFFFFFF),
        badge          = Color(0xFF0284C7),
        muted          = Color(0xFFF0F9FF),
        textOnSurface  = Color(0xFF0369A1),
    ),

    success        = Color(0xFF16A34A),
    successSurface = Color(0xFFF0FDF4),
    warning        = Color(0xFFD97706),
    warningSurface = Color(0xFFFFFBEB),
    danger         = Color(0xFFDC2626),
    dangerSurface  = Color(0xFFFFF1F2),
    info           = Color(0xFF2563EB),
    infoSurface    = Color(0xFFEFF6FF),

    privacy = PrivacyStateColors(
        private           = Color(0xFF059669),
        privateOnFill     = Color(0xFFFFFFFF),
        privateSurface    = Color(0xFFECFDF5),
        tracked           = Color(0xFFB45309),
        trackedOnFill     = Color(0xFFFFFFFF),
        trackedSurface    = Color(0xFFFFFBEB),
        kycTainted        = Color(0xFF9B1C1C),
        kycTaintedOnFill  = Color(0xFFFFFFFF),
        kycTaintedSurface = Color(0xFFFFF1F2),
        unknown           = Color(0xFF52525B),
        unknownOnFill     = Color(0xFFFFFFFF),
    ),

    background      = Color(0xFFFAFAFA),
    surface         = Color(0xFFFFFFFF),
    surfaceVariant  = Color(0xFFF4F4F5),
    surfaceElevated = Color(0xFFFFFFFF),
    outline         = Color(0xFFD1D1D6),
    outlineVariant  = Color(0xFFE4E4E7),

    textPrimary       = Color(0xFF18181B),
    textSecondary     = Color(0xFF52525B),
    textTertiary      = Color(0xFF71717A),
    textDisabled      = Color(0xFFA1A1AA),
    textLink          = Color(0xFF2563EB),
    textAmountPrimary = Color(0xFF18181B),
)

// ─────────────────────────────────────────────────────────────────────────────
// 7. TYPOGRAPHY VALUES (condivisi light/dark — solo i colori cambiano)
// ─────────────────────────────────────────────────────────────────────────────

private val bhodlTypography = BhodlTypography(
    displayLarge  = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Bold, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp),
    headlineLarge  = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineSmall  = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge   = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium  = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
    titleSmall   = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge    = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
    bodyMedium   = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
    bodySmall    = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelLarge   = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium  = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall   = TextStyle(fontFamily = InterFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),

    // Amount styles — JetBrains Mono
    amountHero   = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = 0.5.sp),
    amountLarge  = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.3.sp),
    amountMedium = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp),
    amountSmall  = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
)

// ─────────────────────────────────────────────────────────────────────────────
// 8. COMPOSITION LOCALS — DI dei token nel Composition tree
// ─────────────────────────────────────────────────────────────────────────────

val LocalBhodlColors = compositionLocalOf { darkColors }
val LocalBhodlTypography = compositionLocalOf { bhodlTypography }

/**
 * Accessor shorthand — equivalente a MaterialTheme.colorScheme ma per BHODL.
 *
 * Uso nei componenti:
 *   val colors = LocalBhodlColors.current
 *   val typo = LocalBhodlTypography.current
 */
object BhodlThemeTokens {
    val colors: BhodlColors
        @Composable get() = LocalBhodlColors.current

    val typography: BhodlTypography
        @Composable get() = LocalBhodlTypography.current
}

// ─────────────────────────────────────────────────────────────────────────────
// 9. MATERIAL 3 COLOR SCHEME derivato dai token BHODL
//    Mappa il sottoinsieme M3 → i nostri token semantici.
// ─────────────────────────────────────────────────────────────────────────────

private fun bhodlToM3ColorScheme(c: BhodlColors): ColorScheme = if (c.isDark) {
    darkColorScheme(
        primary          = c.brandPrimary,
        onPrimary        = c.brandOnPrimary,
        primaryContainer = c.brandPrimaryContainer,
        onPrimaryContainer = c.brandOnPrimaryContainer,
        background       = c.background,
        onBackground     = c.textPrimary,
        surface          = c.surface,
        onSurface        = c.textPrimary,
        surfaceVariant   = c.surfaceVariant,
        onSurfaceVariant = c.textSecondary,
        outline          = c.outline,
        outlineVariant   = c.outlineVariant,
        error            = c.danger,
        onError          = Color.White,
        errorContainer   = c.dangerSurface,
        onErrorContainer = c.danger,
    )
} else {
    lightColorScheme(
        primary          = c.brandPrimary,
        onPrimary        = c.brandOnPrimary,
        primaryContainer = c.brandPrimaryContainer,
        onPrimaryContainer = c.brandOnPrimaryContainer,
        background       = c.background,
        onBackground     = c.textPrimary,
        surface          = c.surface,
        onSurface        = c.textPrimary,
        surfaceVariant   = c.surfaceVariant,
        onSurfaceVariant = c.textSecondary,
        outline          = c.outline,
        outlineVariant   = c.outlineVariant,
        error            = c.danger,
        onError          = Color.White,
        errorContainer   = c.dangerSurface,
        onErrorContainer = c.danger,
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// 10. BhodlTheme — entry point composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Root theme composable per BHODL.
 *
 * Uso:
 *   BhodlTheme {
 *     App()
 *   }
 *
 * Con override:
 *   BhodlTheme(useDarkTheme = false) {
 *     SettingsScreen()
 *   }
 *
 * @param useDarkTheme   Segue isSystemInDarkTheme() per default.
 * @param content        Composition tree dell'app.
 */
@Composable
fun BhodlTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val bhodlColors = if (useDarkTheme) darkColors else lightColors
    val m3ColorScheme = bhodlToM3ColorScheme(bhodlColors)

    CompositionLocalProvider(
        LocalBhodlColors provides bhodlColors,
        LocalBhodlTypography provides bhodlTypography,
    ) {
        MaterialTheme(
            colorScheme = m3ColorScheme,
            // Typography M3 standard — i componenti BHODL custom useranno
            // LocalBhodlTypography.current direttamente.
            content = content,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 11. ESEMPI DI UTILIZZO NEI COMPONENTI FOGLIA
// ─────────────────────────────────────────────────────────────────────────────

/*

// Dashboard hero balance — on-chain wallet
@Composable
fun HeroBalance(amountSat: Long) {
    val colors = LocalBhodlColors.current
    val typo = LocalBhodlTypography.current

    Text(
        text = "${amountSat.formatSat()} sat",
        style = typo.amountHero,
        color = colors.textAmountPrimary,
    )
}

// Layer badge — indica su quale layer è una tx
@Composable
fun LayerBadge(layer: BhodlLayer) {
    val colors = LocalBhodlColors.current
    val layerColors = when (layer) {
        BhodlLayer.Onchain   -> colors.onchain
        BhodlLayer.Lightning -> colors.lightning
        BhodlLayer.Liquid    -> colors.liquid
        BhodlLayer.Cashu     -> colors.cashu
        BhodlLayer.Fedimint  -> colors.fedimint
        BhodlLayer.Ark       -> colors.ark
    }
    Surface(
        color = layerColors.muted,
        shape = RoundedCornerShape(BhodlTokens.RadiiSmall), // 4px
    ) {
        Text(
            text = layer.label,
            style = LocalBhodlTypography.current.labelSmall,
            color = layerColors.textOnSurface,
        )
    }
}

// Privacy score indicator — UTXO detail
@Composable
fun PrivacyBadge(state: PrivacyState) {
    val colors = LocalBhodlColors.current.privacy
    val (fill, onFill, label) = when (state) {
        PrivacyState.Private    -> Triple(colors.private, colors.privateOnFill, "Private")
        PrivacyState.Tracked    -> Triple(colors.tracked, colors.trackedOnFill, "Tracked")
        PrivacyState.KycTainted -> Triple(colors.kycTainted, colors.kycTaintedOnFill, "KYC")
        PrivacyState.Unknown    -> Triple(colors.unknown, colors.unknownOnFill, "?")
    }
    Surface(color = fill, shape = CircleShape) {
        Text(text = label, color = onFill, style = LocalBhodlTypography.current.labelSmall)
    }
}

*/
