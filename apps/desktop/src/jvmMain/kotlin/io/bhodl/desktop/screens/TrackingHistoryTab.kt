package io.bhodl.desktop.screens
import io.bhodl.shared.resources.rule_exact_amount
import io.bhodl.desktop.domain.RuleDraft
import io.bhodl.desktop.domain.RulePatternKind
import io.bhodl.desktop.domain.ruleDraftFromEdit
import io.bhodl.desktop.domain.ruleDraftMatches
import io.bhodl.desktop.domain.externalSignedNetSat
import io.bhodl.desktop.domain.applyRules
import io.bhodl.desktop.domain.RecurringCandidate
import io.bhodl.desktop.domain.dayOfMonth
import io.bhodl.desktop.domain.manualAssetsByKindSat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.TrackerCache
import io.bhodl.desktop.navigation.TrackerSection
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.desktop.ui.categoryDisplayName
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_uncategorised
import io.bhodl.shared.resources.tracker_reports_empty_wallets
import io.bhodl.shared.resources.trk_cat_dialog_body
import io.bhodl.shared.resources.trk_cat_dialog_clear
import io.bhodl.shared.resources.trk_cat_dialog_hint
import io.bhodl.shared.resources.trk_cat_dialog_title
import io.bhodl.shared.resources.trp_cb_dialog_exclude
import io.bhodl.shared.resources.trp_cb_dialog_invalid
import io.bhodl.shared.resources.trp_cb_dialog_market
import io.bhodl.shared.resources.trp_cb_dialog_market_none
import io.bhodl.shared.resources.trp_cb_dialog_price
import io.bhodl.shared.resources.trp_cb_dialog_title
import io.bhodl.shared.resources.trk_hist_add_category
import io.bhodl.shared.resources.trk_hist_at_block
import io.bhodl.shared.resources.trk_hist_empty
import io.bhodl.shared.resources.trk_hist_filter_all
import io.bhodl.shared.resources.trk_hist_history
import io.bhodl.shared.resources.trk_hist_no_match
import io.bhodl.shared.resources.trk_hist_pending
import io.bhodl.shared.resources.trk_hist_per_category
import io.bhodl.shared.resources.trk_hist_range
import io.bhodl.shared.resources.trk_hist_received
import io.bhodl.shared.resources.trk_hist_running_balance
import io.bhodl.shared.resources.trk_hist_self_transfer
import io.bhodl.shared.resources.trk_hist_sent
import io.bhodl.shared.resources.trk_hist_showing_50
import io.bhodl.shared.resources.trk_hist_tx_count
import io.bhodl.shared.resources.trk_hist_unknown_wallet
import io.bhodl.shared.resources.trk_hist_wallets
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HistoryTabBody(
    wallets: List<WalletRecordFfi>,
    refreshing: Boolean,
    groupedWallets: List<GroupedWallet>,
    excludedWalletIds: Set<String>,
    onToggleInclude: (walletId: String, included: Boolean) -> Unit,
    events: List<io.bhodl.ffi.PaymentEvent>,
    filteredEvents: List<io.bhodl.ffi.PaymentEvent>,
    categories: List<String>,
    categoryFilter: String?,
    timeRangeFilter: Pair<Long, Long>?,
    onClearTimeRange: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onEditCategory: (io.bhodl.ffi.PaymentEvent) -> Unit,
    /** Transactions redesign 2026-10 — the period (or drill-down range)
     *  the summaries describe, the search box, one-click categorisation. */
    periodEvents: List<io.bhodl.ffi.PaymentEvent> = filteredEvents,
    search: String = "",
    onSearchChange: (String) -> Unit = {},
    onQuickCategorize: (io.bhodl.ffi.PaymentEvent, String) -> Unit = { _, _ -> },
    /** Schema v8 — labels of the manual accounts (cash, bank, card), by their synthetic wallet id. */
    extraLabels: Map<String, String> = emptyMap(),
) {
    val labelByWalletId = wallets.associate { it.id to it.label } + extraLabels
    // Phase 3.7 — running balance map keyed by txid. Walk the full
    // event stream oldest-first, accumulating signed amounts; mempool
    // events (timestampUnix == null) are appended last with the
    // running tail as their balance (best-effort). Looking up the
    // running balance for a row is then O(1) by txid.
    // send_to_self legs only move the fee out of custody.
    var visible by remember(filteredEvents) { mutableStateOf(50) }
    val walletLabel: (String) -> String = { id -> labelByWalletId[id] ?: "?" }
    val unknownWallet = stringResource(Res.string.trk_hist_unknown_wallet)
    val uncategorized = remember(periodEvents) {
        periodEvents.filter { it.category.isNullOrBlank() && it.direction != io.bhodl.ffi.PaymentDirection.SELF_TRANSFER }
            .distinctBy { it.txidOrPaymentId to it.walletId }
            .sortedByDescending { it.timestampUnix ?: ULong.MAX_VALUE }
    }
    val quick = remember(events) { frequentCategories(events) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (timeRangeFilter != null) {
            ActiveRangeFilterChip(range = timeRangeFilter, onClear = onClearTimeRange)
        }
        if (events.isNotEmpty()) TxSummaryRow(periodEvents)

        // Search + wallets included in the totals, then the category pills.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TxSearchField(search, onSearchChange, Modifier.widthIn(min = 280.dp, max = 460.dp))
            if (wallets.isEmpty() && refreshing) {
                SkeletonRow()
            } else if (wallets.isEmpty()) {
                Text(
                    text = stringResource(Res.string.tracker_reports_empty_wallets),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
        if (groupedWallets.isNotEmpty()) {
            WalletIncludeChips(groupedWallets, excludedWalletIds) { group, include ->
                // All member ids share the include/exclude state.
                group.memberIds.forEach { id -> onToggleInclude(id, include) }
            }
        }
        if (events.isNotEmpty()) {
            CategoryFilterRow(
                categories = categories,
                hasUncategorised = events.any { it.category.isNullOrBlank() },
                selected = categoryFilter,
                onSelect = onSelectCategory,
            )
        }

        if (events.isEmpty()) {
            if (refreshing) {
                repeat(3) { SkeletonRow() }
            } else {
                Text(
                    text = stringResource(Res.string.trk_hist_empty),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            return@Column
        }
        val timeline: @Composable (Modifier) -> Unit = { m ->
            if (filteredEvents.isEmpty()) {
                Text(
                    text = stringResource(Res.string.trk_hist_no_match),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = m,
                )
            } else {
                TxTimeline(
                    events = filteredEvents,
                    visible = visible,
                    onShowMore = { visible += 50 },
                    walletLabel = { labelByWalletId[it] ?: unknownWallet },
                    chip = { e -> CategoryChip(category = e.category, onClick = { onEditCategory(e) }) },
                    modifier = m,
                )
            }
        }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
            val lane = uncategorized.isNotEmpty() && quick.isNotEmpty()
            if (!lane) {
                timeline(Modifier.fillMaxWidth())
            } else if (maxWidth < 900.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TxReviewLane(uncategorized, quick, walletLabel, onQuickCategorize, onEditCategory, Modifier.fillMaxWidth())
                    timeline(Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    timeline(Modifier.weight(0.66f))
                    TxReviewLane(uncategorized, quick, walletLabel, onQuickCategorize, onEditCategory, Modifier.weight(0.34f))
                }
            }
        }
    }
}

/**
 * Phase 3.6 — chip shown at the top of the History tab when a
 * time-range filter is active (set by clicking a bar bucket on the
 * Dashboard). Tap the X to clear; the category filter is preserved
 * so the user can stack the two.
 */
@Composable
private fun ActiveRangeFilterChip(
    range: Pair<Long, Long>,
    onClear: () -> Unit,
) {
    val fmt = remember {
        java.time.format.DateTimeFormatter.ofPattern("dd/MM/yy")
            .withZone(java.time.ZoneId.systemDefault())
    }
    val from = fmt.format(java.time.Instant.ofEpochSecond(range.first))
    val to = fmt.format(java.time.Instant.ofEpochSecond(range.second))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BhodlTheme.colors.brandPrimary.copy(alpha = 0.12f))
            .border(
                1.dp,
                BhodlTheme.colors.brandPrimary.copy(alpha = 0.4f),
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.trk_hist_range, from, to),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.brandPrimary,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(BhodlTheme.colors.brandPrimary.copy(alpha = 0.2f))
                .clickable { onClear() }
                .padding(horizontal = 10.dp, vertical = 2.dp)
                .testTag(TrackingTags.RANGE_CLEAR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "✕",
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.brandPrimary,
            )
        }
    }
}

