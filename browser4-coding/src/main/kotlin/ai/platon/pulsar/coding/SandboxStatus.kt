package ai.platon.pulsar.coding

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
 * The `off` value is deliberate: [ExecutionSandboxes.configuredId] already defaults to `off`, and
 * [ai.platon.pulsar.coding.sandbox.SandboxDefaults] publishes the chosen backend only when one is
 * actually in force.
 */
internal object SandboxStatus {

    /** The backend id in force right now (`off` when none). */
    val effectiveId: String
        get() = runCatching { ExecutionSandboxes.configuredId() }
            .getOrDefault("off")
            .ifBlank { "off" }

    /** True when a denial has an OS layer behind it. */
    val osBoundaryActive: Boolean get() = effectiveId != "off"

    /**
     * One clause for a refusal `hint`: whether the OS layer backs the check.
     *
     * Always says which layer is speaking, so the model can tell "the tool refused" from "the OS
     * refused" — the two need different follow-ups (`coding.requestAccess` vs. `--add-dir`).
     */
    fun boundaryClause(): String = if (osBoundaryActive) {
        "the OS sandbox ($effectiveId) enforces the boundary"
    } else {
        "no OS sandbox is active (${ExecutionSandboxes.PROP_SANDBOX}=off), so this tool layer is the " +
            "only thing that refused the access"
    }
}
