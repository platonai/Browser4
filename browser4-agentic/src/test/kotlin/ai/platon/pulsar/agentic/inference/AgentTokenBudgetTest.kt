package ai.platon.pulsar.agentic.inference

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [AgentTokenBudget] — the per-agent-run cache-miss token guard
 * that prevents runaway LLM consumption.
 */
class AgentTokenBudgetTest {

    @Test
    @DisplayName("budget tracks the charged (cache-miss prompt + output) total")
    fun tracksUsage() {
        val budget = AgentTokenBudget(maxTotalTokens = 10_000)
        assertEquals(0, budget.consumedTotal)
        budget.add(3_000, 1_000)
        assertEquals(4_000, budget.consumedTotal)
        budget.add(0, 500)
        assertEquals(4_500, budget.consumedTotal)
    }

    @Test
    @DisplayName("prompt-cache reads are not charged to the budget")
    fun cacheReadsAreFree() {
        val budget = AgentTokenBudget(maxTotalTokens = 1_000)

        budget.charge(inputTokens = 900, outputTokens = 50, cachedInputTokens = 800)

        assertEquals(150, budget.consumedTotal, "charged = (900 - 800) + 50")
        assertEquals(100, budget.consumedCacheMissInputTokens)
        assertEquals(800, budget.consumedCacheReadTokens)
        assertEquals(50, budget.consumedOutputTokens)
        assertFalse(budget.isExceeded)
    }

    @Test
    @DisplayName("a heavily cached loop no longer exhausts a budget its raw prompts would have blown")
    fun heavyCacheHitLoopDoesNotExhaustTheBudget() {
        val budget = AgentTokenBudget(maxTotalTokens = 1_000)

        repeat(10) { budget.add(inputTokens = 10_000, outputTokens = 5, cachedInputTokens = 9_990) }

        assertEquals(150, budget.consumedTotal, "10 misses of 10 tokens + 10 * 5 output")
        assertEquals(99_900, budget.consumedCacheReadTokens)
        assertFalse(
            budget.isExceeded,
            "the raw prompts were 100,000 tokens: charging them was the old behaviour",
        )
    }

    @Test
    @DisplayName("an unreported cache charges the whole prompt — unknown is not a hit")
    fun unreportedCacheChargesTheWholePrompt() {
        val budget = AgentTokenBudget(maxTotalTokens = 1_000)

        val ex = assertThrows<TokenBudgetExceededException> {
            budget.charge(inputTokens = 1_000, outputTokens = 10)
        }

        assertEquals(1_010L, ex.consumedTokens)
        assertEquals(1_010, budget.consumedTotal)
        assertEquals(0, budget.consumedCacheReadTokens)
        assertEquals(0, ex.consumedCacheReadTokens)
    }

    @Test
    @DisplayName("a cache count larger than the prompt is clamped, never negative")
    fun cachedTokensAreClampedToThePrompt() {
        val budget = AgentTokenBudget(maxTotalTokens = 10_000)

        budget.add(inputTokens = 100, outputTokens = 0, cachedInputTokens = 10_000)

        assertEquals(0, budget.consumedTotal, "a nonsensical cache count cannot mint budget")
        assertEquals(100, budget.consumedCacheReadTokens, "the reads are still accounted")
    }

    @Test
    @DisplayName("the 80% warning measures the cache-miss charge only")
    fun warnMeasuresTheCacheMissCharge() {
        val budget = AgentTokenBudget(maxTotalTokens = 1_000)
        budget.add(inputTokens = 9_000, outputTokens = 100, cachedInputTokens = 8_600) // 500 = 50%
        assertFalse(budget.shouldWarn())
        budget.add(inputTokens = 9_000, outputTokens = 100, cachedInputTokens = 8_600) // 1,000 = 100%
        assertTrue(budget.shouldWarn())
    }

    @Test
    @DisplayName("isExceeded becomes true only after the cap is reached")
    fun exceededDetection() {
        val budget = AgentTokenBudget(maxTotalTokens = 1_000)
        assertFalse(budget.isExceeded)
        budget.add(999, 0)
        assertFalse(budget.isExceeded, "still under cap")
        budget.add(0, 2)
        assertTrue(budget.isExceeded, "1001 >= 1000")
    }

    @Test
    @DisplayName("shouldWarn fires exactly once at 80% of the budget")
    fun warnOnceAt80Percent() {
        val budget = AgentTokenBudget(maxTotalTokens = 1_000)
        budget.add(400, 0) // 40%
        assertFalse(budget.shouldWarn())
        budget.add(350, 50) // 80%
        assertTrue(budget.shouldWarn(), "first crossing of 80%")
        assertFalse(budget.shouldWarn(), "must not fire again")
        budget.add(100, 0) // 90%
        assertFalse(budget.shouldWarn(), "already warned")
    }

    @Test
    @DisplayName("unlimited budget (0 or negative) never exceeds and never warns")
    fun unlimitedBudget() {
        val budget = AgentTokenBudget(maxTotalTokens = 0)
        budget.add(9_999_999, 9_999_999)
        assertFalse(budget.isExceeded)
        assertFalse(budget.shouldWarn())
    }

    @Test
    @DisplayName("parseBudgetValue returns default when input is null or blank")
    fun parseBudgetDefault() {
        assertEquals(AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS, AgentTokenBudget.parseBudgetValue(null))
        assertEquals(AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS, AgentTokenBudget.parseBudgetValue(""))
        assertEquals(AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS, AgentTokenBudget.parseBudgetValue("   "))
    }

