package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.common.getLogger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Thrown when an agent run has consumed more cache-miss tokens than its budget
 * allows.
 *
 * Extends [IllegalStateException] so it is classified as a permanent (non-retryable)
 * error by [ai.platon.pulsar.agentic.inference.detail.RetryStrategy], and callers
 * that retry unconditionally should re-throw it immediately.
 *
 * The run is resumable: the interactive session offers a "continue" choice that
 * doubles the budget, and `agent run --resume <id> --token-budget <n>` does the
 * same from a script.
 */
class TokenBudgetExceededException(
    val consumedTokens: Long,
    val budgetTokens: Long,
    /**
     * Prompt-cache tokens the run read but the budget did **not** charge
     * (`0` when the provider reported no cache details).
     */
    val consumedCacheReadTokens: Long = 0,
) : IllegalStateException(
    "Token budget exceeded (cache-miss accounting): charged ${String.format("%,d", consumedTokens)} " +
        "of a ${String.format("%,d", budgetTokens)} cache-miss budget (uncached prompt + output; " +
        "${String.format("%,d", consumedCacheReadTokens)} cache-read tokens were served at the cache price and were not charged). " +
        "Continue with `agent run --resume <task-id> --token-budget ${suggestedBudget(budgetTokens, consumedTokens)}`, " +
        "or pick the continue choice in the interactive session. " +
        "Raise '${AgentTokenBudget.CONFIG_KEY}' in configuration if this task legitimately needs more tokens."
)

/**
 * The budget a "continue" offers after an exhausted one: double the spent cap, so
 * one choice buys the same run another full window instead of asking the user to
 * compute a number. Never below what the run already consumed.
 */
private fun suggestedBudget(budgetTokens: Long, consumedTokens: Long): Long {
    val doubled = if (budgetTokens > Long.MAX_VALUE / 2) budgetTokens else maxOf(budgetTokens, 1L) * 2
    return doubled.coerceAtLeast(consumedTokens)
}

/**
 * Thread-safe budget for a single agent run.
 *
 * ## What the cap counts
 *
 * The budget is a **cache-miss token** cap. One model request is charged
 *
 * ```
 * charged = (prompt tokens - prompt-cache reads) + output tokens
 * ```
 *
 * Prompt-cache reads cost roughly a tenth of the base input price, so a long loop
 * that re-sends an ever-growing cached prefix no longer aborts a run that actually
 * costs little. Uncached prompt tokens (Anthropic's cache *writes* included — they
 * are billed above the base price) and output tokens are charged, because they are
 * the real spend. A provider that reports no cache details charges its whole
 * prompt: an unknown cache is never treated as a hit.
 *
 * Both engine paths account their usage here: every LLM call made through
 * [ai.platon.pulsar.agentic.inference.action.ContextToAction] (legacy engine) and
 * every request of the default CLI tool loop (charged from its `onModelResponse`
 * callback). When the cumulative charge exceeds [effectiveMaxTotalTokens], the run
 * stops with [TokenBudgetExceededException] before a runaway task can burn
 * unbounded provider credits.
 *
 * A [maxTotalTokens] of `0` or less means unlimited (observability only).
 *
 * @param maxTotalTokens hard cap on charged (cache-miss) tokens per agent run.
 * @param taskTotalTokens per-task budget (`agent run --token-budget`). It wins over
 *   both [maxTotalTokens] and the process-wide [RuntimeConfigRegistry] override, and
 *   is never written into the registry: a task-level choice must not change every
 *   later task in the JVM. `null` keeps the config/registry resolution.
 */
