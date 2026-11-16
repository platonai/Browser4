package ai.platon.pulsar.coding

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-flight coordination for Maven builds in one workspace (plan C1).
 *
 * Two builds of one workspace write into the same `target/` directories, so they must never
 * run at the same time (risk D5 in
 * [docs-dev/copilot/20261115-agent-parallel-work-evaluation.md]). This registry is the one
 * place that decides who runs:
 *
 * - a build whose inputs are **already being built** joins that flight — one Maven, one
 *   answer, for every caller asking for the same tree;
 * - a build for **different inputs** waits for the running one instead of racing it
 *   (`target/` is single-tenant);
 * - a **speculative** flight (started by [schedule] while the model was thinking) is
 *   cancelled when a write invalidates its sources — the work is simply no longer wanted.
 *   A caller's own flight is never cancelled: a real tool call must not be killed by
 *   another tool call.
 *
 * The speculative half is opt-in ([enabled], `-Dbrowser4.agent.build.speculative`): the
 * single-flight safety above is always on, because it prevents corruption rather than
 * saving time.
 *
 * @param scope where [schedule] runs the background build; cancelled by `close` semantics
 *   of the owner, since a speculative build must never outlive the task that started it
 * @param enabledProvider read on every access, so a JVM property change takes effect
 * @param clock injected for deterministic tests
 */
