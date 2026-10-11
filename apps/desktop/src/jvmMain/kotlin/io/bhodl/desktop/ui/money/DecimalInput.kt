package io.bhodl.desktop.ui.money

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Locale-aware parser for user-typed decimal amounts (prices, quantities,
 * fees, fiat values) in the tracker dialogs.
 *
 * The previous pattern `s.replace(",", ".").toDoubleOrNull()` silently
 * turned an Italian "350.000" (three hundred fifty thousand) into 350.0
 * and rejected "1.234,56" outright. This parser accepts both `.` and `,`
 * but only resolves them when the meaning is unambiguous:
 *
 *  - Both separators present → the LAST one is the decimal separator,
 *    the other is grouping (`1.234,56` and `1,234.56` both → 1234.56).
 *  - The same separator repeated → grouping (`1.234.567` → 1234567).
 *  - A single separator followed by exactly 3 digits, with 1–3 non-zero-
 *    led digits before it (`350.000`, `1,500`) → ambiguous: resolved by
 *    [locale]'s [DecimalFormatSymbols] (grouping in it_IT for `.`,
 *    decimal in en_US).
 *  - Any other single separator → decimal (`12,5`, `0.500`, `1234.567`).
 *
 * Grouping must be well-formed (groups of exactly 3 digits after the
 * first one of 1–3 digits); otherwise the input is rejected. Spaces,
 * NBSP and apostrophes are accepted as grouping and stripped. Exponents,
 * letters, `NaN` / `Infinity` and multiple decimal separators are
 * rejected. The result is always finite.
 *
 * @return the parsed value, or null when the input is blank or invalid.
 */
public fun parseDecimalInput(raw: String, locale: Locale = Locale.getDefault()): Double? {
    var s = raw.trim()
        .replace(" ", "")
        .replace(" ", "")
        .replace(" ", "")
        .replace("'", "")
        .replace("’", "")
    if (s.isEmpty()) return null
    var negative = false
    if (s[0] == '-' || s[0] == '+') {
        negative = s[0] == '-'
        s = s.substring(1)
    }
    if (s.isEmpty() || s.any { !(it.isAsciiDigit() || it == '.' || it == ',') }) return null
    if (s.none { it.isAsciiDigit() }) return null

    val lastDot = s.lastIndexOf('.')
    val lastComma = s.lastIndexOf(',')
    val dots = s.count { it == '.' }
    val commas = s.count { it == ',' }

    val normalized: String = when {
        dots == 0 && commas == 0 -> s
        dots > 0 && commas > 0 -> {
            val decimalSep = if (lastDot > lastComma) '.' else ','
            val groupSep = if (decimalSep == '.') ',' else '.'
            val decimalCount = if (decimalSep == '.') dots else commas
            if (decimalCount != 1) return null
            val decIdx = s.lastIndexOf(decimalSep)
            val intPart = s.substring(0, decIdx)
            val fracPart = s.substring(decIdx + 1)
            if (fracPart.contains(groupSep)) return null
            val intDigits = ungroup(intPart, groupSep) ?: return null
            "$intDigits.$fracPart"
        }
        else -> {
            val sep = if (dots > 0) '.' else ','
            val count = if (dots > 0) dots else commas
            if (count > 1) {
                ungroup(s, sep) ?: return null
            } else {
                val idx = s.indexOf(sep)
                val intPart = s.substring(0, idx)
                val fracPart = s.substring(idx + 1)
                val couldBeGrouping = fracPart.length == 3 &&
                    intPart.length in 1..3 &&
                    intPart[0] != '0'
                val treatAsGrouping = couldBeGrouping && isGroupingIn(sep, locale)
                if (treatAsGrouping) intPart + fracPart else "$intPart.$fracPart"
            }
        }
    }
    if (normalized == "." || normalized.isEmpty()) return null
    val value = runCatching { BigDecimal(normalized.let { if (it.startsWith(".")) "0$it" else it }) }
        .getOrNull()
        ?.toDouble()
        ?: return null
    if (!value.isFinite()) return null
    return if (negative) -value else value
}

/**
 * Validate and strip grouping separators from an integer part.
 * The first group has 1–3 digits, every following group exactly 3.
 * Returns the bare digits or null when malformed.
 */
private fun ungroup(intPart: String, sep: Char): String? {
    if (intPart.isEmpty()) return null
    val groups = intPart.split(sep)
    if (groups.size == 1) return intPart.takeIf { it.isNotEmpty() }
    if (groups[0].length !in 1..3) return null
    if (groups.drop(1).any { it.length != 3 }) return null
    return groups.joinToString("")
}

/**
 * True when [sep] is the grouping separator of [locale]. A separator
 * that is neither the locale's grouping nor its decimal char is read as
 * decimal — the conservative interpretation (smaller number).
 */
private fun isGroupingIn(sep: Char, locale: Locale): Boolean {
    val symbols = DecimalFormatSymbols.getInstance(locale)
    if (symbols.decimalSeparator == sep) return false
    return symbols.groupingSeparator == sep
}

private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

/**
 * Character filter for decimal text fields: digits and both separators
 * (the parser above decides what they mean).
 */
public fun filterDecimalChars(input: String): String =
    input.filter { it.isAsciiDigit() || it == '.' || it == ',' }

/**
 * Format a stored value for pre-filling an editable decimal field so that
 * [parseDecimalInput] reads it back to the same number in [locale]:
 * plain digits (no grouping, no exponent — `Double.toString` would give
 * `1.0E7`) with the locale's decimal separator. Without this, a ROOT-
 * formatted `350.125` would be re-read as 350125 in it_IT.
 *
 * @param maxFractionDigits rounding applied before formatting (HALF_UP).
 */
public fun formatDecimalInput(
    value: Double,
    maxFractionDigits: Int = 8,
    locale: Locale = Locale.getDefault(),
): String {
    if (!value.isFinite()) return ""
    val plain = BigDecimal.valueOf(value)
        .setScale(maxFractionDigits, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
    val sep = DecimalFormatSymbols.getInstance(locale).decimalSeparator
    return if (sep == '.') plain else plain.replace('.', sep)
}
