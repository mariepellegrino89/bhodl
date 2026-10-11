package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ffi.Network
import java.awt.Desktop
import java.net.URI
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_explorer_copy_txid
import io.bhodl.shared.resources.wview_explorer_open
import io.bhodl.shared.resources.wview_explorer_open_anyway
import io.bhodl.shared.resources.wview_explorer_tor_body
import io.bhodl.shared.resources.wview_explorer_tor_title
import org.jetbrains.compose.resources.stringResource

/**
 * Audit 2026-10 (L6) — block-explorer links.
 *
 * - The explorer path follows the wallet's network (it used to be
 *   hard-coded to `/signet`, so a mainnet txid opened a 404 on the
 *   signet explorer). Regtest has no public explorer → no link.
 * - The link opens in the SYSTEM browser, i.e. outside Tor. With Tor
 *   enabled the user gets a confirmation that says so and offers
 *   "Copy txid" instead (paste it into Tor Browser).
 */
internal object ExplorerLinks {

    /** mempool.space `/tx` base URL for [network], `null` for regtest. */
    fun txBaseUrl(network: Network): String? = when (network) {
        Network.MAINNET -> "https://mempool.space/tx"
        Network.TESTNET4 -> "https://mempool.space/testnet4/tx"
        Network.SIGNET -> "https://mempool.space/signet/tx"
        Network.REGTEST -> null
    }

    /** Human label for [network] ("Mainnet", "Signet", …). */
    fun networkLabel(network: Network): String = when (network) {
        Network.MAINNET -> "Mainnet"
        Network.TESTNET4 -> "Testnet4"
        Network.SIGNET -> "Signet"
        Network.REGTEST -> "Regtest"
    }

    /** Host of an Esplora endpoint URL for display ("mempool.space"). */
    fun endpointHost(url: String): String =
        runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url
}

/** Test tags for [ExplorerLinkButton]. */
public object ExplorerLinkTags {
    public const val TOR_DIALOG: String = "explorer.tor-dialog"
    public const val TOR_DIALOG_COPY: String = "explorer.tor-dialog.copy"
    public const val TOR_DIALOG_OPEN: String = "explorer.tor-dialog.open"
}

/**
 * "Open in mempool.space" button. Hidden when [explorerBaseUrl] is null
 * (regtest). With Tor on, a confirmation dialog warns that the system
 * browser is outside Tor and offers [onCopyTxid] instead.
 */
@Composable
internal fun ExplorerLinkButton(
    txid: String,
    explorerBaseUrl: String?,
    openExplorer: (String) -> Unit,
    onCopyTxid: () -> Unit,
    modifier: Modifier = Modifier,
    torEnabled: Boolean = SettingsStore.torEnabled.value,
) {
    if (explorerBaseUrl == null) return
    val url = "$explorerBaseUrl/$txid"
    var confirmOpen by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { if (torEnabled) confirmOpen = true else openExplorer(url) },
        modifier = modifier,
    ) { Text(stringResource(Res.string.wview_explorer_open)) }

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            modifier = Modifier.testTag(ExplorerLinkTags.TOR_DIALOG),
            title = { Text(stringResource(Res.string.wview_explorer_tor_title)) },
            text = {
                Column {
                    Text(stringResource(Res.string.wview_explorer_tor_body))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        onCopyTxid()
                    },
                    modifier = Modifier.testTag(ExplorerLinkTags.TOR_DIALOG_COPY),
                ) { Text(stringResource(Res.string.wview_explorer_copy_txid)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        openExplorer(url)
                    },
                    modifier = Modifier.testTag(ExplorerLinkTags.TOR_DIALOG_OPEN),
                ) { Text(stringResource(Res.string.wview_explorer_open_anyway)) }
            },
        )
    }
}

/** Default explorer opener: the system browser (outside Tor). */
internal fun openInSystemBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported()) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
            }
        }
    }
}

/** Convenience: full-width variant used by the tx screens. */
@Composable
internal fun ExplorerLinkButtonFullWidth(
    txid: String,
    explorerBaseUrl: String?,
    openExplorer: (String) -> Unit,
    onCopyTxid: () -> Unit,
    testTag: String? = null,
) {
    ExplorerLinkButton(
        txid = txid,
        explorerBaseUrl = explorerBaseUrl,
        openExplorer = openExplorer,
        onCopyTxid = onCopyTxid,
        modifier = Modifier.fillMaxWidth().let { m -> testTag?.let { m.testTag(it) } ?: m },
    )
}
