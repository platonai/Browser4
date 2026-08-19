package ai.platon.pulsar.pagetitle.service

import ai.platon.pulsar.api.WebDriver
import org.slf4j.LoggerFactory

/**
 * Business logic for the pagetitle plugin.
 *
 * Loads a browser-side JavaScript resource from the classpath and executes
 * it via [WebDriver.evaluateValue]. The script runs in the real page
 * context, so it sees the fully rendered DOM.
 */
data class PageInfo(
    val title: String? = null,
    val url: String? = null,
    val description: String? = null
)

open class PagetitleService {
    private val logger = LoggerFactory.getLogger(PagetitleService::class.java)

    private val script: String by lazy {
        loadResource("/pagetitle/getPageInfo.js")
    }

    /**
     * Run the browser-side script on the current page.
     */
    suspend fun getPageInfo(driver: WebDriver): Any? {
        requireNotNull(driver) { "getPageInfo requires a WebDriver (current page context)" }
        return try {
            driver.evaluateValue(script)
        } catch (e: Exception) {
            logger.warn("pagetitle getPageInfo failed on {}: {}", driver.currentUrl(), e.message)
            mapOf("error" to (e.message ?: "unknown error"))
        }
    }

    /**
     * Builds a compact page title summary.
     *
     * An empty title produces an empty string. Titles longer than [maxLength]
     * are truncated and suffixed with an ellipsis.
     */
    fun summarize(info: PageInfo?, maxLength: Int): String {
        val title = info?.title.orEmpty()
        if (title.isEmpty()) return ""
        return if (title.length > maxLength) title.take(maxLength) + "..." else title
    }

    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("pagetitle script resource not found on classpath: $path")
    }
}
