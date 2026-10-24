package io.bhodl.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.LocalWipe
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinErrors
import io.bhodl.desktop.wizard.PinFailureKind
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletPathsView
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ui.theme.BhodlTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.onb_device_pin
import io.bhodl.shared.resources.onb_device_pin_wrong
import io.bhodl.shared.resources.onb_registry_open_failed
import io.bhodl.shared.resources.onb_wipe_body
import io.bhodl.shared.resources.onb_wipe_confirm
import io.bhodl.shared.resources.onb_wipe_erasing
import io.bhodl.shared.resources.onb_wipe_field
import io.bhodl.shared.resources.onb_wipe_forgot_pin
import io.bhodl.shared.resources.onb_wipe_move_confirm
import io.bhodl.shared.resources.onb_wipe_pin_body
import io.bhodl.shared.resources.onb_wipe_seed_ack
import io.bhodl.shared.resources.onb_wipe_title
import io.bhodl.shared.resources.onb_wipe_trash_body
import org.jetbrains.compose.resources.stringResource

/**
 * Audit 2026-10 — device-PIN check used by the pre-auth wipe on Welcome.
 * `true` = PIN correct, `false` = wrong PIN; throws for anything that
 * is not a PIN rejection. Blocking — call from Dispatchers.IO.
 */
public fun interface DevicePinCheck {
    public fun check(dataDir: File, pin: String): Boolean
}

/**
 * Production [DevicePinCheck]: opens the registry with the PIN (the
 * same SQLCipher key probe as [DevicePinUnlockScreen]) and immediately
 * destroys the throw-away handle so the file is not left locked.
 */
public object DefaultDevicePinCheck : DevicePinCheck {
    override fun check(dataDir: File, pin: String): Boolean = try {
        RegistryHandle.createOrLoad(dataDir = dataDir.absolutePath, devicePin = pin).destroy()
        true
    } catch (e: Throwable) {
        if (PinErrors.classify(e) == PinFailureKind.WrongPin) false else throw e
    }
}

/** Injection seam: tests provide a fake [DevicePinCheck]. */
public val LocalDevicePinCheck: androidx.compose.runtime.ProvidableCompositionLocal<DevicePinCheck> =
    staticCompositionLocalOf { DefaultDevicePinCheck }

/**
 * Security audit 2026-10 (M8) — typed-phrase confirmation for the
 * "erase ALL local wallets" action (Welcome reset + Settings factory
 * reset). A two-tap confirm was too easy to trigger by accident.
 *
 * [preAuth] = the Welcome variant, reachable from the locked PIN gate
 * without any authentication (it is the "forgot PIN" escape hatch).
 * Audit 2026-10 hardening:
 *  - when a registry exists, the permanent wipe also requires the
 *    device PIN (counted by [PinAttemptTracker] under the device scope,
 *    same lockout as the unlock gate);
 *  - "I forgot the device PIN" (or no registry at all) never deletes:
 *    the files are moved to a recoverable trash folder under
 *    [trashRoot] ([LocalWipe.moveAllToTrash]) after an explicit
 *    "I have my seed backup" acknowledgement.
 * Settings (authenticated session) keeps the plain typed-phrase wipe.
 *
 * Runs on Dispatchers.IO and hands the verified outcome to [onFinished]
 * (the caller decides whether to navigate).
 */
