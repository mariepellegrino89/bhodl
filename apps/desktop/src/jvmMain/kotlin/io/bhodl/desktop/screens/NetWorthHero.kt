package io.bhodl.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.bhodl.desktop.AppClock
import io.bhodl.desktop.prices.FiatCurrency
import io.bhodl.desktop.prices.NetWorthHistoryClient.NwSnapshot
import io.bhodl.desktop.prices.PriceCache
import io.bhodl.desktop.settings.SettingsStore
import io.bhodl.desktop.ui.STALE_THRESHOLD_MINUTES
import io.bhodl.desktop.ui.Sparkline
import io.bhodl.desktop.ui.formatBtcLike
import io.bhodl.desktop.ui.ChartDataTable
import io.bhodl.desktop.ui.ChartTableToggleButton
import io.bhodl.desktop.ui.chartSemantics
import io.bhodl.desktop.ui.rememberAmountFormatter
import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.chart_name_net_worth
import org.jetbrains.compose.resources.stringResource
import io.bhodl.desktop.ui.formatFiatWithCurrency
import io.bhodl.desktop.ui.isStale
import io.bhodl.desktop.ui.money.Money
import io.bhodl.desktop.ui.money.MoneyDensity
import io.bhodl.desktop.ui.money.MoneyPrimary
import io.bhodl.desktop.ui.money.MoneySemantic
import io.bhodl.desktop.ui.money.MoneyValue
import io.bhodl.desktop.ui.money.LocalRevealAmounts
import io.bhodl.desktop.ui.money.RevealAmounts
import io.bhodl.desktop.ui.money.amountsMasked
import io.bhodl.desktop.ui.relativeTimeAgo
import io.bhodl.shared.resources.period_1y
import io.bhodl.shared.resources.period_30d
import io.bhodl.shared.resources.period_7d
import io.bhodl.shared.resources.period_90d
import io.bhodl.shared.resources.period_all
import io.bhodl.shared.resources.trk_hero_chip_failed
import io.bhodl.shared.resources.trk_hero_chip_fetch
import io.bhodl.shared.resources.trk_hero_chip_refreshing
import io.bhodl.shared.resources.trk_hero_chip_stale
import io.bhodl.shared.resources.trk_hero_enable_fiat
import io.bhodl.shared.resources.trk_hero_hold_to_reveal
import io.bhodl.shared.resources.trk_hero_loading_history
import io.bhodl.shared.resources.trk_hero_net_worth
import org.jetbrains.compose.resources.StringResource
import io.bhodl.ui.theme.BhodlTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Phase 8.3.2 — Net worth hero panel for the TrackerHome, in the new
 * Bitcoin Lens layout.
 *
 * Composition (top to bottom):
 *  1. Title row "NET WORTH" + period selector chips.
 *  2. Fiat refresh trust signal ("↻ EUR · 2m ago").
 *  3. **Hero amount** via the [Money] composable at [MoneyDensity.Hero]
 *     with [MoneyPrimary.Btc]: BTC headline + fiat secondary line. Tap
 *     does NOT cycle units (the legacy `AmountDisplayMode` cycle has
 *     been removed in the Bitcoin Lens migration). When the privacy
 *     auto-hide is on, the hero boots into a bullet silhouette and a
 *     long-press temporarily reveals the amount.
 *  4. Delta row: primary delta in BTC (`±sat` + `±%`), and the
 *     "EUR ↓ X% vs BTC" chip when a fiat history is available.
 *  5. Sparkline with a flat sat-on-y primary line and an optional
 *     dashed fiat overlay (toggle in Settings).
 *
 * The data plumbing (Esplora history + CoinGecko price history) is the
 * caller's responsibility — this panel renders whatever pre-computed
 * series it's given. Keeps the view dumb so it stays testable and the
 * caller can decide caching policy.
 */
