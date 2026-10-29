package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.coding.AgentAccessPolicy
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.PermissionAudit
import ai.platon.pulsar.agentic.model.TcEvaluate
import ai.platon.pulsar.agentic.model.TcException
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.cli.CliBinaryResolver
import ai.platon.pulsar.agentic.cli.CliJobRegistry
import ai.platon.pulsar.agentic.cli.CliProcessManager
import ai.platon.pulsar.agentic.cli.CliResult
import ai.platon.pulsar.agentic.cli.CliRunRequest
import ai.platon.pulsar.agentic.observability.CliMetrics
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
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

    private val logger = getLogger(this)
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
                "The command runs in the task's workspace (the same directory coding.* " +
                "tools use), so relative paths like `eval --file .b4scratch/x.js` resolve " +
                "there — not in the repository checkout or another task's workspace. " +
                "A failed command is reported as `b4.run [fail] …`: a non-zero CLI exit " +
                "code, a JSON `\"status\":\"error\"` answer, a timeout or a cancelled job " +
                "all count as failures, and the output plus the reason stay in the result. " +
                "On Windows the arguments are interpreted by PowerShell " +
                "(pwsh -EncodedCommand \"& browser4-cli ...\"), not as plain argv: they reach " +
                "the CLI with their quoting intact, so `eval --json \"1+1\"` works, but the " +
                "text is still PowerShell syntax — quote @file references ('@query.sql' — an " +
                "unquoted @file is PowerShell splatting) and do not use cmd.exe syntax " +
                "(dir /b, %VAR%). " +
                "There is no stdin: a command that reads it (e.g. `eval --js`, the bool " +
                "shorthand for `eval --stdin`) sees EOF, so pass the JavaScript with " +
                "`eval --file <path>` or as a quoted positional expression instead. " +
                "The binary is resolved automatically (bundled / PATH / auto-install). " +
                "Default timeout 300s (max 600s); above 30s the CLI is also given a matching " +
                "global --timeout, so the CLI's own HTTP deadline is longer than the MCP call " +
                "and a long eval is not cut at the MCP default. Pass timeoutSeconds for longer " +
                "commands, or split a long batch (20 pages in 4 batches of 5). " +
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
                "(e.g. '[job: <id>]'). Returns RUNNING/COMPLETED/CANCELLED/FAILED plus the result when done. " +
                "A FAILED or CANCELLED job is reported as `b4.status [fail] …`, not as a normal result."
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
                "still-running notice when the timeout expires. " +
                "FAILED/CANCELLED is reported as `b4.wait [fail] …` — re-running the command " +
                "will fail the same way, so read the error instead of repeating it."
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
                val shell = receiver as CodingAgentShell
                // D4: the browser (file://) and upload are filesystem readers the coding
                // tools' boundary never covered. Refuse them while the task may not read
                // outside its workspace. The boundary is the shell's CURRENT root, not a
                // directory captured when the executor was built (see the cwd note below).
                externalPathRefusal(cliArgs, shell.workspaceRoot)?.let { return it }
                // D5: `stop` and friends end the backend this task runs in. A live run used
                // `stop` to "fix" a browser failure and killed itself (2026-10-28).
                b4CliLifecycleRefusal(cliArgs)?.let { return it }
                val timeout = paramLong(args, "timeoutSeconds", functionName, required = false, default = 300L) ?: 300L
                // The cwd must be the workspace the coding tools are writing into, not a
                // directory captured when this executor was built: the task runner rebinds
                // the workspace per task, and a stale default made a relative
                // `eval --file .b4scratch/x.js` resolve into the PREVIOUS task's workspace
                // (2026-10-28: the model saw "not found" four times and rewrote the path
                // instead of the binding being wrong). The receiver is the live shell.
                val explicitWorkingDir = paramString(args, "workingDir", functionName, required = false, default = null)
                val workingDir = explicitWorkingDir?.let { Path.of(it) } ?: shell.workspaceRoot
                if (explicitWorkingDir != null && workingDir != shell.workspaceRoot) {
                    logger.warn(
                        "b4.run explicit workingDir {} differs from the task workspace {}",
                        workingDir, shell.workspaceRoot
                    )
                }
                logger.debug("b4.run cwd={} workspaceRoot={} args={}", workingDir, shell.workspaceRoot, cliArgs)
                // Long-command job escalation (design §4.1.1): start the command
                // as a background job; if it finishes within the yield window
                // return the result directly, otherwise hand the model a job
                // handle to poll/wait/kill.
                val request = CliRunRequest(
                    args = cliArgs,
                    timeoutSeconds = timeout,
                    workingDir = workingDir,
                )
                val jobId = jobRegistry.start(request, backendBaseUrl)
                val result = jobRegistry.await(jobId, jobYieldMs)
                if (result != null) {
                    classifyCliResult(result, "run", workingDir)
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
                val text = buildString {
                    append("Job ").append(id).append(" state=").append(job.state)
                    append(" started=").append(job.startedAt)
                    job.finishedAt?.let { append(" finished=").append(it) }
                    job.result?.let { append("\n").append(it.toModelText()) }
                }
                jobStateFailure("status", id, job.state.name, text)
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
                    val text = "Job $id state=$state\n" + result.toModelText()
                    jobStateFailure("wait", id, state, text)
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
     * Turn a result whose bounded text reports a failure into a failed evaluation.
     *
     * The text stays the value — the model must still read the CLI's own output — while
     * the attached [TcException] makes `evaluate.success` false, so `ToolOutcome`
     * renders `b4.run [fail] …` instead of `[ok]`. Before this, a `{"status":"error"}`
     * answer or a non-zero exit was a plain string: the model repeated the same broken
     * command four times in the 2026-10-28 run because every attempt looked successful.
     */
    override suspend fun callFunctionOn(tc: ToolCall, receiver: Any): TcEvaluate {
        val evaluated = super.callFunctionOn(tc, receiver)
        if (evaluated.exception != null) return evaluated
        val reason = b4FailureReason(evaluated.value as? String ?: return evaluated) ?: return evaluated
        evaluated.exception = TcException(
            tc.pseudoExpression,
            B4CliFailureException(reason),
            help(tc.method).takeIf { it.isNotBlank() },
        )
        return evaluated
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
    private fun externalPathRefusal(cliArgs: String, workspace: Path): String? {
        val policy = runCatching { accessPolicy() }.getOrNull() ?: AgentAccessPolicy.serverDefault
        return externalPathRefusal(cliArgs, workspace, policy.allowExternalAccess)
    }
}

/**
 * A CLI invocation that failed by its own report (exit code, JSON `status:error`,
 * timeout, cancelled job).
 *
 * Extends [IllegalStateException] so the retry machinery classifies it as permanent:
 * re-running the same failing command cannot help, and a live run burned four
 * attempts on one missing relative path before this existed.
 */
class B4CliFailureException(message: String) : IllegalStateException(message)

/** First line of a failed `b4.*` result: the form `--follow` renders as `[fail]`. */
private val B4_FAILURE_HEADER = Regex("""^b4\.\w+ \[fail] (.*)$""")

/** The failure reason carried by [text], or null when the result is a normal one. */
internal fun b4FailureReason(text: String): String? {
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
    val match = B4_FAILURE_HEADER.find(firstLine) ?: return null
    return match.groupValues[1].trim().ifEmpty { "b4 CLI command failed" }
}

/** Render a failed CLI invocation: the marked first line, then the command's own output. */
internal fun b4FailureText(method: String, reason: String, detail: String = ""): String = buildString {
    append("b4.").append(method).append(" [fail] ").append(reason)
    if (detail.isNotBlank()) append('\n').append(detail.trim())
}

/**
 * The failure reason of one finished CLI invocation, or null when it succeeded.
 *
 * Covers every way the CLI can say "this did not work": a non-zero exit code, a JSON
 * envelope with `status:error` (the `--json` mode still exits 0 for some commands), a
 * timeout, a cancelled job and an infrastructure failure. Only the reason is
 * classified here; formatting and hints belong to the caller.
 */
internal fun cliFailureReason(result: CliResult): String? {
    result.infraFailure?.let { return "infrastructure failure: $it" }
    if (result.rejected) return "rejected: too many concurrent b4.run calls"
    if (result.timedOut) return "timed out after ${result.durationMs}ms"
    if (result.aborted) return "aborted (job cancelled)"
    cliStatusErrorMessage(result.stdout)?.let { return "CLI reported status=error: $it" }
    val code = result.exitCode
    if (code != null && code != 0) return "CLI exited with code $code"
    return null
}

/**
 * The message of a top-level `{"status":"error"|"failed", "error": …}` CLI envelope.
 *
 * Only a top-level `status` counts: command output routinely contains per-item
 * `"status":"error"` entries (a crawl page that failed), and treating those as the
 * command's own verdict would fail a successful crawl.
 */
internal fun cliStatusErrorMessage(stdout: String): String? {
    val trimmed = stdout.trim()
    if (!trimmed.startsWith("{") || !trimmed.contains("\"status\"")) return null
    val root = runCatching { pulsarObjectMapper().readTree(trimmed) }.getOrNull() ?: return null
    if (!root.isObject) return null
    val status = root.get("status")?.asText()?.trim()?.lowercase() ?: return null
    if (status != "error" && status != "failed") return null
    val error = root.get("error")
    val message = when {
        error == null -> null
        error.isTextual -> error.asText()
        else -> error.get("message")?.asText() ?: error.get("detail")?.asText()
    }
    return message?.takeIf { it.isNotBlank() } ?: "status=$status"
}

/**
 * Format one finished `b4.run` for the model: its output, prefixed with a `[fail]`
 * line and an actionable hint when the invocation failed.
 *
 * @param workingDir the directory relative paths resolved against — included in the
 *   path hint, because "not found" without the lookup root is what made a live run
 *   rewrite a correct relative path four times.
 */
internal fun classifyCliResult(result: CliResult, method: String, workingDir: Path? = null): String {
    val text = result.toModelText()
    val reason = cliFailureReason(result) ?: return text
    val hints = buildList {
        if (result.timedOut) {
            add(
                "the command did not finish in ${result.durationMs}ms: raise timeoutSeconds " +
                    "(max 600) or split it into batches (e.g. 20 pages as 4 batches of 5)"
            )
        }
        if (result.exitCode != null && result.exitCode != 0 && looksLikeMissingPath(text)) {
            val where = workingDir?.toAbsolutePath()?.normalize()?.toString() ?: "(unknown)"
            add("relative paths resolve against the b4.run working directory: $where")
        }
        if (looksLikeEvaluateDeadline(text)) {
            add(
                "a single page-side evaluate has a fixed 30s driver deadline " +
                    "(No response | Runtime.evaluate | PT30S) that timeoutSeconds cannot widen: " +
                    "wait server-side with `wait <ms>`, `wait --selector <css> --timeout <ms>` or " +
                    "`wait --fn '<expr>' --timeout <ms>`, or poll with short eval calls"
            )
        }
        if (result.aborted) add("the job was cancelled; re-running it will do the same unless the cause is fixed")
    }
    val hintLine = hints.joinToString("\n").takeIf { it.isNotBlank() }?.let { "\n[hint] $it" } ?: ""
    return b4FailureText(method, reason, text + hintLine)
}

/** True when the output reads like a path-resolution failure. */
private fun looksLikeMissingPath(text: String): Boolean {
    val lower = text.lowercase()
    return lower.contains("not found") || lower.contains("no such file") || lower.contains("cannot find")
}

/**
 * True when the output reports the driver's fixed 30s page-evaluate deadline.
 *
 * `Runtime.evaluate` gets a 30s CDP deadline that neither `timeoutSeconds` nor the CLI's
 * `--timeout` can widen: a 35s page-side sleep inside `eval --await` always ends as
 * `No response | Runtime.evaluate | (PT30S)` (2026-10-29 Gate, F14). The hint must point
 * at server-side waits, not at a bigger timeout.
 */
internal fun looksLikeEvaluateDeadline(text: String): Boolean {
    val lower = text.lowercase()
    return (lower.contains("runtime.evaluate") && lower.contains("no response")) || lower.contains("pt30s")
}

/**
 * A job that ended FAILED/CANCELLED must not read as a successful result: `wait`
 * returning partial output with no verdict was why a live run re-crawled 20 pages.
 */
internal fun jobStateFailure(method: String, id: String, state: String, text: String): String {
    val failed = state.equals("FAILED", true) || state.equals("CANCELLED", true)
    if (!failed) return text
    return b4FailureText(method, "job $id ended $state", text)
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
