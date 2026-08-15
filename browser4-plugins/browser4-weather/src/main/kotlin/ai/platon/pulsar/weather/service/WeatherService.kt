package ai.platon.pulsar.weather.service

import ai.platon.pulsar.api.WebDriver
import org.slf4j.LoggerFactory

/**
 * Business logic for the weather plugin.
 *
 * Loads a browser-side JavaScript resource from the classpath and executes
 * it via [WebDriver.evaluateValue]. The script runs in the real page
 * context, so it sees the fully rendered DOM.
 */
open class WeatherService {
    private val logger = LoggerFactory.getLogger(WeatherService::class.java)

    private val script: String by lazy { loadResource("/weather/fetchWeather.js") }

    /**
     * Run the browser-side script on the current page.
     */
    suspend fun fetchWeather(driver: WebDriver): Any? {
        requireNotNull(driver) { "fetchWeather requires a WebDriver (current page context)" }
        return try {
            driver.evaluateValue(script)
        } catch (e: Exception) {
            logger.warn("weather fetchWeather failed on {}: {}", driver.currentUrl(), e.message)
            mapOf("error" to (e.message ?: "unknown error"))
        }
    }

    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("weather script resource not found on classpath: $path")
    }
}