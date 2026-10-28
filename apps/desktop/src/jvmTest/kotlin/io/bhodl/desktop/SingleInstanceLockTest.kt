package io.bhodl.desktop

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** GAP-021 — a second instance on the same data dir is refused. */
class SingleInstanceLockTest {

    @Test
    fun `a held lock refuses a second instance until it is released`() {
        val dir = kotlin.io.path.createTempDirectory("bhodl-lock-").toFile()
        try {
            val first = assertNotNull(SingleInstanceLock.tryAcquire(dir))
            assertNull(SingleInstanceLock.tryAcquire(dir))
            first.close()
            SingleInstanceLock.tryAcquire(dir)!!.close()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `different data dirs do not block each other`() {
        val a = kotlin.io.path.createTempDirectory("bhodl-lock-a-").toFile()
        val b = kotlin.io.path.createTempDirectory("bhodl-lock-b-").toFile()
        try {
            val la = assertNotNull(SingleInstanceLock.tryAcquire(a))
            val lb = assertNotNull(SingleInstanceLock.tryAcquire(b))
            la.close()
            lb.close()
        } finally {
            a.deleteRecursively()
            b.deleteRecursively()
        }
    }
}
