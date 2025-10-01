package ai.platon.pulsar.skeleton.ai

import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.getLogger
import ai.platon.pulsar.external.ModelResponse
import ai.platon.pulsar.skeleton.ai.tta.ActionOptions
import ai.platon.pulsar.skeleton.ai.tta.TextToAction
import ai.platon.pulsar.skeleton.crawl.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import java.nio.file.Files
import java.time.Instant
import java.util.*

class WebDriverAgent(
    val driver: WebDriver,
    val maxSteps: Int = 100,
    private val actionGenerator: (suspend (String, WebDriver, String?) -> ai.platon.pulsar.skeleton.ai.tta.ActionDescription)? = null,
    private val executionStrategy: ActionExecutionStrategy = DefaultActionExecutionStrategy,
) {
    private val logger = getLogger(this)

    val uuid = UUID.randomUUID()
    val baseDir = AppPaths.get("agent")
    val conf get() = (driver as? AbstractWebDriver)?.settings?.config ?: ai.platon.pulsar.common.config.ImmutableConfig()

    private val tta by lazy { TextToAction(conf) }

    private val model get() = tta.model

    private val history = mutableListOf<String>()

    suspend fun execute(action: ActionOptions): ModelResponse {
        Files.createDirectories(baseDir)

        val instruction = action.action

        logger.info("Starting WebDriverAgent execution for instruction: {}", instruction.take(100))

        val systemPrompt = buildOperatorSystemPrompt(instruction)
        val startTime = Instant.now()
        var consecutiveNoOps = 0

        var step = 0
        while (step < maxSteps) {
            step++
            logger.debug("Executing step {} of {} for instruction", step, maxSteps)

            val screenshotB64 = safeScreenshot()
            val userMsg = buildUserMessage(instruction)

            val message = buildString {
                appendLine(systemPrompt)
                appendLine(userMsg)
                if (screenshotB64 != null) {
                    appendLine("[Current page screenshot provided as base64 image]")
                }
            }

            // Use TextToAction to generate EXACT ONE step
            val actionDesc = try {
                if (actionGenerator != null) {
                    actionGenerator.invoke(message, driver, screenshotB64)
                } else {
                    tta.generateWebDriverAction(message, driver, screenshotB64)
                }
            } catch (e: Exception) {
                logger.error("Failed to generate action at step {}: {}", step, e.message)
                history += "#$step ERROR: Action generation failed - ${e.message}"
                consecutiveNoOps++
                if (consecutiveNoOps >= 3) {
                    logger.warn("Too many consecutive action generation failures, stopping")
                    break
                }
                delay(500)
                continue
            }

            val response = actionDesc.modelResponse
            val parsed = parseOperatorResponse(response.content)

            // termination checks (supported if model responded with such fields)
            if (parsed.taskComplete == true || parsed.method?.equals("close", true) == true) {
                logger.info("Task completion detected at step {}: taskComplete={}, method={}",
                           step, parsed.taskComplete, parsed.method)
                runCatching { driver.close() }.onFailure { logger.warn("Close failed: {}", it.message) }
                history += "#$step complete: taskComplete=${parsed.taskComplete} method=${parsed.method}"
                break
            }

            val toolCall = parsed.toolCalls.firstOrNull()
            if (toolCall == null) {
                consecutiveNoOps++
                history += "#$step no-op (no tool_calls) - consecutive_no_ops: $consecutiveNoOps"
                logger.warn("No tool calls generated at step {} (consecutive: {})", step, consecutiveNoOps)

                // Stop if model keeps returning nothing
                if (consecutiveNoOps >= 5) {
                    logger.warn("Too many consecutive no-ops, stopping execution")
                    break
                }
                delay(250L * consecutiveNoOps) // Exponential backoff
                continue
            }

            // Handle explicit stop() tool call
            if (toolCall.name.equals("stop", ignoreCase = true)) {
                history += "#$step stop() received"
                logger.info("Stop tool call received at step {}, terminating.", step)
                break
            }

            // Reset consecutive no-ops counter when we have a valid action
            consecutiveNoOps = 0

            val execSummary = runCatching {
                executionStrategy.execute(driver, actionDesc, toolCall.name, toolCall.args)
            }.onFailure { e ->
                logger.error("Tool execution failed at step {}: {} - {}", step, toolCall.name, e.message)
                history += "#$step ERR ${toolCall.name}: ${e.message}"
            }.getOrElse { null }

            if (execSummary != null) {
                history += "#$step ${execSummary}"
                logger.debug("Step {} completed successfully: {}", step, execSummary)
            }

            // Brief pause between actions for stability
            delay(100)
        }

        val executionTime = java.time.Duration.between(startTime, Instant.now())
        logger.info("WebDriverAgent execution completed in {} steps over {}", step, executionTime)

        // Final summary
        val finalSummary = try {
            summarize(instruction)
        } catch (e: Exception) {
            logger.error("Failed to generate final summary: {}", e.message)
            ModelResponse("Failed to generate summary: ${e.message}", ai.platon.pulsar.external.ResponseState.OTHER)
        }

        history += "FINAL ${finalSummary.content.take(200)}" // keep history compact
        persistTranscript(instruction, finalSummary)

        return finalSummary
    }

    private fun buildOperatorSystemPrompt(goal: String): String {
        return """
你是一个网页通用代理，目标是基于用户目标一步一步完成任务。
重要指南：
1) 将复杂动作拆成原子步骤；
2) act 一次仅做一个动作（单击一次、输入一次、选择一次）；
3) 不要在一步中合并多个动作；
4) 多个动作用多步表达；
5) 始终验证目标元素存在且可见后再执行操作；
6) 遇到错误时尝试替代方案或优雅终止；
7) 若任务完成或无法继续，请调用 stop() 或设置 taskComplete=true。

输出严格使用 JSON，字段：
- tool_calls: [ { name: string, args: object } ] // 最多 1 个
- taskComplete: boolean // 可选
- method: string // 可选，'close' 时终止

安全要求：
- 仅操作可见的交互元素
- 避免快速连续操作，适当等待页面加载
- 遇到验证码或安全提示时停止执行

工具规范：
${tta.TOOL_CALL_LIST}

终止条件：
- tool_calls 中的 name 为 stop
- taskComplete=true
- method=close
- 连续多次无操作或超过最大步数

用户总目标：$goal
        """.trimIndent()
    }

    private suspend fun buildUserMessage(instruction: String): String {
        val currentUrl = runCatching { driver.currentUrl() }.getOrNull().orEmpty()
        val h = if (history.isEmpty()) "(无)" else history.takeLast(8).joinToString("\n")
        return """
此前动作摘要：
$h

请基于当前页面截图与历史动作，规划下一步（严格单步原子动作），若无法推进请输出空 tool_calls。
目标：$instruction
当前URL：$currentUrl
        """.trimIndent()
    }

    private data class ToolCall(val name: String, val args: Map<String, Any?>)

    private data class ParsedResponse(
        val toolCalls: List<ToolCall>,
        val taskComplete: Boolean?,
        val method: String?
    )

    private fun parseOperatorResponse(content: String): ParsedResponse {
        // 1) Try structured JSON with tool_calls
        try {
            val root = JsonParser.parseString(content)
            if (root.isJsonObject) {
                val obj = root.asJsonObject
                val toolCalls = mutableListOf<ToolCall>()
                if (obj.has("tool_calls") && obj.get("tool_calls").isJsonArray) {
                    obj.getAsJsonArray("tool_calls").forEach { el ->
                        if (el.isJsonObject) {
                            val tc = el.asJsonObject
                            val name = tc["name"]?.takeIf { it.isJsonPrimitive }?.asString
                            val args = (tc.getAsJsonObject("args") ?: JsonObject()).entrySet()
                                .associate { (k, v) -> k to jsonElementToKotlin(v) }
                            if (!name.isNullOrBlank()) toolCalls += ToolCall(name, args)
                        }
                    }
                }
                val taskComplete = obj.get("taskComplete")?.takeIf { it.isJsonPrimitive }?.asBoolean
                val method = obj.get("method")?.takeIf { it.isJsonPrimitive }?.asString
                if (toolCalls.isNotEmpty() || taskComplete != null || method != null) {
                    return ParsedResponse(toolCalls.take(1), taskComplete, method)
                }
            }
        } catch (_: Exception) { /* fall back */
        }

        // 2) Fall back to TTA tool call JSON parser
        return runCatching {
            val calls = tta.parseToolCalls(content).map { ToolCall(it.name, it.args) }
            ParsedResponse(calls.take(1), null, null)
        }.getOrElse { ParsedResponse(emptyList(), null, null) }
    }

    private fun jsonElementToKotlin(e: com.google.gson.JsonElement): Any? = when {
        e.isJsonNull -> null
        e.isJsonPrimitive -> {
            val p = e.asJsonPrimitive
            when {
                p.isBoolean -> p.asBoolean
                p.isNumber -> {
                    val n = p.asNumber
                    val d = n.toDouble();
                    val i = n.toInt(); if (d == i.toDouble()) i else d
                }

                else -> p.asString
            }
        }

        e.isJsonArray -> e.asJsonArray.map { jsonElementToKotlin(it) }
        e.isJsonObject -> e.asJsonObject.entrySet().associate { it.key to jsonElementToKotlin(it.value) }
        else -> null
    }

    private suspend fun executeToolCall(tc: ToolCall): String {
        return when (tc.name) {
            // Navigation
            "navigateTo", "goto" -> {
                val url = tc.args["url"]?.toString()?.trim().orEmpty()
                if (url.isBlank()) return "skip navigateTo (blank url)"
                val old = runCatching { driver.currentUrl() }.getOrNull()
                driver.navigateTo(url)
                runCatching { driver.waitForNavigation(old ?: "") }
                "navigateTo -> $url"
            }

            // Wait
            "waitForSelector" -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                val timeout = (tc.args["timeoutMillis"] as? Number)?.toLong() ?: 5000L
                if (selector.isBlank()) return "skip waitForSelector (blank selector)"
                driver.waitForSelector(selector, timeout)
                "waitForSelector -> $selector (${timeout}ms)"
            }

            // Basic interactions
            "click" -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                if (selector.isBlank()) return "skip click (blank selector)"
                val count = (tc.args["count"] as? Number)?.toInt() ?: 1
                driver.click(selector, count)
                "click -> $selector x$count"
            }
            overrideName("fill") -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                val text = tc.args["text"]?.toString() ?: ""
                if (selector.isBlank()) return "skip fill (blank selector)"
                driver.fill(selector, text)
                "fill -> $selector text(${text.take(20)})"
            }
            "press" -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                val key = tc.args["key"]?.toString()?.trim().orEmpty()
                if (selector.isBlank() || key.isBlank()) return "skip press (blank args)"
                driver.press(selector, key)
                "press -> $selector key=$key"
            }
            "check" -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                if (selector.isBlank()) return "skip check (blank selector)"
                driver.check(selector)
                "check -> $selector"
            }
            "uncheck" -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                if (selector.isBlank()) return "skip uncheck (blank selector)"
                driver.uncheck(selector)
                "uncheck -> $selector"
            }

            // Scrolling
            "scrollDown" -> { val c = (tc.args["count"] as? Number)?.toInt() ?: 1; driver.scrollDown(c); "scrollDown -> $c" }
            "scrollUp" -> { val c = (tc.args["count"] as? Number)?.toInt() ?: 1; driver.scrollUp(c); "scrollUp -> $c" }
            "scrollToTop" -> { driver.scrollToTop(); "scrollToTop" }
            "scrollToBottom" -> { driver.scrollToBottom(); "scrollToBottom" }
            "scrollToMiddle" -> { val r = (tc.args["ratio"] as? Number)?.toDouble() ?: 0.5; driver.scrollToMiddle(r); "scrollToMiddle -> $r" }
            "scrollToScreen" -> { val n = (tc.args["screenNumber"] as? Number)?.toDouble() ?: 0.0; driver.scrollToScreen(n); "scrollToScreen -> $n" }

            // Advanced clicks
            "clickTextMatches" -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                val pattern = tc.args["pattern"]?.toString()?.trim().orEmpty()
                val count = (tc.args["count"] as? Number)?.toInt() ?: 1
                if (selector.isBlank() || pattern.isBlank()) return "skip clickTextMatches (blank args)"
                driver.clickTextMatches(selector, pattern, count)
                "clickTextMatches -> $selector / $pattern ($count)"
            }
            "clickMatches" -> {
                val selector = tc.args["selector"]?.toString()?.trim().orEmpty()
                val attr = tc.args["attrName"]?.toString()?.trim().orEmpty()
                val pattern = tc.args["pattern"]?.toString()?.trim().orEmpty()
                val count = (tc.args["count"] as? Number)?.toInt() ?: 1
                if (selector.isBlank() || attr.isBlank() || pattern.isBlank()) return "skip clickMatches (blank args)"
                driver.clickMatches(selector, attr, pattern, count)
                "clickMatches -> $selector @$attr~$pattern ($count)"
            }
            "clickNthAnchor" -> {
                val n = (tc.args["n"] as? Number)?.toInt() ?: 0
                val root = tc.args["rootSelector"]?.toString() ?: "body"
                val href = driver.clickNthAnchor(n, root)
                "clickNthAnchor -> #$n in $root => $href"
            }

            // Screenshots
            "captureScreenshot" -> {
                val sel = tc.args["selector"]?.toString()?.trim().orEmpty()
                val b64 = if (sel.isBlank()) driver.captureScreenshot() else driver.captureScreenshot(sel)
                if (b64 != null) saveStepScreenshot(b64)
                val target = sel.ifBlank { "page" }
                "captureScreenshot -> $target"
            }

            // Timing
            "delay" -> {
                val ms = (tc.args["millis"] as? Number)?.toLong() ?: 1000L
                delay(ms)
                "delay -> $ms ms"
            }

            // Graceful unknowns
            else -> "skip unknown tool '${tc.name}'"
        }
    }

    private fun overrideName(name: String) = name // placeholder for future alias handling

    /**
     * Validates if a URL is safe to navigate to
     */
    private fun isSafeUrl(url: String): Boolean {
        if (url.isBlank()) return false

        return runCatching {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()

            // Only allow http and https schemes
            if (scheme !in setOf("http", "https")) {
                logger.warn("Blocked unsafe URL scheme: {}", scheme)
                return false
            }

            // Additional safety checks can be added here
            // - Domain whitelist/blacklist
            // - IP address validation
            // - Port validation

            true
        }.onFailure { e ->
            logger.warn("URL validation failed for '{}': {}", url, e.message)
        }.getOrElse { false }
    }

    private suspend fun safeScreenshot(): String? {
        return runCatching {
            logger.debug("Attempting to capture screenshot")
            val screenshot = driver.captureScreenshot()
            if (screenshot != null) {
                logger.debug("Screenshot captured successfully ({} bytes)", screenshot.length)
            } else {
                logger.warn("Screenshot capture returned null")
            }
            screenshot
        }.onFailure { e ->
            logger.error("Screenshot capture failed: {}", e.message, e)
        }.getOrNull()
    }

    private fun saveStepScreenshot(b64: String) {
        runCatching {
            val ts = Instant.now().toEpochMilli()
            val p = baseDir.resolve("screenshot-${ts}.b64")
            logger.debug("Saving step screenshot to: {}", p)
            Files.writeString(p, b64)
            logger.debug("Screenshot saved successfully ({} bytes)", b64.length)
        }.onFailure { e ->
            logger.warn("Save screenshot failed: {}", e.message, e)
        }
    }

    private fun persistTranscript(instruction: String, finalResp: ModelResponse) {
        runCatching {
            val ts = Instant.now().toEpochMilli()
            val log = baseDir.resolve("session-${ts}.log")
            logger.info("Persisting execution transcript to: {}", log)

            val sb = StringBuilder()
            sb.appendLine("INSTRUCTION: $instruction")
            sb.appendLine("EXECUTION_TIME: ${ts}")
            sb.appendLine("AGENT_UUID: $uuid")
            sb.appendLine("HISTORY:")
            val maxLines = 500
            val trimmedHistory = if (history.size > maxLines) history.takeLast(maxLines) else history
            trimmedHistory.forEach { sb.appendLine(it.take(500)) }
            sb.appendLine()
            sb.appendLine("FINAL_SUMMARY:")
            sb.appendLine(finalResp.content)
            sb.appendLine()
            sb.appendLine("RESPONSE_STATE: ${finalResp.state}")

            Files.writeString(log, sb.toString())
            logger.info("Transcript persisted successfully ({} lines)", history.size + 5)
        }.onFailure { e ->
            logger.error("Failed to persist transcript: {}", e.message, e)
        }
    }

    private fun buildSummaryPrompt(goal: String): Pair<String, String> {
        val system = "你是总结助理，请基于执行轨迹对原始目标进行总结，输出 JSON。"
        val user = buildString {
            appendLine("原始目标：$goal")
            appendLine("执行轨迹(按序)：")
            history.forEach { appendLine(it) }
            appendLine()
            appendLine("""请严格输出 JSON：{"taskComplete":bool,"summary":string,"keyFindings":[string],"nextSuggestions":[string]} 无多余文字。""")
        }
        return system to user
    }

    private fun summarize(goal: String): ModelResponse {
        if (model == null) {
            return ModelResponse("""{"taskComplete":false,"summary":"LLM not configured","keyFindings":[],"nextSuggestions":[]}""".trimIndent(), ai.platon.pulsar.external.ResponseState.OTHER)
        }
        val (system, user) = buildSummaryPrompt(goal)
        return model!!.call(user, system)
    }

    // Internal history snapshot accessor
    internal fun historySnapshot(): List<String> = history.toList()
}

// Simple strategy abstraction -------------------------------------------------------------
interface ActionExecutionStrategy {
    suspend fun execute(driver: WebDriver, actionDescription: ai.platon.pulsar.skeleton.ai.tta.ActionDescription, toolCallName: String, args: Map<String, Any?>): String?
}
object DefaultActionExecutionStrategy : ActionExecutionStrategy {
    override suspend fun execute(driver: WebDriver, actionDescription: ai.platon.pulsar.skeleton.ai.tta.ActionDescription, toolCallName: String, args: Map<String, Any?>): String? {
        // Delegate to driver.act for backward compatibility
        val result = driver.act(actionDescription)
        return result.toString()
    }
}
