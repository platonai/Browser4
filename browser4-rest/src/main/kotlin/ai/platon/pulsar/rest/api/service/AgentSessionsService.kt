package ai.platon.pulsar.rest.api.service

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentSessionContext
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentSessionIndex
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import ai.platon.pulsar.common.getLogger
import org.springframework.stereotype.Service

/**
 * The operator view of the agent sessions this backend remembers: which workspaces they
 * ran in, when, and what one session's context contains.
 *
 * This is what the web console's sidebar draws (`agent.html`): a workspace → session tree
 * where clicking a session switches the page to it and restores its transcript.
 *
 * ## Where the data comes from
 *
 * One row per task, newest per task id ([UserCommandExecutor.agentTaskStatuses]), plus the
 * per-session conversation snapshots ([AgentSessionIndex]). A session is known when either
 * source mentions it: a conversation that outlived its tasks still restores, and a session
 * whose context has expired still lists the tasks it ran.
 *
 * ## What "workspace" means
 *
 * The directory the session's tasks actually used. Only a caller-named directory
 * (`agent run --workspace`, [AgentTaskStatus.workspaceExplicit]) is a durable workspace;
 * every other task writes into its own per-task directory under the agent data root, which
 * is throw-away by design, so those sessions are grouped under the **temporary workspace**
 * instead of inventing a workspace per task id.
 *
 * The grouping is a pure function of the two inputs ([AgentSessionGrouping]) so the rules
 * can be tested without a backend.
 */
@Service
class AgentSessionsService(
    private val commandExecutor: UserCommandExecutor,
    private val sessionIndex: AgentSessionIndexProvider,
) {
    private val logger = getLogger(AgentSessionsService::class)

    /** How long a listing is reused: the sidebar polls, and the task registry is a file. */
    private val cacheMillis = 2_000L

    @Volatile
    private var cachedAt: Long = 0L

    @Volatile
    private var cached: AgentSessionsView? = null

    /**
     * Every workspace with the sessions that ran in it, newest activity first.
     *
     * @param refresh true bypasses the short reuse cache (an explicit "refresh" click).
     */
    fun sessions(refresh: Boolean = false): AgentSessionsView {
        val now = System.currentTimeMillis()
        cached?.let { view ->
            if (!refresh && now - cachedAt <= cacheMillis) return view
        }
        val view = runCatching {
            AgentSessionGrouping.group(
                statuses = commandExecutor.agentTaskStatuses(),
                contexts = sessionIndex.list(),
                liveSessions = commandExecutor.liveAgentSessionIds(),
                now = now,
            )
        }.onFailure { e ->
            // The sidebar must never take the console down: an unreadable registry is
            // reported as an empty tree with the reason in the log.
            logger.warn("Building the agent session tree failed: {}", e.message)
        }.getOrDefault(AgentSessionsView(emptyList(), now))
        cached = view
        cachedAt = now
        return view
    }

    /**
     * One session's restored context, or `null` when this backend does not know it.
     *
     * The response carries both halves an operator needs after clicking the session: the
     * remembered conversation turns (what the next task would inherit) and the tasks it
     * ran (ids, statuses, files, workspace) so a task can still be followed or read.
     */
    fun context(sessionId: String): AgentSessionContextView? {
        val key = sessionId.trim()
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        val statuses = commandExecutor.agentTaskStatuses().filter { it.sessionId?.trim() == key }
        val conversation = sessionIndex.of(key)
        if (statuses.isEmpty() && conversation == null) return null
        val live = commandExecutor.liveAgentSessionIds().contains(key)
        return AgentSessionGrouping.contextOf(key, statuses, conversation, live, now)
    }
}

/**
 * The persisted per-session conversations, exposed as an injectable bean.
 *
 * [AgentSessionsService] must be unit-testable without touching the real data directory, and
 * the index itself reads a process-wide JSONL file — so the indirection is the seam a test
 * replaces with a stub. Production behaviour is exactly [AgentSessionIndex].
 */
@Service
class AgentSessionIndexProvider {

    /** Every remembered session, newest activity first. */
    fun list(): List<AgentSessionContext> = AgentSessionIndex.list()

    /** One session's remembered conversation, or `null` when it is unknown or expired. */
    fun of(sessionId: String): AgentSessionContext? = AgentSessionIndex.of(sessionId)
}

/**
 * The pure grouping rules behind [AgentSessionsService].
 *
 * Kept as an object (not private methods) because the rules are the contract the sidebar
 * depends on: which workspace a session belongs to, when a session is "temporary", and
 * what ordering an operator sees.
 */
