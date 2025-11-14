package ai.platon.pulsar.agentic.tools.executors

import ai.platon.pulsar.agentic.ai.tta.SourceCodeToToolCallSpec
import ai.platon.pulsar.skeleton.crawl.fetch.driver.NavigateEntry
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import java.time.Duration
import kotlin.reflect.KClass

class WebDriverToolExecutor: AbstractToolExecutor() {
    override val domain = "driver"

    override val targetClass: KClass<*> = WebDriver::class

    private val toolCallSpecs = SourceCodeToToolCallSpec.webDriverToolCallList.associateBy { it.method }

    override fun help(method: String): String {
        return toolCallSpecs[method]?.description ?: ""
    }

    /**
     * Execute a WebDriver function by name with named arguments.
     * args: (parameterName -> value). If provided names don't match a supported signature, throw IllegalArgumentException.
     */
    @Suppress("UNUSED_PARAMETER")
    override suspend fun execute(
        objectName: String, functionName: String, args: Map<String, Any?>, target: Any
    ): Any? {
        val driver = requireNotNull(target as? WebDriver) { "The target must be a WebDriver" }

        fun allowed(vararg names: String) = names.toSet()

        // Convert positional arguments to named arguments based on common method signatures
        val namedArgs = convertPositionalToNamedArgs(functionName, args)

        return executeWithNamedArgs(objectName, functionName, namedArgs, driver, ::allowed)
    }

    /**
     * Convert positional arguments (indexed by numbers like "0", "1") to named arguments
     * based on the expected signature of the function.
     */
    private fun convertPositionalToNamedArgs(functionName: String, args: Map<String, Any?>): Map<String, Any?> {
        // If already has named arguments, return as-is
        if (args.keys.any { !it.matches(Regex("\\d+")) }) {
            return args
        }

        val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }.map { it.value.toString() }

