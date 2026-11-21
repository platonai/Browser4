package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.AgentMode
import ai.platon.pulsar.coding.CodingComponents
import ai.platon.pulsar.coding.IsolationProfile
import ai.platon.pulsar.coding.MirrorRoots
import ai.platon.pulsar.coding.PermissionAudit
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Immutable description of one process launch.
 *
 * Built by the caller (shell / runCode) *after* the capability boundary has decided the request,
 * and consumed by an [ExecutionSandbox]. Keeping the environment in the spec rather than in the
 * `ProcessBuilder` is the point of C1: both execution paths must build it the same way.
 *
 * [sandboxAccount] is set by the sandbox during [ExecutionSandbox.prepare] and read by
 * [ProcessLauncher]: the account is what selects the Windows token launch, so the decision stays
 * in one place (the sandbox) and the launcher stays a pure executor.
 *
 * [sandboxLauncher] is the same seam for a backend that has no account to hand over — a
 * Low-integrity or AppContainer token is a live handle, not an identity. It wins over
 * [sandboxAccount] when both are present.
 */
data class ProcessSpec(
    val command: List<String>,
    val directory: Path,
    val environment: Map<String, String>,
    val sandboxAccount: SandboxAccount? = null,
    val sandboxLauncher: SandboxLauncher? = null,
)

/**
 * How a token-based sandbox starts the prepared process.
 *
 * Implementations are closures over a token they own ([WindowsLowIntegritySandbox]); the launcher
 * interface exists so [ProcessLauncher] keeps a single decision point and the sandbox contract
 * ("an unavailable backend never degrades to an unwrapped launch") is not duplicated per backend.
 */
fun interface SandboxLauncher {
    fun start(spec: ProcessSpec, redirectErrorStream: Boolean): Process
}


/**
 * Enforcement seam for the execution tier (plan C1).
 *
 * [OffSandbox] keeps the historical behaviour; [WindowsAclSandbox] (P1-2/P1-3) runs the process as
 * the low-privilege sandbox account and relies on NTFS ACLs for the boundary. An implementation that
 * cannot start must throw [SandboxUnavailableException] — never fall back to an unwrapped launch,
 * otherwise one bad configuration silently turns the boundary off.
 */
interface ExecutionSandbox {
    val id: String

    /**
     * Whether this backend can refuse a *network* connection.
     *
     * File ACLs and integrity levels say nothing about sockets, so most backends answer `false`;
     * only a capability/inspection-based one (AppContainer, a filter, a proxy) can answer `true`.
     * Callers must report a `net=off` request as unenforced when this is `false` instead of
     * implying an egress boundary that does not exist.
     */
    val enforcesNetwork: Boolean get() = false

    /**
     * Whether this backend can actually be used on this machine right now.
     *
     * Consulted by [ExecutionSandboxes.resolveForMode] and by `SandboxDefaults`: an installation must
     * never be switched to a backend that cannot start, because the contract below is fail-closed.
     */
    fun isReady(): Boolean = true

    /**
     * Whether a **mode's chain** may select this backend without an explicit operator choice.
     *
     * `isReady()` answers "can this run here"; this answers "should a task get it by default". A
     * backend whose enforcement works but whose fit with the rest of the product is unproven (the
     * AppContainer cannot read the user profile, where `browser4-cli` lives) declares `false` and
     * stays reachable through `-Dbrowser4.agent.sandbox=<id>` until it is ready to be a default.
     */
    val autoSelectable: Boolean get() = true

    /**
     * Whether this backend can also execute the **backend's own file mutations** inside the boundary
     * (plan P3-6, [SandboxFileOps]).
     *
     * Only meaningful for a backend that confines a *process*: the mutation is handed to a sandboxed
     * child, so the backend process itself is not weakened. A backend that provisions rights for an
     * operator account but was never designed around this channel answers `false` and the file tools
     * keep their historical in-process path — reported as "policy layer only" instead of being
     * counted as covered.
     */
    val executesFileOps: Boolean get() = false

