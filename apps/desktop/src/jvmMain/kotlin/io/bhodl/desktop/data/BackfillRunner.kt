package io.bhodl.desktop.data

import io.bhodl.desktop.settings.EsploraEndpoint
import io.bhodl.desktop.wizard.RegistryStore
import io.bhodl.ffi.MigrationReportFfi
import io.bhodl.ffi.RegistryHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 8.3.1 — one-shot post-unlock orchestrator for the Phase 8.2
 * BTC-at-time backfill.
 *
 * Invoked from [io.bhodl.desktop.App] (or the Main lifecycle moment
 * equivalent) once the device PIN has been entered and the registry
 * is open. The actual work is delegated to
 * `RegistryHandle.phase82BackfillBtcAtTime`, which iterates the four
 * tracker tables and fills the new BTC snapshot columns added by
 * Phase 8.2. Audit 2026-10: it reuses the open handle's derived key (no
 * device PIN in Kotlin, no Argon2 re-run) and goes through the Tor proxy
 * when Tor is on.
 *
 * Idempotent by design: the Rust backend skips rows that already
 * carry a non-null value (counted under `already_done`), so calling
 * this on every app launch is harmless. The runner does NOT
 * implement its own gating — the source of truth is the backend's
 * skip logic.
 *
 * State is exposed through a [StateFlow] so a future debug surface
 * (or, optionally, a discreet "migrating snapshots…" chip) can
 * subscribe without polling.
 */
public object BackfillRunner {

    /** Sealed hierarchy of runner states. */
    public sealed class State {
        public object Idle : State()
        public object Running : State()
        public data class Done(val report: MigrationReportFfi, val durationMs: Long) : State()
        public data class Failed(val error: String) : State()
    }

    /** Source seam for the actual backfill work. Default delegates
     *  to the generated FFI. Tests inject a fake. */
    public fun interface BackfillTarget {
        @Throws(Exception::class)
        public fun run(registry: RegistryHandle, proxyUrl: String?): MigrationReportFfi
    }

    /** Default target: invokes `RegistryHandle.phase82BackfillBtcAtTime`. */
    public object FfiBackfillTarget : BackfillTarget {
        override fun run(registry: RegistryHandle, proxyUrl: String?): MigrationReportFfi =
            registry.phase82BackfillBtcAtTime(proxyUrl)
    }

    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Idle)
    public val state: StateFlow<State> get() = _state.asStateFlow()

    @Volatile
    private var target: BackfillTarget = FfiBackfillTarget

    /** R5 — handle to the in-flight backfill coroutine so the logout /
     *  wipe path can cancel it cooperatively (see [cancel]). */
    @Volatile
    private var job: Job? = null

    /** Inject a fake backfill target. Test-only. */
    public fun setTargetForTest(t: BackfillTarget) {
        target = t
    }

    /** Reset the target to the default FFI binding. */
    public fun resetTargetForTest() {
        target = FfiBackfillTarget
        _state.value = State.Idle
    }

    /**
     * Kick the backfill on [scope] dispatched on [Dispatchers.IO].
     * If no registry is open the call is a no-op (the user is not yet
     * unlocked).
     *
     * [registryProvider] / [proxyProvider] are seams for tests.
     */
    public fun runIfNeeded(
        scope: CoroutineScope,
        registryProvider: () -> RegistryHandle? = { RegistryStore.current() },
        proxyProvider: () -> String? = { EsploraEndpoint.currentProxy() },
    ) {
        job = scope.launch(Dispatchers.IO) {
            val registry = registryProvider() ?: return@launch
            // Guard against re-entrancy: if a previous launch is
            // still running, do not start another (each run walks every
            // tracker table and may hit the rate providers).
            val previous = _state.value
            if (previous is State.Running) return@launch
            _state.value = State.Running
            val t0 = System.currentTimeMillis()
            try {
                val report = target.run(registry, proxyProvider())
                // R5 — if the session was locked while the FFI call was
                // in flight ([cancel] invoked by RegistryStore.clear),
                // do NOT publish a Done state for a session the user has
                // already left.
                if (!isActive) return@launch
                val dt = System.currentTimeMillis() - t0
                _state.value = State.Done(report, dt)
            } catch (c: CancellationException) {
                // Cooperative cancellation — leave the state as set by
                // [cancel] (Idle) and propagate so the coroutine unwinds.
                throw c
            } catch (t: Throwable) {
                // Same guard as Done: a failure caused by the registry
                // being closed under us belongs to the session the user
                // already left, so the state stays as [cancel] set it.
                if (!isActive) return@launch
                _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * R5 — cooperatively cancel an in-flight backfill. Invoked by
     * [io.bhodl.desktop.wizard.RegistryStore.clear] on logout / wipe so
     * the background job does not keep publishing results for a session
     * that has just been closed.
     *
     * The actual FFI call is a blocking JNI hop that cannot be
     * interrupted mid-flight, but cancelling the [Job] ensures that once
     * it returns we skip publishing a Done state (see the `isActive`
     * guard above), and any subsequent suspension unwinds promptly.
     * Resets the visible state to Idle so a re-login starts clean.
     */
    public fun cancel() {
        job?.cancel()
        job = null
        if (_state.value is State.Running) {
            _state.value = State.Idle
        }
    }

    /** Test-only: synchronous variant returning the resulting state
     *  via the provided coroutine scope. Production code always uses
     *  [runIfNeeded]. */
    internal suspend fun runBlockingForTest(
        registry: RegistryHandle?,
        proxyUrl: String? = null,
    ): State {
        if (registry == null) return _state.value
        val previous = _state.value
        if (previous is State.Running) return previous
        _state.value = State.Running
        val t0 = System.currentTimeMillis()
        return try {
            val report = target.run(registry, proxyUrl)
            val dt = System.currentTimeMillis() - t0
            val s = State.Done(report, dt)
            _state.value = s
            s
        } catch (t: Throwable) {
            val s = State.Failed(t.message ?: t.javaClass.simpleName)
            _state.value = s
            s
        }
    }
}
