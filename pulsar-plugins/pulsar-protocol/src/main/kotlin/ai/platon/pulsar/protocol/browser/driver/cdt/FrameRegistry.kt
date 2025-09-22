package ai.platon.pulsar.protocol.browser.driver.cdt

import ai.platon.pulsar.common.getLogger
import com.github.kklisura.cdt.protocol.v2023.types.page.Frame as CDPFrame
import com.github.kklisura.cdt.protocol.v2023.types.page.FrameTree
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for managing iframe contexts and providing frame switching capabilities.
 * This class maintains the state of all available frames and handles frame lifecycle events.
 */
class FrameRegistry {
    private val logger = getLogger(this)

    private val frameContexts = ConcurrentHashMap<String, IframeContext>()
    private val frameInfos = ConcurrentHashMap<String, FrameInfo>()
    private val contextStack: MutableList<String> = mutableListOf()
    private val mutex = Mutex()

    // Current frame context - null means main frame
    private var currentFrameId: String? = null

    /**
     * Get the current frame context
     */
    fun getCurrentFrameContext(): IframeContext? = currentFrameId?.let { frameContexts[it] }

    /**
     * Get the current frame ID (null for main frame)
     */
    fun getCurrentFrameId(): String? = currentFrameId

    /**
     * Check if currently in an iframe
     */
    fun isInFrame(): Boolean = currentFrameId != null

    /**
     * Get all available frame contexts
     */
    fun getAllFrameContexts(): List<IframeContext> = frameContexts.values.toList()

    /**
     * Get all available frame information
     */
    fun getAllFrameInfos(): List<FrameInfo> = frameInfos.values.toList()

    /**
     * Get frame context by ID
     */
    fun getFrameContext(frameId: String): IframeContext? = frameContexts[frameId]

    /**
     * Get frame information by ID
     */
    fun getFrameInfo(frameId: String): FrameInfo? = frameInfos[frameId]

    /**
     * Register a new frame from CDP FrameTree
     */
    suspend fun registerFrameTree(frameTree: FrameTree): Unit = mutex.withLock {
        registerFrame(frameTree.frame, null)
        val childFrames: List<FrameTree>? = frameTree.childFrames
        childFrames?.forEach { childFrame: FrameTree ->
            registerFrameTree(childFrame)
        }
    }

    /**
     * Register a new frame from CDP Frame
     */
    suspend fun registerFrame(frame: CDPFrame, parentFrameId: String? = null) = mutex.withLock {
        val context = IframeContext(
            frameId = frame.id,
            parentFrameId = parentFrameId ?: frame.parentId,
            url = frame.url ?: "",
            name = frame.name,
            securityOrigin = frame.securityOrigin,
            mimeType = frame.mimeType,
            isSecureContext = frame.secureContextType?.equals("Secure") == true,
            isCrossOriginIsolated = frame.crossOriginIsolatedContextType?.equals("Isolated") == true
        )

        frameContexts[frame.id] = context

        val frameInfo = FrameInfo(
            context = context,
            isAccessible = !frame.unreachableUrl.isNullOrEmpty(),
            childFrameIds = mutableListOf(),
            loadState = if (frame.unreachableUrl.isNullOrEmpty()) FrameLoadState.LOADED else FrameLoadState.UNREACHABLE
        )

        frameInfos[frame.id] = frameInfo

        // Update parent frame's child list
        parentFrameId?.let { parentId ->
            frameInfos[parentId]?.let { parentInfo ->
                val updatedChildren = parentInfo.childFrameIds.toMutableList()
                if (!updatedChildren.contains(frame.id)) {
                    updatedChildren.add(frame.id)
                }
                frameInfos[parentId] = parentInfo.copy(childFrameIds = updatedChildren)
            }
        }

        logger.debug("Registered frame: {} (parent: {})", frame.id, parentFrameId)
    }

    /**
     * Unregister a frame (when it's detached)
     */
    suspend fun unregisterFrame(frameId: String) = mutex.withLock {
        frameContexts.remove(frameId)
        frameInfos.remove(frameId)

        // Remove from context stack if present
        contextStack.remove(frameId)

        // Update parent frame's child list
        getFrameContext(frameId)?.parentFrameId?.let { parentId ->
            frameInfos[parentId]?.let { parentInfo ->
                val updatedChildren = parentInfo.childFrameIds.toMutableList()
                updatedChildren.remove(frameId)
                frameInfos[parentId] = parentInfo.copy(childFrameIds = updatedChildren)
            }
        }

        // If this was the current frame, switch to parent or main frame
        if (currentFrameId == frameId) {
            currentFrameId = getFrameContext(frameId)?.parentFrameId
        }

        logger.debug("Unregistered frame: {}", frameId)
    }

