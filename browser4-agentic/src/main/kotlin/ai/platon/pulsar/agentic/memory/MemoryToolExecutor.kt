package ai.platon.pulsar.agentic.memory

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
import ai.platon.pulsar.agentic.tools.specs.ToolResultSchemas
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import java.time.Instant
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
                "by keywords. Every hit names the task it came from and an ISO `createdAt`, " +
                "because hits are HISTORIC — not results of the current run — so live data " +
                "must be re-read before it is reported. Use memory.read to fetch details. " +
                "Optionally restrict to one agent uuid (agent).",
            examples = listOf(
                ToolExample(title = "Find past work on a topic", args = mapOf("query" to "amazon price")),
            ),
        )
        toolSpec["read"] = ToolSpec(
            domain = domain, method = "read",
            arguments = listOf(
                // Optional: a bare `memory.read()` means "show me my own task", the
                // call the model actually makes. Declaring taskId required rejected it
                // with "Missing parameter 'taskId' for read" (observed in a live run)
                // even though the current task is always known.
                ToolSpec.Arg("taskId", "String?", "null", "Task whose memory events to read; defaults to the current task."),
                ToolSpec.Arg("seq", "Long?", "null", "Centre the window on this event seq."),
                ToolSpec.Arg("before", "Int", "0", "Events to include before seq."),
                ToolSpec.Arg("after", "Int", "0", "Events to include after seq."),
            ),
            returnType = "String",
            outputSchema = ToolResultSchemas.MEMORY_READ,
            description = "Read a bounded window of memory events of one task " +
                "around the event seq (defaults to the whole task, newest last). " +
                "Omit taskId to read the current task. Bounded by the configured read window.",
            examples = listOf(
                ToolExample(title = "Read a task's events", args = mapOf("taskId" to "<task-id>")),
                ToolExample(title = "Read the current task", args = emptyMap()),
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
                ToolExample(
                    title = "Checkpoint the plan before a long tool chain",
                    args = mapOf(
                        "key" to "plan",
                        "value" to "done: member map of the driver; next: extract the script layer into ChromeScripts.kt",
                    ),
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
        // Provenance, explicitly: a hit is an event of an EARLIER run. Without this
        // the model read a previous run's numbers (prices, rankings, ids) as if it had
        // just collected them — a live run answered a "today" task from yesterday's
        // memory (2026-10-28). Every hit names its task id and an ISO timestamp, and
        // the envelope says what the hits are.
        val currentTaskId = runCatching { memory.currentTaskId }.getOrNull()
        val hits = page.hits.map { hit ->
            val entry = linkedMapOf<String, Any?>(
                "taskId" to hit.taskId,
                "createdAt" to Instant.ofEpochMilli(hit.ts).toString(),
                "ts" to hit.ts,
                "tool" to hit.tool,
                "snippet" to hit.snippet,
                "tier" to hit.tier,
                "fromCurrentTask" to (currentTaskId != null && hit.taskId == currentTaskId),
            )
            entry.filterValues { it != null }
        }
        val envelope = linkedMapOf<String, Any?>(
            "provenance" to "Hits come from EARLIER agent runs (taskId + createdAt). They are " +
                "historical context, not results of the current run: live data (prices, rankings, " +
                "ids) must be re-read from the page before it is used or reported.",
            "hits" to hits,
            "nextCursor" to page.nextCursor,
        )
        return mapper.writeValueAsString(envelope)
    }

    private suspend fun handleRead(memory: AgentMemory, args: Map<String, Any?>): String {
        // Omitted taskId = the task this agent is running right now; a bare
        // `memory.read()` must never fail for lack of an id the model cannot know.
        val taskId = paramString(args, "taskId", "read", required = false)
            ?: memory.currentTaskId
            ?: throw IllegalArgumentException(
                "memory.read needs a taskId: no current task is being observed by this agent"
            )
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
