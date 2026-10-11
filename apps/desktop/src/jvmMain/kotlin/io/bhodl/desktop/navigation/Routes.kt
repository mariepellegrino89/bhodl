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
 * Phase-2 alpha-series flow:
 * ```
 *  Welcome ─┬─→ GenerateMnemonic ─→ ConfirmMnemonic ─→ PinSetup ─→ Dashboard
 *           └─→ (future: RestoreMnemonic ─→ PinSetup)
 *
 *  Boot guard (handled in App.kt before NavHost mounts):
 *    salt file exists ─→ Unlock ─→ Dashboard
 *    salt file absent  ─→ Welcome
 * ```
 */
public sealed interface Route {
    /** Onboarding hub: "create new wallet" vs "restore existing". */
    @Serializable
    public data object Welcome : Route

    /** Show the freshly generated 12-word mnemonic for the user to write down. */
    @Serializable
    public data object GenerateMnemonic : Route

    /**
     * Type back an existing 12-word BIP39 phrase to restore an offline
     * backup. On submit we hand the phrase to `PinSetup` exactly like
     * the freshly-generated path does — the only difference is that the
     * mnemonic was typed by the user instead of generated.
     */
    @Serializable
    public data object RestoreMnemonic : Route

    /**
     * Restore from an age-encrypted backup blob exported from this
     * (or another) BHODL build. The user pastes the blob + types the
     * backup password; on success we forward to `PinSetup` with the
     * recovered mnemonic AND passphrase already pre-populated, so the
     * Advanced (BIP-39 passphrase) section opens automatically.
     */
    @Serializable
    public data object RestoreBackup : Route

    /**
     * Ask the user to type back a couple of mnemonic words to confirm they
     * wrote it.
     *
     * Audit 2026-10 (M10) — the phrase itself is NOT a route argument
     * any more: `secretId` is an opaque key into
     * [io.bhodl.desktop.wizard.SetupSecrets], which holds the mnemonic
     * as a wipeable CharArray, so the seed never lands in the
     * serialised back stack.
     *
     * `network` is the lower-case enum name (`signet` / `regtest` /
     * `testnet4`) chosen on `GenerateMnemonic` and forwarded to
     * `PinSetup` so the persisted wallet matches the user's pick.
     */
    @Serializable
    public data class ConfirmMnemonic(val secretId: String, val network: String = "signet") : Route

    /**
     * Set the local PIN that derives the `SQLCipher` encryption key.
     *
     * Receives the verified mnemonic from `ConfirmMnemonic` /
     * `RestoreMnemonic` via `secretId` (opaque key into
     * [io.bhodl.desktop.wizard.SetupSecrets], audit 2026-10 M10). The
     * `RestoreBackup` flow also parks the recovered BIP-39 passphrase
     * there so it lands pre-populated and the Advanced section opens
     * by default. `network` is the lower-case enum
     * name (defaults to `signet`) selected in the entry-point screen
     * and persisted via `NetworkPersistence` after the wallet is created.
     */
    @Serializable
    public data class PinSetup(
        val secretId: String,
        /**
         * `true` when the PinSetup arrives from a Restore flow
         * (mnemonic or encrypted backup). Lets the Dashboard render
         * a "Restoring wallet…" banner during the first sync, which
         * is a full chain scan and slower than a normal incremental
         * sync.
         */
        val isRestore: Boolean = false,
        val network: String = "signet",
    ) : Route

    /**
     * Returning user — DB exists, only the PIN is needed.
     *
     * `walletId` (Phase 3.2 sub-B+) is the UUID of the wallet the user
     * tapped in the Welcome picker. `null` for legacy boot flows that
     * preceded the picker (`registryFile` absent, single-wallet
     * install): `UnlockScreen` falls back to
     * `WalletPaths.activeWalletDir()` in that case.
     */
    @Serializable
    public data class Unlock(val walletId: String? = null) : Route

    /**
     * Phase 3.2 boot-time device-PIN gate. Shown the first time the
     * process boots and finds an existing `registry.sqlite` without
     * a populated [`io.bhodl.desktop.wizard.RegistryStore`]. On
     * success the registry handle is stashed in the store; the user
     * then proceeds to the wallet picker (or, transitionally during
     * Phase 3.2 sub-phase A, the legacy [`Unlock`] screen).
     */
    @Serializable
    public data object DevicePinUnlock : Route

    /**
     * Phase 3.2 sub-E partial — "+ Add new wallet" → "Add watch-only".
     * Reuses the form from the debug screen but doesn't require the
     * device-PIN re-prompt: when this route is reachable, `RegistryStore`
     * is already populated.
     */
    @Serializable
    public data object AddWatch : Route

    /**
     * Phase 3.0 storage migration. Routed to when a pre-3.0 single-wallet
     * install is detected at boot but no `registry.sqlite` exists yet.
     * The screen asks the user for a new device PIN and a confirmation
     * of their existing wallet PIN, then calls
     * `bhodl_uniffi.runMigrationIfNeeded` which performs the
     * two-phase commit (atomic backup → registry creation → file
     * moves → cleanup). Single-use route — once the migration
     * succeeds the boot guard never sees this state again.
     */
    @Serializable
    public data object Migration : Route

    /**
     * The signing-wallet workspace (header + Sparrow-style sub-tabs),
     * opened on its Transactions tab.
     */
    @Serializable
    public data object Dashboard : Route

