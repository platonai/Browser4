package ai.platon.pulsar.swarm.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacade
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmFacadeMount
import ai.platon.pulsar.agentic.tools.advanced.crawl.SwarmSessionProvider
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.swarm.service.SwarmService
import ai.platon.pulsar.swarm.tools.SwarmToolExecutor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment

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

    private val logger = LoggerFactory.getLogger(SwarmAutoConfiguration::class.java)

    @Bean(name = ["swarmService"])
    @ConditionalOnMissingBean(name = ["swarmService"])
    open fun swarmService(
        sessionProvider: SwarmSessionProvider,
        environment: Environment,
    ): SwarmService {
        val service = SwarmService(sessionProvider)
        // `swarm.md` documents these two knobs, so they must be reachable from
        // configuration instead of living only as hardcoded fields: when the
        // first fetch of a run pays a multi-minute lazy storage initialization,
        // the default 120 s task timeout reaps tasks that are making progress.
        environment.getProperty("swarm.task-stale-timeout-seconds", Long::class.java)
            ?.let { service.staleTaskTimeoutSeconds = it }
        environment.getProperty("swarm.queue-stall-timeout-seconds", Long::class.java)
            ?.let { service.queueStallTimeoutSeconds = it }
        return service
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
            // Never swallow: a bean-creation or cast failure here means every
            // swarm tool call will fail later with a misleading "Swarm not
            // installed", with nothing in the log to explain why.
            logger.error(
                "Failed to mount the swarm tool executor; swarm tools will be unavailable. " +
                    "The plugin jar is present but its beans could not be created",
                e
            )
            emptyList()
        }
    }

    override fun getSwarmFacade(): SwarmFacade? {
        return try {
            applicationContext.getBean("swarmService") as SwarmFacade
        } catch (e: Exception) {
            logger.error(
                "Failed to mount the swarm facade; swarm REST calls will report 'Swarm not installed'. " +
                    "The plugin jar is present but its beans could not be created",
                e
            )
            null
        }
    }
}
