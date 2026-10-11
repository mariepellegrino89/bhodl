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
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.security_action_settings
import io.bhodl.shared.resources.security_action_verify
import io.bhodl.shared.resources.security_action_wallet_settings
import io.bhodl.shared.resources.security_autolock_value
import io.bhodl.shared.resources.security_backup_no_wallet
import io.bhodl.shared.resources.security_backup_not_verified
import io.bhodl.shared.resources.security_backup_verified
import io.bhodl.shared.resources.security_exported_on
import io.bhodl.shared.resources.security_hub_intro
import io.bhodl.shared.resources.security_hub_open
import io.bhodl.shared.resources.security_hub_score
import io.bhodl.shared.resources.security_hub_title
import io.bhodl.shared.resources.security_network_clearnet
import io.bhodl.shared.resources.security_network_tor
import io.bhodl.shared.resources.security_never_exported
import io.bhodl.shared.resources.security_pin_device_missing
import io.bhodl.shared.resources.security_pin_device_set
import io.bhodl.shared.resources.security_reminder_backup
import io.bhodl.shared.resources.security_reminder_later
import io.bhodl.shared.resources.security_row_autolock
import io.bhodl.shared.resources.security_row_backup
import io.bhodl.shared.resources.security_row_encrypted_backup
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
 * non-sensitive dates persisted in `settings.json`
 * ([SettingsStore.backupVerifiedAt], `backupExportedAt`,
 * `labelsExportedAt`). No secret is read to render this screen.
 */

/** Check result. [INFO] rows are facts, not counted in the score. */
public enum class SecurityStatus { OK, ATTENTION, INFO }

public enum class SecurityCheck { BACKUP, ENCRYPTED_BACKUP, PIN, AUTO_LOCK, NETWORK, WALLETS, LABELS }

/** Inputs of [securityChecks]; every field is a plain, already-known fact. */
public data class SecurityFacts(
    /** Loaded signing wallet, or null (tracker-only session). */
    val activeWalletId: String?,
    val backupVerifiedAtUnix: Long?,
    val backupExportedAtUnix: Long?,
    val labelsExportedAtUnix: Long?,
    /** The multi-wallet registry (device PIN) exists on disk. */
    val devicePinSet: Boolean,
    val torEnabled: Boolean,
    val network: Network,
    val signingWallets: Int,
    val watchWallets: Int,
)

public data class SecurityRow(val check: SecurityCheck, val status: SecurityStatus)

/** Pure status per row, in display order. */
public fun securityChecks(f: SecurityFacts): List<SecurityRow> = listOf(
    SecurityRow(
        SecurityCheck.BACKUP,
        when {
            f.activeWalletId == null -> SecurityStatus.INFO
            f.backupVerifiedAtUnix != null -> SecurityStatus.OK
            else -> SecurityStatus.ATTENTION
        },
    ),
    SecurityRow(
        SecurityCheck.ENCRYPTED_BACKUP,
        // Optional extra: the phrase is the primary backup.
        if (f.activeWalletId != null && f.backupExportedAtUnix != null) SecurityStatus.OK else SecurityStatus.INFO,
    ),
    SecurityRow(SecurityCheck.PIN, if (f.devicePinSet) SecurityStatus.OK else SecurityStatus.INFO),
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

/**
 * Spaced, dismissible reminders (BDG security guidance): shown only
 * when the loaded signing wallet's phrase is unverified, NEVER inside a
 * send flow, and after "Remind me later" snoozed for 1, 3, 7, then 30
 * days. Pure so the timing rules are pinned by tests.
 */
public object SecurityReminderPolicy {

    public val SNOOZE_DAYS: List<Long> = listOf(1L, 3L, 7L, 30L)

    /** Snooze length for the next dismissal, given the dismissals so far. */
    public fun snoozeSeconds(dismissCount: Int): Long =
        SNOOZE_DAYS[dismissCount.coerceIn(0, SNOOZE_DAYS.lastIndex)] * 86_400L

    public fun shouldShow(
        activeWalletId: String?,
        backupVerified: Boolean,
        inSendFlow: Boolean,
        nowUnix: Long,
        snoozedUntilUnix: Long,
    ): Boolean = activeWalletId != null && !backupVerified && !inSendFlow && nowUnix >= snoozedUntilUnix
}

/** Snapshot of [SecurityFacts] from the live stores (composable: subscribes). */
@Composable
internal fun rememberSecurityFacts(dataDir: java.io.File = WalletPaths.defaultDataDir()): SecurityFacts {
    val verified by SettingsStore.backupVerifiedAt
    val exported by SettingsStore.backupExportedAt
    val labels by SettingsStore.labelsExportedAt
    val tor by SettingsStore.torEnabled
    val wallets by TrackerCache.wallets
    val walletId = WalletStore.activeWalletId()
    val devicePin = androidx.compose.runtime.remember(dataDir) {
        runCatching { WalletPaths.from(dataDir).registryExists() }.getOrDefault(false)
    }
    return SecurityFacts(
        activeWalletId = walletId,
        backupVerifiedAtUnix = walletId?.let { verified[it] },
        backupExportedAtUnix = walletId?.let { exported[it] },
        labelsExportedAtUnix = walletId?.let { labels[it] },
        devicePinSet = devicePin,
        torEnabled = tor,
        network = WalletStore.network(),
        signingWallets = wallets.count { it.kind == WalletKindFfi.SIGNING }
            .coerceAtLeast(if (walletId != null) 1 else 0),
        watchWallets = wallets.count { it.kind != WalletKindFfi.SIGNING },
    )
}

@Composable
public fun SecurityHubScreen(
    onBack: () -> Unit,
    onVerifyBackup: () -> Unit,
    onOpenWalletSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    facts: SecurityFacts = rememberSecurityFacts(),
) {
    val rows = securityChecks(facts)
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
                        row.check, facts, onVerifyBackup, onOpenWalletSettings, onOpenSettings,
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
        }
    }
}

