package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.LocalWipe
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.PinValidator
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPathsView
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.WalletException
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_close
import io.bhodl.shared.resources.common_copied
import io.bhodl.shared.resources.common_copy
import io.bhodl.shared.resources.cp_current
import io.bhodl.shared.resources.cp_done
import io.bhodl.shared.resources.cp_failed
import io.bhodl.shared.resources.cp_save
import io.bhodl.shared.resources.cp_title
import io.bhodl.shared.resources.onb_device_pin_wrong
import io.bhodl.shared.resources.onb_device_pin_wrong_wait
import io.bhodl.shared.resources.onb_registry_open_failed
import io.bhodl.shared.resources.onb_wipe_confirm
import io.bhodl.shared.resources.onb_wipe_erasing
import io.bhodl.shared.resources.onb_wipe_field
import io.bhodl.shared.resources.pinrec_checking
import io.bhodl.shared.resources.pinrec_code_hint
import io.bhodl.shared.resources.pinrec_code_label
import io.bhodl.shared.resources.pinrec_confirm_pin
import io.bhodl.shared.resources.pinrec_err_code_format
import io.bhodl.shared.resources.pinrec_err_code_wrong
import io.bhodl.shared.resources.pinrec_err_code_wrong_wait
import io.bhodl.shared.resources.pinrec_have_code
import io.bhodl.shared.resources.pinrec_new_pin
import io.bhodl.shared.resources.pinrec_no_code
import io.bhodl.shared.resources.pinrec_no_code_body
import io.bhodl.shared.resources.pinrec_no_file
import io.bhodl.shared.resources.pinrec_reset
import io.bhodl.shared.resources.pinrec_title
import io.bhodl.shared.resources.rc_confirm_group
import io.bhodl.shared.resources.rc_create
import io.bhodl.shared.resources.rc_done
import io.bhodl.shared.resources.rc_failed
import io.bhodl.shared.resources.rc_group_label
import io.bhodl.shared.resources.rc_intro
import io.bhodl.shared.resources.rc_lose_both
import io.bhodl.shared.resources.rc_regenerate
import io.bhodl.shared.resources.rc_regenerate_warning
import io.bhodl.shared.resources.rc_title
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * PIN recovery (docs/pin-recovery.md). The Rust core keeps the data key
 * wrapped twice: by the device PIN and, once created, by a recovery
 * code. This file holds the three flows built on that:
 *
 *  - [ForgotPinPanel] — on the unlock screen: recovery code → new PIN,
 *    or, without a code, an explained local wipe;
 *  - [RecoveryCodePanel] — create / replace the code (Security Hub);
 *  - [ChangePinPanel] — change the device PIN (Security Hub).
 *
 * Wrong codes and wrong current PINs go through [PinAttemptTracker], so
 * the persistent lockout applies to every way of guessing.
 */
internal object RecoveryCodes {
    /** Error detail the core uses for a well-formed but wrong code. */
    const val REJECTED_DETAIL: String = "recovery code rejected"

    /** Groups of four symbols in a formatted code. */
    const val GROUPS: Int = 6

    /** Group [index] (1-based) of a formatted `XXXX-XXXX-…` code. */
    fun group(code: String, index: Int): String = code.split('-')[index - 1]

    /**
     * `true` when [typed] is group [index] of [code], with the same
     * tolerance as the core (case, spaces, `O`→`0`, `I`/`L`→`1`).
     */
    fun groupMatches(code: String, index: Int, typed: String): Boolean =
        canonical(typed) == group(code, index)

    fun canonical(typed: String): String = typed.uppercase()
        .filterNot { it == '-' || it.isWhitespace() }
        .map { c ->
            when (c) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> c
            }
        }
        .joinToString("")

    /** `true` for the core's "wrong recovery code" verdict (counted). */
    fun isRejected(e: Throwable): Boolean =
        e is WalletException.Storage && e.detail == REJECTED_DETAIL
}

/** User-facing message for a failed recovery attempt; counts wrong codes. */
internal fun recoveryFailureMessage(e: Throwable): String = when {
    RecoveryCodes.isRejected(e) -> {
        val deadline = PinAttemptTracker.recordFailure(PinScopes.RECOVERY)
        if (deadline > 0L) {
            localizedText(
                Res.string.pinrec_err_code_wrong_wait,
                PinAttemptTracker.cooldownRemainingSeconds(PinScopes.RECOVERY),
            )
        } else {
            localizedText(Res.string.pinrec_err_code_wrong)
        }
    }
    e is WalletException.InvalidArgument -> localizedText(Res.string.pinrec_err_code_format)
    else -> e.userMessage(localizedText(Res.string.onb_registry_open_failed))
}

