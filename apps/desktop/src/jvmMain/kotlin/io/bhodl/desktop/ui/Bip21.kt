package io.bhodl.desktop.ui

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * BIP-21 payment request URI builder.
 *
 * Format:
 * ```
 *   bitcoin:<address>[?amount=<btc>&label=<label>&message=<message>]
 * ```
 *
 * - `amount` is in BTC (NOT satoshis) per the BIP, with no scientific
 *   notation and no trailing zeros (`100_000_000 sat` → `"1"`,
 *   `12_345_678 sat` → `"0.12345678"`, `1 sat` → `"0.00000001"`).
 * - `label` and `message` are URL-percent-encoded as UTF-8. We
 *   substitute `+` (URLEncoder's default for space) with `%20` so
 *   the output matches the BIP's RFC-3986 expectation — some
 *   wallet QR scanners reject the `+` form.
 * - Empty / blank optional fields are omitted from the query
 *   string entirely; an address-only call returns the bare
 *   `bitcoin:<address>` so the resulting QR stays compact.
 *
 * The builder does NOT validate the address — callers feed it the
 * address surfaced by the wallet, which has already passed BDK's
 * checks.
 */
public object Bip21 {

    public fun build(
        address: String,
        amountSat: Long? = null,
        label: String? = null,
        message: String? = null,
    ): String {
        val params = buildList {
            amountSat?.takeIf { it > 0 }?.let { add("amount" to formatBtc(it)) }
            label?.takeIf { it.isNotBlank() }?.let { add("label" to it) }
            message?.takeIf { it.isNotBlank() }?.let { add("message" to it) }
        }
        if (params.isEmpty()) return "bitcoin:$address"
        val query = params.joinToString("&") { (k, v) -> "$k=${encode(v)}" }
        return "bitcoin:$address?$query"
    }

    private fun encode(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")

    /**
     * Format `sat` as a BTC string with up to 8 decimal places, no
     * trailing zeros and no scientific notation. Pure integer maths
     * — no `Double` round-trip — so the conversion is exact.
     */
    internal fun formatBtc(sat: Long): String {
        require(sat >= 0) { "sat must be non-negative" }
        val whole = sat / 100_000_000L
        val frac = sat % 100_000_000L
        if (frac == 0L) return whole.toString()
        val fracStr = frac.toString().padStart(8, '0').trimEnd('0')
        return "$whole.$fracStr"
    }
}
