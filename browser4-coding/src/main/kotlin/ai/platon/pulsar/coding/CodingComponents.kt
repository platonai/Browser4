package ai.platon.pulsar.coding

import ai.platon.pulsar.coding.sandbox.ExecutionSandboxes
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Single construction point for the coding components consumed by every agent entry path.
 *
 * Before this factory existed, `AgentToolManager`, `CodingToolExecutor` and `MCPToolController`
 * each hand-built a [CodingAgentShell] and a [CodingAgentFileSystem] from the same JVM properties,
 * so a new switch had to be wired in three places and could silently drift. New options are added
 * here only.
 *
 * The factory maps the policy fields onto the component constructors; it does **not** enforce the
 * preset. Preset enforcement lives in [AgentAccessPolicy.allowsTool] and in the tool executor, so
 * that a component created for an internal purpose (for example the build runner) cannot become a
 * bypass. The component-level flags ([CodingAgentShell.allowExecution], [CodingAgentFileSystem.readOnly])
 * are projections of the preset, not its definition: they make the *component* refuse, so a direct
 * holder of a shell or file-system instance cannot re-open what the tool gate closed.
 */
object CodingComponents {

    private val logger = LoggerFactory.getLogger(CodingComponents::class.java)

    init {
        // Plan P3-1: an unknown `browser4.agent.sandbox` id must stop the start, not the first
        // command. This object is the coding domain's construction point, so it fails here.
        ExecutionSandboxes.validateConfigured()
        warnDeprecatedStringSwitches()
    }

    /**
     * Plan P3-5 (D2): the shell's string checks are advisory, and both legacy switches are
     * deprecated. Tell the operator once, at the domain's single construction point, so a
     * deployment that still sets them knows the OS sandbox — not these switches — is the boundary.
     */
    private fun warnDeprecatedStringSwitches() {
        if (System.getProperty("browser4.agent.shell.pathGuard", "on").lowercase() == "off") {
            logger.warn(
                "{} is deprecated and advisory only: it relaxes a fast-fail check, not the boundary; " +
                    "enforcement comes from the OS sandbox (browser4.agent.sandbox)",
                "browser4.agent.shell.pathGuard=off",
            )
        }
        if (System.getProperty(AgentAccessPolicy.PROP_ALLOW_EXTERNAL_ACCESS, "false").toBoolean()) {
            logger.warn(
                "{} is deprecated and advisory only: prefer browser4.agent.access; the OS sandbox " +
                    "still enforces what a process may reach",
                "browser4.agent.allowExternalAccess=true",
            )
        }
    }

    /** Boundary grant used by [CodingAgentFileSystem] (canonical scope). */
    val CAP_FS_BOUNDARY: CapabilityId = CapabilityId("cap-fs-boundary")

    /** Boundary grant used by [CodingAgentShell] (lexical scope + toolchain/launcher exemptions). */
    val CAP_SHELL_BOUNDARY: CapabilityId = CapabilityId("cap-shell-boundary")

    /**
     * Boundary authorizers cached by canonical workspace root, so the file system and the shell
     * built for the same workspace share one instance (A5: single construction point, no drift).
     * The boundary scope depends only on the root, not on the policy, so caching is safe.
     *
     * The cache is bounded: every task gets a fresh `baseDir/workspace.<taskId>`, so without a
     * bound it would grow for the life of the backend. The [LinkedHashMap] is access-ordered, so
     * the least recently used workspace is evicted once [MAX_BOUNDARY_AUTHORIZERS] is reached.
     */
    internal const val MAX_BOUNDARY_AUTHORIZERS = 64

    private val boundaryAuthorizerLock = Any()

