package io.bhodl.desktop.screens
import io.bhodl.desktop.ui.money.filterDecimalChars
import io.bhodl.desktop.ui.money.formatDecimalInput
import io.bhodl.desktop.ui.money.parseDecimalInput
import io.bhodl.desktop.domain.computeGoalCurrentSat
import io.bhodl.desktop.domain.goalBucketBadgeLabel
import io.bhodl.desktop.domain.GoalBucket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.DatePickerField
import io.bhodl.desktop.ui.SlideOverPanel
import io.bhodl.desktop.ui.displayAmount
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.desktop.ui.bhodlCard
import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ffi.WalletRecordFfi
import io.bhodl.ui.theme.BhodlTheme
import kotlin.math.roundToInt
import io.bhodl.shared.resources.Res
import org.jetbrains.compose.resources.stringResource
import io.bhodl.shared.resources.action_save
import io.bhodl.shared.resources.common_cancel
import io.bhodl.shared.resources.common_delete
import io.bhodl.shared.resources.trp_color_cyan
import io.bhodl.shared.resources.trp_color_green
import io.bhodl.shared.resources.trp_color_orange
import io.bhodl.shared.resources.trp_color_purple
import io.bhodl.shared.resources.trp_color_red
import io.bhodl.shared.resources.trp_color_yellow
import io.bhodl.shared.resources.trp_field_name
import io.bhodl.shared.resources.trp_goal_abs_hint_pct
import io.bhodl.shared.resources.trp_goal_currency_iso
import io.bhodl.shared.resources.trp_goal_current_auto
import io.bhodl.shared.resources.trp_goal_current_sat
import io.bhodl.shared.resources.trp_goal_deadline
import io.bhodl.shared.resources.trp_goal_denom_fiat
import io.bhodl.shared.resources.trp_goal_denomination
import io.bhodl.shared.resources.trp_goal_edit
import io.bhodl.shared.resources.trp_goal_fiat_no_rate
import io.bhodl.shared.resources.trp_goal_fiat_preview
import io.bhodl.shared.resources.trp_goal_kind_savings
import io.bhodl.shared.resources.trp_goal_kind_savings_sub
import io.bhodl.shared.resources.trp_goal_kind_sinking
import io.bhodl.shared.resources.trp_goal_kind_sinking_sub
import io.bhodl.shared.resources.trp_goal_manual_asset
import io.bhodl.shared.resources.trp_goal_name_hint
import io.bhodl.shared.resources.trp_goal_new
import io.bhodl.shared.resources.trp_goal_no_assets
import io.bhodl.shared.resources.trp_goal_no_wallets
import io.bhodl.shared.resources.trp_goal_nw_zero
import io.bhodl.shared.resources.trp_goal_pct_hint
import io.bhodl.shared.resources.trp_goal_pct_preview
import io.bhodl.shared.resources.trp_goal_section_bucket
import io.bhodl.shared.resources.trp_goal_section_color
import io.bhodl.shared.resources.trp_goal_section_goal
import io.bhodl.shared.resources.trp_goal_section_schedule
import io.bhodl.shared.resources.trp_goal_section_target
import io.bhodl.shared.resources.trp_goal_source
import io.bhodl.shared.resources.trp_goal_src_asset
import io.bhodl.shared.resources.trp_goal_src_bitcoin_cash
import io.bhodl.shared.resources.trp_goal_src_investments
import io.bhodl.shared.resources.trp_goal_src_manual
import io.bhodl.shared.resources.trp_goal_src_wallet
import io.bhodl.shared.resources.trp_goal_target_absolute
import io.bhodl.shared.resources.trp_goal_target_amount
import io.bhodl.shared.resources.trp_goal_target_pct
import io.bhodl.shared.resources.trp_goal_target_sat
import io.bhodl.shared.resources.trp_goal_target_type
import io.bhodl.shared.resources.trp_goal_type
import io.bhodl.shared.resources.trp_goal_wallet
import io.bhodl.shared.resources.trp_hint_1000

