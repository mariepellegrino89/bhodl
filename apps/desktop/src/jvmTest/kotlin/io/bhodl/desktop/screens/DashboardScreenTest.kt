package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.ErrorCardTags
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wallet.SyncCache
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.AddressRecord
import io.bhodl.ffi.UtxoRecord
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.assertNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose UI tests for [`DashboardScreen`] driven by [`FakeWalletPort`].
 *
 * The Dashboard is the wallet workspace (UX review 2026-10): header +
 * sub-tabs. Timing detail kept from the button era: the header Sync is
 * disabled while a foreground sync runs. The auto-sync
 * `LaunchedEffect` flips `syncState` to `Loading` on first composition,
 * runs `wallet.syncWithEsplora` + `wallet.balanceSat`, and only then
 * flips back to `Success`. If we `performClick` while the button is
 * still disabled, Compose silently no-ops the input.
 *
 * Every navigation test therefore waits for the auto-sync call to
 * land on the fake before clicking, and clicks again on the next
 * frame so the recomposition has settled.
 */
@OptIn(ExperimentalTestApi::class)
class DashboardScreenTest {

    @BeforeTest
    fun resetSyncCache() {
        // SyncCache is a process-lifetime singleton. Without an
        // explicit reset every test would inherit the cached
        // balance + timestamp from the previous one, which would
        // either let the new test's auto-sync skip (defeating its
        // assertions) or leak a stale balance into the rendered UI.
        SyncCache.clear()
    }

    @AfterTest
    fun teardownStore() {
        // WalletStore.clear() also clears SyncCache, but we keep
        // the explicit BeforeTest above for symmetry / safety in
        // case future code paths populate the cache without going
        // through WalletStore.
        WalletStore.clear()
        SettingsStore.reset()
    }

