package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import kotlinx.coroutines.delay
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_copied
import io.bhodl.shared.resources.wview_copy_address
import io.bhodl.shared.resources.wview_qr_for_address
import io.bhodl.shared.resources.wview_receive_address
import org.jetbrains.compose.resources.stringResource

private const val COPY_FEEDBACK_MS = 1_500L

/**
 * Receive-address card.
 *
 * Layout:
 * - White QR pane (QR ink stays black on white regardless of theme — phone
 *   cameras and other QR readers expect that contrast and break otherwise).
 * - The address itself in centered monospace text, full-width inside the
 *   card so it wraps cleanly without bleeding off either edge.
 * - A "Copy address" outlined button that swaps to "Copied" for ~1.5s on
 *   tap so the user has visual confirmation without a popup.
 *
 * Stateful only for the copy-confirmation flash; everything else flows in
 * via [address]. Copy goes through [SensitiveClipboard] (kept out of the
 * Windows clipboard history / cloud clipboard).
 */
@Composable
public fun ReceiveAddressCard(
    address: String,
    modifier: Modifier = Modifier,
) {
    var justCopied by remember { mutableStateOf(false) }

    // Reset the "Copied" label after the flash window.
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(COPY_FEEDBACK_MS)
            justCopied = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = BhodlTheme.colors.surface,
            contentColor = BhodlTheme.colors.textPrimary,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(Res.string.wview_receive_address),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )

            // White QR pane.
            Box(
                modifier = Modifier
                    .size(192.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = rememberQrCodePainter(data = address),
                    contentDescription = stringResource(Res.string.wview_qr_for_address, address),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Address text.
            Text(
                text = address,
                style = BhodlTheme.typography.mono,
                color = BhodlTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            // Copy button.
            OutlinedButton(
                onClick = {
                    SensitiveClipboard.copy(address)
                    justCopied = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (justCopied) stringResource(Res.string.common_copied) else stringResource(Res.string.wview_copy_address))
            }
        }
    }
}
