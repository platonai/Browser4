package ai.platon.pulsar.agentic.agents

/**
 * The phases of a task that the tool loop cannot see (see [RunPhaseTimings]).
 *
 * [SETUP] and [ISOLATION] nest: a task opens [SETUP], opens [ISOLATION] for the worktree
 * creation, then returns to [SETUP] for the rebind/policy work. Every other phase is a
 * single segment.
 */
enum class RunPhase { SETUP, ISOLATION, RUN, MERGE, FINALIZE }

/**
 * The named phases of a task that the tool loop cannot see (evaluation §2 B2).
 *
 * [RunTimings] measures what the tool loop did — model rounds, tool bodies, callbacks,
 * builds. A task's wall clock also contains everything *around* that loop, and on the four
 * tasks measured for the efficiency plan the residue was **9% of the wall clock with no
 * name attached** (26.4 s / 280.5 s). That residue is where "the agent is waiting for
 * something" usually hides: git worktree creation, workspace rebind, the prompt build before
 * the first model round, the isolation merge, the manifest/status writes in the `finally`.
 *
 * Without these numbers any statement about parallelising the waiting is a guess — you
 * cannot hide a build inside a window whose size nobody measured.
 *
 * The phases partition `[start, snapshot]`, so [wallMs] equals their sum. The tail *after*
 * the snapshot (status publish, lifecycle record, conversation memo) is deliberately not
 * claimed here: size it as `status.elapsedMs - wallMs`, which is where both numbers are
 * already in hand.
 *
 * @property wallMs time from the clock's start mark to the snapshot
 * @property setupMs everything before `agent.run()`: status bookkeeping, workspace rebind,
 *   access policy, live-feed wiring — including [isolationMs]
 * @property isolationMs the worktree creation inside [setupMs] (a share of setup, not an
 *   extra segment of the wall clock)
 * @property runMs the `agent.run()` phase — it *contains* [RunTimings.totalMs]
 * @property mergeMs the isolation merge back into the checkout
 * @property finalizeMs the `finally` block up to the point the result is assembled
 *   (`endTask`, history strip, status persist)
 */
data class RunPhaseTimings(
    val wallMs: Long = 0,
    val setupMs: Long = 0,
    val isolationMs: Long = 0,
    val runMs: Long = 0,
    val mergeMs: Long = 0,
    val finalizeMs: Long = 0,
) {
    /** JSON shape carried under `timings.phases` on the task result. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "wallMs" to wallMs,
        "setupMs" to setupMs,
        "isolationMs" to isolationMs,
        "runMs" to runMs,
        "mergeMs" to mergeMs,
        "finalizeMs" to finalizeMs,
    )
}

/**
 * Clock behind [RunPhaseTimings], driven by **boundary marks** rather than nested timers.
 *
 * Each [mark] closes the currently open phase and opens [phase], so a phase can be reopened
 * later and keeps accumulating — which is what makes `isolation` usable as a *share of*
 * `setup` (setup → isolation → setup → run) without a nested-callback API at the call site.
 * The call sites sit inside a `try/finally` with early returns and exceptions, so explicit
 * marks are the only shape that survives every path.
 *
 * The clock starts with [RunPhase.SETUP] open, because the first thing a task does after the
 * clock is created is setup — that keeps the very first segment from being attributed to a
 * phase that has not started yet.
 *
 * [clock] is injectable so the arithmetic can be pinned without sleeping.
 *
 * Not thread-safe by design: it is driven from the task's own coroutine.
 */
class RunPhaseClock(private val clock: () -> Long = System::nanoTime) {

    private val startedAt = clock()
    private var lastMarkAt = startedAt
    private var open: RunPhase = RunPhase.SETUP
    private val durations = HashMap<RunPhase, Long>()

    /** Close the open phase at the current time and open [phase]. */
    fun mark(phase: RunPhase) {
        val now = clock()
        // A clock that steps backwards must not create negative durations: the bucket
        // would then subtract from the wall clock instead of adding to it.
        val elapsed = (now - lastMarkAt).coerceAtLeast(0)
        durations[open] = (durations[open] ?: 0L) + elapsed
        // ISOLATION is a breakdown *inside* SETUP: the worktree time must also count
        // toward setup, otherwise "setup" would silently exclude the slowest part of it
        // and the phases would stop partitioning the wall clock.
        if (open == RunPhase.ISOLATION) {
            durations[RunPhase.SETUP] = (durations[RunPhase.SETUP] ?: 0L) + elapsed
        }
        lastMarkAt = now
        open = phase
    }

    /** Close the open phase and return what every phase measured so far. */
    fun snapshot(): RunPhaseTimings {
        mark(open)
        return RunPhaseTimings(
            wallMs = (lastMarkAt - startedAt).coerceAtLeast(0),
            setupMs = durations[RunPhase.SETUP] ?: 0L,
            isolationMs = durations[RunPhase.ISOLATION] ?: 0L,
            runMs = durations[RunPhase.RUN] ?: 0L,
            mergeMs = durations[RunPhase.MERGE] ?: 0L,
            finalizeMs = durations[RunPhase.FINALIZE] ?: 0L,
        )
    }
}
