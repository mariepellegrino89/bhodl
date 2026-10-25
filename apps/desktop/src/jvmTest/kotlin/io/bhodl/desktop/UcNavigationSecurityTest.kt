package io.bhodl.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.bhodl.desktop.navigation.Route
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.Tab
import io.bhodl.desktop.wallet.FakeWalletPort
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Back-stack behaviour of the navigation helpers in `App.kt`, on a real
 * NavHost with placeholder destinations (docs/use-cases.md §5, §6, §16).
 */
@OptIn(ExperimentalTestApi::class)
class UcNavigationSecurityTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-nav-").toFile()
        SettingsStore.reset()
    }

    @AfterTest
    fun tearDown() {
        WalletStore.clear()
        RegistryStore.clear()
        SettingsStore.reset()
        dir.deleteRecursively()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.host(start: Route): NavHostController {
        lateinit var nc: NavHostController
        setContent {
            nc = rememberNavController()
            NavHost(nc, startDestination = start) {
                composable<Route.Welcome> { Text("welcome") }
                composable<Route.DevicePinUnlock> { Text("unlock") }
                composable<Route.Tracking> { Text("tracking") }
                composable<Route.Settings> { Text("settings") }
                composable<Route.Dashboard> { Text("dashboard") }
                composable<Route.SecurityHub> { Text("hub") }
                composable<Route.OpenWallet> { Text("open") }
            }
        }
        waitForIdle()
        return nc
    }

    private fun NavBackStackEntry.describe(): String = when {
        destination.hasRoute<Route.Tracking>() -> "tracking:" + toRoute<Route.Tracking>().section
        destination.hasRoute<Route.Settings>() -> "settings"
        destination.hasRoute<Route.Dashboard>() -> "dashboard"
        destination.hasRoute<Route.Welcome>() -> "welcome"
        destination.hasRoute<Route.DevicePinUnlock>() -> "unlock"
        destination.hasRoute<Route.SecurityHub>() -> "hub"
        destination.hasRoute<Route.OpenWallet>() -> "open:" + toRoute<Route.OpenWallet>().walletId
        else -> destination.route.orEmpty()
    }

    private fun NavHostController.depth(): Int {
        var n = 0
        while (popBackStack()) n++
        return n + 1
    }

    @Test
    fun `SEC-144 section clicks replace the tracker entry instead of piling up`() = runDesktopComposeUiTest {
        val nc = host(Route.Tracking())
        runOnIdle {
            nc.navigateToSection(TrackerSection.BUDGET)
            nc.navigateToSection(TrackerSection.GOALS)
            nc.navigateToSection(TrackerSection.REPORTS)
            nc.navigateToSection(TrackerSection.REPORTS)
        }
        waitForIdle()
        runOnIdle {
            assertEquals("tracking:reports", nc.currentBackStackEntry!!.describe())
            assertNull(nc.previousBackStackEntry, "only one tracker entry on the stack")
        }
    }

    @Test
    fun `SEC-145 Wallet with no wallet open and none tracked goes to the tracker HOME`() = runDesktopComposeUiTest {
        val nc = host(Route.Tracking(section = TrackerSection.BUDGET.wire))
        runOnIdle { nc.handleRailTab(Tab.Wallet, current = Tab.Budget, walletOpen = false, firstWalletId = null) }
        waitForIdle()
        runOnIdle {
            assertEquals("tracking:home", nc.currentBackStackEntry!!.describe())
            assertNull(nc.previousBackStackEntry)
        }
    }

    @Test
    fun `SEC-145 Wallet without an open wallet opens the first tracked one`() = runDesktopComposeUiTest {
        val nc = host(Route.Tracking())
        runOnIdle { nc.handleRailTab(Tab.Wallet, current = Tab.Home, walletOpen = false, firstWalletId = "w1") }
        waitForIdle()
        runOnIdle { assertEquals("open:w1", nc.currentBackStackEntry!!.describe()) }
    }

    @Test
    fun `SEC-145 Wallet with an open wallet opens the workspace`() = runDesktopComposeUiTest {
        val nc = host(Route.Tracking())
        runOnIdle { nc.handleRailTab(Tab.Wallet, current = Tab.Home, walletOpen = true) }
        waitForIdle()
        runOnIdle {
            assertEquals("dashboard", nc.currentBackStackEntry!!.describe())
            assertEquals("tracking:home", nc.previousBackStackEntry!!.describe())
        }
    }

    @Test
    fun `SEC-145 the default wallet check reads WalletStore`() = runDesktopComposeUiTest {
        WalletStore.set(FakeWalletPort())
        val nc = host(Route.Tracking())
        runOnIdle { nc.handleRailTab(Tab.Wallet, current = Tab.Home) }
        waitForIdle()
        runOnIdle { assertEquals("dashboard", nc.currentBackStackEntry!!.describe()) }
    }

    @Test
    fun `SEC-145 tapping the selected rail entry does nothing`() = runDesktopComposeUiTest {
        val nc = host(Route.Tracking())
        runOnIdle { nc.handleRailTab(Tab.Home, current = Tab.Home, walletOpen = false) }
        waitForIdle()
        runOnIdle {
            assertEquals("tracking:home", nc.currentBackStackEntry!!.describe())
            assertNull(nc.previousBackStackEntry)
        }
    }

    @Test
    fun `SEC-146 Settings opens full screen on top and Back returns to the section`() = runDesktopComposeUiTest {
        val nc = host(Route.Tracking(section = TrackerSection.PORTFOLIO.wire))
        runOnIdle { nc.handleRailTab(Tab.Settings, current = Tab.Portfolio, walletOpen = false) }
        waitForIdle()
        runOnIdle {
            assertEquals("settings", nc.currentBackStackEntry!!.describe())
            assertTrue(nc.popBackStack())
        }
        waitForIdle()
        runOnIdle { assertEquals("tracking:portfolio", nc.currentBackStackEntry!!.describe()) }
    }

    @Test
    fun `SEC-076 restartAtBootGuard after a wipe lands on Welcome with a single entry`() = runDesktopComposeUiTest {
        val nc = host(Route.Tracking())
        runOnIdle {
            nc.navigate(Route.Settings)
        }
        waitForIdle()
        // Factory reset emptied the data dir: the boot guard now says Welcome.
        runOnIdle { nc.restartAtBootGuard { bootGuardTarget(WalletPaths.from(dir)) } }
        waitForIdle()
        runOnIdle {
            assertEquals("welcome", nc.currentBackStackEntry!!.describe())
            assertNull(nc.previousBackStackEntry)
        }
    }

    @Test
    fun `SEC-069 lockAndRouteToGate clears the stack and lands on the PIN gate`() = runDesktopComposeUiTest {
        File(dir, WalletPaths.REGISTRY_FILE_NAME).writeText("x")
        WalletStore.set(FakeWalletPort())
        val nc = host(Route.Tracking())
        runOnIdle {
            nc.navigate(Route.Dashboard)
            nc.navigate(Route.SecurityHub)
        }
        waitForIdle()
        runOnIdle { nc.lockAndRouteToGate { bootGuardTarget(WalletPaths.from(dir)) } }
        waitForIdle()
        runOnIdle {
            assertEquals("unlock", nc.currentBackStackEntry!!.describe())
            assertNull(nc.previousBackStackEntry, "the back stack is emptied")
            assertNull(WalletStore.current())
            assertEquals(1, nc.depth())
        }
    }
}
