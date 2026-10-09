package ai.platon.pulsar.rest.api.service.scrape

/**
 * The tool dispatch a [SnapshotFormatStepRunner] needs, and nothing more.
 *
 * The format engine addresses tools by domain + method + arguments, so this seam
 * is deliberately narrow: it lets the runner be exercised against a recording
 * fake instead of a browser, which is the only way to *assert* the arguments it
 * injects — the `expires` window above all (see
 * [SnapshotFormatStepRunner.readOnSnapshot]).
 *
 * Implementations must dispatch through the same executors the MCP layer uses, so
 * an internal format step and a client-driven tool call cannot drift apart. The
 * intended source is `CustomToolRegistry` / `AgentToolManager`, bound to the
 * session the request is running on.
 */
interface FormatToolDispatcher {

    /**
     * Run one tool method.
     *
     * @param domain the tool domain, e.g. [ai.platon.pulsar.agentic.tools.advanced.format.HTML_SNAPSHOT_DOMAIN].
     * @param method the tool method, e.g. `export`.
     * @param args the tool arguments.
     * @return the tool's raw output.
     * @throws Exception when the tool fails. The engine decides whether that
     *   degrades the dependent format or fails the whole request, so a failure
     *   must not be swallowed here.
     */
    suspend fun call(domain: String, method: String, args: Map<String, Any?>): Any?

    /**
     * Whether [method] exists in [domain] in this deployment.
     *
     * The engine asks before calling, so an unsupported method degrades with a
     * warning instead of throwing from inside the dispatch.
     */
    fun supports(domain: String, method: String): Boolean
}