        return when (functionName) {
            // Single parameter methods
            "exists", "isVisible", "visible", "isHidden", "isChecked", "focus" -> {
                if (positionalArgs.size == 1) mapOf("selector" to positionalArgs[0]) else args
            }
            // Two parameter methods
            "type", "fill", "press" -> {
                if (positionalArgs.size == 2) mapOf("selector" to positionalArgs[0], "text" to positionalArgs[1]) else args
            }
            "waitForSelector" -> {
                when (positionalArgs.size) {
                    1 -> mapOf("selector" to positionalArgs[0])
                    2 -> mapOf("selector" to positionalArgs[0], "timeoutMillis" to positionalArgs[1])
                    else -> args
                }
            }
            "navigateTo", "open" -> {
                if (positionalArgs.size == 1) mapOf("url" to positionalArgs[0]) else args
            }
            "click" -> {
                when (positionalArgs.size) {
                    1 -> mapOf("selector" to positionalArgs[0])
                    2 -> {
                        // Assume second arg is count (int) or modifier (string)
                        if (positionalArgs[1].toIntOrNull() != null) {
                            mapOf("selector" to positionalArgs[0], "count" to positionalArgs[1])
                        } else {
                            mapOf("selector" to positionalArgs[0], "modifier" to positionalArgs[1])
                        }
                    }
                    else -> args
                }
            }
            else -> args // Unknown function, return as-is
        }
    }

    private suspend fun executeWithNamedArgs(
        objectName: String, functionName: String, args: Map<String, Any?>, driver: WebDriver, allowed: (String) -> Set<String>
    ): Any? {
        fun allowed(vararg names: String) = names.toSet()

        return when (functionName) {
            // Navigation
            "open" -> { validateArgs(args, allowed("url"), setOf("url"), functionName); driver.open(paramString(args, "url", functionName)!!) }
            "navigateTo" -> {
                val url = if (args.containsKey("url")) {
                    paramString(args, "url", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                }

                if (url != null) {
                    driver.navigateTo(url)
                } else {
                    // Handle rawUrl/pageUrl case
                    val rawUrl = args["rawUrl"]?.toString()
                    val pageUrl = args["pageUrl"]?.toString()
                    if (rawUrl != null && pageUrl != null) {
                        driver.navigateTo(NavigateEntry(rawUrl, pageUrl = pageUrl))
                    } else {
                        throw IllegalArgumentException("navigateTo requires 'url' or ('rawUrl','pageUrl')")
                    }
                }
            }
            "reload" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.reload() }
            "goBack" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.goBack() }
            "goForward" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.goForward() }

            // Wait
            "waitForSelector" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("waitForSelector requires 'selector' (optional 'timeoutMillis')")

                val timeoutMillis = if (args.containsKey("timeoutMillis")) {
                    paramLong(args, "timeoutMillis", functionName)
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.size > 1) positionalArgs[1].value.toString().toLongOrNull() else null
                }

                if (timeoutMillis != null) {
                    driver.waitForSelector(selector, timeoutMillis)
                } else {
                    driver.waitForSelector(selector)
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
                        validateArgs(args, allowed("oldUrl", "timeoutMillis"), setOf("oldUrl", "timeoutMillis"), functionName)
                        driver.waitForNavigation(paramString(args, "oldUrl", functionName)!!, paramLong(args, "timeoutMillis", functionName)!!)
                    }
                    else -> throw IllegalArgumentException("waitForNavigation requires 'oldUrl' (optional 'timeoutMillis')")
                }
            }
            "waitForPage" -> {
                when {
                    args.containsKey("url") && args.containsKey("timeoutMillis") -> {
                        validateArgs(args, allowed("url", "timeoutMillis"), setOf("url", "timeoutMillis"), functionName)
                        driver.waitForPage(paramString(args, "url", functionName)!!, Duration.ofMillis(paramLong(args, "timeoutMillis", functionName)!!))
                    }
                    args.containsKey("url") -> {
                        validateArgs(args, allowed("url"), setOf("url"), functionName)
                        driver.waitForPage(paramString(args, "url", functionName)!!, Duration.ofMillis(30000))
                    }
                    else -> throw IllegalArgumentException("waitForPage requires 'url' (optional 'timeoutMillis')")
                }
            }

            // Status checking
            "exists" -> {
                // Handle both positional and named arguments
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    // Try to get positional argument
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("Missing required parameter 'selector' for exists")
                driver.exists(selector)
            }
            "isVisible" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("Missing required parameter 'selector' for isVisible")
                driver.isVisible(selector)
            }
            "visible" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("Missing required parameter 'selector' for visible")
                driver.isVisible(selector)
            }
            "isHidden" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("Missing required parameter 'selector' for isHidden")
                driver.isHidden(selector)
            }
            "isChecked" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("Missing required parameter 'selector' for isChecked")
                driver.isChecked(selector)
            }

            // Interactions
            "focus" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.focus(paramString(args, "selector", functionName)!!) }
            "type" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("type requires 'selector' and 'text'")

                val text = if (args.containsKey("text")) {
                    paramString(args, "text", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.size > 1) positionalArgs[1].value.toString() else null
                } ?: throw IllegalArgumentException("type requires 'selector' and 'text'")

                driver.type(selector, text)
            }
            "fill" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("fill requires 'selector' and 'text'")

                val text = if (args.containsKey("text")) {
                    paramString(args, "text", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.size > 1) positionalArgs[1].value.toString() else null
                } ?: throw IllegalArgumentException("fill requires 'selector' and 'text'")

                driver.fill(selector, text)
            }
            "press" -> { validateArgs(args, allowed("selector", "key"), setOf("selector", "key"), functionName); driver.press(paramString(args, "selector", functionName)!!, paramString(args, "key", functionName)!!) }
            "click" -> {
                val selector = if (args.containsKey("selector")) {
                    paramString(args, "selector", functionName)!!
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.isNotEmpty()) positionalArgs[0].value.toString() else null
                } ?: throw IllegalArgumentException("click requires 'selector' plus optionally one of 'count' or 'modifier'")

                val count = if (args.containsKey("count")) {
                    paramInt(args, "count", functionName)
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.size > 1) positionalArgs[1].value.toString().toIntOrNull() else null
                }

                val modifier = if (args.containsKey("modifier")) {
                    paramString(args, "modifier", functionName)
                } else {
                    val positionalArgs = args.filterKeys { it.matches(Regex("\\d+")) }.entries.sortedBy { it.key.toInt() }
                    if (positionalArgs.size > 1 && positionalArgs[1].value.toString().toIntOrNull() == null) {
                        positionalArgs[1].value.toString()
                    } else {
                        null
                    }
                }

                when {
                    count != null && modifier == null -> driver.click(selector = selector, count = count)
                    count == null && modifier != null -> driver.click(selector = selector, modifier = modifier)
                    count == null && modifier == null -> driver.click(selector = selector)
                    else -> throw IllegalArgumentException("click requires 'selector' plus optionally one of 'count' or 'modifier'")
                }
            }
            "clickTextMatches" -> {
                when {
                    args.containsKey("selector") && args.containsKey("pattern") && args.containsKey("count") -> {
                        validateArgs(args, allowed("selector", "pattern", "count"), setOf("selector", "pattern", "count"), functionName)
                        driver.clickTextMatches(selector = paramString(args, "selector", functionName)!!, pattern = paramString(args, "pattern", functionName)!!, count = paramInt(args, "count", functionName)!!)
                    }
                    args.containsKey("selector") && args.containsKey("pattern") -> {
                        validateArgs(args, allowed("selector", "pattern"), setOf("selector", "pattern"), functionName)
                        driver.clickTextMatches(selector = paramString(args, "selector", functionName)!!, pattern = paramString(args, "pattern", functionName)!!)
                    }
                    else -> throw IllegalArgumentException("clickTextMatches requires 'selector','pattern' (optional 'count')")
                }
            }
            "clickMatches" -> {
                when {
                    args.containsKey("selector") && args.containsKey("attrName") && args.containsKey("pattern") && args.containsKey("count") -> {
                        validateArgs(args, allowed("selector", "attrName", "pattern", "count"), setOf("selector", "attrName", "pattern", "count"), functionName)
                        driver.clickMatches(selector = paramString(args, "selector", functionName)!!, attrName = paramString(args, "attrName", functionName)!!, pattern = paramString(args, "pattern", functionName)!!, count = paramInt(args, "count", functionName)!!)
                    }
                    args.containsKey("selector") && args.containsKey("attrName") && args.containsKey("pattern") -> {
                        validateArgs(args, allowed("selector", "attrName", "pattern"), setOf("selector", "attrName", "pattern"), functionName)
                        driver.clickMatches(selector = paramString(args, "selector", functionName)!!, attrName = paramString(args, "attrName", functionName)!!, pattern = paramString(args, "pattern", functionName)!!)
                    }
                    else -> throw IllegalArgumentException("clickMatches requires 'selector','attrName','pattern' (optional 'count')")
                }
            }
            "clickNthAnchor" -> {
                when {
                    args.containsKey("n") && args.containsKey("rootSelector") -> {
                        validateArgs(args, allowed("n", "rootSelector"), setOf("n", "rootSelector"), functionName)
                        driver.clickNthAnchor(n = paramInt(args, "n", functionName)!!, rootSelector = paramString(args, "rootSelector", functionName)!!)
                    }
                    args.containsKey("n") -> {
                        validateArgs(args, allowed("n"), setOf("n"), functionName)
                        driver.clickNthAnchor(n = paramInt(args, "n", functionName)!!)
                    }
                    else -> throw IllegalArgumentException("clickNthAnchor requires 'n' (optional 'rootSelector')")
                }
            }
            "check" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.check(paramString(args, "selector", functionName)!!) }
            "uncheck" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.uncheck(paramString(args, "selector", functionName)!!) }
            "scrollTo" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.scrollTo(paramString(args, "selector", functionName)!!) }
            "bringToFront" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.bringToFront() }
            "chat" -> { validateArgs(args, allowed("prompt", "selector"), setOf("prompt", "selector"), functionName); driver.chat(prompt = paramString(args, "prompt", functionName)!!, selector = paramString(args, "selector", functionName)!!) }

            // Scrolling
            "scrollDown" -> { validateArgs(args, if (args.isEmpty()) emptySet() else allowed("count"), emptySet(), functionName); driver.scrollDown(count = if (args.isEmpty()) 1 else paramInt(args, "count", functionName)!!) }
            "scrollUp" -> { validateArgs(args, if (args.isEmpty()) emptySet() else allowed("count"), emptySet(), functionName); driver.scrollUp(count = if (args.isEmpty()) 1 else paramInt(args, "count", functionName)!!) }
            "scrollBy" -> { validateArgs(args, allowed("pixels", "smooth"), emptySet(), functionName); driver.scrollBy(pixels = args["pixels"]?.toString()?.toDoubleOrNull() ?: 200.0, smooth = args["smooth"]?.toString()?.lowercase()?.let { it == "true" } ?: true) }
            "scrollToTop" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.scrollToTop() }
            "scrollToBottom" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.scrollToBottom() }
            "scrollToMiddle" -> { validateArgs(args, allowed("ratio"), setOf("ratio"), functionName); driver.scrollToMiddle(paramDouble(args, "ratio", functionName)!!) }
            "scrollToViewport" -> { validateArgs(args, allowed("n"), setOf("n"), functionName); driver.scrollToViewport(paramDouble(args, "n", functionName)!!) }
            "scrollToScreen" -> { validateArgs(args, allowed("screenNumber"), setOf("screenNumber"), functionName); driver.scrollToViewport(paramDouble(args, "screenNumber", functionName)!!) }

            // Mouse wheel / movement
            "mouseWheelDown" -> { validateArgs(args, allowed("count", "deltaX", "deltaY", "delayMillis"), emptySet(), functionName); driver.mouseWheelDown(count = args["count"]?.toString()?.toIntOrNull() ?: 1, deltaX = args["deltaX"]?.toString()?.toDoubleOrNull() ?: 0.0, deltaY = args["deltaY"]?.toString()?.toDoubleOrNull() ?: 150.0, delayMillis = args["delayMillis"]?.toString()?.toLongOrNull() ?: 0L) }
            "mouseWheelUp" -> { validateArgs(args, allowed("count", "deltaX", "deltaY", "delayMillis"), emptySet(), functionName); driver.mouseWheelUp(count = args["count"]?.toString()?.toIntOrNull() ?: 1, deltaX = args["deltaX"]?.toString()?.toDoubleOrNull() ?: 0.0, deltaY = args["deltaY"]?.toString()?.toDoubleOrNull() ?: -150.0, delayMillis = args["delayMillis"]?.toString()?.toLongOrNull() ?: 0L) }
            "moveMouseTo" -> {
                when {
                    args.containsKey("x") && args.containsKey("y") -> { validateArgs(args, allowed("x", "y"), setOf("x", "y"), functionName); driver.moveMouseTo(paramDouble(args, "x", functionName)!!, paramDouble(args, "y", functionName)!!) }
                    args.containsKey("selector") -> { validateArgs(args, allowed("selector", "deltaX", "deltaY"), setOf("selector"), functionName); driver.moveMouseTo(paramString(args, "selector", functionName)!!, paramInt(args, "deltaX", functionName, required = false, default = 0)!!, paramInt(args, "deltaY", functionName, required = false, default = 0)!!) }
                    else -> throw IllegalArgumentException("moveMouseTo requires ('x','y') or 'selector' (+ optional deltaX, deltaY)")
                }
            }
            "dragAndDrop" -> { validateArgs(args, allowed("selector", "deltaX", "deltaY"), setOf("selector", "deltaX"), functionName); driver.dragAndDrop(paramString(args, "selector", functionName)!!, paramInt(args, "deltaX", functionName)!!, paramInt(args, "deltaY", functionName, required = false, default = 0)!!) }
            "captureScreenshot" -> {
                when {
                    args.isEmpty() -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.captureScreenshot() }
                    args.containsKey("selector") -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.captureScreenshot(paramString(args, "selector", functionName)!!) }
                    args.containsKey("fullPage") -> { validateArgs(args, allowed("fullPage"), setOf("fullPage"), functionName); driver.captureScreenshot(paramBool(args, "fullPage", functionName)!!) }
                    else -> throw IllegalArgumentException("captureScreenshot allows none or one of: 'selector' | 'fullPage'")
                }
            }

            // HTML / Text
            "outerHTML" -> { if (args.isEmpty()) { validateArgs(args, emptySet(), emptySet(), functionName); driver.outerHTML() } else { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.outerHTML(paramString(args, "selector", functionName)!!) } }
            "textContent" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.textContent() }
            "nanoDOMTree" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.nanoDOMTree() }
            "selectFirstTextOrNull" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.selectFirstTextOrNull(paramString(args, "selector", functionName)!!) }
            "selectTextAll" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.selectTextAll(paramString(args, "selector", functionName)!!) }
            "selectFirstAttributeOrNull" -> { validateArgs(args, allowed("selector", "attrName"), setOf("selector", "attrName"), functionName); driver.selectFirstAttributeOrNull(paramString(args, "selector", functionName)!!, paramString(args, "attrName", functionName)!!) }
            "selectAttributes" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.selectAttributes(paramString(args, "selector", functionName)!!) }
            "selectAttributeAll" -> {
                when {
                    args.containsKey("selector") && args.containsKey("attrName") && (args.containsKey("start") || args.containsKey("limit")) -> {
                        validateArgs(args, allowed("selector", "attrName", "start", "limit"), setOf("selector", "attrName"), functionName)
                        driver.selectAttributeAll(selector = paramString(args, "selector", functionName)!!, attrName = paramString(args, "attrName", functionName)!!, start = paramInt(args, "start", functionName, required = false, default = 0)!!, limit = paramInt(args, "limit", functionName, required = false, default = 10000)!!)
                    }
                    args.containsKey("selector") && args.containsKey("attrName") -> { validateArgs(args, allowed("selector", "attrName"), setOf("selector", "attrName"), functionName); driver.selectAttributeAll(paramString(args, "selector", functionName)!!, paramString(args, "attrName", functionName)!!) }
                    else -> throw IllegalArgumentException("selectAttributeAll requires 'selector','attrName' (optional 'start','limit')")
                }
            }
            "setAttribute" -> { validateArgs(args, allowed("selector", "attrName", "attrValue"), setOf("selector", "attrName", "attrValue"), functionName); driver.setAttribute(paramString(args, "selector", functionName)!!, paramString(args, "attrName", functionName)!!, paramString(args, "attrValue", functionName)!!) }
            "setAttributeAll" -> { validateArgs(args, allowed("selector", "attrName", "attrValue"), setOf("selector", "attrName", "attrValue"), functionName); driver.setAttributeAll(paramString(args, "selector", functionName)!!, paramString(args, "attrName", functionName)!!, paramString(args, "attrValue", functionName)!!) }

            // Property selection / set
            "selectFirstPropertyValueOrNull" -> { validateArgs(args, allowed("selector", "propName"), setOf("selector", "propName"), functionName); driver.selectFirstPropertyValueOrNull(paramString(args, "selector", functionName)!!, paramString(args, "propName", functionName)!!) }
            "selectPropertyValueAll" -> {
                when {
                    args.containsKey("selector") && args.containsKey("propName") && (args.containsKey("start") || args.containsKey("limit")) -> {
                        validateArgs(args, allowed("selector", "propName", "start", "limit"), setOf("selector", "propName"), functionName)
                        driver.selectPropertyValueAll(selector = paramString(args, "selector", functionName)!!, propName = paramString(args, "propName", functionName)!!, start = paramInt(args, "start", functionName, required = false, default = 0)!!, limit = paramInt(args, "limit", functionName, required = false, default = 10000)!!)
                    }
                    args.containsKey("selector") && args.containsKey("propName") -> { validateArgs(args, allowed("selector", "propName"), setOf("selector", "propName"), functionName); driver.selectPropertyValueAll(paramString(args, "selector", functionName)!!, paramString(args, "propName", functionName)!!) }
                    else -> throw IllegalArgumentException("selectPropertyValueAll requires 'selector','propName' (optional 'start','limit')")
                }
            }
            "setProperty" -> { validateArgs(args, allowed("selector", "propName", "propValue"), setOf("selector", "propName", "propValue"), functionName); driver.setProperty(paramString(args, "selector", functionName)!!, paramString(args, "propName", functionName)!!, paramString(args, "propValue", functionName)!!) }
            "setPropertyAll" -> { validateArgs(args, allowed("selector", "propName", "propValue"), setOf("selector", "propName", "propValue"), functionName); driver.setPropertyAll(paramString(args, "selector", functionName)!!, paramString(args, "propName", functionName)!!, paramString(args, "propValue", functionName)!!) }

            // Hyperlinks / anchors / images
            "selectHyperlinks" -> { validateArgs(args, allowed("selector", "offset", "limit"), setOf("selector"), functionName); driver.selectHyperlinks(selector = paramString(args, "selector", functionName)!!, offset = paramInt(args, "offset", functionName, required = false, default = 1)!!, limit = paramInt(args, "limit", functionName, required = false, default = Int.MAX_VALUE)!!) }
            "selectAnchors" -> { validateArgs(args, allowed("selector", "offset", "limit"), setOf("selector"), functionName); driver.selectAnchors(selector = paramString(args, "selector", functionName)!!, offset = paramInt(args, "offset", functionName, required = false, default = 1)!!, limit = paramInt(args, "limit", functionName, required = false, default = Int.MAX_VALUE)!!) }
            "selectImages" -> { validateArgs(args, allowed("selector", "offset", "limit"), setOf("selector"), functionName); driver.selectImages(selector = paramString(args, "selector", functionName)!!, offset = paramInt(args, "offset", functionName, required = false, default = 1)!!, limit = paramInt(args, "limit", functionName, required = false, default = Int.MAX_VALUE)!!) }

            // JavaScript evaluation
            "evaluate" -> { validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluate(paramString(args, "expression", functionName)!!) }
            "evaluateDetail" -> { validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluateDetail(paramString(args, "expression", functionName)!!) }
            "evaluateValue" -> { validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluateValue(paramString(args, "expression", functionName)!!) }
            "evaluateValueDetail" -> { validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluateValueDetail(paramString(args, "expression", functionName)!!) }

            // Element geometry
            "clickablePoint" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.clickablePoint(paramString(args, "selector", functionName)!!) }
            "boundingBox" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.boundingBox(paramString(args, "selector", functionName)!!) }

            // Jsoup / resource
            "newJsoupSession" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.newJsoupSession() }
            "loadJsoupResource" -> { validateArgs(args, allowed("url"), setOf("url"), functionName); driver.loadJsoupResource(paramString(args, "url", functionName)!!) }
            "loadResource" -> { validateArgs(args, allowed("url"), setOf("url"), functionName); driver.loadResource(paramString(args, "url", functionName)!!) }

            // Cookies
            "getCookies" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.getCookies() }
            "deleteCookies" -> { validateArgs(args, allowed("name", "url", "domain", "path"), setOf("name"), functionName); driver.deleteCookies(name = paramString(args, "name", functionName)!!, url = paramString(args, "url", functionName, required = false), domain = paramString(args, "domain", functionName, required = false), path = paramString(args, "path", functionName, required = false)) }
            "clearBrowserCookies" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.clearBrowserCookies() }

            // Delay / pause / stop
            "delay" -> { validateArgs(args, if (args.isEmpty()) emptySet() else allowed("millis"), emptySet(), functionName); driver.delay(args["millis"]?.toString()?.toLongOrNull() ?: 1000L) }
            "pause" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.pause() }
            "stop" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.stop() }

            // URL & document info
            "currentUrl" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.currentUrl() }
            "url" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.url() }
            "documentURI" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.documentURI() }
            "baseURI" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.baseURI() }
            "referrer" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.referrer() }
            "pageSource" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.pageSource() }

            else -> throw IllegalArgumentException("Unsupported WebDriver tool function: $functionName")
        }
    }
}
