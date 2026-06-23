package ai.platon.pulsar.rest.mcp.controller.handler

// Shared batch DTOs

data class BatchMousePosition(
    val x: Double,
    val y: Double,
)

data class BatchExecutionResult(
    val index: Int,
    val ok: Boolean,
    val durationMillis: Long = 0,
    val sessionId: String? = null,
    val text: String? = null,
    val error: String? = null,
    val pageUrl: String? = null,
    val pageTitle: String? = null,
    val snapshot: String? = null,
    val screenshot: String? = null,
)

data class BatchExecutionResponse(
    val sessionId: String?,
    val failureCount: Int,
    val stoppedOnError: Boolean,
    val results: List<BatchExecutionResult>,
)

// Extension helpers used across handlers

internal fun Any?.toAnyMap(): Map<String, Any?>? {
    if (this !is Map<*, *>) {
        return null
    }
    return this.entries.associate { (key, value) -> key.toString() to value }
}

internal fun Any?.toBatchMousePosition(): BatchMousePosition? {
    val map = this.toAnyMap() ?: return null
    val x = (map["x"] as? Number)?.toDouble() ?: return null
    val y = (map["y"] as? Number)?.toDouble() ?: return null
    return BatchMousePosition(x, y)
}

internal fun Any?.toBooleanValue(): Boolean? = when (this) {
    is Boolean -> this
    is String -> this.toBooleanStrictOrNull()
    else -> null
}
