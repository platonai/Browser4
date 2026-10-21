/**
 * Copyright (c) Platon.AI.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.platon.pulsar.weather.service

import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.weather.config.WeatherConfig

/**
 * Business logic for the browser4-weather plugin.
 *
 * Loads two browser-side JavaScript resources from the classpath and executes
 * them via [WebDriver.evaluateValue]. The scripts run in the real page context,
 * so they see the fully rendered DOM — this is the browser-first advantage over
 * static HTML scraping.
 */
open class WeatherService(
    private val config: WeatherConfig,
) {
    private val logger = getLogger(WeatherService::class)

    private val extractScript: String by lazy { loadResource("/weather/extract-meta.js") }
    private val checkScript: String by lazy { loadResource("/weather/check-issues.js") }

    /**
     * Extract all SEO metadata from the current page.
     * Returns the raw result of [extract-meta.js] (title, description, canonical,
     * Open Graph, Twitter, headings, word count, JSON-LD, etc.).
     */
    fun extractMeta(driver: WebDriver): Any? {
        requireNotNull(driver) { "extractMeta requires a WebDriver (current page context)" }
        return try {
            driver.evaluateValue(extractScript)
        } catch (e: Exception) {
            logger.warn("SEO extractMeta failed on {}: {}", driver.currentUrl(), e.message)
            mapOf("error" to (e.message ?: "unknown error"))
        }
    }

    /**
     * Audit the current page for common SEO issues.
     * Returns the raw result of [check-issues.js] (a list of issues with severity).
     */
    fun checkIssues(driver: WebDriver): Any? {
        requireNotNull(driver) { "checkIssues requires a WebDriver (current page context)" }
        return try {
            driver.evaluateValue(checkScript)
        } catch (e: Exception) {
            logger.warn("SEO checkIssues failed on {}: {}", driver.currentUrl(), e.message)
            mapOf("error" to (e.message ?: "unknown error"))
        }
    }

    private fun loadResource(path: String): String {
        return javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("SEO script resource not found on classpath: $path")
    }
}