    private val boundaryAuthorizers = object : LinkedHashMap<Path, Authorizer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Authorizer>?): Boolean =
            size > MAX_BOUNDARY_AUTHORIZERS
    }

    /** Number of cached boundary authorizers; exposed for tests that pin the bound. */
    internal fun cachedBoundaryAuthorizerCount(): Int = synchronized(boundaryAuthorizerLock) {
        boundaryAuthorizers.size
    }

    /**
     * Effects every boundary entry is in scope for. Both boundary grants allow read and write; a
     * read-only root would be a separate entry, so this stays one set rather than a per-entry field.
     */
    private val BOUNDARY_EFFECTS = setOf(Effect.FS_READ, Effect.FS_WRITE)

    /**
     * JDK, package caches, temp and the OS directory — what a build legitimately reaches outside
     * the workspace, as named scope entries. The single source of truth for [toolchainRoots] and
     * for [scopeManifest]; the ids are the keys P0-1 observation data is recorded under.
     */
    internal fun toolchainEntries(): List<ScopeEntry> {
        val home = runCatching { Path.of(System.getProperty("user.home")) }.getOrNull()
        val shellGrant = setOf(CAP_SHELL_BOUNDARY)
        fun entry(id: String, path: Path) =
            ScopeEntry("toolchain:$id", path.toAbsolutePath().normalize(), ScopeKind.TOOLCHAIN, BOUNDARY_EFFECTS, shellGrant)
        return listOfNotNull(
            runCatching { Path.of(System.getProperty("java.home")) }.getOrNull()?.let { entry("jdk", it) },
            // `$JAVA_HOME` / `%JAVA_HOME%` may name a different JDK than the one running the backend
            // (Maven toolchains, a launcher script); the exception exists for *the JDK*, not for one
            // particular installation. Backslashes are normalised so a Windows spelling resolves on
            // POSIX too.
            System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.replace('\\', '/')
                ?.let { runCatching { Path.of(it) }.getOrNull() }?.let { entry("jdk-declared", it) },
            home?.resolve(".m2")?.let { entry("maven", it) },
            home?.resolve(".gradle")?.let { entry("gradle", it) },
            home?.resolve(".cache")?.let { entry("cache", it) },
            runCatching { Path.of(System.getProperty("java.io.tmpdir")) }.getOrNull()?.let { entry("tmp", it) },
            runCatching { Path.of(System.getenv("SystemRoot") ?: "C:/Windows") }.getOrNull()?.let { entry("os-root", it) },
        )
    }

    /**
     * JDK, package caches, temp and the OS directory — what a build legitimately reaches outside
     * the workspace. Shared so the boundary [Authorizer] and the shell agree on the B4 roots.
     */
    internal fun toolchainRoots(): List<Path> = toolchainEntries().map { it.path }

    /**
     * Toolchain roots the Windows sandbox must be granted explicitly (plan P1-3).
     *
     * Only the entries that live in the operator's profile need a grant: a low-privilege account
     * cannot read them otherwise. The OS directory is already readable by `Users`, and the backend
     * temp directory is private data the child does not need — its own temp comes from the logon
     * token environment — so neither is granted.
     */
    internal fun sandboxReadOnlyRoots(): List<Path> =
        toolchainEntries()
            .filter { it.id != "toolchain:os-root" && it.id != "toolchain:tmp" }
            .map { it.path }

    /**
     * The effective boundary scope for [root] as named entries (plan P0-2).
     *
     * The workspace and its mirrored module roots are in both grants (the file system canonicalises
     * and the shell judges spelling); toolchain roots are shell-only, which is why the two grants
     * have different scopes at all. This is the list observation data attaches to and the list
     * [ScopeNarrowing] advises on — it is derived, not yet declared, so P0-1 output can turn the
     * toolchain entries into explicit declared roots without moving anything else.
     *
     * @param root the workspace root
     */
    internal fun scopeManifest(root: Path): List<ScopeEntry> {
        val realRoot = runCatching { root.toRealPath() }.getOrDefault(root.toAbsolutePath().normalize())
        val boundaryGrants = setOf(CAP_FS_BOUNDARY, CAP_SHELL_BOUNDARY)
        val workspace = ScopeEntry("workspace", realRoot, ScopeKind.WORKSPACE, BOUNDARY_EFFECTS, boundaryGrants)
        val mirrors = MirrorRoots.derive(realRoot).sortedBy { it.toString() }.map {
            ScopeEntry("mirror:$it", it, ScopeKind.MIRROR, BOUNDARY_EFFECTS, boundaryGrants)
        }
        return listOf(workspace) + mirrors + toolchainEntries()
    }

    /**
     * The single boundary [Authorizer] shared by the file system and the shell for a workspace.
     *
     * Holds two grants on the same instance:
     * - [CAP_FS_BOUNDARY]: canonical (real-path) scope over the workspace root and its mirrored
     *   module roots — what the file tools use, so a junction inside the workspace that points
     *   outside is refused.
     * - [CAP_SHELL_BOUNDARY]: lexical scope over the same roots plus the toolchain locations
     *   ([toolchainRoots]) and launcher basenames/extensions — what the shell uses, because its
     *   historical contract judges a path by how it is spelled and build launchers must stay
     *   usable wherever they live.
     *
     * Both scopes are read from [scopeManifest], so the manifest and the grants cannot drift apart.
     * Each caller pins its grant via [Request.capabilityId], so the shell's wider scope cannot
     * accidentally cover a file-system request.
     *
     * @param root the workspace root
     */
    fun boundaryAuthorizer(root: Path): Authorizer {
        val key = runCatching { root.toRealPath() }.getOrDefault(root.toAbsolutePath().normalize())
        synchronized(boundaryAuthorizerLock) {
            boundaryAuthorizers[key]?.let { return it }
            val built = buildBoundaryAuthorizer(key)
            boundaryAuthorizers[key] = built
            return built
        }
    }

    private fun buildBoundaryAuthorizer(workspaceRoot: Path): Authorizer {
        val manifest = scopeManifest(workspaceRoot)
        fun rootsOf(grant: CapabilityId): Set<Path> =
            manifest.filter { grant in it.grants }.map { it.path }.toSet()
        val fsRoots = rootsOf(CAP_FS_BOUNDARY)
        val shellRoots = rootsOf(CAP_SHELL_BOUNDARY)
        return Authorizer(
            listOf(
                Capability(
                    id = CAP_FS_BOUNDARY,
                    effects = setOf(Effect.FS_READ, Effect.FS_WRITE),
                    scope = Scope(readPaths = fsRoots, writePaths = fsRoots),
                    source = CapabilitySource.PRESET,
                ),
                Capability(
                    id = CAP_SHELL_BOUNDARY,
                    effects = setOf(Effect.FS_READ, Effect.FS_WRITE),
                    scope = Scope(
                        readPaths = shellRoots,
                        writePaths = shellRoots,
                        launcherBasenames = CodingAgentShell.LAUNCHER_BASENAMES,
                        executableExtensions = CodingAgentShell.EXECUTABLE_EXTENSIONS,
                        canonicalize = false,
                    ),
                    source = CapabilitySource.PRESET,
                ),
            ),
        )
    }

    /**
     * Builds a [CodingAgentShell] honouring the policy's shell-related switches.
     *
     * `allowExecution` is a projection of [AgentAccessPolicy.allowsExec]: under a preset that does not
     * grant execution, the model-facing shell refuses every command. The build entry points do not use
     * this instance — they use [buildShell] — so `read-build` can still run the project build.
     *
     * @param root the workspace root used as the shell base directory
     * @param policy the policy to read `allowExternalAccess` / `allowNetwork` / `allowDestructive` /
     *   `sensitivePaths` / `protectedDirs` from
     */
    fun shell(root: Path, policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault): CodingAgentShell {
        val boundary = boundaryAuthorizer(root)
        return CodingAgentShell(
            baseDir = root,
            allowNetwork = policy.allowNetwork,
            allowNetworkProvider = { policy.allowNetwork || policy.hasActiveNetworkGrant() },
            allowDestructive = policy.allowDestructive,
            allowExternalAccess = policy.allowExternalAccess,
            allowExecution = policy.allowsExec(),
            allowExecutionProvider = { policy.allowsExec() },
            commandAllowedProvider = { policy.allowsCommandExecution(it) },
            sensitivePaths = policy.sensitivePaths,
            protectedDirs = policy.protectedDirs,
            boundaryAuthorizer = boundary,
        )
    }

    /**
     * Builds the internal build shell — the one `coding.mvnBuild` / `verify` / `validate` /
     * `devTask` run through.
     *
     * A preset that forbids execution must still be able to run the project's own build, otherwise
     * `read-build` (whose whole point is "read + self-check") would have no way to build at all.
     * Two shells therefore exist per target: the model-facing one ([shell], used by `coding.shell*`)
     * and this one, which is only reachable through the build entry points.
     *
     * Under [AccessPreset.FULL] the model shell is returned unchanged: builds share its session
     * environment and command history exactly as before. Under a restricted preset a **separate**
     * instance is built in the [CodingAgentShell] build-only tier, so the build never inherits a
     * permissive command set.
     *
     * @param root the workspace root used as the shell base directory
     * @param policy the policy to read the shell switches from
     * @param modelShell the target's model-facing shell, reused under [AccessPreset.FULL]
     */
    fun buildShell(
        root: Path,
        policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault,
        modelShell: CodingAgentShell? = null,
    ): CodingAgentShell {
        // Only the preset itself may reuse the model shell. An operator grant can make
        // `allowsExec()` true, but the model shell now carries a command-scope provider that
        // approves one program; reusing it would make a permitted build fail on `mvn` because a
        // grant for `git` does not cover it.
        if (policy.preset == AccessPreset.FULL) return modelShell ?: shell(root, policy)
        val boundary = boundaryAuthorizer(root)
        return CodingAgentShell(
            baseDir = root,
            buildOnly = true,
            allowNetwork = policy.allowNetwork,
            allowNetworkProvider = { policy.allowNetwork || policy.hasActiveNetworkGrant() },
            allowDestructive = policy.allowDestructive,
            allowExternalAccess = policy.allowExternalAccess,
            sensitivePaths = policy.sensitivePaths,
            protectedDirs = policy.protectedDirs,
            boundaryAuthorizer = boundary,
        )
    }

    /**
     * Builds a [CodingAgentFileSystem] honouring the policy's file-related switches.
     *
     * `readOnly` is a projection of [AgentAccessPolicy.allowsFileMutation]: `read` and `read-build`
     * get a file system whose mutators refuse, so a mutating call that reaches the file system does
     * not depend on the tool gate having run first.
     *
     * @param root the workspace root
     * @param policy the policy to read `allowExternalAccess` / `allowDestructive` / `protectedFiles` from
     * @param persistSnapshots whether file snapshots survive a restart (the MCP path enables it)
     */
    fun fs(
        root: Path,
        policy: AgentAccessPolicy = AgentAccessPolicy.serverDefault,
        persistSnapshots: Boolean = false,
    ): CodingAgentFileSystem {
        val boundary = boundaryAuthorizer(root)
        return CodingAgentFileSystem(
            workspaceRoot = root,
            allowExternalAccess = policy.allowExternalAccess,
            allowDestructive = policy.allowDestructive,
            readOnly = !policy.allowsFileMutation(),
            readOnlyProvider = { !policy.allowsFileMutation() },
            writeAllowedProvider = { policy.allowsMutationOf(it) },
            protectedFiles = policy.protectedFiles,
            protectedDirs = policy.protectedDirs,
            sensitivePaths = policy.sensitivePaths,
            persistSnapshots = persistSnapshots,
            boundaryAuthorizer = boundary,
        )
    }
}
