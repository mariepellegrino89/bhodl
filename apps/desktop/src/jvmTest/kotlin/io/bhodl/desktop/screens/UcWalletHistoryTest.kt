package io.bhodl.desktop.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.TransactionRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.WatchBipFfi
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Transactions tab and detail pane (WAL-072, 074, 075, 077, 108). */
@OptIn(ExperimentalTestApi::class)
class UcWalletHistoryTest {

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
        tmp?.deleteRecursively()
    }

    private fun record(id: String, net: Long, height: UInt?, ts: ULong? = 1_700_000_000UL, fee: ULong? = 200UL) =
        TransactionRecord(
            txid = id.repeat(8),
            netSat = net,
            feeSat = fee,
            confirmationHeight = height,
            timestamp = ts,
        )

    @Test
    fun `WAL-072 status sort puts pending first ascending, deepest first descending`() {
        val pending = record("0000aaaa", 1, null, ts = null)
        val one = record("1111bbbb", 2, 100u)
        val deep = record("2222cccc", 3, 10u)
        val all = listOf(deep, pending, one)
        assertEquals(
            listOf(pending, one, deep),
            sortTransactions(all, TxSortColumn.STATUS, ascending = true, tipHeight = 100u),
        )
        assertEquals(
            listOf(deep, one, pending),
            sortTransactions(all, TxSortColumn.STATUS, ascending = false, tipHeight = 100u),
        )
        // Unknown tip: pending still comes first.
        assertEquals(
            pending,
            sortTransactions(all, TxSortColumn.STATUS, ascending = true, tipHeight = null).first(),
        )
    }

    private fun rowOrder(test: androidx.compose.ui.test.ComposeUiTest, ids: List<String>): List<String> =
        ids.sortedBy { id ->
            test.onNodeWithTag(HistoryTags.row(id)).fetchSemanticsNode().positionInRoot.y
        }

    @Test
    fun `WAL-072 clicking a header sorts by it and a second click reverses`() = runDesktopComposeUiTest {
        val small = record("1111aaaa", 1_000, 90u, ts = 1_700_000_300UL)
        val big = record("2222bbbb", 9_000, 95u, ts = 1_700_000_100UL)
        val mid = record("3333cccc", 5_000, 99u, ts = 1_700_000_200UL)
        val fake = FakeWalletPort().apply {
            transactions += listOf(small, big, mid)
            tipHeight = 100u
        }
        WalletStore.set(fake)
        val ids = listOf(small, big, mid).map { it.txid }
        setContent {
            BhodlTheme {
                Box(Modifier.size(1400.dp, 900.dp)) { HistoryScreen(syncOnEnter = false) }
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(small.txid)))
        assertEquals(listOf(small.txid, mid.txid, big.txid), rowOrder(this, ids), "newest first")

        onNodeWithTag(HistoryTags.sortHeader(TxSortColumn.AMOUNT)).performClick()
        waitForIdle()
        val first = rowOrder(this, ids)
        onNodeWithTag(HistoryTags.sortHeader(TxSortColumn.AMOUNT)).performClick()
        waitForIdle()
        val second = rowOrder(this, ids)
        assertEquals(setOf(listOf(small.txid, mid.txid, big.txid), listOf(big.txid, mid.txid, small.txid)), setOf(first, second))
        assertEquals(first.reversed(), second)

        onNodeWithTag(HistoryTags.sortHeader(TxSortColumn.STATUS)).performClick()
        waitForIdle()
        val byStatus = rowOrder(this, ids)
        onNodeWithTag(HistoryTags.sortHeader(TxSortColumn.STATUS)).performClick()
        waitForIdle()
        assertEquals(byStatus.reversed(), rowOrder(this, ids))
        assertEquals(setOf(listOf(mid.txid, big.txid, small.txid), listOf(small.txid, big.txid, mid.txid)), setOf(byStatus, byStatus.reversed()))
    }

    @Test
    fun `WAL-074 a tx without a BIP-329 category shows the tracker category from the registry`() =
        runDesktopComposeUiTest {
            val dir = kotlin.io.path.createTempDirectory("bhodl-uc-history-").toFile().also { tmp = it }
            val registry = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
            RegistryStore.set(registry)
            val row = registry.addWatchXpub("W", TPUB, Network.SIGNET, WatchBipFfi.TAPROOT)
            val fromRegistry = record("aaaa1111", 5_000, 10u)
            val own = record("bbbb2222", 6_000, 10u)
            registry.setEventCategory(row.id, fromRegistry.txid, "groceries")
            registry.setEventCategory(row.id, own.txid, "ignored")
            val fake = FakeWalletPort().apply {
                transactions += listOf(fromRegistry, own)
                txCategories[own.txid] = "consulting"
            }
            WalletStore.set(fake, Network.SIGNET, row.id, row.label)
            setContent { BhodlTheme { HistoryScreen(syncOnEnter = false) } }
            waitUntilAtLeastOneExists(hasText("groceries", substring = true), timeoutMillis = 3_000)
            waitUntilAtLeastOneExists(hasText("consulting", substring = true), timeoutMillis = 3_000)
            assertEquals(
                0,
                onAllNodes(hasText("ignored", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }

    @Test
    fun `WAL-075 stand-alone History syncs on entry, embedded does not`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort().apply { transactions += record("cccc3333", 1, 1u) }
        WalletStore.set(fake, Network.TESTNET4)
        var standalone by androidx.compose.runtime.mutableStateOf(false)
        setContent {
            BhodlTheme {
                if (standalone) HistoryScreen(onBack = {}, syncOnEnter = true)
                else HistoryScreen(syncOnEnter = false)
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.TABLE))
        waitForIdle()
        assertEquals(0, fake.syncCalls.size)
        standalone = true
        waitUntil(timeoutMillis = 3_000) { fake.syncCalls.size == 1 }
        assertEquals(io.bhodl.desktop.settings.EsploraEndpoint.DEFAULT_TESTNET4, fake.syncCalls.single())
    }

    @Test
    fun `WAL-075 a failed entry sync keeps the local history`() = runDesktopComposeUiTest {
        val tx = record("dddd4444", 7_000, 5u)
        val fake = FakeWalletPort().apply {
            transactions += tx
            failSync = WalletException.EsploraSync("offline")
        }
        WalletStore.set(fake)
        setContent { BhodlTheme { HistoryScreen(onBack = {}, syncOnEnter = true) } }
        waitUntil(timeoutMillis = 3_000) { fake.syncCalls.size == 1 }
        waitUntilAtLeastOneExists(hasTestTag(HistoryTags.row(tx.txid)), timeoutMillis = 3_000)
    }

    @Test
    fun `WAL-077 incoming tx hides the fee and confirmations follow the tip`() = runDesktopComposeUiTest {
        val incoming = record("eeee5555", 10_000, 100u, fee = null)
        val outgoing = record("ffff6666", -4_000, null, fee = 321UL)
        val fake = FakeWalletPort().apply { transactions += listOf(incoming, outgoing) }
        WalletStore.set(fake)
        var shown by androidx.compose.runtime.mutableStateOf(incoming.txid)
        setContent {
            BhodlTheme {
                TransactionDetailsPane(
                    txid = shown,
                    onClose = null,
                    tipHeight = 102u,
                    openExplorer = {},
                    copyToClipboard = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.AMOUNT), timeoutMillis = 3_000)
        assertEquals(0, onAllNodesWithTag(TransactionDetailsTags.FEE).fetchSemanticsNodes().size)
        waitUntilAtLeastOneExists(hasText("3"))

        shown = outgoing.txid
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.FEE), timeoutMillis = 3_000)
        // Pending: zero confirmations.
        waitUntilAtLeastOneExists(hasText("0"))
    }

    @Test
    fun `WAL-108 a failing label save shows the inline error and keeps the text`() = runDesktopComposeUiTest {
        val tx = record("abab7777", 1_000, 1u)
        val inner = FakeWalletPort().apply { transactions += tx }
        val failing = object : WalletPort by inner {
            override fun setTxLabel(txid: String, text: String) =
                throw WalletException.Storage("label store locked")
        }
        WalletStore.set(failing)
        setContent {
            BhodlTheme {
                TransactionDetailsPane(txid = tx.txid, onClose = null, openExplorer = {}, copyToClipboard = {})
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.LABEL_FIELD), timeoutMillis = 3_000)
        onNodeWithTag(TransactionDetailsTags.LABEL_FIELD).performTextClearance()
        onNodeWithTag(TransactionDetailsTags.LABEL_FIELD).performTextInput("Coffee")
        onNodeWithTag(TransactionDetailsTags.LABEL_SAVE).performClick()
        waitUntilAtLeastOneExists(hasTestTag(TransactionDetailsTags.LABEL_ERROR), timeoutMillis = 3_000)
        waitUntilAtLeastOneExists(hasText("label store locked", substring = true))
        assertEquals(null, inner.labels[tx.txid])
    }

    private companion object {
        const val TPUB =
            "tpubDDfvzhdVV4unsoKt5aE6dcsNsfeWbTgmLZPi8LQDYU2xixrYemMfWJ3BaVneH3u7DBQePdTwhpybaKRU95pi6PMUtLPBJLVQRpzEnjfjZzX"
    }
}
