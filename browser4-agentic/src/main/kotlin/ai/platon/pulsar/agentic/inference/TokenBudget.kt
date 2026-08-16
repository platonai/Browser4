package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.config.ImmutableConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thrown when an agent run has consumed more tokens than its budget allows.
 *
 * Extends [IllegalStateException] so it is classified as a permanent (non-retryable)
 * error by [ai.platon.pulsar.agentic.inference.detail.RetryStrategy], and callers
 * that retry unconditionally should re-throw it immediately.
 */
class TokenBudgetExceededException(
    val consumedTokens: Long,
    val budgetTokens: Long,
) : IllegalStateException(
    "Token budget exceeded: consumed ${String.format("%,d", consumedTokens)} tokens, " +
        "budget ${String.format("%,d", budgetTokens)}. " +
        "Agent run aborted to prevent runaway LLM costs. " +
        "Raise '${AgentTokenBudget.CONFIG_KEY}' in configuration if this task legitimately needs more tokens."
)

/**
 * Thread-safe token budget for a single agent run.
 *
 * All LLM calls made through [ai.platon.pulsar.agentic.inference.action.ContextToAction]
 * are accounted here. When the cumulative usage exceeds [maxTotalTokens], subsequent
 * calls throw [TokenBudgetExceededException], stopping the agent loop before a
 * runaway task can burn unbounded provider credits.
 *
 * A [maxTotalTokens] of `0` or less means unlimited (observability only).
 *
 * @param maxTotalTokens hard cap on total (input + output) tokens per agent run.
 */
class AgentTokenBudget(val maxTotalTokens: Long = DEFAULT_MAX_TOTAL_TOKENS) {
    private val consumedInput = AtomicLong(0)
    private val consumedOutput = AtomicLong(0)
    private val warned = AtomicBoolean(false)

    /** Total tokens consumed so far (input + output). */
    val consumedTotal: Long get() = consumedInput.get() + consumedOutput.get()

    /** True when the budget has been exhausted; further calls must not be made. */
    val isExceeded: Boolean
        get() = maxTotalTokens > 0 && consumedTotal >= maxTotalTokens

    /**
     * Record usage from one LLM call and return the new total.
     */
    fun add(inputTokens: Long, outputTokens: Long): Long {
        if (inputTokens > 0) consumedInput.addAndGet(inputTokens)
        if (outputTokens > 0) consumedOutput.addAndGet(outputTokens)
        return consumedTotal
    }

    /**
     * One-shot warning check: returns true exactly once when usage first
     * reaches 80% of the budget (for proactive logging).
     */
    fun shouldWarn(): Boolean {
        if (maxTotalTokens <= 0) return false
        return consumedTotal * 5 >= maxTotalTokens * 4 && warned.compareAndSet(false, true)
    }

    override fun toString(): String =
        if (maxTotalTokens <= 0) "TokenBudget(unlimited, consumed=${consumedTotal})"
        else "TokenBudget(consumed=$consumedTotal/$maxTotalTokens)"

    companion object {
        /** Configuration key for the per-agent-run token budget. */
        const val CONFIG_KEY = "agent.token.budget.total"

        /**
         * Default budget: 5M total tokens per agent run. A browser agent step
         * typically costs 5K-100K tokens, so this allows dozens of steps while
         * capping the worst case (previously unbounded: maxSteps=100 with
         * growing history could exceed 10M tokens within an hour).
         */
        const val DEFAULT_MAX_TOTAL_TOKENS = 5_000_000L

        /**
         * Build a budget from configuration.
         *
         * Accepted values for [CONFIG_KEY]:
         * - a positive number of tokens (e.g. `2000000`)
         * - `0`, `-1` or `unlimited` — disable enforcement (accounting only)
         * - absent — [DEFAULT_MAX_TOTAL_TOKENS]
         */
        fun from(conf: ImmutableConfig): AgentTokenBudget {
            val raw = conf.get(CONFIG_KEY)?.trim()
            return AgentTokenBudget(parseBudgetValue(raw))
        }

        /**
         * Parse a raw config string into a budget value.
         *
         * Extracted from [from] for direct unit testing without an [ImmutableConfig] instance.
         */
        fun parseBudgetValue(raw: String?): Long {
            val s = raw?.trim()
            return when {
                s.isNullOrEmpty() -> DEFAULT_MAX_TOTAL_TOKENS
                s.equals("unlimited", ignoreCase = true) -> 0L
                else -> s.toLongOrNull()?.let { if (it <= 0L) 0L else it }
                    ?: DEFAULT_MAX_TOTAL_TOKENS
            }
        }
    }
}
