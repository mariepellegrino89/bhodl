package io.bhodl.desktop.ui.money

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import io.bhodl.desktop.settings.SettingsStore

/**
 * UX review 2026-10 (item 13) — the app-wide privacy mode ("hide
 * balances"), implemented INSIDE the central amount formatters so a
 * new screen is covered as soon as it formats through them:
 *
 *  - [BitcoinAmountFormat.format] (and so `displayAmount`,
 *    `rememberAmountFormatter`, `UnitFormat.render`, `Money`),
 *  - the fiat helpers in `ui/AmountFormat.kt` (`formatFiat`,
 *    `formatFiatWithCode`, `formatFiatWithCurrency`, `formatBtcLike`),
 *  - the chart helpers that abbreviate amounts (Sankey `shortSat`,
 *    scatter ticks, chart axes).
 *
 * The mask is a FIXED-width run of [GLYPHS] — never one bullet per
 * digit — so the length of the masked text does not leak the order of
 * magnitude, and signs are dropped (a "+" / "-" reveals the direction
 * of a flow). The unit stays visible ("••••• sat") so the layout and
 * the column semantics are unchanged.
 *
 * Reading [active] reads Compose state ([SettingsStore.balancesHidden]),
 * so a formatter called during composition subscribes the caller and
 * the whole UI re-renders when the user toggles the mode.
 *
 * Not masked on purpose: CSV / BIP-329 exports and backups (they write
 * raw values, the export path warns instead), amount INPUT fields, the
 * BIP21 URI, and the payment confirmation surfaces wrapped in
 * [RevealAmounts] (a user must see what they sign).
 */
public object AmountMask {

    /** Fixed-width mask; five bullets regardless of the hidden value. */
    public const val GLYPHS: String = "•••••"

    /** `true` while the app-wide privacy mode is on. */
    public val active: Boolean get() = SettingsStore.balancesHidden.value

    /** Masked bitcoin amount that keeps the unit of the visible one. */
    public fun bitcoin(asBtc: Boolean, bip177: Boolean = false): String = when {
        asBtc -> "$GLYPHS BTC"
        bip177 -> "${BitcoinAmountFormat.BITCOIN_SIGN}$GLYPHS"
        else -> "$GLYPHS sat"
    }

    /** Masked fiat amount behind its display prefix ("€•••••", "CHF •••••"). */
    public fun fiat(prefix: String = ""): String = "$prefix$GLYPHS"
}

/**
 * `true` inside a subtree that must show real amounts even while the
 * privacy mode is on: the payment review / fee-bump confirmation
 * screens and a long-press "peek" on the net-worth hero.
 */
public val LocalRevealAmounts: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Composable view of the mask: global flag minus a local [RevealAmounts]. */
@Composable
public fun amountsMasked(): Boolean = AmountMask.active && !LocalRevealAmounts.current

/**
 * Show real amounts in [content] regardless of the privacy mode.
 * Composables that format through [amountsMasked] honour it; the pure
 * formatters take an explicit `masked` argument for these call sites.
 */
@Composable
public fun RevealAmounts(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRevealAmounts provides true, content = content)
}
