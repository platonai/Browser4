package ai.platon.pulsar.agentic.inference.chat

import ai.platon.pulsar.agentic.inference.RequestTokenLimiter
import ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification as LangChain4jToolSpec
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse

/**
 * Manages multi-turn tool-calling via LangChain4j's native function-calling
 * protocol.
 *
 * The loop:
 * 1. Sends [initialMessages] + [toolSpecifications] to the model.
 * 2. If the response contains [ToolExecutionRequest]s, executes each via
 *    [ToolExecutionCoordinator] and appends the
 *    [AiMessage] + [ToolExecutionResultMessage]s to the conversation.
 * 3. Repeats until the model returns a text-only response or
 *    [maxIterations] is reached.
 *
 * @param model             The [BrowserChatModel] (uses [langChainChat]).
 * @param toolSpecifications INITIAL native tool specs to expose to the LLM —
 *   with [allToolSpecifications] this is the curated starter set; without it,
 *   the full set (legacy behavior).
 * @param coordinator       Executes individual tool-call requests.
 * @param maxIterations     Hard cap on model↔tool round-trips (default 40).
 * @param allToolSpecifications Full tool registry for on-demand disclosure.
 *   When non-empty, the loop additionally exposes the `system.listTools` /
 *   `system.exposeTools` meta tools and lets the model expand the exposed
 *   set at runtime (see [ToolDisclosureTools]).
 * @param disclosureListingLimit Cap on `system.listTools` result lines.
 */
