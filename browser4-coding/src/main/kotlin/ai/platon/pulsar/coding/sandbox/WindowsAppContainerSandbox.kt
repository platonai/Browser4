package ai.platon.pulsar.coding.sandbox

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * `windows-appcontainer` — the **browse-mode** boundary: nothing outside the workspace is reachable.
 *
 * Browse tasks are browser goals. The model needs the Browser4 CLI, the pages it drives and a place
 * to put scripts — and nothing else. That is a *read and write* confinement, which an integrity label
 * cannot express (it narrows writes only), so this backend uses the stronger Windows mechanism:
 *
 *  - the child runs inside a per-user **AppContainer** ([Win32.APP_CONTAINER_NAME]); the container SID
 *    is granted `(OI)(CI)M` on the workspace (and the folders the task added) and nothing else, so the
 *    user profile, `~/.ssh`, `D:\` and the rest are simply not in its access set;
 *  - **no capabilities are declared**, so the container has **no network at all** — the browser lives
 *    in the backend, outside the container, which is exactly `NetPolicy.BrowserOnly`;
 *  - the profile is created with `CreateAppContainerProfile`, which needs **no administrator**: it is
 *    per-user registry state, and an existing profile is derived rather than recreated.
 *
 * The child's `USERPROFILE`/`APPDATA`/`LOCALAPPDATA`/`TEMP` point into `<workspace>/.b4scratch`, so an
 * ordinary tool that wants a writable home finds one instead of failing on the real profile.
 *
 * Availability is proven, not assumed: the first command in a workspace runs a three-part probe
 * (write inside must succeed; a write in `%SystemRoot%\Temp` and a credential listing must fail) and a
 * mismatch is a [SandboxUnavailableException] — never a silent fallback to an unconfined launch.
 */
class WindowsAppContainerSandbox(
    private val runner: SandboxCommandRunner = SystemSandboxCommandRunner,
    /** Probe hook: `(workspace) -> failure message`, `null` when the boundary holds. */
    private val probe: ((Path) -> String?)? = null,
    /** Where "already granted" markers live; injectable so tests keep the user data dir clean. */
    private val markerDir: Path? = SandboxDefaults.defaultDataDir().resolve("sandbox").resolve("appcontainer"),
) : ExecutionSandbox {

    companion object {
        /** Backend id in `browser4.agent.sandbox` and [ExecutionSandboxes.KNOWN_IDS]. */
        const val SANDBOX_ID = "windows-appcontainer"

        /** Opt-in switch for selecting this backend from a mode's chain (see [autoSelectable]). */
        const val PROP_BROWSE_OPT_IN = "browser4.agent.sandbox.browse"

        private const val GRANT_TIMEOUT_SECONDS = 120L
        private const val PROBE_TIMEOUT_SECONDS = 30L
        private const val PROBE_TIMEOUT_EXIT = 42

        private val logger = LoggerFactory.getLogger(WindowsAppContainerSandbox::class.java)
    }

    /**
     * Roots already granted **by this instance**.
     *
     * Per instance, not per JVM: a test (or an operator) can swap the runner, and a shared cache would
     * then report a grant that never happened — which is exactly how `realContainerIsConfined` lost its
     * data-directory grant once.
     */
    private val grantedRoots = ConcurrentHashMap<Path, Unit>()

    override val id: String = SANDBOX_ID

    /** No capabilities means no egress: the container cannot open a socket. */
    override val enforcesNetwork: Boolean = true

    /**
     * The backend's own file mutations are handed to a container child (plan P3-6): the workspace is
     * the only tree the container SID was granted, so `coding.write` cannot reach the profile either.
     * See [SandboxedFileOps].
     */
    override val executesFileOps: Boolean = true

    /**
     * `b4.run` cannot run here, by construction: `browser4-cli` lives under the operator profile (not in
     * the container's access set) and would have to reach this backend over `127.0.0.1`, which a
     * capability-less container is refused (measured 2026-11-17, `realContainerNetworkAndStagedBinary`).
     *
     * Copying the CLI into the workspace fixes only the first half (a staged binary does run), and
     * granting `privateNetworkClientServer` to fix the second would open every host-local service —
     * so the channel keeps its historical launch in the backend process and `coding.permissions` says
     * it is outside the boundary (decision 2026-11-17, design §14.2.1).
     */
    override val executesProductCommands: Boolean = false

    /**
     * Opt-in only for now (2026-11-17).
     *
     * The confinement itself is proven (workspace-only writes, profile unreachable, no egress), but
     * `browser4-cli` — what `b4.run` and most browse tasks drive — lives **under the user profile**,
     * which a container cannot read at all. Making this the browse default before that is solved would
     * break the very tasks the mode exists for, so a mode's chain skips it and the operator opts in:
     *
     * ```
     * -Dbrowser4.agent.sandbox.browse=on               # let the browse chain pick it
     * -Dbrowser4.agent.sandbox=windows-appcontainer    # or select it outright
     * ```
     */
    override val autoSelectable: Boolean
        get() {
            val raw = System.getProperty(PROP_BROWSE_OPT_IN, "off").trim().lowercase()
            return raw == "on" || raw == "true" || raw == "1" || raw == SANDBOX_ID
        }

    /** The container SID: created on first use, per user, no administrator involved. */
    private val container: ContainerIdentity by lazy { ensureContainer() }

    /**
     * Process-tree lifetime, exactly like the Low-integrity backend: a backend exit takes its
     * container children with it.
     */
    private val job: WindowsJob by lazy { WindowsJob("browser4-$SANDBOX_ID") }

    private val verifiedWorkspaces = ConcurrentHashMap<Path, Unit>()

    override fun prepare(
        spec: ProcessSpec,
        workspace: Path,
        allowNetwork: Boolean,
        extraWritableRoots: List<Path>,
    ): ProcessSpec {
        requireWindows()
        val root = workspace.toAbsolutePath().normalize()
        if (!Files.isDirectory(root)) {
            fail("workspace '$root' is not a directory")
        }
        val container = container
        grant(root, container, required = true)
        extraWritableRoots.map { it.toAbsolutePath().normalize() }.forEach { grant(it, container, required = true) }

        val environment = sandboxEnvironment(spec.environment, root)
        if (!verifiedWorkspaces.contains(root)) {
            val failure = if (probe != null) probe.invoke(root) else defaultProbe(root)
            if (failure != null) {
                fail("appcontainer probe failed: $failure")
            }
            verifiedWorkspaces[root] = Unit
            logger.info("windows-appcontainer probe passed: workspace={} sid={}", root, container.sidString)
        }
        if (allowNetwork) {
            logger.debug("windows-appcontainer has no network capability; net was requested but cannot be granted")
        }
        val sid = container.sid
        return spec.copy(
            environment = environment,
            sandboxLauncher = SandboxLauncher { prepared, redirect ->
                WindowsTokenLauncher.startInAppContainer(prepared, sid, redirect, job)
            },
        )
    }

    /**
     * Whether the backend can run here: Windows and a container SID can be obtained.
     *
     * Deriving the SID creates the profile on first use — per-user state, no elevation — so this is a
     * cheap idempotent call rather than a provisioning step.
     */
    override fun isReady(): Boolean = runCatching {
        if (!isWindows()) return false
        container
        true
    }.getOrElse { e ->
        logger.info("windows-appcontainer is not ready: {}", e.message)
        false
    }

    // ------------------------------------------------------------------
    // Container identity
    // ------------------------------------------------------------------

    private class ContainerIdentity(val sid: Pointer, val sidString: String)

    private fun ensureContainer(): ContainerIdentity {
        val ref = PointerByReference()
        val created = Win32.userenv.CreateAppContainerProfile(
            WString(Win32.APP_CONTAINER_NAME),
            WString("Browser4 agent (browse)"),
            WString("Confines a browse-mode agent's commands to the task workspace"),
            null,
            0,
            ref,
        )
        val sid = when (created) {
            0 -> ref.value ?: fail("CreateAppContainerProfile returned no SID")
            Win32.HRESULT_ALREADY_EXISTS -> {
                val derived = PointerByReference()
                val hr = Win32.userenv.DeriveAppContainerSidFromAppContainerName(
                    WString(Win32.APP_CONTAINER_NAME), derived,
                )
                if (hr != 0) {
                    fail("DeriveAppContainerSidFromAppContainerName failed (hr=0x${hr.toUInt().toString(16)})")
                }
                derived.value ?: fail("DeriveAppContainerSidFromAppContainerName returned no SID")
            }
            else -> fail("CreateAppContainerProfile failed (hr=0x${created.toUInt().toString(16)})")
        }
        val sidString = runCatching {
            val stringRef = PointerByReference()
            if (!Win32.advapi32.ConvertSidToStringSidW(sid, stringRef)) return@runCatching null
            val pointer = stringRef.value ?: return@runCatching null
            try {
                pointer.getWideString(0)
            } finally {
                Win32.kernel32.LocalFree(pointer)
            }
        }.getOrNull() ?: fail("could not render the container SID as a string")
        // The SID itself is owned by the container profile and lives as long as the process does.
        logger.info("windows-appcontainer ready: name={} sid={}", Win32.APP_CONTAINER_NAME, sidString)
        return ContainerIdentity(sid, sidString)
    }

    // ------------------------------------------------------------------
    // ACL grants
    // ------------------------------------------------------------------

    /**
     * Grants the container `(OI)(CI)M` on [path] (deep, inherited by new members).
     *
     * `icacls` accepts the `*S-1-15-2-…` SID form, which is why the container needs no readable name.
     * The grant is recorded next to the data directory so a restart does not re-walk a workspace.
     */
    private fun grant(path: Path, container: ContainerIdentity, required: Boolean) {
        if (!Files.exists(path)) return
        val normalized = path.toAbsolutePath().normalize()
        if (grantedRoots.putIfAbsent(normalized, Unit) != null) return
        if (isGranted(normalized)) return
        val result = runner.run(
            listOf("icacls", normalized.toString(), "/grant", "*${container.sidString}:(OI)(CI)(M)"),
            GRANT_TIMEOUT_SECONDS,
        )
        if (result.ok) {
            rememberGrant(normalized)
            return
        }
        grantedRoots.remove(normalized)
        val detail = "icacls exit=${result.exitCode}: ${firstLine(result.output)}"
        if (required) {
            fail(
                "cannot grant the container SID access to '$normalized' ($detail). The sandbox needs " +
                    "WRITE_DAC there; a path inherited from a shared directory does not grant it.",
            )
        }
        logger.warn("windows-appcontainer could not grant {} ({})", normalized, detail)
    }

    private fun isGranted(path: Path): Boolean {
        val marker = markerFile(path, "grant") ?: return false
        return Files.isRegularFile(marker)
    }

    private fun rememberGrant(path: Path) {
        val marker = markerFile(path, "grant") ?: return
        runCatching {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, path.toString())
        }.onFailure { logger.debug("could not record the container grant for {}: {}", path, it.message) }
    }

    private fun markerFile(path: Path, kind: String): Path? {
        val dir = markerDir ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$kind|${path.toString().lowercase()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return dir.resolve("$digest.grant")
    }

    // ------------------------------------------------------------------
    // Environment
    // ------------------------------------------------------------------

    /**
     * Points the child's home and temp at the workspace.
     *
     * A container cannot read the real profile, so `USERPROFILE` would name an inaccessible directory;
     * tools that write caches there would fail for a reason unrelated to the task. Everything a browse
     * task legitimately needs a home for lives under `<workspace>/.b4scratch`, which is granted.
     */
    private fun sandboxEnvironment(declared: Map<String, String>, workspace: Path): Map<String, String> {
        val scratch = workspace.resolve(".b4scratch")
        val home = scratch.resolve("home")
        val roaming = home.resolve("AppData").resolve("Roaming")
        val local = home.resolve("AppData").resolve("Local")
        val temp = scratch.resolve("tmp")
        listOf(home, roaming, local, temp).forEach { runCatching { Files.createDirectories(it) } }
        return LinkedHashMap(declared).apply {
            put("USERPROFILE", home.toString())
            put("APPDATA", roaming.toString())
            put("LOCALAPPDATA", local.toString())
            put("TEMP", temp.toString())
            put("TMP", temp.toString())
        }
    }

    // ------------------------------------------------------------------
    // Probe
    // ------------------------------------------------------------------

    /**
     * Proves the boundary: a workspace write must succeed, and a write under `%SystemRoot%\Temp` plus a
     * credential listing must fail.
     *
     * The inside check uses a **relative** file name with the working directory set to the workspace:
     * the launcher quotes argv with the C-runtime rules, and a `cmd` payload that carries its own
     * quotes cannot survive both layers (see `windows-low-il`'s PowerShell probe for the same lesson).
     */
    private fun defaultProbe(workspace: Path): String? {
        val inside = workspace.resolve("b4probe-in.txt")
        runCatching { Files.deleteIfExists(inside) }
        try {
            val writeInside = runInContainer(workspace, "echo b4> b4probe-in.txt")
            if (writeInside.first != 0 || !Files.exists(inside)) {
                return "a write inside the workspace failed (exit=${writeInside.first}): ${writeInside.second}"
            }
            val outsideDir = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "Temp")
            if (Files.isDirectory(outsideDir)) {
                val outside = outsideDir.resolve("b4probe-out.txt")
                runCatching { Files.deleteIfExists(outside) }
                val writeOutside = runInContainer(outsideDir, "echo b4> b4probe-out.txt")
                if (writeOutside.first == 0 || Files.exists(outside)) {
                    runCatching { Files.deleteIfExists(outside) }
                    return "a write OUTSIDE the workspace succeeded (exit=${writeOutside.first}): " +
                        "the container SID has more access than the workspace"
                }
            }
            // The child's USERPROFILE points into the workspace, so the real profile is handed over
            // explicitly for the credential check. A path with spaces breaks `dir`, which only ever
            // makes this check *stricter*; the failure that matters is "it succeeded".
            val credential = Path.of(System.getProperty("user.home")).resolve(".ssh")
            if (Files.isDirectory(credential)) {
                val listed = runInContainer(
                    workspace,
                    "dir %B4_SANDBOX_PROBE_HOME%\\.ssh",
                    extraEnv = mapOf("B4_SANDBOX_PROBE_HOME" to System.getProperty("user.home")),
                )
                if (listed.first == 0) {
                    return "a credential store was reachable from the container: $credential"
                }
            }
            return null
        } finally {
            runCatching { Files.deleteIfExists(inside) }
        }
    }

    /** Runs one `cmd /c` line inside the container and returns `(exit code, output)`. */
    internal fun runInContainer(
        directory: Path,
        line: String,
        extraEnv: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val root = verifiedRootFor(directory)
        val container = container
        val spec = ProcessSpec(
            command = listOf("cmd.exe", "/c", line),
            directory = directory,
            environment = sandboxEnvironment(SandboxEnvironment.build(System.getenv()), root) + extraEnv,
        )
        val prepared = spec.copy(
            sandboxLauncher = SandboxLauncher { s, redirect ->
                WindowsTokenLauncher.startInAppContainer(s, container.sid, redirect, job)
            },
        )
        val process = ProcessLauncher.start(prepared, redirectErrorStream = true)
        return try {
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                PROBE_TIMEOUT_EXIT to "timed out after ${PROBE_TIMEOUT_SECONDS}s"
            } else {
                process.exitValue() to output
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    /** The workspace a probe directory belongs to: a probe may run in a subdirectory (or outside it). */
    private fun verifiedRootFor(directory: Path): Path {
        val normalized = directory.toAbsolutePath().normalize()
        return verifiedWorkspaces.keys.firstOrNull { normalized.startsWith(it) }
            ?: verifiedWorkspaces.keys.firstOrNull()
            ?: normalized
    }

    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    private fun requireWindows() {
        if (!isWindows()) {
            fail("the '$SANDBOX_ID' sandbox requires Windows, but os.name='${System.getProperty("os.name")}'")
        }
    }

    private fun fail(message: String): Nothing {
        throw SandboxUnavailableException(SANDBOX_ID, "Windows sandbox unavailable: $message")
    }

    private fun firstLine(text: String, limit: Int = 200): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(limit) ?: ""
}
