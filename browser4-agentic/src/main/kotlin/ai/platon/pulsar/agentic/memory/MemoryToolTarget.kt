package ai.platon.pulsar.agentic.memory

/**
 * Receiver target of the `memory.*` tools. Holds the [AgentMemory] the tool
 * calls operate on. Per-agent instances are registered by the engine
 * (`AgentToolManager.registerCustomTarget("memory", ...)`); the MCP-level
 * dispatcher falls back to the executor's own backend.
 */
class MemoryToolTarget(
    val memory: AgentMemory,
)