    /**
     * Whether a **product** child — the `browser4-cli` behind `b4.run` — can execute inside this
     * boundary.
     *
     * The CLI is not an arbitrary agent program: it is installed under the operator profile and
     * talks to *this backend* over `127.0.0.1`. A backend that cannot read the profile or reach the
     * host's loopback therefore cannot host it at all, and pretending otherwise made `b4.run` fail
     * with a confusing "CLI not found" instead of working (measured 2026-11-17: an AppContainer is
     * refused both the profile and loopback, so the CLI has nowhere to run and no one to talk to).
     *
     * `false` means the channel keeps its historical launch in the backend process and
     * `coding.permissions` states that it is outside the boundary — the same honest treatment
     * git/worktree/LSP get, rather than weakening the fence to fit one program.
     */
    val executesProductCommands: Boolean get() = true

    /**
     * Prepares [spec] for launch inside the boundary of [workspace].
     *
     * @param extraWritableRoots folders granted for this task besides the workspace
     *   (`agent run --add-dir`). A sandbox must grant them exactly like the workspace, otherwise a
     *   folder the string layer allows would be denied by the OS and the grant would be a lie.
     *   Implementations that do not provision ACLs (e.g. [OffSandbox]) ignore them.
     */
    fun prepare(
        spec: ProcessSpec,
        workspace: Path,
        allowNetwork: Boolean,
        extraWritableRoots: List<Path> = emptyList(),
    ): ProcessSpec

    /**
     * Operator-facing notes about this installation that would make a child fail for a reason the
     * sandbox cannot fix by itself, one line each (`coding.permissions` prints them).
     *
     * Today that is the stale AppContainer grant of [ContainerAceScan] (plan G9): a package-SID ACE
     * left behind by `windows-appcontainer` makes a whole toolchain tree unreadable to a
     * low-integrity child, and the only fix is an `icacls … /remove` the operator has to run.
     *
     * Backends that have nothing to say return an empty list; the notes are never a failure.
     */
    fun advisoryNotes(): List<String> = emptyList()

    /**
     * Releases the OS grants [prepare] made for the task that used [workspace].
     *
     * Called once when the task ends (normal completion, failure or cancellation). It exists because
     * the Windows backends are only as per-task as the security identity they use: a shared account,
     * a shared integrity level or a shared container SID lets one task write another task's
     * workspace, and a grant left on disk outlives the task that needed it. Releasing at task end is
     * what bounds that window (2026-11-20 review, issue S2).
     *
     * Contract:
     *  - **best effort** — a failed release is logged by the caller, never propagated: the task's
     *    result has already been decided and a cleanup failure must not turn it into an error;
     *  - **idempotent** — a second call is a no-op;
     *  - **narrow** — only what this task received is removed; other tasks' live grants must survive.
     */
    fun release(workspace: Path) {}
}

object OffSandbox : ExecutionSandbox {
    override val id: String = "off"

    override fun prepare(
        spec: ProcessSpec,
        workspace: Path,
        allowNetwork: Boolean,
        extraWritableRoots: List<Path>,
    ): ProcessSpec = spec
}

/**
 * Plan P1-2/P1-3/P3-2 — the Windows ACL sandbox.
 *
 * The default [provision] applies the full ACL matrix from the P0 scope list: the workspace, its
 * mirror roots and the folders granted to the task (`agent run --add-dir`) are writable, the
 * toolchain roots are read-only, and the operator profile is denied. The account is cached per
 * workspace **and per set of granted folders**, because provisioning runs `net user` and several
 * `icacls` calls; the cache keeps those to once per workspace rather than once per command. The
 * cache is bounded ([DEFAULT_CACHE_LIMIT]) because every task gets a fresh workspace: the least
 * recently used workspace is dropped and its password copy zeroed. The ACL grants are left in
 * place — they are out-of-band provisioning, not cache state.
 *
 * Right after provisioning, [selfCheck] runs the two-write probe (P3-2): a write inside the
 * workspace must succeed and a write outside it must be refused. A mismatch is a hard failure —
 * a sandbox that cannot write its own workspace or leak outside it must not be used silently.
 * The granted folders are not probed: they are passed to the same `icacls` matrix, so a folder the
 * account was not granted fails the first command loudly with an OS denial instead of passing
 * silently.
 *
 * A provisioning failure propagates as [SandboxUnavailableException]; nothing falls back to an
 * unwrapped launch.
 */
