package ai.platon.pulsar.coding

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * One call that needs a decision: which tool, which effects, on what.
 *
 * @property tool the entry point, e.g. `coding.write` — used only for reporting
 * @property effects the effects the call will perform
 * @property targets the locations/files/hosts the effects apply to
 * @property cost what the call is about to spend; default [Cost.NONE]
 * @property capabilityId when set, the decision is made against that specific grant only. This is
 *   how a shared [Authorizer] serves layers whose scopes differ in semantics (the file system
 *   follows links; the shell judges paths by spelling): each layer pins its own grant so a wider
 *   grant from another layer cannot accidentally cover its request.
 */
data class Request(
    val tool: String,
    val effects: Set<Effect>,
    val targets: List<Target> = emptyList(),
    val cost: Cost = Cost.NONE,
    val capabilityId: CapabilityId? = null,
)

/**
 * The only answer the execution layer acts on.
 *
 * Every [Deny] carries a [CapabilityRequest] so the caller can tell the model what to ask for
 * rather than only that it was refused.
 */
sealed interface Decision {

    /** The call is permitted. */
    data object Allow : Decision

    /** The call is permitted and consumes [cost] from [capability]'s budget. */
    data class AllowWithBudget(val capability: CapabilityId, val cost: Cost) : Decision

    /**
     * The call is refused.
     *
     * @property code a [PermissionAudit] code, so refusals stay machine-readable
     * @property effect the first effect that could not be satisfied
     * @property target the first target that could not be satisfied, or the tool name
     * @property needed what the caller must obtain to make the call succeed
     */
    data class Deny(
        val code: AuditCode,
        val effect: Effect,
        val target: String,
        val needed: CapabilityRequest,
    ) : Decision
}

/**
 * The single decision point for every `coding.*` effect.
 *
 * The class is a pure function of its inputs: capabilities at construction, request plus consumed
 * budget at call time. It reads no JVM property and no environment variable, which is what makes
 * `coding.explain` a real dry run and lets a decision be replayed from an audit record.
 *
 * Matching rules:
 * - a request must be covered by **one** capability (all effects and all targets); presets derive
 *   such capabilities, and requiring one grant keeps budget accounting unambiguous;
 * - paths are compared on their canonical form ([canonicalPath]), so junctions and symlinks are
 *   judged by where they really point, not by how they are spelled;
 * - a scope root covers itself and every descendant; there is no way for a scope to broaden
 *   itself through glob expansion;
 * - an effect with no matching target is refused (fail closed), never treated as "no target".
 */