    @Test
    @DisplayName("parseBudgetValue reads a custom positive budget")
    fun parseBudgetCustom() {
        assertEquals(2_500_000L, AgentTokenBudget.parseBudgetValue("2500000"))
        assertEquals(2_500_000L, AgentTokenBudget.parseBudgetValue("  2500000  "))
        assertEquals(1L, AgentTokenBudget.parseBudgetValue("1"))
    }

    @Test
    @DisplayName("parseBudgetValue treats 'unlimited', '0', and '-1' as unlimited (0L)")
    fun parseBudgetUnlimited() {
        for (value in listOf("unlimited", "UNLIMITED", "Unlimited", "0", "-1")) {
            assertEquals(0L, AgentTokenBudget.parseBudgetValue(value), "value '$value' should be unlimited")
        }
    }

    @Test
    @DisplayName("parseBudgetValue falls back to default for unparseable input")
    fun parseBudgetInvalid() {
        assertEquals(AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS, AgentTokenBudget.parseBudgetValue("abc"))
        assertEquals(AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS, AgentTokenBudget.parseBudgetValue("1.5m"))
    }

    @Test
    @DisplayName("TokenBudgetExceededException carries consumed and budget values with comma-formatted message")
    fun exceptionMetadata() {
        val ex = TokenBudgetExceededException(consumedTokens = 1_234_567, budgetTokens = 1_000_000)
        assertEquals(1_234_567L, ex.consumedTokens)
        assertEquals(1_000_000L, ex.budgetTokens)
        assertThrows<IllegalStateException> { throw ex }
        assertTrue(ex.message!!.contains("1,234,567"), "message should contain comma-formatted consumed tokens")
        assertTrue(ex.message!!.contains("1,000,000"), "message should contain comma-formatted budget tokens")
        assertTrue(ex.message!!.contains(AgentTokenBudget.CONFIG_KEY), "message should mention the config key")
    }

    @Test
    @DisplayName("the exceeded exception names the cache reads and a doubled continue budget")
    fun exceptionNamesCacheReadsAndContinueBudget() {
        val ex = TokenBudgetExceededException(
            consumedTokens = 5_100_000,
            budgetTokens = 5_000_000,
            consumedCacheReadTokens = 12_000_000,
        )

        // The message is what a CLI reader and the agent see: it must explain that
        // cached tokens were not charged, and name the one-command way to continue.
        assertTrue(ex.message!!.contains("12,000,000"), ex.message)
        assertTrue(ex.message!!.contains("--token-budget 10000000"), ex.message)
        assertTrue(ex.message!!.contains("resume"), ex.message)
    }

    @Test
    @DisplayName("charge stops the run as soon as the budget is spent")
    fun chargeThrowsWhenExhausted() {
        // The CLI tool loop charges every provider response through this entry point;
        // the exception is what aborts the run (a live CLI run consumed 7.87M input
        // tokens against the 5M default because that loop never charged at all).
        val budget = AgentTokenBudget(maxTotalTokens = 1_000)

        budget.charge(400, 100)
        assertEquals(500, budget.consumedTotal)
        assertFalse(budget.isExceeded, "500 < 1000")

        val ex = assertThrows<TokenBudgetExceededException> { budget.charge(500, 0) }
        assertEquals(1_000L, ex.consumedTokens)
        assertEquals(1_000L, ex.budgetTokens)
    }

    @Test
    @DisplayName("charge never throws when the budget is unlimited")
    fun chargeUnlimitedNeverThrows() {
        val budget = AgentTokenBudget(maxTotalTokens = 0)

        budget.charge(10_000_000, 1_000_000)

        assertEquals(11_000_000, budget.consumedTotal)
        assertFalse(budget.isExceeded, "0 means unlimited: accounting only")
        assertFalse(budget.shouldWarn(), "an unlimited budget never warns")
    }

    @Test
    @DisplayName("a per-task override wins over the configured value and the process-wide registry")
    fun taskOverrideWins() {
        // `agent run --token-budget` is a per-run choice: it must beat both the
        // configured `agent.token.budget.total` and a runtime override, and it is
        // never written into the registry (the next task keeps the default).
        RuntimeConfigRegistry.setOverride(AgentTokenBudget.CONFIG_KEY, "9000000")
        try {
            val configured = AgentTokenBudget(maxTotalTokens = AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS)
            assertEquals(9_000_000L, configured.effectiveMaxTotalTokens, "registry override wins over config")

            val task = AgentTokenBudget(taskTotalTokens = 2_000_000L)
            assertEquals(2_000_000L, task.effectiveMaxTotalTokens, "the task budget must win")
            assertTrue(task.isTaskOverridden)

            val defaulted = AgentTokenBudget(maxTotalTokens = AgentTokenBudget.DEFAULT_MAX_TOTAL_TOKENS)
            assertEquals(9_000_000L, defaulted.effectiveMaxTotalTokens, "no task budget → registry/config")
            assertFalse(defaulted.isTaskOverridden)
        } finally {
            RuntimeConfigRegistry.clearOverride(AgentTokenBudget.CONFIG_KEY)
        }
    }

    @Test
    @DisplayName("a task budget of 0 means unlimited and never aborts")
    fun taskOverrideZeroIsUnlimited() {
        val budget = AgentTokenBudget(taskTotalTokens = 0L)

        assertEquals(0L, budget.effectiveMaxTotalTokens)
        budget.charge(50_000_000, 1_000_000)
        assertFalse(budget.isExceeded)
    }
}