    /**
     * UX review 2026-10 — the wallet workspace opened on a specific
     * sub-tab; `tab` is the `WalletTab` wire name (`transactions`,
     * `send`, `receive`, `addresses`, `utxos`, `settings`). Used by
     * Settings › "Open wallet settings" and deep links.
     */
    @Serializable
    public data class WalletSection(val tab: String = "transactions") : Route

    /**
     * Phase 3.2 sub-D — read-only Dashboard for an activated watch
     * wallet. Reached by tapping a watch row in the Welcome picker.
     * Carries the wallet UUID so the screen knows which row to open
     * via `RegistryHandle.openWatch(walletId)`.
     */
    @Serializable
    public data class WatchDashboard(val walletId: String) : Route

    /**
     * Phase 3.2 sub-F — promote a watch-only wallet to a signing
     * wallet by pairing it with its original BIP-39 seed.
     * Multi-step UX per the security review (§F2): device PIN
     * re-auth, descriptor preview, seed entry + xpub match check,
     * wallet PIN. Reached from the WatchDashboard's "Promote to
     * signing" button.
     */
    @Serializable
    public data class PromoteWatch(val walletId: String) : Route

    /**
     * Phase 9.0 (IA restructure) — the tracker surface, now split into
     * navigable sections instead of internal tabs. `section` carries
     * the [TrackerSection] wire name; each rail entry navigates to
     * `Tracking(section = ...)` so the section is part of the back
     * stack and the NavigationRail selection stays in sync.
     *
     * Route name kept as `Tracking` (not "Dashboard") for git-history
     * continuity with the Phase 3.2/4.0 call sites.
     */
    @Serializable
    public data class Tracking(val section: String = "home") : Route

    /** Wallet workspace on its Send tab (kept for deep links). */
    @Serializable
    public data object Send : Route

    /**
     * Wallet workspace on its label-first Receive tab (kept for deep
     * links).
     */
    @Serializable
    public data object Receive : Route

    /**
     * Confirmation screen showing the decoded send proposal (recipient,
     * amount, fee). Carries the unsigned PSBT base64 across the back
     * stack so the screen can sign + broadcast without rebuilding.
     *
     * Phase 7.2.3 — `category` carries an optional inline category set
     * by the user on the Send form, so the resulting PaymentEvent is
     * pre-categorised on broadcast (no round trip to History to label
     * it after the fact). Null = no category provided.
     */
    @Serializable
    public data class ReviewSend(
        val unsignedPsbtBase64: String,
        val toAddress: String,
        val amountSat: Long,
        val category: String? = null,
    ) : Route

    /** Result screen — shown after a successful broadcast. */
    @Serializable
    public data class SendSuccess(val txid: String) : Route

    /** Wallet workspace on its Transactions tab (kept for deep links). */
    @Serializable
    public data object History : Route

    /**
     * Detailed view for a single transaction — confirmation status,
     * timestamp, fee, label, and links into the mempool.space
     * explorer. The Transactions tab shows the same content in a side
     * pane; this full page stays for deep links.
     */
    @Serializable
    public data class TransactionDetails(val txid: String) : Route

    /**
     * Replace-by-fee bump screen. The History view links here for
     * pending transactions; the screen rebuilds the PSBT at a higher
     * fee rate and routes to `Route.SendSuccess` after broadcast.
     */
    @Serializable
    public data class BumpFee(val originalTxid: String) : Route

    /**
     * Child-Pays-For-Parent speed-up screen. Builds a child PSBT
     * spending only the wallet's outputs of `parentTxid` at a high
     * fee rate. `kind` selects copy + button label only:
     * - `incoming-speed-up` — receiver wants pending inbound to confirm
     * - `change-rescue` — sender wants stuck change to drag the parent
     */
    @Serializable
    public data class Cpfp(val parentTxid: String, val kind: String) : Route

    /** Local address book — add / list / remove `(label, address)` pairs. */
    @Serializable
    public data object AddressBook : Route

    /**
     * App-wide preferences: theme (dark / light / system), Esplora
     * endpoint, language. Reachable from the Dashboard. State is held
     * in [`io.bhodl.desktop.settings.SettingsStore`] for now — moves
     * to persistent storage in a follow-up.
     */
    @Serializable
    public data object Settings : Route

    /** Tracker module management — toggle the 5 progressive-unlock modules. */
    @Serializable
    public data object Modules : Route

    /**
     * UX review 2026-10 (item 14) — Security Hub: backup, PIN,
     * auto-lock, connection, wallets and labels-export status in one
     * screen. Reachable from Settings and the footer shield icon.
     */
    @Serializable
    public data object SecurityHub : Route

    /**
     * Security Hub › "Verify now": re-auth with the wallet PIN, then the
     * recovery-phrase word challenge for the loaded signing wallet.
     */
    @Serializable
    public data object VerifyBackup : Route

    /**
     * Phase 3.1 debug-only screen for smoke-testing watch wallets before
     * the Phase 3.2 Welcome redesign lands. Entry visible in Settings only
     * when `BHODL_DEBUG=1` is in the process environment.
     *
     * Will be removed once Phase 3.2 ships the proper wallet picker.
     */
    @Serializable
    public data object DebugWatch : Route
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
