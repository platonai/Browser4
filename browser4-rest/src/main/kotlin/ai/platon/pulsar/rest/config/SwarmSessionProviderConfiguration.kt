package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.GenericAgenticSession
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmSessionProvider
import ai.platon.pulsar.rest.session.PulsarSessionManager
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Exposes the core swarm session to plugins through the agentic
 * [SwarmSessionProvider] interface, so the swarm plugin never depends on
 * REST module classes. The session itself stays core infrastructure.
 */
@Configuration
class SwarmSessionProviderConfiguration(
    private val sessionManager: PulsarSessionManager,
) {
    @Bean
    fun swarmSessionProvider(): SwarmSessionProvider {
        return SwarmSessionProvider {
            val session = sessionManager.ensureSwarmSession().agenticSession
            require(session is GenericAgenticSession) {
                "Expected GenericAgenticSession but got ${session::class.simpleName} (uuid=${session.uuid})"
            }
            session
        }
    }
}
