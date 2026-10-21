package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.wsend_success_body
import io.bhodl.shared.resources.wsend_success_copy_txid
import io.bhodl.shared.resources.wsend_success_done
import io.bhodl.shared.resources.wsend_success_title
import io.bhodl.shared.resources.wsend_success_txid

/**
 * Step 3 of the send flow: success screen, shown after a successful
 * `broadcast_tx` returns the txid.
 *
 * The screen is intentionally minimal — txid (mono, copyable), an
 * "open in mempool.space" button, and a Done CTA that returns the user
 * to the dashboard. We do NOT auto-sync the wallet here: the dashboard's
 * own Sync button handles that, and forcing a network round-trip on
 * this screen would block the user behind a slow Esplora response with
 * no benefit (the broadcast already succeeded).
 */
@Composable
public fun SendSuccessScreen(
    txid: String,
    onDone: () -> Unit,
    // Audit 2026-10 (L6) — explorer path follows the wallet's network
    // (null on regtest → no link); was hard-coded to signet.
    explorerBaseUrl: String? = ExplorerLinks.txBaseUrl(io.bhodl.desktop.wizard.WalletStore.network()),
    openExplorer: (String) -> Unit = ::openInSystemBrowser,
) {

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 480.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Celebration check — Material Symbols Filled (Phase 7.0
            // replaced the Unicode ✓ glyph). Uses the financial-positive
            // token so the Eastern/Western convention is honoured.
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = stringResource(Res.string.wsend_success_title),
                tint = BhodlTheme.colors.financialPositive,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(Res.string.wsend_success_title),
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.wsend_success_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = BhodlTheme.colors.surface,
                    contentColor = BhodlTheme.colors.textPrimary,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.wsend_success_txid),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    SelectionContainer {
                        Text(
                            text = txid,
                            style = BhodlTheme.typography.mono,
                            color = BhodlTheme.colors.textPrimary,
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = { io.bhodl.desktop.ui.SensitiveClipboard.copy(txid) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.wsend_success_copy_txid))
            }

            // With Tor on, confirms first: the system browser is outside Tor.
            ExplorerLinkButton(
                txid = txid,
                explorerBaseUrl = explorerBaseUrl,
                openExplorer = openExplorer,
                onCopyTxid = { io.bhodl.desktop.ui.SensitiveClipboard.copy(txid) },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.wsend_success_done))
            }
        }
    }
}