internal object AgentSessionGrouping {

    /** Group id of sessions whose tasks never named a durable workspace. */
    const val TEMPORARY_GROUP_ID = "__temporary__"

    /** Display name of the temporary group (the page is Chinese-first). */
    const val TEMPORARY_GROUP_NAME = "临时工作区"

    /** Turns a context response may carry; [AgentSessionIndex.MAX_TURNS] is the hard cap. */
    const val MAX_TURNS = AgentSessionIndex.MAX_TURNS

    /** Tasks a context response may carry (newest first). */
    const val MAX_TASKS = 200

    /** Tasks one session summary may carry. */
    const val MAX_SUMMARY_TASKS = 50

    /**
     * Build the workspace tree.
     *
     * A session is filed under the workspace of its **newest** task that named one; if no
     * task did, it is temporary. That is deliberately one group per session: a session is
     * a conversation, and scattering one conversation across every directory its tasks
     * touched would defeat the point of grouping. The other tasks' directories are still
     * visible on the task rows.
     */
    fun group(
        statuses: List<AgentTaskStatus>,
        contexts: List<AgentSessionContext>,
        liveSessions: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): AgentSessionsView {
        val contextsById = contexts.associateBy { it.sessionId }
        val tasksBySession = statuses
            .mapNotNull { status -> status.sessionId?.trim()?.takeIf { it.isNotEmpty() }?.let { it to status } }
            .groupBy({ it.first }, { it.second })

        val sessionIds = (tasksBySession.keys + contextsById.keys).toSortedSet()
        val groups = LinkedHashMap<String, MutableList<AgentSessionSummary>>()

        sessionIds.forEach { sessionId ->
            val tasks = tasksBySession[sessionId].orEmpty().sortedByDescending { it.lastActivity() }
            val conversation = contextsById[sessionId]
            val explicitWorkspace = tasks
                .firstOrNull { it.workspaceExplicit && !it.workspace.isNullOrBlank() }
                ?.workspace
            val group = workspaceGroupOf(explicitWorkspace)
            val activity = maxOf(
                tasks.firstOrNull()?.lastActivity()?.toEpochMilli() ?: 0L,
                conversation?.updatedAt ?: 0L,
            )
            val summary = AgentSessionSummary(
                sessionId = sessionId,
                live = liveSessions.contains(sessionId),
                running = tasks.any { !it.isDone },
                taskCount = maxOf(tasks.size, conversation?.taskCount ?: 0),
                turnCount = conversation?.turns?.size ?: 0,
                lastActivity = activity,
                workspace = group.path,
                temporary = group.temporary,
                lastTask = tasks.firstOrNull()?.let { taskView(it) },
                tasks = tasks.take(MAX_SUMMARY_TASKS).map { taskView(it) },
            )
            groups.getOrPut(group.id) { mutableListOf() }.add(summary)
        }

        val workspaces = groups.map { (id, sessions) ->
            val first = sessions.first()
            AgentSessionWorkspaceGroup(
                id = id,
                name = if (first.temporary) TEMPORARY_GROUP_NAME else workspaceName(first.workspace),
                path = first.workspace,
                temporary = first.temporary,
                sessions = sessions.sortedByDescending { it.lastActivity },
            )
        }.sortedWith(
            // Durable workspaces first (alphabetically, so the list is stable), the
            // temporary pile last: it is the fallback, not the place to look first.
            compareBy<AgentSessionWorkspaceGroup> { it.temporary }
                .thenBy { it.path?.lowercase().orEmpty() }
        )

        return AgentSessionsView(workspaces = workspaces, generatedAt = now)
    }

    /** One session's restored context. */
    fun contextOf(
        sessionId: String,
        statuses: List<AgentTaskStatus>,
        conversation: AgentSessionContext?,
        live: Boolean,
        now: Long = System.currentTimeMillis(),
    ): AgentSessionContextView {
        val tasks = statuses.sortedByDescending { it.lastActivity() }
        val explicitWorkspace = tasks
            .firstOrNull { it.workspaceExplicit && !it.workspace.isNullOrBlank() }
            ?.workspace
        val group = workspaceGroupOf(explicitWorkspace)
        val turns = (conversation?.turns ?: emptyList())
            .takeLast(MAX_TURNS)
            .map { AgentSessionTurnView(it.role, it.text) }
        val lastActivity = maxOf(
            tasks.firstOrNull()?.lastActivity()?.toEpochMilli() ?: 0L,
            conversation?.updatedAt ?: 0L,
        )
        return AgentSessionContextView(
            sessionId = sessionId,
            workspace = group.path,
            temporary = group.temporary,
            live = live,
            updatedAt = if (lastActivity > 0) lastActivity else now,
            taskCount = maxOf(tasks.size, conversation?.taskCount ?: 0),
            turnCount = turns.size,
            turnsTruncated = conversation?.turnsTruncated == true,
            compressed = conversation?.compressed,
            turns = turns,
            tasks = tasks.take(MAX_TASKS).map { taskView(it) },
        )
    }

