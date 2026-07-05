package ai.platon.browser4.chrome.dom.util

import ai.platon.browser4.api.model.MergedDOMTreeNode
import ai.platon.browser4.api.model.StaticAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility functions for computing element hashes and parent branch hashes.
 */
object HashUtils {

    // Cache for element hash calculations
    private val elementHashCache = ConcurrentHashMap<String, String>()

    // Cache for parent branch hash calculations
    private val parentBranchHashCache = ConcurrentHashMap<String, String>()

    // Configuration for hash generation modes
    data class HashConfig(
        val useBackendNodeId: Boolean = true,
        val useSessionId: Boolean = true,
        val useParentBranch: Boolean = true,
        val useStaticAttributes: Boolean = true,
        val fallbackToSimpleHash: Boolean = true
    )

    // Default configuration
    val DEFAULT_CONFIG = HashConfig()

    // Configuration for legacy compatibility (parent-branch + STATIC_ATTRIBUTES only)
    val LEGACY_CONFIG = HashConfig(
        useBackendNodeId = false,
        useSessionId = false,
        useParentBranch = true,
        useStaticAttributes = true
    )

    // Configuration for backend-node-based hashing
    val BACKEND_NODE_CONFIG = HashConfig(
        useBackendNodeId = true,
        useSessionId = true,
        useParentBranch = false,
        useStaticAttributes = false
    )

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute element hash based on parent-branch path + STATIC_ATTRIBUTES with configuration support.
     *
     * Enhanced with:
     * - Configurable hash generation modes (legacy, backend-node-based, hybrid)
     * - Caching for performance
     * - Fallback strategies for different scenarios
     *
     * @param node The DOM node
     * @param parentBranchHash The hash of the parent branch (optional)
     * @param config Hash generation configuration
     * @param sessionId Optional session ID override
     * @return SHA256 hex string
     */
    fun elementHash(
        node: MergedDOMTreeNode,
        parentBranchHash: String? = null,
        config: HashConfig = DEFAULT_CONFIG,
        sessionId: String? = null
    ): String {
        // Build cache key
        val cacheKey = buildElementHashCacheKey(node, parentBranchHash, config, sessionId)

        // Check cache first
        elementHashCache[cacheKey]?.let { return it }

        // Resolve effective session id once
        val effectiveSessionId = sessionId ?: node.sessionId

        // Detect strict backend-only mode (only backend+session identifiers should be used)
        val isBackendOnlyMode = config.useBackendNodeId && config.useSessionId &&
                !config.useParentBranch && !config.useStaticAttributes

        val parts = mutableListOf<String>()

        if (isBackendOnlyMode) {
            // Only include backend node id and session id (no tag/static/parent)
            node.backendNodeId?.let { parts.add("backend:$it") }
            if (!effectiveSessionId.isNullOrEmpty()) {
                parts.add("session:$effectiveSessionId")
            }

            // If both identifiers are absent, fall back to a minimal identifier to avoid empty input
            if (parts.isEmpty() && config.fallbackToSimpleHash) {
                val fallbackIdentifier = buildFallbackIdentifier(node)
                parts.add("fallback:$fallbackIdentifier")
            }
        } else {
            // Include parent branch hash if available and configured
            if (config.useParentBranch && !parentBranchHash.isNullOrEmpty()) {
                parts.add(parentBranchHash)
            }

            // Add tag name
            parts.add(node.nodeName.lowercase())

            // Add static attributes in sorted order if configured
            if (config.useStaticAttributes) {
                val staticAttrs = node.attributes.filterKeys { it in StaticAttributes.ATTRIBUTES }
                    .toSortedMap()
                    .map { "${it.key}=${it.value}" }
                parts.addAll(staticAttrs)
            }

            // Include backend node ID if available and configured
            if (config.useBackendNodeId) {
                node.backendNodeId?.let { parts.add("backend:$it") }
            }

            // Include session ID if available and configured
            if (config.useSessionId && !effectiveSessionId.isNullOrEmpty()) {
                parts.add("session:$effectiveSessionId")
            }

            // Fallback strategy: if no meaningful identifiers found and fallback is enabled,
            // use a combination of tag + position-based identifier
            if (parts.size <= 1 && config.fallbackToSimpleHash) {
                val fallbackIdentifier = buildFallbackIdentifier(node)
                parts.add("fallback:$fallbackIdentifier")
            }
        }

        val key = parts.joinToString("|")
        val hash = sha256Hex(key.toByteArray(Charsets.UTF_8))

        // Cache the result
        elementHashCache[cacheKey] = hash
        return hash
    }

