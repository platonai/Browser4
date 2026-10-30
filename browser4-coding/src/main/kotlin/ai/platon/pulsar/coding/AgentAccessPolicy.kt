package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory

/**
 * Access presets for the coding tools exposed to the built-in agent.
 *
 * The presets are ordered from most to least permissive; [FULL] is the default and preserves the
 * behaviour that existed before the policy object was introduced.
 */
enum class AccessPreset(val id: String) {
    /** Read the workspace, mutate files, run the shell and execute arbitrary code. */
    FULL("full"),

    /** Read and mutate workspace files; no shell and no code execution. */
    WRITE("write"),

    /** Read the workspace and run the project build (`mvnBuild`/`verify`/`validate`); no file mutation. */
    READ_BUILD("read-build"),

    /** Read only. */
    READ("read");

    companion object {
        /**
         * Parses a preset id, accepting `read-build` and `read_build` in any casing.
         * Returns `null` for blank or unknown values so callers can decide whether to warn.
         */
        fun parse(raw: String?): AccessPreset? {
            val normalized = raw?.trim()?.lowercase()?.replace('_', '-') ?: return null
            if (normalized.isEmpty()) return null
            return values().firstOrNull { it.id == normalized }
        }
    }
}

/**
 * How a single `coding.*` method is gated by an [AgentAccessPolicy].
 *
 * Every registered method must fall into exactly one gate; unclassified methods are treated as
 * [AccessPreset.FULL]-only so that newly added tools fail closed instead of silently widening
 * the surface of the restricted presets.
 */
enum class ToolGate {
    /** No side effects, no workspace mutation. Allowed by every preset. */
    READ,

    /** Builds the project (`mvnBuild`/`verify`/`validate`). Allowed by every preset but [AccessPreset.READ]. */
    BUILD,

    /** Mutates workspace files. Allowed when [AgentAccessPolicy.allowsFileMutation] is true. */
    MUTATE,

    /** Runs the shell or arbitrary code. Allowed only by [AccessPreset.FULL]. */
    EXEC,
}

/**
 * A structured refusal produced by an [AgentAccessPolicy].
 *
 * The executors throw this instead of returning free text so the CLI, the MCP layer and the
 * completion gate can tell "the policy denied this" apart from "the path does not exist".
 *
 * @property domain tool domain of the refused call (`coding`)
 * @property method method name of the refused call
 * @property preset the preset that produced the refusal
 */
class ToolAccessDeniedException(
    val domain: String,
    val method: String,
    val preset: AccessPreset,
    message: String,
) : RuntimeException(message)

/**
 * The single source of truth for what the built-in coding agent may read, write and execute.
 *
 * The policy is consumed by [CodingComponents] (file system and shell construction) and by the
 * tool executors, so that the file-system layer, the shell layer, the tool registry filter and the
 * MCP direct-call path all agree on the same answer.
 *
 * Only the built-in browsing domains are exempt from the presets; `coding` and `skill` are gated
 * per method, and any other domain (plugins, future tools) requires [AccessPreset.FULL] so that a
 * new surface cannot be reached by a restricted preset by default.
 *
 * @property preset the overall access preset
 * @property allowExternalAccess when true, file tools may resolve paths outside the workspace
 * @property allowDestructive when false, `delete`/`copy`/`move` are refused; this is **not** a read-only switch
 * @property allowNetwork when true, the shell may use network-capable commands
 * @property denyTools tool patterns denied regardless of preset; supports `*`, `domain.*`, `domain.method` and bare `method`
 * @property protectedFiles workspace-relative paths that must never be written or deleted
 * @property autoApproveUpTo automatic approval ceiling: a call the preset refuses is approved (and
 *   audited) when the preset that would grant it is no wider than this one. `null` (the default)
 *   keeps automatic approval off. Explicit `denyTools`, protected paths and sensitive paths are
 *   never auto-approved — they are rails, not capabilities, so approval cannot reach across them.
 */
