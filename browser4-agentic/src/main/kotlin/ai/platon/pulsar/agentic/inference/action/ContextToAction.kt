package ai.platon.pulsar.agentic.inference.action

import ai.platon.pulsar.agentic.event.AgentEventBus
import ai.platon.pulsar.agentic.event.AgenticEvents
import ai.platon.pulsar.agentic.inference.AgentMessageList
import ai.platon.pulsar.agentic.inference.AgentTokenBudget
import ai.platon.pulsar.agentic.inference.forceLlmMaxInputTokenLength
import ai.platon.pulsar.agentic.inference.RequestTokenLimiter
import ai.platon.pulsar.agentic.inference.RequestTokenLimitExceededException
import ai.platon.pulsar.agentic.inference.TokenBudgetExceededException
import ai.platon.pulsar.agentic.inference.ToolExposeMode
import ai.platon.pulsar.agentic.inference.collapseToLegacyString
import ai.platon.pulsar.agentic.inference.toChatMessages
import ai.platon.pulsar.agentic.inference.chat.AgentToolCallLoop
import ai.platon.pulsar.agentic.inference.chat.ToolLoopCompressor
import ai.platon.pulsar.agentic.inference.chat.ToolLoopOverflowException
import ai.platon.pulsar.agentic.model.ActionDescription
import ai.platon.pulsar.agentic.model.ExecutionContext
import ai.platon.pulsar.agentic.tools.AgentToolManager
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.ExperimentalApi
import ai.platon.pulsar.common.brief
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.event.EventBus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.agentic.observability.InferenceMetrics
import ai.platon.pulsar.external.BrowserChatModel
import ai.platon.pulsar.external.ChatModelFactory
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.external.ResponseState
import ai.platon.pulsar.skeleton.llm.TestChatModelFactory
import dev.langchain4j.data.image.Image
import dev.langchain4j.data.message.ImageContent
import dev.langchain4j.data.message.ChatMessage
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
        conf.getLong("browser4.agent.toolLoop.maxIterations", 12).toInt().coerceIn(1, 100)

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
            val compressor = if (toolLoopCompressionEnabled) {
                ToolLoopCompressor(
                    enabled = true,
                    thresholdTokens = toolLoopCompressionThresholdTokens,
                    retainTokens = toolLoopRetainTokens,
                    pruneThresholdChars = toolLoopPruneThresholdChars,
                    pruneHeadChars = toolLoopPruneHeadChars,
                    pruneTailChars = toolLoopPruneTailChars,
                ) { prefix -> summarizeToolLoop(prefix, specs) }
            } else {
                null
            }
            ai.platon.pulsar.agentic.inference.chat.AgentToolCallLoop(
                model = chatModel,
                toolSpecifications = specs,
                coordinator = ai.platon.pulsar.agentic.tools.langchain4j.ToolExecutionCoordinator(
                    toolManager, registry
                ),
                maxIterations = toolLoopMaxIterations,
                requestTokenLimiter = requestTokenLimiter,
                compressor = compressor,
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
    open suspend fun generate(messages: AgentMessageList, context: ExecutionContext): ActionDescription {
        onWillGenerate(context, messages)

        try {
            val instruction = context.instruction

            val response = generateResponseRaw(messages, context.screenshotB64)
            val internalToolsExecuted = lastLoopExecutedTools

            // Opt-in hard failure on tool-loop overflow: with the flag off, the
            // overflow digest flows into the next step's prompt (progress kept);
            // with it on, the step is marked failed instead of "success, no action".
            val overflowError = response.modelError
            if (toolLoopFailOnOverflow &&
                overflowError?.startsWith(AgentToolCallLoop.OVERFLOW_ERROR_PREFIX) == true
            ) {
                throw ToolLoopOverflowException(overflowError)
            }

            val actionDescription = tta.modelResponseToActionDescription(instruction, context.agentState, response)
                .let { ad -> if (internalToolsExecuted) ad.copy(internalToolsExecuted = true) else ad }

            // The copy above creates a new instance when internal tools executed —
            // republish it so the state carries the flag (data-class equality
            // would otherwise trip the require below).
            if (internalToolsExecuted) {
                context.agentState.actionDescription = actionDescription
            }

            require(context.agentState.actionDescription == actionDescription) {
                "Required: context.agentState.actionDescription == actionDescription"
            }

            onDidGenerate(context, messages, actionDescription)

            return actionDescription
        } catch (e: TokenBudgetExceededException) {
            // Must propagate — a budget breach must not be swallowed into an
            // error ActionDescription (which would let the agent loop continue
            // stepping and keep burning tokens).
            throw e
        } catch (e: RequestTokenLimitExceededException) {
            // Must propagate — the task must stop and report status so the
            // user can decide whether to raise the limit and re-launch.
            throw e
        } catch (e: ToolLoopOverflowException) {
            // Must propagate — with browser4.agent.toolLoop.failOnOverflow=true
            // an overflow is a hard step failure that the resolve pipeline
            // retries and then aborts on, instead of "success, no action".
            throw e
        } catch (e: Exception) {
            val errorResponse = ModelResponse("Unknown exception: " + e.brief(), ResponseState.OTHER)
            val actionDescription = ActionDescription(
                context.instruction,
                exception = e,
                modelResponse = errorResponse,
                context = context
            )
            context.agentState.actionDescription = actionDescription
            // Record the failure on the state too — a step whose generation
            // crashed used to stay isSuccess=true in the history.
            context.agentState.exception = e

            return actionDescription
        } finally {
        }
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
        val usage = response.tokenUsage
        val input = usage.inputTokenCount.toLong().coerceAtLeast(0L)
        val output = usage.outputTokenCount.toLong().coerceAtLeast(0L)
        if (input == 0L && output == 0L) return

        val total = tokenBudget.add(input, output)

        // Feed the existing Micrometer metrics — previously never recorded in
        // production, leaving the token gauges at zero.
        runCatching {
            InferenceMetrics.recordTokenUsage(metricsModelName, input.toInt(), output.toInt())
        }

        if (tokenBudget.shouldWarn()) {
            logger.warn("⚠️ token usage at 80%+ of budget: $tokenBudget")
        }

        if (tokenBudget.isExceeded) {
            logger.error(
                "🛑 token budget exceeded: consumed {} tokens, budget {} — aborting agent run",
                total, tokenBudget.maxTotalTokens
            )
            throw TokenBudgetExceededException(total, tokenBudget.maxTotalTokens)
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
    private suspend fun generateResponseRawLegacy(
        messages: AgentMessageList,
        screenshotB64: String? = null,
    ): ModelResponse = withChatTimeout {
        generateResponseRawLegacyUnbounded(messages, screenshotB64)
    }

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
        val lc4jUsage = response.tokenUsage()
        return ModelResponse(
            content = content,
            state = ResponseState.STOP,
            tokenUsage = ai.platon.pulsar.external.TokenUsage(
                inputTokenCount = lc4jUsage?.inputTokenCount() ?: 0,
                outputTokenCount = lc4jUsage?.outputTokenCount() ?: 0,
                totalTokenCount = lc4jUsage?.totalTokenCount() ?: 0,
            ),
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
