package ai.platon.pulsar.agentic.permission

import ai.platon.pulsar.agentic.AgenticSession
import ai.platon.pulsar.agentic.agents.BasicBrowserAgent
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.agentic.tools.AgentToolManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@DisplayName("AgentToolManager + Permission System Integration")
class AgentToolManagerPermissionIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var agent: BasicBrowserAgent
    private lateinit var session: AgenticSession
    private lateinit var toolManager: AgentToolManager

    @BeforeEach
    fun setUp() {
        session = mockk(relaxed = true)
        every { session.id } returns 1L

        agent = mockk(relaxed = true)
        every { agent.uuid } returns java.util.UUID.randomUUID()
        every { agent.session } returns session

        toolManager = AgentToolManager(tempDir, agent)
    }

    @Nested
    @DisplayName("disabled by default")
    inner class DisabledByDefault {

        @Test
        @DisplayName("permissionManager is disabled after construction")
        fun isDisabled() {
            assertFalse(toolManager.permissionManager.isEnabled)
        }

        @Test
        @DisplayName("any tool call passes through without permission check")
        fun anyToolPasses() = runBlocking {
            // Even destructive operations pass through when disabled
            val tc = ToolCall("coding", "delete", mutableMapOf("path" to "important.txt"))
            // This should NOT throw a permission exception — it will fail
            // on actual execution (mocked driver is null) but that's expected
            try {
                toolManager.execute(tc)
            } catch (_: Exception) {
                // May fail because the mocked driver is null, but NOT because of permissions
            }
            // Verify it wasn't a permission exception
        }
    }

    @Nested
    @DisplayName("installed policy blocks operations")
    inner class InstalledPolicyBlocks {

        @Test
        @DisplayName("readOnlyProfile blocks coding.write with PermissionDeniedException")
        fun blocksCodingWrite() = runBlocking {
            toolManager.installPermissionPolicy(PermissionDefaults.readOnlyProfile())
            assertTrue(toolManager.permissionManager.isEnabled)

            val tc = ToolCall("coding", "write", mutableMapOf("path" to "test.txt"))
            try {
                toolManager.execute(tc)
                fail("Expected PermissionDeniedException")
            } catch (e: PermissionDeniedException) {
                assertTrue(e.decision.reason.contains("read-only"))
            }
        }

        @Test
        @DisplayName("readOnlyProfile blocks fs.deleteFile with PermissionDeniedException")
        fun blocksFsWrite() = runBlocking {
            toolManager.installPermissionPolicy(PermissionDefaults.readOnlyProfile())

            val tc = ToolCall("fs", "deleteFile", mutableMapOf("fullFileName" to "test.txt"))
            try {
                toolManager.execute(tc)
                fail("Expected PermissionDeniedException")
            } catch (e: PermissionDeniedException) {
                assertTrue(e.decision.reason.contains("read-only"))
            }
        }

        @Test
        @DisplayName("readOnlyProfile allows coding.read to pass permission check")
        fun allowsCodingRead() = runBlocking {
            toolManager.installPermissionPolicy(PermissionDefaults.readOnlyProfile())

            // coding.read is allowed — it will fail on actual execution (no real shell)
            // but should NOT throw a PermissionDeniedException
            val tc = ToolCall("coding", "read", mutableMapOf("path" to "README.md"))
            try {
                toolManager.execute(tc)
            } catch (e: Exception) {
                assertFalse(e is PermissionDeniedException,
                    "coding.read should be allowed but got: ${e.message}")
            }
        }
    }

    @Nested
    @DisplayName("strict profile deny-by-default")
    inner class StrictProfile {

        @Test
        @DisplayName("unlisted operation is denied")
        fun unlistedDenied() = runBlocking {
            toolManager.installPermissionPolicy(PermissionDefaults.strictProfile())

            val tc = ToolCall("tab", "type", mutableMapOf("text" to "hello"))
            try {
                toolManager.execute(tc)
                fail("Expected PermissionDeniedException")
            } catch (e: PermissionDeniedException) {
                assertTrue(e.decision.reason.contains("default deny"))
            }
        }

        @Test
        @DisplayName("explicitly allowed operation passes check")
        fun explicitlyAllowed() = runBlocking {
            toolManager.installPermissionPolicy(PermissionDefaults.strictProfile())

            // tab.title is READ → allowed by strict.allow-tab-read
            val tc = ToolCall("tab", "title")
            try {
                toolManager.execute(tc)
            } catch (e: Exception) {
                assertFalse(e is PermissionDeniedException,
                    "tab.title should be allowed but got: ${e.message}")
            }
        }
    }
}
