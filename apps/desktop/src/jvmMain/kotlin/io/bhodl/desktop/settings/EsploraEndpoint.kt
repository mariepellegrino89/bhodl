package io.bhodl.desktop.settings

import io.bhodl.desktop.wizard.WalletStore
import io.bhodl.ffi.Network

/**
 * Single source of truth for the Esplora REST URL the wallet hits.
 *
 * The built-in defaults point at mempool.space's public endpoints,
 * one per network. The user can override the URL from Settings
 * (e.g. their own electrs Tor onion, or a self-hosted mempool/electrs
 * instance) — when they do, [`current`] returns the override.
 *
 * Security review — the resolver is **network-aware**: with no
 * override, a mainnet wallet resolves to the mainnet endpoint and a
 * signet wallet to the signet one. Before this, every network
 * silently synced against the signet API, so a mainnet wallet showed
 * a zero balance and broadcast against the wrong chain. The active
 * network is read from [WalletStore.network] (set at unlock / wizard
 * completion; defaults to signet when no wallet is loaded).
 *
 * Every screen that previously hard-coded the URL now goes through
 * here so a single Settings change reaches Dashboard sync, fee
 * estimates, broadcast, and fee-bump simultaneously.
 *
 * The lookup is a plain function (not a Composable State<String>)
 * because most callers consume it inside `Dispatchers.IO` blocks
 * where a Compose snapshot read would cross a thread boundary.
 * SettingsStore's writes happen on the UI thread; the new value is
 * visible to the next IO-side read.
 */
public object EsploraEndpoint {

    /** mempool.space's public mainnet REST endpoint. */
    public const val DEFAULT_MAINNET: String = "https://mempool.space/api"

    /** mempool.space's public testnet4 REST endpoint. */
    public const val DEFAULT_TESTNET4: String = "https://mempool.space/testnet4/api"

    /**
     * Blockstream's public signet REST endpoint. Not mempool.space: in
     * October 2026 its signet API answered simple calls but stalled the
     * requests of a full wallet scan (3 of 3 live scans timed out while
     * blockstream.info completed 3 of 3).
     */
    public const val DEFAULT_SIGNET: String = "https://blockstream.info/signet/api"

    /**
     * Regtest has no public endpoint — default to a local electrs
     * Esplora REST server on its stock port. Anyone running regtest
     * overrides this from Settings anyway.
     */
    public const val DEFAULT_REGTEST: String = "http://127.0.0.1:3000"

    /** Built-in default for [network]. */
    public fun defaultFor(network: Network): String = when (network) {
        Network.MAINNET -> DEFAULT_MAINNET
        Network.TESTNET4 -> DEFAULT_TESTNET4
        Network.SIGNET -> DEFAULT_SIGNET
        Network.REGTEST -> DEFAULT_REGTEST
    }

    /**
     * Read the active endpoint for the wallet open in the workspace — the
     * user override for its network when set, otherwise the built-in
     * default for that network.
     */
    public fun current(): String = current(WalletStore.network())

    /**
     * Endpoint for an explicit [network] (audit 2026-10): watch wallets
     * and tracker syncs must use THEIR OWN network, not the open
     * wallet's — a mainnet watch wallet must never be synced
     * against a signet server (or vice versa).
     */
    public fun current(network: Network): String =
        SettingsStore.esploraUrlFor(network) ?: defaultFor(network)

    /** mempool.space's signet endpoint: the fallback of [DEFAULT_SIGNET]. */
    public const val FALLBACK_SIGNET: String = "https://mempool.space/signet/api"

    /**
     * Second server to try when a sync against [current] fails: only for
     * signet on the built-in default (a server the user chose is never
     * swapped behind their back). Public signet servers fail in different
     * ways — one stalls full scans, the other caps requests per hour.
     */
    public fun fallback(network: Network): String? =
        if (network == Network.SIGNET && SettingsStore.esploraUrlFor(network) == null) FALLBACK_SIGNET else null

    /**
     * Parse a registry row's network string ("mainnet", "signet",
     * "testnet4", "regtest"; a few aliases accepted). `null` for an
     * unknown value — callers must then NOT guess a network to sync with.
     */
    public fun networkOf(record: String): Network? = when (record.trim().lowercase()) {
        "mainnet", "bitcoin" -> Network.MAINNET
        "signet" -> Network.SIGNET
        "testnet4", "testnet" -> Network.TESTNET4
        "regtest" -> Network.REGTEST
        else -> null
    }

    /**
     * Active SOCKS proxy URL when Tor mode is on, otherwise `null`
     * (direct connection). Returned in the form the Esplora client
     * expects (`socks5h://host:port`).
     */
    public fun currentProxy(): String? =
        if (SettingsStore.torEnabled.value) SettingsStore.torProxyUrl.value else null
}
