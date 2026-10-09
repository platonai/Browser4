package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.chrome.PulsarWebDriver
import ai.platon.pulsar.chrome.network.NetworkObserver
import ai.platon.pulsar.chrome.network.RouteManager
import ai.platon.pulsar.common.getLogger
import kotlin.reflect.KClass

class BrowserToolExecutor(
    // Timing for the close-verify loop. Production uses the defaults; tests
    // shrink the budget so the "tab never closes" path stays fast.
    private val closeVerifyPollMs: Long = 150L,
    private val closeVerifyPollsPerAttempt: Int = 4,
    private val closeMaxAttempts: Int = 3,
) : AbstractToolExecutor() {
    private val logger = getLogger(this)

    override val domain = "browser"

    override val receiverClass: KClass<*> = Browser::class

    init {
        toolSpec["switchTab"] = ToolSpec(
            domain = domain,
            method = "switchTab",
            arguments = listOf(
                ToolSpec.Arg("index", "Int?", "null", "Zero-based tab index."),
                ToolSpec.Arg("tabId", "String?", "null", "Tab GUID; give either this or index."),
            ),
            returnType = "WebDriver",
            description = "Switch to a specific browser tab by its zero-based index or GUID",
            examples = listOf(
                ToolExample(title = "Switch to the second tab", args = mapOf("index" to "1")),
            ),
        )
        toolSpec["newTab"] = ToolSpec(
            domain = domain,
            method = "newTab",
            arguments = listOf(ToolSpec.Arg("url", "String", "about:blank")),
            returnType = "Map<String, String>",
            description = "Create a new tab. Returns guid and url",
            examples = listOf(
                ToolExample(title = "Open a new tab on a page", args = mapOf("url" to "https://example.com")),
            ),
        )
        toolSpec["closeTab"] = ToolSpec(
            domain = domain,
            method = "closeTab",
            arguments = listOf(
                ToolSpec.Arg("index", "Int?", "null", "Zero-based tab index."),
                ToolSpec.Arg("tabId", "String?", "null", "Tab GUID; give either this or index."),
            ),
            returnType = "Boolean",
            description = "Close a tab by zero-based index or GUID, or the current tab when omitted",
            examples = listOf(
                ToolExample(title = "Close the second tab", args = mapOf("index" to "1")),
            ),
        )
        toolSpec["listTabs"] = ToolSpec(
            domain = domain,
            method = "listTabs",
            arguments = emptyList(),
            returnType = "List<Map<String, Any>>",
            description = "List all tabs with index, guid, title, url, and a boolean active marker",
            examples = listOf(ToolExample(title = "List every tab", runnable = true)),
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
                // The GUID is captured before the close: the index shifts once
                // the tab is gone and the driver object is replaced if page
                // recovery resurrects the tab (see destroyAndVerifyClosed).
                val targetGuid = (driver as? AbstractWebDriver)?.guid
                destroyAndVerifyClosed(browser, driver, targetGuid, functionName)
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
                        // Emit a real Boolean so JSON consumers parse `active` without
                        // string-to-bool coercion.
                        "active" to (driver.guid == frontGuid)
                    )
                }
            }

            else -> throw IllegalArgumentException("Unsupported browser method: $functionName(${args.keys})")
        }
    }

    /**
     * Destroy [driver] and verify that its tab actually disappears.
     *
     * The base browser library removes the driver from its map first and only
     * then sends `Target.closeTarget` over the browser-level web socket — that
     * send is fire-and-forget (transport failures are swallowed and logged at
     * debug level). The very next tab listing triggers unmanaged-page recovery,
     * which re-creates a driver for every tab Chrome still lists: when the
     * close CDP call has not been processed yet (CI under load, a busy browser
     * web socket), the just-closed tab is resurrected permanently and the
     * caller is told the close succeeded while the tab survives.
     *
     * To close that race the removal is verified by GUID; when the tab
     * resurfaces, recovery built a new driver object for the same GUID, so it
     * is re-resolved and destroyed again. A tab that survives every attempt
     * fails loudly instead of reporting a phantom success.
     */
    private suspend fun destroyAndVerifyClosed(
        browser: AbstractBrowser,
        initial: AbstractWebDriver,
        targetGuid: String?,
        functionName: String,
    ) {
        browser.destroyDriver(initial)

        // Without a GUID the close cannot be verified by identity — trust the
        // call rather than guessing from the shifted index.
        if (targetGuid.isNullOrEmpty()) {
            return
        }

        var current: AbstractWebDriver = initial
        repeat(closeMaxAttempts) { attempt ->
            // Poll within this attempt's window — Chrome usually commits the
            // close within the first one or two checks.
            repeat(closeVerifyPollsPerAttempt) { poll ->
                val survivors = browser.listDrivers().filterIsInstance<AbstractWebDriver>()
                if (survivors.none { it.guid == targetGuid }) {
                    return
                }
                if (poll < closeVerifyPollsPerAttempt - 1) {
                    kotlinx.coroutines.delay(closeVerifyPollMs)
                }
            }

            // The tab is still listed. Recovery may have re-created its driver
            // object; re-resolve by GUID (the lookup itself runs recovery) and
            // drive the close again.
            val resurrected = browser.findDriverByGUID(targetGuid)
            if (resurrected == null) {
                // Gone between the last listing and the lookup — closed.
                return
            }
            if (attempt < closeMaxAttempts - 1) {
                logger.warn(
                    "! Tab {} survived close attempt {}/{} — retrying",
                    targetGuid, attempt + 1, closeMaxAttempts
                )
                current = resurrected
                browser.destroyDriver(current)
            } else {
                throw IllegalStateException(
                    "$functionName: tab '$targetGuid' still exists after $closeMaxAttempts " +
                        "close attempts; the browser did not commit the close"
                )
            }
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

