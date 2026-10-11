package io.bhodl.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.bhodl.ui.theme.BhodlTheme

/**
 * UX review 2026-10 (item 17) — a minimal navigation abstraction in
 * the spirit of M3 `NavigationSuiteScaffold`: the shell describes its
 * destinations once ([NavDestination]) and [BhodlNavigationSuite]
 * renders them as a side rail or a bottom bar.
 *
 * Desktop always renders [NavigationLayout.Rail] (the window minimum,
 * `WindowSizing`, is above the compact breakpoint). The bottom bar is
 * here so a future Android / compact host only flips
 * [navigationLayoutFor]'s `allowBottomBar`; it is deliberately bare
 * (no badges, no overflow) until a mobile build exists. Destinations
 * marked [NavDestination.pinnedEnd] (Settings) sit at the bottom of the
 * rail and at the end of the bar.
 */
public enum class NavigationLayout { Rail, BottomBar }

/** Below this content width a host that allows it switches to a bottom bar (M3 "compact"). */
public const val COMPACT_NAV_MAX_WIDTH_DP: Int = 600

public fun navigationLayoutFor(widthDp: Int, allowBottomBar: Boolean): NavigationLayout =
    if (allowBottomBar && widthDp < COMPACT_NAV_MAX_WIDTH_DP) NavigationLayout.BottomBar else NavigationLayout.Rail

public data class NavDestination<T>(
    val key: T,
    val icon: ImageVector,
    val label: String,
    val testTag: String,
    val pinnedEnd: Boolean = false,
)

@Composable
public fun <T> BhodlNavigationSuite(
    layout: NavigationLayout,
    destinations: List<NavDestination<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    railTestTag: String,
    content: @Composable () -> Unit,
) {
    val main = destinations.filterNot { it.pinnedEnd }
    val end = destinations.filter { it.pinnedEnd }
    when (layout) {
        NavigationLayout.Rail -> Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                modifier = Modifier.fillMaxHeight().testTag(railTestTag),
                containerColor = BhodlTheme.colors.surface,
            ) {
                main.forEach { d ->
                    NavigationRailItem(
                        selected = d.key == selected,
                        onClick = { onSelect(d.key) },
                        icon = { Icon(imageVector = d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                        modifier = Modifier.testTag(d.testTag),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                end.forEach { d ->
                    NavigationRailItem(
                        selected = d.key == selected,
                        onClick = { onSelect(d.key) },
                        icon = { Icon(imageVector = d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                        modifier = Modifier.testTag(d.testTag),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxSize().padding(start = 4.dp)) { content() }
        }
        NavigationLayout.BottomBar -> Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            NavigationBar(
                modifier = Modifier.testTag(railTestTag),
                containerColor = BhodlTheme.colors.surface,
            ) {
                (main + end).forEach { d ->
                    NavigationBarItem(
                        selected = d.key == selected,
                        onClick = { onSelect(d.key) },
                        icon = { Icon(imageVector = d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                        modifier = Modifier.testTag(d.testTag),
                    )
                }
            }
        }
    }
}
