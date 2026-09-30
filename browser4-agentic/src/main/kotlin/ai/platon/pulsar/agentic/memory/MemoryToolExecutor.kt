package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
import ai.platon.pulsar.agentic.tools.specs.ToolResultSchemas
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlin.reflect.KClass

/**
 * Model-visible `memory.*` tools (explicit, scoped — never implicit):
 *
 * | Method | Purpose |
 * |--------|---------|
 * | `memory.search` | keyword search over the memory (facts) |
 * | `memory.read` | bounded event window of one task |
 * | `memory.note` | write the working-memory scratchpad |
 * | `memory.forget` | explicitly forget one task (privacy / correction) |
 *
 * The executor is stateless: the receiver ([MemoryToolTarget]) carries the
 * per-agent [AgentMemory]; [fallbackMemory] serves MCP-level dispatch where
 * the receiver is not a [MemoryToolTarget].
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§7.1).
 */
class MemoryToolExecutor(
    private val fallbackMemory: AgentMemory? = null,
) : AbstractToolExecutor(), ToolCallSpecificationProvider {

    override val domain = "memory"
    override val receiverClass: KClass<*> = MemoryToolTarget::class

    /** Falls back to [fallbackMemory] when no [MemoryToolTarget] receiver is bound. */
    override val requiresReceiver: Boolean = false

    private val mapper = pulsarObjectMapper()

    /**
     * Feed the registry/prompt: without this, a Spring-side registration
     * (`MemoryToolMountConfiguration` → PluginManager → CustomToolRegistry)
     * would carry NO specs, and the engine's "already registered" guard would
     * then skip its own spec registration — the model would never see
     * memory.* tools (observed in real-environment e2e).
     */
    override fun getToolCallSpecifications(): List<ToolSpec> = getToolSpecs().values.toList()

    init {
        toolSpec["search"] = ToolSpec(
            domain = domain, method = "search",
            arguments = listOf(
                ToolSpec.Arg("query", "String", null, "Keywords to search past tasks and tool executions for."),
                // Optional without a default: `"String?"` + `"null"` is the
                // convention for that. A bare `null` default means *required*, and
                // declaring `agent` that way made the validator reject a
                // query-only search that the executor handles happily.
                ToolSpec.Arg("agent", "String?", "null", "Restrict the search to one agent uuid."),
                ToolSpec.Arg("limit", "Int", "10", "Maximum number of hits (1-50)."),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.MEMORY_SEARCH,
            description = "Search agent memory (past tasks and tool executions) " +
                "by keywords. Returns hits with taskId, timestamp, tool and a " +
                "snippet; use memory.read to fetch details. Optionally restrict " +
                "to one agent uuid (agent).",
            examples = listOf(
                ToolExample(title = "Find past work on a topic", args = mapOf("query" to "amazon price")),
            ),
        )
        toolSpec["read"] = ToolSpec(
            domain = domain, method = "read",
            arguments = listOf(
                ToolSpec.Arg("taskId", "String", null, "Task whose memory events to read."),
                ToolSpec.Arg("seq", "Long?", "null", "Centre the window on this event seq."),
                ToolSpec.Arg("before", "Int", "0", "Events to include before seq."),
                ToolSpec.Arg("after", "Int", "0", "Events to include after seq."),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.MEMORY_READ,
            description = "Read a bounded window of memory events of one task " +
                "around the event seq (defaults to the whole task, newest last). " +
                "Bounded by the configured read window.",
            examples = listOf(
                ToolExample(title = "Read a task's events", args = mapOf("taskId" to "<task-id>")),
            ),
        )
        toolSpec["note"] = ToolSpec(
            domain = domain, method = "note",
            arguments = listOf(
                ToolSpec.Arg("key", "String"),
                ToolSpec.Arg("value", "String"),
                ToolSpec.Arg("taskId", "String?", "null", "Task the note belongs to; defaults to the current task."),
            ),
            returnType = "String",
            description = "Write one working-memory note. Notes are re-injected " +
                "into the conversation every round and survive context " +
                "compression — use them for stable cross-step conclusions, " +
                "confirmed assumptions, and pending todos.",
            examples = listOf(
                ToolExample(
                    title = "Remember a conclusion",
                    args = mapOf("key" to "price", "value" to "the product costs 19.99"),
                ),
            ),
        )
        toolSpec["forget"] = ToolSpec(
            domain = domain, method = "forget",
            arguments = listOf(
                ToolSpec.Arg("taskId", "String"),
            ),
            returnType = "String",
            description = "Explicitly forget one task from memory (privacy / " +
                "correction). Removes its events from the log and the search index.",
            examples = listOf(
                ToolExample(title = "Forget a task", args = mapOf("taskId" to "<task-id>")),
            ),
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any,
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }

        val memory = (receiver as? MemoryToolTarget)?.memory
            ?: fallbackMemory
            ?: throw IllegalStateException("No memory backend available for domain 'memory'")

        return when (functionName) {
            "search" -> handleSearch(memory, args)
            "read" -> handleRead(memory, args)
            "note" -> handleNote(memory, args)
            "forget" -> handleForget(memory, args)
            else -> throw IllegalArgumentException("Unsupported memory method: $functionName(${args.keys})")
        }
    }

    /** Close the owned fallback backend (MCP-level shared memory), if any. */
    fun close() {
        runCatching { fallbackMemory?.close() }
    }

    private suspend fun handleSearch(memory: AgentMemory, args: Map<String, Any?>): String {
        val query = paramString(args, "query", "search")!!
        val agent = paramString(args, "agent", "search", required = false)
        val limit = paramInt(args, "limit", "search", required = false, default = 10) ?: 10
        val page = memory.queryService.searchEvents(
            query, SearchFilters(agentUuid = agent), limit = limit.coerceIn(1, 50),
        )
        return mapper.writeValueAsString(page)
    }

    private suspend fun handleRead(memory: AgentMemory, args: Map<String, Any?>): String {
        val taskId = paramString(args, "taskId", "read")!!
        val seq = paramLong(args, "seq", "read", required = false)
        val before = paramInt(args, "before", "read", required = false, default = 0) ?: 0
        val after = paramInt(args, "after", "read", required = false, default = 0) ?: 0
        val window = if (seq != null) {
            memory.queryService.readEvent(taskId, seq, before, after)
        } else {
            EventWindow(taskId, memory.queryService.traceTask(taskId))
        }
        return mapper.writeValueAsString(window)
    }

    private fun handleNote(memory: AgentMemory, args: Map<String, Any?>): String {
        val key = paramString(args, "key", "note")!!
        val value = paramString(args, "value", "note")!!
        val taskId = paramString(args, "taskId", "note", required = false)
        return memory.note(key, value, taskId)
    }

    private suspend fun handleForget(memory: AgentMemory, args: Map<String, Any?>): String {
        val taskId = paramString(args, "taskId", "forget")!!
        memory.queryService.forget(taskId)
        return "Forgotten task: $taskId"
    }
}
