package ai.platon.pulsar.agent.tool

import ai.platon.pulsar.agentic.agents.RunEngine
import ai.platon.browser4.common.B4Constants.DEFAULT_SESSION_ID
import ai.platon.pulsar.agentic.model.TaskPolicy
import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.TaskEnvelopes
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.rest.api.entities.CommandStatus
import ai.platon.pulsar.common.ResourceStatus
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
                    "engine", "String?", "null",
                    "Agent engine: `cli` (default) for the tool-loop engine, `observe-act` is deprecated.",
                ),
            ),
            returnType = "String",
            description = "Execute a plain command (URL, instruction, or agent task). " +
                    "When async=true (default), returns a task ID immediately. " +
                    "When async=false, blocks until done and returns the CommandStatus as JSON. " +
                    "noopLimit optionally overrides the consecutive no-op abort threshold for agent tasks. " +
                    "engine optionally selects the agent execution engine: 'cli' (default) for the CLI tool-loop engine; " +
                    "'observe-act' is the DEPRECATED legacy engine. " +
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
                    allowed = setOf("command", "async", "noopLimit", "engine", "coding") + SESSION_ARG,
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
                val engine = paramString(args, "engine", functionName, required = false)?.also {
                    // RunEngine.parse silently maps ANY unknown value to the default
                    // engine — a typo such as "clli" would look accepted and change
                    // nothing. Fail loudly instead.
                    require(it.trim().lowercase() in ENGINE_VALUES) {
                        "Unknown engine '$it': expected 'cli' or 'observe-act' (deprecated)"
                    }
                }?.let { RunEngine.parse(it) }
                // null (absent) = browser task: explicit opt-in only, never guessed.
                val coding = paramBool(args, "coding", functionName, required = false, default = null)
                if (isAsync) {
                    service.submitPlainCommand(sessionId, command, noopLimit, engine, coding)
                } else {
                    val status = service.executePlainCommand(sessionId, command, noopLimit, engine, coding)
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

            else -> throw IllegalArgumentException("Unsupported command method: $functionName(${args.keys})")
        }
    }

    /** Aliases `RunEngine.parse` recognizes; anything else must be a loud client error. */
    private val ENGINE_VALUES = setOf("cli", "observe-act", "observe_act", "v1", "legacy")

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
            statusTool = "command_status",
            resultTool = "command_result",
            cancelTool = "command_cancel",
        )
    }

    /** `CommandStatus` mapped onto the shared status vocabulary. */
    private fun statusWord(status: CommandStatus): String = when {
        // A cancelled task is done and non-OK, but calling it "failed" turns a
        // user-requested cancel into an unexplained failure on every later poll —
        // the flag (set only by explicit cancel/close paths) wins over the code.
        status.isDone && status.cancelled -> "cancelled"
        status.isDone -> if (status.statusCode == ResourceStatus.SC_OK) "done" else "failed"
        status.processState == "created" -> "queued"
        else -> "running"
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
