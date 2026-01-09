package ai.platon.pulsar.examples.agent

import ai.platon.pulsar.agentic.ExtractionSchema
import ai.platon.pulsar.agentic.context.AgenticContexts

class Extract {
    private val session = AgenticContexts.getOrCreateSession()

    suspend fun run() {
        val url = "https://news.ycombinator.com/news"

        val driver = session.getOrCreateBoundDriver()
        val agent = session.companionAgent

        driver.open(url)

        var action = "提取列表页文章数据：文章标题，评论信息"
        var schema = """
{"fields": [{"name": "articles", "type": "array", "description": "文章列表", "arrayElements": {"name": "article", "type": "object", "objectMemberProperties": [{"name": "title", "type": "string", "description": "文章标题", "required": true}, {"name": "comments", "type": "string", "description": "评论数", "required": true}]}}]}
        """.trimIndent()

        var extractResult = agent.extract(action, ExtractionSchema.parse(schema))
        println(extractResult.data)

        action = "提取列表页文章数据：文章标题，评论信息"
        val result = agent.run(action)
        println(result.finalResult)
    }
}

suspend fun main() = Extract().run()
