package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractBrowser
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.Browser
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
                ToolSpec.Arg("tabId", "String", null)
            ),
            returnType = "WebDriver",
            description = "Switch to a specific browser tab by its ID"
        )
        toolSpec["newTab"] = ToolSpec(
            domain = domain,
            method = "newTab",
            arguments = listOf(ToolSpec.Arg("url", "String", "about:blank")),
            returnType = "Map<String, String>",
            description = "Create a new tab"
        )
        toolSpec["closeTab"] = ToolSpec(
            domain = domain,
            method = "closeTab",
            arguments = listOf(ToolSpec.Arg("tabId", "String", null)),
            returnType = "Boolean",
            description = "Close a tab"
        )
        toolSpec["listTabs"] = ToolSpec(
            domain = domain,
            method = "listTabs",
            arguments = emptyList(),
            returnType = "List<Map<String, String>>",
            description = "List all tabs"
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
        val browser = requireNotNull(receiver as AbstractBrowser) { "Target must be Browser" }

        return when (functionName) {
            "switchTab" -> {
                validateArgs(args, allowed = setOf("tabId"), required = setOf("tabId"), functionName)
                val tabId = paramString(args, "tabId", functionName)!!
                val driver = tabId.toIntOrNull()?.let { browser.findDriverById(it) } ?: browser.drivers[tabId]
                if (driver == null || driver !is AbstractWebDriver) {
                    throw IllegalArgumentException("Tab '$tabId' not found")
                }
                driver.bringToFront()
                logger.info("""👀 Switched to tab {} (driver {}/{})""", tabId, driver.id, driver.guid)
                driver
            }
            "newTab" -> {
                val url = paramString(args, "url", functionName) ?: "about:blank"
                val driver = browser.newDriver(url)
                mapOf("id" to driver.id.toString(), "url" to driver.currentUrl())
            }
            "closeTab" -> {
                val tabId = paramString(args, "tabId", functionName)!!
                val driver = tabId.toIntOrNull()?.let { browser.findDriverById(it) } ?: browser.drivers[tabId]
                if (driver != null) {
                    browser.destroyDriver(driver)
                    true
                } else {
                    false
                }
            }
            "listTabs" -> {
                browser.listDrivers().map { driver ->
                    mapOf(
                        "id" to driver.id.toString(),
                        "guid" to driver.guid,
                        "title" to driver.title(),
                        "url" to driver.currentUrl()
                    )
                }
            }

            else -> throw IllegalArgumentException("Unsupported browser method: $functionName(${args.keys})")
        }
    }
}
