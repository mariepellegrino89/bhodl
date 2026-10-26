package io.bhodl.desktop.ui

import androidx.compose.runtime.Composable
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.common_uncategorised
import org.jetbrains.compose.resources.stringResource

/**
 * UX review 2026-10 (rec 3) — the internal bucket key for events with
 * no category. Aggregations group by this key; the UI never shows it
 * verbatim, it goes through [categoryDisplayName].
 */
internal const val UNCATEGORISED_KEY: String = "Uncategorised"

/** Whether [raw] names the "no category" bucket (no budget can be set on it). */
internal fun isUncategorised(raw: String?): Boolean {
    val t = raw?.trim().orEmpty()
    return t.isEmpty() || t == UNCATEGORISED_KEY || t == "Uncategorized"
}

/**
 * Display name of a tracker category: a classic category in the app's
 * language whichever language it was stored in, the user's own category
 * text as typed, or the localized "Uncategorised" for a missing / blank
 * one (and for the internal [UNCATEGORISED_KEY] bucket).
 */
@Composable
internal fun categoryDisplayName(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == UNCATEGORISED_KEY || trimmed == "Uncategorized") {
        return stringResource(Res.string.common_uncategorised)
    }
    return io.bhodl.desktop.screens.presetCategoryNamed(trimmed)?.let { stringResource(it.label) } ?: raw!!
}
