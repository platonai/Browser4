package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.agents.RunEngine
import ai.platon.pulsar.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.entities.CommandStatus
import kotlin.reflect.KClass

/**
 * Tool executor that exposes [ai.platon.pulsar.agentic.tools.advanced.CommandRunner] methods as agent tools.
 *
 * Domain: `command`
 *
 * ## Supported Methods:
 * - `run(command, async?)` — Execute a plain command. Returns a task ID (async) or a [ai.platon.pulsar.agentic.tools.advanced.command.CommandStatus] JSON (sync).
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
 * @see UserCommandExecutor
 */
class CommandToolExecutor(
    private val commandRunner: UserCommandExecutor? = null,
) : AbstractToolExecutor() {

    override val domain: String = "command"

    override val receiverClass: KClass<*> = UserCommandExecutor::class

    init {
        toolSpec["run"] = ToolSpec(
            domain = domain,
            method = "run",
            arguments = listOf(
                ToolSpec.Arg("command", "String", null),
                ToolSpec.Arg("async", "Boolean", "true"),
                ToolSpec.Arg("noopLimit", "Int", null),
                ToolSpec.Arg("engine", "String", null),
            ),
            returnType = "String",
            description = "Execute a plain command (URL, instruction, or agent task). " +
                    "When async=true (default), returns a task ID immediately. " +
                    "When async=false, blocks until done and returns the CommandStatus as JSON. " +
                    "noopLimit optionally overrides the consecutive no-op abort threshold for agent tasks. " +
                    "engine optionally selects the agent execution engine: 'cli' for the CLI tool-loop engine " +
                    "(default: observe-act)."
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
        val service = when {
            receiver is UserCommandExecutor -> receiver
            commandRunner != null -> commandRunner
            else -> throw IllegalArgumentException("Receiver must be a CommandRunner")
        }

        return when (functionName) {
            // command.run(command: String, async?: Boolean = true, noopLimit?: Int = null, engine?: String = null)
            "run" -> {
                validateArgs(
                    args,
                    allowed = setOf("command", "async", "noopLimit", "engine"),
                    required = setOf("command"),
                    functionName
                )
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val command = paramString(args, "command", functionName)!!
                val isAsync = paramBool(args, "async", functionName, required = false, default = true) ?: true
                val noopLimit = paramInt(args, "noopLimit", functionName, required = false, default = null)
                val engine = RunEngine.parse(paramString(args, "engine", functionName, required = false))
                if (isAsync) {
                    service.submitPlainCommand(sessionId, command, noopLimit, engine)
                } else {
                    val status = service.executePlainCommand(sessionId, command, noopLimit, engine)
                    pulsarObjectMapper().writeValueAsString(status)
                }
            }

            // command.status(id: String)
            "status" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val id = paramString(args, "id", functionName)!!
                // Serializing a null status used to emit the literal "null" and
                // the CLI then overwrote its cached terminal statuses with
                // "queued" (P2.5) — return a structured notFound status instead.
                val status = service.getStatus(sessionId, id) ?: CommandStatus.notFound(id)
                pulsarObjectMapper().writeValueAsString(status)
            }

            // command.result(id: String)
            "result" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val id = paramString(args, "id", functionName)!!
                val result = service.getResult(sessionId, id)
                pulsarObjectMapper().writeValueAsString(result)
            }

            else -> throw IllegalArgumentException("Unsupported command method: $functionName(${args.keys})")
        }
    }
}
