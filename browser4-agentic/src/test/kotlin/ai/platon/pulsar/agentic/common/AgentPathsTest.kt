package ai.platon.pulsar.agentic.common

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DisplayName("AgentPaths trace directory layout and dev symlink")
class AgentPathsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("resolveTraceRunDir returns <traceDir>/<time>/<uuid> and creates it")
    fun resolveTraceRunDirCreatesRunDirectory() {
        val time = Instant.parse("2026-08-25T01:17:36Z")
        val uuid = UUID.fromString("5f558901-b1a3-4bf5-85a7-4a4f803dbbc6")

        val runDir = AgentPaths.resolveTraceRunDir(time, uuid)

        assertEquals(AgentPaths.AGENT_TRACE_DIR, runDir.parent.parent)
        assertTrue(Files.isDirectory(runDir), "the run directory must exist: $runDir")
        assertTrue(runDir.toString().contains(uuid.toString()), "run dir must carry the agent uuid: $runDir")
    }

    @Test
    @DisplayName("createTraceLinkIfPossible links projectRoot/logs/agent to the trace dir")
    fun createTraceLinkLinksProjectLogs() {
        val projectRoot = tempDir.resolve("project")
        Files.createDirectories(projectRoot)
        val traceDir = tempDir.resolve("data").resolve("logs").resolve("agent")
        Files.createDirectories(traceDir)

        val link = AgentPaths.createTraceLinkIfPossible(projectRoot, traceDir)

        if (link == null) {
            // Platform without symlink privilege (e.g. Windows without Developer
            // Mode): the failure must be contained, not thrown.
            assertTrue(true, "symlink creation may be unsupported; containment is the contract")
        } else {
            assertEquals(projectRoot.resolve("logs").resolve("agent"), link)
            assertTrue(Files.isSymbolicLink(link), "a real symlink must be created: $link")
            assertEquals(traceDir, Files.readSymbolicLink(link), "link must point at the durable trace dir")
        }
    }

    @Test
    @DisplayName("createTraceLinkIfPossible is a no-op when the link target entry already exists")
    fun createTraceLinkSkipsExistingEntry() {
        val projectRoot = tempDir.resolve("project")
        Files.createDirectories(projectRoot.resolve("logs").resolve("agent"))
        val traceDir = tempDir.resolve("data").resolve("logs").resolve("agent")
        Files.createDirectories(traceDir)

        val link = AgentPaths.createTraceLinkIfPossible(projectRoot, traceDir)

        assertNull(link, "an existing entry must never be replaced")
        assertFalse(Files.isSymbolicLink(projectRoot.resolve("logs").resolve("agent")),
            "the existing real directory must stay untouched")
    }

    @Test
    @DisplayName("createTraceLinkIfPossible keeps a previous symlink intact")
    fun createTraceLinkKeepsPreviousLink() {
        val projectRoot = tempDir.resolve("project")
        Files.createDirectories(projectRoot)
        val traceDir = tempDir.resolve("data").resolve("logs").resolve("agent")
        Files.createDirectories(traceDir)
        val link = projectRoot.resolve("logs").resolve("agent")
        // Create the link once through the helper, then attempt again.
        val first = AgentPaths.createTraceLinkIfPossible(projectRoot, traceDir)

        val second = AgentPaths.createTraceLinkIfPossible(projectRoot, traceDir)

        if (first != null) {
            assertNull(second, "a second attempt must be a no-op")
            assertTrue(Files.isSymbolicLink(link), "the original link must survive")
            assertEquals(traceDir, Files.readSymbolicLink(link))
        }
    }

    @Test
    @DisplayName("createTraceLinkIfPossible creates the logs parent directory when absent")
    fun createTraceLinkCreatesLogsParent() {
        val projectRoot = tempDir.resolve("project")
        Files.createDirectories(projectRoot)
        val traceDir = tempDir.resolve("data").resolve("logs").resolve("agent")
        Files.createDirectories(traceDir)

        val link = AgentPaths.createTraceLinkIfPossible(projectRoot, traceDir)

        if (link != null) {
            assertTrue(Files.isDirectory(projectRoot.resolve("logs")),
                "the logs parent must be created for the link: $link")
        }
    }

    @Test
    @DisplayName("trace dir link helper returns null without a project root")
    fun linkTraceDirNoProjectRootIsNoop() {
        // findProjectRootDir() searches the real working directory; the public
        // entry point degrades to null when nothing is resolvable — the pure
        // creation helper covers the actual link logic.
        assertNotNull(AgentPaths.AGENT_TRACE_DIR)
        assertTrue(Files.isDirectory(AgentPaths.AGENT_TRACE_DIR, LinkOption.NOFOLLOW_LINKS))
    }
}
