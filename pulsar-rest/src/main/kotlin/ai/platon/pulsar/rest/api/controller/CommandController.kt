package ai.platon.pulsar.rest.api.controller

import ai.platon.pulsar.rest.api.entities.CommandRequest
import ai.platon.pulsar.rest.api.entities.CommandResult
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.service.CommandService
import ai.platon.pulsar.skeleton.crawl.event.impl.PageEventHandlersFactory
import kotlinx.coroutines.runBlocking
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
    /**
     * Execute a command with structured JSON input and output.
     *
     * @param request The structured command request
     * @return Structured command response
     * */
    @PostMapping(value = ["", "/"])
    fun submitCommand(@RequestBody request: CommandRequest): ResponseEntity<Any> {
        val eventHandlers = PageEventHandlersFactory.create()
        val response = when {
            request.isAsync() -> commandService.submitAsync(request, eventHandlers)
            else -> runBlocking { commandService.executeSync(request, eventHandlers) }
        }

        return ResponseEntity.ok(response)
    }

    /**
     * Execute a command with plain text input and output.
     *
     * When `conversationService.normalizePlainCommand(plainCommand)` returns a valid CommandRequest,
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
    fun submitPlainCommand(
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
            runBlocking { commandService.submitPlainCommandAsync(plainCommand) }
        } else {
            runBlocking { commandService.executePlainCommandSync(plainCommand) }
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
        return commandService.streamEvents(id)
    }
}
