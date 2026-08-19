package ai.platon.pulsar.headings.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.headings.service.HeadingsService
import kotlin.reflect.KClass

/**
 * Tool executor for the "headings" domain.
 *
 * Tools run browser-side JS via [HeadingsService], which executes a
 * classpath script with [WebDriver.evaluateValue] — so they see the
 * fully rendered DOM.
 */
open class HeadingsToolExecutor(
    private val service: HeadingsService,
) : AbstractToolExecutor() {

    override val domain = "headings"

    /** Tools receive the current page as the receiver. */
    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["extractHeadings"] = ToolSpec(
            domain = domain,
            method = "extractHeadings",
            arguments = emptyList(),
            returnType = "Any",
            description = "Extract page headings (h1-h6) with levels"
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
            "extractHeadings" -> service.extractHeadings(driver)
            else -> throw IllegalArgumentException("Unsupported headings method: $functionName(${args.keys})")
        }
    }
}