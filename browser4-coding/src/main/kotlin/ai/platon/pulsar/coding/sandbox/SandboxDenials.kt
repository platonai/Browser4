package ai.platon.pulsar.coding.sandbox

import ai.platon.pulsar.coding.PermissionAudit
import org.slf4j.LoggerFactory

/**
 * Plan P3-3 — structured translation of OS-level denials.
 *
 * When the sandbox (or the OS ACL it relies on) refuses an operation, the child only leaves a
 * platform-shaped message (`Access is denied.`, `Permission denied`, `EACCES`, …) in its output.
 * Handing that raw text to the model invites it to "fix" the command instead of understanding that
 * the boundary is what refused. The model-facing text therefore becomes the same structured refusal
 * the string layer produces (`PERMISSION_DENIED | code=SANDBOX_DENIED | … | needed=…`), while the
 * raw evidence is kept in the log — an audit needs the exact bytes, the model does not.
 *
 * The translation only applies when a sandbox is actually active: with `off`, an `Access is denied`
 * is an ordinary OS error and must not be attributed to a boundary that is not there.
 */
internal object SandboxDenials {

    private val logger = LoggerFactory.getLogger(SandboxDenials::class.java)

    /**
     * Platform-shaped denial markers. Deliberately narrow: a build log that merely mentions
     * "permissions" must not be rewritten into a sandbox refusal.
     */
    private val MARKERS = listOf(
        "access is denied",
        "permission denied",
        "operation not permitted",
        "eacces",
        "eperm",
    )

    private const val NEEDED =
        "access outside the sandbox's granted roots (workspace, its mirrors, read-only toolchain roots)"

    private const val HINT =
        "keep the command inside the workspace and its granted roots; the sandbox does not fall back"

    /**
     * The structured refusal that replaces a raw denial, or `null` when nothing should be rewritten.
     *
     * @param tool the entry point, e.g. `coding.shell` / `coding.runCode`
     * @param command what was attempted (the model already knows it; it goes into the detail)
     * @param evidence the raw child output, kept in the log only
     */
    fun envelope(tool: String, command: String, evidence: String): String? {
        val sandboxId = activeSandboxId() ?: return null
        if (!isDenial(evidence)) return null
        logger.info(
            "sandbox denied: sandbox={} tool={} command='{}' evidence={}",
            sandboxId, tool, command, evidence.trim(),
        )
        return PermissionAudit.denied(
            tool = tool,
            code = PermissionAudit.CODE_SANDBOX_DENIED,
            detail = "the '$sandboxId' sandbox refused the command (the OS denied access)",
            hint = HINT,
            needed = NEEDED,
        )
    }

    /** True when [text] carries one of the platform's denial markers. */
    fun isDenial(text: String): Boolean = MARKERS.any { text.contains(it, ignoreCase = true) }

    /** The active sandbox id, or `null` for `off` / an unreadable configuration. */
    private fun activeSandboxId(): String? =
        runCatching { ExecutionSandboxes.current().id }
            .getOrNull()
            ?.takeIf { it != OffSandbox.id }
}
