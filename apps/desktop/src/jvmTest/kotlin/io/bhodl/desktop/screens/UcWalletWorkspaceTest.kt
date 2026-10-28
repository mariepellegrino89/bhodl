package io.bhodl.desktop.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.FooterStatusBarTags
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.Network
import io.bhodl.ffi.ReceiveLabelCopyRecord
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WatchBipFfi
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wallet workspace header, sync and tabs (WAL-059…WAL-124). */
@OptIn(ExperimentalTestApi::class)
class UcWalletWorkspaceTest {

    private var tmp: File? = null

    @BeforeTest
    fun setUp() {
        SyncCache.clear()
        SettingsStore.reset()
    }

    @AfterTest
    fun tearDown() {
        WalletStore.clear()
        RegistryStore.clear()
        SettingsStore.reset()
        PriceCache.seedForTest(null)
        tmp?.deleteRecursively()
    }

    private fun realRegistry(): RegistryHandle {
        val dir = kotlin.io.path.createTempDirectory("bhodl-uc-workspace-").toFile()
        tmp = dir
        return RegistryHandle.createOrLoad(dir.absolutePath, "482916").also { RegistryStore.set(it) }
    }

    /** Sync blocks until [release] is counted down. */
    private class BlockingSyncPort(val inner: FakeWalletPort) : WalletPort by inner {
        val release = CountDownLatch(1)
        override fun syncWithEsplora(esploraUrl: String, proxyUrl: String?) {
            inner.syncWithEsplora(esploraUrl, proxyUrl)
            release.await(10, TimeUnit.SECONDS)
        }
    }

    private class FailingListsPort(val inner: FakeWalletPort) : WalletPort by inner {
        override fun listAddresses(change: Boolean): List<AddressRecord> =
            throw WalletException.Storage("address table on fire")
        override fun listUtxos(): List<UtxoRecord> =
            throw WalletException.Storage("utxo table on fire")
    }

    @Test
    fun `WAL-059 manual sync bypasses a fresh cache and disables the button while it runs`() =
        runDesktopComposeUiTest {
            val inner = FakeWalletPort(balance = 1_000u)
            val port = BlockingSyncPort(inner)
            WalletStore.set(port)
            SyncCache.record(1_000u)
            setContent { BhodlTheme { DashboardScreen() } }
            waitUntilAtLeastOneExists(hasTestTag(DashboardTags.SYNC))
            waitForIdle()
            assertTrue(inner.syncCalls.isEmpty(), "fresh cache: no auto-sync")

            onNodeWithTag(DashboardTags.SYNC).performClick()
            waitUntil(timeoutMillis = 3_000) { inner.syncCalls.size == 1 }
            waitForIdle()
            onNodeWithTag(DashboardTags.SYNC).assertIsNotEnabled()
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextContains("Syncing", substring = true)
            onNodeWithTag(DashboardTags.SYNC).performClick()

            port.release.countDown()
            waitUntil(timeoutMillis = 3_000) {
                runCatching { onNodeWithTag(DashboardTags.SYNC).assertIsEnabled() }.isSuccess
            }
            assertEquals(1, inner.syncCalls.size)
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextContains("Synced", substring = true)
        }

