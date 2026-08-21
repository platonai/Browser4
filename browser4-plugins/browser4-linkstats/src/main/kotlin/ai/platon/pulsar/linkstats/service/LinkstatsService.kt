package ai.platon.pulsar.linkstats.service

import ai.platon.pulsar.api.WebDriver
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import org.slf4j.LoggerFactory

/**
 * Business logic for the linkstats plugin.
 *
 * Loads a browser-side JavaScript resource from the classpath and executes
 * it via [WebDriver.evaluateValue]. The script runs in the real page
 * context, so it sees the fully rendered DOM.
 */
open class LinkstatsService {
    private val logger = LoggerFactory.getLogger(LinkstatsService::class.java)

    private val script: String by lazy { loadResource("/linkstats/summarize.js") }

    /**
     * Run the browser-side script on the current page.
     *
     * The script returns a JSON string: {url, title, total, internal, external,
     * mailto, tel, nofollow}. Returns null on failure (never throws).
     */
    suspend fun summarize(driver: WebDriver): Any? {
        requireNotNull(driver) { "summarize requires a WebDriver (current page context)" }
        return try {
            driver.evaluateValue(script)
        } catch (e: Exception) {
            logger.warn("linkstats summarize failed on {}: {}", driver.currentUrl(), e.message)
            null
        }
    }

    /**
     * Run [summarize] and normalize the result to a flat map with string keys.
     *
     * Tolerates both result shapes of [WebDriver.evaluateValue]: a pre-parsed
     * map (JS object) or a JSON string (JSON.stringify). Shared by the browse
     * event handler and the tool executor.
     */
    suspend fun summarizeAsMap(driver: WebDriver): Map<String, Any?> {
        val raw = summarize(driver) ?: return emptyMap()
        return when (raw) {
            is Map<*, *> -> raw.entries.associate { (key, value) -> key.toString() to value }
            is String -> parseJsonObject(raw)
            else -> emptyMap()
        }
    }

    /**
     * Run the browser-side script and return a typed [LinkSummary].
     *
     * Delegates to [summarizeAsMap] so the handler and executor share the
     * same parsing path.
     */
    suspend fun countLinks(driver: WebDriver): LinkSummary = LinkSummary.from(summarizeAsMap(driver))

    /**
     * Parse a JSON object string into a map; returns an empty map on any parse error.
     */
    private fun parseJsonObject(json: String): Map<String, Any?> {
        return try {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) {
                emptyMap()
            } else {
                val map: Map<*, *> = pulsarObjectMapper().readValue(trimmed, Map::class.java)
                map.entries.associate { (key, value) -> key.toString() to value }
            }
        } catch (e: Exception) {
            logger.debug("Failed to parse linkstats result: {}", e.message)
            emptyMap()
        }
    }

    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("linkstats script resource not found on classpath: $path")
    }
}