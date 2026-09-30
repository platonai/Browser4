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
 */
data class CliRunRequest(
    val args: String,
    val timeoutSeconds: Long? = null,
    val workingDir: Path? = null,
    val sessionId: String? = null,
    val taskId: String? = null,
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
)

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
