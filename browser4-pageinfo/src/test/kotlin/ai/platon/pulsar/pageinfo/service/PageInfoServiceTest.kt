package ai.platon.pulsar.pageinfo.service

import ai.platon.pulsar.api.WebDriver
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Tests for [PageInfoService] using a lightweight [Proxy]-based [WebDriver] mock.
 *
 * `evaluateValue`/`currentUrl` are suspend functions, so the mock either returns
 * the value directly (caller resumes with it) or resumes the passed continuation
 * with an exception (caller sees the exception at the call site).
 */
class PageInfoServiceTest {

    @Test
    @DisplayName("extractPageInfo returns the browser-side JSON result")
    fun testExtractPageInfoSuccess() = runBlocking {
        val json = """{"url":"https://example.com","title":"Example"}"""
        val driver = webDriverProxy(evaluateResult = json)
        val service = PageInfoService()

        val result = service.extractPageInfo(driver)

        assertEquals(json, result)
    }

    @Test
    @DisplayName("extractPageInfo returns an error map when evaluation fails")
    fun testExtractPageInfoFailure() = runBlocking {
        val driver = webDriverProxy(evaluateResult = null, failEvaluate = true)
        val service = PageInfoService()

        val result = service.extractPageInfo(driver)

        assertTrue(result is Map<*, *>)
        assertTrue((result as Map<*, *>).containsKey("error"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun webDriverProxy(evaluateResult: Any?, failEvaluate: Boolean = false): WebDriver {
        return Proxy.newProxyInstance(
            WebDriver::class.java.classLoader,
            arrayOf(WebDriver::class.java)
        ) { _, method, args ->
            when (method.name) {
                "evaluateValue" -> {
                    if (failEvaluate) {
                        val continuation = args?.lastOrNull() as? Continuation<Any?>
                        if (continuation != null) {
                            continuation.resumeWithException(IllegalStateException("evaluate failed"))
                            COROUTINE_SUSPENDED
                        } else {
                            throw IllegalStateException("evaluate failed")
                        }
                    } else {
                        evaluateResult
                    }
                }
                "currentUrl" -> "https://example.com"
                else -> null
            }
        } as WebDriver
    }
}
