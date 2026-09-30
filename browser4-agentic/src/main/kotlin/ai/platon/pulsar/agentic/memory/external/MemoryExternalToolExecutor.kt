package ai.platon.pulsar.agentic.memory.external

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.agentic.tools.specs.ToolCallSpecificationProvider
import kotlin.reflect.KClass

/**
 * Agent-facing executor for the tools discovered from an external memory MCP
 * server. Stateless: routing goes through the owned [bridge]; the receiver is
 * ignored (the MCP dispatcher passes `Any()`), so the executor can be
 * registered globally while per-agent dispatch still resolves a target.
 *
 * Tool specs are dynamic — they appear after the bridge's discovery handshake,
 * hence [getToolSpecs] delegates to the bridge instead of a static map.
 *
 * Design: docs-dev/copilot/robust-browser-agent-memory-system-design.md (§7, M4).
 */
class MemoryExternalToolExecutor(
    private val bridge: MemoryExternalBridge,
    override val domain: String,
) : AbstractToolExecutor(), ToolCallSpecificationProvider {

    override val receiverClass: KClass<*> = MemoryExternalBridge::class

    override fun getToolSpecs(): Map<String, ToolSpec> =
        bridge.getToolSpecs().associateBy { it.method }

    override fun getToolCallSpecifications(): List<ToolSpec> = getToolSpecs().values.toList()

    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any,
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        val spec = getToolSpecs()[functionName]
            ?: throw IllegalArgumentException(
                "Unknown external memory tool: $functionName — available: ${getToolSpecs().keys}"
            )
        // Required = args with a null defaultValue (provider schema's required list).
        validateArgs(
            args,
            allowed = spec.arguments.map { it.name }.toSet(),
            required = spec.arguments.filter { it.defaultValue == null }.map { it.name }.toSet(),
            functionName = functionName,
        )
        return bridge.call(functionName, args)
    }
}
