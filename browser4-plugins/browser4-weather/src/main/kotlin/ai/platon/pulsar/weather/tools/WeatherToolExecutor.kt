package ai.platon.pulsar.weather.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.weather.service.WeatherService
import kotlin.reflect.KClass

/**
 * Tool executor for the "weather" domain.
 *
 * Tools run browser-side JS via [WeatherService], which executes a
 * classpath script with [WebDriver.evaluateValue] — so they see the
 * fully rendered DOM.
 */
open class WeatherToolExecutor(
    private val service: WeatherService,
) : AbstractToolExecutor() {

    override val domain = "weather"

    /** Tools receive the current page as the receiver. */
    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["fetchWeather"] = ToolSpec(
            domain = domain,
            method = "fetchWeather",
            arguments = emptyList(),
            returnType = "Any",
            description = "Fetch the current weather for the active page"
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
            "fetchWeather" -> service.fetchWeather(driver)
            else -> throw IllegalArgumentException("Unsupported weather method: $functionName(${args.keys})")
        }
    }
}