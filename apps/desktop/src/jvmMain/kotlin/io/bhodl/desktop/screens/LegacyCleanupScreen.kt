package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_legacy_body
import io.bhodl.shared.resources.onb_legacy_confirm
import io.bhodl.shared.resources.onb_legacy_count
import io.bhodl.shared.resources.onb_legacy_delete
import io.bhodl.shared.resources.onb_legacy_failed
import io.bhodl.shared.resources.onb_legacy_later
import io.bhodl.shared.resources.onb_legacy_title
import io.bhodl.shared.resources.onb_registry_open_failed
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * One-time cleanup after upgrading from a build that could hold seeds.
 *
 * BHODL is watch-only: signing wallets left on disk (registry rows of
 * kind `signing` plus their `wallets/<uuid>/` key directories, or a
 * pre-3.0 single-wallet install at the data-dir root) can no longer be
 * opened. They are deleted only after the user ticks "I have the
 * recovery phrases" — deletion is irreversible. "Not now" leaves
 * everything in place; the screen comes back on the next unlock.
 *
 * Watch wallets that mirrored a signing wallet survive as ordinary
 * watch wallets, so tracker history is kept.
 */
@Composable
public fun LegacyCleanupScreen(
    onDone: () -> Unit,
    dataDir: java.io.File = WalletPaths.defaultDataDir(),
) {
    val scope = rememberCoroutineScope()
    var registryCount by remember { mutableStateOf<Int?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        val registry = RegistryStore.current() ?: return@LaunchedEffect
        registryCount = runCatching {
            withContext(Dispatchers.IO) { registry.legacySigningWalletCount().toInt() }
        }.getOrNull()
    }

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
        ) {
            Text(
                text = stringResource(Res.string.onb_legacy_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_legacy_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            registryCount?.takeIf { it > 0 }?.let { n ->
                Text(
                    text = stringResource(Res.string.onb_legacy_count, n),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = confirmed,
                    onCheckedChange = { confirmed = it },
                    enabled = !working,
                    modifier = Modifier.testTag(LegacyCleanupTags.CONFIRM),
                )
                Text(
                    text = stringResource(Res.string.onb_legacy_confirm),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
            }
            lastError?.let {
                Text(
                    text = it,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Start,
                )
            }
            Button(
                onClick = {
                    working = true
                    lastError = null
                    scope.launch {
                        val outcome = runCatching {
                            withContext(Dispatchers.IO) {
                                // No wallet handle may hold a file lock.
                                WalletStore.clear()
                                RegistryStore.current()?.purgeLegacySigningWallets()
                                WalletPaths.from(dataDir).purgeLegacySigningData()
                            }
                        }
                        working = false
                        outcome.fold(
                            onSuccess = { result ->
                                if (result.ok) {
                                    onDone()
                                } else {
                                    lastError = localizedText(
                                        Res.string.onb_legacy_failed,
                                        result.failed.size,
                                    )
                                }
                            },
                            onFailure = { e ->
                                lastError = e.userMessage(localizedText(Res.string.onb_registry_open_failed))
                            },
                        )
                    }
                },
                enabled = confirmed && !working,
                colors = ButtonDefaults.buttonColors(containerColor = BhodlTheme.colors.error),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LegacyCleanupTags.DELETE),
            ) {
                Text(stringResource(Res.string.onb_legacy_delete))
            }
            TextButton(
                onClick = onDone,
                enabled = !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LegacyCleanupTags.LATER),
            ) {
                Text(stringResource(Res.string.onb_legacy_later))
            }
        }
    }
}

public object LegacyCleanupTags {
    public const val CONFIRM: String = "legacy-cleanup.confirm"
    public const val DELETE: String = "legacy-cleanup.delete"
    public const val LATER: String = "legacy-cleanup.later"
}
