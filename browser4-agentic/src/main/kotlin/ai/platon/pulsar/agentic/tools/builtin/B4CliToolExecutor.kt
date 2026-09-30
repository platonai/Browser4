package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.cli.CliBinaryResolver
import ai.platon.pulsar.agentic.cli.CliJobRegistry
import ai.platon.pulsar.agentic.cli.CliProcessManager
import ai.platon.pulsar.agentic.cli.CliRunRequest
import ai.platon.pulsar.agentic.observability.CliMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * Tool executor for invoking the browser4-cli from within an agent session.
 *
 * Domain: `b4`
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
 * // Executed via AgentToolManager when domain is "b4"
 * b4.run(args = "tab navigate --url https://example.com")
 * b4.run(args = "tab screenshot --selector '#main'")
 * ```
 */
class B4CliToolExecutor(
    private val backendBaseUrl: String? = null,
    private val defaultWorkingDir: Path? = null,
    private val cliProcessManager: CliProcessManager = CliProcessManager(CliBinaryResolver()),
    /** Yield window before a long command escalates to a background job. */
    private val jobYieldMs: Long = 10_000,
) : AbstractToolExecutor() {

    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobRegistry = CliJobRegistry(cliProcessManager, jobScope)

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

    override val domain = "b4"

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
                "Example: b4.run(args=\"tab navigate --url https://example.com\"). " +
                "The binary is resolved automatically (bundled / PATH / auto-install). " +
                "Default timeout 300s (max 600s); pass timeoutSeconds for long commands. " +
                "Commands that finish quickly return their result directly; commands still " +
                "running after ~10s return a '[job: <id>]' handle — poll with b4.status(id=...), " +
                "wait with b4.wait(id=..., timeoutSeconds=...), or cancel with b4.kill(id=...). " +
                "NOTE: 'agent run', 'agent-run', and 'act' subcommands are blocked " +
                "to prevent nested agent spawning."
        )
        toolSpec["status"] = ToolSpec(
            domain = domain, method = "status",
            arguments = listOf(ToolSpec.Arg("id", "String")),
            returnType = "String",
            description = "Check the status of a long-running cli job returned by b4.run " +
                "(e.g. '[job: <id>]'). Returns RUNNING/COMPLETED/CANCELLED/FAILED plus the result when done."
        )
        toolSpec["wait"] = ToolSpec(
            domain = domain, method = "wait",
            arguments = listOf(
                ToolSpec.Arg("id", "String"),
                ToolSpec.Arg("timeoutSeconds", "Long", "60"),
            ),
            returnType = "String",
            description = "Block (up to timeoutSeconds) for a cli job to finish; returns the " +
                "result, or a still-running notice when the timeout expires."
        )
        toolSpec["kill"] = ToolSpec(
            domain = domain, method = "kill",
            arguments = listOf(ToolSpec.Arg("id", "String")),
            returnType = "String",
            description = "Cancel a running cli job by id (kills the process tree)."
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

        return when (functionName) {
            "run" -> {
                validateArgs(args, allowed = setOf("args", "timeoutSeconds", "workingDir"), required = setOf("args"), functionName)
                val cliArgs = paramString(args, "args", functionName)!!
                requireAgentNotSpawned(cliArgs)
                val timeout = paramLong(args, "timeoutSeconds", functionName, required = false, default = 300L) ?: 300L
                val workingDir = paramString(args, "workingDir", functionName, required = false, default = null)
                // Long-command job escalation (design §4.1.1): start the command
                // as a background job; if it finishes within the yield window
                // return the result directly, otherwise hand the model a job
                // handle to poll/wait/kill.
                val request = CliRunRequest(
                    args = cliArgs,
                    timeoutSeconds = timeout,
                    workingDir = workingDir?.let { Path.of(it) } ?: defaultWorkingDir,
                )
                val jobId = jobRegistry.start(request, backendBaseUrl)
                val result = jobRegistry.await(jobId, jobYieldMs)
                if (result != null) {
                    result.toModelText()
                } else {
                    CliMetrics.recordJobEscalation()
                    "[job: $jobId] Command still running after ${jobYieldMs / 1000}s. " +
                        "Poll with b4.status(id=\"$jobId\"), wait with b4.wait(id=\"$jobId\", " +
                        "timeoutSeconds=...), or cancel with b4.kill(id=\"$jobId\")."
                }
            }
            "status" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val job = jobRegistry.status(id)
                    ?: return "Job not found: $id"
                buildString {
                    append("Job ").append(id).append(" state=").append(job.state)
                    append(" started=").append(job.startedAt)
                    job.finishedAt?.let { append(" finished=").append(it) }
                    job.result?.let { append("\n").append(it.toModelText()) }
                }
            }
            "wait" -> {
                validateArgs(args, allowed = setOf("id", "timeoutSeconds"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val timeout = paramLong(args, "timeoutSeconds", functionName, required = false, default = 60L) ?: 60L
                val result = jobRegistry.await(id, timeout * 1000)
                if (result != null) {
                    result.toModelText()
                } else {
                    "[job: $id] still running after ${timeout}s; poll with b4.status(id=\"$id\") " +
                        "or wait again with b4.wait(id=\"$id\", timeoutSeconds=...)."
                }
            }
            "kill" -> {
                validateArgs(args, allowed = setOf("id"), required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                if (jobRegistry.kill(id)) {
                    "Job $id cancelled."
                } else {
                    "Job $id not found or already finished."
                }
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
            else -> throw IllegalArgumentException("Unsupported b4 method: $functionName(${args.keys})")
        }
    }

    /** Cancel all tracked background jobs (called on agent close). */
    fun closeJobs() {
        jobRegistry.close()
        jobScope.cancel()
    }

    /**
     * Reject CLI invocations that would spawn a nested agent.
     *
     * Without this guard the chain
     *   agent → b4.run("agent run …") → subprocess → backend → new agent → …
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
