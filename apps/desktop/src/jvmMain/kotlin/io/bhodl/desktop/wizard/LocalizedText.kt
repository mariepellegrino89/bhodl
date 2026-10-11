package io.bhodl.desktop.wizard

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/**
 * UX review 2026-10 (rec 3) — resolve a localized string OUTSIDE
 * composition, in the app's current language (`Locale.getDefault()`,
 * set from Settings › Language in Main.kt).
 *
 * For the plain (non-composable) helpers that turn a domain outcome
 * into a user-facing message — PIN failure kinds, lockout countdowns,
 * fee warnings, wipe results — and are called from click handlers and
 * coroutine callbacks where `stringResource` is unavailable. Composables
 * keep using `stringResource`. The resource files are parsed once and
 * cached by Compose resources, so the blocking read is a map lookup
 * after the first call.
 */
internal fun localizedText(resource: StringResource, vararg args: Any): String =
    runBlocking { getString(resource, *args) }
