package io.bhodl.desktop.prices

import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.ffi.RecomputeStatsFfi
import io.bhodl.ffi.RegistryHandle

/**
 * Stage 2 (BTC P&L hardening) — thin Kotlin façade over the registry's
 * historical-rate resolvers.
 *
 * The Rust side resolves the BTC/<currency> rate AT an event's date (lot
 * purchase, sell, manual-asset purchase) in the event's own currency,
 * reusing the Phase 8.2 backfill machinery.
 *
 * Audit 2026-10 — the resolvers are plain [RegistryHandle] methods: they
 * reuse the handle's already-derived key (no device PIN, no per-call
 * Argon2) and take the Tor proxy ([EsploraEndpoint.currentProxy]) so the
 * rate providers are never contacted over clearnet while Tor is on.
 *
 * All calls are BLOCKING (the resolver may hit the network) and must run
 * on `Dispatchers.IO`. They are best-effort: a network failure is
 * swallowed — the column stays NULL and the UI shows "no rate", to be
 * filled on a later retry / recompute. We deliberately never write a
 * fake/current rate here (that was the old bug).
 */
internal object TrackerBtcRate {

    /** Resolve `btc_per_unit_at_purchase` for a single lot from its
     *  purchase date. No-op when unresolvable. */
    fun resolveLot(registry: RegistryHandle, lotId: Long) {
        runCatching { registry.resolveLotBtcRate(lotId, EsploraEndpoint.currentProxy()) }
    }

    /** Resolve `btc_per_unit_at_sale` + disposals' `gain_loss_btc` for a
     *  sell from its sale date. No-op when unresolvable. */
    fun resolveSell(registry: RegistryHandle, sellId: Long) {
        runCatching { registry.resolveSellBtc(sellId, EsploraEndpoint.currentProxy()) }
    }

    /** Resolve `purchase_btc_at_time` for a manual asset from its
     *  purchase date. No-op when no purchase snapshot / unresolvable. */
    fun resolveManualAsset(registry: RegistryHandle, id: Long) {
        runCatching {
            registry.resolveManualAssetPurchaseBtc(id, EsploraEndpoint.currentProxy())
        }
    }

    /** Re-resolve every lot's BTC rate from its purchase date, OVERWRITING
     *  prior values and bypassing the rate cache (the one-shot repair).
     *  Returns the stats, or null when the call failed. */
    fun recomputeAllLots(registry: RegistryHandle): RecomputeStatsFfi? =
        runCatching { registry.recomputeAllLotBtcRates(EsploraEndpoint.currentProxy()) }.getOrNull()
}
