package io.bhodl.desktop.settings

import io.bhodl.desktop.wizard.WalletPaths
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * settings.json round-trips and failure handling not covered by
 * SettingsPersistenceTest (docs/use-cases.md SEC-007, SEC-081, SEC-082,
 * SEC-094, GAP-029).
 */
class UcSettingsPersistenceSecurityTest {

    private lateinit var dir: File
    private lateinit var file: File

    private val posix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    private val windows = System.getProperty("os.name").lowercase().contains("win")

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-settings-").toFile()
        file = File(dir, SettingsPersistence.FILE_NAME)
    }

    @AfterTest
    fun tearDown() {
        SettingsStore.reset()
        dir.deleteRecursively()
    }

    @Test
    fun `SEC-007 the units onboarding flag survives save and rehydrate`() {
        SettingsStore.enablePersistence(file)
        SettingsStore.markOnboardingUnitsSeen()
        assertTrue(SettingsPersistence.load(file).getOrThrow()!!.onboardingUnitsSeen)

        SettingsStore.reset()
        assertFalse(SettingsStore.onboardingUnitsSeen.value)
        assertTrue(SettingsPersistence.rehydrate(file).getOrThrow())
        assertTrue(SettingsStore.onboardingUnitsSeen.value)
    }

    @Test
    fun `SEC-094 tracker module switches survive save and rehydrate`() {
        SettingsStore.enablePersistence(file)
        SettingsStore.setModuleInvestments(true)
        SettingsStore.setModuleSavingsGoals(true)
        SettingsStore.setModuleAnalyticsLabs(true)
        SettingsStore.setModulesBannerDismissed(true)

        SettingsStore.reset()
        assertFalse(SettingsStore.anyModuleEnabled)
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertTrue(SettingsStore.moduleInvestments.value)
        assertFalse(SettingsStore.moduleBudgetBills.value)
        assertTrue(SettingsStore.moduleSavingsGoals.value)
        assertFalse(SettingsStore.moduleManualAssets.value)
        assertTrue(SettingsStore.moduleAnalyticsLabs.value)
        assertTrue(SettingsStore.modulesBannerDismissed.value)
    }

    @Test
    fun `SEC-094 an older settings file without module fields keeps every module off`() {
        file.writeText("""{"version":1,"themeMode":"Dark"}""")
        SettingsPersistence.rehydrate(file).getOrThrow()
        assertFalse(SettingsStore.anyModuleEnabled)
        assertFalse(SettingsStore.modulesBannerDismissed.value)
    }

    @Test
    fun `SEC-082 a failed backup copy is reported and makes persistence unsafe`() {
        file.writeText("{ not json")
        val now = 1_700_000_000_000L
        // The backup target exists as a directory: the copy cannot happen.
        File(dir, "${file.name}.corrupt-$now").mkdirs()
        assertNull(SettingsPersistence.backupCorrupt(file, now))

        SettingsStore.setPersistenceWarning("settings were reset")
        dir.listFiles()!!.filter { it.name.contains(".corrupt-") }.forEach { it.deleteRecursively() }
        assertTrue(SettingsPersistence.unsafeToPersist(file), "no backup of the only copy")

        assertNotNull(SettingsPersistence.backupCorrupt(file, now + 1))
        assertFalse(SettingsPersistence.unsafeToPersist(file), "a backup exists")
    }

    @Test
    fun `SEC-082 no warning means persistence is safe`() {
        file.writeText("{ not json")
        assertFalse(SettingsPersistence.unsafeToPersist(file))
    }

    @Test
    fun `SEC-082 an unreadable settings file fails closed and is never overwritten`() {
        assumeTrue("mandatory byte-range locks are a Windows feature", windows)
        file.writeText("""{"version":1,"torEnabled":false}""")
        RandomAccessFile(file, "rw").use { raf ->
            raf.channel.lock().use {
                assertTrue(SettingsPersistence.rehydrate(file).isFailure)
                assertTrue(SettingsStore.torEnabled.value, "an unreadable file turns Tor on")
                assertTrue(SettingsStore.balancesHidden.value)
                assertNotNull(SettingsStore.persistenceWarning.value)
                assertTrue(SettingsPersistence.unsafeToPersist(file))
            }
        }
        assertEquals("""{"version":1,"torEnabled":false}""", file.readText())
    }

    @Test
    fun `SEC-081 settings json is written owner-only`() {
        assumeTrue("POSIX permissions only", posix)
        SettingsPersistence.save(file).getOrThrow()
        val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
        assertEquals("rw-------", perms)
    }

    @Test
    fun `GAP-029 restrictToOwner makes the data dir rwx for the owner only`() {
        assumeTrue("POSIX permissions only", posix)
        val data = File(dir, "data").apply { mkdirs() }
        WalletPaths.restrictToOwner(data)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(data.toPath())))
    }

    @Test
    fun `GAP-029 restrictToOwner never throws on filesystems without POSIX permissions`() {
        val data = File(dir, "data").apply { mkdirs() }
        WalletPaths.restrictToOwner(data)
        WalletPaths.restrictToOwner(File(dir, "missing"))
        assertTrue(data.isDirectory)
    }
}
