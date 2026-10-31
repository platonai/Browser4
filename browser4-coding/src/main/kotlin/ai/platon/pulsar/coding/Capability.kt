package ai.platon.pulsar.coding

import java.nio.file.Path
import java.time.Instant

/**
 * A structured refusal code.
 *
 * Values come from [PermissionAudit] so the decision layer, the audit counters and the CLI all
 * speak the same vocabulary instead of inventing a second code table.
 */
typealias AuditCode = String

/**
 * A side effect a tool can request.
 *
 * The set is **closed on purpose**: adding an effect means changing this enum and its tests, which
 * keeps "declare what you need" reviewable. A policy DSL would eventually grow a combination nobody
 * covered, so there is deliberately no way to express an effect as a string at runtime.
 */
enum class Effect(val id: String) {
    /** Read a file or directory. */
    FS_READ("fs.read"),

    /** Create, modify or delete a file or directory. */
    FS_WRITE("fs.write"),

    /** Run a command or the project build. */
    FS_EXEC("fs.exec"),

    /** Open an outbound network connection. */
    NET_CONNECT("net.connect"),

    /** Spawn a process that is not a shell command (interpreters, language runtimes). */
    PROC_SPAWN("proc.spawn"),

    /** Read a credential location (`~/.ssh`, `~/.aws`, ...). */
    SECRET_READ("secret.read"),
    ;

    companion object {
        /**
         * Parses an effect id, accepting `fs.read` and `fs_read` in any casing.
         * Returns `null` for blank or unknown values so callers can fail closed deliberately.
         */
        fun parse(raw: String?): Effect? {
            val normalized = raw?.trim()?.lowercase()?.replace('_', '.') ?: return null
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.id == normalized }
        }
    }
}

/**
 * What an effect is applied to.
 *
 * A request may carry targets for several effects at once (a shell call that both writes a file and
 * runs a command); each effect consumes the targets of its own kind.
 */
sealed interface Target {

    /** Human-readable form used in refusals and audit records. */
    fun describe(): String

    /** A filesystem location; used by [Effect.FS_READ], [Effect.FS_WRITE] and [Effect.SECRET_READ]. */
    data class PathTarget(val path: Path) : Target {
        override fun describe(): String = path.toString()
    }

    /** An outbound host; used by [Effect.NET_CONNECT]. */
    data class HostTarget(val host: String) : Target {
        /** Lower-cased, without a trailing dot, so `Example.COM.` and `example.com` match. */
        val normalized: String get() = normalize(host)

        override fun describe(): String = host

        companion object {
            fun normalize(raw: String): String = raw.trim().trimEnd('.').lowercase()
        }
    }

    /** An executable; used by [Effect.FS_EXEC] and [Effect.PROC_SPAWN]. */
    data class CommandTarget(val executable: String) : Target {
        /**
         * Lower-cased basename without a Windows launcher suffix, so `/usr/bin/git`, `git` and
         * `git.exe` all match the scope entry `git`.
         */
        val normalized: String get() = normalize(executable)

        override fun describe(): String = executable

        companion object {
            private val SUFFIXES = setOf(".exe", ".cmd", ".bat", ".com", ".ps1")

            fun normalize(raw: String): String {
                var name = raw.trim().substringAfterLast('/').substringAfterLast('\\').lowercase()
                for (suffix in SUFFIXES) {
                    if (name.endsWith(suffix) && name.length > suffix.length) {
                        name = name.removeSuffix(suffix)
                        break
                    }
                }
                return name
            }
        }
    }
}

/**
 * The concrete locations a [Capability] grants.
 *
 * Paths are roots: an entry covers itself and every descendant. That is the semantic of `**` in the
 * design, expressed as a directory root so a scope can never widen itself through glob expansion.
 * Both the entries and the requested targets are canonicalized (real path where it exists) before
 * matching, otherwise a junction inside the workspace would be judged on its lexical form only.
 */
