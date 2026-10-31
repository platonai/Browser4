package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.coding.CodingAgentFileSystem
import ai.platon.pulsar.coding.CodingAgentShell
import ai.platon.pulsar.coding.PermissionAudit
import ai.platon.pulsar.coding.sandbox.SandboxEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan P0-3 — the `coding.permissions` report must not drift from [PermissionAudit].
 *
 * The report is the operator's whole observation surface: it prints the four capability counters
 * that B3/B5 record. If the report and the counters are read from different places (a copy, a cached
 * snapshot, a hardcoded zero), an operator sees "0 out-of-scope writes" while the audit object
 * counts dozens — the exact failure M1 exists to prevent. This test induces a real fact of each
 * kind through its real producer, then asserts the printed number equals
 * [PermissionAudit.snapshot] for the same `tool:code` key.
 *
 * Counts are process-wide, so assertions are equalities against a snapshot taken after the induced
 * work, never absolute numbers: other test classes legitimately add to the same counters.
 */
class PermissionsConsistencyTest {

    @TempDir
    lateinit var tempDir: Path

    private val executor = CodingToolExecutor()

    /** Both boundaries canonicalize their root, so the workspace must exist before construction. */
    @BeforeEach
    fun createWorkspace() {
        Files.createDirectories(tempDir.resolve("ws"))
    }

    @Test
    @DisplayName("the report's capability counters equal PermissionAudit.snapshot()")
    fun testReportMatchesAuditSnapshot() = runBlocking {
        val outside = tempDir.resolve("b4-permissions-outside.txt")
        // The shell scope includes the toolchain roots (tmpdir is one of them), so a temp-dir file
        // would be *in* scope and record nothing; the filesystem root is the same "outside" the
        // shell's own baseline uses.
        val shellOutside = tempDir.root.resolve("b4-permissions-outside.csv")
        val fs = CodingAgentFileSystem(workspaceRoot = tempDir.resolve("ws"), allowExternalAccess = true)
        val shell = CodingAgentShell(
            baseDir = tempDir.resolve("ws"),
            allowExternalAccess = true,
            allowNetwork = true,
        )

        // One real producer per counter: the report may only differ from the audit if it stops
        // reading PermissionAudit.snapshot().
        fs.writeFile(outside.toString(), "x")                 // coding.fs:OUT_OF_SCOPE_WRITE
        shell.execute("type \"$shellOutside\"")                // coding.shell:SCOPE_UNCERTAIN
        shell.execute("curl --version")                       // coding.shell:NETWORK_TARGET
        SandboxEnvironment.build(emptyMap(), mapOf("B4_TEST_API_KEY" to "x")) // coding.env:ENV_PASSTHROUGH

        val report = report(fs, shell)
        val snapshot = PermissionAudit.snapshot()

        // Non-vacuity: the producers above must have moved the counters, otherwise equality is trivial.
        assertTrue(count("coding.fs", PermissionAudit.CODE_OUT_OF_SCOPE_WRITE, snapshot) >= 1)
        assertTrue(count("coding.shell", PermissionAudit.CODE_SCOPE_UNCERTAIN, snapshot) >= 1)
        assertTrue(count("coding.shell", PermissionAudit.CODE_NETWORK_TARGET, snapshot) >= 1)
        assertTrue(count("coding.env", PermissionAudit.CODE_ENV_PASSTHROUGH, snapshot) >= 1)

        assertEquals(
            count("coding.fs", PermissionAudit.CODE_OUT_OF_SCOPE_WRITE, snapshot),
            report.getValue("out-of-scope writes"),
            "out-of-scope writes drifted from coding.fs:${PermissionAudit.CODE_OUT_OF_SCOPE_WRITE}",
        )
        assertEquals(
            count("coding.shell", PermissionAudit.CODE_NETWORK_TARGET, snapshot),
            report.getValue("network targets"),
            "network targets drifted from coding.shell:${PermissionAudit.CODE_NETWORK_TARGET}",
        )
        assertEquals(
            count("coding.shell", PermissionAudit.CODE_SCOPE_UNCERTAIN, snapshot),
            report.getValue("scope uncertain"),
            "scope uncertain drifted from coding.shell:${PermissionAudit.CODE_SCOPE_UNCERTAIN}",
        )
        assertEquals(
            count("coding.env", PermissionAudit.CODE_ENV_PASSTHROUGH, snapshot),
            report.getValue("env passthrough"),
            "env passthrough drifted from coding.env:${PermissionAudit.CODE_ENV_PASSTHROUGH}",
        )
    }

    private fun count(tool: String, code: String, snapshot: Map<String, Int>): Int =
        snapshot["$tool:$code"] ?: 0

    private fun report(fs: CodingAgentFileSystem, shell: CodingAgentShell): Map<String, Int> = runBlocking {
        val value = executor.callFunctionOn(
            ToolCall(domain = "coding", method = "permissions", arguments = mutableMapOf()),
            CodingToolExecutor.Target(shell = shell, fs = fs),
        ).value
        assertTrue(value is String, "coding.permissions returned ${value?.javaClass?.name}: $value")
        parseCounts(value as String)
    }

    /** Parses the four audited counters; a missing label fails rather than defaulting to zero. */
    private fun parseCounts(report: String): Map<String, Int> {
        val found = mutableMapOf<String, Int>()
        for (match in COUNT_LINE.findAll(report)) {
            found[match.groupValues[1]] = match.groupValues[2].toInt()
        }
        assertEquals(AUDITED_LABELS, found.keys, "audited counters missing from the report:\n$report")
        return found
    }

    private companion object {
        /** The four counters `coding.permissions` reports from [PermissionAudit.snapshot]. */
        private val AUDITED_LABELS =
            setOf("out-of-scope writes", "network targets", "scope uncertain", "env passthrough")

        private val COUNT_LINE =
            Regex("""(out-of-scope writes|network targets|scope uncertain|env passthrough)\s*:\s*(\d+)""")
    }
}
