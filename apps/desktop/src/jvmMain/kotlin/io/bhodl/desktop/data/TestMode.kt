package io.bhodl.desktop.data

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import io.bhodl.desktop.AppScope
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.prices.PriceFetcher
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.RegistryHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Test mode ("modalità test") — a separate profile with test data, for
 * trying the app and for developers who share the same data.
 *
 * - The test profile lives in its own directory (`~/.bhodl/demo`), with
 *   its own registry opened with the public [DEMO_PIN]: while it is
 *   open the real registry stays closed, so real data can be neither
 *   seen nor changed. Leaving goes through the real device-PIN gate.
 * - It never touches the network: wallets don't sync (their
 *   transactions are in the profile, see [demoWalletFigures]), BTC
 *   rates come from the profile (the registry answers offline), every
 *   HTTP call of the price layer is refused.
 * - It travels as one `.bhodl-test` file ([TestModePackage]) holding the
 *   profile and its marked initial state ("stato iniziale"). Dates move
 *   by the days between the file's day and today when it is opened, so
 *   shared data stays recent.
 */
public object TestMode {

    /** Public PIN of every test profile: its data is meant to be shared. */
    public const val DEMO_PIN: String = "246810"

    /** File extension of a shared test profile. */
    public const val FILE_EXTENSION: String = "bhodl-test"

    /** Bundled project test data (`resources/demo/base.bhodl-test`). */
    internal const val PROJECT_DATA_RESOURCE: String = "/demo/base.bhodl-test"

    private const val MODULES_META_KEY = "profile_settings"
    private const val PROFILE_SUBDIR = "demo"
    private const val BASELINE_SUBDIR = "demo-baseline"

    private val _active = mutableStateOf(false)

    /** True while the test profile is open. */
    public val active: State<Boolean> get() = _active

    public fun isActive(): Boolean = _active.value

    /** Root of the profiles (test seam; production: `~/.bhodl`). */
    internal var rootDirProvider: () -> File = { WalletPaths.defaultDataDir() }

    /** Opens a registry (test seam: Argon2id + SQLCipher in production). */
    internal var openRegistry: (dataDir: String, pin: String) -> RegistryHandle =
        { dir, pin -> RegistryHandle.createOrLoad(dataDir = dir, devicePin = pin) }

    /** Today, for the date shift (test seam). */
    internal var today: () -> LocalDate = { LocalDate.now(ZoneOffset.UTC) }

    /** The project data, when bundled (test seam). */
    internal var projectData: () -> ByteArray? = {
        TestMode::class.java.getResourceAsStream(PROJECT_DATA_RESOURCE)?.use { it.readBytes() }
    }

    internal fun profileDir(): File = File(rootDirProvider(), PROFILE_SUBDIR)
    internal fun baselineDir(): File = File(rootDirProvider(), BASELINE_SUBDIR)

    private val mutex = Mutex()

    // What test mode replaced, put back on exit.
    private var savedDataDir: (() -> File)? = null
    private var savedBtcPrice: ((FiatCurrency) -> Double?)? = null
    private var savedQuote: ((String) -> io.bhodl.desktop.prices.YahooQuote?)? = null
    private var savedHttp: ((String) -> String)? = null

    // ------------------------------------------------------------------
    // Enter / exit
    // ------------------------------------------------------------------

