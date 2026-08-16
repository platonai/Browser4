package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.coding.TokenEstimator
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage

/**
 * Thrown when a single LLM request's estimated token count exceeds the
 * per-request limit.
 *
 * Extends [IllegalStateException] so it is classified as a permanent
 * (non-retryable) error — retrying would exceed the limit again. Callers
 * must stop the task, report the status and wait for the user to raise
 * [RequestTokenLimiter.CONFIG_KEY] before re-launching.
 */
class RequestTokenLimitExceededException(
    val estimatedTokens: Long,
    val maxTokens: Int,
) : IllegalStateException(
    "Per-request token limit exceeded: estimated ${String.format("%,d", estimatedTokens)} tokens " +
        "for a single LLM request, limit ${String.format("%,d", maxTokens.toLong())}. " +
        "Task halted. To continue, raise '${RequestTokenLimiter.CONFIG_KEY}' in configuration " +
        "and re-launch the task."
)

/**
 * Caps the estimated token count of messages sent to the LLM **per request**.
 *
 * Unlike truncation-based limiters, this one **halts** the task when the
 * estimate exceeds [maxTokens]: [enforce] throws
 * [RequestTokenLimitExceededException], the agent run stops with a failed
 * status, the breach is logged, and the user decides whether to raise the
 * limit and re-launch. Silent content dropping is deliberately avoided —
 * for this product the operator must stay in control of what the LLM sees.
 *
 * This is distinct from [AgentTokenBudget], which caps cumulative tokens
 * across an entire agent run. [RequestTokenLimiter] caps each individual
 * LLM request.
 *
 * @param maxTokens maximum estimated tokens per LLM request. `0` = disabled.
 */
class RequestTokenLimiter(
    val maxTokens: Int = DEFAULT_MAX_REQUEST_TOKENS,
) {
    private val logger = getLogger(RequestTokenLimiter::class)

    /** True when the limiter is active. */
    val enabled: Boolean get() = maxTokens > 0

    /**
     * Verify an [AgentMessageList] fits within [maxTokens] (estimated).
     * Throws [RequestTokenLimitExceededException] when it does not.
     */
    fun enforce(messages: AgentMessageList) {
        if (!enabled || messages.messages.isEmpty()) return
        val total = messages.messages.sumOf { TokenEstimator.estimateTokens(it.content) }
        checkTotal(total, messages.messages.size)
    }

    /**
     * Verify a list of LangChain4j [ChatMessage]s fits within [maxTokens]
     * (estimated). Used by
     * [ai.platon.pulsar.agentic.inference.chat.AgentToolCallLoop] to cap the
     * growing message list during multi-turn tool calling.
     * Throws [RequestTokenLimitExceededException] when it does not.
     */
    fun enforce(messages: List<ChatMessage>) {
        if (!enabled || messages.isEmpty()) return
        val total = messages.sumOf { estimateChatMessageTokens(it) }
        checkTotal(total, messages.size)
    }

    private fun checkTotal(total: Long, messageCount: Int) {
        if (total <= maxTokens) return
        logger.error(
            "🛑 per-request token limit exceeded: {} est. tokens across {} messages > max {} " +
                "— halting task; raise '{}' to continue",
            total, messageCount, maxTokens, CONFIG_KEY
        )
        throw RequestTokenLimitExceededException(total, maxTokens)
    }

    /**
     * Extract a best-effort text representation of a [ChatMessage] for token
     * estimation. Image content is not counted (fixed overhead).
     */
    private fun estimateChatMessageTokens(msg: ChatMessage): Long {
        val text = when (msg) {
            is SystemMessage -> msg.text()
            is UserMessage -> msg.singleText() ?: ""
            is AiMessage -> msg.text() ?: ""
            is ToolExecutionResultMessage -> msg.text()
            else -> msg.toString()
        }
        return TokenEstimator.estimateTokens(text)
    }

    companion object {
        /** Configuration key for the per-request token limit. */
        const val CONFIG_KEY = "agent.llm.maxRequestTokens"

        /**
         * Default: 500,000 estimated tokens per LLM request. A typical browser
         * agent observe/act step sends 5K-30K tokens; 500K leaves generous
         * headroom for large page content while still catching runaway
         * context growth (e.g. accumulated DOM snapshots or tool results).
         */
        const val DEFAULT_MAX_REQUEST_TOKENS = 500_000

        /**
         * Build a limiter from configuration.
         *
         * Accepted values for [CONFIG_KEY]:
         * - a positive number of tokens (e.g. `800000`)
         * - `0`, `-1` or `unlimited` — disable enforcement
         * - absent — [DEFAULT_MAX_REQUEST_TOKENS]
         */
        fun from(conf: ImmutableConfig): RequestTokenLimiter {
            val raw = conf.get(CONFIG_KEY)?.trim()
            return RequestTokenLimiter(parseMaxTokens(raw))
        }

        /**
         * Parse a raw config string into a max-tokens value.
         *
         * Extracted from [from] for direct unit testing without an [ImmutableConfig] instance.
         */
        fun parseMaxTokens(raw: String?): Int {
            val s = raw?.trim()
            return when {
                s.isNullOrEmpty() -> DEFAULT_MAX_REQUEST_TOKENS
                s.equals("unlimited", ignoreCase = true) -> 0
                else -> s.toIntOrNull()?.let { if (it <= 0) 0 else it }
                    ?: DEFAULT_MAX_REQUEST_TOKENS
            }
        }
    }
}
