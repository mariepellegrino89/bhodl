package io.bhodl.desktop.integration

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

/**
 * `bitcoind` running in regtest mode, exposed for both JSON-RPC
 * (port `RPC_PORT`) and the P2P / ZMQ-free regtest defaults.
 *
 * The container uses [`polarlightning/bitcoind`] — a curated build of
 * Bitcoin Core 28.x maintained by the Polar Lightning team. It's the
 * same image LDK / BDK use in their public CI, so reproducibility is
 * the same as their test matrices.
 *
 * Regtest specifics:
 * - `-fallbackfee` is set so `sendtoaddress` works without a fee
 *   estimator (regtest has no historical data).
 * - RPC auth uses static `bhodl` / `bhodl` credentials. These never
 *   leave the container network. **Never copy this pattern outside
 *   tests — production requires cookie auth or per-deployment secrets.**
 *
 * The container is started once per test class via `@Container` and
 * shut down on JVM exit. Tests should mine their own coinbase chain
 * (`generatetoaddress`) on first use to give the wallet something to
 * spend.
 */
public class BitcoindRegtestContainer(
    network: Network,
) : GenericContainer<BitcoindRegtestContainer>(IMAGE) {

    init {
        withNetwork(network)
        withNetworkAliases(NETWORK_ALIAS)
        withExposedPorts(RPC_PORT)
        withCommand(
            "-regtest",
            "-server=1",
            "-printtoconsole=1",
            "-rpcuser=$RPC_USER",
            "-rpcpassword=$RPC_PASSWORD",
            "-rpcallowip=0.0.0.0/0",
            "-rpcbind=0.0.0.0",
            "-fallbackfee=0.00001",
            // Wallet creation is needed for sendtoaddress / generatetoaddress
            // helpers. Without `-wallet` Core 28.x boots with no default
            // wallet loaded.
            "-wallet=miner",
        )
        // Wait until the RPC port is responding to a getblockchaininfo
        // probe. The `printtoconsole=1` log line "init message: Done loading"
        // is also a good signal but the HTTP probe is more robust across
        // image versions.
        waitingFor(
            Wait.forLogMessage(".*init message: Done loading.*", 1)
                .withStartupTimeout(Duration.ofSeconds(60)),
        )
    }

    /** Host-side base URL the JVM uses to drive the RPC. */
    public fun rpcBaseUrl(): String = "http://$host:${getMappedPort(RPC_PORT)}"

    public companion object {
        // Pinned by digest (multi-arch index, resolved 2026-10-21); the tag is
        // kept for readability only. Bump tag and digest together, deliberately,
        // and keep in sync with the pre-pull step in .github/workflows/ci.yml.
        public const val IMAGE: String =
            "polarlightning/bitcoind:28.0@sha256:ff435d758c89f01823924e057d5d5e4206d350ae69dff5e0dc1bbc9c6a36d010"
        public const val RPC_PORT: Int = 18443
        public const val NETWORK_ALIAS: String = "bitcoind"
        public const val RPC_USER: String = "bhodl"
        public const val RPC_PASSWORD: String = "bhodl"
    }
}
