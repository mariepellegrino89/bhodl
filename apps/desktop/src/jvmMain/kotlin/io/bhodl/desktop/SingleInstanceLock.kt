package io.bhodl.desktop

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * GAP-021 — one BHODL process per data dir. Two instances on the same
 * `registry.sqlite` / `watch-state/` files would sync and persist the
 * same wallets concurrently (and fight over `settings.json`).
 *
 * An OS file lock on `<dataDir>/app.lock`: released by the OS when the
 * process dies, so a crash never leaves a stale lock behind.
 */
internal class SingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {

    override fun close() {
        runCatching { lock.release() }
        runCatching { channel.close() }
    }

    internal companion object {
        const val FILE_NAME: String = "app.lock"

        /** The lock, or null when another process (or this one) holds it. */
        fun tryAcquire(dataDir: File): SingleInstanceLock? {
            dataDir.mkdirs()
            val channel = RandomAccessFile(File(dataDir, FILE_NAME), "rw").channel
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: java.io.IOException) {
                null
            }
            if (lock == null) {
                channel.close()
                return null
            }
            return SingleInstanceLock(channel, lock)
        }
    }
}
