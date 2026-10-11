package io.bhodl.desktop.wizard

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Parser for BIP-21 Bitcoin payment URIs:
 *
 *     bitcoin:<address>[?amount=X[&label=...][&message=...][&pj=...]]
 *
 * Only the bits the SendScreen consumes are surfaced — `address`, the
 * optional `amount` (BIP-21 quotes amounts in BTC; we convert to sat)
 * and the BIP-78 `pj` endpoint.
 *
 * Pure Kotlin, no FFI / no crypto: address validity is decided down the
 * line by `WalletHandle.buildSendTx`, which is the only place that knows
 * whether the network matches.
 *
 * # Canonical rules (security)
 *
 * These rules are mirrored 1:1 by the Rust `PayjoinUri::parse`
 * (`crates/bhodl-wallet-onchain/src/payjoin.rs`) so the amount the UI
 * shows can never differ from the amount the signer pays:
 * - scheme `bitcoin:` is ASCII-case-insensitive;
 * - query keys are percent-decoded, then compared ASCII-case-
 *   insensitively; a key seen twice (`amount=1&AMOUNT=0.00001`)
 *   invalidates the URI — no "last wins";
 * - any `req-*` key invalidates the URI (we support none);
 * - malformed percent-encoding or non-UTF-8 bytes invalidate the URI;
 * - `amount` is an exact decimal string: ASCII digits with at most one
 *   `.` and at most 8 fractional digits, `0 < amount <= 21M BTC`; no
 *   sign, no exponent, no rounding.
 *
 * An invalid `bitcoin:` URI is reported through [Bip21Parsed.error]
 * with `amountSat` / `payjoinEndpoint` cleared; the caller must not
 * prefill a payment from it.
 */
public data class Bip21Parsed(
    val address: String,
    val amountSat: Long?,
    /**
     * BIP-78 PayJoin endpoint URL when the URI carries `pj=`. Only
     * `https://` endpoints (or `http://` to a `.onion`) are surfaced,
     * matching what the Rust sender accepts. Stays `null` for plain
     * BIP-21.
     */
    val payjoinEndpoint: String? = null,
    /** Non-null when the URI violates the canonical rules above. */
    val error: String? = null,
)

/**
 * Parse a BIP-21 URI. Returns `null` if the input is not a BIP-21 URI
 * (e.g. a bare bech32 address, an empty string, an HTTPS link).
 *
 * Tolerates empty `&` separators and ignores unknown (non `req-`)
 * keys; see the class docs for the rules that invalidate a URI.
 */
public fun parseBip21(input: String): Bip21Parsed? {
    val trimmed = input.trim()
    if (trimmed.length < SCHEME.length || asciiLower(trimmed.substring(0, SCHEME.length)) != SCHEME) {
        return null
    }
    val rest = trimmed.substring(SCHEME.length)
    val queryStart = rest.indexOf('?')
    val address = if (queryStart < 0) rest else rest.substring(0, queryStart)
    if (address.isBlank()) return null
    val query = if (queryStart < 0) "" else rest.substring(queryStart + 1)

    val params = parseBip21Params(query).getOrElse { e ->
        return Bip21Parsed(address = address, amountSat = null, error = e.message)
    }
    val amountSat = params["amount"]?.let { raw ->
        btcStringToSat(raw) ?: return Bip21Parsed(
            address = address,
            amountSat = null,
            error = "amount \"$raw\" is not a valid BTC amount",
        )
    }
    val payjoinEndpoint = params["pj"]?.takeIf(::isAcceptablePayjoinEndpoint)

    return Bip21Parsed(
        address = address,
        amountSat = amountSat,
        payjoinEndpoint = payjoinEndpoint,
    )
}

private const val SCHEME = "bitcoin:"

private class Bip21Exception(message: String) : IllegalArgumentException(message)

/** Lowercase ASCII letters only — same as Rust `to_ascii_lowercase`. */
private fun asciiLower(s: String): String =
    buildString(s.length) { s.forEach { c -> append(if (c in 'A'..'Z') c + 32 else c) } }

