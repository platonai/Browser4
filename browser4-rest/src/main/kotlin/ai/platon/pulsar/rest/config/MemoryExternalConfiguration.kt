package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.memory.external.ExternalMemoryConfig
import ai.platon.pulsar.agentic.memory.external.MemoryExternalBridge
import ai.platon.pulsar.agentic.memory.external.MemoryExternalToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy

/**
 * MCP-facing registration of the L2 external memory bridge (design M4).
 *
 * When `browser4.agent.memory.external.enabled=true`, connects to the
 * configured third-party memory server and exposes its discovered tools
 * through [ToolMount] (MCP dispatcher + LLM agent tool registry). Storage and
 * accounts belong to the provider; Browser4 only bridges the tool interface.
 */
@Configuration
@ConditionalOnProperty(
    name = ["browser4.agent.memory.external.enabled"],
    havingValue = "true",
)
@Lazy
open class MemoryExternalConfiguration : ToolMount {

    @Bean
    open fun externalMemoryBridge(): MemoryExternalBridge {
        return MemoryExternalBridge(
            ExternalMemoryConfig.fromSystem(),
            CoroutineScope(Dispatchers.Default + SupervisorJob()),
        )
    }

    @Bean
    open fun memoryExternalToolExecutor(externalMemoryBridge: MemoryExternalBridge): MemoryExternalToolExecutor {
        return MemoryExternalToolExecutor(
            externalMemoryBridge,
            ExternalMemoryConfig.fromSystem().toolPrefix,
        )
    }

    override fun getToolExecutors(): List<ToolExecutor> =
        listOf(memoryExternalToolExecutor(externalMemoryBridge()))
}
