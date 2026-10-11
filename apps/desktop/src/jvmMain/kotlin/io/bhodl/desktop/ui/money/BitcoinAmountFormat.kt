package io.bhodl.desktop.ui.money

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * UX review 2026-10 — THE formatter for on-chain (BTC-side) amounts.
 *
 * Every screen that shows a bitcoin amount goes through this object
 * (directly, or via the composable wrappers in `ui/AmountFormat.kt` and
 * the [Money] composable, which read the user's preferences from
 * `SettingsStore`). Hand-built `"$x sat"` strings are what produced the
 * "4230118 sat" vs "4,230,118 sat" mix the review flagged.
 *
 * Rules:
 *  - [UnitFormat.ALWAYS_SAT] (the default): integer sats with locale
 *    thousands separators — `4,230,118 sat` (en) / `4.230.118 sat` (it).
 *  - [UnitFormat.ALWAYS_BTC]: always 8 decimals so columns align —
 *    `0.04230118 BTC` (en) / `0,04230118 BTC` (it).
 *  - [UnitFormat.AUTO_THRESHOLD]: sats below 1 BTC
 *    ([AUTO_THRESHOLD_SAT]), BTC at or above.
 *  - `bip177 = true` (opt-in): the sats form uses the BIP177 `₿` prefix
 *    instead of the ` sat` suffix — `₿4,230,118`. BTC-form amounts keep
 *    their `BTC` suffix: under BIP177 `₿` names the base unit, so
 *    `₿0.04230118` would be ambiguous.
 *  - Negative values get a leading `-`; [format] with `signed = true`
 *    also prefixes positives with `+` (deltas, transaction nets).
 *  - Privacy mode (item 13): with `masked = true` — by default the
 *    app-wide [AmountMask.active] flag — the result is the fixed-width
 *    [AmountMask] text in the same unit (`••••• sat`), with no sign.
 *
 * Pure and locale-explicit so tests can pin every case; the composable
 * call sites pass `Locale.getDefault()` (the app language, see Main.kt).
 */
public object BitcoinAmountFormat {

    /** 1 BTC: the Auto mode switches from sats to BTC at this value. */
    public const val AUTO_THRESHOLD_SAT: Long = 100_000_000L

    /** BIP177 bitcoin sign, used as a prefix for base-unit amounts. */
    public const val BITCOIN_SIGN: String = "₿"

    public fun format(
        sat: Long,
        unit: UnitFormat,
        bip177: Boolean = false,
        locale: Locale = Locale.getDefault(),
        signed: Boolean = false,
        masked: Boolean = AmountMask.active,
    ): String {
        if (masked) return AmountMask.bitcoin(asBtc = unit == UnitFormat.ALWAYS_BTC, bip177 = bip177)
        val negative = sat < 0L
        // Long.MIN_VALUE has no positive counterpart; amounts never get
        // near it (21e14 sat max supply), so clamp instead of overflow.
        val abs = if (negative) (if (sat == Long.MIN_VALUE) Long.MAX_VALUE else -sat) else sat
        val asBtc = when (unit) {
            UnitFormat.ALWAYS_SAT -> false
            UnitFormat.ALWAYS_BTC -> true
            UnitFormat.AUTO_THRESHOLD -> abs >= AUTO_THRESHOLD_SAT
        }
        val body = if (asBtc) btcBody(abs, locale) else satBody(abs, bip177, locale)
        val sign = when {
            negative -> "-"
            signed && sat > 0L -> "+"
            else -> ""
        }
        return sign + body
    }

