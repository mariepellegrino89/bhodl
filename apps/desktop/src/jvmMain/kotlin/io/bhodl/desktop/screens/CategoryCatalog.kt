package io.bhodl.desktop.screens

import io.bhodl.shared.resources.cat_color_n
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.cat_color
import io.bhodl.shared.resources.cat_new
import io.bhodl.shared.resources.cat_preset_education
import io.bhodl.shared.resources.cat_preset_food
import io.bhodl.shared.resources.cat_preset_gifts
import io.bhodl.shared.resources.cat_preset_health
import io.bhodl.shared.resources.cat_preset_home
import io.bhodl.shared.resources.cat_preset_investments
import io.bhodl.shared.resources.cat_preset_leisure
import io.bhodl.shared.resources.cat_preset_other
import io.bhodl.shared.resources.cat_preset_restaurants
import io.bhodl.shared.resources.cat_preset_salary
import io.bhodl.shared.resources.cat_preset_shopping
import io.bhodl.shared.resources.cat_preset_subscriptions
import io.bhodl.shared.resources.cat_preset_transport
import io.bhodl.shared.resources.cat_preset_travel
import io.bhodl.shared.resources.cat_preset_utilities
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * Redesign 2026-10 — category catalog: the classic categories with a
 * fixed colour each, the user's own categories with the colour they
 * picked (SettingsStore.categoryColors), and one [categoryColor] used by
 * every surface (rows, "where the sats went", report pie, chips) so a
 * category looks the same everywhere.
 */

/** Category colours: one hue per slot, a dark-theme and a light-theme shade. */
internal object CategoryPalette {
    private val dark = listOf(
        Color(0xFF3FB950), Color(0xFF58A6FF), Color(0xFFBC8CFF), Color(0xFFF0883E), Color(0xFFFF7B72),
        Color(0xFFF778BA), Color(0xFFE3B341), Color(0xFF39C5CF), Color(0xFF8C9EFF), Color(0xFFA5D65A),
        Color(0xFFFFA198), Color(0xFF56D4DD), Color(0xFFD2A679), Color(0xFF8B949E),
    )
    private val light = listOf(
        Color(0xFF1A7F37), Color(0xFF0969DA), Color(0xFF8250DF), Color(0xFFBC4C00), Color(0xFFCF222E),
        Color(0xFFBF3989), Color(0xFF9A6700), Color(0xFF1B7C83), Color(0xFF3E4FB8), Color(0xFF4D7A1A),
        Color(0xFFA40E26), Color(0xFF0E7490), Color(0xFF7D4E24), Color(0xFF57606A),
    )
    val size: Int get() = dark.size

    /** Slot of "Other": neutral grey, never handed out by the hash fallback. */
    const val OTHER: Int = 13

    @Composable
    fun color(index: Int): Color = (if (BhodlTheme.colors.isDark) dark else light)[Math.floorMod(index, size)]
}

/**
 * A classic category: its label, the names it is recognised by (any
 * language) and its colour slot. The first two aliases are its label in
 * English and in Italian: a category stored under either is shown in
 * the app's language ([presetCategoryNamed]).
 */
internal data class PresetCategory(val label: StringResource, val aliases: List<String>, val colorIndex: Int) {
    /** The label in every app language, lower-case. */
    val names: List<String> get() = aliases.take(2)
}

/** The classic category [name] is the label of (in any app language), if any. */
internal fun presetCategoryNamed(name: String?): PresetCategory? {
    val key = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return PRESET_CATEGORIES.firstOrNull { key in it.names }
}

/**
 * The text to store for a picked [choice]: a classic category already
 * used in the data ([known]) keeps the spelling it has there, so its
 * budget and totals still match after a language change; anything else
 * is stored as picked.
 */
internal fun storedCategoryName(choice: String, known: List<String>): String {
    val preset = presetCategoryNamed(choice) ?: return choice
    return known.firstOrNull { presetCategoryNamed(it) == preset } ?: choice
}

internal val PRESET_CATEGORIES: List<PresetCategory> = listOf(
    PresetCategory(Res.string.cat_preset_food, listOf("food", "cibo", "groceries", "spesa", "alimentari"), 0),
    PresetCategory(Res.string.cat_preset_transport, listOf("transport", "trasporti", "trasporto", "fuel", "carburante"), 1),
    PresetCategory(Res.string.cat_preset_home, listOf("home", "casa", "rent", "affitto"), 2),
    PresetCategory(Res.string.cat_preset_utilities, listOf("utilities", "bollette", "bills"), 3),
    PresetCategory(Res.string.cat_preset_health, listOf("health", "salute"), 4),
    PresetCategory(Res.string.cat_preset_leisure, listOf("leisure", "svago", "entertainment", "intrattenimento"), 5),
    PresetCategory(Res.string.cat_preset_shopping, listOf("shopping", "shopping"), 6),
    PresetCategory(Res.string.cat_preset_travel, listOf("travel", "viaggi", "viaggio"), 7),
    PresetCategory(Res.string.cat_preset_subscriptions, listOf("subscriptions", "abbonamenti"), 8),
    PresetCategory(Res.string.cat_preset_education, listOf("education", "istruzione"), 9),
    PresetCategory(Res.string.cat_preset_gifts, listOf("gifts", "regali"), 10),
    PresetCategory(Res.string.cat_preset_salary, listOf("salary", "stipendio", "income", "entrate"), 11),
    PresetCategory(Res.string.cat_preset_restaurants, listOf("restaurants", "ristoranti", "ristorante"), 12),
    PresetCategory(Res.string.cat_preset_investments, listOf("investments", "investimenti", "dca"), 9),
    PresetCategory(Res.string.cat_preset_other, listOf("other", "altro"), CategoryPalette.OTHER),
)

