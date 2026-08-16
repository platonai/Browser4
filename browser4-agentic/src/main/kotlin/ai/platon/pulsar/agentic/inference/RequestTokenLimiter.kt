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
 * Caps the estimated token count of messages sent to the LLM **per request**.
 *
 * When the total exceeds [maxTokens], older messages are dropped from the
 * middle of the conversation while preserving system messages and the most
 * recent user/tool messages. This prevents a single LLM call from consuming
 * an excessive context window — e.g. when accumulated DOM snapshots, tool
 * results, or page content grow beyond the model's context limit.
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
     * Truncate an [AgentMessageList] to fit within [maxTokens] (estimated).
     *
     * Strategy:
     * 1. Keep all system messages (instructions, tool descriptions — critical, usually small).
     * 2. Keep the last non-system message (current instruction/snapshot).
     * 3. Add older non-system messages from newest to oldest until budget is exhausted.
     * 4. If the last non-system message alone exceeds the remaining budget,
     *    truncate its content from the front (keeping the tail — the most
     *    recent/relevant part).
     */
    fun truncate(messages: AgentMessageList): AgentMessageList {
        if (!enabled || messages.messages.isEmpty()) return messages

        val msgs = messages.messages
        val tokens = msgs.map { TokenEstimator.estimateTokens(it.content) }
        val total = tokens.sum()
        if (total <= maxTokens) return messages

        val systemIdx = msgs.indices.filter { msgs[it].role == "system" }
        val nonSystemIdx = msgs.indices.filter { msgs[it].role != "system" }

        val systemTokens = systemIdx.sumOf { tokens[it] }
        val budgetForNonSystem = maxTokens - systemTokens

        if (nonSystemIdx.isEmpty() || budgetForNonSystem <= 0) {
            // System messages alone exceed budget — keep system + truncate last system msg
            logger.warn("Request token limit: system messages alone est. {} tokens > max {}", systemTokens, maxTokens)
            return keepBestEffort(msgs, tokens, maxTokens.toLong())
        }

        // Always keep the last non-system message
        val lastIdx = nonSystemIdx.last()
        val lastTokens = tokens[lastIdx]
        val keptNonSystem = mutableSetOf(lastIdx)
        var remaining = budgetForNonSystem - lastTokens

        if (remaining < 0) {
            // Last message alone exceeds budget — truncate its content, keep tail
            val result = AgentMessageList()
            systemIdx.forEach { result.addLast(msgs[it]) }
            val truncated = truncateContentTail(msgs[lastIdx].content, lastTokens, budgetForNonSystem)
            val orig = msgs[lastIdx]
            result.addLast(SimpleMessage(orig.role, truncated, orig.name, orig.toolCallId, orig.toolName))
            logger.info(
                "Request token limit: {} est. tokens, kept system({}) + last msg truncated (max {})",
                total, systemTokens, maxTokens
            )
            return result
        }

        // Add older non-system messages newest→oldest until budget exhausted
        for (i in (nonSystemIdx.size - 2) downTo 0) {
            val idx = nonSystemIdx[i]
            val t = tokens[idx]
            if (t <= remaining) {
                keptNonSystem.add(idx)
                remaining -= t
            }
        }

        val allKept = (systemIdx + keptNonSystem).sorted()
        val result = AgentMessageList()
        allKept.forEach { result.addLast(msgs[it]) }

        val droppedCount = msgs.size - allKept.size
        logger.info(
            "Request token limit: {} est. tokens, kept {}/{} messages, dropped {} (max {})",
            total, allKept.size, msgs.size, droppedCount, maxTokens
        )
        return result
    }

    /**
     * Truncate a list of LangChain4j [ChatMessage]s to fit within [maxTokens].
     *
     * Used by [ai.platon.pulsar.agentic.inference.chat.AgentToolCallLoop] to
     * cap the growing message list during multi-turn tool calling.
     *
     * Strategy: keep all [SystemMessage]s, then keep non-system messages from
     * the end (most recent) until budget is exhausted. At least one non-system
     * message is always kept.
     */
    fun truncate(messages: List<ChatMessage>): List<ChatMessage> {
        if (!enabled || messages.isEmpty()) return messages

        val tokens = messages.map { estimateChatMessageTokens(it) }
        val total = tokens.sum()
        if (total <= maxTokens) return messages

        val systemIdx = messages.indices.filter { messages[it] is SystemMessage }
        val nonSystemIdx = messages.indices.filter { messages[it] !is SystemMessage }

        val systemTokens = systemIdx.sumOf { tokens[it] }
        val budgetForNonSystem = maxTokens - systemTokens

        if (nonSystemIdx.isEmpty() || budgetForNonSystem <= 0) {
            logger.warn("Request token limit: system messages alone est. {} tokens > max {}", systemTokens, maxTokens)
            return messages.take(systemIdx.size + 1)
        }

        val keptNonSystem = mutableListOf<Int>()
        var remaining = budgetForNonSystem
        for (i in nonSystemIdx.indices.reversed()) {
            val idx = nonSystemIdx[i]
            val t = tokens[idx]
            if (t <= remaining) {
                keptNonSystem.add(idx)
                remaining -= t
            } else {
                break
            }
        }

        if (keptNonSystem.isEmpty()) {
            keptNonSystem.add(nonSystemIdx.last())
        }

        val allKept = (systemIdx + keptNonSystem).sorted()
        val result = allKept.map { messages[it] }

        logger.info(
            "Request token limit: {} est. tokens, kept {}/{} messages, dropped {} (max {})",
            total, result.size, messages.size, messages.size - result.size, maxTokens
        )
        return result
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

    /**
     * Truncate [content] to approximately [targetTokens] tokens, keeping the
     * **tail** (most recent/relevant part). The head is replaced with an
     * omission marker.
     */
    private fun truncateContentTail(content: String, estimatedTokens: Long, targetTokens: Long): String {
        if (targetTokens <= 0 || content.isEmpty()) return content
        val ratio = (targetTokens.toDouble() / estimatedTokens.toDouble()).coerceIn(0.0, 1.0)
        val keepChars = (content.length * ratio).toInt().coerceIn(1, content.length)
        val omitted = content.length - keepChars
        return "[… $omitted chars omitted to fit request token limit …]\n" +
            content.substring(omitted)
    }

    /**
     * Best-effort fallback when system messages alone exceed the budget.
     * Keeps system messages until budget is exhausted, then truncates.
     */
    private fun keepBestEffort(
        msgs: List<SimpleMessage>,
        tokens: List<Long>,
        maxTokens: Long,
    ): AgentMessageList {
        val result = AgentMessageList()
        var remaining = maxTokens
        for (i in msgs.indices) {
            val t = tokens[i]
            if (t <= remaining) {
                result.addLast(msgs[i])
                remaining -= t
            } else if (result.messages.isEmpty()) {
                // Must keep at least one message, truncated
                val orig = msgs[i]
                val truncated = truncateContentTail(orig.content, t, remaining)
                result.addLast(SimpleMessage(orig.role, truncated, orig.name))
                break
            }
        }
        return result
    }

    companion object {
        /** Configuration key for the per-request token limit. */
        const val CONFIG_KEY = "agent.llm.maxRequestTokens"

        /**
         * Default: 50,000 estimated tokens per LLM request. A typical browser
         * agent observe/act step sends 5K-30K tokens; this leaves headroom for
         * large page content while preventing runaway context growth.
         */
        const val DEFAULT_MAX_REQUEST_TOKENS = 50_000

        /**
         * Build a limiter from configuration.
         *
         * Accepted values for [CONFIG_KEY]:
         * - a positive number of tokens (e.g. `30000`)
         * - `0` or `-1` — disable enforcement
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
