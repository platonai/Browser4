package ai.platon.pulsar.headings.service

import ai.platon.pulsar.api.WebDriver
import org.slf4j.LoggerFactory

/**
 * Business logic for the headings plugin.
 *
 * Loads a browser-side JavaScript resource from the classpath and executes
 * it via [WebDriver.evaluateValue]. The script runs in the real page
 * context, so it sees the fully rendered DOM.
 */
open class HeadingsService {
    private val logger = LoggerFactory.getLogger(HeadingsService::class.java)

    private val script: String by lazy { loadResource("/headings/extractHeadings.js") }

    /**
     * Run the browser-side script on the current page.
     */
    suspend fun extractHeadings(driver: WebDriver): Any? {
        requireNotNull(driver) { "extractHeadings requires a WebDriver (current page context)" }
        return try {
            driver.evaluateValue(script)
        } catch (e: Exception) {
            logger.warn("headings extractHeadings failed on {}: {}", driver.currentUrl(), e.message)
            mapOf("error" to (e.message ?: "unknown error"))
        }
    }

    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("headings script resource not found on classpath: $path")
    }
}