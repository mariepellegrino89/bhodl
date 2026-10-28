package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.RecurringFrequency
import io.bhodl.desktop.domain.RecurringConfidence

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.PaymentDirection
import io.bhodl.ffi.PaymentEvent
import io.bhodl.ui.theme.BhodlTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 8.3.4 — render-tree assertions for the Bitcoin Lens migration
 * of [RecurringCard]. Covers:
 *  - candidate amount renders via `<Money>` Compact BTC primary
 *  - "Accept" and "Dismiss" actions remain wired (regression check)
 */
@OptIn(ExperimentalTestApi::class)
class RecurringCardBitcoinLensTest {

    @BeforeTest
    fun setup() {
        SettingsStore.reset()
        SettingsStore.setFiatCurrency(FiatCurrency.EUR)
        PriceCache.seedForTest(price = 60_000.0, lastFetchUnix = nowSec())
    }

    @AfterTest
    fun teardown() {
        SettingsStore.reset()
        PriceCache.seedForTest(price = null, lastFetchUnix = null)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000L

    /**
     * Build a candidate directly (skip the detector — the rendering
     * test is what matters here). The amount = 200k sat lands above
     * the AUTO_THRESHOLD so the BTC primary is rendered as BTC.
     */
    private fun candidate(): RecurringCandidate = RecurringCandidate(
        payeeKey = "spotify",
        displayName = "Spotify Premium",
        sampleEvents = emptyList<PaymentEvent>().also { /* unused by UI */ },
        frequency = RecurringFrequency.MONTHLY,
        avgAmountSat = 200_000UL,
        nextEstimatedUnix = nowSec() + 30L * 86_400L,
        confidence = RecurringConfidence.HIGH,
    )

    @Test
    fun `candidate amount renders Money Compact BTC primary`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                RecurringCard(
                    candidates = listOf(candidate()),
                    onAccept = {},
                    onDismiss = {},
                )
            }
        }
        // The amount cell carries its own testTag; it lives inside a
        // Column above the action buttons (not inside a clickable row,
        // so the merged tree is enough).
        val amountNodes = onAllNodesWithTag(RecurringTags.amountTag("spotify"))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(amountNodes.isNotEmpty(), "Money Compact amount tag should be in the tree")
        // BTC primary with fiat secondary inline (Compact format =
        // "200,000 sat (€120)" with the default sats unit — the suffix
        // "(€…)" implies the secondary).
        waitUntilAtLeastOneExists(hasText("200,000 sat", substring = true))
        waitUntilAtLeastOneExists(hasText("€", substring = true))
        // Frequency suffix stays plain text.
        waitUntilAtLeastOneExists(hasText("monthly", substring = true))
    }

    @Test
    fun `accept and dismiss actions remain wired`() = runDesktopComposeUiTest {
        var accepted = 0
        var dismissed = 0
        setContent {
            BhodlTheme {
                RecurringCard(
                    candidates = listOf(candidate()),
                    onAccept = { accepted += 1 },
                    onDismiss = { dismissed += 1 },
                )
            }
        }
        waitUntilAtLeastOneExists(hasTestTag(RecurringTags.acceptTag("spotify")))
        onNodeWithTag(RecurringTags.acceptTag("spotify")).assertIsDisplayed()
        onNodeWithTag(RecurringTags.dismissTag("spotify")).assertIsDisplayed()

        onNodeWithTag(RecurringTags.acceptTag("spotify")).performClick()
        assertEquals(1, accepted, "Accept should fire onAccept once")
        onNodeWithTag(RecurringTags.dismissTag("spotify")).performClick()
        assertEquals(1, dismissed, "Dismiss should fire onDismiss once")
    }

    @Test
    fun `empty candidates renders nothing`() = runDesktopComposeUiTest {
        setContent {
            BhodlTheme {
                RecurringCard(
                    candidates = emptyList(),
                    onAccept = {},
                    onDismiss = {},
                )
            }
        }
        // No card → no DETECTED label. Phase 7.5 chose to hide rather
        // than show a zero-state.
        val nodes = onAllNodesWithTag(RecurringTags.amountTag("spotify"))
            .fetchSemanticsNodes(atLeastOneRootRequired = false)
        assertTrue(nodes.isEmpty(), "no amount tags should exist when candidates is empty")
    }
}
