package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Path

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
 * @property operatorGrants live operator-issued capabilities (plan P4-1). Read on every decision so
 *   an approval issued while a task runs takes effect on the next call. The grants widen what the
 *   preset refuses **within the rails** (deny tools, protected/sensitive paths, the workspace
 *   boundary and the OS sandbox); they never reach across them.
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
    /** Operator grants in effect for this task; empty when no approval channel is attached. */
    val operatorGrants: () -> List<Capability> = { emptyList() },
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
        return presetAllows(required) || operatorCoversRequirement(required)
    }

    /**
     * Whether workspace file contents may be modified.
     *
     * Note that [AccessPreset.READ_BUILD] returns false while still allowing `mvnBuild`, which writes
     * build output such as `target/`; this predicate is about source-file mutation, not build artifacts.
     */
    fun allowsFileMutation(): Boolean =
        presetAllows(AccessPreset.WRITE) || operatorCovers(Effect.FS_WRITE)

    /** Whether the shell and arbitrary code execution are allowed. Only [AccessPreset.FULL] grants them. */
    fun allowsExec(): Boolean =
        presetAllows(AccessPreset.FULL) || operatorCovers(Effect.FS_EXEC) || operatorCovers(Effect.PROC_SPAWN)

    /**
     * Whether an active operator grant covers [effect] at [target].
     *
     * This is the target-level companion of [operatorCovers]: the latter answers "is a grant for
     * this effect active at all", this one answers "does one of them actually reach this path,
     * command or host". Delegating to [Authorizer] reuses the canonical/lexical scope matching
     * instead of implementing a second path comparison here.
     */
    fun operatorAllows(effect: Effect, target: Target): Boolean {
        val now = java.time.Instant.now()
        val active = operatorGrants().filter { it.isActiveAt(now) }
        if (active.isEmpty()) return false
        return active.any { capability ->
            val decision = Authorizer(listOf(capability)).decide(
                Request(
                    tool = "coding.policy",
                    effects = setOf(effect),
                    targets = listOf(target),
                    capabilityId = capability.id,
                ),
            )
            decision is Decision.Allow || decision is Decision.AllowWithBudget
        }
    }

    /**
     * Whether [path] may be mutated (written, deleted, copied, moved, ...).
     *
     * A preset that grants mutation keeps its historical behaviour; otherwise the concrete path
     * must be covered by an active `fs.write` operator grant. The workspace boundary and the
     * protected/sensitive paths remain enforced by the file system itself.
     */
    fun allowsMutationOf(path: Path): Boolean =
        presetAllows(AccessPreset.WRITE) || operatorAllows(Effect.FS_WRITE, Target.PathTarget(path))

    /**
     * Whether [command] may be executed.
     *
     * A preset that grants execution keeps its historical behaviour; otherwise the program must
     * be covered by an active `fs.exec` or `proc.spawn` grant. The command line is compared by
     * program name, the same granularity [AccessApprovals.commandRail] approves.
     */
    fun allowsCommandExecution(command: String): Boolean {
        if (presetAllows(AccessPreset.FULL)) return true
        val trimmed = command.trim()
        val program = trimmed.substringBefore(' ').trim('"', '\'')
        if (program.isEmpty()) return false
        return sequenceOf(program, trimmed).any { candidate ->
            operatorAllows(Effect.FS_EXEC, Target.CommandTarget(candidate)) ||
                operatorAllows(Effect.PROC_SPAWN, Target.CommandTarget(candidate))
        }
    }

    /** Whether an active operator grant carries [Effect.NET_CONNECT]; the host scope stays advisory. */
    fun hasActiveNetworkGrant(): Boolean = operatorCovers(Effect.NET_CONNECT)

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
     * `browser4.agent.access` still applies to a task that does not name a preset — only an
     * explicit per-task value replaces it.
     */
    fun withTaskOverrides(preset: AccessPreset?): AgentAccessPolicy =
        copy(preset = preset ?: this.preset)

    /**
     * A structured refusal for `domain.method`, or `null` when the call is allowed.
     *
     * Executors throw this so the CLI, the MCP layer and the completion gate can distinguish a
     * policy denial from a missing path or a bad argument. The text is composed by
     * [PermissionAudit], which also counts and logs the refusal once, so this single call site both
     * enforces and observes.
     */
    fun denial(domain: String, method: String): ToolAccessDeniedException? {
        if (isDenied(domain, method)) {
            return ToolAccessDeniedException(
                domain, method, preset,
                PermissionAudit.denied(
                    tool = "$domain.$method",
                    code = PermissionAudit.CODE_TOOL_DENIED,
                    detail = "excluded by denyTools under access preset '${preset.id}'",
                    hint = "remove '$method' from denyTools — operator approval never overrides an explicit denial",
                ),
            )
        }
        if (domain in UNGATED_DOMAINS) return null

        val required = requiredPresetOf(domain, method)
        if (presetAllows(required)) return null
        if (operatorCoversRequirement(required)) return null

        // The approval is effect-level, so name the effect an operator grant needs to carry — the
        // preset id would be rejected by `coding.requestAccess`. No JVM/CLI switch is named:
        // the refusal only says what is needed and where to ask for it (plan P4-3).
        val effect = when (required) {
            AccessPreset.READ -> Effect.FS_READ
            AccessPreset.READ_BUILD, AccessPreset.FULL -> Effect.FS_EXEC
            AccessPreset.WRITE -> Effect.FS_WRITE
        }
        val hint = "request operator approval with " +
            "coding.requestAccess(effect=\"${effect.id}\", target=\"...\") — it returns " +
            "immediately with a pending requestId, and the task keeps running meanwhile"
        return ToolAccessDeniedException(
            domain, method, preset,
            PermissionAudit.denied(
                tool = "$domain.$method",
                code = PermissionAudit.CODE_TOOL_DENIED,
                detail = "not permitted under access preset '${preset.id}'",
                hint = hint,
                needed = effect.id,
            ),
        )
    }

    /**
     * A structured refusal for a method whose *read* shape is allowed while its mutating shape is
     * not (`coding.protect` without a path lists protections, with a path changes them).
     */
    fun mutationDenial(domain: String, method: String): ToolAccessDeniedException? {
        if (allowsFileMutation()) return null
        return ToolAccessDeniedException(
            domain, method, preset,
            PermissionAudit.denied(
                tool = "$domain.$method",
                code = PermissionAudit.CODE_READ_ONLY,
                detail = "the mutating form is not permitted under access preset '${preset.id}'",
                hint = "call '$domain.$method' without a path to list the current protections " +
                    "instead, or request operator approval with " +
                    "coding.requestAccess(effect=\"fs.write\", target=\"<path>\")",
                needed = Effect.FS_WRITE.id,
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
     * Whether an operator grant covers the requirement of a refused method (plan P4-1).
     *
     * The method gate cannot see the call's target, so this is an effect-level answer: an active
     * `OPERATOR` capability for [Effect.FS_WRITE] unlocks mutating methods, one for
     * [Effect.FS_EXEC]/[Effect.PROC_SPAWN] unlocks execution. The rails (denyTools, protected and
     * sensitive paths, the workspace boundary) are still checked at the layer that knows the
     * target, and the OS sandbox still enforces the process boundary.
     */
    private fun operatorCoversRequirement(required: AccessPreset): Boolean {
        if (operatorGrants().isEmpty()) return false
        return when (required) {
            AccessPreset.READ -> operatorCovers(Effect.FS_READ)
            // A build writes build output, so an `fs.write` grant unlocks it just as `fs.exec` does.
            AccessPreset.READ_BUILD -> operatorCovers(Effect.FS_EXEC) || operatorCovers(Effect.FS_WRITE)
            AccessPreset.WRITE -> operatorCovers(Effect.FS_WRITE)
            AccessPreset.FULL -> operatorCovers(Effect.FS_EXEC) || operatorCovers(Effect.PROC_SPAWN)
        }
    }

    /** Whether an active operator grant carries [effect]. Scope is enforced by the calling layer. */
    private fun operatorCovers(effect: Effect): Boolean {
        val now = java.time.Instant.now()
        return operatorGrants().any { it.isActiveAt(now) && it.grants(effect) }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentAccessPolicy::class.java)

        /** Properties already reported as unparsable, so a bad value cannot spam the log. */
        private val warnedOnce: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** JVM property selecting the server-wide preset. */
        const val PROP_ACCESS = "browser4.agent.access"

        /**
         * JVM property selecting the unattended default (plan P4-1).
         *
         * Values: `deny` / `read` / `read-build` / `write` / `full`. Absent means `full` with outbound
         * network allowed, which is what an unattended job got implicitly before this switch existed.
         * It only applies when the task names no `access` preset and no strategy file; a watched
         * (`agent run -i` on a TTY) run never derives it.
         */
        const val PROP_ACCESS_UNATTENDED = "browser4.agent.access.unattended"

        /**
         * Deprecated JVM property; prefer [PROP_ACCESS].
         *
         * Plan P3-5 (D2) makes it advisory: it relaxes the string-layer path check (and the
         * file-tool resolution heuristic), not the OS boundary — the sandbox still decides what a
         * process may reach.
         */
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
            // Dry-run of the boundary decision: returns what the Authorizer would decide for a path
            // without executing the call. Readable by every preset so a refused model can self-diagnose.
            "explain",
            // Asks the operator for a capability; it creates a pending request, never performs the
            // requested effect. Readable by every preset — a read-only task must be able to ask.
            "requestAccess",
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
                )
            }

        /**
         * The policy an unattended job derives when the task names no preset (plan P4-1).
         *
         * Defaults to `full` with outbound network allowed: a job nobody is watching cannot answer an
         * approval prompt, so the alternative — every task failing on its first write — is worse than
         * the documented default. The rails are untouched (workspace boundary, `denyTools`,
         * protected/sensitive paths and the sandbox), execution still passes the `DEV_COMMANDS`
         * whitelist, and each derived default is audited as `ACCESS_APPROVED(source=default)`.
         * `browser4.agent.access.unattended` narrows it to `deny` / `read` / `read-build` / `write`.
         */
        val unattendedDefault: AgentAccessPolicy
            get() {
                val raw = System.getProperty(PROP_ACCESS_UNATTENDED)?.trim()?.lowercase()?.replace('_', '-')
                if (raw == "deny") {
                    // No preset expresses "grant nothing", and adding one would widen a closed enum
                    // used by the CLI and REST argument tables. A blanket denial of the workspace
                    // surface is the exact same behaviour through an existing rail; browsing stays
                    // available so `deny` means "no workspace power", not "the task cannot run".
                    return AgentAccessPolicy(
                        preset = AccessPreset.READ,
                        allowNetwork = false,
                        denyTools = setOf("coding.*", "skill.*"),
                    )
                }
                val parsed = AccessPreset.parse(raw)
                if (parsed == null && !raw.isNullOrBlank() && warnedOnce.add("$PROP_ACCESS_UNATTENDED=$raw")) {
                    logger.warn(
                        "Ignoring unknown {}='{}'; the unattended default stays 'full'", PROP_ACCESS_UNATTENDED, raw
                    )
                }
                return AgentAccessPolicy(
                    preset = parsed ?: AccessPreset.FULL,
                    allowExternalAccess = System.getProperty(PROP_ALLOW_EXTERNAL_ACCESS, "false").toBoolean(),
                    allowDestructive = System.getProperty(PROP_ALLOW_DESTRUCTIVE, "true") != "false",
                    allowNetwork = true,
                )
            }
    }
}
