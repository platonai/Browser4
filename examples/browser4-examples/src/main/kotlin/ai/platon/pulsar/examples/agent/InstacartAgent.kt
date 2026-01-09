package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val task = """
        Search for apple, eggs, bread one by one on Instacart at the nearest store.

        You will buy all of the items at the same store.
        For each item:
        1. Search for the item
        2. Find the best match (closest name, lowest price)
        3. Add the item to the cart

        Site:
        - Instacart: https://www.instacart.com/
        """.trimIndent()

    agent.run(task)
}
