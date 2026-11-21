package ai.platon.pulsar.coding.sandbox


import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan G9 — the one DACL entry that makes a directory invisible to a low-integrity child.
 *
 * Measured 2026-11-19 (run `4125acaa`, three-phase control 3/3): a directory whose DACL carries an
 * **AppContainer package SID** (`S-1-15-2-<hash>`) ACE is unreadable to a `windows-low-il` child —
 * enumeration reports "file not found", `CreateProcess` on a binary inside it reports "access
 * denied", `readdirSync` reports `EPERM` — while the same account reading it from a normal shell, or
 * from a merely restricted token, succeeds. The well-known *ALL APPLICATION PACKAGES* SID
 * (`S-1-15-2-1`) does **not** trigger it; only a concrete package SID does.
 *
 * Such an entry is normally a leftover: `windows-appcontainer` grants its container SID on the
 * workspace (and the folders a task added) and, until G9 was closed, never revoked one. That is
 * harmless on its own — the grant is for another SID — but the two backends share a mode chain, so a
 * stale grant silently disables every toolchain call the coding sandbox makes into that tree.
 *
 * The scan is deliberately bounded and diagnostic-only: it never edits an ACL, it reports the exact
 * `icacls … /remove` command instead. Removing another task's live grant would break it, and the
 * operator is the only one who can tell a leak from a working container.
 */
internal object ContainerAceScan {


    /** The one package SID every container can read: never a leak, never a reason to warn. */
    const val ALL_APPLICATION_PACKAGES = "S-1-15-2-1"

    /** `icacls` prints SIDs in upper case; match either and normalise. */
    private val PACKAGE_SID = Regex("""s-1-15-2(?:-\d+)+""", RegexOption.IGNORE_CASE)

    private const val SCAN_TIMEOUT_SECONDS = 30L

    /** Upper bound on directories inspected by one scan: the check runs on a task's first command. */
    const val MAX_SCAN_DIRS = 96

    /** Upper bound on `node_modules` children inspected per prefix. */
    private const val MAX_PACKAGE_DIRS = 32

    /** One directory carrying package-SID entries, with the SIDs that were found. */
    data class Finding(val directory: Path, val packageSids: Set<String>)

    /**
     * Whether [sid] is a **concrete** AppContainer package SID.
     *
     * `S-1-15-2-1` (ALL APPLICATION PACKAGES) is explicitly not one: it is part of every container
     * token's access set and a directory carrying it stays readable to a low-integrity child.
     */
    fun isPackageSid(sid: String): Boolean {
        val normalized = sid.trim()
        if (!PACKAGE_SID.matches(normalized)) return false
        return !normalized.equals(ALL_APPLICATION_PACKAGES, ignoreCase = true)
    }

    /** Concrete package SIDs named anywhere in an `icacls` dump (its SIDs are locale-independent). */
    fun packageSidsIn(icaclsOutput: String): Set<String> =
        PACKAGE_SID.findAll(icaclsOutput)
            .map { it.value.uppercase() }
            .filter { isPackageSid(it) }
            .toSet()

    /** The command that removes one package-SID entry, as `icacls` takes it. */
    fun removeCommand(directory: Path, sid: String): List<String> =
        listOf("icacls", directory.toString(), "/remove", "*${sid.uppercase()}")

    /** One operator-facing line: what was found, what it breaks, and how to undo it. */
    fun warning(finding: Finding): String {
        val sids = finding.packageSids.joinToString(", ")
        return "AppContainer ACE on '${finding.directory}' ($sids): a windows-low-il child cannot read " +
            "that tree (dir says \"file not found\", running a binary inside it says \"access denied\"). " +
            "Remove the stale entry with: " +
            finding.packageSids.joinToString(" ; ") { removeCommand(finding.directory, it).joinToString(" ") }
    }

    /**
     * Directories worth a look, in the order they matter, bounded by [MAX_SCAN_DIRS].
     *
     * The workspace is not passed in: the check answers "can this installation run its toolchain at
     * all", which is exactly the question that failed on 2026-11-19 (the npm global prefix lives in
     * `%APPDATA%`, outside every workspace).
     *
     * @param toolchainRoots the shell's toolchain locations (JDK, `~/.m2`, …)
     * @param pathDirs the directories on `PATH` (injectable so a test never reads the real one)
     */
    fun candidateRoots(
        toolchainRoots: Collection<Path>,
        pathDirs: Collection<Path>,
        existing: (Path) -> Boolean = { Files.isDirectory(it) },
    ): List<Path> {
        val roots = LinkedHashSet<Path>()
        toolchainRoots.forEach { roots.add(it.toAbsolutePath().normalize()) }
        pathDirs.forEach { roots.add(it.toAbsolutePath().normalize()) }
        // An npm/pnpm global prefix keeps one directory per package; the leaked entry was on a
        // package directory (`node_modules/browser4-cli`), not on the prefix itself.
        pathDirs.map { it.toAbsolutePath().normalize() }.forEach { dir ->
            val modules = dir.resolve("node_modules")
            if (!existing(modules)) return@forEach
            Files.list(modules).use { stream ->
                stream.filter { existing(it) }
                    .limit(MAX_PACKAGE_DIRS.toLong())
                    .forEach { roots.add(it.toAbsolutePath().normalize()) }
            }
        }
        return roots.filter { existing(it) }.take(MAX_SCAN_DIRS)
    }

    /** The `PATH` directories of this machine, as the shell would resolve them. */
    fun defaultPathDirs(): List<Path> {
        val raw = System.getenv("PATH").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(java.io.File.pathSeparatorChar)
            .map { it.trim().removeSurrounding("\"") }
            .filter { it.isNotEmpty() }
            .mapNotNull { runCatching { Path.of(it) }.getOrNull() }
    }

    /**
     * Inspect [directories] and return the ones carrying a concrete package SID.
     *
     * A directory that cannot be queried (permissions, a name with a wildcard) is skipped: this is a
     * diagnostic, and failing it would turn a warning into a broken command.
     */
    fun scan(
        directories: Collection<Path>,
        runner: SandboxCommandRunner = SystemSandboxCommandRunner,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (directory in directories) {
            val result = runCatching {
                runner.run(listOf("icacls", directory.toString()), SCAN_TIMEOUT_SECONDS)
            }.getOrNull() ?: continue
            if (result.exitCode != 0) continue
            val sids = packageSidsIn(result.output)
            if (sids.isEmpty()) continue
            findings.add(Finding(directory, sids))
        }
        return findings
    }
}
