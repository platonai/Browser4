package ai.platon.pulsar.swarm.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacade
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacadeMount
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmSessionProvider
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.swarm.service.SwarmService
import ai.platon.pulsar.swarm.tools.SwarmToolExecutor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy

/**
 * Auto-configuration for the browser4-swarm plugin.
 *
 * Creates the [SwarmService] task backend and [SwarmToolExecutor], then mounts
 * them through [SwarmFacadeMount] (REST facade) and [ToolMount] (MCP/LLM tools)
 * so `PluginManager` registers them in the core registries.
 *
 * Disable with `swarm.enabled=false`.
 */
@AutoConfiguration
@ConditionalOnProperty(name = ["swarm.enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(SwarmSessionProvider::class)
@Lazy
open class SwarmAutoConfiguration(
    private val applicationContext: ApplicationContext,
) : ToolMount, SwarmFacadeMount {

    @Bean(name = ["swarmService"])
    @ConditionalOnMissingBean(name = ["swarmService"])
    open fun swarmService(sessionProvider: SwarmSessionProvider): SwarmService {
        return SwarmService(sessionProvider)
    }

    @Bean(name = ["swarmToolExecutor"])
    @ConditionalOnMissingBean(name = ["swarmToolExecutor"])
    open fun swarmToolExecutor(swarmService: SwarmService): SwarmToolExecutor {
        return SwarmToolExecutor(swarmService)
    }

    override fun getToolExecutors(): List<ToolExecutor> {
        return try {
            listOf(applicationContext.getBean("swarmToolExecutor") as ToolExecutor)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun getSwarmFacade(): SwarmFacade? {
        return try {
            applicationContext.getBean("swarmService") as SwarmFacade
        } catch (e: Exception) {
            null
        }
    }
}
