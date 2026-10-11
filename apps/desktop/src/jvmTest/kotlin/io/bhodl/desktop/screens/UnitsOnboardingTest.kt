package io.bhodl.desktop.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** UX review 2026-10 (item 16) — one-time sats / BTC explainer. */
@OptIn(ExperimentalTestApi::class)
class UnitsOnboardingTest {

    @BeforeTest fun setup() = SettingsStore.reset()

    @AfterTest fun teardown() = SettingsStore.reset()

    @Test
    fun `examples are sats with the BTC equivalent, never masked`() {
        SettingsStore.setBalancesHidden(true)
        assertEquals("5,000 sat" to "0.00005000 BTC", dualUnitTexts(5_000L))
        assertEquals("₿100,000,000" to "1.00000000 BTC", dualUnitTexts(100_000_000L, bip177 = true))
    }

    @Test
    fun `card shows sats by default and disappears for good after Got it`() = runDesktopComposeUiTest {
        setContent { BhodlTheme { UnitsOnboardingCard() } }
        onNodeWithTag(UnitsOnboardingTags.CARD).assertExists()
        // Default unit stays sats: the visible example is in sats.
        onNodeWithTag(UnitsOnboardingTags.example(250_000L)).assertTextEquals("250,000 sat")
        onNodeWithTag(UnitsOnboardingTags.GOT_IT).performClick()
        waitForIdle()
        onNodeWithTag(UnitsOnboardingTags.CARD).assertDoesNotExist()
        assertTrue(SettingsStore.onboardingUnitsSeen.value)
    }
}