    @Test
    fun `WAL-061 a mainnet wallet syncs against the mainnet endpoint, overrides win`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake, Network.MAINNET)
            setContent { BhodlTheme { DashboardScreen() } }
            waitUntil(timeoutMillis = 3_000) { fake.syncCalls.isNotEmpty() }
            assertEquals(EsploraEndpoint.DEFAULT_MAINNET, fake.syncCalls.last())
        }

    @Test
    fun `WAL-061 a per-network override is used for that wallet only`() = runDesktopComposeUiTest {
        SettingsStore.setEsploraUrl(Network.TESTNET4, "https://t4.example.org/api")
        SettingsStore.setEsploraUrl(Network.SIGNET, "https://signet.example.org/api")
        val fake = FakeWalletPort()
        WalletStore.set(fake, Network.TESTNET4)
        setContent { BhodlTheme { DashboardScreen() } }
        waitUntil(timeoutMillis = 3_000) { fake.syncCalls.isNotEmpty() }
        assertEquals("https://t4.example.org/api", fake.syncCalls.last())
    }

    @Test
    fun `WAL-064 header sync status covers syncing, synced, never synced and failed`() =
        runDesktopComposeUiTest {
            var mode by mutableStateOf(SyncMode.Idle)
            var last by mutableStateOf<Long?>(null)
            var failed by mutableStateOf(false)
            setContent {
                BhodlTheme {
                    WalletHeader(
                        walletName = "W",
                        kindLabel = "Watch",
                        network = Network.SIGNET,
                        balanceSat = 0u,
                        syncMode = mode,
                        lastSyncAtMs = last,
                        syncFailed = failed,
                        onSync = {},
                    )
                }
            }
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextEquals("Not synced yet")
            last = 1_700_000_000_000L
            waitForIdle()
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextContains("Synced ", substring = true)
            mode = SyncMode.Background
            waitForIdle()
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextEquals("Syncing…")
            onNodeWithTag(DashboardTags.SYNC).assertIsEnabled()
            mode = SyncMode.Foreground
            waitForIdle()
            onNodeWithTag(DashboardTags.SYNC).assertIsNotEnabled()
            mode = SyncMode.Idle
            failed = true
            waitForIdle()
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextEquals("Sync failed")
        }

    @Test
    fun `WAL-068 balance shows a dash before any data and a fiat line once priced`() =
        runDesktopComposeUiTest {
            var balance by mutableStateOf<ULong?>(null)
            SettingsStore.setFiatCurrency(FiatCurrency.USD)
            PriceCache.seedForTest(50_000.0)
            setContent {
                BhodlTheme {
                    WalletHeader(
                        walletName = "W",
                        kindLabel = "Watch",
                        network = Network.SIGNET,
                        balanceSat = balance,
                        syncMode = SyncMode.Idle,
                        lastSyncAtMs = null,
                        syncFailed = false,
                        onSync = {},
                    )
                }
            }
            onNodeWithTag(DashboardTags.BALANCE).assertTextEquals("—")
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextEquals("Not synced yet")
            balance = 100_000u
            waitForIdle()
            onNodeWithTag(DashboardTags.BALANCE).assertTextContains("100,000", substring = true)
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextContains("≈", substring = true)
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextContains("$50.00", substring = true)

            SettingsStore.setFiatCurrency(FiatCurrency.NONE)
            waitForIdle()
            onNodeWithTag(DashboardTags.SYNC_STATUS).assertTextEquals("Not synced yet")
        }

    @Test
    fun `WAL-093 receive-label categories land in the tracker registry under the wallet id`() {
        val registry = realRegistry()
        val row = registry.addWatchXpub("W", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        val fake = FakeWalletPort().apply {
            receiveLabelCopies = listOf(
                ReceiveLabelCopyRecord("aa".repeat(32), "tb1pa", "Salary", "income"),
                ReceiveLabelCopyRecord("bb".repeat(32), "tb1pb", "No category", null),
            )
        }
        WalletStore.set(fake, Network.SIGNET, row.id, row.label)
        val before = RegistryStore.dataVersion.value
        applyReceiveLabels(fake)
        assertEquals("income", registry.getEventCategory(row.id, "aa".repeat(32)))
        assertNull(registry.getEventCategory(row.id, "bb".repeat(32)))
        assertEquals(before + 1, RegistryStore.dataVersion.value)

        // Nothing with a category: no bump. A failing core never throws.
        fake.receiveLabelCopies = listOf(ReceiveLabelCopyRecord("cc".repeat(32), "tb1pc", "x", null))
        applyReceiveLabels(fake)
        assertEquals(before + 1, RegistryStore.dataVersion.value)
        val failing = object : WalletPort by fake {
            override fun applyReceiveLabels(): List<ReceiveLabelCopyRecord> =
                throw WalletException.Storage("boom")
        }
        applyReceiveLabels(failing)
    }

    @Test
    fun `WAL-095 the Change chip lists change addresses`() = runDesktopComposeUiTest {
        val recv = AddressRecord(0u, "tb1preceive".padEnd(62, 'r'), false, false, null, null)
        val chg = AddressRecord(0u, "tb1pchange".padEnd(62, 'c'), true, true, null, null)
        val fake = FakeWalletPort().apply {
            receiveAddresses += recv
            changeAddresses += chg
        }
        WalletStore.set(fake)
        setContent { BhodlTheme { DashboardScreen(initialTab = WalletTab.ADDRESSES) } }
        waitUntilAtLeastOneExists(hasTestTag(WalletTabTags.addressRow(recv.address)))
        onNodeWithTag(WalletTabTags.ADDR_CHANGE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletTabTags.addressRow(chg.address)), timeoutMillis = 3_000)
        onNodeWithTag(WalletTabTags.addressRow(recv.address)).assertDoesNotExist()
        onNodeWithTag(WalletTabTags.ADDR_RECEIVE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(WalletTabTags.addressRow(recv.address)), timeoutMillis = 3_000)
    }

    @Test
    fun `WAL-095 WAL-097 load failures of addresses and UTXOs are shown`() = runDesktopComposeUiTest {
        WalletStore.set(FailingListsPort(FakeWalletPort()))
        var tab by mutableStateOf(WalletTab.ADDRESSES)
        setContent {
            BhodlTheme {
                when (tab) {
                    WalletTab.ADDRESSES -> WalletAddressesTab(refreshKey = 0, onShowInReceive = {})
                    else -> WalletUtxosTab(refreshKey = 0)
                }
            }
        }
        waitUntilAtLeastOneExists(hasText("address table on fire", substring = true), timeoutMillis = 3_000)
        tab = WalletTab.UTXOS
        waitUntilAtLeastOneExists(hasText("utxo table on fire", substring = true), timeoutMillis = 3_000)
    }

    @Test
    fun `WAL-124 header name comes from the registry and the footer counts every wallet`() =
        runDesktopComposeUiTest {
            val registry = realRegistry()
            val a = registry.addWatchXpub("Registry name", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
            registry.addWatchAddresses("Second", listOf(SIGNET_ADDRESS), Network.SIGNET)
            WalletStore.set(FakeWalletPort(), Network.SIGNET, a.id, "Stale label")
            SyncCache.record(0u)
            setContent { BhodlTheme { DashboardScreen() } }
            waitUntil(timeoutMillis = 5_000) {
                runCatching {
                    onNodeWithTag(DashboardTags.WALLET_NAME).assertTextEquals("Registry name")
                }.isSuccess
            }
            onNodeWithTag(FooterStatusBarTags.WALLET_COUNT).assertTextEquals("2 wallets")
        }

    @Test
    fun `WAL-124 without a registry the WalletStore label and one wallet are shown`() =
        runDesktopComposeUiTest {
            WalletStore.set(FakeWalletPort(), Network.SIGNET, null, "Local label")
            SyncCache.record(0u)
            setContent { BhodlTheme { DashboardScreen() } }
            waitUntilAtLeastOneExists(hasTestTag(DashboardTags.WALLET_NAME))
            onNodeWithTag(DashboardTags.WALLET_NAME).assertTextEquals("Local label")
            onNodeWithTag(FooterStatusBarTags.WALLET_COUNT).assertTextEquals("1 wallet")
        }

    private companion object {
        const val TPUB =
            "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"

        /** BIP-86 test vector receive address 0, signet encoding. */
        const val SIGNET_ADDRESS = "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
    }
}