data class AgentAccessPolicy(
    val preset: AccessPreset = AccessPreset.FULL,
    val allowExternalAccess: Boolean = false,
    val allowDestructive: Boolean = true,
    val allowNetwork: Boolean = false,
    val denyTools: Set<String> = emptySet(),
    val protectedFiles: Set<String> = CodingAgentFileSystem.DEFAULT_PROTECTED_FILES,
    /** Workspace-relative directories whose whole subtree must never be modified. */
    val protectedDirs: Set<String> = CodingAgentFileSystem.DEFAULT_PROTECTED_DIRS,
    /** Locations outside the workspace that must never be read or written. */
    val sensitivePaths: Set<String> = CodingAgentFileSystem.DEFAULT_SENSITIVE_PATHS,
    /** Automatic approval ceiling, or `null` when automatic approval is off. */
    val autoApproveUpTo: AccessPreset? = null,
) {

    /**
     * Whether `domain.method` may be called under this policy.
     *
     * Only [UNGATED_DOMAINS] — the built-in browsing/tool surface — are exempt; every other
     * domain is gated by [requiredPresetOf], and an unclassified domain or method requires
     * [AccessPreset.FULL] so a newly added tool fails closed.
     */
    fun allowsTool(domain: String, method: String): Boolean {
        if (isDenied(domain, method)) {
            return false
        }
        if (domain in UNGATED_DOMAINS) {
            return true
        }
        val required = requiredPresetOf(domain, method)
        return presetAllows(required) || autoApproves(required)
    }

    /**
     * Whether workspace file contents may be modified.
     *
     * Note that [AccessPreset.READ_BUILD] returns false while still allowing `mvnBuild`, which writes
     * build output such as `target/`; this predicate is about source-file mutation, not build artifacts.
     */
    fun allowsFileMutation(): Boolean =
        presetAllows(AccessPreset.WRITE) || autoApproves(AccessPreset.WRITE)

    /** Whether the shell and arbitrary code execution are allowed. Only [AccessPreset.FULL] grants them. */
    fun allowsExec(): Boolean = presetAllows(AccessPreset.FULL) || autoApproves(AccessPreset.FULL)

    /** Returns the gate for a `coding.*` method, or `null` when the method is not in the matrix. */
    fun gateOf(method: String): ToolGate? = when (method) {
        in EXEC_METHODS -> ToolGate.EXEC
        in MUTATE_METHODS -> ToolGate.MUTATE
        in BUILD_METHODS -> ToolGate.BUILD
        in READ_METHODS -> ToolGate.READ
        else -> null
    }

    /**
     * Returns the gate for `domain.method`, or `null` when the domain is ungated or the method
     * is not classified (which [requiredPresetOf] turns into [AccessPreset.FULL]).
     *
     * `coding` keeps its own matrix; `skill` distinguishes discovery/documentation from
     * execution and installation; every other domain is fail-closed unless [UNGATED_DOMAINS].
     */
    fun gateOf(domain: String, method: String): ToolGate? = when (domain) {
        CODING_DOMAIN -> gateOf(method)
        SKILL_DOMAIN -> SKILL_METHOD_GATES[method]
        else -> null
    }

    /**
     * Applies per-task overrides on top of this (usually server-wide) policy.
     *
     * An absent override keeps this policy's own value, so an operator's
     * `browser4.agent.access` / `browser4.agent.autoApprove` still applies to a task that names
     * neither — only an explicit per-task value replaces it.
     */
    fun withTaskOverrides(preset: AccessPreset?, autoApproveUpTo: AccessPreset?): AgentAccessPolicy =
        copy(
            preset = preset ?: this.preset,
            autoApproveUpTo = autoApproveUpTo ?: this.autoApproveUpTo,
        )

    /**
     * A structured refusal for `domain.method`, or `null` when the call is allowed.
     *
     * Executors throw this so the CLI, the MCP layer and the completion gate can distinguish a
     * policy denial from a missing path or a bad argument. The text is composed by
     * [PermissionAudit], which also counts and logs the refusal once, so this single call site both
     * enforces and observes.
     *
     * It is also the one place an automatic approval is recorded: the preset check comes first, so
     * a call the preset already grants is never audited, and a call that passes only because of
     * [autoApproveUpTo] is audited exactly once per tool shape.
     */
    fun denial(domain: String, method: String): ToolAccessDeniedException? {
        if (isDenied(domain, method)) {
            return ToolAccessDeniedException(
                domain, method, preset,
                PermissionAudit.denied(
                    tool = "$domain.$method",
                    code = PermissionAudit.CODE_TOOL_DENIED,
                    detail = "excluded by denyTools under access preset '${preset.id}'",
                    hint = "remove '$method' from denyTools — automatic approval never overrides an explicit denial",
                ),
            )
        }
        if (domain in UNGATED_DOMAINS) return null

        val required = requiredPresetOf(domain, method)
        if (presetAllows(required)) return null
        if (autoApproves(required)) {
            PermissionAudit.approved("$domain.$method", required, autoApproveUpTo!!)
            return null
        }

        // Name only the presets that actually grant it: a skill or shell call needs `full`, so
        // "access=write" would send the model down a dead end.
        val widening = when (required) {
            AccessPreset.READ -> "access=full, access=write, access=read-build or access=read"
            AccessPreset.READ_BUILD -> "access=full, access=write or access=read-build"
            AccessPreset.WRITE -> "access=full or access=write"
            AccessPreset.FULL -> "access=full"
        }
        val hint = "re-run with $widening to enable it, let the task auto-approve it with " +
            "autoApprove=${required.id}, or continue with a tool this preset allows"
        return ToolAccessDeniedException(
            domain, method, preset,
            PermissionAudit.denied(
                tool = "$domain.$method",
                code = PermissionAudit.CODE_TOOL_DENIED,
                detail = "not permitted under access preset '${preset.id}'",
                hint = hint,
            ),
        )
    }

    /**
     * A structured refusal for a method whose *read* shape is allowed while its mutating shape is
     * not (`coding.protect` without a path lists protections, with a path changes them).
     */
    fun mutationDenial(domain: String, method: String): ToolAccessDeniedException? {
        if (allowsFileMutation()) {
            // The mutation is granted by the approval ceiling rather than by the preset — record it
            // like every other auto-approved capability, otherwise `protect(path=…)` would be the
            // one widening that leaves no trace.
            if (!presetAllows(AccessPreset.WRITE)) {
                PermissionAudit.approved("$domain.$method", AccessPreset.WRITE, autoApproveUpTo!!)
            }
            return null
        }
        return ToolAccessDeniedException(
            domain, method, preset,
            PermissionAudit.denied(
                tool = "$domain.$method",
                code = PermissionAudit.CODE_READ_ONLY,
                detail = "the mutating form is not permitted under access preset '${preset.id}' — " +
                    "workspace mutation requires access=full or access=write",
                hint = "call '$domain.$method' without a path to list the current protections instead",
            ),
        )
    }

    private fun isDenied(domain: String, method: String): Boolean = denyTools.any { pattern ->
        pattern == "*" || pattern == "$domain.$method" || pattern == "$domain.*" || pattern == method
    }

    /**
     * The narrowest preset that grants `domain.method`.
     *
     * Unclassified domains and methods map to [AccessPreset.FULL] so a newly added tool fails
     * closed — and therefore can never be reached by automatic approval either.
     */
    private fun requiredPresetOf(domain: String, method: String): AccessPreset = when (gateOf(domain, method)) {
        ToolGate.READ -> AccessPreset.READ
        ToolGate.BUILD -> AccessPreset.READ_BUILD
        ToolGate.MUTATE -> AccessPreset.WRITE
        ToolGate.EXEC, null -> AccessPreset.FULL
    }

    /** Whether the preset alone grants a method whose requirement is [required]. */
    private fun presetAllows(required: AccessPreset): Boolean = when (required) {
        AccessPreset.READ -> true
        AccessPreset.READ_BUILD -> preset != AccessPreset.READ
        AccessPreset.WRITE -> preset == AccessPreset.FULL || preset == AccessPreset.WRITE
        AccessPreset.FULL -> preset == AccessPreset.FULL
    }

    /**
     * Whether the automatic approval ceiling covers [required].
     *
     * Presets are declared from most to least permissive, so a requirement is covered when its
     * ordinal is at least the ceiling's (`FULL=0`, `WRITE=1`, `READ_BUILD=2`, `READ=3`).
     */
    private fun autoApproves(required: AccessPreset): Boolean {
        val ceiling = autoApproveUpTo ?: return false
        return required.ordinal >= ceiling.ordinal
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentAccessPolicy::class.java)

        /** Properties already reported as unparsable, so a bad value cannot spam the log. */
        private val warnedOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** JVM property selecting the server-wide preset. */
        const val PROP_ACCESS = "browser4.agent.access"

        /**
         * JVM property selecting the server-wide automatic approval ceiling.
         *
         * Absent or blank leaves automatic approval off, which is the default: approval has to be
         * something the operator opted into, never something a typo switches on.
         */
        const val PROP_AUTO_APPROVE = "browser4.agent.autoApprove"

        /** Legacy JVM property; prefer [PROP_ACCESS]. */
        const val PROP_ALLOW_EXTERNAL_ACCESS = "browser4.agent.allowExternalAccess"

        /** Legacy JVM property; prefer [PROP_ACCESS]. */
        const val PROP_ALLOW_DESTRUCTIVE = "browser4.agent.allowDestructive"

        private const val CODING_DOMAIN = "coding"

        private const val SKILL_DOMAIN = "skill"

        /**
         * Domains the presets never gate: the built-in browsing and agent surface.
         *
         * The presets exist to bound *workspace* power (files, shell, code execution) and the
         * skill surface that can execute scripts; a browsing tool stays available under every
         * preset, otherwise `agent run --access read` could not browse at all.
         */
        private val UNGATED_DOMAINS = setOf(
            "tab", "browser", "agent", "b4", "system", "memory", "batch", "driverEx", "experience",
        )

        /**
         * `skill.*` methods by gate.
         *
         * Listing, inspecting, activation and documentation are reads; running, including a skill's
         * own scripts, and installing/uninstalling/reloading skills all execute or rewrite files
         * outside the workspace, so they require [AccessPreset.FULL] like the shell does.
         */
        private val SKILL_METHOD_GATES: Map<String, ToolGate> = mapOf(
            "list" to ToolGate.READ,
            "info" to ToolGate.READ,
            "activate" to ToolGate.READ,
            "readDocumentation" to ToolGate.READ,
            "readReference" to ToolGate.READ,
            "run" to ToolGate.EXEC,
            "runScript" to ToolGate.EXEC,
            "install" to ToolGate.EXEC,
            "uninstall" to ToolGate.EXEC,
            "reload" to ToolGate.EXEC,
        )

        /** `coding.*` methods whose only effect is to read data or metadata. */
        private val READ_METHODS = setOf(
            "read", "readLines", "checksum", "listDir", "glob", "grep", "stat", "diff",
            "changeSummary", "languages", "workspaceRoot",
            "symbols", "references", "impact", "moduleGraph", "diagnostics", "lspServers",
            "ktSymbols", "ktReferences", "ktInheritance",
            "toolsDetect", "projectType", "runCodeLanguages", "classInfo",
            "tokenStats", "estimateTokens",
            // Reports the active policy; readable by every preset — it is the answer a model needs
            // *after* a refusal, so gating it would leave it guessing.
            "permissions",
            // `protect` without a `path` only lists the current protections; the mutating form is
            // additionally gated by allowsFileMutation() at the executor level.
            "protect",
        )

        /** Build entry points; they run the project build and therefore write build output. */
        private val BUILD_METHODS = setOf("mvnBuild", "verify", "validate")

        /** Methods that modify workspace files. */
        private val MUTATE_METHODS = setOf(
            "write", "append", "replace", "replaceRegex", "editLines", "insertAfter",
            "delete", "mkdir", "copy", "move", "revert",
            "scaffold", "scaffoldToDir", "scaffoldFromExample", "scaffoldFlow",
            "devTask", "resumeDevTask", "trapCheck",
        )

        /** Methods that spawn a shell or execute arbitrary code. */
        private val EXEC_METHODS = setOf(
            "shell", "shellOutput", "shellStatus", "shellList", "shellSetEnv", "runCode",
        )

        /**
         * The server-wide default policy, resolved from JVM properties on every access so that
         * runtime reconfiguration (and tests) behave predictably.
         *
         * Precedence: `browser4.agent.access` > legacy `browser4.agent.allowExternalAccess` /
         * `browser4.agent.allowDestructive` > [AgentAccessPolicy] defaults (`FULL`, no external
         * access, destructive operations allowed).
         */
        val serverDefault: AgentAccessPolicy
            get() {
                val rawAccess = System.getProperty(PROP_ACCESS)
                var preset = AccessPreset.FULL
                if (!rawAccess.isNullOrBlank()) {
                    val parsed = AccessPreset.parse(rawAccess)
                    if (parsed != null) {
                        preset = parsed
                    } else {
                        // Warn once per bad value, not once per read: this getter runs on every
                        // task start and every `coding.permissions` call.
                        if (warnedOnce.add("$PROP_ACCESS=$rawAccess")) {
                            logger.warn(
                                "Ignoring unknown {}='{}'; falling back to '{}'", PROP_ACCESS, rawAccess, preset.id
                            )
                        }
                    }
                }
                return AgentAccessPolicy(
                    preset = preset,
                    allowExternalAccess = System.getProperty(PROP_ALLOW_EXTERNAL_ACCESS, "false").toBoolean(),
                    allowDestructive = System.getProperty(PROP_ALLOW_DESTRUCTIVE, "true") != "false",
                    autoApproveUpTo = resolveAutoApproveCeiling(System.getProperty(PROP_AUTO_APPROVE)),
                )
            }

        /** Parses the approval ceiling, warning about unknown values instead of widening silently. */
        private fun resolveAutoApproveCeiling(raw: String?): AccessPreset? {
            if (raw.isNullOrBlank()) return null
            val parsed = AccessPreset.parse(raw)
            if (parsed == null && warnedOnce.add("$PROP_AUTO_APPROVE=$raw")) {
                logger.warn(
                    "Ignoring unknown {}='{}'; automatic approval stays off", PROP_AUTO_APPROVE, raw
                )
            }
            return parsed
        }
    }
}
