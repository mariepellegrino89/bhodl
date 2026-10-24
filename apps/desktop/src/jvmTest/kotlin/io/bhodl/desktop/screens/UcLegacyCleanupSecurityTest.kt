package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import java.io.FileInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LegacyCleanup screen on a temp data dir (docs/use-cases.md SEC-010..SEC-013).
 */
@OptIn(ExperimentalTestApi::class)
class UcLegacyCleanupSecurityTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        RegistryStore.clear()
        dir = kotlin.io.path.createTempDirectory("bhodl-uc-legacy-").toFile()
        File(dir, WalletPaths.WALLET_DB_FILE_NAME).writeText("db")
        File(dir, WalletPaths.SALT_FILE_NAME).writeText("salt")
        File(dir, "${WalletPaths.WALLETS_SUBDIR}/w1").mkdirs()
        File(dir, "${WalletPaths.WALLETS_SUBDIR}/w1/${WalletPaths.WALLET_DB_FILE_NAME}").writeText("db")
        File(dir, "${WalletPaths.WATCH_STATE_SUBDIR}/keep.sqlite").apply { parentFile.mkdirs() }.writeText("watch")
    }

    @AfterTest
    fun tearDown() {
        dir.walkTopDown().forEach { it.setWritable(true) }
        dir.deleteRecursively()
        RegistryStore.clear()
    }

    @Test
    fun `SEC-010 delete stays disabled until the recovery checkbox is ticked`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { LegacyCleanupScreen(onDone = {}, dataDir = dir) } }
        waitUntilAtLeastOneExists(hasTestTag(LegacyCleanupTags.DELETE))
        onNodeWithTag(LegacyCleanupTags.DELETE).assertIsNotEnabled()
        onNodeWithTag(LegacyCleanupTags.CONFIRM).performClick()
        onNodeWithTag(LegacyCleanupTags.DELETE).assertIsEnabled()
        onNodeWithTag(LegacyCleanupTags.CONFIRM).performClick()
        onNodeWithTag(LegacyCleanupTags.DELETE).assertIsNotEnabled()
        assertTrue(WalletPaths.from(dir).legacySigningDataExists())
    }

    @Test
    fun `SEC-011 confirmed cleanup deletes the signing data, keeps watch state and continues`() = runDesktopComposeUiTest {
        var done = 0
        setContent { BhodlTheme { LegacyCleanupScreen(onDone = { done++ }, dataDir = dir) } }
        waitUntilAtLeastOneExists(hasTestTag(LegacyCleanupTags.CONFIRM))
        onNodeWithTag(LegacyCleanupTags.CONFIRM).performClick()
        onNodeWithTag(LegacyCleanupTags.DELETE).performScrollTo().performClick()
        waitUntil(timeoutMillis = 3_000) { done == 1 }
        assertFalse(WalletPaths.from(dir).legacySigningDataExists())
        assertFalse(File(dir, WalletPaths.WALLETS_SUBDIR).exists())
        assertTrue(File(dir, "${WalletPaths.WATCH_STATE_SUBDIR}/keep.sqlite").isFile)
    }

    @Test
    fun `SEC-012 Not now continues without deleting anything`() = runDesktopComposeUiTest {
        var done = 0
        setContent { BhodlTheme { LegacyCleanupScreen(onDone = { done++ }, dataDir = dir) } }
        waitUntilAtLeastOneExists(hasTestTag(LegacyCleanupTags.LATER))
        onNodeWithTag(LegacyCleanupTags.LATER).performScrollTo().performClick()
        assertEquals(1, done)
        assertTrue(File(dir, WalletPaths.WALLET_DB_FILE_NAME).isFile)
        assertTrue(File(dir, WalletPaths.SALT_FILE_NAME).isFile)
        assertTrue(WalletPaths.from(dir).legacySigningDataExists(), "the screen comes back next unlock")
    }

    @Test
    fun `SEC-013 a locked file shows the failure and does not navigate`() = runDesktopComposeUiTest {
        val db = File(dir, WalletPaths.WALLET_DB_FILE_NAME)
        val windows = System.getProperty("os.name").lowercase().contains("win")
        val stream = if (windows) FileInputStream(db) else null
        if (!windows) dir.setWritable(false)
        try {
            var done = 0
            setContent { BhodlTheme { LegacyCleanupScreen(onDone = { done++ }, dataDir = dir) } }
            waitUntilAtLeastOneExists(hasTestTag(LegacyCleanupTags.CONFIRM))
            onNodeWithTag(LegacyCleanupTags.CONFIRM).performClick()
            onNodeWithTag(LegacyCleanupTags.DELETE).performScrollTo().performClick()
            waitUntilAtLeastOneExists(hasText("Some files could not be deleted", substring = true), timeoutMillis = 3_000)
            assertEquals(0, done)
            assertTrue(File(dir, WalletPaths.SALT_FILE_NAME).isFile, "key files stay while a database survives")
            onNodeWithTag(LegacyCleanupTags.DELETE).assertIsEnabled()
        } finally {
            stream?.close()
            dir.setWritable(true)
        }
    }
}
