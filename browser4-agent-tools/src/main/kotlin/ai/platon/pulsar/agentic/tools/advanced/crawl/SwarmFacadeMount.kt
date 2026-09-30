package ai.platon.pulsar.agentic.tools.advanced.crawl

import ai.platon.pulsar.skeleton.plugin.PluginMount

/**
 * Mount point for the swarm task backend.
 *
 * Plugins that implement the swarm backend return their [SwarmFacade] here;
 * `PluginManager` discovers all [SwarmFacadeMount] beans and registers the
 * facade in [SwarmFacadeRegistry], where the thin REST controller picks it up.
 *
 * ## Example
 *
 * ```kotlin
 * @AutoConfiguration
 * class SwarmAutoConfiguration : SwarmFacadeMount {
 *     override fun getSwarmFacade(): SwarmFacade? = swarmService()
 * }
 * ```
 */
interface SwarmFacadeMount : PluginMount {
    /**
     * The swarm facade to register, or null if this mount is not active.
     */
    fun getSwarmFacade(): SwarmFacade? = null
}