@Composable
internal fun ConfirmWipeDialog(
    dataDir: File,
    onDismiss: () -> Unit,
    onFinished: (WalletPathsView.ResetResult) -> Unit,
    preAuth: Boolean = false,
    trashRoot: File = LocalWipe.defaultTrashRoot(dataDir),
) {
    val pinCheck = LocalDevicePinCheck.current
    val scope = rememberCoroutineScope()
    val registryOnDisk = remember(dataDir) { WalletPaths.from(dataDir).registryExists() }
    var typed by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var forgotPin by remember { mutableStateOf(false) }
    var seedAck by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error: String? by remember { mutableStateOf(null) }

    // Which variant is showing. Functions, not vals: run() below must
    // read the live state, never a value captured at composition time.
    fun needsPinNow() = preAuth && registryOnDisk && !forgotPin
    fun toTrashNow() = preAuth && !needsPinNow()
    fun canConfirmNow() = LocalWipe.isConfirmed(typed) && !working &&
        (!needsPinNow() || pin.isNotBlank()) &&
        (!toTrashNow() || seedAck)

    val needsPin = needsPinNow()
    val toTrash = toTrashNow()
    val canConfirm = canConfirmNow()

    fun run() {
        if (!canConfirmNow()) return
        val pinMode = needsPinNow()
        val trashMode = toTrashNow()
        if (pinMode) {
            PinAttemptTracker.lockoutMessage(PinScopes.DEVICE)?.let {
                error = it
                return
            }
        }
        working = true
        error = null
        val attempted = pin
        scope.launch {
            if (pinMode) {
                val verdict = runCatching { withContext(Dispatchers.IO) { pinCheck.check(dataDir, attempted) } }
                when (verdict.getOrNull()) {
                    true -> PinAttemptTracker.recordSuccess(PinScopes.DEVICE)
                    false -> {
                        PinAttemptTracker.recordFailure(PinScopes.DEVICE)
                        pin = ""
                        error = PinAttemptTracker.lockoutMessage(PinScopes.DEVICE)
                            ?: localizedText(Res.string.onb_device_pin_wrong)
                        working = false
                        return@launch
                    }
                    null -> {
                        val e = verdict.exceptionOrNull()
                        val kind = e?.let { PinErrors.classify(it) } ?: PinFailureKind.Other
                        error = if (kind == PinFailureKind.Other) {
                            val fallback = localizedText(Res.string.onb_registry_open_failed)
                            e?.userMessage(fallback) ?: fallback
                        } else {
                            PinErrors.messageFor(kind)
                        }
                        working = false
                        return@launch
                    }
                }
            }
            val result = withContext(Dispatchers.IO) {
                if (trashMode) LocalWipe.moveAllToTrash(dataDir, trashRoot) else LocalWipe.wipeAll(dataDir)
            }
            working = false
            onFinished(result)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(Res.string.onb_wipe_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (toTrash) {
                        stringResource(Res.string.onb_wipe_trash_body, trashRoot.absolutePath, LocalWipe.CONFIRM_PHRASE)
                    } else {
                        stringResource(Res.string.onb_wipe_body, LocalWipe.CONFIRM_PHRASE)
                    },
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.testTag(if (toTrash) ConfirmWipeTags.TRASH_BODY else ConfirmWipeTags.BODY),
                )
                if (needsPin) {
                    Text(
                        text = stringResource(Res.string.onb_wipe_pin_body),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter { c -> !c.isWhitespace() } },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.onb_device_pin)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ConfirmWipeTags.PIN),
                    )
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.onb_wipe_field, LocalWipe.CONFIRM_PHRASE)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ConfirmWipeTags.FIELD),
                )
                if (preAuth && registryOnDisk) {
                    CheckRow(
                        checked = forgotPin,
                        onChange = {
                            forgotPin = it
                            pin = ""
                            error = null
                        },
                        text = stringResource(Res.string.onb_wipe_forgot_pin),
                        tag = ConfirmWipeTags.FORGOT_PIN,
                        enabled = !working,
                    )
                }
                if (toTrash) {
                    CheckRow(
                        checked = seedAck,
                        onChange = { seedAck = it },
                        text = stringResource(Res.string.onb_wipe_seed_ack),
                        tag = ConfirmWipeTags.SEED_ACK,
                        enabled = !working,
                    )
                }
                if (error != null) {
                    Text(
                        text = error!!,
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.error,
                        modifier = Modifier.testTag(ConfirmWipeTags.ERROR),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { run() },
                modifier = Modifier.testTag(ConfirmWipeTags.CONFIRM),
            ) {
                Text(
                    text = when {
                        working -> stringResource(Res.string.onb_wipe_erasing)
                        toTrash -> stringResource(Res.string.onb_wipe_move_confirm)
                        else -> stringResource(Res.string.onb_wipe_confirm)
                    },
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

@Composable
private fun CheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    text: String,
    tag: String,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            text = text,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
        )
    }
}

/** Test tags for [ConfirmWipeDialog]. */
public object ConfirmWipeTags {
    public const val FIELD: String = "confirm-wipe.field"
    public const val CONFIRM: String = "confirm-wipe.confirm"
    public const val CANCEL: String = "confirm-wipe.cancel"
    public const val BODY: String = "confirm-wipe.body"
    public const val TRASH_BODY: String = "confirm-wipe.trash-body"
    public const val PIN: String = "confirm-wipe.pin"
    public const val FORGOT_PIN: String = "confirm-wipe.forgot-pin"
    public const val SEED_ACK: String = "confirm-wipe.seed-ack"
    public const val ERROR: String = "confirm-wipe.error"
}
