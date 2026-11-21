package ai.platon.pulsar.coding.sandbox

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Grants the object owner the two security-information rights a Low-integrity label needs.
 *
 * Writing an explicit mandatory label (`icacls /setintegritylevel`) changes security
 * information, so it requires `WRITE_OWNER` on the object. Ownership implies `READ_CONTROL`
 * and `WRITE_DAC` but **not** `WRITE_OWNER`, and the `Modify` an object inherits from a shared
 * root does not contain it either. That is why a checkout under `D:\`, `%TEMP%` or a tree
 * copied from another account cannot be labelled, and why every `windows-low-il` write fails
 * with `icacls exit=5` (see `docs/agent-permissions.md` §10).
 *
 * The minimal repair is the OWNER RIGHTS ACE (`S-1-3-4`): `(WDAC,WO)` for whoever owns the
 * object — no read/write widening, no other principal. `(OI)(CI)` is essential for a
 * directory: a task worktree is created **after** the grant, so a non-inheritable ACE would
 * never reach it.
 *
 * Best effort by design; the caller decides whether a refusal is fatal. The sandbox retries
 * the label once and then fails with the operator-facing message, while
 * [ai.platon.pulsar.coding.WorkspaceIsolation] only warns — a `windows-acl`/`off` install
 * never labels the tree, so a refused grant must not fail task creation.
 */
internal object OwnerSecurityRights {

    /** OWNER RIGHTS: an ACE keyed on this SID applies only to the object's owner. */
    const val OWNER_RIGHTS_SID = "*S-1-3-4"

    /** `WRITE_DAC` (rewrite the DACL) plus `WRITE_OWNER` (write the mandatory label). */
    const val RIGHTS = "WDAC,WO"

    private const val GRANT_TIMEOUT_SECONDS = 30L

    private val logger = LoggerFactory.getLogger(OwnerSecurityRights::class.java)

    /** Whether mandatory labels — and therefore this repair — exist on this OS. */
    fun isWindows(): Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    /**
     * The `icacls` argv for [path]; pure, so the exact ACE stays testable off Windows.
     *
     * A file object gets no inheritance flags (they would be meaningless); a directory gets
     * `(OI)(CI)` so later children — starting with the next `git worktree add` — inherit the
     * rights.
     */
    fun command(path: Path, isDirectory: Boolean): List<String> = listOf(
        "icacls",
        path.toAbsolutePath().normalize().toString(),
        "/grant",
        if (isDirectory) "$OWNER_RIGHTS_SID:(OI)(CI)($RIGHTS)" else "$OWNER_RIGHTS_SID:($RIGHTS)",
    )

    /**
     * Adds (or refreshes) the OWNER RIGHTS ACE on [path].
     *
     * @param path the object to repair, usually a workspace or worktree root
     * @param runner the command seam; tests inject a fake, production runs `icacls`
     * @return true when `icacls` accepted the grant; false off Windows, when [path] is gone,
     *   or when this account cannot write the object's security information
     */
    fun grant(path: Path, runner: SandboxCommandRunner = SystemSandboxCommandRunner): Boolean {
        if (!isWindows() || !Files.exists(path)) return false
        val result = runner.run(command(path, Files.isDirectory(path)), GRANT_TIMEOUT_SECONDS)
        if (result.ok) return true
        val detail = result.output.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        logger.warn(
            "could not grant OWNER RIGHTS ({}) on {}: icacls exit={} {}",
            RIGHTS, path, result.exitCode, detail,
        )
        return false
    }
}
