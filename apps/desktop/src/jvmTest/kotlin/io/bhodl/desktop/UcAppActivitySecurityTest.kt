package io.bhodl.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * SEC-065 — the real [App] root refreshes the idle clock on pointer and
 * key events. `user.home` points at an empty temp dir, so the boot guard
 * lands on Welcome and nothing touches `~/.bhodl`.
 */
@OptIn(ExperimentalTestApi::class)
class UcAppActivitySecurityTest {

    private val originalHome = System.getProperty("user.home")
    private lateinit var home: java.io.File

    @BeforeTest
    fun setUp() {
        WalletStore.clear()
        RegistryStore.clear()
        SettingsStore.reset()
        home = kotlin.io.path.createTempDirectory("bhodl-uc-home-").toFile()
        System.setProperty("user.home", home.absolutePath)
    }

    @AfterTest
    fun tearDown() {
        System.setProperty("user.home", originalHome)
        home.deleteRecursively()
        SettingsStore.reset()
    }

    @Test
    fun `SEC-065 pointer and key events at the root refresh the idle clock`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { App() } }
        waitUntilAtLeastOneExists(hasText("watch-only", substring = true, ignoreCase = true), timeoutMillis = 5_000)

        IdleLock.touch(nowNanos = 0L)
        onRoot().performMouseInput { click() }
        waitForIdle()
        assertTrue(IdleLock.lastActivityNanosForTest() > 0L, "a click refreshes the idle clock")

        IdleLock.touch(nowNanos = 0L)
        onRoot().performKeyInput { pressKey(Key.Tab) }
        waitForIdle()
        assertTrue(IdleLock.lastActivityNanosForTest() > 0L, "a key press refreshes the idle clock")
        assertTrue(!home.resolve(".bhodl/registry.sqlite").exists())
    }
}
