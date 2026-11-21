package ai.platon.pulsar.coding

import java.nio.file.Path

/**
 * How an `agent run` task is meant to work, which is what its permission profile follows.
 *
 * Mode is not a preset: a preset says *how much* of a fixed surface is granted, a mode says *what
 * the surface is*. `--coding` already marks a task as program work (`help.rs`: "Task type: browser by
 * default (never auto-detected). --coding marks a pure coding task"), but nothing bound that mark to
 * the file/network goals. This enum is that binding, and [IsolationProfile] is its compiled form.
 *
 * See `docs/agent-permissions-redesign.md`.
 */
enum class AgentMode(val id: String) {
    /** Browser work around the Browser4 CLI: minimal reach, scripts allowed, workspace-confined. */
    BROWSE("browse"),

    /** Program work: read like the user, write like a build, so arbitrary projects are developable. */
    CODING("coding"),
}

/**
 * What a process may **read**.
 *
 * Kept as a closed set rather than a list of roots so a backend cannot be "almost right": a mode
 * either reads only its workspace or reads with the user's own rights.
 */
sealed interface ReadScope {
    /** The workspace (and its mirrors) only. Everything else is refused, credentials included. */
    data object WorkspaceOnly : ReadScope

    /**
     * The current OS user's read rights — the goal of coding mode ("读权限继承当前用户权限").
     *
     * The always-refused credential stores ([ReadablePaths.ALWAYS_REFUSED]) stay refused: a backend
     * must consult [ReadablePaths.isAlwaysRefused] before granting a read, so "inherit my rights" is
     * not "hand my keys to a model" (the [AgentAccessPolicy.unrestricted] rule, kept).
     */
    data object CurrentUser : ReadScope
}

/** What a process may **write**. Writes are always the narrow axis — that is the whole point. */
sealed interface WriteScope {
    /** The workspace (and its mirrors) only. */
    data object Workspace : WriteScope

    /**
     * The workspace plus the build caches a toolchain has to write (`~/.m2`, `~/.gradle`, temp …).
     *
     * Without them "write only the workspace" is not a coding mode: `mvn` refuses to run when its
     * local repository is read-only, and that is the first command any project needs.
     */
    data class WorkspacePlus(val caches: List<Path>) : WriteScope

    /** The current user's own write rights — only the explicit `--unrestricted` opt-in. */
    data object CurrentUser : WriteScope
}

/** What a process may **reach on the network**. */
sealed interface NetPolicy {
    /** No egress at all. */
    data object Off : NetPolicy

    /** The Browser4 backend/browser may navigate; the task's shells and scripts may not. */
    data object BrowserOnly : NetPolicy

    /** Egress allowed; an empty [hosts] set means any host (`--net-allow` adds hosts). */
    data class Allow(val hosts: Set<String> = emptySet()) : NetPolicy
}

/** What a process may **execute**. */
enum class ExecPolicy {
    /** The Browser4 CLI and the few programs a browser goal needs; nothing else. */
    BROWSER4_ONLY,

    /** A named allowlist (browser4 CLI, `git`, `python`, `node`, …) — what browse actually uses. */
    ALLOWLIST,

    /** Anything the OS user can run. Coding mode: this is the job. */
    ANY,
}

/**
 * The compiled permission goals of one task — the only thing an enforcement backend consumes.
 *
 * This is the "三条轴" of `docs/agent-permissions-redesign.md` in code: mode → read/write/net/exec.
 * It is deliberately **data**, with no OS calls, so the matrix can be asserted on every host and so
 * a backend can be reviewed against it instead of against prose.
 *
 * Enforcement backends (P1/P2, see [backendChain]) turn this into kernel rules:
 *  - `BROWSE` needs read *and* write confinement → restricted token with a sandbox SID (or
 *    AppContainer), because a Low-integrity token does not restrict reads;
 *  - `CODING` needs "read like the user, write only here" → a Low/Untrusted integrity token, which is
 *    exactly the mandatory policy Windows enforces (NO_WRITE_UP), inherited by every descendant.
 */
