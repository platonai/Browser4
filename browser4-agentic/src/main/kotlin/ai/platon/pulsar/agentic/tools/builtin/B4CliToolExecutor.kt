package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.PermissionAudit
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
    /**
     * The access policy in force for the current task, used to decide whether `b4.run`
     * may reach outside the agent workspace (D4). A supplier because the policy is
     * per-task while this executor outlives tasks.
     */
    private val accessPolicy: () -> AgentAccessPolicy = { AgentAccessPolicy.serverDefault },
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
                "On Windows the arguments are interpreted by PowerShell " +
                "(pwsh -Command \"& browser4-cli ...\"), not as plain argv: quote @file " +
                "references ('@query.sql' — an unquoted @file is PowerShell splatting) and do not " +
                "use cmd.exe syntax (dir /b, %VAR%). " +
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
            description = "Block (up to timeoutSeconds) for a cli job to finish; returns the job's " +
                "terminal state (COMPLETED/FAILED/CANCELLED) followed by its result, or a " +
                "still-running notice when the timeout expires."
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
                // D4: the browser (file://) and upload are filesystem readers the coding
                // tools' boundary never covered. Refuse them while the task may not read
                // outside its workspace.
                externalPathRefusal(cliArgs)?.let { return it }
                // D5: `stop` and friends end the backend this task runs in. A live run used
                // `stop` to "fix" a browser failure and killed itself (2026-10-28).
                b4CliLifecycleRefusal(cliArgs)?.let { return it }
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
                    // The terminal state decides how to read the text: a COMPLETED job's
                    // output is the result, a FAILED/CANCELLED one's is a fragment. A live
                    // run re-ran a whole 20-page crawl because `wait` returned partial
                    // progress with no state (only `status` said FAILED).
                    val state = jobRegistry.status(id)?.state?.name
                        ?: if (result.isSuccess) "COMPLETED" else "FAILED"
                    "Job $id state=$state\n" + result.toModelText()
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

    /** Refuse `file://` reads and absolute uploads outside the workspace (D4). */
    private fun externalPathRefusal(cliArgs: String): String? {
        val policy = runCatching { accessPolicy() }.getOrNull() ?: AgentAccessPolicy.serverDefault
        return externalPathRefusal(cliArgs, defaultWorkingDir, policy.allowExternalAccess)
    }
}

/**
 * Refusal text when [args] would read a file outside [workspace], or null when allowed.
 *
 * `coding.*` file tools and `coding.shell` obey the workspace boundary, but the browser
 * could still open `file:///D:/…` and the agent could `upload` any absolute path — a live
 * run used exactly that to reach a fixture the file tools refused (D4, 2026-10-28). With
 * `browser4.agent.allowExternalAccess=true` the operator opted in and nothing is refused.
 *
 * Relative paths (resolved in the workspace) and paths inside it are always allowed.
 */
internal fun externalPathRefusal(args: String, workspace: Path?, allowExternalAccess: Boolean): String? {
    if (allowExternalAccess) return null
    val root = workspace?.toAbsolutePath()?.normalize()

    val candidates = buildList {
        FILE_URL.findAll(args).forEach { match -> add(match.groupValues[1]) }
        UPLOAD_ARG.findAll(args).forEach { match ->
            val value = match.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: return@forEach
            add(value)
        }
    }

    for (raw in candidates) {
        val path = pathOfFileReference(raw) ?: continue
        if (!path.isAbsolute) continue
        val normalized = path.toAbsolutePath().normalize()
        if (root != null && normalized.startsWith(root)) continue
        return PermissionAudit.format(
            tool = "b4.run",
            code = PermissionAudit.CODE_PATH_OUTSIDE_WORKSPACE,
            detail = "'$raw' is outside the workspace",
            hint = "copy the input into the workspace (task inputs are copied to .b4inputs/), " +
                "or start the backend with -Dbrowser4.agent.allowExternalAccess=true",
        )
    }
    return null
}

