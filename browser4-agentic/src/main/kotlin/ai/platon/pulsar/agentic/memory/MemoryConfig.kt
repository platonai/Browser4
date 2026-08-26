package ai.platon.pulsar.agentic.memory

/**
 * System-property configuration for the generic agent memory system.
 *
 * All keys follow the `browser4.agent.memory.*` convention and default to
 * conservative values so disabling the system restores pre-memory behavior.
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§9).
 */
object MemoryConfig {

    /** Master switch; `false` disables every write/recall path. */
    val enabled: Boolean get() =
        System.getProperty("browser4.agent.memory.enabled", "true").toBoolean()

    /** Raw event retention (days); older event files move to `.archive`. */
    val logTtlDays: Long get() =
        System.getProperty("browser4.agent.memory.log.ttlDays", "30").toLong().coerceAtLeast(1)

    /** Upper bound (chars) of the run-start recall section injected into the system prompt. */
    val recallMaxChars: Int get() =
        System.getProperty("browser4.agent.memory.recallMaxChars", "2000").toInt().coerceAtLeast(200)

    /** Upper bound of events returned before/after a target event in `readEvent`. */
    val readWindowMax: Int get() =
        System.getProperty("browser4.agent.memory.readWindowMax", "50").toInt().coerceIn(1, 500)

    /** Upper bound (chars) of a search snippet. */
    val snippetChars: Int get() =
        System.getProperty("browser4.agent.memory.snippetChars", "200").toInt().coerceIn(20, 2000)

    /** Upper bound (chars) of the task scratchpad injected as a tail message. */
    val scratchpadMaxChars: Int get() =
        System.getProperty("browser4.agent.memory.scratchpadMaxChars", "1500").toInt().coerceAtLeast(100)

    /** Whether the run-start recall section is injected automatically. */
    val autoRecall: Boolean get() =
        System.getProperty("browser4.agent.memory.autoRecall", "true").toBoolean()

    /** FTS index backend: `sqlite` (default) or `none` (log-only, no search index). */
    val indexBackend: String get() =
        System.getProperty("browser4.agent.memory.index.backend", "sqlite").trim().lowercase()

    /** Whether a search index is active at all. */
    val indexEnabled: Boolean get() = indexBackend != "none" && enabled

    /** L2 external memory MCP bridge (M4; default off). */
    val externalEnabled: Boolean get() =
        System.getProperty("browser4.agent.memory.external.enabled", "false").toBoolean()

    /** External bridge stdio command line (e.g. `npx -y @some/memory-server`). */
    val externalCommand: String? get() =
        System.getProperty("browser4.agent.memory.external.command")?.takeIf { it.isNotBlank() }

    /** External bridge transport: `stdio` (default) or `http` (SSE). */
    val externalTransport: String get() =
        System.getProperty("browser4.agent.memory.external.transport", "stdio").trim().lowercase()

    /** External bridge HTTP endpoint (transport=http), e.g. `http://host:port/mcp/sse`. */
    val externalUrl: String? get() =
        System.getProperty("browser4.agent.memory.external.url")?.takeIf { it.isNotBlank() }

    /** External bridge connect timeout. */
    val externalConnectTimeoutMs: Long get() =
        System.getProperty("browser4.agent.memory.external.timeoutMs", "30000").toLong().coerceAtLeast(1_000)

    /** Domain prefix under which external memory tools are registered. */
    val externalToolPrefix: String get() =
        System.getProperty("browser4.agent.memory.external.toolPrefix", "mem").trim()
            .ifBlank { "mem" }

    /** Optional comma-separated allowlist of external tool names (empty = all). */
    val externalToolAllowlist: Set<String> get() =
        System.getProperty("browser4.agent.memory.external.toolAllowlist")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

    /** Upper bound of the live in-memory event buffer (live-preferred reads). */
    val bufferSize: Int get() =
        System.getProperty("browser4.agent.memory.bufferSize", "5000").toInt().coerceAtLeast(100)

    /** L0→L1 knowledge consolidation (PEM fusion, M3). */
    val consolidationEnabled: Boolean get() =
        System.getProperty("browser4.agent.memory.consolidation.enabled", "true").toBoolean()

    /** Minimum interval between consolidations of the same (domain, intent). */
    val consolidationMinIntervalMinutes: Long get() =
        System.getProperty("browser4.agent.memory.consolidation.minIntervalMinutes", "60")
            .toLong().coerceAtLeast(1)
}

