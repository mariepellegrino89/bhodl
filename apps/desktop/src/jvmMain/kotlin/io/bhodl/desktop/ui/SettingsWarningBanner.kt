package io.bhodl.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.wview_dismiss
import org.jetbrains.compose.resources.stringResource

/**
 * Audit 2026-10 (M9) — app-wide banner shown when `settings.json` could
 * not be loaded (corrupt JSON, future version, unreadable). Reads
 * [SettingsStore.persistenceWarning]; renders nothing when it is `null`.
 * "Dismiss" clears the warning for this session.
 */
@Composable
public fun SettingsWarningBanner() {
    val settingsWarning by SettingsStore.persistenceWarning
    val msg = settingsWarning ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BhodlTheme.colors.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(SettingsWarningBannerTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = msg,
            style = BhodlTheme.typography.bodyMedium,
            color = BhodlTheme.colors.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { SettingsStore.setPersistenceWarning(null) },
            modifier = Modifier.testTag(SettingsWarningBannerTags.DISMISS),
        ) {
            Text(stringResource(Res.string.wview_dismiss))
        }
    }
}

/** Test tags for [SettingsWarningBanner]. */
public object SettingsWarningBannerTags {
    public const val BANNER: String = "settings-warning.banner"
    public const val DISMISS: String = "settings-warning.dismiss"
}
