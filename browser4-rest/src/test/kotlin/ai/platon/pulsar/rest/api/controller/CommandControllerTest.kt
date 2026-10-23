package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.entities.CommandInputRequest
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.entities.CommandSubmitResponse
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `POST /api/commands/json` is the REST entry to a browser page visit, so two things must
 * hold at the HTTP boundary: an unusable url is rejected *before* anything is allocated,
 * and the async answer is the JSON object its declared content type promises.
 *
 * The executor is mocked — these are the controller's own decisions, not a browser test.
 * The end-to-end shape assertion lives in `CommandControllerE2ETest` (rest-tests).
 * */
@Tag("Unit")
@Tag("Fast")
@DisplayName("structured command endpoint contract")
class CommandControllerTest {

    private val executor: UserCommandExecutor = Mockito.mock(UserCommandExecutor::class.java)
    private val controller = CommandController(executor)
    private val mapper = pulsarObjectMapper()

    @Test
    @DisplayName("an async submission answers 202 with the task id as a JSON object")
    fun asyncSubmissionAnswersAcceptedWithJsonTaskId() {
        runBlocking {
            whenever(executor.submitPageVisitCommand(any(), any(), any())).thenReturn("task-123")

            val response = controller.submitJsonCommand(
                PageVisitRequest(url = "https://example.com/product/1", async = true)
            )

            assertEquals(HttpStatus.ACCEPTED, response.statusCode)
            assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType)
            assertEquals(CommandSubmitResponse("task-123"), response.body)

            // The wire shape the declared `produces = application/json` promises: a bare id
            // string is not parseable JSON, so the id must travel inside an object.
            val wire = mapper.readValue(mapper.writeValueAsString(response.body), Map::class.java)
            assertEquals(mapOf("id" to "task-123"), wire)
        }
    }

    @Test
    @DisplayName("a sync submission returns the visit status with the validated url")
    fun syncSubmissionReturnsTheVisitStatus() {
        runBlocking {
            val status = CommandStatus(
                id = "task-1",
                statusCode = ResourceStatus.SC_OK,
                processState = "completed",
            )
            whenever(executor.executePageVisitCommand(any(), any(), any())).thenReturn(status)

            val request = PageVisitRequest(url = "  https://example.com/product/1  ", args = "-refresh")
            val response = controller.submitJsonCommand(request)

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(status, response.body)
            assertEquals("https://example.com/product/1", request.url, "the url is trimmed once, then validated")
        }
    }

    @Test
    @DisplayName("an unusable url is rejected before any session, visitor or browser is allocated")
    fun unusableUrlIsRejectedBeforeAllocation() {
        listOf("", "   ", "not a url", "javascript:alert(1)").forEach { url ->
            val response = runBlocking {
                controller.submitJsonCommand(PageVisitRequest(url = url))
            }

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode, "url='$url' must be rejected")

            val status = assertNotNull(response.body as? CommandStatus, "url='$url' keeps the CommandStatus shape")
            assertEquals(ResourceStatus.SC_BAD_REQUEST, status.statusCode, "url='$url'")
            assertEquals("Invalid URL: '$url'", status.message, "the message names the rejected url")
            assertTrue(status.isDone, "url='$url' must not look pollable")
        }

        // The whole point of validating first: a bad request never reaches the executor,
        // so it can never create a managed session or launch a browser.
        Mockito.verifyNoInteractions(executor)
    }

    @Test
    @DisplayName("a plain async submission answers 202 with the same JSON task id")
    fun plainAsyncSubmissionAnswersAcceptedWithJsonTaskId() {
        runBlocking {
            whenever(executor.submitPlainCommand(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn("agent-task-9")

            val response = controller.submitPlainCommand("open the browser", async = true)

            assertEquals(HttpStatus.ACCEPTED, response.statusCode)
            assertEquals(MediaType.APPLICATION_JSON, response.headers.contentType)
            assertEquals(CommandSubmitResponse("agent-task-9"), response.body)

            val wire = mapper.readValue(mapper.writeValueAsString(response.body), Map::class.java)
            assertEquals(mapOf("id" to "agent-task-9"), wire, "the two command routes must agree on the shape")
        }
    }

    @Test
    @DisplayName("a plain sync submission still returns the command status under 200")
    fun plainSyncSubmissionReturnsTheCommandStatus() {
        runBlocking {
            val status = CommandStatus(
                id = "task-2",
                statusCode = ResourceStatus.SC_OK,
                processState = "completed",
            )
            whenever(executor.executePlainCommand(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(status)

            val response = controller.submitPlainCommand("open the browser")

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(status, response.body)
        }
    }

    @Test
    @DisplayName("a cancel that cannot happen names the real reason, not a wrong one")
    fun cancelNamesWhyItDidNotHappen() {
        whenever(executor.cancelAgentTask("agent-1")).thenReturn(true)
        whenever(executor.cancelAgentTask("page-1")).thenReturn(false)
        whenever(executor.isPageVisitTask("page-1")).thenReturn(true)
        whenever(executor.cancelAgentTask("gone")).thenReturn(false)
        whenever(executor.isPageVisitTask("gone")).thenReturn(false)

        assertEquals(
            mapOf("taskId" to "agent-1", "cancelled" to true),
            controller.cancelCommand("agent-1").body,
        )

        // A live page visit is not "unknown": it was accepted, is running, and simply
        // cannot be interrupted — the CLI prints this message straight to the user.
        val page = controller.cancelCommand("page-1").body
        assertEquals(false, page?.get("cancelled"))
        assertEquals("page visit tasks run to completion and cannot be cancelled", page?.get("message"))

        val gone = controller.cancelCommand("gone").body
        assertEquals(false, gone?.get("cancelled"))
        assertEquals("task not running or unknown", gone?.get("message"))
    }

    // ---- session resolution -------------------------------------------------

    @Test
    @DisplayName("the body's session wins, but a blank body session does not shadow a named query")
    fun theBodySessionWinsAndABlankOneIsNotASession() {
        runBlocking {
            whenever(executor.executePageVisitCommand(any(), any(), any())).thenReturn(aVisitStatus())

            controller.submitJsonCommand(
                PageVisitRequest(url = URL, sessionId = "body-session"),
                sessionId = "query-session",
            )
            verify(executor).executePageVisitCommand(eq("body-session"), any(), any())

            // A blank body value is not an explicit choice, so the named query parameter is
            // the one session this request named — not a conflict to warn about.
            controller.submitJsonCommand(
                PageVisitRequest(url = URL, sessionId = "   "),
                sessionId = "query-session",
            )
            verify(executor).executePageVisitCommand(eq("query-session"), any(), any())
        }
    }

    @Test
    @DisplayName("a blank or absent sessionId falls back to the default session, never to \"\"")
    fun aBlankSessionIdFallsBackToTheDefault() {
        runBlocking {
            whenever(executor.executePageVisitCommand(any(), any(), any())).thenReturn(aVisitStatus())

            // The regression: read literally, `?sessionId=` reached the executor as a session
            // whose id is the empty string — a session the caller never opened, addressed by
            // a parameter that looks like it was left out.
            val sessions = argumentCaptor<String>()
            listOf(null, "", "   ").forEach { blank ->
                controller.submitJsonCommand(PageVisitRequest(url = URL), sessionId = blank)
            }

            verify(executor, times(3)).executePageVisitCommand(sessions.capture(), any(), any())
            // Every blank spelling resolves to the same session, and none of them arrives as "".
            assertEquals(
                listOf(DEFAULT_SESSION_ID, DEFAULT_SESSION_ID, DEFAULT_SESSION_ID),
                sessions.allValues,
            )
        }
    }

    private fun aVisitStatus() = CommandStatus(
        id = "task-session",
        statusCode = ResourceStatus.SC_OK,
        processState = "completed",
    )

    // ---- live step log (`agent run --follow` / `-i`) ------------------------

    @Test
    @DisplayName("the events endpoint answers the page, its cursor and the terminal flag")
    fun eventsEndpointAnswersPageCursorAndDoneFlag() {
        val running = CommandStatus(
            id = "agent-1",
            statusCode = ResourceStatus.SC_PROCESSING,
            processState = "in_progress",
        )
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-1")).thenReturn(running)
        whenever(executor.events("agent-1", 2L, 200)).thenReturn(
            listOf(
                AgentTaskEvent(seq = 3, type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "coding.read"),
                AgentTaskEvent(seq = 4, type = AgentTaskEvent.TYPE_MODEL_TEXT, text = "done soon"),
            )
        )

        val response = controller.getEvents("agent-1", after = 2, limit = 200)

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = assertNotNull(response.body)
        assertEquals("agent-1", body.taskId)
        assertEquals("running", body.status)
        assertEquals(false, body.done)
        assertEquals(4L, body.nextSeq, "the cursor is the last delivered sequence")
        assertEquals(listOf(3L, 4L), body.events.map { it.seq })
    }

    @Test
    @DisplayName("an unknown id is 404; a finished id with no new events keeps the cursor")
    fun eventsEndpointHandlesUnknownAndEmptyPages() {
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "nope")).thenReturn(null)
        whenever(executor.events("nope", 0L, 200)).thenReturn(emptyList())

        assertEquals(
            HttpStatus.NOT_FOUND,
            controller.getEvents("nope", after = 0, limit = 200).statusCode,
            "a follower must be able to tell 'unknown id' from 'nothing new'",
        )

        val finished = CommandStatus(
            id = "agent-2",
            statusCode = ResourceStatus.SC_OK,
            processState = "completed",
        )
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-2")).thenReturn(finished)
        whenever(executor.events("agent-2", 7L, 200)).thenReturn(emptyList())

        val body = assertNotNull(controller.getEvents("agent-2", after = 7, limit = 200).body)
        assertTrue(body.done, "a finished task must stop the follower")
        assertEquals("done", body.status)
        assertEquals(7L, body.nextSeq, "an empty page must not rewind the cursor")
        assertTrue(body.events.isEmpty())
    }

    @Test
    @DisplayName("a finished task whose status row expired still reports done from its last event")
    fun eventsEndpointDerivesDoneFromTheTerminalEvent() {
        // The status cache expires 2h after its last WRITE while the event log
        // expires after its last read, so a followed task can outlive its status
        // row. Reporting `done=false` then would make an uncapped follower poll
        // forever.
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-3")).thenReturn(null)
        whenever(executor.events("agent-3", 0L, 200)).thenReturn(
            listOf(
                AgentTaskEvent(seq = 1, type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "b4.run"),
                AgentTaskEvent(seq = 2, type = AgentTaskEvent.TYPE_DONE, text = "finished"),
            )
        )

        val body = assertNotNull(controller.getEvents("agent-3", after = 0, limit = 200).body)

        assertTrue(body.done, "a terminal event must stop the follower")
        assertEquals("done", body.status)
        assertEquals(2L, body.nextSeq)
    }

    @Test
    @DisplayName("steering input is accepted for a live task and reported inline otherwise")
    fun steeringInputIsAcceptedOrReportedInline() {
        whenever(executor.sendInput("agent-1", "focus on the tests")).thenReturn(true)
        whenever(executor.sendInput("agent-2", "too late")).thenReturn(false)

        val accepted = assertNotNull(
            controller.postInput("agent-1", CommandInputRequest("focus on the tests")).body
        )
        assertTrue(accepted.accepted)
        assertEquals("agent-1", accepted.taskId)

        // "The task finished while the user was typing" is a race, not an error:
        // the CLI prints the reason and keeps the session open.
        val rejected = assertNotNull(
            controller.postInput("agent-2", CommandInputRequest("too late")).body
        )
        assertEquals(false, rejected.accepted)
        assertNotNull(rejected.message)

        val blank = controller.postInput("agent-1", CommandInputRequest("   "))
        assertEquals(HttpStatus.BAD_REQUEST, blank.statusCode)
        assertEquals(false, assertNotNull(blank.body).accepted)
    }

    private companion object {
        const val URL = "https://example.com/product/1"
    }
}