data class IsolationProfile(
    val mode: AgentMode,
    val read: ReadScope,
    val write: WriteScope,
    val net: NetPolicy,
    val exec: ExecPolicy,
    /** `--add-dir` grants: read-only for [AgentMode.BROWSE] (writes stay in the workspace). */
    val extraReadRoots: List<Path> = emptyList(),
) {

    /** One line for `coding.permissions` and for the refusal hints. */
    fun describe(): String = buildString {
        append("mode=").append(mode.id)
        append(" read=").append(
            when (read) {
                ReadScope.WorkspaceOnly -> "workspace"
                ReadScope.CurrentUser -> "user(minus credentials)"
            },
        )
        append(" write=").append(
            when (write) {
                WriteScope.Workspace -> "workspace"
                is WriteScope.WorkspacePlus -> "workspace+${write.caches.size} cache root(s)"
                WriteScope.CurrentUser -> "user"
            },
        )
        append(" net=").append(
            when (net) {
                NetPolicy.Off -> "off"
                NetPolicy.BrowserOnly -> "browser-only"
                is NetPolicy.Allow -> if (net.hosts.isEmpty()) "any" else net.hosts.sorted().joinToString(",")
            },
        )
        append(" exec=").append(exec.name.lowercase())
        if (extraReadRoots.isNotEmpty()) append(" add-dir=").append(extraReadRoots.size)
    }

    companion object {

        /**
         * The matrix, in one place.
         *
         * browse = browser goals with minimal reach: read only the workspace, write only the
         * workspace, egress only through the browser, run scripts from an allowlist.
         * coding = program goals: read with the user's rights, write the workspace plus build
         * caches, no egress until it is granted per host, any program runnable.
         */
        fun forMode(
            mode: AgentMode,
            extraReadRoots: List<Path> = emptyList(),
            caches: List<Path> = defaultCaches(),
        ): IsolationProfile = when (mode) {
            AgentMode.BROWSE -> IsolationProfile(
                mode = mode,
                read = ReadScope.WorkspaceOnly,
                write = WriteScope.Workspace,
                net = NetPolicy.BrowserOnly,
                exec = ExecPolicy.ALLOWLIST,
                extraReadRoots = extraReadRoots.map { it.toAbsolutePath().normalize() },
            )
            AgentMode.CODING -> IsolationProfile(
                mode = mode,
                read = ReadScope.CurrentUser,
                write = WriteScope.WorkspacePlus(caches.map { it.toAbsolutePath().normalize() }),
                net = NetPolicy.Off,
                exec = ExecPolicy.ANY,
                extraReadRoots = extraReadRoots.map { it.toAbsolutePath().normalize() },
            )
        }

        /**
         * `agent run --unrestricted`: the historical "run with my own permissions" opt-in.
         *
         * It is a profile like any other so the audit event and `coding.permissions` can name it; the
         * credential stores and the lifecycle rails are still not part of the grant (the backends
         * keep enforcing both — "use my permissions" is not "hand over my keys").
         */
        fun unrestricted(extraReadRoots: List<Path> = emptyList()): IsolationProfile = IsolationProfile(
            mode = AgentMode.CODING,
            read = ReadScope.CurrentUser,
            write = WriteScope.CurrentUser,
            net = NetPolicy.Allow(),
            exec = ExecPolicy.ANY,
            extraReadRoots = extraReadRoots,
        )

        /**
         * Build caches a coding task must be able to write, taken from the single toolchain list
         * ([CodingComponents.toolchainEntries]) so this cannot drift from the boundary scope.
         *
         * The JDK and the OS directory are deliberately absent: they are read-only for a build.
         */
        fun defaultCaches(): List<Path> = CodingComponents.toolchainEntries()
            .filter { it.id in CACHE_ENTRY_IDS }
            .map { it.path }

        /** Toolchain entries that are caches (writable), as opposed to read-only toolchains. */
        private val CACHE_ENTRY_IDS = setOf(
        "toolchain:maven", "toolchain:gradle", "toolchain:cache", "toolchain:tmp",
        // Kotlin/JVM build daemons write their client markers here; without the grant `kapt`
        // aborted with AccessDeniedException inside the sandbox (2026-11-20 agent run).
        "toolchain:kotlin-local", "toolchain:kotlin-home",
    )

        /**
         * Which enforcement backend to try, in order, for [mode] on this platform.
         *
         * The first id that is implemented and ready wins; an empty result means "no OS layer" and the
         * caller must report that state (`code=UNENFORCED`) instead of implying one. `windows-acl`
         * (the low-privilege-account backend) stays last on Windows: it is the strongest, but it needs
         * administrator provisioning, so it is the fallback rather than the default.
         */
        fun backendChain(
            mode: AgentMode,
            osName: String = System.getProperty("os.name", ""),
        ): List<String> {
            val os = osName.lowercase()
            return when {
                os.contains("win") -> when (mode) {
                    AgentMode.BROWSE -> listOf(BROWSE_BACKEND, "windows-acl")
                    AgentMode.CODING -> listOf(CODING_BACKEND, "windows-acl")
                }
                os.contains("linux") -> listOf("landlock", "bwrap")
                os.contains("mac") -> listOf("seatbelt")
                else -> emptyList()
            }
        }

        /**
         * Windows backend for browse: read+write confinement with no account and no administrator.
         *
         * AppContainer over a restricted token (decision D5, "isolation first"): it is the only one
         * of the two that also refuses *network* egress by default (no `internetClient` capability),
         * which is exactly `NetPolicy.BrowserOnly` — the browser runs in the backend, outside this
         * container.
         */
        internal const val BROWSE_BACKEND = "windows-appcontainer"

        /** Windows backend for coding: write confinement via mandatory integrity, no account needed. */
        internal const val CODING_BACKEND = "windows-low-il"
    }
}