@Composable
public fun NetWorthHero(
    /** Net worth in sat (assets − liabilities, see [TrackerNetWorth]);
     *  negative when liabilities exceed assets. */
    currentSat: Long,
    /** Today's net worth valued at each historical BTC/fiat rate
     *  ([netWorthFiatSeries]). Drives the fiat overlay and the
     *  "EUR vs BTC" chip. */
    historySatFiat: List<Pair<Long, Double>>,
    fiatCurrency: FiatCurrency,
    period: NetWorthPeriod,
    onPeriodChange: (NetWorthPeriod) -> Unit,
    modifier: Modifier = Modifier,
    // Phase 8.4 — true sat-on-y net-worth history reconstructed by the
    // Rust `net_worth_history` FFI. When non-empty, replaces the flat
    // sat placeholder line from Phase 8.3.2 with the actual movement of
    // NW-in-sat over the period. When empty (cold start, network
    // failure, fresh DB), the hero falls back to the 8.3.2 flat-line
    // behavior so nothing renders broken.
    nwHistory: List<NwSnapshot> = emptyList(),
    /** UX review 2026-10 (B5) — current fiat value of [currentSat] at
     *  the spot rate ([heroFiat]). When null the hero falls back to the
     *  last point of [historySatFiat]. */
    currentFiat: Double? = null,
    /** UX review 2026-10 (B5) — fiat change of the net worth over the
     *  period, from the reconstructed [nwHistory]. Null hides the tail
     *  instead of printing a fake "+€0.00". */
    periodDeltaFiat: Double? = null,
    /** UX review 2026-10 (B12) — Phase 9 decided ONE global tracker
     *  period selector; the hero no longer draws its own chips unless
     *  a standalone host opts in. */
    showPeriodSelector: Boolean = false,
) {
    val currentSatClamped: ULong = currentSat.coerceAtLeast(0L).toULong()
    // Phase 7.2 → UX review 2026-10 (item 13) — the hero follows the
    // app-wide privacy mode (Ctrl+Shift+H / footer eye icon). While
    // hidden it shows the masked silhouette and only reveals while the
    // user is long-pressing on the amount (a local RevealAmounts).
    val isHidden by SettingsStore.balancesHidden
    val showFiatOverlay by SettingsStore.showFiatOverlayOnNwChart
    var revealing by remember { mutableStateOf(false) }
    // UX review 2026-10 (item 15) — sparkline ↔ table toggle.
    var showTable by remember { mutableStateOf(false) }

    // Scrub state — when non-null, the hero shows this fiat value
    // instead of the latest one. Cleared when the cursor leaves the
    // sparkline.
    var scrubbedPoint by remember { mutableStateOf<Pair<Long, Double>?>(null) }

    val activeFiatValue: Double? = when {
        scrubbedPoint != null -> scrubbedPoint!!.second
        currentFiat != null -> currentFiat
        historySatFiat.isNotEmpty() -> historySatFiat.last().second
        else -> null
    }

    val fiatCurrencyCode: String? =
        if (fiatCurrency == FiatCurrency.NONE) null else fiatCurrency.code

    // Delta row inputs.
    //
    // Phase 8.4 — when [nwHistory] is non-empty, the BTC primary delta
    // is the genuine `last.totalSat - first.totalSat`, so the row
    // "breathes" with real on-chain events / new lots / new manual
    // assets / liability deltas. When [nwHistory] is empty, fall back
    // to the Phase 8.3.2 placeholder (0 / 0%). The
    // "EUR ↓ X% vs BTC" chip is always derived from the fiat history.
    val fiatVsBtc = computeFiatVsBtcChange(historySatFiat, currentSatClamped)
    val nwBtcDelta: NwBtcDelta = remember(nwHistory) { computeNwBtcDelta(nwHistory) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BhodlTheme.shapes.hero)
            .background(BhodlTheme.colors.surface)
            .border(1.dp, BhodlTheme.colors.outline, BhodlTheme.shapes.hero)
            .padding(BhodlTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.md),
    ) {
        // Title row + period selector on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.trk_hero_net_worth),
                style = BhodlTheme.typography.labelMedium,
                color = BhodlTheme.colors.textSecondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showPeriodSelector) PeriodSelector(period, onPeriodChange)
                ChartTableToggleButton(showTable = showTable, onToggle = { showTable = !showTable })
            }
        }

        // Fiat refresh trust signal — Bitcoin Lens still surfaces when
        // the BTC/EUR rate was last fetched. Hidden when fiat is fully
        // off (currency = NONE).
        if (fiatCurrency != FiatCurrency.NONE) {
            FiatRefreshChip(fiatCurrency = fiatCurrency)
        }

        // Hero amount — Bitcoin Lens layout.
        //
        // Gesture model:
        //  - privacy off → no gesture (tap is no longer a cycler).
        //  - privacy on  → HIDDEN. Long-press temporarily reveals via
        //                  the [Money] composable; release re-hides.
        //                  Permanent reveal = turn the privacy mode off.
        val gestureModifier = if (isHidden) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val release = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (release == null) {
                        // Long-press threshold hit — reveal until release.
                        revealing = true
                        waitForUpOrCancellation()
                        revealing = false
                    }
                    // Quick tap in privacy mode is a no-op.
                    @Suppress("UNUSED_VARIABLE") val _ignoredDown = down
                }
            }
        } else {
            Modifier
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(gestureModifier)
                .testTag(NetWorthHeroTags.HERO),
            verticalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.xs),
        ) {
            val effectiveHidden = isHidden && !revealing
            if (effectiveHidden) {
                // Bullet silhouette — match the visible amount's length
                // so the hero doesn't visually jolt on reveal/hide.
                Text(
                    text = bulletsHeroFor(
                        sat = currentSatClamped,
                        fiatValue = activeFiatValue,
                        currency = fiatCurrency,
                    ),
                    style = BhodlTheme.typography.amountHero,
                    color = BhodlTheme.colors.textPrimary,
                )
                Text(
                    text = stringResource(Res.string.trk_hero_hold_to_reveal),
                    style = BhodlTheme.typography.labelMedium,
                    color = BhodlTheme.colors.textTertiary,
                    modifier = Modifier.testTag(NetWorthHeroTags.HOLD_HINT),
                )
            } else {
                RevealAmounts {
                    Money(
                        value = MoneyValue(
                            sat = currentSat,
                            fiatAmount = activeFiatValue,
                            fiatCurrency = fiatCurrencyCode,
                            semantic = MoneySemantic.AtNow,
                        ),
                        density = MoneyDensity.Hero,
                        primary = MoneyPrimary.Btc,
                        showSecondary = true,
                    )
                }
            }
        }

        // Delta row — Bitcoin Lens. Suppressed while the hero is
        // hidden (the delta is a privacy leak even when the main
        // number is masked).
        if (!isHidden || revealing) {
            CompositionLocalProvider(LocalRevealAmounts provides revealing) {
                DeltaRow(
                    periodDeltaFiat = periodDeltaFiat,
                    fiatCurrency = fiatCurrency,
                    period = period,
                    fiatVsBtc = fiatVsBtc,
                    nwBtcDelta = nwBtcDelta,
                )
            }
        }

        // Sparkline + scrubbing. Bitcoin Lens primary = sat-on-y; the
        // overlay carries the fiat history when the setting is on.
        //
        // Phase 8.4 path: when [nwHistory] is non-empty, the primary
        // line draws the reconstructed NW-in-sat movement over the
        // period. The fiat overlay (when enabled) still rides on top
        // so the user can see both axes at once.
        //
        // Fallback (Phase 8.3.2 behavior): if [nwHistory] is empty but
        // a fiat history exists, draw a flat sat line spanning the fiat
        // x-range so the hero still shows the period bounds. If both
        // are empty, the placeholder box renders instead.
        val satPrimary: List<Pair<Long, Double>> = when {
            nwHistory.isNotEmpty() -> nwHistory.map { it.dateUnix to it.totalSat.toDouble() }
            historySatFiat.isNotEmpty() -> {
                val tStart = historySatFiat.first().first
                val tEnd = historySatFiat.last().first
                listOf(
                    tStart to currentSat.toDouble(),
                    tEnd to currentSat.toDouble(),
                )
            }
            else -> emptyList()
        }
        if (showTable && satPrimary.isNotEmpty()) {
            val titles = chartColumnTitles()
            ChartDataTable(
                seriesTable(
                    satPrimary.map { it.first to it.second.toLong() },
                    titles.netWorth,
                    titles,
                    rememberAmountFormatter(),
                ),
            )
        } else if (satPrimary.isNotEmpty()) {
            Sparkline(
                points = satPrimary,
                overlayPoints = if (showFiatOverlay && historySatFiat.isNotEmpty()) {
                    historySatFiat
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .chartSemantics(stringResource(Res.string.chart_name_net_worth))
                    .testTag(NetWorthHeroTags.SPARKLINE),
                // The primary line is in sat: map the scrubbed x back to
                // a FIAT value (the snapshot's fiat equivalent, or the
                // nearest fiat-series point) — the hero's secondary
                // line must never print a sat count as €.
                onScrub = { p ->
                    scrubbedPoint = p?.let { scrubFiatAt(it.first, nwHistory, historySatFiat) }
                },
            )
        } else {
            // Placeholder when history is loading or unavailable.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(BhodlTheme.colors.rowZebra),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (fiatCurrency == FiatCurrency.NONE) {
                        stringResource(Res.string.trk_hero_enable_fiat)
                    } else {
                        stringResource(Res.string.trk_hero_loading_history)
                    },
                    style = BhodlTheme.typography.bodyMedium,
                    color = BhodlTheme.colors.textTertiary,
                )
            }
        }
    }
}

