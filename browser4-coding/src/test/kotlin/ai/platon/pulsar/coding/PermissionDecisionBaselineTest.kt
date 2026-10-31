package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * M0 golden decision baseline (execution plan A7).
 *
 * The pre-A tests describe decisions one call at a time
 * (`CodingAgentShell*Test`, `CodingAgentFileSystem*Test`, `AgentAccessPolicyTest`). This test
 * freezes those outcomes in a JSON resource and replays each input against the current
 * implementation, so a later phase that shifts a decision — allow to deny, or a different audit
 * code — fails here instead of drifting unnoticed.
 *
 * Only the *decision* is compared. Nothing spawns a process, and nothing asserts on message
 * wording beyond the audit code, so the baseline stays an authorization contract rather than a
 * copy of the refusal text.
 */
class PermissionDecisionBaselineTest {

    @TempDir
    lateinit var tempDir: Path

    private val workspace: Path by lazy { Files.createDirectories(tempDir.resolve("workspace")) }

    /** A file next to the workspace: outside the file-system boundary, inside the temp dir. */
    private val outsideFile: Path by lazy {
        Files.createDirectories(tempDir.resolve("outside")).resolve("outside.txt").also {
            Files.writeString(it, "outside\n")
        }
    }

    /**
     * A file at the filesystem root.
     *
     * The shell boundary has to be probed with a path outside the workspace *and* outside the
     * toolchain roots ([CodingComponents.toolchainRoots] includes `java.io.tmpdir`), which is why
     * this uses the root rather than [outsideFile].
     */
    private val rootPath: Path by lazy { workspace.root.resolve("b4-outside-data.csv") }

    private val baseline: JsonNode by lazy {
        val resource = javaClass.getResourceAsStream("/ai/platon/pulsar/coding/permission-decision-baseline.json")
        assertNotNull(resource, "baseline resource is missing from the test classpath")
        ObjectMapper().readTree(resource)
    }

    @BeforeEach
    fun requireBaselineShape() {
        for (section in listOf("shell", "fs", "policy")) {
            val entries = baseline.path(section)
            assertTrue(entries.isArray && entries.size() > 0, "baseline section '$section' must be a non-empty array")
        }
    }

    // ==================== replay ====================

    @Test
    @DisplayName("shell decisions match the golden baseline")
    fun shellDecisionsMatch() {
        forEachEntry("shell") { node ->
            val command = substitute(node.path("command").asText())
            val shell = CodingAgentShell(
                baseDir = workspace,
                allowExternalAccess = node.path("allowExternalAccess").asBoolean(false),
                pathGuardEnabled = node.path("pathGuardEnabled").asBoolean(true),
                workspaceBoundaryEnabled = node.path("workspaceBoundaryEnabled").asBoolean(true),
            )
            val actual = shellDecision(shell, command)
            assertMatches("shell", node, actual, command)
        }
    }

    @Test
    @DisplayName("file-system decisions match the golden baseline")
    fun fileSystemDecisionsMatch() = runBlocking {
        forEachEntry("fs") { node ->
            val fs = CodingAgentFileSystem(
                workspaceRoot = workspace,
                allowExternalAccess = node.path("allowExternalAccess").asBoolean(false),
                readOnly = node.path("readOnly").asBoolean(false),
            )
            val path = substitute(node.path("path").asText())
            val result = when (node.path("op").asText()) {
                "read" -> fs.readFile(path)
                "write" -> fs.writeFile(path, node.path("content").asText(""))
                "delete" -> fs.delete(path)
                else -> error("unknown fs op '${node.path("op").asText()}' in baseline")
            }
            val actual = fsDecision(result)
            assertMatches("fs", node, actual, "$path -> ${shorten(result)}")
        }
    }

    @Test
    @DisplayName("access-policy decisions match the golden baseline")
    fun accessPolicyDecisionsMatch() {
        forEachEntry("policy") { node ->
            val preset = AccessPreset.parse(node.path("preset").asText())
                ?: error("unknown preset '${node.path("preset").asText()}' in baseline")
            val denyTools = node.path("denyTools")
                .takeIf { it.isArray }
                ?.map { it.asText() }
                ?.toSet()
                ?: emptySet()
            val policy = AgentAccessPolicy(preset = preset, denyTools = denyTools)
            val target = "${node.path("domain").asText()}.${node.path("method").asText()}"
            val denial = policy.denial(node.path("domain").asText(), node.path("method").asText())
            val actual = if (denial == null) Decision(true, null)
            else Decision(false, auditCodeOf(denial.message.orEmpty()))
            assertMatches("policy", node, actual, target)
        }
    }

    // ==================== decision helpers ====================

    private data class Decision(val allowed: Boolean, val code: String?)

    private fun shellDecision(shell: CodingAgentShell, command: String): Decision {
        if (shell.isAllowed(command)) return Decision(true, null)
        // `explain` delegates to the same validator, and the refusal text carries the cause prefix
        // that `commandRefusal` maps to an audit code. Mirroring that mapping keeps the baseline
        // independent of private internals.
        val text = shell.explain(command)
        val code = when {
            text.contains("path guard: ") -> PermissionAudit.CODE_COMMAND_PATH_DENIED
            text.contains("protected path: ") -> PermissionAudit.CODE_PROTECTED_PATH
            else -> PermissionAudit.CODE_COMMAND_NOT_ALLOWED
        }
        return Decision(false, code)
    }

    private fun fsDecision(result: String): Decision {
        if (!result.contains(PermissionAudit.DENIED)) return Decision(true, null)
        return Decision(false, auditCodeOf(result))
    }

    private val auditCode = Regex("""code=([A-Z_]+)""")

    private fun auditCodeOf(text: String): String? = auditCode.find(text)?.groupValues?.get(1)

    private fun assertMatches(kind: String, node: JsonNode, actual: Decision, detail: String) {
        val id = node.path("id").asText()
        val expectedAllowed = node.path("allowed").asBoolean()
        val expectedCode = node.path("code").takeIf { it.isTextual }?.asText()

        assertEquals(
            expectedAllowed, actual.allowed,
            "[$kind] $id: allow/deny drifted from the M0 baseline ($detail)"
        )
        assertEquals(
            expectedCode, actual.code,
            "[$kind] $id: audit code drifted from the M0 baseline ($detail)"
        )
    }

    // `inline` so the replay lambda keeps the caller's coroutine context: the file-system replay
    // calls suspend APIs under `runBlocking`, and a non-inline lambda would break that.
    private inline fun forEachEntry(section: String, replay: (JsonNode) -> Unit) {
        for (node in baseline.path(section)) {
            assertTrue(node.path("allowed").isBoolean, "baseline entry in '$section' is missing 'allowed': $node")
            replay(node)
        }
    }

    private fun substitute(text: String): String = text
        .replace("{workspace}", workspace.toString())
        .replace("{outsideFile}", outsideFile.toString())
        .replace("{rootPath}", rootPath.toString())
        .replace("{relativeEscape}", workspace.relativize(rootPath).toString())

    private fun shorten(text: String): String =
        text.replace('\n', ' ').take(60)
}
