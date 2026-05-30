package ai.platon.browser4.chrome.handler.util

import ai.platon.browser4.chrome.RemoteDevTools
import ai.platon.browser4.chrome.handler.RemoteChromeProtocol
import ai.platon.pulsar.browser.impl.BrowserProtocol
import ai.platon.pulsar.browser.impl.NodeRef
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

    val bp = RemoteChromeProtocol(devTools)
    val objectId = when {
        node.nodeId > 0 -> bp.resolveNodeByNodeId(node.nodeId).objectId
        node.backendNodeId > 0 -> bp.resolveNodeByBackendNodeId(node.backendNodeId).objectId
        else -> null
    }

    return objectId?.let { ResolvedNodeObjectId(it, true) }
}

suspend fun resolveNodeObjectId(bp: BrowserProtocol, node: NodeRef): ResolvedNodeObjectId? {
    val devTools = (bp as RemoteChromeProtocol).remoteDevToolsOrNull ?: return null
    return resolveNodeObjectId(devTools, node)
}

/**
 * Releases a temporary runtime object id previously returned by [resolveNodeObjectId].
 */
suspend fun releaseNodeObjectIfNeeded(devTools: RemoteDevTools, resolved: ResolvedNodeObjectId?) {
    if (resolved?.shouldRelease != true || !AppContext.isActive || !devTools.isOpen) {
        return
    }

    val bp = RemoteChromeProtocol(devTools)
    runCatching { bp.releaseObject(resolved.objectId) }
}

suspend fun releaseNodeObjectIfNeeded(bp: BrowserProtocol, resolved: ResolvedNodeObjectId?) {
    val devTools = (bp as RemoteChromeProtocol).remoteDevToolsOrNull ?: return
    releaseNodeObjectIfNeeded(devTools, resolved)
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

suspend inline fun <T> withNodeObjectId(
    bp: BrowserProtocol,
    node: NodeRef,
    block: suspend (String) -> T,
): T? {
    val devTools = (bp as RemoteChromeProtocol).remoteDevToolsOrNull ?: return null
    return withNodeObjectId(devTools, node, block)
}


