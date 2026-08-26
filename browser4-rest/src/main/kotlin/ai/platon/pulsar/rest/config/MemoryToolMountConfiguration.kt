package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.memory.AgentMemory
import ai.platon.pulsar.agentic.memory.MemoryScope
import ai.platon.pulsar.agentic.memory.MemoryToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy

/**
 * Registers the `memory.*` tools via [ToolMount] so they are discovered by
 * [ai.platon.pulsar.boot.plugin.PluginManager] and made available to both the
 * MCP dispatcher and the LLM agent tool system.
 *
 * The shared [AgentMemory] (backend scope) reads/writes the same event log
 * directory as per-agent memories; per-agent dispatch inside an agent run
 * binds the agent's own memory via a custom target (see
 * `RobustBrowserAgent.agentMemory`).
 *
 * Enabled by default; opt out with `browser4.agent.memory.enabled=false`.
 */
@Configuration
@ConditionalOnProperty(
    name = ["browser4.agent.memory.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@Lazy
open class MemoryToolMountConfiguration : ToolMount {

    @Bean
    open fun agentMemory(): AgentMemory {
        // AgentMemory itself honors the PEM `knowledge.dir` system property
        // (explicit knowledgeDir wins, then the property, then the PEM default).
        val memory = AgentMemory(MemoryScope(null))
        // Rolling hygiene on boot: move expired raw events to the archive.
        runCatching { memory.eventLog.archiveExpired() }
        return memory
    }

    @Bean
    open fun memoryToolExecutor(agentMemory: AgentMemory): MemoryToolExecutor {
        return MemoryToolExecutor(agentMemory)
    }

    override fun getToolExecutors(): List<ToolExecutor> = listOf(memoryToolExecutor(agentMemory()))
}
