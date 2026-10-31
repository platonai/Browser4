package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Structured, countable record of every refusal — and every automatic approval — the access policy
 * produced.
 *
 * Before this object existed a denial was just prose — `"Command blocked — ..."`, `"Path not
 * resolved: ..."` — which made the whole hardening effort unobservable in three ways:
 *
 * - the model retried the same call with different syntax instead of taking an allowed route
 *   (nothing in the text said the refusal was structural),
 * - `FailureCategory.classify` matched none of the phrasings, so a refusal was reported as a
 *   generic failure rather than as a permission problem,
 * - nobody could answer "how often does this preset block the agent, and on what".
 *
 * Every refusal therefore goes through [denied], which stamps a stable machine-readable prefix
 * (`PERMISSION_DENIED | code=… | tool=… | detail=… | hint=…`), counts it per `tool:code`, and logs it
 * **once per process** so a retry loop cannot turn into a log storm.
 *
 * The refusal is returned to the caller, never thrown: the agent loop's contract is that a step can
 * fail without the run failing, and the task's final state stays whatever `system.taskComplete`
 * decides.
 *
 * The counters are process-wide; a caller that needs per-task attribution differences [snapshot]
 * across its own task boundaries.
 */
object PermissionAudit {

    /** The prefix of every refusal text; `FailureCategory` classifies on it. */
    const val DENIED = "PERMISSION_DENIED"

    /** The access preset does not grant this method at all. */
    const val CODE_TOOL_DENIED = "TOOL_DENIED"

    /** The workspace is read-only under the active preset. */
    const val CODE_READ_ONLY = "READ_ONLY"

    /** The path resolves outside the workspace while external access is off. */
    const val CODE_PATH_OUTSIDE_WORKSPACE = "PATH_OUTSIDE_WORKSPACE"

    /**
     * A path the file tools would not resolve, reported at the caller.
     *
     * The precise cause is counted under [CODE_PATH_OUTSIDE_WORKSPACE] / [CODE_SENSITIVE_PATH] by
     * the resolver, which knows it; callers only know that the path was refused.
     */
    const val CODE_PATH_DENIED = "PATH_DENIED"

    /** The path names a credential location (`.ssh`, `.aws`, `.browser4`, ...). */
    const val CODE_SENSITIVE_PATH = "SENSITIVE_PATH"

    /** The path is a governance file or lies in a protected directory. */
    const val CODE_PROTECTED_PATH = "PROTECTED_PATH"

    /** The command is not in the tier this shell runs at. */
    const val CODE_COMMAND_NOT_ALLOWED = "COMMAND_NOT_ALLOWED"

    /** An argument of the command names a credential location. */
    const val CODE_COMMAND_PATH_DENIED = "COMMAND_PATH_DENIED"

    /** No shell command may run at all under the active preset. */
    const val CODE_EXEC_DISABLED = "EXEC_DISABLED"

    /** The call would exceed the budget a capability allows (duration, bytes or call count). */
    const val CODE_BUDGET_EXCEEDED = "BUDGET_EXCEEDED"

    /**
     * A call the preset refused was granted by the automatic approval ceiling.
     *
     * Deprecated by plan P4-1 (decision #4): the ceiling was removed, so nothing produces this code
     * any more. The constant stays so historic audit snapshots keep their meaning.
     */
    @Deprecated("Automatic approval was removed in plan P4-1; operator approval records ACCESS_APPROVED")
    const val CODE_AUTO_APPROVED = "AUTO_APPROVED"

    /** A `coding.requestAccess` call created a pending request (plan P4-1). */
    const val CODE_ACCESS_REQUESTED = "ACCESS_REQUESTED"

    /** An operator (or the unattended default) granted a capability. */
    const val CODE_ACCESS_APPROVED = "ACCESS_APPROVED"

    /** An operator denied a request, or a rail refused it. */
    const val CODE_ACCESS_DENIED = "ACCESS_DENIED"

    /** An issued capability stopped applying; detected lazily on the next decision. */
    const val CODE_ACCESS_EXPIRED = "ACCESS_EXPIRED"

    /**
     * A write that landed outside the workspace capability scope, allowed only by
     * `-Dbrowser4.agent.allowExternalAccess=true`.
     *
     * Recorded as a fact ([writeEvent]), not a refusal: the call succeeded. The file tools resolve
     * the real path, so this count is trustworthy; see [CODE_SCOPE_UNCERTAIN] for the shell, where
     * the string layer cannot confirm a write.
     */
    const val CODE_OUT_OF_SCOPE_WRITE = "OUT_OF_SCOPE_WRITE"

