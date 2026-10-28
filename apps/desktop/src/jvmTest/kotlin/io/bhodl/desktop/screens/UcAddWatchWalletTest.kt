package io.bhodl.desktop.screens

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wallet.AccountKey
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletLabel
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WatchBipFfi
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WAL-018…WAL-023 — the "Add watch-only" form against a real registry. */
@OptIn(ExperimentalTestApi::class)
class UcAddWatchWalletTest {

    private lateinit var tmp: File
    private lateinit var registry: RegistryHandle

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        // Every auto-sync after an add hits a closed local port: no network.
        for (n in Network.entries) SettingsStore.setEsploraUrl(n, DEAD_ESPLORA)
        tmp = kotlin.io.path.createTempDirectory("bhodl-uc-addwatch-").toFile()
        registry = RegistryHandle.createOrLoad(tmp.absolutePath, "482916")
        RegistryStore.set(registry)
    }

    @AfterTest
    fun tearDown() {
        RegistryStore.clear()
        WalletStore.clear()
        SettingsStore.reset()
        tmp.deleteRecursively()
    }

    private fun ComposeUiTest.showForm(onAdded: () -> Unit = {}) {
        setContent { BhodlTheme { AddWatchScreen(onAdded = onAdded, onBack = {}) } }
        waitForIdle()
    }

    private fun ComposeUiTest.fill(label: String, xpub: String) {
        onNodeWithTag(AddWatchTags.LABEL).performTextInput(label)
        onNodeWithTag(AddWatchTags.XPUB).performTextInput(xpub)
    }

    private fun ComposeUiTest.waitForRows(n: Int) {
        waitUntil(timeoutMillis = 15_000) { registry.listWallets().size == n }
    }

    @Test
    fun `WAL-018 WAL-022 submit is gated, the row lands with the chosen script and network, sync failure still returns`() =
        runDesktopComposeUiTest {
            var added = 0
            val before = RegistryStore.dataVersion.value
            showForm(onAdded = { added++ })
            onNodeWithTag(AddWatchTags.SUBMIT).assertIsNotEnabled()
            onNodeWithTag(AddWatchTags.LABEL).performTextInput("Cold storage")
            onNodeWithTag(AddWatchTags.SUBMIT).assertIsNotEnabled()
            onNodeWithTag(AddWatchTags.XPUB).performTextInput("  $TPUB  ")
            onNodeWithTag(AddWatchTags.SUBMIT).assertIsEnabled()
            onNodeWithText("BIP-84").performClick()
            onNodeWithText("Testnet4").performClick()
            onNodeWithTag(AddWatchTags.SUBMIT).performClick()

            waitUntil(timeoutMillis = 30_000) { added == 1 }
            val row = registry.listWallets().single()
            assertEquals("Cold storage", row.label)
            assertEquals(WalletKindFfi.WATCH_XPUB, row.kind)
            assertEquals("testnet4", row.network)
            assertTrue(row.descriptorPub.startsWith("wpkh($TPUB/0/*)"), row.descriptorPub)
            assertTrue(RegistryStore.dataVersion.value > before)
            assertTrue(File(tmp, "watch-state/${row.id}.sqlite").exists())
        }

    @Test
    fun `WAL-018 defaults are Taproot on Signet`() = runDesktopComposeUiTest {
        var added = 0
        showForm(onAdded = { added++ })
        fill("Default", TPUB)
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntil(timeoutMillis = 30_000) { added == 1 }
        val row = registry.listWallets().single()
        assertEquals("signet", row.network)
        assertTrue(row.descriptorPub.startsWith("tr($TPUB/0/*)"), row.descriptorPub)
    }

    @Test
    fun `WAL-019 a label already used, ignoring case and spaces, is refused`() = runDesktopComposeUiTest {
        registry.addWatchXpub("Cold", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        var added = 0
        showForm(onAdded = { added++ })
        fill("  cOLD ", MAINNET_XPUB)
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntilAtLeastOneExists(hasText("A wallet with this name already exists", substring = true))
        assertEquals(0, added)
        assertEquals(1, registry.listWallets().size)
    }

    @Test
    fun `WAL-019 isDuplicate trims, ignores case and can exclude the wallet being renamed`() {
        val row = registry.addWatchXpub("My Wallet", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        assertTrue(WalletLabel.isDuplicate(registry, " my wallet "))
        assertFalse(WalletLabel.isDuplicate(registry, "My Wallet 2"))
        assertFalse(WalletLabel.isDuplicate(registry, "   "))
        assertFalse(WalletLabel.isDuplicate(registry, "MY WALLET", excludeWalletId = row.id))
    }

    @Test
    fun `WAL-018 the back arrow at the top leaves without adding`() = runDesktopComposeUiTest {
        var back = 0
        setContent { BhodlTheme { AddWatchScreen(onAdded = {}, onBack = { back++ }) } }
        onNodeWithTag(io.bhodl.desktop.ui.BhodlBackArrowTags.ROOT).performClick()
        assertEquals(1, back)
        assertTrue(registry.listWallets().isEmpty())
    }

    @Test
    fun `WAL-020 the same tpub twice is refused and names the existing wallet`() = runDesktopComposeUiTest {
        registry.addWatchXpub("Vault", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        var added = 0
        showForm(onAdded = { added++ })
        fill("Again", TPUB)
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntilAtLeastOneExists(hasText("already registered as “Vault”", substring = true))
        assertEquals(0, added)
        assertEquals(1, registry.listWallets().size)
    }

    @Test
    fun `WAL-020 a vpub of an account saved as tpub is caught as a duplicate`() = runDesktopComposeUiTest {
        registry.addWatchXpub("Vault", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        val vpub = withVersion(TPUB, VPUB_VERSION)
        assertTrue(vpub.startsWith("vpub"))
        var added = 0
        showForm(onAdded = { added++ })
        fill("Segwit view", vpub)
        onNodeWithText("BIP-84").performClick()
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntilAtLeastOneExists(hasText("already registered as “Vault”", substring = true))
        assertEquals(0, added)
        assertEquals(1, registry.listWallets().size)
    }

    @Test
    fun `WAL-021 mainnet without Tor warns, Cancel adds nothing, Continue adds`() = runDesktopComposeUiTest {
        var added = 0
        showForm(onAdded = { added++ })
        fill("Main", MAINNET_XPUB)
        onNodeWithText("Mainnet").performClick()
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntilAtLeastOneExists(hasText("Mainnet sync without Tor"))
        onNodeWithText("Cancel").performClick()
        waitForIdle()
        assertEquals(0, onAllNodesWithText("Mainnet sync without Tor").fetchSemanticsNodes().size)
        assertTrue(registry.listWallets().isEmpty())

        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntilAtLeastOneExists(hasText("Continue without Tor"))
        onNodeWithText("Continue without Tor").performClick()
        waitUntil(timeoutMillis = 30_000) { added == 1 }
        assertEquals("mainnet", registry.listWallets().single().network)
    }

    @Test
    fun `WAL-021 mainnet with Tor on adds without the warning`() = runDesktopComposeUiTest {
        SettingsStore.setTorProxyUrl("socks5h://127.0.0.1:1")
        SettingsStore.setTorEnabled(true)
        var added = 0
        showForm(onAdded = { added++ })
        fill("Main", MAINNET_XPUB)
        onNodeWithText("Mainnet").performClick()
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntil(timeoutMillis = 30_000) { added == 1 }
        assertEquals(0, onAllNodesWithText("Mainnet sync without Tor").fetchSemanticsNodes().size)
        assertEquals(1, registry.listWallets().size)
    }

    @Test
    fun `WAL-023 core errors are shown and leave no row`() = runDesktopComposeUiTest {
        var added = 0
        showForm(onAdded = { added++ })
        fill("Wrong net", MAINNET_XPUB)
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntilAtLeastOneExists(
            hasText("does not match wallet network", substring = true),
            timeoutMillis = 10_000,
        )
        assertEquals(0, added)
        assertTrue(registry.listWallets().isEmpty())
        assertTrue(File(tmp, "watch-state").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `WAL-023 garbage xpub is refused with a readable error`() = runDesktopComposeUiTest {
        showForm()
        fill("Junk", "definitely-not-an-xpub")
        onNodeWithTag(AddWatchTags.SUBMIT).performClick()
        waitUntilAtLeastOneExists(hasText("invalid xpub", substring = true), timeoutMillis = 10_000)
        assertTrue(registry.listWallets().isEmpty())
    }

    @Test
    fun `WAL-020 AccountKey normalises SLIP-132 keys and leaves the rest alone`() {
        assertEquals(TPUB, AccountKey.normalize(withVersion(TPUB, VPUB_VERSION)))
        assertEquals(TPUB, AccountKey.normalize(withVersion(TPUB, UPUB_VERSION)))
        assertEquals(MAINNET_XPUB, AccountKey.normalize(withVersion(MAINNET_XPUB, ZPUB_VERSION)))
        assertEquals(MAINNET_XPUB, AccountKey.normalize(" ${withVersion(MAINNET_XPUB, YPUB_VERSION)} "))
        assertEquals(TPUB, AccountKey.normalize(" $TPUB "))
        val broken = withVersion(TPUB, VPUB_VERSION).dropLast(1) + "1"
        assertEquals(broken, AccountKey.normalize(broken))
        assertEquals("vpubgarbage!", AccountKey.normalize("vpubgarbage!"))
    }

    private fun withVersion(key: String, version: ByteArray): String {
        val data = AccountKey.decodeCheck(key)!!
        version.copyInto(data)
        return AccountKey.encodeCheck(data)
    }

    private companion object {
        const val DEAD_ESPLORA = "http://127.0.0.1:1"

        /** Account-level tpub shared with the other desktop tests. */
        const val TPUB =
            "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"

        /** BIP-86 test vector account m/86'/0'/0'. */
        const val MAINNET_XPUB =
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"

        val VPUB_VERSION = byteArrayOf(0x04, 0x5F, 0x1C, 0xF6.toByte())
        val UPUB_VERSION = byteArrayOf(0x04, 0x4A, 0x52, 0x62)
        val ZPUB_VERSION = byteArrayOf(0x04, 0xB2.toByte(), 0x47, 0x46)
        val YPUB_VERSION = byteArrayOf(0x04, 0x9D.toByte(), 0x7C, 0xB2.toByte())
    }
}
