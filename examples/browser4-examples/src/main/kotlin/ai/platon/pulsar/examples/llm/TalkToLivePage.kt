package ai.platon.pulsar.examples.llm

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.skeleton.event.impl.DefaultPageEventHandlers
import ai.platon.pulsar.test.TestUrls
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.milliseconds

/**
 * Demonstrates talking to the active webpage.
 *
 * Every line of `actions` is one natural-language task executed by the session's
 * companion agent — the CLI tool loop. The session's one-shot `act(...)` entry
 * point was removed on 2026-11-12 (`agent.run` is the only engine now), so this
 * example drives the same steps through `companionAgent.run`.
 * */
suspend fun main() {
    val session = AgenticContexts.createSession()
    val url = TestUrls.PRODUCT_DETAIL_URL

    val actions = """
move cursor to the search bar and click it
scroll to middle
scroll to top
get the text of the element with id 'productTitle'
        """.trimIndent().split("\n").filter { it.isNotBlank() }

    val agent = session.companionAgent

    val eventHandlers = DefaultPageEventHandlers()
    eventHandlers.browseEventHandlers.onDocumentFullyLoaded.addLast { _, driver ->
        while (true) {
            println("Talk and execute on the active page")

            actions.forEach { action ->
                println("\n")
                println(OffsetDateTime.now())
                println(">>> $action")
                // Keep the tool loop on the page this handler fired for.
                session.bindDriver(driver)
                val history = agent.run(action)
                val state = history.finalResult
                val result = state?.toolCallResult
                val expression = result?.expression?.takeIf { it.isNotBlank() }
                    ?: state?.actionDescription?.toString().orEmpty()
                println("> $expression")
                val value = result?.evaluate?.value?.toString()
                if (!value.isNullOrBlank()) {
                    println()
                    println(Strings.compactInline(value, 500))
                }
                delay(1000.milliseconds)
            }

            println("All done.")
        }
    }
    session.open(url, eventHandlers)

    readlnOrNull()
}