data class Scope(
    /** Roots that may be read. */
    val readPaths: Set<Path> = emptySet(),
    /** Roots that may be written. */
    val writePaths: Set<Path> = emptySet(),
    /** Hosts that may be connected to; exact match is required. */
    val hosts: Set<String> = emptySet(),
    /** Executable names that may be run; a basename match without a launcher suffix. */
    val commands: Set<String> = emptySet(),
    /**
     * Basenames that are always treated as in-scope when they appear as a path target, even if the
     * path lives outside [readPaths]. This encodes the shell's launcher exemption (B3): an absolute
     * `mvnw`/`python3` outside the workspace stays usable because it is a tool, not data.
     */
    val launcherBasenames: Set<String> = emptySet(),
    /**
     * File extensions that mark a path as a launcher, applied the same way as [launcherBasenames].
     */
    val executableExtensions: Set<String> = emptySet(),
    /**
     * When `true` (default), path targets and roots are compared on their canonical (real) form so
     * junctions and symlinks are judged by where they really point. When `false`, paths are compared
     * by their lexical normalized form. Per-capability because different layers have different
     * historical contracts: the file system follows links, the shell judges paths by how they are
     * spelled.
     */
    val canonicalize: Boolean = true,
) {
    companion object {
        /** A scope that grants nothing. */
        val EMPTY = Scope()
    }
}

/**
 * How much a capability may spend before it must be re-approved.
 *
 * A `null` field means "no limit on this dimension", which is different from `0` ("none allowed").
 */
data class Limits(
    val durationSec: Long? = null,
    val writeBytes: Long? = null,
    val execCalls: Int? = null,
) {
    /** True when no dimension is bounded. */
    val isUnbounded: Boolean get() = durationSec == null && writeBytes == null && execCalls == null

    companion object {
        /** No limit at all. */
        val NONE = Limits()
    }
}

/**
 * What one call is about to spend, or what it has already spent.
 *
 * [Cost] is deliberately additive: a request cost plus the consumed total is the projected spend
 * that [Authorizer] compares against [Limits]. Compared to a nullable field, `0` here means "none".
 */
data class Cost(
    val durationSec: Long = 0,
    val writeBytes: Long = 0,
    val execCalls: Int = 0,
) {
    operator fun plus(other: Cost): Cost = Cost(
        durationSec = durationSec + other.durationSec,
        writeBytes = writeBytes + other.writeBytes,
        execCalls = execCalls + other.execCalls,
    )

    /** Whether this spend breaches [limits]; exactly equal to a limit is still allowed. */
    fun exceeds(limits: Limits): Boolean =
        (limits.durationSec?.let { durationSec > it } ?: false) ||
            (limits.writeBytes?.let { writeBytes > it } ?: false) ||
            (limits.execCalls?.let { execCalls > it } ?: false)

    companion object {
        /** Nothing spent. */
        val NONE = Cost()
    }
}

/** Who granted a capability; an audit field that does not participate in matching. */
enum class CapabilitySource {
    /** Derived from an [AccessPreset]. */
    PRESET,

    /** Declared by the task file or the CLI for this run only. */
    TASK,

    /** Issued by an operator through the approval flow. */
    OPERATOR,
}

/** Stable identity of a capability, e.g. `cap-ws-write`. */
data class CapabilityId(val value: String) {
    override fun toString(): String = value
}

/**
 * A grant: the effects an agent may perform, where, and under which budget.
 *
 * @property id stable identity used by audit records and future approval
 * @property effects the effects this grant permits; an empty set grants nothing
 * @property scope the locations reachable under those effects
 * @property limits the spend ceiling for the grant, or [Limits.NONE]
 * @property source who issued the grant; audit only
 * @property expiresAt when the grant stops applying, or `null` for "until the task ends"
 */
data class Capability(
    val id: CapabilityId,
    val effects: Set<Effect>,
    val scope: Scope = Scope.EMPTY,
    val limits: Limits = Limits.NONE,
    val source: CapabilitySource = CapabilitySource.PRESET,
    val expiresAt: Instant? = null,
) {
    /** Whether this grant still applies at [now]. */
    fun isActiveAt(now: Instant): Boolean = expiresAt == null || now.isBefore(expiresAt)

    /** Whether this grant permits [effect]. Scope is checked separately. */
    fun grants(effect: Effect): Boolean = effect in effects
}

/**
 * What an agent must ask for when a [Decision.Deny] refused it.
 *
 * Carried by every refusal so the model reads "you need fs.write at C:\temp" instead of a bare
 * "denied", and so the CLI can render an approval prompt without parsing prose.
 */
data class CapabilityRequest(
    val effects: Set<Effect>,
    val targets: List<Target> = emptyList(),
    val reason: String? = null,
)
