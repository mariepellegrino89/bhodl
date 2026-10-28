package io.bhodl.desktop.wizard

import io.bhodl.desktop.data.BackfillRunner
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.NetWorthHistoryClient
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.ffi.MigrationReportFfi
import io.bhodl.ffi.NwSnapshotFfi
import io.bhodl.ffi.PaymentEventLiteFfi
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.TableBackfillStatsFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `RegistryStore.clear()` — what a lock / wipe must release
 * (docs/use-cases.md SEC-070, GAP-015).
 */
class UcRegistryStoreSecurityTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-registry-store-").toFile()
        NetWorthHistoryClient.clearCacheForTest()
        PriceCache.clearSessionCaches()
    }

    @AfterTest
    fun tearDown() {
        RegistryStore.clear()
        BackfillRunner.cancel()
        BackfillRunner.resetTargetForTest()
        NetWorthHistoryClient.resetSourceForTest()
        NetWorthHistoryClient.clearCacheForTest()
        PriceCache.clearSessionCaches()
        dir.deleteRecursively()
    }

    @Test
    fun `SEC-070 clear destroys the handle, closes the session and releases the file`() {
        val handle = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        RegistryStore.set(handle)
        DevicePinSession.markUnlocked()
        RegistryStore.bumpDataVersion()

        RegistryStore.clear()

        assertNull(RegistryStore.current())
        assertFalse(DevicePinSession.isUnlocked())
        assertEquals(0L, RegistryStore.dataVersion.value)
        assertTrue(runCatching { handle.listWallets() }.isFailure, "a destroyed handle refuses every call")
        assertTrue(File(dir, WalletPaths.REGISTRY_FILE_NAME).delete(), "the SQLCipher file lock is released")
    }

    @Test
    fun `SEC-070 clear cancels an in-flight backfill without publishing a result`() {
        val handle = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        RegistryStore.set(handle)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        BackfillRunner.setTargetForTest { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            finished.countDown()
            report()
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            BackfillRunner.runIfNeeded(scope, registryProvider = { handle }, proxyProvider = { null })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertIs<BackfillRunner.State.Running>(BackfillRunner.state.value)

            RegistryStore.clear()
            assertIs<BackfillRunner.State.Idle>(BackfillRunner.state.value)

            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertIs<BackfillRunner.State.Idle>(BackfillRunner.state.value, "no Done for a closed session")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `GAP-015 clear forgets the net-worth history and per-currency rate caches`() {
        var calls = 0
        NetWorthHistoryClient.setSourceForTest(object : NetWorthHistoryClient.NwHistorySource {
            override fun netWorthHistory(
                fromUnix: Long,
                toUnix: Long,
                currency: String,
                events: List<PaymentEventLiteFfi>,
            ): List<NwSnapshotFfi> {
                calls++
                return listOf(NwSnapshotFfi(fromUnix, 1_000L, 1_000L, 0L, 0L, 0L, 0.4, 40_000.0))
            }
        })
        runBlocking { NetWorthHistoryClient.netWorthHistory(0L, 86_400L * 30, FiatCurrency.EUR, dataVersion = 0L) }
        assertEquals(1, NetWorthHistoryClient.cacheSizeForTest())
        PriceCache.seedCurrencyRatesForTest(mapOf("CHF" to 90_000.0))
        assertTrue(PriceCache.currencyRates.value.isNotEmpty())

        RegistryStore.clear()

        assertEquals(0, NetWorthHistoryClient.cacheSizeForTest())
        assertTrue(PriceCache.currencyRates.value.isEmpty())
        runBlocking { NetWorthHistoryClient.netWorthHistory(0L, 86_400L * 30, FiatCurrency.EUR, dataVersion = 0L) }
        assertEquals(2, calls, "the next session recomputes instead of reading the old one")
    }

    private fun report(): MigrationReportFfi {
        val s = TableBackfillStatsFfi(considered = 0u, fetched = 0u, alreadyDone = 0u, failed = 0u)
        return MigrationReportFfi(investmentLots = s, investmentSells = s, lotDisposals = s, manualAssets = s)
    }
}