    /** Unsigned convenience for [ULong] balances. */
    public fun format(
        sat: ULong,
        unit: UnitFormat,
        bip177: Boolean = false,
        locale: Locale = Locale.getDefault(),
        masked: Boolean = AmountMask.active,
    ): String = format(
        sat = if (sat > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else sat.toLong(),
        unit = unit,
        bip177 = bip177,
        locale = locale,
        masked = masked,
    )

    /**
     * UX review 2026-10 (rec 2) — the unit ONE table column uses.
     *
     * A column never mixes sats and BTC, so [UnitFormat.AUTO_THRESHOLD]
     * is resolved once for the whole column: BTC when any value in it
     * reaches 1 BTC, sats otherwise. The fixed units pass through.
     * The result is always [UnitFormat.ALWAYS_SAT] or [UnitFormat.ALWAYS_BTC].
     */
    public fun resolveColumnUnit(
        unit: UnitFormat,
        columnSats: Iterable<Long>,
        // Item 13 (security review) — while masked, Auto must not pick BTC
        // for a big column: "••••• BTC" vs "••••• sat" would reveal ≥ 1 BTC.
        // Masked Auto columns, ticks and headers always use sats.
        masked: Boolean = false,
    ): UnitFormat = when (unit) {
        UnitFormat.ALWAYS_SAT, UnitFormat.ALWAYS_BTC -> unit
        UnitFormat.AUTO_THRESHOLD ->
            if (!masked && columnSats.any { it >= AUTO_THRESHOLD_SAT || it <= -AUTO_THRESHOLD_SAT }) {
                UnitFormat.ALWAYS_BTC
            } else {
                UnitFormat.ALWAYS_SAT
            }
    }

    /**
     * Unit label for a column header: `sat`, `BTC`, or `₿` for sats
     * under BIP177. [unit] must already be resolved ([resolveColumnUnit]);
     * an unresolved Auto falls back to `sat`.
     */
    public fun unitLabel(unit: UnitFormat, bip177: Boolean = false): String = when (unit) {
        UnitFormat.ALWAYS_BTC -> "BTC"
        else -> if (bip177) BITCOIN_SIGN else "sat"
    }

    /**
     * The number alone, for table cells whose header carries the unit
     * ([unitLabel]): `4,230,118` in sats, `0.04230118` in BTC (always
     * 8 decimals). [unit] must already be resolved; Auto is treated as
     * sats. Sign rules as in [format].
     */
    public fun formatBare(
        sat: Long,
        unit: UnitFormat,
        locale: Locale = Locale.getDefault(),
        signed: Boolean = false,
        masked: Boolean = AmountMask.active,
    ): String {
        // Item 13 — privacy mode: the header keeps the unit, the cell is masked.
        if (masked) return AmountMask.GLYPHS
        val negative = sat < 0L
        val abs = if (negative) (if (sat == Long.MIN_VALUE) Long.MAX_VALUE else -sat) else sat
        val body = if (unit == UnitFormat.ALWAYS_BTC) {
            val decimal = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            val whole = groupDigits(abs / AUTO_THRESHOLD_SAT, locale)
            "$whole$decimal${(abs % AUTO_THRESHOLD_SAT).toString().padStart(8, '0')}"
        } else {
            groupDigits(abs, locale)
        }
        val sign = when {
            negative -> "-"
            signed && sat > 0L -> "+"
            else -> ""
        }
        return sign + body
    }

    /**
     * Short form for chart labels and axis ticks, where a full
     * `4,230,118 sat` does not fit: `4.2M sat`, `450k sat`, `12,345 sat`
     * (`₿4.2M` with BIP177); BTC as `1.25 BTC` (≥ 1 BTC), `0.0423 BTC`
     * (≥ 0.01 BTC) or `0.000123 BTC` (to the sat, trailing zeros dropped). Lossy by design — never use it in
     * tables, forms or anything the user copies. Same unit resolution
     * as [format] (pass a column-resolved unit for an axis).
     */
    public fun formatCompact(
        sat: Long,
        unit: UnitFormat,
        bip177: Boolean = false,
        locale: Locale = Locale.getDefault(),
        masked: Boolean = AmountMask.active,
    ): String {
        // Item 13 — privacy mode masks chart labels and axis ticks too.
        if (masked) return AmountMask.bitcoin(asBtc = unit == UnitFormat.ALWAYS_BTC, bip177 = bip177)
        val negative = sat < 0L
        val abs = if (negative) (if (sat == Long.MIN_VALUE) Long.MAX_VALUE else -sat) else sat
        val asBtc = when (unit) {
            UnitFormat.ALWAYS_SAT -> false
            UnitFormat.ALWAYS_BTC -> true
            UnitFormat.AUTO_THRESHOLD -> abs >= AUTO_THRESHOLD_SAT
        }
        val body = if (asBtc) {
            val decimals = when {
                abs >= AUTO_THRESHOLD_SAT -> 2
                abs >= AUTO_THRESHOLD_SAT / 100 -> 4
                else -> 8
            }
            val text = decimal(abs / AUTO_THRESHOLD_SAT.toDouble(), decimals, locale)
            // Below 0.01 BTC: exact to the sat, trailing zeros dropped (`0.00000001`).
            val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            "${if (decimals == 8) text.trimEnd('0').trimEnd(sep) else text} BTC"
        } else {
            val digits = when {
                abs >= 999_500L -> "${decimal(abs / 1_000_000.0, 1, locale)}M"
                abs >= 100_000L -> "${abs / 1_000L + (if (abs % 1_000L >= 500L) 1 else 0)}k"
                else -> groupDigits(abs, locale)
            }
            if (bip177) "$BITCOIN_SIGN$digits" else "$digits sat"
        }
        return (if (negative) "-" else "") + body
    }

    private fun decimal(value: Double, decimals: Int, locale: Locale): String {
        val plain = java.math.BigDecimal.valueOf(value)
            .setScale(decimals, java.math.RoundingMode.HALF_UP)
            .toPlainString()
        val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val dot = plain.indexOf('.')
        val whole = if (dot >= 0) plain.substring(0, dot) else plain
        val frac = if (dot >= 0) plain.substring(dot + 1) else ""
        val groupedWhole = groupDigits(whole.toLong(), locale)
        return if (frac.isEmpty()) groupedWhole else "$groupedWhole$sep$frac"
    }

    /** Integer with the locale's grouping separator: `4,230,118` / `4.230.118`. */
    public fun groupDigits(value: Long, locale: Locale = Locale.getDefault()): String {
        val grouping = DecimalFormatSymbols.getInstance(locale).groupingSeparator
        val digits = value.toString()
        val sb = StringBuilder(digits.length + digits.length / 3)
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(grouping)
            sb.append(c)
        }
        return sb.toString()
    }

    private fun satBody(abs: Long, bip177: Boolean, locale: Locale): String {
        val digits = groupDigits(abs, locale)
        return if (bip177) "$BITCOIN_SIGN$digits" else "$digits sat"
    }

    private fun btcBody(abs: Long, locale: Locale): String =
        "${formatBare(abs, UnitFormat.ALWAYS_BTC, locale, masked = false)} BTC"
}
