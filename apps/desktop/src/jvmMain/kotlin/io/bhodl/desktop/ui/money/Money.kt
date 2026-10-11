package io.bhodl.desktop.ui.money

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_no_rate
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 8.3.1 — unified amount renderer.
 *
 * Single composable entry point for every future "render an amount"
 * call site. Resolves the BTC line via the active [UnitFormat]
 * (the unified "Bitcoin unit" preference, [SettingsStore.unitFormat],
 * default sats, plus the BIP177 toggle) and the
 * fiat secondary via either the [MoneyValue.rateBtcFiat] snapshot or
 * the live [PriceCache].
 *
 * The composable does NOT replace any existing call site in 8.3.1 —
 * it is the foundation for the view-by-view migration in 8.3.2 onward.
 *
 * Sign + color rules:
 *  - [MoneySemantic.Pnl] colorises positive sat → `financialPositive`,
 *    negative sat → `financialNegative`. The plus sign is explicit on
 *    positive values.
 *  - [MoneySemantic.Delta] preserves the sign but stays in `textPrimary`
 *    — a delta is a fact, not a verdict.
 *  - Other semantics render as-is, no leading sign for non-negative
 *    values.
 *
 * Density choice drives both font sizes and the layout (single vs
 * dual line). See [MoneyDensity] for the chosen tokens.
 */
@Composable
public fun Money(
    value: MoneyValue,
    density: MoneyDensity = MoneyDensity.Medium,
    primary: MoneyPrimary = MoneyPrimary.Btc,
    showSecondary: Boolean = true,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val settingsUnit by SettingsStore.unitFormat
    // Inside a table, the unit resolved for the whole column.
    val unitFormat = io.bhodl.desktop.ui.LocalColumnUnit.current ?: settingsUnit
    val bip177 by SettingsStore.bip177Notation
    val livePriceBtcInFiat by PriceCache.price
    val activeFiatCurrency by SettingsStore.fiatCurrency
    val noRate = stringResource(Res.string.common_no_rate)
    // UX review 2026-10 (item 13) — app-wide privacy mode (minus a
    // local RevealAmounts, e.g. the hero's long-press peek).
    val masked = amountsMasked()

    // -----------------------------------------------------------------
    // 1. Resolve the BTC line (plan §3.2)
    // -----------------------------------------------------------------
    val resolvedSat: Long? = when {
        value.sat != null -> value.sat
        value.fiatAmount != null && value.rateBtcFiat != null && value.rateBtcFiat > 0.0 ->
            ((value.fiatAmount / value.rateBtcFiat) * 100_000_000.0).toLong()
        value.fiatAmount != null &&
            value.semantic == MoneySemantic.AtNow &&
            livePriceBtcInFiat != null &&
            livePriceBtcInFiat!! > 0.0 ->
            ((value.fiatAmount / livePriceBtcInFiat!!) * 100_000_000.0).toLong()
        else -> null
    }

    // Phase 9.3 — "—" is ambiguous (zero? missing data? missing
    // rate?). When the value EXISTS in the other denomination and the
    // line is empty only because no conversion rate is available, say
    // so explicitly ("no rate", Copilot-style transparency). A bare
    // "—" now always means "no underlying value at all".
    val btcLine: String = when {
        resolvedSat != null -> {
            val rendered = BitcoinAmountFormat.format(resolvedSat, unitFormat, bip177 = bip177, masked = masked)
            // P&L positive values get an explicit "+" prefix per spec §3.2.
            if (!masked && value.semantic == MoneySemantic.Pnl && resolvedSat > 0L) "+$rendered" else rendered
        }
        value.fiatAmount != null -> noRate
        else -> "—"
    }

    // -----------------------------------------------------------------
    // 2. Resolve the fiat line (plan §3.2)
    // -----------------------------------------------------------------
    val resolvedFiatAmount: Double? = when {
        value.fiatAmount != null -> value.fiatAmount
        value.sat != null && livePriceBtcInFiat != null && livePriceBtcInFiat!! > 0.0 ->
            (value.sat.toDouble() / 100_000_000.0) * livePriceBtcInFiat!!
        else -> null
    }
    val resolvedFiatCurrencyCode: String? = value.fiatCurrency
        ?: activeFiatCurrency.takeIf { it != FiatCurrency.NONE }?.name

    // UX review 2026-10 (B7) — shared ISO prefix ("CHF 3,200.00"; the
    // old "₣" rendered as "F"); symbol placement and separators follow
    // the app language (formatFiatWithCode).

    val fiatLine: String = when {
        resolvedFiatAmount != null && resolvedFiatCurrencyCode != null && masked ->
            io.bhodl.desktop.ui.formatFiatWithCode(0.0, resolvedFiatCurrencyCode, masked = true)
        resolvedFiatAmount != null && resolvedFiatCurrencyCode != null -> {
            val absValue = if (resolvedFiatAmount < 0.0) -resolvedFiatAmount else resolvedFiatAmount
            val sign = when {
                value.semantic == MoneySemantic.Pnl && resolvedFiatAmount > 0.0 -> "+"
                resolvedFiatAmount < 0.0 -> "-"
                else -> ""
            }
            "$sign${io.bhodl.desktop.ui.formatFiatWithCode(absValue, resolvedFiatCurrencyCode, masked = false)}"
        }
        // Phase 9.3 — sat value exists but no live rate (or no fiat
        // currency selected): explicit, not a silent dash.
        value.sat != null && resolvedFiatCurrencyCode != null -> noRate
        else -> "—"
    }

    // -----------------------------------------------------------------
    // 3. Color (semantic-driven)
    // -----------------------------------------------------------------
    val pnlColor: Color = when {
        // Privacy mode: the P&L colour would leak the sign.
        masked -> BhodlTheme.colors.textPrimary
        value.semantic == MoneySemantic.Pnl && resolvedSat != null && resolvedSat >= 0L ->
            BhodlTheme.colors.financialPositive
        value.semantic == MoneySemantic.Pnl && resolvedSat != null && resolvedSat < 0L ->
            BhodlTheme.colors.financialNegative
        // Delta and the other semantics stay neutral — the value
        // matters but its sign is informational, not a verdict.
        else -> BhodlTheme.colors.textPrimary
    }

    // -----------------------------------------------------------------
    // 4. Render
    // -----------------------------------------------------------------
    val taggedModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    // Swap primary / secondary depending on the caller's choice.
    val primaryText = if (primary == MoneyPrimary.Btc) btcLine else fiatLine
    val secondaryText = if (primary == MoneyPrimary.Btc) fiatLine else btcLine

    when (density) {
        MoneyDensity.Compact -> {
            // Single inline line: "₿0.0194 (€11.4k)" or just "₿0.0194"
            // when secondary is off or unavailable.
            val secondaryReadable = secondaryText != "—" && showSecondary
            val text = if (secondaryReadable) "$primaryText ($secondaryText)" else primaryText
            Text(
                text = text,
                fontSize = 12.sp,
                color = pnlColor,
                modifier = taggedModifier,
            )
        }
        MoneyDensity.Medium -> {
            Column(
                modifier = taggedModifier,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = pnlColor,
                )
                if (showSecondary) {
                    Text(
                        text = secondaryText,
                        fontSize = 11.sp,
                        color = BhodlTheme.colors.textSecondary,
                    )
                }
            }
        }
        MoneyDensity.Hero -> {
            Column(
                modifier = taggedModifier,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = primaryText,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = pnlColor,
                )
                if (showSecondary) {
                    Text(
                        text = secondaryText,
                        fontSize = 16.sp,
                        color = BhodlTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}
