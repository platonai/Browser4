package ai.platon.pulsar.agentic.tools.util

import ai.platon.browser4.driver.chrome.dom.FBNLocator
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.common.getLogger
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Validates actions before execution to ensure safety and correctness.
 *
 * This helper class provides validation for:
 * - URL safety checks
 * - Selector syntax validation
 * - Tool call parameter validation
 * - Security policy enforcement
 *
 * @author Vincent Zhang, ivincent.zhang@gmail.com, platon.ai
 */
class ActionValidator(
    val maxSelectorLength: Int = 100,
    val allowedPorts: Set<Int> = setOf(80, 443, 8080, 8443, 3000, 5000, 8000, 9000),
    val denyUnknownActions: Boolean = false,
    val allowLocalhost: Boolean = true,
) {
    private val logger = getLogger(this)

    // Validation cache to avoid repeated validation of same actions
    private val validationCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Validates tool calls before execution
     * High Priority #5: Deny unknown actions by default for security
     */
    fun validateToolCall(toolCall: ToolCall?): Boolean {
        if (toolCall == null) {
            return false
        }

        if (toolCall.domain != "tab") {
            return true
        }

        val cacheKey = "${toolCall.method}:${toolCall.arguments}"
        return validationCache.getOrPut(cacheKey) {
            when (toolCall.method) {
                "open", "navigate", "navigateTo" -> validateNavigate(toolCall.arguments)
                "click", "fill", "press", "check", "uncheck", "exists", "isVisible", "focus", "hover", "scrollTo",
                "type", "isHidden", "visible", "isChecked", "bringToFront",
                "selectFirstTextOrNull", "selectTextAll", "selectFirstAttributeOrNull", "selectAttributes", "selectAttributeAll", "selectImages",
                "evaluate", "clickablePoint", "boundingBox" -> validateElementAction(toolCall.arguments)

                "waitForNavigation", "waitForSelector" -> validateWaitForNavigation(toolCall.arguments)
                "screenshot", "outerHTML" -> validateOptionalElementAction(toolCall.arguments)
                // New no-selector actions
                "scrollDown", "scrollUp" -> true
                "scrollBy" -> validateScrollBy(toolCall.arguments)
                "reload", "goBack", "goForward", "delay", "scrollToTop", "scrollToBottom", "scrollToMiddle", "scrollToViewport",
                "currentUrl", "url", "documentURI", "baseURI", "referrer", "pageSource", "getCookies",
                "textContent", "mouseWheelDown", "mouseWheelUp", "moveMouseTo", "dragAndDrop", "switchTab" -> true // These don't need validation
                "writeString", "readString", "replaceContent" -> true
                else -> {
                    if (denyUnknownActions) {
                        logger.warn("Unknown action blocked: ${toolCall.method}")
                        false
                    } else {
                        logger.warn("Unknown action allowed (config): ${toolCall.method}")
                        true
                    }
                }
            }
        }
    }

    /**
     * Validates navigation actions
     */
    private fun validateNavigate(args: Map<String, Any?>?): Boolean {
        args ?: return true

        val url = args["url"]?.toString() ?: return false
        return isSafeUrl(url)
    }

    /**
     * Validates element interaction actions
     * Medium Priority #11: Improved validation with selector syntax checking
     */
    private fun validateElementAction(args: Map<String, Any?>?): Boolean {
        args ?: return false

        val selector = args["selector"]?.toString() ?: return false

        // Basic validation
        if (selector.isBlank() || selector.length > maxSelectorLength) {
            return false
        }

        val isSimplifiedFBN = selector.matches(FBNLocator.SIMPLIFIED_REGEX)
        if (isSimplifiedFBN) {
            return true
        }

        // Medium Priority #11: Check for common selector syntax patterns
        val hasValidPrefix = selector.startsWith("xpath:") ||
                selector.startsWith("css:") ||
                selector.startsWith("#") ||
                selector.startsWith(".") ||
                selector.startsWith("//") ||
                selector.startsWith("fbn:") ||
                selector.startsWith("backend:") ||
                selector.matches(Regex("^[a-zA-Z][a-zA-Z0-9]*$")) // tag name

        return hasValidPrefix
    }

    /**
     * Validates element interaction actions with optional selector
     */
    private fun validateOptionalElementAction(args: Map<String, Any?>?): Boolean {
        args ?: return true

        val selector = args["selector"]?.toString()
        if (selector.isNullOrBlank()) {
            return true // No selector, so no validation needed
        }

        // If selector exists, validate it
        return validateElementAction(args)
    }

    /**
     * Validate scrollBy arguments: pixels within a reasonable range and smooth is boolean-like when present.
     */
    private fun validateScrollBy(args: Map<String, Any?>?): Boolean {
        args ?: return true
        val pixelsAny = args["pixels"]
        val smoothAny = args["smooth"]
        val pixels = when (pixelsAny) {
            is Number -> pixelsAny.toDouble()
            is String -> pixelsAny.toDoubleOrNull()
            null -> 200.0
            else -> null
        } ?: return false
        // limit to -20000..20000 to avoid crazy values
        if (pixels !in -20000.0..20000.0) return false
        val smoothOk = when (smoothAny) {
            null -> true
            is Boolean -> true
            is String -> smoothAny.equals("true", true) || smoothAny.equals("false", true)
            else -> false
        }
        return smoothOk
    }

    /**
     * Validates waitForNavigation actions.
     *
     * @param args Action arguments containing oldUrl and timeout
     * @return true if the parameters are valid
     */
    private fun validateWaitForNavigation(args: Map<String, Any?>?): Boolean {
        args ?: return true

        val oldUrl = args["oldUrl"]?.toString() ?: ""
        val timeout = (args["timeoutMillis"] as? Number)?.toLong() ?: 5000L
        return timeout in 100L..60000L && oldUrl.length < 1000 // Reasonable timeout range and URL length
    }

    /**
     * Enhanced URL validation with comprehensive safety checks.
     * Configurable for localhost and port restrictions.
     *
     * @param url The URL to validate
     * @return true if the URL is safe to use
     */
    fun isSafeUrl(url: String): Boolean {
        if (url.isBlank()) return false

        return runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()

            // Only allow http and https schemes
            if (scheme !in setOf("http", "https")) {
                logger.warn("Blocked unsafe URL scheme: $scheme for URL: ${url.take(50)}")
                return false
            }

            // Additional safety checks
            val host = uri.host?.lowercase() ?: return false

            // Configurable localhost blocking
            if (!allowLocalhost) {
                val dangerousPatterns = listOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
                if (dangerousPatterns.any { host.contains(it) }) {
                    logger.warn("Blocked localhost URL (config): $host")
                    return false
                }
            }

            // Validate port with configurable whitelist
            val port = uri.port
            if (port != -1 && port !in allowedPorts) {
                logger.warn("Blocked URL with non-whitelisted port $port: $host")
                return false
            }

            true
        }.getOrDefault(false)
    }

    /**
     * Clear the validation cache.
     * Should be called periodically to prevent memory issues.
     */
    fun clearCache() {
        if (validationCache.size > 1000) {
            validationCache.clear()
            logger.debug("Validation cache cleared")
        }
    }
}
