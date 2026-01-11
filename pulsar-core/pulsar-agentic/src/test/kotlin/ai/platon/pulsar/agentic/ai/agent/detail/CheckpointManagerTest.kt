package ai.platon.pulsar.agentic.ai.agent.detail

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

/**
 * Integration tests for CheckpointManager component.
 */
class CheckpointManagerTest {
    
    @TempDir
    lateinit var tempDir: Path
    
    private lateinit var checkpointManager: CheckpointManager
    
    @BeforeEach
    fun setUp() {
        checkpointManager = CheckpointManager(tempDir)
    }
    
    @Test
    fun `should save and load checkpoint`() {
        val checkpoint = createTestCheckpoint("session-123")
        
        val path = checkpointManager.save(checkpoint)
        assertTrue(path.toFile().exists())
        
        val loaded = checkpointManager.load("session-123")
        assertNotNull(loaded)
        assertEquals(checkpoint.sessionId, loaded?.sessionId)
        assertEquals(checkpoint.currentStep, loaded?.currentStep)
        assertEquals(checkpoint.instruction, loaded?.instruction)
    }
    
    @Test
    fun `should create latest checkpoint file`() {
        val checkpoint = createTestCheckpoint("session-456")
        
        checkpointManager.save(checkpoint)
        
        val latestFile = tempDir.resolve("checkpoint-session-456-latest.json")
        assertTrue(latestFile.toFile().exists())
    }
    
    @Test
    fun `should load latest checkpoint when checkpointId not specified`() {
        val checkpoint1 = createTestCheckpoint("session-789", step = 10)
        val checkpoint2 = createTestCheckpoint("session-789", step = 20)
        
        checkpointManager.save(checkpoint1)
        checkpointManager.save(checkpoint2)
        
        val loaded = checkpointManager.load("session-789")
        assertNotNull(loaded)
        assertEquals(20, loaded?.currentStep)
    }
    
    @Test
    fun `should return null for non-existent checkpoint`() {
        val loaded = checkpointManager.load("non-existent-session")
        assertNull(loaded)
    }
    
    @Test
    fun `should list all checkpoints for session`() {
        val session = "session-list-test"
        
        checkpointManager.save(createTestCheckpoint(session, step = 10))
        checkpointManager.save(createTestCheckpoint(session, step = 20))
        checkpointManager.save(createTestCheckpoint(session, step = 30))
        
        val checkpoints = checkpointManager.listCheckpoints(session)
        
        // Should not include "latest" file
        assertEquals(3, checkpoints.size)
        
        // Should be sorted by timestamp (newest first)
        assertTrue(checkpoints[0].currentStep >= checkpoints[1].currentStep)
    }
    
    @Test
    fun `should prune old checkpoints`() {
        val session = "session-prune-test"
        
        // Save 5 checkpoints
        repeat(5) { i ->
            Thread.sleep(10) // Ensure different timestamps
            checkpointManager.save(createTestCheckpoint(session, step = i * 10))
        }
        
        // Prune to keep only 2
        checkpointManager.pruneOldCheckpoints(session, keepCount = 2)
        
        val remaining = checkpointManager.listCheckpoints(session)
        assertEquals(2, remaining.size)
    }
    
    @Test
    fun `should delete all checkpoints for session`() {
        val session = "session-delete-test"
        
        checkpointManager.save(createTestCheckpoint(session, step = 10))
        checkpointManager.save(createTestCheckpoint(session, step = 20))
        
        checkpointManager.deleteAll(session)
        
        val remaining = checkpointManager.listCheckpoints(session)
        assertEquals(0, remaining.size)
        
        val latest = checkpointManager.load(session)
        assertNull(latest)
    }
    
