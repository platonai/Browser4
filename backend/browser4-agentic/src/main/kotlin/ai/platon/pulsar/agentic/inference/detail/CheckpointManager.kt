package ai.platon.pulsar.agentic.inference.detail

import ai.platon.pulsar.agentic.model.AgentState
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*

/**
 * Checkpoint data for resuming agent sessions.
 *
 * Contains all necessary state to resume execution from a saved point.
 */
data class AgentCheckpoint(
    val sessionId: String,
    val checkpointId: String = UUID.randomUUID().toString(),
    val timestamp: Instant = Instant.now(),
    val currentStep: Int,
    val instruction: String,
    val targetUrl: String?,

    // State history (limited to avoid large files)
    val recentStateHistory: List<AgentStateSnapshot>,

    // Performance metrics
    val totalSteps: Int,
    val successfulActions: Int,
    val failedActions: Int,

    // Circuit breaker state
    val failureCounts: Map<String, Int>,

    // Configuration snapshot
    val configSnapshot: Map<String, Any>,

    // Additional metadata
    val metadata: Map<String, Any> = emptyMap()
) {
    @get:JsonIgnore
    val age: Long get() = System.currentTimeMillis() - timestamp.toEpochMilli()
}

/**
 * Simplified snapshot of AgentState for serialization.
 */
data class AgentStateSnapshot(
    val step: Int,
    val instruction: String,
    val domain: String?,
    val action: String?,
    val description: String?,
    val success: Boolean = true
) {
    companion object {
        fun from(state: AgentState): AgentStateSnapshot {
            return AgentStateSnapshot(
                step = state.step,
                instruction = state.instruction,
                domain = state.domain,
                action = state.method,
                description = state.description,
                success = state.description?.contains("FAIL") != true
            )
        }
    }
}

/**
 * Manages checkpointing and restoration of agent sessions.
 *
 * Supports saving/loading session state to enable:
 * - Recovery from crashes
 * - Pause/resume functionality
 * - Debugging and analysis
 */
class CheckpointManager(
    private val checkpointDir: Path
) {
    private val mapper = ObjectMapper().apply {
        registerModule(KotlinModule.Builder().build())
        registerModule(JavaTimeModule())
        enable(SerializationFeature.INDENT_OUTPUT)
        disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }

    init {
        Files.createDirectories(checkpointDir)
    }

    /**
     * Save a checkpoint to disk.
     *
     * @param checkpoint The checkpoint data to save
     * @return Path to the saved checkpoint file
     */
    fun save(checkpoint: AgentCheckpoint): Path {
        val filename = "checkpoint-${checkpoint.sessionId}-${checkpoint.checkpointId}.json"
        val file = checkpointDir.resolve(filename)

        mapper.writeValue(file.toFile(), checkpoint)

        // Also save as "latest" for easy recovery
        val latestFile = checkpointDir.resolve("checkpoint-${checkpoint.sessionId}-latest.json")
        mapper.writeValue(latestFile.toFile(), checkpoint)

        return file
    }

    /**
     * Load a checkpoint from disk.
     *
     * @param sessionId The session ID to load
     * @param checkpointId Optional specific checkpoint ID, or null for latest
     * @return The loaded checkpoint, or null if not found
     */
    fun load(sessionId: String, checkpointId: String? = null): AgentCheckpoint? {
        val file = if (checkpointId != null) {
            checkpointDir.resolve("checkpoint-$sessionId-$checkpointId.json")
        } else {
            checkpointDir.resolve("checkpoint-$sessionId-latest.json")
        }

        return if (Files.exists(file)) {
            mapper.readValue(file.toFile())
        } else {
            null
        }
    }

    /**
     * List all checkpoints for a session.
     *
     * @param sessionId The session ID
     * @return List of checkpoint metadata
     */
    fun listCheckpoints(sessionId: String): List<CheckpointMetadata> {
        return Files.list(checkpointDir)
            .filter { it.fileName.toString().startsWith("checkpoint-$sessionId-") }
            .filter { !it.fileName.toString().endsWith("-latest.json") }
            .map { path ->
                val checkpoint: AgentCheckpoint = mapper.readValue(path.toFile())
                CheckpointMetadata(
                    sessionId = checkpoint.sessionId,
                    checkpointId = checkpoint.checkpointId,
                    timestamp = checkpoint.timestamp,
                    currentStep = checkpoint.currentStep,
                    path = path
                )
            }
            .sorted(compareByDescending<CheckpointMetadata> { it.timestamp }
                .thenByDescending { it.currentStep })
            .toList()
    }

    /**
     * Delete old checkpoints for a session, keeping only the most recent N.
     *
     * @param sessionId The session ID
     * @param keepCount Number of recent checkpoints to keep
     */
    fun pruneOldCheckpoints(sessionId: String, keepCount: Int = 5) {
        val checkpoints = listCheckpoints(sessionId)
        checkpoints.drop(keepCount).forEach { metadata ->
            Files.deleteIfExists(metadata.path)
        }
    }

    /**
     * Delete all checkpoints for a session.
     */
    fun deleteAll(sessionId: String) {
        Files.list(checkpointDir)
            .filter { it.fileName.toString().startsWith("checkpoint-$sessionId-") }
            .forEach { Files.deleteIfExists(it) }
    }
}

/**
 * Metadata about a checkpoint file.
 */
data class CheckpointMetadata(
    val sessionId: String,
    val checkpointId: String,
    val timestamp: Instant,
    val currentStep: Int,
    @JsonIgnore val path: Path
)
