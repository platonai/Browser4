package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.advanced.agent.AgentTaskEvent
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.common.ResourceStatus
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.urls.URLUtils
import ai.platon.pulsar.rest.api.entities.CommandEvents
import ai.platon.pulsar.rest.api.entities.CommandEventsResponse
import ai.platon.pulsar.rest.api.entities.CommandInputRequest
import ai.platon.pulsar.rest.api.entities.CommandInputResponse
import ai.platon.pulsar.rest.api.entities.CommandResult
import ai.platon.pulsar.rest.api.entities.CommandStatsResponse
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.entities.CommandSubmitResponse
import ai.platon.pulsar.rest.api.entities.CommandTraceFileResponse
import ai.platon.pulsar.rest.api.entities.CommandTraceResponse
import ai.platon.pulsar.rest.api.entities.statusWord
import ai.platon.pulsar.rest.api.support.SessionResolution
import ai.platon.pulsar.rest.api.support.toServerSentEvents
import ai.platon.pulsar.rest.api.support.toServerSentEventsOf
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux
import java.time.Duration
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
     * The live step log of an agent task — what the task actually did.
     *
     * `GET /api/commands/{id}/status` answers "is it still running"; this answers
     * "what is it doing": one entry per model round, tool call and tool result,
     * each with a monotonic `seq`. Clients follow a task by calling this with
     * `?after=<nextSeq>` and stop when `done` is true — a cursor model instead of
     * SSE, so a follower reconnects naturally and is unaffected by proxies that
     * buffer responses.
     *
     * Events live in memory next to the task status and are bounded (the oldest
     * fall out first): a task restored after a backend restart has a status but
     * no events. `after` beyond the retained tail simply returns an empty page
     * with `nextSeq` unchanged — never an error. A cursor that fell BEHIND the
     * retained window is reported (`resynced`/`dropped`/`firstRetainedSeq`) instead of
     * being indistinguishable from "nothing new" (issue #652).
     *
     * @param id task id (the same id `agent run` prints)
     * @param after cursor: only events with a greater `seq` are returned
     * @param limit page size (clamped to [MAX_EVENTS_PAGE])
     * @param sessionId optional session, used only to find the owning runner
     * @return `200` + [CommandEventsResponse], `404` when the id is unknown
     * */
    @GetMapping("/{id}/events")
    fun getEvents(
        @PathVariable id: String,
        @RequestParam(name = "after", defaultValue = "0") after: Long,
        @RequestParam(name = "limit", defaultValue = "200") limit: Int,
        @RequestParam(name = "sessionId", required = false) sessionId: String? = null,
    ): ResponseEntity<CommandEventsResponse> {
        val effectiveSessionId = SessionResolution.firstNamedOrDefault(sessionId)
        val status = commandExecutor.getStatus(effectiveSessionId, id)
        // `null` (no log on this backend) is distinct from an empty page: only the
        // former can mean "unknown id" together with a missing status row.
        val page = commandExecutor.eventsPage(id, after, limit.coerceIn(1, MAX_EVENTS_PAGE))
        if (status == null && page?.events.isNullOrEmpty()) {
            return ResponseEntity.notFound().build()
        }

        return ResponseEntity.ok(CommandEvents.page(id, status, page, after))
    }

    /**
     * The aggregate run statistics of an agent task: what a run actually cost.
     *
     * Answers "how many LLM calls, how many tool calls, how many tokens" for the
     * interactive session's `/stats` command. The counters are maintained by the
     * task runner's event log as steps are appended, so they stay exact after
     * steps fall out of the bounded buffer (a client cannot reconstruct them by
     * counting events it received).
     *
     * `404` means this backend has no log for the id: the task never existed,
     * belonged to another backend process, or its log's idle TTL expired. The
     * status row may also be gone while the counters survive — `status` is then
     * `unknown`, which is why this response carries its own `status`/`isDone`
     * instead of making the caller fetch the status envelope.
     *
     * @param id task id (the same id `agent run` prints)
     * @param sessionId optional session, used only to find the owning runner
     * @return `200` + [CommandStatsResponse], `404` when no run log exists
     * */
    @GetMapping("/{id}/stats")
    fun getStats(
        @PathVariable id: String,
        @RequestParam(name = "sessionId", required = false) sessionId: String? = null,
    ): ResponseEntity<CommandStatsResponse> {
        val effectiveSessionId = SessionResolution.firstNamedOrDefault(sessionId)
        val stats = commandExecutor.stats(id) ?: return ResponseEntity.notFound().build()
        val status = commandExecutor.getStatus(effectiveSessionId, id)

        // Elapsed time: the status row is the authority once the task started
        // (it survives a log eviction and keeps updating while the run is in
        // flight); the counters' own time span is the fallback when the row is
        // gone but the log is not.
        val now = Instant.now()
        val started = status?.startedTime
            ?: stats.firstEpochMillis?.let { Instant.ofEpochMilli(it) }
        val finished = when {
            status?.isDone == true -> status.finishTime ?: status.lastModifiedTime ?: now
            status != null -> now
            else -> stats.lastEpochMillis?.let { Instant.ofEpochMilli(it) }
        }
        val elapsedMs = if (started != null) {
            Duration.between(started, finished ?: now).toMillis().coerceAtLeast(0)
        } else {
            null
        }

        return ResponseEntity.ok(
            CommandStatsResponse(
                taskId = id,
                status = status?.statusWord() ?: STATUS_UNKNOWN,
                processState = status?.processState ?: STATUS_UNKNOWN,
                isDone = status?.isDone ?: false,
                elapsedMs = elapsedMs,
                stats = stats,
            )
        )
    }

    /**
     * Steer a running agent task: the message is injected at the agent's next
     * model round-trip.
     *
     * This is the transport behind the interactive `agent run -i` session, where
     * a line typed while the task runs must change what the agent does next
     * instead of queueing a second task. `accepted=false` is a normal answer (the
     * task finished while the user was typing) and is reported inline rather
     * than as an HTTP error.
     *
     * @param id task id
     * @param request the steering text
     * @return `200` + [CommandInputResponse]; `400` for a blank text
     * */
    @PostMapping("/{id}/input")
    fun postInput(
        @PathVariable id: String,
        @RequestBody request: CommandInputRequest,
    ): ResponseEntity<CommandInputResponse> {
        val text = request.text.trim()
        if (text.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(CommandInputResponse(id, accepted = false, message = "text must not be blank"))
        }

        val inputId = commandExecutor.sendInput(id, text, request.deliverAs)
        val accepted = inputId != null
        val followUp = request.deliverAs.trim().lowercase().replace("_", "").replace("-", "") == "followup"
        return ResponseEntity.ok(
            CommandInputResponse(
                taskId = id,
                accepted = accepted,
                message = when {
                    !accepted -> "task is not running or does not accept input"
                    followUp -> "queued: the agent continues with it when the current step finishes"
                    else -> "queued: the agent sees it at its next model round"
                },
                // The id the delivered `steer` / `follow_up` step will repeat, so a
                // caller can prove the step it observes is its own message.
                inputId = inputId,
            )
        )
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

    /**
     * The same step stream as [getEvents], pushed instead of polled:
     * `GET /api/commands/{id}/events/stream`.
     *
     * The cursor endpoint requires a client to poll every few hundred milliseconds, which
     * costs one request per observer per tick and makes "was this step already shown"
     * the client's problem. This endpoint is a Server-Sent Events stream of the same
     * [AgentTaskEvent]s, and it speaks the two conventions a follower already has:
     *
     * - **`Last-Event-ID`**: every frame carries `id: <seq>`, so a browser's `EventSource`
     *   reconnect — or any client that stores the last id — resumes exactly where it
     *   stopped (an explicit `?after=` wins when both are present);
     * - **`done`**: the stream completes right after the task's terminal step, so a
     *   supervisor does not have to guess when to stop reading.
     *
     * `?maxSeconds=` bounds the stream for a caller that cannot afford an unbounded
     * connection (a dashboard refresh, a test); `0` (the default) reads until the task ends.
     * A silent run still sends a comment keep-alive, so a proxy does not cut the connection
     * during a three-minute tool call.
     *
     * Backpressure is the reader's: frames are produced on demand (one poll per subscribed
     * connection), and a slow reader simply delays the next poll — it cannot lose a step,
     * because the cursor only advances with a frame that was actually emitted.
     *
     * @param id task id (the same id `agent run` prints)
     * @param after explicit cursor; `null` falls back to `Last-Event-ID`, then to 0
     * @param lastEventId the SSE reconnect header
     * @param limit page size per internal poll (clamped to [MAX_EVENTS_PAGE])
     * @param maxSeconds how long the stream may stay open (`0` = until the task ends)
     * @param sessionId optional session, used only to find the owning runner
     * @return `200` + the event stream, `404` when the id is unknown
     * */
    @GetMapping("/{id}/events/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamSteps(
        @PathVariable id: String,
        @RequestParam(name = "after", required = false) after: Long?,
        @RequestHeader(name = LAST_EVENT_ID_HEADER, required = false) lastEventId: String?,
        @RequestParam(name = "limit", required = false, defaultValue = "200") limit: Int,
        @RequestParam(name = "maxSeconds", required = false, defaultValue = "0") maxSeconds: Long,
        @RequestParam(name = "sessionId", required = false) sessionId: String?,
    ): ResponseEntity<Flux<ServerSentEvent<AgentTaskEvent>>> {
        val effectiveSessionId = SessionResolution.firstNamedOrDefault(sessionId)
        val cursor = after
            ?: lastEventId?.trim()?.toLongOrNull()
            ?: 0L
        if (commandExecutor.getStatus(effectiveSessionId, id) == null && !commandExecutor.hasEvents(id)) {
            return ResponseEntity.notFound().build()
        }
        val pageSize = limit.coerceIn(1, MAX_EVENTS_PAGE)
        val deadline = if (maxSeconds > 0) System.currentTimeMillis() + maxSeconds * 1000 else Long.MAX_VALUE

        val frames = flow {
            var next = cursor
            var lastFrameAt = System.currentTimeMillis()
            while (true) {
                val status = commandExecutor.getStatus(effectiveSessionId, id)
                val page = commandExecutor.eventsPage(id, next, pageSize)
                val events = page?.events.orEmpty()
                for (event in events) {
                    emit(StepFrame(event = event))
                    next = event.seq
                    lastFrameAt = System.currentTimeMillis()
                }
                val response = CommandEvents.page(id, status, page, next)
                // A terminal step is the last thing this task will ever write: complete right
                // after emitting it instead of waiting for the next poll to notice `done`.
                if (events.any { it.type in CommandEvents.TERMINAL_EVENT_TYPES }) return@flow
                if (response.done && events.isEmpty()) return@flow
                if (System.currentTimeMillis() >= deadline) return@flow
                if (events.isEmpty() && System.currentTimeMillis() - lastFrameAt >= KEEPALIVE_INTERVAL_MS) {
                    // A comment frame, not an event: it keeps the connection warm without
                    // asking the client to parse (or ignore) a synthetic step.
                    emit(StepFrame(comment = "keepalive"))
                    lastFrameAt = System.currentTimeMillis()
                }
                delay(STEP_STREAM_POLL_MS)
            }
        }

        return ResponseEntity.ok(
            frames.toServerSentEventsOf(commandExecutor.launchScope(), logger) { frame ->
                val event = frame.event
                if (event != null) {
                    ServerSentEvent.builder(event)
                        .id(event.seq.toString())
                        .event(event.type)
                        .build()
                } else {
                    ServerSentEvent.builder<AgentTaskEvent>()
                        .comment(frame.comment ?: "keepalive")
                        .build()
                }
            }
        )
    }

    /**
     * The stream of [CommandController.streamSteps] carries one of two things.
     *
     * A data frame ([event]) is a real step; a comment frame ([comment]) is the keep-alive.
     * The pair exists because Server-Sent Events express keep-alives as comments, and the
     * shared flow-to-SSE helper needs a non-null payload to carry one.
     *
     * @property event the step to send, or `null` for a comment frame
     * @property comment the comment text of a keep-alive frame
     */
    data class StepFrame(
        val event: AgentTaskEvent? = null,
        val comment: String? = null,
    )

    /**
     * The full run trace of a task: `GET /api/commands/{id}/trace`.
     *
     * The step log ([getEvents]) is what the run did, in order, capped and previewed for a live
     * view. The trace is the audit trail behind it: the exact prompt the model saw, one line per
     * executed tool with its complete arguments and complete output, the provider's usage per
     * request, the page timeline. It lives on disk under `logs/agent/<time>/<uuid>/`, a path
     * keyed by the agent's own uuid — which is why this endpoint exists at all: a reviewer
     * holding the task id had no way to resolve it (issue #652, gap 5).
     *
     * Only the `*.jsonl` audit streams are listed. The raw prompt dumps stay local: they carry
     * the whole conversation, whose credentials are redacted at the tool layer but not per message
     * yet, so serving them would move an unredacted copy over HTTP.
     *
     * @param id task id (the same id `agent run` prints)
     * @return `200` + [CommandTraceResponse], `404` when the task has no indexed trace (never ran
     *   here, tracing was off, or the retention policy already removed it)
     * */
    @GetMapping("/{id}/trace")
    fun getTrace(@PathVariable id: String): ResponseEntity<CommandTraceResponse> {
        val trace = commandExecutor.trace(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(trace)
    }

    /**
     * One page of one trace stream: `GET /api/commands/{id}/trace/{file}?offset=&limit=`.
     *
     * Paged by line because a long run's tool stream is megabytes of JSONL. Every line is
     * redacted on the way out: the current build redacts when it writes, and this also covers a
     * trace written by an older build in the same data dir.
     *
     * @param id task id
     * @param file stream name (`cli-tool-trace.jsonl`, `cli-usage.jsonl`, …); any other name is
     *   rejected — the trace directory is not a file browser
     * @param offset first line to return (0-based)
     * @param limit page size (clamped to 2000)
     * @return `200` + [CommandTraceFileResponse], `404` when the task or the stream is unknown
     * */
    @GetMapping("/{id}/trace/{file}")
    fun getTraceFile(
        @PathVariable id: String,
        @PathVariable file: String,
        @RequestParam(name = "offset", defaultValue = "0") offset: Int,
        @RequestParam(name = "limit", defaultValue = "500") limit: Int,
    ): ResponseEntity<CommandTraceFileResponse> {
        val page = commandExecutor.traceFile(id, file, offset, limit)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(page)
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

    private companion object {
        /** Upper bound of one `events` page (`?limit=`). */
        const val MAX_EVENTS_PAGE = 1000

        /** Status word when the id has no resolvable status row (`/stats`). */
        const val STATUS_UNKNOWN = "unknown"

        /** The SSE reconnect header a browser's `EventSource` sends (also a request header here). */
        const val LAST_EVENT_ID_HEADER = "Last-Event-ID"

        /** How often the step stream looks for new steps. */
        const val STEP_STREAM_POLL_MS = 250L

        /** Silence after which the step stream sends a comment keep-alive. */
        const val KEEPALIVE_INTERVAL_MS = 15_000L
    }
}
