package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val task = """
        1. Navigate to https://news.ycombinator.com/news.
        """.trimIndent()

    val history = agent.run(task)
    println(history.finalResult)
}
