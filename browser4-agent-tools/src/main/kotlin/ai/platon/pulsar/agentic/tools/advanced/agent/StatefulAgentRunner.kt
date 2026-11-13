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
import ai.platon.pulsar.coding.CodingWorkspace
import ai.platon.pulsar.coding.IsolationGate
import ai.platon.pulsar.coding.RepoLock
import ai.platon.pulsar.coding.SessionWorktree
import ai.platon.pulsar.coding.ShadowBoot
import ai.platon.pulsar.coding.WorkspaceIsolation
import ai.platon.pulsar.coding.WorkspaceManifestStore
import ai.platon.pulsar.coding.WorktreeRetention
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
     * The session's cross-task conversation: a compacted record of the tasks this
     * runner already finished, prepended to the next task's instruction.
     *
     * Every task used to start from zero, so a session of ten related tasks had the
     * user restating the same background ten times. This is the memory that fixes
     * it; [SessionConversation] also owns its budget-driven compression, which is
     * what keeps a long session's prompt from growing without bound.
     *
     * `null` when `browser4.agent.session.contextEnabled=false`.
     */
    private val conversation: SessionConversation? =
        runCatching { SessionConversation.fromConf(session.sessionConfig) }
            .onFailure { logger.warn("session conversation disabled: {}", it.message) }
            .getOrNull()

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
        logger.debug("Created agent task status {} (agentSession={})", status.id, session.uuid)
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
     * Append the terminal failure step, carrying the machine-readable reason.
     *
     * `task.failed` used to be prose only ("TokenBudgetExceededException: …"), so a
     * follower could not branch on the cause and `agent result` had no code to map
     * onto an exit reason.
     */
    private fun recordFailure(status: AgentTaskStatus) {
        val code = status.errorCode ?: AgentTaskStatus.errorCodeOf(status.failureReason, status.cancelled)
        val reason = status.failureReason ?: status.message
        eventLog(status.id).append(
            type = if (status.cancelled) AgentTaskEvent.TYPE_CANCELLED else AgentTaskEvent.TYPE_FAILED,
            text = AgentTaskEventSanitizer.preview(reason),
            errorCode = code,
            resumable = AgentTaskStatus.isResumable(code),
        )
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
            cachedInputTokens = event.cachedInputTokens,
            cacheWriteInputTokens = event.cacheWriteInputTokens,
            budgetTokens = event.budgetTokens,
            cumulativeTokens = event.cumulativeTokens,
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
            "Queued steering message for agent task {} (agentSession={}, {} chars)",
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
        sharedWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        unattended: Boolean = true,
        resumeTaskId: String? = null,
        tokenBudget: Long? = null,
        addDirs: List<Path> = emptyList(),
    ): AgentTaskStatus {
        val status = create()
        execute(
            plainCommand, status, noopLimit, coding, freshWorkspace, sharedWorkspace, access, unattended, resumeTaskId,
            tokenBudget, addDirs,
        )
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
        sharedWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        unattended: Boolean = true,
        resumeTaskId: String? = null,
        tokenBudget: Long? = null,
        addDirs: List<Path> = emptyList(),
    ): Job {
        val job = taskScope.launch(start = CoroutineStart.LAZY) {
            execute(
                plainCommand, status, noopLimit, coding, freshWorkspace, sharedWorkspace, access, unattended,
                resumeTaskId, tokenBudget, addDirs,
            )
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
        logger.info("Cancelling agent task {} (agentSession={})", id, session.uuid)
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
        sharedWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        unattended: Boolean = true,
        resumeTaskId: String? = null,
        tokenBudget: Long? = null,
        addDirs: List<Path> = emptyList(),
    ) {
        try {
            runMutex.withLock {
                executeSerialized(
                    plainCommand, status, noopLimit, coding, freshWorkspace, sharedWorkspace, access, unattended,
                    resumeTaskId, tokenBudget, addDirs,
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

    /**
     * Start the isolated worktree for a coding task (self-development isolation
     * plan §D1), or null when the task must run in its per-task directory.
     *
     * Resume re-attaches to the interrupted task's worktree, so the files it
     * already changed are what the new run continues from. Any other failure to
     * start isolation is logged and degrades to the per-task workspace — never to
     * writing straight into the user's checkout.
     */
    private suspend fun startIsolation(
        taskId: String,
        sessionId: String,
        resumeWorkspace: Path?,
        sharedWorkspace: Boolean?,
    ): IsolationStart {
        if (!CodingWorkspace.isIsolationEnabled()) {
            return IsolationStart(null, null)
        }
        if (sharedWorkspace == true) {
            // A shared directory is not a worktree, and reusing another task's tree is
            // exactly the attribution problem isolation removes. Refuse instead of
            // silently running the coding task in the legacy mirror.
            throw IllegalArgumentException(
                "--shared-workspace cannot be combined with --coding: a coding task runs in its " +
                    "own git worktree and merges the change into the checkout. Drop the flag, or " +
                    "set -Dbrowser4.agent.workspace.isolation=off to run in place."
            )
        }
        val resumable = resumeWorkspace?.takeIf {
            it.toString().replace('\\', '/').contains("/.browser4/worktrees/")
        }
        if (resumable != null) {
            val reopened = runCatching { WorkspaceIsolation.reopen(resumable, taskId) }
                .onFailure { logger.warn("Agent task {}: could not reopen worktree {}: {}", taskId, resumable, it.message) }
                .getOrNull()
            if (reopened != null) {
                // The lock is acquired on the MAIN checkout, not on the worktree.
                val lock = RepoLock.acquire(reopened.repoRoot, taskId)
                sweepWorktrees(reopened.repoRoot, exclude = resumable, taskId = taskId)
                logger.info("Agent task {}: resuming the isolated worktree at {}", taskId, resumable)
                return IsolationStart(reopened, lock)
            }
        }
        val source = runCatching { CodingWorkspace.workspaceRoot }.getOrNull()
            ?: return IsolationStart(null, null)
        if (!WorkspaceIsolation.isAvailable(source)) {
            logger.info("Agent task {}: {} is not a git work tree; running in the per-task workspace", taskId, source)
            return IsolationStart(null, null)
        }
        // Throws when another coding task holds the checkout: running this one
        // unisolated would collide at merge time and in the Maven build cache.
        val lock = RepoLock.acquire(source, taskId)

        // Session-scoped reuse (plan S4-3): only when the previous task merged, and
        // only when the operator opted in. A refused/interrupted task keeps its tree
        // and the new task gets its own — unmerged work must stay inspectable.
        val sessionDir = if (CodingWorkspace.isSessionReuseEnabled()) {
            SessionWorktree.dirFor(source, sessionId)
        } else null
        sweepWorktrees(source, exclude = sessionDir, taskId = taskId)
        if (sessionDir != null && Files.isDirectory(sessionDir)) {
            val reusable = SessionWorktree.isReusable(
                runCatching { WorkspaceManifestStore().read(sessionDir) }.getOrNull()
            )
            if (reusable) {
                val reused = runCatching { WorkspaceIsolation.reopen(sessionDir, taskId) }.getOrNull()
                if (reused != null && reused.rebase()) {
                    logger.info("Agent task {}: reusing the session worktree {}", taskId, sessionDir)
                    return IsolationStart(reused, lock)
                }
                logger.info(
                    "Agent task {}: session worktree {} could not be reset; starting a task worktree",
                    taskId, sessionDir
                )
            } else {
                logger.info(
                    "Agent task {}: the session worktree {} has unmerged work; starting a task worktree",
                    taskId, sessionDir
                )
            }
        }
        val runId = sessionDir?.let { SessionWorktree.runIdFor(sessionId) } ?: taskId
        return runCatching { WorkspaceIsolation.begin(source, runId) }.fold(
            onSuccess = { IsolationStart(it, lock) },
            onFailure = { e ->
                logger.warn(
                    "Agent task {}: workspace isolation could not start ({}); running in the per-task workspace",
                    taskId, e.message
                )
                lock.release()
                IsolationStart(null, null)
            },
        )
    }

    /**
     * Reclaim stale worktrees (isolation plan S4). Runs while this task holds the
     * checkout lock, so nothing else can be using a worktree being removed.
     * Best-effort: a GC failure is logged and must never fail the task.
     */
    private fun sweepWorktrees(repoRoot: Path, exclude: Path?, taskId: String) {
        if (!WorktreeRetention.isEnabled()) return
        runCatching { WorktreeRetention.sweep(repoRoot, exclude = exclude) }
            .onSuccess { if (it.changed) logger.info("Agent task {}: {}", taskId, it.summary()) }
            .onFailure { logger.warn("Agent task {}: worktree gc failed: {}", taskId, it.message) }
    }

    /** One task's isolation handles: the worktree and the checkout lock guarding it. */
    private data class IsolationStart(
        val isolation: WorkspaceIsolation?,
        val lock: RepoLock?,
    )

    /**
     * Gate the isolated change and apply it to the main work tree (§D3/D4).
     *
     * A refused merge fails the task: reporting success while the change exists only
     * in a hidden worktree is exactly the "where did my work go?" failure this design
     * exists to prevent.
     */
    private suspend fun finishIsolation(
        agent: PerceptiveAgent,
        status: AgentTaskStatus,
        isolation: WorkspaceIsolation,
    ) {
        status.sourceRepo = isolation.repoRoot.toString()
        status.isolatedWorkspace = isolation.isolatedRoot.toString()
        status.isolationMode = "worktree"
        val changed = isolation.changedFiles()
        val verdict = (agent as? BasicBrowserAgent)?.lastInnerVerdictJson
        val decision = IsolationGate.evaluate(changed, verdict)
        if (!decision.allow) {
            markIsolationRefused(status, isolation, decision.reason, changed)
            return
        }
        // Boot probe (plan §D9): a change that compiles can still fail to start.
        // Opt-in (`browser4.agent.isolation.shadowBoot`); `auto` probes boot-chain
        // changes only. An unavailable probe is skipped, a failed one refuses the merge.
        val shadow = withContext(Dispatchers.IO) {
            runCatching { ShadowBoot.verify(isolation.repoRoot, isolation.isolatedRoot, changed) }
                .getOrElse { e -> ShadowBoot.Outcome(false, false, "shadow boot failed: ${e.message}") }
        }
        if (!shadow.ok) {
            val reason = if (shadow.logTail.isBlank()) shadow.reason else "${shadow.reason}\n${shadow.logTail}"
            markIsolationRefused(status, isolation, reason, changed)
            return
        }
        if (!shadow.skipped) {
            logger.info("Agent task {}: {}", status.id, shadow.reason)
        }
        val outcome = isolation.applyToMain(status.id)
        status.isolationPatch = outcome.patchPath
        if (!outcome.ok) {
            markIsolationRefused(status, isolation, outcome.message, outcome.changedFiles.ifEmpty { changed })
            return
        }
        status.isolationMerge = "applied"
        logger.info(
            "Agent task {}: merged {} file(s) into {} (patch: {})",
            status.id, outcome.changedFiles.size, isolation.repoRoot, outcome.patchPath
        )
    }

    /** Fail the task and name where the unmerged change lives. */
    private fun markIsolationRefused(
        status: AgentTaskStatus,
        isolation: WorkspaceIsolation,
        reason: String,
        changedFiles: List<String>,
    ) {
        status.isolationMerge = "refused"
        val detail = "Changes were NOT applied to ${isolation.repoRoot}: $reason. " +
            "The isolated worktree is kept at ${isolation.isolatedRoot}."
        logger.warn("Agent task {}: {}", status.id, detail)
        status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
        status.failureReason = detail
        status.message = detail
        status.deliverables = changedFiles.takeIf { it.isNotEmpty() } ?: status.deliverables
    }

    /**
     * The files THIS run wrote, for a run that ended without a completion report.
     *
     * Reads the run's mutation log (the authoritative record, already free of
     * scratch/agent-state paths) and falls back to files modified since the task
     * started. The previous version listed every file in the workspace, so a reused
     * workspace reported earlier tasks' CSVs and reports as this run's deliverables
     * (2026-10-28 baseline).
     */
    private fun currentRunDeliverables(agent: PerceptiveAgent, status: AgentTaskStatus): List<String> {
        val mutations = runCatching {
            (agent as? BasicBrowserAgent)?.agentToolManager?.codingFs?.mutationsSnapshot()
                ?.map { it.path }
                .orEmpty()
        }.getOrDefault(emptyList())
        return deliverableFilesOf(status.workspace, status.startedTime, mutations)
    }

    private suspend fun executeSerialized(
        plainCommand: String,
        status: AgentTaskStatus,
        noopLimit: Int? = null,
        coding: Boolean? = null,
        freshWorkspace: Boolean? = null,
        sharedWorkspace: Boolean? = null,
        access: AccessPreset? = null,
        unattended: Boolean = true,
        resumeTaskId: String? = null,
        tokenBudget: Long? = null,
        addDirs: List<Path> = emptyList(),
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
        // What this session already did, as a compacted `## Session context` block.
        // Empty for the first task of a session (so a one-shot `agent run` is
        // byte-identical to before) and whenever the feature is switched off.
        val sessionBlock = conversation?.renderBlock().orEmpty()
        val effectiveInstruction =
            if (sessionBlock.isEmpty()) instruction else "$instruction\n\n$sessionBlock"
        val codingMode = coding ?: resume?.codingMode ?: false
        val accessPreset = access ?: resume?.accessPreset?.let { AccessPreset.parse(it) }
        // Extra folders granted to THIS task (`agent run --add-dir`). Explicit values win; an
        // omitted flag on a resumed run inherits the interrupted task's grant, the same way the
        // preset and the workspace do — a resume that silently lost its folders would fail on the
        // exact paths the new instruction tells it to continue with.
        val addedDirs: List<Path> = addDirs.ifEmpty {
            resume?.additionalDirs.orEmpty().mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        }

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

        // The task's isolated worktree (coding tasks only). Declared here because the
        // catch/finally paths must still report where the unmerged change lives.
        var isolation: WorkspaceIsolation? = null
        // The checkout lock taken for this task, released in the finally.
        var repoLock: RepoLock? = null

        try {
            status.refresh(ResourceStatus.SC_PROCESSING)
            // Record what this run is: the effective flags decide a later resume's
            // inheritance, and `resumedFrom` links the two task ids.
            status.codingMode = codingMode
            status.accessPreset = accessPreset?.id
            status.addedDirs = addedDirs.map { it.toString() }.takeIf { it.isNotEmpty() }
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
                // Per-task token budget (`agent run --token-budget`): a per-run
                // override, NOT a process-wide config write — the next task in this
                // session (or another session) must keep the configured default. The
                // runner clears it in its finally.
                it.tokenBudgetOverride = tokenBudget
                if (tokenBudget != null) {
                    logger.info("Agent task {} (session={}): token budget overridden to {}", status.id, session.uuid, tokenBudget)
                }
                if (noopLimit != null) {
                    logger.info("Agent task {} (agentSession={}): noop limit overridden to {}", status.id, session.uuid, noopLimit)
                }
                // Per-task workspace isolation (P1-1): EVERY task gets its own
                // workspace, so files from earlier runs can never be mistaken for this
                // run's deliverables (the 2026-10-28 baseline reported a CSV written 36
                // minutes earlier) and `coding_listDir` no longer shows a previous
                // task's 125 scratch scripts. The docs have promised this since
                // agent.md/agent-workspace.md were written; the code only did it for
                // --fresh-workspace.
                //
                //   --resume <id>       → the interrupted task's workspace (its files
                //                         are what the new run continues from)
                //   --shared-workspace  → the old `<baseDir>/workspace` (explicit opt-in;
                //                         that is the agent's initial/restored root)
                //   otherwise           → `<baseDir>/workspace.<taskId>`
                //
                // --fresh-workspace is still accepted and is now the default behaviour;
                // its old `workspace.fresh.*` directory is no longer created.
                //
                // Coding tasks run in a git worktree (self-development isolation plan
                // §D1): the worktree IS the task workspace, so it must be created
                // BEFORE the rebind. Failure to start isolation is logged and falls
                // back to the per-task directory — never to "write straight into the
                // user's checkout".
                //
                // The rebind happens BEFORE the access policy is applied below:
                // `applyAccessPolicy` loads `<workspace>/.browser4/access-policy.json` and
                // rebuilds the coding components from the *current* workspace root, so
                // applying it first read the previous (usually shared) workspace's file.
                // No tool can run between the two calls — the task thread starts after
                // this block — so the components rebuilt here with the previous policy
                // are never dispatched.
                val started = if (codingMode) {
                    startIsolation(status.id, session.uuid.toString(), resume?.workspace, sharedWorkspace)
                } else IsolationStart(null, null)
                repoLock = started.lock
                val startedIsolation = started.isolation
                isolation = startedIsolation
                it.workspaceIsolation = startedIsolation
                val taskWorkspace = startedIsolation?.isolatedRoot
                    ?: taskWorkspaceFor(it.baseDir, status.id, resume?.workspace, sharedWorkspace)
                if (taskWorkspace != null) {
                    java.nio.file.Files.createDirectories(taskWorkspace)
                    it.workspaceDirOverride = taskWorkspace
                    it.agentToolManager.rebindWorkspace(taskWorkspace)
                    logger.info(
                        "Agent task {} (session={}): workspace at {} (resume={}, shared={}, isolation={})",
                        status.id, session.uuid, taskWorkspace, resume != null, sharedWorkspace == true,
                        startedIsolation?.let { "worktree" } ?: "none"
                    )
                } else {
                    logger.info(
                        "Agent task {} (session={}): shared workspace at {} (--shared-workspace)",
                        status.id, session.uuid, it.agentToolManager.workspaceRoot
                    )
                }
                // Per-task access policy (`command.run(access=…, unattended=…)`). Applied
                // before any tool call and after the workspace rebind above, so the policy
                // file and the rebuilt coding components both point at the workspace this
                // task actually runs in. An explicit preset wins; otherwise an unattended
                // job derives the documented default (`full` with network) and a watched
                // TTY run keeps the server-wide policy. The finally below restores the
                // server default either way, so a companion agent shared across tasks
                // never inherits the previous task's grant.
                val basePolicy = when {
                    accessPreset != null -> AgentAccessPolicy.serverDefault.withTaskOverrides(accessPreset)
                    unattended -> AgentAccessPolicy.unattendedDefault
                    else -> AgentAccessPolicy.serverDefault
                }
                // Extra folders (`agent run --add-dir`) join the boundary for THIS task only: the
                // grant is scope, not power — the preset above still decides whether a write there
                // is possible. Applied here, after the workspace rebind, so the boundary is built
                // once for the workspace this task really runs in.
                val effectivePolicy = basePolicy.withAdditionalDirs(addedDirs)
                if (accessPreset == null && unattended) {
                    ai.platon.pulsar.coding.PermissionAudit.writeEvent(
                        tool = "coding.access",
                        code = ai.platon.pulsar.coding.PermissionAudit.CODE_ACCESS_APPROVED,
                        detail = "unattended default: preset=${effectivePolicy.preset.id} " +
                            "allowNetwork=${effectivePolicy.allowNetwork} source=default task=${status.id}",
                    )
                }
                it.agentToolManager.applyAccessPolicy(effectivePolicy, status.id)
                // Explicit coding flag, never auto-detected: the coding flag is
                // opt-in per call (`command_run`'s coding argument, e.g. the CLI's
                // agent run --coding). A resumed run inherits it from its checkpoint.
                it.codingMode = codingMode
                if (codingMode) {
                    logger.info("Agent task {} (agentSession={}): coding mode (explicit) — skipping driver health check and page navigation", status.id, session.uuid)
                }
                // Live step feed and steering for THIS task. Cleared in the finally
                // below: the companion agent outlives the task and is shared by
                // every other caller of the session.
                it.runEventListener = { event -> recordRunEvent(status.id, event) }
                it.steeringSource = {
                    val drained = drainInputs(status.id)
                    // A line typed into a running task is a user instruction — often a
                    // correction ("no, use CSV not XLSX"). It used to die with the run it
                    // steered; the session keeps it so later tasks still honour it.
                    if (drained.isNotEmpty()) {
                        conversation?.noteUserInput(drained)
                    }
                    drained
                }
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
                            logger.debug("Collected event {} for agent task {} (agentSession={})", event.eventType, status.id, session.uuid)
                        }
                    } catch (e: CancellationException) {
                        logger.debug("Event collector cancelled for agent task {}", status.id)
                        throw e
                    } catch (e: Exception) {
                        logger.error("Error collecting events for agent task {} (agentSession={})", status.id, session.uuid, e)
                    }
                }

                try {
                    // Bind server-side agent event handlers to THIS coroutine so multiple commands can run concurrently.
                    AgentEventBus.withServerSideAgentEventHandlers(serverSideAgentEventHandlers) {
                        if (runDispatcher != null) {
                            withContext(runDispatcher) {
                                executeAgentCommand(effectiveInstruction, status, agent, runHasReturned, startIndex, codingMode, freshWorkspace)
                            }
                        } else {
                            executeAgentCommand(effectiveInstruction, status, agent, runHasReturned, startIndex, codingMode, freshWorkspace)
                        }
                    }
                } finally {
                    // Cancel event collector when command completes
                    eventCollectorJob.cancel()
                }
            }

            // Isolation merge (plan S1). Reaching this line means the run itself
            // finished without throwing — the only point at which a patch may reach
            // the main work tree. A refused merge fails the task here, before the
            // finally block publishes the terminal state.
            isolation?.let { iso ->
                runCatching { finishIsolation(agent, status, iso) }
                    .onFailure { e ->
                        logger.warn("Agent task {}: isolation merge failed: {}", status.id, e.message)
                        val changed = runCatching { iso.changedFiles() }.getOrDefault(emptyList())
                        markIsolationRefused(status, iso, "isolation merge failed: ${e.message}", changed)
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
            // the deliverables that are already on disk. Attributed to THIS task —
            // files from earlier runs in a shared workspace are not ours to claim.
            status.deliverables = currentRunDeliverables(agent, status).takeIf { it.isNotEmpty() }
            // The change exists only in the isolated tree: say so, or the caller reads
            // "workspace: <repo>" and finds nothing there.
            isolation?.let { iso ->
                status.sourceRepo = iso.repoRoot.toString()
                status.isolatedWorkspace = iso.isolatedRoot.toString()
                status.isolationMode = "worktree"
                status.isolationMerge = "refused"
            }
            throw e
        } catch (e: Throwable) {
            logger.error("Failed to execute agent command: {} (agentSession={})", plainCommand, session.uuid, e)
            status.failed(ResourceStatus.SC_EXPECTATION_FAILED)
            status.failureReason = "${e.javaClass.simpleName}: ${e.message}"
            status.message = e.message
            // A budget/step abort used to report a bare failure with `filesChanged`
            // empty although the data was already written (2026-10-28: a complete
            // 20×75 CSV, task reported as `failed (417)` with no artifacts).
            status.deliverables = currentRunDeliverables(agent, status).takeIf { it.isNotEmpty() }
            isolation?.let { iso ->
                status.sourceRepo = iso.repoRoot.toString()
                status.isolatedWorkspace = iso.isolatedRoot.toString()
                status.isolationMode = "worktree"
                status.isolationMerge = "refused"
            }
            // Persist the terminal row NOW. The finally block below runs task bookkeeping
            // (endTask, workspace reset, policy reset) before it appends again, and a
            // status poll landing in that window used to read the last RUNNING row from
            // disk — reporting the generic interruption message instead of this reason
            // (observed 2026-10-28: a NOOP_LIMIT abort reported as a server restart).
            runCatching { onStatusChanged(status) }
                .onFailure { logger.warn("persisting failed status for {} failed: {}", status.id, it.message) }
        } finally {
            // Release the checkout lock first: a slow manifest/persistence write must
            // not keep the next coding task blocked on a finished one.
            runCatching { repoLock?.release() }
                .onFailure { logger.warn("could not release the coding lock: {}", it.message) }
            // Reset the per-task overrides: the companion agent is shared with callers
            // outside this runner's mutex (chat, direct agent tools), and a stale
            // --noop-limit override would silently change those runs too.
            (agent as? RobustBrowserAgent)?.let {
                it.noopLimitOverride = null
                // Per-task budget: cleared so the next task in this session goes back
                // to the configured default (a task-level choice must not leak).
                it.tokenBudgetOverride = null
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
                runCatching {
                    it.agentToolManager.endTask(
                        status.id,
                        taskStatus,
                        merge = status.isolationMerge,
                        patch = status.isolationPatch,
                        preRef = isolation?.let { iso -> ai.platon.pulsar.coding.WorkspaceIsolation.preRef(status.id) },
                    )
                }.onFailure { logger.warn("endTask failed for {}: {}", status.id, it.message) }
                // Drop the isolation handle: the companion agent is shared, and a stale
                // worktree reference would make the next task write into this one's tree.
                it.workspaceIsolation = null
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
            // Machine-readable failure semantics (P0-6): the code and the resume
            // affordance are derived from the terminal state while it is still in
            // hand, so `agent result` can exit non-zero with a reason instead of prose
            // only, and the `task.failed` step carries the same code.
            // Assigned unconditionally: a `null` must CLEAR the field instead of
            // leaving the value of an earlier task in place. The agent is shared by
            // every task of an interactive session, and a run that was cancelled (or
            // failed) before it installed its budget reports `null` - a supervisor
            // must see "unknown", never the previous task's limit.
            status.effectiveTokenBudget = (agent as? RobustBrowserAgent)?.lastRunTokenBudget
            status.errorCode = AgentTaskStatus.errorCodeOf(status.failureReason, status.cancelled)
            status.resumable = if (status.cancelled || status.failureReason != null) {
                AgentTaskStatus.isResumable(status.errorCode)
            } else {
                null
            }
            status.done()
            // The record must not carry the prompt prelude: the session block that ran
            // is a few KB and would otherwise multiply into every published snapshot.
            runCatching { stripSessionBlockFromHistory(status.agentHistory) }
                .onFailure { logger.debug("stripping the session block failed for {}: {}", status.id, it.message) }
            onStatusChanged(status)
            // Close the live feed: the terminal step is what makes a follower
            // stop, and the steering queue can never be drained after this point.
            pendingInputs.remove(status.id)
            if (status.cancelled || status.failureReason != null) {
                recordFailure(status)
            } else {
                recordLifecycle(status, AgentTaskEvent.TYPE_DONE, status.message)
            }
            // Remember this task for the rest of the session: the next task's
            // instruction carries a compacted version of it (see [SessionConversation]),
            // so a multi-task session builds on what it already did instead of
            // restarting from zero. Recorded here, in the terminal path, so cancelled
            // and failed runs are remembered too — "that attempt failed this way" is
            // exactly the context the next task needs.
            runCatching { conversation?.record(status) }
                .onFailure { logger.warn("Recording the session conversation failed for {}: {}", status.id, it.message) }
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
        // The prompt may carry blocks this runner appended — a `## Resume context`
        // block and/or this session's `## Session context` — and neither is part of
        // what the user submitted: storing them would nest blocks on a later resume
        // and misreport the task in `agent status`.
        status.submittedTask = stripResumeBlock(stripSessionBlock(plainCommand))

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
        logger.info("StatefulAgentRunner closed (agentSession={})", session.uuid)
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
