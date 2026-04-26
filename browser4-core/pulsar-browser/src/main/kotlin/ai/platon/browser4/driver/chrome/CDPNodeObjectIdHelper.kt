package ai.platon.browser4.driver.chrome

import ai.platon.pulsar.common.AppContext

/**
 * Result of resolving a DOM node to a temporary or pre-existing runtime object id.
 *
 * @property objectId The runtime object id for the node.
 * @property shouldRelease Whether the caller should release the object after use.
 */
data class ResolvedNodeObjectId(
    val objectId: String,
    val shouldRelease: Boolean,
)

/**
 * Resolves a [NodeRef] into a runtime object id.
 *
 * If the node already carries an object id, it is reused and the caller should not release it.
 * Otherwise a temporary object id is resolved via CDP DOM APIs and must be released by the caller.
 */
suspend fun resolveNodeObjectId(devTools: RemoteDevTools, node: NodeRef): ResolvedNodeObjectId? {
    node.objectId?.let { return ResolvedNodeObjectId(it, false) }

    if (!AppContext.isActive || !devTools.isOpen) {
        return null
    }

    val domAPI = devTools.dom
    val objectId = when {
        node.nodeId > 0 -> domAPI.resolveNode(nodeId = node.nodeId).objectId
        node.backendNodeId > 0 -> domAPI.resolveNode(backendNodeId = node.backendNodeId).objectId
        else -> null
    }

    return objectId?.let { ResolvedNodeObjectId(it, true) }
}

/**
 * Releases a temporary runtime object id previously returned by [resolveNodeObjectId].
 */
suspend fun releaseNodeObjectIfNeeded(devTools: RemoteDevTools, resolved: ResolvedNodeObjectId?) {
    if (resolved?.shouldRelease != true || !AppContext.isActive || !devTools.isOpen) {
        return
    }

    runCatching { devTools.runtime.releaseObject(resolved.objectId) }
}

/**
 * Resolves a node to a runtime object id, executes [block], and releases temporary objects automatically.
 */
suspend inline fun <T> withNodeObjectId(
    devTools: RemoteDevTools,
    node: NodeRef,
    block: suspend (String) -> T,
): T? {
    val resolved = resolveNodeObjectId(devTools, node) ?: return null

    return try {
        block(resolved.objectId)
    } finally {
        releaseNodeObjectIfNeeded(devTools, resolved)
    }
}


