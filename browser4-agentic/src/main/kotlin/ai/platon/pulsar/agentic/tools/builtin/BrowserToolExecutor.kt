package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.model.ToolExample
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.api.AbstractBrowser
import ai.platon.pulsar.api.AbstractWebDriver
import ai.platon.pulsar.api.Browser
import ai.platon.pulsar.api.model.WebDriverException
import ai.platon.pulsar.chrome.PulsarBrowser
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
            returnType = "List<Map<String, String>>",
            description = "List all tabs with index, guid, title, and url",
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
                val guid = driver.guid
                browser.destroyDriver(driver)
                // destroyDriver swallows CDP close failures (runCatching around
                // closeMe), so a failed close would otherwise report success
                // while every tab stays open.  Verify the tab is actually gone
                // and surface the failure (AGENTS.md: no silent failures).
                //
                // The verification waits out the CDP teardown: Target.closeTarget
                // resolves before the browser has dropped the target, so a single
                // immediate check intermittently finds the closing tab still in
                // the browser's tab list (observed as a flaky closeTab failure in
                // CI).  A tab that is genuinely still open never disappears, so
                // the throw below still fires for real failures.
                if (!awaitTabClosed(browser, guid)) {
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

    /**
     * Wait for [guid] to disappear from the browser's live tab list.
     *
     * `Target.closeTarget` resolves before the browser has finished tearing the
     * target down, so an immediate single check can still see the closing tab.
     * Polls for a short bounded window ([TAB_CLOSE_GRACE_MILLIS]) and reports a
     * failure only when the browser still lists the tab at the end of it.
     */
    private suspend fun awaitTabClosed(browser: AbstractBrowser, guid: String): Boolean =
        awaitTabGone(TAB_CLOSE_GRACE_MILLIS, TAB_CLOSE_POLL_MILLIS) { isTabListed(browser, guid) }

    /**
     * Poll [isListed] until it stops reporting the tab, up to [graceMillis].
     *
     * Returns `true` as soon as the tab is gone, `false` only when the last
     * observation inside the window still lists it — a tab that stayed listed
     * for the whole grace window was never closed.
     */
    internal suspend fun awaitTabGone(
        graceMillis: Long,
        pollMillis: Long,
        isListed: () -> Boolean?,
    ): Boolean {
        val deadline = System.currentTimeMillis() + graceMillis
        while (true) {
            val listed = isListed()
            if (listed == false) return true
            // `null` means the tab list could not be read (the browser itself is
            // going away), which is no evidence of an open tab: keep watching
            // and only report a failure from a positive sighting.
            if (System.currentTimeMillis() >= deadline) return listed != true
            kotlinx.coroutines.delay(pollMillis)
        }
    }

    /**
     * Whether the browser still has a tab with [guid], or `null` when that
     * cannot be determined.
     *
     * Evidence comes from two reads that both leave the browser untouched: the
     * driver registry (which `destroyDriver` clears synchronously) and the
     * browser's own tab list (`GET /json/list` on the CDP endpoint).
     * `listDrivers()` must NOT be used here: it runs `recoverUnmanagedPages()`,
     * which re-registers a tab the browser has not dropped yet as a brand new
     * driver.  Nothing removes such a recovered driver before `pageLoadTimeout`
     * (minutes), so the phantom entry would keep reporting the tab as open and
     * turn every close that races the teardown into a failure — the very
     * failure this verification exists to outlive.
     */
    private fun isTabListed(browser: AbstractBrowser, guid: String): Boolean? {
        // The close must at least have dropped the driver it was given.
        if (browser.drivers.containsKey(guid)) return true
        val pulsarBrowser = browser as? PulsarBrowser ?: return false
        return try {
            pulsarBrowser.listTabs().any { it.id == guid }
        } catch (e: WebDriverException) {
            logger.warn("Failed to read the browser tab list while verifying tab {} | {}", guid, e.message)
            null
        }
    }

    private companion object {
        /** How long `closeTab` waits for the CDP target teardown to land. */
        const val TAB_CLOSE_GRACE_MILLIS = 2_000L

        /** Interval between live-tab checks inside the grace window. */
        const val TAB_CLOSE_POLL_MILLIS = 100L
    }
}