    private fun taskView(status: AgentTaskStatus): AgentSessionTaskView {
        // The completion report is what `agent result.filesChanged` shows; the mutation log
        // ([AgentTaskStatus.deliverables]) is the fallback for a run that never reported. Using
        // only the mutation log made a task that plainly wrote two files claim "0 文件" in the
        // sidebar, which is exactly the kind of wrong number an operator would trust.
        val reported = status.agentState?.filesChanged?.size?.takeIf { it > 0 }
        return AgentSessionTaskView(
            id = status.id,
            instruction = status.submittedTask?.takeIf { it.isNotBlank() },
            status = status.processState,
            statusCode = status.statusCode,
            cancelled = status.cancelled,
            workspace = status.workspace,
            workspaceExplicit = status.workspaceExplicit,
            startedAt = status.startedTime?.toEpochMilli(),
            finishedAt = status.finishTime?.toEpochMilli(),
            lastActivity = status.lastActivity().toEpochMilli(),
            filesChanged = reported ?: status.deliverables?.size ?: 0,
        )
    }

    private data class WorkspaceGroup(val id: String, val path: String?, val temporary: Boolean)

    /**
     * The group a task row's directory belongs to.
     *
     * The path is normalized **lexically**, never through [java.nio.file.Path.toAbsolutePath]:
     * the row was written by this machine, so it is already absolute, and resolving it again
     * would (a) depend on the backend's working directory and (b) make the group id change
     * when a deleted directory can no longer be resolved. Separator style and case are folded
     * only for the id, so `D:\Work\Corpus` and `d:/work/corpus/` are one workspace while the
     * display path stays exactly what the task recorded.
     */
    private fun workspaceGroupOf(explicitWorkspace: String?): WorkspaceGroup {
        val raw = explicitWorkspace?.trim()?.takeIf { it.isNotEmpty() }
            ?: return WorkspaceGroup(TEMPORARY_GROUP_ID, null, true)
        val path = raw.trimEnd('/', '\\')
        return WorkspaceGroup(
            id = path.replace('\\', '/').lowercase(),
            path = path,
            temporary = false,
        )
    }

    private fun workspaceName(path: String?): String {
        if (path.isNullOrBlank()) return TEMPORARY_GROUP_NAME
        val tail = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return tail.ifBlank { path }
    }
}

/** The tree the sidebar draws: workspaces first, the temporary pile last. */
data class AgentSessionsView(
    val workspaces: List<AgentSessionWorkspaceGroup>,
    val generatedAt: Long,
)

/** One workspace and the sessions that ran in it. */
data class AgentSessionWorkspaceGroup(
    val id: String,
    val name: String,
    val path: String?,
    val temporary: Boolean,
    val sessions: List<AgentSessionSummary>,
)

/** One session (a cross-task conversation), as the sidebar and the restore view need it. */
data class AgentSessionSummary(
    val sessionId: String,
    val live: Boolean,
    val running: Boolean,
    val taskCount: Int,
    val turnCount: Int,
    val lastActivity: Long,
    val workspace: String?,
    val temporary: Boolean,
    val lastTask: AgentSessionTaskView?,
    val tasks: List<AgentSessionTaskView>,
)

/** One task of a session. */
data class AgentSessionTaskView(
    val id: String,
    val instruction: String?,
    val status: String,
    val statusCode: Int,
    val cancelled: Boolean,
    val workspace: String?,
    val workspaceExplicit: Boolean,
    val startedAt: Long?,
    val finishedAt: Long?,
    val lastActivity: Long,
    val filesChanged: Int,
)

/** One restored session: its remembered conversation plus the tasks it ran. */
data class AgentSessionContextView(
    val sessionId: String,
    val workspace: String?,
    val temporary: Boolean,
    val live: Boolean,
    val updatedAt: Long,
    val taskCount: Int,
    val turnCount: Int,
    val turnsTruncated: Boolean,
    val compressed: String?,
    val turns: List<AgentSessionTurnView>,
    val tasks: List<AgentSessionTaskView>,
)

/** One conversation turn of a restored session. */
data class AgentSessionTurnView(
    val role: String,
    val text: String,
)
