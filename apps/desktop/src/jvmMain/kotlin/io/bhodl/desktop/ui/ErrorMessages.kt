package io.bhodl.desktop.ui

import io.bhodl.ffi.WalletException

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
public fun Throwable.userMessage(fallback: String = "Unexpected error."): String {
    val raw = (this as? WalletException)?.message ?: message
    return sanitizeErrorDetail(raw, fallback)
}

/**
 * Sanitise a raw error string for UI / log surfaces: redact absolute
 * filesystem paths and truncate. Shared by [userMessage] and the
 * non-Throwable call sites (e.g. stderr logging in the price clients).
 */
public fun sanitizeErrorDetail(
    raw: String?,
    fallback: String = "Unexpected error.",
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
