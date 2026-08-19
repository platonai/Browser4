package ai.platon.pulsar.forms.service

import ai.platon.pulsar.api.WebDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resumeWithException

class FormsServiceTest {

    @Test
    fun `successfully returns browser JSON string`() = runBlocking {
        val service = FormsService()

        val result = service.detectForms(webDriver(fail = false))

        assertEquals(BROWSER_JSON, result)
    }

    @Test
    fun `returns map with error key when evaluateValue fails`() = runBlocking {
        val service = FormsService()

        val result = service.detectForms(webDriver(fail = true))

        assertTrue(result is Map<*, *>)
        assertEquals("script failed", (result as Map<*, *>)["error"])
    }

    private fun webDriver(fail: Boolean): WebDriver {
        return Proxy.newProxyInstance(
            WebDriver::class.java.classLoader,
            arrayOf(WebDriver::class.java)
        ) { _, method, args ->
            when (method.name) {
                "evaluateValue" -> {
                    if (fail) {
                        val continuation = args!!.last() as Continuation<Any?>
                        continuation.resumeWithException(IllegalStateException("script failed"))
                        COROUTINE_SUSPENDED
                    } else {
                        BROWSER_JSON
                    }
                }

                "currentUrl" -> CURRENT_URL
                else -> throw UnsupportedOperationException("Unexpected method: ${method.name}")
            }
        } as WebDriver
    }

    private companion object {
        const val BROWSER_JSON = """{"forms":1,"fields":3}"""
        const val CURRENT_URL = "https://example.com/forms"
    }
}
