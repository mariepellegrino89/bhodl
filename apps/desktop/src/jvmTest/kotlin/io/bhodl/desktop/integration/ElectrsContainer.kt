package io.bhodl.desktop.integration

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import java.time.Duration

/**
 * `electrs` (mempool.space fork) running against the bitcoind regtest
 * container, exposing the Esplora-compatible HTTP REST API on
 * [`HTTP_PORT`].
 *
 * The mempool fork of electrs ships the `--http-addr` flag that turns
 * the binary into both an Electrum server AND an Esplora HTTP REST
 * server. That second face is what `wallet.syncWithEsplora(url)`
 * expects — it is byte-for-byte the same API mempool.space exposes
 * publicly under `/api/`, so swapping the URL is the only difference
 * between integration and production code paths.
 *
 * Container ordering: depends on a running [`BitcoindRegtestContainer`]
 * sharing the same Docker network and reachable at the network alias
 * declared by that container.
 */
public class ElectrsContainer(
    network: Network,
) : GenericContainer<ElectrsContainer>(IMAGE) {

    init {
        withNetwork(network)
        withNetworkAliases(NETWORK_ALIAS)
        withExposedPorts(HTTP_PORT, ELECTRUM_PORT)
        // The image's ENTRYPOINT is already `/bin/electrs`, so we pass
        // only the flags here (a leading `electrs` would be parsed as
        // a positional arg and crash the container with "Found argument
        // 'electrs' which wasn't expected").
        //
        // `--daemon-dir /tmp` points to an existing readable directory
        // inside the electrs image — even with `--jsonrpc-import`
        // electrs requires the dir to exist (it tries to stat the
        // cookie file there before falling back to the inline
        // `--cookie user:pass`). Without it the binary exits before
        // the HTTP listener comes up.
        withCommand(
            "--network", "regtest",
            "--daemon-dir", "/tmp",
            "--daemon-rpc-addr",
            "${BitcoindRegtestContainer.NETWORK_ALIAS}:${BitcoindRegtestContainer.RPC_PORT}",
            "--cookie",
            "${BitcoindRegtestContainer.RPC_USER}:${BitcoindRegtestContainer.RPC_PASSWORD}",
            "--http-addr", "0.0.0.0:$HTTP_PORT",
            "--electrum-rpc-addr", "0.0.0.0:$ELECTRUM_PORT",
            "--db-dir", "/tmp/electrs-db",
            // Allow CORS so the JVM can hit the REST endpoint from
            // localhost without browser-style preflight games.
            "--cors", "*",
            // jsonrpc-import keeps electrs in sync via Core's RPC
            // instead of reading the on-disk block files. Slower but
            // immune to the chain rewrites that regtest mining causes.
            "--jsonrpc-import",
            // verbose enough to see why a startup fails inside the
            // container without flooding the log on a happy path.
            "-vvv",
        )
        waitingFor(
            // electrs prints "serving Electrum RPC on" once it has
            // indexed the initial state and is ready to serve queries.
            // Wait on the HTTP listener log too so callers do not hit
            // a 502 race window.
            Wait.forLogMessage(".*Electrum RPC server running on.*", 1)
                .withStartupTimeout(Duration.ofSeconds(120)),
        )
    }

    /** Host-side base URL for the Esplora REST API (matches mempool.space `/api`). */
    public fun esploraApiUrl(): String = "http://$host:${getMappedPort(HTTP_PORT)}"

    public companion object {
        // mempool/electrs combines the Electrum protocol server with an
        // Esplora-compatible REST endpoint. Pinned tag so the test stays
        // reproducible across container registry mirrors.
        public const val IMAGE: String = "mempool/electrs:v3.2.0"
        public const val HTTP_PORT: Int = 3002
        public const val ELECTRUM_PORT: Int = 60401
        public const val NETWORK_ALIAS: String = "electrs"
    }
}
