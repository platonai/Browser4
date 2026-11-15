package ai.platon.pulsar.agentic.cli

import ai.platon.pulsar.coding.TokenEstimator
import java.nio.file.Path

/**
 * Raw CLI run request coming from the tool layer (the command line the LLM
 * wrote for `browser4-cli`, without the binary itself).
 *
 * @param args            e.g. `goto --url https://example.com`
 * @param timeoutSeconds  optional per-call override; clamped in [CliProcessManager.resolve]
 * @param workingDir      optional working directory
 * @param sessionId       ownership tag for per-session concurrency and job tracking
 * @param taskId          agent task ownership tag
 * @param stdin           payload for a command that reads its own input from stdin
 *   (`eval --stdin`, `htmlsnapshot inspect --stdin`, `fs write --stdin`, `batch --json`, …).
 *   Carrying the payload here instead of inside [args] keeps it out of the command
 *   line, which is what lets quoting-free commands skip the shell entirely
 *   (evaluation §12.6/§12.7, E-1). Empty/absent means "the child sees EOF", the
 *   historical behaviour for an argv-transported command.
 */
data class CliRunRequest(
    val args: String,
    val timeoutSeconds: Long? = null,
    val workingDir: Path? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
    val stdin: String? = null,
)

/**
 * Fully resolved run specification — request/spec separation (explicit > implicit).
 *
 * Every default (timeout, grace, output bound, env) is materialised here by
 * [CliProcessManager.resolve]; downstream layers never fall back to `?? default`.
 */
data class CliRunSpec(
    val binaryPath: Path,
    val shell: String,
    val shellArgv: List<String>,
    val commandLine: String,
    val timeoutMs: Long,
    val graceMs: Long,
    val outputBufferBytes: Int,
    val returnMaxTokens: Int,
    val workingDir: Path?,
    val sessionId: String?,
    val taskId: String?,
    val env: Map<String, String>,
    /** Payload written to the child's stdin, or null to close it immediately (EOF). */
    val stdin: String? = null,
)

/**
 * Where one CLI invocation spent its wall clock, as reported by the CLI itself
 * (`BROWSER4_CLI_TIMING=1` → a `[timing]` footer on stderr; evaluation E-4).
 *
 * - [startupMs] — process start → the first backend request: arg parsing,
 *   config/state resolution, development-mode port allocation, readiness probes.
 * - [httpMs] — total time inside tool-call HTTP round trips.
 * - [serverMs] — the part of [httpMs] the backend reports for its own handler
 *   (`X-Browser4-Duration-Ms`), i.e. the page/tool action.
 * - [netMs] — `httpMs - serverMs`: dispatch, sockets, serialization.
 * - [launchMs] — what the *caller* spent before the CLI existed: the shell
 *   interpreter and process spawn. The CLI cannot see it (its clock starts inside
 *   itself), so [CliProcessManager] computes it as
 *   `subprocess duration - totalMs`. Null when the CLI was not run through this
 *   manager (or when the manager could not attribute it).
 */
data class CliTiming(
    val startupMs: Long,
    val httpMs: Long,
    val serverMs: Long,
    val netMs: Long,
    val calls: Int,
    val totalMs: Long,
    val launchMs: Long? = null,
) {
    /** The same one-line form the CLI prints, rendered for logs and results. */
    fun format(): String = buildString {
        append("[timing] ")
        launchMs?.let { append("launch=").append(it).append("ms ") }
        append("startup=").append(startupMs).append("ms ")
        append("http=").append(httpMs).append("ms ")
        append("server=").append(serverMs).append("ms ")
        append("net=").append(netMs).append("ms ")
        append("calls=").append(calls).append(' ')
        append("total=").append(totalMs).append("ms")
    }
}

/**
 * Outcome of one CLI invocation.
 *
 * Error semantics: [infraFailure] is set only for infrastructure failures
 * (binary missing, backend unreachable, spawn failure) — everything else
 * (non-zero exit, timeout kill, abort kill) resolves into a normal result.
 * [timedOut] and [aborted] are mutually exclusive: the layer that owns the
 * deadline attributes the cause.
 */
