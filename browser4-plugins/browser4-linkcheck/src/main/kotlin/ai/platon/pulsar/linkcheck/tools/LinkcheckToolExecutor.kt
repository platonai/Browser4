package ai.platon.pulsar.linkcheck.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.linkcheck.service.LinkcheckService
import kotlin.reflect.KClass

/**
 * Tool executor for the "linkcheck" domain.
 *
 * Tools run browser-side JS via [LinkcheckService], which executes a
 * classpath script with [WebDriver.evaluateValue] — so they see the
 * fully rendered DOM.
 */
open class LinkcheckToolExecutor(
    private val service: LinkcheckService,
) : AbstractToolExecutor() {
    override val domain = "linkcheck"

    /** Tools receive the current page as the receiver. */
    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["countLinks"] = ToolSpec(
            domain = "linkcheck",
            method = "countLinks",
            arguments = emptyList(),
            returnType = "LinkCountResult",
            description = "Count total, external and internal links on the current page",
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        if (functionName == "countLinks") {
            return service.countLinks(receiver as WebDriver)
        }
        throw IllegalArgumentException("Unsupported linkcheck method: $functionName")
    }
}
