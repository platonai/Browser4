package ai.platon.pulsar.rest.api.controller

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agentic.tools.advanced.crawl.PageVisitRequest
import ai.platon.pulsar.common.SessionManager
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.rest.api.entities.CommandResult
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.agent.tool.UserCommandExecutor
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
    val sessionManager: SessionManager,
    val commandExecutor: UserCommandExecutor,
) {
    private val logger = getLogger(CommandController::class)

    /**
     * Execute a command with structured JSON input and output.
     *
     * @param request The structured command request
     * @return Structured command response
     * */
    @PostMapping(value = ["", "/"])
    suspend fun submitCommand(@RequestBody request: PageVisitRequest): ResponseEntity<Any> {
        val sessionId = request.sessionId ?: DEFAULT_SESSION_ID

        val eventHandlers = PageEventHandlersFactory.create()
        val response = when {
            request.isAsync() -> commandExecutor.submitPageVisitCommand(sessionId, request, eventHandlers)
            else -> commandExecutor.executePageVisitCommand(sessionId, request, eventHandlers)
        }

        return ResponseEntity.ok(response)
    }

    /**
     * Execute a command with plain text input and output.
     *
     * When the command normalizer returns a valid PageVisitRequest,
     * the command is executed using the standard command execution flow.
     * When it returns null (meaning the command cannot be normalized to a URL-based command),
     * the command is executed using the agent's run method.
     *
     * @param plainCommand The plain text command
     * @param async Whether to execute the command asynchronously
     * @param mode The execution mode, e.g., "sync" or "async". (Deprecated: use [async] instead)
     * @return Command response (CommandStatus for sync execution, status ID string for async execution)
     * */
    @PostMapping("/plain")
    suspend fun submitPlainCommand(
        @RequestBody plainCommand: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
        @RequestParam(name = "async") async: Boolean? = null,
        @RequestParam(name = "mode") mode: String? = null,
    ): ResponseEntity<Any> {
        fun isAsync(): Boolean {
            return when {
                async == true -> true
                mode?.lowercase() == "async" -> true
                else -> false
            }
        }

        val sessionId = sessionId ?: DEFAULT_SESSION_ID

        val response = if (isAsync()) {
            commandExecutor.submitPlainCommand(sessionId, plainCommand)
        } else {
            commandExecutor.executePlainCommand(sessionId, plainCommand)
        }

        return ResponseEntity.ok(response)
    }

    @GetMapping(value = ["/{id}/status"])
    fun getStatus(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): ResponseEntity<CommandStatus> {
        val sessionId = sessionId ?: DEFAULT_SESSION_ID

        return ResponseEntity.ok(commandExecutor.getStatus(sessionId, id))
    }

    @GetMapping(value = ["/{id}/result"])
    fun getResult(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): ResponseEntity<CommandResult> {
        val sessionId = sessionId ?: DEFAULT_SESSION_ID

        return ResponseEntity.ok(commandExecutor.getResult(sessionId, id))
    }

    @GetMapping(value = ["/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(
        @PathVariable id: String,
        @RequestParam(name = "sessionId") sessionId: String? = null,
    ): Flux<ServerSentEvent<CommandStatus>> {
        val sessionId = sessionId ?: DEFAULT_SESSION_ID

        return Flux.create { sink ->
            val job = commandExecutor.commandStatusFlow(sessionId, id)
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
