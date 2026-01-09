package ai.platon.pulsar.examples.llm

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.skeleton.crawl.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.test.TestResourceUtil
import kotlinx.coroutines.delay
import java.time.OffsetDateTime

/**
 * Demonstrates talking to the active webpage.
 * */
fun main() {
    val session = AgenticContexts.createSession()
    val url = TestResourceUtil.PRODUCT_DETAIL_URL

    val actions = """
move cursor to the search bar and click it
scroll to middle
scroll to top
get the text of the element with id 'productTitle'
        """.trimIndent().split("\n").filter { it.isNotBlank() }

    val eventHandlers = DefaultPageEventHandlers()
    eventHandlers.browseEventHandlers.onDocumentFullyLoaded.addLast { page, driver ->
        while (true) {
            println("Talk and execute on the active page")

            actions.forEach { action ->
                println("\n")
                println(OffsetDateTime.now())
                println(">>> $action")
                session.bindDriver(driver)
                val results = session.plainActs(action)
                println(results.joinToString { it.modelResponse ?: "" })

                results.forEach { result ->
                    val expression = result.expression
                    val functionResult = result.evaluate
                    println()
                    println("> $expression")
                    if (functionResult != null && functionResult !is Unit) {
                        val s = functionResult.toString()
                        if (s.isNotBlank()) {
                            println()
                            println(Strings.compactInline(s, 500))
                        }
                    }
                }
                delay(1000)
            }

            println("All done.")
        }
    }
    session.open(url, eventHandlers)

    readlnOrNull()
}
