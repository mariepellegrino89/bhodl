package io.bhodl.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsPersistence
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.settings.ThemeMode
import io.bhodl.desktop.ui.SettingsWarningBanner
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.app_already_running
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import java.util.Locale

/**
 * BHODL desktop entry point.
 *
 * Holds the desktop window configuration only; everything Compose-side
 * lives in [`App`]. The split exists so any future entry point (Android
 * `Activity`, iOS `UIViewController` factory) can reuse the same `App()`
 * composable without dragging desktop-specific window code with it.
 */
fun main() {
    // GAP-021 — refuse a second instance on the same data dir (held for
    // the process lifetime; the OS releases it on exit or crash).
    val instanceLock = SingleInstanceLock.tryAcquire(WalletPaths.defaultDataDir())
    if (instanceLock == null) {
        val message = kotlinx.coroutines.runBlocking {
            org.jetbrains.compose.resources.getString(Res.string.app_already_running)
        }
        javax.swing.JOptionPane.showMessageDialog(
            null, message, "BHODL", javax.swing.JOptionPane.INFORMATION_MESSAGE,
        )
        return
    }
    Runtime.getRuntime().addShutdownHook(Thread { instanceLock.close() })

    // T2-11 — settings persistence wiring. Rehydrate
    // `<dataDir>/settings.json` BEFORE the first composition so theme /
    // fiat currency / modules / Tor are correct on the first frame, then
    // install the write-through hook so every later setter change is
    // saved atomically. Contract (per SettingsStore.enablePersistence):
    // rehydrate FIRST — the hook isn't installed yet, so the apply pass
    // triggers no disk writes — THEN enablePersistence. Best-effort: a
    // missing file (first launch) or a parse error leaves the store at
    // its defaults and the next setter resyncs the file. The file is
    // intentionally NOT PIN-encrypted (low-sensitivity UX prefs), so this
    // runs pre-unlock at process start.
    val settingsFile = File(WalletPaths.defaultDataDir(), SettingsPersistence.FILE_NAME)
    // Audit 2026-10 (M9) — a corrupt / future-version file is backed up
    // to settings.json.corrupt-<ts>, Tor fails closed, and
    // SettingsStore.persistenceWarning is raised (banner below). If the
    // backup itself failed we do NOT install the write-through hook, so
    // the only copy of the user's settings is never overwritten.
    SettingsPersistence.rehydrate(settingsFile)
    if (!SettingsPersistence.unsafeToPersist(settingsFile)) {
        SettingsStore.enablePersistence(settingsFile)
    }
    // UX review 2026-10 (item 13) — "Start with balances hidden": the
    // session privacy flag is armed before the first frame.
    SettingsStore.applyStartHidden()

    // Security — persist the PIN failure/lockout counter across process
    // restarts. Without this the cooldown lives only in RAM, so a
    // kill+restart loop resets the lockout and re-grants free attempts,
    // bypassing the online brute-force defence. The file holds only
    // {scope, timestamps} — its existence reveals nothing about the PIN,
    // so (like settings.json) it is intentionally NOT encrypted and is
    // safe to read at process start, pre-unlock.
    PinAttemptTracker.enablePersistence(
        File(WalletPaths.defaultDataDir(), PinAttemptTracker.PIN_FAILURES_FILE_NAME),
    )

    val windowState = WindowState(
        // UX review 2026-10 (item 17) — the dense desktop layouts (rail +
        // full-width wallet workspace + tracker tables) open at a
        // laptop-friendly size; the old 460dp phone-form-factor window was
        // below the new minimum. See [WindowSizing].
        size = DpSize(width = WindowSizing.DEFAULT_WIDTH_DP.dp, height = WindowSizing.DEFAULT_HEIGHT_DP.dp),
        position = WindowPosition(Alignment.Center),
    )
    singleWindowApplication(
    title = "BHODL",
    state = windowState,
    // UX review 2026-10 (item 13) — Ctrl+Shift+H toggles the privacy
    // mode from every screen (window-level, so it works even while a
    // text field has focus).
    onPreviewKeyEvent = { io.bhodl.desktop.ui.PrivacyShortcut.handle(it) },
) {
    // UX review 2026-10 (item 17) — below this size the rail + dense
    // tables stop fitting; AWT sizes are in logical (scaled) pixels, i.e. dp.
    LaunchedEffect(Unit) {
        window.minimumSize = java.awt.Dimension(WindowSizing.MIN_WIDTH_DP, WindowSizing.MIN_HEIGHT_DP)
    }
    // Audit 2026-10 (L-idle) — feed the minimised state to the idle-lock
    // policy: an iconified window locks after IdleLock.MINIMIZED_GRACE_MS.
    LaunchedEffect(windowState) {
        snapshotFlow { windowState.isMinimized }.collect { IdleLock.setMinimized(it) }
    }
    // Re-theme the entire app when the user toggles between
    // Dark / Light / System in Settings. SettingsStore is a Compose
    // state holder, so reading it here registers main as a subscriber
    // and a different mode triggers exactly one recomposition at the
    // root.
    val themeMode by SettingsStore.themeMode
    val systemDark = isSystemInDarkTheme()
    // Phase 4.1 — neon is its own mode, OR-ed with whatever the dark/
    // light pick would have been. When neon is on, BhodlTheme() routes
    // straight to the neon palette regardless of useDarkTheme.
    val neonMode = themeMode == ThemeMode.Neon
    val useDarkTheme = themeMode.usesDarkPalette(systemDark)
    // Language: compose-resources reads `Locale.getDefault()`, which
    // SettingsStore.setLocaleOverride sets before the state change; App's
    // key(localeOverride) then rebuilds the screens (back stack kept) in
    // the new language.
    // Phase 5.0 — boot the BTC/fiat price cache once per process.
    // The PriceCache loop respects SettingsStore.fiatCurrency and is
    // idempotent on repeated start() calls.
    //
    // R3 — wire the registry seams here (the composition root) so the
    // `prices` package never reaches into the `wizard` layer directly.
    // PriceCache reads the open registry through the provider and signals
    // mutations through the data-changed callback; both resolve to
    // RegistryStore, but that dependency now lives at the root, not
    // inside the price subsystem.
    LaunchedEffect(Unit) {
        PriceCache.wireRegistry(
            provider = { io.bhodl.desktop.wizard.RegistryStore.current() },
            onDataChanged = { io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion() },
        )
        PriceCache.start()
    }
    // Phase 7.2.3 — boot the tracker / dashboard data cache. Runs a
    // 2-min background sync ticker and observes RegistryStore.dataVersion
    // so registry mutations propagate to every visible surface without
    // each composable owning its own loader. Idempotent.
    LaunchedEffect(Unit) { io.bhodl.desktop.data.TrackerCache.start() }
    // Phase 5.0 — refresh immediately when the user changes the fiat
    // currency in Settings so the dashboard reflects the new pick
    // without waiting for the 5-min loop tick.
    val fiatCurrency by SettingsStore.fiatCurrency
    LaunchedEffect(fiatCurrency) {
        PriceCache.refreshNow()
    }
    // Phase 9 — when the user turns ON "auto-fetch investment prices",
    // pull the quotes immediately instead of waiting for the next 5-min
    // loop tick. Keyed on the flag, so it fires on the off→on transition
    // (and harmlessly on first composition / when the registry is still
    // locked, where refreshInvestmentPrices early-returns).
    val autoFetchInvestments by SettingsStore.autoFetchInvestmentPrices
    LaunchedEffect(autoFetchInvestments) {
        if (autoFetchInvestments) {
            PriceCache.refreshInvestmentPrices()
        }
    }
    // Phase 3.10 — cultural color convention propagated through the
    // theme so every chart / KPI / amount color automatically reflects
    // the user's preference (Western green-positive vs Eastern red-positive).
    val colorConvention by SettingsStore.colorConvention
    // UX review 2026-10 — row density (Compact 32dp / Ultra-dense 24dp)
    // reaches every table through the theme's LocalBhodlDensity.
    val density by SettingsStore.density
    BhodlTheme(
        useDarkTheme = useDarkTheme,
        colorConvention = colorConvention,
        neonMode = neonMode,
        density = density,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BhodlTheme.colors.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Audit 2026-10 (M9) — settings.json could not be loaded.
                SettingsWarningBanner()
                Box(modifier = Modifier.weight(1f)) {
                    App()
                }
            }
        }
    }
    }
}
