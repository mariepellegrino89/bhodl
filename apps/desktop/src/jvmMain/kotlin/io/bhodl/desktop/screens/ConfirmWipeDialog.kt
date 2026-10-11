package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.wizard.LocalWipe
import io.bhodl.desktop.wizard.WalletPathsView
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.onb_wipe_body
import io.bhodl.shared.resources.onb_wipe_confirm
import io.bhodl.shared.resources.onb_wipe_erasing
import io.bhodl.shared.resources.onb_wipe_field
import io.bhodl.shared.resources.onb_wipe_title
import org.jetbrains.compose.resources.stringResource

/**
 * Security audit 2026-10 (M8) — typed-phrase confirmation for the
 * "erase ALL local wallets" action (Welcome reset + Settings factory
 * reset). A two-tap confirm was too easy to trigger by accident, and
 * the Welcome variant is reachable before any authentication.
 *
 * Runs [LocalWipe.wipeAll] on Dispatchers.IO and hands the verified
 * outcome to [onFinished] (the caller decides whether to navigate).
 */
@Composable
internal fun ConfirmWipeDialog(
    dataDir: java.io.File,
    onDismiss: () -> Unit,
    onFinished: (WalletPathsView.ResetResult) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(Res.string.onb_wipe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.onb_wipe_body, LocalWipe.CONFIRM_PHRASE),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.onb_wipe_field, LocalWipe.CONFIRM_PHRASE)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConfirmWipeTags.FIELD),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = LocalWipe.isConfirmed(typed) && !working,
                onClick = {
                    if (!LocalWipe.isConfirmed(typed) || working) return@TextButton
                    working = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { LocalWipe.wipeAll(dataDir) }
                        working = false
                        onFinished(result)
                    }
                },
                modifier = Modifier.testTag(ConfirmWipeTags.CONFIRM),
            ) {
                Text(
                    text = if (working) stringResource(Res.string.onb_wipe_erasing) else stringResource(Res.string.onb_wipe_confirm),
                    color = BhodlTheme.colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !working,
                modifier = Modifier.testTag(ConfirmWipeTags.CANCEL),
            ) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** Test tags for [ConfirmWipeDialog]. */
public object ConfirmWipeTags {
    public const val FIELD: String = "confirm-wipe.field"
    public const val CONFIRM: String = "confirm-wipe.confirm"
    public const val CANCEL: String = "confirm-wipe.cancel"
}
