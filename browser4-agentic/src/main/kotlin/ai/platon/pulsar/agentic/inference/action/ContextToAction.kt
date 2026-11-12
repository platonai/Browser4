package ai.platon.pulsar.agentic.inference.action

import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.*
import ai.platon.pulsar.agentic.inference.chat.*
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.observability.InferenceMetrics
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.agentic.tools.langchain4j.ToolSpecificationConverter
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import ai.platon.pulsar.external.TokenUsage
import ai.platon.pulsar.skeleton.llm.TestChatModelFactory
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

open class ContextToAction(
    val conf: ImmutableConfig,
    private val toolManager: AgentToolManager? = null,
) {
    private val logger = getLogger(this)

    val baseDir = AppPaths.get("tta")

    /** Conf with the PulsarRPA input-length cap force-raised for deepseek-v4-flash's 1M context window. */
    private val chatModelConf: ImmutableConfig by lazy { conf.forceLlmMaxInputTokenLength() }

    val chatModel: BrowserChatModel
        get() = TestChatModelFactory.getOrCreate(chatModelConf) ?: ChatModelFactory.getOrCreate(chatModelConf)

    val tta = TextToAction(conf)

    /**
     * Per-agent token budget, enforced on every LLM call that flows through
     * [generateResponseRaw]. Prevents runaway agent loops from burning
     * unbounded provider credits.
     */
    val tokenBudget: AgentTokenBudget = AgentTokenBudget.from(conf)

    /**
     * Per-request token limiter — caps the estimated token count of each
     * individual LLM call. Older messages are dropped from the middle of
     * the conversation when the cap is exceeded.
     */
    val requestTokenLimiter: RequestTokenLimiter = RequestTokenLimiter.from(conf)

    /**
     * Model name tag used for [InferenceMetrics] token accounting.
     * Falls back to "default" when no model name is configured.
     */
    private val metricsModelName: String =
        conf.get("openai.model.name") ?: conf.get("openrouter.model.name") ?: "default"

    /** How tools are exposed to the LLM (config-driven). */
    val toolExposeMode: ToolExposeMode = ToolExposeMode.from(conf)

    /**
     * Max model↔tool round-trips inside one native tool-calling turn.
     * 12 by default: multi-tool coding chains (write×N → mvnBuild → test)
     * exceed the legacy 5 and would otherwise restart mid-chain.
     */
    private val toolLoopMaxIterations: Int =
        conf.getLong("browser4.agent.toolLoop.maxIterations", 12).toInt().coerceIn(1, 40)

    /** Automatic tool-loop context compression (mirrors deepseek-harness compaction). */
    private val toolLoopCompressionEnabled: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.compressionEnabled", true)

    /** Estimated-token pressure threshold that triggers region compaction. */
    private val toolLoopCompressionThresholdTokens: Long =
        conf.getLong("browser4.agent.toolLoop.compressionThresholdTokens", 60_000L).coerceAtLeast(1_000L)

    /** Estimated-token budget of the recent tail kept verbatim. */
    private val toolLoopRetainTokens: Long =
        conf.getLong("browser4.agent.toolLoop.retainTokens", 24_000L).coerceAtLeast(1_000L)

    /** Per-result pruning budgets for the model-free phase. */
    private val toolLoopPruneThresholdChars: Int =
        conf.getLong("browser4.agent.toolLoop.pruneThresholdChars", 1_500L).toInt().coerceAtLeast(100)
    private val toolLoopPruneHeadChars: Int =
        conf.getLong("browser4.agent.toolLoop.pruneHeadChars", 800L).toInt().coerceAtLeast(0)
    private val toolLoopPruneTailChars: Int =
        conf.getLong("browser4.agent.toolLoop.pruneTailChars", 400L).toInt().coerceAtLeast(0)

    /** Max output tokens for the compaction summarization request. */
    private val toolLoopSummarizationMaxTokens: Int =
        conf.getLong("browser4.agent.toolLoop.summarizationMaxTokens", 2_048L).toInt().coerceIn(128, 16_384)

    /** Convert a tool-loop overflow step into an explicit step failure (default: keep stepping). */
    private val toolLoopFailOnOverflow: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.failOnOverflow", false)

    /** Web-context optimization: fold repeated page content (references/diffs). */
    private val pageViewDedupEnabled: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.pageViewDedupEnabled", true)
    private val pageViewDiffEnabled: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.pageViewDiffEnabled", true)
    private val pageViewDiffMaxChars: Int =
        conf.getLong("browser4.agent.toolLoop.pageViewDiffMaxChars", 3_000L).toInt().coerceAtLeast(500)
    private val pageViewDigestChars: Int =
        conf.getLong("browser4.agent.toolLoop.pageViewDigestChars", 300L).toInt().coerceAtLeast(100)
    private val duplicateFoldEnabled: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.duplicateFoldEnabled", true)
    private val viewToolNames: Set<String> =
        (conf.get("browser4.agent.toolLoop.viewToolNames")
            ?: "ariaSnapshot,textContent,snapshot,dump,htmlsnapshot,extract")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    /** Keep the round holding the most recent full page view out of compaction. */
    private val retainLatestPageView: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.retainLatestPageView", true)
    /** Cumulative tool-result token budget per request (0 = off). */
    private val maxToolResultTokens: Long =
        conf.getLong("browser4.agent.toolLoop.maxToolResultTokens", 20_000L).coerceAtLeast(0L)
    /**
     * Tool-result names exempt from pruning and the result-token budget —
     * knowledge documents (e.g. `system_skillDoc`) must reach the model whole.
     */
    private val protectedToolNames: Set<String> =
        (conf.get("browser4.agent.toolLoop.protectedToolNames") ?: "system_skillDoc")
            .split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()

    /** Compaction traceability ledger (compaction-traceability-design.md). */
    private val compactionLedgerEnabled: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.compactionLedgerEnabled", true)
    /** Reject a compaction summary no smaller than the content it shadows. */
    private val requireShrink: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.requireShrink", true)
    /** Extra summarization attempts after blank / shrink / structure failures. */
    private val summarizationRetries: Int =
        conf.getLong("browser4.agent.toolLoop.summarizationRetries", 1L).toInt().coerceIn(0, 5)
    /** Context-window overflow recovery retries (0 = off). */
    private val maxOverflowRetries: Int =
        conf.getLong("browser4.agent.toolLoop.maxOverflowRetries", 1L).toInt().coerceIn(0, 5)
    /** Structured audit logging of compaction transactions. */
    private val auditCompaction: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.auditCompaction", true)

    /**
     * Initial tool set for the native tool-calling loop: `core` (default —
     * page/agent/system domains only), `all` (legacy full exposure), or an
     * explicit comma-separated pattern list (`domain.*`, `domain.method`, `method`).
     */
    private val initialToolSet: String =
        conf.get("browser4.agent.toolLoop.initialToolSet") ?: "core"

    /** On-demand tool disclosure (`system.listTools` / `system.exposeTools`). */
    private val toolDisclosureEnabled: Boolean =
        conf.getBoolean("browser4.agent.toolLoop.toolDisclosureEnabled", true)
    private val toolDisclosureListingLimit: Int =
        conf.getLong("browser4.agent.toolLoop.toolDisclosureListingLimit", 200L).toInt().coerceIn(10, 1_000)

    /**
     * Set by the tool-calling loop's [AgentToolCallLoop.onToolExecuted] callback
     * while [generateResponseRawWithLangChain4jUnbounded] runs — tells the
     * caller whether the step executed ≥1 internal tool regardless of whether
     * the final response parsed into a [ToolCall] (overflow steps don't).
     */
    @Volatile
    private var lastLoopExecutedTools = false

    /** Lazy tool-calling loop — engaged only in TOOL_CALLING mode. */
    private val toolCallLoop by lazy {
        if (toolManager == null || !toolExposeMode.nativeToolCalling) {
            null
        } else {
            val specs = toolManager.getLangChain4jToolSpecifications()
            val registry = toolManager.getLangChain4jToolRegistry()
            // Progressive disclosure: expose a curated initial set and let the
            // model pull in the rest on demand (system.listTools/exposeTools).
            // Selection works on ToolSpecs (domain/method intact) and is then
            // converted to the native spec list.
            val initialSpecs = if (toolDisclosureEnabled) {
                ToolSpecificationConverter.toToolSpecifications(
                    ToolDisclosureTools.selectInitialSpecs(
                        toolManager.getAllExposedToolSpecs(), initialToolSet,
                    )
                )
            } else {
                specs
            }
            val disclosureRegistry = if (toolDisclosureEnabled && initialSpecs.size < specs.size) specs else emptyList()
            // One shared traceability ledger across compressor, deduper and
            // loop: references stay resolvable after compression.
            val compactionLedger = CompactionLedger(enabled = compactionLedgerEnabled)
            val compressor = if (toolLoopCompressionEnabled) {
                ToolLoopCompressor(
                    enabled = true,
                    thresholdTokens = toolLoopCompressionThresholdTokens,
                    retainTokens = toolLoopRetainTokens,
                    pruneThresholdChars = toolLoopPruneThresholdChars,
                    pruneHeadChars = toolLoopPruneHeadChars,
                    pruneTailChars = toolLoopPruneTailChars,
                    retainLatestPageView = retainLatestPageView,
                    viewToolNames = viewToolNames,
                    maxResultTokens = maxToolResultTokens,
                    protectedToolNames = protectedToolNames,
                    ledger = compactionLedger,
                    requireShrink = requireShrink,
                    summarizationRetries = summarizationRetries,
                    audit = auditCompaction,
                ) { prefix -> summarizeToolLoop(prefix, specs) }
            } else {
                null
            }
            AgentToolCallLoop(
                model = chatModel,
                toolSpecifications = initialSpecs,
                allToolSpecifications = disclosureRegistry,
                disclosureListingLimit = toolDisclosureListingLimit,
                coordinator = ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator(
                    toolManager, registry
                ),
                maxIterations = toolLoopMaxIterations,
                requestTokenLimiter = requestTokenLimiter,
                // Per-LLM-call timeout (parity with the CLI engine): a hung
                // provider call fails fast; long multi-tool rounds keep
                // progressing instead of dying to a whole-loop budget.
                inferenceTimeoutMs = conf.getLong("browser4.agent.llmInferenceTimeoutMs", 600_000L),
                // Bound the main request's output deterministically.
                maxOutputTokens = conf.getLong("browser4.agent.toolLoop.maxOutputTokens", 8_192L)
                    .toInt().coerceIn(256, 65_536),
                compressor = compressor,
                pageViewDeduper = PageViewDeduper(
                    enabled = pageViewDedupEnabled,
                    diffEnabled = pageViewDiffEnabled,
                    diffMaxChars = pageViewDiffMaxChars,
                    digestChars = pageViewDigestChars,
                    duplicateFoldEnabled = duplicateFoldEnabled,
                    viewToolNames = viewToolNames,
                    ledger = compactionLedger,
                ),
                maxOverflowRetries = maxOverflowRetries,
                compactionLedger = compactionLedger,
                onToolExecuted = { lastLoopExecutedTools = true },
            )
        }
    }

    /**
     * Production summarizer for tool-loop compaction: reuses the conversation's
     * own tool specifications and appends the compaction instruction as the
     * final user message, so the auxiliary call reuses the routed request
     * prefix (mirrors deepseek-harness KV-cache reuse).
     */
    private suspend fun summarizeToolLoop(
        prefix: List<ChatMessage>,
        toolSpecifications: List<dev.langchain4j.agent.tool.ToolSpecification>,
    ): String {
        val request = ChatRequest.builder()
            .messages(prefix + UserMessage.from(ToolLoopCompressor.COMPACTION_INSTRUCTION))
            .toolSpecifications(toolSpecifications)
            .maxOutputTokens(toolLoopSummarizationMaxTokens)
            .build()
        val response = chatModel.langChainChat(request, "cta-compaction")
        return response.aiMessage().text() ?: ""
    }

    /**
     * Tracks whether the configured chat model supports vision (image) input.
     * null = unknown, true = supports images, false = text-only.
     * The default comes from the configured model name: known text-only families
     * (deepseek, o1/o3 reasoning models) reject `image_url` content, so screenshots
     * are skipped from the very first step instead of burning the chat model layer's
     * retries (3 attempts) on an image-bearing call that is doomed to fail.
     * For unknown models the value stays optimistic and is lazily corrected by a
     * failed image-bearing call; cached thereafter.
     */
    private val modelSupportsVision = AtomicBoolean(defaultVisionSupport(metricsModelName))

    /**
     * Set to true once we have made an actual image-bearing call and got a definitive
     * answer; before that, modelSupportsVision is an optimistic default (or a
     * model-name-based prediction, which already counts as resolved).
     */
    private var visionCapabilityResolved = !modelSupportsVision.get()

    /**
     * Returns true if the model is known or assumed to support vision (image) input.
     * Optimistically returns true until proven otherwise by a failed image-bearing call.
     */
    val isVisionSupported: Boolean get() = modelSupportsVision.get()

    init {
        Files.createDirectories(baseDir)
    }

    @ExperimentalApi
    open suspend fun generateResponseRaw(messages: AgentMessageList, screenshotB64: String? = null): ModelResponse {
        // Halt the task when a single request would exceed the per-request
        // token limit — no silent truncation, the operator stays in control.
        requestTokenLimiter.enforce(messages)
        return if (toolExposeMode == ToolExposeMode.TEXT) {
            generateResponseRawLegacy(messages, screenshotB64)
        } else {
            generateResponseRawWithLangChain4j(messages, screenshotB64)
        }.also { response -> accountTokenUsage(response) }
    }

    /**
     * Record real token usage from a [ModelResponse] into the per-agent
     * [tokenBudget] and [InferenceMetrics]. Throws [TokenBudgetExceededException]
     * when the budget is exhausted, halting the agent loop.
     *
     * Resilient to null/zero usage (some providers omit counts on errors).
     */
    private fun accountTokenUsage(response: ModelResponse) {
        // Normalized: the prompt count is cache-inclusive, so a cache read is never
        // charged twice (OpenAI-style) and never dropped (Anthropic-style).
        val usage = response.tokenUsage.normalized()
        val input = usage.inputTokenCount.toLong().coerceAtLeast(0L)
        val output = usage.outputTokenCount.toLong().coerceAtLeast(0L)
        val cached = usage.cachedInputTokenCount?.toLong()?.coerceAtLeast(0L) ?: 0L
        if (input == 0L && output == 0L) return

        // Cache-miss accounting: prompt-cache reads are billed at ~10% and are not
        // charged, so a cached loop no longer burns the budget it did not spend.
        val total = tokenBudget.add(input, output, cached)

        // Feed the existing Micrometer metrics — previously never recorded in
        // production, leaving the token gauges at zero. The cache breakdown is
        // recorded alongside the headline counts.
        runCatching {
            InferenceMetrics.recordTokenUsage(
                metricsModelName, input.toInt(), output.toInt(),
                cachedInputTokens = usage.cachedInputTokenCount,
                cacheWriteInputTokens = usage.cacheWriteInputTokenCount,
            )
        }

        if (tokenBudget.shouldWarn()) {
            logger.warn("⚠️ cache-miss token usage at 80%+ of budget: $tokenBudget")
        }

        if (tokenBudget.isExceeded) {
            logger.error(
                "🛑 cache-miss token budget exceeded: charged {} tokens (cache reads not charged), budget {} — aborting agent run",
                total, tokenBudget.effectiveMaxTotalTokens
            )
            throw TokenBudgetExceededException(
                total,
                tokenBudget.effectiveMaxTotalTokens,
                tokenBudget.consumedCacheReadTokens,
            )
        }
    }

    /**
     * Per-request timeout for every LLM chat call. A hung provider connection
     * (no response for minutes) previously stalled the whole agent loop
     * indefinitely; the timeout turns it into a TimeoutCancellationException
     * that the resolve pipeline treats as a retryable step error.
     * Configurable via `browser4.agent.chat.requestTimeoutMs` (default 5 min).
     */
    private val requestTimeoutMs: Long =
        conf.getLong("browser4.agent.chat.requestTimeoutMs", 300_000L).coerceIn(10_000L, 3_600_000L)

    private suspend fun <T> withChatTimeout(block: suspend () -> T): T =
        kotlinx.coroutines.withTimeout(requestTimeoutMs) { block() }

    /**
     * Legacy TEXT-mode path — byte-identical to the original implementation.
     * Collapses system/user messages to two plain strings and calls
     * [BrowserChatModel.call].
     */
    @Deprecated("Use the CLI tool-loop engine path instead")
    private suspend fun generateResponseRawLegacy(
        messages: AgentMessageList,
        screenshotB64: String? = null,
    ): ModelResponse = withChatTimeout {
        generateResponseRawLegacyUnbounded(messages, screenshotB64)
    }

    @Deprecated("Use the CLI tool-loop engine path instead")
    private suspend fun generateResponseRawLegacyUnbounded(
        messages: AgentMessageList,
        screenshotB64: String? = null,
    ): ModelResponse {
        val systemMessage = messages.systemMessages().joinToString("\n")
        val userMessage = messages.userMessages().joinToString("\n")

        val category = "cta"
        val response = if (screenshotB64 != null && modelSupportsVision.get()) {
            try {
                chatModel.call(
                    systemMessage,
                    userMessage,
                    imageUrl = null,
                    b64Image = screenshotB64,
                    mediaType = "image/jpeg", category = category
                ).also {
                    if (!visionCapabilityResolved) {
                        visionCapabilityResolved = true
                        logger.info("Model supports vision input (image-bearing call succeeded)")
                    }
                }
            } catch (e: Exception) {
                if (isImageNotSupportedError(e)) {
                    logger.warn(
                        "Model does not support image input (image_url rejected); " +
                            "disabling screenshots for this session. " +
                            "Consider using a vision-capable model or setting openai.model.name to a multimodal model."
                    )
                    modelSupportsVision.set(false)
                    visionCapabilityResolved = true
                    chatModel.call(systemMessage, userMessage, category = category)
                } else {
                    throw e
                }
            }
        } else {
            chatModel.call(systemMessage, userMessage, category = category)
        }

        return response
    }

    /**
     * Structured path (CHAT or TOOL_CALLING mode).
     *
     * CHAT: Converts [AgentMessageList] → [ChatMessage]s, optionally
     * attaches screenshot, calls [BrowserChatModel.langChainChat].
     *
     * TOOL_CALLING: Same, but engages [AgentToolCallLoop] for native
     * multi-turn function calling.
     */
    private suspend fun generateResponseRawWithLangChain4j(
        messages: AgentMessageList,
        screenshotB64: String? = null,
    ): ModelResponse = withChatTimeout {
        generateResponseRawWithLangChain4jUnbounded(messages, screenshotB64)
    }

    private suspend fun generateResponseRawWithLangChain4jUnbounded(
        messages: AgentMessageList,
        screenshotB64: String? = null,
    ): ModelResponse {
        var chatMessages = messages.toChatMessages()

        // Attach screenshot to the last user message (vision)
        if (screenshotB64 != null && modelSupportsVision.get()) {
            chatMessages = attachScreenshot(chatMessages, screenshotB64)
        }

        // TOOL_CALLING mode: use the tool-calling loop
        val loop = toolCallLoop
        if (loop != null) {
            return try {
                lastLoopExecutedTools = false
                loop.generate(chatMessages)
            } catch (e: Exception) {
                if (screenshotB64 != null && isImageNotSupportedError(e)) {
                    logger.warn(
                        "Model does not support image input; retrying without screenshot."
                    )
                    modelSupportsVision.set(false)
                    visionCapabilityResolved = true
                    lastLoopExecutedTools = false
                    loop.generate(messages.toChatMessages())
                } else if (isToolSpecUnsupportedError(e)) {
                    // Provider rejects native tool specifications — degrade to the
                    // legacy TEXT path for the rest of the task (design P5.2).
                    if (degradedToTextMode.compareAndSet(false, true)) {
                        logger.warn(
                            "Model/provider does not support native tool calling ({}); " +
                                "degrading to TEXT mode for this task", e.message
                        )
                    }
                    generateResponseRawLegacyUnbounded(messages, screenshotB64)
                } else {
                    throw e
                }
            }
        }

        // CHAT mode: simple langChainChat without tool specifications
        val request = ChatRequest.builder()
            .messages(chatMessages)
            .build()

        val response: ChatResponse = try {
            chatModel.langChainChat(request, "cta")
        } catch (e: Exception) {
            if (screenshotB64 != null && isImageNotSupportedError(e)) {
                logger.warn(
                    "Model does not support image input; retrying without screenshot."
                )
                modelSupportsVision.set(false)
                visionCapabilityResolved = true
                val textOnly = messages.toChatMessages()
                chatModel.langChainChat(
                    ChatRequest.builder().messages(textOnly).build(), "cta"
                )
            } else {
                throw e
            }
        }

        return chatResponseToModelResponse(response)
    }

    /**
     * Attach a base64 screenshot to the last [UserMessage] as [ImageContent].
     */
    private fun attachScreenshot(
        messages: List<dev.langchain4j.data.message.ChatMessage>,
        screenshotB64: String,
    ): List<dev.langchain4j.data.message.ChatMessage> {
        val result = messages.toMutableList()
        val lastUserIdx = result.indices.lastOrNull {
            result[it] is UserMessage
        }
        if (lastUserIdx != null) {
            val userMsg = result[lastUserIdx] as UserMessage
            val builder = UserMessage.Builder()
            userMsg.contents().forEach { builder.addContent(it) }
            builder.addContent(
                ImageContent(
                    Image.builder()
                        .base64Data(screenshotB64)
                        .mimeType("image/jpeg")
                        .build()
                )
            )
            if (userMsg.name() != null) builder.name(userMsg.name())
            result[lastUserIdx] = builder.build()
        } else {
            result.add(
                UserMessage.builder()
                    .addContent(
                        ImageContent(
                            Image.builder()
                                .base64Data(screenshotB64)
                                .mimeType("image/jpeg")
                                .build()
                        )
                    )
                    .build()
            )
        }
        return result
    }

    /**
     * Map a LangChain4j [ChatResponse] to the external [ModelResponse] type
     * that downstream code expects.
     */
    private fun chatResponseToModelResponse(response: ChatResponse): ModelResponse {
        val content = response.aiMessage().text() ?: ""
        // Keep the provider's prompt-cache breakdown and normalize it, so the
        // budget/metrics see one meaning regardless of the provider protocol.
        val usage = TokenUsage.from(response.tokenUsage()).normalized()
        return ModelResponse(
            content = content,
            state = ResponseState.STOP,
            tokenUsage = usage,
        )
    }

    /**
     * Checks whether the given exception was caused by the LLM API rejecting
     * `image_url` content blocks (typical of text-only models like DeepSeek).
     */
    /**
     * Set once the provider rejects native tool specifications — the task
     * degrades to the legacy TEXT path (warn once, then stay degraded).
     */
    private val degradedToTextMode = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Heuristic: does the exception indicate the model/provider does not accept
     * native tool specifications (as opposed to transient/network errors)?
     */
    private fun isToolSpecUnsupportedError(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val msg = (cause.message ?: "").lowercase()
            if ((msg.contains("tool") || msg.contains("function")) &&
                (msg.contains("not supported") || msg.contains("unsupported") ||
                    msg.contains("not allowed") || msg.contains("invalid") ||
                    msg.contains("unknown parameter") || msg.contains("unexpected"))
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    private fun isImageNotSupportedError(e: Exception): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            val msg = cause.message ?: ""
            if ((msg.contains("image_url") || msg.contains("image_url")) &&
                msg.contains("expected") &&
                (msg.contains("text") || msg.contains("unknown variant"))
            ) {
                return true
            }
            cause = cause.cause
        }
        return false
    }

    companion object {
        /**
         * Model-name prefixes known to reject `image_url` content (text-only families).
         * Screenshots are skipped from the first step for these, so no image-bearing
         * request (and its retry storm) is ever sent to them.
         */
        private val TEXT_ONLY_MODEL_PREFIXES = listOf("deepseek", "o1", "o3")

        /**
         * Initial vision-support guess from the configured model name. Known text-only
         * families resolve to false immediately; anything else stays optimistic (true)
         * and is corrected lazily by a failed image-bearing call. `-Dbrowser4.agent.vision.enabled=false`
         * force-disables vision regardless of model.
         */
        private fun defaultVisionSupport(modelName: String?): Boolean {
            if (System.getProperty("browser4.agent.vision.enabled", "true").toBoolean() == false) {
                return false
            }
            val name = modelName?.lowercase()?.trim() ?: return true
            return TEXT_ONLY_MODEL_PREFIXES.none { name.startsWith(it) }
        }
    }

    private fun onWillGenerate(context: ExecutionContext, messages: AgentMessageList) {
        // Emit AgentEventBus inference event
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.ContextToAction.ON_WILL_GENERATE,
            agentId = context.uuid,
            message = "Starting LLM inference",
            metadata = mapOf(
                "context" to context.sid,
                "step" to context.step
            )
        )

        EventBus.emit(
            AgenticEvents.ContextToAction.ON_WILL_GENERATE, mapOf(
                "context" to context,
                "messages" to messages
            )
        )
    }

    private fun onDidGenerate(
        context: ExecutionContext,
        messages: AgentMessageList,
        actionDescription: ActionDescription
    ) {
        val modelResponse = actionDescription.modelResponse!!

        // Emit AgentEventBus inference event
        AgentEventBus.emitInferenceEvent(
            eventType = AgenticEvents.ContextToAction.ON_DID_GENERATE,
            agentId = context.uuid,
            message = "LLM inference completed,",
            metadata = mapOf(
                "context" to context.sid,
                "step" to context.step,
                "inputToken" to modelResponse.tokenUsage.inputTokenCount,
                "outputToken" to modelResponse.tokenUsage.outputTokenCount,
                "totalToken" to modelResponse.tokenUsage.totalTokenCount
            )
        )

        EventBus.emit(
            AgenticEvents.ContextToAction.ON_DID_GENERATE, mapOf(
                "context" to context,
                "messages" to messages,
                "actionDescription" to actionDescription
            )
        )
    }
}
