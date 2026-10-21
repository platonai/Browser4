package ai.platon.pulsar.coding

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * One completed plan step, recorded in a [DevTaskCheckpoint].
 *
 * [output] is bounded: the checkpoint is a resume record, not a log.
 */
data class DevTaskStepResult(
    val order: Int,
    val tool: String,
    val ok: Boolean,
    val output: String = "",
)

/**
 * Durable progress record of one `coding.devTask(execute=true)` run (plan P2.4).
 *
 * Execution is step-based and deterministic, so a crashed/timed-out run can
 * continue where it stopped: the store keeps the plan snapshot plus every
 * completed step, and a resume runs ONLY the remaining steps. Re-running a
 * completed step is skipped rather than repeated — the checkpoint is the
 * idempotency key (plan P2.5).
 */
data class DevTaskCheckpoint(
    val id: String,
    val task: String,
    /** `RUNNING` | `FAILED` | `DONE`. */
    val state: String = STATE_RUNNING,
    /** Plan snapshot: order → (tool, purpose, command, args). */
    val steps: List<DevTaskStepSnapshot> = emptyList(),
    /** Steps that already ran, in execution order. */
    val completed: List<DevTaskStepResult> = emptyList(),
    val failedStep: Int? = null,
    val failureMessage: String? = null,
    /** The last `coding.verify` verdict observed during the run, if any. */
    val verificationPassed: Boolean? = null,
    /**
     * The isolated worktree the failed run was using, when workspace isolation
     * was on. A failed run KEEPS its worktree (the main tree stays clean either
     * way) so a resume continues in it instead of starting from scratch.
     */
    val isolatedRoot: String? = null,
    val createdAt: String = "",
    val updatedAt: String = "",
) {
    val isDone: Boolean get() = state == STATE_DONE

    /** Steps still to run (ordered). */
    fun remainingSteps(): List<DevTaskStepSnapshot> {
        val done = completed.map { it.order }.toSet()
        return steps.filter { it.order !in done }.sortedBy { it.order }
    }

    companion object {
        const val STATE_RUNNING = "RUNNING"
        const val STATE_FAILED = "FAILED"
        const val STATE_DONE = "DONE"
    }
}

/** One step of the plan snapshot stored in a checkpoint. */
data class DevTaskStepSnapshot(
    val order: Int,
    val tool: String,
    val purpose: String,
    val command: String,
    val args: Map<String, String> = emptyMap(),
)

/**
 * File-backed store for [DevTaskCheckpoint]s.
 *
 * Lives under `<workspace>/.browser4/devtask-checkpoints/` (git-ignored), one
 * JSON file per run, so a checkpoint survives a backend restart — the whole
 * point of a resume record.
 */
