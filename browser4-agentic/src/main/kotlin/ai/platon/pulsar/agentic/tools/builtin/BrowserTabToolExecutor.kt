package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.agentic.tools.specs.ToolSpecGenerator
import ai.platon.pulsar.skeleton.crawl.fetch.driver.NavigateEntry
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import java.time.Duration
import kotlin.reflect.KClass

class BrowserTabToolExecutor: AbstractToolExecutor() {
    override val domain = "tab"

    override val receiverClass: KClass<*> = WebDriver::class

    init {
        ToolSpecGenerator.apply {
            generateAllOnce()
            webDriverToolSpecs.associateByTo(toolSpec) { it.method }
        }
    }

    override fun help(method: String): String {
        val spec = toolSpec[method] ?: return "No help available for unknown method: $method"

        return spec.help ?: """
            ${spec.expression}
            ${spec.description}
        """.trimIndent()
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

        return when (functionName) {
            // Navigation
            "open" -> { validateArgs(args, allowed("url"), setOf("url"), functionName); driver.open(paramString(args, "url", functionName)!!) }
            "navigate" -> {
                when {
                    args.containsKey("url") -> { validateArgs(args, allowed("url"), setOf("url"), functionName); driver.navigate(paramString(args, "url", functionName)!!) }
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
                    }
                    else -> throw IllegalArgumentException("navigate requires 'url' or ('rawUrl','pageUrl')")
                }
            }
            "reload" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.reload() }
            "goBack" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.goBack() }
            "goForward" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.goForward() }

            // Wait
            "waitForSelector" -> {
                when {
                    args.isEmpty() -> throw IllegalArgumentException("waitForSelector requires 'selector' (optional 'timeoutMillis')")
                    args.containsKey("selector") && !args.containsKey("timeoutMillis") -> {
                        validateArgs(args, allowed("selector"), setOf("selector"), functionName)
                        driver.waitForSelector(paramString(args, "selector", functionName)!!)
                    }
                    args.containsKey("selector") && args.containsKey("timeoutMillis") -> {
                        validateArgs(args, allowed("selector", "timeoutMillis"), setOf("selector", "timeoutMillis"), functionName)
                        driver.waitForSelector(paramString(args, "selector", functionName)!!, paramLong(args, "timeoutMillis", functionName)!!)
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
            "exists" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.exists(paramString(args, "selector", functionName)!!) }
            "isVisible" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isVisible(paramString(args, "selector", functionName)!!) }
            "visible" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isVisible(paramString(args, "selector", functionName)!!) }
            "isHidden" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isHidden(paramString(args, "selector", functionName)!!) }
            "isChecked" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.isChecked(paramString(args, "selector", functionName)!!) }

            // Interactions
            "focus" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.focus(paramString(args, "selector", functionName)!!) }
            "hover" -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.hover(paramString(args, "selector", functionName)!!) }
            "type" -> { validateArgs(args, allowed("selector", "text"), setOf("selector", "text"), functionName); driver.type(paramString(args, "selector", functionName)!!, paramString(args, "text", functionName)!!) }
            "fill" -> { validateArgs(args, allowed("selector", "text"), setOf("selector", "text"), functionName); driver.fill(paramString(args, "selector", functionName)!!, paramString(args, "text", functionName)!!) }
            "press" -> { validateArgs(args, allowed("selector", "key"), setOf("selector", "key"), functionName); driver.press(paramString(args, "selector", functionName)!!, paramString(args, "key", functionName)!!) }
            "click" -> {
                when {
                    args.containsKey("selector") && args.containsKey("count") && !args.containsKey("modifier") -> {
                        validateArgs(args, allowed("selector", "count"), setOf("selector", "count"), functionName)
                        driver.click(selector = paramString(args, "selector", functionName)!!, count = paramInt(args, "count", functionName)!!)
                    }
                    args.containsKey("selector") && args.containsKey("modifier") && !args.containsKey("count") -> {
                        validateArgs(args, allowed("selector", "modifier"), setOf("selector", "modifier"), functionName)
                        driver.click(selector = paramString(args, "selector", functionName)!!, modifier = paramString(args, "modifier", functionName)!!)
                    }
                    args.containsKey("selector") && !args.containsKey("count") && !args.containsKey("modifier") -> {
                        validateArgs(args, allowed("selector"), setOf("selector"), functionName)
                        driver.click(selector = paramString(args, "selector", functionName)!!)
                    }
                    else -> throw IllegalArgumentException("click requires 'selector' plus optionally one of 'count' or 'modifier'")
                }
            }
            "dblclick" -> {
                validateArgs(args, if (args.containsKey("modifier")) allowed("selector", "modifier") else allowed("selector"), setOf("selector"), functionName)
                if (args.containsKey("modifier")) {
                    driver.dblclick(selector = paramString(args, "selector", functionName)!!, modifier = paramString(args, "modifier", functionName)!!)
                } else {
                    driver.dblclick(selector = paramString(args, "selector", functionName)!!)
                }
            }
            "upload" -> {
                validateArgs(args, allowed("selector", "paths"), setOf("selector", "paths"), functionName)
                val paths = args["paths"] as? List<String> ?: throw IllegalArgumentException("paths must be a list of strings")
                driver.upload(selector = paramString(args, "selector", functionName)!!, paths = paths)
            }
            "selectOption" -> {
                validateArgs(args, allowed("selector", "values"), setOf("selector", "values"), functionName)
                val values = args["values"] as? List<String> ?: throw IllegalArgumentException("values must be a list of strings")
                driver.selectOption(selector = paramString(args, "selector", functionName)!!, values = values)
            }
            "ariaSnapshot" -> {
                validateArgs(args, allowed("viewports"), emptySet(), functionName)
                val viewports = paramString(args, "viewports", functionName, required = false)
                if (viewports.isNullOrBlank()) driver.ariaSnapshot() else driver.ariaSnapshot(viewports)
            }
            "title" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.title() }
            "dialogAccept" -> {
                validateArgs(args, allowed("promptText"), emptySet(), functionName)
                driver.dialogAccept(paramString(args, "promptText", functionName, required = false))
            }
            "dialogDismiss" -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.dialogDismiss() }
            "resize" -> {
                validateArgs(args, allowed("width", "height"), setOf("width", "height"), functionName)
                driver.resize(width = paramInt(args, "width", functionName)!!, height = paramInt(args, "height", functionName)!!)
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
            "dragAndDrop" -> { validateArgs(args, allowed("selector", "deltaX", "deltaY"), setOf("selector", "deltaX"), functionName); driver.dragAndDrop(paramString(args, "selector", functionName)!!, paramInt(args, "deltaX", functionName)!!, paramInt(args, "deltaY", functionName, required = false, default = 0)!!) }
            "drag" -> {
                validateArgs(args, allowed("sourceSelector", "targetSelector"), setOf("sourceSelector", "targetSelector"), functionName)
                driver.drag(
                    sourceSelector = paramString(args, "sourceSelector", functionName)!!,
                    targetSelector = paramString(args, "targetSelector", functionName)!!
                )
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
            "mouseWheel" -> {
                validateArgs(args, allowed("deltaX", "deltaY"), emptySet(), functionName)
                driver.mouseWheel(
                    deltaX = args["deltaX"]?.toString()?.toDoubleOrNull() ?: 0.0,
                    deltaY = args["deltaY"]?.toString()?.toDoubleOrNull() ?: 150.0
                )
            }
            "moveMouseTo", "mouseMove" -> {
                when {
                    args.containsKey("x") && args.containsKey("y") -> {
                        validateArgs(args, allowed("x", "y"), setOf("x", "y"), functionName)
                        if (functionName == "mouseMove") {
                            driver.mouseMove(paramDouble(args, "x", functionName)!!, paramDouble(args, "y", functionName)!!)
                        } else {
                            driver.moveMouseTo(paramDouble(args, "x", functionName)!!, paramDouble(args, "y", functionName)!!)
                        }
                    }
                    args.containsKey("selector") -> { validateArgs(args, allowed("selector", "deltaX", "deltaY"), setOf("selector"), functionName); driver.moveMouseTo(paramString(args, "selector", functionName)!!, paramInt(args, "deltaX", functionName, required = false, default = 0)!!, paramInt(args, "deltaY", functionName, required = false, default = 0)!!) }
                    else -> throw IllegalArgumentException("moveMouseTo requires ('x','y') or 'selector' (+ optional deltaX, deltaY)")
                }
            }
            "screenshot" -> {
                when {
                    args.isEmpty() -> { validateArgs(args, emptySet(), emptySet(), functionName); driver.screenshot() }
                    args.containsKey("selector") -> { validateArgs(args, allowed("selector"), setOf("selector"), functionName); driver.screenshot(paramString(args, "selector", functionName)!!) }
                    args.containsKey("fullPage") -> { validateArgs(args, allowed("fullPage"), setOf("fullPage"), functionName); driver.screenshot(paramBool(args, "fullPage", functionName)!!) }
                    else -> throw IllegalArgumentException("screenshot allows none or one of: 'selector' | 'fullPage'")
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

            // JavaScript evaluation
            "evaluate" -> { validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluate(paramString(args, "expression", functionName)!!) }
            "evaluateDetail" -> { validateArgs(args, allowed("expression"), setOf("expression"), functionName); driver.evaluateDetail(paramString(args, "expression", functionName)!!) }
            "evaluateValue" -> {
                when {
                    args.containsKey("selector") && args.containsKey("functionDeclaration") -> {
                        validateArgs(args, allowed("selector", "functionDeclaration"), setOf("selector", "functionDeclaration"), functionName)
                        driver.evaluateValue(
                            paramString(args, "selector", functionName)!!,
                            paramString(args, "functionDeclaration", functionName)!!
                        )
                    }
                    args.containsKey("expression") -> {
                        validateArgs(args, allowed("expression"), setOf("expression"), functionName)
                        driver.evaluateValue(paramString(args, "expression", functionName)!!)
                    }
                    else -> throw IllegalArgumentException("evaluateValue requires 'expression' or ('selector','functionDeclaration')")
                }
            }
            "evaluateValueDetail" -> {
                when {
                    args.containsKey("selector") && args.containsKey("functionDeclaration") -> {
                        validateArgs(args, allowed("selector", "functionDeclaration"), setOf("selector", "functionDeclaration"), functionName)
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

            "help" -> help()

            else -> throw IllegalArgumentException("Unsupported WebDriver tool function: $functionName, call $domain.help() for more details")
        }
    }
}
