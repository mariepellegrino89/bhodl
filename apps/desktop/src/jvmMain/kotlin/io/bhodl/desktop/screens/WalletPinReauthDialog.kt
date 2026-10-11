package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wallet.UniffiWalletFactory
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinErrors
import io.bhodl.desktop.wizard.PinFailureKind
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_confirm
import io.bhodl.shared.resources.onb_checking
import io.bhodl.shared.resources.onb_pin_check_failed
import io.bhodl.shared.resources.onb_pin_wrong
import io.bhodl.shared.resources.onb_reauth_body
import io.bhodl.shared.resources.onb_reauth_pin_label
import io.bhodl.shared.resources.onb_reauth_title
import org.jetbrains.compose.resources.stringResource

/**
 * Security audit 2026-10 (H5) — verification of the ACTIVE signing
 * wallet's PIN right before a secret-revealing action (recovery-phrase
 * reveal, encrypted-backup export).
 *
 * Previously both actions only required the wallet to be unlocked, so
 * anyone walking up to an unlocked session could read the seed. The
 * FFI has no dedicated "verify wallet PIN" call, so the default
 * implementation re-runs the real unlock path (`unlockPersistent`)
 * against the wallet's own directory and immediately closes the
 * throw-away handle: the exact same check the Unlock screen performs.
 */
public interface WalletPinCheck {
    /** PinAttemptTracker scope for the active wallet. */
    public fun scope(): String

    /**
     * `true` = PIN correct, `false` = wrong PIN. Throws for anything
     * that is not a PIN rejection (storage, network mismatch, …).
     * Blocking — call from Dispatchers.IO.
     */
    public fun check(pin: String): Boolean
}

/** Production [WalletPinCheck] (see type doc). */
public object DefaultWalletPinCheck : WalletPinCheck {

    private fun activeDir(): File {
        val view = WalletPaths.from(WalletPaths.defaultDataDir())
        val id = WalletStore.activeWalletId()
        return if (id != null) File(view.walletsRoot, id) else view.activeWalletDir() ?: view.dir
    }

    override fun scope(): String =
        PinScopes.wallet(WalletStore.activeWalletId() ?: activeDir().name)

    override fun check(pin: String): Boolean {
        val dir = activeDir()
        return try {
            UniffiWalletFactory()
                .unlockPersistent(dir.absolutePath, WalletStore.network(), pin)
                .close()
            true
        } catch (e: Throwable) {
            if (PinErrors.classify(e) == PinFailureKind.WrongPin) false else throw e
        }
    }
}

/** Injection seam: tests provide a fake [WalletPinCheck]. */
public val LocalWalletPinCheck: androidx.compose.runtime.ProvidableCompositionLocal<WalletPinCheck> =
    staticCompositionLocalOf { DefaultWalletPinCheck }

/**
 * Modal PIN re-entry. Calls [onVerified] once the wallet PIN checks
 * out; every attempt is counted by [PinAttemptTracker] under the
 * wallet's scope (same lockout as the Unlock screen).
 */
@Composable
internal fun WalletPinReauthDialog(
    purpose: String,
    onVerified: () -> Unit,
    onDismiss: () -> Unit,
) {
    val check = LocalWalletPinCheck.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error: String? by remember { mutableStateOf(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (working || pin.isBlank()) return
        val scopeKey = check.scope()
        PinAttemptTracker.lockoutMessage(scopeKey)?.let {
            error = it
            return
        }
        working = true
        error = null
        val attempted = pin
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { check.check(attempted) } }
            working = false
            when (result.getOrNull()) {
                true -> {
                    PinAttemptTracker.recordSuccess(scopeKey)
                    pin = ""
                    onVerified()
                }
                false -> {
                    PinAttemptTracker.recordFailure(scopeKey)
                    pin = ""
                    error = PinAttemptTracker.lockoutMessage(scopeKey) ?: localizedText(Res.string.onb_pin_wrong)
                }
                null -> {
                    val e = result.exceptionOrNull()
                    val kind = e?.let { PinErrors.classify(it) } ?: PinFailureKind.Other
                    error = if (kind == PinFailureKind.Other) {
                        localizedText(Res.string.onb_pin_check_failed).let { fallback ->
                            e?.userMessage(fallback) ?: fallback
                        }
                    } else {
                        PinErrors.messageFor(kind)
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(Res.string.onb_reauth_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(Res.string.onb_reauth_body, purpose),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { c -> !c.isWhitespace() } },
                    label = { Text(stringResource(Res.string.onb_reauth_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(WalletPinReauthTags.PIN),
                )
                if (error != null) {
                    Text(
                        text = error!!,
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.error,
                        modifier = Modifier.testTag(WalletPinReauthTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = pin.isNotBlank() && !working,
                modifier = Modifier.testTag(WalletPinReauthTags.CONFIRM),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_checking)
                    else stringResource(Res.string.common_confirm),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !working,
                modifier = Modifier.testTag(WalletPinReauthTags.CANCEL),
            ) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** Test tags for [WalletPinReauthDialog]. */
public object WalletPinReauthTags {
    public const val PIN: String = "wallet-pin-reauth.pin"
    public const val CONFIRM: String = "wallet-pin-reauth.confirm"
    public const val CANCEL: String = "wallet-pin-reauth.cancel"
    public const val ERROR: String = "wallet-pin-reauth.error"
}
