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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.AppScope
import io.bhodl.desktop.data.BackfillRunner
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.DevicePinSession
import io.bhodl.desktop.wizard.PinPairValidation
import io.bhodl.desktop.wizard.PinStrength
import io.bhodl.desktop.wizard.PinValidation
import io.bhodl.desktop.wizard.PinValidator
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.ffi.RegistryHandle
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_back
import io.bhodl.shared.resources.onb_device_pin
import io.bhodl.shared.resources.onb_device_pin_confirm
import io.bhodl.shared.resources.onb_device_pin_create
import io.bhodl.shared.resources.onb_device_pin_setup_body
import io.bhodl.shared.resources.onb_device_pin_setup_title
import io.bhodl.shared.resources.onb_device_pins_mismatch
import io.bhodl.shared.resources.onb_opening
import io.bhodl.shared.resources.onb_pin_empty
import io.bhodl.shared.resources.onb_pin_non_numeric
import io.bhodl.shared.resources.onb_pin_strength_moderate
import io.bhodl.shared.resources.onb_pin_too_long
import io.bhodl.shared.resources.onb_pin_too_short
import io.bhodl.shared.resources.onb_registry_open_failed
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * First-launch device-PIN creation.
 *
 * The device PIN is the only secret BHODL has: Argon2id turns it into
 * the SQLCipher key of the registry (wallet list + every tracker table)
 * and of each watch wallet's state file. There is no per-wallet PIN —
 * watch wallets hold public material only.
 *
 * On submit the PIN pair is validated ([PinValidator]: 6–32 digits,
 * matching confirmation), then `RegistryHandle.createOrLoad` creates the
 * registry off the main thread (Rust enforces the same new-PIN policy).
 * The handle is parked in [RegistryStore] and the idle-lock session
 * opened, exactly like [DevicePinUnlockScreen].
 */
@Composable
public fun DevicePinSetupScreen(
    onCreated: () -> Unit,
    onBack: () -> Unit,
    // Injection seam so tests can target a temp dir instead of ~/.bhodl.
    dataDir: java.io.File = WalletPaths.defaultDataDir(),
    // Test seam: the FFI call that creates the registry (Argon2id).
    createRegistry: (dataDir: String, pin: String) -> RegistryHandle =
        { dir, chosenPin -> RegistryHandle.createOrLoad(dataDir = dir, devicePin = chosenPin) },
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }

    val pinFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { pinFocus.requestFocus() } }

    val pinOptions = KeyboardOptions(
        keyboardType = KeyboardType.NumberPassword,
        imeAction = ImeAction.Next,
    )

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
                text = stringResource(Res.string.onb_device_pin_setup_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_device_pin_setup_body, PinValidator.MIN_LENGTH),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { c -> !c.isWhitespace() }; lastError = null },
                label = { Text(stringResource(Res.string.onb_device_pin)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = pinOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(pinFocus)
                    .testTag(DevicePinSetupTags.PIN),
            )
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it.filter { c -> !c.isWhitespace() }; lastError = null },
                label = { Text(stringResource(Res.string.onb_device_pin_confirm)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = pinOptions.copy(imeAction = ImeAction.Done),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DevicePinSetupTags.CONFIRM),
            )

            if (PinValidator.strength(pin) == PinStrength.Moderate) {
                Text(
                    text = stringResource(Res.string.onb_pin_strength_moderate, pin.length),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }

            lastError?.let {
                Text(
                    text = it,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(DevicePinSetupTags.ERROR),
                )
            }

            Button(
                onClick = {
                    if (working) return@Button
                    val problem = pinPairProblem(PinValidator.validatePair(pin, confirm))
                    if (problem != null) {
                        lastError = problem
                        return@Button
                    }
                    working = true
                    lastError = null
                    val chosen = pin
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                WalletPaths.restrictToOwner(dataDir.apply { mkdirs() })
                                createRegistry(dataDir.absolutePath, chosen)
                            }
                        }.fold(
                            onSuccess = { handle ->
                                RegistryStore.set(handle)
                                DevicePinSession.markUnlocked()
                                BackfillRunner.runIfNeeded(AppScope.io)
                                working = false
                                onCreated()
                            },
                            onFailure = { e ->
                                working = false
                                lastError = e.userMessage(localizedText(Res.string.onb_registry_open_failed))
                            },
                        )
                    }
                },
                enabled = pin.isNotEmpty() && confirm.isNotEmpty() && !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DevicePinSetupTags.CREATE),
            ) {
                Text(
                    if (working) stringResource(Res.string.onb_opening)
                    else stringResource(Res.string.onb_device_pin_create),
                )
            }
            TextButton(
                onClick = onBack,
                enabled = !working,
                modifier = Modifier.testTag(DevicePinSetupTags.BACK),
            ) {
                Text(stringResource(Res.string.action_back))
            }
        }
    }
}

/** Localized message for an invalid PIN pair, `null` when valid. */
internal fun pinPairProblem(result: PinPairValidation): String? = when (result) {
    PinPairValidation.Valid -> null
    PinPairValidation.Mismatch -> localizedText(Res.string.onb_device_pins_mismatch)
    is PinPairValidation.PinInvalid -> when (val r = result.reason) {
        PinValidation.Valid -> null
        PinValidation.Empty -> localizedText(Res.string.onb_pin_empty)
        is PinValidation.TooShort -> localizedText(Res.string.onb_pin_too_short, r.actual, r.minimum)
        is PinValidation.TooLong -> localizedText(Res.string.onb_pin_too_long, r.actual, r.maximum)
        PinValidation.NonNumeric -> localizedText(Res.string.onb_pin_non_numeric)
    }
}

public object DevicePinSetupTags {
    public const val PIN: String = "device-pin-setup.pin"
    public const val CONFIRM: String = "device-pin-setup.confirm"
    public const val CREATE: String = "device-pin-setup.create"
    public const val ERROR: String = "device-pin-setup.error"
    public const val BACK: String = "device-pin-setup.back"
}
