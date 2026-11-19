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
 *
 * Two things made the translation skip exactly the case it exists for (run `4125acaa`, 2026-11-19):
 * the markers were ASCII-only, so a Chinese Windows child reported `拒绝访问。` and nothing matched;
 * and "is a sandbox active" was read from the bare `browser4.agent.sandbox` property, which reads
 * `off` on a task whose chain selected `windows-low-il`. Both are fixed here — markers now cover the
 * localized text, and the caller passes the task's own [ExecutionSandbox] through [sandboxId].
 */
internal object SandboxDenials {

    private val logger = LoggerFactory.getLogger(SandboxDenials::class.java)

    /**
     * Platform-shaped denial markers. Deliberately narrow: a build log that merely mentions
     * "permissions" must not be rewritten into a sandbox refusal.
     *
     * The localized forms are the ones `cmd.exe`/Node actually print on a non-English Windows
     * (`拒绝访问。` from the Win32 message table, the same bytes a refused `CreateProcess` leaves in
     * `stderr`). "找不到文件" is deliberately **not** included: a missing file is an ordinary
     * command failure, and rewriting it would hide real mistakes.
     */
    private val MARKERS = listOf(
        "access is denied",
        "permission denied",
        "operation not permitted",
        "eacces",
        "eperm",
        // zh-CN: 拒绝访问 / 无权限访问
        "拒绝访问",
        "无权限访问",
    )

    private const val NEEDED =
        "access outside the sandbox's granted roots (workspace, its mirrors, read-only toolchain roots)"

    private const val HINT =
        "keep the command inside the workspace and its granted roots; the sandbox does not fall back"

    /**
     * The low-integrity backend's own failure mode, seen on 2026-11-19.
     *
     * A directory that carries an object ACE for an **AppContainer package SID** (`S-1-15-2-…`,
     * left behind by a container run) cannot be read by a low-integrity restricted token at all:
     * enumeration reports "file not found", `CreateProcess` on a binary inside it reports "access
     * denied". Both halves of the npm global root failed that way while the toolchain worked from a
     * normal shell. Naming the cause and the exact `icacls` line turns a dead end into one command.
     */
    private const val LOW_IL_HINT =
        "if the refused path is a toolchain root (the npm global root, node/java/bin folders), check " +
            "it for a stale container ACL: an `S-1-15-2-...` entry in `icacls <dir>` makes that whole " +
            "tree unreadable to the low-integrity token — remove it with " +
            "`icacls <dir> /remove *<sid>`; `b4.run` (the product CLI channel) keeps working either way"

    /**
     * The structured refusal that replaces a raw denial, or `null` when nothing should be rewritten.
     *
     * @param tool the entry point, e.g. `coding.shell` / `coding.runCode`
     * @param command what was attempted (the model already knows it; it goes into the detail)
     * @param evidence the raw child output, kept in the log only
     * @param sandboxId the backend the calling task actually runs under (see
     *   [ai.platon.pulsar.coding.SandboxStatus.effectiveIdOf]); `null` falls back to the
     *   process-wide property
     */
    fun envelope(
        tool: String,
        command: String,
        evidence: String,
        sandboxId: String? = null,
    ): String? {
        val id = (sandboxId ?: activeSandboxId())
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != OffSandbox.id }
            ?: return null
        if (!isDenial(evidence)) return null
        logger.info(
            "sandbox denied: sandbox={} tool={} command='{}' evidence={}",
            id, tool, command, evidence.trim(),
        )
        val hint = if (id == WindowsLowIntegritySandbox.SANDBOX_ID) "$HINT; $LOW_IL_HINT" else HINT
        return PermissionAudit.denied(
            tool = tool,
            code = PermissionAudit.CODE_SANDBOX_DENIED,
            detail = "the '$id' sandbox refused the command (the OS denied access)",
            hint = hint,
            needed = NEEDED,
        )
    }

    /** True when [text] carries one of the platform's denial markers. */
    fun isDenial(text: String): Boolean = MARKERS.any { text.contains(it, ignoreCase = true) }

    /** The process-wide sandbox id, or `null` for `off` / an unreadable configuration. */
    private fun activeSandboxId(): String? =
        runCatching { ExecutionSandboxes.current().id }
            .getOrNull()
            ?.takeIf { it != OffSandbox.id }
}
