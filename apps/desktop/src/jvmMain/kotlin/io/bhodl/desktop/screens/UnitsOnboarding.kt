package io.bhodl.desktop.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.money.BitcoinAmountFormat
import io.bhodl.desktop.ui.money.UnitFormat
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.units_intro_body
import io.bhodl.shared.resources.units_intro_got_it
import io.bhodl.shared.resources.units_intro_hover
import io.bhodl.shared.resources.units_intro_title
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/**
 * UX review 2026-10 (item 16) — one-time units explainer of the
 * first-run onboarding (Welcome screen, no wallet yet).
 *
 * The default unit stays sats (user decision); the card shows the
 * DOUBLE display: each example amount in sats, with the same amount in
 * BTC on hover (tooltip) and in its accessible description. BIP177 and
 * BTC / Auto remain choices in Settings › Appearance. "Got it" stores
 * a single flag ([SettingsStore.onboardingUnitsSeen]); the card never
 * comes back.
 */
@Composable
internal fun UnitsOnboardingCard(modifier: Modifier = Modifier) {
    val seen by SettingsStore.onboardingUnitsSeen
    if (seen) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(8.dp))
            .padding(16.dp)
            .testTag(UnitsOnboardingTags.CARD),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(Res.string.units_intro_title),
            style = BhodlTheme.typography.titleMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        Text(
            text = stringResource(Res.string.units_intro_body),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UNITS_EXAMPLES_SAT.forEach { sat -> DualUnitAmount(sat) }
        }
        Text(
            text = stringResource(Res.string.units_intro_hover),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
        Button(
            onClick = { SettingsStore.markOnboardingUnitsSeen() },
            modifier = Modifier.testTag(UnitsOnboardingTags.GOT_IT),
        ) { Text(stringResource(Res.string.units_intro_got_it)) }
    }
}

/** Example amounts: a coffee, a month of savings, one bitcoin. */
internal val UNITS_EXAMPLES_SAT: List<Long> = listOf(5_000L, 250_000L, 100_000_000L)

/** "sats" text, "BTC" on hover. Illustrative values: never privacy-masked. */
internal fun dualUnitTexts(sat: Long, bip177: Boolean = false): Pair<String, String> {
    val sats = BitcoinAmountFormat.format(sat = sat, unit = UnitFormat.ALWAYS_SAT, bip177 = bip177, masked = false)
    val btc = BitcoinAmountFormat.format(sat = sat, unit = UnitFormat.ALWAYS_BTC, bip177 = false, masked = false)
    return sats to btc
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DualUnitAmount(sat: Long) {
    val bip177 by SettingsStore.bip177Notation
    val (sats, btc) = dualUnitTexts(sat, bip177)
    TooltipArea(
        tooltip = {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.surfaceVariant)
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag(UnitsOnboardingTags.tooltip(sat)),
            ) {
                Text(btc, style = BhodlTheme.typography.amountSmall, color = BhodlTheme.colors.textPrimary)
            }
        },
        delayMillis = 200,
    ) {
        Text(
            text = sats,
            style = BhodlTheme.typography.amountSmall,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BhodlTheme.colors.rowZebra)
                .padding(horizontal = 8.dp, vertical = 4.dp)
                // Keyboard / screen-reader users get both units too.
                .semantics { contentDescription = "$sats = $btc" }
                .testTag(UnitsOnboardingTags.example(sat)),
        )
    }
}

internal object UnitsOnboardingTags {
    const val CARD: String = "units-onboarding.card"
    const val GOT_IT: String = "units-onboarding.got-it"
    fun example(sat: Long): String = "units-onboarding.example.$sat"
    fun tooltip(sat: Long): String = "units-onboarding.tooltip.$sat"
}
