package io.bhodl.desktop.ui

/**
 * UX review 2026-10 (B11) — render a bitcoin address in monospace
 * blocks of four characters (`tb1q w508 d6qe …`), the Sparrow / Bitcoin
 * Design Guide convention for comparing an address chunk by chunk.
 *
 * The full address is always returned — never truncated at either end:
 * the start (network prefix + first chars) is exactly the part a user
 * checks against the receiver's copy, so cutting it hides the check.
 *
 * Returns `null` for input that is not address-shaped (blank, a
 * `bitcoin:` URI still being typed, anything with spaces), so callers
 * can simply skip the preview.
 */
public fun groupAddressInFours(address: String): String? {
    val trimmed = address.trim()
    if (trimmed.length < MIN_GROUPABLE_LENGTH) return null
    if (trimmed.any { it.isWhitespace() || it == ':' || it == '?' }) return null
    return trimmed.chunked(4).joinToString(" ")
}

/** Shorter strings are still being typed; grouping them is just noise. */
private const val MIN_GROUPABLE_LENGTH: Int = 14
