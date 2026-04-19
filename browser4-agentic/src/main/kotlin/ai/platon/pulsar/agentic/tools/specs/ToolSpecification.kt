package ai.platon.pulsar.agentic.tools.specs

object ToolSpecification {

    /**
     * The `TOOL_CALL_SPECIFICATION` is written using kotlin syntax to express the tool's `domain`, `method`, `arguments`.
     * */
    const val TOOL_CALL_SPECIFICATION = """
// domain: tab
tab.navigate(url: String)
tab.reload()
tab.goBack()
tab.goForward()
tab.waitForSelector(selector: String, timeoutMillis: Long = 3000)
tab.exists(selector: String): Boolean
tab.isVisible(selector: String): Boolean
tab.focus(selector: String)
tab.hover(selector: String)
tab.click(selector: String)                         // focus on an element with [selector] and click it
tab.click(selector: String, modifier: String)       // focus on an element with [selector] and click it with modifier pressed
tab.fill(selector: String, text: String)
tab.type(selector: String, text: String)
tab.press(selector: String, key: String)
tab.check(selector: String)
tab.uncheck(selector: String)
tab.scrollTo(selector: String)
tab.scrollToMiddle(ratio: Double = 0.5)          // ratio: The ratio of the page to scroll to, 0.0 means the top, 1.0 means the bottom.
tab.scrollBy(pixels: Double = 200.0): Double
tab.ariaSnapshot(viewports: String = "all")      // Returns the accessibility tree. viewports: "all", "3", "1,3,5", "2-4"
tab.textContent(): String?                            // Returns the document's text content.
tab.selectFirstTextOrNull(selector: String): String?  // Returns the first node's text content (descendants included). Returns null if no node.
tab.delay(millis: Long)

// domain: browser
browser.switchTab(tabId: String): Int
browser.closeTab(tabId: String)

// domain: fs
fs.writeString(filename: String, content: String)
fs.readString(filename: String): String
fs.append(filename: String, content: String)
fs.replaceContent(filename: String, oldStr: String, newStr: String): String
fs.fileExists(filename: String): String
fs.getFileInfo(filename: String): String
fs.deleteFile(filename: String): String
fs.copyFile(source: String, dest: String): String
fs.moveFile(source: String, dest: String): String
fs.listFiles(): String

// domain: agent
agent.extract(instruction: String, schema: String): String // Extract data with given JSON schema
agent.summarize(instruction: String?, selector: String?): String // Extract textContent and generate a summary

// domain: system
system.help(domain: String): String                        // get help for tool calls in a domain
system.help(domain: String, method: String): String        // get help for a tool call

    """

    val SUPPORTED_TOOL_CALLS = TOOL_CALL_SPECIFICATION
        .split("\n").asSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("//") }
        .filter { it.contains("(") }
        .toList()

    val SUPPORTED_ACTIONS = SUPPORTED_TOOL_CALLS.map { it.substringBefore("(").trim() }

    val MAY_NAVIGATE_ACTIONS = setOf("navigate", "click", "reload", "goBack", "goForward")

    /**
     * Domains whose actions directly interact with the browser page and may change its visual state.
     * Used to decide whether screenshots and DOM snapshots are necessary, and whether
     * page-state diff comparisons are meaningful for no-op detection.
     */
    val BROWSER_INTERACTION_DOMAINS = setOf("tab", "browser")

    /**
     * Returns `true` if the given [domain] represents a browser-interaction action
     * that may change the visible page state (e.g., clicking, navigating, switching tabs).
     *
     * Non-browser-interaction domains (e.g., `fs`, `agent`, `system`) do not alter the
     * webpage and therefore do not require fresh screenshots or page-state comparisons.
     */
    fun isBrowserInteraction(domain: String?): Boolean {
        // Default to true for safety: ensures screenshots are captured when the domain is
        // unknown or on the first step where no previous action exists.
        if (domain.isNullOrBlank()) return true
        return BROWSER_INTERACTION_DOMAINS.contains(domain.lowercase())
    }
}
