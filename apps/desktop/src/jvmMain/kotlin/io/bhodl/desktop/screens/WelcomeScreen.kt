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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_erase_all_local
import io.bhodl.shared.resources.onb_get_started
import io.bhodl.shared.resources.onb_welcome_body
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource

/**
 * First-launch screen (no registry on disk yet).
 *
 * Explains what BHODL is — a watch-only tracker that never holds keys —
 * shows the one-time sats / BTC explainer and leads to device-PIN
 * creation. Returning users never land here: the boot guard routes them
 * to the device-PIN gate and then to the tracker, whose wallet list is
 * the wallet picker.
 *
 * The reset button stays tucked at the bottom behind a typed-phrase
 * confirmation: it is the escape hatch for leftover data (e.g. a
 * registry whose device PIN was forgotten and then moved aside).
 */
@Composable
public fun WelcomeScreen(
    onGetStarted: () -> Unit,
    onResetComplete: () -> Unit,
    // Injection seam so Compose UI tests can target a tempdir
    // instead of `~/.bhodl/`.
    dataDir: java.io.File = WalletPaths.defaultDataDir(),
) {
    var resetDialogOpen by remember { mutableStateOf(false) }
    var resetMessage: String? by remember { mutableStateOf(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BhodlTheme.colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 560.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BHODL",
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_welcome_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            // UX review 2026-10 (item 16) — one-time sats / BTC explainer.
            UnitsOnboardingCard()

            Button(
                onClick = onGetStarted,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WelcomeTags.GET_STARTED),
            ) {
                Text(stringResource(Res.string.onb_get_started))
            }

            // Reset zone — visually de-emphasised. Audit 2026-10 (M8):
            // typed-phrase confirmation, handles released BEFORE the
            // files are deleted, deletion verified (see LocalWipe).
            resetMessage?.let {
                Text(
                    text = it,
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            TextButton(
                onClick = { resetDialogOpen = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(WelcomeTags.RESET),
            ) {
                Text(
                    text = stringResource(Res.string.onb_erase_all_local),
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            if (resetDialogOpen) {
                // Audit 2026-10 — pre-auth: device PIN for a real wipe,
                // otherwise ("forgot PIN") a recoverable move to trash.
                ConfirmWipeDialog(
                    dataDir = dataDir,
                    preAuth = true,
                    onDismiss = { resetDialogOpen = false },
                    onFinished = { result ->
                        resetDialogOpen = false
                        resetMessage = io.bhodl.desktop.wizard.LocalWipe.describe(result)
                        if (!result.aborted) onResetComplete()
                    },
                )
            }
        }
    }
}

public object WelcomeTags {
    public const val GET_STARTED: String = "welcome.get-started"
    public const val RESET: String = "welcome.reset"
}
