package ai.platon.pulsar.agentic.tools.advanced.crawl

import ai.platon.pulsar.agentic.GenericAgenticSession

/**
 * Provides the shared swarm session to the swarm backend implementation.
 *
 * The session itself is core infrastructure (created and routed by the REST
 * session manager); exposing it behind this interface lets plugins consume it
 * without depending on any REST module class.
 */
fun interface SwarmSessionProvider {
    /**
     * The shared swarm [GenericAgenticSession], created on demand.
     */
    fun session(): GenericAgenticSession
}
