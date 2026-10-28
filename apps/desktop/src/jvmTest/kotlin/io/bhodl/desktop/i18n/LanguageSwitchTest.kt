package io.bhodl.desktop.i18n

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.mtx_out
import org.jetbrains.compose.resources.stringResource
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Switching the language rebuilds the visible screen in the new
 * language at once (App.kt keys the NavHost on the override): the JVM
 * locale must already be the new one when that rebuild runs.
 */
@OptIn(ExperimentalTestApi::class)
class LanguageSwitchTest {

    @BeforeTest
    fun setUp() = SettingsStore.reset()

    @AfterTest
    fun tearDown() = SettingsStore.reset()

    @Test
    fun `the screen on show changes language as soon as the language is picked`() = runDesktopComposeUiTest {
        setContent {
            val override by SettingsStore.localeOverride
            key(override) { Text(stringResource(Res.string.mtx_out), Modifier.testTag("label")) }
        }
        SettingsStore.setLocaleOverride("en")
        waitForIdle()
        onNodeWithTag("label").assertTextEquals("Spending")
        SettingsStore.setLocaleOverride("it")
        waitForIdle()
        onNodeWithTag("label").assertTextEquals("Uscita")
        assertEquals("it", Locale.getDefault().language)
    }

    @Test
    fun `following the system goes back to the OS language`() {
        val system = Locale.getDefault()
        SettingsStore.setLocaleOverride("it")
        SettingsStore.setLocaleOverride(null)
        assertEquals(system, Locale.getDefault())
    }
}
