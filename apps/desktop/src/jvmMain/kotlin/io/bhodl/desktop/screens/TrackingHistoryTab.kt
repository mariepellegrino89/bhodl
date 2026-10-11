package io.bhodl.desktop.screens
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
import io.bhodl.ffi.WalletKindFfi
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
import io.bhodl.shared.resources.trk_cat_dialog_suggestions
import io.bhodl.shared.resources.trk_cat_dialog_title
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
    breakdown: List<Pair<String?, CategoryTotals>>,
    categories: List<String>,
    categoryFilter: String?,
    timeRangeFilter: Pair<Long, Long>?,
    onClearTimeRange: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onEditCategory: (io.bhodl.ffi.PaymentEvent) -> Unit,
) {
    val labelByWalletId = wallets.associate { it.id to it.label }
    // Phase 3.7 — running balance map keyed by txid. Walk the full
    // event stream oldest-first, accumulating signed amounts; mempool
    // events (timestampUnix == null) are appended last with the
    // running tail as their balance (best-effort). Looking up the
    // running balance for a row is then O(1) by txid.
    val runningBalance = remember(events) {
        val confirmed = events
            .filter { it.timestampUnix != null }
            .sortedBy { it.timestampUnix!! }
        val unconfirmed = events.filter { it.timestampUnix == null }
        var running = 0L
        val map = HashMap<String, Long>()
        for (e in confirmed) {
            // send_to_self legs only move the fee out of custody.
            running += e.externalSignedNetSat()
            map[e.txidOrPaymentId] = running
        }
        for (e in unconfirmed) {
            map[e.txidOrPaymentId] = running
        }
        map
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (timeRangeFilter != null) {
            ActiveRangeFilterChip(
                range = timeRangeFilter,
                onClear = onClearTimeRange,
            )
        }
        Text(
            text = stringResource(Res.string.trk_hist_wallets),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        if (wallets.isEmpty()) {
            if (refreshing) {
                repeat(2) { SkeletonRow() }
            } else {
                Text(
                    text = stringResource(Res.string.tracker_reports_empty_wallets),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        } else {
            groupedWallets.forEach { group ->
                val included = group.memberIds.any { it !in excludedWalletIds }
                GroupedWalletRow(
                    group = group,
                    included = included,
                    onToggleInclude = {
                        // All member ids share the include/exclude
                        // state — toggling flips every one of them so
                        // a single click on a "signing (tracked)" row
                        // hides both the signing UUID and its mirror.
                        group.memberIds.forEach { id ->
                            onToggleInclude(id, !included)
                        }
                    },
                )
            }
        }

        // Phase 3.4 — per-category breakdown card. Hidden when the
        // registry has zero events to avoid an empty box on first
        // launch.
        if (events.isNotEmpty()) {
            CategoryBreakdownCard(breakdown = breakdown)
        }

        Text(
            text = stringResource(Res.string.trk_hist_history),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        // Phase 3.4 — filter chips above the history list. Always
        // shown when at least one event exists so the user has a
        // discoverable way to scope down by tag.
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
        } else if (filteredEvents.isEmpty()) {
            Text(
                text = stringResource(Res.string.trk_hist_no_match),
                style = BhodlTheme.typography.bodyMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        } else {
            filteredEvents.take(50).forEach { event ->
                PaymentEventRow(
                    event = event,
                    walletLabel = labelByWalletId[event.walletId]
                        ?: stringResource(Res.string.trk_hist_unknown_wallet),
                    runningBalanceSat = runningBalance[event.txidOrPaymentId],
                    onEditCategory = { onEditCategory(event) },
                )
            }
            if (filteredEvents.size > 50) {
                Text(
                    text = stringResource(Res.string.trk_hist_showing_50, filteredEvents.size),
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
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
private fun PaymentEventRow(
    event: io.bhodl.ffi.PaymentEvent,
    walletLabel: String,
    runningBalanceSat: Long?,
    onEditCategory: () -> Unit,
) {
    val directionLabel = when (event.direction) {
        io.bhodl.ffi.PaymentDirection.INCOMING -> stringResource(Res.string.trk_hist_received)
        io.bhodl.ffi.PaymentDirection.OUTGOING -> stringResource(Res.string.trk_hist_sent)
        io.bhodl.ffi.PaymentDirection.SELF_TRANSFER -> stringResource(Res.string.trk_hist_self_transfer)
    }
    val sign = when (event.direction) {
        io.bhodl.ffi.PaymentDirection.INCOMING -> "+"
        io.bhodl.ffi.PaymentDirection.OUTGOING -> "−"
        io.bhodl.ffi.PaymentDirection.SELF_TRANSFER -> ""
    }
    // Phase 3.10 — semantic colors with the +/− sign prefix as the
    // secondary cue (CVD-safe double encoding). Outgoing rows take the
    // negative semantic color; self-transfers stay neutral.
    val color = when (event.direction) {
        io.bhodl.ffi.PaymentDirection.INCOMING -> BhodlTheme.colors.financialPositive
        io.bhodl.ffi.PaymentDirection.OUTGOING -> BhodlTheme.colors.financialNegative
        io.bhodl.ffi.PaymentDirection.SELF_TRANSFER -> BhodlTheme.colors.financialNeutral
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$directionLabel · $walletLabel",
                    style = BhodlTheme.typography.titleMedium,
                    color = BhodlTheme.colors.textPrimary,
                )
                // Phase 3.11 — mempool / pending rows render the
                // timestamp in the dedicated `financialPending` amber
                // (Sparrow / Wasabi convention) so the user can tell
                // at a glance which rows are not yet final.
                val isPending = event.timestampUnix == null
                Text(
                    text = formatEventTimestamp(event.timestampUnix, event.blockHeight),
                    style = BhodlTheme.typography.bodyMedium,
                    color = if (isPending) BhodlTheme.colors.financialPending
                    else BhodlTheme.colors.textTertiary,
                )
                Text(
                    text = event.txidOrPaymentId.take(8) + "…" +
                        event.txidOrPaymentId.takeLast(8),
                    style = BhodlTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = BhodlTheme.colors.textTertiary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$sign${displayAmount(event.amountSat)}",
                    style = BhodlTheme.typography.titleMedium,
                    color = color,
                )
                // Phase 3.7 — running balance column (Sparrow pattern).
                // Cumulative wallet-wide net flow at the moment of
                // this event. Null when the running balance is
                // unknown (no timestamp anchor).
                if (runningBalanceSat != null) {
                    Text(
                        text = stringResource(
                            Res.string.trk_hist_running_balance,
                            displayAmount(runningBalanceSat.coerceAtLeast(0L).toULong()),
                        ),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
            }
        }
        // Phase 3.4 — category chip. Click to open the inline editor
        // dialog. Empty / null reads as "Add category" so the user
        // sees a clear affordance for tagging.
        // Phase 7.10 — crypto event kind badge sits to the right of
        // the category chip. Hidden when the kind is REGULAR (= no
        // override) so the row stays clean for ordinary tx.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CategoryChip(
                category = event.category,
                onClick = onEditCategory,
            )
            CryptoEventKindBadge(
                kind = CryptoEventKind.fromWire(event.cryptoEventKind),
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
            text = if (tagged) category!! else stringResource(Res.string.trk_hist_add_category),
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
                label = cat,
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
 * Per-category breakdown card. Lists each bucket with its incoming /
 * outgoing totals and the raw event count. The "Uncategorised"
 * bucket (key = null) only appears when at least one event is
 * untagged so a fully-categorised registry shows a clean list.
 */
@Composable
private fun CategoryBreakdownCard(breakdown: List<Pair<String?, CategoryTotals>>) {
    if (breakdown.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bhodlCard()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.trk_hist_per_category),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textSecondary,
        )
        breakdown.forEach { (category, totals) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = categoryDisplayName(category),
                        style = BhodlTheme.typography.titleMedium,
                        color = BhodlTheme.colors.textPrimary,
                    )
                    Text(
                        text = stringResource(Res.string.trk_hist_tx_count, totals.count),
                        style = BhodlTheme.typography.bodyMedium,
                        color = BhodlTheme.colors.textTertiary,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (totals.incomingSat > 0UL) {
                        Text(
                            text = "+${displayAmount(totals.incomingSat)}",
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.financialPositive,
                        )
                    }
                    if (totals.outgoingSat > 0UL) {
                        Text(
                            text = "−${displayAmount(totals.outgoingSat)}",
                            style = BhodlTheme.typography.bodyMedium,
                            color = BhodlTheme.colors.financialNegative,
                        )
                    }
                }
            }
        }
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
) {
    var input by remember(event.txidOrPaymentId) {
        mutableStateOf(event.category.orEmpty())
    }
    var cryptoKind by remember(event.txidOrPaymentId) {
        mutableStateOf(CryptoEventKind.fromWire(event.cryptoEventKind))
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
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.trk_cat_dialog_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TrackingTags.CATEGORY_INPUT),
                )
                // Suggest categories that match what the user is
                // typing. When the field is empty we surface the
                // entire known list (capped to keep the dialog
                // compact); once they start typing we narrow to a
                // case-insensitive substring match.
                val suggestions = remember(input, knownCategories) {
                    val q = input.trim()
                    if (q.isEmpty()) {
                        knownCategories.take(8)
                    } else {
                        knownCategories
                            .filter { it.contains(q, ignoreCase = true) && it != q }
                            .take(8)
                    }
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

                if (suggestions.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.trk_cat_dialog_suggestions),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { s ->
                            FilterChipPill(
                                label = s,
                                selected = false,
                                onClick = { input = s },
                                testTagId = null,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(input) },
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
