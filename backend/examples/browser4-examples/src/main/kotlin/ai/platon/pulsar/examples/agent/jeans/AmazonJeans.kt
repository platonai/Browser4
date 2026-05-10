package ai.platon.pulsar.examples.agent.jeans

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val task = """
        1. go to https://www.amazon.com/
        2. search for jeans
        3. compare the first 10 ones
        4. write the result to a markdown file
        """.trimIndent()

    agent.run(task)
}
