package io.bhodl.desktop.ui.money

import java.util.Locale

/**
 * How to render a BTC-side magnitude — the user's "Bitcoin unit"
 * preference (Settings › Appearance, `SettingsStore.unitFormat`).
 *
 *  - [ALWAYS_SAT] — `"1,234,567 sat"` regardless of magnitude. The
 *    default (UX review 2026-10, user decision: "always sats").
 *  - [ALWAYS_BTC] — `"0.01234567 BTC"` regardless of magnitude.
 *  - [AUTO_THRESHOLD] — sats below 1 BTC, BTC at or above
 *    ([BitcoinAmountFormat.AUTO_THRESHOLD_SAT]). Until 2026-10 the
 *    threshold was 0.001 BTC (Phase 8.3.1); it moved to 1 BTC with the
 *    unified unit setting.
 *
 * Enum names are persisted in settings.json — do not rename.
 * Rendering lives in [BitcoinAmountFormat]; [render] is a shortcut.
 */
public enum class UnitFormat {
    ALWAYS_SAT,
    ALWAYS_BTC,
    AUTO_THRESHOLD;

    public fun render(
        sat: Long,
        bip177: Boolean = false,
        locale: Locale = Locale.getDefault(),
    ): String = BitcoinAmountFormat.format(sat, this, bip177 = bip177, locale = locale)
}
