package ai.platon.pulsar.agentic.inference.action

import ai.platon.pulsar.agentic.inference.AgentTokenBudget
import ai.platon.pulsar.agentic.inference.TokenBudgetExceededException
import ai.platon.pulsar.common.config.ImmutableConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Regression tests for the cross-task token-accounting defect.
 *
 * [ContextToAction] belongs to the *agent*, and an interactive session
 * (`agent run -i`) shares one agent across every task. The budget used to be
 * created once, lazily, with the agent: task N inherited the spend of tasks
 * 1..N-1 and could be aborted with a spurious [TokenBudgetExceededException]
 * while `/stats` reported a cross-task cumulative count. Every run must start
 * from a clean budget installed by [ContextToAction.beginRun].
 */
class ContextToActionBeginRunTest {

    private val conf = ImmutableConfig()

    @Test
    @DisplayName("a new run replaces the previous task's budget")
    fun newRunReplacesPreviousBudget() {
        val cta = ContextToAction(conf)
        // Simulate an earlier task of the same interactive session that spent the
        // whole default budget.
        cta.tokenBudget.add(4_000_000, 1_000_000)
        assertTrue(cta.tokenBudget.isExceeded, "precondition: the previous run is exhausted")

        val runBudget = cta.beginRun(conf, 10_000)

        assertSame(runBudget, cta.tokenBudget, "beginRun must install the budget it returns")
        assertEquals(0, runBudget.consumedTotal, "a fresh task must not inherit the previous spend")
        assertFalse(runBudget.isExceeded)
        assertEquals(10_000, runBudget.effectiveMaxTotalTokens)
    }

    @Test
    @DisplayName("the configured budget is restored when a task has no override")
    fun configBudgetRestoredWithoutOverride() {
        val cta = ContextToAction(conf)
        cta.beginRun(conf, 10_000)
        assertEquals(10_000, cta.tokenBudget.effectiveMaxTotalTokens)

        val next = cta.beginRun(conf)

        assertEquals(
            AgentTokenBudget.from(conf).effectiveMaxTotalTokens,
            next.effectiveMaxTotalTokens,
            "dropping the per-task override must fall back to the configured budget",
        )
        assertEquals(0, next.consumedTotal)
    }

    @Test
    @DisplayName("charging one run never leaks into the next run")
    fun chargeDoesNotLeakAcrossRuns() {
        val cta = ContextToAction(conf)
        val first = cta.beginRun(conf, 1_000)
        assertThrows<TokenBudgetExceededException> { first.charge(10_000, 0) }

        val second = cta.beginRun(conf, 1_000)

        assertEquals(0, second.consumedTotal)
        assertFalse(second.isExceeded)
    }
}
