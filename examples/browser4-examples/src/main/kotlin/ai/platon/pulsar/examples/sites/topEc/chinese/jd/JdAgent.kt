package ai.platon.pulsar.examples.sites.topEc.chinese.jd

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.createAgent()
    val history = agent.run("""
        Open https://jd.com and search for "智能耳机", then list the top 5 products with their names and prices.
    """.trimIndent())

    println(history.finalResult)
}
