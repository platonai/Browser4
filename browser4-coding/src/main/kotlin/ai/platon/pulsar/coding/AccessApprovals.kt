package ai.platon.pulsar.coding

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a request or decision came from. Audit only: the [Authorizer] never reads it.
 *
 * Plan P4-1 defines three channels that all converge on one `OPERATOR` capability — a strategy
 * file, the REST API and the CLI slash commands. [DEFAULT] is the unattended default, which is not
 * a request but a pre-approval derived for every unattended job.
 */
enum class AccessSource(val id: String) {
    /** Pre-approved by a task/workspace strategy file. */
    FILE("file"),

    /** Approved or denied through the REST API. */
    REST("rest"),

    /** Approved or denied through the interactive CLI. */
    CLI("cli"),

    /** The unattended default (plan P4-1 §6.3). */
    DEFAULT("default"),
}

/** The lifecycle of one [AccessRequest]. */
enum class AccessState { PENDING, APPROVED, DENIED, EXPIRED }

/**
 * One `coding.requestAccess` call.
 *
 * The request is deliberately non-blocking: it records what the model needs and returns
 * immediately, so the agent loop keeps working with the tools the preset already grants. A
 * duplicate `effect + target` returns the existing request instead of creating a second pending
 * item, otherwise a retrying model could flood the operator's queue.
 *
 * @property id stable id handed back to the model and used by approve/deny
 * @property effect the effect refused by the preset
 * @property target the location the effect applies to
 * @property reason why the model needs it, as the model stated it
 * @property ttlSeconds how long an approval would stay valid; `null` means until the task ends
 */
class AccessRequest(
    val id: String,
    val effect: Effect,
    val target: Target,
    val reason: String?,
    val ttlSeconds: Long?,
    val requestedAt: Instant,
    /** Which channel created it: REST for a model-requested entry, FILE for a strategy-file entry. */
    val requestedBy: AccessSource = AccessSource.REST,
) {
    @Volatile
    var state: AccessState = AccessState.PENDING
        internal set

    /** The issued capability once approved, or `null`. */
    @Volatile
    var capability: Capability? = null
        internal set

    /** When the request stopped being pending: decision time or expiry time. */
    @Volatile
    var decidedAt: Instant? = null
        internal set

    /** Expiry of the issued capability (`null` = until the task ends). */
    val expiresAt: Instant? get() = capability?.expiresAt

    override fun toString(): String =
        "$id effect=${effect.id} target=${target.describe()} state=$state reason=${reason ?: "-"}"
}

/**
 * One pending request as a finished task left it.
 *
 * An immutable copy of the fields the REST/CLI view renders. The live queue is dropped when the
 * task ends, so the terminal summary needs its own copy of what the run was waiting on.
 */
data class PendingAccessRequest(
    val id: String,
    val effect: Effect,
    val target: Target,
    val reason: String?,
)

/**
 * Read-only view of one task's approval state.
 *
 * Backed either by the live [AccessApprovals] queue or by an [AccessApprovalsSnapshot] taken when
 * the task ended. There is deliberately no approve/deny here: a finished task cannot be decided.
 */
interface AccessApprovalsView {
    fun pending(): List<PendingAccessRequest>

    fun activeCapabilities(): List<Capability>

    fun describe(): String
}

/**
 * The last pending queue of a finished task.
 *
 * Only the pending list survives. The task is terminal, so its operator grants are no longer in
 * force and approve/deny are impossible. Each agent tool manager keeps exactly one instance (its
 * most recently finished task), so this cannot grow without bound.
 */
class AccessApprovalsSnapshot(
    val taskId: String,
    private val pendingRequests: List<PendingAccessRequest>,
) : AccessApprovalsView {
    override fun pending(): List<PendingAccessRequest> = pendingRequests

    override fun activeCapabilities(): List<Capability> = emptyList()

    override fun describe(): String = buildString {
        appendLine("Task: $taskId (finished)")
        appendLine("Pending access requests (${pendingRequests.size}):")
        if (pendingRequests.isEmpty()) {
            appendLine("  none")
        } else {
            pendingRequests.forEach { request ->
                appendLine(
                    "  ${request.id}  effect=${request.effect.id}  target=${request.target.describe()}" +
                        (request.reason?.let { "  reason=$it" } ?: "")
                )
            }
        }
        appendLine("Operator grants in force (0):")
        appendLine("  none")
    }.trimEnd()
}

