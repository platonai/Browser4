package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agent.tool.UserCommandExecutor
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.rest.session.PulsarSessionManager
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.rest.api.entities.CommandResult
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.skeleton.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Flux

@RestController
@CrossOrigin
@RequestMapping(
    "api/commands",
    consumes = [MediaType.ALL_VALUE],
    produces = [MediaType.APPLICATION_JSON_VALUE]
)
class CommandController(
    val sessionManager: PulsarSessionManager,
    val commandExecutor: UserCommandExecutor,
) {
    private val logger = getLogger(CommandController::class)

    /**
     * Execute a command with structured JSON input and output.
     *
     * Delegates to [submitJsonCommand].
     *
     * @param request The structured command request (see [PageVisitRequest]).
     * @return For sync requests ([PageVisitRequest.async] is false/absent):
     *         a [CommandStatus] JSON object with the full execution result.
     *         For async requests ([PageVisitRequest.async] is true):
     *         a JSON string containing the command ID for status polling.
     * @see submitJsonCommand
     * */
    @PostMapping(value = ["", "/"])
    suspend fun submitCommand(@RequestBody request: PageVisitRequest): ResponseEntity<Any> {
        return submitJsonCommand(request)
    }

    /**
     * Execute a command with structured JSON input and output.
     *
     * This is the primary structured command endpoint. Based on the [PageVisitRequest.async] flag:
     * - **Sync**  ([async]=false or absent): executes the page visit and returns the full
     *   [CommandStatus] object, including extracted data, page summary, and fields.
     * - **Async** ([async]=true): submits the command for background execution and returns
     *   a plain-text command ID string. Use [getStatus] to poll for progress or
     *   [streamEvents] for Server-Sent Events streaming.
     *
     * @param request The structured command request (see [PageVisitRequest]).
     * @return [CommandStatus] for sync, command ID string for async.
     * */
    @PostMapping(value = ["/json"])
    suspend fun submitJsonCommand(@RequestBody request: PageVisitRequest): ResponseEntity<Any> {
        val effectiveSessionId = request.sessionId ?: DEFAULT_SESSION_ID

        val eventHandlers = PageEventHandlersFactory.create()
        val response = when {
            request.isAsync() -> commandExecutor.submitPageVisitCommand(effectiveSessionId, request, eventHandlers)
            else -> commandExecutor.executePageVisitCommand(effectiveSessionId, request, eventHandlers)
        }

        return ResponseEntity.ok(response)
    }

    /**
     * Execute a command with plain text input.
     *
     * When the command normalizer returns a valid [PageVisitRequest],
     * the command is executed using the standard page visit flow.
     * When it returns null (meaning the command cannot be normalized to a URL-based command),
     * the command is executed using the agent's run method.
     *
     * @param plainCommand The plain text command (URL, X-SQL, or natural language instruction).
     * @param sessionId Optional session identifier. Defaults to [DEFAULT_SESSION_ID].
     * @param async If true, executes the command asynchronously and returns a command ID string.
     *        If false/absent, executes synchronously and returns a [CommandStatus] object.
     * @param mode Deprecated: use [async] instead. Recognized value: "async". Any other value
     *        triggers a warning and falls back to sync execution.
     * @return [CommandStatus] for sync, command ID string for async.
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

        val effectiveSessionId = sessionId ?: DEFAULT_SESSION_ID

        val response = if (isAsync()) {
            commandExecutor.submitPlainCommand(effectiveSessionId, plainCommand)
        } else {
            commandExecutor.executePlainCommand(effectiveSessionId, plainCommand)
        }

        return ResponseEntity.ok(response)
    }

    @GetMapping(value = ["/{id}/status"])
    fun getStatus(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): ResponseEntity<CommandStatus> {
        val effectiveSessionId = sessionId ?: DEFAULT_SESSION_ID

        val status = commandExecutor.getStatus(effectiveSessionId, id)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(status)
    }

    @GetMapping(value = ["/{id}/result"])
    fun getResult(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): ResponseEntity<CommandResult> {
        val effectiveSessionId = sessionId ?: DEFAULT_SESSION_ID

        val result = commandExecutor.getResult(effectiveSessionId, id)
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(result)
    }

    @GetMapping(value = ["/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): Flux<ServerSentEvent<CommandStatus>> {
        val effectiveSessionId = sessionId ?: DEFAULT_SESSION_ID

        return Flux.create { sink ->
            val job = commandExecutor.commandStatusFlow(effectiveSessionId, id)
                .onEach { sink.next(it) }
                .onCompletion { sink.complete() }
                .catch {
                    logger.error("Error in command status flow", it)
                    sink.error(it)
                }
                .launchIn(commandExecutor.launchScope())

            sink.onDispose { job.cancel() }
        }.map {
            // NOTE: [2025/5/20] JavaScript client-side code expects only JSON data, not the event ID nor event name.
            ServerSentEvent.builder(it).build()
        }
    }
}
