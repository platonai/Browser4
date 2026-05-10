package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val agent = AgenticContexts.getOrCreateAgent()
    val task = "打开微博首页，搜索人马座A星的观星人，阅读该用户的最新微博，告诉我他最近都在关注哪些问题"
    val history = agent.run(task)
    println(history.finalResult)
}
