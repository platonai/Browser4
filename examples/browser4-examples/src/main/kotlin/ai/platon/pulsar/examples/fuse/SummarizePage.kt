package ai.platon.pulsar.examples.fuse

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.test.server.DemoSiteStarter

class SummarizePage {
    private val logger = getLogger(this)

    private var stepNo = 0
    private val session = AgenticContexts.getOrCreateSession()

    suspend fun run() {
        // Use local mock site instead of external site so actions are deterministic.
        val url = "http://localhost:18080/generated/tta/act/act-demo.html"
        // one more short wait after potential start (shorter, less verbose)
        val starter = DemoSiteStarter()
        starter.start(url)
        session.registerClosable(starter)

        val agent = session.companionAgent

        step("Open URL: $url")
        val page = session.open(url)
        result("page", page)

        step("Action: Extract the full text of the webpage")
        var result = agent.act("Extract the full text of the webpage")
        result("action result", result)

        step("Action: Summarize the full text of the webpage")
        val response = session.chat("Summarize the full text of the webpage: \n\n\n" + result.tcEvalValue)
        result("action result", response)

        AgenticContexts.close()
    }

    private fun step(label: String) = logger.info("[STEP ${++stepNo}] $label")

    private fun result(label: String, value: Any?) {
        val text = Strings.compactInline(value?.toString(), 2000)

        val e = """🟢"""
        printlnPro("$e [RESULT ${stepNo}] $label => $text")
    }
}

suspend fun main() = SummarizePage().run()
