package ai.platon.pulsar.examples.sites.zhihu

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.AppPaths
import java.nio.file.Files
import java.nio.file.Path

suspend fun main() {
    val agent = AgenticContexts.createAgent()
    val url = "https://www.zhihu.com/topic/19552134/hot"
    
    val task = """
        1. Visit $url
        2. If a login popup appears, try to close it and continue browsing (do not login)
        3. Find the list of "hot" questions in the visible area
        4. Extract the top 10 question titles (titles only)
        5. Save the title list as `zhihu-hot-questions.md` (use ordered list)
        6. Include the full content of `zhihu-hot-questions.md` in the answer
    """.trimIndent()

    val history = agent.run(task)
    println("Task completed. Final result:")
    println(history.finalResult)

    // Verification: check if the file was created
    val reportFile = AppPaths.REPORT_DIR.resolve("zhihu-hot-questions.md")
    if (Files.exists(reportFile)) {
        println("File created at: $reportFile")
        println("Content:")
        println(Files.readString(reportFile))
    } else {
        println("File was not created by the agent directly. Checking current directory...")
        val localFile = Path.of("zhihu-hot-questions.md")
        if (Files.exists(localFile)) {
             println("File created at: ${localFile.toAbsolutePath()}")
             println("Content:")
             println(Files.readString(localFile))
        }
    }
}
