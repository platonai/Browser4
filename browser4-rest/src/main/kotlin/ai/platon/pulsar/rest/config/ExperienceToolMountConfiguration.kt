package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.agentic.tools.experience.ExperienceToolExecutor
import ai.platon.pulsar.agentic.tools.experience.KnowledgeStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy

/**
 * Registers [ExperienceToolExecutor] via [ToolMount] so it is discovered by
 * [ai.platon.browser4.boot.plugin.PluginManager] and made available to
 * both the MCP dispatcher and the LLM agent tool system.
 *
 * Enabled by default; opt out with `browser4.experience.enabled=false`.
 */
@Configuration
@ConditionalOnProperty(
    name = ["browser4.experience.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@Lazy
open class ExperienceToolMountConfiguration : ToolMount {

    @Bean
    open fun knowledgeStore(): KnowledgeStore {
        val store = KnowledgeStore()
        store.initializeStore()
        return store
    }

    @Bean
    open fun experienceToolExecutor(knowledgeStore: KnowledgeStore): ExperienceToolExecutor {
        return ExperienceToolExecutor(knowledgeStore)
    }

    override fun getToolExecutors(): List<ToolExecutor> = listOf(experienceToolExecutor(knowledgeStore()))
}