/**
 * Per-task operator approvals (plan P4-1).
 *
 * Holds the pending queue and the capabilities an operator issued for one task. The policy reads
 * [activeCapabilities] on every decision, so an approval takes effect on the next call without
 * rebuilding the tool components; nothing here blocks the agent loop.
 *
 * Guardrails are checked at approval time, not at request time, so a model can ask for anything it
 * needs and the operator's answer is the one that has to obey the rails. An approval can never
 * cross `denyTools` (`coding.*` / `skill.*`), a protected file/directory, a credential path, the
 * workspace boundary when `allowExternalAccess=false`, or the `DEV_COMMANDS` whitelist for
 * execution. The OS sandbox remains the enforced layer underneath.
 *
 * Expiry is lazy: [activeCapabilities] and [describe] drop expired grants and record
 * `ACCESS_EXPIRED` once, so no background timer is needed.
 *
 * @param taskId the task these approvals belong to, used in the audit detail and REST answer
 * @param policy the task's policy; read through a supplier so a policy replaced later is visible
 * @param workspaceRoot the task's workspace root, read through a supplier so a later rebind is
 *   visible instead of pinning the root that was active when the queue was built
 */
class AccessApprovals(
    val taskId: String,
    private val policy: () -> AgentAccessPolicy,
    private val workspaceRoot: () -> Path,
    private val clock: () -> Instant = Instant::now,
) : AccessApprovalsView {
    private val logger = LoggerFactory.getLogger(AccessApprovals::class.java)

    private val requests = ConcurrentHashMap<String, AccessRequest>()

    /** Dedupe key (`effect|target`) to request id, so a retry does not create a second entry. */
    private val dedupe = ConcurrentHashMap<String, String>()

    private val sequence = AtomicInteger(0)

    /**
     * Records a `coding.requestAccess` call and returns it immediately.
     *
     * [requestedBy] names the channel the request came in on: the model's tool call (REST) or a
     * strategy-file entry (FILE). It is audit-only, like every [AccessSource].
     */
    fun request(
        effect: Effect,
        target: Target,
        reason: String? = null,
        ttlSeconds: Long? = null,
        requestedBy: AccessSource = AccessSource.REST,
    ): AccessRequest {
        val key = dedupeKey(effect, target)
        val existing = dedupe[key]?.let { requests[it] }
        // An approved request stays deduped as long as its grant is active; once it expired (lazily
        // or explicitly) a fresh request is allowed, otherwise the model could never ask again.
        if (existing != null && existing.state == AccessState.PENDING) return existing
        if (existing != null && existing.capability?.isActiveAt(clock()) == true) return existing

        val request = AccessRequest(
            id = "req-${sequence.incrementAndGet()}",
            effect = effect,
            target = target,
            reason = reason?.trim()?.takeIf { it.isNotEmpty() },
            ttlSeconds = ttlSeconds?.takeIf { it > 0 },
            requestedAt = clock(),
            requestedBy = requestedBy,
        )
        requests[request.id] = request
        dedupe[key] = request.id
        PermissionAudit.writeEvent(
            tool = "coding.requestAccess",
            code = PermissionAudit.CODE_ACCESS_REQUESTED,
            detail = "${request.id} effect=${effect.id} target=${target.describe()} " +
                "source=${requestedBy.id}${request.reason?.let { " reason=$it" } ?: ""}",
        )
        return request
    }

    /**
     * Pre-approves the workspace strategy file (plan P4-4; see [AccessPolicyFile]).
     *
     * Each entry becomes a regular request that is immediately approved with
     * [AccessSource.FILE], so the pending view, the audit trail and the rails are identical to
     * an operator answering by hand — including a denial when an entry crosses a rail
     * (`denyTools`, a protected path, the workspace boundary, the command whitelist).
     *
     * @return how many entries ended up approved
     */
    fun loadPolicyFile(path: Path = workspaceRoot().resolve(AccessPolicyFile.RELATIVE_PATH)): Int {
        val entries = AccessPolicyFile.load(path)
        var approvedCount = 0
        entries.forEach { entry ->
            val effect = Effect.parse(entry.effect)
            if (effect == null) {
                logger.warn("access policy file {}: unknown effect '{}'; skipping the entry", path, entry.effect)
                return@forEach
            }
            val request = request(
                effect = effect,
                target = targetFor(effect, entry.target),
                reason = entry.reason,
                ttlSeconds = entry.ttlSeconds,
                requestedBy = AccessSource.FILE,
            )
            approve(request.id, AccessSource.FILE)
            if (request.state == AccessState.APPROVED) approvedCount++
        }
        if (entries.isNotEmpty()) {
            logger.info(
                "access policy file {}: {} of {} grant(s) pre-approved for task {}",
                path, approvedCount, entries.size, taskId,
            )
        }
        return approvedCount
    }

    /** Interprets a strategy-file target by effect: a command line, a host, or a path. */
    private fun targetFor(effect: Effect, raw: String): Target = when (effect) {
        Effect.FS_EXEC, Effect.PROC_SPAWN -> Target.CommandTarget(raw)
        Effect.NET_CONNECT -> Target.HostTarget(raw)
        else -> {
            val path = Path.of(raw)
            Target.PathTarget(if (path.isAbsolute) path else workspaceRoot().resolve(raw).normalize())
        }
    }

    /**
     * Approves [requestId] and issues one `OPERATOR` capability.
     *
     * @return the request after the decision; its [AccessRequest.state] tells whether it was
     *   approved or refused by a rail, so the REST layer can answer 200 vs 409 without parsing text.
     */
    fun approve(requestId: String, source: AccessSource): AccessRequest? {
        val request = requests[requestId] ?: return null
        if (request.state == AccessState.APPROVED) return request

        val violation = guardrailViolation(request.effect, request.target)
        if (violation != null) {
            deny(requestId, source, violation)
            return request
        }

        val expiresAt = request.ttlSeconds?.let { clock().plus(Duration.ofSeconds(it)) }
        request.capability = Capability(
            id = CapabilityId("op-${request.id}"),
            effects = setOf(request.effect),
            scope = scopeFor(request.effect, request.target),
            source = CapabilitySource.OPERATOR,
            expiresAt = expiresAt,
        )
        request.state = AccessState.APPROVED
        request.decidedAt = clock()
        PermissionAudit.writeEvent(
            tool = "coding.requestAccess",
            code = PermissionAudit.CODE_ACCESS_APPROVED,
            detail = "${request.id} effect=${request.effect.id} target=${request.target.describe()} " +
                "source=${source.id} expiresAt=${expiresAt ?: "task-end"}",
        )
        return request
    }

    /** Denies [requestId]; [reason] defaults to the model's own stated reason. */
    fun deny(requestId: String, source: AccessSource, reason: String? = null): AccessRequest? {
        val request = requests[requestId] ?: return null
        if (request.state == AccessState.DENIED) return request
        request.state = AccessState.DENIED
        request.decidedAt = clock()
        PermissionAudit.writeEvent(
            tool = "coding.requestAccess",
            code = PermissionAudit.CODE_ACCESS_DENIED,
            detail = "${request.id} effect=${request.effect.id} target=${request.target.describe()} " +
                "source=${source.id}${reason?.let { " reason=$it" } ?: ""}",
        )
        return request
    }

    /** Requests still waiting for an operator, expired ones excluded. */
    override fun pending(): List<PendingAccessRequest> = requests.values
        .filter { it.state == AccessState.PENDING }
        .map { PendingAccessRequest(it.id, it.effect, it.target, it.reason) }

    /**
     * A read-only copy of the pending queue, taken by `AgentToolManager.resetAccessPolicy` just
     * before it drops the live queue. Keeps the terminal `GET /api/agent/{taskId}/access` answer
     * available after the task has ended.
     */
    fun snapshot(): AccessApprovalsSnapshot =
        AccessApprovalsSnapshot(taskId, pending().sortedBy { it.id })

    /** All requests known to this task, in creation order. */
    fun all(): List<AccessRequest> = requests.values.sortedBy { it.id }

    /**
     * The grants currently in force, dropping expired ones (and auditing the expiry once).
     *
     * Read by [AgentAccessPolicy.operatorGrants] on every decision, so this must stay cheap and
     * side-effect free apart from the one-time expiry record.
     */
    override fun activeCapabilities(): List<Capability> {
        val now = clock()
        val active = mutableListOf<Capability>()
        requests.values.forEach { request ->
            val capability = request.capability ?: return@forEach
            if (request.state != AccessState.APPROVED) return@forEach
            if (capability.isActiveAt(now)) {
                active.add(capability)
            } else if (request.state != AccessState.EXPIRED) {
                request.state = AccessState.EXPIRED
                PermissionAudit.writeEvent(
                    tool = "coding.requestAccess",
                    code = PermissionAudit.CODE_ACCESS_EXPIRED,
                    detail = "${request.id} effect=${request.effect.id} target=${request.target.describe()} " +
                        "expiredAt=${capability.expiresAt}",
                )
            }
        }
        return active
    }

    /** The `coding.permissions` / REST view: pending queue plus grants in force. */
    override fun describe(): String = buildString {
        appendLine("Task: $taskId  workspace: ${workspaceRoot()}")
        appendLine("Unattended default: ${unattendedLabel()}")
        val pendingList = pending()
        appendLine("Pending access requests (${pendingList.size}):")
        if (pendingList.isEmpty()) {
            appendLine("  none")
        } else {
            pendingList.sortedBy { it.id }.forEach { request ->
                appendLine(
                    "  ${request.id}  effect=${request.effect.id}  target=${request.target.describe()}" +
                        (request.reason?.let { "  reason=$it" } ?: "")
                )
            }
        }
        val active = activeCapabilities()
        appendLine("Operator grants in force (${active.size}):")
        if (active.isEmpty()) {
            appendLine("  none")
        } else {
            active.sortedBy { it.id.value }.forEach { grant ->
                appendLine(
                    "  ${grant.id}  effects=${grant.effects.sortedBy { it.id }.joinToString(",") { it.id }}" +
                        "  expiresAt=${grant.expiresAt ?: "task-end"}"
                )
            }
        }
    }.trimEnd()

    /**
     * Why [effect] at [target] can never be approved, or `null` when the rails allow it.
     *
     * The checks mirror the layer that enforces them, so an approval cannot become a way around a
     * rail: `denyTools` (`coding.*` / `skill.*`), protected files/directories, credential paths, the
     * workspace boundary and the shell's `DEV_COMMANDS` whitelist.
     */
    fun guardrailViolation(effect: Effect, target: Target): String? {
        val current = policy()
        if (current.denyTools.any { it == "*" || it == "coding.*" }) {
            return "denyTools ('coding.*') excludes every coding method — an approval cannot cross it"
        }
        return when (target) {
            is Target.PathTarget -> pathRail(current, effect, target.path)
            is Target.CommandTarget -> commandRail(effect, target)
            is Target.HostTarget -> null
        }
    }

    private fun pathRail(current: AgentAccessPolicy, effect: Effect, path: Path): String? {
        if (effect == Effect.SECRET_READ) {
            return "credential locations are never approved"
        }
        val resolved = canonicalPath(path)
        val root = runCatching { workspaceRoot().toRealPath() }.getOrDefault(workspaceRoot().toAbsolutePath().normalize())
        if (!current.allowExternalAccess && !resolved.startsWith(root)) {
            return "path '$resolved' is outside the workspace and allowExternalAccess=false — " +
                "an approval cannot cross the workspace boundary"
        }
        if (resolved.startsWith(root)) {
            val rel = root.relativize(resolved).toString().replace('\\', '/')
            val protectedDir = current.protectedDirs.firstOrNull { rel == it || rel.startsWith("$it/") }
            if (protectedDir != null) return "path '$rel' is inside protected directory '$protectedDir'"
            val protectedFile = current.protectedFiles.any { p ->
                if (p == "pom.xml" || p.contains('/')) p == rel else rel.substringAfterLast('/') == p
            }
            if (protectedFile) return "file '$rel' is protected"
        }
        val sensitiveRoots = SensitivePaths.roots(current.sensitivePaths)
        if (SensitivePaths.isSensitive(resolved, sensitiveRoots)) {
            return "path '$resolved' is a credential location — never approved"
        }
        return null
    }

    private fun commandRail(effect: Effect, target: Target.CommandTarget): String? {
        if (effect != Effect.FS_EXEC && effect != Effect.PROC_SPAWN) return null
        // The same whitelist the shell itself enforces: safe (read-only) commands plus dev tools.
        // An approval may pick among them, never add a program the shell would refuse anyway.
        val allowed = CodingAgentShell.DEV_COMMANDS + CodingAgentShell.SAFE_COMMANDS
        // The model may name a full command line (`echo hi`) instead of a bare program, so compare
        // only the program name. Anything unusual (env prefixes, wrappers) fails closed here; the
        // shell's own validator still has the final say at execution time.
        val program = target.normalized.substringBefore(' ')
        if (program !in allowed) {
            return "'${target.describe()}' is not in the dev-command whitelist (safe commands and " +
                "DEV_COMMANDS) — an approval cannot add an arbitrary program"
        }
        return null
    }

    /**
     * The scope a granted capability carries.
     *
     * This is now also the enforcement boundary: [AgentAccessPolicy.operatorAllows] decides a call
     * against exactly this scope, so a grant for one path/command cannot unlock another. A command
     * approval is stored as its program name — the same granularity [commandRail] checks — so
     * `echo hi` approved from a command line still covers an `echo` invocation.
     */
    private fun scopeFor(effect: Effect, target: Target): Scope = when (target) {
        is Target.PathTarget -> when (effect) {
            Effect.FS_WRITE -> Scope(writePaths = setOf(target.path))
            else -> Scope(readPaths = setOf(target.path))
        }
        is Target.HostTarget -> Scope(hosts = setOf(target.normalized))
        is Target.CommandTarget -> Scope(commands = setOf(target.normalized.substringBefore(' ').trim()))
    }

    private fun dedupeKey(effect: Effect, target: Target): String =
        "${effect.id}|${target.javaClass.simpleName}|${target.describe()}"

    private fun unattendedLabel(): String {
        val raw = System.getProperty(AgentAccessPolicy.PROP_ACCESS_UNATTENDED)?.trim()
        return if (raw.isNullOrBlank()) "full" else raw.lowercase().replace('_', '-')
    }
}
