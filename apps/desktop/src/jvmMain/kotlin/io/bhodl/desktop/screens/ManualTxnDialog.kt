package io.bhodl.desktop.screens

import io.bhodl.shared.resources.mtx_filter_all
import io.bhodl.shared.resources.mtx_filter_wallets
import io.bhodl.desktop.data.ManualAccountKind
import kotlinx.coroutines.launch
import io.bhodl.shared.resources.mtx_paid_with
import io.bhodl.shared.resources.mtx_received_on
import io.bhodl.shared.resources.mtx_add_account
import io.bhodl.shared.resources.mtx_wallets_auto
import io.bhodl.shared.resources.mtx_new_account_name
import io.bhodl.shared.resources.mtx_new_account_balance
import io.bhodl.shared.resources.mtx_new_account_create
import io.bhodl.shared.resources.mtx_account_hint
import io.bhodl.shared.resources.mtx_card_hint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.data.ManualAccount
import io.bhodl.desktop.data.ManualTxns
import io.bhodl.desktop.data.btcRateOn
import io.bhodl.desktop.data.fiatToSatAtRate
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.DatePickerField
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.formatFiatWithCode
import io.bhodl.desktop.ui.money.filterDecimalChars
import io.bhodl.desktop.ui.money.formatDecimalInput
import io.bhodl.desktop.ui.money.parseDecimalInput
import io.bhodl.ffi.ManualTxnFfi
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.mtx_amount
import io.bhodl.shared.resources.mtx_body
import io.bhodl.shared.resources.mtx_category
import io.bhodl.shared.resources.mtx_date
import io.bhodl.shared.resources.mtx_edit
import io.bhodl.shared.resources.mtx_in
import io.bhodl.shared.resources.mtx_new
import io.bhodl.shared.resources.mtx_no_rate
import io.bhodl.shared.resources.mtx_out
import io.bhodl.shared.resources.mtx_preview
import io.bhodl.shared.resources.mtx_unit_sat
import io.bhodl.shared.resources.tracker_note_optional
import io.bhodl.shared.resources.trk_cat_dialog_hint
import io.bhodl.ui.theme.BhodlTheme
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the manual-transaction dialog saves. */
internal data class ManualTxnDraft(
    /** [ManualTxns.DIRECTION_IN] or [ManualTxns.DIRECTION_OUT]. */
    val direction: String,
    val amountSat: ULong,
    /** What was typed, with its currency; both null for an amount in sat. */
    val amountFiat: Double?,
    val currency: String?,
    val occurredUnix: Long,
    val accountRef: String?,
    val category: String?,
    val note: String?,
)

/** Defaults for a new entry (a bill paid outside the wallets prefills these). */
internal data class ManualTxnPreset(
    val direction: String = ManualTxns.DIRECTION_OUT,
    val category: String? = null,
    val note: String? = null,
)

internal object ManualTxnTags {
    const val ADD: String = "manual-txn.add"
    const val DIR_OUT: String = "manual-txn.dir.out"
    const val DIR_IN: String = "manual-txn.dir.in"
    const val AMOUNT: String = "manual-txn.amount"
    const val PREVIEW: String = "manual-txn.preview"
    const val DATE: String = "manual-txn.date"
    const val CATEGORY: String = "manual-txn.category"
    const val NOTE: String = "manual-txn.note"
    const val SAVE: String = "manual-txn.save"
    const val DELETE: String = "manual-txn.delete"
    fun currency(code: String): String = "manual-txn.currency.$code"
    fun account(index: Int): String = "manual-txn.account.$index"
    const val BALANCE_HINT: String = "manual-txn.balance-hint"
    const val WALLETS_NOTE: String = "manual-txn.wallets-note"
    const val ADD_ACCOUNT: String = "manual-txn.add-account"
    const val NEW_ACCOUNT_NAME: String = "manual-txn.new-account.name"
    const val NEW_ACCOUNT_BALANCE: String = "manual-txn.new-account.balance"
    const val NEW_ACCOUNT_CREATE: String = "manual-txn.new-account.create"
}