    /** A network command that actually ran under `allowNetwork=true`; a fact, not a refusal. */
    const val CODE_NETWORK_TARGET = "NETWORK_TARGET"

    /**
     * A shell command that names a path outside the workspace while external access is enabled.
     *
     * Deliberately a *marker*, not an `OUT_OF_SCOPE_WRITE`: the shell judges arguments by spelling
     * and cannot tell whether the command reads or writes that path. The confirmed write fact comes
     * from the sandbox layer (phase C), never from guessing here.
     */
    const val CODE_SCOPE_UNCERTAIN = "SCOPE_UNCERTAIN"

    /** The execution sandbox refused the process (phase C); the string layer never produces this. */
    const val CODE_SANDBOX_DENIED = "SANDBOX_DENIED"

    /**
     * A secret-shaped variable was explicitly passed to a child process.
     *
     * Phase C strips long-lived credentials from the inherited environment; an explicit
     * passthrough still wins, because the operator or the task asked for it. This records the
     * decision so `coding.permissions` can show it instead of leaving a silent exception.
     */
    const val CODE_ENV_PASSTHROUGH = "ENV_PASSTHROUGH"

    private val logger = LoggerFactory.getLogger(PermissionAudit::class.java)

    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    /** `tool:code` keys already logged at info level — one line per refusal *shape*. */
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /**
     * Records a refusal and returns the text handed back to the model / MCP client.
     *
     * @param tool the refused entry point, e.g. `coding.write` or `shell.execute`
     * @param code one of the `CODE_*` constants
     * @param detail what exactly was refused and why
     * @param hint the allowed alternative, so the caller does not have to guess
     */
    fun denied(
        tool: String,
        code: String,
        detail: String,
        hint: String? = null,
        needed: String? = null,
    ): String {
        val key = record(tool, code)
        if (logged.add(key)) {
            logger.info("coding access denied: tool={} code={} detail={}", tool, code, detail)
        }
        return format(tool, code, detail, hint, needed)
    }

    /**
     * Records a fact about an allowed call — an out-of-scope write, a network target, a sandbox
     * denial — distinct from [denied].
     *
     * An event confirms that something *did* happen; a refusal confirms something did not. Both
     * live in [snapshot] so `coding.permissions` can report the two without inventing a second
     * counter. Logging is once per `tool:code` shape, keyed apart from refusals so a refusal and an
     * event for the same tool both get their one line.
     *
     * @param tool the entry point that produced the fact, e.g. `coding.fs`
     * @param code one of the `CODE_*` constants
     * @param detail what happened, precisely enough to audit
     */
    fun writeEvent(tool: String, code: String, detail: String) {
        val key = record(tool, code)
        if (logged.add("event:$key")) {
            logger.info("coding capability event: tool={} code={} detail={}", tool, code, detail)
        }
    }

    /**
     * Formats a refusal without counting or logging it.
     *
     * Used when the refusal was already recorded at the layer that knows the precise cause, but the
     * text is composed further up (the file system records `PATH_OUTSIDE_WORKSPACE` /
     * `SENSITIVE_PATH` in its resolver and formats at the call site).
     */
    fun format(
        tool: String,
        code: String,
        detail: String,
        hint: String? = null,
        needed: String? = null,
    ): String = buildString {
        append(DENIED)
        append(" | code=").append(code)
        append(" | tool=").append(tool)
        append(" | detail=").append(detail)
        if (!hint.isNullOrBlank()) {
            append(" | hint=").append(hint)
        }
        if (!needed.isNullOrBlank()) {
            append(" | needed=").append(needed)
        }
    }

    /** Counts a refusal whose text is produced by someone else (e.g. the shell's own message). */
    fun record(tool: String, code: String): String {
        val key = "$tool:$code"
        counts.computeIfAbsent(key) { AtomicInteger() }.incrementAndGet()
        return key
    }

    /** Refusals so far, keyed by `tool:code`. */
    fun snapshot(): Map<String, Int> = counts.entries.associate { it.key to it.value.get() }

    /** Clears the counters and the log-once memory. Tests only. */
    fun reset() {
        counts.clear()
        logged.clear()
    }
}