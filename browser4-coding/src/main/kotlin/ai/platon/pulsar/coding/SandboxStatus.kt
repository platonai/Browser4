package ai.platon.pulsar.coding

import ai.platon.pulsar.coding.sandbox.ExecutionSandbox
import ai.platon.pulsar.coding.sandbox.ExecutionSandboxes

/**
 * The **effective** enforcement layer, as the model is told about it.
 *
 * The string layer (`coding.shell`'s path guard, `coding.fs`'s boundary) is a fast-fail check; the
 * thing that actually confines a process is [ExecutionSandboxes]. Those two are only the same thing
 * when a backend is configured — and on an installation where `browser4.agent.sandbox` resolves to
 * `off` they are very much not: `python -c`, `powershell -File` and `%VAR%` expansions walk straight
 * out of the workspace (measured on run `966a5f2e`, 2026-11-16).
 *
 * Every model-facing refusal that mentions the boundary asks this object instead of restating the
 * promise, so a refusal on an unprotected installation cannot read as if an OS boundary were behind
 * it. Keeping one source also keeps the shell, the file tools and the policy log in step.
 *
 * **Which backend is in force is a per-task fact, not a property.** `browser4.agent.sandbox` is
 * only the operator's override; with it unset, the task's [IsolationProfile.backendChain] decides
 * (`CodingComponents.sandbox(policy)`), so a coding task on this machine really runs under
 * `windows-low-il` while the bare property still reads `off`. Reporting the property made every
 * refusal claim "no OS sandbox is active" next to a child that was demonstrably low-integrity
 * (run `4125acaa`, 2026-11-19): the model then treated a hard OS refusal as advice. Callers that
 * hold the task's [ExecutionSandbox] therefore pass it in; the no-argument forms remain only as a
 * last-resort view of the process-wide property.
 *
 * The `off` value is deliberate: [ExecutionSandboxes.configuredId] already defaults to `off`, and
 * [ai.platon.pulsar.coding.sandbox.SandboxDefaults] publishes the chosen backend only when one is
 * actually in force.
 */
internal object SandboxStatus {

    /** The process-wide backend id (`off` when no operator override is set). */
    val effectiveId: String
        get() = runCatching { ExecutionSandboxes.configuredId() }
            .getOrDefault("off")
            .ifBlank { "off" }

    /**
     * The backend id a task holding [sandbox] actually runs under.
     *
     * `null` means the caller has no task-scoped seam (a unit test, an operator log line); the
     * process-wide property is then the best answer available.
     */
    fun effectiveIdOf(sandbox: ExecutionSandbox?): String =
        sandbox?.id?.takeIf { it.isNotBlank() } ?: effectiveId

    /** True when a denial has an OS layer behind it. */
    val osBoundaryActive: Boolean get() = effectiveId != "off"

    /** True when a denial for a task holding [sandbox] has an OS layer behind it. */
    fun osBoundaryActive(sandbox: ExecutionSandbox?): Boolean = effectiveIdOf(sandbox) != "off"

    /**
     * One clause for a refusal `hint`: whether the OS layer backs the check.
     *
     * Always says which layer is speaking, so the model can tell "the tool refused" from "the OS
     * refused" — the two need different follow-ups (`coding.requestAccess` vs. `--add-dir`).
     */
    fun boundaryClauseFor(id: String): String {
        // Backend ids are lowercase by contract (`ExecutionSandboxes.of` normalizes), but an
        // operator-typed property or a test string can arrive shouted; the clause must not print
        // "the OS sandbox (OFF) enforces the boundary".
        val effective = id.trim().lowercase().ifBlank { "off" }
        return if (effective != "off") {
            "the OS sandbox ($effective) enforces the boundary"
        } else {
            "no OS sandbox is active (${ExecutionSandboxes.PROP_SANDBOX}=off), so this tool layer is the " +
                "only thing that refused the access"
        }
    }

    /** [boundaryClauseFor] for a task holding [sandbox]. */
    fun boundaryClause(sandbox: ExecutionSandbox?): String = boundaryClauseFor(effectiveIdOf(sandbox))

    /** [boundaryClauseFor] from the process-wide property alone. */
    fun boundaryClause(): String = boundaryClauseFor(effectiveId)
}
