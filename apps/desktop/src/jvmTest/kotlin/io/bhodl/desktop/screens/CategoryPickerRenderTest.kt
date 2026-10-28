package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ffi.PaymentKind
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Redesign 2026-10 — the category drop-down and colours, rendered for the visual review. */
@OptIn(ExperimentalTestApi::class)
class CategoryPickerRenderTest {

    @BeforeTest fun setUp() = SettingsStore.reset()
    @AfterTest fun tearDown() = SettingsStore.reset()

    private val ev = PaymentEvent("w", "t", PaymentKind.ON_CHAIN, PaymentDirection.OUTGOING, 42_000UL, null, 1_760_000_000UL, null, "Cibo", null)

    @Test
    fun `render the category picker`() = runDesktopComposeUiTest(width = 900, height = 1300) {
        SettingsStore.addCustomCategory("Palestra")
        SettingsStore.setCategoryColor("Palestra", 5)
        for ((dark, name) in listOf(true to "category-picker-dark", false to "category-picker-light")) {
            setContent {
                BhodlTheme(useDarkTheme = dark) {
                    CategoryEditDialog(ev, listOf("Cibo", "Palestra"), onDismiss = {}, onSave = {})
                }
            }
            onNodeWithTag(CategoryPickerTags.TOGGLE).performClick()
            waitForIdle()
            screenshotTopmost(name)
        }
    }
}