/**
 * Phase 8.3.2 — delta row used by [NetWorthHero] in the Bitcoin Lens
 * layout.
 *
 * Two elements side by side:
 *  - **BTC primary delta**: `±sat` formatted + `±%`. NW-in-sat is held
 *    constant in this approximation (true for pure-BTC users), so the
 *    delta is most often zero. The text still renders so the row has a
 *    stable shape across periods.
 *  - **`EUR ↓ X% vs BTC` chip**: derived from [fiatVsBtc]. Subject is
 *    the fiat (not BTC). Colored muted negative when the rate rose
 *    (fiat lost), muted positive when the rate fell (fiat gained).
 */
@Composable
private fun DeltaRow(
    periodDeltaFiat: Double?,
    fiatCurrency: FiatCurrency,
    period: NetWorthPeriod,
    fiatVsBtc: FiatVsBtcChange?,
    nwBtcDelta: NwBtcDelta,
) {
    // BTC primary delta. Phase 8.4 fills this with the genuine
    // `last.totalSat - first.totalSat` when the reconstructed NW
    // history is available. When the FFI returned empty (network
    // failure, fresh DB), [nwBtcDelta] is the 0/0% placeholder so
    // the row keeps a stable shape.
    val deltaSat: Long = nwBtcDelta.deltaSat
    val deltaPctBtc: Double = nwBtcDelta.deltaPct
    val fmtAmount = rememberAmountFormatter()
    val masked = amountsMasked()
    val deltaSatLabel = run {
        val absRender = fmtAmount(abs(deltaSat))
        val sign = if (deltaSat == 0L && !masked) "±" else io.bhodl.desktop.ui.amountSign(deltaSat, masked = masked, minus = "-")
        "$sign$absRender"
    }
    val pctLabel = "${"%+.2f".format(deltaPctBtc)}% • ${period.label}"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$deltaSatLabel ($pctLabel)",
            style = BhodlTheme.typography.amountSmall.copy(fontWeight = FontWeight.Medium),
            color = BhodlTheme.colors.textSecondary,
            modifier = Modifier.testTag(NetWorthHeroTags.DELTA_BTC),
        )
        // Chip omitted when the fiat history is missing / single-point
        // / currentSat=0 — see [computeFiatVsBtcChange].
        if (fiatVsBtc != null && fiatCurrency != FiatCurrency.NONE) {
            FiatVsBtcChip(
                fiatCurrency = fiatCurrency,
                change = fiatVsBtc,
            )
        }
        // Tail-only fiat absolute reference for sanity check when both
        // ends of the period have a fiat number — quiet, secondary
        // tone.
        if (periodDeltaFiat != null && fiatCurrency != FiatCurrency.NONE) {
            val diff = periodDeltaFiat
            val sign = io.bhodl.desktop.ui.amountSign(diff, masked = masked, minus = "")
            Text(
                text = "$sign${formatFiatWithCurrency(diff, fiatCurrency, masked = masked)}",
                style = BhodlTheme.typography.amountSmall,
                color = BhodlTheme.colors.textTertiary,
                modifier = Modifier.testTag(NetWorthHeroTags.DELTA_FIAT_TAIL),
            )
        }
    }
}