    /**
     * Update frame information (e.g., after navigation)
     */
    suspend fun updateFrame(frameId: String, url: String, name: String? = null) = mutex.withLock {
        frameContexts[frameId]?.let { context ->
            val updatedContext = context.copy(
                url = url,
                name = name ?: context.name,
                lastAccessedAt = java.time.Instant.now()
            )
            frameContexts[frameId] = updatedContext

            frameInfos[frameId]?.let { info ->
                frameInfos[frameId] = info.copy(context = updatedContext)
            }

            logger.debug("Updated frame {}: url={}, name={}", frameId, url, name)
        }
    }

    /**
     * Switch to a specific frame
     */
    suspend fun switchToFrame(frameId: String): Boolean = mutex.withLock {
        if (!frameContexts.containsKey(frameId)) {
            logger.warn("Attempted to switch to non-existent frame: {}", frameId)
            return false
        }

        // Push current frame to stack if we're not already in it
        if (currentFrameId != frameId) {
            currentFrameId?.let { contextStack.add(it) }
            currentFrameId = frameId
            frameContexts[frameId]?.touch()
            logger.debug("Switched to frame: {}", frameId)
        }

        return true
    }

    /**
     * Switch to parent frame
     */
    suspend fun switchToParentFrame(): Boolean = mutex.withLock {
        val currentContext = getCurrentFrameContext()
        val parentId = currentContext?.parentFrameId

        return if (parentId != null && frameContexts.containsKey(parentId)) {
            currentFrameId = parentId
            frameContexts[parentId]?.touch()
            logger.debug("Switched to parent frame: {}", parentId)
            true
        } else {
            // Switch to main frame if no parent
            switchToDefaultContent()
            true
        }
    }

    /**
     * Switch to default content (main frame)
     */
    suspend fun switchToDefaultContent(): Boolean = mutex.withLock {
        currentFrameId = null
        contextStack.clear()
        logger.debug("Switched to default content (main frame)")
        return true
    }

    /**
     * Find frame by name or ID
     */
    fun findFrameByName(name: String): IframeContext? {
        return frameContexts.values.find {
            it.name == name || it.frameId == name
        }
    }

    /**
     * Find frame by index (depth-first traversal)
     */
    fun findFrameByIndex(index: Int): IframeContext? {
        // Make ordering deterministic by creation time to ensure stable test behavior
        val frames = getAllFrameContexts().sortedBy { it.createdAt }
        return if (index >= 0 && index < frames.size) frames[index] else null
    }

    /**
     * Get frame hierarchy as a tree structure
     */
    fun getFrameHierarchy(): FrameHierarchy {
        val mainFrame = frameContexts.values.find { it.isMainFrame() }
        return mainFrame?.let { buildFrameTree(it) } ?: FrameHierarchy()
    }

    private fun buildFrameTree(context: IframeContext): FrameHierarchy {
        val children = frameContexts.values
            .filter { it.parentFrameId == context.frameId }
            .sortedBy { it.createdAt }
            .map { buildFrameTree(it) }

        return FrameHierarchy(
            context = context,
            children = children,
            info = frameInfos[context.frameId]
        )
    }

    /**
     * Clear all frame data (for page navigation)
     */
    suspend fun clearAll() = mutex.withLock {
        frameContexts.clear()
        frameInfos.clear()
        contextStack.clear()
        currentFrameId = null
        logger.debug("Cleared all frame data")
    }

    /**
     * Validate that a frame still exists and is accessible
     */
    fun validateFrame(frameId: String): Boolean {
        val context = frameContexts[frameId]
        val info = frameInfos[frameId]

        return context != null &&
               info != null &&
               info.isAccessible &&
               !context.isStale()
    }
}

/**
 * Frame hierarchy tree structure
 */
data class FrameHierarchy(
    val context: IframeContext? = null,
    val children: List<FrameHierarchy> = emptyList(),
    val info: FrameInfo? = null
) {
    /**
     * Get all contexts in this hierarchy (depth-first)
     */
    fun getAllContexts(): List<IframeContext> {
        val contexts = mutableListOf<IframeContext>()
        context?.let { contexts.add(it) }
        children.forEach { child -> contexts.addAll(child.getAllContexts()) }
        return contexts
    }

    /**
     * Find context by ID in this hierarchy
     */
    fun findContext(frameId: String): IframeContext? {
        if (context?.frameId == frameId) return context
        return children.asSequence()
            .mapNotNull { it.findContext(frameId) }
            .firstOrNull()
    }
}