package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.high.command.CommandService
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import kotlin.reflect.KClass

/**
 * Tool executor that exposes [ai.platon.pulsar.agentic.tools.high.command.CommandService] methods as agent tools.
 *
 * Domain: `command`
 *
 * ## Supported Methods:
 * - `run(command, async?)` — Execute a plain command. Returns a task ID (async) or a [ai.platon.pulsar.agentic.tools.high.command.CommandStatus] JSON (sync).
 * - `status(id)` — Get the status of a running command task.
 * - `result(id)` — Get the result of a completed command task.
 *
 * ## Usage Example:
 *
 * ```kotlin
 * // command.run(command="https://example.com -expires 1d -parse", async=true)
 * // command.status(id="<task-id>")
 * // command.result(id="<task-id>")
 * ```
 *
 * @see ai.platon.pulsar.agentic.tools.high.command.CommandService
 */
class CommandToolExecutor : AbstractToolExecutor() {

    override val domain: String = "command"

    override val receiverClass: KClass<*> = CommandService::class

    init {
        toolSpec["run"] = ToolSpec(
            domain = domain,
            method = "run",
            arguments = listOf(
                ToolSpec.Arg("command", "String", null),
                ToolSpec.Arg("async", "Boolean", "true"),
            ),
            returnType = "String",
            description = "Execute a plain command (URL, instruction, or agent task). " +
                    "When async=true (default), returns a task ID immediately. " +
                    "When async=false, blocks until done and returns the CommandStatus as JSON."
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null),
            ),
            returnType = "CommandStatus",
            description = "Get the status of a previously submitted command task by its task ID."
        )

        toolSpec["result"] = ToolSpec(
            domain = domain,
            method = "result",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null),
            ),
            returnType = "CommandResult",
            description = "Get the result of a completed command task by its task ID."
        )
    }

    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(receiver is CommandService) { "Receiver must be a CommandService" }

        val service = receiver

        return when (functionName) {
            // command.run(command: String, async?: Boolean = true)
            "run" -> {
                validateArgs(
                    args,
                    allowed = setOf("command", "async"),
                    required = setOf("command"),
                    functionName
                )
                val command = paramString(args, "command", functionName)!!
                val isAsync = paramBool(args, "async", functionName, required = false, default = true) ?: true
                if (isAsync) {
                    service.submitPlainCommandAsync(command)
                } else {
                    val status = service.executePlainCommandSync(command)
                    pulsarObjectMapper().writeValueAsString(status)
                }
            }

            // command.status(id: String)
            "status" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val status = service.getStatus(id)
                pulsarObjectMapper().writeValueAsString(status)
            }

            // command.result(id: String)
            "result" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val result = service.getResult(id)
                pulsarObjectMapper().writeValueAsString(result)
            }

            else -> throw IllegalArgumentException("Unsupported command method: $functionName(${args.keys})")
        }
    }
}