private data class RowText(val title: String, val value: String, val action: Pair<String, () -> Unit>?)

@Composable
private fun rowContent(
    check: SecurityCheck,
    f: SecurityFacts,
    onVerifyBackup: () -> Unit,
    onOpenWalletSettings: () -> Unit,
    onOpenSettings: () -> Unit,
): RowText {
    val walletSettings = stringResource(Res.string.security_action_wallet_settings) to onOpenWalletSettings
    val hasWallet = f.activeWalletId != null
    return when (check) {
        SecurityCheck.BACKUP -> RowText(
            stringResource(Res.string.security_row_backup),
            when {
                !hasWallet -> stringResource(Res.string.security_backup_no_wallet)
                f.backupVerifiedAtUnix != null ->
                    stringResource(Res.string.security_backup_verified, formatSecurityDate(f.backupVerifiedAtUnix))
                else -> stringResource(Res.string.security_backup_not_verified)
            },
            if (hasWallet) stringResource(Res.string.security_action_verify) to onVerifyBackup else null,
        )
        SecurityCheck.ENCRYPTED_BACKUP -> RowText(
            stringResource(Res.string.security_row_encrypted_backup),
            exportedText(hasWallet, f.backupExportedAtUnix),
            if (hasWallet) walletSettings else null,
        )
        SecurityCheck.PIN -> RowText(
            stringResource(Res.string.security_row_pin),
            stringResource(
                if (f.devicePinSet) Res.string.security_pin_device_set else Res.string.security_pin_device_missing,
            ),
            null,
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
            stringResource(Res.string.security_wallets_value, f.signingWallets, f.watchWallets),
            null,
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

/**
 * The spaced backup reminder. Hosts pass [inSendFlow] = true on every
 * send surface so a reminder never interrupts a payment.
 */
@Composable
internal fun SecurityReminderBanner(
    inSendFlow: Boolean,
    onOpenHub: () -> Unit,
    nowUnix: Long = System.currentTimeMillis() / 1000L,
) {
    val verified by SettingsStore.backupVerifiedAt
    val snoozedUntilByWallet by SettingsStore.securityReminderSnoozedUntil
    val dismissCountByWallet by SettingsStore.securityReminderDismissCount
    val walletId = WalletStore.activeWalletId()
    val show = SecurityReminderPolicy.shouldShow(
        activeWalletId = walletId,
        backupVerified = walletId != null && verified[walletId] != null,
        inSendFlow = inSendFlow,
        nowUnix = nowUnix,
        snoozedUntilUnix = walletId?.let { snoozedUntilByWallet[it] } ?: 0L,
    )
    if (!show || walletId == null) return
    val message = stringResource(Res.string.security_reminder_backup)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BhodlTheme.colors.financialPending.copy(alpha = 0.10f))
            .border(1.dp, BhodlTheme.colors.financialPending.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = message }
            .testTag(SecurityHubTags.REMINDER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Shield, contentDescription = null, tint = BhodlTheme.colors.financialPending, modifier = Modifier.size(18.dp))
        Text(
            text = message,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onOpenHub, modifier = Modifier.testTag(SecurityHubTags.REMINDER_OPEN)) {
            Text(stringResource(Res.string.security_hub_open))
        }
        TextButton(
            onClick = {
                SettingsStore.snoozeSecurityReminder(
                    walletId,
                    nowUnix + SecurityReminderPolicy.snoozeSeconds(dismissCountByWallet[walletId] ?: 0),
                )
            },
            modifier = Modifier.testTag(SecurityHubTags.REMINDER_LATER),
        ) { Text(stringResource(Res.string.security_reminder_later)) }
    }
}

public object SecurityHubTags {
    public const val ROOT: String = "security-hub.root"
    public const val SCORE: String = "security-hub.score"
    public const val REMINDER: String = "security-hub.reminder"
    public const val REMINDER_OPEN: String = "security-hub.reminder.open"
    public const val REMINDER_LATER: String = "security-hub.reminder.later"
    public const val SETTINGS_ENTRY: String = "security-hub.settings-entry"
    public fun row(c: SecurityCheck): String = "security-hub.row.${c.name.lowercase()}"
    public fun value(c: SecurityCheck): String = "security-hub.value.${c.name.lowercase()}"
    public fun status(c: SecurityCheck): String = "security-hub.status.${c.name.lowercase()}"
    public fun action(c: SecurityCheck): String = "security-hub.action.${c.name.lowercase()}"
}
