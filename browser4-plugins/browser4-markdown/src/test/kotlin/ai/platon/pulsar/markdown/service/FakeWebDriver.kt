/**
 * Copyright (c) Vincent Zhang, ivincent.zhang@gmail.com, Platon.AI.
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
package ai.platon.pulsar.markdown.service

import ai.platon.pulsar.core.api.WebDriver
import java.lang.reflect.Proxy

/**
 * In-memory [WebDriver] for unit tests.
 *
 * The markdown services only use `currentUrl`, `navigate` and `evaluate`, so a
 * dynamic proxy serves canned page content for those three and fails loudly for
 * anything else. No browser, no CDP connection, no network.
 */
internal class FakeWebDriver(entryUrl: String) {

    /** Content served for one URL: the three probes [MarkdownConverter] runs. */
    internal data class Page(
        val title: String = "",
        val markdown: String = "",
        val linksJson: String = "[]",
    )

    private val pages = mutableMapOf<String, Page>()
    private val navigationHistory = mutableListOf<String>()

    /** The URL the fake tab is currently on. */
    var currentUrl: String = entryUrl
        private set

    /** URLs passed to [WebDriver.navigate], in order. */
    val navigations: List<String> get() = navigationHistory.toList()

    /** Register the content served when the fake tab is on [url]. */
    fun addPage(url: String, title: String = "", markdown: String = "", linksJson: String = "[]") {
        pages[url] = Page(title = title, markdown = markdown, linksJson = linksJson)
    }

    /** The proxied driver to hand to the markdown services. */
    val driver: WebDriver = Proxy.newProxyInstance(
        WebDriver::class.java.classLoader,
        arrayOf(WebDriver::class.java),
    ) { proxy, method, args ->
        invoke(proxy, method.name, args)
    } as WebDriver

    private fun invoke(proxy: Any, methodName: String, args: Array<out Any?>?): Any? {
        return when (methodName) {
            "currentUrl" -> currentUrl

            "navigate" -> {
                val target = args?.firstOrNull()?.toString().orEmpty()
                navigationHistory.add(target)
                currentUrl = target
                Unit
            }

            "evaluate" -> {
                val expression = args?.firstOrNull()?.toString().orEmpty()
                val page = pages[currentUrl]
                    ?: error("FakeWebDriver has no page registered for $currentUrl")
                when (expression) {
                    MarkdownConverter.MARKDOWN_EXTRACTION_SCRIPT -> page.markdown
                    MarkdownConverter.LINK_DISCOVERY_SCRIPT -> page.linksJson
                    "document.title" -> page.title
                    else -> error("FakeWebDriver cannot evaluate: ${expression.take(60)}")
                }
            }

            "close" -> Unit
            "toString" -> "FakeWebDriver($currentUrl)"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> throw UnsupportedOperationException("FakeWebDriver does not implement $methodName")
        }
    }
}
