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
}
