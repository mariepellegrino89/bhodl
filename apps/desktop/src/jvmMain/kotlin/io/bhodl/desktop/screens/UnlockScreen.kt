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
import androidx.compose.foundation.text.KeyboardOptions
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
import io.bhodl.desktop.ui.BhodlBackArrow
import io.bhodl.desktop.ui.BhodlBackArrowTags
import io.bhodl.desktop.wallet.NetworkPersistence
import io.bhodl.desktop.wallet.UniffiWalletFactory
import io.bhodl.desktop.wallet.WalletFactory
import io.bhodl.desktop.wallet.WalletPort
import io.bhodl.desktop.ui.userMessage
import io.bhodl.desktop.wizard.PinAttemptTracker
import io.bhodl.desktop.wizard.PinErrors
import io.bhodl.desktop.wizard.PinFailureKind
import io.bhodl.desktop.wizard.PinScopes
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.localizedText
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_pin_err_unlock_failed
import io.bhodl.shared.resources.onb_pin_label
import io.bhodl.shared.resources.onb_pin_lockout
import io.bhodl.shared.resources.onb_pin_wrong
import io.bhodl.shared.resources.onb_pin_wrong_wait
import io.bhodl.shared.resources.onb_unlock
import io.bhodl.shared.resources.onb_unlock_body
import io.bhodl.shared.resources.onb_unlock_title
import io.bhodl.shared.resources.onb_unlock_wait
import io.bhodl.shared.resources.onb_unlocking
import org.jetbrains.compose.resources.stringResource

/**
 * Unlock screen for returning users.
 *
 * The salt file at `~/.bhodl/wallet.salt` plus the user-typed PIN feed
 * Argon2id; the resulting key opens the SQLCipher database. BDK then
 * reconstructs the wallet from the descriptors persisted inside the
 * encrypted DB — the mnemonic is paper-only and is never required on
 * subsequent logins (see `WalletHandle.unlockPersistent`).
 *
 * Wrong PINs surface as `WalletException.Storage` and are reported as
 * a generic "Wrong PIN". Distinguishing wrong-PIN from disk corruption
 * is a follow-up (SQLCipher reports both as the same open failure).
 */
