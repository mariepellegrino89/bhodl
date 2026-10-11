package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_network_label
import io.bhodl.shared.resources.wview_network_locked_hint
import org.jetbrains.compose.resources.stringResource

/**
 * Three-chip network picker shown at the top of the wallet creation /
 * restore wizard.
 *
 * Phase-2 scope: **Signet** (default), **Testnet4**, **Regtest**.
 * Mainnet is intentionally absent from the UI — we will gate it on
 * hardware-wallet support landing in Phase 4 so users do not put real
 * funds behind a hot-key software-only signing path. Power users can
 * still create mainnet wallets through the FFI directly during dev.
 *
 * The choice is **immutable for the lifetime of the wallet** because
 * BIP-86 derivation paths encode `coin_type` per network: switching at
 * runtime would either lock the user out (descriptor mismatch on load)
 * or silently surface a different wallet entirely. The Settings screen
 * surfaces the persisted choice read-only with guidance on how to
 * switch (wipe + create / restore on a different network).
 */
@Composable
public fun NetworkSelector(
    selected: Network,
    onSelected: (Network) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(Res.string.wview_network_label),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkChoice.entries.forEach { choice ->
                FilterChip(
                    selected = choice.network == selected,
                    onClick = { onSelected(choice.network) },
                    enabled = enabled,
                    label = {
                        Text(
                            text = choice.label,
                            style = BhodlTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BhodlTheme.colors.brandPrimary,
                        selectedLabelColor = BhodlTheme.colors.background,
                    ),
                    modifier = Modifier.testTag(NetworkSelectorTags.tagFor(choice.network)),
                )
            }
        }
        Text(
            text = stringResource(Res.string.wview_network_locked_hint),
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

/**
 * Networks the wizard offers, in display order. Mainnet is
 * intentionally absent — see the [`NetworkSelector`] kdoc.
 */
private enum class NetworkChoice(public val network: Network, public val label: String) {
    SIGNET(Network.SIGNET, "Signet"),
    TESTNET4(Network.TESTNET4, "Testnet4"),
    REGTEST(Network.REGTEST, "Regtest"),
}

/** Test tags exposed by [`NetworkSelector`]. */
public object NetworkSelectorTags {
    public const val SIGNET: String = "network-selector.signet"
    public const val TESTNET4: String = "network-selector.testnet4"
    public const val REGTEST: String = "network-selector.regtest"
    public const val MAINNET: String = "network-selector.mainnet"

    public fun tagFor(network: Network): String = when (network) {
        Network.SIGNET -> SIGNET
        Network.TESTNET4 -> TESTNET4
        Network.REGTEST -> REGTEST
        Network.MAINNET -> MAINNET
    }
}