class AgentToolCallLoop(
    private val model: BrowserChatModel,
    private val toolSpecifications: List<LangChain4jToolSpec>,
    private val coordinator: ToolExecutionCoordinator,
    private val maxIterations: Int = 40,
    private val requestTokenLimiter: RequestTokenLimiter = RequestTokenLimiter(),
    private val compressor: ToolLoopCompressor? = null,
    /**
     * Web-context optimization: folds repeated page content (identical
     * snapshots → references, changed views → diffs) at append time so the
     * same page never enters the conversation twice. See
     * [PageViewDeduper] / docs-dev/copilot/web-page-context-optimization-design.md.
     */
    private val pageViewDeduper: PageViewDeduper? = null,
    /**
     * Full tool registry for progressive disclosure. When non-empty, the
     * initial [toolSpecifications] act as a curated starter set and the model
     * can pull in more tools via the always-exposed `system.listTools` /
     * `system.exposeTools` meta tools. When empty, disclosure is off and
     * [toolSpecifications] is the complete set (legacy behavior).
     */
    private val allToolSpecifications: List<LangChain4jToolSpec> = emptyList(),
    private val disclosureListingLimit: Int = 200,
    private val onToolExecuted: () -> Unit = {},
    /** Called for every tool request before execution (name, arguments JSON). */
    private val onToolRequest: (String, String) -> Unit = { _, _ -> },
    /**
     * Called after every executed tool with its request, result message and
     * execution duration — feeds complete run tracing (name, arguments, full
     * result text).
     */
    private val onToolResult: (ToolExecutionRequest, ToolExecutionResultMessage, Long) -> Unit = { _, _, _ -> },
    /**
     * Called with the EXACT message list and the EXACT tool specifications
     * right before each model request — feeds complete prompt logging (system
     * prompt, history, tool results, tool-call messages and tool specs exactly
     * as sent to the LLM: the exposed set plus the disclosure meta tools).
     */
    private val onBeforeGenerate: (List<ChatMessage>, List<LangChain4jToolSpec>) -> Unit = { _, _ -> },
    /**
     * Called right after every model response with the 1-based round number
     * and the raw [ChatResponse] — feeds token-usage persistence (the loop's
     * [onBeforeGenerate] only sees the request; provider usage arrives here).
     */
    private val onModelResponse: (Int, ChatResponse) -> Unit = { _, _ -> },
    /**
     * Context-overflow recovery budget: when the provider rejects a request
     * with a context-window-exceeded error, the loop prunes + compacts once
     * per retry and re-issues the request. Reset to zero on every successful
     * response. 0 disables recovery (deepseek-harness `maxOverflowRetries`).
     */
    private val maxOverflowRetries: Int = 1,
    /**
     * Shared compaction traceability ledger; pass the same instance given to
     * [compressor] and [pageViewDeduper] so references survive compression.
     */
    private val compactionLedger: CompactionLedger? = null,
    /**
     * Called for every executed tool after page-view decoration, with the
     * request, the RAW result and the DECORATED message actually appended to
     * the conversation (identical when no fold/diff happened). Feeds the
     * disk-side page timeline (`page-timeline.jsonl`): URL/title/fingerprint
     * per view survive even after the conversation is compacted.
     */
    private val onToolDecorated: (
        ToolExecutionRequest,
        ToolExecutionResultMessage,
        ToolExecutionResultMessage,
    ) -> Unit = { _, _, _ -> },
) {
    private val logger = getLogger(AgentToolCallLoop::class)

    /** Progressive disclosure state: grows as the model calls system.exposeTools. */
    private val exposedToolSpecs: MutableList<LangChain4jToolSpec> = toolSpecifications.toMutableList()

    private val allToolSpecsByName: Map<String, LangChain4jToolSpec> =
        allToolSpecifications.associateBy { it.name() }

    private val disclosureEnabled: Boolean = allToolSpecifications.isNotEmpty()

    /** Always-exposed meta tools that drive the disclosure protocol. */
    private val metaSpecs: List<LangChain4jToolSpec> =
        if (disclosureEnabled) ToolDisclosureTools.metaSpecs() else emptyList()

    /**
     * Compaction traceability ledger. When the caller shares one instance
     * with [compressor] and [pageViewDeduper] (see
     * docs-dev/copilot/compaction-traceability-design.md), historical
     * references stay resolvable after compression; otherwise a private
     * instance backs only the overflow digest.
     */
    private val ledger: CompactionLedger = compactionLedger ?: CompactionLedger()

    /**
     * Run the tool-calling loop and return a [ModelResponse] containing the
     * final assistant text (still in JSON ActionDescription format, so
     * downstream parsing is unchanged).
     */
    suspend fun generate(initialMessages: List<ChatMessage>): ModelResponse {
        val messages = initialMessages.toMutableList()
        var response: ChatResponse? = null
        // Accumulate real usage across all rounds — previously only the final
        // response's usage was reported, undercounting consumption by every
        // intermediate tool-calling round.
        var totalInput = 0
        var totalOutput = 0
        var totalTotal = 0
        val executedTools = mutableListOf<String>()
        // Provider-confirmed context-window overflow recovery budget: reset on
        // every successful response, consumed by prune+compact retries.
        var overflowRetries = 0

        for (iteration in 0 until maxIterations) {
            // Automatic context compression: prune over-budget tool results,
            // then compact the oldest rounds into a checkpoint when pressure
            // exceeds the threshold. Keeps the loop's O(N²) re-send cost
            // bounded without discarding the recent tail.
            //
            // Runs BEFORE the token-limit check: the limiter must validate the
            // post-compression context, otherwise a halting exception would
            // fire on a list the compressor could have fixed.
            if (compressor != null) {
                val pruned = compressor.pruneToolResults(messages)
                val budgeted = compressor.enforceResultTokenBudget(messages)
                val compacted = compressor.compressIfNeeded(messages)
                if (pruned || budgeted || compacted) {
                    logger.info("🧹 tool-loop compression applied (pruned={}, budgeted={}, compacted={})", pruned, budgeted, compacted)
                    // Compression rewrote history: the deduper's recorded
                    // message indices may be stale — rebuild from the
                    // post-compression conversation on the next decorate().
                    pageViewDeduper?.reset()
                }
            }

            // Halt the task when the accumulated messages would exceed the
            // per-request token limit — tool results can grow the context
            // beyond the model's limit across multi-round loops.
            requestTokenLimiter.enforce(messages)

            // Complete-prompt hook: the message list at this point is EXACTLY
            // what the model will see on this round-trip (post-compression,
            // pre-request), and the specs are EXACTLY the ones the request
            // carries (exposed set + always-on meta tools). Dumping both here
            // preserves the full prompt even when the round later overflows or
            // the caller discards the list.
            onBeforeGenerate(messages, exposedToolSpecs + metaSpecs)

            val request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(exposedToolSpecs + metaSpecs)
                .build()

            response = try {
                model.langChainChat(request, "cta")
            } catch (e: Exception) {
                // Provider-confirmed context-window overflow: recover by
                // pruning + compacting, then retry the SAME request. Any other
                // failure propagates unchanged.
                if (compressor != null && overflowRetries < maxOverflowRetries && isContextOverflowError(e)) {
                    logger.warn(
                        "🧹 context-window overflow on round {} (attempt {}): compacting and retrying",
                        iteration + 1, overflowRetries + 1,
                    )
                    val recovered = compressor.compactForOverflow(messages)
                    if (recovered) {
                        overflowRetries += 1
                        continue
                    }
                    logger.warn("🧹 context-window overflow: compaction produced no reduction; propagating the error")
                }
                throw e
            }
            // A successful response resets the overflow-recovery budget.
            overflowRetries = 0
            onModelResponse(iteration + 1, response)
            val aiMessage = response.aiMessage()
            messages.add(aiMessage)

            response.tokenUsage()?.let { u ->
                totalInput += u.inputTokenCount() ?: 0
                totalOutput += u.outputTokenCount() ?: 0
                totalTotal += u.totalTokenCount() ?: 0
            }

            val toolRequests = aiMessage.toolExecutionRequests()
            if (toolRequests.isNullOrEmpty()) {
                // No more tool calls — return the final response
                logger.debug("Tool loop finished after ${iteration + 1} round(s)")
                return chatResponseToModelResponse(response, totalInput, totalOutput, totalTotal)
            }

            logger.debug("Tool loop round ${iteration + 1}: executing ${toolRequests.size} tool(s)")

            // Execute each tool request and append results
            for (request in toolRequests) {
                onToolRequest(request.name(), request.arguments() ?: "{}")
                val executedAt = System.currentTimeMillis()
                // Progressive disclosure: system.listTools / system.exposeTools
                // are intercepted here — exposeTools mutates exposedToolSpecs,
                // so the very next request carries the expanded specs.
                val resultMessage: ToolExecutionResultMessage = when {
                    !disclosureEnabled -> coordinator.execute(request)
                    request.name() == ToolDisclosureTools.LIST_TOOLS_NAME -> ToolDisclosureTools.listToolsResult(
                        request.id() ?: "", allToolSpecsByName,
                        exposedToolSpecs.map { it.name() }.toSet(),
                        ToolDisclosureTools.parseStringArg(request.arguments(), "domain"),
                        disclosureListingLimit,
                    )
                    request.name() == ToolDisclosureTools.EXPOSE_TOOLS_NAME -> ToolDisclosureTools.exposeToolsResult(
                        request.id() ?: "", request.arguments(), allToolSpecsByName, exposedToolSpecs,
                    )
                    else -> coordinator.execute(request)
                }
                onToolResult(request, resultMessage, System.currentTimeMillis() - executedAt)
                // Web-context optimization: fold repeated page content at
                // append time (identical → reference, changed → diff). The
                // raw result still reaches onToolResult for run tracing.
                val decorated = pageViewDeduper?.decorate(resultMessage, messages) ?: resultMessage
                onToolDecorated(request, resultMessage, decorated)
                messages.add(decorated)
                // Meta tools are protocol plumbing, not task progress: keep
                // them out of the overflow digest.
                if (request.name() !in ToolDisclosureTools.META_NAMES) {
                    executedTools += request.name()
                }
                onToolExecuted()
            }
        }

        // Max iterations exhausted — return last response with error. The error
        // carries the executed-tool names PLUS a bounded digest of the newest
        // results, so the caller can persist real progress instead of discarding
        // a whole step's work (the results list itself dies with the loop).
        logger.warn("Tool loop exceeded max iterations ($maxIterations)")
        val executed = executedTools.distinct().joinToString(", ")
        return chatResponseToModelResponse(response!!, totalInput, totalOutput, totalTotal).let { mr ->
            mr.copy(modelError = OVERFLOW_ERROR_PREFIX + " ($maxIterations)" +
                (if (executed.isNotBlank()) "; executed: $executed" else "") +
                overflowProgressSummary(messages))
        }
    }

    /**
     * Bounded progress digest of the newest tool results for the overflow error:
     * up to [OVERFLOW_SUMMARY_RESULTS] results, first line only (<=200 chars each),
     * whole digest capped at [OVERFLOW_SUMMARY_MAX_CHARS].
     */
    private fun overflowProgressSummary(messages: List<ChatMessage>): String {
        val summaries = messages.filterIsInstance<ToolExecutionResultMessage>()
            .takeLast(OVERFLOW_SUMMARY_RESULTS)
            .map { m ->
                // Reference/diff forms resolve to their original content
                // through the ledger when it is still live in the conversation.
                val text = if (PageViewDeduper.isCompactForm(m.text())) {
                    val resolution = ledger.resolve(m.id())
                    if (resolution is CompactionLedger.Resolution.Live) {
                        messageText(messages.getOrNull(resolution.messageIndex)) ?: m.text()
                    } else {
                        m.text()
                    }
                } else {
                    m.text()
                }
                val firstLine = text.lineSequence().firstOrNull().orEmpty().trim().take(200)
                "${m.toolName()} -> $firstLine"
            }
        return if (summaries.isEmpty()) "" else "; results: " +
            summaries.joinToString(" | ").take(OVERFLOW_SUMMARY_MAX_CHARS)
    }

    /** Best-effort text of any [ChatMessage] for digesting. */
    private fun messageText(message: ChatMessage?): String? = when (message) {
        null -> null
        is SystemMessage -> message.text()
        is UserMessage -> message.singleText()
        is AiMessage -> message.text()
        is ToolExecutionResultMessage -> message.text()
        else -> message.toString()
    }

    private fun chatResponseToModelResponse(
        response: ChatResponse,
        summedInput: Int = 0,
        summedOutput: Int = 0,
        summedTotal: Int = 0,
    ): ModelResponse {
        val content = response.aiMessage().text() ?: ""
        // Prefer summed usage when non-zero (multi-round loop); fall back to the
        // single-response usage for the trivial single-round case.
        val usage = response.tokenUsage()
        val input = if (summedInput > 0) summedInput else (usage?.inputTokenCount() ?: 0)
        val output = if (summedOutput > 0) summedOutput else (usage?.outputTokenCount() ?: 0)
        val total = if (summedTotal > 0) summedTotal else (usage?.totalTokenCount() ?: 0)
        return ModelResponse(
            content = content,
            state = ResponseState.STOP,
            tokenUsage = ai.platon.pulsar.external.TokenUsage(
                inputTokenCount = input,
                outputTokenCount = output,
                totalTokenCount = total,
            ),
        )
    }

    companion object {
        /** Prefix of the modelError set when the loop exhausts [maxIterations]. */
        const val OVERFLOW_ERROR_PREFIX = "Tool call loop exceeded max iterations"

        /** How many newest tool results the overflow digest summarizes. */
        const val OVERFLOW_SUMMARY_RESULTS = 6

        /** Hard cap on the whole overflow result digest. */
        const val OVERFLOW_SUMMARY_MAX_CHARS = 2_000

        /**
         * Loose detection of provider-confirmed context-window overflow across
         * the exception chain. Browser4 has no unified LLM error-code seam, so
         * the check matches common provider error strings (case-insensitive).
         */
        fun isContextOverflowError(error: Throwable): Boolean {
            val haystack = buildString {
                var current: Throwable? = error
                while (current != null) {
                    append(current.message ?: current.javaClass.name).append('\n')
                    current = current.cause
                }
            }.lowercase()
            return CONTEXT_OVERFLOW_KEYWORDS.any { haystack.contains(it) }
        }

        private val CONTEXT_OVERFLOW_KEYWORDS = listOf(
            "context window",
            "context length",
            "context_length_exceeded",
            "maximum context",
            "exceeds the context",
            "too many tokens",
            "token limit exceeded",
        )
    }
}

/**
 * Thrown when the tool-calling loop exhausts its iteration budget AND the
 * operator enabled `browser4.agent.toolLoop.failOnOverflow` — converts the
 * "success with no action" overflow step into an explicit step failure.
 */
class ToolLoopOverflowException(message: String) : IllegalStateException(message)
