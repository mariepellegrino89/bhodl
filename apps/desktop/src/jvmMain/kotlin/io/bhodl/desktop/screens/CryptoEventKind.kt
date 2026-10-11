package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.trp_cek_airdrop
import io.bhodl.shared.resources.trp_cek_badge_airdrop
import io.bhodl.shared.resources.trp_cek_badge_gift_in
import io.bhodl.shared.resources.trp_cek_badge_gift_out
import io.bhodl.shared.resources.trp_cek_badge_interest
import io.bhodl.shared.resources.trp_cek_badge_internal
import io.bhodl.shared.resources.trp_cek_badge_mining
import io.bhodl.shared.resources.trp_cek_badge_staking
import io.bhodl.shared.resources.trp_cek_badge_swap
import io.bhodl.shared.resources.trp_cek_gift_received
import io.bhodl.shared.resources.trp_cek_gift_sent
import io.bhodl.shared.resources.trp_cek_lending
import io.bhodl.shared.resources.trp_cek_mining
import io.bhodl.shared.resources.trp_cek_regular
import io.bhodl.shared.resources.trp_cek_send_to_self
import io.bhodl.shared.resources.trp_cek_staking
import io.bhodl.shared.resources.trp_cek_swap
import io.bhodl.shared.resources.trp_cek_tax_note
import io.bhodl.shared.resources.trp_cek_title

/**
 * Phase 7.10 — semantic classification of a PaymentEvent for tax
 * purposes. Mirrors the Rust `VALID_CRYPTO_EVENT_KINDS` whitelist.
 *
 * Tax treatment of `gift_*` varies by jurisdiction — the UI shows a
 * footer warning when the user picks one. BHODL doesn't try to apply
 * any country-specific rule; the tax-export reader (Phase 8) maps
 * these kinds to country-specific accounts.
 */
internal enum class CryptoEventKind(public val wireValue: String) {
    /** Default — no special tax treatment, equivalent to no row. */
    REGULAR("regular"),

    /** Ordinary income at fair market value on receipt. */
    AIRDROP("airdrop"),

    /** Ordinary income — staking, mining, lending reuse the same flow. */
    STAKING_REWARD("staking_reward"),
    LENDING_INTEREST("lending_interest"),
    MINING_REWARD("mining_reward"),

    /** Capital gain on disposed side + new lot on incoming. */
    SWAP("swap"),

    /** Internal transfer between user's own wallets — not taxable. */
    SEND_TO_SELF("send_to_self"),

    /** Varies — basis usually inherits from the donor. */
    GIFT_RECEIVED("gift_received"),

    /** Varies — many jurisdictions exempt capital gain on gifts sent. */
    GIFT_SENT("gift_sent");

    public companion object {
        public fun fromWire(s: String?): CryptoEventKind = when (s) {
            "airdrop" -> AIRDROP
            "staking_reward" -> STAKING_REWARD
            "lending_interest" -> LENDING_INTEREST
            "mining_reward" -> MINING_REWARD
            "swap" -> SWAP
            "send_to_self" -> SEND_TO_SELF
            "gift_received" -> GIFT_RECEIVED
            "gift_sent" -> GIFT_SENT
            else -> REGULAR  // null / "regular" / unknown
        }
    }
}

/**
 * Phase 7.10 — short uppercase label for the row badge. Skipped for
 * REGULAR because its absence is the default UX (no badge means
 * "ordinary tx").
 */
@Composable
internal fun CryptoEventKind.badgeLabel(): String? = when (this) {
    CryptoEventKind.REGULAR -> null
    CryptoEventKind.AIRDROP -> stringResource(Res.string.trp_cek_badge_airdrop)
    CryptoEventKind.STAKING_REWARD -> stringResource(Res.string.trp_cek_badge_staking)
    CryptoEventKind.LENDING_INTEREST -> stringResource(Res.string.trp_cek_badge_interest)
    CryptoEventKind.MINING_REWARD -> stringResource(Res.string.trp_cek_badge_mining)
    CryptoEventKind.SWAP -> stringResource(Res.string.trp_cek_badge_swap)
    CryptoEventKind.SEND_TO_SELF -> stringResource(Res.string.trp_cek_badge_internal)
    CryptoEventKind.GIFT_RECEIVED -> stringResource(Res.string.trp_cek_badge_gift_in)
    CryptoEventKind.GIFT_SENT -> stringResource(Res.string.trp_cek_badge_gift_out)
}

/**
 * Phase 7.10 — human-readable label for the picker rows. Slightly
 * longer than the badge so the user can disambiguate when there are
 * 9 options in the dialog.
 */
