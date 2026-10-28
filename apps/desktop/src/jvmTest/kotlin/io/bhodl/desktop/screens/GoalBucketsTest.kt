package io.bhodl.desktop.screens
import io.bhodl.desktop.domain.computeGoalCurrentSat
import io.bhodl.desktop.domain.investmentsTotalSat
import io.bhodl.desktop.domain.goalBucketBadgeLabel
import io.bhodl.desktop.domain.manualAssetsTotalSat
import io.bhodl.desktop.domain.manualAssetsByKindSat
import io.bhodl.desktop.domain.GoalBucket

import io.bhodl.ffi.TrackingInvestmentFfi
import io.bhodl.ffi.TrackingInvestmentLotFfi
import io.bhodl.ffi.TrackingManualAssetFfi
import io.bhodl.ffi.TrackingSavingsGoalFfi
import io.bhodl.ffi.WalletKindFfi
import io.bhodl.ffi.WalletRecordFfi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 7.2.4 — pure tests for the goal-bucket binding.
 *
 * The function under test ([computeGoalCurrentSat]) is the load-
 * bearing piece of the goal-to-NW slice fix: it turns a stored
 * `bucket_kind` + `bucket_ref` into the live sat value the progress
 * bar should show. Pinning the behaviour here keeps the
 * [SavingsGoalsCard] dialog free of math.
 */
class GoalBucketsTest {

    // ─── GoalBucket.fromWire ────────────────────────────────────────

    @Test
    fun `fromWire maps each known string`() {
        assertEquals(GoalBucket.MANUAL, GoalBucket.fromWire("manual"))
        assertEquals(GoalBucket.INVESTMENTS, GoalBucket.fromWire("investments"))
        assertEquals(GoalBucket.BITCOIN_CASH, GoalBucket.fromWire("bitcoin_cash"))
        assertEquals(GoalBucket.WALLET, GoalBucket.fromWire("wallet"))
        assertEquals(GoalBucket.MANUAL_ASSET, GoalBucket.fromWire("manual_asset"))
    }

    @Test
    fun `fromWire coerces null and unknown to MANUAL`() {
        // Downgrade safety: a UI older than the schema must not crash
        // and must surface the hand-entered current_sat rather than
        // silently miscompute.
        assertEquals(GoalBucket.MANUAL, GoalBucket.fromWire(null))
        assertEquals(GoalBucket.MANUAL, GoalBucket.fromWire(""))
        assertEquals(GoalBucket.MANUAL, GoalBucket.fromWire("future_bucket_v3"))
    }

    // ─── MANUAL ─────────────────────────────────────────────────────

