// kotlin
package ai.platon.pulsar.examples.fuse

import ai.platon.pulsar.agentic.context.AgenticContexts
import ai.platon.pulsar.common.Strings
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.test.server.MockSiteStarter

class FusedActs {

    private val log = getLogger(this)

    private var step = 0

    private val session = AgenticContexts.getOrCreateSession()

    suspend fun run() {
        // Use local mock site instead of external site so actions are deterministic.
        val url = "http://localhost:18080/generated/tta/act/act-demo.html"
        // one more short wait after potential start (shorter, less verbose)
        val starter = MockSiteStarter()
        starter.start(url)
        session.registerClosable(starter)

        val agent = session.companionAgent
        val driver = session.getOrCreateBoundDriver()

        log.info("[STEP ${++step}] Open URL: $url")
        var page = session.open(url)
        result("page", page)

        log.info("[STEP ${++step}] Parse the page into a lightweight local DOM")
        var document = session.parse(page)
        result("document", document)

        log.info("[STEP ${++step}] Extract fields (title) with CSS selector (no LLM required)")
        var fields = session.extract(document, mapOf("title" to "#title"))
        result("fields", fields)

        // Basic action examples (natural language instructions) - now operate on local mock page
        log.info("[STEP ${++step}] Action: search for 'browser'")
        var history = agent.run("search for 'browser' (RESULTS will display in the same page)")
        result("action history", history.finalResult)

        log.info("[STEP ${++step}] Capture body text in the live DOM after search (snippet)")
        var content = driver.selectFirstTextOrNull("body")
        result("body snippet", content?.take(160))

        log.info("[STEP ${++step}] Action: click the 3rd link")
        history = agent.run("click the 3rd link")
        result("action history", history.finalResult)

        log.info("[STEP ${++step}] Capture body text in the live DOM after clicking 3rd link (snippet)")
        content = driver.selectFirstTextOrNull("body")
        result("body snippet", content?.take(160))

        log.info("[STEP ${++step}] Action: go back")
        history = agent.run("go back")
        result("action history", history.finalResult)

        log.info("[STEP ${++step}] Action: open the 4th link in new tab")
        history = agent.run("open the 4th link in new tab")
        result("action history", history.finalResult)

        log.info("[STEP ${++step}] Action: switch to the newly open tab")
        history = agent.run("switch to the newly open tab")
        result("action history", history.finalResult)

        // More typical session.act() examples

        // 1) Use the page's search box (enter text and submit)
        log.info("[STEP ${++step}] Action: find the search box, type 'web scraping' and submit the form")
        history = agent.run("find the search box, type 'web scraping' and submit the form (RESULTS will display in the same page)")
        result("action history", history.finalResult)

        log.info("[STEP ${++step}] Captures the live page and parse after search form submission")
        page = session.capture(driver)
        result("page", page)
        document = session.parse(page)
        result("document", document)

        log.info("[STEP ${++step}] Extract title after search form submission")
        fields = session.extract(document, mapOf("title" to "#title"))
        result("fields", fields)

        // 2) Click a link by visible text (Show/Ask HN like titles in mock page)
        log.info("[STEP ${++step}] Action: click the first link that contains 'Show HN' or 'Ask HN'")
        history = agent.run("click the first link that contains 'Show HN' or 'Ask HN'")
        result("action history", history.finalResult)

        log.info("[STEP ${++step}] Capture body text after clicking Show/Ask HN link (snippet)")
        content = driver.selectFirstTextOrNull("body")
        result("body snippet", content?.take(160))

        // 3) Scroll to bottom (triggers infinite scroll loading extra items on mock page)
        log.info("[STEP ${++step}] Action: scroll to the bottom of the page and wait for new content to load")
        agent.clearHistory()
        history = agent.run("scroll to the bottom of the page and wait for new content to load")
        result("action history", history.finalResult)

        // 4) Open the first comment thread
        log.info("[STEP ${++step}] Action: search for 'calabi-yau' on amazon")
        agent.clearHistory()
        history = agent.run("goto https://www.amazon.com/dp/B08PP5MSVB , search for 'calabi-yau' and submit the form")
        result("action history", history.finalResult)

        // 5) Navigate back
        log.info("[STEP ${++step}] Action: navigate back")
        history = agent.run("navigate back")
        result("action history", history.finalResult)

        // 5b) Navigate forward
        log.info("[STEP ${++step}] Action: navigate forward")
        history = agent.run("navigate forward")
        result("action history", history.finalResult)

        // 7) Extract specific data after interactions
        log.info("[STEP ${++step}] Action: extract article titles and their hrefs from the main list")
        agent.clearHistory()
        history = agent.run("goto https://en.cppreference.com/index.html , extract first 10 articles with there titles and hrefs from the main list")
        result("action history", history.finalResult)

        // 5) Navigate back
        log.info("[STEP ${++step}] Action: navigate back")
        history = agent.run("navigate back")
        result("action history", history.finalResult)

        log.info("[STEP ${++step}] Captures the live page as a local copy and parse for titles")
        page = session.capture(driver)
        document = session.parse(page)
        fields = session.extract(document, mapOf("titles" to ".athing .title a"))
        result("fields", fields)

        // add more action examples here

        log.info("[STEP ${++step}] Re-open the original URL and re-parse")
        page = session.open(url)
        document = session.parse(page)
        fields = session.extract(document, mapOf("title" to "#title"))
        result("fields", fields)

        // Print final values so variables are referenced (avoid unused warnings in IDE/build)
        log.info("[STEP ${++step}] Summary outputs")
        log.info("Final extracted fields keys: ${fields.keys}")
        log.info("Sample page content snippet: ${content?.take(120)}")
        log.info("Last action result: $agent")

        agent.processTrace.forEach { println("""🚩$it""") }

        session.context.close()
    }

    private fun result(label: String, value: Any?) {
        val text = Strings.compactInline(value?.toString(), 2000)

        val e = """🟢"""
        printlnPro("$e [RESULT ${step}] $label => $text")
    }
}

suspend fun main() = FusedActs().run()
