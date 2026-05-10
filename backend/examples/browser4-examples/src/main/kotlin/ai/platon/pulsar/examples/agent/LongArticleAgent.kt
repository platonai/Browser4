package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val task = """
        1. Navigate to https://news.ycombinator.com/news.
        2. Search for the keyword "browser".
        3. Read the top 3 results and summarize each one.
        """.trimIndent()

    val history = agent.run(task)
    println(history.finalResult)
}
