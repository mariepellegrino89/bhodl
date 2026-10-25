package io.bhodl.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.bhodl.desktop.navigation.Route
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.screens.AddWatchScreen
import io.bhodl.desktop.screens.AddressBookScreen
import io.bhodl.desktop.screens.DashboardScreen
import io.bhodl.desktop.screens.DevicePinSetupScreen
import io.bhodl.desktop.screens.DevicePinUnlockScreen
import io.bhodl.desktop.screens.LegacyCleanupScreen
import io.bhodl.desktop.screens.ModulesScreen
import io.bhodl.desktop.screens.SettingsScreen
import io.bhodl.desktop.screens.TrackingScreen
import io.bhodl.desktop.screens.TransactionDetailsScreen
import io.bhodl.desktop.screens.WalletTab
import io.bhodl.desktop.screens.WelcomeScreen
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.Tab
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.DevicePinSession
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletPathsView
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.onb_registry_open_failed
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Top-level Compose root — wires the [`NavHost`] that drives every
 * BHODL desktop screen.
 *
 * BHODL is watch-only: the only secret is the device PIN, which opens
 * the registry (wallet list + tracker tables + every watch wallet's
 * state). The boot guard (computed once on first composition) picks:
 *
 *  1. **Wallet port in memory** ([`WalletStore.current`] non-null) →
 *     [`Route.Dashboard`]. This is the language-switch path: `Main.kt`
 *     `key(localeOverride)` re-mounts the NavHost subtree and must not
 *     bounce the user to the PIN gate.
 *  2. **Registry on disk** → [`Route.DevicePinUnlock`] (then
 *     [`Route.LegacyCleanup`] if seed-era data is still around, then
 *     the tracker).
 *  3. **Only seed-era data on disk** → [`Route.LegacyCleanup`].
 *  4. **Nothing** → [`Route.Welcome`] → device-PIN setup → add wallet.
 */