@Composable
private fun CategoryChip(
    category: String?,
    onClick: () -> Unit,
) {
    val tagged = !category.isNullOrBlank()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (tagged) BhodlTheme.colors.brandPrimary.copy(alpha = 0.15f)
                else BhodlTheme.colors.background,
            )
            .border(
                width = 1.dp,
                color = if (tagged) BhodlTheme.colors.brandPrimary
                else BhodlTheme.colors.outline,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(TrackingTags.CATEGORY_CHIP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (tagged) categoryDisplayName(category) else stringResource(Res.string.trk_hist_add_category),
            style = BhodlTheme.typography.bodyMedium,
            color = if (tagged) BhodlTheme.colors.brandPrimary
            else BhodlTheme.colors.textTertiary,
        )
    }
}

/**
 * Filter strip rendered above the History list. Three logical states:
 *   - "Tutte" (selected = `null`) shows every event.
 *   - "Senza categoria" (selected = `""`) shows only events whose
 *     `category` is null or blank — only rendered when the registry
 *     actually contains uncategorised events, otherwise the chip would
 *     dead-end at zero matches.
 *   - One chip per known category (selected = exact string match).
 */
@Composable
private fun CategoryFilterRow(
    categories: List<String>,
    hasUncategorised: Boolean,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    if (categories.isEmpty() && !hasUncategorised) return
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChipPill(
            label = stringResource(Res.string.trk_hist_filter_all),
            selected = selected == null,
            onClick = { onSelect(null) },
            testTagId = TrackingTags.FILTER_ALL,
        )
        if (hasUncategorised) {
            FilterChipPill(
                label = stringResource(Res.string.common_uncategorised),
                selected = selected == "",
                onClick = { onSelect("") },
                testTagId = TrackingTags.FILTER_NONE,
            )
        }
        categories.forEach { cat ->
            FilterChipPill(
                label = categoryDisplayName(cat),
                selected = selected == cat,
                onClick = { onSelect(cat) },
                testTagId = null,
            )
        }
    }
}

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTagId: String?,
) {
    val base = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(
            if (selected) BhodlTheme.colors.brandPrimary
            else BhodlTheme.colors.surface,
        )
        .border(
            width = 1.dp,
            color = if (selected) BhodlTheme.colors.brandPrimary
            else BhodlTheme.colors.outline,
            shape = RoundedCornerShape(8.dp),
        )
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)
    Row(
        modifier = if (testTagId != null) base.testTag(testTagId) else base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.bodyMedium,
            color = if (selected) BhodlTheme.colors.background
            else BhodlTheme.colors.textPrimary,
        )
    }
}

