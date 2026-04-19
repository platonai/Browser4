package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.AgentToolExecutor
import ai.platon.pulsar.common.getLogger
import kotlin.reflect.KClass

class SystemToolExecutor(
    val agentToolExecutor: AgentToolExecutor
) : AbstractToolExecutor() {
    private val logger = getLogger(this)

    override val domain = "system"

    override val receiverClass: KClass<*> = SystemToolExecutor::class

    init {
        toolSpec["help"] = ToolSpec(
            domain = domain,
            method = "help",
            arguments = listOf(
                ToolSpec.Arg("domain", "String", null),
                ToolSpec.Arg("method", "String", null)
            ),
            returnType = "String",
            description = "Get help information for a specific tool method in a domain"
        )
    }

    fun help(domain: String, method: String): String {
        return agentToolExecutor.help(domain, method)
    }

    /**
     * Execute system.* expressions with named args.
     */
    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }

        return when (functionName) {
            "help" -> {
                validateArgs(args, allowed = setOf("domain", "method"), required = setOf("domain", "method"), functionName)
                help(args["domain"]!! as String, args["method"]!! as String)
            }

            else -> throw IllegalArgumentException("Unsupported system method: $functionName(${args.keys})")
        }
    }
}
