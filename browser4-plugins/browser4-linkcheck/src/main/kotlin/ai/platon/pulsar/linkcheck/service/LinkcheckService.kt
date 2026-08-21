package ai.platon.pulsar.linkcheck.service

import ai.platon.pulsar.api.WebDriver
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Result of counting links on the current page.
 *
 * @property total the number of all `a[href]` elements
 * @property external the number of absolute http/https links whose origin differs
 *                    from `location.origin`
 * @property internal the number of the remaining links (relative links, `#` anchors,
 *                    `mailto:`, `tel:`, and same-origin absolute links)
 */
data class LinkCountResult(
    val total: Int,
    val external: Int,
    val internal: Int,
)

/**
 * Business logic for the linkcheck plugin.
 *
 * Loads a browser-side JavaScript resource from the classpath and executes
 * it via [WebDriver.evaluateValue]. The script runs in the real page
 * context, so it sees the fully rendered DOM.
 */
open class LinkcheckService {
    private val logger = LoggerFactory.getLogger(LinkcheckService::class.java)

    private val script: String by lazy { loadResource("/linkcheck/countLinks.js") }

    /**
     * Run the browser-side script on the current page and parse the counts.
     *
     * On any failure, logs a warning and returns a zeroed [LinkCountResult].
     */
    suspend fun countLinks(driver: WebDriver): LinkCountResult {
        requireNotNull(driver) { "countLinks requires a WebDriver (current page context)" }
        return try {
            val json = driver.evaluateValue(script) as? String ?: ""
            parseCounts(json)
        } catch (e: Exception) {
            logger.warn("linkcheck countLinks failed on {}: {}", driver.currentUrl(), e.message)
            LinkCountResult(total = 0, external = 0, internal = 0)
        }
    }

    /**
     * Parses a JSON object `{"total":..,"external":..,"internal":..}` into a [LinkCountResult].
     *
     * - an empty/blank string yields all zeros
     * - a missing or non-integer field yields 0 for that field
     * - invalid JSON yields all zeros
     */
    fun parseCounts(json: String): LinkCountResult {
        if (json.isBlank()) {
            return LinkCountResult(total = 0, external = 0, internal = 0)
        }
        return try {
            val node = objectMapper.readTree(json)
            LinkCountResult(
                total = node.path("total").takeIf { it.isInt }?.intValue() ?: 0,
                external = node.path("external").takeIf { it.isInt }?.intValue() ?: 0,
                internal = node.path("internal").takeIf { it.isInt }?.intValue() ?: 0,
            )
        } catch (e: Exception) {
            LinkCountResult(total = 0, external = 0, internal = 0)
        }
    }

    /**
     * Human-readable one-line summary of the link counts.
     */
    fun summarize(result: LinkCountResult): String {
        return "total=${result.total} external=${result.external} internal=${result.internal}"
    }

    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("linkcheck script resource not found on classpath: $path")
    }

    companion object {
        private val objectMapper = ObjectMapper()
    }
}
