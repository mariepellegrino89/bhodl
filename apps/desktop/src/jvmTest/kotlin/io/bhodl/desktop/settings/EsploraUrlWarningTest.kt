package io.bhodl.desktop.settings

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [SettingsStore.esploraUrlWarning].
 *
 * Verifies the three cases:
 *  - http with a remote host  -> warning string returned
 *  - http with a local host   -> null (no warning)
 *  - https (any host)         -> null (no warning)
 */
class EsploraUrlWarningTest {

    @BeforeTest
    fun reset() { SettingsStore.reset() }

    @AfterTest
    fun cleanup() { SettingsStore.reset() }

    // ---- http remote: must warn -----------------------------------------

    @Test
    fun `http remote host returns a warning`() {
        val result = SettingsStore.esploraUrlWarning("http://mempool.space/api")
        assertNotNull(result, "http:// with a remote host must produce a warning")
    }

    @Test
    fun `http remote host with port returns a warning`() {
        val result = SettingsStore.esploraUrlWarning("http://192.168.1.100:3000/api")
        assertNotNull(result, "http:// with a LAN IP must produce a warning")
    }

    @Test
    fun `http remote host without path returns a warning`() {
        val result = SettingsStore.esploraUrlWarning("http://mynode.example.com")
        assertNotNull(result)
    }

    // ---- http local: must NOT warn --------------------------------------

    @Test
    fun `http localhost returns null`() {
        assertNull(SettingsStore.esploraUrlWarning("http://localhost/api"),
            "http://localhost is a legitimate local configuration")
    }

    @Test
    fun `http 127 0 0 1 returns null`() {
        assertNull(SettingsStore.esploraUrlWarning("http://127.0.0.1:3002/api"))
    }

    @Test
    fun `http IPv6 loopback returns null`() {
        assertNull(SettingsStore.esploraUrlWarning("http://[::1]:3002/api"),
            "http://[::1] is a legitimate local configuration")
    }

    // ---- https: must NOT warn -------------------------------------------

    @Test
    fun `https remote host returns null`() {
        assertNull(SettingsStore.esploraUrlWarning("https://mempool.space/api"))
    }

    @Test
    fun `https localhost returns null`() {
        assertNull(SettingsStore.esploraUrlWarning("https://localhost/api"))
    }

    // ---- edge cases -----------------------------------------------------

    @Test
    fun `blank url returns null`() {
        assertNull(SettingsStore.esploraUrlWarning("   "))
    }

    @Test
    fun `garbage url returns null`() {
        // Not parseable as a URI, so no warning (isValidEsploraUrl would also
        // reject it; esploraUrlWarning is purely additive).
        assertNull(SettingsStore.esploraUrlWarning("not a url"))
    }

    @Test
    fun `ftp scheme returns null`() {
        // Only http triggers the warning; other schemes are handled by isValidEsploraUrl.
        assertNull(SettingsStore.esploraUrlWarning("ftp://example.com/"))
    }
}
