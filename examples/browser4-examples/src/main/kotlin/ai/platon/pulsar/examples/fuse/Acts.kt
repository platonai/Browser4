package ai.platon.pulsar.examples.fuse

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.printlnPro

class Acts {
    private val logger = getLogger(this)

    private var stepNo = 0
    private val session = AgenticContexts.getOrCreateSession()

    suspend fun run() {
        val url = "https://www.amazon.com/dp/B08PP5MSVB"

        val driver = session.getOrCreateBoundDriver()
        val agent = session.companionAgent

        driver.open(url)

        // 1) Use the page's search box (enter text and submit)
        var history = agent.run("goto https://www.amazon.com/dp/B08PP5MSVB , search for 'calabi-yau' and submit the form")
        result("action result", history.finalResult)

        agent.clearHistory()
        history = agent.run("goto https://en.cppreference.com/index.html , extract top 20 articles' titles from the main list")
        result("action result", history.finalResult)

        agent.processTrace.forEach { println("""🚩$it""") }
    }

    private fun result(label: String, value: Any?) {
        val text = Strings.compactInline(value?.toString(), 2000)

        val e = """🟢"""
        printlnPro("$e [RESULT ${stepNo}] $label => $text")
    }
}

suspend fun main() = Acts().run()
