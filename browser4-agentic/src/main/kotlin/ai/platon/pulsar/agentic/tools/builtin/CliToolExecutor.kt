package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.common.CodingAgentShell
import ai.platon.pulsar.agentic.model.ToolSpec
import kotlin.reflect.KClass

/**
 * Tool executor for invoking the browser4-cli from within an agent session.
 *
 * Domain: `cli`
 *
 * Allows an agent to invoke `browser4-cli` as a subprocess, enabling
 * composition of CLI commands within agent workflows. Results are captured
 * and returned as structured text.
 *
 * ## Supported Methods
 * - `run(args...)` — Execute browser4-cli with given arguments
 * - `version()` — Get the CLI version
 * - `help(command?)` — Get CLI help
 *
 * ## Usage
 *
 * ```kotlin
 * // Executed via AgentToolManager when domain is "cli"
 * cli.run(args = "tab navigate --url https://example.com")
 * cli.run(args = "tab screenshot --selector '#main'")
 * ```
 */
class CliToolExecutor(
    private val cliPath: String = "browser4-cli",
) : AbstractToolExecutor() {

    override val domain = "cli"

    override val receiverClass: KClass<*> = CodingAgentShell::class

    init {
        toolSpec["run"] = ToolSpec(
            domain = domain, method = "run",
            arguments = listOf(
                ToolSpec.Arg("args", "String"),
                ToolSpec.Arg("timeoutSeconds", "Long", "120"),
                ToolSpec.Arg("workingDir", "String", "null"),
            ),
            returnType = "String",
            description = "Execute browser4-cli with the given arguments. " +
                "Example: coding.cli(args=\"tab navigate --url https://example.com\"). " +
                "The CLI must be installed and on PATH."
        )
        toolSpec["version"] = ToolSpec(
            domain = domain, method = "version",
            arguments = emptyList(),
            returnType = "String",
            description = "Get the installed browser4-cli version"
        )
        toolSpec["help"] = ToolSpec(
            domain = domain, method = "help",
            arguments = listOf(ToolSpec.Arg("command", "String", "null")),
            returnType = "String",
            description = "Get help for browser4-cli or a specific command"
        )
    }

    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(receiver is CodingAgentShell) { "Target must be a CodingAgentShell" }

        val shell = receiver

        return when (functionName) {
            "run" -> {
                validateArgs(args, allowed = setOf("args", "timeoutSeconds", "workingDir"), required = setOf("args"), functionName)
                val cliArgs = paramString(args, "args", functionName)!!
                val timeout = paramLong(args, "timeoutSeconds", functionName, required = false, default = 120L) ?: 120L
                val workingDir = paramString(args, "workingDir", functionName, required = false, default = null)
                shell.execute("$cliPath $cliArgs", timeoutSeconds = timeout, workingDir = workingDir)
            }
            "version" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                shell.execute("$cliPath --version", timeoutSeconds = 10)
            }
            "help" -> {
                validateArgs(args, allowed = setOf("command"), required = emptySet(), functionName)
                val command = paramString(args, "command", functionName, required = false)
                val helpArgs = if (command != null) "$command --help" else "--help"
                shell.execute("$cliPath $helpArgs", timeoutSeconds = 10)
            }
            else -> throw IllegalArgumentException("Unsupported cli method: $functionName(${args.keys})")
        }
    }
}
