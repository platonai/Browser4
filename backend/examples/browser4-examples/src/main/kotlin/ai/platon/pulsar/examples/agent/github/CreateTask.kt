package ai.platon.pulsar.examples.agent.github

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()

    val task = """
        1. go to https://github.com/platonai/Browser4
        2. click on the "Open agents panel" button on the top right
        3. wait for the agents panel to open
        4. type "create document for AgenticSession" in the input box
        5. submit the input to start the task
        """.trimIndent()

    agent.run(task)
}