    /**
     * Open the test profile (the real session must be closed already).
     * A first start loads the project data, or builds it when the app
     * has none bundled.
     */
    public suspend fun enter(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!WalletPaths.from(profileDir()).registryExists()) {
                val bundled = projectData()
                if (bundled != null) {
                    installPackage(TestModePackage.read(bundled), keepDates = false)
                } else {
                    createFromSeed()
                }
            }
            openAndActivate()
        }
    }

    /**
     * Close the test profile and put the real app back. The caller
     * routes to the real device-PIN gate.
     */
    public fun exit() {
        if (!_active.value) return
        closeSession()
        deactivate()
    }

    // ------------------------------------------------------------------
    // The test profile's commands
    // ------------------------------------------------------------------

    /** Write the test profile and its initial state to [target]. */
    public suspend fun exportTo(target: File): Unit = whileClosed {
        val pkg = TestModePackage(
            manifest = TestModePackage.Manifest(
                anchorDay = today().toString(),
                baselineAnchorDay = readBaselineAnchor()?.toString(),
            ),
            current = TestModePackage.readDir(profileDir()),
            baseline = baselineProfileDir().takeIf { it.isDirectory }?.let(TestModePackage::readDir),
        )
        target.parentFile?.mkdirs()
        target.writeBytes(pkg.write())
    }

    /**
     * Replace the test data with [source]'s. [keepDates] keeps the
     * file's dates instead of moving them to today.
     *
     * @throws TestModeException when the file is not a test profile.
     */
    public suspend fun importFrom(source: File, keepDates: Boolean = false): Unit =
        importBytes(readFileCapped(source), keepDates)

    /** Replace the test data with the project's (bundled, or built). */
    public suspend fun loadProjectData(): Unit {
        val bundled = projectData()
        if (bundled != null) {
            importBytes(bundled, keepDates = false)
        } else {
            whileClosed { createFromSeed() }
        }
    }

    /** The current test data becomes the initial state. */
    public suspend fun markInitialState(): Unit = whileClosed {
        val target = baselineProfileDir()
        replaceDir(target) { tmp -> profileDir().copyRecursively(tmp, overwrite = true) }
        File(baselineDir(), BASELINE_ANCHOR_FILE).writeText(today().toString())
    }

    /** Back to the initial state (dates moved to today). */
    public suspend fun restoreInitialState(): Unit = whileClosed {
        val source = baselineProfileDir()
        if (!source.isDirectory) throw TestModeException(TestModeException.Reason.NO_INITIAL_STATE)
        replaceDir(profileDir()) { tmp -> source.copyRecursively(tmp, overwrite = true) }
        shiftToToday(readBaselineAnchor())
    }

    /** True when an initial state is marked. */
    public fun hasInitialState(): Boolean = baselineProfileDir().isDirectory

    /** The day the initial state was marked, if any. */
    public fun initialStateDay(): LocalDate? = readBaselineAnchor()

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private const val BASELINE_ANCHOR_FILE = "anchor-day.txt"
    private const val MAX_PACKAGE_BYTES = 200 * 1024 * 1024

    private fun baselineProfileDir(): File = File(baselineDir(), "profile")

    private fun readBaselineAnchor(): LocalDate? =
        runCatching { LocalDate.parse(File(baselineDir(), BASELINE_ANCHOR_FILE).readText().trim()) }.getOrNull()

    private fun readFileCapped(file: File): ByteArray {
        if (file.length() > MAX_PACKAGE_BYTES) throw TestModeException(TestModeException.Reason.TOO_LARGE)
        return file.readBytes()
    }

    private suspend fun importBytes(bytes: ByteArray, keepDates: Boolean) {
        // Read and check the file before closing anything.
        val pkg = withContext(Dispatchers.IO) { TestModePackage.read(bytes) }
        whileClosed { installPackage(pkg, keepDates) }
    }

    /**
     * Close the session, run [block] on the closed profile, reopen it.
     * The profile is reopened even when [block] fails.
     */
    private suspend fun whileClosed(block: () -> Unit): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            closeSession()
            try {
                block()
            } finally {
                openAndActivate()
            }
        }
    }

    /**
     * Unpack [pkg] over the test profile, after checking in a scratch
     * directory that it opens with the public PIN and is a test profile
     * (never a real registry), then move its dates to today.
     */
    private fun installPackage(pkg: TestModePackage, keepDates: Boolean) {
        val scratch = File(rootDirProvider(), "demo-import.tmp").apply { deleteRecursively() }
        try {
            TestModePackage.writeDir(pkg.current, File(scratch, "current"))
            val handle = runCatching { openRegistry(File(scratch, "current").absolutePath, DEMO_PIN) }
                .getOrElse { throw TestModeException(TestModeException.Reason.NOT_A_TEST_PROFILE, it) }
            try {
                if (!handle.isDemoProfile()) throw TestModeException(TestModeException.Reason.NOT_A_TEST_PROFILE)
            } finally {
                handle.destroy()
            }
            replaceDir(profileDir()) { tmp -> File(scratch, "current").copyRecursively(tmp, overwrite = true) }
            val baseline = pkg.baseline
            replaceDir(baselineProfileDir()) { tmp ->
                if (baseline != null) TestModePackage.writeDir(baseline, tmp)
                else File(scratch, "current").copyRecursively(tmp, overwrite = true)
            }
            val baselineAnchor = if (baseline != null) pkg.manifest.baselineAnchorDay else pkg.manifest.anchorDay
            File(baselineDir(), BASELINE_ANCHOR_FILE).writeText(baselineAnchor ?: pkg.manifest.anchorDay)
            if (!keepDates) shiftToToday(runCatching { LocalDate.parse(pkg.manifest.anchorDay) }.getOrNull())
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** A fresh test profile from [DemoSeed]; it is its own initial state. */
    private fun createFromSeed() {
        replaceDir(profileDir()) { tmp ->
            val handle = openRegistry(tmp.absolutePath, DEMO_PIN)
            try {
                handle.markDemoProfile()
                DemoSeed.populate(handle, today())
            } finally {
                handle.destroy()
            }
        }
        replaceDir(baselineProfileDir()) { tmp -> profileDir().copyRecursively(tmp, overwrite = true) }
        File(baselineDir(), BASELINE_ANCHOR_FILE).writeText(today().toString())
    }

    /** Move the closed profile's dates from [anchor] to today. */
    private fun shiftToToday(anchor: LocalDate?) {
        val days = anchor?.let { ChronoUnit.DAYS.between(it, today()) } ?: 0L
        if (days == 0L) return
        val handle = openRegistry(profileDir().absolutePath, DEMO_PIN)
        try {
            handle.shiftDemoDates(days)
        } finally {
            handle.destroy()
        }
    }

    /**
     * Build the new content of [target] in a sibling directory, then
     * swap it in (a failure leaves [target] as it was). Windows may
     * hold a just-closed SQLite file for a moment: retried briefly.
     */
    private fun replaceDir(target: File, fill: (File) -> Unit) {
        val fresh = File(target.parentFile, "${target.name}.new").apply { deleteRecursively() }
        fresh.mkdirs()
        try {
            fill(fresh)
        } catch (e: Throwable) {
            fresh.deleteRecursively()
            throw e
        }
        val old = File(target.parentFile, "${target.name}.old").apply { deleteRecursively() }
        retrying { if (target.exists() && !target.renameTo(old)) error("cannot move ${target.name}") }
        retrying { if (!fresh.renameTo(target)) error("cannot move ${fresh.name}") }
        old.deleteRecursively()
    }

    private fun retrying(block: () -> Unit) {
        var last: Throwable? = null
        repeat(25) {
            try {
                block()
                return
            } catch (e: IllegalStateException) {
                last = e
                Thread.sleep(200)
            }
        }
        throw last ?: IllegalStateException("retry failed")
    }

    private fun closeSession() {
        WalletStore.clear()
        RegistryStore.clear()
    }

    /** Open the test profile and switch the app onto it. */
    private fun openAndActivate() {
        val handle = openRegistry(profileDir().absolutePath, DEMO_PIN)
        if (!runCatching { handle.isDemoProfile() }.getOrDefault(false)) {
            handle.destroy()
            throw TestModeException(TestModeException.Reason.NOT_A_TEST_PROFILE)
        }
        activate(handle)
        RegistryStore.set(handle)
        RegistryStore.bumpDataVersion()
        AppScope.io.launch {
            runCatching { PriceCache.refreshNow() }
            runCatching { TrackerCache.refresh(syncEsplora = false) }
        }
    }

    private fun activate(handle: RegistryHandle) {
        if (!_active.value) {
            savedDataDir = TrackerCache.dataDirProvider
            savedBtcPrice = PriceCache.btcPriceSource
            savedQuote = PriceCache.quoteSource
            savedHttp = PriceFetcher.httpGetOverride
        }
        TrackerCache.dataDirProvider = { profileDir() }
        // The spot price is the profile's rate of today (or its latest).
        PriceCache.btcPriceSource = { currency -> RegistryStore.current()?.let { demoSpotRate(it, currency.code) } }
        PriceCache.quoteSource = { null }
        PriceFetcher.httpGetOverride = { throw java.io.IOException("no network in test mode") }
        SettingsStore.beginProfileSettings(readProfileSettings(handle)) { settings ->
            RegistryStore.current()?.let { reg ->
                runCatching { reg.setProfileMeta(MODULES_META_KEY, Json.encodeToString(StoredSettings.from(settings))) }
            }
        }
        _active.value = true
    }

    private fun deactivate() {
        savedDataDir?.let { TrackerCache.dataDirProvider = it }
        savedBtcPrice?.let { PriceCache.btcPriceSource = it }
        savedQuote?.let { PriceCache.quoteSource = it }
        PriceFetcher.httpGetOverride = savedHttp
        savedDataDir = null
        savedBtcPrice = null
        savedQuote = null
        savedHttp = null
        SettingsStore.endProfileSettings()
        _active.value = false
    }

    private fun demoSpotRate(registry: RegistryHandle, currency: String): Double? {
        val day = today().atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return runCatching { registry.btcRateAt(day, currency, io.bhodl.desktop.settings.EsploraEndpoint.currentProxy()) }.getOrNull()
            ?: runCatching { registry.btcRateRange(day - 3_650L * 86_400L, day, currency, io.bhodl.desktop.settings.EsploraEndpoint.currentProxy()).lastOrNull()?.rate }
                .getOrNull()
    }

    /** Every module on, no custom categories: what a profile without saved settings shows. */
    private val defaultTestSettings = SettingsStore.ProfileSettings(
        SettingsStore.ModuleFlags(
            investments = true, budgetBills = true, savingsGoals = true,
            manualAssets = true, analyticsLabs = false,
        ),
    )

    private fun readProfileSettings(handle: RegistryHandle): SettingsStore.ProfileSettings =
        runCatching { handle.profileMeta(MODULES_META_KEY) }.getOrNull()
            ?.let { runCatching { Json.decodeFromString<StoredSettings>(it).toSettings() }.getOrNull() }
            ?: defaultTestSettings

    /** Seeds a profile's saved settings (used by [DemoSeed]). */
    internal fun writeProfileSettings(handle: RegistryHandle, settings: SettingsStore.ProfileSettings = defaultTestSettings) {
        handle.setProfileMeta(MODULES_META_KEY, Json.encodeToString(StoredSettings.from(settings)))
    }

    /** Test seam: forget the active state without closing anything. */
    internal fun resetForTest() {
        if (_active.value) deactivate()
    }

    @Serializable
    private data class StoredSettings(
        val investments: Boolean = true,
        val budgetBills: Boolean = true,
        val savingsGoals: Boolean = true,
        val manualAssets: Boolean = true,
        val analyticsLabs: Boolean = false,
        val categoryColors: Map<String, Int> = emptyMap(),
        val customCategories: List<String> = emptyList(),
    ) {
        fun toSettings() = SettingsStore.ProfileSettings(
            SettingsStore.ModuleFlags(investments, budgetBills, savingsGoals, manualAssets, analyticsLabs),
            categoryColors,
            customCategories,
        )

        companion object {
            fun from(s: SettingsStore.ProfileSettings) = StoredSettings(
                s.modules.investments, s.modules.budgetBills, s.modules.savingsGoals,
                s.modules.manualAssets, s.modules.analyticsLabs, s.categoryColors, s.customCategories,
            )
        }
    }
}

/** A test-mode command refused, with a reason the UI can put in words. */
public class TestModeException(public val reason: Reason, cause: Throwable? = null) :
    Exception(reason.name, cause) {
    public enum class Reason { NOT_A_TEST_PROFILE, BAD_FILE, NEWER_FORMAT, TOO_LARGE, NO_INITIAL_STATE }
}
