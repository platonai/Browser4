package ai.platon.pulsar.agentic.tools.advanced.agent

import java.nio.file.Path

/**
 * Read-only view of the sessions the backend remembers, for operator surfaces.
 *
 * The web console (`agent.html`) lists sessions by workspace and restores one when the
 * operator clicks it; the CLI prints the same facts in `agent list`. Both need a
 * projection of two internal stores:
 *
 * - the shared task registry (`async-tasks.jsonl`, [AgentTaskStatus]) — which tasks a
 *   session ran, when, in which workspace, and how they ended;
 * - the per-session conversation log (`agent-session-context.jsonl`,
 *   [SessionConversation]) — what the next task of that session actually inherits.
 *
 * This file exposes the second one without leaking the mutable conversation object: a
 * snapshot is folded to the newest row per session and returned as plain data.
 *
 * ## Why the TTL filter lives here
 *
 * Rows are retained beyond the session TTL so a restart can still restore within it;
 * a sidebar must not therefore list a session whose context has already expired.
 * [DEFAULT_TTL_MINUTES] mirrors the runner's default
 * (`browser4.agent.session.contextTtlMinutes`), and [list] accepts an override so the
 * REST layer can pass the configured value.
 */
object AgentSessionIndex {

    /** Mirrors the runner's `browser4.agent.session.contextTtlMinutes` default. */
    const val DEFAULT_TTL_MINUTES = 720L

    /**
     * Upper bound on the turns one response carries.
     *
     * A long session's transcript is unbounded by design (it is compressed, not
     * deleted), and an operator surface showing the recent history does not need every
     * turn: the newest [MAX_TURNS] are returned and [AgentSessionContext.turnsTruncated]
     * says the rest exists.
     */
    const val MAX_TURNS = 400

    /** The sysprop an operator can use to override [DEFAULT_TTL_MINUTES]. */
    const val TTL_PROPERTY = "browser4.agent.session.contextTtlMinutes"

    /**
     * Every remembered session, newest activity first.
     *
     * @param ttlMinutes sessions whose last write is older than this are dropped;
     *   non-positive means "no filter" (an operator surface that wants the full log).
     * @param now epoch millis, injectable for tests
     * @param persistencePath the conversation log, overridable so tests never touch the real
     *   data directory
     */
    fun list(
        ttlMinutes: Long = configuredTtlMinutes(),
        now: Long = System.currentTimeMillis(),
        persistencePath: Path = SessionConversationStore.defaultPath(),
    ): List<AgentSessionContext> {
        val cutoff = if (ttlMinutes > 0) now - ttlMinutes * 60_000L else Long.MIN_VALUE
        val newest = LinkedHashMap<String, SessionSnapshot>()
        // The file is an append log: the LAST row per session id is the truth.
        for (row in SessionConversationStore.shared(persistencePath).readAll()) {
            if (row.sessionId.isBlank()) continue
            if (row.updatedAt < cutoff) continue
            val seen = newest[row.sessionId]
            if (seen == null || row.updatedAt >= seen.updatedAt) {
                newest[row.sessionId] = row
            }
        }
        return newest.values
            .sortedByDescending { it.updatedAt }
            .map { it.toView() }
    }

    /** One session's remembered conversation, or `null` when it is unknown or expired. */
    fun of(
        sessionId: String,
        ttlMinutes: Long = configuredTtlMinutes(),
        now: Long = System.currentTimeMillis(),
        persistencePath: Path = SessionConversationStore.defaultPath(),
    ): AgentSessionContext? {
        val key = sessionId.trim()
        if (key.isEmpty()) return null
        return list(ttlMinutes, now, persistencePath).firstOrNull { it.sessionId == key }
    }

    /** [DEFAULT_TTL_MINUTES] unless the operator overrode it via [TTL_PROPERTY]. */
    fun configuredTtlMinutes(raw: String? = System.getProperty(TTL_PROPERTY)): Long =
        raw?.trim()?.toLongOrNull() ?: DEFAULT_TTL_MINUTES

    private fun SessionSnapshot.toView(): AgentSessionContext {
        val all = turns.map { AgentSessionTurn(it.role, it.text) }
        val kept = if (all.size > MAX_TURNS) all.subList(all.size - MAX_TURNS, all.size) else all
        return AgentSessionContext(
            sessionId = sessionId,
            updatedAt = updatedAt,
            taskCount = taskCount,
            compressed = compressed,
            turns = kept.toList(),
            turnsTruncated = kept.size < all.size,
        )
    }
}

/**
 * One turn of a session's remembered conversation.
 *
 * @property role `task` | `model` | `tool` | `result` | `user` (a steering line) |
 *   `outcome`, the vocabulary [SessionConversation] records
 * @property text the turn, already collapsed to one bounded line
 */
data class AgentSessionTurn(
    val role: String,
    val text: String,
)

/**
 * A session's persisted cross-task conversation.
 *
 * @property sessionId the transport session id (CLI `-s <name>`, `web-<hex>`, …)
 * @property updatedAt epoch millis of the newest write
 * @property taskCount how many tasks this session already began
 * @property compressed the model-written memory that replaced folded-away turns
 * @property turns the newest remembered turns, oldest first (at most [AgentSessionIndex.MAX_TURNS])
 * @property turnsTruncated true when older turns exist but were not carried
 */
data class AgentSessionContext(
    val sessionId: String,
    val updatedAt: Long,
    val taskCount: Int,
    val compressed: String?,
    val turns: List<AgentSessionTurn>,
    val turnsTruncated: Boolean = false,
)
