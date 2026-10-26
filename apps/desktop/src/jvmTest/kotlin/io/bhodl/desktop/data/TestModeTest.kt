package io.bhodl.desktop.data

import io.bhodl.desktop.prices.PriceFetcher
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.RegistryHandle
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test mode: a separate profile with test data, shared as one file,
 * with a marked initial state — and the real profile never touched.
 */
class TestModeTest {

    private lateinit var root: File
    private val day0 = LocalDate.of(2026, 10, 26)
    private val savedRoot = TestMode.rootDirProvider
    private val savedToday = TestMode.today
    private val savedProject = TestMode.projectData

    @BeforeTest
    fun setUp() {
        SettingsStore.reset()
        TrackerCache.reset()
        root = kotlin.io.path.createTempDirectory("bhodl-test-mode-").toFile()
        TestMode.rootDirProvider = { root }
        TestMode.today = { day0 }
        TestMode.projectData = { null }
    }

    @AfterTest
    fun tearDown() {
        TestMode.exit()
        TestMode.resetForTest()
        RegistryStore.clear()
        TestMode.rootDirProvider = savedRoot
        TestMode.today = savedToday
        TestMode.projectData = savedProject
        SettingsStore.reset()
        root.deleteRecursively()
    }

    private fun registry(): RegistryHandle = assertNotNull(RegistryStore.current())

    private fun noon(date: LocalDate) = date.atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    private fun addSpending(note: String, date: LocalDate = day0): Long =
        registry().addManualTxn("out", 5_000uL, null, null, noon(date), null, note)

    private fun notes(): List<String> = registry().listManualTxns().mapNotNull { it.note }

    @Test
    fun `entering builds the test profile apart from the real one and works offline`() = runBlocking<Unit> {
        TestMode.enter()
        assertTrue(TestMode.isActive())
        assertTrue(registry().isDemoProfile())
        assertTrue(File(root, "demo/registry.sqlite").isFile)
        assertFalse(File(root, "registry.sqlite").exists(), "the real profile is never created or opened")
        assertNotNull(TestMode.initialStateDay(), "a fresh profile is its own initial state")
        // Every module on, wallets with their test transactions.
        assertTrue(SettingsStore.moduleBudgetBills.value && SettingsStore.moduleInvestments.value)
        assertTrue(registry().listDemoEvents().isNotEmpty())
        assertNotNull(PriceFetcher.httpGetOverride, "no request leaves the device")

        TrackerCache.refresh(syncEsplora = true)
        assertTrue(TrackerCache.bitcoinAssetSat.value > 0uL, "balances come from the test transactions")
        assertTrue(TrackerCache.events.value.any { it.category == "Stipendio" })
        // Today's rate is the profile's own.
        val today = day0.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        assertNotNull(registry().btcRateAt(today, "EUR", null))
    }

    @Test
    fun `leaving puts the real settings back and keeps the test ones in the profile`() = runBlocking<Unit> {
        SettingsStore.setModuleInvestments(false)
        SettingsStore.addCustomCategory("Reale")
        TestMode.enter()
        assertTrue(SettingsStore.moduleInvestments.value)
        assertFalse("Reale" in SettingsStore.customCategories.value)
        SettingsStore.addCustomCategory("Solo test")
        assertFalse(SettingsStore.persistedProfileSettings().customCategories.contains("Solo test"))

        TestMode.exit()
        assertFalse(TestMode.isActive())
        assertNull(RegistryStore.current())
        assertNull(PriceFetcher.httpGetOverride)
        assertFalse(SettingsStore.moduleInvestments.value)
        assertEquals(listOf("Reale"), SettingsStore.customCategories.value)

        TestMode.enter()
        assertTrue("Solo test" in SettingsStore.customCategories.value, "the test profile kept its own")
    }

