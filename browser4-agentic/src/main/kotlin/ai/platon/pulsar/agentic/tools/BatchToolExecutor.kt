package ai.platon.pulsar.agentic.tools

import ai.platon.pulsar.agentic.mcp.McpToolNames
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlin.reflect.KClass

/**
 * `batch.run` — the batch primitive MCP does not have (requirement 12).
 *
 * A batch is one tool call carrying many, so a client that would otherwise pay a
 * round trip per step (and lose the ordering guarantee between them) can send
 * `[navigate, click, type, press, extract]` in one request. The execution policy,
 * the per-step envelope and the observability live in [BatchExecutor]; this class
 * only resolves a step's tool name to a `domain.method` call on the session's
 * [AgentToolManager] and runs it.
 *
 * Registered on **both** channels: the private dispatcher reaches it through
 * `CustomToolRegistry` (like crawl/command), the standard server merges the same
 * registry, so `batch_run` is advertised by both.
 */
class BatchToolExecutor(
    /** Shared with the channels, so a batch reuses what a single read cached. */
    private val cache: ToolResultCache = ToolResultCache.shared,
) : AbstractToolExecutor() {

    override val domain: String = "batch"
    override val receiverClass: KClass<*> = AgentToolManager::class

    init {
        toolSpec["run"] = ToolSpec(
            domain = domain,
            method = "run",
            arguments = listOf(
                ToolSpec.Arg(
                    "steps", "List<Map<String, Any>>", null,
                    "Steps to run in order: `[{id?, tool, args}]`. `tool` is any advertised tool name " +
                        "(e.g. `navigate`, `browser_click`, `crawl_submit`). A JSON string is accepted too.",
                ),
                ToolSpec.Arg(
                    "bail", "Boolean", "false",
                    "Stop at the first failing step instead of running the rest.",
                ),
                ToolSpec.Arg(
                    "concurrency", "Int", "1",
                    "Run steps in parallel. Only allowed when every step is a read-only tool " +
                        "(max ${BatchExecutor.MAX_CONCURRENCY}); browser work stays serial.",
                ),
                ToolSpec.Arg(
                    "sessionId", "String?", "null",
                    "Session the steps act on; both channels fill this in when the request carries one.",
                ),
            ),
            returnType = "String",
            description = "Run several tool calls as one batch and return a per-step result envelope.",
            help = """
                batch.run(steps: List<Map<String, Any>>, bail: Boolean = false, concurrency: Int = 1)

                Steps run serially by default, because browser work is stateful: a
                batch that clicks and then reads must observe the click. Set
                concurrency>1 only for batches made entirely of read-only tools, or
                the call is refused instead of silently reordering your steps.

                Each step in the answer carries {index, id, tool, ok, durationMs,
                text, errorCode?, cached?}; the batch carries failureCount,
                stoppedOnError, cached, cachedSteps and durationMs. A batch whose
                steps are all cacheable reads is replayed from the result cache the
                second time it is sent (`cached=true` on the batch and on each step).
            """.trimIndent(),
            examples = listOf(
                ToolExample(
                    title = "Drive a form and read the result in one call",
                    args = mapOf(
                        "steps" to
                            """[{"tool":"navigate","args":{"url":"https://example.com"}},""" +
                            """{"tool":"click","args":{"selector":"#submit"}},""" +
                            """{"tool":"title","args":{}}]""",
                    ),
                ),
                ToolExample(
                    title = "Read four things in parallel",
                    args = mapOf(
                        "steps" to """[{"tool":"title","args":{}},{"tool":"current_url","args":{}}]""",
                        "concurrency" to "2",
                    ),
                    notes = "Allowed because every step is read-only",
                ),
            ),
        )
    }

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName == "run") { "Unsupported batch method: $functionName" }

        val manager = receiver as? AgentToolManager
            ?: throw IllegalArgumentException("batch.run requires an AgentToolManager session receiver")

        val steps = BatchExecutor.parseSteps(stepsArg(args["steps"]))
        val bail = booleanArg(args["bail"])
        val concurrency = intArg(args["concurrency"]) ?: 1
        val sessionId = args["sessionId"]?.toString()?.takeIf { it.isNotBlank() }
        // `cache:false` on the batch bypasses the cache for every step.
        val bypassCache = args["cache"]?.let { booleanArg(it).not() || it.toString().equals("false", true) } ?: false

        val executor = BatchExecutor(
            stepRunner = { step -> runStep(manager, step, sessionId, bypassCache) },
            readOnly = { toolName -> isReadOnly(manager, toolName) },
        )
        val outcome = executor.run(steps, bail, concurrency)
        return pulsarObjectMapper().writeValueAsString(outcome.toMap())
    }

    /**
     * Run one step through the session's tool manager, reusing the result cache.
     *
     * The channels' cache sits *above* the dispatcher, so a step dispatched through
     * the manager would never hit it — a batch of reads would re-run every read on
     * every call. The lookups here use the same cache, key and policy as a single
     * call, which is what makes a read-only batch replayable (requirement 13) while a
     * batch with any state-changing step still invalidates the session's cache and is
     * itself never replayed.
     */
    private suspend fun runStep(
        manager: AgentToolManager,
        step: BatchExecutor.BatchStep,
        sessionId: String?,
        bypassCache: Boolean,
    ): BatchExecutor.BatchStepResult {
        val call = resolveCall(manager, step.tool)
            ?: return BatchExecutor.BatchStepResult.failed(
                "Unknown tool '${step.tool}' in batch step ${step.index}",
                ToolErrorCode.UNKNOWN_TOOL.wire,
            )

        val spec = manager.getToolSpec(call.first, call.second)
        if (spec != null && !bypassCache) {
            cache.get(spec, sessionId, step.args)?.let { cached ->
                ToolInvocationLogger.logCacheHit(step.tool, cached.ageMs)
                return BatchExecutor.BatchStepResult(ok = true, text = cached.text, cached = true)
            }
        }

        val result = manager.execute(ToolCall(call.first, call.second, step.args.toMutableMap()))
        val evaluate = result.evaluate
        val exception = evaluate.exception
        if (exception != null) {
            // Same wording as a single tool call, so a client sees one failure format.
            spec?.let { cache.put(it, sessionId, step.args, "", success = false) }
            return BatchExecutor.BatchStepResult.failed(
                "${step.tool} failed: ${exception.cause?.message ?: exception.expression}",
                ToolErrorMapper.classify(exception.cause).wire,
            )
        }

        val text = ToolResultTextRenderer.render(evaluate)
        spec?.let { cache.put(it, sessionId, step.args, text, success = true) }
        return BatchExecutor.BatchStepResult(ok = true, text = text)
    }

    /**
     * Map an advertised tool name onto `(domain, method)`.
     *
     * Accepts the canonical name (`navigate`), a frontend alias
     * (`browser_snapshot` → `ariaSnapshot`) and the dotted form (`tab.click`),
     * because a batch is written by hand and by a model, and all three spellings
     * appear in the wild. A `browser_`-prefixed name that is not in the alias table
     * falls back to its bare method (`browser_click` → `tab.click`), which is how
     * clients name the convenience tools.
     */
    private fun resolveCall(manager: AgentToolManager, toolName: String): Pair<String, String>? {
        if (toolName.contains('.')) {
            val domain = toolName.substringBefore('.')
            val method = toolName.substringAfter('.')
            if (manager.getToolSpec(domain, method) != null) return domain to method
        }

        McpToolNames.frontendAliases.firstOrNull { it.frontendName == toolName }
            ?.let { alias -> manager.getToolSpec(alias.domain, alias.method)?.let { return alias.domain to alias.method } }

        val candidates = listOf(toolName, toolName.removePrefix("browser_"))
        manager.getAllToolSpecs().forEach { (domain, methods) ->
            methods.keys.forEach { method ->
                if (McpToolNames.toMcpToolName(domain, method) in candidates) return domain to method
            }
        }
        return null
    }

    /** Whether a step may run in parallel with its siblings. */
    private fun isReadOnly(manager: AgentToolManager, toolName: String): Boolean {
        val (domain, method) = resolveCall(manager, toolName) ?: return false
        return ToolRateLimitPolicy.isReadOnly(method) &&
            ToolCachePolicy.ttlMs(manager.getToolSpec(domain, method) ?: return false) != null
    }

    private fun booleanArg(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        else -> value.toString().toBooleanStrictOrNull() ?: false
    }

    /**
     * The `steps` argument, which arrives either as an array or as its JSON text.
     *
     * The standard MCP server hands non-primitive arguments over as JSON text (its
     * documented convention for structured parameters), while the private dispatcher
     * passes the parsed list through: accepting both is what keeps one tool
     * definition working on both channels.
     */
    private fun stepsArg(value: Any?): Any? = when (value) {
        is String -> runCatching { pulsarObjectMapper().readValue(value, List::class.java) }.getOrNull() ?: value
        else -> value
    }

    /** Reads a numeric argument that may arrive as JSON text. */
    private fun intArg(value: Any?): Int? = when (value) {
        null -> null
        is Number -> value.toInt()
        else -> value.toString().toIntOrNull()
    }
}
