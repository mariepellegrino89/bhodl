package io.bhodl.desktop.settings

import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.BhodlDensity
import io.bhodl.ui.theme.ColorConvention
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.set_persist_corrupt_backup
import io.bhodl.shared.resources.set_persist_corrupt_backup_tor
import io.bhodl.shared.resources.set_persist_corrupt_nobackup
import io.bhodl.shared.resources.set_persist_corrupt_nobackup_tor
import org.jetbrains.compose.resources.getString

/**
 * T2-11 — JSON persistence layer for [SettingsStore].
 *
 * The store has historically been in-memory only — meaning a user who
 * enabled Tor, restarted the app, and went back to fetching prices
 * would silently leak hostnames to clearnet because the Tor flag
 * had reverted to its default. Same hazard for the fiat-currency
 * choice (price fetcher would stop) and the auto-fetch toggle.
 *
 * The persistence file is a small JSON document next to the wallet
 * data dir (`<data_dir>/settings.json`). The file is intentionally
 * NOT encrypted with the device PIN — a wallet lock should not also
 * lock the user's theme or unit preference; the trade-off is that
 * the file reveals which currency the user prefers (low-sensitivity
 * metadata).
 *
 * Versioning: `version` is bumped whenever the snapshot shape
 * changes incompatibly. Reading a newer version is rejected; reading
 * an older version uses default values for the missing fields.
 */
public object SettingsPersistence {

    public const val CURRENT_VERSION: Int = 1
    public const val FILE_NAME: String = "settings.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Wire format. Each field maps 1:1 to a [SettingsStore] property.
     * Enums are serialised by `name` so they remain readable and
     * order-independent across renames.
     */
    @Serializable
    public data class Snapshot(
        val version: Int = CURRENT_VERSION,
        val themeMode: String = ThemeMode.System.name,
        // Legacy (pre-audit-2026-10) single global override. Only READ,
        // for migration into [esploraUrls]; always written as null.
        val esploraUrl: String? = null,
        // Per-network Esplora overrides keyed by `Network.name`.
        val esploraUrls: Map<String, String> = emptyMap(),
        val localeOverride: String? = null,
        val torEnabled: Boolean = false,
        val torProxyUrl: String = SettingsStore.DEFAULT_TOR_PROXY_URL,
        val colorConvention: String = ColorConvention.WESTERN.name,
        val btcUnit: String = BtcUnit.SAT.name,
        val fiatCurrency: String = SettingsStore.DEFAULT_FIAT_CURRENCY.name,
        val autoFetchInvestmentPrices: Boolean = false,
        // Phase 7.2 — when true, the net-worth hero boots into the
        // HIDDEN display mode (bullets). Backward-compatible: missing
        // field defaults to false so existing snapshots keep behaving
        // as before.
        val privacyAutoHide: Boolean = false,
        // R4 (Phase 3.14 follow-up) — wallet UUIDs excluded from
        // cross-wallet totals and Tracker aggregations. Persisted so the
        // exclusion survives app restarts. Backward-compatible: absent in
        // old snapshots → empty set (every wallet included, the prior
        // default behaviour).
        val excludedWalletIds: List<String> = emptyList(),
        // Redesign 2026-10 — category colours and the user's own categories.
        val categoryColors: Map<String, Int> = emptyMap(),
        val customCategories: List<String> = emptyList(),
        // Phase 7.6 — last Unix timestamp the user dismissed the
        // monthly-review banner. Persisted so the dismiss survives
        // app restarts. 0 = never dismissed (default for old snapshots).
        val lastMonthlyReviewDismissedUnix: Long = 0L,
        // Phase 8.3.1 — Bitcoin Lens advanced display preferences.
        // Five fields total: one enum for how BTC is rendered, four
        // booleans for the operational surfaces where fiat is shown
        // alongside BTC. All have backward-compatible defaults so an
        // existing snapshot pre-8.3.1 rehydrates to the same UX the
        // user previously had (defaults: AUTO_THRESHOLD + all four
        // fiat surfaces ON).
        val unitFormat: String = UnitFormat.AUTO_THRESHOLD.name,
        val showFiatInBankCsv: Boolean = true,
        val showFiatInCurrentMonthFiatTx: Boolean = true,
        val showFiatInTaxExports: Boolean = true,
        val showFiatInInvoiceReceipts: Boolean = true,
        // Phase 8.3.2 — Bitcoin Lens net-worth fiat overlay. Default
        // ON so existing installs keep seeing the dashed fiat line on
        // the sparkline; opt-out via Settings → Bitcoin Lens (advanced).
        val showFiatOverlayOnNwChart: Boolean = true,
        // Tracker modules (progressive unlock). All default false so
        // existing installs start with the minimal view. Backward-
        // compatible: old snapshots simply get false for each flag.
        val moduleInvestments: Boolean = false,
        val moduleBudgetBills: Boolean = false,
        val moduleSavingsGoals: Boolean = false,
        val moduleManualAssets: Boolean = false,
        val moduleAnalyticsLabs: Boolean = false,
        val modulesBannerDismissed: Boolean = false,
        // UX review 2026-10 — unified "Bitcoin unit" (a [UnitFormat]
        // name). `null` = snapshot written before the unification: the
        // unit is then derived from the legacy [btcUnit] / [unitFormat]
        // pair (see [resolveUnitFormat]). [unitFormat] and [btcUnit]
        // are still written, mirroring this value, so an older build
        // reading a newer file keeps a sensible unit.
        val bitcoinUnit: String? = null,
        // UX review 2026-10 — BIP177 `₿` prefix for sats, opt-in.
        val bip177Notation: Boolean = false,
        // UX review 2026-10 — row density (a [BhodlDensity] name).
        val density: String = BhodlDensity.Compact.name,
        // UX review 2026-10 (item 14) — Security Hub: last BIP-329 labels
        // export (unix seconds keyed by wallet UUID). Dates only. Keys of
        // the seed-era fields (backup dates, reminder snooze, tracker
        // pairing) in older files are ignored (`ignoreUnknownKeys`).
        val labelsExportedAt: Map<String, Long> = emptyMap(),
        // UX review 2026-10 (item 16) — units explainer acknowledged.
        val onboardingUnitsSeen: Boolean = false,
    )

