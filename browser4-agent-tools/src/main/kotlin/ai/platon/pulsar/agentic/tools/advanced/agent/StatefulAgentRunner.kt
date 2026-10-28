package ai.platon.pulsar.agentic.tools.advanced.agent

import ai.platon.browser4.common.B4Constants.PAGE_STATUS_UNKNOWN
import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.PerceptiveAgent
import ai.platon.pulsar.agentic.agents.AgentRunEvent
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.agents.RobustBrowserAgent
import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.detail.DefaultServerSideAgentEventHandlers
import ai.platon.pulsar.agentic.model.AgentHistory
import ai.platon.pulsar.agentic.tools.advanced.common.JsonlPersistence
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class StatefulAgentRunner(
    val session: AgenticSession,
    /**
     * Optional dispatcher the agent run itself is confined to. Waiting for the
     * [runMutex] happens on [taskScope] regardless — a queued task does not
     * occupy one of the bounded dispatcher's slots, only the actually RUNNING
     * task does.
     */
    private val runDispatcher: CoroutineDispatcher? = null,
) : Closeable {
    private val logger = getLogger(StatefulAgentRunner::class)

    /**
     * Size-bounded, time-expiring cache of agent task statuses.
     *
     * - Entries live at most 2 hours after their last write. An in-progress task
     *   now refreshes its entry on every progress event ([persistProgress]), so a
     *   run longer than the TTL is no longer evicted while it is still running.
     * - At most [STATUS_CACHE_SIZE] entries; Window TinyLFU eviction beyond that.
     *   The previous 100 was too small for a long-lived server: a single coding
     *   session submitted more than twenty tasks in an hour, so an older
     *   still-running task could be evicted and `agent status` answered
     *   "no such task" mid-run.
     * - Persisted to JSONL so task statuses survive restarts.
     *
     * Note: the cache's `expireAfterWrite` clock starts at `create()` (the single
     * cache write), while restore/compaction TTLs are measured from
     * [AgentTaskStatus.finishTime] — see [compactPersistence] and [restoreFromDisk]
     * for the on-disk TTL handling.
     * */
    private val statusCache: Cache<String, AgentTaskStatus> = Caffeine.newBuilder()
        .maximumSize(STATUS_CACHE_SIZE)
        .expireAfterWrite(2, TimeUnit.HOURS)
        .recordStats()
        .build()

    /**
     * Serializes agent task execution for this session. The companion agent and its
     * AgentStateManager are shared session resources — concurrent tasks would interleave
     * their contexts and histories, so only one run may be active at a time. Tasks
     * submitted while another is running are queued here.
     */
    private val runMutex = Mutex()

    private val taskSupervisor = SupervisorJob()

    /**
     * Scope for agent task execution jobs. Cancelling the job of a task stops
     * its agent loop (see [cancel]). Cancelling this scope in [close] stops all
     * of them — otherwise tasks kept running against a closed runner (and a
     * possibly closed session) after a rebuild.
     */
    private val taskScope = CoroutineScope(
        Dispatchers.IO + taskSupervisor + CoroutineName("agent-tasks")
    )

    /** Currently queued/running task jobs by task id (for [cancel]). */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /**
     * Bounded per-task step logs: what the task actually DID, as opposed to
     * [statusCache]'s coarse `processState`.
     *
     * Served by `GET /api/commands/{id}/events` (the CLI's `agent run --follow`
     * and the interactive `agent run -i` REPL). In memory only — the JSONL row
     * set is a task registry, and persisting a thousand events per task would
     * dwarf it. A task evicted from this cache still answers `agent status` /
     * `agent result`; its live event tail is simply gone.
     */
    private val eventLogs: Cache<String, AgentTaskEventLog> = Caffeine.newBuilder()
        .maximumSize(EVENT_LOG_CACHE_SIZE)
        .expireAfterAccess(EVENT_LOG_TTL_MINUTES, TimeUnit.MINUTES)
        .build()

    /**
     * Steering messages the interactive session typed while its task runs,
     * keyed by task id. Drained at every model round-trip by
     * [RobustBrowserAgent.steeringSource] and removed when the task ends.
     *
     * Only messages for a *live* task are accepted ([enqueueInput]); a task id
     * that already finished is rejected so a stale terminal id cannot grow the
     * map forever.
     */
    private val pendingInputs = ConcurrentHashMap<String, ConcurrentLinkedQueue<String>>()

    /** Guards [pendingInputs] so an enqueue can never race a drain (see [enqueueInput]). */
    private val inputLock = Any()

    /**
     * Shared per process: all runners write the same global JSONL file, so they
     * must share ONE persistence instance — separate instances would interleave
     * unsynchronized appends and each compaction would drop the other runners'
     * rows when clearing the common file.
     */
    internal val persistence: JsonlPersistence<AgentTaskStatus> = sharedAgentPersistence()

    /** Dedicated dispatcher for cleanup operations. */
    private val cleanupDispatcher = Dispatchers.IO.limitedParallelism(2)

    private val cleanupScope = CoroutineScope(
        cleanupDispatcher + SupervisorJob() + CoroutineName("agent-cleanup")
    )

    /** How long to keep terminal tasks before compacting them out of the JSONL file. */
    @Volatile
    var taskTtlMinutes: Int = 120

    /** Wall-clock time of the last in-progress snapshot (see [persistProgress]). */
    @Volatile
    private var lastProgressPersistedAt = 0L

    init {
        liveInstances.add(this)
        restoreFromDisk()
        // Periodically compact the JSONL file so evicted entries don't accumulate forever.
        cleanupScope.launch {
            while (isActive) {
                delay(5 * 60 * 1000L) // every 5 minutes
                compactPersistence()
            }
        }
    }

    /**
     * Entries whose finish (or creation for non-terminal rows) is this long ago are
     * eligible for compaction.
     */
    private fun ttlCutoff(): Instant = Instant.now().minusSeconds(taskTtlMinutes * 60L)

    fun restoreFromDisk() {
        val ttlCutoff = ttlCutoff()
        val terminalState = TERMINAL_PROCESS_STATE

        // Fold the append-only file down to ONE row per task *before* writing any
        // correction. The previous streaming version appended an "interrupted" row the
        // first time it met a non-terminal row — the submit-time `created` row — and that
        // correction became the LAST row for the id, shadowing the real terminal row on
        // every later read. `agent run --resume` then saw no `workspace`, reported
        // "previous workspace is no longer available", and the resumed task re-collected
        // everything from scratch (2026-10-28). Terminal states still never regress.
        val latest = LinkedHashMap<String, AgentTaskStatus>()
        persistence.readAll().forEach { entry ->
            val known = latest[entry.id]
            if (known != null) {
                // A generic restart correction is *derived* from an older row by an
                // earlier restore, so it must never shadow a specific terminal outcome
                // (budget exceeded, cancelled, completed). Files written before the fold
                // fix still carry such a correction as their last row; without this
                // guard it keeps winning and the task's workspace stays lost.
                val genericCorrection = entry.failureReason == INTERRUPTED_REASON
                if (genericCorrection && known.processState == terminalState &&
                    known.failureReason != INTERRUPTED_REASON
                ) {
                    logger.debug("Skipping generic interruption row for agent task {} — a specific terminal row is known", entry.id)
                    return@forEach
                }
                if (known.processState == terminalState && entry.processState != terminalState) {
                    logger.debug("Skipping non-terminal row for agent task {} — terminal status already read", entry.id)
                    return@forEach
                }
            }
            latest[entry.id] = entry
        }

        latest.values.forEach { entry ->
            // TTL is measured from the row's LAST activity, not its creation: a
            // run that has been going for hours keeps writing progress rows, and
            // judging those rows by createdTime threw the newest state away.
            val lastActivity = entry.lastActivity()
            // Skip expired rows: terminal ones were evicted from the cache before
            // shutdown, and created/queued tasks are never auto-resumed after a
            // restart — restoring them only pollutes the caches with zombie records.
            if (lastActivity.isBefore(ttlCutoff)) {
                logger.debug(
                    "Skipping stale agent task {} during restore (state={}, lastActivity={})",
                    entry.id, entry.processState, lastActivity
                )
                return@forEach
            }
            if (entry.processState != terminalState) {
                // A non-terminal LAST row means the process died mid-task (or the task was
                // queued and never started): nobody will ever resume it. Restoring it as
                // in_progress produced a zombie status that looked alive for the rest of
                // the cache TTL — restore it as an interrupted failure instead. A task
                // still owned by a live runner in this process is authoritative and must
                // not be clobbered from the file snapshot.
                val liveOwner = liveInstances.firstOrNull { it !== this && it.getStatus(entry.id)?.isDone == false }
                if (liveOwner != null) {
                    statusCache.put(entry.id, entry)
                    return@forEach
                }
                entry.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                entry.failureReason = INTERRUPTED_REASON
                entry.message = "Agent task did not complete before the server restarted"
                statusCache.put(entry.id, entry)
                // Persist the correction so subsequent restores see a terminal row.
                persistence.append(entry)
                logger.debug("Restored interrupted agent task {} as failed", entry.id)
                return@forEach
            }
            statusCache.put(entry.id, entry)
            logger.debug("Restored agent task {}", entry.id)
        }
        logger.info("Restored {} agent task(s) from disk", latest.size)
    }

    /**
     * Compact the JSONL persistence file.
     *
     * Two triggers: terminal entries past the TTL, and a file larger than
     * [COMPACTION_SIZE_THRESHOLD_BYTES] — periodic progress snapshots keep
     * appending rows, so staleness alone can no longer be the trigger.
     *
     * The rewrite **merges** the rows already in the file with the live caches
     * instead of writing only this runner's cache: the file is shared by every
     * session (one row set under `<data>/agent/`), and rewriting just one
     * session's view dropped tasks created by other sessions after this runner
     * started. Sessions may also hold rows not yet flushed to disk, so every
     * live runner's cache is unioned in as well.
     */
    internal fun compactPersistence() {
        val ttlCutoff = ttlCutoff()
        val terminalState = TERMINAL_PROCESS_STATE

        val stale = statusCache.asMap().entries.filter {
            it.value.processState == terminalState && it.value.lastActivity().isBefore(ttlCutoff)
        }
        val oversized = persistence.sizeInBytes() > COMPACTION_SIZE_THRESHOLD_BYTES
        if (stale.isEmpty() && !oversized) return

        stale.forEach { statusCache.invalidate(it.key) }

        // Merge + replace in ONE locked section (see JsonlPersistence#rewrite): a
        // row another session appends mid-compaction lands in the replacement
        // instead of vanishing between an unlocked read and the atomic move.
        // Snapshots are detached: an in-progress status holds the agent's live
        // history reference, which mutates concurrently with this rewrite.
        var kept = emptyList<AgentTaskStatus>()
        persistence.rewrite {
            val merged = LinkedHashMap<String, AgentTaskStatus>()
            // Append order: a later row for the same id supersedes the earlier one.
            persistence.readAll().forEach { merged[it.id] = it }
            // The live caches are newer than anything on disk — union every
            // runner's, not only this runner's.
            for (runner in liveInstances) {
                for ((id, status) in runner.statusCache.asMap()) {
                    merged[id] = status
                }
            }
            // Drop what restore would drop as well: anything with no activity for TTL.
            kept = merged.values.filter { !it.lastActivity().isBefore(ttlCutoff) }
            kept.map { it.detachedSnapshot() }
        }
        logger.info(
            "Compacting agent task persistence ({} stale, {} rows kept, {} bytes, TTL {} min)",
            stale.size, kept.size, persistence.sizeInBytes(), taskTtlMinutes
        )
    }

    fun create(): AgentTaskStatus {
        val status = AgentTaskStatus()
        statusCache.put(status.id, status)
        persistence.append(status)
        status.emitEvent("StatefulAgentRunner.created")
        // The live step log starts here, not when the run starts: a follower may
        // attach while the task is still queued (behind another task of the
        // session) and must see that it exists.
        recordLifecycle(status, AgentTaskEvent.TYPE_CREATED)
        logger.debug("Created agent task status {} (session={})", status.id, session.uuid)
        return status
    }

    /**
     * The task's live step log; created on first use and kept bounded by
     * [eventLogs] (capacity + idle TTL), never persisted.
     */
    private fun eventLog(taskId: String): AgentTaskEventLog =
        eventLogs.get(taskId) { AgentTaskEventLog() }

    /** Append one of the runner's own lifecycle steps for [status]. */
    private fun recordLifecycle(status: AgentTaskStatus, type: String, text: String? = null) {
        eventLog(status.id).append(type, text = AgentTaskEventSanitizer.preview(text))
    }

    /**
     * Translate one agent-reported step into the task's event log.
     *
     * `internal` (not private) so the runner's tests can exercise the
     * sanitize-on-the-way-in wiring without a real agent run.
     */
    internal fun recordRunEvent(taskId: String, event: AgentRunEvent) {
        eventLog(taskId).append(
            type = event.type,
            tool = event.tool,
            arguments = AgentTaskEventSanitizer.arguments(event.tool, event.arguments),
            ok = event.ok,
            durationMs = event.durationMs,
            text = AgentTaskEventSanitizer.preview(event.text),
            turn = event.turn,
            inputTokens = event.inputTokens,
            outputTokens = event.outputTokens,
            totalTokens = event.totalTokens,
        )
    }

    /**
     * The run's aggregate counters for [id]: rounds, LLM requests, tool calls,
     * failures, steering messages and token usage.
     *
     * Counted by the task's event log rather than derived from the steps a client
     * happens to have seen, so the numbers stay exact after steps are evicted
     * from the bounded buffer. `null` when this runner has no log for [id]
     * (unknown task, a page visit, or the log's idle TTL expired).
     *
     * @param id task id
     * */
    fun stats(id: String): AgentTaskRunStats? = eventLogs.getIfPresent(id)?.stats()

    /**
     * The steps of [id] after [afterSeq], oldest first.
     *
     * Empty when the task is unknown, has not emitted anything after the cursor,
     * or its log has been evicted — the caller distinguishes those cases with
     * the task status, which outlives the log.
     *
     * @param afterSeq cursor: return events with a strictly greater sequence
     * @param limit maximum events in this page
     */
    fun events(
        id: String,
        afterSeq: Long = 0L,
        limit: Int = AgentTaskEventLog.DEFAULT_PAGE_LIMIT,
    ): List<AgentTaskEvent> = eventLogs.getIfPresent(id)?.eventsAfter(afterSeq, limit) ?: emptyList()

    /**
     * Queue a steering [text] for the running task [id].
     *
     * The message is injected into the agent at its next model round-trip (see
     * [RobustBrowserAgent.steeringSource]); a task that already finished is
     * rejected, because nothing will ever drain its queue.
     *
     * @return true when the task is live in this runner and the message was queued
     */
    fun enqueueInput(id: String, text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty()) return false
        val status = statusCache.getIfPresent(id) ?: return false
        if (status.isDone) return false
        // Enqueue and drain take the same lock: an `add` racing a drain could land
        // on a queue the drain had already detached, losing the message.
        synchronized(inputLock) {
            pendingInputs.computeIfAbsent(id) { ConcurrentLinkedQueue() }.add(message)
        }
        logger.info(
            "Queued steering message for agent task {} (session={}, {} chars)",
            id, session.uuid, message.length
        )
        return true
    }

    /**
     * Drain the steering messages queued for [id].
     *
     * Called by the agent on every model round-trip. Draining is destructive —
     * the agent reports the returned lines as one injected message, and an empty
     * list means "nothing new".
     */
    fun drainInputs(id: String): List<String> {
        val drained = ArrayList<String>(1)
        synchronized(inputLock) {
            val queue = pendingInputs[id] ?: return emptyList()
            while (true) {
                val next = queue.poll() ?: break
                drained += next
            }
            if (queue.isEmpty()) pendingInputs.remove(id, queue)
        }
        return drained
    }

    private fun onStatusChanged(status: AgentTaskStatus) {
        persistence.append(status)
    }

    /**
     * Persist the running task's latest status (throttled to
     * [PROGRESS_PERSIST_INTERVAL_MS]) and refresh its cache entry.
     *
     * Previously a task only wrote its status when it finished. Two consequences
     * were visible on real runs:
     * - a backend restart revived the submit-time row (`created`, no message, no
     *   startedTime) for a task that had been running for an hour;
     * - the cache entry was written once at submit, and Caffeine's
     *   `expireAfterWrite(2h)` evicted it while the task was still running, so
     *   `agent status` answered "no task found" for a live task.
     *
     * The snapshot drops the agent history (huge for the legacy engine, empty for
     * a running CLI task); the terminal write keeps it.
     */
    private fun persistProgress(status: AgentTaskStatus, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastProgressPersistedAt < PROGRESS_PERSIST_INTERVAL_MS) return
        lastProgressPersistedAt = now
        // put() counts as a write: it resets expireAfterWrite and keeps a long
        // run's entry (and everything restored from disk) alive.
        statusCache.put(status.id, status)
        persistence.append(status.detachedSnapshot(includeHistory = false))
    }

    /**
     * Execute a plain command using the agent's run method.
     *
     * This method creates a cached status, executes the agent's run method, and updates
     * the status with the result.
     *
     * @param plainCommand The plain text command for the agent to execute.
     * @return AgentStatus containing the execution result.
     */
    suspend fun execute(
        plainCommand: String,
        noopLimit: Int? = null,
        coding: Boolean? = null,
        freshWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        autoApprove: AccessPreset? = null,
        resumeTaskId: String? = null,
    ): AgentTaskStatus {
        val status = create()
        execute(plainCommand, status, noopLimit, coding, freshWorkspace, access, autoApprove, resumeTaskId)
        return status
    }

    /**
     * Submit [plainCommand] for background execution and return the tracked [Job].
     *
     * The job is registered BEFORE it starts ([CoroutineStart.LAZY]), so a cancel
     * racing the scheduler always finds it in [runningJobs]. Cancelling a task that
     * never gets to run (still queued on [runMutex], or cancelled before start)
     * marks its status terminal here or in [execute] — the status can never stay
     * `created` on a dead task.
     */
    fun submit(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        coding: Boolean? = null,
        freshWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        autoApprove: AccessPreset? = null,
        resumeTaskId: String? = null,
    ): Job {
        val job = taskScope.launch(start = CoroutineStart.LAZY) {
            execute(plainCommand, status, noopLimit, coding, freshWorkspace, access, autoApprove, resumeTaskId)
        }
        runningJobs[status.id] = job
        job.start()
        job.invokeOnCompletion { cause ->
            runningJobs.remove(status.id, job)
            // A job cancelled before its coroutine ever ran (scope cancelled racing
            // this registration, submit against an already-closed runner) leaves no
            // coroutine behind to mark the status — the completion cause is the last
            // chance. When the coroutine did run, execute()'s own marking already
            // made it terminal before this fires.
            if (cause is CancellationException && !status.isDone) {
                status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                status.cancelled = true
                status.failureReason = status.failureReason ?: CANCELLED_MESSAGE
                status.message = status.message ?: CANCELLED_MESSAGE
                persistence.append(status)
                // A follower attached to this task must see it end, even though
                // its coroutine never ran (the finally of executeSerialized did
                // not fire).
                pendingInputs.remove(status.id)
                recordLifecycle(status, AgentTaskEvent.TYPE_CANCELLED, status.failureReason)
            }
        }
        return job
    }

    /**
     * Cancel a queued/running task by id.
     *
     * @return true when a live job was found and cancelled; false when the task
     *   is unknown, already finished, or its job is no longer tracked.
     */
    fun cancel(id: String): Boolean {
        val job = runningJobs.remove(id) ?: return false
        logger.info("Cancelling agent task {} (session={})", id, session.uuid)
        // Mark the status BEFORE cancelling the job: the coroutine may be waiting on
        // [runMutex] or not yet scheduled, in which case nothing inside it can mark
        // the status terminal. Idempotent with executeSerialized's own marking.
        statusCache.getIfPresent(id)?.takeIf { !it.isDone }?.let { status ->
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.cancelled = true
            status.failureReason = CANCELLED_MESSAGE
            status.message = CANCELLED_MESSAGE
            persistence.append(status)
        }
        job.cancel()
        return true
    }

    /**
     * Internal method to execute agent command with a pre-created status.
     *
     * Execution is serialized per session through [runMutex] so concurrent tasks cannot
     * interleave the shared companion agent's execution contexts and history.
     *
     * This method creates and wires up ServerSideAgentEventHandlers for event collection,
     * following the pattern from StatefulPageVisitor#doVisit. A [supervisorScope] ensures
     * the event collector is structured within this call — it is launched as a child,
     * cancelled in the finally block, and the scope suspends until it terminates.
     */
    suspend fun execute(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        coding: Boolean? = null,
        freshWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        autoApprove: AccessPreset? = null,
        resumeTaskId: String? = null,
    ) {
        try {
            runMutex.withLock {
                executeSerialized(
                    plainCommand, status, noopLimit, coding, freshWorkspace, access, autoApprove, resumeTaskId
                )
            }
        } catch (e: CancellationException) {
            // A task cancelled while queued never reaches executeSerialized — without
            // this, its status would stay `created` forever and every poll would look
            // queue-healthy (CLI --wait spins until its timeout, agent list shows
            // it alive).
            if (!status.isDone) {
                status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                status.cancelled = true
                status.failureReason = status.failureReason ?: CANCELLED_MESSAGE
                status.message = status.message ?: CANCELLED_MESSAGE
                onStatusChanged(status)
            }
            throw e
        }
    }

    private suspend fun executeSerialized(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        coding: Boolean? = null,
        freshWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        autoApprove: AccessPreset? = null,
        resumeTaskId: String? = null,
    ) {
        val agent = session.companionAgent

        // `agent run --resume <id>`: seed this NEW run from the interrupted task's
        // checkpoint — its instruction, workspace and files. The old conversation
        // died with the process, so this is the only continuity there is. Resolved
        // before the per-task flags so an omitted --coding / --access inherits what
        // the interrupted task ran with.
        val resume = resumeTaskId?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
            // The cache holds this runner's restored rows; the shared JSONL is the
            // fallback for a checkpoint that belongs to another session's runner
            // (the CLI resumes through whichever session it is attached to).
            val prior = getStatus(id)
                ?: runCatching { persistence.readAll().lastOrNull { it.id == id } }.getOrNull()
                ?: throw IllegalArgumentException(
                    "No checkpoint for task '$id': this backend does not know it " +
                        "(it may have run on another backend, or the row expired)."
                )
            resumeCheckpointOf(prior).also {
                logger.info(
                    "Agent task {} (session={}): resuming {} (workspace={}, files={})",
                    status.id, session.uuid, id, it.workspace, it.files.size
                )
            }
        }
        val instruction = resume?.let { resumeInstruction(plainCommand, it) } ?: plainCommand
        val codingMode = coding ?: resume?.codingMode ?: false
        val accessPreset = access ?: resume?.accessPreset?.let { AccessPreset.parse(it) }

        // The companion agent keeps ONE cumulative stateHistory across every task
        // executed in the session. Remember where this task's states begin so the
        // published history never leaks states from earlier tasks (a running
        // status must not serialize other tasks' states either — its history is
        // refreshed as a task-scoped snapshot below, never the live list).
        val startIndex = agent.stateHistory.size

        // Set once agent.run() has returned. Events the collector drains after that
        // point are stale: they must not overwrite the final result message or the
        // detached final snapshot taken in the finally block below.
        val runHasReturned = AtomicBoolean(false)

        try {
            status.refresh(ResourceStatus.SC_PROCESSING)
            // Record what this run is: the effective flags decide a later resume's
            // inheritance, and `resumedFrom` links the two task ids.
            status.codingMode = codingMode
            status.accessPreset = accessPreset?.id
            status.resumedFrom = resume?.taskId
            // The submit-time row says "created"; record the transition to
            // "in progress" immediately so a restart cannot revive a task that had
            // already started as if it were still queued.
            persistProgress(status, force = true)
            recordLifecycle(
                status, AgentTaskEvent.TYPE_STARTED,
                plainCommand.ifBlank { resume?.instruction.orEmpty() }.ifBlank { instruction },
            )

            // Per-task flags. Explicit values win; a null resets any stale
            // override left over from a previous task (the companion agent is
            // shared across tasks).
            (agent as? RobustBrowserAgent)?.let {
                it.noopLimitOverride = noopLimit
                if (noopLimit != null) {
                    logger.info("Agent task {} (session={}): noop limit overridden to {}", status.id, session.uuid, noopLimit)
                }
                // Per-task access policy (`command.run(access=…, autoApprove=…)`). Applied before
                // any tool call and before the fresh-workspace rebind below, because that rebind
                // rebuilds the coding components from the active policy. Absent values keep the
                // server-wide defaults; the finally below restores them either way, so a companion
                // agent shared across tasks never inherits the previous task's grant.
                it.agentToolManager.applyAccessPolicy(
                    AgentAccessPolicy.serverDefault.withTaskOverrides(accessPreset, autoApprove)
                )
                // Explicit coding flag, never auto-detected: the coding flag is
                // opt-in per call (`command_run`'s coding argument, e.g. the CLI's
                // agent run --coding). A resumed run inherits it from its checkpoint.
                it.codingMode = codingMode
                if (codingMode) {
                    logger.info("Agent task {} (session={}): coding mode (explicit) — skipping driver health check and page navigation", status.id, session.uuid)
                }
                // Fresh-workspace isolation (design §6.6): create a timestamped
                // workspace dir under the agent's base dir, point both the agent's
                // workspaceDir and the tool manager's coding shell/fs at it.
                if (freshWorkspace == true) {
                    val fresh = it.baseDir.resolve("workspace.fresh.${status.id}")
                    java.nio.file.Files.createDirectories(fresh)
                    it.workspaceDirOverride = fresh
                    it.agentToolManager.rebindWorkspace(fresh)
                    logger.info("Agent task {} (session={}): fresh workspace at {}", status.id, session.uuid, fresh)
                } else if (resume?.workspace != null) {
                    // Resume keeps the interrupted task's workspace, so the files it
                    // already wrote are what the new run continues from.
                    it.workspaceDirOverride = resume.workspace
                    it.agentToolManager.rebindWorkspace(resume.workspace)
                    logger.info(
                        "Agent task {} (session={}): resumed workspace at {}",
                        status.id, session.uuid, resume.workspace
                    )
                }
                // Live step feed and steering for THIS task. Cleared in the finally
                // below: the companion agent outlives the task and is shared by
                // every other caller of the session.
                it.runEventListener = { event -> recordRunEvent(status.id, event) }
                it.steeringSource = { drainInputs(status.id) }
            }

            // Create and wire up ServerSideAgentEventHandlers for this command
            val serverSideAgentEventHandlers = DefaultServerSideAgentEventHandlers()
            status.serverSideAgentEventHandlers = serverSideAgentEventHandlers

            // Start with an empty task-scoped snapshot (never the live cumulative list).
            status.agentHistory = taskScopedHistory(agent.stateHistory, startIndex)

            supervisorScope {
                // Start a child coroutine to collect events and update status
                val eventCollectorJob = launch {
                    try {
                        serverSideAgentEventHandlers.eventFlow.collect { event ->
                            status.emitEvent(event.eventType)
                            if (!runHasReturned.get()) {
                                status.agentHistory = taskScopedHistory(agent.stateHistory, startIndex)
                            }
                            // The CLI engine reports its executed-tool count with the
                            // throttled progress events (every 5 calls): surface it as
                            // live progress and persist the snapshot, so a long run is
                            // observable and survivable.
                            (event.metadata["toolExecutions"] as? Number)?.let {
                                status.toolExecutions = it.toInt()
                            }
                            persistProgress(status)
                            logger.debug("Collected event {} for agent task {} (session={})", event.eventType, status.id, session.uuid)
                        }
                    } catch (e: CancellationException) {
                        logger.debug("Event collector cancelled for agent task {}", status.id)
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error collecting events for agent task {} (session={})", status.id, session.uuid, e)
                    }
                }

                try {
                    // Bind server-side agent event handlers to THIS coroutine so multiple commands can run concurrently.
                    AgentEventBus.withServerSideAgentEventHandlers(serverSideAgentEventHandlers) {
                        if (runDispatcher != null) {
                            withContext(runDispatcher) {
                                executeAgentCommand(instruction, status, agent, runHasReturned, startIndex, codingMode, freshWorkspace)
                            }
                        } else {
                            executeAgentCommand(instruction, status, agent, runHasReturned, startIndex, codingMode, freshWorkspace)
                        }
                    }
                } finally {
                    // Cancel event collector when command completes
                    eventCollectorJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            logger.warn("Agent task {} cancelled: {}", status.id, e.message)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            // The CancellationException message is the coroutine's own name
            // ("StandaloneCoroutine was cancelled") — internal noise that told
            // the CLI user nothing about why the task ended. Report the fact,
            // and mark the row as user-facing "cancelled" so `agent status`
            // word and `failed` stay distinguishable.
            status.cancelled = true
            status.failureReason = CANCELLED_MESSAGE
            status.message = CANCELLED_MESSAGE
            // Keep what the run already produced: a cancel is not a reason to lose
            // the deliverables that are already on disk.
            status.deliverables = deliverableFilesOf(status.workspace).takeIf { it.isNotEmpty() }
            throw e
        } catch (e: Throwable) {
            logger.error("Failed to execute agent command: {} (session={})", plainCommand, session.uuid, e)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.failureReason = "${e.javaClass.simpleName}: ${e.message}"
            status.message = e.message
            // A budget/step abort used to report a bare failure with `filesChanged`
            // empty although the data was already written (2026-10-28: a complete
            // 20×75 CSV, task reported as `failed (417)` with no artifacts).
            status.deliverables = deliverableFilesOf(status.workspace).takeIf { it.isNotEmpty() }
            // Persist the terminal row NOW. The finally block below runs task bookkeeping
            // (endTask, workspace reset, policy reset) before it appends again, and a
            // status poll landing in that window used to read the last RUNNING row from
            // disk — reporting the generic interruption message instead of this reason
            // (observed 2026-10-28: a NOOP_LIMIT abort reported as a server restart).
            runCatching { onStatusChanged(status) }
                .onFailure { logger.warn("persisting failed status for {} failed: {}", status.id, it.message) }
        } finally {
            // Reset the per-task overrides: the companion agent is shared with callers
            // outside this runner's mutex (chat, direct agent tools), and a stale
            // --noop-limit override would silently change those runs too.
            (agent as? RobustBrowserAgent)?.let {
                it.noopLimitOverride = null
                it.codingMode = false
                // Detach this task's live feed and steering queue: the companion
                // agent is shared, and a stale listener would keep reporting into
                // a finished task's log (and a stale steering source would drain
                // another task's messages).
                it.runEventListener = null
                it.steeringSource = null
                // Write point 3 (§6.1): record the terminal task state in the
                // workspace manifest. Done from finally so cancelled/failed runs
                // are still recorded.
                val taskStatus = when {
                    status.cancelled -> ai.platon.pulsar.coding.TaskStatus.CANCELLED
                    status.failureReason != null -> ai.platon.pulsar.coding.TaskStatus.FAILED
                    else -> ai.platon.pulsar.coding.TaskStatus.COMPLETE
                }
                runCatching { it.agentToolManager.endTask(status.id, taskStatus) }
                    .onFailure { logger.warn("endTask failed for {}: {}", status.id, it.message) }
                // Reset the fresh-workspace override (if any) back to the default
                // workspace so subsequent tasks in this session don't inherit it.
                if (it.workspaceDirOverride != null) {
                    val default = it.baseDir.resolve("workspace")
                    java.nio.file.Files.createDirectories(default)
                    it.workspaceDirOverride = null
                    runCatching { it.agentToolManager.rebindWorkspace(default) }
                        .onFailure { w -> logger.warn("reset workspace failed: {}", w.message) }
                }
                // Restore the server-wide policy: the next task decides its own grant.
                runCatching { it.agentToolManager.resetAccessPolicy() }
                    .onFailure { w -> logger.warn("reset access policy failed: {}", w.message) }
            }
            status.done()
            onStatusChanged(status)
            // Close the live feed: the terminal step is what makes a follower
            // stop, and the steering queue can never be drained after this point.
            pendingInputs.remove(status.id)
            val terminalType = when {
                status.cancelled -> AgentTaskEvent.TYPE_CANCELLED
                status.failureReason != null -> AgentTaskEvent.TYPE_FAILED
                else -> AgentTaskEvent.TYPE_DONE
            }
            recordLifecycle(status, terminalType, status.failureReason ?: status.message)
        }
    }

    /**
     * Ensure the session's bound driver is usable before an agent task runs.
     *
     * [ai.platon.pulsar.skeleton.session.PulsarSession.getOrCreateBoundDriver]
     * returns the existing bound driver without a health check, so a driver whose
     * browser was torn down keeps being reused across tasks. When the health check
     * fails, unbind and close the dead driver (and its browser) so the next
     * [ai.platon.pulsar.skeleton.session.PulsarSession.getOrCreateBoundDriver]
     * launches a fresh browser.
     */
    private suspend fun ensureSessionDriverHealthy() {
        val driver = runCatching { session.boundDriver }.getOrNull() as? AbstractWebDriver
            ?: return // no bound driver yet — a fresh one is created on first use

        val healthy = runCatching { driver.quickCheckHealthy().isOK }.getOrDefault(false)
        if (healthy) {
            return
        }

        logger.warn("Agent task: session driver is unhealthy; resetting bound driver and browser")
        runCatching { session.unbindDriver(driver) }
            .onFailure { logger.warn("Failed to unbind unhealthy driver", it) }
        runCatching { driver.close() }
            .onFailure { logger.warn("Failed to close unhealthy driver", it) }

        val browser = runCatching { session.boundBrowser }.getOrNull()
        if (browser != null) {
            runCatching { session.unbindBrowser(browser) }
                .onFailure { logger.warn("Failed to unbind dead browser", it) }
            runCatching { browser.close() }
                .onFailure { logger.warn("Failed to close dead browser", it) }
        }
    }

    /**
     * Executes the agent command logic.
     *
     * This method is extracted to allow the event handlers to be properly bound
     * to the execution context via PulsarEventBus.withServerSideEventHandlers.
     */
    private suspend fun executeAgentCommand(
        plainCommand: String,
        status: AgentTaskStatus,
        agent: PerceptiveAgent,
        runHasReturned: AtomicBoolean,
        startIndex: Int,
        coding: Boolean? = null,
        freshWorkspace: Boolean? = null,
    ) {
        // Record the submitted instruction so callers can verify they're
        // getting results for the right task (prevents cross-talk confusion).
        // A resumed run's prompt carries a `## Resume context` block that is not
        // part of what the user submitted, so store the task text without it —
        // otherwise a second resume would nest blocks.
        status.submittedTask = stripResumeBlock(plainCommand)

        // Record where this task's tools may write, so a finished task can point its
        // caller at the deliverables instead of leaving them to search app-data.
        status.workspace = (agent as? BasicBrowserAgent)?.workspaceRoot?.toString()

        // Coding vs browser is an EXPLICIT per-task choice (browser is the product
        // default; coding is auxiliary): the keyword-based CodingTaskDetector was
        // removed. `executeSerialized` already applied the coding flag to the agent
        // (`codingMode = coding == true`); here only the browser branch resets a
        // possibly stale bound driver so this task gets a fresh browser.
        if (coding != true) {
            // A session reuses its bound driver across tasks. If a previous task (or a
            // close-all) shut the browser down, the driver points at a dead browser and
            // the agent would fail every step (DOM settle timeout, browserUseState
            // degradation, zero-step runs). Reset the bound driver/browser so a fresh
            // browser is created for this task.
            ensureSessionDriverHealthy()
        }

        // Save the user's current page URL so we can restore it after the agent
        // runs, preventing the agent from polluting the shared browser session.
        val savedUrl = runCatching { session.boundDriver?.currentUrl() }.getOrNull()
        logger.debug("Agent task {}: saved user page URL before agent run: {}", status.id, savedUrl)

        val history = try {
            agent.run(plainCommand).also { runHasReturned.set(true) }
        } finally {
            // Scope the status history to THIS task's execution session and detach it
            // from the agent's live (accumulating/trimming) history list. Without this,
            // a status could expose other tasks' states, and later history trims could
            // retroactively shrink a completed task's history.
            //
            // `lastRunSessionId` is only published once `run()` returns, so a run that
            // fails early leaves it null and `snapshotFor` empty; the history size
            // captured before the run is then the remaining evidence of this task's
            // own states.
            val sessionSnapshot = agent.stateHistory.snapshotFor(agent.lastRunSessionId)
            status.agentHistory = if (sessionSnapshot.isNotEmpty()) sessionSnapshot
            else taskScopedHistory(agent.stateHistory, startIndex)
        }
        status.agentHistory = history

        // The page the task ended on: its HTTP status is the honest value for the
        // record's `pageStatusCode`. Read BEFORE the user page is restored below —
        // afterwards the session is back on the user's page and the status would
        // describe a page the task never touched. Unobservable ⇒ PAGE_STATUS_UNKNOWN,
        // never the model's old 201/"created" default.
        status.pageStatusCode = DocumentHttpStatus.of(session.boundDriver) ?: PAGE_STATUS_UNKNOWN

        // Restore the user's page if the agent navigated away from it
        try {
            if (savedUrl != null && savedUrl.isNotBlank()) {
                val currentUrl = session.boundDriver?.currentUrl()
                if (currentUrl != savedUrl && currentUrl != "about:blank") {
                    logger.info(
                        "Agent task {}: restoring user page from '{}' back to '{}'",
                        status.id, currentUrl, savedUrl
                    )
                    session.boundDriver?.navigateTo(savedUrl)
                }
            }
        } catch (e: Exception) {
            logger.warn("Agent task {}: failed to restore user page: {}", status.id, e.message)
        }

        // status.agentHistory was set above and is not touched again: on success it is
        // `history`, the detached task-scoped snapshot `run()` itself returned, and on
        // failure it is the snapshot the `finally` block took. Reassigning it to the
        // agent's live cumulative `stateHistory` here would undo that scoping.
        val finalState = history.finalResult

        if (finalState == null) {
            // The agent produced no result. Distinguish "empty page" (the agent
            // navigated somewhere with no useful content) from a genuine failure
            // (the agent didn't navigate at all or crashed).
            val lastState = history.states.lastOrNull()
            val hasPageContent = lastState?.currentPageContentSummary != null
            if (!hasPageContent) {
                status.refresh(ResourceStatus.SC_EXPECTATION_FAILED)
                status.message = "Agent produced no results (no page content). " +
                    "The task may not have navigated to a valid page or the agent encountered an error."
                status.failureReason = "Agent produced no results (no page content)"
            } else {
                status.refresh(ResourceStatus.SC_OK)
                status.message = "Agent completed but produced no summary (page content loaded)"
            }
        } else {
            // AgentState has 'summary' for the final result message
            status.message = finalState.summary ?: finalState.description ?: ""
            status.refresh(ResourceStatus.SC_OK)
        }
    }

    fun getStatus(id: String) = statusCache.getIfPresent(id)

    /**
     * Returns the latest agent state for the given task ID, or null if the task
     * is not found.  The returned state may be an intermediate state if the agent
     * is still executing.
     */
    fun getLatestState(id: String) = statusCache.getIfPresent(id)?.agentState

    /**
     * Returns IDs of all statuses that are still in progress.
     */
    fun activeStatusIds(): List<String> = statusCache.asMap()
        .filterValues { !it.isDone }
        .keys.toList()

    /**
     * Return cache statistics for observability.
     * */
    fun cacheStats(): Map<String, Any> {
        val stats = statusCache.stats()
        return mapOf(
            "estimatedSize" to statusCache.estimatedSize(),
            "hitCount" to stats.hitCount(),
            "missCount" to stats.missCount(),
            "hitRate" to "%.2f".format(stats.hitRate()),
            "evictionCount" to stats.evictionCount(),
        )
    }

    override fun close() {
        cleanupScope.cancel()

        // Cancel every task job: agents must not keep running against a closed
        // runner (and possibly a closed session) after a rebuild.
        taskScope.cancel()

        // Jobs that never got to run (queued on the mutex, or cancelled before
        // start) have no coroutine inside to mark their status terminal — do it
        // here so no task dies leaving a healthy-looking `created` status.
        runningJobs.keys.forEach { id ->
            statusCache.getIfPresent(id)?.takeIf { !it.isDone }?.let { status ->
                status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
                status.cancelled = true
                status.failureReason = "Runner closed"
                status.message = "Agent task aborted: runner closed"
                persistence.append(status)
                pendingInputs.remove(id)
                recordLifecycle(status, AgentTaskEvent.TYPE_CANCELLED, status.failureReason)
            }
        }
        runningJobs.clear()
        pendingInputs.clear()

        // Best-effort wait for in-flight coroutines to finish marking their
        // statuses; a non-cooperative coroutine cannot block shutdown forever.
        runCatching {
            runBlocking { withTimeoutOrNull(5_000) { taskSupervisor.join() } }
        }

        statusCache.invalidateAll()
        eventLogs.invalidateAll()
        liveInstances.remove(this)
        logger.info("StatefulAgentRunner closed (session={})", session.uuid)
    }

    companion object {
        private const val TERMINAL_PROCESS_STATE = "done"
        private const val INTERRUPTED_REASON = "Interrupted by server restart"

        /**
         * Reason for a non-terminal row with **no live owner** and no evidence of what
         * ended it. Deliberately does not claim a restart: during a live run the only
         * thing known is that the task stopped reporting (`statusFromDisk` used to answer
         * "server restarted" for a NOOP_LIMIT abort whose JVM never restarted).
         */
        private const val UNREPORTED_REASON = "Interrupted before the task reported completion"

        /**
         * Runners are per session, but the JSONL file is global — keep the list of
         * live runners so a compaction can rewrite the union of everyone's statuses
         * instead of clobbering the other runners' rows.
         */
        private val liveInstances = CopyOnWriteArrayList<StatefulAgentRunner>()

        private val sharedPersistences = ConcurrentHashMap<Path, JsonlPersistence<AgentTaskStatus>>()

        /** The ONE persistence instance for [agentPersistencePath] in this process. */
        fun sharedAgentPersistence(): JsonlPersistence<AgentTaskStatus> = sharedPersistences.computeIfAbsent(agentPersistencePath()) { path ->
            JsonlPersistence(
                file = path,
                clazz = AgentTaskStatus::class,
                objectMapper = pulsarObjectMapper()
            )
        }

        /** User-facing reason recorded when a task is cancelled (see [cancel]). */
        const val CANCELLED_MESSAGE = "Task cancelled"

        /** Max cached task statuses per runner (see the status cache's KDoc). */
        const val STATUS_CACHE_SIZE = 1_000L

        /** Min spacing between in-progress status snapshots (see [persistProgress]). */
        const val PROGRESS_PERSIST_INTERVAL_MS = 10_000L

        /**
         * Max tasks whose live step logs stay in memory (see the `eventLogs`
         * field): 128 × at most 1000 events is a few tens of MB worst case, and
         * the whole point of the log is the task the user is WATCHING.
         */
        const val EVENT_LOG_CACHE_SIZE = 128L

        /** Idle minutes after which a task's step log is dropped. */
        const val EVENT_LOG_TTL_MINUTES = 120L

        /**
         * Persistence file size above which the 5-minute maintenance pass rewrites
         * it even when no task has expired. Periodic progress snapshots grow the
         * file without producing stale entries, so staleness alone can no longer
         * be the trigger.
         */
        const val COMPACTION_SIZE_THRESHOLD_BYTES = 4L * 1024 * 1024

        fun agentPersistencePath(): Path = Path.of(
            System.getProperty("browser4.data.dir", System.getProperty("user.home")),
            ".browser4", "data", "agent", "agent-tasks.jsonl"
        )

        /**
         * Look up a task status straight from the disk persistence, without
         * constructing a runner (and therefore a session). Used as the last
         * fallback of status lookups after a restart: `getStatus` used to build a
         * full agent runner — a running status poll for an unknown id with an
         * arbitrary session id created session+runner objects as a side effect.
         *
         * Returns the LAST row for the id. A non-terminal row is reported as an
         * interrupted failure: the file cannot tell if the task later completed
         * in another process, but in this one nobody will ever resume it.
         *
         * The whole file is scanned at most once per file version; the rows are
         * kept in a companion-level snapshot keyed by the file's size+mtime
         * fingerprint. Without it every status poll for an unknown id re-parsed
         * the whole JSONL. The fingerprint is taken BEFORE the scan, so a row
         * appended during a scan cannot be swallowed by a fingerprint that
         * already covers it — the next poll sees a different fingerprint and
         * rescans.
         */
        fun statusFromDisk(id: String): AgentTaskStatus? {
            if (id.isBlank()) return null
            // A live runner's in-memory status is authoritative. The caller is often a
            // DIFFERENT session's runner (the CLI polls `command_status` with no session),
            // and the owner may not have flushed its terminal row yet. Reading the file
            // first turned a live task into a phantom failure: on 2026-10-28 a NOOP_LIMIT
            // abort was reported as "did not complete before the server restarted" 263 ms
            // after the runner had recorded the real reason, and no restart had happened.
            liveInstances.firstNotNullOfOrNull { runCatching { it.getStatus(id) }.getOrNull() }
                ?.let { return it }
            val rows = synchronized(diskScanLock) {
                val fingerprint = diskFileFingerprint()
                val cached = diskScan
                if (cached != null && cached.fingerprint == fingerprint) {
                    cached.rows
                } else {
                    val scanned = HashMap<String, AgentTaskStatus>()
                    sharedAgentPersistence().restore { entry -> scanned[entry.id] = entry }
                    // Last row per id wins (the consumer above overwrites).
                    diskScan = DiskScan(fingerprint, scanned)
                    scanned
                }
            }
            return rows[id]?.let { entry ->
                if (entry.processState == TERMINAL_PROCESS_STATE) entry
                else entry.failed(ResourceStatus.SC_EXPECTATION_FAILED).apply {
                    failureReason = UNREPORTED_REASON
                    message = "Agent task ended before it reported completion — no live runner " +
                        "owns it, and its last written state was still running. The backend may " +
                        "have stopped mid-task; check the backend log for the cause."
                }
            }
        }

        private data class DiskScan(val fingerprint: String?, val rows: Map<String, AgentTaskStatus>)

        @Volatile
        private var diskScan: DiskScan? = null

        private val diskScanLock = Any()

        private fun diskFileFingerprint(): String? = runCatching {
            val file = agentPersistencePath()
            if (!Files.exists(file)) return@runCatching null
            // The path is part of the fingerprint: several persistence files can be
            // active at once (tests use one per temp dir), and two files with equal
            // size and mtime-to-the-millisecond would otherwise share one snapshot.
            "${file.toAbsolutePath()}:${Files.getLastModifiedTime(file).toMillis()}:${Files.size(file)}"
        }.getOrNull()

        /**
         * Returns an independent [AgentHistory] containing only the states produced
         * by the current task.
         *
         * The companion agent's history is cumulative for the whole session and is
         * shared by every task, so handing it out directly leaks prior tasks' steps
         * into a fresh task's status payload. [startIndex] is the history size
         * captured before this task's `agent.run()` call.
         */
        internal fun taskScopedHistory(history: AgentHistory, startIndex: Int): AgentHistory {
            val safeStart = startIndex.coerceIn(0, history.size)
            val states = history.states
                .subList(safeStart, history.size)
                .toMutableList() // detached copy: immune to later appends/trimming
            return AgentHistory(states)
        }
    }
}
