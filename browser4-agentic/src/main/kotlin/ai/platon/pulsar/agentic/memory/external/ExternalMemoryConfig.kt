package ai.platon.pulsar.agentic.memory.external

/**
 * Configuration of the L2 external memory MCP bridge (design M4).
 *
 * Aligned with the DSH reference (third-party memory MCP examples): the
 * bridge only starts/stops the server process and routes `tools/call`;
 * account, storage, embeddings and data policy belong to the provider.
 * Default-off (`browser4.agent.memory.external.enabled=false`).
 */
data class ExternalMemoryConfig(
    val enabled: Boolean = false,
    /** `stdio` (default) or `http` (SSE endpoint). */
    val transport: String = "stdio",
    /** stdio: full command line of the external memory server process. */
    val command: String? = null,
    /** http: MCP SSE endpoint of the external memory server. */
    val url: String? = null,
    /** Upper bound for the connect + tool-discovery handshake. */
    val connectTimeoutMs: Long = 30_000,
    /** Domain prefix under which discovered tools are registered for the agent. */
    val toolPrefix: String = "mem",
    /** Optional tool-name allowlist (empty = expose all discovered tools). */
    val toolAllowlist: Set<String> = emptySet(),
) {
    companion object {
        fun fromSystem(): ExternalMemoryConfig = ExternalMemoryConfig(
            enabled = ai.platon.pulsar.agentic.memory.MemoryConfig.externalEnabled,
            transport = ai.platon.pulsar.agentic.memory.MemoryConfig.externalTransport,
            command = ai.platon.pulsar.agentic.memory.MemoryConfig.externalCommand,
            url = ai.platon.pulsar.agentic.memory.MemoryConfig.externalUrl,
            connectTimeoutMs = ai.platon.pulsar.agentic.memory.MemoryConfig.externalConnectTimeoutMs,
            toolPrefix = ai.platon.pulsar.agentic.memory.MemoryConfig.externalToolPrefix,
            toolAllowlist = ai.platon.pulsar.agentic.memory.MemoryConfig.externalToolAllowlist,
        )
    }
}
