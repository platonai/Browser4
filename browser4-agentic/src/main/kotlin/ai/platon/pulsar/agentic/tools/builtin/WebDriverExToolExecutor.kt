package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import kotlin.reflect.KClass

class WebDriverExToolExecutor: AbstractToolExecutor() {
    private val logger = getLogger(this)

    override val domain = "driverEx"

    // target must be AbstractWebDriver (was Browser before, incorrect)
    override val receiverClass: KClass<*> = AbstractWebDriver::class

    init {
        toolSpec["extract"] = ToolSpec(
            domain = domain,
            method = "extract",
            arguments = listOf(
                ToolSpec.Arg("selectors", "List<String>", null)
            ),
            returnType = "List<String>",
            description = "Extract text content using multiple CSS selectors (union selection)"
        )
    }

    /**
     * Execute driverEx.* expressions with named args.
     */
    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }
        val driver = requireNotNull(receiver as? AbstractWebDriver) { "Target must be AbstractWebDriver" }

        return when (functionName) {
            "extract" -> {
                validateArgs(args, allowed = setOf("selectors"), required = setOf("selectors"), functionName)
                val selectors = paramStringList(args, "selectors", functionName, required = true)
                // simple behavior: union selection by comma
                val union = selectors.joinToString(",")
                driver.selectTextAll(union)
            }
            else -> throw IllegalArgumentException("Unsupported driverEx method: $functionName(${args.keys})")
        }
    }
}
