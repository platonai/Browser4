package ai.platon.pulsar.agent.tool

import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.TaskEnvelopes
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.coding.AccessPreset
import ai.platon.pulsar.coding.AdditionalDirs
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.entities.CommandEvents
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.rest.api.entities.statusWord
import java.time.Duration
import java.time.Instant
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
 * - `events(id, after?, limit?)` — Page the task's live step log (what it is doing).
 * - `stats(id)` — Read the task's run counters (what it costs, tool calls vs files changed).
 * - `input(id, text, deliverAs?)` — Steer a running task or queue a follow-up.
 *
 * The last three close the "supervisable, not just watchable" gap of issue #652: before
 * them an MCP-only client could submit and cancel a task but could not see its steps, its
 * counters, or inject a correction — the same facts were only reachable over REST.
 *
 * ## Usage Example:
 *
 * ```kotlin
 * // command.run(command="https://example.com -expires 1d -parse", async=true)
 * // command.status(id="<task-id>")
 * // command.result(id="<task-id>")
 * // command.events(id="<task-id>", after=0, limit=200)
 * // command.stats(id="<task-id>")
 * // command.input(id="<task-id>", text="also collect the prices", deliverAs="steer")
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
                        "Required unless `resumeTaskId` is given (a resume takes its instruction from " +
                        "the interrupted task's checkpoint).",
                    requiredUnless = "resumeTaskId",
                ),
                ToolSpec.Arg(
                    "async", "Boolean", "true",
                    "`true` (default) returns a task id immediately; `false` blocks until the command " +
                        "finishes and returns the CommandStatus JSON.",
                ),
                ToolSpec.Arg(
                    "noopLimit", "Int?", "null",
                    "Consecutive no-op abort threshold for agent tasks; omit to use the server default.",
                ),
                ToolSpec.Arg(
                    "coding", "Boolean?", "null",
                    "Explicit task type. True marks the task as a coding task (plain file/build work; " +
                        "no browser page, no page navigation, no screenshots). Everything else — including " +
                        "any URL or page interaction — stays a browser task (the default, also when omitted): " +
                        "task type is never auto-detected.",
                ),
                ToolSpec.Arg(
                    "freshWorkspace", "Boolean?", "null",
                    "Deprecated compatibility switch: every task now runs in its own workspace " +
                        "(<agent data dir>/workspace.<taskId>), so this is a no-op. Kept so older " +
                        "clients keep working; use `sharedWorkspace` for the opposite.",
                ),
                ToolSpec.Arg(
                    "sharedWorkspace", "Boolean?", "null",
                    "True runs this task in the shared <agent data dir>/workspace instead of a " +
                        "per-task workspace, so it sees and mutates previous tasks' files. " +
                        "Omit or false keeps the default isolation (one workspace per task; " +
                        "`resumeTaskId` keeps the interrupted task's workspace).",
                ),
                ToolSpec.Arg(
                    "engine", "String?", "null",
                    "Deprecated compatibility argument: the CLI tool loop is the only engine. " +
                        "`cli` is accepted and ignored; the removed `observe-act` engine is rejected.",
                ),
                ToolSpec.Arg(
                    "access", "String?", "null",
                    "Access preset for this task's coding tools: `full` (default), `write` (no shell), " +
                        "`read-build` (read + project build, no shell, no file mutation), `read`. " +
                        "Omit to use the server-wide `browser4.agent.access` default. The preset is " +
                        "reset when the task ends.",
                ),
                ToolSpec.Arg(
                    "unattended", "Boolean?", "true",
                    "Whether nobody is watching this task (the default). An unattended task that names " +
                        "no `access` preset derives the documented default (`full` with outbound network, " +
                        "audited as ACCESS_APPROVED source=default); pass false from an interactive TTY " +
                        "run so the server-wide policy applies instead. `browser4.agent.access.unattended` " +
                        "narrows the derived default.",
                ),
                ToolSpec.Arg(
                    "unrestricted", "Boolean?", "null",
                    "Run this task with the current OS user's own permissions (`agent run " +
                        "--unrestricted`): full preset, external access, network, destructive commands, " +
                        "no protected/sensitive rails, the OS sandbox is not applied, and the coding " +
                        "shell's command allow-list is off too, so inline scripts and PowerShell " +
                        "cmdlets run as written (shapes it cannot decompose are handed to the platform " +
                        "shell in every mode). Opt-in per task and audited; " +
                        "inherited by `resumeTaskId`. Omit to keep the normal rails.",
                ),
                ToolSpec.Arg(
                    "resumeTaskId", "String?", "null",
                    "Resume an interrupted task by id: the new run starts from that task's checkpoint — " +
                        "its instruction, workspace and the files already written — and inherits its " +
                        "coding/access flags. `command` may then be empty. The id must be known to this " +
                        "backend (the interrupted row is restored after a restart, within its TTL).",
                ),
                ToolSpec.Arg(
                    "tokenBudget", "Long?", "null",
                    "**Cache-miss** token budget for THIS task (`agent run --token-budget`), overriding " +
                        "`agent.token.budget.total` for this run only — a per-run override, never a " +
                        "process-wide config write. The charge is uncached prompt + output; prompt-cache " +
                        "reads do not count, and a provider that reports no cache details charges its " +
                        "whole prompt. 0 means unlimited. Omit to use the configured default.",
                ),
                ToolSpec.Arg(
                    "addDirs", "List<String>?", "null",
                    "Extra folders this task's agent may reach besides its workspace " +
                        "(`agent run --add-dir`, repeatable). Each entry must be an absolute path to an " +
                        "existing directory on the backend machine; relative paths, missing folders and " +
                        "OS credential stores are rejected at submission. An added folder is readable, " +
                        "writable and usable as a shell working directory for this task only (the grant " +
                        "is reset when it ends), while the access preset still decides whether anything " +
                        "may be written there.",
                ),
            ),
            returnType = "String",
            description = "Execute a plain command (URL, instruction, or agent task). " +
                    "When async=true (default), returns a task ID immediately. " +
                    "When async=false, blocks until done and returns the CommandStatus as JSON. " +
                    "noopLimit optionally overrides the consecutive no-op abort threshold for agent tasks. " +
                    "engine is a deprecated compatibility argument: the CLI tool loop is the only engine, " +
                    "and the removed 'observe-act' engine is rejected. " +
                    "coding=true explicitly marks an agent task as a coding task (file/build work, no page); " +
                    "omitted or false means a browser task — the default, and never auto-detected.",
            help = """
                command.run(command: String)
                command.run(command: String, async: Boolean = true, noopLimit: Int?, engine: String?, coding: Boolean?)

                `command` is a URL to load, a natural-language instruction, or an agent
                task. With async=true (the default) the call returns a task id and you
                poll it with `command.status` (JSON status envelope), read the payload
                with `command.result`, and stop it with `command.cancel`. Pass
                async=false when you would rather block for the finished status.
                Pass coding=true for a pure coding task (read/write files, run builds):
                the agent then works from its coding tool set without a browser page.
                Tasks are browser tasks unless the caller says otherwise.
                addDirs grants extra absolute folders besides the workspace ("agent run
                --add-dir"): the task may read, write and run commands there, the access
                preset still limits what may be written, and the grant ends with the task.
            """.trimIndent(),
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
            task = TaskPolicy(
                statusTool = "command_status",
                resultTool = "command_result",
                cancelTool = "command_cancel",
                // The supervision surface is named on the submit envelope so a client
                // that only speaks MCP discovers it exists (#652, P0-3).
                eventsTool = "command_events",
                statsTool = "command_stats",
            ),
            outputSchema = TaskEnvelopes.SUBMIT_SCHEMA,
        )

        toolSpec["status"] = ToolSpec(
            domain = domain,
            method = "status",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `command.run`. Required."),
            ),
            returnType = "String",
            description = "Poll a command task: the shared status envelope as JSON.",
            help = """
                command.status(id: String)

                Returns the shared envelope — `taskId`, `status`
                (`queued|running|done|failed|cancelled`), `processed` when the agent
                has recorded steps, and the tool names to poll, read and cancel with.
                An unknown id reports `status=failed`, never a healthy-looking state.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
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
            returnType = "String",
            description = "Read a finished command task: its status envelope and payload.",
            help = """
                command.result(id: String)

                The status envelope plus a `result` field holding the command's own
                payload. Poll `command.status` until it is terminal before reading, or
                call this directly and treat a non-terminal `status` as "not ready".
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Read the finished command's output",
                    args = mapOf("id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01"),
                ),
            ),
        )

        toolSpec["cancel"] = ToolSpec(
            domain = domain,
            method = "cancel",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `command.run`. Required."),
            ),
            returnType = "String",
            description = "Cancel a running command task: an agent task is interrupted, a page visit runs to completion.",
            help = """
                Stops the task's runner and reports the outcome as JSON
                (`cancelled=true|false`, plus a `reason` whenever nothing was cancelled).
                A page visit cannot be interrupted mid-navigation: cancelling a live one
                reports `cancelled=false` with that reason, and so does cancelling a task
                that already finished — the response never pretends the id is unknown.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Stop a running agent command",
                    args = mapOf("id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01"),
                ),
            ),
        )

        toolSpec["events"] = ToolSpec(
            domain = domain,
            method = "events",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `command.run`. Required."),
                ToolSpec.Arg(
                    "after", "Long?", "0",
                    "Cursor: only steps with a greater `seq` are returned. Pass the " +
                        "`nextSeq` of the previous page; 0 starts from the retained tail.",
                ),
                ToolSpec.Arg(
                    "limit", "Int?", "200",
                    "Page size, clamped to 1000. A long coding run emits thousands of steps, " +
                        "so page instead of asking for everything.",
                ),
            ),
            returnType = "String",
            description = "Page a command task's live step log: what the task is actually doing, one step per round/tool/model reply.",
            help = """
                command.events(id: String, after: Long = 0, limit: Int = 200)

                Answers "what is it doing" while `command.status` answers "is it alive".
                Each step carries `seq`, `type` (`round.start`, `tool.call`,
                `tool.result`, `model.text`, `run.usage`, lifecycle and the decision
                events `gate.evaluated`, `verify.verdict`, `repair.round`, `nudge`,
                `isolation.merge`, …), plus `callId` on tool steps (pair `tool.call`
                with its `tool.result` by it, never by tool name) and a bounded
                `detail` map on decision steps.

                The answer also carries the cursor facts: `nextSeq` to pass back as
                `after`, `firstRetainedSeq` (oldest step still retained), `dropped`
                (steps evicted from the bounded buffer) and `resynced=true` when your
                cursor had fallen behind the window — the page is then the retained
                tail, not a continuation, and steps are missing rather than absent.

                Steps are in memory only (bounded per task): a backend restart keeps
                the status row and loses the process. `done=true` means stop polling.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.EVENTS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Read the newest steps of a running task",
                    args = mapOf("id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01"),
                ),
                ToolExample(
                    title = "Follow a task from the beginning of the retained window",
                    args = mapOf("id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01", "after" to "0", "limit" to "100"),
                ),
            ),
        )

        toolSpec["stats"] = ToolSpec(
            domain = domain,
            method = "stats",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `command.run`. Required."),
            ),
            returnType = "String",
            description = "Read a command task's exact run counters: rounds, LLM calls, tool calls, failures, tokens and file mutations.",
            help = """
                command.stats(id: String)

                The counters are maintained as steps are appended, so they stay exact
                after steps fall out of the bounded buffer (counting the steps you
                received would undercount). Fields: `rounds`, `modelRequests`,
                `toolCalls`, `toolFailures`, `tokens{in,out,total,cachedIn,cacheWriteIn}`,
                `cacheReported`, `events`, `droppedEvents`, `toolCounts`, and the progress
                gauges `mutations` / `readOnlyRounds`.

                `toolCalls` with `mutations == 0` on a coding task is the earliest
                reliable sign the run will not converge. `mutations`/`readOnlyRounds`
                are `null` (not `0`) when the engine never reported them — a browsing
                task does not track file mutations.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Check whether a long coding run has changed any file",
                    args = mapOf("id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01"),
                ),
            ),
        )

        toolSpec["input"] = ToolSpec(
            domain = domain,
            method = "input",
            arguments = listOf(
                ToolSpec.Arg("id", "String", null, "Task id returned by `command.run`. Required."),
                ToolSpec.Arg(
                    "text", "String", null,
                    "What the agent should do from now on. Required and must not be blank.",
                ),
                ToolSpec.Arg(
                    "deliverAs", "String?", "steer",
                    "`steer` (default) reaches the agent at its next model round-trip and " +
                        "redirects the current step; `followUp` is delivered when the agent " +
                        "would stop and continues the same conversation with the new instruction.",
                ),
            ),
            returnType = "String",
            description = "Steer a running command task, or queue a follow-up that continues it.",
            help = """
                command.input(id: String, text: String, deliverAs: String = "steer")

                `accepted=true` means the line was QUEUED, not read: the agent sees a
                `steer` step at its next model round-trip (or a `follow_up` step at its
                stop point), and the returned `message` says which. `accepted=false` is a
                normal race (the task finished while you were typing) and comes with the
                reason instead of an error.
            """.trimIndent(),
            outputSchema = TaskEnvelopes.STATUS_SCHEMA,
            examples = listOf(
                ToolExample(
                    title = "Redirect a running task",
                    args = mapOf(
                        "id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01",
                        "text" to "also record the publish date of every item",
                    ),
                ),
                ToolExample(
                    title = "Queue an instruction for the stop point",
                    args = mapOf(
                        "id" to "5c3a1f2e-9b47-4d21-8f0a-1e6b7c8d9a01",
                        "text" to "then summarize what you changed",
                        "deliverAs" to "followUp",
                    ),
                ),
            ),
        )
    }

    /**
     * The transport argument every method reads but none declares as a tool
     * argument: it must be accepted by the executor's own `validateArgs`, or a
     * client that sends `sessionId` — which both channels pass through — gets
     * "Extraneous parameter 'sessionId'" instead of its answer.
     */
    private val SESSION_ARG = setOf("sessionId")

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
            // command.run(command: String, async?: Boolean = true, noopLimit?: Int = null,
            //              engine?: String = null, coding?: Boolean = null)
            "run" -> {
                validateArgs(
                    args,
                    allowed = setOf(
                        "command", "async", "noopLimit", "engine", "coding", "freshWorkspace", "sharedWorkspace",
                        "access", "unattended", "resumeTaskId", "rewindTaskId", "tokenBudget", "addDirs",
                        "unrestricted",
                    ) + SESSION_ARG,
                    // `agent run --resume <id>` may omit the task text: the instruction
                    // comes from the interrupted task's checkpoint. Validated below.
                    required = emptySet(),
                    functionName
                )
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val command = paramString(args, "command", functionName, required = false, default = "") ?: ""
                // Resume: seed a new run from an interrupted task's checkpoint. The
                // backend must know the id; an unknown one fails the task loudly in
                // the runner instead of quietly starting from the bare instruction.
                val resumeTaskId = paramString(args, "resumeTaskId", functionName, required = false, default = null)
                // `agent run --rewind <task-id>`: continue this session's conversation from
                // the point where that task began (the abandoned branch is summarized).
                val rewindTaskId = paramString(args, "rewindTaskId", functionName, required = false, default = null)
                require(command.isNotBlank() || !resumeTaskId.isNullOrBlank()) {
                    "command is required (or resumeTaskId, to resume an interrupted task)"
                }
                val isAsync = paramBool(args, "async", functionName, required = false, default = true) ?: true
                val noopLimit = paramInt(args, "noopLimit", functionName, required = false, default = null)
                // `engine` survives only for compatibility with existing clients: the
                // observe-act engine was removed and the CLI tool loop is the only
                // engine left. Accept the remaining id, reject a removed one loudly —
                // silently ignoring `observe-act` would run a different engine than
                // the caller asked for.
                paramString(args, "engine", functionName, required = false)?.let { raw ->
                    val value = raw.trim().lowercase()
                    require(value in CLI_ENGINE_VALUES) {
                        if (value in REMOVED_ENGINE_VALUES) {
                            "The '$raw' engine was removed: the CLI tool loop is the only engine. " +
                                "Drop the `engine` argument (or pass 'cli')."
                        } else {
                            "Unknown engine '$raw': expected 'cli'"
                        }
                    }
                }
                // null (absent) = browser task: explicit opt-in only, never guessed.
                val coding = paramBool(args, "coding", functionName, required = false, default = null)
                val freshWorkspace = paramBool(args, "freshWorkspace", functionName, required = false, default = null)
                val sharedWorkspace = paramBool(args, "sharedWorkspace", functionName, required = false, default = null)
                // Unknown preset must fail loudly: silently falling back to the server default
                // would run a task the caller believes is read-only with full access.
                val access = paramString(args, "access", functionName, required = false, default = null)
                    ?.also { raw ->
                        require(AccessPreset.parse(raw) != null) {
                            "Unknown access '$raw': expected one of " +
                                AccessPreset.values().joinToString(", ") { it.id }
                        }
                    }
                    ?.let { AccessPreset.parse(it) }
                // Plan P4-1: nobody is watching a submitted job by default, so it derives the
                // documented access default. The interactive CLI passes false on a TTY.
                val unattended = paramBool(args, "unattended", functionName, required = false, default = true) ?: true
                // `--unrestricted` is a capability opt-in, so it is parsed strictly: a non-boolean
                // value must fail rather than silently leaving the rails in place (or, worse, off).
                val unrestricted = paramBool(args, "unrestricted", functionName, required = false, default = null)
                // Per-task token budget: a negative value is not a budget, so it is
                // rejected rather than silently becoming "unlimited".
                val tokenBudget = paramLong(args, "tokenBudget", functionName, required = false, default = null)
                    ?.also { require(it >= 0) { "tokenBudget must be >= 0 (0 = unlimited), actual=$it" } }
                // Extra folders granted to this task (`agent run --add-dir`). Resolved and validated
                // here — the single decision point — so a missing directory fails the call with an
                // actionable message instead of starting a task whose grant silently means nothing.
                val addDirs = AdditionalDirs.resolveAll(
                    paramStringList(args, "addDirs", functionName, required = false)
                )
                if (isAsync) {
                    service.submitPlainCommand(
                        sessionId, command, noopLimit, coding, freshWorkspace, sharedWorkspace, access, unattended,
                        resumeTaskId, tokenBudget, addDirs, unrestricted, rewindTaskId,
                    )
                } else {
                    val status = service.executePlainCommand(
                        sessionId, command, noopLimit, coding, freshWorkspace, sharedWorkspace, access, unattended,
                        resumeTaskId, tokenBudget, addDirs, unrestricted, rewindTaskId,
                    )
                    pulsarObjectMapper().writeValueAsString(status)
                }
            }

            // command.status(id: String)
            "status" -> {
                validateArgs(args, allowed = setOf("id") + SESSION_ARG, required = setOf("id"), functionName)
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val id = paramString(args, "id", functionName)!!
                // Serializing a null status used to emit the literal "null" and
                // the CLI then overwrote its cached terminal statuses with
                // "queued" (P2.5) — return a structured notFound status instead.
                val status = service.getStatus(sessionId, id) ?: CommandStatus.notFound(id)
                pulsarObjectMapper().writeValueAsString(envelopeOf(status))
            }

            // command.result(id: String)
            "result" -> {
                validateArgs(args, allowed = setOf("id") + SESSION_ARG, required = setOf("id"), functionName)
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val id = paramString(args, "id", functionName)!!
                val result = service.getResult(sessionId, id)
                pulsarObjectMapper().writeValueAsString(
                    envelopeOf(service.getStatus(sessionId, id) ?: CommandStatus.notFound(id)) +
                        mapOf("result" to result)
                )
            }

            // command.cancel(id: String)
            "cancel" -> {
                validateArgs(args, allowed = setOf("id") + SESSION_ARG, required = setOf("id"), functionName)
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val id = paramString(args, "id", functionName)!!
                val cancelled = service.cancelAgentTask(id)
                val status = service.getStatus(sessionId, id) ?: CommandStatus.notFound(id)
                pulsarObjectMapper().writeValueAsString(
                    envelopeOf(status, overrideStatus = if (cancelled) "cancelled" else null) +
                        mapOf("cancelled" to cancelled) +
                        cancelReason(cancelled, service.isPageVisitTask(id), status)
                )
            }

            // command.events(id: String, after?: Long = 0, limit?: Int = 200)
            "events" -> {
                validateArgs(args, allowed = setOf("id", "after", "limit") + SESSION_ARG, required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val after = paramLong(args, "after", functionName, required = false, default = 0L) ?: 0L
                require(after >= 0) { "after must be >= 0, actual=$after" }
                val limit = paramInt(args, "limit", functionName, required = false, default = DEFAULT_EVENTS_PAGE)!!
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val status = service.getStatus(sessionId, id)
                val page = service.eventsPage(id, after, limit.coerceIn(1, MAX_EVENTS_PAGE))
                if (status == null && page == null) {
                    // Same 404-flavoured envelope the status tool returns: an unknown id
                    // must not look like "a task with no steps yet".
                    pulsarObjectMapper().writeValueAsString(
                        envelopeOf(CommandStatus.notFound(id)) + mapOf(
                            "done" to true,
                            "nextSeq" to after,
                            "firstRetainedSeq" to 0,
                            "dropped" to 0,
                            "resynced" to false,
                            "events" to emptyList<Any>(),
                            "eventsTool" to "command_events",
                            "statsTool" to "command_stats",
                        )
                    )
                } else {
                    // The same shape the REST endpoint answers (`CommandEventsResponse`
                    // plus the pointers), so an MCP client and a REST client read one
                    // contract. Not the status envelope: a task whose status row expired
                    // while its log survived is `unknown`, not `failed`.
                    val response = CommandEvents.page(id, status, page, after)
                    pulsarObjectMapper().writeValueAsString(
                        linkedMapOf<String, Any?>(
                            "taskId" to id,
                            "status" to response.status,
                            "done" to response.done,
                            "nextSeq" to response.nextSeq,
                            "firstRetainedSeq" to response.firstRetainedSeq,
                            "dropped" to response.dropped,
                            "resynced" to response.resynced,
                            "events" to response.events,
                        ) + supervisionPointers()
                    )
                }
            }

            // command.stats(id: String)
            "stats" -> {
                validateArgs(args, allowed = setOf("id") + SESSION_ARG, required = setOf("id"), functionName)
                val id = paramString(args, "id", functionName)!!
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val stats = service.stats(id)
                val status = service.getStatus(sessionId, id)
                if (stats == null) {
                    pulsarObjectMapper().writeValueAsString(
                        envelopeOf(status ?: CommandStatus.notFound(id)) + mapOf(
                            "done" to (status?.isDone ?: true),
                            "processState" to (status?.processState ?: "done"),
                            "eventsTool" to "command_events",
                            "statsTool" to "command_stats",
                            "message" to (
                                "no run counters for '$id' on this backend: the task is unknown, " +
                                    "belongs to another backend process, or its log's idle TTL expired"
                                ),
                        )
                    )
                } else {
                    // `CommandStatsResponse` plus the pointers: the counters outlive the
                    // status row (the log is read-TTL, the row is write-TTL), so the word
                    // is `unknown` when the row is gone — never `failed`.
                    pulsarObjectMapper().writeValueAsString(
                        linkedMapOf<String, Any?>(
                            "taskId" to id,
                            "status" to (status?.statusWord() ?: "unknown"),
                            "processState" to (status?.processState ?: "unknown"),
                            "isDone" to (status?.isDone ?: false),
                            "elapsedMs" to elapsedMsOf(status),
                            "stats" to stats,
                        ) + supervisionPointers()
                    )
                }
            }

            // command.input(id: String, text: String, deliverAs?: String = "steer")
            "input" -> {
                validateArgs(
                    args, allowed = setOf("id", "text", "deliverAs") + SESSION_ARG,
                    required = setOf("id", "text"), functionName
                )
                val sessionId = paramString(args, "sessionId", functionName, default = DEFAULT_SESSION_ID)!!
                val id = paramString(args, "id", functionName)!!
                val text = paramString(args, "text", functionName)!!.trim()
                require(text.isNotEmpty()) { "text must not be blank" }
                // Only the two documented delivery points exist: silently falling back
                // would deliver a follow-up as a steer (a different, earlier injection).
                val deliverAs = paramString(args, "deliverAs", functionName, required = false, default = "steer")!!
                val normalized = deliverAs.trim().lowercase().replace("_", "").replace("-", "")
                require(normalized in DELIVERY_MODES) {
                    "Unknown deliverAs '$deliverAs': expected one of steering (steer) or followUp"
                }
                val followUp = normalized == "followup"
                val inputId = service.sendInput(id, text, if (followUp) "followUp" else "steer")
                val accepted = inputId != null
                val status = service.getStatus(sessionId, id) ?: CommandStatus.notFound(id)
                val payload = linkedMapOf<String, Any>(
                    "accepted" to accepted,
                    "message" to when {
                        !accepted -> "task is not running or does not accept input"
                        followUp -> "queued: the agent continues with it when the current step finishes"
                        else -> "queued: the agent sees it at its next model round"
                    },
                )
                // The delivered step repeats this id in `detail.inputIds`, so "queued" is
                // verifiable instead of merely claimed (issue #652, P1-5).
                inputId?.let { payload["inputId"] = it }
                pulsarObjectMapper().writeValueAsString(envelopeOf(status) + payload)
            }

            else -> throw IllegalArgumentException("Unsupported command method: $functionName(${args.keys})")
        }
    }

    /** Delivery points of `command.input`: mid-run steering, or the stop-point follow-up. */
    private val DELIVERY_MODES = setOf("steer", "followup")

    /** Default page size of `command.events` (matches `GET /api/commands/{id}/events`). */
    private val DEFAULT_EVENTS_PAGE = 200

    /** Upper bound of one `command.events` page (matches the REST controller's cap). */
    private val MAX_EVENTS_PAGE = 1000

    /** The only remaining engine id (and its alias): anything else is a loud client error. */
    private val CLI_ENGINE_VALUES = setOf("cli", "cli-tool-loop")

    /** Engine ids that used to select the removed observe-act engine, rejected loudly. */
    private val REMOVED_ENGINE_VALUES = setOf("observe-act", "observe_act", "v1", "legacy")

    /**
     * The shared status envelope of a command task.
     *
     * `command.result` and `command.run(async=false)` keep returning the domain's
     * own payload alongside it, so existing clients read what they always read.
     *
     * @param overrideStatus the word to report instead of the task's own (a cancel
     *   that just happened reports `cancelled` even though the runner has not
     *   written its final state yet)
     */
    private fun envelopeOf(status: CommandStatus, overrideStatus: String? = null): Map<String, Any> {
        val word = overrideStatus ?: statusWord(status)
        // Progress signals, best first: the live tool-call counter the engine
        // reports while a CLI run is in flight, the recorded step history, then the
        // domain's own instruct results. A page load has none of them, and `null`
        // stays honest about that.
        val processed = status.toolExecutions.takeIf { it > 0 }
            ?: status.agentHistory?.states?.size?.takeIf { it > 0 }
            ?: status.instructResults.size.takeIf { it > 0 }
        // How long the task has been running: a poller could previously not tell a
        // 50-minute run from one that started seconds ago (`agent status` showed
        // only "running / 102 / Agent is working").
        val elapsedMs = status.startedTime?.let { started ->
            Duration.between(started, status.finishTime ?: Instant.now()).toMillis().coerceAtLeast(0)
        }
        return TaskEnvelopes.of(
            taskId = status.id,
            status = word,
            processed = processed,
            elapsedMs = elapsedMs,
            // The domain's own code: callers key on 404 to tell "no such task" from a
            // genuine failure without pattern-matching English error text.
            statusCode = status.statusCode,
            // Human-readable progress, present while the task is still healthy.
            message = status.message,
            error = if (word == "failed") status.message else null,
            // Failure semantics travel on the envelope as well as the payload: every
            // client reads the envelope first, and `status=failed` alone cannot tell
            // a token-budget abort from a broken browser.
            errorCode = status.errorCode,
            resumable = status.resumable,
            effectiveTokenBudget = status.effectiveTokenBudget,
            statusTool = "command_status",
            resultTool = "command_result",
            cancelTool = "command_cancel",
            // The supervision surface is part of the envelope, not something the client
            // is expected to know: an MCP-only supervisor could not discover that it can
            // read the steps, read the counters or steer (#652, P0-3).
            eventsTool = "command_events",
            statsTool = "command_stats",
            // Live progress gauges (P0-B): "N tool calls / 0 files changed" readable from
            // the same poll that says "running". `null` stays absent = never reported.
            mutations = status.mutations,
            readOnlyRounds = status.readOnlyRounds,
            // Structured failure evidence (P0-C): a poll of the envelope answers "which
            // gate rule, which files, which check" without a second call.
            failureDetail = status.failureDetail,
        )
    }

    /** `CommandStatus` mapped onto the shared status vocabulary. */
    private fun statusWord(status: CommandStatus): String = status.statusWord()

    /**
     * The tool names every command payload advertises.
     *
     * Shared by the status envelope and the two supervision answers: whichever call a
     * client makes, the response tells it what else it can call — an MCP-only supervisor
     * has no other way to discover `command_events` / `command_stats`.
     */
    private fun supervisionPointers(): Map<String, Any> = linkedMapOf(
        "statusTool" to "command_status",
        "resultTool" to "command_result",
        "cancelTool" to "command_cancel",
        "eventsTool" to "command_events",
        "statsTool" to "command_stats",
    )

    /** Wall-clock run time of [status], or `null` when it never started. */
    private fun elapsedMsOf(status: CommandStatus?): Long? = status?.startedTime?.let { started ->
        Duration.between(started, status.finishTime ?: Instant.now()).toMillis().coerceAtLeast(0)
    }

    /**
     * Why a cancellation did not happen.
     *
     * The envelope alone cannot distinguish "a live page visit that cannot be
     * interrupted", "an agent task that already finished", and "a synchronous
     * run with no cancellable job" — all report `cancelled=false`, and leaving
     * the caller to guess is how a cancel silently looks like a no-op. A page
     * visit is checked first because it is the case that is still *running*
     * when the answer is false.
     */
    private fun cancelReason(cancelled: Boolean, pageVisit: Boolean, status: CommandStatus): Map<String, Any> = when {
        cancelled -> emptyMap()
        pageVisit -> mapOf("reason" to "page visit tasks run to completion and cannot be cancelled")
        status.isDone -> mapOf("reason" to "task already finished")
        else -> mapOf(
            "reason" to (
                "no cancellable job is registered for this id — a synchronous " +
                    "'command run' cannot be cancelled mid-run; submit it asynchronously " +
                    "(async=true, the default) if cancelling it matters"
                )
        )
    }
}