/**
 * CLI subcommands that end the backend this task runs in, or mutate the installation
 * underneath it.
 *
 * `close` is deliberately **absent**: closing one session is the documented anti-bot step
 * (`close`, then `open --headed` with the same `-s`). Only the whole-backend variants are
 * blocked, plus installation/state rewrites an agent run must not perform on itself.
 */
private val B4_LIFECYCLE_COMMANDS = setOf(
    "stop", "close-all", "kill-all", "install", "uninstall", "upgrade", "delete-data",
)

/** Global CLI flags that consume the next token, so it is not mistaken for the subcommand. */
private val B4_GLOBAL_FLAGS_WITH_VALUE = setOf("-s", "--session", "--server", "--timeout")

/**
 * Refuse `b4.run` invocations that would stop the backend running this agent task.
 *
 * Why: `b4.run` forwards raw CLI arguments, so `b4.run("stop")` terminates the very
 * process executing the task. A live supervision run (Amazon top-20, 2026-10-28) did
 * exactly that while "fixing" an unrelated browser failure: the JVM exited, the task was
 * reported as `server restarted`, and the real cause — its own `stop` — was lost.
 * Installation/state rewrites (`install`, `uninstall`, `upgrade`, `delete-data`,
 * `close-all`, `kill-all`, `doctor --fix`) are blocked for the same reason: an agent run
 * may not rewrite the runtime it is executing from.
 *
 * @param args the raw `b4.run(args=...)` string, as forwarded to the CLI
 * @return a structured `PERMISSION_DENIED` refusal, or null when the command is allowed
 */
internal fun b4CliLifecycleRefusal(args: String): String? {
    val words = args.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
    var i = 0
    // Global flags (e.g. `-s work1`) precede the subcommand; skip them and their values.
    while (i < words.size && words[i].startsWith("-")) {
        if (words[i] in B4_GLOBAL_FLAGS_WITH_VALUE) i++
        i++
    }
    val command = words.getOrNull(i)?.trim('"', '\'')?.lowercase() ?: return null
    val rest = words.drop(i + 1).map { it.trim('"', '\'') }
    // `--help` is read-only: a task may ask what a lifecycle command does before reporting
    // it as a blocker.
    if (rest.any { it.equals("--help", ignoreCase = true) || it == "-h" }) return null
    val mutatingDoctor = command == "doctor" && rest.any { it.equals("--fix", ignoreCase = true) }
    if (command !in B4_LIFECYCLE_COMMANDS && !mutatingDoctor) return null

    val blocked = if (mutatingDoctor) "doctor --fix" else command
    return PermissionAudit.format(
        tool = "b4.run",
        code = PermissionAudit.CODE_COMMAND_NOT_ALLOWED,
        detail = "'$blocked' is blocked inside an agent run — it stops or rewrites the backend " +
            "executing this task",
        hint = "run lifecycle/installation commands outside the task (before or after it), and " +
            "report the blocker in system.taskComplete(problems=[...]) instead",
    )
}

/** `file:///D:/x`, `file://server/share/x` and plain paths, URL-decoded. */
private fun pathOfFileReference(raw: String): Path? {
    var value = raw.trim()
    if (value.startsWith("//")) {
        value = "//" + value.trimStart('/')
    }
    val decoded = runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)
    val cleaned = if (decoded.length >= 3 && decoded[0] == '/' && decoded[2] == ':') {
        decoded.substring(1)
    } else {
        decoded
    }
    return runCatching { Path.of(cleaned) }.getOrNull()
}

private val FILE_URL = Regex("""file:(?:/{1,3})?([^"'\s]+)""", RegexOption.IGNORE_CASE)

/** `upload <ref> <path>` — the path is either quoted or a bare token. */
private val UPLOAD_ARG = Regex("""\bupload\s+\S+\s+(?:"([^"]+)"|'([^']+)'|(\S+))""", RegexOption.IGNORE_CASE)
