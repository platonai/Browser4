package ai.platon.pulsar.manual

import ai.platon.pulsar.agentic.context.AgenticContexts

class AgentRunner {
    val agent = AgenticContexts.getOrCreateAgent()

    suspend fun run() {
        val tasks = """
go to https://news.ycombinator.com/news , search for browser and read top 5 articles and give me a summary
go to amazon.com and search for pens to draw on whiteboards
打开百度查找厦门岛旅游景点，给出一个总结
go to https://news.ycombinator.com/news , open the 4-th articles in new tab
go to https://news.ycombinator.com/news , read top 3 articles and give me a summary
        """.lines().filter { it.isNotBlank() }

        val task = tasks[0]
        agent.run(task)
    }
}

suspend fun main() = AgentRunner().run()
