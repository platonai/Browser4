package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.agents.RunEngine
import ai.platon.pulsar.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolExample
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
                ToolSpec.Arg(
                    "command", "String", null,
                    "What to run: a URL to load, a natural-language instruction, or an agent task. " +
                        "Required.",
                ),
                ToolSpec.Arg(
                    "async", "Boolean", "true",
                    "`true` (default) returns a task id immediately; `false` blocks until the command " +
                        "finishes and returns the CommandStatus JSON.",
                ),
                ToolSpec.Arg(
                    "noopLimit", "Int", null,
                    "Consecutive no-op abort threshold for agent tasks; omit to use the server default.",
                ),
                ToolSpec.Arg(
                    "engine", "String", null,
                    "Agent engine: `cli` (default) for the tool-loop engine, `observe-act` is deprecated.",
                ),
            ),
            returnType = "String",
            description = "Execute a plain command (URL, instruction, or agent task). " +
                    "When async=true (default), returns a task ID immediately. " +
                    "When async=false, blocks until done and returns the CommandStatus as JSON. " +
                    "noopLimit optionally overrides the consecutive no-op abort threshold for agent tasks. " +
                    "engine optionally selects the agent execution engine: 'cli' (default) for the CLI tool-loop engine; " +
                    "'observe-act' is the DEPRECATED legacy engine.",
            examples = listOf(
                ToolExample(
                    title = "Load a page asynchronously",
                    args = mapOf("command" to "https://example.com"),
                    notes = "Returns a task id; poll it with command.status",
                ),
                ToolExample(
                    title = "Run an agent task and wait",
                    args = mapOf("command" to "collect the page title", "async" to "false"),
                ),
            ),
            task = TaskPolicy(statusTool = "command_status", resultTool = "command_result"),
            outputSchema = TASK_ENVELOPE_SCHEMA,
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `command.run`. Required."),
            ),
            returnType = "CommandStatus",
            description = "Get the status of a previously submitted command task by its task ID.",
            examples = listOf(
                ToolExample(
                    title = "Poll a running command",
                    args = mapOf("id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01"),
                ),
            ),
        )

        toolSpec["result"] = ToolSpec(
            domain = domain,
            method = "result",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `command.run`. Required."),
            ),
            returnType = "CommandResult",
            description = "Get the result of a completed command task by its task ID.",
            examples = listOf(
                ToolExample(
                    title = "Read the finished command's output",
                    args = mapOf("id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01"),
                ),
            ),
        )
    }

    private companion object {
        /**
         * The shared task envelope every submit-style tool returns, exposed as MCP
         * `structuredContent` so a generic client can poll without knowing the
         * domain's status tool.
         */
        const val TASK_ENVELOPE_SCHEMA = """
            {
              "type": "object",
              "required": ["taskId", "status", "statusTool"],
              "properties": {
                "taskId": {"type": "string"},
                "status": {"type": "string", "enum": ["running", "done", "failed"]},
                "pollAfterMs": {"type": "integer"},
                "statusTool": {"type": "string"},
                "resultTool": {"type": "string"}
              }
            }
        """
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
                // null (param absent) must stay null so the agent falls back to
                // its configured runEngine; RunEngine.parse(null) would force
                // OBSERVE_ACT and clobber a CLI_TOOL_LOOP config.
                val engine = paramString(args, "engine", functionName, required = false)
                    ?.let { RunEngine.parse(it) }
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
