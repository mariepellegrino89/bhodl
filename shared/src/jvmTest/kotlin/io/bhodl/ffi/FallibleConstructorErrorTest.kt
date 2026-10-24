package io.bhodl.ffi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Regression: a fallible constructor / method that returns an object must
 * surface the typed [WalletException], not a NullPointerException from the
 * generated bindings (see the `fallibleCallNpe` rewrite in
 * shared/build.gradle.kts). The desktop PIN lockout only counts
 * `WalletException.Storage` as a wrong PIN, so an NPE here silently
 * disabled the lockout.
 */
class FallibleConstructorErrorTest {
    @Test
    fun `wrong device pin on createOrLoad throws WalletException Storage`() {
        val dir = Files.createTempDirectory("bhodl-wrong-pin").toFile()
        try {
            RegistryHandle.createOrLoad(dir.absolutePath, "1234567").destroy()

            assertFailsWith<WalletException.Storage> {
                RegistryHandle.createOrLoad(dir.absolutePath, "9999999").destroy()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `openWatch of an unknown wallet id throws WalletException`() {
        val dir = Files.createTempDirectory("bhodl-unknown-watch").toFile()
        val registry = RegistryHandle.createOrLoad(dir.absolutePath, "1234567")
        try {
            assertFailsWith<WalletException> {
                registry.openWatch("00000000-0000-4000-8000-000000000000").destroy()
            }
            assertFailsWith<WalletException> {
                registry.openWatch("not-a-uuid").destroy()
            }
        } finally {
            runCatching { registry.destroy() }
            dir.deleteRecursively()
        }
    }
}