class WindowsAclSandbox(
    private val provision: (Path, List<Path>) -> SandboxAccount = defaultProvision(),
    private val selfCheck: (Path, SandboxAccount) -> String? = ::defaultSelfCheck,
    cacheLimit: Int = DEFAULT_CACHE_LIMIT,
    /** Command seam for the release path; injectable so a test never touches a real DACL. */
    private val runner: SandboxCommandRunner = SystemSandboxCommandRunner,
) : ExecutionSandbox {

    companion object {
        /**
         * Upper bound on cached workspaces.
         *
         * Every task gets a fresh `baseDir/workspace.<taskId>`, so without a bound the cache —
         * and one password copy per workspace — would grow for the life of the backend.
         */
        const val DEFAULT_CACHE_LIMIT = 64

        /** One `icacls /remove` per root; the ACL edit itself is fast, a hung console is not. */
        private const val RELEASE_TIMEOUT_SECONDS = 30L
    }

    private val logger = LoggerFactory.getLogger(WindowsAclSandbox::class.java)

    override val id: String = WindowsSandboxBootstrap.SANDBOX_ID

    /** The account and its credential are provisioned; nothing is applied by this check. */
    override fun isReady(): Boolean = runCatching { WindowsSandboxBootstrap().isReady() }.getOrDefault(false)

    private val cacheLimit: Int = cacheLimit.also { require(it > 0) { "cacheLimit must be positive: $it" } }

    /** One workspace's account and probe result, so both share the same cache key lifetime. */
    private class WorkspaceEntry(val account: SandboxAccount) {
        @Volatile
        var verified: Boolean = false
    }

    /**
     * Provisioning cache key: the workspace plus the folders granted to the task.
     *
     * The extra folders are part of the ACL matrix, so two tasks in the same workspace with
     * different `--add-dir` grants need different accounts; the key keeps that honest instead of
     * reusing a grant that does not cover the new folder.
     */
    private data class ProvisionKey(val workspace: Path, val extraRoots: List<Path>)

    private val entries = ConcurrentHashMap<ProvisionKey, WorkspaceEntry>()

    /** Access-ordered index over [entries], guarded by [evictionLock]; it only bounds the cache. */
    private val recency = LinkedHashMap<ProvisionKey, Unit>(16, 0.75f, true)

    private val evictionLock = Any()

    override fun prepare(
        spec: ProcessSpec,
        workspace: Path,
        allowNetwork: Boolean,
        extraWritableRoots: List<Path>,
    ): ProcessSpec {
        val root = workspace.toAbsolutePath().normalize()
        val extras = extraWritableRoots
            .map { it.toAbsolutePath().normalize() }
            .distinct()
            .sortedBy { it.toString() }
        val key = ProvisionKey(root, extras)
        val entry = entries.computeIfAbsent(key) { WorkspaceEntry(provision(it.workspace, it.extraRoots)) }
        touchAndTrim(key)
        if (!entry.verified) {
            // The write probe runs once per workspace, not once per command; the double check
            // makes two concurrent first commands observe the same result.
            synchronized(entry) {
                if (!entry.verified) {
                    val failure = selfCheck(root, entry.account)
                    if (failure != null) {
                        throw SandboxUnavailableException(
                            id,
                            "Windows sandbox unavailable: write probe failed: $failure",
                        )
                    }
                    logger.info("windows sandbox write probe passed: workspace={}", root)
                    entry.verified = true
                }
            }
        }
        logger.debug("windows sandbox account ready: account={} workspace={}", entry.account.userName, root)
        ExecutionSandboxes.bindTask(root, this)
        return spec.copy(sandboxAccount = entry.account)
    }

    /**
     * Removes the sandbox account's grants for the workspace this task used.
     *
     * The account is shared by every task of the installation, so a grant left behind is a standing
     * permission for the *next* task to write a directory it was never given: the workspace, its
     * mirror roots and the folders the task added are all revoked here. Only `grant` ACEs are
     * removed (`/remove:g`), never a deny the operator provisioned.
     *
     * Best effort by contract: a refused `icacls` is logged and the task result stands.
     */
    override fun release(workspace: Path) {
        val root = workspace.toAbsolutePath().normalize()
        val key = entries.keys.firstOrNull { sameSandboxPath(it.workspace, root) } ?: return
        val entry = entries.remove(key) ?: return
        synchronized(evictionLock) { recency.remove(key) }
        val roots = (listOf(key.workspace) + MirrorRoots.derive(key.workspace) + key.extraRoots).distinct()
        for (path in roots) {
            val result = runner.run(
                listOf("icacls", path.toString(), "/remove:g", entry.account.userName),
                RELEASE_TIMEOUT_SECONDS,
            )
            if (result.ok) {
                logger.info("windows sandbox released the account grant on {}", path)
            } else {
                logger.warn(
                    "could not revoke the sandbox account grant on {} (icacls exit={}): {}",
                    path, result.exitCode, result.output.lineSequence().firstOrNull { it.isNotBlank() },
                )
            }
        }
        entry.account.dispose()
    }

    /**
     * Marks [key] as most recently used and evicts the least recently used workspaces beyond the
     * bound. Evicted accounts are disposed, clearing their password copy; the ACL grants stay on
     * disk because they are out-of-band provisioning, not cache state.
     */
    private fun touchAndTrim(key: ProvisionKey) {
        val evicted = mutableListOf<ProvisionKey>()
        synchronized(evictionLock) {
            recency[key] = Unit
            while (recency.size > cacheLimit) {
                val eldest = recency.keys.firstOrNull() ?: break
                recency.remove(eldest)
                evicted.add(eldest)
            }
        }
        for (path in evicted) {
            entries.remove(path)?.account?.dispose()
        }
    }
}

