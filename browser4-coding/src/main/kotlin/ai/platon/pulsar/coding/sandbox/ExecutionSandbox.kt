package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.CodingComponents
import ai.platon.pulsar.coding.MirrorRoots
import ai.platon.pulsar.coding.PermissionAudit
import org.slf4j.LoggerFactory
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
 */
data class ProcessSpec(
    val command: List<String>,
    val directory: Path,
    val environment: Map<String, String>,
    val sandboxAccount: SandboxAccount? = null,
)

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

    fun prepare(spec: ProcessSpec, workspace: Path, allowNetwork: Boolean): ProcessSpec
}

object OffSandbox : ExecutionSandbox {
    override val id: String = "off"

    override fun prepare(spec: ProcessSpec, workspace: Path, allowNetwork: Boolean): ProcessSpec = spec
}

/**
 * Plan P1-2/P1-3/P3-2 — the Windows ACL sandbox.
 *
 * The default [provision] applies the full ACL matrix from the P0 scope list: the workspace and its
 * mirror roots are writable, the toolchain roots are read-only, and the operator profile is denied.
 * The account is cached per workspace, because provisioning runs `net user` and several `icacls`
 * calls; the cache keeps those to once per workspace rather than once per command. The cache is
 * bounded ([DEFAULT_CACHE_LIMIT]) because every task gets a fresh workspace: the least recently
 * used workspace is dropped and its password copy zeroed. The ACL grants are left in place — they
 * are out-of-band provisioning, not cache state.
 *
 * Right after provisioning, [selfCheck] runs the two-write probe (P3-2): a write inside the
 * workspace must succeed and a write outside it must be refused. A mismatch is a hard failure —
 * a sandbox that cannot write its own workspace or leak outside it must not be used silently.
 *
 * A provisioning failure propagates as [SandboxUnavailableException]; nothing falls back to an
 * unwrapped launch.
 */
class WindowsAclSandbox(
    private val provision: (Path) -> SandboxAccount = defaultProvision(),
    private val selfCheck: (Path, SandboxAccount) -> String? = ::defaultSelfCheck,
    cacheLimit: Int = DEFAULT_CACHE_LIMIT,
) : ExecutionSandbox {

    companion object {
        /**
         * Upper bound on cached workspaces.
         *
         * Every task gets a fresh `baseDir/workspace.<taskId>`, so without a bound the cache —
         * and one password copy per workspace — would grow for the life of the backend.
         */
        const val DEFAULT_CACHE_LIMIT = 64
    }

    private val logger = LoggerFactory.getLogger(WindowsAclSandbox::class.java)

    override val id: String = WindowsSandboxBootstrap.SANDBOX_ID

    private val cacheLimit: Int = cacheLimit.also { require(it > 0) { "cacheLimit must be positive: $it" } }

    /** One workspace's account and probe result, so both share the same cache key lifetime. */
    private class WorkspaceEntry(val account: SandboxAccount) {
        @Volatile
        var verified: Boolean = false
    }

    private val entries = ConcurrentHashMap<Path, WorkspaceEntry>()

    /** Access-ordered index over [entries], guarded by [evictionLock]; it only bounds the cache. */
    private val recency = LinkedHashMap<Path, Unit>(16, 0.75f, true)

    private val evictionLock = Any()

    override fun prepare(spec: ProcessSpec, workspace: Path, allowNetwork: Boolean): ProcessSpec {
        val root = workspace.toAbsolutePath().normalize()
        val entry = entries.computeIfAbsent(root) { WorkspaceEntry(provision(it)) }
        touchAndTrim(root)
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
        return spec.copy(sandboxAccount = entry.account)
    }

    /**
     * Marks [root] as most recently used and evicts the least recently used workspaces beyond the
     * bound. Evicted accounts are disposed, clearing their password copy; the ACL grants stay on
     * disk because they are out-of-band provisioning, not cache state.
     */
    private fun touchAndTrim(root: Path) {
        val evicted = mutableListOf<Path>()
        synchronized(evictionLock) {
            recency[root] = Unit
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
 * file tools make through a mirror must also work for a build the sandbox runs. The read-only set is
 * the toolchain roots from the P0 scope list. The deny set is the operator profile, applied as a
 * folder-only deny so a workspace nested under it keeps its explicit grant.
 */
private fun defaultProvision(): (Path) -> SandboxAccount {
    val bootstrap = WindowsSandboxBootstrap()
    return { workspace ->
        bootstrap.provision(
            workspace,
            writableRoots = MirrorRoots.derive(workspace).toList(),
            readOnlyRoots = CodingComponents.sandboxReadOnlyRoots(),
            denyRoots = defaultDenyRoots(),
        )
    }
}

object ExecutionSandboxes {
    const val PROP_SANDBOX = "browser4.agent.sandbox"

    /** Backend ids the switch accepts (plan P3-1); implementations arrive with P1/P2. */
    val KNOWN_IDS: Set<String> = setOf(
        "off", "windows-acl", "bwrap", "container", "nsjail", "sandbox-exec",
    )

    /**
     * Instances resolved so far, keyed by id.
     *
     * The resolution is read once per id rather than per command, so a [WindowsAclSandbox] keeps
     * its per-workspace account cache across commands instead of re-running `net user`/`icacls`
     * every time. A property change picks a different id key and therefore a fresh instance.
     */
    private val instances = ConcurrentHashMap<String, ExecutionSandbox>()

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

    fun of(id: String): ExecutionSandbox = when (val normalized = id.trim().lowercase()) {
        "", "off" -> OffSandbox
        WindowsSandboxBootstrap.SANDBOX_ID -> WindowsAclSandbox()
        in KNOWN_IDS -> throw SandboxUnavailableException(
            normalized,
            "Sandbox '$normalized' is known but not implemented in this build " +
                "(the Linux/macOS backends arrive with P2). Use 'off' or 'windows-acl'; " +
                "the sandbox never falls back to an unwrapped launch.",
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

/** Single process-launch point for both execution paths, so the sandbox spec is actually applied. */
object ProcessLauncher {
    fun start(spec: ProcessSpec, redirectErrorStream: Boolean = false): Process {
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
