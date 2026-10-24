package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.IdleLock
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.BhodlTopBar
import io.bhodl.desktop.wizard.WalletPaths
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.rc_saved
import io.bhodl.shared.resources.security_action_change
import io.bhodl.shared.resources.security_action_create
import io.bhodl.shared.resources.security_action_manage
import io.bhodl.shared.resources.security_action_wallets
import io.bhodl.shared.resources.security_recovery_missing
import io.bhodl.shared.resources.security_recovery_no_pin
import io.bhodl.shared.resources.security_recovery_set
import io.bhodl.shared.resources.security_row_recovery
import io.bhodl.shared.resources.security_action_settings
import io.bhodl.shared.resources.security_action_wallet_settings
import io.bhodl.shared.resources.security_autolock_value
import io.bhodl.shared.resources.security_backup_no_wallet
import io.bhodl.shared.resources.security_exported_on
import io.bhodl.shared.resources.security_hub_intro
import io.bhodl.shared.resources.security_hub_score
import io.bhodl.shared.resources.security_hub_title
import io.bhodl.shared.resources.security_network_clearnet
import io.bhodl.shared.resources.security_network_tor
import io.bhodl.shared.resources.security_never_exported
import io.bhodl.shared.resources.security_pin_device_missing
import io.bhodl.shared.resources.security_pin_device_set
import io.bhodl.shared.resources.security_row_autolock
import io.bhodl.shared.resources.security_row_labels
import io.bhodl.shared.resources.security_row_network
import io.bhodl.shared.resources.security_row_pin
import io.bhodl.shared.resources.security_row_wallets
import io.bhodl.shared.resources.security_status_attention
import io.bhodl.shared.resources.security_status_info
import io.bhodl.shared.resources.security_status_ok
import io.bhodl.shared.resources.security_wallets_value
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * UX review 2026-10 (item 14) — Security Hub (Bitkey Security Hub /
 * Blockstream Security tab): one screen with the security state of the
 * install and a direct action per row.
 *
 * Every row is derived by the pure [securityChecks] from facts the app
 * already has (wallet registry, Tor flag, idle-lock policy) plus the
 * non-sensitive labels-export date persisted in `settings.json`
 * ([SettingsStore.labelsExportedAt]). BHODL holds no keys, so there is
 * no seed backup to check; the one thing worth backing up is the PIN
 * recovery code, created and changed from here ([RecoveryCodePanel],
 * [ChangePinPanel]). No secret is read to render this screen.
 */

/** Check result. [INFO] rows are facts, not counted in the score. */
public enum class SecurityStatus { OK, ATTENTION, INFO }

public enum class SecurityCheck { PIN, RECOVERY, AUTO_LOCK, NETWORK, WALLETS, LABELS }

/** Inputs of [securityChecks]; every field is a plain, already-known fact. */
public data class SecurityFacts(
    /** Wallet open in the workspace, or null (tracker-only session). */
    val activeWalletId: String?,
    val labelsExportedAtUnix: Long?,
    /** The multi-wallet registry (device PIN) exists on disk. */
    val devicePinSet: Boolean,
    val torEnabled: Boolean,
    val network: Network,
    val watchWallets: Int,
    /** A PIN recovery code exists (`registry.recovery.json`). */
    val recoveryCodeSet: Boolean = false,
)

public data class SecurityRow(val check: SecurityCheck, val status: SecurityStatus)

/** Pure status per row, in display order. */
public fun securityChecks(f: SecurityFacts): List<SecurityRow> = listOf(
    SecurityRow(SecurityCheck.PIN, if (f.devicePinSet) SecurityStatus.OK else SecurityStatus.INFO),
    // Without a code, a forgotten PIN means losing the tracker data.
    SecurityRow(
        SecurityCheck.RECOVERY,
        when {
            !f.devicePinSet -> SecurityStatus.INFO
            f.recoveryCodeSet -> SecurityStatus.OK
            else -> SecurityStatus.ATTENTION
        },
    ),
    // Fixed policy (IdleLock), always armed while a wallet is open.
    SecurityRow(SecurityCheck.AUTO_LOCK, SecurityStatus.OK),
    SecurityRow(
        SecurityCheck.NETWORK,
        when {
            f.torEnabled -> SecurityStatus.OK
            f.network == Network.MAINNET -> SecurityStatus.ATTENTION
            else -> SecurityStatus.INFO
        },
    ),
    SecurityRow(SecurityCheck.WALLETS, SecurityStatus.INFO),
    SecurityRow(
        SecurityCheck.LABELS,
        if (f.activeWalletId != null && f.labelsExportedAtUnix != null) SecurityStatus.OK else SecurityStatus.INFO,
    ),
)

/** (ok, counted): the "N of M checks OK" readiness score; INFO rows don't count. */
public fun securityScore(rows: List<SecurityRow>): Pair<Int, Int> {
    val counted = rows.filter { it.status != SecurityStatus.INFO }
    return counted.count { it.status == SecurityStatus.OK } to counted.size
}

