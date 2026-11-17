package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.IsolationProfile
import ai.platon.pulsar.coding.MirrorRoots
import ai.platon.pulsar.coding.ReadablePaths
import com.sun.jna.Memory
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
 * `windows-low-il` — the **coding-mode** boundary: read like the user, write only the workspace.
 *
 * Why this backend exists (design: `docs/agent-permissions-redesign.md`): coding tasks need the
 * opposite trade-off from browser tasks. They must read the machine the way the developer can
 * (`~/.m2`, a foreign checkout, `D:\`), and they must *not* write outside their workspace. Windows'
 * mandatory integrity control expresses exactly that: a Low-integrity subject can read Medium
 * objects (read-up is allowed by default) and is refused a *write* up (`NO_WRITE_UP`). Writes into
 * the workspace and the build caches are legal because those objects are labelled Low — and a write
 * *down* is legal for the developer's own Medium-level shell too, so nothing regresses.
 *
 * No local account and no administrator:
 *  - the child token is a `CreateRestrictedToken(DISABLE_MAX_PRIVILEGE)` copy of the backend token,
 *    lowered with `SetTokenInformation(TokenIntegrityLevel)`; `CreateProcessAsUserW` accepts it
 *    because it is a restricted derivative of the caller. The backend's own token is untouched.
 *  - labels are written with `icacls /setintegritylevel` and `SetNamedSecurityInfoW`
 *    (`LABEL_SECURITY_INFORMATION`), which need ownership, not privilege.
 *  - the token is inherited by every descendant, so a program the task writes and then runs is
 *    confined too — requirement 3 of the design, and the reason none of this is a string rule.
 *
 * **Credentials stay unreadable** (decision D1): [ReadablePaths.ALWAYS_REFUSED] directories get a
 * Medium mandatory label with `NO_READ_UP`, so the Low child cannot read them while the developer's
 * own Medium processes still can (a same-level access is not an "up" access).
 *
 * **Network is not enforced by this backend** ([enforcesNetwork] is false): an integrity level says
 * nothing about sockets. `NetPolicy.Off` needs the AppContainer backend or a host-level filter, and
 * until then the state is reported instead of implied.
 *
 * Availability is proven, not assumed: the first command in a workspace runs a three-part probe
 * (write inside must succeed; write outside and a credential listing must fail) and a mismatch is a
 * [SandboxUnavailableException] — never a silent fallback to an unconfined launch.
 */
class WindowsLowIntegritySandbox(
    private val runner: SandboxCommandRunner = SystemSandboxCommandRunner,
    /** Probe hook: `(workspace) -> failure message`, `null` when the boundary holds. */
    private val probe: ((Path) -> String?)? = null,
    /** Cache roots to label; injectable so a test never walks the developer's real `~/.m2`. */
    private val caches: () -> List<Path> = { IsolationProfile.defaultCaches() },
    /** Credential-store labelling; injectable so a test never relabels the real `~/.ssh`. */
    private val protectCredentialStores: Boolean = true,
    /** Which stores stay unreadable (decision D1); injectable for the same reason. */
    private val credentialStores: List<String> = ReadablePaths.ALWAYS_REFUSED,
    /** Where the "already labelled" markers live; injectable so tests keep the user data dir clean. */
    private val markerDir: Path? = SandboxDefaults.defaultDataDir().resolve("sandbox").resolve("low-il"),
) : ExecutionSandbox {

    companion object {
        /** Backend id in `browser4.agent.sandbox` and [ExecutionSandboxes.KNOWN_IDS]. */
        const val SANDBOX_ID = "windows-low-il"

        private const val LABEL_TIMEOUT_SECONDS = 30L
        private const val PROBE_TIMEOUT_SECONDS = 30L
        private const val PROBE_TIMEOUT_EXIT = 42

        /**
         * The in-workspace probe: write a file, or list a directory, and report via the exit code.
         *
         * A script (rather than a `cmd` payload) keeps the path a single argv element, so quoting is
         * the launcher's job instead of a fragile `cmd /c` string.
         */
        private val PROBE_SCRIPT = """
            param([string]${'$'}Mode, [string]${'$'}Path)
            try {
              if (${'$'}Mode -eq 'write') { [IO.File]::WriteAllText(${'$'}Path, 'b4') }
              elseif (${'$'}Mode -eq 'list') { [void][IO.Directory]::GetFileSystemEntries(${'$'}Path) }
              exit 0
            } catch { exit 1 }
        """.trimIndent()

        private val logger = LoggerFactory.getLogger(WindowsLowIntegritySandbox::class.java)
    }

    /** Roots already labelled by this instance; labels are idempotent and icacls is not free. */
    private val labeledRoots = ConcurrentHashMap<Path, Unit>()

    /** Credential stores labelled by this instance (`"ok"`), so a failure is retried next time. */
    private val credentialLabels = ConcurrentHashMap<Path, String>()

    override val id: String = SANDBOX_ID

    /** Integrity levels do not touch sockets; see the class KDoc. */
    override val enforcesNetwork: Boolean = false

    /**
     * The backend's own file mutations are handed to a Low-integrity child (plan P3-6): the workspace
     * is labelled Low, so the child may write there and nowhere else — the same kernel decision the
     * shell gets, applied to `coding.write`/`delete`/`move`/… See [SandboxedFileOps].
     */
    override val executesFileOps: Boolean = true

    /** One restricted, Low-integrity primary token for the life of the backend. */
    private val token: Pointer by lazy { createLowIntegrityToken() }

    /**
     * Process-tree lifetime: everything this sandbox starts joins one job, so a backend exit (or a
     * crash) takes its children with it instead of leaving `mvn`/`python` orphans behind.
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
        prepareTaskTemp(root)
        labelWritable(root, deep = true, required = true)
        // The same roots the account backend provisions: a module junction (or a worktree's source
        // checkout) is written through the workspace, so the OS must allow the write there too.
        // Not required: a workspace without mirrors has nothing to label.
        MirrorRoots.derive(root).forEach { labelWritable(it, deep = true) }
        extraWritableRoots.map { it.toAbsolutePath().normalize() }.forEach {
            labelWritable(it, deep = true, required = true)
        }
        // Caches are labelled deeply and only once per installation (the marker makes the walk
        // permanent): a build rewrites existing files under ~/.m2 (`_remote.repositories`,
        // `resolver-status.properties`), and those are Medium until the tree is labelled.
        caches().forEach { labelWritable(it, deep = true) }
        if (protectCredentialStores) labelCredentialsOnce()

        if (!verifiedWorkspaces.contains(root)) {
            // `?:` would treat "the probe passed" (null) as "no probe was supplied" and run the
            // real probe anyway; the two cases must stay distinct.
            val failure = if (probe != null) probe.invoke(root) else defaultProbe(root)
            if (failure != null) {
                fail("low-integrity probe failed: $failure")
            }
            verifiedWorkspaces[root] = Unit
            logger.info("windows-low-il write probe passed: workspace={}", root)
        }
        if (!allowNetwork) {
            logger.debug("windows-low-il does not enforce the network policy; the task asked for net=off")
        }
        return launchable(spec, root)
    }

    /**
     * Whether the backend can run here at all: Windows and a Low-integrity token can be built.
     *
     * Cheap after the first call (the token is cached) and side-effect free, so `SandboxDefaults`
     * can ask before it upgrades an installation.
     */
    override fun isReady(): Boolean = runCatching {
        if (!isWindows()) return false
        token
        true
    }.getOrElse { e ->
        logger.info("windows-low-il is not ready: {}", e.message)
        false
    }

    // ------------------------------------------------------------------
    // Launch
    // ------------------------------------------------------------------

    /** The spec as the low-integrity child sees it: our temp dirs and our token. */
    private fun launchable(spec: ProcessSpec, workspace: Path): ProcessSpec {
        val temp = workspace.resolve(".b4scratch").resolve("tmp")
        val environment = LinkedHashMap(spec.environment).apply {
            put("TEMP", temp.toString())
            put("TMP", temp.toString())
        }
        val lowToken = token
        // Best effort, exactly like the account path: a restricted derivative of our own token does
        // not need SeAssignPrimaryToken, but enabling what we have keeps a quota failure impossible.
        WindowsTokenLauncher.enableTokenPrivileges()
        return spec.copy(
            environment = environment,
            sandboxLauncher = SandboxLauncher { prepared, redirect ->
                WindowsTokenLauncher.startWithToken(prepared, lowToken, redirect, job)
            },
        )
    }

    // ------------------------------------------------------------------
    // Token
    // ------------------------------------------------------------------

    private fun createLowIntegrityToken(): Pointer {
        val current = PointerByReference()
        val access = Win32.TOKEN_DUPLICATE or Win32.TOKEN_QUERY or
            Win32.TOKEN_ASSIGN_PRIMARY or Win32.TOKEN_ADJUST_DEFAULT
        if (!Win32.advapi32.OpenProcessToken(Win32.kernel32.GetCurrentProcess(), access, current)) {
            fail("OpenProcessToken failed (error ${Native.getLastError()})")
        }
        val currentToken = current.value ?: fail("OpenProcessToken returned no handle")
        try {
            val restricted = PointerByReference()
            val created = Win32.advapi32.CreateRestrictedToken(
                currentToken,
                Win32.DISABLE_MAX_PRIVILEGE,
                0, null,
                0, null,
                0, null,
                restricted,
            )
            if (!created) {
                fail("CreateRestrictedToken failed (error ${Native.getLastError()})")
            }
            val child = restricted.value ?: fail("CreateRestrictedToken returned no handle")
            if (!lowerIntegrity(child)) {
                Win32.kernel32.CloseHandle(child)
                fail("SetTokenInformation(TokenIntegrityLevel=Low) failed (error ${Native.getLastError()})")
            }
            logger.info("windows-low-il token ready: restricted + low integrity")
            return child
        } finally {
            Win32.kernel32.CloseHandle(currentToken)
        }
    }

    private fun lowerIntegrity(token: Pointer): Boolean {
        val sid = PointerByReference()
        if (!Win32.advapi32.ConvertStringSidToSidW(WString(Win32.SID_LOW_INTEGRITY), sid)) return false
        val sidPointer = sid.value ?: return false
        try {
            val label = Win32.SidAndAttributes().apply {
                Sid = sidPointer
                Attributes = Win32.SE_GROUP_INTEGRITY
            }
            label.write()
            return Win32.advapi32.SetTokenInformation(
                token,
                Win32.TOKEN_INTEGRITY_LEVEL,
                label.pointer,
                label.size(),
            )
        } finally {
            Win32.kernel32.LocalFree(sidPointer)
        }
    }

    // ------------------------------------------------------------------
    // Labels
    // ------------------------------------------------------------------

    /**
     * Lowers [path]'s mandatory label to Low so the child may write it (`icacls /setintegritylevel`,
     * inheritance included). A write *down* stays legal for the developer's own shell.
     */
    private fun labelWritable(path: Path, deep: Boolean, required: Boolean = false) {
        if (!Files.exists(path)) return
        val normalized = path.toAbsolutePath().normalize()
        if (labeledRoots.putIfAbsent(normalized, Unit) != null) return
        if (isLabelled(normalized, deep)) return
        val level = if (deep) "(OI)(CI)Low" else "Low"
        val result = runner.run(
            listOf("icacls", normalized.toString(), "/setintegritylevel", level),
            LABEL_TIMEOUT_SECONDS,
        )
        if (result.ok) {
            rememberLabelled(normalized, deep)
            return
        }
        labeledRoots.remove(normalized)
        val detail = "icacls exit=${result.exitCode}: ${firstLine(result.output)}"
        if (required) {
            // A workspace whose label cannot be lowered cannot be protected. Fail loudly with the
            // reason instead of probing, failing the probe, and reporting the symptom.
            fail(
                "cannot lower the mandatory label of '$normalized' ($detail). The sandbox needs " +
                    "WRITE_OWNER on the workspace; a path inherited from a shared directory (for " +
                    "example %TEMP%) does not grant it.",
            )
        }
        logger.warn("windows-low-il could not lower the label of {} ({})", normalized, detail)
    }

    /**
     * Whether [path] was labelled (`deep` or shallow) by an earlier backend start.
     *
     * The workspace walk is expensive on a real checkout and `icacls` re-walks on every call, so the
     * decision is recorded next to the data directory. No marker directory means "always label".
     */
    private fun isLabelled(path: Path, deep: Boolean): Boolean {
        val marker = markerFile(path, deep) ?: return false
        return Files.isRegularFile(marker)
    }

    private fun rememberLabelled(path: Path, deep: Boolean) {
        val marker = markerFile(path, deep) ?: return
        runCatching {
            Files.createDirectories(marker.parent)
            Files.writeString(marker, "$deep|$path")
        }.onFailure { logger.debug("could not record the label of {}: {}", path, it.message) }
    }

    private fun markerFile(path: Path, deep: Boolean): Path? {
        val dir = markerDir ?: return null
        val name = sha256("$deep|${path.toString().lowercase()}")
        return dir.resolve("$name.label")
    }

    private fun sha256(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Gives every always-refused credential store a Medium label with `NO_READ_UP`.
     *
     * Medium keeps the developer's own processes working (same level is not "up") while the Low child
     * is refused — this is what makes D1 real without a separate account.
     */
    private fun labelCredentialsOnce() {
        for (raw in credentialStores) {
            val path = expandHome(raw) ?: continue
            if (!Files.exists(path)) continue
            if (credentialLabels.containsKey(path)) continue
            val failure = applyNoReadUp(path)
            if (failure == null) {
                credentialLabels[path] = "ok"
            } else {
                logger.warn("windows-low-il could not protect {}: {}", path, failure)
            }
        }
    }

    private fun applyNoReadUp(path: Path): String? {
        val sid = PointerByReference()
        if (!Win32.advapi32.ConvertStringSidToSidW(WString(Win32.SID_MEDIUM_INTEGRITY), sid)) {
            return "ConvertStringSidToSidW failed (error ${Native.getLastError()})"
        }
        val sidPointer = sid.value ?: return "ConvertStringSidToSidW returned no SID"
        try {
            val aclSize = 1024
            val acl = Memory(aclSize.toLong())
            if (!Win32.advapi32.InitializeAcl(acl, aclSize, Win32.ACL_REVISION)) {
                return "InitializeAcl failed (error ${Native.getLastError()})"
            }
            val added = Win32.advapi32.AddMandatoryAce(
                acl,
                Win32.ACL_REVISION,
                Win32.OBJECT_INHERIT_ACE or Win32.CONTAINER_INHERIT_ACE,
                Win32.SYSTEM_MANDATORY_LABEL_NO_READ_UP,
                sidPointer,
            )
            if (!added) return "AddMandatoryAce failed (error ${Native.getLastError()})"
            val status = Win32.advapi32.SetNamedSecurityInfoW(
                WString(path.toAbsolutePath().toString()),
                Win32.SE_FILE_OBJECT,
                Win32.LABEL_SECURITY_INFORMATION,
                null, null, null, acl,
            )
            return if (status == 0) null else "SetNamedSecurityInfoW failed (status $status)"
        } finally {
            Win32.kernel32.LocalFree(sidPointer)
        }
    }

    // ------------------------------------------------------------------
    // Task temp
    // ------------------------------------------------------------------

    /**
     * A Low-labelled temp directory of the task's own.
     *
     * The backend's `%TEMP%` is Medium, so a Low child cannot write it; pointing `TEMP`/`TMP` at a
     * directory we label ourselves keeps compilers and scripts working without lowering the
     * machine-wide temp directory.
     */
    private fun prepareTaskTemp(workspace: Path): Path {
        val temp = workspace.resolve(".b4scratch").resolve("tmp")
        runCatching { Files.createDirectories(temp) }
        labelWritable(temp, deep = true)
        return temp
    }

    // ------------------------------------------------------------------
    // Probe
    // ------------------------------------------------------------------

    /**
     * Proves the boundary instead of trusting it: a write inside must succeed, a write outside and a
     * credential listing must fail.
     */
    private fun defaultProbe(workspace: Path): String? {
        val inside = workspace.resolve(".b4probe-in.txt")
        val home = Path.of(System.getProperty("user.home"))
        val outside = home.resolve(".b4probe-outside.txt")
        runCatching { Files.deleteIfExists(inside) }
        runCatching { Files.deleteIfExists(outside) }
        try {
            val writeInside = writeFileAsLow(workspace, inside)
            if (writeInside.first != 0 || !Files.exists(inside)) {
                return "a write inside the workspace failed (exit=${writeInside.first}): ${writeInside.second}"
            }
            val writeOutside = writeFileAsLow(workspace, outside)
            if (writeOutside.first == 0 || Files.exists(outside)) {
                return "a write OUTSIDE the workspace succeeded (exit=${writeOutside.first}): " +
                    "the label was not applied — ${writeOutside.second}"
            }
            if (protectCredentialStores) {
                // Check the stores this instance was told to protect, not a hard-coded `~/.ssh`: the
                // list is what `labelCredentialsOnce` actually labelled.
                for (raw in credentialStores) {
                    val credential = expandHome(raw) ?: continue
                    if (!Files.isDirectory(credential)) continue
                    val listCredentials = listDirAsLow(workspace, credential)
                    if (listCredentials.first == 0) {
                        return "a credential store was listable from the sandboxed child: $credential"
                    }
                }
            }
            return null
        } finally {
            runCatching { Files.deleteIfExists(inside) }
            runCatching { Files.deleteIfExists(outside) }
        }
    }

    /**
     * Writes [target] from a low-integrity child and returns `(exit code, output)`.
     *
     * The child is a **PowerShell script inside the workspace**, not a `cmd /c` line: the launcher
     * quotes argv with the C-runtime rules, so a `cmd` payload that carries its own quotes
     * (`echo x> "C:\a b\f.txt"`) cannot survive both layers. Passing the path as an ordinary argument
     * lets each layer quote it correctly.
     */
    internal fun writeFileAsLow(workspace: Path, target: Path): Pair<Int, String> =
        runProbeScript(workspace, "write", target)

    /** Lists [target] from a low-integrity child; non-zero means the boundary refused it. */
    internal fun listDirAsLow(workspace: Path, target: Path): Pair<Int, String> =
        runProbeScript(workspace, "list", target)

    private fun runProbeScript(workspace: Path, mode: String, target: Path): Pair<Int, String> {
        val script = ensureProbeScript(workspace)
        val command = listOf(
            "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
            "-File", script.toString(), "-Mode", mode, "-Path", target.toString(),
        )
        return runAsLowWithCommand(workspace, command)
    }

    /** Writes the probe script once per workspace and returns its path. */
    private fun ensureProbeScript(workspace: Path): Path {
        val dir = workspace.resolve(".b4scratch")
        runCatching { Files.createDirectories(dir) }
        val script = dir.resolve("b4-probe.ps1")
        if (!Files.isRegularFile(script)) {
            Files.writeString(script, PROBE_SCRIPT)
        }
        return script
    }
    /** Runs one `cmd /c` line with the low-integrity token and returns its exit code. */
    internal fun runAsLow(workspace: Path, line: String): Int =
        runAsLowWithOutput(workspace, line).first

    /** Same, with the child's combined output, so a probe failure says *why*. */
    internal fun runAsLowWithOutput(
        workspace: Path,
        line: String,
        directory: Path = workspace,
        extraEnv: Map<String, String> = emptyMap(),
    ): Pair<Int, String> =
        runAsLowWithCommand(workspace, listOf("cmd.exe", "/c", line), directory, extraEnv)

    /** Runs an arbitrary argv as the low-integrity child. */
    internal fun runAsLowWithCommand(
        workspace: Path,
        command: List<String>,
        directory: Path = workspace,
        extraEnv: Map<String, String> = emptyMap(),
    ): Pair<Int, String> {
        val spec = ProcessSpec(
            command = command,
            directory = directory,
            environment = SandboxEnvironment.build(System.getenv()) + extraEnv,
        )
        val prepared = launchable(spec, workspace.toAbsolutePath().normalize())
        val process = ProcessLauncher.start(prepared, redirectErrorStream = true)
        return try {
            val output = decodeConsole(process.inputStream.readBytes()).trim()
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
    private fun expandHome(raw: String): Path? = runCatching {
        val home = Path.of(System.getProperty("user.home"))
        if (raw.startsWith("~/")) home.resolve(raw.substring(2)) else Path.of(raw)
    }.getOrNull()

    private fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    private fun requireWindows() {
        if (!isWindows()) {
            fail("the '$SANDBOX_ID' sandbox requires Windows, but os.name='${System.getProperty("os.name")}'")
        }
    }

    private fun fail(message: String): Nothing {
        throw SandboxUnavailableException(SANDBOX_ID, "Windows sandbox unavailable: $message")
    }

    /**
     * `cmd` writes its errors in the console code page (936 on a Chinese Windows), so a UTF-8 decode
     * would turn the probe diagnosis into question marks. Keep ASCII, fall back to the OEM charset.
     */
    private fun decodeConsole(bytes: ByteArray): String {
        val utf8 = bytes.toString(Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { bytes.toString(charset("GBK")) }.getOrDefault(utf8)
    }

    private fun firstLine(text: String, limit: Int = 200): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(limit) ?: ""
}