data class CliResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val aborted: Boolean = false,
    val infraFailure: String? = null,
    val rejected: Boolean = false,
    val durationMs: Long,
    /** Per-segment breakdown reported by the CLI, when it was asked to collect it. */
    val timing: CliTiming? = null,
) {
    val isSuccess: Boolean
        get() = !rejected && infraFailure == null && !timedOut && !aborted && exitCode == 0

    /** Machine-parseable markers appended to the model-visible text. */
    fun markers(): List<String> = buildList {
        if (timedOut) add("[timed out after ${durationMs}ms]")
        if (aborted) add("[aborted]")
        if (rejected) add("[rejected: concurrency limit]")
        infraFailure?.let { add("[infra failure: $it]") }
        exitCode?.let { if (it != 0) add("[exit code: $it]") }
    }

    /**
     * Bounded text for the model: stdout + stderr + markers, truncated to
     * roughly [maxTokens] estimated tokens (head + tail, middle dropped).
     */
    fun toModelText(maxTokens: Int = 10_000): String {
        val body = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("stderr: ").append(stderr)
            }
        }
        val suffix = markers().takeIf { it.isNotEmpty() }?.let { "\n" + it.joinToString("\n") } ?: ""
        val full = body + suffix
        if (TokenEstimator.estimateTokens(full) <= maxTokens) return full

        val budgetChars = maxTokens * 4
        val headChars = budgetChars * 2 / 3
        val tailChars = budgetChars / 3
        val head = body.take(headChars)
        val tail = if (body.length > headChars) body.takeLast(tailChars) else ""
        val middle = if (tail.isEmpty()) "" else "\n…[truncated middle]…\n"
        return head + middle + tail + suffix
    }
}

/**
 * One `[timing]` footer line the CLI prints when `BROWSER4_CLI_TIMING=1`.
 *
 * Blank-line tolerant and order-independent: the footer is a diagnostics channel,
 * so a future extra field must not break the parse. Unknown keys are ignored.
 */
private val CLI_TIMING_LINE = Regex("""\[timing](.*)$""")
private val CLI_TIMING_FIELD = Regex("""([A-Za-z_]+)\s*=\s*(\d+)""")

/** The whole `[timing]` line (with its line ending), for stripping it from stderr. */
private val CLI_TIMING_FOOTER_LINE = Regex("""(?m)^[ \t]*\[timing][^\r\n]*(?:\r?\n|$)""")

/** Startup-phase trace lines (`BROWSER4_CLI_TRACE_STARTUP=1`), stripped as well. */
private val CLI_STARTUP_TRACE_LINE = Regex("""(?m)^[ \t]*\[startup][^\r\n]*(?:\r?\n|$)""")

/** Parse one `[timing] startup=…ms http=…ms server=…ms net=…ms calls=… total=…ms` line. */
internal fun parseCliTimingLine(line: String): CliTiming? {
    val payload = CLI_TIMING_LINE.find(line)?.groupValues?.get(1) ?: return null
    val fields = CLI_TIMING_FIELD.findAll(payload).associate { it.groupValues[1].lowercase() to it.groupValues[2].toLong() }
    if ("startup" !in fields || "total" !in fields) return null
    val http = fields["http"] ?: 0L
    val server = (fields["server"] ?: 0L).coerceAtMost(http)
    return CliTiming(
        startupMs = fields["startup"] ?: 0L,
        httpMs = http,
        serverMs = server,
        netMs = fields["net"] ?: (http - server),
        calls = (fields["calls"] ?: 0L).toInt(),
        totalMs = fields["total"] ?: 0L,
        // Present only when a launcher (this manager) rendered the line.
        launchMs = fields["launch"],
    )
}

/**
 * Split the CLI's machine-readable timing footer off [stderr].
 *
 * The footer is instrumentation, not command output: leaving it in would put a
 * line of internal numbers in front of the model on every `b4.run` (and a
 * failed command's stderr is shown verbatim). The trace lines are dropped for
 * the same reason.
 *
 * @return the stderr with those lines removed, and the parsed timing when present
 */
internal fun extractCliTiming(stderr: String): Pair<String, CliTiming?> {
    val hasTiming = stderr.contains("[timing]")
    val hasTrace = stderr.contains("[startup]")
    if (!hasTiming && !hasTrace) return stderr to null
    val timing = if (hasTiming) {
        CLI_TIMING_LINE.findAll(stderr).lastOrNull()?.let { parseCliTimingLine(it.value) }
    } else {
        null
    }
    // Strip by prefix, not by parse result: a future footer shape the parser does
    // not know yet is still instrumentation and must not reach the model.
    var cleaned = if (hasTiming) CLI_TIMING_FOOTER_LINE.replace(stderr, "") else stderr
    if (hasTrace) cleaned = CLI_STARTUP_TRACE_LINE.replace(cleaned, "")
    return cleaned to timing
}