/**
 * Default self-check (plan P3-2): write inside the workspace, then try the operator profile.
 *
 * The outside target comes from the same deny list the provisioning used, so the probe checks the
 * matrix that was actually applied rather than a guess.
 */
private fun defaultSelfCheck(workspace: Path, account: SandboxAccount): String? {
    val outside = SandboxProbe.outsideTarget(workspace)
        ?: return "no directory outside the workspace is available for the write probe"
    return SandboxProbe.verify(workspace, account, outside)
}

class SandboxUnavailableException(
    val sandboxId: String,
    message: String,
) : IllegalStateException(message)

/**
 * Default provisioning for `windows-acl` (plan P1-3).
 *
 * The writable set is the workspace plus the real paths its module junctions point at — writes the
 * file tools make through a mirror must also work for a build the sandbox runs — plus the folders
 * the task was granted (`agent run --add-dir`), which the string layer already allows. The
 * read-only set is the toolchain roots from the P0 scope list. The deny set is the operator
 * profile, applied as a folder-only deny so a workspace nested under it keeps its explicit grant.
 */
private fun defaultProvision(): (Path, List<Path>) -> SandboxAccount {
    val bootstrap = WindowsSandboxBootstrap()
    return { workspace, additionalDirs ->
        bootstrap.provision(
            workspace,
            writableRoots = (MirrorRoots.derive(workspace) + additionalDirs).toList(),
            readOnlyRoots = CodingComponents.sandboxReadOnlyRoots(),
            denyRoots = defaultDenyRoots(),
        )
    }
}

object ExecutionSandboxes {
    const val PROP_SANDBOX = "browser4.agent.sandbox"

    /** Backend ids the switch accepts (plan P3-1); implementations arrive with P1/P2. */
    val KNOWN_IDS: Set<String> = setOf(
        "off",
        "windows-acl",
        WindowsLowIntegritySandbox.SANDBOX_ID,
        WindowsAppContainerSandbox.SANDBOX_ID,
        "windows-restricted-token",
        "bwrap", "container", "nsjail", "sandbox-exec",
    )

    /**
     * Instances resolved so far, keyed by id.
     *
     * The resolution is read once per id rather than per command, so a [WindowsAclSandbox] keeps
     * its per-workspace account cache across commands instead of re-running `net user`/`icacls`
     * every time. A property change picks a different id key and therefore a fresh instance.
     */
    private val instances = ConcurrentHashMap<String, ExecutionSandbox>()

    private val logger = LoggerFactory.getLogger(ExecutionSandboxes::class.java)

    /**
     * The backend each live task's workspace was prepared under.
     *
     * [releaseTask] needs the *instance* that prepared the workspace: the low-integrity backend keeps
     * per-task roots in memory and the container backend keeps a per-task identity there, so
     * resolving a fresh instance at task end would have nothing to release. Entries are removed when
     * the task ends; a crash leaves them in memory and the process exits with them.
     */
    private val taskBackends = ConcurrentHashMap<Path, ExecutionSandbox>()

