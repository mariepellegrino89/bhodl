package io.bhodl.shared

/**
 * Public KMP-shared formatter facade.
 *
 * The Compose UI layer (Android, Desktop, iOS) only sees Kotlin idioms here;
 * the Rust call is hidden behind this thin wrapper so:
 *   - we can swap the Rust crate without touching UI code,
 *   - we can stub it in tests on platforms where the cdylib isn't loaded,
 *   - the API uses idiomatic Kotlin types (`ULong`) instead of FFI primitives.
 *
 * @param sat raw satoshi value (must fit in u64; we never accept negatives).
 * @return canonical "<n> sat" string straight from Rust's `Amount::Display`.
 */
public fun formatSat(sat: ULong): String =
    io.bhodl.ffi.formatSat(sat)

/**
 * Phase 3.11 — display unit for on-chain amounts.
 *
 * Two canonical units: integer sat ("1,234,567 sat") and decimal BTC
 * ("0.01234567 BTC"). The unit is a user preference (Settings →
 * Display → Unit) and persists across the session. Mixing units in
 * the same view is a known footgun: the choice is global.
 *
 * Fiat overlay is intentionally NOT in this enum — fiat needs a price
 * source (mempool.space / CoinGecko / manual) and goes through a
 * separate composable wrapper.
 */
public enum class BtcUnit { SAT, BTC }

/**
 * Phase 3.11 — format an amount in the requested unit.
 *
 * SAT:
 *  - Identical to [formatSat]: comma-grouped integer with the " sat"
 *    suffix supplied by Rust's `Amount::Display`.
 *
 * BTC:
 *  - 8-decimal fixed (1 BTC = 100,000,000 sat). Trailing zeros are
 *    kept so the column aligns in tabular views; the suffix is
 *    " BTC".
 *  - The split is at the 100,000,000 boundary — every sat value
 *    produces a deterministic BTC string. No rounding.
 */
public fun formatAmount(sat: ULong, unit: BtcUnit): String = when (unit) {
    BtcUnit.SAT -> formatSat(sat)
    BtcUnit.BTC -> formatBtc(sat)
}

/**
 * Phase 3.11 — format a sat amount as a fixed-precision BTC string.
 * Always 8 decimal places, integer part comma-grouped, suffix " BTC".
 *
 * Example: 1_234_567 sat → "0.01234567 BTC"; 250_000_000 sat → "2.50000000 BTC".
 */
public fun formatBtc(sat: ULong): String {
    val whole = sat / 100_000_000UL
    val frac = sat % 100_000_000UL
    // Comma-group the whole part to match formatSat's convention.
    val wholeStr = whole.toString().reversed().chunked(3).joinToString(",").reversed()
    val fracStr = frac.toString().padStart(8, '0')
    return "$wholeStr.$fracStr BTC"
}