    /**
     * UX review 2026-10 — the unit to apply from [snapshot].
     *
     * A snapshot with [Snapshot.bitcoinUnit] uses it. Older snapshots
     * carry two independent unit settings; the old defaults were
     * `btcUnit = SAT` and `unitFormat = AUTO_THRESHOLD` (always written,
     * `encodeDefaults = true`), so:
     *  - a non-default `unitFormat` was an explicit Bitcoin-Lens choice → keep it;
     *  - otherwise `btcUnit = BTC` was an explicit choice → ALWAYS_BTC;
     *  - otherwise the user never touched either → the new default (sats).
     */
    internal fun resolveUnitFormat(snapshot: Snapshot): UnitFormat {
        snapshot.bitcoinUnit
            ?.let { runCatching { UnitFormat.valueOf(it) }.getOrNull() }
            ?.let { return it }
        val legacyFormat = runCatching { UnitFormat.valueOf(snapshot.unitFormat) }.getOrNull()
        if (legacyFormat != null && legacyFormat != UnitFormat.AUTO_THRESHOLD) return legacyFormat
        if (snapshot.btcUnit == BtcUnit.BTC.name) return UnitFormat.ALWAYS_BTC
        return SettingsStore.DEFAULT_UNIT_FORMAT
    }

    /**
     * Read the current [SettingsStore] state and return a [Snapshot]
     * suitable for serialisation. Pure read — no I/O.
     */
    public fun snapshot(): Snapshot = Snapshot(
        version = CURRENT_VERSION,
        themeMode = SettingsStore.themeMode.value.name,
        esploraUrl = null,
        esploraUrls = SettingsStore.esploraUrls.value.mapKeys { it.key.name },
        localeOverride = SettingsStore.localeOverride.value,
        torEnabled = SettingsStore.torEnabled.value,
        torProxyUrl = SettingsStore.torProxyUrl.value,
        colorConvention = SettingsStore.colorConvention.value.name,
        btcUnit = SettingsStore.btcUnit.value.name,
        fiatCurrency = SettingsStore.fiatCurrency.value.name,
        autoFetchInvestmentPrices = SettingsStore.autoFetchInvestmentPrices.value,
        privacyAutoHide = SettingsStore.privacyAutoHide.value,
        excludedWalletIds = SettingsStore.excludedWalletIds.value.toList(),
        // The real profile's, also while a test profile is open.
        categoryColors = SettingsStore.persistedProfileSettings().categoryColors,
        customCategories = SettingsStore.persistedProfileSettings().customCategories,
        lastMonthlyReviewDismissedUnix = SettingsStore.lastMonthlyReviewDismissedUnix.value,
        unitFormat = SettingsStore.unitFormat.value.name,
        showFiatInBankCsv = SettingsStore.showFiatInBankCsv.value,
        showFiatInCurrentMonthFiatTx = SettingsStore.showFiatInCurrentMonthFiatTx.value,
        showFiatInTaxExports = SettingsStore.showFiatInTaxExports.value,
        showFiatInInvoiceReceipts = SettingsStore.showFiatInInvoiceReceipts.value,
        showFiatOverlayOnNwChart = SettingsStore.showFiatOverlayOnNwChart.value,
        moduleInvestments = SettingsStore.persistedProfileSettings().modules.investments,
        moduleBudgetBills = SettingsStore.persistedProfileSettings().modules.budgetBills,
        moduleSavingsGoals = SettingsStore.persistedProfileSettings().modules.savingsGoals,
        moduleManualAssets = SettingsStore.persistedProfileSettings().modules.manualAssets,
        moduleAnalyticsLabs = SettingsStore.persistedProfileSettings().modules.analyticsLabs,
        modulesBannerDismissed = SettingsStore.modulesBannerDismissed.value,
        bitcoinUnit = SettingsStore.unitFormat.value.name,
        bip177Notation = SettingsStore.bip177Notation.value,
        density = SettingsStore.density.value.name,
        labelsExportedAt = SettingsStore.labelsExportedAt.value,
        onboardingUnitsSeen = SettingsStore.onboardingUnitsSeen.value,
    )

