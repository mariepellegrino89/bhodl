package io.bhodl.desktop.wizard

import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Lazy-loaded English BIP-39 wordlist (2048 entries) read from the
 * Compose resource at `files/bip39-english.txt`.
 *
 * The file is shipped as a resource (not as a Kotlin literal) so the
 * source tree stays small and we can drop in extra language lists
 * later without rewiring the call sites.
 *
 * Loading is suspending because [`Res.readBytes`] is suspending on
 * Compose Multiplatform. The first caller pays the I/O once; every
 * subsequent caller gets the cached list back synchronously via
 * [`cached`].
 */
@OptIn(ExperimentalResourceApi::class)
public object Bip39Wordlist {

    @Volatile
    private var loaded: List<String>? = null

    /**
     * Block-load the wordlist. Idempotent — a second call returns
     * the cached list. Designed to be called from a Compose
     * `LaunchedEffect`.
     */
    public suspend fun load(): List<String> {
        loaded?.let { return it }
        val bytes = Res.readBytes("files/bip39-english.txt")
        val list = bytes.decodeToString().lines().mapNotNull { line ->
            line.trim().takeIf { it.isNotEmpty() }
        }
        loaded = list
        return list
    }

    /** Cached wordlist, or `null` if [`load`] has not finished yet. */
    public fun cached(): List<String>? = loaded
}
