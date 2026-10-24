package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.SizeLimitExceededException
import io.bhodl.desktop.data.readTextCapped
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wallet.UniffiWalletPort
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.ContactRecord
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ffi.WatchBipFfi
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Wallet settings, legacy cleanup, contacts, receive copy and helpers (area B). */
@OptIn(ExperimentalTestApi::class)
class UcWalletSettingsTest {

    private lateinit var tmp: File

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        SyncCache.clear()
        tmp = kotlin.io.path.createTempDirectory("bhodl-uc-wsettings-").toFile()
    }

    @AfterTest
    fun tearDown() {
        WalletStore.clear()
        RegistryStore.clear()
        SettingsStore.reset()
        tmp.deleteRecursively()
    }

    private fun registry(): RegistryHandle =
        RegistryHandle.createOrLoad(tmp.absolutePath, "482916").also { RegistryStore.set(it) }

    private class FakeClipboard(var text: String? = null) : ClipboardManager {
        override fun getText(): AnnotatedString? = text?.let { AnnotatedString(it) }
        override fun setText(annotatedString: AnnotatedString) {
            text = annotatedString.text
        }
    }

    // ---- WAL-029 rename ----------------------------------------------------------

    @Test
    fun `WAL-029 rename refuses blank and duplicate names, saves and bumps the data version`() =
        runDesktopComposeUiTest {
            val reg = registry()
            val mine = reg.addWatchXpub("Mine", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
            reg.addWatchAddresses("Other", listOf(SIGNET_ADDRESS), Network.SIGNET)
            WalletStore.set(FakeWalletPort(), Network.SIGNET, mine.id, mine.label)
            setContent { BhodlTheme { RenameWalletSection() } }
            waitUntil(timeoutMillis = 3_000) {
                runCatching { onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).assertTextContains("Mine") }.isSuccess
            }

            onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextClearance()
            onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextInput("   ")
            onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).assertIsNotEnabled()

            onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextClearance()
            onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextInput(" other ")
            onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).performClick()
            waitUntilAtLeastOneExists(hasText("Another wallet already uses this name."))
            assertEquals("Mine", reg.getWallet(mine.id)!!.label)

            // Same name with another case is allowed: it is this wallet.
            val before = RegistryStore.dataVersion.value
            onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextClearance()
            onNodeWithTag(SettingsTags.WALLET_NAME_FIELD).performTextInput("  Daily spend ")
            onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).performClick()
            waitUntilAtLeastOneExists(hasText("Saved."))
            assertEquals("Daily spend", reg.getWallet(mine.id)!!.label)
            assertEquals(before + 1, RegistryStore.dataVersion.value)
        }

    @Test
    fun `WAL-029 renaming a wallet missing from the registry says not found`() = runDesktopComposeUiTest {
        registry()
        WalletStore.set(FakeWalletPort(), Network.SIGNET, "00000000-0000-4000-8000-0000000000aa", "Ghost")
        setContent { BhodlTheme { RenameWalletSection() } }
        waitUntil(timeoutMillis = 3_000) {
            runCatching { onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).assertIsEnabled() }.isSuccess
        }
        onNodeWithTag(SettingsTags.WALLET_NAME_SAVE).performClick()
        waitUntilAtLeastOneExists(hasText("Wallet not found in registry."))
    }

    // ---- WAL-032 / GAP-024 remove --------------------------------------------------

    @Test
    fun `WAL-032 GAP-024 second tap closes the handle, removes row and file, clears the exclusion`() =
        runDesktopComposeUiTest {
            val reg = registry()
            val row = reg.addWatchXpub("Doomed", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
            val keep = reg.addWatchAddresses("Keep", listOf(SIGNET_ADDRESS), Network.SIGNET)
            val stateFile = File(tmp, "watch-state/${row.id}.sqlite")
            assertTrue(stateFile.exists())
            reg.setEventCategory(row.id, "aa".repeat(32), "food")
            SettingsStore.setWalletIncluded(row.id, false)
            SettingsStore.setWalletIncluded(keep.id, false)
            WalletStore.set(reg.openWatch(row.id), Network.SIGNET, row.id, row.label)
            var removed = 0
            setContent { BhodlTheme { RemoveThisWalletSection(onRemoved = { removed++ }) } }
            waitUntilAtLeastOneExists(hasTestTag(SettingsTags.DELETE_THIS_WALLET))
            onNodeWithTag(SettingsTags.DELETE_THIS_WALLET).performClick()
            waitUntilAtLeastOneExists(hasText("Tap again", substring = true))
            onNodeWithTag(SettingsTags.DELETE_THIS_WALLET).performClick()
            waitUntil(timeoutMillis = 10_000) { removed == 1 }

            assertNull(WalletStore.current())
            assertEquals(listOf(keep.id), reg.listWallets().map { it.id })
            assertFalse(stateFile.exists(), "watch-state file deleted (handle closed first)")
            assertNull(reg.getEventCategory(row.id, "aa".repeat(32)))
            assertFalse(row.id in SettingsStore.excludedWalletIds.value)
            assertTrue(keep.id in SettingsStore.excludedWalletIds.value)
        }

    // ---- WAL-121 / WAL-122 descriptor --------------------------------------------

    @Test
    fun `WAL-121 Hide puts the descriptor away again`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        setContent { BhodlTheme { ShowWatchOnlyDescriptorSection() } }
        onNodeWithTag(SettingsTags.WATCH_DESC_REVEAL).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_XPUB), timeoutMillis = 3_000)
        onNodeWithTag(SettingsTags.WATCH_DESC_HIDE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_REVEAL))
        assertEquals(0, onAllNodesWithTag(SettingsTags.WATCH_DESC_XPUB).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithTag(SettingsTags.WATCH_DESC_EXTERNAL).fetchSemanticsNodes().size)
    }

    @Test
    fun `WAL-121 the copy buttons put the xpub and descriptor on the clipboard`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        val clip = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clip) {
                BhodlTheme { ShowWatchOnlyDescriptorSection() }
            }
        }
        onNodeWithTag(SettingsTags.WATCH_DESC_REVEAL).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.WATCH_DESC_COPY_XPUB), timeoutMillis = 3_000)
        onNodeWithTag(SettingsTags.WATCH_DESC_COPY_XPUB).performClick()
        assertEquals("tpubFAKE", clip.text)
        onNodeWithTag(SettingsTags.WATCH_DESC_COPY_DESC).performClick()
        assertEquals(fake.exportPublicDescriptorResult!!.externalDescriptor, clip.text)
    }

    private fun waitFor(timeoutMs: Long = 3_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
    }

    @Test
    fun `WAL-122 the clipboard is cleared after the delay only if it still holds the value`() {
        val timer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val clip = FakeClipboard()
            copyToClipboardWithAutoClear(timer, clip, "xpubSECRET", timerScope = timer, delayMs = 50, uiContext = Dispatchers.Default)
            assertEquals("xpubSECRET", clip.text)
            waitFor { clip.text == "" }
            assertEquals("", clip.text)

            val other = FakeClipboard()
            copyToClipboardWithAutoClear(timer, other, "xpubSECRET", timerScope = timer, delayMs = 150, uiContext = Dispatchers.Default)
            other.text = "something the user copied later"
            Thread.sleep(400)
            assertEquals("something the user copied later", other.text)
        } finally {
            timer.cancel()
        }
    }

    // ---- WAL-110 / WAL-111 / WAL-112 labels --------------------------------------

    @Test
    fun `WAL-110 a failing import shows the rollback note and no count`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply { failImportLabels = WalletException.Storage("line 3 is not JSON") }
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                LabelsSection(
                    saveJsonl = { error("export not exercised") },
                    loadJsonl = { FileLoadResult(content = "{}\n{}\nnope", error = null) },
                )
            }
        }
        onNodeWithTag(SettingsTags.LABELS_IMPORT).performClick()
        waitUntilAtLeastOneExists(hasTestTag(SettingsTags.LABELS_ERROR), timeoutMillis = 3_000)
        onNodeWithTag(SettingsTags.LABELS_ERROR).assertTextContains("No labels were changed.", substring = true)
        onNodeWithTag(SettingsTags.LABELS_ERROR).assertTextContains("line 3 is not JSON", substring = true)
        assertEquals(0, onAllNodesWithTag(SettingsTags.LABELS_STATUS).fetchSemanticsNodes().size)
        onNodeWithTag(SettingsTags.LABELS_IMPORT).assertIsEnabled()
    }

    @Test
    fun `WAL-111 readTextCapped refuses files above the cap and reads the rest`() {
        val small = File(tmp, "small.jsonl").apply { writeText("{\"type\":\"tx\"}\n") }
        assertEquals("{\"type\":\"tx\"}\n", readTextCapped(small, 1024))
        val exact = File(tmp, "exact.jsonl").apply { writeBytes(ByteArray(1024) { 'a'.code.toByte() }) }
        assertEquals(1024, readTextCapped(exact, 1024).length)
        val big = File(tmp, "big.jsonl").apply { writeBytes(ByteArray(1025)) }
        val e = assertFailsWith<SizeLimitExceededException> { readTextCapped(big, 1024) }
        assertTrue(e.message!!.contains("too large"), e.message)
        // 50 MiB is the production cap: an empty sparse-ish file far below it reads fine.
        assertEquals("", readTextCapped(File(tmp, "empty.jsonl").apply { writeText("") }, 50 * 1024 * 1024))
    }

    @Test
    fun `WAL-112 label export runs without a privacy prompt even with amounts hidden`() =
        runDesktopComposeUiTest {
            SettingsStore.setBalancesHidden(true)
            val fake = FakeWalletPort().apply { labels["ab".repeat(32)] = "Rent" }
            WalletStore.set(fake)
            var saved: String? = null
            setContent {
                BhodlTheme {
                    LabelsSection(
                        saveJsonl = { saved = it; FileSaveResult(path = "labels.jsonl", error = null) },
                        loadJsonl = { error("import not exercised") },
                    )
                }
            }
            onNodeWithTag(SettingsTags.LABELS_EXPORT).performClick()
            waitUntil(timeoutMillis = 3_000) { saved != null }
            assertTrue(saved!!.contains("Rent"))
            // BIP-329 JSONL carries labels only, never amounts.
            assertFalse(saved!!.contains("sat"))
        }

    // ---- WAL-118 contacts against the real core ----------------------------------

    @Test
    fun `WAL-118 a core refusal of the address is shown and nothing is saved`() = runDesktopComposeUiTest {
        val reg = registry()
        val row = reg.addWatchXpub("W", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        val port = UniffiWalletPort(reg.openWatch(row.id))
        WalletStore.set(port, Network.SIGNET, row.id, row.label)
        setContent { BhodlTheme { AddressBookScreen(onBack = {}) } }
        waitUntilAtLeastOneExists(hasTestTag(AddressBookTags.LABEL))
        onNodeWithTag(AddressBookTags.LABEL).performTextInput("Exchange")
        onNodeWithTag(AddressBookTags.ADDRESS).performTextInput(MAINNET_ADDRESS)
        onNodeWithTag(AddressBookTags.SAVE).performClick()
        waitUntilAtLeastOneExists(hasText("Invalid address", substring = true), timeoutMillis = 5_000)
        assertTrue(port.listContacts().isEmpty())

        onNodeWithTag(AddressBookTags.ADDRESS).performTextClearance()
        onNodeWithTag(AddressBookTags.ADDRESS).performTextInput("definitely-not-an-address")
        onNodeWithTag(AddressBookTags.SAVE).performClick()
        waitForIdle()
        Thread.sleep(300)
        waitUntilAtLeastOneExists(hasText("Invalid address", substring = true), timeoutMillis = 5_000)
        assertTrue(port.listContacts().isEmpty())
    }

    @Test
    fun `WAL-118 a failing contact list shows the load error`() = runDesktopComposeUiTest {
        val failing = object : WalletPort by FakeWalletPort() {
            override fun listContacts(): List<ContactRecord> = throw WalletException.Storage("contacts unreadable")
        }
        WalletStore.set(failing)
        setContent { BhodlTheme { AddressBookScreen(onBack = {}) } }
        waitUntilAtLeastOneExists(hasText("contacts unreadable", substring = true), timeoutMillis = 3_000)
    }

    // ---- WAL-034 legacy cleanup ---------------------------------------------------

    private fun seedLegacy(): List<File> {
        val sub = File(tmp, "${WalletPaths.WALLETS_SUBDIR}/11111111-1111-4111-8111-111111111111").apply { mkdirs() }
        return listOf(
            File(sub, WalletPaths.WALLET_DB_FILE_NAME).apply { writeText("legacy") },
            File(tmp, WalletPaths.WALLET_DB_FILE_NAME).apply { writeText("legacy root") },
        )
    }

    @Test
    fun `WAL-034 delete stays disabled until confirmed, then removes the old wallets`() = runDesktopComposeUiTest {
        val kept = registry().addWatchXpub("Watch", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        val files = seedLegacy()
        var done = 0
        setContent { BhodlTheme { LegacyCleanupScreen(onDone = { done++ }, dataDir = tmp) } }
        waitUntilAtLeastOneExists(hasTestTag(LegacyCleanupTags.DELETE))
        onNodeWithTag(LegacyCleanupTags.DELETE).assertIsNotEnabled()
        onNodeWithTag(LegacyCleanupTags.CONFIRM).performClick()
        onNodeWithTag(LegacyCleanupTags.DELETE).assertIsEnabled().performClick()
        waitUntil(timeoutMillis = 5_000) { done == 1 }
        files.forEach { assertFalse(it.exists(), "$it must be deleted") }
        assertFalse(File(tmp, WalletPaths.WALLETS_SUBDIR).exists())
        assertEquals(listOf(kept.id), RegistryStore.require().listWallets().map { it.id })
        assertTrue(File(tmp, "watch-state/${kept.id}.sqlite").exists())
    }

    @Test
    fun `WAL-034 Not now leaves every file in place`() = runDesktopComposeUiTest {
        registry()
        val files = seedLegacy()
        var done = 0
        setContent { BhodlTheme { LegacyCleanupScreen(onDone = { done++ }, dataDir = tmp) } }
        onNodeWithTag(LegacyCleanupTags.LATER).performClick()
        waitUntil(timeoutMillis = 2_000) { done == 1 }
        files.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `WAL-034 a locked legacy file reports the failure and stays on screen`() = runDesktopComposeUiTest {
        org.junit.Assume.assumeTrue(
            "file locks only block deletion on Windows",
            System.getProperty("os.name").lowercase().contains("win"),
        )
        registry()
        val files = seedLegacy()
        var done = 0
        FileInputStream(files.first()).use {
            setContent { BhodlTheme { LegacyCleanupScreen(onDone = { done++ }, dataDir = tmp) } }
            onNodeWithTag(LegacyCleanupTags.CONFIRM).performClick()
            onNodeWithTag(LegacyCleanupTags.DELETE).performClick()
            waitUntilAtLeastOneExists(hasText("could not be deleted", substring = true), timeoutMillis = 5_000)
        }
        assertEquals(0, done)
    }

    // ---- WAL-036 wallet list ------------------------------------------------------

    @Test
    fun `WAL-036 clicking a wallet row opens that wallet`() = runDesktopComposeUiTest {
        val rows = listOf(
            WalletRecordFfi("w1", "Cold", WalletKindFfi.WATCH_XPUB, "tr(tpubA/0/*)", "signet", 0),
            WalletRecordFfi("w2", "List", WalletKindFfi.WATCH_ADDRESSES, "[\"tb1q\"]", "signet", 1),
        )
        val opened = mutableListOf<String>()
        setContent {
            BhodlTheme {
                Box(Modifier.size(1200.dp, 600.dp)) {
                    WalletListTable(wallets = rows, watchBalances = emptyMap(), onOpenWallet = { opened += it })
                }
            }
        }
        onNodeWithTag(WalletListTableTags.row("w2")).performClick()
        onNodeWithTag(WalletListTableTags.row("w1")).performClick()
        assertEquals(listOf("w2", "w1"), opened)
    }

    // ---- WAL-046 / WAL-047 wallet store and port -----------------------------------

    @Test
    fun `WAL-046 switching wallet drops the sync cache, re-setting the same id keeps it`() {
        WalletStore.set(FakeWalletPort(), Network.SIGNET, "a", "A")
        SyncCache.record(5u)
        WalletStore.set(FakeWalletPort(), Network.SIGNET, "a", "A")
        assertEquals(5uL, SyncCache.lastBalanceSat())
        WalletStore.set(FakeWalletPort(), Network.SIGNET, "b", "B")
        assertNull(SyncCache.lastBalanceSat())
        SyncCache.record(7u)
        WalletStore.clear()
        assertNull(SyncCache.lastBalanceSat())
    }

    @Test
    fun `WAL-047 closing a real wallet port twice is harmless and frees the file`() {
        val reg = registry()
        val row = reg.addWatchXpub("W", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        val port = UniffiWalletPort(reg.openWatch(row.id))
        assertEquals(0uL, port.balanceSat())
        port.close()
        port.close()
        assertTrue(reg.removeWallet(row.id))
        assertFalse(File(tmp, "watch-state/${row.id}.sqlite").exists())
    }

    // ---- WAL-088 receive copy ------------------------------------------------------

    @Test
    fun `WAL-088 copy address and copy URI fill the clipboard and flash Copied`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        val clip = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboardManager provides clip) {
                BhodlTheme { ReceiveScreen() }
            }
        }
        onNodeWithTag(ReceiveTags.SKIP).performClick()
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.QR), timeoutMillis = 3_000)
        val address = fake.nextReceiveBuilder(0)
        onNodeWithTag(ReceiveTags.COPY_ADDRESS).performScrollTo().performClick()
        assertEquals(address, clip.text)
        waitUntilAtLeastOneExists(hasText("Copied address"))
        onNodeWithTag(ReceiveTags.COPY_URI).performScrollTo().performClick()
        assertEquals("bitcoin:$address", clip.text)
        waitUntilAtLeastOneExists(hasText("Copied URI"))
        waitUntilAtLeastOneExists(hasText("Copy address"), timeoutMillis = 4_000)
    }

    // ---- GAP-033 aggregate balance from Kotlin -------------------------------------

    @Test
    fun `GAP-033 aggregate balance per network with stale ids reaches Kotlin`() {
        val reg = registry()
        reg.addWatchXpub("Signet", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
        val broken = reg.addWatchAddresses("Broken", listOf(SIGNET_ADDRESS), Network.SIGNET)
        File(tmp, "watch-state/${broken.id}.sqlite").writeText("garbage")
        val agg = reg.aggregateWatchBalance(Network.SIGNET)
        assertEquals(0uL, agg.totalSat)
        assertEquals(listOf(broken.id), agg.staleWalletIds)
        assertEquals(agg.totalSat, reg.aggregateWatchBalanceSat(Network.SIGNET))
        assertTrue(reg.aggregateWatchBalance(Network.MAINNET).staleWalletIds.isEmpty())
        assertNotNull(io.bhodl.ffi.generateWalletId().takeIf { it.length == 36 })
    }

    private companion object {
        const val TPUB =
            "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"
        const val SIGNET_ADDRESS = "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
        const val MAINNET_ADDRESS = "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"
    }
}