/** Snapshot of [SecurityFacts] from the live stores (composable: subscribes). */
@Composable
internal fun rememberSecurityFacts(dataDir: java.io.File = WalletPaths.defaultDataDir()): SecurityFacts {
    val labels by SettingsStore.labelsExportedAt
    val tor by SettingsStore.torEnabled
    val wallets by TrackerCache.wallets
    val walletId = WalletStore.activeWalletId()
    val devicePin = androidx.compose.runtime.remember(dataDir) {
        runCatching { WalletPaths.from(dataDir).registryExists() }.getOrDefault(false)
    }
    val recoveryCode = androidx.compose.runtime.remember(dataDir) {
        runCatching { io.bhodl.ffi.registryHasRecoveryCode(dataDir.absolutePath) }.getOrDefault(false)
    }
    return SecurityFacts(
        activeWalletId = walletId,
        labelsExportedAtUnix = walletId?.let { labels[it] },
        devicePinSet = devicePin,
        torEnabled = tor,
        network = WalletStore.network(),
        watchWallets = wallets.size,
        recoveryCodeSet = recoveryCode,
    )
}

@Composable
public fun SecurityHubScreen(
    onBack: () -> Unit,
    onOpenWallets: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Wallet › Wallet settings of the open wallet; `null` without one. */
    onOpenWalletSettings: (() -> Unit)? = null,
    facts: SecurityFacts = rememberSecurityFacts(),
    // PIN recovery seams (tests): default to the open registry.
    createRecoveryCode: (() -> String)? = null,
    changeDevicePin: ((old: String, new: String) -> Boolean)? = null,
) {
    // The code created in this session flips the row without a re-read.
    var createdNow by remember { mutableStateOf(false) }
    val shownFacts = if (createdNow) facts.copy(recoveryCodeSet = true) else facts
    var showRecovery by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var notice: String? by remember { mutableStateOf(null) }
    val rows = securityChecks(shownFacts)
    val (ok, counted) = securityScore(rows)
    Box(
        modifier = Modifier.fillMaxSize().background(BhodlTheme.colors.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .widthIn(max = 720.dp)
                .padding(horizontal = 32.dp, vertical = 32.dp)
                .testTag(SecurityHubTags.ROOT),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BhodlTopBar(title = stringResource(Res.string.security_hub_title), onBack = onBack)
            Text(
                text = stringResource(Res.string.security_hub_intro),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            Text(
                text = stringResource(Res.string.security_hub_score, ok, counted),
                style = BhodlTheme.typography.titleMedium,
                color = if (ok == counted) BhodlTheme.colors.financialPositive else BhodlTheme.colors.textPrimary,
                modifier = Modifier.testTag(SecurityHubTags.SCORE),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(8.dp)),
            ) {
                rows.forEachIndexed { i, row ->
                    val (title, value, action) = rowContent(
                        row.check, shownFacts, onOpenWallets, onOpenWalletSettings, onOpenSettings,
                        onRecovery = { notice = null; showRecovery = true },
                        onChangePin = { notice = null; showChangePin = true },
                    )
                    SecurityRowView(
                        check = row.check,
                        status = row.status,
                        title = title,
                        value = value,
                        action = action,
                        zebra = i % 2 == 1,
                    )
                }
            }
            notice?.let {
                Text(
                    text = it,
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.financialPositive,
                    modifier = Modifier.testTag(SecurityHubTags.NOTICE),
                )
            }
        }
    }
    if (showRecovery) {
        val saved = stringResource(Res.string.rc_saved)
        val onSaved = {
            showRecovery = false
            createdNow = true
            notice = saved
        }
        if (createRecoveryCode != null) {
            RecoveryCodePanel(shownFacts.recoveryCodeSet, { showRecovery = false }, onSaved, createRecoveryCode)
        } else {
            RecoveryCodePanel(shownFacts.recoveryCodeSet, { showRecovery = false }, onSaved)
        }
    }
    if (showChangePin) {
        val onChanged = { message: String ->
            showChangePin = false
            notice = message
        }
        if (changeDevicePin != null) {
            ChangePinPanel({ showChangePin = false }, onChanged, changeDevicePin)
        } else {
            ChangePinPanel({ showChangePin = false }, onChanged)
        }
    }
}

private data class RowText(val title: String, val value: String, val action: Pair<String, () -> Unit>?)

