package ai.platon.pulsar.agentic.mcp.server

import ai.platon.pulsar.agentic.tools.AgentToolManager

/**
 * Resolves the [AgentToolManager] an MCP tool call must run against.
 *
 * The standard MCP server used to be hard-wired to a single agent: every client
 * and every stream drove the one browser session the server was started with,
 * with no way to say "act on *that* session" — unlike the private dispatcher,
 * which takes an explicit `sessionId` handle.
 *
 * A resolver restores that parity without forcing every deployment to become
 * multi-session:
 *
 * - a plain stdio server keeps [single] semantics (one bound session, the
 *   `sessionId` argument is accepted and ignored)
 * - the Spring-hosted HTTP server resolves `sessionId` against
 *   `PulsarSessionManager`, so an MCP client can drive a session the CLI opened
 *   (and falls back to the server's own session when the argument is omitted)
 */
fun interface ToolManagerResolver {

    /**
     * Resolve the tool manager for [sessionId].
     *
     * @param sessionId the session handle supplied by the client, or `null` when
     *   the call does not address a specific session
     * @return the tool manager to execute against, or `null` when the requested
     *   session does not exist (the caller reports it as a tool error)
     */
    fun resolve(sessionId: String?): AgentToolManager?

    /**
     * Whether this resolver can address sessions other than the server's own.
     *
     * Drives the description of the injected `sessionId` tool argument: a
     * single-session server must not advertise a handle it cannot honor.
     */
    val multiSession: Boolean get() = false

    companion object {
        /**
         * A resolver for a server that owns exactly one session.
         *
         * Any `sessionId` is accepted and ignored: the server has one browser, so
         * there is nothing to disambiguate. [multiSession] stays `false` so the
         * injected `sessionId` argument documents itself as ignored.
         */
        fun single(toolManager: AgentToolManager): ToolManagerResolver =
            ToolManagerResolver { toolManager }

        /**
         * A resolver backed by a session lookup (e.g. `PulsarSessionManager`).
         *
         * [defaultToolManager] serves calls that omit `sessionId`; [lookup]
         * resolves an explicit handle and returns `null` when it is unknown.
         */
        fun of(
            defaultToolManager: () -> AgentToolManager?,
            lookup: (String) -> AgentToolManager?,
        ): ToolManagerResolver = object : ToolManagerResolver {
            override fun resolve(sessionId: String?): AgentToolManager? =
                if (sessionId.isNullOrBlank()) defaultToolManager() else lookup(sessionId)

            override val multiSession: Boolean get() = true
        }
    }
}
