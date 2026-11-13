package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Refusals produced because the shell cannot **parse** a command — not because the access policy
 * forbids it.
 *
 * The two families are deliberately kept apart, because they answer different questions and the
 * model must react differently to each:
 *
 * - a **permission** refusal ([PermissionAudit], `PERMISSION_DENIED`) says "the policy will not
 *   allow this". It is counted in `coding.permissions`, a wider preset or an operator grant can
 *   lift it, and `coding.requestAccess` is a meaningful next step;
 * - a **syntax** refusal (this object, `UNSUPPORTED_SYNTAX`) says "this shell executes one command
 *   per call; it does not parse blocks, parentheses or `else`". No preset, approval or
 *   configuration can change it, and asking for `fs.exec` would not help — the one exception is
 *   `agent run --unrestricted`, which lifts the whole syntax gate
 *   ([CodingAgentShell]'s `allowUnsupportedSyntax`): the line is handed to the platform shell
 *   verbatim, so no syntax refusal is produced and nothing is counted here.
 *
 * Reporting the second as the first was a real defect, observed in a live run (2026-10-28): the
 * model was refused `if exist …` with `code=COMMAND_NOT_ALLOWED`, treated it as a permission
 * problem, and ended up writing a Python script instead. The counters in `coding.permissions` also
 * reported policy friction that did not exist, so an operator reading them could not tell
 * "the preset is too narrow" from "we do not support this syntax".
 *
 * The envelope keeps the same `key=value | key=value` grammar as [PermissionAudit], so clients that
 * split segments keep working; only the prefix and the code are new, and neither can collide with a
 * policy code.
 */
object UnsupportedSyntax {

    /** The prefix of every syntax refusal text; `FailureCategory` classifies on it. */
    const val PREFIX = "UNSUPPORTED_SYNTAX"

    /**
     * Machine-readable code, unique to this family.
     *
     * Deliberately not one of the `PermissionAudit.CODE_*` constants: a client that switches on the
     * policy codes must not see a syntax problem arrive as a policy one.
     */
    const val CODE = "SYNTAX_NOT_SUPPORTED"

    /**
     * The hint handed to the model for a command the shell cannot parse.
     *
     * Both alternatives are real routes this codebase supports: deciding in the task (the tools are
     * the decision layer — `coding.stat` answers "does this path exist", `coding.mvnBuild`/
     * `coding.verify` return the build's exit status) and putting the block in a script that is then
     * run by path. Naming a path form (`.\run.cmd`, not `run.cmd`) is not cosmetic: a bare filename
     * is validated as a program name, which fails.
     */
    const val HINT =
        "decide in the task instead (coding.stat reports whether a path exists, " +
            "coding.mvnBuild/coding.verify return the build's exit status), or put the block in a " +
            "script file and run it by path (e.g. .\\run.cmd on Windows, ./run.sh elsewhere)"

    private val logger = LoggerFactory.getLogger(UnsupportedSyntax::class.java)

    /** `tool` → refusals so far; separate from [PermissionAudit]'s policy counters on purpose. */
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** Tools already logged — one line per tool, like [PermissionAudit], so a retry cannot spam. */
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /**
     * Counts one syntax refusal and returns the text handed to the model.
     *
     * @param tool the entry point, e.g. `coding.shell`
     * @param detail what shape was refused and why the shell cannot run it
     * @param hint the supported alternatives; defaults to [HINT]
     */
    fun refused(tool: String, detail: String, hint: String = HINT): String {
        record(tool)
        if (logged.add(tool)) {
            logger.info("coding syntax unsupported: tool={} detail={}", tool, detail)
        }
        return format(tool, detail, hint)
    }

    /** Formats a refusal without counting or logging it; [hint] defaults to [HINT]. */
    fun format(tool: String, detail: String, hint: String? = HINT): String = buildString {
        append(PREFIX)
        append(" | code=").append(CODE)
        append(" | tool=").append(tool)
        append(" | detail=").append(detail)
        if (!hint.isNullOrBlank()) {
            append(" | hint=").append(hint)
        }
    }

    /** Counts a refusal whose text is produced by someone else. */
    fun record(tool: String): String {
        counts.computeIfAbsent(tool) { AtomicInteger() }.incrementAndGet()
        return tool
    }

    /** Syntax refusals so far, keyed by tool. Never part of [PermissionAudit.snapshot]. */
    fun snapshot(): Map<String, Int> = counts.entries.associate { it.key to it.value.get() }

    /** Clears the counters and the log-once memory. Tests only. */
    fun reset() {
        counts.clear()
        logged.clear()
    }
}
