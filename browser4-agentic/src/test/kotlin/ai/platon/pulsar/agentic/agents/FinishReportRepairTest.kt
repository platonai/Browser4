package ai.platon.pulsar.agentic.agents

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The finish-report repair policy exists because of a real 2026-10-27 incident:
 * a 13-minute browsing run wrote 37 helper scripts, the model listed 20 in
 * `filesChanged`, and the strict gate failed a task whose GitHub issue had
 * already been filed and verified ("Invalid state: step 0 - Finish report
 * rejected: the run modified 17 file(s)…").
 *
 * The policy must (1) give the model the exact list back for a bounded number of
 * rounds, (2) amend the report from the run's trace rather than fail a finished
 * run, and (3) still reject anything that is a lie rather than an omission.
 */
@DisplayName("FinishReportRepair")
class FinishReportRepairTest {

    private fun omission(vararg files: String): FinishReportGate.Verdict =
        FinishReportGate.Verdict(
            violations = listOf(
                "the run modified ${files.size} file(s) the report does not list: " +
                    files.take(5).joinToString(", ")
            ),
            warnings = emptyList(),
            unlistedFiles = files.toList(),
        )

    private fun passed(): FinishReportGate.Verdict =
        FinishReportGate.Verdict(violations = emptyList(), warnings = emptyList())

    private fun lie(): FinishReportGate.Verdict =
        FinishReportGate.Verdict(
            violations = listOf("filesChanged lists 1 file(s) git does not show as changed: fake.kt"),
            warnings = emptyList(),
        )

    @Test
    @DisplayName("a passing report needs no repair")
    fun passingReportIsAccepted() {
        val decision = FinishReportRepair(maxRounds = 3).decide(passed())

        assertEquals(FinishReportRepair.Action.PASSED, decision.action)
        assertNull(decision.message)
        assertNull(decision.amendedFiles)
    }

    @Test
    @DisplayName("an omission spends the budget, then the report is amended from the trace")
    fun omissionFollowsTheBudgetThenAmends() {
        val policy = FinishReportRepair(maxRounds = 2)
        val verdict = omission("ev.js", "evf.js")
        val reported = listOf("issue_body.md")

        val first = policy.decide(verdict, reported)
        assertEquals(FinishReportRepair.Action.RETRY, first.action)
        assertEquals(1, first.round)
        assertTrue(first.message!!.contains("ev.js"), first.message)
        assertTrue(first.message.contains("evf.js"), first.message)
        assertTrue(
            first.message.contains("coding.changeSummary"),
            "the repair must name the authoritative source: ${first.message}"
        )

        val second = policy.decide(verdict, reported)
        assertEquals(FinishReportRepair.Action.RETRY, second.action)
        assertEquals(2, second.round)

        val third = policy.decide(verdict, reported)
        assertEquals(FinishReportRepair.Action.AMEND, third.action, "the budget is spent: amend, do not fail")
        assertEquals(2, policy.roundsUsed)
        assertEquals(listOf("issue_body.md", "ev.js", "evf.js"), third.amendedFiles)
    }

    @Test
    @DisplayName("no loop budget left means amend immediately")
    fun noRepairRoundAmendsImmediately() {
        val policy = FinishReportRepair(maxRounds = 3)

        val decision = policy.decide(omission("ev.js"), listOf("issue_body.md"), canRepair = false)

        assertEquals(FinishReportRepair.Action.AMEND, decision.action)
        assertEquals(0, policy.roundsUsed, "without a loop there is no round to spend")
        assertEquals(listOf("issue_body.md", "ev.js"), decision.amendedFiles)
    }

    @Test
    @DisplayName("repair maxRounds=0 amends without asking")
    fun zeroRoundsAmendsWithoutAsking() {
        val decision = FinishReportRepair(maxRounds = 0).decide(omission("ev.js"))

        assertEquals(FinishReportRepair.Action.AMEND, decision.action)
    }

    @Test
    @DisplayName("a lie is rejected, never amended")
    fun nonAmendableViolationIsRejected() {
        val decision = FinishReportRepair(maxRounds = 3).decide(lie())

        assertEquals(FinishReportRepair.Action.REJECT, decision.action)
        assertNull(decision.amendedFiles)
    }

    @Test
    @DisplayName("an omission reported together with a lie is rejected too")
    fun omissionPlusLieIsRejected() {
        val verdict = FinishReportGate.Verdict(
            violations = listOf(
                "filesChanged lists 1 file(s) git does not show as changed: fake.kt",
                "the run modified 1 file(s) the report does not list: ev.js",
            ),
            warnings = emptyList(),
            unlistedFiles = listOf("ev.js"),
        )

        val decision = FinishReportRepair(maxRounds = 3).decide(verdict)

        assertEquals(FinishReportRepair.Action.REJECT, decision.action)
    }

    @Test
    @DisplayName("fast-fail mode restores the pre-fix rejection")
    fun rejectModeDisablesAmend() {
        val decision = FinishReportRepair(maxRounds = 3, amendUnlistedFiles = false)
            .decide(omission("ev.js"), listOf("issue_body.md"))

        assertEquals(FinishReportRepair.Action.REJECT, decision.action)
    }

    @Test
    @DisplayName("the amended list keeps the report order and adds no duplicates")
    fun amendMergesWithoutDuplicates() {
        val verdict = omission("ev.js", "scan3.js")

        val decision = FinishReportRepair(maxRounds = 0)
            .decide(verdict, listOf("./ev.js", "issue_body.md", "issue_body.md"))

        assertEquals(FinishReportRepair.Action.AMEND, decision.action)
        assertEquals(
            listOf("./ev.js", "issue_body.md", "scan3.js"),
            decision.amendedFiles,
            "the report's own entries come first, in order, and are not duplicated",
        )
    }

    @Test
    @DisplayName("a passing report resets the spent budget")
    fun passingResetsBudget() {
        val policy = FinishReportRepair(maxRounds = 2)
        policy.decide(omission("a.js"))
        assertEquals(1, policy.roundsUsed)

        policy.decide(passed())
        assertEquals(0, policy.roundsUsed)

        policy.reset()
        assertEquals(0, policy.roundsUsed)
    }

    @Test
    @DisplayName("the repair message stays bounded on a long omission list")
    fun repairMessageIsBounded() {
        val files = (1..FinishReportRepair.MAX_LISTED_FILES + 5).map { "scratch$it.js" }

        val message = FinishReportRepair.formatRepair(omission(*files.toTypedArray()), round = 1, maxRounds = 3)

        assertTrue(message.contains("1. scratch1.js"), message)
        assertFalse(message.contains("scratch${FinishReportRepair.MAX_LISTED_FILES + 1}.js"), message)
        assertTrue(message.contains("5 more omitted"), message)

        val truncated = FinishReportRepair.formatRepair(
            omission(*files.toTypedArray()), round = 1, maxRounds = 3, maxChars = 200,
        )
        assertTrue(truncated.length <= 200, "length=${truncated.length}")
        assertTrue(truncated.contains("truncated"), truncated)
    }
}