class AgentTokenBudget(
    val maxTotalTokens: Long = DEFAULT_MAX_TOTAL_TOKENS,
    val taskTotalTokens: Long? = null,
) {
    private val logger = getLogger(this)
    private val consumedCharge = AtomicLong(0)
    private val consumedCacheMissInput = AtomicLong(0)
    private val consumedCacheRead = AtomicLong(0)
    private val consumedOutput = AtomicLong(0)
    private val warned = AtomicBoolean(false)

    /**
     * The budget actually enforced: a per-task override wins over a runtime override
     * from [RuntimeConfigRegistry] (unified REST config interface), which wins over
     * the constructor value.
     */
    val effectiveMaxTotalTokens: Long
        get() = taskTotalTokens ?: RuntimeConfigRegistry.getOverrideAsLong(CONFIG_KEY) ?: maxTotalTokens

    /** True when this run's budget came from a per-task override. */
    val isTaskOverridden: Boolean get() = taskTotalTokens != null

    /** Charged (cache-miss) tokens consumed so far. */
    val consumedTotal: Long get() = consumedCharge.get()

    /** Uncached prompt tokens consumed so far. */
    val consumedCacheMissInputTokens: Long get() = consumedCacheMissInput.get()

    /** Prompt-cache read tokens consumed so far (never charged to the budget). */
    val consumedCacheReadTokens: Long get() = consumedCacheRead.get()

    /** Output tokens consumed so far. */
    val consumedOutputTokens: Long get() = consumedOutput.get()

    /** True when the budget has been exhausted; further calls must not be made. */
    val isExceeded: Boolean
        get() = effectiveMaxTotalTokens > 0 && consumedTotal >= effectiveMaxTotalTokens

    /**
     * Record usage from one LLM call and return the new charged total.
     *
     * @param inputTokens prompt tokens the request processed, prompt-cache tokens
     *   included (the normalized `TokenUsage.inputTokenCount`)
     * @param outputTokens completion tokens of the request
     * @param cachedInputTokens prompt tokens served from the provider's prompt
     *   cache (`0` when the provider reported no cache details)
     */
    fun add(inputTokens: Long, outputTokens: Long, cachedInputTokens: Long = 0): Long {
        val input = inputTokens.coerceAtLeast(0)
        val cached = cachedInputTokens.coerceIn(0, input)
        val miss = input - cached
        val output = outputTokens.coerceAtLeast(0)
        if (miss > 0) consumedCacheMissInput.addAndGet(miss)
        if (cached > 0) consumedCacheRead.addAndGet(cached)
        if (output > 0) consumedOutput.addAndGet(output)
        return consumedCharge.addAndGet(miss + output)
    }

    /**
     * One-shot warning check: returns true exactly once when usage first
     * reaches 80% of the budget (for proactive logging).
     */
    fun shouldWarn(): Boolean {
        val limit = effectiveMaxTotalTokens
        if (limit <= 0) return false
        return consumedTotal * 5 >= limit * 4 && warned.compareAndSet(false, true)
    }

    /**
     * Charge one provider response to this run and stop the loop when the budget
     * is spent.
     *
     * This is the single entry point both engines use: the legacy
     * [ai.platon.pulsar.agentic.inference.action.ContextToAction] counts every LLM
     * call, and the default CLI tool loop calls it from its `onModelResponse`
     * callback. Before that wiring existed the budget was enforced only on the
     * legacy path, so a CLI-engine run could consume unbounded tokens (observed:
     * 7.87M input tokens against the 5M default, with no abort).
     *
     * @param inputTokens prompt tokens reported by the provider, cache included
     *   (0 when unknown)
     * @param outputTokens completion tokens reported by the provider (0 when unknown)
     * @param cachedInputTokens prompt tokens served from the provider's prompt cache
     *   (`0` when the provider reported no cache details)
     * @throws TokenBudgetExceededException when the cumulative charge reaches the budget
     */
    fun charge(inputTokens: Long, outputTokens: Long, cachedInputTokens: Long = 0) {
        val total = add(inputTokens, outputTokens, cachedInputTokens)
        if (shouldWarn()) {
            logger.warn("⚠️ cache-miss token usage at 80%+ of budget: {}", this)
        }
        if (isExceeded) {
            throw TokenBudgetExceededException(total, effectiveMaxTotalTokens, consumedCacheRead.get())
        }
    }

    override fun toString(): String {
        val limit = effectiveMaxTotalTokens
        val cacheRead = consumedCacheRead.get()
        val cacheNote = if (cacheRead > 0) " cacheRead=$cacheRead (not charged)" else ""
        return if (limit <= 0) "TokenBudget(unlimited, consumed=${consumedTotal}$cacheNote)"
        else "TokenBudget(consumed=${consumedTotal}/$limit$cacheNote)"
    }

    companion object {
        /** Configuration key for the per-agent-run token budget. */
        const val CONFIG_KEY = "agent.token.budget.total"

        /**
         * Default budget: 5M **cache-miss** tokens per agent run. A browser agent
         * step typically costs 5K-100K uncached tokens; with prompt caching a long
         * loop re-sends mostly cached prefixes, so the old "raw input + output" cap
         * aborted runs that cost little. Prompt-cache reads no longer count; the cap
         * still stops a runaway loop long before it can burn unbounded credits.
         */
        const val DEFAULT_MAX_TOTAL_TOKENS = 5_000_000L

        /**
         * Build a budget from configuration.
         *
         * Accepted values for [CONFIG_KEY]:
         * - a positive number of **cache-miss** tokens (e.g. `2000000`)
         * - `0`, `-1` or `unlimited` — disable enforcement (accounting only)
         * - absent — [DEFAULT_MAX_TOTAL_TOKENS]
         */
        fun from(conf: ImmutableConfig): AgentTokenBudget {
            val raw = conf.get(CONFIG_KEY)?.trim()
            return AgentTokenBudget(parseBudgetValue(raw))
        }

        /**
         * Build the budget for one run: the per-task override when the caller named
         * one, the configured value otherwise.
         *
         * @param conf the session configuration
         * @param taskTokens `agent run --token-budget` value (`null` when omitted)
         */
        fun forRun(conf: ImmutableConfig, taskTokens: Long?): AgentTokenBudget =
            if (taskTokens != null) AgentTokenBudget(taskTotalTokens = taskTokens) else from(conf)

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
