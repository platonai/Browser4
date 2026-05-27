package ai.platon.browser4.driver.chrome.impl

import ai.platon.browser4.driver.chrome.protocol.BrowserProtocol
import ai.platon.browser4.driver.chrome.NodeRef
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
 * Resolves a [ai.platon.browser4.driver.chrome.NodeRef] into a runtime object id.
 *
 * If the node already carries an object id, it is reused and the caller should not release it.
 * Otherwise a temporary object id is resolved via BrowserProtocol DOM APIs and must be released by the caller.
 */
suspend fun resolveNodeObjectId(browserProtocol: BrowserProtocol, node: NodeRef): ResolvedNodeObjectId? {
    node.objectId?.let { return ResolvedNodeObjectId(it, false) }

    if (!AppContext.isActive || !browserProtocol.isOpen) {
        return null
    }

    val objectId = when {
        node.nodeId > 0 -> browserProtocol.resolveNodeByNodeId(node.nodeId).objectId
        node.backendNodeId > 0 -> browserProtocol.resolveNodeByBackendNodeId(node.backendNodeId).objectId
        else -> null
    }

    return objectId?.let { ResolvedNodeObjectId(it, true) }
}

/**
 * Releases a temporary runtime object id previously returned by [resolveNodeObjectId].
 */
suspend fun releaseNodeObjectIfNeeded(browserProtocol: BrowserProtocol, resolved: ResolvedNodeObjectId?) {
    if (resolved?.shouldRelease != true || !AppContext.isActive || !browserProtocol.isOpen) {
        return
    }

    runCatching { browserProtocol.releaseObject(resolved.objectId) }
}

/**
 * Resolves a node to a runtime object id, executes [block], and releases temporary objects automatically.
 */
suspend inline fun <T> withNodeObjectId(
    browserProtocol: BrowserProtocol,
    node: NodeRef,
    block: suspend (String) -> T,
): T? {
    val resolved = resolveNodeObjectId(browserProtocol, node) ?: return null

    return try {
        block(resolved.objectId)
    } finally {
        releaseNodeObjectIfNeeded(browserProtocol, resolved)
    }
}


