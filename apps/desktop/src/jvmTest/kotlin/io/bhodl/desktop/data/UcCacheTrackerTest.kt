package io.bhodl.desktop.data

import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshots.Snapshot
import io.bhodl.desktop.domain.SEND_TO_SELF
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.Network
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.TrackingBillFfi
import io.bhodl.ffi.WalletRecordFfi
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Area C — [TrackerCache.refresh] / coldLoad / reset against a REAL
 * registry (SQLCipher in a temp dir). Chain data comes from the
 * [TrackerCache.watchEventsSource] seam (categories and kinds read back
 * from the registry, like the FFI does); no network except an
 * unreachable Esplora endpoint for the sync-failure paths.
 */
class UcCacheTrackerTest {

    private lateinit var dir: File
    private lateinit var registry: RegistryHandle
    private val defaultSource = TrackerCache.watchEventsSource
    private val defaultDataDir = TrackerCache.dataDirProvider
    private val defaultInterval = TrackerCache.backgroundRefreshIntervalMs

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        TrackerCache.reset()
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-cache-").toFile()
        registry = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        RegistryStore.set(registry)
        TrackerCache.dataDirProvider = { File(dir, "marker") }
        TrackerCache.watchEventsSource = { emptyList() }
    }

    @AfterTest
    fun tearDown() {
        TrackerCache.watchEventsSource = defaultSource
        TrackerCache.dataDirProvider = defaultDataDir
        TrackerCache.backgroundRefreshIntervalMs = defaultInterval
        RegistryStore.clear()
        SettingsStore.reset()
        dir.deleteRecursively()
    }

    private fun addWallet(label: String, address: String): WalletRecordFfi =
        registry.addWatchAddresses(label, listOf(address), Network.SIGNET)

    private fun leg(
        wallet: String,
        txid: String,
        direction: PaymentDirection,
        amount: ULong,
        ts: Long? = 1_700_000_000L,
        fee: ULong? = null,
    ) = PaymentEvent(wallet, txid, PaymentKind.ON_CHAIN, direction, amount, fee, ts?.toULong(), null, null, null)

    /** Fake chain: the given legs, with category / kind merged from the registry like the FFI. */
    private fun chain(vararg legs: PaymentEvent) {
        TrackerCache.watchEventsSource = { reg ->
            legs.map {
                it.copy(
                    category = reg.getEventCategory(it.walletId, it.txidOrPaymentId),
                    cryptoEventKind = reg.getEventCryptoKind(it.walletId, it.txidOrPaymentId),
                )
            }
        }
    }

    private fun refresh(sync: Boolean = false) = runBlocking { TrackerCache.refresh(syncEsplora = sync) }

    private fun unreachableEsplora() = SettingsStore.setEsploraUrl(Network.SIGNET, "http://127.0.0.1:1")

    // ─── A. refresh / cold load / reset ─────────────────────────────

    @Test
    fun `TRK-003 refresh without an open registry reports it and publishes nothing`() {
        RegistryStore.clear()
        refresh()
        assertEquals("Registry not open.", TrackerCache.lastError.value)
        assertFalse(TrackerCache.hasLoadedOnce.value)
        assertFalse(TrackerCache.refreshing.value)
    }

    @Test
    fun `TRK-002 TRK-009 registry-only refresh publishes registry data without stamping a sync`() {
        val w = addWallet("Cold", SIGNET_A)
        registry.addBill("Rent", 1_000UL, 5u, null)
        registry.setBudget("Food", 500UL, "monthly")
        chain(leg(w.id, "t1", PaymentDirection.INCOMING, 10_000UL))
        refresh(sync = false)
        assertTrue(TrackerCache.hasLoadedOnce.value)
        assertNull(TrackerCache.lastSyncUnix.value)
        assertNull(TrackerCache.lastError.value)
        assertEquals(listOf(w.id), TrackerCache.wallets.value.map { it.id })
        assertEquals(0UL, TrackerCache.watchBalances.value[w.id])
        assertEquals(listOf("t1"), TrackerCache.events.value.map { it.txidOrPaymentId })
        assertEquals(listOf("Rent"), TrackerCache.bills.value.map { it.name })
        assertEquals(listOf("Food"), TrackerCache.budgets.value.map { it.category })
        assertTrue(TrackerCache.staleWalletIds.value.isEmpty())
        assertFalse(TrackerCache.refreshing.value)
    }

    @Test
    fun `TRK-008 a network refresh with no watch wallets counts as synced`() {
        refresh(sync = true)
        assertNotNull(TrackerCache.lastSyncUnix.value)
        assertNull(TrackerCache.lastError.value)
    }

    @Test
    fun `TRK-001 TRK-004 a failed Esplora sync marks the wallet stale and keeps cached data`() {
        unreachableEsplora()
        val w = addWallet("Cold", SIGNET_A)
        chain(leg(w.id, "t1", PaymentDirection.INCOMING, 10_000UL))
        refresh(sync = true)
        assertEquals(setOf(w.id), TrackerCache.staleWalletIds.value)
        val err = TrackerCache.lastError.value
        assertNotNull(err)
        assertTrue(err.startsWith("Sync failed for 1 wallet(s) — showing cached data."), err)
        assertNull(TrackerCache.lastSyncUnix.value, "no successful sync, no stamp")
        // Cached state still published.
        assertEquals(listOf(w.id), TrackerCache.wallets.value.map { it.id })
        assertEquals(0UL, TrackerCache.watchBalances.value[w.id])
        assertEquals(listOf("t1"), TrackerCache.events.value.map { it.txidOrPaymentId })
        assertTrue(TrackerCache.perWalletTxs.value.containsKey(w.id))
    }

    @Test
    fun `TRK-004 the message counts every failed wallet`() {
        unreachableEsplora()
        addWallet("A", SIGNET_A)
        addWallet("B", SIGNET_B)
        refresh(sync = true)
        assertTrue(TrackerCache.lastError.value!!.startsWith("Sync failed for 2 wallet(s)"))
        assertEquals(2, TrackerCache.staleWalletIds.value.size)
    }

    @Test
    fun `TRK-007 a registry-only refresh keeps the stale verdict and sync message of existing wallets`() {
        unreachableEsplora()
        val a = addWallet("A", SIGNET_A)
        val b = addWallet("B", SIGNET_B)
        refresh(sync = true)
        val message = TrackerCache.lastError.value
        refresh(sync = false)
        assertEquals(setOf(a.id, b.id), TrackerCache.staleWalletIds.value)
        assertEquals(message, TrackerCache.lastError.value)

        registry.removeWallet(b.id)
        refresh(sync = false)
        assertEquals(setOf(a.id), TrackerCache.staleWalletIds.value)
    }

    @Test
    fun `TRK-006 a wallet whose watch state cannot be opened is stale and the rest continues`() {
        val good = addWallet("Good", SIGNET_A)
        val broken = addWallet("Broken", SIGNET_B)
        File(dir, "watch-state/${broken.id}.sqlite").writeBytes("not a database".toByteArray())
        refresh(sync = false)
        assertEquals(setOf(broken.id), TrackerCache.staleWalletIds.value)
        assertEquals(0UL, TrackerCache.watchBalances.value[good.id])
        assertNull(TrackerCache.watchBalances.value[broken.id])
        assertEquals(2, TrackerCache.wallets.value.size)
    }

    @Test
    fun `TRK-010 an unexpected failure surfaces in lastError and clears refreshing`() {
        registry.destroy()
        refresh(sync = false)
        assertNotNull(TrackerCache.lastError.value)
        assertFalse(TrackerCache.refreshing.value)
        assertFalse(TrackerCache.hasLoadedOnce.value)
    }

    @Test
    fun `TRK-011 cold load runs once even when several screens kick it`() {
        val calls = AtomicInteger()
        TrackerCache.watchEventsSource = { calls.incrementAndGet(); emptyList() }
        runBlocking {
            TrackerCache.coldLoad()
            TrackerCache.coldLoad()
        }
        // One registry-only pass + one network pass, then nothing.
        assertEquals(2, calls.get())
        assertTrue(TrackerCache.hasLoadedOnce.value)
        assertNotNull(TrackerCache.lastSyncUnix.value)
    }

    @Test
    fun `TRK-011 cold load is skipped once the cache already loaded`() {
        val calls = AtomicInteger()
        TrackerCache.watchEventsSource = { calls.incrementAndGet(); emptyList() }
        refresh()
        runBlocking { TrackerCache.coldLoad() }
        assertEquals(1, calls.get())
    }

    @Test
    fun `TRK-012 reset empties every state and re-arms the cold load`() {
        unreachableEsplora()
        val w = addWallet("Cold", SIGNET_A)
        registry.addBill("Rent", 1_000UL, 5u, null)
        chain(leg(w.id, "t1", PaymentDirection.INCOMING, 10_000UL))
        runBlocking { TrackerCache.coldLoad() }
        TrackerCache.seedForTest(
            bills = listOf(TrackingBillFfi(1, "x", 1UL, 1u, null, null, 0)),
            staleWalletIds = setOf("s"),
            categories = listOf("Food"),
        )
        TrackerCache.reset()
        with(TrackerCache) {
            assertTrue(wallets.value.isEmpty() && watchBalances.value.isEmpty() && events.value.isEmpty())
            assertTrue(bills.value.isEmpty() && budgets.value.isEmpty() && categories.value.isEmpty())
            assertTrue(views.value.isEmpty() && rules.value.isEmpty() && recurringCandidates.value.isEmpty())
            assertTrue(staleWalletIds.value.isEmpty() && eventLegs.value.isEmpty() && perWalletTxs.value.isEmpty())
            assertTrue(goals.value.isEmpty() && liabilities.value.isEmpty() && investments.value.isEmpty())
            assertTrue(manualAssets.value.isEmpty() && costBasisInputs.value.isEmpty())
            assertEquals(0UL, totalAssetSat.value)
            assertNull(lastSyncUnix.value)
            assertNull(lastError.value)
            assertFalse(hasLoadedOnce.value)
            assertFalse(refreshing.value)
        }
        // Cold load armed again.
        val calls = AtomicInteger()
        TrackerCache.watchEventsSource = { calls.incrementAndGet(); emptyList() }
        runBlocking { TrackerCache.coldLoad() }
        assertEquals(2, calls.get())
    }

    // ─── GAP-019 — lock / wipe during a running refresh ─────────────

    private fun refreshBlockedUntil(release: CountDownLatch): Pair<Thread, CountDownLatch> {
        val entered = CountDownLatch(1)
        TrackerCache.watchEventsSource = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            emptyList()
        }
        val t = thread { runBlocking { TrackerCache.refresh(syncEsplora = false) } }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        return t to entered
    }

    @Test
    fun `GAP-019 a lock during a refresh does not repopulate the cache with the closed session`() {
        addWallet("Cold", SIGNET_A)
        registry.addBill("Rent", 1_000UL, 5u, null)
        val release = CountDownLatch(1)
        val (t, _) = refreshBlockedUntil(release)
        RegistryStore.clear()
        release.countDown()
        t.join(10_000)
        assertTrue(TrackerCache.wallets.value.isEmpty())
        assertTrue(TrackerCache.bills.value.isEmpty())
        assertFalse(TrackerCache.hasLoadedOnce.value)
        assertNull(TrackerCache.lastError.value)
        assertFalse(TrackerCache.refreshing.value)
    }

    @Test
    fun `GAP-019 the next unlock still runs its cold load on the new registry`() {
        addWallet("Old", SIGNET_A)
        val release = CountDownLatch(1)
        val (t, _) = refreshBlockedUntil(release)
        // Lock + unlock of a different data dir while the old pass is stuck.
        TrackerCache.reset()
        val dir2 = kotlin.io.path.createTempDirectory("bhodl-uc-cache2-").toFile()
        try {
            val second = RegistryHandle.createOrLoad(dir2.absolutePath, "482916")
            second.addBill("New bill", 2_000UL, 9u, null)
            RegistryStore.set(second)
            release.countDown()
            t.join(10_000)
            assertFalse(TrackerCache.hasLoadedOnce.value)
            assertTrue(TrackerCache.wallets.value.isEmpty())

            TrackerCache.watchEventsSource = { emptyList() }
            runBlocking { TrackerCache.coldLoad() }
            assertTrue(TrackerCache.hasLoadedOnce.value)
            assertEquals(listOf("New bill"), TrackerCache.bills.value.map { it.name })
            assertTrue(TrackerCache.wallets.value.isEmpty())
        } finally {
            RegistryStore.clear()
            dir2.deleteRecursively()
        }
    }

    // ─── B. legs, dedupe, categories ────────────────────────────────

    @Test
    fun `TRK-022 TRK-023 refresh dedupes by txid but exposes every leg`() {
        val a = addWallet("A", SIGNET_A)
        val b = addWallet("B", SIGNET_B)
        registry.setEventCategory(b.id, "t", "Rent")
        chain(
            leg(a.id, "t", PaymentDirection.OUTGOING, 10_500UL, fee = 500UL),
            leg(b.id, "t", PaymentDirection.INCOMING, 7_000UL),
            leg(a.id, "u", PaymentDirection.INCOMING, 1UL, ts = null),
        )
        refresh()
        val events = TrackerCache.events.value
        assertEquals(listOf("u", "t"), events.map { it.txidOrPaymentId })
        assertEquals(b.id, events[1].walletId, "the categorised leg represents the txid")
        assertEquals("Rent", events[1].category)
        assertEquals(3, TrackerCache.eventLegs.value.size)
    }

    @Test
    fun `TRK-035 a category is written on the representative leg only`() {
        val a = addWallet("A", SIGNET_A)
        val b = addWallet("B", SIGNET_B)
        chain(
            leg(a.id, "t", PaymentDirection.OUTGOING, 10_500UL, fee = 500UL),
            leg(b.id, "t", PaymentDirection.INCOMING, 7_000UL),
        )
        refresh()
        val rep = TrackerCache.events.value.single()
        assertEquals(a.id, rep.walletId, "the fee leg represents an uncategorised txid")
        io.bhodl.desktop.screens.saveEventCategory(registry, rep, "  Rent  ")
        assertEquals("Rent", registry.getEventCategory(a.id, "t"))
        assertNull(registry.getEventCategory(b.id, "t"))
        refresh()
        assertEquals("Rent", TrackerCache.events.value.single().category)
    }

    @Test
    fun `TRK-034 saving a blank category clears the row`() {
        val a = addWallet("A", SIGNET_A)
        registry.setEventCategory(a.id, "t", "Food")
        io.bhodl.desktop.screens.saveEventCategory(registry, leg(a.id, "t", PaymentDirection.INCOMING, 1UL), "   ")
        assertNull(registry.getEventCategory(a.id, "t"))
    }

    // ─── D. send_to_self ────────────────────────────────────────────

    @Test
    fun `TRK-045 refresh tags an internal transfer through the AUTO path and on the representative`() {
        val a = addWallet("A", SIGNET_A)
        val b = addWallet("B", SIGNET_B)
        registry.setEventCategory(b.id, "t", "Savings")
        chain(
            leg(a.id, "t", PaymentDirection.OUTGOING, 100_500UL, fee = 500UL),
            leg(b.id, "t", PaymentDirection.INCOMING, 100_000UL),
        )
        refresh()
        assertEquals(SEND_TO_SELF, registry.getEventCryptoKind(a.id, "t"))
        assertEquals(SEND_TO_SELF, registry.getEventCryptoKind(b.id, "t"))
        val rep = TrackerCache.events.value.single()
        assertEquals(b.id, rep.walletId)
        assertEquals(SEND_TO_SELF, rep.cryptoEventKind)
    }

    @Test
    fun `TRK-045 a user-pinned regular is never re-tagged`() {
        val a = addWallet("A", SIGNET_A)
        val b = addWallet("B", SIGNET_B)
        registry.setEventCryptoKind(a.id, "t", "regular")
        chain(
            leg(a.id, "t", PaymentDirection.OUTGOING, 100_500UL, fee = 500UL),
            leg(b.id, "t", PaymentDirection.INCOMING, 100_000UL),
        )
        refresh()
        assertEquals("regular", registry.getEventCryptoKind(a.id, "t"))
    }

    @Test
    fun `TRK-046 the one-time cleanup resets artefact tags, writes its marker and never runs again`() {
        val a = addWallet("A", SIGNET_A)
        registry.setEventCryptoKindAuto(a.id, "solo", SEND_TO_SELF)
        chain(leg(a.id, "solo", PaymentDirection.OUTGOING, 1_000UL, fee = 100UL), leg(a.id, "solo2", PaymentDirection.OUTGOING, 1UL))
        refresh()
        assertNull(registry.getEventCryptoKind(a.id, "solo"))
        assertTrue(File(dir, "marker/${TrackerCache.SEND_TO_SELF_CLEANUP_MARKER}").exists())

        registry.setEventCryptoKindAuto(a.id, "solo2", SEND_TO_SELF)
        refresh()
        assertEquals(SEND_TO_SELF, registry.getEventCryptoKind(a.id, "solo2"), "cleanup runs once per data dir")
    }

    // ─── E. rules at import ─────────────────────────────────────────

    @Test
    fun `TRK-062 enabled rules fill only uncategorised events and the manual category wins`() {
        val a = addWallet("Exchange", SIGNET_A)
        registry.addRule("Exchange buys", "payee", "equals", "Exchange", "category", "DCA", 0, true)
        registry.addRule("Off", "payee", "equals", "Exchange", "category", "Never", 10, false)
        registry.setEventCategory(a.id, "manual", "Gift")
        chain(
            leg(a.id, "auto", PaymentDirection.INCOMING, 5_000UL),
            leg(a.id, "manual", PaymentDirection.INCOMING, 6_000UL),
        )
        refresh()
        assertEquals("DCA", registry.getEventCategory(a.id, "auto"))
        assertEquals("Gift", registry.getEventCategory(a.id, "manual"))
        assertEquals(
            mapOf("auto" to "DCA", "manual" to "Gift"),
            TrackerCache.events.value.associate { it.txidOrPaymentId to it.category },
        )
        assertEquals(2, TrackerCache.rules.value.size)
    }

    @Test
    fun `TRK-063 disabling a rule keeps the categories it already assigned`() {
        val a = addWallet("Exchange", SIGNET_A)
        val id = registry.addRule("R", "payee", "equals", "Exchange", "category", "DCA", 0, true)
        chain(leg(a.id, "auto", PaymentDirection.INCOMING, 5_000UL))
        refresh()
        registry.updateRule(id, "R", "payee", "equals", "Exchange", "category", "DCA", 0, false)
        refresh()
        assertEquals("DCA", registry.getEventCategory(a.id, "auto"))
    }

    @Test
    fun `TRK-064 reapply categorises uncategorised events with enabled rules only`() {
        val a = addWallet("Exchange", SIGNET_A)
        val b = addWallet("B", SIGNET_B)
        registry.addRule("R", "payee", "equals", "Exchange", "category", "DCA", 0, true)
        registry.addRule("Off", "description", "contains", "t", "category", "Never", 9, false)
        val events = listOf(
            leg(a.id, "t1", PaymentDirection.INCOMING, 1UL),
            leg(a.id, "t2", PaymentDirection.INCOMING, 1UL).apply { category = "Keep" },
            leg(b.id, "t3", PaymentDirection.INCOMING, 1UL),
        )
        val written = io.bhodl.desktop.screens.reapplyRules(
            registry, events, registry.listRules(), mapOf(a.id to "Exchange"),
        )
        assertEquals(1, written)
        assertEquals("DCA", registry.getEventCategory(a.id, "t1"))
        assertNull(registry.getEventCategory(a.id, "t2"))
        assertNull(registry.getEventCategory(b.id, "t3"))
    }

    // ─── G. recurring ───────────────────────────────────────────────

    private fun monthlyGym(wallet: String): Array<PaymentEvent> {
        val now = System.currentTimeMillis() / 1000L
        return (1..4).map { i ->
            leg(wallet, "gym$i", PaymentDirection.OUTGOING, 20_000UL, ts = now - (5 - i) * 30L * 86_400L)
        }.toTypedArray()
    }

    @Test
    fun `TRK-079 TRK-082 recurring candidates come from the merged stream minus dismissed keys`() {
        val a = addWallet("A", SIGNET_A)
        for (i in 1..4) registry.setEventCategory(a.id, "gym$i", "Gym")
        chain(*monthlyGym(a.id))
        refresh()
        val c = TrackerCache.recurringCandidates.value.single()
        assertEquals("Gym", c.displayName)
        registry.dismissRecurring(c.payeeKey)
        refresh()
        assertTrue(TrackerCache.recurringCandidates.value.isEmpty())
        assertEquals(listOf(c.payeeKey), registry.listDismissedRecurring())
    }

    @Test
    fun `TRK-083 accepting a suggestion creates the bill on the next due day and dismisses it`() {
        val a = addWallet("A", SIGNET_A)
        for (i in 1..4) registry.setEventCategory(a.id, "gym$i", "Gym")
        chain(*monthlyGym(a.id))
        refresh()
        val c = TrackerCache.recurringCandidates.value.single()
        val zone = java.time.ZoneOffset.UTC
        val id = io.bhodl.desktop.screens.acceptRecurringAsBill(registry, c, zone)
        val bill = registry.listBills().single()
        assertEquals(id, bill.id)
        assertEquals("Gym", bill.name)
        assertEquals("Gym", bill.category, "the bill counts in the group's budget category")
        assertEquals(c.avgAmountSat, bill.amountSat)
        val expectedDay = java.time.Instant.ofEpochSecond(c.nextEstimatedUnix).atZone(zone).dayOfMonth
        assertEquals(expectedDay.toUInt(), bill.dueDay)
        assertEquals(listOf(c.payeeKey), registry.listDismissedRecurring())
        refresh()
        assertTrue(TrackerCache.recurringCandidates.value.isEmpty())
    }

    // ─── A. background loops ────────────────────────────────────────

    @Test
    fun `TRK-013 after start a data version bump triggers a registry-only refresh`() {
        val calls = AtomicInteger()
        TrackerCache.watchEventsSource = { calls.incrementAndGet(); emptyList() }
        refresh()
        val afterFirst = calls.get()
        TrackerCache.backgroundRefreshIntervalMs = 60L * 60L * 1000L
        TrackerCache.start()
        try {
            TrackerCache.start() // idempotent
            Thread.sleep(300)
            RegistryStore.bumpDataVersion()
            Snapshot.sendApplyNotifications()
            val deadline = System.currentTimeMillis() + 10_000
            while (calls.get() == afterFirst && System.currentTimeMillis() < deadline) {
                Snapshot.sendApplyNotifications()
                Thread.sleep(50)
            }
            assertTrue(calls.get() > afterFirst, "no refresh after a data version bump")
        } finally {
            TrackerCache.stop()
        }
    }

    private companion object {
        const val SIGNET_A = "tb1p8wpt9v4frpf3tkn0srd97pksgsxc5hs52lafxwru9kgeephvs7rqlqt9zj"
        const val SIGNET_B = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
    }

    @Test
    fun `a refresh cancelled by its caller leaves no error`() {
        addWallet("Main", "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        TrackerCache.watchEventsSource = { entered.countDown(); release.await(); emptyList() }
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { TrackerCache.refresh(syncEsplora = false) }
            entered.await()
            // What happens when the screen that started the pass closes.
            job.cancel(kotlinx.coroutines.CancellationException("The coroutine scope left the composition"))
            release.countDown()
            job.join()
        }
        assertNull(TrackerCache.lastError.value)
        assertEquals(false, TrackerCache.refreshing.value)
    }

    // Schema v8 — income and spending outside the wallets.
    @Test
    fun `off-wallet entries join the events with their category and can pay a bill`() {
        addWallet("Main", SIGNET_A)
        val bill = registry.addBill("Luce e gas", 140_000UL, 15u, null)
        registry.setBillCategory(bill, "Bollette")
        val state = io.bhodl.desktop.screens.ManualDialogState(
            forBill = registry.listBills().single(),
        )
        val draft = io.bhodl.desktop.screens.ManualTxnDraft(
            direction = ManualTxns.DIRECTION_OUT,
            amountSat = 61_000UL,
            amountFiat = 42.5,
            currency = "EUR",
            occurredUnix = 1_760_000_000L,
            accountRef = null,
            category = "Bollette",
            note = "Bolletta",
        )
        io.bhodl.desktop.screens.saveManualTxn(registry, state, draft)
        refresh()

        val ev = TrackerCache.events.value.single()
        assertTrue(ManualTxns.isManual(ev.walletId))
        assertEquals(io.bhodl.ffi.manualTxnWalletId(null), ev.walletId)
        assertEquals(PaymentDirection.OUTGOING, ev.direction)
        assertEquals(61_000UL, ev.amountSat)
        assertEquals("Bollette", ev.category, "counts in the Bollette budget")
        val paid = TrackerCache.bills.value.single()
        assertEquals("${ev.walletId}|${ev.txidOrPaymentId}", paid.paidRef)
        assertEquals(61_000UL, paid.lastPaidAmountSat)

        // Deleting the entry takes it out of the events and unpays the bill.
        registry.deleteManualTxn(ManualTxns.idOf(ev)!!)
        refresh()
        assertTrue(TrackerCache.events.value.isEmpty())
        assertNull(TrackerCache.bills.value.single().lastPaidUnix)
    }
}