    /**
     * Apply a deserialised [Snapshot] to [SettingsStore]. Unknown enum
     * values (e.g. a future ThemeMode) fall back to the default.
     */
    /**
     * Network a legacy (global, pre-per-network) Esplora override most
     * likely serves, from its path: "/signet" -> SIGNET, "/testnet..." ->
     * TESTNET4, "/regtest" -> REGTEST, otherwise MAINNET (the common case
     * of a self-hosted mainnet node / onion).
     */
    internal fun legacyEsploraNetwork(url: String): io.bhodl.ffi.Network {
        val path = runCatching { java.net.URI(url.trim()).path }.getOrNull().orEmpty().lowercase()
        return when {
            "signet" in path -> io.bhodl.ffi.Network.SIGNET
            "testnet" in path -> io.bhodl.ffi.Network.TESTNET4
            "regtest" in path -> io.bhodl.ffi.Network.REGTEST
            else -> io.bhodl.ffi.Network.MAINNET
        }
    }

    public fun apply(snapshot: Snapshot) {
        runCatching { SettingsStore.setThemeMode(ThemeMode.valueOf(snapshot.themeMode)) }
        // Security review (A02): log a warning when the persisted Esplora URL
        // uses http:// with a non-local host. We still apply the URL so the
        // user does not silently lose their setting, but the console message
        // ensures the configuration is visible in crash/debug logs.
        val overrides: Map<io.bhodl.ffi.Network, String> = buildMap {
            // Legacy global override -> the network its URL most likely serves.
            snapshot.esploraUrl?.let { put(legacyEsploraNetwork(it), it) }
            snapshot.esploraUrls.forEach { (name, url) ->
                runCatching { io.bhodl.ffi.Network.valueOf(name) }.getOrNull()?.let { put(it, url) }
            }
        }
        io.bhodl.ffi.Network.entries.forEach { network ->
            val url = overrides[network]
            url?.let {
                val warning = SettingsStore.esploraUrlWarning(it)
                if (warning != null) {
                    System.err.println("[SettingsPersistence] WARNING: persisted esploraUrl — $warning")
                }
            }
            SettingsStore.setEsploraUrl(network, url)
        }
        SettingsStore.setLocaleOverride(snapshot.localeOverride)
        SettingsStore.setTorEnabled(snapshot.torEnabled)
        // setTorProxyUrl normalises a legacy socks5:// to socks5h://.
        SettingsStore.setTorProxyUrl(snapshot.torProxyUrl)
        runCatching { SettingsStore.setColorConvention(ColorConvention.valueOf(snapshot.colorConvention)) }
        // UX review 2026-10 — one unit preference (btcUnit + unitFormat
        // merged); see resolveUnitFormat for the legacy mapping.
        SettingsStore.setUnitFormat(resolveUnitFormat(snapshot))
        SettingsStore.setBip177Notation(snapshot.bip177Notation)
        runCatching { SettingsStore.setDensity(BhodlDensity.valueOf(snapshot.density)) }
        runCatching { SettingsStore.setFiatCurrency(FiatCurrency.valueOf(snapshot.fiatCurrency)) }
        SettingsStore.setAutoFetchInvestmentPrices(snapshot.autoFetchInvestmentPrices)
        SettingsStore.setPrivacyAutoHide(snapshot.privacyAutoHide)
        // Use replace (not the public setter) to avoid triggering an
        // immediate persistIfEnabled() during rehydrate — the caller
        // arranges the persistence hook AFTER rehydrate by contract.
        SettingsStore.replaceExcludedWalletIds(snapshot.excludedWalletIds.toSet())
        SettingsStore.replaceCategories(snapshot.categoryColors, snapshot.customCategories)
        SettingsStore.replaceLastMonthlyReviewDismissedUnix(snapshot.lastMonthlyReviewDismissedUnix)
        SettingsStore.replaceSecurityTimestamps(labelsExportedAt = snapshot.labelsExportedAt)
        SettingsStore.replaceOnboardingUnitsSeen(snapshot.onboardingUnitsSeen)
        // Phase 8.3.1 — Bitcoin Lens advanced preferences (the unit
        // format is applied above, merged with btcUnit).
        SettingsStore.setShowFiatInBankCsv(snapshot.showFiatInBankCsv)
        SettingsStore.setShowFiatInCurrentMonthFiatTx(snapshot.showFiatInCurrentMonthFiatTx)
        SettingsStore.setShowFiatInTaxExports(snapshot.showFiatInTaxExports)
        SettingsStore.setShowFiatInInvoiceReceipts(snapshot.showFiatInInvoiceReceipts)
        // Phase 8.3.2 — net-worth fiat overlay toggle.
        SettingsStore.setShowFiatOverlayOnNwChart(snapshot.showFiatOverlayOnNwChart)
        // Tracker modules.
        SettingsStore.setModuleInvestments(snapshot.moduleInvestments)
        SettingsStore.setModuleBudgetBills(snapshot.moduleBudgetBills)
        SettingsStore.setModuleSavingsGoals(snapshot.moduleSavingsGoals)
        SettingsStore.setModuleManualAssets(snapshot.moduleManualAssets)
        SettingsStore.setModuleAnalyticsLabs(snapshot.moduleAnalyticsLabs)
        SettingsStore.setModulesBannerDismissed(snapshot.modulesBannerDismissed)
    }