/**
 * Phase 8.3.2 — the "EUR ↓ X% vs BTC" chip. The arrow points DOWN when
 * the fiat lost vs BTC (rate rose) and UP when the fiat gained (rate
 * fell). Muted-negative tone on a fiat loss (visible but not urgent —
 * it's a fact, not an alert) and muted-positive on a fiat gain.
 */
@Composable
private fun FiatVsBtcChip(
    fiatCurrency: FiatCurrency,
    change: FiatVsBtcChange,
) {
    val arrow = when (change.direction) {
        Direction.FiatDown -> "↓"
        Direction.FiatUp -> "↑"
        Direction.Flat -> "→"
    }
    val fg = when (change.direction) {
        Direction.FiatDown -> BhodlTheme.colors.financialNegativeMuted
        Direction.FiatUp -> BhodlTheme.colors.financialPositive
        Direction.Flat -> BhodlTheme.colors.textSecondary
    }
    val bg = when (change.direction) {
        Direction.FiatDown -> BhodlTheme.colors.financialNegativeMuted.copy(alpha = 0.14f)
        Direction.FiatUp -> BhodlTheme.colors.financialPositive.copy(alpha = 0.14f)
        Direction.Flat -> BhodlTheme.colors.surfaceVariant
    }
    val absPct = abs(change.deltaPct)
    val label = "${fiatCurrency.code} $arrow ${"%.1f".format(absPct)}% vs BTC"
    Box(
        modifier = Modifier
            .clip(BhodlTheme.shapes.pill)
            .background(bg)
            .padding(horizontal = BhodlTheme.spacing.sm, vertical = BhodlTheme.spacing.xs)
            .testTag(NetWorthHeroTags.CHIP_FIAT_VS_BTC),
    ) {
        Text(
            text = label,
            style = BhodlTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = fg,
        )
    }
}