/** A wrong current device PIN (change-PIN flow): counted like an unlock. */
internal fun wrongDevicePinMessage(): String {
    val deadline = PinAttemptTracker.recordFailure(PinScopes.DEVICE)
    return if (deadline > 0L) {
        localizedText(
            Res.string.onb_device_pin_wrong_wait,
            PinAttemptTracker.cooldownRemainingSeconds(PinScopes.DEVICE),
        )
    } else {
        localizedText(Res.string.onb_device_pin_wrong)
    }
}

private enum class ForgotStage { CHOOSE, CODE, NO_CODE }

/**
 * Body of the unlock screen once "Forgot your PIN?" is clicked.
 * [recover] opens the registry with the code and sets the new PIN
 * (production: `RegistryHandle.unlockWithRecoveryCode`); [wipe] is the
 * factory reset ([LocalWipe.wipeAll]).
 */
@Composable
internal fun ForgotPinPanel(
    dataDir: java.io.File,
    hasRecoveryCode: Boolean,
    recover: (dataDir: String, code: String, newPin: String) -> RegistryHandle,
    wipe: (java.io.File) -> WalletPathsView.ResetResult,
    onRecovered: (RegistryHandle) -> Unit,
    onWiped: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf(ForgotStage.CHOOSE) }
    var code by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var typedConfirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error: String? by remember { mutableStateOf(null) }

    Column(
        modifier = Modifier.fillMaxWidth().testTag(ForgotPinTags.ROOT),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.pinrec_title),
            style = BhodlTheme.typography.titleMedium,
            color = BhodlTheme.colors.textPrimary,
        )
        when (stage) {
            ForgotStage.CHOOSE -> {
                if (hasRecoveryCode) {
                    Button(
                        onClick = { stage = ForgotStage.CODE; error = null },
                        modifier = Modifier.fillMaxWidth().testTag(ForgotPinTags.HAVE_CODE),
                    ) { Text(stringResource(Res.string.pinrec_have_code)) }
                } else {
                    Text(
                        text = stringResource(Res.string.pinrec_no_file),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
                OutlinedButton(
                    onClick = { stage = ForgotStage.NO_CODE; error = null },
                    modifier = Modifier.fillMaxWidth().testTag(ForgotPinTags.NO_CODE),
                ) { Text(stringResource(Res.string.pinrec_no_code)) }
            }
            ForgotStage.CODE -> {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it; error = null },
                    label = { Text(stringResource(Res.string.pinrec_code_label)) },
                    supportingText = { Text(stringResource(Res.string.pinrec_code_hint)) },
                    singleLine = true,
                    textStyle = BhodlTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().testTag(ForgotPinTags.CODE),
                )
                PinField(pin, { pin = it; error = null }, Res.string.pinrec_new_pin, ForgotPinTags.NEW_PIN)
                PinField(confirm, { confirm = it; error = null }, Res.string.pinrec_confirm_pin, ForgotPinTags.CONFIRM)
                Button(
                    onClick = {
                        if (working) return@Button
                        PinAttemptTracker.lockoutMessage(PinScopes.RECOVERY)?.let {
                            error = it
                            return@Button
                        }
                        pinPairProblem(PinValidator.validatePair(pin, confirm))?.let {
                            error = it
                            return@Button
                        }
                        working = true
                        error = null
                        val (c, p) = code to pin
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) { recover(dataDir.absolutePath, c, p) }
                            }.fold(
                                onSuccess = { handle ->
                                    PinAttemptTracker.recordSuccess(PinScopes.RECOVERY)
                                    working = false
                                    onRecovered(handle)
                                },
                                onFailure = { e ->
                                    working = false
                                    error = recoveryFailureMessage(e)
                                },
                            )
                        }
                    },
                    enabled = code.isNotBlank() && pin.isNotEmpty() && confirm.isNotEmpty() && !working,
                    modifier = Modifier.fillMaxWidth().testTag(ForgotPinTags.RESET),
                ) {
                    Text(
                        if (working) stringResource(Res.string.pinrec_checking)
                        else stringResource(Res.string.pinrec_reset),
                    )
                }
            }
            ForgotStage.NO_CODE -> {
                Text(
                    text = stringResource(Res.string.pinrec_no_code_body),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textSecondary,
                    modifier = Modifier.testTag(ForgotPinTags.NO_CODE_BODY),
                )
                OutlinedTextField(
                    value = typedConfirm,
                    onValueChange = { typedConfirm = it; error = null },
                    label = { Text(stringResource(Res.string.onb_wipe_field, LocalWipe.CONFIRM_PHRASE)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(ForgotPinTags.WIPE_FIELD),
                )
                Button(
                    onClick = {
                        if (working || !LocalWipe.isConfirmed(typedConfirm)) return@Button
                        working = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { wipe(dataDir) }
                            working = false
                            if (result.aborted || result.failed.isNotEmpty()) {
                                error = LocalWipe.describe(result)
                            } else {
                                onWiped()
                            }
                        }
                    },
                    enabled = LocalWipe.isConfirmed(typedConfirm) && !working,
                    modifier = Modifier.fillMaxWidth().testTag(ForgotPinTags.WIPE),
                ) {
                    Text(
                        if (working) stringResource(Res.string.onb_wipe_erasing)
                        else stringResource(Res.string.onb_wipe_confirm),
                    )
                }
            }
        }
        error?.let {
            Text(
                text = it,
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(ForgotPinTags.ERROR),
            )
        }
        TextButton(
            onClick = {
                error = null
                if (stage == ForgotStage.CHOOSE) onBack() else stage = ForgotStage.CHOOSE
            },
            enabled = !working,
            modifier = Modifier.testTag(ForgotPinTags.BACK),
        ) { Text(stringResource(Res.string.action_back)) }
    }
}