@Composable
internal fun CryptoEventKind.displayLabel(): String = when (this) {
    CryptoEventKind.REGULAR -> stringResource(Res.string.trp_cek_regular)
    CryptoEventKind.AIRDROP -> stringResource(Res.string.trp_cek_airdrop)
    CryptoEventKind.STAKING_REWARD -> stringResource(Res.string.trp_cek_staking)
    CryptoEventKind.LENDING_INTEREST -> stringResource(Res.string.trp_cek_lending)
    CryptoEventKind.MINING_REWARD -> stringResource(Res.string.trp_cek_mining)
    CryptoEventKind.SWAP -> stringResource(Res.string.trp_cek_swap)
    CryptoEventKind.SEND_TO_SELF -> stringResource(Res.string.trp_cek_send_to_self)
    CryptoEventKind.GIFT_RECEIVED -> stringResource(Res.string.trp_cek_gift_received)
    CryptoEventKind.GIFT_SENT -> stringResource(Res.string.trp_cek_gift_sent)
}

/**
 * Phase 7.10 — color accent for the badge AND for the picker chip
 * selected state. Income-y kinds (airdrop / staking / mining /
 * interest) share the `financialPositive` family; capital-gain (swap)
 * + gifts take distinct hues; `send_to_self` is neutral grey because
 * it's not a tax event.
 */
@Composable
internal fun CryptoEventKind.accentColor(): Color = when (this) {
    CryptoEventKind.REGULAR -> BhodlTheme.colors.textTertiary
    CryptoEventKind.AIRDROP -> BhodlTheme.colors.brandPrimary
    CryptoEventKind.STAKING_REWARD,
    CryptoEventKind.MINING_REWARD,
    CryptoEventKind.LENDING_INTEREST -> BhodlTheme.colors.financialPositive
    CryptoEventKind.SWAP -> BhodlTheme.colors.chartCategorical
        .getOrElse(2) { BhodlTheme.colors.accentNeon }  // purple-ish
    CryptoEventKind.SEND_TO_SELF -> BhodlTheme.colors.textTertiary
    CryptoEventKind.GIFT_RECEIVED,
    CryptoEventKind.GIFT_SENT -> BhodlTheme.colors.accentNeon
}

/**
 * Phase 7.10 — pill badge rendered next to the row's category badge
 * on the History tab. Hidden when the kind is REGULAR (= default).
 */
@Composable
internal fun CryptoEventKindBadge(
    kind: CryptoEventKind,
    modifier: Modifier = Modifier,
) {
    val label = kind.badgeLabel() ?: return
    val accent = kind.accentColor()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .testTag(CryptoEventKindTags.badgeTag(kind.wireValue)),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = accent,
        )
    }
}

/**
 * Phase 7.10 — picker section for the tx detail dialog. Two rows of
 * chips (Income / Capital). When the user picks a `gift_*`, a footer
 * warns that the tax treatment varies — BHODL doesn't apply any
 * country-specific rule. Selecting REGULAR clears the override.
 */
@Composable
internal fun CryptoEventKindPicker(
    current: CryptoEventKind,
    onChange: (CryptoEventKind) -> Unit,
) {
    Text(
        text = stringResource(Res.string.trp_cek_title),
        style = BhodlTheme.typography.labelMedium,
        color = BhodlTheme.colors.textSecondary,
    )
    // Two rows so 9 options don't wrap awkwardly on small windows.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            CryptoEventKind.REGULAR,
            CryptoEventKind.AIRDROP,
            CryptoEventKind.STAKING_REWARD,
            CryptoEventKind.LENDING_INTEREST,
            CryptoEventKind.MINING_REWARD,
        ).forEach { k -> CryptoKindChip(k, current, onChange) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            CryptoEventKind.SWAP,
            CryptoEventKind.SEND_TO_SELF,
            CryptoEventKind.GIFT_RECEIVED,
            CryptoEventKind.GIFT_SENT,
        ).forEach { k -> CryptoKindChip(k, current, onChange) }
    }
    if (current == CryptoEventKind.GIFT_RECEIVED || current == CryptoEventKind.GIFT_SENT) {
        Text(
            text = stringResource(Res.string.trp_cek_tax_note),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CryptoKindChip(
    kind: CryptoEventKind,
    current: CryptoEventKind,
    onChange: (CryptoEventKind) -> Unit,
) {
    val selected = kind == current
    val accent = kind.accentColor()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) accent.copy(alpha = 0.18f)
                else BhodlTheme.colors.surfaceVariant,
            )
            .clickable { onChange(kind) }
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(CryptoEventKindTags.chipTag(kind.wireValue)),
    ) {
        Text(
            text = kind.displayLabel(),
            style = BhodlTheme.typography.labelMedium,
            color = if (selected) accent else BhodlTheme.colors.textSecondary,
        )
    }
}

internal object CryptoEventKindTags {
    fun badgeTag(kind: String): String = "crypto-event-kind.badge.$kind"
    fun chipTag(kind: String): String = "crypto-event-kind.chip.$kind"
}
