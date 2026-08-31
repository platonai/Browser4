package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.chrome.network.NetworkObserver
import ai.platon.pulsar.chrome.network.RouteManager
import ai.platon.pulsar.common.getLogger
import kotlin.reflect.KClass

class BrowserToolExecutor : AbstractToolExecutor() {
    private val logger = getLogger(this)

    override val domain = "browser"

    override val receiverClass: KClass<*> = Browser::class

    init {
        toolSpec["switchTab"] = ToolSpec(
            domain = domain,
            method = "switchTab",
            arguments = listOf(
                ToolSpec.Arg("index", "Int", null),
                ToolSpec.Arg("tabId", "String", null)
            ),
            returnType = "WebDriver",
            description = "Switch to a specific browser tab by its zero-based index or GUID"
        )
        toolSpec["newTab"] = ToolSpec(
            domain = domain,
            method = "newTab",
            arguments = listOf(ToolSpec.Arg("url", "String", "about:blank")),
            returnType = "Map<String, String>",
            description = "Create a new tab. Returns guid and url"
        )
        toolSpec["closeTab"] = ToolSpec(
            domain = domain,
            method = "closeTab",
            arguments = listOf(
                ToolSpec.Arg("index", "Int", null),
                ToolSpec.Arg("tabId", "String", null)
            ),
            returnType = "Boolean",
            description = "Close a tab by zero-based index or GUID, or the current tab when omitted"
        )
        toolSpec["listTabs"] = ToolSpec(
            domain = domain,
            method = "listTabs",
            arguments = emptyList(),
            returnType = "List<Map<String, String>>",
            description = "List all tabs with index, guid, title, and url"
        )
    }

    /**
     * Execute browser.* expressions against a Browser target using named args.
     */
    @Suppress("UNUSED_PARAMETER")
    @Throws(IllegalArgumentException::class)
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        require(domain == this.domain) { "Unsupported domain: $domain" }
        require(functionName.isNotBlank()) { "Function name must not be blank" }
        val browser =
            requireNotNull(receiver as AbstractBrowser) { "Target must be Browser" }

        return when (functionName) {
            "switchTab" -> {
                val driver = resolveTabDriver(browser, args, functionName, allowCurrentTab = false)
                try {
                    driver.bringToFront()
                } catch (e: Exception) {
                    // Page.bringToFront can fail (e.g. while the previously
                    // front tab's window is still being torn down).  The switch
                    // is still what the caller asked for, so record it on the
                    // browser regardless of the CDP outcome.
                    logger.warn("! bringToFront failed for tab {}; recording switch anyway", driver.guid, e)
                }
                // Set explicitly: upstream only sets frontDriver after the CDP
                // round-trip succeeds, so a swallowed failure leaves it stale.
                browser.frontDriver = driver
                // The CDP activation may return before the browser has fully
                // committed the tab switch.  A short delay gives the rendering
                // pipeline time to settle so that a subsequent evaluate/call
                // targets the correct page.
                kotlinx.coroutines.delay(200)
                logger.info("""👀 Switched to tab {}""", driver.guid)
                // Return the resolved GUID so AgentToolManager binds the SAME
                // driver it resolved here.  The WebDriver itself is not
                // serializable (AbstractToolExecutor wraps it in a description
                // map that loses the identity), and re-resolving from `index`
                // later can hit a different listDrivers() order, binding the
                // wrong tab (ConcurrentHashMap iteration order is unstable).
                mapOf("guid" to driver.guid)
            }

            "newTab" -> {
                val url = paramString(args, "url", functionName) ?: "about:blank"
                val driver = browser.newDriver()
                // Claim the CDP event-listener slots for the new tab BEFORE
                // the first navigation: the base library's NetworkManager
                // registers its listeners on navigation and its event
                // dispatcher keeps only ONE listener per event key, so a
                // listener registered afterwards would silently never fire.
                if (driver is PulsarWebDriver) {
                    NetworkObserver.forProtocol(driver.browserProtocol).preRegister()
                    RouteManager.forProtocol(driver.browserProtocol).preRegister()
                }
                // call navigate so JavaScript injection works
                driver.navigate(url)
                mapOf("guid" to driver.guid, "url" to driver.currentUrl())
            }

            "closeTab" -> {
                val driver = resolveTabDriver(browser, args, functionName, allowCurrentTab = true)
                val guid = driver.guid
                browser.destroyDriver(driver)
                // destroyDriver swallows CDP close failures (runCatching around
                // closeMe), so a failed close would otherwise report success
                // while every tab stays open.  Verify the tab is actually gone
                // and surface the failure (AGENTS.md: no silent failures).
                val stillOpen = browser.listDrivers().any { it.guid == guid }
                if (stillOpen) {
                    throw IllegalStateException(
                        "Failed to close tab '$guid': the tab is still open after destroyDriver"
                    )
                }
                true
            }

            "listTabs" -> {
                val frontGuid = (browser.frontDriver as? AbstractWebDriver)?.guid
                browser.listDrivers().mapIndexed { i, driver ->
                    mapOf(
                        "index" to i.toString(),
                        "guid" to driver.guid,
                        "title" to driver.title(),
                        "url" to driver.currentUrl(),
                        "active" to (driver.guid == frontGuid).toString()
                    )
                }
            }

            else -> throw IllegalArgumentException("Unsupported browser method: $functionName(${args.keys})")
        }
    }

    private suspend fun resolveTabDriver(
        browser: AbstractBrowser,
        args: Map<String, Any?>,
        functionName: String,
        allowCurrentTab: Boolean,
    ): AbstractWebDriver {
        val index = parseTabIndex(args, functionName)
        if (index != null) {
            require(index >= 0) { "Tab index must be non-negative for $functionName" }
            val drivers = browser.listDrivers().filterIsInstance<AbstractWebDriver>()
            return drivers.getOrNull(index)
                ?: throw IllegalArgumentException("Tab index '$index' out of range; found ${drivers.size} tabs")
        }

        val tabId = args["tabId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        if (tabId != null) {
            return browser.findDriverByGUID(tabId)
                ?: throw IllegalArgumentException("Tab '$tabId' not found")
        }

        if (allowCurrentTab) {
            // frontDriver is not cleared by destroyDriver and is only updated
            // after bringToFront's CDP round-trip, so it can dangle after the
            // previously active tab was closed.  Destroying a dangling driver
            // is a silent no-op that leaves every tab open; only accept the
            // front driver when it is still a live driver of this browser.
            val front = (browser.frontDriver as? AbstractWebDriver)
                ?.takeIf { browser.drivers.containsKey(it.guid) }
            return front
                ?: browser.listDrivers().filterIsInstance<AbstractWebDriver>().firstOrNull()
                ?: throw IllegalArgumentException("No browser tabs are currently open")
        }

        throw IllegalArgumentException("Missing parameter 'index' for $functionName")
    }

    private fun parseTabIndex(args: Map<String, Any?>, functionName: String): Int? {
        val raw = args["index"] ?: return null
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid tab index '$raw' for $functionName")
            else -> throw IllegalArgumentException("Invalid tab index '$raw' for $functionName")
        }
    }
}