@Composable
private fun rowContent(
    check: SecurityCheck,
    f: SecurityFacts,
    onOpenWallets: () -> Unit,
    onOpenWalletSettings: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onRecovery: () -> Unit,
    onChangePin: () -> Unit,
): RowText {
    val walletSettings = onOpenWalletSettings?.let {
        stringResource(Res.string.security_action_wallet_settings) to it
    }
    val hasWallet = f.activeWalletId != null
    return when (check) {
        SecurityCheck.PIN -> RowText(
            stringResource(Res.string.security_row_pin),
            stringResource(
                if (f.devicePinSet) Res.string.security_pin_device_set else Res.string.security_pin_device_missing,
            ),
            if (f.devicePinSet) stringResource(Res.string.security_action_change) to onChangePin else null,
        )
        SecurityCheck.RECOVERY -> RowText(
            stringResource(Res.string.security_row_recovery),
            stringResource(
                when {
                    !f.devicePinSet -> Res.string.security_recovery_no_pin
                    f.recoveryCodeSet -> Res.string.security_recovery_set
                    else -> Res.string.security_recovery_missing
                },
            ),
            when {
                !f.devicePinSet -> null
                f.recoveryCodeSet -> stringResource(Res.string.security_action_manage) to onRecovery
                else -> stringResource(Res.string.security_action_create) to onRecovery
            },
        )
        SecurityCheck.AUTO_LOCK -> RowText(
            stringResource(Res.string.security_row_autolock),
            stringResource(
                Res.string.security_autolock_value,
                (IdleLock.IDLE_TIMEOUT_MS / 60_000L).toInt(),
                (IdleLock.MINIMIZED_GRACE_MS / 1_000L).toInt(),
            ),
            null,
        )
        SecurityCheck.NETWORK -> {
            val proxy by SettingsStore.torProxyUrl
            RowText(
                stringResource(Res.string.security_row_network),
                if (f.torEnabled) stringResource(Res.string.security_network_tor, proxy)
                else stringResource(Res.string.security_network_clearnet),
                stringResource(Res.string.security_action_settings) to onOpenSettings,
            )
        }
        SecurityCheck.WALLETS -> RowText(
            stringResource(Res.string.security_row_wallets),
            stringResource(Res.string.security_wallets_value, f.watchWallets),
            stringResource(Res.string.security_action_wallets) to onOpenWallets,
        )
        SecurityCheck.LABELS -> RowText(
            stringResource(Res.string.security_row_labels),
            exportedText(hasWallet, f.labelsExportedAtUnix),
            if (hasWallet) walletSettings else null,
        )
    }
}

@Composable
private fun exportedText(hasWallet: Boolean, atUnix: Long?): String = when {
    !hasWallet -> stringResource(Res.string.security_backup_no_wallet)
    atUnix != null -> stringResource(Res.string.security_exported_on, formatSecurityDate(atUnix))
    else -> stringResource(Res.string.security_never_exported)
}

@Composable
private fun SecurityRowView(
    check: SecurityCheck,
    status: SecurityStatus,
    title: String,
    value: String,
    action: Pair<String, () -> Unit>?,
    zebra: Boolean,
) {
    val (icon, tint, statusLabel) = statusVisual(status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (zebra) BhodlTheme.colors.rowZebra else Color.Transparent)
            .heightIn(min = maxOf(BhodlTheme.density.rowHeight, 48.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(SecurityHubTags.row(check)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Status is icon + text (never colour alone, WCAG 1.4.1).
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary)
            Text(
                text = value,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
                modifier = Modifier.testTag(SecurityHubTags.value(check)),
            )
        }
        Text(
            text = statusLabel,
            style = BhodlTheme.typography.labelMedium,
            color = tint,
            modifier = Modifier.testTag(SecurityHubTags.status(check)),
        )
        if (action != null) {
            TextButton(
                onClick = action.second,
                modifier = Modifier.testTag(SecurityHubTags.action(check)),
            ) { Text(action.first) }
        }
    }
}

@Composable
private fun statusVisual(status: SecurityStatus): Triple<androidx.compose.ui.graphics.vector.ImageVector, Color, String> =
    when (status) {
        SecurityStatus.OK -> Triple(
            Icons.Filled.CheckCircle, BhodlTheme.colors.financialPositive,
            stringResource(Res.string.security_status_ok),
        )
        SecurityStatus.ATTENTION -> Triple(
            Icons.Filled.Warning, BhodlTheme.colors.financialPending,
            stringResource(Res.string.security_status_attention),
        )
        SecurityStatus.INFO -> Triple(
            Icons.Filled.Info, BhodlTheme.colors.textTertiary,
            stringResource(Res.string.security_status_info),
        )
    }

internal fun formatSecurityDate(unix: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(unix))

public object SecurityHubTags {
    public const val ROOT: String = "security-hub.root"
    public const val SCORE: String = "security-hub.score"
    public const val SETTINGS_ENTRY: String = "security-hub.settings-entry"
    public const val NOTICE: String = "security-hub.notice"
    public fun row(c: SecurityCheck): String = "security-hub.row.${c.name.lowercase()}"
    public fun value(c: SecurityCheck): String = "security-hub.value.${c.name.lowercase()}"
    public fun status(c: SecurityCheck): String = "security-hub.status.${c.name.lowercase()}"
    public fun action(c: SecurityCheck): String = "security-hub.action.${c.name.lowercase()}"
}
