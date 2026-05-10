package ai.platon.browser4.driver.chrome

import ai.platon.browser4.driver.chrome.experimental.CDP
import ai.platon.browser4.driver.common.BrowserSettings
import ai.platon.pulsar.common.getLogger
import org.apache.commons.lang3.StringUtils

/**
 * Manages isolated worlds for Browser4 runtime injection.
 *
 * The IsolatedWorldManager implements the dual-world architecture:
 * - Page World: Contains minimal stealth patches and fingerprint patches
 * - Isolated World: Contains the full Browser4 runtime that is invisible to page JavaScript
 *
 * Design Goals:
 * 1. Page JS cannot directly access Agent Runtime
 * 2. Page JS cannot reliably detect Agent Runtime
 * 3. CDP / Agent can stably access Runtime
 * 4. Runtime is versionable and hot-updatable
 * 5. Runtime doesn't break page behavior
 * 6. Runtime is observable, extensible, and evolvable
 */
class IsolatedWorldManager(
    val devTools: RemoteDevTools,
    val settings: BrowserSettings
) {
    companion object {
        /**
         * Isolated world name for Browser4 runtime.
         * This name is used to identify and access the isolated world via CDP.
         */
        const val RUNTIME_WORLD_NAME = "__browser4_runtime__"

        /**
         * Version of the Browser4 runtime.
         * Increment this when the runtime API changes.
         */
        const val RUNTIME_VERSION = "1.0.0"

        private const val DEFAULT_CREATE_WORLD_RETRIES = 3
    }

    private val logger = getLogger(this)
    private val cdp = CDP(devTools)
    private val confuser get() = settings.confuser

    /**
     * Stores the frame ID to isolated world context ID mapping.
     * Key: frameId, Value: executionContextId
     */
    private val isolatedWorldContexts = mutableMapOf<String, Int>()

    /**
     * Creates an isolated world for the given frame.
     *
     * @param frameId The frame ID to create the isolated world in.
     *                If null, creates in the main frame.
     * @return The execution context ID of the created isolated world
     */
    suspend fun createIsolatedWorld(frameId: String? = null): Int {
        val resolvedFrameId: String? = frameId ?: runCatching { cdp.mainFrame().id }.getOrNull()

        logger.debug(
            "Creating isolated world '{}' for frame: {}",
            RUNTIME_WORLD_NAME,
            resolvedFrameId ?: "main"
        )

        var lastError: Exception? = null
        repeat(DEFAULT_CREATE_WORLD_RETRIES) { attempt ->
            try {
                val executionContextId = cdp.createIsolatedWorld(
                    frameId = resolvedFrameId ?: "main",
                    worldName = RUNTIME_WORLD_NAME,
                    grantUniveralAccess = true,
                )

                if (executionContextId <= 0) {
                    throw IllegalStateException(
                        "Failed to create isolated world: invalid executionContextId '$executionContextId' (frameId=$resolvedFrameId, attempt=${attempt + 1})"
                    )
                }

                // Always store mapping for the resolved main frame.
                // Use empty string as fallback key so callers without a frame id can still resolve it.
                val key = resolvedFrameId?.ifBlank { null } ?: ""
                isolatedWorldContexts[key] = executionContextId
                // Also store a default fallback mapping in case frameId cannot be resolved later.
                isolatedWorldContexts[""] = executionContextId

                logger.debug("Created isolated world with execution context ID: {}", executionContextId)
                return executionContextId
            } catch (e: Exception) {
                lastError = e
                if (attempt > 1) {
                    logger.warn("Failed to create isolated world | frameId=$resolvedFrameId, attempt=${attempt + 1}", e)
                } else {
                    logger.warn("Failed to create isolated world | frameId=$resolvedFrameId, attempt=${attempt + 1}: {}", e.message)
                }
            }
        }

        throw IllegalStateException(
            "Failed to create isolated world after $DEFAULT_CREATE_WORLD_RETRIES attempts (frameId=$resolvedFrameId)",
            lastError
        )
    }

    /**
     * Evaluates JavaScript in the isolated world.
     *
     * @param script The JavaScript code to evaluate
     * @param contextId The execution context ID of the isolated world.
     *                  If null, evaluates in the default isolated world.
     * @return The result of the evaluation
     */
    suspend fun evaluateInIsolatedWorld(script: String, contextId: Int? = null): Any? {
        val result = cdp.evaluate(
            expression = confuser.confuse(script),
            contextId = contextId,
            returnByValue = true,
            awaitPromise = true,
        )

        val exception = result.exceptionDetails
        if (exception != null) {
            val abbreviation = StringUtils.abbreviateMiddle(script, "...", 200)
            logger.warn("Exception during isolated world evaluation: {} \n>>>$abbreviation<<<", exception)
        }

        return result.result
    }

    /**
     * Injects the Browser4 runtime into the isolated world.
     *
     * The Isolated World and the Page World share the same DOM (so you can manipulate the page, click buttons, etc.),
     * However, the JavaScript global objects on both sides are isolated (each has its own window/ globalThis/ prototype chain),
     * So when you execute window.__pulsar_utils__ = ...in the Isolated World, it is, by default, attached only to the window of the Isolated World,
     * The page's own scripts (Page World) access the window of the Page World and cannot see the global variables of the Isolated World.
     *
     * @param runtimeScript The compiled runtime JavaScript code
     * @param contextId The execution context ID of the isolated world
     */
    suspend fun injectRuntime(runtimeScript: String, contextId: Int) {
        logger.info(
            "Injecting Browser4 runtime (v{}) into isolated world context {}",
            RUNTIME_VERSION,
            contextId
        )

        // Wrap the runtime with version info
        // $runtimeScript includes js code in __pulsar_utils__.js and other runtime files
        val versionedScript = """
            // Browser4 Runtime v$RUNTIME_VERSION
            (function() {
                'use strict';

                const runtimeMetadata = {
                    version: '$RUNTIME_VERSION',
                    worldName: '$RUNTIME_WORLD_NAME',
                };

                const existingRuntime = (typeof window !== 'undefined' && window.__browser4_runtime__) || null;
                const __browser4_runtime__ = existingRuntime ? Object.assign(existingRuntime, runtimeMetadata) : runtimeMetadata;

                if (typeof window !== 'undefined' && !existingRuntime) {
                    Object.defineProperty(window, '__browser4_runtime__', {
                        value: __browser4_runtime__,
                        writable: false,
                        enumerable: false,
                        configurable: false
                    });
                }


                $runtimeScript


                return __browser4_runtime__;
            })();
        """.trimIndent()

        evaluateInIsolatedWorld(versionedScript, contextId)

        logger.debug("Browser4 runtime injection completed")
    }

    /**
     * Ensures an isolated world exists for the target frame and injects runtime if absent.
     */
    suspend fun ensureRuntime(frameId: String? = null, runtimeScript: String): Int {
        val existing = getContextId(frameId)
        if (existing != null && existing > 0) return existing

        val ctx = createIsolatedWorld(frameId)
        injectRuntime(runtimeScript, ctx)
        return ctx
    }

    /**
     * Gets the execution context ID for a frame's isolated world.
     *
     * @param frameId The frame ID
     * @return The execution context ID, or null if not found
     */
    fun getContextId(frameId: String?): Int? {
        val key = frameId?.ifBlank { null } ?: ""
        return isolatedWorldContexts[key] ?: isolatedWorldContexts[""]
    }

    /**
     * Clears all isolated world contexts.
     * Called when navigating to a new page or closing the tab.
     */
    fun clearContexts() {
        isolatedWorldContexts.clear()
        logger.debug("Cleared all isolated world contexts")
    }
}
