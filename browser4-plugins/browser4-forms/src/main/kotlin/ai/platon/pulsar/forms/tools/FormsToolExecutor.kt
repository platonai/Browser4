package ai.platon.pulsar.forms.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.forms.service.FormsService
import kotlin.reflect.KClass

/**
 * Tool executor for the "forms" domain.
 *
 * Tools run browser-side JS via [FormsService], which executes a
 * classpath script with [WebDriver.evaluateValue] — so they see the
 * fully rendered DOM.
 */
open class FormsToolExecutor(
    private val service: FormsService,
) : AbstractToolExecutor() {

    override val domain = "forms"

    /** Tools receive the current page as the receiver. */
    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["detectForms"] = ToolSpec(
            domain = domain,
            method = "detectForms",
            arguments = emptyList(),
            returnType = "Any",
            description = "Detect forms, their fields and submit buttons on the current page"
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
            "detectForms" -> service.detectForms(driver)
            else -> throw IllegalArgumentException("Unsupported forms method: $functionName(${args.keys})")
        }
    }
}