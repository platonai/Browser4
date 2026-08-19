package ai.platon.pulsar.pagetitle.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.pagetitle.service.PagetitleService
import kotlin.reflect.KClass

/**
 * Tool executor for the "pagetitle" domain.
 *
 * Tools run browser-side JS via [PagetitleService], which executes a
 * classpath script with [WebDriver.evaluateValue] — so they see the
 * fully rendered DOM.
 */
open class PagetitleToolExecutor(
    private val service: PagetitleService,
) : AbstractToolExecutor() {

    override val domain = "pagetitle"

    /** Tools receive the current page as the receiver. */
    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["getPageInfo"] = ToolSpec(
            domain = domain,
            method = "getPageInfo",
            arguments = emptyList(),
            returnType = "Any",
            description = "Get the current page title URL and meta description"
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
            "getPageInfo" -> service.getPageInfo(driver)
            else -> throw IllegalArgumentException("Unsupported pagetitle method: $functionName(${args.keys})")
        }
    }
}