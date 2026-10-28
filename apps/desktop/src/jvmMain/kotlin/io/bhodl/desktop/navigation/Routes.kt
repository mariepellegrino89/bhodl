package io.bhodl.desktop.navigation

import androidx.compose.runtime.Composable
import kotlinx.serialization.Serializable
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_nav_section_budget
import io.bhodl.shared.resources.onb_nav_section_goals
import io.bhodl.shared.resources.onb_nav_section_home
import io.bhodl.shared.resources.onb_nav_section_portfolio
import io.bhodl.shared.resources.onb_nav_section_reports
import io.bhodl.shared.resources.onb_nav_section_transactions
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.StringResource

/**
 * Type-safe navigation routes for the BHODL desktop app.
 *
 * Each `object` here is a destination `NavController` can navigate to.
 * Pairing them with [`NavHost`] in `App.kt` gives compile-time-checked
 * deep links (no string IDs) and serialisable arguments via
 * `kotlinx.serialization`.
 *
 * Boot flow (BHODL is watch-only — no seeds, no per-wallet PIN):
 * ```
 *  no registry  ─→ Welcome ─→ DevicePinSetup ─→ AddWatch ─→ Tracking
 *  registry     ─→ DevicePinUnlock ─→ (LegacyCleanup) ─→ Tracking
 *  Tracking wallet row ─→ OpenWallet(id) ─→ WalletSection
 * ```
 */
public sealed interface Route {
    /** First launch: what BHODL is, then "Create device PIN". */
    @Serializable
    public data object Welcome : Route

    /**
     * Create the device PIN that encrypts the registry (wallet list +
     * every tracker table) and each watch wallet's state.
     */
    @Serializable
    public data object DevicePinSetup : Route

    /** Returning user: the device PIN opens the registry. */
    @Serializable
    public data object DevicePinUnlock : Route

    /**
     * Shown once after an upgrade from a build that could hold seeds:
     * lists the leftover signing wallets and deletes them only after the
     * user confirms they have their recovery phrase elsewhere.
     */
    @Serializable
    public data object LegacyCleanup : Route

    /** Add a watch-only wallet (xpub, HD descriptor or address list). */
    @Serializable
    public data object AddWatch : Route

    /**
     * Open watch wallet `walletId` (device key, no PIN), park it in
     * `WalletStore`, then continue to the wallet workspace.
     */
    @Serializable
    public data class OpenWallet(val walletId: String) : Route

    /** The wallet workspace (header + Sparrow-style sub-tabs) on Transactions. */
    @Serializable
    public data object Dashboard : Route

    /**
     * The wallet workspace opened on a specific sub-tab; `tab` is the
     * `WalletTab` wire name (`transactions`, `receive`, `addresses`,
     * `utxos`, `settings`).
     */
    @Serializable
    public data class WalletSection(val tab: String = "transactions") : Route

    /**
     * The tracker surface, split into navigable sections. `section`
     * carries the [TrackerSection] wire name so the section is part of
     * the back stack and the NavigationRail selection stays in sync.
     */
    @Serializable
    public data class Tracking(val section: String = "home") : Route

    /** Wallet workspace on its Receive tab (kept for deep links). */
    @Serializable
    public data object Receive : Route

    /** Wallet workspace on its Transactions tab (kept for deep links). */
    @Serializable
    public data object History : Route

    /**
     * Detailed view for a single transaction — confirmation status,
     * timestamp, fee, label, and links into the explorer. The
     * Transactions tab shows the same content in a side pane; this full
     * page stays for deep links.
     */
    @Serializable
    public data class TransactionDetails(val txid: String) : Route

    /** Local address book — add / list / remove `(label, address)` pairs. */
    @Serializable
    public data object AddressBook : Route

    /** App-wide preferences (theme, units, Esplora / Tor, language, …). */
    @Serializable
    public data object Settings : Route

    /** Tracker module management — toggle the progressive-unlock modules. */
    @Serializable
    public data object Modules : Route

    /**
     * Security Hub: device PIN, auto-lock, connection, wallets and
     * labels-export status in one screen.
     */
    @Serializable
    public data object SecurityHub : Route
}

/**
 * Phase 9.0 (IA restructure) — the tracker's navigable sections.
 * Promoted from `TrackingScreen`'s internal tab indices so each
 * finance module surfaces as a first-class NavigationRail entry
 * (Kubera/Monarch convention: one page per domain, not one scroll
 * of stacked cards).
 *
 * `wire` is the string carried inside [Route.Tracking.section] so
 * the enum can evolve without breaking serialized back stacks.
 */
public enum class TrackerSection(public val wire: String, public val labelRes: StringResource) {
    /** Net-worth hero + KPI synthesis + wallet list. */
    HOME("home", Res.string.onb_nav_section_home),

    /** Cross-wallet ledger with composable category + time filters. */
    TRANSACTIONS("transactions", Res.string.onb_nav_section_transactions),

    /** Budget breakdowns, bills, recurring detection, rules. */
    BUDGET("budget", Res.string.onb_nav_section_budget),

    /** Savings + sinking goals. */
    GOALS("goals", Res.string.onb_nav_section_goals),

    /** Investments, manual assets, liabilities. */
    PORTFOLIO("portfolio", Res.string.onb_nav_section_portfolio),

    /** Report tables + power-user charts. */
    REPORTS("reports", Res.string.onb_nav_section_reports),
    ;

    /** UX review 2026-10 (rec 3) — localized section name (rail, palette). */
    public val label: String
        @Composable get() = stringResource(labelRes)

    public companion object {
        public fun fromWire(wire: String?): TrackerSection =
            entries.firstOrNull { it.wire == wire } ?: HOME
    }
}