/**
 * Colour slot of [category]: the colour the user picked, else the
 * classic category's, else a stable hash (grey excluded). Null when
 * uncategorised.
 */
internal fun categoryColorIndex(category: String?, custom: Map<String, Int>): Int? {
    val key = category?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    custom[key]?.let { return it }
    PRESET_CATEGORIES.firstOrNull { key in it.aliases }?.let { return it.colorIndex }
    return Math.floorMod(key.hashCode(), CategoryPalette.size - 1)
}

/** The colour of [category] on every surface; uncategorised is the "pending" amber. */
@Composable
internal fun categoryColor(category: String?): Color {
    val index = categoryColorIndex(category, SettingsStore.categoryColors.value)
        ?: return BhodlTheme.colors.financialPending
    return CategoryPalette.color(index)
}

/**
 * The categories the picker lists, in order: the classic ones (in the
 * app's language), the user's own, then any other name already used in
 * the registry. Deduplicated case-insensitively, a classic category
 * also absorbing its aliases (an existing "cibo" shows as "Food").
 */
@Composable
internal fun categoryChoices(known: List<String>): List<String> {
    val presets = PRESET_CATEGORIES.map { stringResource(it.label) }
    val presetAliases = PRESET_CATEGORIES.flatMap { it.aliases }.toSet()
    val seen = presets.map { it.lowercase() }.toMutableSet()
    val extra = (SettingsStore.customCategories.value + known)
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase() !in presetAliases && seen.add(it.lowercase()) }
    return presets + extra
}

/** Whether [query] names [choice] — its label or, for a classic category, one of its aliases. */
@Composable
internal fun categoryMatches(choice: String, query: String, exact: Boolean): Boolean {
    val q = query.trim()
    val preset = PRESET_CATEGORIES.firstOrNull { stringResource(it.label).equals(choice, ignoreCase = true) }
    val names = listOf(choice) + preset?.aliases.orEmpty()
    return names.any { if (exact) it.equals(q, ignoreCase = true) else it.contains(q, ignoreCase = true) }
}

internal object CategoryPickerTags {
    const val TOGGLE: String = "category-picker.toggle"
    const val LIST: String = "category-picker.list"
    fun item(name: String): String = "category-picker.item.${name.lowercase()}"
    const val NEW: String = "category-picker.new"
    fun swatch(i: Int): String = "category-picker.swatch.$i"
}

@Composable
internal fun CategoryDot(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** The drop-down list under the category field: every choice with its colour, plus "New category". */
@Composable
internal fun CategoryChoiceList(
    choices: List<String>,
    query: String,
    /** False when the list was opened with the arrow: everything is shown. */
    filter: Boolean,
    onPick: (String) -> Unit,
    onNew: (String) -> Unit,
) {
    val q = query.trim()
    val shown = if (q.isEmpty() || !filter) choices else choices.filter { categoryMatches(it, q, exact = false) }
    val exact = choices.any { categoryMatches(it, q, exact = true) }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, BhodlTheme.colors.outline, RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .testTag(CategoryPickerTags.LIST),
    ) {
        shown.forEach { name ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(name) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(CategoryPickerTags.item(name)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CategoryDot(categoryColor(name))
                Text(name, style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textPrimary)
            }
        }
        if (q.isNotEmpty() && !exact) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onNew(q) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag(CategoryPickerTags.NEW),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.cat_new, q),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.brandPrimary,
                )
            }
        }
    }
}

/** Colour swatches for [selected] slot; the same palette the charts use. */
@Composable
internal fun CategoryColorRow(selected: Int?, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.cat_color), style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (0 until CategoryPalette.size).forEach { i ->
                val on = i == selected
                val label = stringResource(Res.string.cat_color_n, i + 1)
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .border(if (on) 2.dp else 0.dp, if (on) BhodlTheme.colors.textPrimary else Color.Transparent, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(CategoryPalette.color(i))
                        .clickable(role = Role.RadioButton) { onPick(i) }
                        .semantics { this.selected = on; contentDescription = label }
                        .testTag(CategoryPickerTags.swatch(i)),
                )
            }
        }
    }
}
