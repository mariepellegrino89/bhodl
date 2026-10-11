package io.bhodl.desktop.data

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Thrown when an input exceeds the caller's size cap. */
public class SizeLimitExceededException(message: String) : IOException(message)

/**
 * Read at most [maxBytes] from [input]. Reads `maxBytes + 1` so an
 * over-sized input is detected without buffering it whole, and throws
 * [SizeLimitExceededException] in that case. Does not close [input].
 */
public fun readBytesCapped(input: InputStream, maxBytes: Int): ByteArray {
    require(maxBytes >= 0) { "maxBytes must be >= 0" }
    val bytes = input.readNBytes(maxBytes + 1)
    if (bytes.size > maxBytes) {
        throw SizeLimitExceededException("input exceeds the ${humanBytes(maxBytes.toLong())} limit")
    }
    return bytes
}

/**
 * Read a user-chosen file as UTF-8 text, refusing files larger than
 * [maxBytes] (checked on the declared length first, then enforced while
 * reading in case the file grows / lies). The message of the thrown
 * [SizeLimitExceededException] is suitable for the UI.
 */
public fun readTextCapped(file: File, maxBytes: Int): String {
    val declared = file.length()
    if (declared > maxBytes) {
        throw SizeLimitExceededException(
            "file is too large (${humanBytes(declared)}; limit ${humanBytes(maxBytes.toLong())})",
        )
    }
    return file.inputStream().use { readBytesCapped(it, maxBytes) }.toString(Charsets.UTF_8)
}

private fun humanBytes(n: Long): String = when {
    n >= 1024L * 1024L -> "%.1f MiB".format(java.util.Locale.ROOT, n / (1024.0 * 1024.0))
    n >= 1024L -> "%.1f KiB".format(java.util.Locale.ROOT, n / 1024.0)
    else -> "$n B"
}
