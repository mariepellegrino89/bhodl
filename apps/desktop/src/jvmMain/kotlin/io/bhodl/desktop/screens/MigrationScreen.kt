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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinErrors
import io.bhodl.desktop.wizard.PinFailureKind
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.MigrationOutcome
import io.bhodl.ffi.WalletException
import io.bhodl.ffi.runMigrationIfNeeded
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_device_pins_mismatch
import io.bhodl.shared.resources.onb_mig_backup_hint
import io.bhodl.shared.resources.onb_mig_body
import io.bhodl.shared.resources.onb_mig_confirm_device_pin
import io.bhodl.shared.resources.onb_mig_current_pin
import io.bhodl.shared.resources.onb_mig_failed
import io.bhodl.shared.resources.onb_mig_new_device_pin
import io.bhodl.shared.resources.onb_mig_title
import io.bhodl.shared.resources.onb_migrate
import io.bhodl.shared.resources.onb_migrating
import io.bhodl.shared.resources.onb_wallet_pin_wrong
import io.bhodl.shared.resources.onb_wallet_pin_wrong_wait
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3.0 migration screen — routed to from the boot guard when a
 * pre-3.0 single-wallet install is detected without a `registry.sqlite`.
 *
 * Collects two PINs:
 * - **Device PIN** (new) — protects the registry. Set explicitly per
 *   the security review trade-off A2 (
 *   `docs/phase-3-architecture.md` §"Accepted trade-offs").
 * - **Wallet PIN** (existing) — the user's current PIN. Used both
 *   to authenticate the migration (the wallet's old encryption key
 *   is derived from it) and as the wallet PIN of the migrated
 *   signing wallet entry.
 *
 * On submit, calls
 * `bhodl_uniffi.runMigrationIfNeeded(dataDir, devicePin, legacyPin)`
 * which executes the two-phase commit. The FFI function returns:
 * - `MigrationOutcome.NotNeeded` — race / already migrated; treat as success.
 * - `MigrationOutcome.Migrated` — migration ran; navigate to Unlock.
 * - `MigrationOutcome.RecoveredFromInterruption` — should not happen
 *   on this code path (boot guard handles recovery silently before
 *   routing here), but if it does, treat as success.
 *
 * On error (typically wrong legacy PIN — `WalletException.Storage`
 * because SQLCipher refused the key), shows the error inline and
 * leaves the user on the screen to retry.
 */
@Composable
public fun MigrationScreen(
    onMigrated: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devicePin by remember { mutableStateOf("") }
    var devicePinConfirm by remember { mutableStateOf("") }
    var legacyPin by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }

    val devicePinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { devicePinFocus.requestFocus() }
    }

    val pinMismatch = devicePinConfirm.isNotEmpty() && devicePin != devicePinConfirm
    val canSubmit =
        !working &&
            devicePin.length >= 6 &&
            devicePin == devicePinConfirm &&
            legacyPin.isNotBlank()

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
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "BHODL",
                style = BhodlTheme.typography.headlineMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_mig_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_mig_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.onb_mig_backup_hint),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = devicePin,
                onValueChange = { devicePin = it.filter { c -> !c.isWhitespace() } },
                label = { Text(stringResource(Res.string.onb_mig_new_device_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(devicePinFocus)
                    .testTag(MigrationTags.DEVICE_PIN),
            )
            OutlinedTextField(
                value = devicePinConfirm,
                onValueChange = { devicePinConfirm = it.filter { c -> !c.isWhitespace() } },
                label = { Text(stringResource(Res.string.onb_mig_confirm_device_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = pinMismatch,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MigrationTags.DEVICE_PIN_CONFIRM),
            )
            if (pinMismatch) {
                Text(
                    text = stringResource(Res.string.onb_device_pins_mismatch),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                )
            }
            OutlinedTextField(
                value = legacyPin,
                onValueChange = { legacyPin = it.filter { c -> !c.isWhitespace() } },
                label = { Text(stringResource(Res.string.onb_mig_current_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MigrationTags.LEGACY_PIN),
            )

            if (lastError != null) {
                Text(
                    text = lastError!!,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Center,
                )
            }

            Button(
                onClick = {
                    if (!canSubmit) return@Button
                    // Audit 2026-10 — the legacy wallet PIN check goes
                    // through the same lockout tracker as every other
                    // PIN prompt (it was an uncounted PIN oracle).
                    PinAttemptTracker.lockoutMessage(PinScopes.MIGRATION_LEGACY)?.let {
                        lastError = it
                        return@Button
                    }
                    working = true
                    lastError = null
                    scope.launch {
                        val dataDir = WalletPaths.defaultDataDir()
                        runCatching {
                            withContext(Dispatchers.IO) {
                                runMigrationIfNeeded(
                                    dataDir = dataDir.absolutePath,
                                    devicePin = devicePin,
                                    legacyPin = legacyPin,
                                )
                                // Phase 3.2 — populate RegistryStore in
                                // the same coroutine so the user is not
                                // prompted again for the device PIN
                                // they just typed. The migration left
                                // the registry on disk; re-opening with
                                // the same PIN derives the same key
                                // (Argon2id is deterministic per
                                // KdfParams + salt) and gives us a
                                // session-lifetime handle.
                                io.bhodl.ffi.RegistryHandle.createOrLoad(
                                    dataDir = dataDir.absolutePath,
                                    devicePin = devicePin,
                                )
                            }
                        }.fold(
                            onSuccess = { registryHandle ->
                                PinAttemptTracker.recordSuccess(PinScopes.MIGRATION_LEGACY)
                                io.bhodl.desktop.wizard.RegistryStore.set(registryHandle)
                                // Open the device-PIN session (idle
                                // clock); the PIN itself is not kept.
                                io.bhodl.desktop.wizard.DevicePinSession.markUnlocked()
                                // Phase 8.3.1 — kick the backfill on
                                // a background coroutine; idempotent
                                // and non-blocking so the post-
                                // migration nav happens immediately.
                                // Audit 2026-10 — app-lifetime scope (this
                                // screen's scope dies on navigation).
                                io.bhodl.desktop.data.BackfillRunner
                                    .runIfNeeded(io.bhodl.desktop.AppScope.io)
                                working = false
                                onMigrated()
                            },
                            onFailure = { e ->
                                working = false
                                val kind = PinErrors.classify(e)
                                lastError = when (kind) {
                                    PinFailureKind.WrongPin -> {
                                        val deadline = PinAttemptTracker
                                            .recordFailure(PinScopes.MIGRATION_LEGACY)
                                        if (deadline > 0L) {
                                            val wait = PinAttemptTracker
                                                .cooldownRemainingSeconds(PinScopes.MIGRATION_LEGACY)
                                            localizedText(Res.string.onb_wallet_pin_wrong_wait, wait)
                                        } else {
                                            localizedText(Res.string.onb_wallet_pin_wrong)
                                        }
                                    }
                                    PinFailureKind.Other -> e.userMessage(localizedText(Res.string.onb_mig_failed))
                                    else -> PinErrors.messageFor(kind)
                                }
                            },
                        )
                    }
                },
                enabled = canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(MigrationTags.SUBMIT),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_migrating)
                    else stringResource(Res.string.onb_migrate),
                )
            }
        }
    }
}

public object MigrationTags {
    public const val DEVICE_PIN: String = "migration.device_pin"
    public const val DEVICE_PIN_CONFIRM: String = "migration.device_pin_confirm"
    public const val LEGACY_PIN: String = "migration.legacy_pin"
    public const val SUBMIT: String = "migration.submit"
}