/**
 * Phase 7.2 — trust-signal chip for the fiat overlay.
 *
 * Shows the relative age of the cached BTC/fiat price, with an inline
 * refresh affordance. Three visual states:
 *  - **fresh** (last fetch within [STALE_THRESHOLD_MINUTES]): neutral
 *    surfaceVariant background, secondary text.
 *  - **stale** (older than the threshold): muted amber background
 *    (financialPending) + a "stale" prefix so the user knows the fiat
 *    overlay may be off-market.
 *  - **error** (last fetch failed): muted red background +
 *    "fetch failed" — tap to retry.
 *
 * Click anywhere on the chip triggers [PriceCache.refreshNow].
 */
@Composable
private fun FiatRefreshChip(fiatCurrency: FiatCurrency) {
    val lastFetchUnix by PriceCache.lastFetchUnix
    val refreshing by PriceCache.refreshing
    val lastError by PriceCache.lastError
    val scope = rememberCoroutineScope()

    val nowSec = AppClock.nowUnix()
    val ageLabel = lastFetchUnix?.let { relativeTimeAgo(it, nowSec) }

    val isStaleNow = isStale(lastFetchUnix, nowSec)
    val hasError = lastError != null && lastFetchUnix == null

    val (bg, fg, text) = when {
        refreshing -> Triple(
            BhodlTheme.colors.surfaceVariant,
            BhodlTheme.colors.textSecondary,
            stringResource(Res.string.trk_hero_chip_refreshing),
        )
        hasError -> Triple(
            BhodlTheme.colors.financialNegativeMuted,
            BhodlTheme.colors.financialNegative,
            stringResource(Res.string.trk_hero_chip_failed),
        )
        ageLabel == null -> Triple(
            BhodlTheme.colors.surfaceVariant,
            BhodlTheme.colors.textTertiary,
            stringResource(Res.string.trk_hero_chip_fetch, fiatCurrency.code),
        )
        isStaleNow -> Triple(
            BhodlTheme.colors.financialPending.copy(alpha = 0.18f),
            BhodlTheme.colors.financialPending,
            stringResource(Res.string.trk_hero_chip_stale, ageLabel.orEmpty()),
        )
        else -> Triple(
            BhodlTheme.colors.surfaceVariant,
            BhodlTheme.colors.textSecondary,
            "↻ ${fiatCurrency.code} · $ageLabel",
        )
    }

    Box(
        modifier = Modifier
            .clip(BhodlTheme.shapes.pill)
            .background(bg)
            .clickable(enabled = !refreshing) {
                // A manual tap re-prices everything, including investment
                // quotes, regardless of the background auto-fetch toggle.
                scope.launch { PriceCache.refreshNow(forceInvestmentPrices = true) }
            }
            .padding(horizontal = BhodlTheme.spacing.sm, vertical = BhodlTheme.spacing.xs)
            .testTag(NetWorthHeroTags.FIAT_CHIP),
    ) {
        Text(
            text = text,
            style = BhodlTheme.typography.labelMedium,
            color = fg,
        )
    }
}

