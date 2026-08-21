package ai.platon.pulsar.linkstats.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.linkstats.service.LinkstatsService
import kotlin.reflect.KClass

/**
 * Tool executor for the "linkstats" domain.
 *
 * Tools run browser-side JS via [LinkstatsService], which executes a
 * classpath script with [WebDriver.evaluateValue] — so they see the
 * fully rendered DOM.
 */
open class LinkstatsToolExecutor(
    private val service: LinkstatsService,
) : AbstractToolExecutor() {

    override val domain = "linkstats"

    /** Tools receive the current page as the receiver. */
    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["summarize"] = ToolSpec(
            domain = domain,
            method = "summarize",
            arguments = emptyList(),
            returnType = "Any",
            description = "统计页面链接分布"
        )
    }

    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        val driver = receiver as? WebDriver
            ?: throw IllegalArgumentException(
                "$domain.$functionName requires a WebDriver receiver (current page context)"
            )
        return when (functionName) {
            "summarize" -> service.summarizeAsMap(driver)
            else -> throw IllegalArgumentException("Unsupported linkstats method: $functionName(${args.keys})")
        }
    }
}