private fun parseBip21Params(query: String): Result<Map<String, String>> = runCatching {
    val out = LinkedHashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val rawKey = if (eq < 0) pair else pair.substring(0, eq)
        val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
        val key = asciiLower(
            strictPercentDecode(rawKey)
                ?: throw Bip21Exception("malformed percent-encoding in key \"$rawKey\""),
        )
        if (key.isEmpty()) throw Bip21Exception("empty parameter name")
        if (key in out) throw Bip21Exception("duplicate parameter \"$key\"")
        if (key.startsWith("req-")) throw Bip21Exception("unsupported required parameter \"$key\"")
        out[key] = strictPercentDecode(rawValue)
            ?: throw Bip21Exception("malformed percent-encoding in \"$key\"")
    }
    out
}

/**
 * Byte-level `%xx` / `+`-as-space decoder, identical to the Rust
 * `decode_percent`: a malformed escape or a non-UTF-8 result returns
 * `null` (Java's `URLDecoder` would throw on the former and silently
 * substitute U+FFFD on the latter).
 */
private fun strictPercentDecode(s: String): String? {
    val bytes = s.toByteArray(StandardCharsets.UTF_8)
    val out = ByteArrayOutputStream(bytes.size)
    var i = 0
    while (i < bytes.size) {
        when (val b = bytes[i].toInt()) {
            '+'.code -> { out.write(' '.code); i += 1 }
            '%'.code -> {
                if (i + 2 >= bytes.size) return null
                val h = hexDigit(bytes[i + 1].toInt()) ?: return null
                val l = hexDigit(bytes[i + 2].toInt()) ?: return null
                out.write((h shl 4) or l)
                i += 3
            }
            else -> { out.write(b); i += 1 }
        }
    }
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(out.toByteArray()))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun hexDigit(b: Int): Int? = when (b) {
    in '0'.code..'9'.code -> b - '0'.code
    in 'a'.code..'f'.code -> 10 + b - 'a'.code
    in 'A'.code..'F'.code -> 10 + b - 'A'.code
    else -> null
}

/**
 * `https://` endpoints are accepted; `http://` only for `.onion` hosts
 * (BIP-78 — the Tor circuit provides the encryption). The Rust sender
 * applies the same rule plus the SSRF guard.
 */
private fun isAcceptablePayjoinEndpoint(url: String): Boolean {
    val lower = asciiLower(url)
    if (lower.startsWith("https://")) return true
    if (!lower.startsWith("http://")) return false
    val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
    return asciiLower(host).removeSuffix(".").endsWith(".onion")
}

private val AMOUNT_SHAPE = Regex("^[0-9]*(\\.[0-9]*)?$")

/**
 * Convert a BIP-21 BTC amount string (e.g. `"0.001"`, `"21"`, `".5"`)
 * to satoshis, returning `null` for anything outside the canonical
 * grammar: digits with at most one `.`, at most 8 fractional digits
 * (BIP-21 forbids sub-satoshi precision — refuse rather than round),
 * `0 < amount <= 21M BTC`. No sign, no exponent: `BigDecimal` alone
 * would accept `1E2` / `+1`, so the shape is checked first.
 */
private fun btcStringToSat(raw: String): Long? {
    if (raw.isEmpty() || raw == "." || !AMOUNT_SHAPE.matches(raw)) return null
    val frac = raw.substringAfter('.', missingDelimiterValue = "")
    if (frac.length > 8) return null
    val decimal = runCatching { BigDecimal(raw) }.getOrNull() ?: return null
    val sat = decimal.movePointRight(8)
    if (sat.signum() <= 0 || sat > MAX_SAT_DECIMAL) return null
    return runCatching { sat.longValueExact() }.getOrNull()
}

// 21,000,000 BTC = 2.1e15 sat — the absolute supply cap. Any URI quoting
// more than this is malformed regardless of network.
private val MAX_SAT_DECIMAL: BigDecimal = BigDecimal(2_100_000_000_000_000L)
