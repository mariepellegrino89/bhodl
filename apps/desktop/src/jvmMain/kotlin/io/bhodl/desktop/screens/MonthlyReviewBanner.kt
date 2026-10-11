package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.shouldShowMonthlyReviewBanner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.trk_review_action
import io.bhodl.shared.resources.trk_review_body
import io.bhodl.shared.resources.trk_review_dismiss
import io.bhodl.shared.resources.trk_review_title
import org.jetbrains.compose.resources.stringResource

/**
 * Phase 7.6 — Monthly review banner.
 *
 * Sits above the NetWorthHero on the Tracker home. Shows up only in
 * the first 3 days of a month, and only if the user hasn't already
 * dismissed it this month (via [SettingsStore.lastMonthlyReviewDismissedUnix]).
 *
 * Two actions:
 *  - **Review**: invokes [onReview] — the host navigates to the
 *    Tracking dashboard with `period = LAST_MONTH` preselected so
 *    the user sees the just-closed month at a glance.
 *  - **Dismiss**: writes "now" to the dismissed timestamp, hiding
 *    the banner until next month.
 *
 * This banner is the user-facing closing of the monthly loop (research
 * doc §3): an in-app nudge converts the app from "consultable"
 * to "habitual" without needing OS notification permissions.
 */
@Composable
internal fun MonthlyReviewBanner(
    nowUnix: Long = System.currentTimeMillis() / 1000L,
    onReview: () -> Unit,
) {
    val lastDismissed by SettingsStore.lastMonthlyReviewDismissedUnix
    if (!shouldShowMonthlyReviewBanner(nowUnix, lastDismissed)) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BhodlTheme.colors.accentNeon.copy(alpha = 0.10f))
            .border(
                1.dp,
                BhodlTheme.colors.accentNeon.copy(alpha = 0.30f),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag(MonthlyReviewBannerTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.trk_review_title),
                style = BhodlTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = BhodlTheme.colors.accentNeon,
            )
            Text(
                text = stringResource(Res.string.trk_review_body),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textSecondary,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(start = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.accentNeon)
                    .clickable(onClick = onReview)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag(MonthlyReviewBannerTags.REVIEW),
            ) {
                Text(
                    text = stringResource(Res.string.trk_review_action),
                    style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = BhodlTheme.colors.background,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BhodlTheme.colors.surfaceVariant)
                    .clickable(onClick = { SettingsStore.markMonthlyReviewDismissed(nowUnix) })
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag(MonthlyReviewBannerTags.DISMISS),
            ) {
                Text(
                    text = stringResource(Res.string.trk_review_dismiss),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
            }
        }
    }
}

internal object MonthlyReviewBannerTags {
    const val BANNER: String = "monthly-review.banner"
    const val REVIEW: String = "monthly-review.review"
    const val DISMISS: String = "monthly-review.dismiss"
}
