package ai.platon.pulsar.protocol.browser.driver.cdt

import java.time.Instant

/**
 * Represents an iframe context for frame switching and management.
 * This class encapsulates all state and metadata related to a specific iframe.
 */
data class IframeContext(
    val frameId: String,
    val parentFrameId: String? = null,
    val url: String = "",
    val name: String? = null,
    val securityOrigin: String? = null,
    val mimeType: String? = null,
    val isSecureContext: Boolean = false,
    val isCrossOriginIsolated: Boolean = false,
    val executionContextId: Int? = null,
    val createdAt: Instant = Instant.now(),
    var lastAccessedAt: Instant = Instant.now()
) {
    /**
     * Check if this iframe is a child of the specified frame
     */
    fun isChildOf(parentId: String): Boolean = parentFrameId == parentId

    /**
     * Check if this iframe is the main frame (no parent)
     */
    fun isMainFrame(): Boolean = parentFrameId == null

    /**
     * Check if this iframe is cross-origin relative to its parent
     */
    fun isCrossOrigin(): Boolean = securityOrigin?.isNotBlank() == true && parentFrameId != null

    /**
     * Update last accessed timestamp
     */
    fun touch() {
        lastAccessedAt = Instant.now()
    }

    /**
     * Check if this context is stale (older than specified duration)
     */
    fun isStale(maxAgeMillis: Long = 300_000): Boolean =
        java.time.Duration.between(lastAccessedAt, Instant.now()).toMillis() > maxAgeMillis
}

/**
 * Frame navigation event data
 */
data class FrameNavigationEvent(
    val frameId: String,
    val url: String,
    val name: String? = null,
    val parentFrameId: String? = null,
    val navigationType: NavigationType,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Types of frame navigation events
 */
enum class NavigationType {
    NAVIGATE,
    RELOAD,
    BACK,
    FORWARD,
    ATTACH,
    DETACH
}

/**
 * Enhanced Frame information with additional metadata
 */
data class FrameInfo(
    val context: IframeContext,
    val isAccessible: Boolean = true,
    val childFrameIds: List<String> = emptyList(),
    val loadState: FrameLoadState = FrameLoadState.LOADING
)

/**
 * Frame loading states
 */
enum class FrameLoadState {
    LOADING,
    LOADED,
    FAILED,
    UNREACHABLE
}