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
tab.type(text: String, selector: String? = null)
tab.press(key: String, selector: String? = null)
tab.check(selector: String)
tab.uncheck(selector: String)
tab.scrollTo(selector: String)
tab.scrollToMiddle(ratio: Double = 0.5)          // ratio: The ratio of the page to scroll to, 0.0 means the top, 1.0 means the bottom.
tab.scrollBy(pixels: Double = 200.0): Double
tab.ariaSnapshot(viewports: String = "all", boxes: Boolean = true)      // Returns the accessibility tree. viewports: "all", "3", "1,3,5", "2-4". boxes: include bounding boxes as [box=x,y,w,h] (on by default)
tab.textContent(): String?                            // Returns the document's text content.
tab.selectFirstTextOrNull(selector: String): String?  // Returns the first node's text content (descendants included). Returns null if no node.
tab.eval(expression: String)
tab.eval(expression: String, selector: String)
tab.delay(millis: Long)

// domain: browser
browser.switchTab(tabId: String): Int
browser.closeTab(tabId: String)
browser.newTab(url: String = "about:blank"): Map<String, String>
browser.listTabs(): List<Map<String, String>>

// domain: agent
agent.extract(instruction: String, schema: String): String // Extract data with given JSON schema
agent.summarize(instruction: String?, selector: String?): String // Extract textContent and generate a summary
agent.observe(instruction: String): String                 // observe the current page following the instruction

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
     * The set of domain names that already have their tool specs hardcoded in
     * [TOOL_CALL_SPECIFICATION].  Used by [ToolCallSpecificationRenderer] to decide
     * which dynamically-registered domain specs are supplementary vs. duplicates.
     *
     * NOTE: the legacy `fs` domain (fs.writeString etc.) is intentionally NOT
     * listed/advertised here — its executor is deprecated and unregistered, so
     * advertising it only makes agents attempt calls that fail with
     * "Unsupported receiver class".  Agents must use the `coding.*` tools
     * (coding.read/write/append/replace/listDir/glob/grep/...) instead, which
     * operate on the configurable coding workspace.
     */
    val BUILTIN_DOMAINS_IN_SPEC: Set<String> = setOf("tab", "browser", "agent", "system")

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
        // A null/blank domain means "no tool call was attempted" (e.g. the model returned
        // plain text, or the previous state had no action at all). That cannot be a browser
        // interaction: it does not change the page, must not count as a no-op, and does not
        // require a fresh screenshot. Callers that need the "first step / unknown domain"
        // safety (e.g. initial screenshot) must handle it explicitly.
        if (domain.isNullOrBlank()) return false
        return BROWSER_INTERACTION_DOMAINS.contains(domain.lowercase())
    }
}
