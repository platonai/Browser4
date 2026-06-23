package ai.platon.pulsar.agentic.inference

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.MultiSinkMessageWriter
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Persists inference input/output logs to disk as structured JSON files.
 *
 * Dependencies are injected through the constructor as plain data rather than
 * referencing the agent directly, keeping file I/O separate from inference logic.
 */
class InferenceLogger(
    private val agentUuid: UUID,
    private val agentStartTime: Instant,
) {
    private val auxRunLogDir: Path by lazy {
        val auxLogDir = AppPaths.detectAuxiliaryLogDir().resolve("agent")
        auxLogDir.resolve(AppPaths.fromTime(agentStartTime)).resolve(agentUuid.toString())
    }
    private val auxLogger by lazy { MultiSinkMessageWriter(auxRunLogDir) }

    fun logSummary(filename: String, payload: Map<String, Any?>): Path {
        val path = auxRunLogDir.resolve("summary").resolve(filename)
        return auxLogger.writeTo(payload, path)
    }

    fun log(subdirectory: String, filename: String, payload: Map<String, Any?>): Path {
        val path = auxRunLogDir.resolve(subdirectory).resolve(filename)
        return auxLogger.writeTo(payload, path)
    }

    fun log(
        subdirectory: String, requestId: String, filename: String,
        messages: List<Any>, enabled: Boolean = true
    ): Path? {
        if (!enabled) return null

        val payload = mapOf("requestId" to requestId, "messages" to messages)
        val path = auxRunLogDir.resolve(subdirectory).resolve(filename)
        return auxLogger.writeTo(payload, path)
    }
}
