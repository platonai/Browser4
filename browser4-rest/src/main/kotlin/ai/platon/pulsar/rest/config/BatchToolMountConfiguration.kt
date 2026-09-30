package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.tools.BatchToolExecutor
import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [BatchToolExecutor] (`batch_run`) through [ToolMount], so both MCP
 * channels advertise it: the private dispatcher resolves it from
 * `CustomToolRegistry`, and the standard server merges the same registry into its
 * tool list.
 *
 * The executor needs the session's `AgentToolManager` as its receiver — see
 * [ai.platon.pulsar.rest.mcp.controller.CustomToolTargets], which resolves it for
 * the dispatcher and for the standard server alike.
 */
@Configuration
class BatchToolMountConfiguration : ToolMount {

    @Bean
    fun batchToolExecutor(): BatchToolExecutor = BatchToolExecutor()

    override fun getToolExecutors(): List<ToolExecutor> = listOf(batchToolExecutor())
}