/** The unit chip for an amount typed in sat. */
private const val SAT_UNIT = "sat"

/**
 * Registry schema v8 — add or edit income / spending outside the
 * wallets. The amount is typed in its own currency (or in sat) and
 * stored in sat at the BTC rate of the chosen day; the dialog shows the
 * conversion before saving. Editing keeps the stored sat value unless
 * the amount, currency or day changes.
 */
@Composable
internal fun ManualTxnDialog(
    editing: ManualTxnFfi?,
    editingCategory: String?,
    accounts: List<ManualAccount>,
    knownCategories: List<String>,
    onDismiss: () -> Unit,
    onSave: (ManualTxnDraft) -> Unit,
    onDelete: (() -> Unit)? = null,
    preset: ManualTxnPreset = ManualTxnPreset(),
    title: String? = null,
    /** Day zero for a new entry (test seam). */
    nowUnix: Long = System.currentTimeMillis() / 1000L,
    /** BTC rate on a day for a currency (test seam). */
    rateOn: suspend (dateUnix: Long, currency: String) -> Double? = { d, c -> btcRateOn(d, c) },
    /** The wallets, shown (not selectable) among the channels: their spending arrives by itself. */
    walletLabels: List<String> = emptyList(),
    /** "+ Account": creates a manual cash account (name, currency, balance) and returns it. */
    onAddAccount: (suspend (name: String, currency: String, balance: Double) -> ManualAccount?)? = null,
) {
    val key = editing?.id
    val dashboard = SettingsStore.fiatCurrency.value
    val fiatCodes = FiatCurrency.entries.filter { it != FiatCurrency.NONE }.map { it.code }
    var direction by remember(key) { mutableStateOf(editing?.direction ?: preset.direction) }
    var accountRef by remember(key) { mutableStateOf(editing?.accountRef) }
    // Accounts created here, until the reloaded list carries them.
    var created by remember { mutableStateOf<List<ManualAccount>>(emptyList()) }
    val allAccounts = accounts + created.filter { c -> accounts.none { it.ref == c.ref } }
    val account = allAccounts.firstOrNull { it.ref == accountRef } ?: allAccounts.first()
    // An account's balance moves in its own currency: the entry is typed in it.
    val accountCurrency = account.currency?.trim()?.uppercase()?.takeIf { account.kind == ManualAccountKind.ACCOUNT }
    val currencies = if (accountCurrency != null) listOf(accountCurrency) else fiatCodes + SAT_UNIT
    var currency by remember(key) {
        mutableStateOf(
            editing?.let { it.currency ?: SAT_UNIT }
                ?: accountCurrency
                ?: dashboard.code.takeIf { dashboard != FiatCurrency.NONE }
                ?: SAT_UNIT,
        )
    }
    if (currency !in currencies) currency = currencies.first()
    var amountText by remember(key) {
        mutableStateOf(
            editing?.let { t -> t.amountFiat?.let { formatDecimalInput(it, maxFractionDigits = 2) } ?: t.amountSat.toString() }.orEmpty(),
        )
    }
    var dateUnix by remember(key) { mutableStateOf(editing?.occurredUnix ?: nowUnix) }
    var category by remember(key) { mutableStateOf(editingCategory ?: preset.category.orEmpty()) }
    var note by remember(key) { mutableStateOf(editing?.note ?: preset.note.orEmpty()) }

    val inSat = currency == SAT_UNIT
    val typed: Double? = if (inSat) amountText.toLongOrNull()?.toDouble() else parseDecimalInput(amountText)
    var rate by remember { mutableStateOf<Double?>(null) }
    var rateLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(currency, dateUnix) {
        rateLoaded = false
        rate = if (currency == SAT_UNIT) null else rateOn(dateUnix, currency)
        rateLoaded = true
    }
    // Unchanged amount, currency and day: keep the stored value (no repricing).
    val unchanged = editing != null && dateUnix == editing.occurredUnix &&
        (editing.currency ?: SAT_UNIT) == currency &&
        (if (inSat) typed?.toULong() == editing.amountSat else typed == editing.amountFiat)
    val amountSat: ULong? = when {
        typed == null || typed <= 0.0 -> null
        unchanged -> editing!!.amountSat
        inSat -> typed.toLong().toULong()
        else -> fiatToSatAtRate(typed, rate)
    }?.takeIf { it > 0UL }
    val canSave = amountSat != null

    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(if (editing == null) Res.string.mtx_new else Res.string.mtx_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.mtx_body), style = BhodlTheme.typography.bodyMedium, color = BhodlTheme.colors.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChoicePill(stringResource(Res.string.mtx_out), direction == ManualTxns.DIRECTION_OUT, ManualTxnTags.DIR_OUT) {
                        direction = ManualTxns.DIRECTION_OUT
                    }
                    ChoicePill(stringResource(Res.string.mtx_in), direction == ManualTxns.DIRECTION_IN, ManualTxnTags.DIR_IN) {
                        direction = ManualTxns.DIRECTION_IN
                    }
                }
                ChannelPicker(
                    label = stringResource(if (direction == ManualTxns.DIRECTION_IN) Res.string.mtx_received_on else Res.string.mtx_paid_with),
                    accounts = allAccounts,
                    selected = account,
                    // The channel is part of the entry's identity: fixed once saved.
                    enabled = editing == null,
                    walletLabels = walletLabels,
                    onPick = { a ->
                        accountRef = a.ref
                        val own = a.currency?.trim()?.uppercase()?.takeIf { a.kind == ManualAccountKind.ACCOUNT }
                        if (own != null && own != currency) {
                            currency = own
                            amountText = ""
                        }
                    },
                    onAddAccount = onAddAccount?.takeIf { editing == null }?.let { add ->
                        { name, ccy, balance ->
                            add(name, ccy, balance)?.also { a ->
                                created = created + a
                                accountRef = a.ref
                                currency = ccy
                                amountText = ""
                            }
                        }
                    },
                    currencies = fiatCodes,
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = if (inSat) it.filter { ch -> ch.isDigit() } else filterDecimalChars(it) },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.mtx_amount)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag(ManualTxnTags.AMOUNT),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    currencies.forEach { c ->
                        val label = if (c == SAT_UNIT) stringResource(Res.string.mtx_unit_sat) else c
                        ChoicePill(label, currency == c, ManualTxnTags.currency(c)) {
                            if (currency != c) {
                                currency = c
                                amountText = ""
                            }
                        }
                    }
                }
                val previewText = when {
                    inSat || typed == null || !rateLoaded -> null
                    amountSat == null -> stringResource(Res.string.mtx_no_rate)
                    else -> stringResource(
                        Res.string.mtx_preview,
                        displayAmount(amountSat.toLong()),
                        Instant.ofEpochSecond(dateUnix).atZone(ZoneOffset.UTC).toLocalDate()
                            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        rate?.let { formatFiatWithCode(it, currency) }.orEmpty(),
                    )
                }
                if (previewText != null) {
                    Text(
                        previewText,
                        style = BhodlTheme.typography.labelMedium,
                        color = if (amountSat == null) BhodlTheme.colors.financialNegative else BhodlTheme.colors.textSecondary,
                        modifier = Modifier.testTag(ManualTxnTags.PREVIEW),
                    )
                }
                when (account.kind) {
                    ManualAccountKind.ACCOUNT, ManualAccountKind.CARD -> Text(
                        stringResource(
                            if (account.kind == ManualAccountKind.CARD) Res.string.mtx_card_hint else Res.string.mtx_account_hint,
                            account.label,
                        ),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                        modifier = Modifier.testTag(ManualTxnTags.BALANCE_HINT),
                    )
                    else -> {}
                }
                DatePickerField(
                    label = stringResource(Res.string.mtx_date),
                    epochSeconds = dateUnix,
                    onPick = { dateUnix = it },
                    testTagId = ManualTxnTags.DATE,
                )
                CategoryField(
                    value = category,
                    onValueChange = { category = it },
                    knownCategories = knownCategories,
                    label = stringResource(Res.string.mtx_category),
                    tag = ManualTxnTags.CATEGORY,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.tracker_note_optional)) },
                    modifier = Modifier.fillMaxWidth().testTag(ManualTxnTags.NOTE),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val sat = amountSat ?: return@TextButton
                    onSave(
                        ManualTxnDraft(
                            direction = direction,
                            amountSat = sat,
                            amountFiat = if (inSat) null else typed,
                            currency = if (inSat) null else currency,
                            occurredUnix = dateUnix,
                            accountRef = account.ref,
                            category = category.trim().ifBlank { null },
                            note = note.trim().ifBlank { null },
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier.testTag(ManualTxnTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag(ManualTxnTags.DELETE)) {
                        Text(stringResource(Res.string.common_delete))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

/**
 * "Paid with": cash, the accounts, the cards and "other", plus the
 * wallets greyed out (their spending arrives with the sync: entering it
 * by hand would count it twice) and "+ Account".
 */
@Composable
private fun ChannelPicker(
    label: String,
    accounts: List<ManualAccount>,
    selected: ManualAccount,
    enabled: Boolean,
    walletLabels: List<String>,
    onPick: (ManualAccount) -> Unit,
    onAddAccount: (suspend (String, String, Double) -> ManualAccount?)?,
    currencies: List<String>,
) {
    var adding by remember { mutableStateOf(false) }
    Text(label, style = BhodlTheme.typography.labelMedium, color = BhodlTheme.colors.textSecondary)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        accounts.forEachIndexed { i, a ->
            ChoicePill(a.label, a.ref == selected.ref, ManualTxnTags.account(i), enabled = enabled) { onPick(a) }
        }
        if (onAddAccount != null) {
            ChoicePill(stringResource(Res.string.mtx_add_account), adding, ManualTxnTags.ADD_ACCOUNT) { adding = !adding }
        }
        walletLabels.forEach { w -> ChoicePill(w, selected = false, tag = "manual-txn.wallet.$w", enabled = false) {} }
    }
    if (walletLabels.isNotEmpty()) {
        Text(
            stringResource(Res.string.mtx_wallets_auto),
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
            modifier = Modifier.testTag(ManualTxnTags.WALLETS_NOTE),
        )
    }
    if (adding && onAddAccount != null) {
        NewAccountForm(currencies = currencies, onCreate = { name, ccy, balance ->
            if (onAddAccount(name, ccy, balance) != null) adding = false
        })
    }
}

/** Inline "+ Account": a manual cash account with its current balance. */
@Composable
private fun NewAccountForm(currencies: List<String>, onCreate: suspend (String, String, Double) -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    val dashboard = SettingsStore.fiatCurrency.value
    var ccy by remember { mutableStateOf(dashboard.code.takeIf { it in currencies } ?: currencies.first()) }
    var balanceText by remember { mutableStateOf("") }
    val balance = if (balanceText.isBlank()) 0.0 else parseDecimalInput(balanceText)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BhodlTheme.colors.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(Res.string.mtx_new_account_name)) },
            modifier = Modifier.fillMaxWidth().testTag(ManualTxnTags.NEW_ACCOUNT_NAME),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            currencies.forEach { c -> ChoicePill(c, ccy == c, "manual-txn.new-account.currency.$c") { ccy = c } }
        }
        OutlinedTextField(
            value = balanceText,
            onValueChange = { balanceText = filterDecimalChars(it) },
            singleLine = true,
            label = { Text(stringResource(Res.string.mtx_new_account_balance)) },
            modifier = Modifier.fillMaxWidth().testTag(ManualTxnTags.NEW_ACCOUNT_BALANCE),
        )
        TextButton(
            onClick = { scope.launch { onCreate(name.trim(), ccy, balance ?: 0.0) } },
            enabled = name.isNotBlank() && balance != null,
            modifier = Modifier.testTag(ManualTxnTags.NEW_ACCOUNT_CREATE),
        ) { Text(stringResource(Res.string.mtx_new_account_create)) }
    }
}

