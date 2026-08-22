package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.cli.CliBinaryResolver
import ai.platon.pulsar.agentic.cli.CliProcessManager
import ai.platon.pulsar.agentic.cli.CliRunRequest
import java.nio.file.Path
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
    private val backendBaseUrl: String? = null,
    private val cliProcessManager: CliProcessManager = CliProcessManager(CliBinaryResolver()),
) : AbstractToolExecutor() {

    companion object {
        /**
         * Subcommands that would spawn a nested agent, leading to unbounded
         * recursion (agent → cli → subprocess → backend → new agent → …).
         * Rejected before any subprocess is launched.
         */
        private val AGENT_SPAWN_PATTERN = Regex(
            """^\s*(agent\s+run|agent-run|act)\s""",
            RegexOption.IGNORE_CASE,
        )
    }

    override val domain = "cli"

    override val receiverClass: KClass<*> = CodingAgentShell::class

    init {
        toolSpec["run"] = ToolSpec(
            domain = domain, method = "run",
            arguments = listOf(
                ToolSpec.Arg("args", "String"),
                ToolSpec.Arg("timeoutSeconds", "Long", "300"),
                ToolSpec.Arg("workingDir", "String", "null"),
            ),
            returnType = "String",
            description = "Execute browser4-cli with the given arguments. " +
                "Example: cli.run(args=\"tab navigate --url https://example.com\"). " +
                "The binary is resolved automatically (bundled / PATH / auto-install). " +
                "Default timeout 300s (max 600s); pass timeoutSeconds for long commands. " +
                "NOTE: 'agent run', 'agent-run', and 'act' subcommands are blocked " +
                "to prevent nested agent spawning."
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
                requireAgentNotSpawned(cliArgs)
                val timeout = paramLong(args, "timeoutSeconds", functionName, required = false, default = 300L) ?: 300L
                val workingDir = paramString(args, "workingDir", functionName, required = false, default = null)
                cliProcessManager.run(
                    CliRunRequest(
                        args = cliArgs,
                        timeoutSeconds = timeout,
                        workingDir = workingDir?.let { Path.of(it) },
                    ),
                    backendBaseUrl = backendBaseUrl,
                ).toModelText()
            }
            "version" -> {
                validateArgs(args, allowed = emptySet(), required = emptySet(), functionName)
                cliProcessManager.run(
                    CliRunRequest(args = "--version", timeoutSeconds = 10),
                    backendBaseUrl = backendBaseUrl,
                ).toModelText()
            }
            "help" -> {
                validateArgs(args, allowed = setOf("command"), required = emptySet(), functionName)
                val command = paramString(args, "command", functionName, required = false)
                val helpArgs = if (command != null) "$command --help" else "--help"
                cliProcessManager.run(
                    CliRunRequest(args = helpArgs, timeoutSeconds = 10),
                    backendBaseUrl = backendBaseUrl,
                ).toModelText()
            }
            else -> throw IllegalArgumentException("Unsupported cli method: $functionName(${args.keys})")
        }
    }

    /**
     * Reject CLI invocations that would spawn a nested agent.
     *
     * Without this guard the chain
     *   agent → cli.run("agent run …") → subprocess → backend → new agent → …
     * has no depth limit and leads to unbounded recursion, OS-subprocess
     * exhaustion, and multiplied LLM spend.
     */
    private fun requireAgentNotSpawned(cliArgs: String) {
        require(!AGENT_SPAWN_PATTERN.containsMatchIn(cliArgs)) {
            "Nested agent spawning via '${cliArgs.trim().split(" ").first()}' is blocked. " +
                "Use coding.*, tab.*, or fs.* tools directly instead of spawning " +
                "a subprocess agent, which would lead to unbounded recursion."
        }
    }
}
