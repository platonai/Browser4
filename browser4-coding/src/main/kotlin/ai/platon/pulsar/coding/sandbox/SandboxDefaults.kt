package ai.platon.pulsar.coding.sandbox

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Chooses, migrates and persists the sandbox backend of this installation.
 *
 * History: plan P3-4 (decision D1) turned the boundary on for **new** installations only, because
 * enabling it underneath an existing installation could surface an ACL miss as a failed build. The
 * 2026-11-16 review of agent run `966a5f2e` measured what that asymmetry costs: an old installation
 * keeps the historical `off`, and with `off` the string layer is the only thing left. That run left
 * the workspace through `python -c`, a `powershell -File` script and `%VAR%` expansions, while every
 * refusal still claimed *"the OS sandbox enforces the boundary"*. `off` was never a decision on
 * those installations — nobody was asked.
 *
 * The default therefore **upgrades** an installation that has not decided under the current marker
 * schema:
 *
 *  - an explicit `-Dbrowser4.agent.sandbox=<id>` always wins and is never persisted;
 *  - a marker written by this schema (`v2:<id>`) is replayed verbatim — including `v2:off`, which
 *    is how an operator keeps the historical behaviour;
 *  - a legacy (bare) marker that already names a boundary (`windows-acl`) is honoured and pinned;
 *  - a legacy bare `off` marker, a data directory with no marker, and a fresh installation all get
 *    [defaultForPlatform]; the first two log a warning naming the property that restores `off`,
 *    because they upgrade a decision that was made by absence.
 *
 * The upgrade is **gated on the backend being ready** ([isReady]): the Windows backend needs a
 * provisioned low-privilege account and its credential, and switching it on without them would turn
 * every `coding.shell` command into a hard [SandboxUnavailableException] — a self-inflicted outage
 * rather than a boundary. When the backend is not ready the installation stays `off`, the state is
 * reported loudly (that warning, plus the shell's own refusal text), and **nothing is pinned**, so
 * the next start re-probes and upgrades automatically once an administrator has provisioned it.
 *
 * Where the backend *is* ready, the upgrade is fail-closed by construction: an unavailable or
 * misconfigured sandbox raises [SandboxUnavailableException] and never falls back to an unwrapped
 * launch (see [ExecutionSandbox]) — the worst case is a loud failure, never a silent hole.
 *
 * This runs from the application entry points, not from the library default: unit tests and
 * embedded users keep `off` unless they ask for a sandbox explicitly.
 */
object SandboxDefaults {

    private val logger = LoggerFactory.getLogger(SandboxDefaults::class.java)

    /** Records the decision next to the data directory; its content is `<version>:<backend id>`. */
    internal const val MARKER = "sandbox-default"

    /**
     * Marker schema version.
     *
     * `2` is the first schema that distinguishes "the installation chose `off`" from "the
     * installation predates the boundary": only a `v2` marker is replayed as an explicit decision,
     * a bare legacy id is treated as the old automatic default. Bump this only together with a
     * migration rule, otherwise the next start re-interprets existing decisions.
     */
    internal const val MARKER_VERSION = 2

    private const val OFF = "off"

    /**
     * The id an **operator** set, or `null` when the effective value came from [apply].
     *
     * The difference matters for per-mode selection: `apply` publishes the installation default as
     * the system property, so reading the property alone cannot tell "the operator demanded
     * `windows-acl`" from "that is just the default this install happens to have". Only the former
     * must override the mode's backend chain.
     */
    @Volatile
    private var defaultApplied: Boolean = false

    @Volatile
    private var operatorExplicit: String? = null

    /** See [operatorExplicit]; callable before [apply] (library/tests) reads the property directly. */
    fun operatorConfiguredId(): String? =
        if (defaultApplied) {
            operatorExplicit
        } else {
            System.getProperty(ExecutionSandboxes.PROP_SANDBOX)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.lowercase()
        }

    private val MARKER_PATTERN = Regex("^v(\\d+):(.+)$")

    /**
     * Applies the installation default and publishes it as [ExecutionSandboxes.PROP_SANDBOX].
     *
     * @param dataDir the installation's data directory (`<browser4.data.dir>/.browser4`)
     * @return the effective backend id
     */
    fun apply(dataDir: Path = defaultDataDir()): String =
        apply(dataDir, defaultForPlatform(), ::defaultBackendReady)

    /**
     * Same as [apply], with the platform backend and its readiness probe injected so every rule can
     * be tested on every host instead of only on a provisioned Windows machine.
     *
     * @param platformDefault the backend a fresh installation of this platform would use
     * @param backendReady whether that backend can actually run here (see [isReady])
     */
    internal fun apply(
        dataDir: Path,
        platformDefault: String,
        backendReady: (String) -> Boolean = ::defaultBackendReady,
    ): String {
        val explicit = System.getProperty(ExecutionSandboxes.PROP_SANDBOX)?.trim()?.takeIf { it.isNotEmpty() }
        operatorExplicit = explicit?.lowercase()
        defaultApplied = true
        if (explicit != null) {
            val id = explicit.lowercase()
            logger.info("sandbox configured explicitly: {}", id)
            return id
        }

        val marker = dataDir.resolve(MARKER)
        val stored = readDecision(marker)

        // A decision written by this schema is replayed as is: `v2:off` is an operator's choice and
        // must not be upgraded again on the next start.
        if (stored != null && stored.version >= MARKER_VERSION) {
            publish(stored.id)
            logger.info("sandbox default restored: {} (marker={})", stored.id, marker)
            return stored.id
        }

        // A legacy marker that names a boundary keeps it; pinning the id stops a later version from
        // re-interpreting the same file as "undecided".
        if (stored != null && stored.id != OFF) {
            persist(marker, stored.id)
            publish(stored.id)
            logger.info("sandbox default restored from the legacy marker: {} (marker={})", stored.id, marker)
            return stored.id
        }

        val id = platformDefault.lowercase()
        if (id == OFF) {
            persist(marker, id)
            // Nothing to publish: `off` is already the value `configuredId()` falls back to, and
            // publishing it here would make this very decision look like an explicit operator
            // property to a later [apply] in the same JVM.
            logger.info(
                "sandbox default chosen: {} (no backend is implemented for this platform yet, " +
                    "marker={})", id, marker,
            )
            return id
        }

        if (!backendReady(id)) {
            // Fail-safe, not fail-closed: an unprovisionable backend would fail *every* command, so
            // the installation stays off — loudly, and without pinning the decision, so provisioning
            // the account later upgrades on the next start.
            logger.warn(
                "sandbox boundary NOT enforced: '{}' is the platform default but it is not usable on " +
                    "this machine yet (provision the account and its credential, see " +
                    "docs/config.md). -D{}=off accepts that state explicitly. " +
                    "Until then a coding.shell command that runs its own code is not confined to the " +
                    "workspace.",
                id, ExecutionSandboxes.PROP_SANDBOX,
            )
            return OFF
        }

        persist(marker, id)
        publish(id)
        when {
            stored == null -> logger.info(
                "sandbox default chosen: {} (no marker; dataDir={})", id, dataDir,
            )
            // A legacy `off` was the automatic default for every installation that predates the
            // boundary; it is upgraded and the operator is told how to keep it.
            else -> logger.warn(
                "sandbox default upgraded from the legacy 'off' to '{}': the boundary was off only " +
                    "because this installation predates it, not because it was chosen. " +
                    "Pass -D{}=off to keep the historical behaviour (marker={})",
                id, ExecutionSandboxes.PROP_SANDBOX, marker,
            )
        }
        return id
    }

    /**
     * The backend a fresh installation starts with; only a working backend may be a default.
     *
     * Still `windows-acl`: `windows-low-il` (2026-11-17) confines writes and credential reads with no
     * account and no administrator, but it is a **per-mode** backend — making it the global default
     * before the mode wiring exists would also confine browse tasks, whose `b4` CLI legitimately
     * writes `~/.browser4` state (Medium objects). It is therefore opt-in today
     * (`-Dbrowser4.agent.sandbox=windows-low-il`, recommended for coding-only installations) and
     * becomes the coding default together with the `AgentMode` wiring (P1.5).
     */
    internal fun defaultForPlatform(osName: String = System.getProperty("os.name", "")): String =
        if (osName.lowercase().contains("win")) WindowsSandboxBootstrap.SANDBOX_ID else OFF

    /**
     * Whether the backend can be used here right now. `windows-acl` asks the bootstrap, which
     * checks only the preconditions (account + credential) and applies nothing; every other id is
     * either [OFF] or not implemented in this build.
     */
    private fun defaultBackendReady(id: String): Boolean = when (id.lowercase()) {
        OFF -> true
        WindowsLowIntegritySandbox.SANDBOX_ID ->
            runCatching { WindowsLowIntegritySandbox().isReady() }.getOrDefault(false)
        WindowsSandboxBootstrap.SANDBOX_ID ->
            runCatching { WindowsSandboxBootstrap().isReady() }.getOrDefault(false)
        else -> false
    }

    /**
     * The installation's data directory, resolved the same way the rest of the product resolves it
     * (`<browser4.data.dir or user.home>/.browser4`).
     */
    fun defaultDataDir(): Path = Path.of(
        System.getProperty("browser4.data.dir", System.getProperty("user.home")),
        ".browser4",
    )

    /** A persisted decision and the marker schema that wrote it (`0` = the legacy bare id). */
    private data class Decision(val version: Int, val id: String)

    private fun readDecision(marker: Path): Decision? {
        if (!Files.isRegularFile(marker)) return null
        val text = runCatching { Files.readString(marker).trim() }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val match = MARKER_PATTERN.matchEntire(text)
        if (match != null) {
            val version = match.groupValues[1].toIntOrNull() ?: 0
            return Decision(version, match.groupValues[2].trim().lowercase())
        }
        // The pre-migration format: the file held the bare backend id.
        return Decision(0, text.lowercase())
    }

    private fun persist(marker: Path, id: String) {
        runCatching {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, "v$MARKER_VERSION:$id")
        }.onFailure { logger.warn("Failed to persist the sandbox default in {}: {}", marker, it.message) }
    }

    private fun publish(id: String) {
        System.setProperty(ExecutionSandboxes.PROP_SANDBOX, id)
    }
}
