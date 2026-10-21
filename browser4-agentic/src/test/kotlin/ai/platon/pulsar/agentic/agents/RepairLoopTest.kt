package ai.platon.pulsar.agentic.agents

import ai.platon.pulsar.coding.BuildDiagnostic
import ai.platon.pulsar.coding.VerificationCheck
import ai.platon.pulsar.coding.VerificationReport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for the repair-round policy (plan P1.3):
 * - a passing verdict accepts the completion,
 * - a failing verdict spends one round and returns the structured diagnostics,
 * - the budget is bounded (`browser4.agent.repair.maxRounds`, 0 disables),
 * - an exhausted budget is a task failure, not a silent success,
 * - the injected message stays under the context budget.
 */
class RepairLoopTest {

    private fun failingReport(
        module: String? = "browser4-coding",
        diagnostics: List<BuildDiagnostic> = listOf(
            BuildDiagnostic(
                file = "browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/Foo.kt",
                line = 12,
                column = 5,
                severity = "error",
                message = "Unresolved reference 'bar'",
            )
        ),
        requested: List<String> = listOf("compile"),
    ) = VerificationReport(
        checks = listOf(
            VerificationCheck(
                name = "compile",
                target = module ?: "(none)",
                ran = true,
                ok = false,
                exitCode = 1,
                durationMs = 1_200,
                evidence = "1 diagnostic(s), exit 1",
                detail = "browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/Foo.kt:12:5 — Unresolved reference 'bar'",
                diagnostics = diagnostics,
            )
        ),
        module = module,
        requested = requested,
        durationMs = 1_200,
    )

    private fun passingReport() = VerificationReport(
        checks = listOf(
            VerificationCheck(
                name = "compile",
                target = "browser4-coding",
                ran = true,
                ok = true,
                exitCode = 0,
                durationMs = 800,
                evidence = "exit 0",
            )
        ),
        module = "browser4-coding",
        requested = listOf("compile"),
        durationMs = 800,
    )

    @Test
    @DisplayName("a passing verdict accepts the completion")
    fun passingVerdictAccepts() {
        val decision = RepairLoop(maxRounds = 3).decide(passingReport())
        assertEquals(RepairLoop.Action.PASSED, decision.action)
        assertNull(decision.message)
        assertFalse(decision.shouldRetry)
    }

    @Test
    @DisplayName("a failing verdict returns the diagnostics and spends one round")
    fun failingVerdictReturnsDiagnostics() {
        val loop = RepairLoop(maxRounds = 3)
        val decision = loop.decide(failingReport())
        assertEquals(RepairLoop.Action.RETRY, decision.action)
        assertEquals(1, decision.round)
        assertEquals(1, loop.roundsUsed)
        val message = decision.message!!
        assertTrue(message.contains("Verification round 1/3 FAILED"), message)
        assertTrue(message.contains("Unresolved reference 'bar'"), message)
        assertTrue(message.contains("Foo.kt:12:5"), message)
        assertTrue(message.contains("coding.verify(module=\"browser4-coding\""), message)
        assertTrue(message.contains("system.taskComplete"), message)
    }

    @Test
    @DisplayName("the budget is bounded and exhaustion reports EXHAUSTED")
    fun budgetIsBounded() {
        val loop = RepairLoop(maxRounds = 2)
        assertEquals(RepairLoop.Action.RETRY, loop.decide(failingReport()).action)
        assertEquals(RepairLoop.Action.RETRY, loop.decide(failingReport()).action)
        assertEquals(RepairLoop.Action.EXHAUSTED, loop.decide(failingReport()).action)
        assertEquals(2, loop.roundsUsed)
    }

    @Test
    @DisplayName("maxRounds=0 disables the loop")
    fun zeroBudgetDisables() {
        val loop = RepairLoop(maxRounds = 0)
        val decision = loop.decide(failingReport())
        assertEquals(RepairLoop.Action.DISABLED, decision.action)
        assertNull(decision.message)
        assertEquals(0, loop.roundsUsed)
    }

    @Test
    @DisplayName("a passing verdict clears the budget")
    fun passingClearsBudget() {
        val loop = RepairLoop(maxRounds = 2)
        loop.decide(failingReport())
        assertEquals(1, loop.roundsUsed)
        loop.decide(passingReport())
        assertEquals(0, loop.roundsUsed)
        assertEquals(RepairLoop.Action.RETRY, loop.decide(failingReport()).action)
    }

    @Test
    @DisplayName("reset clears the budget")
    fun resetClearsBudget() {
        val loop = RepairLoop(maxRounds = 1)
        loop.decide(failingReport())
        assertEquals(RepairLoop.Action.EXHAUSTED, loop.decide(failingReport()).action)
        loop.reset()
        assertEquals(0, loop.roundsUsed)
        assertEquals(RepairLoop.Action.RETRY, loop.decide(failingReport()).action)
    }

    @Test
    @DisplayName("the injected message stays inside the feedback budget")
    fun messageIsBounded() {
        val many = (1..40).map {
            BuildDiagnostic("a/big/path/File$it.kt", it, 1, "error", "error number $it with a long explanation")
        }
        val report = failingReport(diagnostics = many)
        val message = RepairLoop.formatFeedback(report, round = 1, maxRounds = 3, maxChars = 300)
        assertTrue(message.length <= 300, "length=${message.length}")
        assertTrue(message.contains("truncated"), message)
    }

    @Test
    @DisplayName("diagnostics are capped and the overflow is counted")
    fun diagnosticsAreCapped() {
        val many = (1..30).map {
            BuildDiagnostic("a/File$it.kt", it, 1, "error", "boom $it")
        }
        val message = RepairLoop.formatFeedback(failingReport(diagnostics = many), round = 1, maxRounds = 3)
        assertTrue(message.contains("Diagnostics (30)"), message)
        assertTrue(message.contains("more diagnostics omitted"), message)
    }

    @Test
    @DisplayName("the feedback names the failing checks when there is no module")
    fun feedbackWithoutModule() {
        val message = RepairLoop.formatFeedback(failingReport(module = null), round = 2, maxRounds = 3)
        assertTrue(message.contains("Verification round 2/3 FAILED"), message)
        assertTrue(message.contains("coding.verify()"), message)
    }
}
