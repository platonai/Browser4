package ai.platon.pulsar.agentic.tools.advanced.crawl

import ai.platon.pulsar.common.getLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * Registry for the [SwarmFacade] implementation, mirroring the ToolMount →
 * [ai.platon.pulsar.agentic.tools.CustomToolRegistry] pattern.
 *
 * `PluginManager` discovers [SwarmFacadeMount] beans and registers their
 * facade here; the thin REST controller reads it on every request. A
 * registered facade can be replaced at runtime (e.g. plugin re-install),
 * which keeps the singleton decoupled from Spring bean lifecycle.
 */
class SwarmFacadeRegistry private constructor() {

    private val logger = getLogger(this)
    private val facadeRef = AtomicReference<SwarmFacade>()

    /**
     * Register (or replace) the active swarm facade.
     */
    fun register(facade: SwarmFacade) {
        val previous = facadeRef.getAndSet(facade)
        if (previous != null && previous !== facade) {
            logger.info(
                "Replaced swarm facade: {} -> {}",
                previous.javaClass.simpleName, facade.javaClass.simpleName
            )
        } else {
            logger.info("Registered swarm facade: {}", facade.javaClass.simpleName)
        }
    }

    /**
     * Clear the active swarm facade (used by tests and plugin shutdown).
     */
    fun unregister() {
        val previous = facadeRef.getAndSet(null)
        if (previous != null) {
            logger.info("Unregistered swarm facade: {}", previous.javaClass.simpleName)
        }
    }

    /**
     * The active facade, or null when no swarm plugin is installed.
     */
    fun get(): SwarmFacade? = facadeRef.get()

    companion object {
        /**
         * Singleton instance of the registry.
         */
        val instance: SwarmFacadeRegistry by lazy { SwarmFacadeRegistry() }
    }
}
