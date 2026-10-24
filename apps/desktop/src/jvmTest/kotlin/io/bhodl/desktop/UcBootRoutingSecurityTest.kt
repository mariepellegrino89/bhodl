package io.bhodl.desktop

import io.bhodl.desktop.navigation.Route
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.DevicePinSession
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.RegistryHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Boot routing, post-unlock / legacy-cleanup destinations, the idle-lock
 * watcher loop and the lock routine — the pure seams extracted from
 * `App.kt` (docs/use-cases.md §1 and §5).
 */
class UcBootRoutingSecurityTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-boot-").toFile()
        SettingsStore.reset()
    }

    @AfterTest
    fun tearDown() {
        WalletStore.clear()
        RegistryStore.clear()
        SettingsStore.reset()
        dir.deleteRecursively()
    }

    private fun view() = WalletPaths.from(dir)

    @Test
    fun `SEC-001 empty data dir boots to Welcome`() {
        assertEquals(Route.Welcome, bootGuardTarget(view()))
        assertEquals(Route.Welcome, bootGuardTarget(WalletPaths.from(File(dir, "missing"))))
    }

    @Test
    fun `SEC-002 a registry on disk boots to the device PIN gate`() {
        File(dir, WalletPaths.REGISTRY_FILE_NAME).writeText("x")
        assertEquals(Route.DevicePinUnlock, bootGuardTarget(view()))
        // Seed-era leftovers next to a registry still go through the PIN first.
        File(dir, WalletPaths.SALT_FILE_NAME).writeText("x")
        assertEquals(Route.DevicePinUnlock, bootGuardTarget(view()))
    }

    @Test
    fun `SEC-003 only seed-era data boots to LegacyCleanup without a PIN`() {
        File(dir, WalletPaths.WALLET_DB_FILE_NAME).writeText("x")
        assertEquals(Route.LegacyCleanup, bootGuardTarget(view()))
        File(dir, WalletPaths.WALLET_DB_FILE_NAME).delete()
        File(dir, "${WalletPaths.WALLETS_SUBDIR}/abc").mkdirs()
        assertEquals(Route.LegacyCleanup, bootGuardTarget(view()))
    }

    @Test
    fun `SEC-003 watch state alone is not seed-era data`() {
        File(dir, "${WalletPaths.WATCH_STATE_SUBDIR}/w.sqlite").apply { parentFile.mkdirs() }.writeText("x")
        assertEquals(Route.Welcome, bootGuardTarget(view()))
    }

    @Test
    fun `SEC-004 a re-mount with an unlocked session never bounces to the PIN gate`() {
        val boot = { error("the boot guard must not run while unlocked") }
        assertEquals(Route.Dashboard, startRoute(walletOpen = true, registryOpen = true, boot = boot))
        assertEquals(Route.Tracking(), startRoute(walletOpen = false, registryOpen = true, boot = boot))
        assertEquals(Route.Welcome, startRoute(walletOpen = false, registryOpen = false) { Route.Welcome })
        // A wallet without a registry (not reachable today) falls back to the boot guard.
        assertEquals(Route.DevicePinUnlock, startRoute(walletOpen = true, registryOpen = false) { Route.DevicePinUnlock })
    }

    @Test
    fun `SEC-009 unlock goes to LegacyCleanup while signing data survives`() {
        assertFalse(legacyCleanupNeeded(view()) { 0u })
        assertEquals(Route.Tracking(), postUnlockTarget(legacyCleanupNeeded(view()) { 0u }))

        assertTrue(legacyCleanupNeeded(view()) { 2u }, "signing rows in the registry")
        assertEquals(Route.LegacyCleanup, postUnlockTarget(legacyCleanupNeeded(view()) { 2u }))

        File(dir, WalletPaths.KDF_FILE_NAME).writeText("{}")
        assertTrue(legacyCleanupNeeded(view()) { null }, "key files on disk")
    }

    @Test
    fun `SEC-009 a registry error while counting legacy rows does not block the tracker`() {
        assertFalse(legacyCleanupNeeded(view()) { error("registry closed") })
    }

    @Test
    fun `SEC-012 SEC-014 LegacyCleanup continues to the tracker or, without registry, to Welcome`() {
        assertEquals(Route.Tracking(), legacyCleanupDoneTarget(registryOpen = true))
        assertEquals(Route.Welcome, legacyCleanupDoneTarget(registryOpen = false))
    }

    @Test
    fun `SEC-071 a wallet route without an open wallet falls back to the tracker HOME`() {
        assertEquals(TrackerSection.HOME, walletWorkspaceRedirect(walletOpen = false))
        assertNull(walletWorkspaceRedirect(walletOpen = true))
    }

    @Test
    fun `SEC-066 the watcher sleeps one interval before each check and locks only when due`() {
        val sleeps = mutableListOf<Long>()
        val events = mutableListOf<String>()
        var checks = 0
        runCatching {
            runBlocking {
                runIdleLockWatcher(
                    intervalMs = IDLE_LOCK_CHECK_INTERVAL_MS,
                    shouldLock = { checks++; events += "check"; checks >= 3 },
                    sleep = { ms ->
                        sleeps += ms
                        events += "sleep"
                        if (sleeps.size > 4) throw CancellationException("stop")
                    },
                    onLock = { events += "lock" },
                )
            }
        }
        assertEquals(List(5) { IDLE_LOCK_CHECK_INTERVAL_MS }, sleeps)
        assertEquals(
            listOf("sleep", "check", "sleep", "check", "sleep", "check", "lock", "sleep", "check", "lock", "sleep"),
            events,
        )
        assertEquals(10_000L, IDLE_LOCK_CHECK_INTERVAL_MS)
    }

    @Test
    fun `SEC-066 nothing open never makes the watcher lock`() {
        RegistryStore.clear()
        DevicePinSession.clear()
        assertFalse(idleLockDue())
    }

    @Test
    fun `SEC-069 lockSession releases wallet, registry and session and re-arms start hidden`() {
        val registry = RegistryHandle.createOrLoad(dir.absolutePath, "482916")
        RegistryStore.set(registry)
        DevicePinSession.markUnlocked()
        val wallet = FakeWalletPort()
        WalletStore.set(wallet)
        SettingsStore.setPrivacyAutoHide(true)
        SettingsStore.setBalancesHidden(false)

        lockSession()

        assertNull(WalletStore.current())
        assertEquals(1, wallet.closeCalls)
        assertNull(RegistryStore.current())
        assertFalse(DevicePinSession.isUnlocked())
        assertTrue(SettingsStore.balancesHidden.value, "start hidden applies after every lock")
        // The registry file was released: the wipe path can delete it.
        assertTrue(File(dir, WalletPaths.REGISTRY_FILE_NAME).delete())
    }

    @Test
    fun `SEC-069 a lock never reveals amounts hidden by hand`() {
        SettingsStore.setPrivacyAutoHide(false)
        SettingsStore.setBalancesHidden(true)
        lockSession()
        assertTrue(SettingsStore.balancesHidden.value)
    }
}
