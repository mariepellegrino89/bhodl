package io.bhodl.desktop.settings

import io.bhodl.ffi.Network
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests for [`EsploraEndpoint`]'s default vs override resolution. */
class EsploraEndpointTest {

    @BeforeTest
    fun reset() {
        SettingsStore.reset()
    }

    @AfterTest
    fun cleanup() {
        SettingsStore.reset()
    }

    @Test
    fun `current returns the mempool_space signet default when no override is set`() {
        assertEquals(EsploraEndpoint.DEFAULT_SIGNET, EsploraEndpoint.current())
        assertEquals("https://mempool.space/signet/api", EsploraEndpoint.current())
    }

    @Test
    fun `current returns the override when SettingsStore has one`() {
        SettingsStore.setEsploraUrl("https://my.onion/api")
        assertEquals("https://my.onion/api", EsploraEndpoint.current())
    }

    @Test
    fun `clearing the override falls back to the default`() {
        SettingsStore.setEsploraUrl("https://my.onion/api")
        SettingsStore.setEsploraUrl(null)
        assertEquals(EsploraEndpoint.DEFAULT_SIGNET, EsploraEndpoint.current())
    }

    @Test
    fun `blank override is treated as default (SettingsStore strips blank)`() {
        SettingsStore.setEsploraUrl("   ")
        assertEquals(EsploraEndpoint.DEFAULT_SIGNET, EsploraEndpoint.current())
    }

    @Test
    fun `currentProxy is null when Tor is disabled`() {
        assert(SettingsStore.torEnabled.value == false) {
            "Tor must default to disabled for this test"
        }
        assertEquals(null, EsploraEndpoint.currentProxy())
    }

    @Test
    fun `currentProxy returns the configured Tor proxy when Tor is on`() {
        SettingsStore.setTorEnabled(true)
        assertEquals(
            SettingsStore.DEFAULT_TOR_PROXY_URL,
            EsploraEndpoint.currentProxy(),
        )
    }

    @Test
    fun `currentProxy returns the user-customised proxy URL`() {
        SettingsStore.setTorEnabled(true)
        SettingsStore.setTorProxyUrl("socks5h://my.tor:9150")
        assertEquals("socks5h://my.tor:9150", EsploraEndpoint.currentProxy())
    }

    // ---- Audit 2026-10 — per-network endpoints ---------------------------

    @Test
    fun `current(network) returns each network's own default`() {
        assertEquals(EsploraEndpoint.DEFAULT_MAINNET, EsploraEndpoint.current(Network.MAINNET))
        assertEquals(EsploraEndpoint.DEFAULT_SIGNET, EsploraEndpoint.current(Network.SIGNET))
        assertEquals(EsploraEndpoint.DEFAULT_TESTNET4, EsploraEndpoint.current(Network.TESTNET4))
    }

    @Test
    fun `an override applies only to its own network`() {
        SettingsStore.setEsploraUrl(Network.SIGNET, "https://my.signet.node/api")
        assertEquals("https://my.signet.node/api", EsploraEndpoint.current(Network.SIGNET))
        // A mainnet (watch) wallet must NOT be synced against the signet server.
        assertEquals(EsploraEndpoint.DEFAULT_MAINNET, EsploraEndpoint.current(Network.MAINNET))
        SettingsStore.setEsploraUrl(Network.MAINNET, "http://mynode.onion/api")
        assertEquals("http://mynode.onion/api", EsploraEndpoint.current(Network.MAINNET))
        assertEquals("https://my.signet.node/api", EsploraEndpoint.current(Network.SIGNET))
        SettingsStore.setEsploraUrl(Network.SIGNET, null)
        assertEquals(EsploraEndpoint.DEFAULT_SIGNET, EsploraEndpoint.current(Network.SIGNET))
        assertEquals("http://mynode.onion/api", EsploraEndpoint.current(Network.MAINNET))
    }

    @Test
    fun `networkOf parses registry network strings and refuses unknown ones`() {
        assertEquals(Network.MAINNET, EsploraEndpoint.networkOf("mainnet"))
        assertEquals(Network.SIGNET, EsploraEndpoint.networkOf("signet"))
        assertEquals(Network.TESTNET4, EsploraEndpoint.networkOf("testnet4"))
        assertEquals(Network.REGTEST, EsploraEndpoint.networkOf("regtest"))
        assertEquals(null, EsploraEndpoint.networkOf("liquid"))
    }

    @Test
    fun `legacy global override migrates to the network its path names`() {
        assertEquals(Network.SIGNET, SettingsPersistence.legacyEsploraNetwork("https://x.example/signet/api"))
        assertEquals(Network.TESTNET4, SettingsPersistence.legacyEsploraNetwork("https://x.example/testnet4/api"))
        assertEquals(Network.MAINNET, SettingsPersistence.legacyEsploraNetwork("http://abc.onion/api"))
        SettingsPersistence.apply(
            SettingsPersistence.Snapshot(esploraUrl = "https://mempool.example/signet/api"),
        )
        assertEquals("https://mempool.example/signet/api", EsploraEndpoint.current(Network.SIGNET))
        assertEquals(EsploraEndpoint.DEFAULT_MAINNET, EsploraEndpoint.current(Network.MAINNET))
        // Re-serialised in the per-network form only.
        val snap = SettingsPersistence.snapshot()
        assertEquals(null, snap.esploraUrl)
        assertEquals(mapOf("SIGNET" to "https://mempool.example/signet/api"), snap.esploraUrls)
    }
}