@Composable
private fun PinField(
    value: String,
    onChange: (String) -> Unit,
    label: org.jetbrains.compose.resources.StringResource,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> !c.isWhitespace() }) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * Create (or replace) the recovery code and walk the user through
 * saving it: the code is shown once, then one random group must be
 * typed back before the panel can be closed with "I saved it".
 */
@Composable
internal fun RecoveryCodePanel(
    hasCode: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    createCode: () -> String = { checkNotNull(RegistryStore.current()) { "registry not open" }.createRecoveryCode() },
    pickGroup: () -> Int = { (1..RecoveryCodes.GROUPS).random() },
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var code: String? by remember { mutableStateOf(null) }
    val group = remember { pickGroup() }
    var typed by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error: String? by remember { mutableStateOf(null) }

    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.rc_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(RecoveryCodeTags.ROOT)) {
                Text(stringResource(Res.string.rc_intro), style = BhodlTheme.typography.bodyMedium)
                val shown = code
                if (shown == null) {
                    if (hasCode) {
                        Text(
                            stringResource(Res.string.rc_regenerate_warning),
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.financialPending,
                        )
                    }
                } else {
                    SelectionContainer {
                        Text(
                            text = shown,
                            style = BhodlTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = BhodlTheme.colors.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(BhodlTheme.colors.rowZebra)
                                .padding(12.dp)
                                .testTag(RecoveryCodeTags.CODE),
                        )
                    }
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(shown)); copied = true },
                        modifier = Modifier.testTag(RecoveryCodeTags.COPY),
                    ) {
                        Text(stringResource(if (copied) Res.string.common_copied else Res.string.common_copy))
                    }
                    Text(
                        stringResource(Res.string.rc_lose_both),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    Text(stringResource(Res.string.rc_confirm_group, group), style = BhodlTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text(stringResource(Res.string.rc_group_label, group)) },
                        singleLine = true,
                        textStyle = BhodlTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth().testTag(RecoveryCodeTags.GROUP),
                    )
                }
                error?.let {
                    Text(it, color = BhodlTheme.colors.error, modifier = Modifier.testTag(RecoveryCodeTags.ERROR))
                }
            }
        },
        confirmButton = {
            val shown = code
            if (shown == null) {
                Button(
                    onClick = {
                        if (working) return@Button
                        working = true
                        error = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { createCode() } }
                                .onSuccess { code = it }
                                .onFailure { error = localizedText(Res.string.rc_failed, it.userMessage()) }
                            working = false
                        }
                    },
                    enabled = !working,
                    modifier = Modifier.testTag(RecoveryCodeTags.CREATE),
                ) {
                    Text(stringResource(if (hasCode) Res.string.rc_regenerate else Res.string.rc_create))
                }
            } else {
                Button(
                    onClick = onSaved,
                    enabled = RecoveryCodes.groupMatches(shown, group, typed),
                    modifier = Modifier.testTag(RecoveryCodeTags.DONE),
                ) { Text(stringResource(Res.string.rc_done)) }
            }
        },
        dismissButton = if (code == null) {
            { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } }
        } else {
            null
        },
    )
}

