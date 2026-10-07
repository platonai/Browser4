package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
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
            whenever(executor.submitPlainCommand(any(), any(), anyOrNull(), anyOrNull())).thenReturn("agent-task-9")

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
            whenever(executor.executePlainCommand(any(), any(), anyOrNull(), anyOrNull())).thenReturn(status)

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

    private companion object {
        const val URL = "https://example.com/product/1"
    }
}