    @Test
    fun `a transaction added by one person reaches whoever imports the file, dates moved to their day`() = runBlocking<Unit> {
        TestMode.enter()
        addSpending("added by Ada")
        val file = File(root, "shared.bhodl-test")
        TestMode.exportTo(file)
        assertTrue(file.length() > 0)
        TestMode.exit()

        // Another person, another machine, five days later.
        val other = kotlin.io.path.createTempDirectory("bhodl-test-mode-b-").toFile()
        try {
            TestMode.rootDirProvider = { other }
            TestMode.today = { day0.plusDays(5) }
            TestMode.enter()
            assertFalse("added by Ada" in notes())
            TestMode.importFrom(file)
            val entry = registry().listManualTxns().single { it.note == "added by Ada" }
            assertEquals(noon(day0.plusDays(5)), entry.occurredUnix, "dates move by the days since the export")
            // The file's initial state came along.
            TestMode.restoreInitialState()
            assertFalse("added by Ada" in notes(), "Ada added it after the initial state")

            TestMode.importFrom(file, keepDates = true)
            assertEquals(noon(day0), registry().listManualTxns().single { it.note == "added by Ada" }.occurredUnix)
        } finally {
            TestMode.exit()
            other.deleteRecursively()
        }
    }

    @Test
    fun `back to the initial state drops later changes, marking moves the initial state`() = runBlocking<Unit> {
        TestMode.enter()
        addSpending("before mark")
        TestMode.markInitialState()
        assertEquals(day0, TestMode.initialStateDay())
        addSpending("after mark")
        TestMode.restoreInitialState()
        assertTrue("before mark" in notes())
        assertFalse("after mark" in notes())
    }

    @Test
    fun `a real registry or a broken file is never imported`() = runBlocking<Unit> {
        TestMode.enter()
        addSpending("kept")
        // A real registry, even one opened with the public PIN.
        val realDir = File(root, "real").apply { mkdirs() }
        RegistryHandle.createOrLoad(realDir.absolutePath, TestMode.DEMO_PIN).destroy()
        val realFile = File(root, "real.bhodl-test")
        realFile.writeBytes(
            TestModePackage(
                TestModePackage.Manifest(anchorDay = day0.toString()),
                TestModePackage.readDir(realDir),
                null,
            ).write(),
        )
        val refused = assertFailsWith<TestModeException> { TestMode.importFrom(realFile) }
        assertEquals(TestModeException.Reason.NOT_A_TEST_PROFILE, refused.reason)

        val junk = File(root, "junk.bhodl-test").apply { writeText("not a zip") }
        assertEquals(TestModeException.Reason.BAD_FILE, assertFailsWith<TestModeException> { TestMode.importFrom(junk) }.reason)

        // Still in test mode, data untouched.
        assertTrue(TestMode.isActive())
        assertTrue("kept" in notes())
    }

    @Test
    fun `a package path leaving the profile is refused`() {
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                zip.write("""{"anchorDay":"2026-10-26"}""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("current/../../evil.txt"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
            }
        }.toByteArray()
        assertEquals(TestModeException.Reason.BAD_FILE, assertFailsWith<TestModeException> { TestModePackage.read(bytes) }.reason)
    }

    @Test
    fun `a file from a newer app is refused with its own reason`() {
        val pkg = TestModePackage(
            TestModePackage.Manifest(format = TestModePackage.FORMAT + 1, anchorDay = day0.toString()),
            mapOf("registry.sqlite" to byteArrayOf(1)),
            null,
        ).write()
        assertEquals(TestModeException.Reason.NEWER_FORMAT, assertFailsWith<TestModeException> { TestModePackage.read(pkg) }.reason)
    }

    @Test
    fun `the project test data bundled with the app opens as a test profile`() = runBlocking<Unit> {
        TestMode.projectData = savedProject
        if (savedProject() == null) return@runBlocking
        TestMode.enter()
        assertTrue(registry().isDemoProfile())
        assertTrue(registry().listDemoEvents().isNotEmpty())
        assertTrue(registry().listBills().isNotEmpty())
    }

    /**
     * Not a check: rebuilds `resources/demo/base.bhodl-test` from
     * [DemoSeed] when `BHODL_WRITE_DEMO_DATA` names the target file.
     */
    @Test
    fun `write the project test data when asked`() = runBlocking<Unit> {
        val target = System.getenv("BHODL_WRITE_DEMO_DATA")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        TestMode.today = { LocalDate.now(ZoneOffset.UTC) }
        TestMode.enter()
        TestMode.exportTo(File(target))
    }
}