/**
 * The period selector chip row. Compact 11sp labels in a horizontal
 * row, the active period highlighted with the brand accent.
 */
@Composable
private fun PeriodSelector(
    selected: NetWorthPeriod,
    onPick: (NetWorthPeriod) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(BhodlTheme.spacing.xs)) {
        NetWorthPeriod.entries.forEach { p ->
            val isActive = p == selected
            Box(
                modifier = Modifier
                    .clip(BhodlTheme.shapes.pill)
                    .background(
                        if (isActive) BhodlTheme.colors.accentNeon.copy(alpha = 0.18f)
                        else BhodlTheme.colors.surfaceVariant,
                    )
                    .clickable { onPick(p) }
                    .padding(horizontal = BhodlTheme.spacing.sm, vertical = BhodlTheme.spacing.xs)
                    .testTag(NetWorthHeroTags.periodChip(p)),
            ) {
                Text(
                    text = p.label,
                    style = BhodlTheme.typography.labelMedium,
                    color = if (isActive) BhodlTheme.colors.accentNeon
                    else BhodlTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * Phase 7.2 — render the same digit count as the visible amount
 * would use, with bullets replacing every digit so the hero "shape"
 * (length, comma positions, decimal places, currency symbol) stays
 * stable across reveal / hide.
 *
 * Examples:
 *  - "€12,345.67"  → "€●●,●●●.●●"
 *  - "0.01234567 BTC" → "●.●●●●●●●● BTC"
 *
 * Exposed internal so tests can pin the silhouette without standing
 * up a Compose runtime.
 */
internal fun bulletsHeroFor(
    sat: ULong,
    fiatValue: Double?,
    currency: FiatCurrency,
): String {
    val visible = when {
        fiatValue != null && currency != FiatCurrency.NONE ->
            formatFiatWithCurrency(fiatValue, currency)
        else -> formatBtcLike(sat, asBtc = true)
    }
    return visible.map { ch -> if (ch.isDigit()) '●' else ch }.joinToString("")
}

/**
 * Phase 8.4 — BTC primary delta computed from the reconstructed
 * net-worth history. When the history is empty, the values are 0 /
 * 0.0 so the delta row keeps a stable shape (matching the Phase 8.3.2
 * placeholder behavior).
 *
 * Exposed `internal` so unit tests can pin the math without a Compose
 * runtime.
 */
internal data class NwBtcDelta(
    val deltaSat: Long,
    val deltaPct: Double,
)

/**
 * Pure helper — derives [NwBtcDelta] from a list of [NwSnapshot] in
 * chronological order. Falls back to (0, 0.0) when the list is empty,
 * has a single point, or when `first.totalSat == 0L` (avoids a
 * `/ 0` blowup; percentage is meaningless from a zero baseline).
 */
internal fun computeNwBtcDelta(history: List<NwSnapshot>): NwBtcDelta {
    if (history.size < 2) return NwBtcDelta(0L, 0.0)
    val first = history.first().totalSat
    val last = history.last().totalSat
    val deltaSat = last - first
    val deltaPct = if (first != 0L) {
        (last - first).toDouble() / first.toDouble() * 100.0
    } else {
        0.0
    }
    return NwBtcDelta(deltaSat = deltaSat, deltaPct = deltaPct)
}

/**
 * UX review 2026-10 (B5) — the hero's fiat figures.
 *
 * Root cause of the "€0.00 in dark vs a non-zero value in light" report: the
 * hero printed the LAST point of a fiat series reconstructed from the
 * per-wallet on-chain tx snapshots only (no investments / manual
 * assets / liabilities; empty for watch wallets whose tx list was not
 * synced yet → €0.00). That series arrives asynchronously (CoinGecko
 * price history), so a screenshot taken before it landed showed the
 * spot conversion (light) and one taken after showed €0.00 (dark) —
 * a load race, not a theme bug. Same for the "+€0.00" tail (first vs
 * last point of that zero series).
 *
 * Now:
 *  - [nowFiat] = net worth × the current spot rate, the same value
 *    the Portfolio fiat toggle shows. Null without a usable rate.
 *  - [periodDeltaFiat] = fiat change of the reconstructed NW history
 *    (last − first `fiatEquivalent`); null when the history has fewer
 *    than two points, so the tail is hidden rather than "+€0.00".
 */
internal data class HeroFiat(
    val nowFiat: Double?,
    val periodDeltaFiat: Double?,
)

internal fun heroFiat(
    netWorthSat: Long,
    spotRate: Double?,
    nwHistory: List<NwSnapshot>,
): HeroFiat {
    val now = spotRate
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { netWorthSat.toDouble() / 100_000_000.0 * it }
    val delta = if (nwHistory.size >= 2) {
        val d = nwHistory.last().fiatEquivalent - nwHistory.first().fiatEquivalent
        d.takeIf { it.isFinite() }
    } else {
        null
    }
    return HeroFiat(nowFiat = now, periodDeltaFiat = delta)
}

/**
 * UX review 2026-10 (B5) — today's net worth valued at each point of
 * the BTC/fiat price history (`timestampMs → fiat`). This is the
 * series [computeFiatVsBtcChange] assumes (NW-in-sat held constant,
 * only the rate moves) and the hero's fiat overlay draws. Replaces
 * the old on-chain-only reconstruction that ignored investments,
 * manual assets and liabilities.
 */
internal fun netWorthFiatSeries(
    netWorthSat: Long,
    priceHistory: List<io.bhodl.desktop.prices.HistoricalPricePoint>,
): List<Pair<Long, Double>> {
    val btc = netWorthSat.toDouble() / 100_000_000.0
    return priceHistory.map { p -> p.timestampMs to btc * p.price }
}

/**
 * Map a scrubbed sparkline point (x = timestamp of the PRIMARY sat
 * line) to the fiat value the hero should print. Prefers the
 * reconstructed snapshot's `fiatEquivalent`; falls back to the nearest
 * point of the fiat series. Returns `(timestamp, fiat)`, or null when
 * neither series has data.
 */
internal fun scrubFiatAt(
    timestamp: Long,
    nwHistory: List<NwSnapshot>,
    fiatSeries: List<Pair<Long, Double>>,
): Pair<Long, Double>? {
    nwHistory.firstOrNull { it.dateUnix == timestamp }?.let { return it.dateUnix to it.fiatEquivalent }
    if (fiatSeries.isEmpty()) return null
    return fiatSeries.minByOrNull { kotlin.math.abs(it.first - timestamp) }
}

/**
 * Net worth time windows exposed in the period selector. [days] is
 * passed to CoinGecko's `market_chart` endpoint; the granularity is
 * auto-determined server-side (5-min for 1d, hourly for 2-90d,
 * daily for >90d). "All" caps at ~10 years which is more than the
 * lifetime of any BHODL deployment.
 */
public enum class NetWorthPeriod(public val days: Int, public val labelRes: StringResource) {
    D7(7, Res.string.period_7d),
    D30(30, Res.string.period_30d),
    D90(90, Res.string.period_90d),
    Y1(365, Res.string.period_1y),
    ALL(3650, Res.string.period_all),
}

/** UX review 2026-10 (rec 3) — localized chip label, shared with the tracker selector. */
public val NetWorthPeriod.label: String
    @Composable get() = stringResource(labelRes)

public object NetWorthHeroTags {
    public const val HERO: String = "net-worth-hero.hero"
    public const val SPARKLINE: String = "net-worth-hero.sparkline"
    public const val HOLD_HINT: String = "net-worth-hero.hold-hint"
    public const val FIAT_CHIP: String = "net-worth-hero.fiat-chip"

    /** Phase 8.3.2 — BTC delta line in the Bitcoin Lens delta row. */
    public const val DELTA_BTC: String = "net-worth-hero.delta-btc"

    /** Phase 8.3.2 — small tail with the absolute fiat diff. */
    public const val DELTA_FIAT_TAIL: String = "net-worth-hero.delta-fiat-tail"

    /** Phase 8.3.2 — "EUR ↓ X% vs BTC" chip. */
    public const val CHIP_FIAT_VS_BTC: String = "net-worth-hero.chip-fiat-vs-btc"

    public fun periodChip(p: NetWorthPeriod): String =
        "net-worth-hero.period.${p.name.lowercase()}"
}
