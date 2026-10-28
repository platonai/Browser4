package ai.platon.pulsar.rest.api.entities

import ai.platon.browser4.common.B4Constants.PAGE_STATUS_UNKNOWN
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import ai.platon.pulsar.common.ResourceStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * `pageStatusCode` describes the PAGE. An agent or loop record must therefore
 * report the status the page returned, or "not observed" — never the
 * task-lifecycle code `201 / SC_CREATED`, which used to be what the conversion
 * shipped for every agent task (the field was dropped entirely, so the DTO's
 * default leaked).
 */
@DisplayName("Command status page status")
class CommandStatusPageStatusTest {

    @Test
    @DisplayName("the page status an agent observed survives the conversion")
    fun observedPageStatusIsCarriedIntoTheCommandStatus() {
        val agentStatus = AgentTaskStatus(statusCode = ResourceStatus.SC_OK, pageStatusCode = 200)

        val commandStatus = agentStatus.toCommandStatus()

        assertEquals(200, commandStatus.pageStatusCode)
        assertEquals(ResourceStatus.SC_OK, commandStatus.statusCode, "the task status is a separate field")
    }

    @Test
    @DisplayName("a task that observed no page reports unknown, not 201")
    fun unobservedPageStatusIsUnknown() {
        val commandStatus = AgentTaskStatus(statusCode = ResourceStatus.SC_OK).toCommandStatus()

        assertEquals(PAGE_STATUS_UNKNOWN, commandStatus.pageStatusCode)
        assertNotEquals(
            ResourceStatus.SC_CREATED,
            commandStatus.pageStatusCode,
            "201/'created' must never stand in for the page's HTTP status",
        )
    }

    @Test
    @DisplayName("a page that really returned 201 is still reported as 201")
    fun realCreatedStatusIsPreserved() {
        val commandStatus = AgentTaskStatus(pageStatusCode = ResourceStatus.SC_CREATED).toCommandStatus()

        assertEquals(ResourceStatus.SC_CREATED, commandStatus.pageStatusCode)
        assertNotEquals(
            PAGE_STATUS_UNKNOWN,
            commandStatus.pageStatusCode,
            "a real 201 from the page must stay distinguishable from 'not observed'",
        )
    }

    @Test
    @DisplayName("a command that never observed a page does not claim a page status")
    fun bareCommandStatusReportsUnknown() {
        // The default path: nothing observed a page, so nothing may be claimed.
        assertEquals(PAGE_STATUS_UNKNOWN, CommandStatus().pageStatusCode)
        assertEquals(PAGE_STATUS_UNKNOWN, CommandStatus.notFound("missing").pageStatusCode)
        assertEquals(
            PAGE_STATUS_UNKNOWN,
            CommandStatus(id = "x", statusCode = ResourceStatus.SC_BAD_REQUEST, processState = "done").pageStatusCode,
        )
    }

    @Test
    @DisplayName("an explicit page status from a caller is still honoured")
    fun explicitPageStatusWins() {
        // `failed(statusCode)` passes pageStatusCode = statusCode deliberately (a page
        // load that timed out really did fail with that code). The fix is about the
        // UNSET default, so an explicit value must survive untouched.
        assertEquals(
            ResourceStatus.SC_EXPECTATION_FAILED,
            CommandStatus.failed(ResourceStatus.SC_EXPECTATION_FAILED).pageStatusCode,
        )
        assertEquals(404, CommandStatus.failed("id", 404, 404).pageStatusCode)
    }

    // -----------------------------------------------------------------
    // Failure semantics (P0-6)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a failed run's errorCode, resumability and budget survive the conversion")
    fun failureSemanticsAreCarriedIntoThePayload() {
        val agentStatus = AgentTaskStatus(
            statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
            processState = "done",
            message = "Token budget exceeded: consumed 5,006,041 tokens",
        )
        agentStatus.failureReason = "TokenBudgetExceededException: Token budget exceeded"
        agentStatus.errorCode = AgentTaskStatus.ERROR_TOKEN_BUDGET
        agentStatus.resumable = true
        agentStatus.effectiveTokenBudget = 5_000_000

        val commandStatus = agentStatus.toCommandStatus()
        val result = commandStatus.commandResult!!

        assertEquals(AgentTaskStatus.ERROR_TOKEN_BUDGET, commandStatus.errorCode)
        assertEquals(true, commandStatus.resumable)
        assertEquals(5_000_000L, commandStatus.effectiveTokenBudget)
        assertEquals(AgentTaskStatus.ERROR_TOKEN_BUDGET, result.errorCode)
        assertEquals(true, result.resumable)
        assertEquals(5_000_000L, result.effectiveTokenBudget)
    }

    @Test
    @DisplayName("a failed run's summary says what it wrote and how to resume")
    fun failedSummaryKeepsTheDeliverablesAndResumeHint() {
        // A budget abort used to read as a bare exception, so a 20×75 CSV already on
        // disk looked lost (2026-10-28 baseline).
        val agentStatus = AgentTaskStatus(
            id = "abc-123",
            statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
            processState = "done",
        )
        agentStatus.failureReason = "TokenBudgetExceededException: Token budget exceeded"
        agentStatus.errorCode = AgentTaskStatus.ERROR_TOKEN_BUDGET
        agentStatus.resumable = true
        agentStatus.deliverables = listOf("amazon_microwaves.csv")

        val result = agentStatus.toCommandStatus().commandResult!!

        assertEquals(listOf("amazon_microwaves.csv"), result.filesChanged)
        val summary = result.summary.orEmpty()
        assertTrue(summary.contains("wrote 1 file(s)"), summary)
        assertTrue(summary.contains("amazon_microwaves.csv"), summary)
        assertTrue(summary.contains("agent run --resume abc-123"), summary)
    }
}
