package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.security_verify_intro
import io.bhodl.shared.resources.security_verify_load_failed
import io.bhodl.shared.resources.security_verify_title
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * UX review 2026-10 (item 14) — Security Hub › "Verify now".
 *
 * Wallet-PIN re-auth (same gate as the recovery-phrase reveal), then
 * the wizard's word challenge ([ConfirmMnemonicScreen]) against the
 * loaded signing wallet's phrase. On success only the DATE is stored
 * ([SettingsStore.markBackupVerified]); the phrase lives in Compose
 * state for the duration of the challenge and is dropped on exit.
 */
@Composable
public fun VerifyBackupScreen(
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var mnemonic by remember { mutableStateOf<String?>(null) }
    var reauthOpen by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { mnemonic = null } }

    val phrase = mnemonic
    if (phrase != null) {
        ConfirmMnemonicScreen(
            mnemonic = phrase,
            onConfirmed = {
                WalletStore.activeWalletId()?.let { SettingsStore.markBackupVerified(it) }
                mnemonic = null
                onDone()
            },
            onBack = {
                mnemonic = null
                onBack()
            },
        )
        return
    }

    Box(
        modifier = Modifier.fillMaxSize().background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .padding(horizontal = 32.dp, vertical = 40.dp)
                .testTag(VerifyBackupTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BhodlTopBar(title = stringResource(Res.string.security_verify_title), onBack = onBack)
            Text(
                text = stringResource(Res.string.security_verify_intro),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            error?.let {
                Text(
                    text = stringResource(Res.string.security_verify_load_failed, it),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    modifier = Modifier.testTag(VerifyBackupTags.ERROR),
                )
            }
            OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.action_back)) }
        }
    }

    if (reauthOpen) {
        WalletPinReauthDialog(
            purpose = stringResource(Res.string.security_verify_title).lowercase(),
            onVerified = {
                reauthOpen = false
                val wallet = WalletStore.current()
                if (wallet == null) {
                    onBack()
                    return@WalletPinReauthDialog
                }
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { wallet.peekMnemonic().mnemonic } }
                        .onSuccess { mnemonic = it }
                        .onFailure { error = it.userMessage("reveal failed") }
                }
            },
            onDismiss = {
                reauthOpen = false
                onBack()
            },
        )
    }
}

public object VerifyBackupTags {
    public const val ROOT: String = "verify-backup.root"
    public const val ERROR: String = "verify-backup.error"
}