/**
 * Change the device PIN: current PIN + new PIN pair. [changePin]
 * returns `false` for a wrong current PIN (production:
 * `RegistryHandle.changeDevicePin`, a 32-byte re-wrap).
 */
@Composable
internal fun ChangePinPanel(
    onDismiss: () -> Unit,
    onChanged: (message: String) -> Unit,
    changePin: (old: String, new: String) -> Boolean = { old, new ->
        checkNotNull(RegistryStore.current()) { "registry not open" }.changeDevicePin(old, new)
    },
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error: String? by remember { mutableStateOf(null) }

    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.cp_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag(ChangePinTags.ROOT)) {
                PinField(current, { current = it; error = null }, Res.string.cp_current, ChangePinTags.CURRENT)
                PinField(pin, { pin = it; error = null }, Res.string.pinrec_new_pin, ChangePinTags.NEW_PIN)
                PinField(confirm, { confirm = it; error = null }, Res.string.pinrec_confirm_pin, ChangePinTags.CONFIRM)
                error?.let {
                    Text(it, color = BhodlTheme.colors.error, modifier = Modifier.testTag(ChangePinTags.ERROR))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (working) return@Button
                    PinAttemptTracker.lockoutMessage(PinScopes.DEVICE)?.let {
                        error = it
                        return@Button
                    }
                    pinPairProblem(PinValidator.validatePair(pin, confirm))?.let {
                        error = it
                        return@Button
                    }
                    working = true
                    error = null
                    val (o, n) = current to pin
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { changePin(o, n) } }
                            .onSuccess { changed ->
                                if (changed) {
                                    PinAttemptTracker.recordSuccess(PinScopes.DEVICE)
                                    onChanged(localizedText(Res.string.cp_done))
                                } else {
                                    error = wrongDevicePinMessage()
                                }
                            }
                            .onFailure { error = localizedText(Res.string.cp_failed, it.userMessage()) }
                        working = false
                    }
                },
                enabled = current.isNotEmpty() && pin.isNotEmpty() && confirm.isNotEmpty() && !working,
                modifier = Modifier.testTag(ChangePinTags.SAVE),
            ) { Text(stringResource(Res.string.cp_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(Res.string.common_close)) }
        },
    )
}

public object ForgotPinTags {
    public const val ROOT: String = "forgot-pin.root"
    public const val HAVE_CODE: String = "forgot-pin.have-code"
    public const val NO_CODE: String = "forgot-pin.no-code"
    public const val CODE: String = "forgot-pin.code"
    public const val NEW_PIN: String = "forgot-pin.new-pin"
    public const val CONFIRM: String = "forgot-pin.confirm"
    public const val RESET: String = "forgot-pin.reset"
    public const val NO_CODE_BODY: String = "forgot-pin.no-code-body"
    public const val WIPE_FIELD: String = "forgot-pin.wipe-field"
    public const val WIPE: String = "forgot-pin.wipe"
    public const val ERROR: String = "forgot-pin.error"
    public const val BACK: String = "forgot-pin.back"
}

public object RecoveryCodeTags {
    public const val ROOT: String = "recovery-code.root"
    public const val CODE: String = "recovery-code.code"
    public const val COPY: String = "recovery-code.copy"
    public const val GROUP: String = "recovery-code.group"
    public const val CREATE: String = "recovery-code.create"
    public const val DONE: String = "recovery-code.done"
    public const val ERROR: String = "recovery-code.error"
}

public object ChangePinTags {
    public const val ROOT: String = "change-pin.root"
    public const val CURRENT: String = "change-pin.current"
    public const val NEW_PIN: String = "change-pin.new-pin"
    public const val CONFIRM: String = "change-pin.confirm"
    public const val SAVE: String = "change-pin.save"
    public const val ERROR: String = "change-pin.error"
}
