package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.dashboard_button_settings
import io.bhodl.shared.resources.wview_nav_activity
import io.bhodl.shared.resources.wview_nav_budget
import io.bhodl.shared.resources.wview_nav_goals
import io.bhodl.shared.resources.wview_nav_home
import io.bhodl.shared.resources.wview_nav_portfolio
import io.bhodl.shared.resources.wview_nav_reports
import io.bhodl.shared.resources.wview_nav_wallet
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 9.0 (IA restructure) — the persistent left NavigationRail.
 *
 * The Phase-4.0 three-tab shell (Wallets / Dashboard / Analytics)
 * grew into a section rail following the finance-tracker convention
 * (Kubera / Monarch / Copilot): one rail entry per domain instead of
 * a single dashboard scroll. Entries:
 *
 *  - **Home** — net-worth hero + KPI synthesis + wallet list
 *  - **Transactions** — cross-wallet ledger with composable filters
 *  - **Budget** / **Goals** / **Portfolio** — module-gated: each entry
 *    appears only when the matching progressive-unlock module is on
 *    (Settings → Modules), replacing the old "every module is one
 *    more card in the same scroll" layout
 *  - **Reports** — report tables + power-user charts
 *  - **Wallet** — custody operations (Send / Receive / signing)
 *  - **Settings** — pinned at the bottom of the rail
 *
 * The rail itself stays dumb: it renders icons + labels and forwards
 * [onTabSelected]; `App.kt` decides what to navigate to. Action routes
 * (Send / Receive / History / Settings) are NOT wrapped — entering
 * them hides the rail, giving the action screen full width.
 *
 * File name kept as `TwoTabShell` for git-history continuity
 * (Phase 3.2 → 4.0 → 9.0 rename trail).
 */
@Composable
public fun TwoTabShell(
    selected: Tab,
    onTabSelected: (Tab) -> Unit,
    content: @Composable () -> Unit,
) {
    val modBudgetBills by SettingsStore.moduleBudgetBills
    val modSavingsGoals by SettingsStore.moduleSavingsGoals
    val modInvestments by SettingsStore.moduleInvestments
    val modManualAssets by SettingsStore.moduleManualAssets
    val anyFinanceModule = SettingsStore.anyModuleEnabled

    // UX review 2026-10 (item 17) — destinations are data; the suite
    // renders them as a rail (desktop) or a bottom bar (compact hosts).
    val destinations = buildList {
        add(NavDestination(Tab.Home, Icons.Filled.Home, stringResource(Res.string.wview_nav_home), TwoTabShellTags.TAB_HOME))
        add(NavDestination(Tab.Transactions, Icons.Filled.ReceiptLong, stringResource(Res.string.wview_nav_activity), TwoTabShellTags.TAB_TRANSACTIONS))
        if (modBudgetBills) {
            add(NavDestination(Tab.Budget, Icons.Filled.Payments, stringResource(Res.string.wview_nav_budget), TwoTabShellTags.TAB_BUDGET))
        }
        if (modSavingsGoals) {
            add(NavDestination(Tab.Goals, Icons.Filled.Flag, stringResource(Res.string.wview_nav_goals), TwoTabShellTags.TAB_GOALS))
        }
        if (modInvestments || modManualAssets) {
            add(NavDestination(Tab.Portfolio, Icons.Filled.PieChart, stringResource(Res.string.wview_nav_portfolio), TwoTabShellTags.TAB_PORTFOLIO))
        }
        if (anyFinanceModule) {
            add(NavDestination(Tab.Reports, Icons.Filled.QueryStats, stringResource(Res.string.wview_nav_reports), TwoTabShellTags.TAB_REPORTS))
        }
        add(NavDestination(Tab.Wallet, Icons.Filled.AccountBalanceWallet, stringResource(Res.string.wview_nav_wallet), TwoTabShellTags.TAB_WALLET))
        add(
            NavDestination(
                Tab.Settings, Icons.Filled.Settings, stringResource(Res.string.dashboard_button_settings), TwoTabShellTags.TAB_SETTINGS,
                pinnedEnd = true,
            ),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
    ) {
        BhodlNavigationSuite(
            // Desktop: always the rail (the window minimum is above the
            // compact breakpoint). A mobile host passes allowBottomBar = true.
            layout = navigationLayoutFor(widthDp = Int.MAX_VALUE, allowBottomBar = false),
            destinations = destinations,
            selected = selected,
            onSelect = onTabSelected,
            railTestTag = TwoTabShellTags.RAIL,
            content = content,
        )
    }
}

/**
 * Rail entries. Tracker sections map 1:1 onto [TrackerSection];
 * [Wallet] and [Settings] are non-tracker destinations.
 */
public enum class Tab {
    Home, Transactions, Budget, Goals, Portfolio, Reports, Wallet, Settings;

    public companion object {
        public fun fromSection(section: TrackerSection): Tab = when (section) {
            TrackerSection.HOME -> Home
            TrackerSection.TRANSACTIONS -> Transactions
            TrackerSection.BUDGET -> Budget
            TrackerSection.GOALS -> Goals
            TrackerSection.PORTFOLIO -> Portfolio
            TrackerSection.REPORTS -> Reports
        }
    }

    /** Null for [Wallet] / [Settings] — they are not tracker sections. */
    public fun toSection(): TrackerSection? = when (this) {
        Home -> TrackerSection.HOME
        Transactions -> TrackerSection.TRANSACTIONS
        Budget -> TrackerSection.BUDGET
        Goals -> TrackerSection.GOALS
        Portfolio -> TrackerSection.PORTFOLIO
        Reports -> TrackerSection.REPORTS
        Wallet, Settings -> null
    }
}

public object TwoTabShellTags {
    public const val RAIL: String = "shell.rail"
    public const val TAB_WALLET: String = "shell.tab.wallet"
    public const val TAB_HOME: String = "shell.tab.home"
    public const val TAB_TRANSACTIONS: String = "shell.tab.transactions"
    public const val TAB_BUDGET: String = "shell.tab.budget"
    public const val TAB_GOALS: String = "shell.tab.goals"
    public const val TAB_PORTFOLIO: String = "shell.tab.portfolio"
    public const val TAB_REPORTS: String = "shell.tab.reports"
    public const val TAB_SETTINGS: String = "shell.tab.settings"
}
