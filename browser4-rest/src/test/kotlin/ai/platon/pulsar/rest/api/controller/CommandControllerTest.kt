package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.agents.AgentGoal
import ai.platon.pulsar.agentic.agents.GoalStep
import ai.platon.pulsar.agentic.agents.GoalStepStatus
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEventPage
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskRunStats
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskStatus
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.entities.CommandGoalRequest
import ai.platon.pulsar.rest.api.entities.CommandInputRequest
import ai.platon.pulsar.rest.api.entities.CommandResult
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.entities.CommandSubmitResponse
import ai.platon.pulsar.rest.api.entities.CommandTraceFile
import ai.platon.pulsar.rest.api.entities.CommandTraceFileResponse
import ai.platon.pulsar.rest.api.entities.CommandTraceResponse
import ai.platon.pulsar.rest.api.entities.toCommandStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
            whenever(
                executor.submitPlainCommand(
                    any(), any(), anyOrNull(), anyOrNull(), anyOrNull(),
                    anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                    // addDirs (11th parameter, defaulted): a defaulted argument
                    // still occupies a matcher slot, so omitting it mixes raw
                    // values with matchers and fails the stubbing.
                    anyOrNull(),
                    // unrestricted (12th parameter, defaulted) — same rule: every
                    // declared parameter needs its own matcher, even when the
                    // caller never passes it.
                    anyOrNull(),
                    // rewindTaskId (13th parameter, defaulted) — same rule.
                    anyOrNull(),
                    // workspace (14th parameter, defaulted) — the console's dialog may name it.
                    anyOrNull(),
                    // intent (15th parameter, defaulted) — the console's live-view switch.
                    anyOrNull(),
                )
            ).thenReturn("agent-task-9")

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
            whenever(
                executor.executePlainCommand(
                    any(), any(), anyOrNull(), anyOrNull(), anyOrNull(),
                    anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                    // addDirs (11th parameter, defaulted) — see the async test above.
                    anyOrNull(),
                    // unrestricted (12th parameter, defaulted) — see the async test above.
                    anyOrNull(),
                    // rewindTaskId (13th parameter, defaulted) — added with the pi
                    // context alignment; a defaulted argument still occupies a matcher
                    // slot, so omitting it fails the whole stub.
                    anyOrNull(),
                    // workspace (14th parameter, defaulted) — see the async test above.
                    anyOrNull(),
                    // intent (15th parameter, defaulted) — see the async test above.
                    anyOrNull(),
                )
            ).thenReturn(status)

            val response = controller.submitPlainCommand("open the browser")

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(status, response.body)
        }
    }

    @Test
    @DisplayName("a plain submission forwards the chosen workspace, so the task runs in place there")
    fun plainSubmissionForwardsTheWorkspace() {
        runBlocking {
            val dir = java.nio.file.Files.createTempDirectory("b4-console-workspace-")
            try {
                whenever(
                    executor.submitPlainCommand(
                        any(), any(), anyOrNull(), anyOrNull(), anyOrNull(),
                        anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                        anyOrNull(), anyOrNull(), anyOrNull(),
                        // workspace (14th): the one value this test is about.
                        anyOrNull(),
                        // intent (15th, defaulted): every parameter needs its matcher.
                        anyOrNull(),
                    )
                ).thenReturn("agent-task-ws")

                val response = controller.submitPlainCommand(
                    "collect the rows", async = true, workspace = dir.toString(),
                )

                assertEquals(HttpStatus.ACCEPTED, response.statusCode)
                assertEquals(CommandSubmitResponse("agent-task-ws"), response.body)
                verify(executor).submitPlainCommand(
                    any(), any(), anyOrNull(), anyOrNull(), anyOrNull(),
                    anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                    anyOrNull(), anyOrNull(), anyOrNull(),
                    eq(dir.toAbsolutePath().normalize()),
                    anyOrNull(),
                )
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    @DisplayName("an unusable workspace is rejected before a task is created")
    fun unusableWorkspaceIsRejected() {
        runBlocking {
            val dir = java.nio.file.Files.createTempDirectory("b4-console-workspace-")
            try {
                val missing = dir.resolve("does-not-exist")

                val response = controller.submitPlainCommand(
                    "collect the rows", async = true, workspace = missing.toString(),
                )

                assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
                val status = response.body as CommandStatus
                assertTrue(status.message!!.contains("not an existing directory"), status.message)
                assertTrue(status.isDone, "a rejected submission must not look pollable")
                Mockito.verifyNoInteractions(executor)
            } finally {
                dir.toFile().deleteRecursively()
            }
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
        whenever(executor.eventsPage("agent-1", 2L, 200)).thenReturn(
            AgentTaskEventPage(
                events = listOf(
                    AgentTaskEvent(seq = 3, type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "coding.read"),
                    AgentTaskEvent(seq = 4, type = AgentTaskEvent.TYPE_MODEL_TEXT, text = "done soon"),
                ),
                nextSeq = 4,
                firstRetainedSeq = 1,
                lastSeq = 4,
                dropped = 0,
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
        assertEquals(1L, body.firstRetainedSeq, "the client can see where the window begins")
        assertEquals(0L, body.dropped)
        assertEquals(false, body.resynced, "a cursor inside the window is a real continuation")
    }

    @Test
    @DisplayName("an unknown id is 404; a finished id with no new events keeps the cursor")
    fun eventsEndpointHandlesUnknownAndEmptyPages() {
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "nope")).thenReturn(null)
        whenever(executor.eventsPage("nope", 0L, 200)).thenReturn(null)

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
        whenever(executor.eventsPage("agent-2", 7L, 200)).thenReturn(
            AgentTaskEventPage(events = emptyList(), nextSeq = 7, firstRetainedSeq = 1, lastSeq = 7)
        )

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
        whenever(executor.eventsPage("agent-3", 0L, 200)).thenReturn(
            AgentTaskEventPage(
                events = listOf(
                    AgentTaskEvent(seq = 1, type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "b4.run"),
                    AgentTaskEvent(seq = 2, type = AgentTaskEvent.TYPE_DONE, text = "finished"),
                ),
                nextSeq = 2,
                firstRetainedSeq = 1,
                lastSeq = 2,
            )
        )

        val body = assertNotNull(controller.getEvents("agent-3", after = 0, limit = 200).body)

        assertTrue(body.done, "a terminal event must stop the follower")
        assertEquals("done", body.status)
        assertEquals(2L, body.nextSeq)
    }

    @Test
    @DisplayName("a cursor behind the retained window is reported as resynced, never as an empty gap")
    fun eventsEndpointReportsAResyncedCursor() {
        // A supervisor that slept past the 1000-step ring must be told its cursor fell
        // behind the window; returning an empty page with the cursor unchanged is
        // indistinguishable from "no new steps" (issue #652, P0-D).
        val running = CommandStatus(
            id = "agent-4",
            statusCode = ResourceStatus.SC_PROCESSING,
            processState = "in_progress",
        )
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-4")).thenReturn(running)
        whenever(executor.eventsPage("agent-4", 3L, 200)).thenReturn(
            AgentTaskEventPage(
                events = listOf(AgentTaskEvent(seq = 500, type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "b4.run")),
                nextSeq = 500,
                firstRetainedSeq = 500,
                lastSeq = 900,
                dropped = 499,
                resynced = true,
            )
        )

        val body = assertNotNull(controller.getEvents("agent-4", after = 3, limit = 200).body)

        assertTrue(body.resynced, "the follower must know it lost steps")
        assertEquals(500L, body.firstRetainedSeq)
        assertEquals(499L, body.dropped)
    }

    @Test
    @DisplayName("steering input is accepted for a live task and reported inline otherwise")
    fun steeringInputIsAcceptedOrReportedInline() {
        whenever(executor.sendInput("agent-1", "focus on the tests")).thenReturn("input-7")
        whenever(executor.sendInput("agent-2", "too late")).thenReturn(null)

        val accepted = assertNotNull(
            controller.postInput("agent-1", CommandInputRequest("focus on the tests")).body
        )
        assertTrue(accepted.accepted)
        assertEquals("agent-1", accepted.taskId)
        // The caller can recognise its own message later: the delivered `steer` step
        // repeats this id in `detail.inputIds` (issue #652, P1-5).
        assertEquals("input-7", accepted.inputId)

        // "The task finished while the user was typing" is a race, not an error:
        // the CLI prints the reason and keeps the session open.
        val rejected = assertNotNull(
            controller.postInput("agent-2", CommandInputRequest("too late")).body
        )
        assertEquals(false, rejected.accepted)
        assertNull(rejected.inputId)
        assertNotNull(rejected.message)

        val blank = controller.postInput("agent-1", CommandInputRequest("   "))
        assertEquals(HttpStatus.BAD_REQUEST, blank.statusCode)
        assertEquals(false, assertNotNull(blank.body).accepted)
    }

    // ---- the goal of a task (goal mode, `/goal`) ---------------------------

    @Test
    @DisplayName("the goal endpoint answers the pinned goal and its plan")
    fun theGoalEndpointAnswersTheGoalAndPlan() {
        val status = CommandStatus(id = "agent-1", statusCode = ResourceStatus.SC_PROCESSING, processState = "in_progress")
        status.goal = AgentGoal.fromInstruction("extract the price of the product")
            .withPlan(
                listOf(
                    GoalStep("open the product page", GoalStepStatus.DONE),
                    GoalStep("extract the price"),
                ),
                listOf("the price is in the answer"),
            )
            .statusView(round = 3)
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-1")).thenReturn(status)

        val body = assertNotNull(controller.getGoal("agent-1").body)

        assertEquals("agent-1", body.taskId)
        assertEquals(false, body.isDone)
        val goal = assertNotNull(body.goal, "every agent task runs under a goal")
        assertEquals("extract the price of the product", goal.text)
        assertEquals(2, goal.steps.size)
        assertEquals(1, goal.pending, "the open step is what a supervisor has to see")
        assertEquals(3, goal.round)
        assertEquals(listOf("the price is in the answer"), goal.doneWhen)

        // An unknown id is a 404, not an empty goal.
        assertEquals(HttpStatus.NOT_FOUND, controller.getGoal("nope").statusCode)
    }

    @Test
    @DisplayName("replacing a goal is accepted for a live task and reported inline otherwise")
    fun replacingTheGoalIsAcceptedOrReportedInline() {
        val status = CommandStatus(id = "agent-1", statusCode = ResourceStatus.SC_PROCESSING, processState = "in_progress")
        status.goal = AgentGoal.fromInstruction("collect every offer")
            .amended("only the three cheapest offers", "operator: /goal")
            .statusView()
        whenever(executor.amendGoal("agent-1", "only the three cheapest offers", "operator: /goal"))
            .thenReturn("amend-7")
        whenever(executor.amendGoal("agent-2", "too late", null)).thenReturn(null)
        whenever(executor.getStatus(eq(DEFAULT_SESSION_ID), any())).thenReturn(status)

        val accepted = assertNotNull(
            controller.postGoal(
                "agent-1",
                CommandGoalRequest("only the three cheapest offers", "operator: /goal"),
            ).body
        )
        assertTrue(accepted.accepted)
        assertEquals("amend-7", accepted.amendmentId)
        assertEquals(2, accepted.revision, "the operator's change is revision 2, not a new goal")

        // "The task finished while the operator was writing" is a race, not an error.
        val rejected = assertNotNull(
            controller.postGoal("agent-2", CommandGoalRequest("too late")).body
        )
        assertEquals(false, rejected.accepted)
        assertNull(rejected.amendmentId)

        val blank = controller.postGoal("agent-1", CommandGoalRequest("   "))
        assertEquals(HttpStatus.BAD_REQUEST, blank.statusCode)
        assertEquals(false, assertNotNull(blank.body).accepted)
    }

    // ---- run statistics (the interactive `/stats` command) ------------------
    @Test
    @DisplayName("the stats endpoint answers the counters, the status word and the elapsed time")
    fun statsEndpointAnswersCountersStatusAndElapsed() {
        val running = CommandStatus(
            id = "agent-1",
            statusCode = ResourceStatus.SC_PROCESSING,
            processState = "in_progress",
        )
        // 12s into the run: the elapsed time is measured from `startedTime`, so a
        // call that has been running for a while does not report 0.
        running.startedTime = Instant.now().minusSeconds(12)
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-1")).thenReturn(running)
        whenever(executor.stats("agent-1")).thenReturn(
            AgentTaskRunStats(
                rounds = 3,
                modelRequests = 11,
                toolCalls = 39,
                toolFailures = 2,
                steerMessages = 1,
                inputTokens = 48_231,
                outputTokens = 6_120,
                totalTokens = 54_351,
                cachedInputTokens = 38_585,
                cacheWriteInputTokens = 120,
                cacheReported = true,
                events = 132,
                toolCounts = linkedMapOf("b4.run" to 12L, "browser_snapshot" to 8L),
            )
        )

        val response = controller.getStats("agent-1")

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = assertNotNull(response.body)
        assertEquals("agent-1", body.taskId)
        assertEquals("running", body.status)
        assertEquals(false, body.isDone)
        assertTrue(
            assertNotNull(body.elapsedMs) >= 12_000,
            "elapsedMs must be measured from startedTime, got ${body.elapsedMs}",
        )
        assertEquals(3, body.stats.rounds)
        assertEquals(11, body.stats.modelRequests)
        assertEquals(39, body.stats.toolCalls)
        assertEquals(2, body.stats.toolFailures)
        assertEquals(54_351, body.stats.totalTokens)
        // The prompt-cache breakdown is part of the wire contract the CLI reads.
        assertEquals(38_585, body.stats.cachedInputTokens)
        assertEquals(120, body.stats.cacheWriteInputTokens)
        assertTrue(body.stats.cacheReported)
        assertEquals(38_585.0 / 48_231.0, body.stats.cacheHitRatio!!, 1e-9)
        assertEquals(12L, body.stats.toolCounts["b4.run"])
    }

    @Test
    @DisplayName("a finished task keeps its counters after the status row expires")
    fun statsEndpointSurvivesAnExpiredStatusRow() {
        // The status cache expires 2h after its last write while the counters live
        // in the event log: reporting 404 then would lose the numbers of a run
        // that just finished.
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-2")).thenReturn(null)
        whenever(executor.stats("agent-2")).thenReturn(
            AgentTaskRunStats(rounds = 1, modelRequests = 2, toolCalls = 4, events = 9)
        )

        val body = assertNotNull(controller.getStats("agent-2").body)

        assertEquals("unknown", body.status, "a gone status row must not be invented")
        assertEquals(false, body.isDone)
        assertEquals(2, body.stats.modelRequests)
    }

    @Test
    @DisplayName("an id with no run log answers 404")
    fun statsEndpointIs404ForAnUnknownId() {
        whenever(executor.stats("nope")).thenReturn(null)

        assertEquals(HttpStatus.NOT_FOUND, controller.getStats("nope").statusCode)
    }

    @Test
    @DisplayName("the stats wire shape carries the keys the CLI reads")
    fun statsWireShapeCarriesTheCliKeys() {
        // The CLI (`parse_task_stats` in agent_interactive.rs) and the mock
        // backend used by the CLI e2e both key on these exact names; renaming a
        // Kotlin property would silently turn every `/stats` report into zeros.
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-1")).thenReturn(
            CommandStatus(id = "agent-1", statusCode = ResourceStatus.SC_OK, processState = "completed")
        )
        whenever(executor.stats("agent-1")).thenReturn(
            AgentTaskRunStats(
                rounds = 3,
                modelRequests = 11,
                toolCalls = 39,
                toolFailures = 2,
                steerMessages = 1,
                inputTokens = 48_231,
                outputTokens = 6_120,
                totalTokens = 54_351,
                cachedInputTokens = 38_585,
                cacheWriteInputTokens = 120,
                cacheReported = true,
                events = 132,
                toolCounts = linkedMapOf("b4.run" to 12L),
            )
        )

        val body = assertNotNull(controller.getStats("agent-1").body)
        @Suppress("UNCHECKED_CAST")
        val wire = mapper.readValue(mapper.writeValueAsString(body), Map::class.java) as Map<String, Any?>
        val stats = wire["stats"] as Map<*, *>

        assertEquals("agent-1", wire["taskId"])
        assertEquals("done", wire["status"])
        assertEquals(true, wire["isDone"])
        assertEquals(3, stats["rounds"])
        assertEquals(11, stats["modelRequests"])
        assertEquals(39, stats["toolCalls"])
        assertEquals(2, stats["toolFailures"])
        assertEquals(48_231, stats["inputTokens"])
        assertEquals(6_120, stats["outputTokens"])
        assertEquals(54_351, stats["totalTokens"])
        // Prompt-cache breakdown: the CLI reads these names verbatim.
        assertEquals(38_585, stats["cachedInputTokens"])
        assertEquals(120, stats["cacheWriteInputTokens"])
        assertEquals(true, stats["cacheReported"])
        // `pulsarObjectMapper` rounds doubles to 2 decimals on the wire, so the
        // ratio is checked within that precision instead of bit-exactly.
        val ratio = stats["cacheHitRatio"] as Double
        assertTrue(
            ratio in 0.79..0.81,
            "the cache hit ratio must ride the wire (38,585 / 48,231 ≈ 0.80), got $ratio",
        )
        assertEquals(132, stats["events"])
        assertEquals(mapOf("b4.run" to 12), stats["toolCounts"])
    }

    @Test
    @DisplayName("stats carry the progress gauges, and an unreported gauge stays null not 0")
    fun statsCarryTheProgressGauges() {
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-5")).thenReturn(
            CommandStatus(id = "agent-5", statusCode = ResourceStatus.SC_PROCESSING, processState = "in_progress")
        )
        whenever(executor.stats("agent-5")).thenReturn(
            AgentTaskRunStats(toolCalls = 175, events = 400, mutations = 0, readOnlyRounds = 6)
        )
        // A browsing task never tracks file mutations: 0 would be a lie, null is honest.
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-6")).thenReturn(
            CommandStatus(id = "agent-6", statusCode = ResourceStatus.SC_PROCESSING, processState = "in_progress")
        )
        whenever(executor.stats("agent-6")).thenReturn(AgentTaskRunStats(toolCalls = 12, events = 30))

        val coding = assertNotNull(controller.getStats("agent-5").body)
        assertEquals(175, coding.stats.toolCalls)
        assertEquals(0L, coding.stats.mutations, "175 tool calls and 0 file mutations is the signal")
        assertEquals(6, coding.stats.readOnlyRounds)

        val browsing = assertNotNull(controller.getStats("agent-6").body)
        assertNull(browsing.stats.mutations, "an untracked gauge must not read as 0")
        assertNull(browsing.stats.readOnlyRounds)
    }

    @Test
    @DisplayName("a failed task's result carries the structured failure detail")
    fun resultCarriesTheStructuredFailureDetail() {
        // `/result` must answer "which rule, which files, which check" without the backend
        // log a remote supervisor cannot read (issue #652, acceptance criterion 1).
        val failed = CommandStatus(
            id = "agent-7",
            statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
            processState = "completed",
        )
        failed.errorCode = "TOOL_ERROR"
        failed.resumable = true
        failed.failureDetail = mapOf(
            "errorCode" to "TOOL_ERROR",
            "finishGate" to mapOf(
                "passed" to false,
                "violations" to listOf("filesChanged lists a file git does not show"),
                "unlistedFiles" to listOf("ev.js"),
            ),
        )
        whenever(executor.getResult(DEFAULT_SESSION_ID, "agent-7")).thenReturn(
            ai.platon.pulsar.rest.api.entities.CommandResult(
                summary = "rejected",
                failureDetail = failed.failureDetail,
            )
        )

        val result = assertNotNull(controller.getResult("agent-7").body)

        val detail = assertNotNull(result.failureDetail)
        @Suppress("UNCHECKED_CAST")
        val gate = detail["finishGate"] as Map<String, Any?>
        assertEquals(false, gate["passed"])
        assertEquals(listOf("ev.js"), gate["unlistedFiles"])
    }

    @Test
    @DisplayName("the step stream pushes frames with ids, honouring Last-Event-ID, and stops at the terminal step")
    fun stepStreamPushesFramesAndStopsAtTheTerminalStep() {
        // A subscriber that reconnects has one cursor: the explicit `?after=` wins, then the
        // SSE `Last-Event-ID` header, then 0. Every frame carries `id: <seq>` so a browser's
        // EventSource resumes exactly where it stopped.
        whenever(executor.launchScope()).thenReturn(CoroutineScope(Dispatchers.Unconfined))
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "agent-1")).thenReturn(
            CommandStatus(id = "agent-1", statusCode = ResourceStatus.SC_PROCESSING, processState = "in_progress")
        )
        whenever(executor.eventsPage("agent-1", 2L, 200)).thenReturn(
            AgentTaskEventPage(
                events = listOf(
                    AgentTaskEvent(seq = 3, type = AgentTaskEvent.TYPE_TOOL_CALL, tool = "b4.run"),
                    AgentTaskEvent(seq = 4, type = AgentTaskEvent.TYPE_DONE, text = "finished"),
                ),
                nextSeq = 4,
                firstRetainedSeq = 1,
            )
        )

        val response = controller.streamSteps(
            id = "agent-1",
            after = null,
            lastEventId = "2",
            limit = 200,
            maxSeconds = 0,
            sessionId = null,
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        val frames = assertNotNull(response.body).collectList().block(Duration.ofSeconds(5))
        assertNotNull(frames)
        assertEquals(2, frames.size, "one frame per step, then the stream completes: $frames")
        assertEquals(listOf("3", "4"), frames.map { it.id() }, "the id is the cursor the client sends back")
        assertEquals(
            listOf(AgentTaskEvent.TYPE_TOOL_CALL, AgentTaskEvent.TYPE_DONE),
            frames.map { it.event() },
        )
        assertEquals(4L, frames.last().data()!!.seq)
    }

    @Test
    @DisplayName("the step stream answers 404 for an id no runner knows")
    fun stepStreamIs404ForAnUnknownId() {
        whenever(executor.getStatus(DEFAULT_SESSION_ID, "ghost")).thenReturn(null)
        whenever(executor.hasEvents("ghost")).thenReturn(false)

        val response = controller.streamSteps(
            id = "ghost",
            after = 0,
            lastEventId = null,
            limit = 200,
            maxSeconds = 1,
            sessionId = null,
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    @DisplayName("the trace endpoints expose the audit streams, and 404 when nothing is indexed")
    fun traceEndpointsExposeTheAuditStreams() {
        whenever(executor.trace("agent-1")).thenReturn(
            CommandTraceResponse(
                taskId = "agent-1",
                runDir = "/tmp/logs/agent/2026-11-22/aaaa",
                files = listOf(
                    CommandTraceFile("cli-tool-trace.jsonl", bytes = 2_048, modifiedAt = "2026-11-22T00:00:00Z"),
                ),
            )
        )
        whenever(executor.trace("ghost")).thenReturn(null)
        whenever(executor.traceFile("agent-1", "cli-tool-trace.jsonl", 0, 500)).thenReturn(
            CommandTraceFileResponse(
                taskId = "agent-1",
                file = "cli-tool-trace.jsonl",
                offset = 0,
                limit = 500,
                totalLines = 1,
                lines = listOf("""{"seq":1,"tool":"coding.read"}"""),
            )
        )
        whenever(executor.traceFile("agent-1", "../../etc/passwd", 0, 500)).thenReturn(null)

        val trace = assertNotNull(controller.getTrace("agent-1").body)
        assertEquals("agent-1", trace.taskId)
        assertEquals("cli-tool-trace.jsonl", trace.files.single().name)

        val page = assertNotNull(controller.getTraceFile("agent-1", "cli-tool-trace.jsonl", 0, 500).body)
        assertEquals(1, page.totalLines)
        assertEquals("""{"seq":1,"tool":"coding.read"}""", page.lines.single())

        assertEquals(HttpStatus.NOT_FOUND, controller.getTrace("ghost").statusCode)
        assertEquals(
            HttpStatus.NOT_FOUND,
            controller.getTraceFile("agent-1", "../../etc/passwd", 0, 500).statusCode,
            "the trace id is not a file browser",
        )
    }
    @Test
    @DisplayName("the status conversion passes the failure detail and the gauges through")
    fun toCommandStatusCarriesFailureDetailAndGauges() {
        val agent = AgentTaskStatus(
            id = "agent-8",
            statusCode = ResourceStatus.SC_EXPECTATION_FAILED,
            processState = "done",
        )
        agent.failureDetail = mapOf("errorCode" to "TOOL_ERROR", "reason" to "rejected")
        agent.mutations = 4
        agent.readOnlyRounds = 0

        val status = agent.toCommandStatus()

        assertEquals(agent.failureDetail, status.failureDetail)
        assertEquals(4L, status.mutations, "the live gauges must reach the status poll")
        assertEquals(0, status.readOnlyRounds)
        assertEquals(agent.failureDetail, status.commandResult?.failureDetail)
    }

    private companion object {
        const val URL = "https://example.com/product/1"
    }
}
