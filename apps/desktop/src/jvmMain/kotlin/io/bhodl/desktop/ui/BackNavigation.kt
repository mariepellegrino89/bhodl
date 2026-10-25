package io.bhodl.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState

/**
 * The rail's always-present Back button (redesign 2026-10).
 *
 * Back goes, in order: out of the innermost in-screen page that
 * registered with [BackHandlerEffect] (e.g. a portfolio item); then to
 * the previous navigation entry; then to the rail section visited
 * before, from [tabHistory] (tracker sections replace each other, so
 * the navigation stack alone cannot go back to them).
 */
public object BackNavigation {

    private class Handler(var onBack: () -> Unit)

    private val handlers = mutableStateListOf<Handler>()
    private val history = mutableStateListOf<Tab>()

    private const val MAX_HISTORY = 30

    /** An in-screen page is open: Back closes it first. */
    public val hasInScreenPage: Boolean get() = handlers.isNotEmpty()

    /** Rail sections visited before the current one, oldest first. */
    public val tabHistory: List<Tab> get() = history

    /** Closes the innermost in-screen page; false when none is open. */
    public fun closeInScreenPage(): Boolean {
        val h = handlers.lastOrNull() ?: return false
        h.onBack()
        return true
    }

    /** Remember [tab] as the section to come back to. */
    public fun recordTab(tab: Tab) {
        if (history.lastOrNull() == tab) return
        history += tab
        if (history.size > MAX_HISTORY) history.removeAt(0)
    }

    /** The section to go back to, removed from the history; null when none. */
    public fun popTab(): Tab? = history.removeLastOrNull()

    public fun reset() {
        handlers.clear()
        history.clear()
    }

    @Composable
    internal fun Register(enabled: Boolean, onBack: () -> Unit) {
        val current = rememberUpdatedState(onBack)
        DisposableEffect(enabled) {
            if (!enabled) return@DisposableEffect onDispose {}
            val h = Handler { current.value() }
            handlers += h
            onDispose { handlers.remove(h) }
        }
    }
}

/** While [enabled], the rail's Back button calls [onBack] (closes this in-screen page). */
@Composable
public fun BackHandlerEffect(enabled: Boolean, onBack: () -> Unit) {
    BackNavigation.Register(enabled, onBack)
}
