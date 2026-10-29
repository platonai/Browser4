package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The budget wrap-up directive exists because a run left alone spends every token it is
 * given: on 2026-10-29 the 20-microwave task was aborted at 99–100% of a 5M run, a 6M run
 * *and* the supervised baseline, even though the deliverable CSV had been written at minute
 * 14 and the tail was self-audit. The nudge must fire once per threshold and only when a
 * budget is actually set.
 */
@DisplayName("budget wrap-up nudge")
class BudgetPressureNudgeTest {

    @Test
    @DisplayName("an unlimited run is never nudged")
    fun unlimitedBudgetIsNeverNudged() {
        assertNull(budgetPressureNudge(consumed = 9_000_000, limit = 0))
        assertNull(budgetPressureNudge(consumed = 9_000_000, limit = -1))
        assertNull(budgetPressureNudge(consumed = 0, limit = 5_000_000))
    }

    @Test
    @DisplayName("nothing is said before the threshold")
    fun belowThresholdIsSilent() {
        assertNull(budgetPressureNudge(consumed = 3_999_999, limit = 5_000_000))
        assertNull(budgetPressureNudge(consumed = 4_749_999, limit = 5_000_000, percent = 95))
    }

    @Test
    @DisplayName("the directive names the usage, the deliverable and taskComplete")
    fun nudgeAtThresholdCarriesTheAction() {
        val text = budgetPressureNudge(consumed = 4_000_000, limit = 5_000_000)!!

        assertTrue(text.contains("80%"), text)
        assertTrue(text.contains("4000000"), text)
        assertTrue(text.contains("system.taskComplete"), text)
        assertTrue(text.contains("problems"), text)
        assertTrue(text.contains("workspace root"), text)
    }

    @Test
    @DisplayName("the 95% directive can follow the 80% one")
    fun finalThresholdIsReachable() {
        assertNull(budgetPressureNudge(consumed = 4_749_999, limit = 5_000_000, percent = 95))
        val text = budgetPressureNudge(consumed = 4_750_000, limit = 5_000_000, percent = 95)!!
        assertTrue(text.contains("95%"), text)
    }

    @Test
    @DisplayName("a run already over budget still gets the directive")
    fun overBudgetStillNudges() {
        val text = budgetPressureNudge(consumed = 5_012_835, limit = 5_000_000)!!
        assertTrue(text.contains("100%"), text)
    }
}
