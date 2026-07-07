package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.browser4.chrome.dom.model.AriaSnapshotOptions
import ai.platon.pulsar.agentic.model.ToolSpec
import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import ai.platon.pulsar.browser.common.NavigateEntry
import ai.platon.pulsar.core.api.WebDriver
import kotlinx.coroutines.delay
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BrowserTabToolExecutor : AbstractToolExecutor() {
    companion object {
        private const val MIN_READ_INTERVAL_MILLIS = 50L // 50 milliseconds is enough for a DOM to update the state
        private const val READ_ACTIONS_WHITELIST_PROPERTY = "browser4.tab.read.actions.whitelist"
        private const val READ_ACTIONS_WHITELIST_ENV = "BROWSER4_TAB_READ_ACTIONS_WHITELIST"
        private val logger: Logger = Logger.getLogger(BrowserTabToolExecutor::class.java.name)
        // Actions that read page state and can become flaky if executed too soon after mutations/navigation.
        private val DEFAULT_READ_PAGE_STATE_ACTIONS = setOf(
            "waitForSelector", "waitForNavigation", "waitForPage",
            "exists", "isVisible", "visible", "isHidden", "isChecked",
            "ariaSnapshot", "title", "screenshot",
            "outerHTML", "textContent", "nanoDOMTree",
            "selectFirstTextOrNull", "selectTextAll",
            "selectFirstAttributeOrNull", "selectAttributes", "selectAttributeAll",
            "selectFirstPropertyValueOrNull", "selectPropertyValueAll",
            "clickablePoint", "boundingBox",
            "getCookies", "saveStorageState",
            "currentUrl", "url", "documentURI", "baseURI", "referrer", "pageSource"
        )

        private val READ_PAGE_STATE_ACTIONS = resolveReadPageStateActions()

        // Commands that may change DOM/content and can make immediate reads flaky.
        private val DOM_AFFECTING_ACTIONS = setOf(
            "open", "navigate", "reload", "goBack", "goForward",
            "focus", "hover", "type", "fill", "press", "click", "dblclick",
            "upload", "selectOption", "dialogAccept", "dialogDismiss",
            "keydown", "keyDown", "keyup", "keyUp",
            "mousedown", "mouseDown", "mouseup", "mouseUp",
            "mouseWheel", "mouseWheelDown", "mouseWheelUp",
            "scrollDown", "scrollUp", "scrollBy", "scrollTo", "scrollToTop", "scrollToBottom",
            "scrollToMiddle", "scrollToViewport",
            "dragAndDrop", "drag", "clickTextMatches", "clickMatches",
            "check", "uncheck", "setAttribute", "setAttributeAll", "setProperty", "setPropertyAll",
            "evaluate", "evaluateDetail", "eval", "evaluateValue", "evaluateValueDetail",
            "loadStorageState"
        )

        // Actions that may trigger page navigation (form submission, link clicks, history traversal, etc.).
        // After these execute, the executor checks whether the page started navigating and waits for the
        // DOM to settle before returning — preventing the CLI's post_command_snapshot from capturing an
        // empty/partial page while navigation is still in flight.
        private val NAVIGATION_TRIGGERING_ACTIONS = setOf(
            "press", "click", "dblclick", "goBack", "goForward", "reload"
        )

        private const val NAVIGATION_POLL_TIMEOUT_MS = 30_000L
        private const val NAVIGATION_DOM_READY_TIMEOUT_MS = 10_000L
        private const val NAVIGATION_DOM_SETTLE_DELAY_MS = 1_000L

        private fun resolveReadPageStateActions(): Set<String> {
            val configuredValue = System.getProperty(READ_ACTIONS_WHITELIST_PROPERTY)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: System.getenv(READ_ACTIONS_WHITELIST_ENV)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return DEFAULT_READ_PAGE_STATE_ACTIONS

            val configuredActions = configuredValue
                .split(',', ';', ' ', '\n', '\r', '\t')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()

            if (configuredActions.isEmpty()) {
                return DEFAULT_READ_PAGE_STATE_ACTIONS
            }

            val unknownActions = configuredActions - DEFAULT_READ_PAGE_STATE_ACTIONS
            if (unknownActions.isNotEmpty()) {
                logger.warning(
                    "Ignoring unknown read-action names from whitelist config: ${unknownActions.joinToString(",")}" +
                            " (supported: ${DEFAULT_READ_PAGE_STATE_ACTIONS.joinToString(",")})"
                )
            }

            val effectiveActions = configuredActions intersect DEFAULT_READ_PAGE_STATE_ACTIONS
            if (effectiveActions.isEmpty()) {
                logger.warning(
                    "Read-action whitelist config contains no supported actions, fallback to defaults. " +
                            "property=$READ_ACTIONS_WHITELIST_PROPERTY env=$READ_ACTIONS_WHITELIST_ENV"
                )
                return DEFAULT_READ_PAGE_STATE_ACTIONS
            }

            logger.info(
                "Configured read-action whitelist: ${effectiveActions.joinToString(",")}" +
                        " (property=$READ_ACTIONS_WHITELIST_PROPERTY env=$READ_ACTIONS_WHITELIST_ENV)"
            )
            return effectiveActions
        }
    }

    override val domain = "tab"

    override val receiverClass: KClass<*> = WebDriver::class

    private val lastActionAtMillis = AtomicLong(0L)
    private val lastActionAffectsDom = AtomicBoolean(false)

    init {
        ToolSpecGenerator.apply {
            generateAllOnce()
            webDriverToolSpecs.associateByTo(toolSpec) { it.method }
        }
        toolSpec["eval"] = ToolSpec(
            domain = domain,
            method = "eval",
            arguments = listOf(ToolSpec.Arg("expression", "String")),
            returnType = "Any?",
            description = "Alias of evaluateValue for page or element-scoped JavaScript evaluation.",
            help = """
                tab.eval(expression: String)
                tab.eval(expression: String, selector: String)

                Alias of tab.evaluateValue(...).
                When both selector and expression are provided, expression is forwarded as functionDeclaration.
            """.trimIndent()
        )
        toolSpec["type"] = ToolSpec(
            domain = domain,
            method = "type",
            arguments = listOf(
                ToolSpec.Arg("text", "String"),
                ToolSpec.Arg("selector", "String?", "null")
            ),
            returnType = "Unit",
            description = "Insert text into the currently focused element or the element matched by selector.",
            help = """
                tab.type(text: String)
                tab.type(text: String, selector: String?)

                Types text into the currently focused element when selector is omitted.
                When selector is provided, the executor focuses the matched element first and then types text.
            """.trimIndent()
        )
        toolSpec["press"] = ToolSpec(
            domain = domain,
            method = "press",
            arguments = listOf(
                ToolSpec.Arg("key", "String"),
                ToolSpec.Arg("selector", "String?", "null")
            ),
            returnType = "Unit",
            description = "Press a key on the currently focused element or the element matched by selector.",
            help = """
                tab.press(key: String)
                tab.press(key: String, selector: String?)

                Presses the key on the currently focused element when selector is omitted.
                When selector is provided, the executor focuses the matched element first and then presses the key.
            """.trimIndent()
        )
        toolSpec["saveStorageState"] = ToolSpec(
            domain = domain,
            method = "saveStorageState",
            arguments = emptyList(),
            returnType = "String",
            description = "Save cookies and the active origin's localStorage as a JSON storage-state payload.",
            help = """
                tab.saveStorageState()

                Returns a JSON string containing the current browser cookies and the active origin's localStorage entries.
            """.trimIndent()
        )
        toolSpec["loadStorageState"] = ToolSpec(
            domain = domain,
            method = "loadStorageState",
            arguments = listOf(ToolSpec.Arg("state", "String")),
            returnType = "String",
            description = "Load a JSON storage-state payload, restoring cookies and localStorage.",
            help = """
                tab.loadStorageState(state: String)

                Restores cookies plus localStorage from a JSON string previously returned by tab.saveStorageState().
            """.trimIndent()
        )
        toolSpec["consoleMessages"] = ToolSpec(
            domain = domain,
            method = "consoleMessages",
            arguments = listOf(ToolSpec.Arg("level", "String", "info")),
            returnType = "String",
            description = "Retrieve buffered browser console messages (log/warn/error/info/debug).",
            help = """
                tab.consoleMessages()
                tab.consoleMessages(level: String = "info")

                Returns a JSON array of console messages filtered to the given minimum level.
                Intercepts console.log/warn/error/info/debug on first call and buffers subsequent messages.
            """.trimIndent()
        )
        toolSpec["consoleClear"] = ToolSpec(
            domain = domain,
            method = "consoleClear",
            arguments = emptyList(),
            returnType = "String",
            description = "Clear the buffered console message list.",
            help = """
                tab.consoleClear()

                Empties the in-browser console message buffer.
            """.trimIndent()
        )
    }

    override fun help(method: String): String {
        val spec = toolSpec[method] ?: return "No help available for unknown method: $method"

        return spec.help ?: """
            ${spec.expression}
            ${spec.description}
        """.trimIndent()
    }

    private fun normalizeEvaluateValueArgs(args: Map<String, Any?>): Map<String, Any?> {
        val normalized = args.toMutableMap()
        val selector = normalized["selector"]?.toString()?.takeIf { it.isNotBlank() }
        val expression = normalized["expression"]?.toString()?.takeIf { it.isNotBlank() }

        if (selector != null && expression != null && !normalized.containsKey("functionDeclaration")) {
            normalized.remove("expression")
            normalized["functionDeclaration"] = expression
        }

        return normalized
    }

    private fun evaluationValueUsage(functionName: String): String {
        return when (functionName) {
            "eval" -> "eval requires 'expression' or ('expression','selector')"
            else -> "evaluateValue requires 'expression' or ('selector','functionDeclaration')"
        }
    }

    /**
     * TODO: add an option for each WebDriver action to control the behaviour of the action including waiting.
     * */
    private suspend fun waitBeforeReadIfNeeded(functionName: String) {
        if (functionName !in READ_PAGE_STATE_ACTIONS) {
            return
        }
        if (!lastActionAffectsDom.get()) {
            return
        }

        val lastActionAt = lastActionAtMillis.get()
        if (lastActionAt <= 0L) {
            return
        }

        val elapsed = System.currentTimeMillis() - lastActionAt
        if (elapsed < MIN_READ_INTERVAL_MILLIS) {
            delay((MIN_READ_INTERVAL_MILLIS - elapsed).milliseconds)
        }
    }

    /**
     * After a navigation-triggering action, detect whether the page started navigating and wait for
     * the DOM to settle before returning.
     *
     * Algorithm (inspired by [PageStateTracker.waitForDOMSettle]):
     * 1. Check if the URL already changed — if so the navigation is complete, wait for body + settle delay.
     * 2. If the URL is unchanged, eval `document.readyState`. If "loading", the navigation is in flight —
     *    call `waitForNavigation` then settle.
     * 3. Otherwise no navigation occurred — return immediately (no unnecessary delay).
     *
     * TODO: add an option for each WebDriver action to control the behaviour of the action including waiting.
     */
    private suspend fun waitForPotentialNavigation(driver: WebDriver, urlBefore: String) {
        // wait for a while for the action effects
        delay(200.milliseconds)

        try {
            val urlAfter = driver.currentUrl()
            if (urlAfter != urlBefore) {
                // URL already changed — navigation completed, wait for DOM to be ready.
                // Use a shorter timeout here since the navigation itself has already finished;
                // we only need the new page's body element to appear.
                driver.waitForSelector("body", NAVIGATION_DOM_READY_TIMEOUT_MS)
                delay(NAVIGATION_DOM_SETTLE_DELAY_MS.milliseconds)
                return
            }

            // URL unchanged — check if the document is currently loading
            val readyState = driver.evaluateValue("document.readyState") as? String
            if (readyState == "loading") {
                // Navigation is in flight, wait for it to complete
                driver.waitForNavigation(urlBefore, NAVIGATION_POLL_TIMEOUT_MS)
                driver.waitForSelector("body", NAVIGATION_POLL_TIMEOUT_MS)
                delay(NAVIGATION_DOM_SETTLE_DELAY_MS.milliseconds)

                // Verify the navigation actually landed on a different URL.
                // If the URL is unchanged after waiting, the navigation silently failed.
                val finalUrl = driver.currentUrl()
                if (finalUrl == urlBefore) {
                    logger.warning(
                        "waitForPotentialNavigation: navigation appeared to start (readyState=loading) " +
                                "but URL did not change from '$urlBefore'. Navigation may have failed silently."
                    )
                }
            } else {
                // No navigation detected. The action may have been a no-op (e.g. retry
                // computed a wrong targetIndex). Log at FINE for diagnostics.
                logger.fine(
                    "waitForPotentialNavigation: no navigation detected. " +
                            "urlBefore='$urlBefore', urlAfter='$urlAfter', readyState='$readyState'"
                )
            }
        } catch (e: Exception) {
            // Best-effort: navigation detection failures should not break the command
            logger.fine("waitForPotentialNavigation: exception while checking navigation: ${e.message}")
        }
    }

    /**
     * Execute a WebDriver function by name with named arguments.
     * args: (parameterName -> value). If provided names don't match a supported signature, throw IllegalArgumentException.
     */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun callFunctionOn(
        domain: String, functionName: String, args: Map<String, Any?>, receiver: Any
    ): Any? {
        val driver = requireNotNull(receiver as? WebDriver) { "The target must be a WebDriver" }

        fun allowed(vararg names: String) = names.toSet()

        waitBeforeReadIfNeeded(functionName)

        val result = when (functionName) {
            // Navigation
            "open" -> {
                validateArgs(args, allowed("url"), setOf("url"), functionName); driver.open(
                    paramString(
                        args,
                        "url",
                        functionName
                    )!!
                )
            }

            "navigate" -> {
                when {
                    args.containsKey("url") -> {
                        validateArgs(args, allowed("url"), setOf("url"), functionName)
                        driver.navigate(paramString(args, "url", functionName)!!)
                        // After navigation, wait for the page to load by waiting for the body element to be present
                        driver.waitForNavigation()
                        driver.waitForSelector("body", timeoutMillis = 10_000)
                        // wait for another seconds for DOM ready
                        delay(1.seconds)
                    }

                    args.containsKey("rawUrl") || args.containsKey("pageUrl") -> {
                        validateArgs(args, allowed("rawUrl", "pageUrl"), setOf("rawUrl", "pageUrl"), functionName)
                        driver.navigate(
                            NavigateEntry(
                                paramString(args, "rawUrl", functionName)!!,
                                pageUrl = paramString(args, "pageUrl", functionName)!!
                            )
                        )
                        // After navigation, wait for the page to load by waiting for the body element to be present
                        driver.waitForNavigation()
                        driver.waitForSelector("body", timeoutMillis = 10_000)
                        // wait for another seconds for DOM ready
                        delay(1.seconds)
                    }

                    else -> throw IllegalArgumentException("navigate requires 'url' or ('rawUrl','pageUrl')")
                }
            }

            "reload" -> {
                validateArgs(args, emptySet(), emptySet(), functionName)
                val urlBefore = driver.currentUrl()
                driver.reload()
                waitForPotentialNavigation(driver, urlBefore)
            }

            "goBack" -> {
                validateArgs(args, emptySet(), emptySet(), functionName)
                val urlBefore = driver.currentUrl()
                driver.goBack()
                waitForPotentialNavigation(driver, urlBefore)
            }

            "goForward" -> {
                validateArgs(args, emptySet(), emptySet(), functionName)
                val urlBefore = driver.currentUrl()
                driver.goForward()
                waitForPotentialNavigation(driver, urlBefore)
            }

            // Wait
            "waitForSelector" -> {
                when {
                    args.isEmpty() -> throw IllegalArgumentException("waitForSelector requires 'selector' (optional 'timeoutMillis')")
                    args.containsKey("selector") && !args.containsKey("timeoutMillis") -> {
                        validateArgs(args, allowed("selector"), setOf("selector"), functionName)
                        driver.waitForSelector(paramString(args, "selector", functionName)!!)
                    }

                    args.containsKey("selector") && args.containsKey("timeoutMillis") -> {
                        validateArgs(
                            args,
                            allowed("selector", "timeoutMillis"),
                            setOf("selector", "timeoutMillis"),
                            functionName
                        )
                        driver.waitForSelector(
                            paramString(args, "selector", functionName)!!,
                            paramLong(args, "timeoutMillis", functionName)!!
                        )
                    }

                    else -> throw IllegalArgumentException("waitForSelector requires 'selector' (optional 'timeoutMillis')")
                }
            }

            "waitForNavigation" -> {
                when {
                    args.isEmpty() -> driver.waitForNavigation()
                    args.containsKey("oldUrl") && !args.containsKey("timeoutMillis") -> {
                        validateArgs(args, allowed("oldUrl"), setOf("oldUrl"), functionName)
                        driver.waitForNavigation(paramString(args, "oldUrl", functionName)!!)
                    }

                    args.containsKey("oldUrl") && args.containsKey("timeoutMillis") -> {
                        validateArgs(
                            args,
                            allowed("oldUrl", "timeoutMillis"),
                            setOf("oldUrl", "timeoutMillis"),
                            functionName
                        )
                        driver.waitForNavigation(
                            paramString(args, "oldUrl", functionName)!!,
                            paramLong(args, "timeoutMillis", functionName)!!
                        )
                    }

                    else -> throw IllegalArgumentException("waitForNavigation requires 'oldUrl' (optional 'timeoutMillis')")
                }
            }

            "waitForPage" -> {
                when {
                    args.containsKey("url") && args.containsKey("timeoutMillis") -> {
                        validateArgs(args, allowed("url", "timeoutMillis"), setOf("url", "timeoutMillis"), functionName)
                        driver.waitForPage(
                            paramString(args, "url", functionName)!!,
                            Duration.ofMillis(paramLong(args, "timeoutMillis", functionName)!!)
                        )
                    }

                    args.containsKey("url") -> {
                        validateArgs(args, allowed("url"), setOf("url"), functionName)
                        driver.waitForPage(paramString(args, "url", functionName)!!, Duration.ofMillis(30000))
                    }

                    else -> throw IllegalArgumentException("waitForPage requires 'url' (optional 'timeoutMillis')")
                }
            }

            "waitForFunction" -> {
                when {
                    args.containsKey("pageFunction") && args.containsKey("timeoutMillis") -> {
                        validateArgs(args, allowed("pageFunction", "timeoutMillis"), setOf("pageFunction", "timeoutMillis"), functionName)
                        driver.waitForFunction(
                            paramString(args, "pageFunction", functionName)!!,
                            Duration.ofMillis(paramLong(args, "timeoutMillis", functionName)!!)
                        )
                    }
                    args.containsKey("pageFunction") -> {
                        validateArgs(args, allowed("pageFunction"), setOf("pageFunction"), functionName)
                        driver.waitForFunction(paramString(args, "pageFunction", functionName)!!, Duration.ofMillis(30000))
                    }
                    else -> throw IllegalArgumentException("waitForFunction requires 'pageFunction' (optional 'timeoutMillis')")
                }
            }

            // Status checking
            "exists" -> {
                validateArgs(
                    args,
                    allowed("selector"),
                    setOf("selector"),
                    functionName
                ); driver.exists(paramString(args, "selector", functionName)!!)
            }

            "isVisible" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isVisible(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "visible" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isVisible(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "isHidden" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isHidden(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "isChecked" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isChecked(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            // Interactions
            "focus" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.focus(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "hover" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.hover(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "press" -> {
                val urlBefore = driver.currentUrl()
                when {
                    args.containsKey("selector") && args.containsKey("key") -> {
                        validateArgs(args, allowed("selector", "key"), setOf("selector", "key"), functionName)
                        driver.press(
                            paramString(args, "key", functionName)!!,
                            paramString(args, "selector", functionName)!!
                        )
                    }

                    args.containsKey("key") -> {
                        validateArgs(args, allowed("key"), setOf("key"), functionName)
                        driver.press(paramString(args, "key", functionName)!!)
                    }

                    else -> throw IllegalArgumentException("press requires 'key' and optionally 'selector'")
                }
                waitForPotentialNavigation(driver, urlBefore)
            }

            "type" -> {
                when {
                    args.containsKey("selector") && args.containsKey("text") -> {
                        validateArgs(args, allowed("selector", "text"), setOf("selector", "text"), functionName)
                        driver.type(
                            paramString(args, "text", functionName)!!,
                            paramString(args, "selector", functionName)!!
                        )
                    }

                    args.containsKey("text") -> {
                        validateArgs(args, allowed("text"), setOf("text"), functionName)
                        driver.type(paramString(args, "text", functionName)!!)
                    }

                    else -> throw IllegalArgumentException("type requires 'text' and optionally 'selector'")
                }
            }

            "fill" -> {
                validateArgs(args, allowed("selector", "text"), setOf("selector", "text"), functionName); driver.fill(
                    paramString(args, "selector", functionName)!!,
                    paramString(args, "text", functionName)!!
                )
            }

            "click" -> {
                val urlBefore = driver.currentUrl()
                when {
                    args.containsKey("selector") && args.containsKey("count") && !args.containsKey("modifier") -> {
                        validateArgs(args, allowed("selector", "count"), setOf("selector", "count"), functionName)
                        driver.click(
                            selector = paramString(args, "selector", functionName)!!,
                            count = paramInt(args, "count", functionName)!!
                        )
                    }

                    args.containsKey("selector") && args.containsKey("modifier") && !args.containsKey("count") -> {
                        validateArgs(args, allowed("selector", "modifier"), setOf("selector", "modifier"), functionName)
                        driver.click(
                            selector = paramString(args, "selector", functionName)!!,
                            modifier = paramString(args, "modifier", functionName)!!
                        )
                    }

                    args.containsKey("selector") && !args.containsKey("count") && !args.containsKey("modifier") -> {
                        validateArgs(args, allowed("selector"), setOf("selector"), functionName)
                        driver.click(selector = paramString(args, "selector", functionName)!!)
                    }

                    else -> throw IllegalArgumentException("click requires 'selector' plus optionally one of 'count' or 'modifier'")
                }
                waitForPotentialNavigation(driver, urlBefore)
            }

            "dblclick" -> {
                val urlBefore = driver.currentUrl()
                validateArgs(
                    args,
                    if (args.containsKey("modifier")) allowed("selector", "modifier") else allowed("selector"),
                    setOf("selector"),
                    functionName
                )
                if (args.containsKey("modifier")) {
                    driver.dblclick(
                        selector = paramString(args, "selector", functionName)!!,
                        modifier = paramString(args, "modifier", functionName)!!
                    )
                } else {
                    driver.dblclick(selector = paramString(args, "selector", functionName)!!)
                }
                waitForPotentialNavigation(driver, urlBefore)
            }

            "upload" -> {
                validateArgs(args, allowed("selector", "paths"), setOf("selector", "paths"), functionName)
                val paths =
                    args["paths"] as? List<String> ?: throw IllegalArgumentException("paths must be a list of strings")
                driver.upload(selector = paramString(args, "selector", functionName)!!, paths = paths)
            }

            "selectOption" -> {
                validateArgs(args, allowed("selector", "values"), setOf("selector", "values"), functionName)
                val values = args["values"] as? List<String>
                    ?: throw IllegalArgumentException("values must be a list of strings")
                driver.selectOption(selector = paramString(args, "selector", functionName)!!, values = values)
            }

            "ariaSnapshot" -> {
                validateArgs(
                    args,
                    allowed("viewports", "interactive", "urls", "compact", "depth", "selector", "boxes", "limit"),
                    emptySet(),
                    functionName
                )
                val viewports = paramString(args, "viewports", functionName, required = false)
                val interactive = paramBool(args, "interactive", functionName, required = false) ?: false
                val urls = paramBool(args, "urls", functionName, required = false) ?: false
                val compact = paramBool(args, "compact", functionName, required = false) ?: true
                val maxDepth = paramInt(args, "depth", functionName, required = false) ?: -1
                val selector = paramString(args, "selector", functionName, required = false)
                val boxes = paramBool(args, "boxes", functionName, required = false) ?: true
                val maxNodes = paramInt(args, "limit", functionName, required = false) ?: -1

                val options = AriaSnapshotOptions(
                    interactive = interactive,
                    urls = urls,
                    compact = compact,
                    maxDepth = maxDepth,
                    selector = selector,
                    viewports = viewports,
                    boxes = boxes,
                    maxNodes = maxNodes
                )
                driver.ariaSnapshot(options)
            }

            "title" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.title()
            }

            "dialogAccept" -> {
                validateArgs(args, allowed("promptText"), emptySet(), functionName)
                driver.dialogAccept(paramString(args, "promptText", functionName, required = false))
            }

            "dialogDismiss" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.dialogDismiss()
            }

            "resize" -> {
                validateArgs(args, allowed("width", "height"), setOf("width", "height"), functionName)
                driver.resize(
                    width = paramInt(args, "width", functionName)!!,
                    height = paramInt(args, "height", functionName)!!
                )
            }

            "keydown", "keyDown" -> {
                validateArgs(args, allowed("key"), setOf("key"), functionName)
                driver.keyDown(paramString(args, "key", functionName)!!)
            }

            "keyup", "keyUp" -> {
                validateArgs(args, allowed("key"), setOf("key"), functionName)
                driver.keyUp(paramString(args, "key", functionName)!!)
            }

            "mousedown", "mouseDown" -> {
                validateArgs(args, allowed("button", "clickCount"), emptySet(), functionName)
                driver.mouseDown(
                    button = paramString(args, "button", functionName, required = false) ?: "left",
                    clickCount = paramInt(args, "clickCount", functionName, required = false, default = 1)!!
                )
            }

            "mouseup", "mouseUp" -> {
                validateArgs(args, allowed("button", "clickCount"), emptySet(), functionName)
                driver.mouseUp(
                    button = paramString(args, "button", functionName, required = false) ?: "left",
                    clickCount = paramInt(args, "clickCount", functionName, required = false, default = 1)!!
                )
            }

            "dragAndDrop" -> {
                validateArgs(
                    args,
                    allowed("selector", "deltaX", "deltaY"),
                    setOf("selector", "deltaX"),
                    functionName
                ); driver.dragAndDrop(
                    paramString(args, "selector", functionName)!!,
                    paramInt(args, "deltaX", functionName)!!,
                    paramInt(args, "deltaY", functionName, required = false, default = 0)!!
                )
            }

            "drag" -> {
                validateArgs(
                    args,
                    allowed("sourceSelector", "targetSelector"),
                    setOf("sourceSelector", "targetSelector"),
                    functionName
                )
                driver.drag(
                    sourceSelector = paramString(args, "sourceSelector", functionName)!!,
                    targetSelector = paramString(args, "targetSelector", functionName)!!
                )
            }

            "clickTextMatches" -> {
                when {
                    args.containsKey("selector") && args.containsKey("pattern") && args.containsKey("count") -> {
                        validateArgs(
                            args,
                            allowed("selector", "pattern", "count"),
                            setOf("selector", "pattern", "count"),
                            functionName
                        )
                        driver.clickTextMatches(
                            selector = paramString(args, "selector", functionName)!!,
                            pattern = paramString(args, "pattern", functionName)!!,
                            count = paramInt(args, "count", functionName)!!
                        )
                    }

                    args.containsKey("selector") && args.containsKey("pattern") -> {
                        validateArgs(args, allowed("selector", "pattern"), setOf("selector", "pattern"), functionName)
                        driver.clickTextMatches(
                            selector = paramString(args, "selector", functionName)!!,
                            pattern = paramString(args, "pattern", functionName)!!
                        )
                    }

                    else -> throw IllegalArgumentException("clickTextMatches requires 'selector','pattern' (optional 'count')")
                }
            }

            "clickMatches" -> {
                when {
                    args.containsKey("selector") && args.containsKey("attrName") && args.containsKey("pattern") && args.containsKey(
                        "count"
                    ) -> {
                        validateArgs(
                            args,
                            allowed("selector", "attrName", "pattern", "count"),
                            setOf("selector", "attrName", "pattern", "count"),
                            functionName
                        )
                        driver.clickMatches(
                            selector = paramString(args, "selector", functionName)!!,
                            attrName = paramString(args, "attrName", functionName)!!,
                            pattern = paramString(args, "pattern", functionName)!!,
                            count = paramInt(args, "count", functionName)!!
                        )
                    }

                    args.containsKey("selector") && args.containsKey("attrName") && args.containsKey("pattern") -> {
                        validateArgs(
                            args,
                            allowed("selector", "attrName", "pattern"),
                            setOf("selector", "attrName", "pattern"),
                            functionName
                        )
                        driver.clickMatches(
                            selector = paramString(args, "selector", functionName)!!,
                            attrName = paramString(args, "attrName", functionName)!!,
                            pattern = paramString(args, "pattern", functionName)!!
                        )
                    }

                    else -> throw IllegalArgumentException("clickMatches requires 'selector','attrName','pattern' (optional 'count')")
                }
            }

            "check" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.check(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "uncheck" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.uncheck(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "scrollTo" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.scrollTo(
                    paramString(
                        args,
                        "selector",
                        functionName
                    )!!
                )
            }

            "bringToFront" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.bringToFront()
            }

            // Scrolling
            "scrollDown" -> {
                validateArgs(
                    args,
                    if (args.isEmpty()) emptySet() else allowed("count"),
                    emptySet(),
                    functionName
                ); driver.scrollDown(count = if (args.isEmpty()) 1 else paramInt(args, "count", functionName)!!)
            }

            "scrollUp" -> {
                validateArgs(
                    args,
                    if (args.isEmpty()) emptySet() else allowed("count"),
                    emptySet(),
                    functionName
                ); driver.scrollUp(count = if (args.isEmpty()) 1 else paramInt(args, "count", functionName)!!)
            }

            "scrollBy" -> {
                validateArgs(
                    args,
                    allowed("pixels", "smooth"),
                    emptySet(),
                    functionName
                ); driver.scrollBy(
                    pixels = args["pixels"]?.toString()?.toDoubleOrNull() ?: 200.0,
                    smooth = args["smooth"]?.toString()?.lowercase()?.let { it == "true" } ?: true)
            }

            "scrollToTop" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.scrollToTop()
            }

            "scrollToBottom" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.scrollToBottom()
            }

            "scrollToMiddle" -> {
                validateArgs(args, allowed("ratio"), setOf("ratio"), functionName); driver.scrollToMiddle(
                    paramDouble(
                        args,
                        "ratio",
                        functionName
                    )!!
                )
            }

            "scrollToViewport" -> {
                validateArgs(args, allowed("n"), setOf("n"), functionName); driver.scrollToViewport(
                    paramDouble(
                        args,
                        "n",
                        functionName
                    )!!
                )
            }

            "scrollToScreen" -> {
                validateArgs(
                    args,
                    allowed("screenNumber"),
                    setOf("screenNumber"),
                    functionName
                ); driver.scrollToViewport(paramDouble(args, "screenNumber", functionName)!!)
            }

            "mouseWheel" -> {
                validateArgs(args, allowed("deltaX", "deltaY"), emptySet(), functionName)
                driver.mouseWheel(
                    deltaX = args["deltaX"]?.toString()?.toDoubleOrNull() ?: 0.0,
                    deltaY = args["deltaY"]?.toString()?.toDoubleOrNull() ?: 150.0
                )
            }

            "mouseMove" -> {
                when {
                    args.containsKey("x") && args.containsKey("y") -> {
                        validateArgs(args, allowed("x", "y"), setOf("x", "y"), functionName)
                        driver.mouseMove(
                            paramDouble(args, "x", functionName)!!,
                            paramDouble(args, "y", functionName)!!
                        )
                    }

                    args.containsKey("selector") -> {
                        validateArgs(
                            args,
                            allowed("selector", "deltaX", "deltaY"),
                            setOf("selector"),
                            functionName
                        ); driver.moveMouseTo(
                            paramString(args, "selector", functionName)!!,
                            paramInt(args, "deltaX", functionName, required = false, default = 0)!!,
                            paramInt(args, "deltaY", functionName, required = false, default = 0)!!
                        )
                    }

                    else -> throw IllegalArgumentException("moveMouseTo requires ('x','y') or 'selector' (+ optional deltaX, deltaY)")
                }
            }

            "screenshot" -> {
                when {
                    args.isEmpty() -> {
                        validateArgs(args, emptySet(), emptySet(), functionName); driver.screenshot()
                    }

                    args.containsKey("selector") -> {
                        validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.screenshot(
                            paramString(args, "selector", functionName)!!
                        )
                    }

                    args.containsKey("fullPage") -> {
                        validateArgs(args, allowed("fullPage"), setOf("fullPage"), functionName); driver.screenshot(
                            paramBool(args, "fullPage", functionName)!!
                        )
                    }

                    args.containsKey("viewport") -> {
                        validateArgs(args, allowed("viewport"), setOf("viewport"), functionName)
                        val viewportIndex = paramInt(args, "viewport", functionName)!!
                        val w = driver.evaluateValue("window.innerWidth")?.toString()?.toDoubleOrNull() ?: 1920.0
                        val h = driver.evaluateValue("window.innerHeight")?.toString()?.toDoubleOrNull() ?: 1080.0
                        val rect = ai.platon.pulsar.common.math.geometric.RectD(
                            0.0, viewportIndex * h, w, h
                        )
                        driver.screenshot(rect)
                    }

                    else -> throw IllegalArgumentException("screenshot allows none or one of: 'selector' | 'fullPage' | 'viewport'")
                }
            }

            "pdf" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.pdf()
            }

            // HTML / Text
            "outerHTML" -> {
                if (args.isEmpty()) {
                    validateArgs(args, emptySet(), emptySet(), functionName); driver.outerHTML()
                } else {
                    validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.outerHTML(
                        paramString(args, "selector", functionName)!!
                    )
                }
            }

            "textContent" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.textContent()
            }

            "nanoDOMTree" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.nanoDOMTree()
            }

            "selectFirstTextOrNull" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.selectFirstTextOrNull(
                    paramString(args, "selector", functionName)!!
                )
            }

            "selectTextAll" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.selectTextAll(
                    paramString(args, "selector", functionName)!!
                )
            }

            "selectFirstAttributeOrNull" -> {
                validateArgs(
                    args,
                    allowed("selector", "attrName"),
                    setOf("selector", "attrName"),
                    functionName
                ); driver.selectFirstAttributeOrNull(
                    paramString(args, "selector", functionName)!!,
                    paramString(args, "attrName", functionName)!!
                )
            }

            "selectAttributes" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.selectAttributes(
                    paramString(args, "selector", functionName)!!
                )
            }

            "selectAttributeAll" -> {
                when {
                    args.containsKey("selector") && args.containsKey("attrName") && (args.containsKey("start") || args.containsKey(
                        "limit"
                    )) -> {
                        validateArgs(
                            args,
                            allowed("selector", "attrName", "start", "limit"),
                            setOf("selector", "attrName"),
                            functionName
                        )
                        driver.selectAttributeAll(
                            selector = paramString(args, "selector", functionName)!!,
                            attrName = paramString(args, "attrName", functionName)!!,
                            start = paramInt(args, "start", functionName, required = false, default = 0)!!,
                            limit = paramInt(args, "limit", functionName, required = false, default = 10000)!!
                        )
                    }

                    args.containsKey("selector") && args.containsKey("attrName") -> {
                        validateArgs(
                            args,
                            allowed("selector", "attrName"),
                            setOf("selector", "attrName"),
                            functionName
                        ); driver.selectAttributeAll(
                            paramString(args, "selector", functionName)!!,
                            paramString(args, "attrName", functionName)!!
                        )
                    }

                    else -> throw IllegalArgumentException("selectAttributeAll requires 'selector','attrName' (optional 'start','limit')")
                }
            }

            "setAttribute" -> {
                validateArgs(
                    args,
                    allowed("selector", "attrName", "attrValue"),
                    setOf("selector", "attrName", "attrValue"),
                    functionName
                ); driver.setAttribute(
                    paramString(args, "selector", functionName)!!,
                    paramString(args, "attrName", functionName)!!,
                    paramString(args, "attrValue", functionName)!!
                )
            }

            "setAttributeAll" -> {
                validateArgs(
                    args,
                    allowed("selector", "attrName", "attrValue"),
                    setOf("selector", "attrName", "attrValue"),
                    functionName
                ); driver.setAttributeAll(
                    paramString(args, "selector", functionName)!!,
                    paramString(args, "attrName", functionName)!!,
                    paramString(args, "attrValue", functionName)!!
                )
            }

            // Property selection / set
            "selectFirstPropertyValueOrNull" -> {
                validateArgs(
                    args,
                    allowed("selector", "propName"),
                    setOf("selector", "propName"),
                    functionName
                ); driver.selectFirstPropertyValueOrNull(
                    paramString(args, "selector", functionName)!!,
                    paramString(args, "propName", functionName)!!
                )
            }

            "selectPropertyValueAll" -> {
                when {
                    args.containsKey("selector") && args.containsKey("propName") && (args.containsKey("start") || args.containsKey(
                        "limit"
                    )) -> {
                        validateArgs(
                            args,
                            allowed("selector", "propName", "start", "limit"),
                            setOf("selector", "propName"),
                            functionName
                        )
                        driver.selectPropertyValueAll(
                            selector = paramString(args, "selector", functionName)!!,
                            propName = paramString(args, "propName", functionName)!!,
                            start = paramInt(args, "start", functionName, required = false, default = 0)!!,
                            limit = paramInt(args, "limit", functionName, required = false, default = 10000)!!
                        )
                    }

                    args.containsKey("selector") && args.containsKey("propName") -> {
                        validateArgs(
                            args,
                            allowed("selector", "propName"),
                            setOf("selector", "propName"),
                            functionName
                        ); driver.selectPropertyValueAll(
                            paramString(args, "selector", functionName)!!,
                            paramString(args, "propName", functionName)!!
                        )
                    }

                    else -> throw IllegalArgumentException("selectPropertyValueAll requires 'selector','propName' (optional 'start','limit')")
                }
            }

            "setProperty" -> {
                validateArgs(
                    args,
                    allowed("selector", "propName", "propValue"),
                    setOf("selector", "propName", "propValue"),
                    functionName
                ); driver.setProperty(
                    paramString(args, "selector", functionName)!!,
                    paramString(args, "propName", functionName)!!,
                    paramString(args, "propValue", functionName)!!
                )
            }

            "setPropertyAll" -> {
                validateArgs(
                    args,
                    allowed("selector", "propName", "propValue"),
                    setOf("selector", "propName", "propValue"),
                    functionName
                ); driver.setPropertyAll(
                    paramString(args, "selector", functionName)!!,
                    paramString(args, "propName", functionName)!!,
                    paramString(args, "propValue", functionName)!!
                )
            }

            // JavaScript evaluation
            "evaluate" -> {
                validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluate(
                    paramString(args, "expression", functionName)!!
                )
            }

            "evaluateDetail" -> {
                validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluateDetail(
                    paramString(args, "expression", functionName)!!
                )
            }

            "eval", "evaluateValue" -> {
                val normalizedArgs = normalizeEvaluateValueArgs(args)
                when {
                    normalizedArgs.containsKey("selector") && normalizedArgs.containsKey("functionDeclaration") -> {
                        validateArgs(
                            normalizedArgs,
                            allowed("selector", "functionDeclaration"),
                            setOf("selector", "functionDeclaration"),
                            functionName
                        )
                        driver.evaluateValueDetail(
                            paramString(normalizedArgs, "selector", functionName)!!,
                            paramString(normalizedArgs, "functionDeclaration", functionName)!!
                        )
                    }

                    normalizedArgs.containsKey("expression") -> {
                        validateArgs(normalizedArgs, allowed("expression"), setOf("expression"), functionName)
                        driver.evaluateValueDetail(paramString(normalizedArgs, "expression", functionName)!!)
                    }

                    else -> throw IllegalArgumentException(evaluationValueUsage(functionName))
                }
            }

            "evaluateValueDetail" -> {
                when {
                    args.containsKey("selector") && args.containsKey("functionDeclaration") -> {
                        validateArgs(
                            args,
                            allowed("selector", "functionDeclaration"),
                            setOf("selector", "functionDeclaration"),
                            functionName
                        )
                        driver.evaluateValueDetail(
                            paramString(args, "selector", functionName)!!,
                            paramString(args, "functionDeclaration", functionName)!!
                        )
                    }

                    args.containsKey("expression") -> {
                        validateArgs(args, allowed("expression"), setOf("expression"), functionName)
                        driver.evaluateValueDetail(paramString(args, "expression", functionName)!!)
                    }

                    else -> throw IllegalArgumentException("evaluateValueDetail requires 'expression' or ('selector','functionDeclaration')")
                }
            }

            "generateLocator" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName)
                driver.generateLocator(paramString(args, "selector", functionName)!!)
            }

            // Element geometry
            "clickablePoint" -> {
                validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.clickablePoint(
                    paramString(args, "selector", functionName)!!
                )
            }

            "boundingBox" -> {
                validateArgs(
                    args,
                    allowed("selector"),
                    setOf("selector"),
                    functionName
                ); driver.boundingBox(paramString(args, "selector", functionName)!!)
            }

            // Jsoup / resource
            "newJsoupSession" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.newJsoupSession()
            }

            "loadJsoupResource" -> {
                validateArgs(args, allowed("url"), setOf("url"), functionName); driver.loadJsoupResource(
                    paramString(
                        args,
                        "url",
                        functionName
                    )!!
                )
            }

            "loadResource" -> {
                validateArgs(args, allowed("url"), setOf("url"), functionName); driver.loadResource(
                    paramString(
                        args,
                        "url",
                        functionName
                    )!!
                )
            }

            // Cookies
            "getCookies" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.getCookies()
            }

            "deleteCookies" -> {
                validateArgs(
                    args,
                    allowed("name", "url", "domain", "path"),
                    setOf("name"),
                    functionName
                ); driver.deleteCookies(
                    name = paramString(args, "name", functionName)!!,
                    url = paramString(args, "url", functionName, required = false),
                    domain = paramString(args, "domain", functionName, required = false),
                    path = paramString(args, "path", functionName, required = false)
                )
            }

            "clearBrowserCookies" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.clearBrowserCookies()
            }

            "saveStorageState" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.saveStorageState()
            }

            "loadStorageState" -> {
                validateArgs(args, allowed("state"), setOf("state"), functionName); driver.loadStorageState(
                    paramString(
                        args,
                        "state",
                        functionName
                    )!!
                )
            }

            // Delay / pause / stop
            "delay" -> {
                validateArgs(
                    args,
                    if (args.isEmpty()) emptySet() else allowed("millis"),
                    emptySet(),
                    functionName
                ); driver.delay(args["millis"]?.toString()?.toLongOrNull() ?: 1000L)
            }

            "pause" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.pause()
            }

            "stop" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.stop()
            }

            // URL & document info
            "currentUrl" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.currentUrl()
            }

            "url" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.url()
            }

            "documentURI" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.documentURI()
            }

            "baseURI" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.baseURI()
            }

            "referrer" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.referrer()
            }

            "pageSource" -> {
                validateArgs(args, emptySet(), emptySet(), functionName); driver.pageSource()
            }

            // Console messages
            "consoleMessages" -> {
                validateArgs(args, allowed("level"), emptySet(), functionName)
                val level = paramString(args, "level", functionName, required = false) ?: "info"
                val script = """
                    (function() {
                        if (!window.__b4_console_intercepted) {
                            window.__b4_console = window.__b4_console || [];
                            var levels = ['log', 'warn', 'error', 'info', 'debug'];
                            levels.forEach(function(lvl) {
                                var original = console[lvl];
                                console[lvl] = function() {
                                    var args = Array.prototype.slice.call(arguments);
                                    window.__b4_console.push({
                                        level: lvl,
                                        text: args.map(function(a) {
                                            return typeof a === 'object' ? JSON.stringify(a) : String(a);
                                        }).join(' '),
                                        timestamp: Date.now()
                                    });
                                    original.apply(console, arguments);
                                };
                            });
                            window.__b4_console_intercepted = true;
                        }
                        var minPriority = { error: 0, warn: 1, info: 2, log: 2, debug: 3 };
                        var min = minPriority['""" + level + """'] !== undefined ? minPriority['""" + level + """'] : 2;
                        var filtered = (window.__b4_console || []).filter(function(e) {
                            var p = minPriority[e.level] !== undefined ? minPriority[e.level] : 2;
                            return p <= min;
                        });
                        return JSON.stringify(filtered);
                    })()
                """.trimIndent()
                driver.evaluateValueDetail(script)
            }

            "consoleClear" -> {
                validateArgs(args, emptySet(), emptySet(), functionName)
                driver.evaluateValueDetail("window.__b4_console = []; 'Console cleared'")
            }

            "help" -> help()

            else -> throw IllegalArgumentException("Unsupported WebDriver tool function: $functionName, call $domain.help() for more details")
        }

        lastActionAtMillis.set(System.currentTimeMillis())
        lastActionAffectsDom.set(functionName in DOM_AFFECTING_ACTIONS)
        return result
    }
}