class Authorizer(
    capabilities: List<Capability>,
    private val clock: () -> Instant = Instant::now,
) {

    private val ordered: List<Capability> = capabilities.sortedBy { it.id.value }

    // Each capability carries its own scope, and canonicalization is a per-scope property
    // (the file system follows links; the shell judges paths by how they are spelled).
    private val scopes: Map<CapabilityId, CanonicalScope> =
        ordered.associate { it.id to CanonicalScope.of(it.scope) }

    /**
     * Decides [request] against the capabilities this instance was built with.
     *
     * @param request what the tool wants to do
     * @param consumed budget already spent per capability, usually from the task's audit trail
     */
    fun decide(request: Request, consumed: Map<CapabilityId, Cost> = emptyMap()): Decision {
        if (request.effects.isEmpty()) return Decision.Allow

        val now = clock()
        val effects = request.effects.sortedBy { it.id }
        val needed = CapabilityRequest(request.effects, request.targets)

        // When the caller pins a grant, judge the request against that grant alone.
        val grants = if (request.capabilityId != null) {
            ordered.filter { it.id == request.capabilityId }
        } else {
            ordered
        }

        // 1. No active grant even mentions the effect.
        val ungranted = effects.firstOrNull { effect ->
            grants.none { it.grants(effect) && it.isActiveAt(now) }
        }
        if (ungranted != null) {
            return Decision.Deny(
                code = PermissionAudit.CODE_TOOL_DENIED,
                effect = ungranted,
                target = targetLabel(request, ungranted),
                needed = needed,
            )
        }

        // 2. A path/command/network effect without a target cannot be judged, so it is refused
        //    instead of being treated as "no target to check".
        val missingTarget = effects.firstOrNull { targetsFor(it, request.targets).isEmpty() }
        if (missingTarget != null) {
            return Decision.Deny(
                code = PermissionAudit.CODE_TOOL_DENIED,
                effect = missingTarget,
                target = request.tool,
                needed = needed,
            )
        }

        // 3. A grant exists for the effect, but none covers where the call wants to act.
        val outOfScope = effects.firstOrNull { effect ->
            grants.none { cap ->
                cap.grants(effect) && cap.isActiveAt(now) && covers(cap, effect, request.targets)
            }
        }
        if (outOfScope != null) {
            return Decision.Deny(
                code = scopeCode(outOfScope),
                effect = outOfScope,
                target = targetLabel(request, outOfScope),
                needed = needed,
            )
        }

        // 4. One grant must cover the whole request, not one effect at a time.
        val candidates = grants.filter { cap ->
            cap.isActiveAt(now) &&
                effects.all { cap.grants(it) } &&
                effects.all { covers(cap, it, request.targets) }
        }
        if (candidates.isEmpty()) {
            return Decision.Deny(
                code = PermissionAudit.CODE_TOOL_DENIED,
                effect = effects.first(),
                target = targetLabel(request, effects.first()),
                needed = needed,
            )
        }

        // 5. The first covering grant that still has budget wins; order is by id, so a decision is
        //    reproducible across runs.
        val withinBudget = candidates.firstOrNull { cap ->
            val projected = (consumed[cap.id] ?: Cost.NONE) + request.cost
            !projected.exceeds(cap.limits)
        }
        if (withinBudget == null) {
            return Decision.Deny(
                code = PermissionAudit.CODE_BUDGET_EXCEEDED,
                effect = effects.first(),
                target = targetLabel(request, effects.first()),
                needed = needed,
            )
        }

        return if (withinBudget.limits.isUnbounded) {
            Decision.Allow
        } else {
            Decision.AllowWithBudget(withinBudget.id, request.cost)
        }
    }

    /** Whether [cap] grants [effect] at every target the effect applies to. */
    private fun covers(cap: Capability, effect: Effect, targets: List<Target>): Boolean {
        val scope = scopes.getValue(cap.id)
        val relevant = targetsFor(effect, targets)
        if (relevant.isEmpty()) return false
        return relevant.all { scope.covers(effect, it) }
    }

    /** The targets of [effect]'s kind; an empty list is a fail-closed condition. */
    private fun targetsFor(effect: Effect, targets: List<Target>): List<Target> = when (effect) {
        Effect.FS_READ, Effect.FS_WRITE, Effect.SECRET_READ -> targets.filterIsInstance<Target.PathTarget>()
        Effect.NET_CONNECT -> targets.filterIsInstance<Target.HostTarget>()
        Effect.FS_EXEC, Effect.PROC_SPAWN -> targets.filterIsInstance<Target.CommandTarget>()
    }

    private fun targetLabel(request: Request, effect: Effect): String =
        targetsFor(effect, request.targets).firstOrNull()?.describe() ?: request.tool

    /** Maps an out-of-scope effect to the code the audit layer already understands. */
    private fun scopeCode(effect: Effect): AuditCode = when (effect) {
        Effect.FS_READ, Effect.FS_WRITE, Effect.SECRET_READ -> PermissionAudit.CODE_PATH_OUTSIDE_WORKSPACE
        Effect.FS_EXEC, Effect.PROC_SPAWN -> PermissionAudit.CODE_COMMAND_NOT_ALLOWED
        Effect.NET_CONNECT -> PermissionAudit.CODE_TOOL_DENIED
    }

    /** A [Scope] with every path canonicalized once, so matching does not re-resolve roots. */
    private data class CanonicalScope(
        val readPaths: List<Path>,
        val writePaths: List<Path>,
        val hosts: Set<String>,
        val commands: Set<String>,
        val launcherBasenames: Set<String>,
        val executableExtensions: Set<String>,
        val canonicalize: Boolean,
    ) {
        fun covers(effect: Effect, target: Target): Boolean = when (effect) {
            Effect.FS_READ, Effect.SECRET_READ ->
                target is Target.PathTarget && (within(target.path, readPaths) || isLauncher(target.path))
            Effect.FS_WRITE ->
                target is Target.PathTarget && (within(target.path, writePaths) || isLauncher(target.path))
            Effect.NET_CONNECT ->
                target is Target.HostTarget && target.normalized in hosts
            Effect.FS_EXEC, Effect.PROC_SPAWN ->
                target is Target.CommandTarget && target.normalized in commands
        }

        private fun within(target: Path, roots: List<Path>): Boolean {
            if (roots.isEmpty()) return false
            val comparable = if (canonicalize) canonicalPath(target) else target.toAbsolutePath().normalize()
            return roots.any { comparable.startsWith(it) }
        }

        /** A path whose filename is a known launcher or has an executable extension is treated as
         *  in-scope regardless of where it lives — tools, not data (B3). */
        private fun isLauncher(path: Path): Boolean {
            if (launcherBasenames.isEmpty() && executableExtensions.isEmpty()) return false
            val name = path.fileName?.toString()?.lowercase() ?: return false
            if (name in launcherBasenames) return true
            val ext = name.substringAfterLast('.', "")
            return ext.isNotEmpty() && ext in executableExtensions
        }

        companion object {
            fun of(scope: Scope): CanonicalScope = CanonicalScope(
                readPaths = scope.readPaths.map { root(it, scope.canonicalize) }.distinct(),
                writePaths = scope.writePaths.map { root(it, scope.canonicalize) }.distinct(),
                hosts = scope.hosts.map { Target.HostTarget.normalize(it) }.toSet(),
                commands = scope.commands.map { Target.CommandTarget.normalize(it) }.toSet(),
                launcherBasenames = scope.launcherBasenames.map { it.lowercase() }.toSet(),
                executableExtensions = scope.executableExtensions.map { it.lowercase() }.toSet(),
                canonicalize = scope.canonicalize,
            )

            private fun root(path: Path, canonicalize: Boolean): Path =
                if (canonicalize) canonicalPath(path) else path.toAbsolutePath().normalize()
        }
    }
}

/**
 * Resolves [path] to the location it really names.
 *
 * An existing path uses `toRealPath()`, which follows junctions and symlinks. A path that does not
 * exist yet is resolved against its nearest existing ancestor, so a file the call is about to
 * create is still judged by the real location of its parent. A path whose roots cannot be resolved
 * falls back to its normalized absolute form rather than being silently accepted.
 */
internal fun canonicalPath(path: Path): Path {
    val absolute = runCatching { path.toAbsolutePath().normalize() }.getOrDefault(path)
    if (Files.exists(absolute)) {
        return runCatching { absolute.toRealPath() }.getOrDefault(absolute)
    }

    val missing = ArrayDeque<Path>()
    var cursor: Path? = absolute
    while (cursor != null && !Files.exists(cursor)) {
        cursor.fileName?.let { missing.addFirst(it) }
        cursor = cursor.parent
    }
    val base = cursor?.let { runCatching { it.toRealPath() }.getOrDefault(it) } ?: return absolute
    return missing.fold(base) { acc, name -> acc.resolve(name) }.normalize()
}
