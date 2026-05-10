package ai.platon.pulsar.protocol.browser.driver.cdt.detail

import ai.platon.browser4.driver.chrome.NodeRef
import ai.platon.browser4.driver.chrome.PageHandler
import ai.platon.browser4.driver.chrome.util.ChromeDriverException
import ai.platon.cdt.kt.protocol.commands.Fetch
import ai.platon.cdt.kt.protocol.events.network.ResponseReceived
import ai.platon.cdt.kt.protocol.types.network.ResourceType
import ai.platon.cdt.kt.protocol.types.runtime.CallFunctionOn
import ai.platon.cdt.kt.protocol.types.runtime.Evaluate
import ai.platon.pulsar.common.AppPaths
import ai.platon.pulsar.common.MultiSinkMessageWriter
import ai.platon.pulsar.common.alwaysFalse
import ai.platon.pulsar.common.warnInterruptible
import ai.platon.pulsar.skeleton.workflow.common.InternalURLUtil
import ai.platon.pulsar.skeleton.workflow.fetch.driver.JsEvaluation
import ai.platon.pulsar.skeleton.workflow.fetch.driver.JsException
import ai.platon.pulsar.skeleton.workflow.fetch.driver.NavigateEntry
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

class WebDriverHelper(
    val driver: WebDriver,
    val rpc: RobustRPC,
    val page: PageHandler,
    val fetchAPI: Fetch?,
    val messageWriter: MultiSinkMessageWriter
) {
    suspend fun reportInterestingResources(entry: NavigateEntry, event: ResponseReceived) {
        runCatching { traceInterestingResources0(entry, event) }.onFailure { warnInterruptible(this, it) }
    }

    suspend fun traceInterestingResources0(entry: NavigateEntry, event: ResponseReceived) {
        val mimeType = event.response.mimeType
        val mimeTypes = listOf("application/json")
        if (mimeType !in mimeTypes) {
            return
        }

        val resourceTypes = listOf(
            ResourceType.FETCH,
            ResourceType.XHR,
            ResourceType.SCRIPT,
        )
        if (event.type !in resourceTypes) {
            // return
        }

        // page url is normalized
        val pageUrl = entry.pageUrl
        val resourceUrl = event.response.url
        val host = InternalURLUtil.getHost(pageUrl) ?: "unknown"
        val reportDir = messageWriter.baseDir.resolve("trace").resolve(host)

        if (!Files.exists(reportDir)) {
            withContext(Dispatchers.IO) {
                Files.createDirectories(reportDir)
            }
        }

        val count = withContext(Dispatchers.IO) {
            Files.list(reportDir)
        }.count()
        if (count > 2_000) {
            // TOO MANY tracing
            return
        }

        var suffix = "-" + event.type.name.lowercase() + "-urls.txt"
        var filename = AppPaths.md5Hex(pageUrl) + suffix
        var path = reportDir.resolve(filename)

        val message = String.format("%s\t%s", mimeType, event.response.url)
        messageWriter.writeTo(message, path)

        // configurable
        val saveResourceBody =
            mimeType == "application/json" && event.response.encodedDataLength < 1_000_000 && alwaysFalse()
        if (saveResourceBody) {
            val body = rpc.invokeSilently("getResponseBody") {
                fetchAPI?.enable()
                fetchAPI?.getResponseBody(event.requestId)?.body
            }
            if (!body.isNullOrBlank()) {
                suffix = "-" + event.type.name.lowercase() + "-body.txt"
                filename = AppPaths.fromUri(resourceUrl, suffix = suffix)
                path = reportDir.resolve(filename)
                messageWriter.writeTo(body, path)
            }
        }
    }

    fun serialize(cookie: ai.platon.cdt.kt.protocol.types.network.Cookie): Map<String, String> {
        val mapper = jacksonObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        return mapper.readValue(mapper.writeValueAsString(cookie))
    }

    suspend fun <T> invokeOnPage(
        name: String,
        url: String? = null,
        message: String? = null,
        action: suspend () -> T
    ): T? {
        try {
            return rpc.invokeWithRetry(name, url = url) {
                action()
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, name, message)
        }

        return null
    }

    suspend fun <T> invokeOnElement(
        selector: String,
        name: String,
        focus: Boolean = false,
        scrollIntoView: Boolean = false,
        action: suspend (NodeRef) -> T
    ): T? {
        try {
            return rpc.invokeWithRetry(name) {
                val node = if (focus) {
                    page.focusOnSelector(selector)
                } else if (scrollIntoView) {
                    page.scrollIntoViewIfNeeded(selector)
                } else {
                    page.queryLocator(selector)
                }

                if (node != null) {
                    action(node)
                } else {
                    null
                }
            }
        } catch (e: ChromeDriverException) {
            rpc.handleChromeException(e, name, "selector: [$selector], focus: $focus, scrollIntoView: $scrollIntoView")
        }

        return null
    }

    suspend fun predicateOnElement(
        selector: String,
        name: String,
        focus: Boolean = false,
        scrollIntoView: Boolean = false,
        predicate: suspend (NodeRef) -> Boolean
    ): Boolean = invokeOnElement(selector, name, focus, scrollIntoView, predicate) == true

    fun createJsEvaluate(evaluate: Evaluate?): JsEvaluation? {
        evaluate ?: return null

        val result = evaluate.result
        val exception = evaluate.exceptionDetails

        return if (exception != null) {
            val jsException = JsException(
                text = exception.text,
                lineNumber = exception.lineNumber,
                columnNumber = exception.columnNumber,
                url = exception.url,
            )
            JsEvaluation(exception = jsException)
        } else {
            JsEvaluation(
                value = result.value,
                unserializableValue = result.unserializableValue,
                className = result.className,
                description = result.description
            )
        }
    }

    fun createJsEvaluate(evaluate: CallFunctionOn?): JsEvaluation? {
        evaluate ?: return null

        val result = evaluate.result
        val exception = evaluate.exceptionDetails

        return if (exception != null) {
            val jsException = JsException(
                text = exception.text,
                lineNumber = exception.lineNumber,
                columnNumber = exception.columnNumber,
                url = exception.url,
            )
            JsEvaluation(exception = jsException)
        } else {
            JsEvaluation(
                value = result.value,
                unserializableValue = result.unserializableValue,
                className = result.className,
                description = result.description
            )
        }
    }
}
