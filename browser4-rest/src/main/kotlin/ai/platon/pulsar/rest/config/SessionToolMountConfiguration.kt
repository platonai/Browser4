package ai.platon.pulsar.rest.config

import ai.platon.pulsar.agentic.tools.ToolMount
import ai.platon.pulsar.agentic.tools.builtin.ToolExecutor
import ai.platon.pulsar.rest.mcp.controller.SessionToolExecutor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers [SessionToolExecutor] (`open_session`, `close_session`,
 * `attach_browser`, …) through [ToolMount], so both MCP channels advertise and
 * can execute the session lifecycle tools.
 *
 * They used to be inline branches of the private dispatcher, which made them
 * invisible to the standard MCP server — it only knows executors. Mounting them
 * puts their specs in the shared tool catalog; the executor itself delegates to
 * the dispatcher's handlers, so the two channels answer a lifecycle call with the
 * same verified payload.
 */
@Configuration
class SessionToolMountConfiguration : ToolMount {

    @Bean
    fun sessionToolExecutor(): SessionToolExecutor = SessionToolExecutor()

    override fun getToolExecutors(): List<ToolExecutor> = listOf(sessionToolExecutor())
}
