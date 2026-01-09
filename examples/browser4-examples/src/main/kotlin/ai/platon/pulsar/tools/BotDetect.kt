package ai.platon.pulsar.tools

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()
    val history = agent.run("navigate to https://www.browserscan.net/ and tell me what leaks")
    println(history.finalResult)
}