/**
 * Inline editor dialog for one event's category. The text field is
 * the source of truth (free-form, BIP-329-style). Suggestion chips
 * below offer one-tap insertion of categories already used in the
 * registry — clicking one fills the input but does NOT auto-save,
 * so the user can still edit (e.g., to fix a typo before commit).
 */
@Composable
internal fun CategoryEditDialog(
    event: io.bhodl.ffi.PaymentEvent,
    knownCategories: List<String>,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /**
     * Phase 7.10 — receives the new crypto event kind whenever the
     * user picks a chip. Called eagerly (not on Save) because the
     * kind picker has its own implicit persistence model: each tap
     * commits via `setEventCryptoKind`. Default no-op for callers
     * that don't care.
     */
    onCryptoEventKindChange: (CryptoEventKind) -> Unit = {},
    /**
     * BTC average cost — when non-null the dialog also edits the price
     * this transaction counts at and its exclusion; committed on Save
     * together with the category, only when changed.
     */
    costBasis: CostBasisEditState? = null,
    onCostBasisSave: (pricePerBtc: Double?, excluded: Boolean) -> Unit = { _, _ -> },
    /** Redesign 2026-10 — when non-null, "also create a rule" (off by default). */
    ruleOption: RuleOptionContext? = null,
    /** Called on Save, before [onSave], when the rule option is on. */
    onCreateRule: (draft: RuleDraft, applyTo: List<io.bhodl.ffi.PaymentEvent>) -> Unit = { _, _ -> },
) {
    var input by remember(event.txidOrPaymentId) {
        mutableStateOf(event.category.orEmpty())
    }
    val initialPriceText = remember(event.txidOrPaymentId, costBasis?.userPrice) {
        costBasis?.userPrice?.let { io.bhodl.desktop.ui.money.formatDecimalInput(it, maxFractionDigits = 2) }.orEmpty()
    }
    var priceText by remember(event.txidOrPaymentId, initialPriceText) { mutableStateOf(initialPriceText) }
    var excluded by remember(event.txidOrPaymentId, costBasis?.excluded) {
        mutableStateOf(costBasis?.excluded ?: false)
    }
    val parsedPrice = priceText.takeIf { it.isNotBlank() }?.let { io.bhodl.desktop.ui.money.parseDecimalInput(it) }
    val priceInvalid = priceText.isNotBlank() && (parsedPrice == null || parsedPrice < 0.0)
    var cryptoKind by remember(event.txidOrPaymentId) {
        mutableStateOf(CryptoEventKind.fromWire(event.cryptoEventKind))
    }
    var makeRule by remember(event.txidOrPaymentId) { mutableStateOf(false) }
    // Redesign 2026-10 — category drop-down and colour.
    var menuOpen by remember(event.txidOrPaymentId) { mutableStateOf(false) }
    var menuFilter by remember(event.txidOrPaymentId) { mutableStateOf(false) }
    var pickedColor by remember(event.txidOrPaymentId) { mutableStateOf<Int?>(null) }
    val customColors = io.bhodl.desktop.settings.SettingsStore.categoryColors.value
    val shownColor = pickedColor ?: categoryColorIndex(input, customColors)
    val choices = categoryChoices(knownCategories)
    val isKnown = choices.any { categoryMatches(it, input, exact = true) }
    var ruleKind by remember(event.txidOrPaymentId) { mutableStateOf<RulePatternKind?>(null) }
    var applyExisting by remember(event.txidOrPaymentId) { mutableStateOf(true) }
    val exactAmountName = stringResource(Res.string.rule_exact_amount)
    val ruleDraft = ruleOption?.takeIf { makeRule }?.let { ctx ->
        val kind = ruleKind ?: RulePatternKind.entries.firstOrNull {
            ruleDraftFromEdit(event, input, ctx.walletLabel, it, exactAmountName) != null
        }
        kind?.let { ruleDraftFromEdit(event, input, ctx.walletLabel, it, exactAmountName) }
    }
    val ruleMatches = remember(ruleDraft, ruleOption?.events) {
        if (ruleDraft == null || ruleOption == null) emptyList()
        else ruleDraftMatches(ruleDraft, ruleOption.events, ruleOption.payeeOf, edited = event)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.trk_cat_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(Res.string.trk_cat_dialog_body),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; menuOpen = true; menuFilter = true; pickedColor = null },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.trk_cat_dialog_hint)) },
                    leadingIcon = if (input.isNotBlank()) {
                        { CategoryDot(shownColor?.let { CategoryPalette.color(it) } ?: BhodlTheme.colors.financialPending, size = 12) }
                    } else {
                        null
                    },
                    trailingIcon = {
                        androidx.compose.material3.IconButton(
                            onClick = { menuOpen = !menuOpen; menuFilter = false },
                            modifier = Modifier.testTag(CategoryPickerTags.TOGGLE),
                        ) { Text(if (menuOpen) "▴" else "▾", color = BhodlTheme.colors.textSecondary) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TrackingTags.CATEGORY_INPUT),
                )
                if (menuOpen) {
                    CategoryChoiceList(
                        choices = choices,
                        query = input,
                        filter = menuFilter,
                        onPick = { input = storedCategoryName(it, knownCategories); menuOpen = false; pickedColor = null },
                        onNew = { input = it; menuOpen = false },
                    )
                }
                if (input.isNotBlank()) {
                    CategoryColorRow(selected = shownColor, onPick = { pickedColor = it })
                }
                if (ruleOption != null && input.isNotBlank()) {
                    RuleOptionSection(
                        event = event,
                        category = input,
                        context = ruleOption,
                        enabled = makeRule,
                        onEnabledChange = { makeRule = it },
                        kind = ruleKind,
                        onKindChange = { ruleKind = it },
                        matches = ruleMatches,
                        applyExisting = applyExisting,
                        onApplyExistingChange = { applyExisting = it },
                    )
                }
                // Phase 7.10 — crypto event kind picker. Lives below
                // the category input because it's a *different
                // dimension* (taxonomy vs free-text label), and
                // because BHODL renders both badges on the row.
                CryptoEventKindPicker(
                    current = cryptoKind,
                    onChange = {
                        cryptoKind = it
                        onCryptoEventKindChange(it)
                    },
                )

                if (costBasis != null) {
                    Text(
                        text = stringResource(Res.string.trp_cb_dialog_title),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    Text(
                        text = costBasis.marketPrice?.let {
                            stringResource(
                                Res.string.trp_cb_dialog_market,
                                io.bhodl.desktop.ui.formatFiatWithCode(it, costBasis.currency),
                            )
                        } ?: stringResource(Res.string.trp_cb_dialog_market_none),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = io.bhodl.desktop.ui.money.filterDecimalChars(it) },
                        singleLine = true,
                        enabled = !excluded,
                        isError = priceInvalid,
                        label = { Text(stringResource(Res.string.trp_cb_dialog_price, costBasis.currency)) },
                        supportingText = if (priceInvalid) {
                            { Text(stringResource(Res.string.trp_cb_dialog_invalid)) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(TrackingTags.COST_BASIS_PRICE),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { excluded = !excluded }
                            .testTag(TrackingTags.COST_BASIS_EXCLUDE),
                    ) {
                        androidx.compose.material3.Checkbox(checked = excluded, onCheckedChange = null)
                        Text(
                            text = stringResource(Res.string.trp_cb_dialog_exclude),
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.textPrimary,
                        )
                    }
                }

            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    if (costBasis != null) {
                        val newPrice = if (excluded) costBasis.userPrice else parsedPrice
                        if (newPrice != costBasis.userPrice || excluded != costBasis.excluded) {
                            onCostBasisSave(newPrice, excluded)
                        }
                    }
                    ruleDraft?.let { onCreateRule(it, if (applyExisting) ruleMatches else emptyList()) }
                    // A new category and a picked colour are remembered.
                    val name = input.trim()
                    if (name.isNotEmpty()) {
                        if (!isKnown) {
                            io.bhodl.desktop.settings.SettingsStore.addCustomCategory(name)
                        }
                        pickedColor?.let { io.bhodl.desktop.settings.SettingsStore.setCategoryColor(name, it) }
                    }
                    onSave(input)
                },
                enabled = !priceInvalid,
                modifier = Modifier.testTag(TrackingTags.CATEGORY_SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!event.category.isNullOrBlank()) {
                    androidx.compose.material3.TextButton(
                        onClick = { onSave("") },
                        modifier = Modifier.testTag(TrackingTags.CATEGORY_CLEAR),
                    ) { Text(stringResource(Res.string.trk_cat_dialog_clear)) }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.common_cancel))
                }
            }
        },
    )
}

/**
 * BTC average cost fields of [CategoryEditDialog]: the user's stored
 * price for the transaction (null = market rate), its exclusion, and the
 * market rate of its day shown as the default.
 */
internal data class CostBasisEditState(
    val currency: String,
    val userPrice: Double?,
    val excluded: Boolean,
    val marketPrice: Double?,
)

@Composable
private fun formatEventTimestamp(
    timestamp: ULong?,
    height: UInt?,
): String {
    if (timestamp == null) return stringResource(Res.string.trk_hist_pending)
    val instant = java.time.Instant.ofEpochSecond(timestamp.toLong())
    val formatted = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
    return if (height != null) stringResource(Res.string.trk_hist_at_block, formatted, height.toString()) else formatted
}

/** Phase 3.4 — per-category totals shown in the breakdown card.
 *  `count` is the raw event count in this bucket, regardless of
 *  direction, so the user can spot a rarely-tagged category. */
internal data class CategoryTotals(
    val incomingSat: ULong,
    val outgoingSat: ULong,
    val count: Int,
)