@Composable
internal fun GoalEditDialog(
    editing: TrackingSavingsGoalFfi?,
    /**
     * Phase 7.2.2 / 7.3 — full net worth in sat (= BTC + investments),
     * used to (a) convert the user's % input into a concrete
     * `target_sat` snapshot at save time and (b) render the "≈ X sat"
     * / "≈ X% NW" hint under the active input. When 0, the % mode is
     * locked off because the conversion would produce a meaningless
     * target. Phase 7.3 renamed this from `currentNetWorthSat` and
     * now expects the *total* — investments included — so that the
     * dialog's preview agrees with the dashboard hero.
     */
    totalNetWorthSat: ULong,
    /**
     * Phase 7.3 — BTC-only aggregate, used by the "Bitcoin cash"
     * bucket preview ("≈ X sat" next to the option) so that the
     * preview honors the bucket semantics (= only BTC, not total NW).
     */
    bitcoinAssetSat: ULong,
    /**
     * Phase 7.2.4 — context for the bucket picker preview. Watch
     * wallets list drives the "Specific wallet" dropdown; balances +
     * investments + fiatToSat drive the "≈ X sat" preview shown next
     * to each bucket option.
     */
    wallets: List<WalletRecordFfi>,
    watchBalances: Map<String, ULong>,
    investments: List<TrackingInvestmentFfi>,
    /**
     * Phase 7.4 — manual asset rows for the MANUAL_ASSET bucket
     * sub-picker (analogous to the WALLET sub-picker). Pass the
     * cached list from [io.bhodl.desktop.data.TrackerCache.manualAssets].
     */
    manualAssets: List<TrackingManualAssetFfi>,
    fiatToSat: (Double) -> ULong?,
    /** Lots per investment (effective quantity for the INVESTMENTS preview). */
    lotsByInvestmentId: Map<Long, List<io.bhodl.ffi.TrackingInvestmentLotFfi>> = emptyMap(),
    /** Per-currency BTC rate (production `PriceCache::rateFor`); null =
     *  legacy dashboard-currency conversion via [fiatToSat]. */
    rateFor: ((String) -> Double?)? = null,
    onDismiss: () -> Unit,
    /**
     * Phase 8.6 — extended with 3 trailing parameters: denomination
     * ("sat" | "fiat"), fiat amount (only when fiat), and fiat
     * currency code (only when fiat). The host wires these to
     * `Registry::set_savings_goal_denomination` after the
     * upsert/update returns so the row's denomination metadata lands
     * atomically with the rest of the goal record.
     */
    onSave: (
        name: String,
        targetSat: ULong,
        currentSat: ULong,
        color: String,
        targetPctOfNetWorth: UInt?,
        bucketKind: String,
        bucketRef: String?,
        goalKind: String,
        deadlineUnix: Long?,
        targetDenomination: String,
        targetFiatAmount: Double?,
        targetFiatCurrency: String?,
    ) -> Unit,
    onDelete: (id: Long) -> Unit,
) {
    var name by remember(editing?.id) { mutableStateOf(editing?.name ?: "") }
    var currentStr by remember(editing?.id) {
        mutableStateOf(editing?.currentSat?.toString() ?: "0")
    }
    var color by remember(editing?.id) { mutableStateOf(editing?.color ?: "cyan") }
    // Phase 7.2.2 — mutual-exclusive target inputs. The user picks
    // ABSOLUTE ("I want 1 BTC") or PERCENT ("I want 30% of NW").
    // Initial value: if the row already has a pct, the user originally
    // chose PERCENT; otherwise ABSOLUTE. With NW=0 the % mode would
    // produce a divide-by-zero (no NW, no allocation) so we lock to
    // ABSOLUTE and grey out the PERCENT chip.
    val percentDisabled = totalNetWorthSat == 0UL
    var targetKind by remember(editing?.id, percentDisabled) {
        val initial = when {
            percentDisabled -> TargetKind.ABSOLUTE
            editing?.targetPctOfNetWorth != null -> TargetKind.PERCENT
            else -> TargetKind.ABSOLUTE
        }
        mutableStateOf(initial)
    }
    var targetSatStr by remember(editing?.id) {
        mutableStateOf(editing?.targetSat?.toString() ?: "")
    }
    var pctStr by remember(editing?.id) {
        mutableStateOf(editing?.targetPctOfNetWorth?.toString() ?: "")
    }
    // Phase 7.2.4 — bucket state. Initial selection reflects the
    // persisted `bucket_kind` (or MANUAL when absent / unknown).
    // `bucketRef` is the wallet id when bucket is WALLET. Switching
    // back to MANUAL preserves the wallet ref so the user can toggle
    // without retyping (sticky-input pattern matches TargetKind).
    var bucket by remember(editing?.id) {
        mutableStateOf(GoalBucket.fromWire(editing?.bucketKind))
    }
    var bucketWalletRef by remember(editing?.id) {
        mutableStateOf(
            editing?.bucketRef
                ?: wallets.firstOrNull()?.id,
        )
    }
    // Phase 7.4 — separate ref state for the MANUAL_ASSET sub-picker.
    // Initial: the editing goal's bucket_ref when bucket=manual_asset,
    // otherwise default to the first available asset (or null if none
    // — UI then disables the option).
    var bucketManualAssetRef by remember(editing?.id) {
        mutableStateOf(
            if (editing?.bucketKind == GoalBucket.MANUAL_ASSET.wireValue) editing.bucketRef
            else manualAssets.firstOrNull()?.id?.toString(),
        )
    }

    // Phase 7.7 — goal kind (savings vs sinking) + deadline state.
    // The deadline is now a [DatePickerField] (epoch seconds), so the
    // state holds the Long? directly — no string parsing. The field
    // is mandatory only when the kind is SINKING.
    var goalKind by remember(editing?.id) {
        mutableStateOf(GoalKind.fromWire(editing?.goalKind))
    }
    var deadlineUnix by remember(editing?.id) {
        mutableStateOf(editing?.deadlineUnix)
    }

    // Phase 8.6 — denomination picker. Default `sat` for new goals;
    // for existing goals we read the persisted `target_denomination`
    // (the Rust migration backfills `'sat'` for legacy rows so this
    // never lands on a malformed value). Switching the picker clears
    // the opposite-side string so there's no auto-conversion (safer
    // — see plan §1.2 / §4.1).
    var denomination by remember(editing?.id) {
        mutableStateOf(GoalDenomination.fromWire(editing?.targetDenomination))
    }
    var fiatAmountStr by remember(editing?.id) {
        mutableStateOf(editing?.targetFiatAmount?.let { formatDecimalInput(it) } ?: "")
    }
    var fiatCurrencyStr by remember(editing?.id) {
        mutableStateOf(
            editing?.targetFiatCurrency
                ?: SettingsStore.fiatCurrency.value.code.takeIf { it.isNotBlank() }
                ?: "EUR",
        )
    }
    // Locale-aware parse (it_IT "350.000" = 350000) — see parseDecimalInput.
    val parsedFiatAmount: Double? = parseDecimalInput(fiatAmountStr)
        ?.takeIf { it > 0.0 }
    val normalizedFiatCurrency: String = fiatCurrencyStr.trim().uppercase()

    val current: ULong = currentStr.trim().toULongOrNull() ?: 0UL
    // Parse inputs depending on which mode the user is in. The other
    // field's string is kept around so toggling back to the original
    // mode preserves what was typed (matches Kubera-style sticky inputs).
    val absoluteTarget: ULong? = targetSatStr.trim().takeIf { it.isNotBlank() }?.toULongOrNull()
    val pct: UInt? = pctStr.trim().takeIf { it.isNotBlank() }
        ?.toUIntOrNull()
        ?.coerceAtMost(100u)
    // Derived target_sat: the absolute one when in ABSOLUTE mode, or
    // the live snapshot when in PERCENT mode. The PERCENT save still
    // persists a sat value (so a downgrade to a pre-7.2.4 client
    // doesn't see target_sat=0); the live UI now ignores it in favour
    // of the current NW × pct.
    val derivedTargetSat: ULong = when (targetKind) {
        TargetKind.ABSOLUTE -> absoluteTarget ?: 0UL
        TargetKind.PERCENT -> if (pct != null) targetSatFromPct(totalNetWorthSat, pct) else 0UL
    }
    val canSave = name.isNotBlank() && when {
        // Phase 8.6 — fiat-denominated goal: validate the fiat fields
        // independently of the sat-side TargetKind machinery. The
        // sat-target string can stay empty in this branch because
        // `target_sat` is ignored for fiat goals at render time.
        denomination == GoalDenomination.FIAT -> {
            parsedFiatAmount != null && normalizedFiatCurrency.length == 3
        }
        // Sat-denominated: keep the existing ABSOLUTE / PERCENT contract.
        targetKind == TargetKind.ABSOLUTE -> absoluteTarget != null && absoluteTarget > 0UL
        targetKind == TargetKind.PERCENT ->
            pct != null && pct > 0u && derivedTargetSat > 0UL
        else -> false
    } &&
        (bucket != GoalBucket.WALLET || bucketWalletRef != null) &&
        (bucket != GoalBucket.MANUAL_ASSET || bucketManualAssetRef != null) &&
        // Phase 7.7: sinking goals must have a picked deadline.
        (goalKind != GoalKind.SINKING || deadlineUnix != null)

    SlideOverPanel(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) stringResource(Res.string.trp_goal_new) else stringResource(Res.string.trp_goal_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GoalSectionHeader(stringResource(Res.string.trp_goal_section_goal))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(Res.string.trp_field_name)) },
                    placeholder = { Text(stringResource(Res.string.trp_goal_name_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(GoalTags.INPUT_NAME),
                )

                // Phase 7.7 — goal kind picker. Two chips: Savings
                // (open-ended) vs Sinking (target with deadline). The
                // deadline input below only renders when sinking is
                // selected; that's the contract the registry enforces.
                Text(
                    text = stringResource(Res.string.trp_goal_type),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GoalKindChip(
                        label = stringResource(Res.string.trp_goal_kind_savings),
                        sublabel = stringResource(Res.string.trp_goal_kind_savings_sub),
                        selected = goalKind == GoalKind.SAVINGS,
                        testTag = GoalTags.GOAL_KIND_SAVINGS,
                        onClick = { goalKind = GoalKind.SAVINGS },
                    )
                    GoalKindChip(
                        label = stringResource(Res.string.trp_goal_kind_sinking),
                        sublabel = stringResource(Res.string.trp_goal_kind_sinking_sub),
                        selected = goalKind == GoalKind.SINKING,
                        testTag = GoalTags.GOAL_KIND_SINKING,
                        onClick = { goalKind = GoalKind.SINKING },
                    )
                }
                GoalSectionHeader(stringResource(Res.string.trp_goal_section_target))
                // Phase 8.6 — denomination picker. Two chips, mutually
                // exclusive. SAT (default) keeps the existing
                // ABSOLUTE/PERCENT sat machinery. FIAT swaps in the
                // fiat amount + currency inputs and hides the sat
                // target box (the persisted `target_sat` is ignored
                // for fiat goals at render time — see GoalRow).
                // Switching the picker clears the opposite-side fields
                // so there's no surprise auto-conversion (plan §1.2).
                Text(
                    text = stringResource(Res.string.trp_goal_denomination),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TargetKindChip(
                        label = "sat",
                        selected = denomination == GoalDenomination.SAT,
                        enabled = true,
                        testTag = GoalTags.DENOM_SAT,
                        onClick = {
                            if (denomination != GoalDenomination.SAT) {
                                denomination = GoalDenomination.SAT
                                // Clear fiat-side fields so they don't
                                // silently round-trip on Save.
                                fiatAmountStr = ""
                            }
                        },
                    )
                    TargetKindChip(
                        label = stringResource(Res.string.trp_goal_denom_fiat),
                        selected = denomination == GoalDenomination.FIAT,
                        enabled = true,
                        testTag = GoalTags.DENOM_FIAT,
                        onClick = {
                            if (denomination != GoalDenomination.FIAT) {
                                denomination = GoalDenomination.FIAT
                                // Clear sat-side strings so the user
                                // re-enters a meaningful fiat figure.
                                targetSatStr = ""
                                pctStr = ""
                            }
                        },
                    )
                }

                if (denomination == GoalDenomination.SAT) {
                    // Phase 7.2.2 — target type toggle. Two pill chips,
                    // mutually exclusive. PERCENT is greyed out when
                    // NW = 0 since the conversion would produce 0.
                    Text(
                        text = stringResource(Res.string.trp_goal_target_type),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TargetKindChip(
                            label = stringResource(Res.string.trp_goal_target_absolute),
                            selected = targetKind == TargetKind.ABSOLUTE,
                            enabled = true,
                            testTag = GoalTags.TARGET_KIND_ABSOLUTE,
                            onClick = { targetKind = TargetKind.ABSOLUTE },
                        )
                        TargetKindChip(
                            label = stringResource(Res.string.trp_goal_target_pct),
                            selected = targetKind == TargetKind.PERCENT,
                            enabled = !percentDisabled,
                            testTag = GoalTags.TARGET_KIND_PERCENT,
                            onClick = { targetKind = TargetKind.PERCENT },
                        )
                    }
                    if (percentDisabled) {
                        Text(
                            text = stringResource(Res.string.trp_goal_nw_zero),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                    }

                    // Phase 7.2.2 — exactly one of these is visible at a
                    // time. Sticky-input pattern: typing in ABSOLUTE then
                    // toggling to PERCENT and back preserves both fields.
                    if (targetKind == TargetKind.ABSOLUTE) {
                        OutlinedTextField(
                            value = targetSatStr,
                            onValueChange = { targetSatStr = it.filter { ch -> ch.isDigit() } },
                            singleLine = true,
                            label = { Text(stringResource(Res.string.trp_goal_target_sat)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            modifier = Modifier.fillMaxWidth().testTag(GoalTags.INPUT_TARGET),
                        )
                        // Hint: how big is this target as a % of current NW?
                        val absHintPct = absoluteTarget?.let {
                            pctFromTargetSat(totalNetWorthSat, it)
                        }
                        if (absHintPct != null) {
                            Text(
                                text = stringResource(Res.string.trp_goal_abs_hint_pct, absHintPct.toString()),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.textTertiary,
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = pctStr,
                            onValueChange = { raw ->
                                // Keep only digits; cap visually at 3 chars
                                // — the value is also clamped at 100 below.
                                pctStr = raw.filter { ch -> ch.isDigit() }.take(3)
                            },
                            singleLine = true,
                            label = { Text(stringResource(Res.string.trp_goal_target_pct)) },
                            placeholder = { Text(stringResource(Res.string.trp_goal_pct_hint)) },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                            ),
                            modifier = Modifier.fillMaxWidth().testTag(GoalTags.INPUT_PCT_NW),
                        )
                        // Hint: what does that % translate to in sat at the
                        // current NW? Phase 7.2.4 — this is now a *live*
                        // preview: the rendered amount continues to track
                        // the NW after save, not a frozen snapshot.
                        if (pct != null && pct > 0u) {
                            Text(
                                text = stringResource(Res.string.trp_goal_pct_preview, displayAmount(derivedTargetSat)),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.textTertiary,
                            )
                        }
                    }
                } else {
                    // Phase 8.6 — fiat-denominated target. The user
                    // types an absolute amount (€1000) plus a 3-letter
                    // ISO 4217 code; we persist both. At render time
                    // we look up the rate via PriceCache.rateFor — if
                    // the cache holds no rate for this currency (today
                    // PriceCache is single-currency), the row renders
                    // "—" + a "BTC equiv unavailable" chip.
                    OutlinedTextField(
                        value = fiatAmountStr,
                        onValueChange = { raw ->
                            // Accept digits + a single decimal separator
                            // (. or ,). We normalise to "." on parse so
                            // the user can type either.
                            fiatAmountStr = filterDecimalChars(raw)
                        },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_goal_target_amount)) },
                        placeholder = { Text(stringResource(Res.string.trp_hint_1000)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(GoalTags.INPUT_FIAT_AMOUNT),
                    )
                    OutlinedTextField(
                        value = fiatCurrencyStr,
                        onValueChange = { raw ->
                            // Trim to 3 ASCII letters; uppercase happens
                            // in `normalizedFiatCurrency` at the parse
                            // step, the user sees their own casing.
                            fiatCurrencyStr = raw.filter { ch -> ch.isLetter() }.take(3)
                        },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_goal_currency_iso)) },
                        placeholder = { Text("EUR / USD / CHF") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(GoalTags.INPUT_FIAT_CURRENCY),
                    )
                    // Live preview: when the PriceCache has a rate for
                    // the requested currency, show the approximate sat
                    // equivalent so the user understands what they're
                    // committing to. When it doesn't, surface the same
                    // "BTC equiv unavailable" message the row will use
                    // — keeps expectation and reality aligned.
                    if (parsedFiatAmount != null && normalizedFiatCurrency.length == 3) {
                        val rate = PriceCache.rateFor(normalizedFiatCurrency)
                        if (rate != null && rate > 0.0) {
                            val previewSat = ((parsedFiatAmount / rate) * 100_000_000.0)
                                .toLong()
                                .coerceAtLeast(0L)
                                .toULong()
                            Text(
                                text = stringResource(Res.string.trp_goal_fiat_preview, displayAmount(previewSat)),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.textTertiary,
                            )
                        } else {
                            Text(
                                text = stringResource(Res.string.trp_goal_fiat_no_rate, normalizedFiatCurrency),
                                style = BhodlTheme.typography.labelMedium,
                                color = BhodlTheme.colors.textTertiary,
                            )
                        }
                    }
                }

                // Phase 7.7 — sinking-goal deadline. Calendar-backed
                // picker (epoch seconds) — no free-text parsing, so a
                // picked date is always valid; Save just requires one
                // to exist when the kind is SINKING.
                if (goalKind == GoalKind.SINKING) {
                    GoalSectionHeader(stringResource(Res.string.trp_goal_section_schedule))
                    DatePickerField(
                        label = stringResource(Res.string.trp_goal_deadline),
                        epochSeconds = deadlineUnix,
                        onPick = { deadlineUnix = it },
                        modifier = Modifier.fillMaxWidth(),
                        testTagId = GoalTags.INPUT_DEADLINE,
                    )
                }

                GoalSectionHeader(stringResource(Res.string.trp_goal_section_bucket))
                // Phase 7.2.4 — bucket picker. Replaces the legacy
                // hand-entered "Current progress (sat)" input for the
                // non-manual cases. The amount input below stays read-
                // only when bucket≠MANUAL, showing the live derived
                // value so the user knows exactly what the progress
                // bar will display.
                Text(
                    text = stringResource(Res.string.trp_goal_source),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textSecondary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BucketOptionRow(
                        label = stringResource(Res.string.trp_goal_src_manual),
                        selected = bucket == GoalBucket.MANUAL,
                        preview = null,
                        testTag = GoalTags.BUCKET_MANUAL,
                        onClick = { bucket = GoalBucket.MANUAL },
                    )
                    BucketOptionRow(
                        label = stringResource(Res.string.trp_goal_src_investments),
                        selected = bucket == GoalBucket.INVESTMENTS,
                        preview = previewLabel(
                            sat = computeGoalCurrentSat(
                                goal = previewGoal(GoalBucket.INVESTMENTS, null),
                                bitcoinAssetSat = bitcoinAssetSat,
                                watchBalances = watchBalances,
                                investments = investments,
                                fiatToSat = fiatToSat,
                                lotsByInvestmentId = lotsByInvestmentId,
                                rateFor = rateFor,
                            ),
                        ),
                        testTag = GoalTags.BUCKET_INVESTMENTS,
                        onClick = { bucket = GoalBucket.INVESTMENTS },
                    )
                    BucketOptionRow(
                        label = stringResource(Res.string.trp_goal_src_bitcoin_cash),
                        selected = bucket == GoalBucket.BITCOIN_CASH,
                        // Phase 7.3 — preview shows BTC only (the
                        // bucket's semantics), not the total NW.
                        preview = previewLabel(sat = bitcoinAssetSat),
                        testTag = GoalTags.BUCKET_BITCOIN_CASH,
                        onClick = { bucket = GoalBucket.BITCOIN_CASH },
                    )
                    BucketOptionRow(
                        label = stringResource(Res.string.trp_goal_src_wallet),
                        selected = bucket == GoalBucket.WALLET,
                        preview = bucketWalletRef?.let { ref ->
                            previewLabel(sat = watchBalances[ref] ?: 0UL)
                        },
                        testTag = GoalTags.BUCKET_WALLET,
                        onClick = { bucket = GoalBucket.WALLET },
                    )
                    // Phase 7.4 — bind a goal to a single manual asset
                    // (cash / real estate / etc.). Preview shows the
                    // sat equivalent of the selected asset at the
                    // current rate; "—" when none selected.
                    BucketOptionRow(
                        label = stringResource(Res.string.trp_goal_src_asset),
                        selected = bucket == GoalBucket.MANUAL_ASSET,
                        preview = bucketManualAssetRef?.let { ref ->
                            val asset = manualAssets.firstOrNull { it.id.toString() == ref }
                            if (asset != null) previewLabel(
                                sat = io.bhodl.desktop.domain.manualAssetsTotalSat(
                                    listOf(asset), fiatToSat, rateFor,
                                ),
                            ) else null
                        },
                        testTag = GoalTags.BUCKET_MANUAL_ASSET,
                        onClick = { bucket = GoalBucket.MANUAL_ASSET },
                    )
                }
                // Wallet picker shows only when bucket = WALLET.
                if (bucket == GoalBucket.WALLET) {
                    Text(
                        text = stringResource(Res.string.trp_goal_wallet),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    val watchOnly = wallets
                    if (watchOnly.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.trp_goal_no_wallets),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                    } else {
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            watchOnly.forEach { w ->
                                val selected = bucketWalletRef == w.id
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (selected) {
                                                BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                            } else BhodlTheme.colors.surfaceVariant,
                                        )
                                        .clickable { bucketWalletRef = w.id }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .testTag(GoalTags.bucketWalletTag(w.id)),
                                ) {
                                    Text(
                                        text = w.label,
                                        style = BhodlTheme.typography.labelMedium,
                                        color = if (selected) {
                                            BhodlTheme.colors.accentNeon
                                        } else BhodlTheme.colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }

                // Phase 7.4 — sub-picker for the MANUAL_ASSET bucket.
                // Same shape as the wallet sub-picker but lists manual
                // assets instead. Disabled state copy nudges the user
                // to add an asset first if the list is empty.
                if (bucket == GoalBucket.MANUAL_ASSET) {
                    Text(
                        text = stringResource(Res.string.trp_goal_manual_asset),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textSecondary,
                    )
                    if (manualAssets.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.trp_goal_no_assets),
                            style = BhodlTheme.typography.labelMedium,
                            color = BhodlTheme.colors.textTertiary,
                        )
                    } else {
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            manualAssets.forEach { a ->
                                val selected = bucketManualAssetRef == a.id.toString()
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(
                                            if (selected) {
                                                BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                                            } else BhodlTheme.colors.surfaceVariant,
                                        )
                                        .clickable { bucketManualAssetRef = a.id.toString() }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                        .testTag(GoalTags.bucketManualAssetTag(a.id)),
                                ) {
                                    Text(
                                        text = a.name,
                                        style = BhodlTheme.typography.labelMedium,
                                        color = if (selected) {
                                            BhodlTheme.colors.accentNeon
                                        } else BhodlTheme.colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }

                // Phase 7.2.4 — current progress field. MANUAL: editable
                // free input (legacy). Otherwise: read-only mirror of
                // the live bucket value so the user sees what will be
                // shown on the card.
                if (bucket == GoalBucket.MANUAL) {
                    OutlinedTextField(
                        value = currentStr,
                        onValueChange = { currentStr = it.filter { ch -> ch.isDigit() } },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.trp_goal_current_sat)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag(GoalTags.INPUT_CURRENT),
                    )
                } else {
                    val activeRef: String? = when (bucket) {
                        GoalBucket.WALLET -> bucketWalletRef
                        GoalBucket.MANUAL_ASSET -> bucketManualAssetRef
                        else -> null
                    }
                    val livePreview = computeGoalCurrentSat(
                        goal = previewGoal(bucket, activeRef),
                        bitcoinAssetSat = bitcoinAssetSat,
                        watchBalances = watchBalances,
                        investments = investments,
                        manualAssets = manualAssets,
                        fiatToSat = fiatToSat,
                        lotsByInvestmentId = lotsByInvestmentId,
                        rateFor = rateFor,
                    )
                    Text(
                        text = stringResource(Res.string.trp_goal_current_auto, displayAmount(livePreview)),
                        style = BhodlTheme.typography.labelMedium,
                        color = BhodlTheme.colors.textTertiary,
                        modifier = Modifier.testTag(GoalTags.CURRENT_PREVIEW),
                    )
                }
                GoalSectionHeader(stringResource(Res.string.trp_goal_section_color))
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("cyan", "orange", "green", "red", "yellow", "purple").forEach { k ->
                        val swatch = goalPaletteColor(k)
                        val selected = color == k
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) swatch.copy(alpha = 0.18f)
                                    else BhodlTheme.colors.surface)
                                .clickable { color = k }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(swatch)
                                        .padding(6.dp),
                                ) {}
                                Text(
                                    text = goalColorLabel(k),
                                    style = BhodlTheme.typography.labelMedium,
                                    color = if (selected) swatch
                                    else BhodlTheme.colors.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (canSave) {
                        // Phase 8.6 — for fiat-denominated goals we
                        // persist `target_sat = 0` (and `target_pct =
                        // null`) because the effective sat target is
                        // recomputed live from the fiat amount / rate.
                        // For sat-denominated goals the existing
                        // ABSOLUTE/PERCENT machinery wins as before.
                        val savedTargetSat: ULong = when (denomination) {
                            GoalDenomination.FIAT -> 0UL
                            GoalDenomination.SAT -> derivedTargetSat
                        }
                        val savedPct: UInt? = when (denomination) {
                            GoalDenomination.FIAT -> null
                            GoalDenomination.SAT ->
                                if (targetKind == TargetKind.PERCENT) pct else null
                        }
                        // For non-manual buckets we still write a
                        // sensible current_sat to the registry: the
                        // last computed value at save time. Acts as a
                        // fallback for offline reads (e.g. before the
                        // first refresh repopulates the cache).
                        val savedRef: String? = when (bucket) {
                            GoalBucket.WALLET -> bucketWalletRef
                            GoalBucket.MANUAL_ASSET -> bucketManualAssetRef
                            else -> null
                        }
                        val savedCurrentSat = if (bucket == GoalBucket.MANUAL) {
                            current
                        } else {
                            computeGoalCurrentSat(
                                goal = previewGoal(bucket, savedRef),
                                bitcoinAssetSat = bitcoinAssetSat,
                                watchBalances = watchBalances,
                                investments = investments,
                                manualAssets = manualAssets,
                                fiatToSat = fiatToSat,
                                lotsByInvestmentId = lotsByInvestmentId,
                                rateFor = rateFor,
                            )
                        }
                        // Phase 7.7: persist a deadline only when the
                        // user has explicitly opted into SINKING. A
                        // SAVINGS goal may still carry a deadline if
                        // the user picked one before switching kinds,
                        // but we drop it to keep the row's semantics
                        // unambiguous.
                        val savedDeadline = if (goalKind == GoalKind.SINKING) deadlineUnix
                        else null
                        // Phase 8.6 — denomination payload. For SAT we
                        // null out the fiat fields (the Rust setter
                        // enforces the same invariant on the way in
                        // — see `set_savings_goal_denomination`).
                        val savedFiatAmount: Double? = when (denomination) {
                            GoalDenomination.FIAT -> parsedFiatAmount
                            GoalDenomination.SAT -> null
                        }
                        val savedFiatCurrency: String? = when (denomination) {
                            GoalDenomination.FIAT -> normalizedFiatCurrency
                            GoalDenomination.SAT -> null
                        }
                        onSave(
                            name.trim(),
                            savedTargetSat,
                            savedCurrentSat,
                            color,
                            savedPct,
                            bucket.wireValue,
                            savedRef,
                            goalKind.wireValue,
                            savedDeadline,
                            denomination.wireValue,
                            savedFiatAmount,
                            savedFiatCurrency,
                        )
                    }
                },
                enabled = canSave,
                modifier = Modifier.testTag(GoalTags.SAVE),
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (editing != null) {
                    TextButton(
                        onClick = { onDelete(editing.id) },
                        modifier = Modifier.testTag(GoalTags.DELETE),
                    ) { Text(stringResource(Res.string.common_delete)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
            }
        },
    )
}

/**
 * Phase 7.2.4 — radio-style row for the bucket picker. Single line,
 * left-aligned label, right-aligned "≈ X sat" preview. Selected
 * state mirrors the [TargetKindChip] but the wider footprint suits
 * the longer labels here.
 */
@Composable
private fun BucketOptionRow(
    label: String,
    selected: Boolean,
    preview: String?,
    testTag: String,
    onClick: () -> Unit,
) {
    val accent = BhodlTheme.colors.accentNeon
    val bg = if (selected) accent.copy(alpha = 0.18f) else BhodlTheme.colors.surfaceVariant
    val fg = if (selected) accent else BhodlTheme.colors.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = fg,
            modifier = Modifier.weight(1f),
        )
        if (preview != null) {
            Text(
                text = preview,
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textTertiary,
            )
        }
    }
}

