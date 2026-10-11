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
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.RegistryHandle
import io.bhodl.desktop.ui.userMessage
import io.bhodl.ffi.WalletException
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_device_pin
import io.bhodl.shared.resources.onb_device_pin_unlock_body
import io.bhodl.shared.resources.onb_device_pin_wrong
import io.bhodl.shared.resources.onb_device_pin_wrong_wait
import io.bhodl.shared.resources.onb_open_registry
import io.bhodl.shared.resources.onb_opening
import io.bhodl.shared.resources.onb_registry_open_failed
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 3.2 boot-time device-PIN gate.
 *
 * Shown on every fresh app launch when a post-3.0 registry already
 * exists on disk but [`RegistryStore`] has not been populated yet
 * (i.e. the previous process is gone, the SQLCipher key derived from
 * the device PIN is no longer in memory). User types the device PIN
 * once, the registry handle is opened on `Dispatchers.IO`, and
 * routing continues to the wallet picker / Unlock flow.
 *
 * The screen is intentionally minimal — one field, one button, no
 * "I forgot my PIN" recovery affordance (a wrong device PIN simply
 * fails to decrypt the registry; SQLCipher's PRAGMA key probe rejects
 * the wrong key cheaply and we surface that as
 * `WalletException.Storage` → "Wrong device PIN."). Recovery for a
 * truly forgotten device PIN is the BIP-39 seed phrase + the
 * encrypted .age backup, not a UI affordance.
 */
/** Scope key for [io.bhodl.desktop.wizard.PinAttemptTracker]
 *  when counting wrong device-PIN attempts (registry unlock). */
private const val DEVICE_PIN_SCOPE: String = io.bhodl.desktop.wizard.PinScopes.DEVICE

@Composable
public fun DevicePinUnlockScreen(
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devicePin by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }

    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { pinFocus.requestFocus() }
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
                text = stringResource(Res.string.onb_device_pin),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_device_pin_unlock_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = devicePin,
                onValueChange = { devicePin = it.filter { c -> !c.isWhitespace() } },
                label = { Text(stringResource(Res.string.onb_device_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(pinFocus)
                    .testTag(DevicePinUnlockTags.PIN),
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
                    if (working || devicePin.isBlank()) return@Button
                    // T1-9 — refuse if a device-PIN lockout is active.
                    io.bhodl.desktop.wizard.PinAttemptTracker
                        .lockoutMessage(DEVICE_PIN_SCOPE)?.let {
                            lastError = it
                            return@Button
                        }
                    working = true
                    lastError = null
                    val attempted = devicePin
                    val dataDir = WalletPaths.defaultDataDir().absolutePath
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                RegistryHandle.createOrLoad(
                                    dataDir = dataDir,
                                    devicePin = attempted,
                                )
                            }
                        }.fold(
                            onSuccess = { handle ->
                                // T1-9 — clear the device-PIN failure
                                // history on a successful open.
                                io.bhodl.desktop.wizard.PinAttemptTracker
                                    .recordSuccess(DEVICE_PIN_SCOPE)
                                RegistryStore.set(handle)
                                // Open the device-PIN session (idle
                                // clock). Audit 2026-10: the PIN itself
                                // is no longer parked — background jobs
                                // use the RegistryHandle's derived key.
                                // Cleared by RegistryStore.clear().
                                io.bhodl.desktop.wizard.DevicePinSession.markUnlocked()
                                // Phase 8.3.1 — kick the BTC-at-time
                                // backfill in the background. Returns
                                // immediately; the runner posts its
                                // state on a StateFlow so the UI does
                                // not freeze on first launch post-8.2.
                                // Idempotent: the backend skips rows
                                // already filled.
                                // Audit 2026-10 — app-lifetime scope:
                                // this screen's scope is cancelled by
                                // the navigation right below.
                                io.bhodl.desktop.data.BackfillRunner
                                    .runIfNeeded(io.bhodl.desktop.AppScope.io)
                                working = false
                                onUnlocked()
                            },
                            onFailure = { e ->
                                working = false
                                val kind = io.bhodl.desktop.wizard.PinErrors.classify(e)
                                lastError = when (kind) {
                                    io.bhodl.desktop.wizard.PinFailureKind.WrongPin -> {
                                        val deadline = io.bhodl.desktop.wizard
                                            .PinAttemptTracker.recordFailure(DEVICE_PIN_SCOPE)
                                        if (deadline > 0L) {
                                            val wait = io.bhodl.desktop.wizard
                                                .PinAttemptTracker
                                                .cooldownRemainingSeconds(DEVICE_PIN_SCOPE)
                                            localizedText(Res.string.onb_device_pin_wrong_wait, wait)
                                        } else {
                                            localizedText(Res.string.onb_device_pin_wrong)
                                        }
                                    }
                                    io.bhodl.desktop.wizard.PinFailureKind.Other ->
                                        e.userMessage(localizedText(Res.string.onb_registry_open_failed))
                                    else -> io.bhodl.desktop.wizard.PinErrors.messageFor(kind)
                                }
                            },
                        )
                    }
                },
                enabled = devicePin.isNotBlank() && !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DevicePinUnlockTags.UNLOCK),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_opening)
                    else stringResource(Res.string.onb_open_registry),
                )
            }
        }
    }
}

public object DevicePinUnlockTags {
    public const val PIN: String = "device-pin-unlock.pin"
    public const val UNLOCK: String = "device-pin-unlock.unlock"
}