    @Test
    fun `manual bucket returns persisted current_sat unchanged`() {
        // Manual = legacy semantics. The user typed it; we render it.
        val goal = sampleGoal(bucketKind = "manual", currentSat = 123_456UL)
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 999_999_999UL,
            watchBalances = mapOf("w1" to 1UL),
            investments = listOf(sampleInvestment(value = 1_000.0, quantity = 1.0)),
            fiatToSat = { (it * 1000).toLong().toULong() },
        )
        assertEquals(123_456UL, sat)
    }

    // ─── BITCOIN_CASH ───────────────────────────────────────────────

    @Test
    fun `bitcoin_cash bucket returns aggregate watch balance`() {
        val goal = sampleGoal(bucketKind = "bitcoin_cash")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 7_500_000UL,
            watchBalances = mapOf("w1" to 1_000_000UL, "w2" to 6_500_000UL),
            investments = emptyList(),
            fiatToSat = { null },
        )
        assertEquals(7_500_000UL, sat)
    }

    // ─── WALLET ─────────────────────────────────────────────────────

    @Test
    fun `wallet bucket returns balance for matching ref`() {
        val goal = sampleGoal(bucketKind = "wallet", bucketRef = "cold-storage")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 100_000UL,
            watchBalances = mapOf("hot" to 20_000UL, "cold-storage" to 80_000UL),
            investments = emptyList(),
            fiatToSat = { null },
        )
        assertEquals(80_000UL, sat)
    }

    @Test
    fun `wallet bucket returns zero when ref is missing from balances`() {
        // The bound wallet was removed from the registry. The goal is
        // intentionally not auto-zeroed in storage (so the user can
        // re-pair on the same wallet later); the *view* just renders 0.
        val goal = sampleGoal(bucketKind = "wallet", bucketRef = "removed-wallet")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 100_000UL,
            watchBalances = mapOf("hot" to 20_000UL),
            investments = emptyList(),
            fiatToSat = { null },
        )
        assertEquals(0UL, sat)
    }

    @Test
    fun `wallet bucket returns zero when ref is null`() {
        // Defensive: a malformed row (kind=wallet, ref=null) should
        // not crash. UI prevents this; the helper still tolerates it.
        val goal = sampleGoal(bucketKind = "wallet", bucketRef = null)
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 100_000UL,
            watchBalances = mapOf("w" to 50_000UL),
            investments = emptyList(),
            fiatToSat = { null },
        )
        assertEquals(0UL, sat)
    }

    // ─── INVESTMENTS ────────────────────────────────────────────────

    @Test
    fun `investments bucket sums fetched value when present`() {
        // 10 AAPL @ €150 + 5 IWDA @ €80 = €1900 → 19000 sat at the
        // test rate. Both have fetched_current_value, so manual is
        // ignored (mirrors InvestmentsCard policy).
        val goal = sampleGoal(bucketKind = "investments")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = listOf(
                sampleInvestment(quantity = 10.0, fetched = 150.0, manual = 999.0),
                sampleInvestment(quantity = 5.0, fetched = 80.0),
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertEquals(19_000UL, sat)
    }

    @Test
    fun `investments bucket falls back to manual when no fetched value`() {
        val goal = sampleGoal(bucketKind = "investments")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = listOf(sampleInvestment(quantity = 2.0, manual = 50.0)),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        // 2 * 50 = 100 fiat → 1000 sat
        assertEquals(1_000UL, sat)
    }

    @Test
    fun `investments bucket skips entries with no current value`() {
        val goal = sampleGoal(bucketKind = "investments")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = listOf(
                sampleInvestment(quantity = 1.0, fetched = null, manual = null),
                sampleInvestment(quantity = 1.0, fetched = 42.0),
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        // Only the second contributes: 1 * 42 = 42 → 420 sat
        assertEquals(420UL, sat)
    }

    @Test
    fun `investments bucket returns zero when price is not cached`() {
        // fiatToSat == null means PriceCache hasn't been hydrated yet.
        // Surfacing 0 (not crashing) lets the UI render "—" via the
        // surrounding badge until the next refresh tick.
        val goal = sampleGoal(bucketKind = "investments")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = listOf(sampleInvestment(quantity = 1.0, manual = 100.0)),
            fiatToSat = { null },
        )
        assertEquals(0UL, sat)
    }

    @Test
    fun `investments bucket returns zero when list is empty`() {
        val goal = sampleGoal(bucketKind = "investments")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = emptyList(),
            fiatToSat = { (it * 1000).toLong().toULong() },
        )
        assertEquals(0UL, sat)
    }

    // ─── investmentsTotalSat (Phase 7.3) ────────────────────────────

    @Test
    fun `investmentsTotalSat returns zero for empty list`() {
        val sat = investmentsTotalSat(
            investments = emptyList(),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertEquals(0UL, sat)
    }

    @Test
    fun `investmentsTotalSat prefers fetched over manual`() {
        // 10 shares × fetched €150 = €1500. Manual is set to a bogus
        // value to assert we ignore it (mirrors InvestmentsCard policy).
        val sat = investmentsTotalSat(
            investments = listOf(
                sampleInvestment(quantity = 10.0, fetched = 150.0, manual = 9_999.0),
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertEquals(15_000UL, sat)
    }

    @Test
    fun `investmentsTotalSat falls back to manual when no fetched value`() {
        val sat = investmentsTotalSat(
            investments = listOf(sampleInvestment(quantity = 2.0, manual = 50.0)),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertEquals(1_000UL, sat)
    }

    @Test
    fun `investmentsTotalSat returns zero when price not cached`() {
        // fiatToSat == null happens on cold start before PriceCache has
        // hydrated. UI is expected to render "—" via a separate
        // `investmentsPricingAvailable` flag rather than 0 sat, so the
        // user can tell the difference between "no investments" and
        // "investments exist but no price yet".
        val sat = investmentsTotalSat(
            investments = listOf(sampleInvestment(quantity = 1.0, manual = 100.0)),
            fiatToSat = { null },
        )
        assertEquals(0UL, sat)
    }

    @Test
    fun `investmentsTotalSat skips non-finite and negative values`() {
        // Defensive: a manual entry typo could land NaN/Inf in the DB
        // bypassing validate_finite (which runs only at FFI boundary
        // on save, not on read). Skipping at compute time avoids
        // poisoning the aggregate.
        val sat = investmentsTotalSat(
            investments = listOf(
                sampleInvestment(quantity = 1.0, manual = Double.NaN),
                sampleInvestment(quantity = 1.0, manual = Double.POSITIVE_INFINITY),
                sampleInvestment(quantity = 1.0, manual = -50.0),  // negative
                sampleInvestment(quantity = 1.0, manual = 42.0),   // valid
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        // Only the last contributes: 1 * 42 = 42 → 420 sat
        assertEquals(420UL, sat)
    }

    @Test
    fun `bitcoin_cash bucket does NOT include investments after 7-3 rename`() {
        // Phase 7.3 regression test: a goal bound to bitcoin_cash must
        // measure progress against BTC holdings only — even if the user
        // also has €5000 of stocks worth ~6.25M sat at the current rate,
        // the bucket value stays at the watch-balance number. Phase 7.3
        // renamed the helper param `totalAssetSat → bitcoinAssetSat` to
        // make this semantics enforceable by the type system.
        val goal = sampleGoal(bucketKind = "bitcoin_cash")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 3_000_000UL,  // 3M sat of BTC
            watchBalances = mapOf("w1" to 3_000_000UL),
            investments = listOf(sampleInvestment(quantity = 1.0, fetched = 5_000.0)),
            fiatToSat = { (it * 1000).toLong().toULong() },  // 1 fiat = 1000 sat
        )
        // Pure BTC, no inflation from the 5M sat of investments
        assertEquals(3_000_000UL, sat)
    }

    // ─── manualAssetsTotalSat + manualAssetsByKindSat (Phase 7.4) ───

    @Test
    fun `manualAssetsTotalSat returns zero for empty list`() {
        assertEquals(
            0UL,
            manualAssetsTotalSat(
                assets = emptyList(),
                fiatToSat = { (it * 10).toLong().toULong() },
            ),
        )
    }

    @Test
    fun `manualAssetsTotalSat sums multi-kind multi-row`() {
        val sat = manualAssetsTotalSat(
            assets = listOf(
                sampleManualAsset(kind = "cash", value = 5_000.0),
                sampleManualAsset(kind = "real_estate", value = 350_000.0),
                sampleManualAsset(kind = "other", value = 1_000.0),
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        // 5000 + 350000 + 1000 = 356000 fiat → 3_560_000 sat
        assertEquals(3_560_000UL, sat)
    }

    @Test
    fun `manualAssetsTotalSat skips NaN Inf and non-positive`() {
        val sat = manualAssetsTotalSat(
            assets = listOf(
                sampleManualAsset(value = Double.NaN),
                sampleManualAsset(value = Double.POSITIVE_INFINITY),
                sampleManualAsset(value = 0.0),
                sampleManualAsset(value = -100.0),
                sampleManualAsset(value = 42.0),  // valid
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertEquals(420UL, sat)
    }

    @Test
    fun `manualAssetsTotalSat returns zero when price not cached`() {
        assertEquals(
            0UL,
            manualAssetsTotalSat(
                assets = listOf(sampleManualAsset(value = 1_000.0)),
                fiatToSat = { null },
            ),
        )
    }

    @Test
    fun `manualAssetsByKindSat groups by kind and converts each`() {
        val byKind = manualAssetsByKindSat(
            assets = listOf(
                sampleManualAsset(kind = "cash", value = 1_000.0),
                sampleManualAsset(kind = "cash", value = 500.0),
                sampleManualAsset(kind = "real_estate", value = 200_000.0),
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        // cash: 1500 → 15000 ; real_estate: 200_000 → 2_000_000
        assertEquals(15_000UL, byKind["cash"])
        assertEquals(2_000_000UL, byKind["real_estate"])
        assertEquals(2, byKind.size)
    }

    @Test
    fun `manualAssetsByKindSat drops kinds with zero sat`() {
        // Aggregate that would round to 0 sat (or no valid rows for
        // that kind) gets dropped — the table treats absence as
        // "don't show this row".
        val byKind = manualAssetsByKindSat(
            assets = listOf(
                sampleManualAsset(kind = "cash", value = 1_000.0),
                sampleManualAsset(kind = "vehicle", value = -50.0),  // invalid
            ),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertTrue(byKind.containsKey("cash"))
        assertFalse(byKind.containsKey("vehicle"))
    }

    // ─── MANUAL_ASSET bucket (Phase 7.4) ────────────────────────────

    @Test
    fun `manual_asset bucket returns sat for matching ref`() {
        val asset = sampleManualAsset(id = 42L, value = 1_000.0)
        val goal = sampleGoal(bucketKind = "manual_asset", bucketRef = "42")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = emptyList(),
            manualAssets = listOf(asset),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertEquals(10_000UL, sat)
    }

    @Test
    fun `manual_asset bucket returns zero for missing ref`() {
        val goal = sampleGoal(bucketKind = "manual_asset", bucketRef = "999")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = emptyList(),
            manualAssets = listOf(sampleManualAsset(id = 1L, value = 1_000.0)),
            fiatToSat = { (it * 10).toLong().toULong() },
        )
        assertEquals(0UL, sat)
    }

    @Test
    fun `manual_asset bucket returns zero when fiatToSat null`() {
        // Cold start: price not cached yet. Same UX as the
        // investments bucket — UI is expected to render "—".
        val asset = sampleManualAsset(id = 7L, value = 100.0)
        val goal = sampleGoal(bucketKind = "manual_asset", bucketRef = "7")
        val sat = computeGoalCurrentSat(
            goal = goal,
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = emptyList(),
            manualAssets = listOf(asset),
            fiatToSat = { null },
        )
        assertEquals(0UL, sat)
    }

    @Test
    fun `manual_asset badge label resolves asset name`() {
        val asset = sampleManualAsset(id = 5L, name = "Bank EUR", value = 100.0)
        val goal = sampleGoal(bucketKind = "manual_asset", bucketRef = "5")
        assertEquals(
            "Asset · Bank EUR",
            goalBucketBadgeLabel(goal, wallets = emptyList(), manualAssets = listOf(asset)),
        )
    }

    @Test
    fun `manual_asset badge label is missing marker when asset is gone`() {
        val goal = sampleGoal(bucketKind = "manual_asset", bucketRef = "removed")
        assertEquals(
            "Asset (missing)",
            goalBucketBadgeLabel(goal, wallets = emptyList(), manualAssets = emptyList()),
        )
    }

    // ─── goalBucketBadgeLabel ───────────────────────────────────────

    @Test
    fun `badge label is null for manual bucket`() {
        // Manual goals show no AUTO chip — the input is hand-entered,
        // there's nothing to mark as "auto".
        assertNull(goalBucketBadgeLabel(sampleGoal(bucketKind = "manual"), emptyList()))
    }

    @Test
    fun `badge label is fixed string for investments and bitcoin_cash`() {
        assertEquals(
            "Investments",
            goalBucketBadgeLabel(sampleGoal(bucketKind = "investments"), emptyList()),
        )
        assertEquals(
            "Bitcoin cash",
            goalBucketBadgeLabel(sampleGoal(bucketKind = "bitcoin_cash"), emptyList()),
        )
    }

    @Test
    fun `badge label resolves wallet name from current registry`() {
        // Wallet was renamed after the binding — the badge follows the
        // new label without needing a stored copy on the goal row.
        val goal = sampleGoal(bucketKind = "wallet", bucketRef = "w1")
        val wallets = listOf(sampleWallet(id = "w1", label = "Cold Storage v2"))
        assertEquals("Wallet · Cold Storage v2", goalBucketBadgeLabel(goal, wallets))
    }

    @Test
    fun `badge label is missing marker when wallet ref is gone`() {
        val goal = sampleGoal(bucketKind = "wallet", bucketRef = "removed")
        val wallets = listOf(sampleWallet(id = "still-here", label = "X"))
        assertEquals("Wallet (missing)", goalBucketBadgeLabel(goal, wallets))
    }

    // ─── Test fixtures ──────────────────────────────────────────────

    // ─── H14: lot-managed positions use the effective quantity ─────

    @Test
    fun `investmentsTotalSat values lot-managed positions on lot quantity`() {
        // seed_investment_lot_from_aggregate zeroes the summary quantity;
        // the 3 + 2 units now live in lots.
        val inv = sampleInvestment(quantity = 0.0, fetched = 100.0)
        val lots = mapOf(1L to listOf(lot(qty = 3.0), lot(qty = 2.0)))
        val sat = investmentsTotalSat(
            investments = listOf(inv),
            fiatToSat = { (it * 1_000).toULong() },
            lotsByInvestmentId = lots,
        )
        assertEquals(500_000UL, sat) // 5 × 100 = 500 fiat
        // Without lots the zeroed row would count 0.
        assertEquals(0UL, investmentsTotalSat(listOf(inv), { (it * 1_000).toULong() }))
    }

    @Test
    fun `INVESTMENTS goal bucket uses lots too`() {
        val inv = sampleInvestment(quantity = 0.0, fetched = 10.0)
        val sat = computeGoalCurrentSat(
            goal = sampleGoal(bucketKind = "investments"),
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = listOf(inv),
            fiatToSat = { (it * 1_000).toULong() },
            lotsByInvestmentId = mapOf(1L to listOf(lot(qty = 4.0))),
        )
        assertEquals(40_000UL, sat)
    }

    // ─── M7: per-row currency ───────────────────────────────────────

    @Test
    fun `rateFor converts each row at its own currency`() {
        // 1 BTC = 50_000 EUR = 100_000 USD (fake rates).
        val rates = mapOf("EUR" to 50_000.0, "USD" to 100_000.0)
        val eur = sampleInvestment(fetched = 500.0)
        val usd = sampleInvestment(fetched = 500.0).copy(id = 2, costBasisCurrency = "USD")
        val sat = investmentsTotalSat(
            investments = listOf(eur, usd),
            fiatToSat = { error("legacy path must not be used") },
            rateFor = { rates[it] },
        )
        // 500 EUR = 0.01 BTC, 500 USD = 0.005 BTC.
        assertEquals(1_500_000UL, sat)
    }

    @Test
    fun `rows without a rate are left out (no rate policy)`() {
        val rates = mapOf("EUR" to 50_000.0)
        val assets = listOf(
            sampleManualAsset(id = 1, value = 500.0, currency = "EUR"),
            sampleManualAsset(id = 2, value = 999.0, currency = "JPY"),
        )
        assertEquals(1_000_000UL, manualAssetsTotalSat(assets, { null }, rateFor = { rates[it] }))
        val byKind = manualAssetsByKindSat(
            assets + sampleManualAsset(id = 3, kind = "real_estate", value = 5_000.0, currency = "eur"),
            { null },
            rateFor = { rates[it] },
        )
        assertEquals(mapOf("cash" to 1_000_000UL, "real_estate" to 10_000_000UL), byKind)
    }

    @Test
    fun `MANUAL_ASSET bucket converts at the asset currency`() {
        val rates = mapOf("EUR" to 50_000.0, "USD" to 100_000.0)
        val sat = computeGoalCurrentSat(
            goal = sampleGoal(bucketKind = "manual_asset", bucketRef = "7"),
            bitcoinAssetSat = 0UL,
            watchBalances = emptyMap(),
            investments = emptyList(),
            manualAssets = listOf(sampleManualAsset(id = 7, value = 1_000.0, currency = "USD")),
            fiatToSat = { error("legacy path must not be used") },
            rateFor = { rates[it] },
        )
        assertEquals(1_000_000UL, sat)
    }

    private fun lot(qty: Double): TrackingInvestmentLotFfi = TrackingInvestmentLotFfi(
        id = 1,
        investmentId = 1,
        quantity = qty,
        pricePerUnit = 1.0,
        feesFiat = 0.0,
        purchaseUnix = 0,
        notes = null,
        createdAtUnix = 0,
    )

    private fun sampleGoal(
        bucketKind: String,
        bucketRef: String? = null,
        currentSat: ULong = 0UL,
    ): TrackingSavingsGoalFfi = TrackingSavingsGoalFfi(
        id = 1,
        name = "test",
        targetSat = 1_000_000UL,
        currentSat = currentSat,
        color = "cyan",
        deadlineUnix = null,
        linkedWalletId = null,
        targetPctOfNetWorth = null,
        bucketKind = bucketKind,
        bucketRef = bucketRef,
        goalKind = "savings",
        targetDenomination = "sat",
        targetFiatAmount = null,
        targetFiatCurrency = null,
        createdAtUnix = 0,
    )

    private fun sampleInvestment(
        quantity: Double = 1.0,
        fetched: Double? = null,
        manual: Double? = null,
        value: Double? = null,
    ): TrackingInvestmentFfi = TrackingInvestmentFfi(
        id = 1,
        name = "test",
        assetType = "stock",
        ticker = "TEST",
        quantity = quantity,
        unit = "",
        costBasisFiat = 100.0,
        costBasisCurrency = "EUR",
        purchaseUnix = 0,
        manualCurrentValue = manual ?: value,
        fetchedCurrentValue = fetched,
        lastPricedAtUnix = null,
        notes = null,
        costBasisMethod = "fifo",
        createdAtUnix = 0,
    )

    private fun sampleWallet(id: String, label: String): WalletRecordFfi =
        WalletRecordFfi(
            id = id,
            label = label,
            kind = WalletKindFfi.WATCH_XPUB,
            descriptorPub = "wpkh(xpub.../0/*)",
            network = "signet",
            createdAtUnix = 0,
        )

    private fun sampleManualAsset(
        id: Long = 1L,
        name: String = "test",
        kind: String = "cash",
        value: Double = 100.0,
        currency: String = "EUR",
    ): TrackingManualAssetFfi = TrackingManualAssetFfi(
        id = id,
        name = name,
        kind = kind,
        valueFiat = value,
        currency = currency,
        displayLabel = null,
        notes = null,
        lastUpdatedUnix = 0,
        createdAtUnix = 0,
    )
}
