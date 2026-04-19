package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.agentic.tools.high.command.CommandResult
import ai.platon.pulsar.agentic.tools.high.command.CommandService
import ai.platon.pulsar.agentic.tools.high.command.CommandStatus
import ai.platon.pulsar.agentic.tools.high.crawl.PageVisitRequest
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.crawl.event.impl.PageEventHandlersFactory
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
    val commandService: CommandService,
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
        val eventHandlers = PageEventHandlersFactory.create()
        val response = when {
            request.isAsync() -> commandService.submitPageVisitCommandAsync(request, eventHandlers)
            else -> commandService.executePageVisitCommandSync(request, eventHandlers)
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

        val response = if (isAsync()) {
            commandService.submitPlainCommandAsync(plainCommand)
        } else {
            commandService.executePlainCommandSync(plainCommand)
        }

        return ResponseEntity.ok(response)
    }

    @GetMapping(value = ["/{id}/status"])
    fun getStatus(@PathVariable id: String): ResponseEntity<CommandStatus> {
        return ResponseEntity.ok(commandService.getStatus(id))
    }

    @GetMapping(value = ["/{id}/result"])
    fun getResult(@PathVariable id: String): ResponseEntity<CommandResult> {
        return ResponseEntity.ok(commandService.getResult(id))
    }

    @GetMapping(value = ["/{id}/stream"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamEvents(@PathVariable id: String): Flux<ServerSentEvent<CommandStatus>> {
        return Flux.create<CommandStatus> { sink ->
            val job = commandService.commandStatusFlow(id)
                .onEach { sink.next(it) }
                .onCompletion { sink.complete() }
                .catch {
                    logger.error("Error in command status flow", it)
                    sink.error(it)
                }
                .launchIn(commandService.launchScope())

            sink.onDispose { job.cancel() }
        }.map {
            // NOTE: [2025/5/20] JavaScript client-side code expects only JSON data, not the event ID nor event name.
            ServerSentEvent.builder(it).build()
        }
    }
}