/** A rounded choice chip (direction, currency, account). */
@Composable
internal fun ChoicePill(label: String, selected: Boolean, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = BhodlTheme.typography.labelMedium,
        color = when {
            selected -> BhodlTheme.colors.brandPrimary
            enabled -> BhodlTheme.colors.textSecondary
            else -> BhodlTheme.colors.textTertiary
        },
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) BhodlTheme.colors.brandPrimary.copy(alpha = 0.14f) else BhodlTheme.colors.surfaceVariant)
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
    )
}

/** A category text field with the catalog drop-down (presets plus the user's own). */
@Composable
internal fun CategoryField(
    value: String,
    onValueChange: (String) -> Unit,
    knownCategories: List<String>,
    label: String,
    tag: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var menuFilter by remember { mutableStateOf(false) }
    val customColors = SettingsStore.categoryColors.value
    val color = categoryColorIndex(value, customColors)
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it); menuOpen = true; menuFilter = true },
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(stringResource(Res.string.trk_cat_dialog_hint)) },
        leadingIcon = if (value.isNotBlank()) {
            { CategoryDot(color?.let { CategoryPalette.color(it) } ?: BhodlTheme.colors.financialPending, size = 12) }
        } else {
            null
        },
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = { menuOpen = !menuOpen; menuFilter = false }) {
                Text(if (menuOpen) "▴" else "▾", color = BhodlTheme.colors.textSecondary)
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
    if (menuOpen) {
        CategoryChoiceList(
            choices = categoryChoices(knownCategories),
            query = value,
            filter = menuFilter,
            onPick = { onValueChange(it); menuOpen = false },
            onNew = { onValueChange(it); menuOpen = false },
        )
    }
}

