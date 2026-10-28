package io.bhodl.desktop.settings

import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.shared.BtcUnit
import io.bhodl.ui.theme.ColorConvention
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T2-11 — round-trip + edge-case tests for [SettingsPersistence].
 *
 * Each test uses a TempDir-style File in `Files.createTempDirectory`
 * and cleans up on AfterTest. SettingsStore is reset on entry/exit so
 * tests don't leak state into each other.
 */
class SettingsPersistenceTest {

    private lateinit var tempDir: File
    private lateinit var file: File

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        tempDir = Files.createTempDirectory("bhodl-settings-test").toFile()
        file = File(tempDir, SettingsPersistence.FILE_NAME)
    }

    @AfterTest
    fun tearDown() {
        SettingsStore.disablePersistence()
        SettingsStore.reset()
        tempDir.deleteRecursively()
    }

    // ---- snapshot reflects the current store -------------------------

    @Test
    fun `snapshot defaults match SettingsStore defaults on a freshly reset store`() {
        val snap = SettingsPersistence.snapshot()
        assertEquals(SettingsPersistence.CURRENT_VERSION, snap.version)
        assertEquals(ThemeMode.System.name, snap.themeMode)
        assertNull(snap.esploraUrl)
        assertNull(snap.localeOverride)
        assertEquals(false, snap.torEnabled)
        assertEquals(SettingsStore.DEFAULT_TOR_PROXY_URL, snap.torProxyUrl)
        assertEquals(ColorConvention.WESTERN.name, snap.colorConvention)
        assertEquals(BtcUnit.SAT.name, snap.btcUnit)
        assertEquals(SettingsStore.DEFAULT_FIAT_CURRENCY.name, snap.fiatCurrency)
        assertEquals(false, snap.autoFetchInvestmentPrices)
        // Phase 7.2 — privacy auto-hide defaults off so existing
        // installs aren't surprised by suddenly-bulleted heroes after
        // an upgrade.
        assertEquals(false, snap.privacyAutoHide)
    }

    // ---- Phase 7.2 — privacy auto-hide persistence ------------------

    @Test
    fun `privacyAutoHide setter is captured in the snapshot`() {
        SettingsStore.setPrivacyAutoHide(true)
        val snap = SettingsPersistence.snapshot()
        assertTrue(snap.privacyAutoHide)
        SettingsStore.setPrivacyAutoHide(false)
        assertFalse(SettingsPersistence.snapshot().privacyAutoHide)
    }

    @Test
    fun `privacyAutoHide round-trips through save and rehydrate`() {
        SettingsStore.setPrivacyAutoHide(true)
        SettingsPersistence.save(file).getOrThrow()

        // Wipe the in-memory store, then rehydrate from disk.
        SettingsStore.reset()
        assertFalse(SettingsStore.privacyAutoHide.value)
        val applied = SettingsPersistence.rehydrate(file).getOrThrow()
        assertTrue(applied, "rehydrate should report apply=true when file existed")
        assertTrue(
            SettingsStore.privacyAutoHide.value,
            "privacyAutoHide should rehydrate from JSON snapshot",
        )
    }

    @Test
    fun `missing privacyAutoHide field in older JSON defaults to false`() {
        // Simulate a Phase 7.1 snapshot (no privacyAutoHide field) by
        // writing a minimal JSON manually. ignoreUnknownKeys + the
        // field default in Snapshot should yield privacyAutoHide=false.
        file.writeText(
            """{"version": 1, "themeMode": "System", "fiatCurrency": "NONE"}""",
            Charsets.UTF_8,
        )
        val applied = SettingsPersistence.rehydrate(file).getOrThrow()
        assertTrue(applied)
        assertFalse(SettingsStore.privacyAutoHide.value)
    }

    @Test
    fun `snapshot reflects mutated state`() {
        SettingsStore.setThemeMode(ThemeMode.Neon)
        SettingsStore.setTorEnabled(true)
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        SettingsStore.setAutoFetchInvestmentPrices(true)
        SettingsStore.setBtcUnit(BtcUnit.BTC)

        val snap = SettingsPersistence.snapshot()
        assertEquals(ThemeMode.Neon.name, snap.themeMode)
        assertEquals(true, snap.torEnabled)
        assertEquals(FiatCurrency.EUR.name, snap.fiatCurrency)
        assertEquals(true, snap.autoFetchInvestmentPrices)
        assertEquals(BtcUnit.BTC.name, snap.btcUnit)
    }

    // ---- save / load round-trip --------------------------------------

    @Test
    fun `save writes a UTF-8 JSON file and creates parent dirs`() {
        // file path is inside a non-existent subdir to verify mkdirs.
        val nested = File(File(tempDir, "deep/nested"), SettingsPersistence.FILE_NAME)
        SettingsStore.setTorEnabled(true)
        val result = SettingsPersistence.save(nested)
        assertTrue(result.isSuccess, "save failed: ${result.exceptionOrNull()}")
        assertTrue(nested.exists())
        val text = nested.readText(Charsets.UTF_8)
        assertTrue("torEnabled" in text, "expected JSON to contain torEnabled; got $text")
        assertTrue("true" in text, "expected torEnabled=true in JSON; got $text")
    }

    @Test
    fun `save then load round-trips every field`() {
        SettingsStore.setThemeMode(ThemeMode.Dark)
        SettingsStore.setEsploraUrl("https://my.node/api")
        SettingsStore.setLocaleOverride("it")
        SettingsStore.setTorEnabled(true)
        SettingsStore.setTorProxyUrl("socks5h://other.tor:9150")
        SettingsStore.setColorConvention(ColorConvention.EASTERN)
        SettingsStore.setBtcUnit(BtcUnit.BTC)
        SettingsStore.setFiatCurrency(FiatCurrency.CHF)
        SettingsStore.setAutoFetchInvestmentPrices(true)
        SettingsPersistence.save(file).getOrThrow()

        // Wipe in-memory state and rehydrate from disk.
        SettingsStore.reset()
        val ok = SettingsPersistence.rehydrate(file).getOrThrow()
        assertTrue(ok)
        assertEquals(ThemeMode.Dark, SettingsStore.themeMode.value)
        assertEquals("https://my.node/api", SettingsStore.esploraUrl.value)
        assertEquals("it", SettingsStore.localeOverride.value)
        assertEquals(true, SettingsStore.torEnabled.value)
        assertEquals("socks5h://other.tor:9150", SettingsStore.torProxyUrl.value)
        assertEquals(ColorConvention.EASTERN, SettingsStore.colorConvention.value)
        assertEquals(BtcUnit.BTC, SettingsStore.btcUnit.value)
        assertEquals(FiatCurrency.CHF, SettingsStore.fiatCurrency.value)
        assertEquals(true, SettingsStore.autoFetchInvestmentPrices.value)
    }

    @Test
    fun `load returns null on missing file (first launch)`() {
        val absent = File(tempDir, "never-existed.json")
        val result = SettingsPersistence.load(absent).getOrThrow()
        assertNull(result)
    }

    @Test
    fun `rehydrate returns false on missing file and leaves state untouched`() {
        SettingsStore.setFiatCurrency(FiatCurrency.USD)
        val absent = File(tempDir, "never-existed.json")
        val didApply = SettingsPersistence.rehydrate(absent).getOrThrow()
        assertFalse(didApply)
        // State preserved.
        assertEquals(FiatCurrency.USD, SettingsStore.fiatCurrency.value)
    }

    @Test
    fun `load fails on a future version snapshot`() {
        // Hand-craft a JSON with a future version.
        file.writeText(
            """{"version":9999,"themeMode":"System","torEnabled":false,
               "torProxyUrl":"x","colorConvention":"WESTERN","btcUnit":"SAT",
               "fiatCurrency":"NONE","autoFetchInvestmentPrices":false}""",
            Charsets.UTF_8,
        )
        val result = SettingsPersistence.load(file)
        assertTrue(result.isFailure, "future version must be rejected")
    }

    @Test
    fun `load tolerates unknown extra fields (forward compatibility)`() {
        file.writeText(
            """{"version":1,"themeMode":"Dark","esploraUrl":null,
               "localeOverride":null,"torEnabled":true,"torProxyUrl":"socks5h://x:9050",
               "colorConvention":"WESTERN","btcUnit":"SAT","fiatCurrency":"EUR",
               "autoFetchInvestmentPrices":false,"unknownFutureField":42,
               "anotherOne":["a","b"]}""",
            Charsets.UTF_8,
        )
        val snap = SettingsPersistence.load(file).getOrThrow()
        assertNotNull(snap)
        assertEquals(true, snap.torEnabled)
        assertEquals("EUR", snap.fiatCurrency)
    }

    @Test
    fun `load tolerates missing fields (uses defaults)`() {
        // Snapshot from a hypothetical older build that didn't have
        // fiatCurrency or autoFetchInvestmentPrices. ignoreUnknownKeys
        // + per-field defaults make this safe.
        file.writeText("""{"version":1,"themeMode":"Light"}""", Charsets.UTF_8)
        val snap = SettingsPersistence.load(file).getOrThrow()
        assertNotNull(snap)
        assertEquals("Light", snap.themeMode)
        assertEquals(SettingsStore.DEFAULT_FIAT_CURRENCY.name, snap.fiatCurrency)
        assertEquals(false, snap.autoFetchInvestmentPrices)
    }

    @Test
    fun `apply with an unrecognised enum value falls back silently`() {
        val snap = SettingsPersistence.Snapshot(
            themeMode = "TotallyNotARealMode",
            fiatCurrency = "XYZ-NOT-A-CURRENCY",
        )
        SettingsPersistence.apply(snap)
        // Default state survives.
        assertEquals(ThemeMode.System, SettingsStore.themeMode.value)
        assertEquals(FiatCurrency.NONE, SettingsStore.fiatCurrency.value)
    }

    // ---- enablePersistence write-through ------------------------------

    @Test
    fun `enablePersistence writes the file on the next setter`() {
        SettingsStore.enablePersistence(file)
        assertFalse(file.exists(), "file must not exist before any setter")
        SettingsStore.setFiatCurrency(FiatCurrency.USD)
        assertTrue(file.exists(), "setter must trigger a save")
        val snap = SettingsPersistence.load(file).getOrThrow()!!
        assertEquals(FiatCurrency.USD.name, snap.fiatCurrency)
    }

    @Test
    fun `every persisted setter triggers a re-save`() {
        SettingsStore.enablePersistence(file)
        SettingsStore.setTorEnabled(true)
        var snap1 = SettingsPersistence.load(file).getOrThrow()!!
        assertEquals(true, snap1.torEnabled)

        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        var snap2 = SettingsPersistence.load(file).getOrThrow()!!
        assertEquals(FiatCurrency.EUR.name, snap2.fiatCurrency)
        // Tor flag from the previous save still in the file.
        assertEquals(true, snap2.torEnabled)
    }

    @Test
    fun `reset clears the persistence hook`() {
        SettingsStore.enablePersistence(file)
        SettingsStore.setFiatCurrency(FiatCurrency.USD)
        assertTrue(file.exists())
        // Reset MUST also drop the persistence file pointer so a
        // subsequent setter does not silently re-create a file
        // outside the test's scope.
        SettingsStore.reset()
        file.delete()
        SettingsStore.setFiatCurrency(FiatCurrency.CHF)
        assertFalse(file.exists(), "reset should have dropped the persistence hook")
    }

    // ---- Watch-only: fields of removed features ------------------------

    @Test
    fun `load ignores fields of removed signing features in older snapshots`() {
        // Builds that could hold seeds wrote backup / reminder / tracker
        // pairing state. The watch-only build must load such a file and
        // keep only the labels export date.
        file.writeText(
            """{"version":1,"themeMode":"System","torEnabled":false,
               "torProxyUrl":"x","colorConvention":"WESTERN","btcUnit":"SAT",
               "fiatCurrency":"NONE","autoFetchInvestmentPrices":false,
               "trackerPairingSkipped":["w-1"],
               "backupVerifiedAt":{"w-1":100},"backupExportedAt":{"w-1":200},
               "securityReminderSnoozedUntil":{"w-1":400},
               "labelsExportedAt":{"w-1":300}}""",
            Charsets.UTF_8,
        )
        val snap = SettingsPersistence.load(file).getOrThrow()
        assertNotNull(snap)
        assertEquals(mapOf("w-1" to 300L), snap.labelsExportedAt)
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(300L, SettingsStore.labelsExportedAt.value["w-1"])
    }

    @Test
    fun `saved snapshot carries no field of removed signing features`() {
        SettingsStore.markLabelsExported("w-1", 300L)
        SettingsPersistence.save(file).getOrThrow()
        val text = file.readText()
        for (gone in listOf("trackerPairingSkipped", "backupVerifiedAt", "backupExportedAt", "securityReminder")) {
            assertFalse(gone in text, "$gone must no longer be persisted")
        }
        assertTrue("labelsExportedAt" in text)
    }

    // ---- Phase 8.3.1 — Bitcoin Lens advanced preferences -------------

    @Test
    fun `snapshot defaults include Bitcoin Lens fields with sensible defaults`() {
        val snap = SettingsPersistence.snapshot()
        // UX review 2026-10 — the unified unit defaults to sats.
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT.name,
            snap.unitFormat,
        )
        assertTrue(snap.showFiatInBankCsv)
        assertTrue(snap.showFiatInCurrentMonthFiatTx)
        assertTrue(snap.showFiatInTaxExports)
        assertTrue(snap.showFiatInInvoiceReceipts)
    }

    @Test
    fun `save then rehydrate restores the 5 Bitcoin Lens preferences`() {
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC)
        SettingsStore.setShowFiatInBankCsv(false)
        SettingsStore.setShowFiatInCurrentMonthFiatTx(true)
        SettingsStore.setShowFiatInTaxExports(false)
        SettingsStore.setShowFiatInInvoiceReceipts(false)
        SettingsPersistence.save(file).getOrThrow()

        // Wipe in-memory state, rehydrate from disk.
        SettingsStore.reset()
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT,
            SettingsStore.unitFormat.value,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC,
            SettingsStore.unitFormat.value,
        )
        assertFalse(SettingsStore.showFiatInBankCsv.value)
        assertTrue(SettingsStore.showFiatInCurrentMonthFiatTx.value)
        assertFalse(SettingsStore.showFiatInTaxExports.value)
        assertFalse(SettingsStore.showFiatInInvoiceReceipts.value)
    }

    @Test
    fun `older JSON without Bitcoin Lens fields rehydrates with safe defaults`() {
        // No unitFormat / showFiatInXxx keys — pre-8.3.1 snapshot.
        file.writeText(
            """{"version":1,"themeMode":"System","torEnabled":false,
               "torProxyUrl":"x","colorConvention":"WESTERN","btcUnit":"SAT",
               "fiatCurrency":"NONE","autoFetchInvestmentPrices":false}""",
            Charsets.UTF_8,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        // UX review 2026-10 — an untouched legacy pair (btcUnit SAT,
        // unitFormat absent) lands on the new default: sats.
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT,
            SettingsStore.unitFormat.value,
        )
        assertTrue(SettingsStore.showFiatInBankCsv.value)
        assertTrue(SettingsStore.showFiatInCurrentMonthFiatTx.value)
        assertTrue(SettingsStore.showFiatInTaxExports.value)
        assertTrue(SettingsStore.showFiatInInvoiceReceipts.value)
    }

    // ---- UX review 2026-10 — display preferences ----------------------

    @Test
    fun `snapshot defaults for display preferences`() {
        val snap = SettingsPersistence.snapshot()
        assertEquals(ThemeMode.System.name, snap.themeMode)
        assertEquals(io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT.name, snap.bitcoinUnit)
        assertFalse(snap.bip177Notation)
        assertEquals(io.bhodl.ui.theme.BhodlDensity.Compact.name, snap.density)
    }

    @Test
    fun `display preferences round-trip through settings json`() {
        SettingsStore.setThemeMode(ThemeMode.Light)
        SettingsStore.setUnitFormat(io.bhodl.desktop.ui.money.UnitFormat.AUTO_THRESHOLD)
        SettingsStore.setBip177Notation(true)
        SettingsStore.setDensity(io.bhodl.ui.theme.BhodlDensity.UltraDense)
        SettingsPersistence.save(file).getOrThrow()

        SettingsStore.reset()
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(ThemeMode.Light, SettingsStore.themeMode.value)
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.AUTO_THRESHOLD,
            SettingsStore.unitFormat.value,
        )
        assertTrue(SettingsStore.bip177Notation.value)
        assertEquals(io.bhodl.ui.theme.BhodlDensity.UltraDense, SettingsStore.density.value)
    }

    @Test
    fun `existing explicit theme is kept when upgrading`() {
        // A pre-2026-10 file with an explicit Dark pick and no display
        // keys: the user keeps Dark (System is only the fresh default).
        file.writeText(
            """{"version":1,"themeMode":"Dark","torEnabled":false,
               "torProxyUrl":"x","colorConvention":"WESTERN","btcUnit":"SAT",
               "fiatCurrency":"NONE","autoFetchInvestmentPrices":false}""",
            Charsets.UTF_8,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(ThemeMode.Dark, SettingsStore.themeMode.value)
        assertFalse(SettingsStore.bip177Notation.value)
        assertEquals(io.bhodl.ui.theme.BhodlDensity.Compact, SettingsStore.density.value)
    }

    @Test
    fun `legacy btcUnit BTC migrates to the unified BTC unit`() {
        file.writeText(
            """{"version":1,"themeMode":"System","btcUnit":"BTC",
               "unitFormat":"AUTO_THRESHOLD"}""",
            Charsets.UTF_8,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_BTC,
            SettingsStore.unitFormat.value,
        )
    }

    @Test
    fun `legacy non-default Bitcoin Lens unit format wins over btcUnit`() {
        file.writeText(
            """{"version":1,"themeMode":"System","btcUnit":"BTC",
               "unitFormat":"ALWAYS_SAT"}""",
            Charsets.UTF_8,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT,
            SettingsStore.unitFormat.value,
        )
    }

    @Test
    fun `legacy untouched unit settings land on sats`() {
        file.writeText(
            """{"version":1,"themeMode":"System","btcUnit":"SAT",
               "unitFormat":"AUTO_THRESHOLD"}""",
            Charsets.UTF_8,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.ALWAYS_SAT,
            SettingsStore.unitFormat.value,
        )
    }

    @Test
    fun `bitcoinUnit key wins over the legacy pair and unknown density falls back`() {
        file.writeText(
            """{"version":1,"themeMode":"System","btcUnit":"SAT",
               "unitFormat":"AUTO_THRESHOLD","bitcoinUnit":"AUTO_THRESHOLD",
               "density":"Galactic"}""",
            Charsets.UTF_8,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(
            io.bhodl.desktop.ui.money.UnitFormat.AUTO_THRESHOLD,
            SettingsStore.unitFormat.value,
        )
        assertEquals(io.bhodl.ui.theme.BhodlDensity.Compact, SettingsStore.density.value)
    }

    @Test
    fun `save survives a partial-write crash via atomic rename`() {

        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        SettingsPersistence.save(file).getOrThrow()
        val originalText = file.readText(Charsets.UTF_8)
        // Simulate a previous "in-progress" tmp file — the atomic
        // rename pattern should overwrite without corruption.
        val tmp = File(tempDir, "${file.name}.tmp")
        tmp.writeText("stale-junk", Charsets.UTF_8)
        SettingsStore.setFiatCurrency(FiatCurrency.USD)
        SettingsPersistence.save(file).getOrThrow()
        assertFalse(tmp.exists(), "tmp file should be renamed away")
        val newText = file.readText(Charsets.UTF_8)
        assertTrue("USD" in newText)
        // Original was overwritten cleanly.
        assertFalse(originalText == newText)
    }

    // ---- R4 — excluded-wallets persistence --------------------------

    @Test
    fun `excludedWalletIds survive a save - reset - rehydrate round-trip`() {
        SettingsStore.setWalletIncluded("wallet-a", included = false)
        SettingsStore.setWalletIncluded("wallet-b", included = false)
        val snap = SettingsPersistence.snapshot()
        assertTrue("wallet-a" in snap.excludedWalletIds)
        assertTrue("wallet-b" in snap.excludedWalletIds)

        SettingsPersistence.save(file).getOrThrow()
        SettingsStore.reset()
        assertTrue(SettingsStore.excludedWalletIds.value.isEmpty())

        SettingsPersistence.rehydrate(file).getOrThrow()
        assertEquals(setOf("wallet-a", "wallet-b"), SettingsStore.excludedWalletIds.value)
    }

    @Test
    fun `re-including a wallet drops it from the persisted set`() {
        SettingsStore.setWalletIncluded("wallet-a", included = false)
        SettingsStore.setWalletIncluded("wallet-a", included = true)
        assertFalse("wallet-a" in SettingsPersistence.snapshot().excludedWalletIds)
    }

    @Test
    fun `old snapshot without excludedWalletIds rehydrates to empty set`() {
        file.writeText(
            """{"version":1,"themeMode":"System","torEnabled":false,
               "torProxyUrl":"x","colorConvention":"WESTERN","btcUnit":"SAT",
               "fiatCurrency":"NONE","autoFetchInvestmentPrices":false}""",
            Charsets.UTF_8,
        )
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertTrue(SettingsStore.excludedWalletIds.value.isEmpty())
    }

    // ---- Audit 2026-10 (M9) — corrupt file recovery -------------------

    @Test
    fun `corrupt file is backed up, Tor fails closed and a warning is raised`() {
        file.writeText("""{"version": 1, "torEnabled": true, "themeMode": """, Charsets.UTF_8)
        val result = SettingsPersistence.rehydrate(file)
        assertTrue(result.isFailure)
        assertTrue(SettingsStore.torEnabled.value, "Tor must stay ON when the corrupt file had it on")
        assertNotNull(SettingsStore.persistenceWarning.value)
        val backups = tempDir.listFiles()!!.filter { it.name.startsWith("settings.json.corrupt-") }
        assertEquals(1, backups.size)
        assertFalse(SettingsPersistence.unsafeToPersist(file))
    }

    @Test
    fun `future version is treated as corrupt and keeps Tor off when it was off`() {
        file.writeText("""{"version": 999, "torEnabled": false}""", Charsets.UTF_8)
        assertTrue(SettingsPersistence.rehydrate(file).isFailure)
        assertFalse(SettingsStore.torEnabled.value)
        assertNotNull(SettingsStore.persistenceWarning.value)
    }

    @Test
    fun `socks5 proxy is normalised to socks5h`() {
        SettingsStore.setTorProxyUrl("socks5://127.0.0.1:9150")
        assertEquals("socks5h://127.0.0.1:9150", SettingsStore.torProxyUrl.value)
    }
}