class DevTaskCheckpointStore(
    private val workspaceRoot: Path,
    private val enabled: Boolean = true,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(DevTaskCheckpointStore::class.java)

        const val DIR = ".browser4/devtask-checkpoints"

        /** Keep the serialized checkpoints readable and stable in diffs/tests. */
        private val MAPPER = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)

        /** Snapshot cap: a checkpoint records progress, not a transcript. */
        const val MAX_STEP_OUTPUT = 4_000
    }

    private val dir: Path = workspaceRoot.resolve(DIR)

    fun save(checkpoint: DevTaskCheckpoint): Boolean {
        if (!enabled) return false
        return runCatching {
            Files.createDirectories(dir)
            Files.writeString(file(checkpoint.id), MAPPER.writeValueAsString(toJson(checkpoint)))
            true
        }.getOrElse { e ->
            logger.warn("Could not persist dev-task checkpoint {}: {}", checkpoint.id, e.message)
            false
        }
    }

    fun load(id: String): DevTaskCheckpoint? {
        if (!enabled) return null
        val file = file(id)
        if (!Files.isRegularFile(file)) return null
        return runCatching { fromJson(Files.readString(file)) }.getOrElse { e ->
            logger.warn("Could not read dev-task checkpoint {}: {}", id, e.message)
            null
        }
    }

    /** Newest checkpoints first. */
    fun list(limit: Int = 20): List<DevTaskCheckpoint> {
        if (!enabled || !Files.isDirectory(dir)) return emptyList()
        return runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") }
                    .map { it.fileName.toString().removeSuffix(".json") }
                    .toList()
            }.mapNotNull { load(it) }
                .sortedByDescending { it.updatedAt }
                .take(limit)
        }.getOrDefault(emptyList())
    }

    fun delete(id: String): Boolean {
        if (!enabled) return false
        return runCatching { Files.deleteIfExists(file(id)) }.getOrDefault(false)
    }

    private fun file(id: String): Path = dir.resolve("${sanitize(id)}.json")

    private fun sanitize(id: String): String =
        id.replace(Regex("[^A-Za-z0-9._-]"), "-").take(96).ifBlank { "unknown" }

    /**
     * Explicit JSON mapping: the checkpoint travels through restart and crash
     * boundaries, so its shape is a contract — hand-mapping it keeps the store
     * independent of Kotlin/Jackson module wiring (browser4-coding deliberately
     * depends only on jackson-databind).
     */
    private fun toJson(cp: DevTaskCheckpoint): Map<String, Any?> = linkedMapOf(
        "id" to cp.id,
        "task" to cp.task,
        "state" to cp.state,
        "steps" to cp.steps.map { s ->
            linkedMapOf(
                "order" to s.order,
                "tool" to s.tool,
                "purpose" to s.purpose,
                "command" to s.command,
                "args" to s.args,
            )
        },
        "completed" to cp.completed.map { c ->
            linkedMapOf("order" to c.order, "tool" to c.tool, "ok" to c.ok, "output" to c.output)
        },
        "failedStep" to cp.failedStep,
        "failureMessage" to cp.failureMessage,
        "verificationPassed" to cp.verificationPassed,
        "isolatedRoot" to cp.isolatedRoot,
        "createdAt" to cp.createdAt,
        "updatedAt" to cp.updatedAt,
    )

    private fun fromJson(json: String): DevTaskCheckpoint? = runCatching {
        val node = MAPPER.readTree(json)
        DevTaskCheckpoint(
            id = node.get("id")?.asText() ?: return null,
            task = node.get("task")?.asText().orEmpty(),
            state = node.get("state")?.asText() ?: DevTaskCheckpoint.STATE_RUNNING,
            steps = node.get("steps")?.map { s ->
                DevTaskStepSnapshot(
                    order = s.get("order")?.asInt() ?: 0,
                    tool = s.get("tool")?.asText().orEmpty(),
                    purpose = s.get("purpose")?.asText().orEmpty(),
                    command = s.get("command")?.asText().orEmpty(),
                    args = s.get("args")?.fields()?.asSequence()
                        ?.associate { it.key to it.value.asText() } ?: emptyMap(),
                )
            } ?: emptyList(),
            completed = node.get("completed")?.map { c ->
                DevTaskStepResult(
                    order = c.get("order")?.asInt() ?: 0,
                    tool = c.get("tool")?.asText().orEmpty(),
                    ok = c.get("ok")?.asBoolean() ?: false,
                    output = c.get("output")?.asText().orEmpty(),
                )
            } ?: emptyList(),
            failedStep = node.get("failedStep")?.takeIf { !it.isNull }?.asInt(),
            failureMessage = node.get("failureMessage")?.takeIf { !it.isNull }?.asText(),
            verificationPassed = node.get("verificationPassed")?.takeIf { !it.isNull }?.asBoolean(),
            isolatedRoot = node.get("isolatedRoot")?.takeIf { !it.isNull }?.asText(),
            createdAt = node.get("createdAt")?.asText().orEmpty(),
            updatedAt = node.get("updatedAt")?.asText().orEmpty(),
        )
    }.getOrNull()
}