    @Test
    fun `auto-sync fires on first composition and balance lands`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort(balance = 12_345u)
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                DashboardScreen(
                    onAddressBookClicked = {},
                    onLockClicked = {},
                )
            }
        }
        waitUntil(timeoutMillis = 2_000) { fake.syncCalls.isNotEmpty() }
        waitUntilAtLeastOneExists(hasText("12,345 sat"), timeoutMillis = 2_000)
        onNodeWithText("12,345 sat").assertIsDisplayed()
    }

    // -----------------------------------------------------------------
    // UX review 2026-10 — Sparrow-style sub-tabs replace the old page
    // buttons (Receive / Send / History / Address book / Settings).
    // -----------------------------------------------------------------

    @Test
    fun `sub-tabs switch between Transactions, Send, Receive, Addresses, UTXOs and Wallet settings`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort()
            WalletStore.set(fake)
            setContent { BhodlTheme { DashboardScreen() } }
            waitForReady(fake)
            // Transactions is the default tab.
            waitUntilAtLeastOneExists(hasTestTag(HistoryTags.TABLE))
            onNodeWithTag(DashboardTags.tab(WalletTab.TRANSACTIONS)).assertIsSelected()

            val expected = listOf(
                WalletTab.SEND to SendTags.REVIEW,
                WalletTab.RECEIVE to ReceiveTags.REQUEST_STEP,
                WalletTab.ADDRESSES to WalletTabTags.ADDR_TABLE,
                WalletTab.UTXOS to WalletTabTags.UTXO_TABLE,
                WalletTab.SETTINGS to WalletTabTags.SETTINGS,
                WalletTab.TRANSACTIONS to HistoryTags.TABLE,
            )
            for ((tab, marker) in expected) {
                onNodeWithTag(DashboardTags.tab(tab)).performClick()
                waitUntilAtLeastOneExists(hasTestTag(marker), timeoutMillis = 3_000)
                onNodeWithTag(DashboardTags.tab(tab)).assertIsSelected()
            }
        }

    @Test
    fun `initialTab opens the workspace on the requested tab`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { DashboardScreen(initialTab = WalletTab.RECEIVE) } }
        waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REQUEST_STEP))
        onNodeWithTag(DashboardTags.tab(WalletTab.RECEIVE)).assertIsSelected()
        // Label-first: opening the tab reveals no address yet.
        assertTrue(fake.nextReceiveCalls.isEmpty())
    }

    @Test
    fun `Send tab Review hands the PSBT to onReviewSend`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort(unsignedPsbtBuilder = { "psbt-from-tab" })
        WalletStore.set(fake)
        var reviewed: String? = null
        setContent {
            BhodlTheme {
                DashboardScreen(
                    initialTab = WalletTab.SEND,
                    onReviewSend = { psbt, _, _, _ -> reviewed = psbt },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
        onNodeWithTag(SendTags.ADDRESS).performTextInput(
            "tb1pqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
        )
        onNodeWithTag(SendTags.AMOUNT).performTextInput("1000")
        onNodeWithTag(SendTags.REVIEW).performScrollTo().performClick()
        waitUntil(timeoutMillis = 2_000) { reviewed != null }
        assertEquals("psbt-from-tab", reviewed)
    }

    @Test
    fun `Contacts header action fires onAddressBookClicked`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        var opened = 0
        setContent { BhodlTheme { DashboardScreen(onAddressBookClicked = { opened++ }) } }
        waitForReady(fake)
        onNodeWithTag(DashboardTags.ADDRESS_BOOK).performClick()
        waitUntil(timeoutMillis = 1_000) { opened == 1 }
    }

    @Test
    fun `Lock header action fires onLockClicked`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        var locked = 0
        setContent { BhodlTheme { DashboardScreen(onLockClicked = { locked++ }) } }
        waitForReady(fake)
        onNodeWithTag(DashboardTags.LOGOUT).performClick()
        waitUntil(timeoutMillis = 1_000) { locked == 1 }
    }

    @Test
    fun `UTXOs tab freezes a coin and sends a selection through coin control`() = runDesktopComposeUiTest {
        val a = UtxoRecord("aa".repeat(32) + ":0", 50_000u, "tb1pcoina", false, true, false)
        val b = UtxoRecord("bb".repeat(32) + ":1", 20_000u, "tb1pcoinb", true, true, false)
        val fake = FakeWalletPort().apply { utxos += listOf(a, b) }
        WalletStore.set(fake)
        setContent { BhodlTheme { DashboardScreen(initialTab = WalletTab.UTXOS) } }
        waitUntilAtLeastOneExists(hasTestTag(WalletTabTags.utxoRow(a.outpoint)))

        onNodeWithTag(WalletTabTags.utxoFreeze(b.outpoint)).performClick()
        waitUntil(timeoutMillis = 2_000) { fake.setFrozenCalls.isNotEmpty() }
        assertEquals(b.outpoint to true, fake.setFrozenCalls.single())
        waitUntil(timeoutMillis = 2_000) {
            onAllNodesWithTag(WalletTabTags.utxoFrozen(b.outpoint), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithTag(WalletTabTags.utxoRow(a.outpoint)).performClick()
        onNodeWithTag(WalletTabTags.UTXO_SPEND_SELECTED).performClick()
        // Send tab, coin control on, exactly that coin selected.
        waitUntilAtLeastOneExists(hasTestTag(SendTags.REVIEW))
        waitUntilAtLeastOneExists(hasText("Selected: 1 of 2"), timeoutMillis = 3_000)
        onNodeWithTag(DashboardTags.tab(WalletTab.SEND)).assertIsSelected()
    }

    @Test
    fun `Addresses tab lists used and unused, Show opens Receive with a reuse warning`() =
        runDesktopComposeUiTest {
            val used = AddressRecord(0u, "tb1pusedaddress".padEnd(62, 'u'), false, true, "Salary", "Income")
            val fresh = AddressRecord(1u, "tb1pfreshaddress".padEnd(62, 'f'), false, false, null, null)
            val fake = FakeWalletPort().apply { receiveAddresses += listOf(used, fresh) }
            WalletStore.set(fake)
            setContent { BhodlTheme { DashboardScreen(initialTab = WalletTab.ADDRESSES) } }
            waitUntilAtLeastOneExists(hasTestTag(WalletTabTags.addressRow(used.address)))
            onNodeWithTag(WalletTabTags.addressStatus(used.address), useUnmergedTree = true)
                .assertTextEquals("Used")
            onNodeWithTag(WalletTabTags.addressStatus(fresh.address), useUnmergedTree = true)
                .assertTextEquals("Unused")

            onNodeWithTag(WalletTabTags.addressShow(used.address)).performClick()
            waitUntilAtLeastOneExists(hasTestTag(ReceiveTags.REUSE_WARNING), timeoutMillis = 3_000)
            onNodeWithTag(DashboardTags.tab(WalletTab.RECEIVE)).assertIsSelected()
            // Showing an existing address never reveals a new index.
            assertTrue(fake.nextReceiveCalls.isEmpty())
        }

    @Test
    fun `every sync copies receive labels through the core`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent { BhodlTheme { DashboardScreen() } }
        waitForReady(fake)
        waitUntil(timeoutMillis = 2_000) { fake.applyReceiveLabelsCalls == 1 }
        onNodeWithTag(DashboardTags.SYNC).performClick()
        waitUntil(timeoutMillis = 2_000) { fake.applyReceiveLabelsCalls == 2 }
    }

    @Test
    fun `restore banner is rendered when WalletStore_justRestored is true`() =
        runDesktopComposeUiTest {
            WalletStore.markJustRestored()
            val fake = FakeWalletPort(balance = 0u).apply {
                // Block the auto-sync path so the banner stays up
                // and we can assert its presence deterministically.
                failSync = WalletException.EsploraSync(detail = "blocked-on-purpose")
            }
            WalletStore.set(fake)
            setContent {
                BhodlTheme {
                    DashboardScreen(
                        onAddressBookClicked = {},
                        onLockClicked = {},
                    )
                }
            }
            waitUntilAtLeastOneExists(hasTestTag(DashboardTags.RESTORE_BANNER))
            onNodeWithTag(DashboardTags.RESTORE_BANNER).assertIsDisplayed()
        }

    @Test
    fun `restore banner clears once the first auto-sync succeeds`() = runDesktopComposeUiTest {
        WalletStore.markJustRestored()
        val fake = FakeWalletPort(balance = 12_345u)
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                DashboardScreen(
                    onAddressBookClicked = {},
                    onLockClicked = {},
                )
            }
        }
        // First the banner is rendered, then the sync settles and
        // markRestoreComplete fires from runBackgroundSync.onSuccess
        // -> the banner disappears.
        waitUntil(timeoutMillis = 2_000) { fake.syncCalls.isNotEmpty() }
        waitUntil(timeoutMillis = 2_000) { !WalletStore.justRestored.value }
        assertEquals(false, WalletStore.justRestored.value)
    }

    @Test
    fun `auto-sync passes the SettingsStore Tor proxy to the FFI`() = runDesktopComposeUiTest {
        SettingsStore.setTorEnabled(true)
        SettingsStore.setTorProxyUrl("socks5h://test.tor:9050")
        val fake = FakeWalletPort(balance = 999u)
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                DashboardScreen(
                    onAddressBookClicked = {},
                    onLockClicked = {},
                )
            }
        }
        waitUntil(timeoutMillis = 2_000) { fake.syncCalls.isNotEmpty() }
        assertEquals("socks5h://test.tor:9050", fake.syncProxies.first())
    }

    @Test
    fun `auto-sync passes a null proxy when Tor is disabled`() = runDesktopComposeUiTest {
        // SettingsStore.reset in @BeforeTest already disables Tor.
        val fake = FakeWalletPort(balance = 999u)
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                DashboardScreen(
                    onAddressBookClicked = {},
                    onLockClicked = {},
                )
            }
        }
        waitUntil(timeoutMillis = 2_000) { fake.syncCalls.isNotEmpty() }
        assertNull(fake.syncProxies.first())
    }

    @Test
    fun `auto-sync is skipped when SyncCache is still fresh`() = runDesktopComposeUiTest {
        // Pre-populate the cache as if a sync had just landed. The
        // Dashboard's LaunchedEffect should look at this and skip
        // the auto-sync entirely, sparing mempool.space a redundant
        // round-trip when the user bounces between sub-screens.
        SyncCache.record(balanceSat = 7_777u, atMs = System.currentTimeMillis())
        val fake = FakeWalletPort()
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                DashboardScreen(
                    onAddressBookClicked = {},
                    onLockClicked = {},
                )
            }
        }

        // Wait for the screen to settle — the receive address card
        // proves we got past first composition.
        waitUntilAtLeastOneExists(hasTestTag(DashboardTags.SYNC))
        waitForIdle()
        waitForIdle()

        // Crucially: NO sync call landed on the fake.
        assertTrue(
            fake.syncCalls.isEmpty(),
            "auto-sync should be skipped while cache is fresh, but got ${fake.syncCalls.size} calls",
        )
        // The cached balance is rendered immediately.
        onNodeWithText("7,777 sat").assertIsDisplayed()
    }

    @Test
    fun `cached balance is displayed before any sync happens`() = runDesktopComposeUiTest {
        // Same setup as above but slightly different angle: prove
        // that the optimistic balance is the cache value, NOT a
        // post-sync value. (The fake would return 0 sat by default,
        // so if the displayed value were post-sync we'd see "0 sat".)
        SyncCache.record(balanceSat = 42u, atMs = System.currentTimeMillis())
        val fake = FakeWalletPort(balance = 0u)
        WalletStore.set(fake)

        setContent {
            BhodlTheme {
                DashboardScreen(
                    onAddressBookClicked = {},
                    onLockClicked = {},
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(DashboardTags.SYNC))
        onNodeWithText("42 sat").assertIsDisplayed()
        assertTrue(fake.syncCalls.isEmpty(), "no auto-sync expected with fresh cache")
    }

    @Test
    fun `sync failure renders an ErrorCard, retry triggers another sync`() =
        runDesktopComposeUiTest {
            val fake = FakeWalletPort().apply {
                failSync = WalletException.EsploraSync(
                    detail = "java.net.UnknownHostException: signet.example.com",
                )
            }
            WalletStore.set(fake)

            setContent {
                BhodlTheme {
                    DashboardScreen(
                        onAddressBookClicked = {},
                        onLockClicked = {},
                    )
                }
            }
            // Auto-sync fires on first composition and lands as a
            // failure → ErrorCard appears. We do not waitForReady
            // (no need to wait for buttons to enable; they stay
            // enabled during background sync) — just wait for the
            // error card to materialise.
            waitUntilAtLeastOneExists(hasTestTag(ErrorCardTags.ROOT))
            assertEquals(1, fake.syncCalls.size)

            // Retry button → forces a second sync. The fake is still
            // configured to fail, so the error card stays — we only
            // assert the second sync call happened.
            onNodeWithTag(ErrorCardTags.RETRY).performClick()
            waitUntil(timeoutMillis = 2_000) { fake.syncCalls.size >= 2 }
            assertEquals(2, fake.syncCalls.size)
        }

    @Test
    fun `manual Sync also calls the FFI`() = runDesktopComposeUiTest {
        val fake = FakeWalletPort()
        WalletStore.set(fake)
        setContent {
            BhodlTheme {
                DashboardScreen(
                    onAddressBookClicked = {},
                    onLockClicked = {},
                )
            }
        }
        waitForReady(fake)
        onNodeWithTag(DashboardTags.SYNC).performClick()
        waitUntil(timeoutMillis = 2_000) { fake.syncCalls.size == 2 }
        assertTrue(fake.syncCalls.size == 2)
    }
}

/**
 * Wait for the Dashboard's auto-sync to complete, so the Send /
 * History / Address book / Logout / Sync buttons are no longer in
 * the `Loading` disabled state.
 *
 * `waitUntilAtLeastOneExists` on the SYNC tag only proves the screen
 * composed; we also need to wait for the FFI call to land and the
 * recomposition that re-enables the buttons.
 */
@OptIn(ExperimentalTestApi::class)
private fun androidx.compose.ui.test.ComposeUiTest.waitForReady(fake: FakeWalletPort) {
    waitUntilAtLeastOneExists(hasTestTag(DashboardTags.SYNC))
    waitUntil(timeoutMillis = 2_000) { fake.syncCalls.isNotEmpty() }
    // Two `waitForIdle`s give the LaunchedEffect's onSuccess
    // closure time to flip syncState back to Idle/Success and the
    // Compose runtime time to recompose the buttons as enabled.
    waitForIdle()
    waitForIdle()
}