@Composable
public fun App() {
    // The navController is created ONCE per App() lifetime and lives
    // OUTSIDE the `key(localeOverride)` block below, so the user's
    // backstack survives a language flip.
    val navController = rememberNavController()
    val startDestination: Route = remember {
        startRoute(
            walletOpen = WalletStore.current() != null,
            registryOpen = RegistryStore.current() != null,
        )
    }

    // Security — idle auto-lock (A07). A single root-level watcher polls
    // the device-PIN session; the policy lives in [IdleLock] (pointer and
    // key activity keep it alive, minimising the window locks after a
    // short grace — see Main.kt). Lives outside `key(localeOverride)` so
    // a language flip doesn't restart the timer.
    LaunchedEffect(Unit) {
        runIdleLockWatcher(onLock = { navController.lockAndRouteToGate() })
    }

    // Locale-aware re-mount of the NavHost (but NOT the navController):
    // flipping the language chip in Settings rebuilds every visible
    // `stringResource` while the back stack is preserved.
    val localeOverride by SettingsStore.localeOverride
    key(localeOverride) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Idle-lock activity hook — every pointer / key event refreshes
        // the device-PIN idle clock. Never consumed.
        modifier = Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        IdleLock.touch()
                    }
                }
            }
            .onPreviewKeyEvent {
                IdleLock.touch()
                false
            },
    ) {
        composable<Route.Welcome> {
            WelcomeScreen(
                onGetStarted = { navController.navigate(Route.DevicePinSetup) },
                // After a wipe: rebuild the boot decision with a
                // single-entry back stack.
                onResetComplete = { navController.restartAtBootGuard() },
            )
        }

        composable<Route.DevicePinSetup> {
            DevicePinSetupScreen(
                onCreated = {
                    // The registry is open and empty: add the first wallet.
                    navController.navigate(Route.AddWatch) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.DevicePinUnlock> {
            DevicePinUnlockScreen(
                onUnlocked = {
                    navController.navigate(postUnlockTarget(legacyCleanupNeeded())) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.restartAtBootGuard() },
            )
        }

        composable<Route.LegacyCleanup> {
            LegacyCleanupScreen(
                onDone = {
                    navController.navigate(legacyCleanupDoneTarget(RegistryStore.current() != null)) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable<Route.AddWatch> {
            AddWatchScreen(
                onAdded = { navController.navigateToSection(TrackerSection.HOME) },
                onBack = {
                    // First run: AddWatch is the only entry on the stack.
                    if (!navController.popBackStack()) {
                        navController.navigateToSection(TrackerSection.HOME)
                    }
                },
            )
        }

        composable<Route.OpenWallet> { entry ->
            val args = entry.toRoute<Route.OpenWallet>()
            OpenWalletRoute(
                walletId = args.walletId,
                onOpened = {
                    navController.navigate(Route.Dashboard) {
                        popUpTo<Route.OpenWallet> { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.Dashboard> {
            WalletWorkspaceRoute(navController, WalletTab.TRANSACTIONS)
        }

        composable<Route.WalletSection> { entry ->
            val args = entry.toRoute<Route.WalletSection>()
            WalletWorkspaceRoute(navController, WalletTab.fromWire(args.tab))
        }

        composable<Route.Tracking> { entry ->
            val args = entry.toRoute<Route.Tracking>()
            val section = TrackerSection.fromWire(args.section)
            io.bhodl.desktop.ui.TwoTabShell(
                selected = Tab.fromSection(section),
                onTabSelected = { tab ->
                    navController.handleRailTab(tab, current = Tab.fromSection(section))
                },
                onBack = navController.railBack(Tab.fromSection(section)),
            ) {
                TrackingScreen(
                    section = section,
                    onSectionChange = { target -> navController.navigateToSection(target) },
                    onOpenWallet = { walletId ->
                        navController.navigate(Route.OpenWallet(walletId = walletId))
                    },
                    onAddWatch = { navController.navigate(Route.AddWatch) },
                    onOpenModules = { navController.navigate(Route.Modules) },
                    onOpenSecurityHub = { navController.navigate(Route.SecurityHub) },
                    onLockDevice = { navController.lockAndRouteToGate() },
                )
            }
        }

        // Receive / History are tabs of the wallet workspace; the routes
        // stay for deep links.
        composable<Route.Receive> {
            WalletWorkspaceRoute(navController, WalletTab.RECEIVE)
        }

        composable<Route.History> {
            WalletWorkspaceRoute(navController, WalletTab.TRANSACTIONS)
        }

        composable<Route.TransactionDetails> { entry ->
            val args = entry.toRoute<Route.TransactionDetails>()
            WalletShell(navController) {
                TransactionDetailsScreen(
                    txid = args.txid,
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable<Route.AddressBook> {
            WalletShell(navController) {
                AddressBookScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable<Route.Settings> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onWalletWiped = { navController.restartAtBootGuard() },
                onOpenWalletSettings = if (WalletStore.current() != null) {
                    { navController.navigate(Route.WalletSection(tab = WalletTab.SETTINGS.wire)) }
                } else {
                    null
                },
                onOpenSecurityHub = { navController.navigate(Route.SecurityHub) },
            )
        }

        composable<Route.SecurityHub> {
            io.bhodl.desktop.screens.SecurityHubScreen(
                onBack = { navController.popBackStack() },
                onOpenWallets = { navController.navigateToSection(TrackerSection.HOME) },
                onOpenSettings = { navController.navigate(Route.Settings) },
                onOpenWalletSettings = if (WalletStore.current() != null) {
                    { navController.navigate(Route.WalletSection(tab = WalletTab.SETTINGS.wire)) }
                } else {
                    null
                },
            )
        }

        composable<Route.Modules> {
            ModulesScreen(
                onBack = { navController.popBackStack() },
            )
        }
    }
    } // end `key(localeOverride)` — see comment above NavHost.
}

/**
 * How often the idle-lock watcher polls the device-PIN session. Fine
 * grained enough that the overshoot past the idle timeout is negligible.
 */
internal const val IDLE_LOCK_CHECK_INTERVAL_MS: Long = 10_000L

/**
 * The idle-lock watcher loop: every [intervalMs] ask [shouldLock] and
 * run [onLock] when it says so. Never returns; cancel the coroutine.
 */
internal suspend fun runIdleLockWatcher(
    intervalMs: Long = IDLE_LOCK_CHECK_INTERVAL_MS,
    shouldLock: () -> Boolean = ::idleLockDue,
    sleep: suspend (Long) -> Unit = { delay(it) },
    onLock: () -> Unit,
) {
    while (true) {
        sleep(intervalMs)
        if (shouldLock()) onLock()
    }
}

/** [IdleLock] policy applied to the current registry / session state. */
internal fun idleLockDue(): Boolean {
    val registryOpen = RegistryStore.current() != null
    return IdleLock.shouldLock(
        sessionOpen = registryOpen,
        registryOpen = registryOpen,
        devicePinExpired = DevicePinSession.isExpired(),
    )
}

/**
 * Start destination of a (re-)mounted [App]: an open wallet keeps the
 * workspace (language switch), an open registry the tracker, otherwise
 * the cold-boot decision.
 */
internal fun startRoute(
    walletOpen: Boolean,
    registryOpen: Boolean,
    boot: () -> Route = { bootGuardTarget() },
): Route = when {
    walletOpen && registryOpen -> Route.Dashboard
    registryOpen -> Route.Tracking()
    else -> boot()
}

/** Where a successful device-PIN unlock continues. */
internal fun postUnlockTarget(legacyCleanupNeeded: Boolean): Route =
    if (legacyCleanupNeeded) Route.LegacyCleanup else Route.Tracking()

/** Where LegacyCleanup continues (cleaned or "Not now"). */
internal fun legacyCleanupDoneTarget(registryOpen: Boolean): Route =
    if (registryOpen) Route.Tracking() else Route.Welcome

/**
 * Audit 2026-10 (M11 / L-idle) — the one lock routine shared by the
 * wallet "Lock" button, the tracker "Lock device" action and the idle /
 * minimise watcher: release every in-memory handle (wallet port,
 * registry handle — whose clear() also wipes the device-PIN session and
 * cancels the backfill) and route to the gate with an empty back stack.
 */
internal fun NavController.lockAndRouteToGate(target: () -> Route = { bootGuardTarget() }) {
    lockSession()
    navigate(target()) {
        popUpTo(0) { inclusive = true }
    }
}

/** The in-memory half of [lockAndRouteToGate]. */
internal fun lockSession() {
    // UX review 2026-10 (item 13) — "Start with balances hidden" also
    // applies after every lock.
    SettingsStore.applyStartHidden()
    WalletStore.clear()
    RegistryStore.clear()
    DevicePinSession.clear()
}

/** Re-run the cold-boot routing decision with an empty back stack. */
internal fun NavController.restartAtBootGuard(target: () -> Route = { bootGuardTarget() }) {
    navigate(target()) {
        popUpTo(0) { inclusive = true }
    }
}

/** Boot-guard target when nothing is unlocked in memory. */
internal fun bootGuardTarget(
    view: WalletPathsView = WalletPaths.from(WalletPaths.defaultDataDir()),
): Route {
    return when {
        view.registryExists() -> Route.DevicePinUnlock
        view.legacySigningDataExists() -> Route.LegacyCleanup
        else -> Route.Welcome
    }
}

/**
 * `true` when seed-era data survives next to an open registry: legacy
 * signing rows in the registry or key files on disk.
 */
internal fun legacyCleanupNeeded(
    view: WalletPathsView = WalletPaths.from(WalletPaths.defaultDataDir()),
    registryLegacyCount: () -> UInt? = { RegistryStore.current()?.legacySigningWalletCount() },
): Boolean {
    val onDisk = view.legacySigningDataExists()
    val inRegistry = runCatching { (registryLegacyCount() ?: 0u) > 0u }.getOrDefault(false)
    return onDisk || inRegistry
}

/**
 * Phase 9.0 — navigate to a tracker section, replacing the current
 * tracker entry so rail clicks don't pile section after section on the
 * back stack.
 */
internal fun NavController.navigateToSection(section: TrackerSection) {
    navigate(Route.Tracking(section = section.wire)) {
        popUpTo<Route.Tracking> { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Shared NavigationRail dispatch for every shell-wrapped route. Tracker
 * sections route to [navigateToSection]; Wallet opens the workspace of
 * the wallet already open, else the first tracked wallet (wallet
 * redesign 2026-10), else the tracker HOME wallet list;
 * Settings is a full-screen action route.
 */
internal fun NavController.handleRailTab(
    tab: Tab,
    current: Tab,
    walletOpen: Boolean = WalletStore.current() != null,
    firstWalletId: String? = TrackerCache.wallets.value.firstOrNull()?.id,
    /** False when going back: the move must not enter the history again. */
    record: Boolean = true,
) {
    if (tab == current) return
    // Redesign 2026-10 — the rail's Back returns to the section left here.
    if (record && tab != Tab.Settings) io.bhodl.desktop.ui.BackNavigation.recordTab(current)
    when (tab) {
        Tab.Wallet -> {
            if (!walletOpen && firstWalletId != null) {
                navigate(Route.OpenWallet(walletId = firstWalletId))
            } else if (!walletOpen) {
                navigateToSection(TrackerSection.HOME)
            } else {
                navigate(Route.Dashboard) {
                    popUpTo(Route.Dashboard) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        Tab.Settings -> navigate(Route.Settings)
        else -> navigateToSection(tab.toSection() ?: TrackerSection.HOME)
    }
}

/**
 * Redesign 2026-10 — the rail's Back once no in-screen page is open: the
 * previous navigation entry, else the section visited before; null when
 * there is nowhere to go back to.
 */
internal fun NavController.railBack(current: Tab): (() -> Unit)? = when {
    previousBackStackEntry != null -> { { popBackStack() } }
    io.bhodl.desktop.ui.BackNavigation.tabHistory.isNotEmpty() -> {
        {
            io.bhodl.desktop.ui.BackNavigation.popTab()?.let { handleRailTab(it, current, record = false) }
        }
    }
    else -> null
}

/**
 * Open watch wallet [walletId] with the device key held by the registry
 * handle, park it in [WalletStore] and continue to the workspace.
 */
@Composable
internal fun OpenWalletRoute(walletId: String, onOpened: () -> Unit, onBack: () -> Unit) {
    var error by remember(walletId) { mutableStateOf<String?>(null) }
    LaunchedEffect(walletId) {
        if (WalletStore.activeWalletId() == walletId && WalletStore.current() != null) {
            onOpened()
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                val registry = RegistryStore.require()
                val record = registry.getWallet(walletId) ?: error("wallet $walletId not found")
                val network = EsploraEndpoint.networkOf(record.network)
                    ?: error("unknown network '${record.network}'")
                Triple(registry.openWatch(walletId), network, record.label)
            }
        }.fold(
            onSuccess = { (handle, network, label) ->
                WalletStore.set(handle, network, walletId, label)
                onOpened()
            },
            onFailure = { error = it.userMessage(localizedText(Res.string.onb_registry_open_failed)) },
        )
    }
    Box(
        modifier = Modifier.fillMaxSize().background(BhodlTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        error?.let { message ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(message, color = BhodlTheme.colors.error)
                TextButton(onClick = onBack) { Text(stringResource(Res.string.action_back)) }
            }
        }
    }
}

/**
 * The wallet workspace inside the rail shell, opened on [initialTab].
 * Every wallet entry route (Dashboard, WalletSection, Receive, History)
 * lands here. Without an open wallet (stale deep link after a lock) it
 * falls back to the tracker wallet list.
 */
@Composable
private fun WalletWorkspaceRoute(navController: NavController, initialTab: WalletTab) {
    if (walletWorkspaceRedirect(WalletStore.current() != null) != null) {
        LaunchedEffect(Unit) { navController.navigateToSection(TrackerSection.HOME) }
        return
    }
    WalletShell(navController) {
        DashboardScreen(
            initialTab = initialTab,
            onAddressBookClicked = { navController.navigate(Route.AddressBook) },
            onOpenSecurityHub = { navController.navigate(Route.SecurityHub) },
            onLockClicked = { navController.lockAndRouteToGate() },
            onWalletRemoved = {
                WalletStore.clear()
                navController.navigateToSection(TrackerSection.HOME)
            },
            onOpenWallet = { id -> navController.navigate(Route.OpenWallet(walletId = id)) },
            onAddWallet = { navController.navigate(Route.AddWatch) },
        )
    }
}

/**
 * Tracker section a wallet route falls back to when no wallet is open
 * (stale deep link after a lock); `null` when the workspace can render.
 */
internal fun walletWorkspaceRedirect(walletOpen: Boolean): TrackerSection? =
    if (walletOpen) null else TrackerSection.HOME

/** The rail shell with Wallet selected, for every wallet route. */
@Composable
private fun WalletShell(navController: NavController, content: @Composable () -> Unit) {
    io.bhodl.desktop.ui.TwoTabShell(
        selected = Tab.Wallet,
        onTabSelected = { tab -> navController.handleRailTab(tab, current = Tab.Wallet) },
        onBack = navController.railBack(Tab.Wallet),
        content = content,
    )
}
