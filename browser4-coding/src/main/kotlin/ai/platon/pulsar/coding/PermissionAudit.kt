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

    /** A call the preset refused was granted by the automatic approval ceiling. */
    const val CODE_AUTO_APPROVED = "AUTO_APPROVED"

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
    fun denied(tool: String, code: String, detail: String, hint: String? = null): String {
        val key = record(tool, code)
        if (logged.add(key)) {
            logger.info("coding access denied: tool={} code={} detail={}", tool, code, detail)
        }
        return format(tool, code, detail, hint)
    }

    /**
     * Records an automatic approval: a call the preset refused, granted by the ceiling.
     *
     * Approvals are counted under [CODE_AUTO_APPROVED] and logged once per `tool` shape, so an
     * "allow everything" task still leaves a trace of *which* capabilities it actually used. The
     * caller proceeds normally; nothing is returned because the call is allowed.
     *
     * @param tool the approved entry point, e.g. `coding.shell`
     * @param required the narrowest preset that grants the call
     * @param ceiling the ceiling that approved it
     */
    fun approved(tool: String, required: AccessPreset, ceiling: AccessPreset) {
        val key = record(tool, CODE_AUTO_APPROVED)
        if (logged.add(key)) {
            logger.info(
                "coding access auto-approved: tool={} required={} ceiling={}", tool, required.id, ceiling.id
            )
        }
    }

    /**
     * Formats a refusal without counting or logging it.
     *
     * Used when the refusal was already recorded at the layer that knows the precise cause, but the
     * text is composed further up (the file system records `PATH_OUTSIDE_WORKSPACE` /
     * `SENSITIVE_PATH` in its resolver and formats at the call site).
     */
    fun format(tool: String, code: String, detail: String, hint: String? = null): String = buildString {
        append(DENIED)
        append(" | code=").append(code)
        append(" | tool=").append(tool)
        append(" | detail=").append(detail)
        if (!hint.isNullOrBlank()) {
            append(" | hint=").append(hint)
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