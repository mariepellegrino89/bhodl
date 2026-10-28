package io.bhodl.desktop.data

import io.bhodl.ffi.MigrationReportFfi
import io.bhodl.ffi.NoPointer
import io.bhodl.ffi.RegistryHandle
import io.bhodl.ffi.TableBackfillStatsFfi
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 8.3.1 — orchestration tests for [BackfillRunner].
 *
 * The FFI target is replaced with a fake that returns a canned report
 * (or throws). We verify the state-flow transition Idle → Running →
 * Done|Failed and the idempotence promise (a second run on an "already
 * migrated" backend reports `already_done` == considered).
 */
class BackfillRunnerTest {

    /** Pointer-less fake handle: the target is faked, so it is never called into. */
    private val fakeRegistry = RegistryHandle(NoPointer)

    @AfterTest
    fun teardown() {
        BackfillRunner.resetTargetForTest()
    }

    private fun stats(
        considered: Int,
        fetched: Int,
        alreadyDone: Int,
        failed: Int,
    ): TableBackfillStatsFfi = TableBackfillStatsFfi(
        considered = considered.toUInt(),
        fetched = fetched.toUInt(),
        alreadyDone = alreadyDone.toUInt(),
        failed = failed.toUInt(),
    )

    private fun firstRunReport(): MigrationReportFfi = MigrationReportFfi(
        investmentLots = stats(considered = 3, fetched = 3, alreadyDone = 0, failed = 0),
        investmentSells = stats(considered = 1, fetched = 1, alreadyDone = 0, failed = 0),
        lotDisposals = stats(considered = 0, fetched = 0, alreadyDone = 0, failed = 0),
        manualAssets = stats(considered = 2, fetched = 2, alreadyDone = 0, failed = 0),
    )

    private fun idempotentReport(): MigrationReportFfi = MigrationReportFfi(
        investmentLots = stats(considered = 3, fetched = 0, alreadyDone = 3, failed = 0),
        investmentSells = stats(considered = 1, fetched = 0, alreadyDone = 1, failed = 0),
        lotDisposals = stats(considered = 0, fetched = 0, alreadyDone = 0, failed = 0),
        manualAssets = stats(considered = 2, fetched = 0, alreadyDone = 2, failed = 0),
    )

    @Test
    fun `runIfNeeded transitions Idle to Done with a happy-path target`() = runBlocking {
        var invocationCount = 0
        BackfillRunner.setTargetForTest { _, _ ->
            invocationCount += 1
            firstRunReport()
        }
        BackfillRunner.resetTargetForTest()
        BackfillRunner.setTargetForTest { _, _ ->
            invocationCount += 1
            firstRunReport()
        }
        // Use the test-only blocking shim so we don't have to dispatch
        // on Dispatchers.IO from a synchronous test.
        val state = BackfillRunner.runBlockingForTest(registry = fakeRegistry)
        assertEquals(1, invocationCount)
        assertTrue(state is BackfillRunner.State.Done, "expected Done, got $state")
        val done = state as BackfillRunner.State.Done
        assertEquals(3u, done.report.investmentLots.fetched)
        assertEquals(0u, done.report.investmentLots.alreadyDone)
    }

    @Test
    fun `runIfNeeded is idempotent - already_done equals considered on the second run`() =
        runBlocking {
            BackfillRunner.setTargetForTest { _, _ -> idempotentReport() }
            val state = BackfillRunner.runBlockingForTest(registry = fakeRegistry)
            assertTrue(state is BackfillRunner.State.Done)
            val report = (state as BackfillRunner.State.Done).report
            // No rows were freshly fetched in the second run — they
            // were already filled.
            assertEquals(0u, report.investmentLots.fetched)
            assertEquals(3u, report.investmentLots.alreadyDone)
            assertEquals(3u, report.investmentLots.considered)
        }

    @Test
    fun `runIfNeeded transitions to Failed on Registry exception`() = runBlocking {
        BackfillRunner.setTargetForTest { _, _ ->
            throw RuntimeException("simulated registry open failure")
        }
        val state = BackfillRunner.runBlockingForTest(registry = fakeRegistry)
        assertTrue(state is BackfillRunner.State.Failed, "expected Failed, got $state")
        val failed = state as BackfillRunner.State.Failed
        assertTrue(
            failed.error.contains("simulated registry open failure"),
            "expected error message to surface, got '${failed.error}'",
        )
    }

    @Test
    fun `runIfNeeded with no open registry is a no-op (state stays Idle or Done)`() = runBlocking {
        // After resetTargetForTest the state is Idle.
        BackfillRunner.resetTargetForTest()
        // No registry — runner must not invoke the target.
        var invoked = false
        BackfillRunner.setTargetForTest { _, _ ->
            invoked = true
            firstRunReport()
        }
        val state = BackfillRunner.runBlockingForTest(registry = null)
        assertEquals(false, invoked)
        assertTrue(state is BackfillRunner.State.Idle)
    }

    // ---- R5 — cooperative cancellation -----------------------------

    @Test
    fun `cancel resets an in-flight backfill to Idle`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        BackfillRunner.setTargetForTest { _, _ ->
            started.countDown()
            // Block until the test releases — mirrors a slow FFI call
            // still in flight when the user logs out.
            release.await()
            firstRunReport()
        }
        BackfillRunner.runIfNeeded(
            scope = this,
            registryProvider = { fakeRegistry },
            proxyProvider = { null },
        )
        assertTrue(
            started.await(5, TimeUnit.SECONDS),
            "target never started — runIfNeeded did not launch",
        )
        assertTrue(BackfillRunner.state.value is BackfillRunner.State.Running)

        BackfillRunner.cancel()
        assertTrue(
            BackfillRunner.state.value is BackfillRunner.State.Idle,
            "cancel() must reset a Running backfill to Idle",
        )
        // Let the blocked target unwind so runBlocking can return.
        release.countDown()
    }

    @Test
    fun `a failure after cancel stays Idle instead of Failed`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        BackfillRunner.setTargetForTest { _, _ ->
            started.countDown()
            release.await()
            try {
                // The registry was closed by the lock: the FFI call fails.
                throw IllegalStateException("registry closed")
            } finally {
                finished.countDown()
            }
        }
        BackfillRunner.runIfNeeded(
            scope = this,
            registryProvider = { fakeRegistry },
            proxyProvider = { null },
        )
        assertTrue(started.await(5, TimeUnit.SECONDS))
        BackfillRunner.cancel()
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertTrue(
            BackfillRunner.state.value is BackfillRunner.State.Idle,
            "a failure of the closed session must not surface as Failed",
        )
    }

    @Test
    fun `cancel is a no-op when idle`() {
        BackfillRunner.resetTargetForTest() // state = Idle
        BackfillRunner.cancel()
        assertTrue(BackfillRunner.state.value is BackfillRunner.State.Idle)
    }

    @Test
    fun `cancel does not clobber a completed Done state`() = runBlocking {
        BackfillRunner.setTargetForTest { _, _ -> firstRunReport() }
        val state = BackfillRunner.runBlockingForTest(registry = fakeRegistry)
        assertTrue(state is BackfillRunner.State.Done)
        BackfillRunner.cancel()
        assertTrue(
            BackfillRunner.state.value is BackfillRunner.State.Done,
            "cancel() must only reset a Running state, not a finished one",
        )
    }
}
