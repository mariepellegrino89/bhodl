package io.bhodl.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.money.AmountMask
import io.bhodl.desktop.ui.money.BitcoinAmountFormat
import io.bhodl.desktop.ui.money.amountsMasked
import io.bhodl.desktop.ui.money.UnitFormat
import java.util.Locale

/**
 * Unit-aware amount formatter for the desktop UI — the composable entry
 * point to [BitcoinAmountFormat].
 *
 * Reads the user's "Bitcoin unit" (Sats / BTC / Auto) and BIP177
 * preferences from [SettingsStore] and formats with the app locale's
 * separators. Use everywhere the UI displays an on-chain amount; raw
 * `formatSat(...)` calls stay for log lines / debug surfaces / CSV
 * exports where the unit is intentionally fixed.
 *
 * Sats: "1,234,567 sat" (or "₿1,234,567" with BIP177).
 * BTC:  "0.01234567 BTC" (8-decimal fixed).
 *
 * Mixing units inside one screen is a known footgun: prefer this helper
 * over a screen-local override so the entire app updates atomically
 * when the user flips the unit in Settings.
 */
@Composable
public fun displayAmount(sat: ULong): String = rememberAmountFormatter()(
    if (sat > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else sat.toLong(),
)

/** [displayAmount] for a signed [Long] (negative keeps its `-`). */
@Composable
public fun displayAmount(sat: Long): String = rememberAmountFormatter()(sat)

/**
 * UX review 2026-10 (item 13, security review) — the ONE place a sign
 * glyph for a signed AMOUNT is chosen: `+` for positive (when
 * [plusForPositive]), [minus] for negative, nothing for zero — and
 * nothing at all while [masked], because the sign (like a green / red
 * colour, see [signedAmountColor]) reveals the direction of a hidden
 * amount. UI code must not hand-roll `if (x > 0) "+"`; a scan test
 * (`SignedAmountScanTest`) enforces it.
 */
public fun amountSign(
    value: Long,
    masked: Boolean = AmountMask.active,
    minus: String = "−",
    plusForPositive: Boolean = true,
): String = when {
    masked -> ""
    value > 0L -> if (plusForPositive) "+" else ""
    value < 0L -> minus
    else -> ""
}

/** [amountSign] for fiat / BTC-as-Double values. */
public fun amountSign(
    value: Double,
    masked: Boolean = AmountMask.active,
    minus: String = "−",
    plusForPositive: Boolean = true,
): String = when {
    masked -> ""
    value > 0.0 -> if (plusForPositive) "+" else ""
    value < 0.0 -> minus
    else -> ""
}

/**
 * Colour of a signed amount: [positive] / [negative] / [neutral] by
 * sign, always [neutral] while the privacy mode masks amounts here.
 */
@Composable
public fun signedAmountColor(
    value: Double?,
    positive: androidx.compose.ui.graphics.Color,
    negative: androidx.compose.ui.graphics.Color,
    neutral: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.graphics.Color = when {
    value == null || amountsMasked() -> neutral
    value > 0.0 -> positive
    value < 0.0 -> negative
    else -> neutral
}

/**
 * Signed variant: positive values get a `+`, negative `−`, zero just
 * the raw amount. Used by the "Net" KPI row, the signed cell in the
 * Reports table and transaction nets in History.
 */
@Composable
public fun displaySignedAmount(net: Long): String {
    // Privacy mode: no sign either (it reveals the flow direction).
    val abs = if (net < 0L) -net else net
    return "${amountSign(net, masked = amountsMasked())}${displayAmount(abs)}"
}

/**
 * UX review 2026-10 — formatter lambda bound to the current unit /
 * BIP177 preferences, for places that format amounts outside a direct
 * composable call (inside `buildString`, list mappers, callbacks).
 * Reading the preferences here subscribes the caller to them, so a
 * change in Settings re-formats every amount.
 */
@Composable
public fun rememberAmountFormatter(): (Long) -> String {
    val settingsUnit by SettingsStore.unitFormat
    val unit = LocalColumnUnit.current ?: settingsUnit
    val bip177 by SettingsStore.bip177Notation
    val locale = Locale.getDefault()
    // UX review 2026-10 (item 13) — privacy mode, minus a local
    // RevealAmounts (payment confirmation screens).
    val masked = amountsMasked()
    return remember(unit, bip177, locale, masked) {
        { sat: Long -> BitcoinAmountFormat.format(sat, unit, bip177 = bip177, locale = locale, masked = masked) }
    }
}

/**
 * UX review 2026-10 (rec 2) — unit already resolved for the enclosing
 * table ([ProvideColumnUnit]). When set, [rememberAmountFormatter],
 * [displayAmount] and the `Money` composable use it instead of the raw
 * setting, so Auto cannot render one row in sats and the next in BTC.
 */
public val LocalColumnUnit: androidx.compose.runtime.ProvidableCompositionLocal<UnitFormat?> =
    androidx.compose.runtime.compositionLocalOf { null }

/**
 * Resolve the user's unit once for a table whose amount columns hold
 * [columnSats] ([BitcoinAmountFormat.resolveColumnUnit]) and render
 * [content] with it. For tables whose cells keep the unit suffix (e.g.
 * dual BTC / fiat `Money` cells).
 */
@Composable
public fun ProvideColumnUnit(columnSats: Iterable<Long>, content: @Composable () -> Unit) {
    val unit by SettingsStore.unitFormat
    androidx.compose.runtime.CompositionLocalProvider(
        LocalColumnUnit provides BitcoinAmountFormat.resolveColumnUnit(unit, columnSats, masked = amountsMasked()),
        content = content,
    )
}

/**
 * UX review 2026-10 (rec 2) — formatter for ONE table column.
 *
 * [unit] is already resolved to sats or BTC for the whole column (see
 * [BitcoinAmountFormat.resolveColumnUnit]), so Auto never mixes the two
 * inside a column and every cell has the same number of decimals.
 * Tables put [unitLabel] in the column header and render cells with
 * [bare]; where a header cannot carry the unit, [full] keeps the suffix.
 */
public class ColumnAmountFormat(
    public val unit: UnitFormat,
    public val bip177: Boolean,
    public val locale: Locale,
    /** Item 13 — privacy mask; null = the app-wide flag at format time. */
    private val maskedOverride: Boolean? = null,
) {
    private val masked: Boolean get() = maskedOverride ?: AmountMask.active

    /** `sat`, `BTC` or `₿` (BIP177 sats). */
    public val unitLabel: String get() = BitcoinAmountFormat.unitLabel(unit, bip177)

    /** Number only: `4,230,118` / `0.04230118`. */
    public fun bare(sat: Long, signed: Boolean = false): String =
        BitcoinAmountFormat.formatBare(sat, unit, locale, signed, masked = masked)

    /** Number with its unit, same column unit: `4,230,118 sat` / `0.04230118 BTC`. */
    public fun full(sat: Long, signed: Boolean = false): String =
        BitcoinAmountFormat.format(sat, unit, bip177 = bip177, locale = locale, signed = signed, masked = masked)
}

/**
 * [ColumnAmountFormat] for a column holding [columnSats], bound to the
 * user's unit / BIP177 preferences (re-resolves when they change).
 */
@Composable
public fun rememberColumnAmountFormat(columnSats: Iterable<Long>): ColumnAmountFormat {
    val unit by SettingsStore.unitFormat
    val bip177 by SettingsStore.bip177Notation
    val locale = Locale.getDefault()
    val masked = amountsMasked()
    val resolved = BitcoinAmountFormat.resolveColumnUnit(unit, columnSats, masked = masked)
    return remember(resolved, bip177, locale, masked) { ColumnAmountFormat(resolved, bip177, locale, masked) }
}

/**
 * Compact formatter ([BitcoinAmountFormat.formatCompact]) for chart
 * labels and axis ticks, bound to the unit / BIP177 preferences. Pass
 * [axisSats] (the values the axis spans) so Auto picks ONE unit for
 * every tick of the axis; null keeps Auto per value (node labels).
 */
@Composable
public fun rememberCompactAmountFormatter(axisSats: Iterable<Long>? = null): (Long) -> String {
    val unit by SettingsStore.unitFormat
    val bip177 by SettingsStore.bip177Notation
    val locale = Locale.getDefault()
    val masked = amountsMasked()
    val resolved = when {
        axisSats != null -> BitcoinAmountFormat.resolveColumnUnit(unit, axisSats, masked = masked)
        // Auto per value would put "BTC" on the big labels only.
        masked && unit == io.bhodl.desktop.ui.money.UnitFormat.AUTO_THRESHOLD -> io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT
        else -> unit
    }
    return remember(resolved, bip177, locale, masked) {
        { sat: Long -> BitcoinAmountFormat.formatCompact(sat, resolved, bip177 = bip177, locale = locale, masked = masked) }
    }
}

/** Column header with its unit: `Amount (sat)`. */
public fun headerWithUnit(label: String, unitLabel: String): String = "$label ($unitLabel)"

/**
 * Phase 5.0 — amount + optional fiat overlay.
 *
 * Renders `1,234,567 sat (~€0.42)` when the fiat overlay is enabled
 * (SettingsStore.fiatCurrency != NONE) AND the PriceCache has a
 * fresh price. Falls back to the bare sat/BTC display when fiat is
 * disabled or the price hasn't been fetched yet.
 *
 * The "~" prefix is a deliberate cue that the conversion is
 * approximate — BTC price moves continuously and the cached value
 * may be up to ~5 minutes old.
 */
@Composable
public fun displayAmountWithFiat(sat: ULong): String {
    val base = displayAmount(sat)
    val currency by SettingsStore.fiatCurrency
    if (currency == FiatCurrency.NONE) return base
    val fiatValue = PriceCache.satToFiat(sat) ?: return base
    return "$base (~${formatFiatWithCode(fiatValue, currency.code, masked = amountsMasked())})"
}

/**
 * Phase 5.0 — format a fiat amount with 2 decimal places (HALF_UP) and
 * the [locale]'s grouping / decimal separators, like the bitcoin
 * formatter. Symbol prefixes / suffixes are the caller's job — prefer
 * [formatFiatWithCode], which also places the symbol per locale.
 *
 * Example: 1234.567 → "1,234.57" (en) / "1.234,57" (it)
 */
public fun formatFiat(
    amount: Double,
    locale: Locale = Locale.getDefault(),
    // Item 13 — privacy mode: the fixed-width mask, no sign.
    masked: Boolean = AmountMask.active,
): String {
    if (masked) return AmountMask.fiat()
    val rounded = roundToCents(amount)
    val absRounded = if (rounded < 0L) -rounded else rounded
    val whole = (absRounded / 100L)
    val cents = (absRounded % 100L).toString().padStart(2, '0')
    val decimal = java.text.DecimalFormatSymbols.getInstance(locale).decimalSeparator
    val sign = if (rounded < 0L) "-" else ""
    return "$sign${BitcoinAmountFormat.groupDigits(whole, locale)}$decimal$cents"
}

/**
 * UX review 2026-10 — true when [locale] writes the currency symbol
 * AFTER the number (it-IT "1.234,56 €"), read from the JDK's own
 * currency pattern so we follow the locale convention rather than a
 * hand-kept table.
 */
internal fun currencySymbolAfter(locale: Locale): Boolean {
    val pattern = (java.text.NumberFormat.getCurrencyInstance(locale) as? java.text.DecimalFormat)
        ?.toPattern()
        ?: return false
    val positive = pattern.substringBefore(';')
    return positive.trimEnd().endsWith('¤') && !positive.trimStart().startsWith('¤')
}

/**
 * Phase 7.1 — format a fiat amount prefixed with its currency symbol.
 * Used by the net-worth hero and breakdown table where the currency
 * context is implicit but the symbol is desired. Delegates to
 * [formatFiatWithCode] (sign before the symbol: "-€12.00").
 */
public fun formatFiatWithCurrency(
    amount: Double,
    currency: io.bhodl.desktop.prices.FiatCurrency,
    masked: Boolean = AmountMask.active,
): String = formatFiatWithCode(amount, currency.code, masked = masked)

/**
 * UX review 2026-10 (B7) — the ONE ISO-4217 → display-prefix mapping.
 *
 * Only currencies with an unambiguous single-glyph symbol get one
 * (€, $, £, ¥); every other code — CHF included — renders as the code
 * plus a space ("CHF 3,200.00"). The old per-screen tables disagreed
 * and `Money` used "₣", which the UI font draws as a bare "F"
 * ("F3,200.00"). Blank / null → no prefix.
 */
public fun fiatPrefix(code: String?): String {
    val c = code?.trim()?.uppercase().orEmpty()
    return when (c) {
        "" -> ""
        "EUR" -> "€"
        "USD" -> "$"
        "GBP" -> "£"
        "JPY" -> "¥"
        else -> "$c "
    }
}

/**
 * "CHF 3,200.00", "-€12.50": sign, then [fiatPrefix], then [formatFiat].
 * The sign follows the ROUNDED value, so -0.004 prints "€0.00", never
 * "-€0.00".
 *
 * UX review 2026-10 — locale-aware like the bitcoin formatter: the
 * separators follow [locale], and in a locale that writes the symbol
 * after the number (Italian, as `NumberFormat.getCurrencyInstance(it_IT)`
 * does) the single-glyph symbols move behind it with a no-break space:
 * "1.234,56 €", "-12,50 €". ISO-code currencies keep the code prefix
 * in every language ("CHF 3.200,00") so the code is never mistaken for
 * a unit of the next column.
 */
public fun formatFiatWithCode(
    amount: Double,
    code: String?,
    locale: Locale = Locale.getDefault(),
    // Item 13 — privacy mode keeps the currency symbol, masks the value.
    masked: Boolean = AmountMask.active,
): String {
    val sign = if (masked || roundToCents(amount) >= 0L) "" else "-"
    val prefix = fiatPrefix(code)
    val number = if (masked) AmountMask.GLYPHS else formatFiat(kotlin.math.abs(amount), locale, masked = false)
    val glyph = prefix.length == 1
    return if (glyph && currencySymbolAfter(locale)) {
        "$sign$number $prefix"
    } else {
        "$sign$prefix$number"
    }
}

/**
 * Code review 2026-10 — amount in whole cents, HALF_UP on the Double's
 * shortest decimal representation (`BigDecimal.valueOf`), so 0.29 is
 * 29 cents (the old `(x * 100).toLong()` truncated it to 28) and 1.005
 * rounds to 101. Non-finite input → 0.
 */
internal fun roundToCents(amount: Double): Long {
    if (!amount.isFinite()) return 0L
    return java.math.BigDecimal.valueOf(amount)
        .setScale(2, java.math.RoundingMode.HALF_UP)
        .movePointRight(2)
        .toLong()
}

/**
 * Phase 7.1 — pure formatter for an on-chain amount in EITHER BTC or
 * sat form, independent of the user's preferred BtcUnit setting.
 * Lets composables that need to render BOTH units (e.g. dual-display
 * hero) bypass the global preference.
 *
 *  - asBtc=true  → "0.01234567 BTC" (always 8 decimals, grouped whole)
 *  - asBtc=false → "1,234,567 sat" (integer, grouped; "₿1,234,567" with BIP177)
 *
 * UX review 2026-10 — delegates to [BitcoinAmountFormat] so the forced
 * unit still gets the locale separators and the BIP177 preference.
 */
public fun formatBtcLike(
    sat: ULong,
    asBtc: Boolean,
    bip177: Boolean = false,
    locale: Locale = Locale.getDefault(),
    // Item 13 — privacy mode ("••••• BTC" / "••••• sat").
    masked: Boolean = AmountMask.active,
): String = BitcoinAmountFormat.format(
    sat = sat,
    unit = if (asBtc) UnitFormat.ALWAYS_BTC else UnitFormat.ALWAYS_SAT,
    bip177 = bip177,
    locale = locale,
    masked = masked,
)

/**
 * Phase 7.1 — display mode for the dual-currency hero. Tapping the
 * hero cycles through the modes in this order: BTC → SAT → FIAT →
 * HIDDEN → BTC. HIDDEN is the privacy mode for use in public
 * (renders the value as bullets); see Phase 7.2 for the broader
 * privacy flow.
 */
public enum class AmountDisplayMode {
    BTC, SAT, FIAT, HIDDEN;

    public fun next(): AmountDisplayMode = when (this) {
        BTC -> SAT
        SAT -> FIAT
        FIAT -> HIDDEN
        HIDDEN -> BTC
    }

    /**
     * Phase 7.2.1 — cycle that respects a "fiat is unavailable" hint
     * by skipping the FIAT mode. Without it the user tap-cycles into
     * a state where the hero renders an em-dash "—" (the fallback for
     * "no price"), which looks like a rendering bug at 56sp. With
     * `fiatAvailable=false` the cycle is BTC → SAT → HIDDEN → BTC.
     */
    public fun next(fiatAvailable: Boolean): AmountDisplayMode {
        val nxt = next()
        return if (!fiatAvailable && nxt == FIAT) nxt.next() else nxt
    }
}