@Composable
public fun UnlockScreen(
    onUnlocked: () -> Unit,
    onBack: () -> Unit,
    factory: WalletFactory = UniffiWalletFactory(),
    dbDirOverride: String? = null,
    /**
     * UUID of the wallet to unlock, as carried by `Route.Unlock`
     * when the user taps a row in the Welcome picker. `null` for
     * legacy boot paths (registry absent, single-wallet install)
     * where `WalletPaths.activeWalletDir()` provides the fallback.
     */
    walletId: String? = null,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var lastError: String? by remember { mutableStateOf(null) }
    // UX review 2026-10 (B10) — the PIN lockout is now visible in the
    // controls, not just in a grey text line: the Unlock button stays
    // disabled for the whole cooldown and the message counts down live.
    // ONE scope key for the lockout check, the failure record and the
    // success reset (audit 2026-10, M3), resolved once per wallet.
    val scopeKey = remember(walletId) {
        PinScopes.wallet(walletId ?: resolveUnlockDir(null).name)
    }
    var cooldownSeconds by remember {
        mutableStateOf(PinAttemptTracker.cooldownRemainingSeconds(scopeKey))
    }
    // `true` when the running cooldown was started by a wrong PIN typed
    // on this screen ("Wrong PIN. Wait…"), `false` when it was inherited
    // (e.g. persisted from a previous run: "Too many wrong PINs…").
    var cooldownFromThisAttempt by remember { mutableStateOf(false) }
    LaunchedEffect(cooldownSeconds > 0L) {
        while (cooldownSeconds > 0L) {
            delay(COOLDOWN_TICK_MS)
            cooldownSeconds = PinAttemptTracker.cooldownRemainingSeconds(scopeKey)
        }
        cooldownFromThisAttempt = false
    }
    // Auto-focus the PIN field on screen entry so the user can start
    // typing immediately without clicking the input first. Requested
    // during the 2026-05-11 smoke test (finding #12).
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
        // UX review 2026-10 (B10) — the top-left arrow is the ONLY back
        // affordance (the duplicate bottom "Back" button is gone).
        BhodlBackArrow(
            onBack = { if (!working) onBack() },
            modifier = Modifier.align(Alignment.TopStart),
        )
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
                text = stringResource(Res.string.onb_unlock_title),
                style = BhodlTheme.typography.titleMedium,
                color = BhodlTheme.colors.textPrimary,
            )
            Text(
                text = stringResource(Res.string.onb_unlock_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )

            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter { c -> !c.isWhitespace() } },
                label = { Text(stringResource(Res.string.onb_pin_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(pinFocus)
                    .testTag(UnlockTags.PIN),
            )

            val errorLine: String? = when {
                cooldownSeconds > 0L && cooldownFromThisAttempt ->
                    stringResource(Res.string.onb_pin_wrong_wait, cooldownSeconds)
                cooldownSeconds > 0L ->
                    stringResource(Res.string.onb_pin_lockout, cooldownSeconds)
                else -> lastError
            }
            if (errorLine != null) {
                Text(
                    text = errorLine,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(UnlockTags.ERROR),
                )
            }

            Button(
                onClick = {
                    if (working || pin.isBlank()) return@Button
                    // Security audit 2026-10 (M3) — [scopeKey] (resolved
                    // once above) is used for the lockout check, the
                    // failure record AND the success reset. Previously a
                    // failure with walletId == null was recorded under
                    // "unknown" while success cleared activeDir.name, so
                    // the lockout never applied to legacy unlocks.
                    val resolvedDir = resolveUnlockDir(walletId)
                    // T1-9 — refuse the unlock attempt if a PIN lockout
                    // is active for this wallet (defence in depth: the
                    // button is already disabled during a cooldown).
                    val remaining = PinAttemptTracker.cooldownRemainingSeconds(scopeKey)
                    if (remaining > 0L) {
                        cooldownSeconds = remaining
                        return@Button
                    }
                    working = true
                    lastError = null
                    val attempted = pin
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val activeDir = resolvedDir
                                if (walletId != null) {
                                    require(activeDir.isDirectory) {
                                        "wallets/$walletId does not exist"
                                    }
                                }
                                val resolvedNetwork = resolveWalletNetwork(
                                    activeDir = activeDir,
                                    walletId = walletId ?: activeDir.name,
                                )
                                val port = openExistingWallet(
                                    factory = factory,
                                    pin = attempted,
                                    dbDirOverride = dbDirOverride ?: activeDir.absolutePath,
                                    network = resolvedNetwork,
                                )
                                // Phase 3.2 recovery — if this wallet
                                // exists on disk but is not in the
                                // registry (orphan from pre-fix wizard
                                // runs, or fresh install pre-registry),
                                // register it now. We have a live handle
                                // and can extract the public descriptor
                                // and xpub without the user re-typing
                                // anything.
                                runCatching {
                                    val registry = io.bhodl.desktop.wizard
                                        .RegistryStore.current()
                                    val effectiveId = walletId ?: activeDir.name
                                    if (registry != null && registry.getWallet(effectiveId) == null) {
                                        val descriptor = port.exportPublicDescriptor()
                                        registry.addWallet(
                                            io.bhodl.ffi.WalletRecordFfi(
                                                id = effectiveId,
                                                label = "My Wallet",
                                                kind = io.bhodl.ffi.WalletKindFfi.SIGNING,
                                                descriptorPub = descriptor.externalDescriptor,
                                                network = descriptor.network,
                                                createdAtUnix = System.currentTimeMillis() / 1000,
                                                pinsWarned = false,
                                            ),
                                        )
                                        io.bhodl.desktop.wizard.RegistryStore.bumpDataVersion()
                                    }
                                }
                                Triple(port, resolvedNetwork, walletId ?: activeDir.name)
                            }
                        }.fold(
                            onSuccess = { (port, network, resolvedId) ->
                                // T1-9 — successful unlock clears any
                                // accumulated failure count for this
                                // wallet so the next typo doesn't
                                // inherit a lockout from earlier.
                                PinAttemptTracker.recordSuccess(scopeKey)
                                WalletStore.set(port, network, walletId = resolvedId)
                                working = false
                                onUnlocked()
                            },
                            onFailure = { e ->
                                working = false
                                // M4 — only a (probable) wrong PIN counts
                                // against the lockout. Network mismatch /
                                // storage problems get their own message.
                                val kind = PinErrors.classify(e)
                                lastError = if (kind == PinFailureKind.WrongPin) {
                                    val deadline = PinAttemptTracker.recordFailure(scopeKey)
                                    if (deadline > 0L) {
                                        cooldownFromThisAttempt = true
                                        cooldownSeconds = PinAttemptTracker
                                            .cooldownRemainingSeconds(scopeKey)
                                    }
                                    localizedText(Res.string.onb_pin_wrong)
                                } else if (kind == PinFailureKind.Other) {
                                    e.userMessage(localizedText(Res.string.onb_pin_err_unlock_failed))
                                } else {
                                    PinErrors.messageFor(kind)
                                }
                            },
                        )
                    }
                },
                enabled = pin.isNotBlank() && !working && cooldownSeconds <= 0L,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UnlockTags.UNLOCK),
            ) {
                Text(
                    when {
                        working -> stringResource(Res.string.onb_unlocking)
                        cooldownSeconds > 0L -> stringResource(Res.string.onb_unlock_wait, cooldownSeconds)
                        else -> stringResource(Res.string.onb_unlock)
                    },
                )
            }
        }
    }
}

