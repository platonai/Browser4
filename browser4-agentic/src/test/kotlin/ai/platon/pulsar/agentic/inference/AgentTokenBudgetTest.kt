package ai.platon.pulsar.agentic.inference

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [AgentTokenBudget] — the per-agent-run token guard that
 * prevents runaway LLM consumption.
 */
class AgentTokenBudgetTest {

    @Test
    @DisplayName("budget tracks input+output and reports consumed total")
    fun tracksUsage() {
        val budget = AgentTokenBudget(maxTotalTokens = 10_000)
        assertEquals(0, budget.consumedTotal)
        budget.add(3_000, 1_000)
        assertEquals(4_000, budget.consumedTotal)
        budget.add(0, 500)
        assertEquals(4_500, budget.consumedTotal)
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