    /**
     * Serialise the current [SettingsStore] state to [file]. Writes are
     * atomic-ish: write to `<file>.tmp` then rename over the target,
     * so a crash mid-write leaves the previous JSON intact.
     */
    @Synchronized
    public fun save(file: File): Result<Unit> = runCatching {
        file.parentFile?.mkdirs()
        val text = json.encodeToString(Snapshot.serializer(), snapshot())
        val tmp = File(file.parentFile ?: file.canonicalFile.parentFile, "${file.name}.tmp")
        // Security review — pre-create the tmp with owner-only perms
        // (600) BEFORE writing content, so there is no window where
        // another local user can read the snapshot. Best-effort: on
        // non-POSIX filesystems (Windows) `createFile` with a POSIX
        // attribute throws and we fall back to the platform default
        // ACLs; a leftover tmp from a crashed run also lands in the
        // catch (writeText below overwrites it either way).
        runCatching {
            java.nio.file.Files.createFile(
                tmp.toPath(),
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                ),
            )
        }
        tmp.writeText(text, Charsets.UTF_8)
        // Files.move + REPLACE_EXISTING, NOT java.io.File#renameTo: on
        // Windows `renameTo` fails whenever the destination exists, so
        // every save after the first one would fall back to
        // delete-then-rename — a window in which a crash leaves NO
        // settings file at all and `torEnabled` silently reverts to
        // false on the next launch (clearnet price fetches). Files.move
        // maps to rename(2) on POSIX and MoveFileExW on Windows, both
        // of which replace the target in one step. Same pattern as
        // PinAttemptTracker.persistToDisk.
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /**
     * Read [file] into a [Snapshot]. Returns `null` when the file does
     * not exist (first launch), a [Result.failure] when the file is
     * present but unparseable or carries a future version.
     */
    public fun load(file: File): Result<Snapshot?> = runCatching {
        if (!file.exists()) return@runCatching null
        val text = file.readText(Charsets.UTF_8)
        val parsed = json.decodeFromString(Snapshot.serializer(), text)
        if (parsed.version > CURRENT_VERSION) {
            error("settings.json version ${parsed.version} is newer than build (max $CURRENT_VERSION)")
        }
        parsed
    }