    /**
     * Build fallback identifier for nodes without meaningful attributes.
     */
    private fun buildFallbackIdentifier(node: MergedDOMTreeNode): String {
        val parts = mutableListOf<String>()
        parts.add(node.nodeName.lowercase())

        // Add any available attributes that might help identify the element
        val helpfulAttrs = node.attributes.filterKeys { key ->
            key in setOf("class", "role", "type", "name", "placeholder", "title")
        }.toSortedMap()

        helpfulAttrs.forEach { (key, value) ->
            parts.add("$key=$value")
        }

        // Add backend node ID if available
        node.backendNodeId?.let { parts.add("backendId=$it") }

        return parts.joinToString(",")
    }

    /**
     * Build cache key for element hash calculation.
     */
    private fun buildElementHashCacheKey(
        node: MergedDOMTreeNode,
        parentBranchHash: String?,
        config: HashConfig,
        sessionId: String?
    ): String {
        // Build a stable attributes signature so that changes to attributes invalidate the cache
        val attributesSignature = buildString {
            node.attributes.toSortedMap().forEach { (k, v) ->
                append(k)
                append("=")
                append(v)
                append(";")
            }
        }
        return buildString {
            append(node.nodeId)
            append(":")
            append(node.backendNodeId ?: "null")
            append(":")
            append(node.nodeName.lowercase())
            append(":")
            append(parentBranchHash ?: "null")
            append(":")
            append(config.useBackendNodeId)
            append(":")
            append(config.useSessionId)
            append(":")
            append(config.useParentBranch)
            append(":")
            append(config.useStaticAttributes)
            append(":")
            append(sessionId ?: node.sessionId ?: "null")
            append(":")
            append(attributesSignature)
        }
    }

    /**
     * Compute parent branch hash for a node based on its position in the tree.
     * Enhanced with caching and improved path calculation.
     * This is used to build unique paths through the DOM tree.
     *
     * @param ancestors List of ancestor nodes from root to immediate parent
     * @return SHA256 hex string representing the path to this node
     */
    fun parentBranchHash(ancestors: List<MergedDOMTreeNode>): String {
        // Build cache key
        val cacheKey = buildParentBranchHashCacheKey(ancestors)

        // Check cache first
        parentBranchHashCache[cacheKey]?.let { return it }

        val parts = ancestors.map { node ->
            val tag = node.nodeName.lowercase()
            val id = node.attributes["id"]
            val classes = node.attributes["class"]
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.sorted()
                ?.joinToString(".")
                ?: ""

            // Enhanced path segment with additional context for shadow DOM and iframes
            buildString {
                append(tag)
                if (!id.isNullOrEmpty()) append("#$id")
                if (classes.isNotEmpty()) append(".$classes")

                // Add special markers for shadow hosts and iframes
                when {
                    node.shadowRoots.isNotEmpty() -> append("[shadow-host]")
                    tag == "iframe" || tag == "frame" -> append("[iframe]")
                    tag == "slot" -> {
                        val slotName = node.attributes["name"]
                        append("[slot${slotName?.let { "=$it" } ?: ""}]")
                    }
                }
            }
        }

        val key = parts.joinToString("/")
        val hash = sha256Hex(key.toByteArray(Charsets.UTF_8))

        // Cache the result
        parentBranchHashCache[cacheKey] = hash
        return hash
    }

    /**
     * Build cache key for parent branch hash calculation.
     */
    private fun buildParentBranchHashCacheKey(ancestors: List<MergedDOMTreeNode>): String {
        return buildString {
            ancestors.forEach { ancestor ->
                append(ancestor.nodeId)
                append(":")
                append(ancestor.nodeName)
                append(":")
                ancestor.attributes["id"]?.let { append(it) }
                append("|")
            }
        }
    }

    /**
     * Simple element hash for quick lookups (without parent branch).
     * Useful for backward compatibility with existing code.
     */
    fun simpleElementHash(node: MergedDOMTreeNode): String {
        return elementHash(node, parentBranchHash = null)
    }
}
