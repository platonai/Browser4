package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.common.AgentShell
import ai.platon.pulsar.agentic.model.ToolSpec
import kotlin.reflect.KClass

/**
 * Tool executor for shell command operations.
 *
 * Provides agent tools for executing shell commands (bash/powershell),
 * reading command output, querying command status, and listing sessions.
 *
 * Domain: `shell`
 *
 * ## Supported Methods:
 * - `execute(command, timeoutSeconds?, workingDir?)` — Execute a shell command
 * - `readOutput(sessionId)` — Read the output of a previous command
 * - `getStatus(sessionId)` — Get the status of a previous command
 * - `listSessions()` — List all tracked command sessions
 *
 * ## Usage Example:
 *
 * ```kotlin
 * // The shell tool is registered as a built-in tool in AgentToolManager.
 * // Agents can call it via:
 * // shell.execute(command="echo hello", timeoutSeconds=10)
 * // shell.readOutput(sessionId="shell-1")
 * // shell.getStatus(sessionId="shell-1")
 * // shell.listSessions()
 * ```
 *
 * @author Browser4 Team
 */
class ShellToolExecutor : AbstractToolExecutor() {

    override val domain = "shell"

    override val receiverClass: KClass<*> = AgentShell::class

    init {
        toolSpec["execute"] = ToolSpec(
            domain = domain,
            method = "execute",
            arguments = listOf(
                ToolSpec.Arg("command", "String", null),
                ToolSpec.Arg("timeoutSeconds", "Long", "30"),
                ToolSpec.Arg("workingDir", "String", "null"),
            ),
            returnType = "String",
            description = "Execute a shell command (bash on Linux/macOS, cmd on Windows) with optional timeout and " +
                    "working directory. Allowed commands: " + AgentShell.ALLOWED_COMMANDS
        )

        toolSpec["readOutput"] = ToolSpec(
            domain = domain,
            method = "readOutput",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
            ),
            returnType = "String",
            description = "Read the output of a previously executed shell command by session ID"
        )

        toolSpec["getStatus"] = ToolSpec(
            domain = domain,
            method = "getStatus",
            arguments = listOf(
                ToolSpec.Arg("sessionId", "String", null),
            ),
            returnType = "String",
            description = "Get the execution status of a previously executed shell command by session ID"
        )

        toolSpec["listSessions"] = ToolSpec(
            domain = domain,
            method = "listSessions",
            arguments = emptyList(),
            returnType = "String",
            description = "List all tracked shell command sessions with their status"
        )
    }

    /**
     * Execute shell.* expressions against an AgentShell target using named args.
     */
    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }
        require(receiver is AgentShell) { "Target must be an AgentShell" }

        val shell = receiver

        return when (functionName) {
            // shell.execute(command: String, timeoutSeconds?: Long, workingDir?: String)
            "execute" -> {
                validateArgs(
                    args,
                    allowed = setOf("command", "timeoutSeconds", "workingDir"),
                    required = setOf("command"),
                    functionName
                )
                shell.execute(
                    command = paramString(args, "command", functionName)!!,
                    timeoutSeconds = paramLong(args, "timeoutSeconds", functionName, required = false, default = 30L) ?: 30L,
                    workingDir = paramString(args, "workingDir", functionName, required = false, default = null),
                )
            }
            // shell.readOutput(sessionId: String)
            "readOutput" -> {
                validateArgs(args, allowed = setOf("sessionId"), required = setOf("sessionId"), functionName)
                shell.readOutput(
                    sessionId = paramString(args, "sessionId", functionName)!!,
                )
            }
            // shell.getStatus(sessionId: String)
            "getStatus" -> {
                validateArgs(args, allowed = setOf("sessionId"), required = setOf("sessionId"), functionName)
                shell.getStatus(
                    sessionId = paramString(args, "sessionId", functionName)!!,
                )
            }
            // shell.listSessions()
            "listSessions" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                shell.listSessions()
            }
            else -> throw IllegalArgumentException("Unsupported shell method: $functionName(${args.keys})")
        }
    }
}
