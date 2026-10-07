package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.rest.api.entities.CommandResult
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.entities.CommandSubmitResponse
import ai.platon.pulsar.rest.api.support.SessionResolution
import ai.platon.pulsar.rest.api.support.toServerSentEvents
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import java.time.Instant

/**
 * The structured command endpoints: page visits driven by a JSON [PageVisitRequest].
 *
 * A page visit is a browser operation, so a malformed request must be rejected
 * *before* a session (and with it a browser) is allocated — see [submitJsonCommand].
 *
 * The session is named by the body, else by the query parameter, else by [DEFAULT_SESSION_ID].
 * A blank value counts as "not named" rather than as a session whose id is the empty string —
 * see [SessionResolution], which every endpoint here resolves through.
 * */
@RestController
@CrossOrigin
@RequestMapping(
    "api/commands",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class CommandController(
    private val commandExecutor: UserCommandExecutor,
) {
    private val logger = getLogger(CommandController::class)

    /**
     * Execute a command with structured JSON input and output.
     *
     * Alias of [submitJsonCommand] — both routes behave identically.
     *
     * @param request The structured command request (see [PageVisitRequest]).
     * @param sessionId Optional session id, used only when the body carries none.
     * @return `200` + [CommandStatus] for a sync request, `202` + [CommandSubmitResponse]
     *         for an async request, `400` + [CommandStatus] for an unusable url.
     * @see submitJsonCommand
     * */
    @PostMapping(value = ["", "/"])
    suspend fun submitCommand(
        @RequestBody request: PageVisitRequest,
        @RequestParam(name = "sessionId", required = false) sessionId: String? = null,
    ): ResponseEntity<Any> {
        return submitJsonCommand(request, sessionId)
    }

    /**
     * Execute a command with structured JSON input and output.
     *
     * This is the primary structured command endpoint. Based on the [PageVisitRequest.async] flag:
     * - **Sync**  ([PageVisitRequest.async] false or absent): runs the page visit, then returns the
     *   [CommandStatus] JSON object, including extracted data, page summary, and fields. The HTTP
     *   status is `200`; the *command's* outcome is the body's `statusCode`/`isDone`, so a client
     *   must read the body to learn whether the visit succeeded.
     * - **Async** ([PageVisitRequest.async] true): schedules the command and returns
     *   `202 Accepted` with a [CommandSubmitResponse] — `{"id": "<task id>"}`, valid JSON under the
     *   declared `application/json` content type. Use [getStatus] to poll with that id, or
     *   [streamEvents] for Server-Sent Events streaming. A page visit runs to completion:
     *   [cancelCommand] cannot stop it, and says so instead of reporting a live task as unknown.
     *
     * A request whose [PageVisitRequest.url] is blank or not a standard http(s) URL is rejected with
     * `400 Bad Request` **before** any session, visitor or browser is allocated; the body is still a
     * [CommandStatus] so JSON clients keep a single response shape. The url is trimmed in place, so a
     * padded url reaches the visitor as the same value that was validated.
     *
     * The session is taken from [PageVisitRequest.sessionId]; when the body carries none, the
     * `sessionId` query parameter is used, falling back to [DEFAULT_SESSION_ID]. A client-supplied
     * [PageVisitRequest.id] is **not** honored — the task id is always assigned by the server and
     * returned in the response.
     *
     * @param request The structured command request (see [PageVisitRequest]).
     * @param sessionId Optional session id, used only when the body carries none.
     * @return `200` + [CommandStatus] for sync, `202` + [CommandSubmitResponse] for async,
     *         `400` + [CommandStatus] for an unusable url.
     * */
    @PostMapping(value = ["/json"])
    suspend fun submitJsonCommand(
        @RequestBody request: PageVisitRequest,
        @RequestParam(name = "sessionId", required = false) sessionId: String? = null,
    ): ResponseEntity<Any> {
        // Validate before resolving the session: ensurePageVisitor() creates a managed
        // session and (on first use) launches a browser, so a bad url must not get that far.
        // The predicate is the one StatefulPageVisitor itself enforces, so nothing that
        // could have been visited before is rejected here — only the timing changes.
        val url = request.url.trim()
        if (!URLUtils.isStandard(url)) {
            logger.warn("Rejecting page visit command with an unusable url: '{}'", request.url)
            return ResponseEntity.badRequest().body(rejectedStatus(request, "Invalid URL: '${request.url}'"))
        }
        request.url = url

        val effectiveSessionId = resolveSessionId(request.sessionId, sessionId)
        val eventHandlers = PageEventHandlersFactory.create()

        return when {
            request.isAsync() -> {
                val id = commandExecutor.submitPageVisitCommand(effectiveSessionId, request, eventHandlers)
                // No log here: submitPageVisitCommand already logs the submission.
                ResponseEntity.accepted()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(CommandSubmitResponse(id))
            }

            else -> ResponseEntity.ok(
                commandExecutor.executePageVisitCommand(effectiveSessionId, request, eventHandlers)
            )
        }
    }

    /**
     * Resolve the session for a structured command.
     *
     * The body wins over the query parameter (the body is the per-request, explicit
     * choice); a client that sends both and disagrees is warned rather than silently
     * overridden, following the [submitPlainCommand] precedent for conflicting parameters.
     *
     * "Both and disagrees" means both actually named a session: a blank value is not a
     * choice, so `?sessionId=s1` with a blank `body.sessionId` is not a conflict to warn
     * about — it is the one session that was named.
     * */
    private fun resolveSessionId(requestSessionId: String?, querySessionId: String?): String {
        val bodyNames = !requestSessionId.isNullOrBlank()
        val queryNames = !querySessionId.isNullOrBlank()

        if (bodyNames && queryNames && requestSessionId != querySessionId) {
            logger.warn(
                "Conflicting session ids: body sessionId='{}' but ?sessionId='{}'. Using the body value.",
                requestSessionId, querySessionId
            )
        }

        return SessionResolution.firstNamedOrDefault(requestSessionId, querySessionId)
    }

    /**
     * A [CommandStatus] shaped rejection for a request that never reached the executor.
     *
     * The HTTP status is `400` and the body keeps the [CommandStatus] shape, so a client
     * that only parses the body learns what one that reads the status line learns.
     * The status is terminal: a rejected command must not look pollable.
     * */
    private fun rejectedStatus(request: PageVisitRequest, message: String): CommandStatus {
        val now = Instant.now()
        return CommandStatus(
            statusCode = ResourceStatus.SC_BAD_REQUEST,
            processState = "completed",
            pageStatusCode = ResourceStatus.SC_BAD_REQUEST,
            message = message,
            request = request,
        ).also {
            it.lastModifiedTime = now
            it.finishTime = now
        }
    }

    /**
     * Execute a command with plain text input.
     *
     * When the command normalizer returns a valid [PageVisitRequest],
     * the command is executed using the standard page visit flow.
     * When it returns null (meaning the command cannot be normalized to a URL-based command),
     * the command is executed using the agent's run method.
     *
     * The response contract is the one [submitJsonCommand] documents: `200` + [CommandStatus]
     * for sync, `202` + [CommandSubmitResponse] for async — a JSON object, never a bare id
     * string under a declared `application/json` content type.
     *
     * @param plainCommand The plain text command (URL, X-SQL, or natural language instruction).
     * @param sessionId Optional session identifier. Defaults to [DEFAULT_SESSION_ID].
     * @param async If true, submits the command and returns its task id in a
     *        [CommandSubmitResponse]. If false/absent, executes synchronously and returns a
     *        [CommandStatus] object.
     * @param mode Deprecated: use [async] instead. Recognized value: "async". Any other value
     *        triggers a warning and falls back to sync execution.
     * @return `200` + [CommandStatus] for sync, `202` + [CommandSubmitResponse] for async.
     * */
    @PostMapping("/plain")
    suspend fun submitPlainCommand(
        @RequestBody plainCommand: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
        @RequestParam(name = "async") async: Boolean? = null,
        @RequestParam(name = "mode") mode: String? = null,
    ): ResponseEntity<Any> {
        fun isAsync(): Boolean {
            val modeIsAsync = mode?.lowercase() == "async"

            // Warn on conflicting async/mode parameters — async takes precedence
            if (async != null && mode != null && async != modeIsAsync) {
                logger.warn("Conflicting parameters: async=$async but mode='$mode'. " +
                    "Using async=$async (mode is deprecated).")
            }

            // Warn on unrecognized mode values (only when async is not set)
            if (async == null && mode != null && !modeIsAsync) {
                logger.warn("Unrecognized mode='$mode'. Mode parameter is deprecated; " +
                    "use 'async' instead. Falling back to sync.")
            }

            return async ?: modeIsAsync
        }

        val effectiveSessionId = SessionResolution.firstNamedOrDefault(sessionId)

        return if (isAsync()) {
            ResponseEntity.accepted()
                .contentType(MediaType.APPLICATION_JSON)
                .body(CommandSubmitResponse(commandExecutor.submitPlainCommand(effectiveSessionId, plainCommand)))
        } else {
            ResponseEntity.ok(commandExecutor.executePlainCommand(effectiveSessionId, plainCommand))
        }
    }

    @GetMapping(value = ["/{id}/status"])
    fun getStatus(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): ResponseEntity<CommandStatus> {
        val effectiveSessionId = SessionResolution.firstNamedOrDefault(sessionId)

        val status = commandExecutor.getStatus(effectiveSessionId, id)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(status)
    }

    @GetMapping(value = ["/{id}/result"])
    fun getResult(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): ResponseEntity<CommandResult> {
        val effectiveSessionId = SessionResolution.firstNamedOrDefault(sessionId)

        val result = commandExecutor.getResult(effectiveSessionId, id)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(result)
    }

    /**
     * Cancel a running/queued agent task by id.
     *
     * Cancelling interrupts the agent loop and marks the task failed with
     * reason "Task cancelled" — see
     * [ai.platon.pulsar.agentic.tools.advanced.agent.StatefulAgentRunner.cancel].
     *
     * A page visit submitted through [submitJsonCommand] with `async=true` is **not**
     * cancellable: the page load already dispatched to the browser cannot be
     * interrupted, so the response says exactly that (`cancelled=false` with the real
     * reason) instead of reporting a live task as unknown. The id alone identifies the
     * task, which is why this endpoint — unlike its siblings — takes no `sessionId`.
     */
    @PostMapping("/{id}/cancel")
    fun cancelCommand(@PathVariable id: String): ResponseEntity<Map<String, Any>> {
        val cancelled = commandExecutor.cancelAgentTask(id)
        val body = when {
            cancelled -> mapOf("taskId" to id, "cancelled" to true)
            commandExecutor.isPageVisitTask(id) -> mapOf(
                "taskId" to id,
                "cancelled" to false,
                "message" to "page visit tasks run to completion and cannot be cancelled",
            )

            else -> mapOf("taskId" to id, "cancelled" to false, "message" to "task not running or unknown")
        }
        return ResponseEntity.ok(body)
    }

    @GetMapping(value = ["/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): Flux<ServerSentEvent<CommandStatus>> {
        val effectiveSessionId = SessionResolution.firstNamedOrDefault(sessionId)

        // The default event shape is this endpoint's contract: JavaScript client-side code
        // expects only JSON data, not the event ID nor the event name.
        return commandExecutor.commandStatusFlow(effectiveSessionId, id)
            .toServerSentEvents(commandExecutor.launchScope(), logger)
    }
}
