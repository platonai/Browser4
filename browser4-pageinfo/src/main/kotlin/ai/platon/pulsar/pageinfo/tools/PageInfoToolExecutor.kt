package ai.platon.pulsar.pageinfo.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.pageinfo.service.PageInfoService
import kotlin.reflect.KClass

/**
 * Tool executor for the "pageinfo" domain.
 *
 * Tools run browser-side JS via [PageInfoService], which executes a
 * classpath script with [WebDriver.evaluateValue] — so they see the
 * fully rendered DOM.
 */
open class PageInfoToolExecutor(
    private val service: PageInfoService,
) : AbstractToolExecutor() {

    override val domain = "pageinfo"

    /** Tools receive the current page as the receiver. */
    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["extractPageInfo"] = ToolSpec(
            domain = domain,
            method = "extractPageInfo",
            arguments = emptyList(),
            returnType = "Any",
            description = "Extract page title, URL and meta tags via browser-side JavaScript"
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
            "extractPageInfo" -> service.extractPageInfo(driver)
            else -> throw IllegalArgumentException("Unsupported pageinfo method: $functionName(${args.keys})")
        }
    }
}