    /** Records that [sandbox] prepared [workspace], so its grants can be released at task end. */
    internal fun bindTask(workspace: Path, sandbox: ExecutionSandbox) {
        taskBackends[workspace.toAbsolutePath().normalize()] = sandbox
    }

    /**
     * Releases the OS grants of the task that used [workspace].
     *
     * Called from the task-end lifecycle. No-op when this process never prepared that workspace (a
     * resumed task whose backend already restarted, or a workspace that never ran a command), and
     * never throws: the task's outcome has been decided by the time cleanup runs.
     */
    fun releaseTask(workspace: Path) {
        val root = workspace.toAbsolutePath().normalize()
        val binding = taskBackends.entries.firstOrNull { sameSandboxPath(it.key, root) } ?: return
        taskBackends.remove(binding.key)
        runCatching { binding.value.release(binding.key) }.onFailure { e ->
            logger.warn(
                "could not release the OS sandbox grants of {} (backend={}): {}",
                binding.key, binding.value.id, e.message,
            )
        }
    }

    /**
     * Plan P3-1 — fail fast when the configured id is not a known backend.
     *
     * Called at coding-domain construction ([CodingComponents]), so a typo in
     * `-Dbrowser4.agent.sandbox` stops the start instead of surfacing on the first command.
     */
    fun validateConfigured() {
        of(configuredId())
    }

    /** The configured backend id, normalized; blank means `off`. */
    fun configuredId(): String = System.getProperty(PROP_SANDBOX, "off").trim().lowercase()

    /** Reads [PROP_SANDBOX]; hard-fails rather than degrading when the requested backend is absent. */
    fun current(): ExecutionSandbox =
        instances.computeIfAbsent(configuredId()) { of(it) }

    /**
     * The backend a task of [AgentMode] should use.
     *
     * Order: an operator''s explicit `-Dbrowser4.agent.sandbox` wins; otherwise the mode''s chain
     * ([IsolationProfile.backendChain]) is walked and the first *ready* backend is used; when the
     * chain offers nothing usable the installation default ([current]) applies — including `off`,
     * which is reported by `SandboxStatus` instead of being papered over.
     *
     * Readiness is what keeps this honest: `windows-appcontainer` is listed for browse but not
     * implemented, and `windows-acl` needs administrator provisioning, so neither is selected just
     * because it appears in the chain.
     *
     * @param chain the candidate ids, in order. Defaults to this host's chain; a caller that reasons
     *   about another platform (a test of the Windows rules on a Linux CI, say) passes
     *   `IsolationProfile.backendChain(mode, osName = ...)` instead of mutating `os.name`.
     */
    fun resolveForMode(
        mode: AgentMode,
        ready: (String) -> Boolean = { id -> runCatching { of(id).isReady() }.getOrDefault(false) },
        instantiate: (String) -> ExecutionSandbox = { id -> of(id) },
        chain: List<String> = IsolationProfile.backendChain(mode),
    ): ExecutionSandbox {
        SandboxDefaults.operatorConfiguredId()?.let { explicit ->
            return instantiate(explicit)
        }
        for (id in chain) {
            if (!ready(id)) continue
            val sandbox = instantiate(id)
            // A backend that must not be a default (see [autoSelectable]) is skipped rather than
            // handed to a task that never asked for it.
            if (!sandbox.autoSelectable) continue
            return sandbox
        }
        return runCatching { current() }.getOrDefault(OffSandbox)
    }

    fun of(id: String): ExecutionSandbox = when (val normalized = id.trim().lowercase()) {
        "", "off" -> OffSandbox
        WindowsSandboxBootstrap.SANDBOX_ID -> WindowsAclSandbox()
        WindowsLowIntegritySandbox.SANDBOX_ID -> WindowsLowIntegritySandbox()
        WindowsAppContainerSandbox.SANDBOX_ID -> WindowsAppContainerSandbox()
        in KNOWN_IDS -> throw SandboxUnavailableException(
            normalized,
            "Sandbox '$normalized' is known but not implemented in this build " +
                "(browse-mode isolation is the AppContainer backend, still to come). " +
                "Use 'off', '${WindowsLowIntegritySandbox.SANDBOX_ID}' or " +
                "'${WindowsSandboxBootstrap.SANDBOX_ID}'; the sandbox never falls back to an unwrapped launch.",
        )
        else -> throw SandboxUnavailableException(
            normalized,
            "Unknown sandbox '$normalized'. Known ids: ${KNOWN_IDS.joinToString(", ")}. " +
                "Fix -D$PROP_SANDBOX or remove it; an invalid value stops the start.",
        )
    }
}