/**
 * Resolve the on-disk directory of the wallet to unlock. A `walletId`
 * (Welcome picker tap) maps to `wallets/<id>/`; otherwise fall back to
 * `activeWalletDir()` (legacy single-wallet installs).
 */
private fun resolveUnlockDir(walletId: String?): java.io.File {
    val view = WalletPaths.from(WalletPaths.defaultDataDir())
    return if (walletId != null) {
        java.io.File(view.walletsRoot, walletId)
    } else {
        view.activeWalletDir() ?: view.dir
    }
}

/**
 * Security audit 2026-10 (M4) — determine the wallet's network.
 *
 * The registry row (written at creation from the wallet's own public
 * descriptor) is authoritative when available; `wallet.network` is the
 * fallback. A missing / corrupt network file used to silently fall back
 * to SIGNET, so a mainnet wallet then failed BDK's `check_network` and
 * the failure was reported (and counted) as "Wrong PIN". When the
 * registry disagrees with the file we repair the file best-effort.
 */
private fun resolveWalletNetwork(activeDir: java.io.File, walletId: String): Network {
    val fromDisk = NetworkPersistence.readOrNull(activeDir)
    val fromRegistry = runCatching {
        io.bhodl.desktop.wizard.RegistryStore.current()
            ?.getWallet(walletId)
            ?.network
            ?.let { NetworkPersistence.parse(it.trim().lowercase()) }
    }.getOrNull()
    if (fromRegistry != null) {
        if (fromDisk != fromRegistry) {
            runCatching { NetworkPersistence.write(activeDir, fromRegistry) }
        }
        return fromRegistry
    }
    return fromDisk ?: Network.SIGNET
}

private fun openExistingWallet(
    factory: WalletFactory,
    pin: String,
    dbDirOverride: String?,
    network: Network,
): WalletPort {
    val dbDir = dbDirOverride
        ?: WalletPaths.from(WalletPaths.defaultDataDir()).dataDirAsString()
    return factory.unlockPersistent(
        dbDir = dbDir,
        network = network,
        pin = pin,
    )
}

/** Poll interval of the lockout countdown (UX review 2026-10, B10). */
private const val COOLDOWN_TICK_MS: Long = 250L

/** Test tags exposed by [`UnlockScreen`]. */
public object UnlockTags {
    public const val PIN: String = "unlock.pin"
    public const val UNLOCK: String = "unlock.unlock"
    /** The single back affordance: the top-left arrow (B10). */
    public const val BACK: String = BhodlBackArrowTags.ROOT
    /** Inline error line (wrong PIN, lockout, storage problem). */
    public const val ERROR: String = "unlock.error"
}
