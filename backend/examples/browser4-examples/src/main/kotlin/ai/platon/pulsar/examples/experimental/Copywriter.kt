package ai.platon.pulsar.examples.experimental

import ai.platon.pulsar.agentic.context.AgenticContexts

suspend fun main() {
    val task = """
        1. go to https://weibo.com/u/1239003334
        2. search for "编程" posted by this user
        3. extract all the posts related to "编程"
        4. write each post to a file
        5. summarize the result in 500 words and save it to summary.txt
        """.trimIndent()

    AgenticContexts.run(task)
}