/**
 * Environment construction shared by `coding.shell` and `coding.runCode` (plan C1/C2).
 *
 * The child gets **only** [WHITELISTED] names from the backend process plus variables the caller
 * declared explicitly (per-command `env`, session `setEnv`). Long-lived credentials are therefore
 * not inherited: an inherited `DEEPSEEK_API_KEY` never reaches an agent-spawned process. An
 * explicitly declared secret still passes — that is a deliberate operator/agent decision — and is
 * recorded as an audit event rather than silently dropped.
 */
object SandboxEnvironment {

    private const val TOOL = "coding.env"

    /**
     * Names a build or runtime legitimately needs. Secrets are never listed here; the list is
     * about toolchains (PATH/JAVA_HOME/maven/gradle/python/node) and OS plumbing (temp dirs,
     * COMSPEC, PATHEXT).
     */
    private val WHITELISTED: Set<String> = setOf(
        // POSIX
        "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "SHELL", "TERM", "TMPDIR",
        // JVM / build toolchains
        "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "MAVEN_OPTS", "GRADLE_HOME", "GRADLE_USER_HOME",
        "KOTLIN_HOME", "ANT_HOME", "SBT_HOME", "SCALA_HOME",
        // Language runtimes
        "GOROOT", "GOPATH", "CARGO_HOME", "RUSTUP_HOME", "NODE_HOME", "NVM_HOME", "NVM_SYMLINK",
        "PYTHONPATH", "PYTHONHOME", "VIRTUAL_ENV", "CONDA_PREFIX",
        // Windows plumbing
        "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "PATHEXT", "TEMP", "TMP",
        "USERPROFILE", "APPDATA", "LOCALAPPDATA", "PROGRAMDATA", "HOMEDRIVE", "HOMEPATH",
        "PROGRAMFILES", "PROGRAMFILES(X86)", "COMMONPROGRAMFILES", "COMMONPROGRAMFILES(X86)",
        "NUMBER_OF_PROCESSORS", "PROCESSOR_ARCHITECTURE", "PROCESSOR_IDENTIFIER",
    )

    /**
     * Secret-shaped names. Used only to decide whether an explicit passthrough deserves an audit
     * event and to document the intent — inherited values with these names are already excluded by
     * the whitelist.
     */
    private val SECRET_MARKERS: List<String> = listOf(
        "API_KEY", "APIKEY", "ACCESS_KEY", "SECRET", "TOKEN", "PASSWORD", "PASSWD",
        "CREDENTIAL", "PRIVATE_KEY", "AUTH",
    )

    fun isWhitelisted(name: String): Boolean = name.uppercase() in WHITELISTED

    /**
     * Variables every child gets, independent of the whitelist, unless the caller declares them.
     *
     * `PYTHONIOENCODING` fixes Python's **stdio** encoding: on a zh-CN Windows host
     * `print('中文')` writes GBK bytes into a pipe the agent reads as UTF-8, so the text either
     * arrived as mojibake or (with per-line decoding) as one misread word. It is deliberately
     * narrow — `PYTHONUTF8=1` would also switch how scripts *open files*, which changes the
     * behaviour of working tasks, so it is not forced here.
     */
    private val DEFAULTS: Map<String, String> = mapOf("PYTHONIOENCODING" to "utf-8")

    fun isSecretName(name: String): Boolean {
        val upper = name.uppercase()
        return SECRET_MARKERS.any { upper.contains(it) }
    }

