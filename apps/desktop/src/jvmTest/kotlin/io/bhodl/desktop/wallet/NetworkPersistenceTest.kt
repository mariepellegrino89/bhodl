package io.bhodl.desktop.wallet

import io.bhodl.ffi.Network
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [`NetworkPersistence`]. The encoding is small but the
 * fallback semantics matter: a missing or unparseable file MUST surface
 * as Signet (the only network the desktop app supported pre-2.5) so an
 * upgrade does not lock anyone out.
 */
class NetworkPersistenceTest {

    private lateinit var dir: File

    @BeforeTest
    fun setupDir() {
        dir = Files.createTempDirectory("bhodl-net-test-").toFile()
    }

    @AfterTest
    fun tearDownDir() {
        dir.deleteRecursively()
    }

    @Test
    fun `read on empty dir returns Signet`() {
        assertEquals(Network.SIGNET, NetworkPersistence.read(dir))
    }

    @Test
    fun `read parses every supported value`() {
        for (network in listOf(
            Network.MAINNET,
            Network.TESTNET4,
            Network.SIGNET,
            Network.REGTEST,
        )) {
            NetworkPersistence.write(dir, network)
            assertEquals(network, NetworkPersistence.read(dir))
        }
    }

    @Test
    fun `read trims whitespace and tolerates trailing newline`() {
        File(dir, NetworkPersistence.FILE_NAME).writeText("  REGTEST\n")
        assertEquals(Network.REGTEST, NetworkPersistence.read(dir))
    }

    @Test
    fun `read returns Signet for unknown content`() {
        File(dir, NetworkPersistence.FILE_NAME).writeText("liquid")
        assertEquals(Network.SIGNET, NetworkPersistence.read(dir))
    }

    @Test
    fun `write is atomic — no orphan tmp file`() {
        NetworkPersistence.write(dir, Network.TESTNET4)
        // The atomic-write strategy uses a sibling .tmp file; verify
        // it was renamed away and no debris remains.
        val tmp = File(dir, "${NetworkPersistence.FILE_NAME}.tmp")
        assertEquals(false, tmp.exists())
        assertEquals(true, File(dir, NetworkPersistence.FILE_NAME).exists())
    }

    @Test
    fun `serialise round-trips through parse for every variant`() {
        for (network in listOf(
            Network.MAINNET,
            Network.TESTNET4,
            Network.SIGNET,
            Network.REGTEST,
        )) {
            assertEquals(network, NetworkPersistence.parse(NetworkPersistence.serialise(network)))
        }
    }
}
