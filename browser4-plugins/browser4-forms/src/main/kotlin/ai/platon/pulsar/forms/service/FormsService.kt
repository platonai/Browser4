package ai.platon.pulsar.forms.service

import ai.platon.pulsar.api.WebDriver
import org.slf4j.LoggerFactory

/**
 * Business logic for the forms plugin.
 *
 * Loads a browser-side JavaScript resource from the classpath and executes
 * it via [WebDriver.evaluateValue]. The script runs in the real page
 * context, so it sees the fully rendered DOM.
 */
open class FormsService {
    private val logger = LoggerFactory.getLogger(FormsService::class.java)

    private val script: String by lazy { loadResource("/forms/detectForms.js") }

    /**
     * Run the browser-side script on the current page.
     */
    suspend fun detectForms(driver: WebDriver): Any? {
        requireNotNull(driver) { "detectForms requires a WebDriver (current page context)" }
        return try {
            driver.evaluateValue(script)
        } catch (e: Exception) {
            logger.warn("forms detectForms failed on {}: {}", driver.currentUrl(), e.message)
            mapOf("error" to (e.message ?: "unknown error"))
        }
    }

    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("forms script resource not found on classpath: $path")
    }
}