/** Localized name of a goal palette colour key ("cyan", …). */
@Composable
private fun goalColorLabel(key: String): String = when (key) {
    "cyan" -> stringResource(Res.string.trp_color_cyan)
    "orange" -> stringResource(Res.string.trp_color_orange)
    "green" -> stringResource(Res.string.trp_color_green)
    "red" -> stringResource(Res.string.trp_color_red)
    "yellow" -> stringResource(Res.string.trp_color_yellow)
    "purple" -> stringResource(Res.string.trp_color_purple)
    else -> key.replaceFirstChar { it.uppercaseChar() }
}

/**
 * Format a sat amount for the bucket picker preview. @Composable
 * because [displayAmount] reads the user's BTC unit preference from
 * [io.bhodl.desktop.settings.SettingsStore]; using it here keeps the
 * preview consistent with what the row will later show.
 */
@Composable
private fun previewLabel(sat: ULong): String = "≈ ${displayAmount(sat)}"

/**
 * Build a throwaway goal record that carries just enough fields for
 * [computeGoalCurrentSat]. Used twice in the dialog: once to drive
 * each bucket-option preview, once to compute the read-only "current
 * progress" mirror under the bucket section.
 */
private fun previewGoal(bucket: GoalBucket, ref: String?): TrackingSavingsGoalFfi =
    TrackingSavingsGoalFfi(
        id = 0,
        name = "",
        targetSat = 0UL,
        currentSat = 0UL,
        color = "cyan",
        deadlineUnix = null,
        linkedWalletId = null,
        targetPctOfNetWorth = null,
        bucketKind = bucket.wireValue,
        bucketRef = ref,
        goalKind = GoalKind.SAVINGS.wireValue,
        targetDenomination = GoalDenomination.SAT.wireValue,
        targetFiatAmount = null,
        targetFiatCurrency = null,
        createdAtUnix = 0,
    )

