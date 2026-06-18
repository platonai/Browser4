package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.browser.AbstractBrowser
import ai.platon.pulsar.browser.AbstractWebDriver
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.core.api.Browser
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
            description = "Create a new tab. Returns guid (string), id (int), and url"
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
                driver.bringToFront()
                logger.info("""👀 Switched to tab {} (guid={}, id={})""", args["index"] ?: args["tabId"] ?: driver.guid, driver.guid, driver.id)
                driver
            }

            "newTab" -> {
                val url = paramString(args, "url", functionName) ?: "about:blank"
                val driver = browser.newDriver(url)
                mapOf("guid" to driver.guid, "id" to driver.id.toString(), "url" to driver.currentUrl())
            }

            "closeTab" -> {
                val driver = resolveTabDriver(browser, args, functionName, allowCurrentTab = true)
                browser.destroyDriver(driver)
                true
            }

            "listTabs" -> {
                browser.listDrivers().mapIndexed { i, driver ->
                    mapOf(
                        "index" to i.toString(),
                        "guid" to driver.guid,
                        "title" to driver.title(),
                        "url" to driver.currentUrl()
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
            return (browser.findDriverByGUID(tabId)
                ?: tabId.toIntOrNull()?.let { browser.findDriverById(it) })
                ?: throw IllegalArgumentException("Tab '$tabId' not found")
        }

        if (allowCurrentTab) {
            return (browser.frontDriver as? AbstractWebDriver)
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
