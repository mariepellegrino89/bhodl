package io.bhodl.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.navigation.NavController
import io.bhodl.desktop.navigation.Route
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.Tab
import io.bhodl.desktop.screens.AddressBookScreen
import io.bhodl.desktop.screens.BumpFeeScreen
import io.bhodl.desktop.screens.ConfirmMnemonicScreen
import io.bhodl.desktop.screens.CpfpKind
import io.bhodl.desktop.screens.CpfpScreen
import io.bhodl.desktop.screens.DashboardScreen
import io.bhodl.desktop.screens.DebugWatchScreen
import io.bhodl.desktop.screens.GenerateMnemonicScreen
import io.bhodl.desktop.screens.AddWatchScreen
import io.bhodl.desktop.screens.DevicePinUnlockScreen
import io.bhodl.desktop.screens.MigrationScreen
import io.bhodl.desktop.screens.PromoteWatchScreen
import io.bhodl.desktop.screens.TrackingScreen
import io.bhodl.desktop.screens.WatchDashboardScreen
import io.bhodl.desktop.screens.PinSetupScreen
import io.bhodl.desktop.screens.RestoreBackupScreen
import io.bhodl.desktop.screens.RestoreMnemonicScreen
import io.bhodl.desktop.screens.ReviewSendScreen
import io.bhodl.desktop.screens.SendSuccessScreen
import io.bhodl.desktop.screens.SettingsScreen
import io.bhodl.desktop.screens.ModulesScreen
import io.bhodl.desktop.screens.TransactionDetailsScreen
import io.bhodl.desktop.screens.UnlockScreen
import io.bhodl.desktop.screens.WalletTab
import io.bhodl.desktop.screens.WelcomeScreen
import io.bhodl.desktop.wallet.NetworkPersistence
import io.bhodl.desktop.wizard.DevicePinSession
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.SetupSecrets
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.wizard.WatchStore
import kotlinx.coroutines.delay

/**
 * Top-level Compose root — wires the [`NavHost`] that drives every
 * BHODL desktop screen.
 *
 * The boot guard (computed once on first composition) chooses the
 * start destination in this priority order:
 *
 *  1. **In-memory port present** ([`WalletStore.current`] is non-null)
 *     → [`Route.Dashboard`]. This is the language-switch path: when
 *     the user toggles locale in Settings, `Main.kt` `key(localeOverride)`
 *     tears down and re-mounts the entire NavHost subtree. Without
 *     this branch the rebuild would route through the disk check
 *     (Unlock for any wallet that was just being used) and force a
 *     PIN re-entry — see finding #11 in `SMOKE_TEST_FINDINGS.md`. The
 *     in-memory check is also harmless on the very first launch
 *     because nothing has populated `WalletStore` yet.
 *  2. **On-disk wallet exists** (`wallet.kdf.json` or legacy
 *     `wallet.salt` present) → [`Route.Unlock`] (returning user
 *     resuming a previous session).
 *  3. **Neither** → [`Route.Welcome`] (first-time setup).
 *
 * Once the user finishes the wizard or unlocks an existing wallet, the
 * `WalletHandle` is parked in `WalletStore` and the dashboard reads it
 * from there.
 */
