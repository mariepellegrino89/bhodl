package io.bhodl.desktop.wizard

import java.security.SecureRandom

/**
 * Security audit 2026-10 (M10) — wipeable, in-memory holder for the
 * seed material that travels through the setup wizard
 * (GenerateMnemonic / RestoreMnemonic / RestoreBackup → ConfirmMnemonic
 * → PinSetup).
 *
 * The mnemonic and BIP-39 passphrase used to be navigation ARGUMENTS
 * (`Route.ConfirmMnemonic(mnemonic)`, `Route.PinSetup(mnemonic,
 * passphrase)`), i.e. serialised into the NavController back stack,
 * where they survived after the wizard finished whenever the
 * `popUpTo(Route.Welcome)` did not match (setup started from the
 * Tracker). Routes now carry only an opaque random [id]; the secrets
 * live here as [CharArray]s that are zero-filled by [wipe] / [wipeAll]
 * as soon as the wizard completes or the app locks.
 *
 * Residual exposure: the screens still need `String`s (Compose text +
 * FFI parameters) — those are short-lived and GC-collectable; the
 * long-lived copy is the one we can and do scrub.
 */
public object SetupSecrets {

    private class Entry(val mnemonic: CharArray, val passphrase: CharArray)

    private val entries: MutableMap<String, Entry> = mutableMapOf()
    private val random = SecureRandom()

    /** Park [mnemonic] + [passphrase]; returns the opaque id to route with. */
    @Synchronized
    public fun put(mnemonic: String, passphrase: String = ""): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        entries[id] = Entry(mnemonic.toCharArray(), passphrase.toCharArray())
        return id
    }

    /** Mnemonic for [id], or `null` if unknown / already wiped. */
    @Synchronized
    public fun mnemonic(id: String): String? = entries[id]?.let { String(it.mnemonic) }

    /** Passphrase for [id], or `""` if unknown / already wiped. */
    @Synchronized
    public fun passphrase(id: String): String = entries[id]?.let { String(it.passphrase) } ?: ""

    /** Zero-fill and drop the entry for [id]. Idempotent. */
    @Synchronized
    public fun wipe(id: String) {
        entries.remove(id)?.let { e ->
            e.mnemonic.fill(Char.MIN_VALUE)
            e.passphrase.fill(Char.MIN_VALUE)
        }
    }

    /** Zero-fill and drop every entry (wizard done, lock, wipe). */
    @Synchronized
    public fun wipeAll() {
        entries.values.forEach { e ->
            e.mnemonic.fill(Char.MIN_VALUE)
            e.passphrase.fill(Char.MIN_VALUE)
        }
        entries.clear()
    }

    /** Number of parked entries (tests). */
    @Synchronized
    public fun size(): Int = entries.size
}