class SpeculativeBuild(
    private val scope: CoroutineScope,
    private val enabledProvider: () -> Boolean = Companion::enabled,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(SpeculativeBuild::class.java)

    /** One build in flight for one workspace. */
    class Flight internal constructor(
        /** `BuildFingerprint.id` — the content digest of the sources being built. */
        val fingerprintId: String,
        /** Started by [schedule] (nobody is waiting on it yet), not by a tool call. */
        val speculative: Boolean,
        val startedAt: Long,
    ) {
        internal val result = CompletableDeferred<BuildResult>()
        internal var job: Job? = null

        /** Joiners served by this flight; 0 when it completes means nobody was waiting. */
        @Volatile
        internal var joins: Int = 0

        /** A write invalidated the sources: the result must not be used or joined. */
        @Volatile
        internal var cancelled: Boolean = false
    }

    /**
     * Counters a run report can carry.
     *
     * [builds] counts every Maven invocation coordinated here, [speculativeBuilds] the share
     * of it that nobody had asked for yet, [reused] the callers served by a build already in
     * flight, and [speculativeMs] the CPU time those guesses spent.
     *
     * `reused / speculativeBuilds` is the join metric, and it is NOT the whole keep-or-drop
     * story: a guess that finishes before the model asks for the build cannot be joined, so
     * the request then lands on `BuildCache` (`cached@<fp>`) and `reused` stays 0 while the
     * guess did serve the task. Measured on toy-shaped workspaces (evaluation §11.4b) that is
     * a regular half of the outcomes, so the two flight outcomes are counted separately:
     * [flightsUsed] (someone was waiting) and [flightsUnclaimed] (nothing was waiting when it
     * finished — the next request is the cache's, not the coordinator's).
     *
     * Counters are process-wide, so a task reports the **delta** between two snapshots
     * ([deltaSince]) rather than the running total — otherwise the second task in a JVM would
     * inherit the first one's numbers.
     */
    data class Stats(
        val builds: Int = 0,
        val speculativeBuilds: Int = 0,
        val reused: Int = 0,
        val cancelled: Int = 0,
        val failed: Int = 0,
        val speculativeMs: Long = 0,
        /** Speculative flights at least one caller joined. */
        val flightsUsed: Int = 0,
        /** Speculative flights that finished with nobody waiting; see the class KDoc. */
        val flightsUnclaimed: Int = 0,
    ) {
        /** 0.0 when nothing speculative started, so a report never divides by zero. */
        val reuseRatio: Double
            get() = if (speculativeBuilds == 0) 0.0 else reused.toDouble() / speculativeBuilds

        /**
         * The counts observed between [earlier] and this snapshot.
         *
         * Clamped at zero: `resetStats` (or a fresh process) must not produce a negative
         * count in a task report. A named method rather than `minus`, because `-` would read
         * as "earlier minus later" at the call site.
         */
        fun deltaSince(earlier: Stats): Stats = Stats(
            builds = (builds - earlier.builds).coerceAtLeast(0),
            speculativeBuilds = (speculativeBuilds - earlier.speculativeBuilds).coerceAtLeast(0),
            reused = (reused - earlier.reused).coerceAtLeast(0),
            cancelled = (cancelled - earlier.cancelled).coerceAtLeast(0),
            failed = (failed - earlier.failed).coerceAtLeast(0),
            speculativeMs = (speculativeMs - earlier.speculativeMs).coerceAtLeast(0),
            flightsUsed = (flightsUsed - earlier.flightsUsed).coerceAtLeast(0),
            flightsUnclaimed = (flightsUnclaimed - earlier.flightsUnclaimed).coerceAtLeast(0),
        )

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "builds" to builds,
            "speculativeBuilds" to speculativeBuilds,
            "reused" to reused,
            "cancelled" to cancelled,
            "failed" to failed,
            // Time spent guessing. Compare it with the wall clock a reused build would have
            // cost: the plan's A/B is "does the saved build time exceed this, and the wall
            // clock drop by ≥ 2%".
            "speculativeMs" to speculativeMs,
            // Per-request (can exceed speculativeBuilds when several callers join one flight).
            "reuseRatio" to (Math.round(reuseRatio * 100.0) / 100.0),
            // Per-flight, so the two add up: used + unclaimed (+ cancelled/failed) is the
            // number of speculative builds, which is what makes a report readable.
            "flightsUsed" to flightsUsed,
            "flightsUnclaimed" to flightsUnclaimed,
        )
    }

    private sealed interface Decision {
        class Own(val flight: Flight) : Decision
        class Join(val flight: Flight) : Decision
        class Wait(val flight: Flight) : Decision
    }

    private val flights = ConcurrentHashMap<String, Flight>()

    /**
     * Serializes the check-and-insert in [decide].
     *
     * ONE mutex, not one per workspace: a previous `ConcurrentHashMap<String, Mutex>` grew an
     * entry per workspace ever seen (a long-lived backend pointed at many workspaces — CI, a
     * bench harness, an operator switching `-Dbrowser4.agent.workspace` — accumulated them for
     * the life of the JVM). `decide` is a map lookup plus an insert, so a shared mutex costs
     * microseconds; what must NOT happen is holding it across `block()`, which is why only
     * [decide] takes it and [own] never does.
     */
    private val decideLock = Mutex()
    private val buildsCount = AtomicInteger()
    private val speculativeBuildsCount = AtomicInteger()
    private val reusedCount = AtomicInteger()
    private val cancelledCount = AtomicInteger()
    private val failedCount = AtomicInteger()
    private val speculativeMsCount = java.util.concurrent.atomic.AtomicLong()
    private val flightsUsedCount = AtomicInteger()
    private val flightsUnclaimedCount = AtomicInteger()

    /** Whether speculative builds may be started at all. */
    val enabled: Boolean get() = enabledProvider()

    /** True while a build of any kind is running for [workspaceKey]. */
    fun inFlight(workspaceKey: String): Boolean = flights.containsKey(workspaceKey)

    fun stats(): Stats = Stats(
        builds = buildsCount.get(),
        speculativeBuilds = speculativeBuildsCount.get(),
        reused = reusedCount.get(),
        cancelled = cancelledCount.get(),
        failed = failedCount.get(),
        speculativeMs = speculativeMsCount.get(),
        flightsUsed = flightsUsedCount.get(),
        flightsUnclaimed = flightsUnclaimedCount.get(),
    )

    fun resetStats() {
        buildsCount.set(0)
        speculativeBuildsCount.set(0)
        reusedCount.set(0)
        cancelledCount.set(0)
        failedCount.set(0)
        speculativeMsCount.set(0)
        flightsUsedCount.set(0)
        flightsUnclaimedCount.set(0)
    }

    /**
     * Run [block] as the only build of [workspaceKey] for [fingerprintId].
     *
     * Slower callers do not duplicate work: identical inputs join the flight in progress,
     * different inputs wait for it and then run their own.
     */
    suspend fun run(
        workspaceKey: String,
        fingerprintId: String,
        speculative: Boolean = false,
        block: suspend () -> BuildResult,
    ): BuildResult {
        while (true) {
            when (val decision = decide(workspaceKey, fingerprintId, speculative)) {
                is Decision.Own -> return own(workspaceKey, decision.flight, block)
                is Decision.Join -> {
                    reusedCount.incrementAndGet()
                    decision.flight.joins++
                    // Same inputs as the flight in progress: its answer IS our answer.
                    // A cancellation here means the flight was invalidated mid-run, so the
                    // sources changed under us — retry rather than return a stale success.
                    try {
                        return decision.flight.result.await()
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                    }
                }
                is Decision.Wait -> {
                    // Different inputs (or a cancellable flight that is going away): let it
                    // finish before touching the same `target/` directories. Its outcome is
                    // irrelevant to us, so failures are swallowed.
                    try {
                        decision.flight.result.await()
                    } catch (e: CancellationException) {
                        if (!currentCoroutineContext().isActive) throw e
                    } catch (_: Throwable) {
                        // The other build's failure is reported to its own callers.
                    }
                    // The owner removes its entry in `finally`; give it that chance before
                    // deciding again, otherwise this becomes a hot loop.
                    yield()
                }
            }
        }
    }

    /**
     * Start a compile **now** for sources the agent has not asked to build yet.
     *
     * Returns `false` when the feature is off, when the workspace is already building, or
     * when another speculative build was just started — the caller does not care which,
     * because this is an optimisation, never a correctness requirement.
     */
    fun schedule(workspaceKey: String, fingerprintId: String, block: suspend () -> BuildResult): Boolean {
        if (!enabled) return false
        if (flights.containsKey(workspaceKey)) return false
        scope.launch {
            // A speculative build has no caller to report to: log the failure at debug and
            // let the on-demand build run later (it will produce the real diagnostics).
            runCatching { run(workspaceKey, fingerprintId, speculative = true, block = block) }
                .onFailure { logger.debug("speculative build for {} ended: {}", workspaceKey, it.message) }
        }
        return true
    }

    /**
     * Cancel the speculative build of [workspaceKey], if any.
     *
     * Called on a write: the sources it is compiling are no longer the tree anybody wants.
     * Deliberately **not** suspend so a write path can call it; the flight stays in the map
     * until its owner unwinds, so the next build waits for the process to die instead of
     * racing it.
     */
    fun cancel(workspaceKey: String): Boolean {
        val flight = flights[workspaceKey] ?: return false
        if (!flight.speculative || flight.cancelled) return false
        flight.cancelled = true
        cancelledCount.incrementAndGet()
        flight.result.completeExceptionally(
            CancellationException("speculative build of $workspaceKey invalidated by a write")
        )
        flight.job?.cancel()
        logger.debug("speculative build of {} cancelled: its sources changed", workspaceKey)
        return true
    }

    private suspend fun decide(workspaceKey: String, fingerprintId: String, speculative: Boolean): Decision =
        decideLock.withLock {
            val existing = flights[workspaceKey]
            when {
                existing == null -> {
                    val flight = Flight(fingerprintId, speculative, clock())
                    flights[workspaceKey] = flight
                    Decision.Own(flight)
                }
                // A cancelled flight is on its way out: waiting is the only option, and
                // joining it would hand the caller a result about sources that are gone.
                existing.cancelled -> Decision.Wait(existing)
                existing.fingerprintId == fingerprintId -> Decision.Join(existing)
                else -> Decision.Wait(existing)
            }
        }

    private suspend fun own(
        workspaceKey: String,
        flight: Flight,
        block: suspend () -> BuildResult,
    ): BuildResult {
        flight.job = currentCoroutineContext()[Job]
        buildsCount.incrementAndGet()
        if (flight.speculative) speculativeBuildsCount.incrementAndGet()
        val startedAt = clock()
        try {
            val result = block()
            flight.result.complete(result)
            return result
        } catch (t: Throwable) {
            failedCount.incrementAndGet()
            flight.result.completeExceptionally(t)
            throw t
        } finally {
            // A cancelled guess still cost CPU, so the spend is accounted in `finally` rather
            // than on the success path only.
            if (flight.speculative) {
                speculativeMsCount.addAndGet((clock() - startedAt).coerceAtLeast(0))
                // The flight outcome, counted once per flight so the two shares add up. A
                // flight with no joiner is not a miss: the request usually arrives after the
                // build ends and `BuildCache` answers it, which `reused` cannot see.
                if (flight.joins == 0) flightsUnclaimedCount.incrementAndGet()
                else flightsUsedCount.incrementAndGet()
            }
            // No lock here on purpose: this runs while unwinding (possibly cancelled), and
            // a suspending lock acquisition in a cancelled coroutine could never complete —
            // the entry would leak and every later build would wait on a dead flight.
            // `remove(key, flight)` is conditional on THIS flight, so it cannot delete a
            // newer one: `decide` only inserts when the map has no entry, which requires
            // this removal to have happened first.
            flights.remove(workspaceKey, flight)
        }
    }

    companion object {
        /**
         * `-Dbrowser4.agent.build.speculative=true` opts in.
         *
         * Off by default: the skeleton ships the single-flight safety, while the extra
         * Maven process only pays off once the wall-clock A/B (evaluation §3) says so.
         */
        fun enabled(): Boolean =
            System.getProperty("browser4.agent.build.speculative", "false").toBoolean()

        /** The instance every `MavenBuildSupport` shares inside one JVM. */
        val shared: SpeculativeBuild by lazy {
            SpeculativeBuild(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        }
    }
}