    /**
     * Convenience: read [file] (if any) and apply it to [SettingsStore].
     * Used once on app start. Silently swallows missing file (first
     * launch) and surfaces parse errors as a [Result.failure] so the
     * caller can choose to warn the user vs. continue with defaults.
     *
     * Audit 2026-10 (M9) — on failure we no longer leave the store at
     * plain defaults (torEnabled = false!) for the next setter to
     * overwrite the original file with:
     *  1. the unreadable file is copied to `settings.json.corrupt-<ts>`
     *     BEFORE anything can be written over it ([backupCorrupt]);
     *  2. privacy fails CLOSED: Tor is enabled when the raw text shows
     *     `"torEnabled": true`, or when the file could not even be read;
     *  3. [SettingsStore.persistenceWarning] is set so the UI can tell
     *     the user their preferences were reset.
     */
    public fun rehydrate(file: File): Result<Boolean> {
        val result = load(file).map {
            if (it == null) {
                false
            } else {
                apply(it)
                true
            }
        }
        result.exceptionOrNull()?.let { e -> recoverFromCorrupt(file, e) }
        return result
    }

    /** `"torEnabled": true` anywhere in the raw (possibly corrupt) text. */
    private val TOR_ENABLED_TRUE = Regex(""""torEnabled"\s*:\s*true""")

    private fun recoverFromCorrupt(file: File, cause: Throwable) {
        val raw: String? = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
        val backup = backupCorrupt(file)
        // Fail closed for privacy: unreadable file, or Tor was on.
        if (raw == null || TOR_ENABLED_TRUE.containsMatchIn(raw)) {
            SettingsStore.setTorEnabled(true)
        }
        // Security review (item 13) — "Start with balances hidden" is
        // unknown too: start hidden for this session (fail closed). Only
        // the session flag; the stored preference is not invented.
        SettingsStore.setBalancesHidden(true)
        System.err.println(
            "[SettingsPersistence] settings.json could not be loaded " +
                "(${cause::class.simpleName}); defaults in use" +
                (backup?.let { ", original kept as ${it.name}" } ?: ""),
        )
        // Shown in the startup banner. Resolved here (outside composition)
        // in the default locale: with the settings file unreadable the
        // language preference is the system one anyway.
        val torKept = SettingsStore.torEnabled.value
        SettingsStore.setPersistenceWarning(
            kotlinx.coroutines.runBlocking {
                if (backup != null) {
                    getString(
                        if (torKept) Res.string.set_persist_corrupt_backup_tor else Res.string.set_persist_corrupt_backup,
                        backup.name,
                    )
                } else {
                    getString(
                        if (torKept) Res.string.set_persist_corrupt_nobackup_tor else Res.string.set_persist_corrupt_nobackup,
                    )
                }
            },
        )
    }

    /**
     * Copy [file] to `<name>.corrupt-<epochMillis>` next to it. Returns
     * the backup, or `null` when the copy failed.
     */
    public fun backupCorrupt(file: File, nowMs: Long = System.currentTimeMillis()): File? {
        if (!file.exists()) return null
        val target = File(file.parentFile ?: file.absoluteFile.parentFile, "${file.name}.corrupt-$nowMs")
        return runCatching {
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            target
        }.getOrNull()
    }

    /**
     * `true` when [rehydrate] reported a problem AND the corrupt
     * original could not be backed up — the caller must then NOT
     * install the write-through hook, or the next setter would destroy
     * the only copy.
     */
    public fun unsafeToPersist(file: File): Boolean {
        if (SettingsStore.persistenceWarning.value == null) return false
        val dir = file.parentFile ?: file.absoluteFile.parentFile ?: return true
        val hasBackup = dir.listFiles()?.any { it.name.startsWith("${file.name}.corrupt-") } ?: false
        return file.exists() && !hasBackup
    }
}
