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
package ai.platon.pulsar.weather.tools

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.builtin.AbstractToolExecutor
import ai.platon.pulsar.core.api.WebDriver
import ai.platon.pulsar.weather.service.WeatherService
import kotlin.reflect.KClass

/**
 * LLM agent tool executor for the `weather` domain.
 *
 * Provides AI agents with the ability to:
 * - `weather.extractMeta()` — extract all SEO metadata from the current page
 * - `weather.checkIssues()` — audit the current page for common SEO problems
 *
 * Both tools run JavaScript inside the real browser page, so they see the
 * fully rendered DOM (including client-side injected meta tags).
 */
open class WeatherToolExecutor(
    private val seoService: WeatherService,
) : AbstractToolExecutor() {

    override val domain = "weather"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        toolSpec["extractMeta"] = ToolSpec(
            domain = domain,
            method = "extractMeta",
            arguments = emptyList(),
            returnType = "SeoMeta",
            description = "Extract all SEO metadata from the current page: title, meta description, canonical URL, Open Graph, Twitter Card, robots, headings count, word count, and JSON-LD structured data.",
            help = """
                weather.extractMeta()

                Runs a script inside the browser page and returns a JSON object with all
                SEO-relevant meta tags. No arguments needed — operates on the current page.

                Example return fields: url, title, description, canonical, og, twitter,
                headings {h1,h2,h3,h4}, images, imagesWithoutAlt, wordCount, jsonLd.
            """.trimIndent()
        )

        toolSpec["checkIssues"] = ToolSpec(
            domain = domain,
            method = "checkIssues",
            arguments = emptyList(),
            returnType = "SeoAuditResult",
            description = "Audit the current page for common SEO issues: missing/short title, missing meta description, no canonical link, missing Open Graph tags, multiple h1 headings, thin content, images without alt text, noindex directive. Returns a categorized issue list with severity levels.",
            help = """
                weather.checkIssues()

                Runs an audit script inside the browser page and returns a JSON object
                with an issue list. Each issue has: severity (error|warning|info),
                field, message, and value. Also includes summary counts.

                Use this after weather.extractMeta() to get actionable recommendations.
            """.trimIndent()
        )
    }

    override suspend fun callFunctionOn(
        domain: String,
        functionName: String,
        args: Map<String, Any?>,
        receiver: Any,
    ): Any? {
        val driver = receiver as? WebDriver
            ?: throw IllegalArgumentException(
                "weather.$functionName requires a WebDriver receiver (current page context)"
            )

        return when (functionName) {
            "extractMeta" -> seoService.extractMeta(driver)
            "checkIssues" -> seoService.checkIssues(driver)
            else -> throw IllegalArgumentException(
                "Unsupported weather method: $functionName. Supported: extractMeta, checkIssues."
            )
        }
    }
}
