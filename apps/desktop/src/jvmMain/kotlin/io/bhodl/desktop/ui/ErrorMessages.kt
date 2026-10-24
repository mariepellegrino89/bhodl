package io.bhodl.desktop.ui

import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.WalletException
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.err_kind_bip32
import io.bhodl.shared.resources.err_kind_derivation_path
import io.bhodl.shared.resources.err_kind_esplora_sync
import io.bhodl.shared.resources.err_kind_invalid_address
import io.bhodl.shared.resources.err_kind_invalid_argument
import io.bhodl.shared.resources.err_kind_invalid_salt
import io.bhodl.shared.resources.err_kind_io
import io.bhodl.shared.resources.err_kind_key_derivation
import io.bhodl.shared.resources.err_kind_rate_all_failed
import io.bhodl.shared.resources.err_kind_rate_currency
import io.bhodl.shared.resources.err_kind_rate_network
import io.bhodl.shared.resources.err_kind_storage
import io.bhodl.shared.resources.err_kind_wallet_create
import io.bhodl.shared.resources.err_unexpected

/**
 * Security review — sanitised, user-facing error messages.
 *
 * Raw exception messages coming up from the FFI (and from the JVM
 * itself) routinely embed absolute filesystem paths (`C:\Users\alice\…`,
 * `/home/alice/.bhodl/…`) and can be arbitrarily long. Surfacing them
 * verbatim in the UI leaks the local username / directory layout into
 * screenshots and support requests, and a multi-KB SQL error can blow
 * up a Compose layout.
 *
 * [userMessage] is the single funnel every screen should use in place
 * of the old hand-rolled
 * `(e as? WalletException)?.message ?: e.message ?: "…"` pattern:
 * same precedence, plus path redaction and a length cap.
 */

/** Windows drive paths, e.g. `C:\Users\alice\.bhodl\wallet.sqlite`. */
private val WINDOWS_PATH_REGEX = Regex("""[A-Za-z]:\\[^\s]+""")

/**
 * Unix absolute paths with at least two segments, e.g.
 * `/home/alice/.bhodl`. The negative lookbehind keeps URL paths
 * intact: in `https://mempool.space/signet/api` every `/` is preceded
 * by `:`, `/` or a word character, so nothing matches.
 */
private val UNIX_PATH_REGEX = Regex("""(?<![\w:/])/[^\s:]+/[^\s]+""")

/** Cap so a multi-KB backend error cannot blow up a Compose layout. */
private const val MAX_USER_MESSAGE_LENGTH: Int = 200

/**
 * Best human-readable message for this throwable, with absolute
 * filesystem paths redacted to `<path>` and the result truncated to
 * [MAX_USER_MESSAGE_LENGTH] characters. [fallback] is returned when
 * the throwable carries no message at all.
 */
public fun Throwable.userMessage(fallback: String = localizedText(Res.string.err_unexpected)): String {
    val we = this as? WalletException ?: return sanitizeErrorDetail(message, fallback)
    // FFI errors: a localized kind ("Storage error") plus the technical
    // detail, instead of the generated "detail=…" message.
    val (kind, detail) = we.kindAndDetail()
    val shownDetail = sanitizeErrorDetail(detail, fallback = "")
    return sanitizeErrorDetail(if (shownDetail.isEmpty()) kind else "$kind: $shownDetail", fallback)
}

private fun WalletException.kindAndDetail(): Pair<String, String?> = when (this) {
    is WalletException.Bip32 -> localizedText(Res.string.err_kind_bip32) to detail
    is WalletException.InvalidAddress -> localizedText(Res.string.err_kind_invalid_address) to detail
    is WalletException.InvalidArgument -> localizedText(Res.string.err_kind_invalid_argument) to detail
    is WalletException.EsploraSync -> localizedText(Res.string.err_kind_esplora_sync) to detail
    is WalletException.Storage -> localizedText(Res.string.err_kind_storage) to detail
    is WalletException.WalletCreate -> localizedText(Res.string.err_kind_wallet_create) to detail
    is WalletException.DerivationPath -> localizedText(Res.string.err_kind_derivation_path) to detail
    is WalletException.KeyDerivation -> localizedText(Res.string.err_kind_key_derivation) to detail
    is WalletException.Io -> localizedText(Res.string.err_kind_io) to detail
    is WalletException.InvalidSalt ->
        localizedText(Res.string.err_kind_invalid_salt, expected.toInt(), actual.toInt()) to null
    is WalletException.RateCurrencyNotSupported ->
        localizedText(Res.string.err_kind_rate_currency, currency) to null
    is WalletException.RateNetworkUnavailable -> localizedText(Res.string.err_kind_rate_network) to null
    is WalletException.RateAllSourcesFailed -> localizedText(Res.string.err_kind_rate_all_failed) to detail
}

/**
 * Sanitise a raw error string for UI / log surfaces: redact absolute
 * filesystem paths and truncate. Shared by [userMessage] and the
 * non-Throwable call sites (e.g. stderr logging in the price clients).
 */
public fun sanitizeErrorDetail(
    raw: String?,
    fallback: String = localizedText(Res.string.err_unexpected),
): String {
    val msg = raw?.takeIf { it.isNotBlank() } ?: return fallback
    val redacted = msg
        .replace(WINDOWS_PATH_REGEX, "<path>")
        .replace(UNIX_PATH_REGEX, "<path>")
    return if (redacted.length <= MAX_USER_MESSAGE_LENGTH) {
        redacted
    } else {
        redacted.take(MAX_USER_MESSAGE_LENGTH) + "…"
    }
}