    @Test
    fun `should preserve checkpoint data integrity`() {
        val checkpoint = AgentCheckpoint(
            sessionId = "session-integrity-test",
            currentStep = 42,
            instruction = "Test instruction with special chars: <>&\"'",
            targetUrl = "https://example.com/path?query=value",
            recentStateHistory = listOf(
                AgentStateSnapshot(1, "inst1", "dom1", "act1", "desc1", true),
                AgentStateSnapshot(2, "inst2", "dom2", "act2", "desc2", false)
            ),
            totalSteps = 100,
            successfulActions = 85,
            failedActions = 15,
            failureCounts = mapOf(
                "LLM_FAILURE" to 3,
                "VALIDATION_FAILURE" to 2
            ),
            configSnapshot = mapOf(
                "maxSteps" to 100,
                "maxRetries" to 3
            ),
            metadata = mapOf(
                "key1" to "value1",
                "key2" to "value2"
            )
        )
        
        checkpointManager.save(checkpoint)
        val loaded = checkpointManager.load(checkpoint.sessionId)
        
        assertNotNull(loaded)
        assertEquals(checkpoint.sessionId, loaded?.sessionId)
        assertEquals(checkpoint.currentStep, loaded?.currentStep)
        assertEquals(checkpoint.instruction, loaded?.instruction)
        assertEquals(checkpoint.targetUrl, loaded?.targetUrl)
        assertEquals(2, loaded?.recentStateHistory?.size)
        assertEquals(checkpoint.totalSteps, loaded?.totalSteps)
        assertEquals(checkpoint.successfulActions, loaded?.successfulActions)
        assertEquals(checkpoint.failedActions, loaded?.failedActions)
        assertEquals(2, loaded?.failureCounts?.size)
        assertEquals(2, loaded?.configSnapshot?.size)
        assertEquals(2, loaded?.metadata?.size)
    }

    @Test
    fun `should check if checkpoint exists`() {
        val session = "session-exists-test"
        
        // Before saving, should not exist
        assertFalse(checkpointManager.exists(session))
        
        // After saving, should exist
        checkpointManager.save(createTestCheckpoint(session, step = 10))
        assertTrue(checkpointManager.exists(session))
        
        // After deleting, should not exist
        checkpointManager.deleteAll(session)
        assertFalse(checkpointManager.exists(session))
    }

    @Test
    fun `should preserve step-level state for resumption`() {
        val checkpoint = AgentCheckpoint(
            sessionId = "session-resume-test",
            currentStep = 15,
            instruction = "Test resume instruction",
            targetUrl = "https://example.com/resume",
            recentStateHistory = listOf(
                AgentStateSnapshot(15, "instruction", "domain", "action", "description", true)
            ),
            totalSteps = 20,
            successfulActions = 18,
            failedActions = 2,
            failureCounts = mapOf("LLM_FAILURE" to 1),
            configSnapshot = mapOf("maxSteps" to 100),
            metadata = mapOf("test" to "value"),
            consecutiveNoOps = 2,
            isComplete = false,
            lastEvent = "toolExec",
            agentUuid = "test-agent-uuid-123"
        )
        
        checkpointManager.save(checkpoint)
        val loaded = checkpointManager.load(checkpoint.sessionId)
        
        assertNotNull(loaded)
        assertEquals(2, loaded?.consecutiveNoOps)
        assertEquals(false, loaded?.isComplete)
        assertEquals("toolExec", loaded?.lastEvent)
        assertEquals("test-agent-uuid-123", loaded?.agentUuid)
    }

    @Test
    fun `should preserve completed checkpoint state`() {
        val checkpoint = AgentCheckpoint(
            sessionId = "session-complete-test",
            currentStep = 25,
            instruction = "Test complete instruction",
            targetUrl = "https://example.com/complete",
            recentStateHistory = listOf(
                AgentStateSnapshot(25, "instruction", "domain", "action", "description", true)
            ),
            totalSteps = 25,
            successfulActions = 24,
            failedActions = 1,
            failureCounts = mapOf("LLM_FAILURE" to 0),
            configSnapshot = mapOf("maxSteps" to 100),
            metadata = mapOf("test" to "value"),
            consecutiveNoOps = 0,
            isComplete = true,
            lastEvent = "complete",
            agentUuid = "test-agent-uuid-456"
        )
        
        checkpointManager.save(checkpoint)
        val loaded = checkpointManager.load(checkpoint.sessionId)
        
        assertNotNull(loaded)
        assertEquals(true, loaded?.isComplete)
        assertEquals("complete", loaded?.lastEvent)
    }

    @Test
    fun `should return checkpoint directory path`() {
        assertEquals(tempDir, checkpointManager.getCheckpointDir())
    }
    
    private fun createTestCheckpoint(sessionId: String, step: Int = 10): AgentCheckpoint {
        return AgentCheckpoint(
            sessionId = sessionId,
            currentStep = step,
            instruction = "Test instruction",
            targetUrl = "https://example.com",
            recentStateHistory = listOf(
                AgentStateSnapshot(step, "instruction", "domain", "action", "description", true)
            ),
            totalSteps = step,
            successfulActions = step - 1,
            failedActions = 1,
            failureCounts = mapOf("LLM_FAILURE" to 0),
            configSnapshot = mapOf("maxSteps" to 100),
            metadata = mapOf("test" to "value")
        )
    }
}
