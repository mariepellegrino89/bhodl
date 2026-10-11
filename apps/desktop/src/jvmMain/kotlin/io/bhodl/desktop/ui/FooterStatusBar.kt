package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.security_hub_open
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import io.bhodl.shared.resources.wview_footer_sync
import io.bhodl.shared.resources.wview_footer_wallet_one
import io.bhodl.shared.resources.wview_footer_wallets

/**
 * Phase 3.11 — footer status bar (Sparrow / Bitcoin Core / Specter
 * convention). Renders at the bottom of a screen as a fixed-height
 * strip with at-a-glance network state:
 *  - Network (signet / testnet / mainnet)
 *  - Connection: Tor (privacy-on) or Clearnet (privacy-off)
 *  - Esplora endpoint hostname (custom override visible)
 *  - Wallet count
 *  - Last refresh time
 *
 * UX review 2026-10 (items 13/14) — the right end carries the
 * privacy-mode eye toggle (Ctrl+Shift+H) and, when the host passes
 * [onOpenSecurityHub], the Security Hub shield.
 *
 * This is the single visible signal that the user is on the network
 * they expect. The convention is established: every serious desktop
 * Bitcoin wallet has a footer like this for "am I about to broadcast
 * to mainnet" reassurance.
 */
@Composable
public fun BhodlFooterStatusBar(
    network: Network,
    walletCount: Int,
    lastSyncUnix: Long?,
    modifier: Modifier = Modifier,
    onOpenSecurityHub: (() -> Unit)? = null,
) {
    val torEnabled by SettingsStore.torEnabled
    val esploraUrlOverride by SettingsStore.esploraUrl
    val timeFmt = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    val esploraHost = (esploraUrlOverride ?: EsploraEndpoint.DEFAULT_SIGNET)
        .substringAfter("://").substringBefore('/').take(32)
    val syncLabel = lastSyncUnix
        ?.let { timeFmt.format(Instant.ofEpochSecond(it)) }
        ?: "—"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(FooterStatusBarTags.ROOT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusPill(
            label = networkLabel(network),
            color = networkColor(network),
            testTagId = FooterStatusBarTags.NETWORK,
        )
        StatusPill(
            label = if (torEnabled) "Tor" else "Clearnet",
            color = if (torEnabled) BhodlTheme.colors.financialPositive
            else BhodlTheme.colors.financialPending,
            testTagId = FooterStatusBarTags.CONNECTION,
        )
        Text(
            text = esploraHost,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(
                if (walletCount == 1) Res.string.wview_footer_wallet_one else Res.string.wview_footer_wallets,
                walletCount,
            ),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.testTag(FooterStatusBarTags.WALLET_COUNT),
        )
        Text(
            text = stringResource(Res.string.wview_footer_sync, syncLabel),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.testTag(FooterStatusBarTags.SYNC),
        )
        PrivacyToggleButton()
        if (onOpenSecurityHub != null) {
            SecurityHubShieldButton(onClick = onOpenSecurityHub)
        }
    }
}

/**
 * Small color-coded chip used as the leading visual signal in the
 * footer. The color carries the "danger / safe / neutral" cue; the
 * label is the verbal redundancy (CVD-safe double encoding).
 */
@Composable
private fun StatusPill(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    testTagId: String,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .testTag(testTagId),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = color,
        )
    }
}

private fun networkLabel(n: Network): String = when (n) {
    Network.MAINNET -> "mainnet"
    Network.TESTNET4 -> "testnet"
    Network.SIGNET -> "signet"
    Network.REGTEST -> "regtest"
}

/**
 * Phase 3.11 — color-code the network pill:
 *  - mainnet: brand orange (real money — earns the visual weight)
 *  - signet / testnet / regtest: muted neutral (no real-value risk)
 *
 * Deliberately NOT using `financialNegative` for mainnet — red would
 * read as "error / danger" when it's actually the normal production
 * state.
 */
@Composable
private fun networkColor(n: Network): androidx.compose.ui.graphics.Color = when (n) {
    Network.MAINNET -> BhodlTheme.colors.brandPrimary
    else -> BhodlTheme.colors.financialNeutral
}

public object FooterStatusBarTags {
    public const val ROOT: String = "footer-status.root"
    public const val NETWORK: String = "footer-status.network"
    public const val CONNECTION: String = "footer-status.connection"
    public const val WALLET_COUNT: String = "footer-status.wallet-count"
    public const val SYNC: String = "footer-status.sync"
    public const val SECURITY_HUB: String = "footer-status.security-hub"
}

/** UX review 2026-10 (item 14) — footer entry point to the Security Hub. */
@Composable
private fun SecurityHubShieldButton(onClick: () -> Unit) {
    val label = stringResource(Res.string.security_hub_open)
    StatusBarIconButton(
        icon = Icons.Filled.Shield,
        label = label,
        tooltip = label,
        onClick = onClick,
        modifier = Modifier.testTag(FooterStatusBarTags.SECURITY_HUB),
    )
}
