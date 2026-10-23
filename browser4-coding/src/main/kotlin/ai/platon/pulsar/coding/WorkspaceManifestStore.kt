package ai.platon.pulsar.coding

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Persistence for [WorkspaceManifest].
 *
 * Design notes (coding-run-isolation-design.md §6.1):
 * - **Non-suspending**: all I/O is blocking and atomic (`temp file + ATOMIC_MOVE`),
 *   so it is safe to call from a `finally` block that runs after cancellation.
 * - **Legacy detection**: a missing manifest, or one with `schema < 1`, is
 *   treated as legacy — the workspace predates this mechanism. Callers skip
 *   archive/inheritance and create a fresh manifest on task end.
 * - **Schema upgrade**: v1 manifests (single `lastTask` field, no `currentTask`)
 *   are upgraded in place on read by mapping `lastTask` directly. We do NOT
 *   rewrite the file on read (avoid surprise writes); the next explicit write
 *   brings it to schema 2.
 *
 * Uses plain `jackson-databind` (the only Jackson dep in this module) with a
 * custom [Instant] serializer (ISO-8601), because `jackson-datatype-jsr310`
 * is not on the classpath here.
 */
class WorkspaceManifestStore {

    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(SimpleModule().apply {
            addSerializer(Instant::class.java, object : JsonSerializer<Instant>() {
                override fun serialize(value: Instant, gen: JsonGenerator, serializers: SerializerProvider) =
                    gen.writeString(value.toString())
            })
        })
        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val logger = LoggerFactory.getLogger(WorkspaceManifestStore::class.java)

    /** Absolute path to the manifest for a given workspace root. */
    fun manifestPath(workspaceRoot: Path): Path =
        workspaceRoot.resolve(WorkspaceManifest.FILENAME)

    /** Read the manifest, or null if the workspace is legacy (no manifest /
     *  unreadable / pre-schema). Corrupt files are logged and yield null so
     *  callers fall back to legacy behaviour rather than crashing the task. */
    fun read(workspaceRoot: Path): WorkspaceManifest? {
        val file = manifestPath(workspaceRoot)
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            val node = mapper.readTree(file.toFile())
            val schema = node.get("schema")?.asInt(1) ?: 1
            if (schema < 1) return@runCatching null
            WorkspaceManifest(
                schema = WorkspaceManifest.SCHEMA_VERSION,
                workspaceId = node.get("workspaceId")?.asText() ?: workspaceRoot.fileName?.toString() ?: "unknown",
                agentUuid = node.get("agentUuid")?.asText() ?: "unknown",
                engine = node.get("engine")?.asText() ?: "unknown",
                sourceRepo = node.get("sourceRepo")?.asText(),
                createdAt = node.get("createdAt")?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: Instant.now(),
                state = node.get("state")?.asText()?.let { runCatching { WorkspaceState.valueOf(it.uppercase()) }.getOrNull() }
                    ?: WorkspaceState.COMPLETE,
                currentTask = node.get("currentTask")?.let { parseTask(it) },
                lastTask = node.get("lastTask")?.let { parseTask(it) },
            )
        }.onFailure { logger.warn("workspace manifest unreadable at $file, treating as legacy", it) }
            .getOrNull()
    }

    /** Parse a [TaskSnapshot] from a [JsonNode] manually. We avoid
     *  `treeToValue(TaskSnapshot::class.java)` because this module only has
     *  `jackson-databind` (no kotlin module) and data classes without a
     *  no-arg constructor can't be constructed by the default databind. */
    private fun parseTask(node: JsonNode): TaskSnapshot = TaskSnapshot(
        taskId = node.get("taskId")?.asText() ?: "",
        instruction = node.get("instruction")?.asText() ?: "",
        coding = node.get("coding")?.asBoolean(false) ?: false,
        startedAt = node.get("startedAt")?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.now(),
        status = node.get("status")?.asText()?.let { runCatching { TaskStatus.valueOf(it.uppercase()) }.getOrNull() },
        endedAt = node.get("endedAt")?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() },
        filesChanged = node.get("filesChanged")?.takeIf { it.isArray }?.mapNotNull { it.asText() } ?: emptyList(),
        mutations = node.get("mutations")?.asInt(0) ?: 0,
        baselineCaptured = node.get("baselineCaptured")?.asBoolean(false) ?: false,
    )

    /** Atomically write [manifest] to `<workspace>/.browser4/workspace.json`. */
    fun write(workspaceRoot: Path, manifest: WorkspaceManifest) {
        val file = manifestPath(workspaceRoot)
        runCatching { Files.createDirectories(file.parent) }.getOrThrow()
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), manifest)
        runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
            .getOrThrow()
    }

    /** True if the workspace has no readable manifest (legacy pre-schema area). */
    fun isLegacy(workspaceRoot: Path): Boolean = read(workspaceRoot) == null

    /** Remove the manifest (used by GC and tests). */
    fun delete(workspaceRoot: Path) {
        val file = manifestPath(workspaceRoot)
        runCatching { Files.deleteIfExists(file) }
    }
}