/**
 * Phase 7.2.2 — pill chip used by the target-kind toggle. Single
 * accent color tracks the active state; `enabled=false` greys it
 * out (used when net worth is 0 and the PERCENT mode would yield
 * a meaningless target).
 */
@Composable
private fun TargetKindChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val accent = BhodlTheme.colors.accentNeon
    val bg = when {
        !enabled -> BhodlTheme.colors.surfaceVariant.copy(alpha = 0.4f)
        selected -> accent.copy(alpha = 0.18f)
        else -> BhodlTheme.colors.surfaceVariant
    }
    val fg = when {
        !enabled -> BhodlTheme.colors.textTertiary
        selected -> accent
        else -> BhodlTheme.colors.textSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium,
            color = fg,
        )
    }
}

/**
 * Phase 7.7 — pill chip for the Savings / Sinking picker. Has a
 * sublabel ("open-ended" / "target by date") so the two options are
 * distinguishable without a tooltip.
 */
@Composable
private fun GoalKindChip(
    label: String,
    sublabel: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val accent = BhodlTheme.colors.accentNeon
    val bg = if (selected) accent.copy(alpha = 0.18f) else BhodlTheme.colors.surfaceVariant
    val fg = if (selected) accent else BhodlTheme.colors.textSecondary
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(testTag),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = fg,
        )
        Text(
            text = sublabel,
            style = BhodlTheme.typography.labelMedium,
            color = BhodlTheme.colors.textTertiary,
        )
    }
}

/**
 * Small uppercase section header used to group the [GoalEditDialog]
 * form (GOAL / TARGET / SCHEDULE / BUCKET / COLOR) now that the
 * slide-over panel gives the form full-height room.
 */
@Composable
private fun GoalSectionHeader(label: String) {
    Text(
        text = label,
        style = BhodlTheme.typography.labelMedium,
        color = BhodlTheme.colors.textSecondary,
        modifier = Modifier.padding(top = 8.dp),
    )
}
