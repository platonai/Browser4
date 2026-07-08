package ${package}.tools

/**
 * Optional: Register custom tools for LLM agents.
 *
 * When your plugin is wired as a [ToolMount], its [ToolExecutor] instances
 * are registered in the [CustomToolRegistry] and become available to LLM
 * agents (LangChain4j, MCP, etc.).
 *
 * To enable tool registration:
 * 1. Add browser4-agentic dependency to your pom.xml (provided scope)
 * 2. Implement [ToolMount] on your auto-configuration class
 * 3. Override [getToolExecutors] to return your executor instances
 *
 * Example:
 * ```kotlin
 * @AutoConfiguration
 * class PluginAutoConfiguration : ToolMount {
 *     override fun getToolExecutors(): List<ToolExecutor> {
 *         return listOf(MyToolExecutor())
 *     }
 * }
 * ```
 */
open class MyToolExecutor {
    // Add your tool executor implementation here.
    // See ai.platon.pulsar.agentic.tools.builtin.ToolExecutor for the interface.
}