@Composable
public fun App() {
    // The navController is created ONCE per App() lifetime and lives
    // OUTSIDE the `key(localeOverride)` block below, so the user's
    // backstack survives a language flip. Without this hoisting, the
    // 2026-05-12 attempt to "remount everything on locale change"
    // dropped the user back to Dashboard regardless of where they
    // were — see finding #11 history.
    val navController = rememberNavController()
    val startDestination: Route = remember {
        // Priority 1: in-memory handle survives a Compose subtree
        // remount (e.g. locale flip in Settings). Without this short
        // circuit, a remount would loop the user through Unlock even
        // though the wallet has not actually been locked.
        if (WalletStore.current() != null) {
            Route.Dashboard
        } else {
            val view = WalletPaths.from(WalletPaths.defaultDataDir())
            when {
                // Phase 3 — interrupted migration recovery is silent
                // (Rust completes the sentinel-driven recovery
                // without further user input). Pre-3.0 wallet
                // detected but no registry → prompt the user via
                // MigrationScreen for the two PINs.
                view.migrationNeeded() || view.migrationRecoveryPending() ->
                    Route.Migration

                // Phase 3.2 — registry exists but RegistryStore is
                // empty (cold start, or restart after Logout). Gate
                // on the device PIN before falling through to per-
                // wallet unlock so every screen that reads
                // `RegistryStore` (Settings → Show watch-only
                // descriptor, debug screen, future tracking
                // dashboard) finds it populated.
                view.registryExists() && io.bhodl.desktop.wizard.RegistryStore.current() == null ->
                    Route.DevicePinUnlock

                view.walletExists() -> Route.Unlock()
                else -> Route.Welcome
            }
        }
    }

    // Security — idle auto-lock (A07). A single root-level watcher polls
    // the device-PIN session: once it has been idle past
    // [DevicePinSession.DEFAULT_IDLE_TIMEOUT_MS], we drop the in-memory
    // handles and bounce to the device-PIN gate so an unattended desktop
    // can't be browsed after the timeout. The clock is kept alive ONLY by
    // the pointer / key activity hook on the NavHost below (background
    // jobs no longer touch it — audit 2026-10), so an actively-used session
    // never locks mid-use. Guarded on `RegistryStore.current() != null`:
    // legacy single-wallet installs with no device-PIN session (and the
    // lock screen itself, where the registry is closed) are never armed.
    // Lives outside `key(localeOverride)` so a language flip doesn't
    // restart the timer.
    //
    // Audit 2026-10 (L-idle) — the policy now lives in [IdleLock]: it
    // arms whenever a signing wallet OR the registry is open (legacy
    // registry-less wallets included), counts key events as well as
    // pointer events, and locks after a short grace once the window is
    // minimised (see Main.kt).
    LaunchedEffect(Unit) {
        while (true) {
            delay(IDLE_LOCK_CHECK_INTERVAL_MS)
            val registryOpen = RegistryStore.current() != null
            val sessionOpen = registryOpen || WalletStore.current() != null
            if (IdleLock.shouldLock(
                    sessionOpen = sessionOpen,
                    registryOpen = registryOpen,
                    devicePinExpired = DevicePinSession.isExpired(),
                )
            ) {
                navController.lockAndRouteToGate()
            }
        }
    }

    // Locale-aware re-mount of the NavHost (but NOT the navController):
    // when the user flips the language chip in Settings the inner
    // `NavHost` is torn down and rebuilt so every visible
    // `stringResource` is re-resolved against the new `Locale.getDefault()`.
    // The same `navController` is passed in, so its backstack — and
    // therefore the user's current screen — is preserved across the
    // re-mount. Trade-off: any non-`rememberSaveable` Compose state
    // inside the currently-displayed screen (typed-but-not-submitted
    // form fields, etc.) is wiped. Acceptable: nobody changes
    // language mid-Send.
    val localeOverride by SettingsStore.localeOverride
    key(localeOverride) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // Idle-lock activity hook — every pointer event (move / press)
        // refreshes the device-PIN idle clock via [DevicePinSession.touch]
        // (a no-op when no session is parked). PointerEventPass.Initial
        // observes during the tunneling pass *before* children, and the
        // event is never consumed, so screen interactions are unaffected.
        modifier = Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        IdleLock.touch()
                    }
                }
            }
            // Audit 2026-10 — keyboard-only use counts as activity too
            // (never consumed: returns false).
            .onPreviewKeyEvent {
                IdleLock.touch()
                false
            },
    ) {
        composable<Route.Welcome> {
            // Audit 2026-10 (M10) — back on the hub: any half-finished
            // wizard's seed material is dropped.
            LaunchedEffect(Unit) { SetupSecrets.wipeAll() }
            WelcomeScreen(
                onCreateWallet = { navController.navigate(Route.GenerateMnemonic) },
                onUnlock = { walletId ->
                    navController.navigate(Route.Unlock(walletId = walletId))
                },
                onActivateWatch = { walletId ->
                    navController.navigate(Route.WatchDashboard(walletId = walletId))
                },
                onRestore = { navController.navigate(Route.RestoreMnemonic) },
                onRestoreBackup = { navController.navigate(Route.RestoreBackup) },
                onAddWatch = { navController.navigate(Route.AddWatch) },
                onOpenTracker = {
                    navController.navigate(Route.Tracking()) {
                        popUpTo(Route.Welcome) { inclusive = true }
                    }
                },
                // After a wipe: stay on Welcome but bounce the start
                // destination so the next user action sees a fresh boot
                // guard. We pop to Welcome inclusive then push it back so
                // the back stack contains exactly one entry.
                onResetComplete = {
                    navController.navigate(Route.Welcome) {
                        popUpTo(Route.Welcome) { inclusive = true }
                    }
                },
            )
        }

        composable<Route.RestoreBackup> {
            RestoreBackupScreen(
                onValidated = { mnemonic, passphrase, network ->
                    navController.navigate(
                        Route.PinSetup(
                            secretId = SetupSecrets.put(mnemonic, passphrase),
                            isRestore = true,
                            network = NetworkPersistence.serialise(network),
                        ),
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.GenerateMnemonic> {
            GenerateMnemonicScreen(
                onConfirmed = { mnemonic, network ->
                    navController.navigate(
                        Route.ConfirmMnemonic(
                            secretId = SetupSecrets.put(mnemonic),
                            network = NetworkPersistence.serialise(network),
                        ),
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.RestoreMnemonic> {
            RestoreMnemonicScreen(
                // Skip the ConfirmMnemonic challenge: the user just typed
                // the phrase from a paper backup, asking them to type a
                // subset of it again would be theater. Jump straight to
                // PinSetup where the mnemonic gets sealed into the
                // SQLCipher DB.
                onValidated = { mnemonic, network ->
                    navController.navigate(
                        Route.PinSetup(
                            secretId = SetupSecrets.put(mnemonic),
                            isRestore = true,
                            network = NetworkPersistence.serialise(network),
                        ),
                    )
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.ConfirmMnemonic> { entry ->
            val args = entry.toRoute<Route.ConfirmMnemonic>()
            val mnemonic = remember(args.secretId) { SetupSecrets.mnemonic(args.secretId) }
            if (mnemonic == null) {
                // Secret already wiped (lock / completed wizard) — the
                // stale entry has nothing to show.
                LaunchedEffect(Unit) { navController.restartAtBootGuard() }
            } else {
                ConfirmMnemonicScreen(
                    mnemonic = mnemonic,
                    onConfirmed = { _ ->
                        navController.navigate(
                            Route.PinSetup(secretId = args.secretId, network = args.network),
                        )
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable<Route.PinSetup> { entry ->
            val args = entry.toRoute<Route.PinSetup>()
            val mnemonic = remember(args.secretId) { SetupSecrets.mnemonic(args.secretId) }
            val passphrase = remember(args.secretId) { SetupSecrets.passphrase(args.secretId) }
            if (mnemonic == null) {
                LaunchedEffect(Unit) { navController.restartAtBootGuard() }
            } else {
                PinSetupScreen(
                    mnemonic = mnemonic,
                    initialPassphrase = passphrase,
                    isRestore = args.isRestore,
                    network = NetworkPersistence.parse(args.network) ?: io.bhodl.ffi.Network.SIGNET,
                    onSetupComplete = {
                        // UX review 2026-10 (item 14) — a new wallet's
                        // phrase just passed the ConfirmMnemonic word
                        // challenge: record the date for the Security Hub.
                        // A restore proves possession of the backup too
                        // (security review): restored wallets count as
                        // verified, or the reminder would nag forever.
                        recordSetupBackupVerified(WalletStore.activeWalletId())
                        // Audit 2026-10 (M10) — wipe the parked seed and
                        // drop the WHOLE setup flow from the back stack.
                        // `popUpTo(Route.Welcome)` was a no-op when the
                        // wizard started from the Tracker, leaving
                        // ConfirmMnemonic / PinSetup (and their seed)
                        // reachable via Back.
                        SetupSecrets.wipeAll()
                        navController.navigate(Route.Dashboard) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable<Route.Migration> {
            MigrationScreen(
                onMigrated = {
                    // After a successful migration the wallet now lives
                    // under `wallets/<uuid>/`. The Unlock flow finds it
                    // via `WalletPathsView.activeWalletDir()` and
                    // continues with the legacy PIN as the wallet PIN.
                    // Migration just upgraded a pre-3.0 single-wallet
                    // install — there is exactly one signing wallet on
                    // disk, no walletId needed. Phase 3.2 sub-B would
                    // route to Welcome (picker) here, but migration is
                    // a one-time event and the user just typed both
                    // PINs; going straight to Unlock is the better UX.
                    navController.navigate(Route.Unlock()) {
                        popUpTo(Route.Migration) { inclusive = true }
                    }
                },
                onBack = {
                    // Migration is the start destination when needed,
                    // so the back stack is empty. Route to Welcome as
                    // an escape hatch (matches the Unlock screen
                    // pattern below).
                    navController.navigate(Route.Welcome) {
                        popUpTo(Route.Migration) { inclusive = true }
                    }
                },
            )
        }

        composable<Route.AddWatch> {
            AddWatchScreen(
                onAdded = {
                    navController.navigate(Route.Welcome) {
                        popUpTo(Route.Welcome) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.DevicePinUnlock> {
            DevicePinUnlockScreen(
                onUnlocked = {
                    // Phase 6.0 → 9.0 — after the device PIN gate, land
                    // on the tracker HOME section (hero + wallet list,
                    // the merged TrackerHome). The user can unlock a
                    // signing wallet or activate a watch-only from
                    // there — without ever being forced through a
                    // signing PIN to "just look at the numbers".
                    navController.navigate(Route.Tracking()) {
                        popUpTo(Route.DevicePinUnlock) { inclusive = true }
                    }
                },
                onBack = {
                    navController.navigate(Route.Welcome) {
                        popUpTo(Route.DevicePinUnlock) { inclusive = true }
                    }
                },
            )
        }

        composable<Route.Unlock> { entry ->
            val unlockArgs = entry.toRoute<Route.Unlock>()
            UnlockScreen(
                onUnlocked = {
                    navController.navigate(Route.Dashboard) {
                        popUpTo<Route.Unlock> { inclusive = true }
                    }
                },
                // The boot guard makes `Unlock` the start destination for
                // returning users, so the back stack is empty here and a
                // plain `popBackStack()` would be a no-op (user trapped on
                // the unlock screen). Route them to Welcome instead — that
                // is the only entry point from which they can re-create a
                // wallet, restore from seed, or pick a different data dir
                // once those flows land.
                onBack = {
                    navController.navigate(Route.Welcome) {
                        popUpTo<Route.Unlock> { inclusive = true }
                    }
                },
                walletId = unlockArgs.walletId,
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
            // Audit 2026-10 (M10) — wizard abandoned back to the tracker.
            LaunchedEffect(Unit) { SetupSecrets.wipeAll() }
            val args = entry.toRoute<Route.Tracking>()
            val section = TrackerSection.fromWire(args.section)
            io.bhodl.desktop.ui.TwoTabShell(
                selected = Tab.fromSection(section),
                onTabSelected = { tab ->
                    navController.handleRailTab(tab, current = Tab.fromSection(section))
                },
            ) {
                TrackingScreen(
                    section = section,
                    onSectionChange = { target ->
                        navController.navigateToSection(target)
                    },
                    onUnlockWallet = { walletId ->
                        navController.navigate(Route.Unlock(walletId = walletId))
                    },
                    onActivateWatch = { walletId ->
                        navController.navigate(Route.WatchDashboard(walletId = walletId))
                    },
                    onAddWatch = { navController.navigate(Route.AddWatch) },
                    onCreateWallet = { navController.navigate(Route.GenerateMnemonic) },
                    onOpenModules = { navController.navigate(Route.Modules) },
                    onOpenSecurityHub = { navController.navigate(Route.SecurityHub) },
                    onLockDevice = {
                        // Full logout from the device-PIN session: drop
                        // the signing-wallet port (if any) AND the
                        // registry handle, then force the boot guard to
                        // re-evaluate by routing to DevicePinUnlock (the
                        // registry file is still on disk; the device PIN
                        // is needed again to decrypt it).
                        navController.lockAndRouteToGate()
                    },
                )
            }
        }

        composable<Route.WatchDashboard> { entry ->
            val args = entry.toRoute<Route.WatchDashboard>()
            WatchDashboardScreen(
                walletId = args.walletId,
                onBack = {
                    navController.navigate(Route.Welcome) {
                        popUpTo<Route.WatchDashboard> { inclusive = true }
                    }
                },
                onPromote = { id ->
                    navController.navigate(Route.PromoteWatch(walletId = id))
                },
            )
        }

        composable<Route.PromoteWatch> { entry ->
            val args = entry.toRoute<Route.PromoteWatch>()
            PromoteWatchScreen(
                walletId = args.walletId,
                onPromoted = {
                    // After promote, the WalletStore holds the newly-
                    // minted signing wallet handle. Send the user
                    // straight into Dashboard — same destination as
                    // the post-Unlock signing flow.
                    navController.navigate(Route.Dashboard) {
                        popUpTo<Route.PromoteWatch> { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        // UX review 2026-10 — Send / Receive / History are tabs of the
        // wallet workspace now; the routes stay for deep links.
        composable<Route.Receive> {
            WalletWorkspaceRoute(navController, WalletTab.RECEIVE)
        }

        composable<Route.Send> {
            WalletWorkspaceRoute(navController, WalletTab.SEND)
        }

        composable<Route.ReviewSend> { entry ->
            val args = entry.toRoute<Route.ReviewSend>()
            // Item 13 — payment confirmations always show real amounts.
            WalletShell(navController) {
                io.bhodl.desktop.ui.money.RevealAmounts {
                    ReviewSendScreen(
                        unsignedPsbtBase64 = args.unsignedPsbtBase64,
                        toAddress = args.toAddress,
                        amountSat = args.amountSat,
                        category = args.category,
                        onSent = { txid ->
                            navController.navigate(Route.SendSuccess(txid)) {
                                // Drop the form + review off the stack so the
                                // user can't hit Back and re-broadcast.
                                popUpTo(Route.Dashboard)
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable<Route.SendSuccess> { entry ->
            val args = entry.toRoute<Route.SendSuccess>()
            WalletShell(navController) {
                SendSuccessScreen(
                    txid = args.txid,
                    onDone = {
                        navController.navigate(Route.Dashboard) {
                            popUpTo(Route.Dashboard) { inclusive = true }
                        }
                    },
                )
            }
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
                    onCpfpChangeRescue = { txid ->
                        navController.navigate(
                            Route.Cpfp(parentTxid = txid, kind = "change-rescue"),
                        )
                    },
                )
            }
        }

        composable<Route.BumpFee> { entry ->
            val args = entry.toRoute<Route.BumpFee>()
            // Item 13 — payment confirmations always show real amounts.
            WalletShell(navController) {
                io.bhodl.desktop.ui.money.RevealAmounts {
                    BumpFeeScreen(
                        originalTxid = args.originalTxid,
                        onSent = { newTxid ->
                            navController.navigate(Route.SendSuccess(newTxid)) {
                                popUpTo(Route.Dashboard)
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        composable<Route.Cpfp> { entry ->
            val args = entry.toRoute<Route.Cpfp>()
            val kind = if (args.kind == "change-rescue") {
                CpfpKind.ChangeRescue
            } else {
                CpfpKind.IncomingSpeedUp
            }
            // Item 13 — payment confirmations always show real amounts.
            WalletShell(navController) {
                io.bhodl.desktop.ui.money.RevealAmounts {
                    CpfpScreen(
                        parentTxid = args.parentTxid,
                        kind = kind,
                        onSent = { newTxid ->
                            navController.navigate(Route.SendSuccess(newTxid)) {
                                popUpTo(Route.Dashboard)
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
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
                onWalletWiped = {
                    navController.navigate(Route.Welcome) {
                        // Drop everything from the back stack — there
                        // is no wallet to unlock anymore, and a Back
                        // tap from Welcome should not bounce us into
                        // a stale Dashboard / Settings.
                        popUpTo(0) { inclusive = true }
                    }
                },
                onOpenDebugWatch = { navController.navigate(Route.DebugWatch) },
                onOpenWalletSettings = {
                    navController.navigate(Route.WalletSection(tab = WalletTab.SETTINGS.wire))
                },
                onOpenSecurityHub = { navController.navigate(Route.SecurityHub) },
            )
        }

        composable<Route.SecurityHub> {
            io.bhodl.desktop.screens.SecurityHubScreen(
                onBack = { navController.popBackStack() },
                onVerifyBackup = { navController.navigate(Route.VerifyBackup) },
                onOpenWalletSettings = {
                    navController.navigate(Route.WalletSection(tab = WalletTab.SETTINGS.wire))
                },
                onOpenSettings = { navController.navigate(Route.Settings) },
            )
        }

        composable<Route.VerifyBackup> {
            io.bhodl.desktop.screens.VerifyBackupScreen(
                onDone = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.Modules> {
            ModulesScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<Route.DebugWatch> {
            // Audit 2026-10 — never reachable in a packaged build, even
            // via a stale back stack / deep link.
            if (DebugFlags.debugToolsEnabled) {
                DebugWatchScreen(
                    onBack = { navController.popBackStack() },
                )
            } else {
                LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
    }
    } // end `key(localeOverride)` — see comment above NavHost.
}

/**
 * How often the idle-lock watcher polls the device-PIN session. 30s is
 * fine-grained enough that the worst-case overshoot past the 15-minute
 * idle timeout is negligible, while keeping the wake-ups cheap.
 */
private const val IDLE_LOCK_CHECK_INTERVAL_MS: Long = 10_000L

/**
 * Audit 2026-10 (M11 / L-idle) — the one lock routine shared by the
 * Dashboard "Lock" button, the Tracker "Lock device" action and the
 * idle / minimise watcher: release every in-memory handle (signing
 * port, watch handle, registry handle — whose clear() also wipes the
 * device-PIN session and cancels the backfill), wipe any parked wizard
 * seed, and route to the right gate with an empty back stack.
 */
internal fun NavController.lockAndRouteToGate() {
    // UX review 2026-10 (item 13) — "Start with balances hidden" also
    // applies after every lock.
    SettingsStore.applyStartHidden()
    WalletStore.clear()
    WatchStore.clear()
    RegistryStore.clear()
    DevicePinSession.clear()
    SetupSecrets.wipeAll()
    navigate(bootGuardTarget()) {
        popUpTo(0) { inclusive = true }
    }
}

/** Re-run the cold-boot routing decision with an empty back stack. */
private fun NavController.restartAtBootGuard() {
    navigate(bootGuardTarget()) {
        popUpTo(0) { inclusive = true }
    }
}

/** Boot-guard target when nothing is unlocked in memory. */
private fun bootGuardTarget(): Route {
    val view = WalletPaths.from(WalletPaths.defaultDataDir())
    return when {
        view.migrationNeeded() || view.migrationRecoveryPending() -> Route.Migration
        view.registryExists() -> Route.DevicePinUnlock
        view.walletExists() -> Route.Unlock()
        else -> Route.Welcome
    }
}

/**
 * Phase 9.0 — navigate to a tracker section, replacing the current
 * tracker entry so rail clicks don't pile section after section on
 * the back stack (Back from the tracker leaves the tracker, it
 * doesn't replay every section visited).
 */
private fun NavController.navigateToSection(section: TrackerSection) {
    navigate(Route.Tracking(section = section.wire)) {
        popUpTo<Route.Tracking> { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Phase 9.0 — shared NavigationRail dispatch for every shell-wrapped
 * route. Tracker sections route to [navigateToSection]; Wallet keeps
 * the Phase 6.0 rule (no active signing wallet → land on the tracker
 * HOME, where the wallet list + unlock affordances now live, instead
 * of forcing a PIN); Settings is a full-screen action route.
 */
private fun NavController.handleRailTab(tab: Tab, current: Tab) {
    if (tab == current) return
    when (tab) {
        Tab.Wallet -> {
            if (io.bhodl.desktop.wizard.WalletStore.current() == null) {
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
 * UX review 2026-10 (§4 item 5) — the signing-wallet workspace inside
 * the rail shell, opened on [initialTab]. Every wallet entry route
 * (Dashboard, WalletSection, Send, Receive, History) lands here.
 */
@Composable
private fun WalletWorkspaceRoute(navController: NavController, initialTab: WalletTab) {
    WalletShell(navController) {
        DashboardScreen(
            initialTab = initialTab,
            onReviewSend = { psbt, address, amountSat, category ->
                navController.navigate(
                    Route.ReviewSend(
                        unsignedPsbtBase64 = psbt,
                        toAddress = address,
                        amountSat = amountSat,
                        category = category,
                    ),
                )
            },
            onPayjoinSent = { txid ->
                navController.navigate(Route.SendSuccess(txid)) {
                    // Back from Success must not bounce into a stale form.
                    popUpTo(Route.Dashboard)
                }
            },
            onBumpFee = { txid -> navController.navigate(Route.BumpFee(txid)) },
            onCpfp = { txid, kind ->
                navController.navigate(
                    Route.Cpfp(
                        parentTxid = txid,
                        kind = if (kind == CpfpKind.ChangeRescue) "change-rescue" else "incoming-speed-up",
                    ),
                )
            },
            onAddressBookClicked = { navController.navigate(Route.AddressBook) },
            onOpenSecurityHub = { navController.navigate(Route.SecurityHub) },
            onLockClicked = {
                // Audit 2026-10 (M11) — "Lock" is a real lock: drop the
                // signing port AND the registry handle + device-PIN
                // session, then route to the device-PIN gate.
                navController.lockAndRouteToGate()
            },
            onWalletWiped = {
                navController.navigate(Route.Welcome) {
                    // No wallet left to unlock: empty back stack.
                    popUpTo(0) { inclusive = true }
                }
            },
            onSwitchSigningWallet = { targetId ->
                // Phase 3.2-G — drop the current wallet's port and
                // unlock the requested wallet (per-wallet PIN), which
                // routes back to the wallet workspace.
                WalletStore.clear()
                navController.navigate(Route.Unlock(walletId = targetId)) {
                    popUpTo(0) { inclusive = true }
                }
            },
        )
    }
}

/** The rail shell with Wallet selected, for every wallet route. */
@Composable
private fun WalletShell(navController: NavController, content: @Composable () -> Unit) {
    io.bhodl.desktop.ui.TwoTabShell(
        selected = Tab.Wallet,
        onTabSelected = { tab -> navController.handleRailTab(tab, current = Tab.Wallet) },
        content = content,
    )
}

/**
 * UX review 2026-10 (item 14) — a wallet that just finished setup has a
 * verified backup: a new wallet passed the ConfirmMnemonic word
 * challenge, a restored one was rebuilt from its phrase / backup.
 */
internal fun recordSetupBackupVerified(walletId: String?, nowUnix: Long = System.currentTimeMillis() / 1000L) {
    walletId?.let { SettingsStore.markBackupVerified(it, nowUnix) }
}