    /**
     * @param inherited the backend process environment (normally `System.getenv()`)
     * @param explicit variables the caller declared for this launch; they win over the whitelist
     */
    fun build(
        inherited: Map<String, String>,
        explicit: Map<String, String> = emptyMap(),
    ): MutableMap<String, String> {
        val env = LinkedHashMap<String, String>()
        for ((name, value) in inherited) {
            if (isWhitelisted(name)) env[name] = value
        }
        for ((name, value) in DEFAULTS) {
            env[name] = value
        }
        for ((name, value) in explicit) {
            if (isSecretName(name)) {
                PermissionAudit.writeEvent(
                    TOOL,
                    PermissionAudit.CODE_ENV_PASSTHROUGH,
                    "explicitly passed '$name' to a child process",
                )
            }
            env[name] = value
        }
        return env
    }
}

/**
 * Guards the `<workspace>/.b4scratch` tree, which the **backend** writes to with its own rights.
 *
 * `SandboxedFileOps` stages payloads and its bridge script there, the coding backends create and label
 * `.b4scratch/tmp` there, and the probes write there. A link inside that tree therefore does two things
 * the agent must not be able to do: it redirects a Medium write outside the workspace, and it makes the
 * backend lower the mandatory label of whatever the link names — which is the whole boundary.
 *
 * `Files.isSymbolicLink` does not report a Windows junction, so a link is detected the only
 * cross-platform way there is (the same test [MirrorRoots] uses): an entry whose real path is not its
 * own absolute path. Every component from the workspace down is checked, because the link may be
 * `.b4scratch` itself rather than one of its children.
 */
internal object ScratchBoundary {

    /**
     * `null` when [target] may be used as the task's scratch path, otherwise the reason it may not.
     *
     * A missing component is acceptable: a directory that does not exist yet cannot be a link.
     */
    fun refusal(workspace: Path, target: Path): String? {
        val absoluteRoot = workspace.toAbsolutePath().normalize()
        val realRoot = runCatching { workspace.toRealPath() }.getOrElse {
            // A workspace that does not exist yet holds no link; the caller creates it.
            return if (Files.exists(absoluteRoot)) "cannot resolve the workspace '$absoluteRoot': ${it.message}" else null
        }
        val normalized = target.toAbsolutePath().normalize()
        if (!normalized.startsWith(absoluteRoot)) {
            return "'$normalized' is not inside the workspace '$absoluteRoot'"
        }
        var cursor = absoluteRoot
        for (part in absoluteRoot.relativize(normalized)) {
            cursor = cursor.resolve(part)
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue
            val real = runCatching { cursor.toRealPath() }.getOrNull()
                ?: return "cannot resolve '$cursor'"
            if (!samePath(real, cursor)) {
                return "'$cursor' is a link to '$real', which leaves the task scratch tree"
            }
            if (!real.startsWith(realRoot)) {
                return "'$cursor' resolves outside the workspace, to '$real'"
            }
        }
        return null
    }

    /** Windows path comparison is case-insensitive; `toRealPath` returns the on-disk casing. */
    private fun samePath(a: Path, b: Path): Boolean =
        a.toString().equals(b.toAbsolutePath().normalize().toString(), ignoreCase = true)
}

/** Single process-launch point for both execution paths, so the sandbox spec is actually applied. */
object ProcessLauncher {
    fun start(spec: ProcessSpec, redirectErrorStream: Boolean = false): Process {
        // A token-based backend owns the launch (it holds a live handle, not an identity) and wins
        // over the account path. An unavailable backend throws instead of degrading.
        spec.sandboxLauncher?.let { launcher ->
            return launcher.start(spec, redirectErrorStream)
        }
        spec.sandboxAccount?.let { account ->
            return WindowsTokenLauncher.start(spec, account, redirectErrorStream)
        }
        val builder = ProcessBuilder(spec.command)
            .directory(spec.directory.toFile())
            .redirectErrorStream(redirectErrorStream)
        builder.environment().clear()
        builder.environment().putAll(spec.environment)
        return builder.start()
    }
}

/** Case-insensitive on Windows so a task-end release finds the binding its prepare recorded. */
private fun sameSandboxPath(a: Path, b: Path): Boolean {
    val left = a.toAbsolutePath().normalize().toString()
    val right = b.toAbsolutePath().normalize().toString()
    return if (System.getProperty("os.name").lowercase().contains("win")) {
        left.equals(right, ignoreCase = true)
    } else {
        left == right
    }
}