internal object ChannelFilterTags {
    const val ALL: String = "channel-filter.all"
    const val WALLETS: String = "channel-filter.wallets"
    fun channel(walletId: String): String = "channel-filter.$walletId"
}

/**
 * Schema v8 — Transactions by channel: all, the wallets, or one
 * off-wallet channel (only the ones that have entries). Hidden while
 * there are no off-wallet entries.
 */
@Composable
internal fun ChannelFilterRow(
    events: List<io.bhodl.ffi.PaymentEvent>,
    accounts: List<ManualAccount>,
    selected: io.bhodl.desktop.data.ChannelFilter,
    onSelect: (io.bhodl.desktop.data.ChannelFilter) -> Unit,
) {
    val used = events.filter { ManualTxns.isManual(it.walletId) }.map { it.walletId }.toSet()
    if (used.isEmpty() && selected == io.bhodl.desktop.data.ChannelFilter.All) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChoicePill(stringResource(Res.string.mtx_filter_all), selected == io.bhodl.desktop.data.ChannelFilter.All, ChannelFilterTags.ALL) {
            onSelect(io.bhodl.desktop.data.ChannelFilter.All)
        }
        ChoicePill(stringResource(Res.string.mtx_filter_wallets), selected == io.bhodl.desktop.data.ChannelFilter.Wallets, ChannelFilterTags.WALLETS) {
            onSelect(io.bhodl.desktop.data.ChannelFilter.Wallets)
        }
        accounts.filter { it.walletId in used }.forEach { a ->
            val filter = io.bhodl.desktop.data.ChannelFilter.Channel(a.walletId)
            ChoicePill(a.label, selected == filter, ChannelFilterTags.channel(a.walletId)) { onSelect(filter) }
        }
    }
}
