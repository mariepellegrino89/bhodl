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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme

/**
 * Single placeholder composable shared by every wizard step in step 2c.1.
 *
 * Step 2c.1 wires the navigation skeleton only — the four wizard screens
 * (Welcome, GenerateMnemonic, ConfirmMnemonic, PinSetup) plus Unlock are
 * empty shells with a primary "next" CTA and an optional secondary "back"
 * CTA. Step 2c.2+ replace each call site with the real screen.
 *
 * Keeping a single shared placeholder lets us see the back-stack
 * behaviour and animation timings in isolation before any business
 * logic ships.
 */
@Composable
public fun WizardPlaceholderScreen(
    title: String,
    description: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
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
            Text(
                text = "BHODL",
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = title,
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = description,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onPrimary,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(primaryLabel)
            }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}
