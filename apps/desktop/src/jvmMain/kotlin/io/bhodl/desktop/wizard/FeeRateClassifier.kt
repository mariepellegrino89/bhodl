package io.bhodl.desktop.wizard

import io.bhodl.shared.resources.Res
import io.bhodl.shared.resources.onb_fee_rate_extreme
import io.bhodl.shared.resources.onb_fee_rate_high

/**
 * Classify a sat/vB fee rate into a coarse "is this safe?" bucket.
 *
 * Signet has been seen returning ~65 000 sat/vB through mempool.space
 * (already clamped to 500 sat/vB at the FFI boundary). Mainnet
 * congestion peaks have reached ~600 sat/vB during 2023's Ordinals
 * spike — well above what any honest user would deliberately pay.
 *
 * The two thresholds were picked to match what a typical Bitcoin
 * wallet user would consider unusual:
 * - Above [`HIGH_THRESHOLD_SAT_VB`] (100 sat/vB) the wallet shows
 *   an orange advisory: "this is much higher than typical".
 * - Above [`EXTREME_THRESHOLD_SAT_VB`] (500 sat/vB) the advisory
 *   turns red and (in a follow-up) requires a typed confirmation
 *   before the build kicks off.
 *
 * Both thresholds are SETTINGS-FREE knobs for now. We will expose
 * them in the Settings screen once the cost model gets nuanced
 * enough to need it.
 */
public object FeeRateClassifier {

    public const val HIGH_THRESHOLD_SAT_VB: Long = 100
    public const val EXTREME_THRESHOLD_SAT_VB: Long = 500

    public enum class Level {
        /** Within the typical signet / mainnet range. No advisory. */
        Normal,

        /** Higher than usual; worth a heads-up but the user may proceed. */
        High,

        /** Pathological — likely a typo or mempool feed glitch. */
        Extreme,
    }

    public fun classify(satPerVb: Long): Level = when {
        satPerVb >= EXTREME_THRESHOLD_SAT_VB -> Level.Extreme
        satPerVb >= HIGH_THRESHOLD_SAT_VB -> Level.High
        else -> Level.Normal
    }

    /**
     * Human-readable advisory string for a given rate. Returns `null`
     * when no advisory is needed (Normal case) so the caller can
     * conditionally hide the entire UI block.
     */
    public fun advisory(satPerVb: Long): String? = when (classify(satPerVb)) {
        Level.Normal -> null
        Level.High -> localizedText(Res.string.onb_fee_rate_high, satPerVb)
        Level.Extreme -> localizedText(Res.string.onb_fee_rate_extreme, satPerVb)
    }
}
