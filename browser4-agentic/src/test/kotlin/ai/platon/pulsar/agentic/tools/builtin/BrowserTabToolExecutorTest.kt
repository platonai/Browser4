package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.browser4.api.model.JsEvaluation
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.core.api.WebDriver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BrowserTabToolExecutorTest {
    private val executor = BrowserTabToolExecutor()

    @Test
    fun `type accepts focused element text only`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)

            executor.callFunctionOn(
                ToolCall("tab", "type", mutableMapOf<String, Any?>("text" to "hello")),
                driver
            )

            verify(driver).type("hello", null)
        }
    }

    @Test
    fun `type still accepts selector and text`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)

            executor.callFunctionOn(
                ToolCall("tab", "type", mutableMapOf<String, Any?>("selector" to "#q", "text" to "hello")),
                driver
            )

            verify(driver).type("hello", "#q")
        }
    }

    @Test
    fun `press accepts focused element key only`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)

            executor.callFunctionOn(
                ToolCall("tab", "press", mutableMapOf<String, Any?>("key" to "Enter")),
                driver
            )

            verify(driver).press("Enter", null)
        }
    }

    @Test
    fun `press still accepts selector and key`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)

            executor.callFunctionOn(
                ToolCall("tab", "press", mutableMapOf<String, Any?>("selector" to "#q", "key" to "Enter")),
                driver
            )

            verify(driver).press("Enter", "#q")
        }
    }

    @Test
    fun `help advertises selector optional type and press`() {
        val typeHelp = executor.help("type")
        val pressHelp = executor.help("press")

        assertTrue(typeHelp.contains("tab.type(text: String)"))
        assertTrue(typeHelp.contains("selector: String?"))
        assertTrue(pressHelp.contains("tab.press(key: String)"))
        assertTrue(pressHelp.contains("selector: String?"))
    }

    @Test
    fun `evaluateValue accepts page expression`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValueDetail("document.title"))
                .thenReturn(JsEvaluation(value = "Browser4 CLI Other Fixture"))

            val result = executor.callFunctionOn(
                ToolCall("tab", "evaluateValue", mutableMapOf<String, Any?>("expression" to "document.title")),
                driver
            )

            assertEquals("Browser4 CLI Other Fixture", result.value)
            verify(driver).evaluateValueDetail("document.title")
        }
    }

    @Test
    fun `evaluateValue accepts element selector and function declaration`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValueDetail("#page-marker", "(element) => element.textContent"))
                .thenReturn(JsEvaluation(value = "other page"))

            val result = executor.callFunctionOn(
                ToolCall(
                    "tab",
                    "evaluateValue",
                    mutableMapOf<String, Any?>(
                        "selector" to "#page-marker",
                        "functionDeclaration" to "(element) => element.textContent"
                    )
                ),
                driver
            )

            assertEquals("other page", result.value)
            verify(driver).evaluateValueDetail("#page-marker", "(element) => element.textContent")
        }
    }

    @Test
    fun `evaluateValue accepts element selector and expression`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValueDetail("#page-marker", "(element) => element.textContent"))
                .thenReturn(JsEvaluation(value = "other page"))

            val result = executor.callFunctionOn(
                ToolCall(
                    "tab",
                    "evaluateValue",
                    mutableMapOf<String, Any?>(
                        "selector" to "#page-marker",
                        "expression" to "(element) => element.textContent"
                    )
                ),
                driver
            )

            assertEquals("other page", result.value)
            verify(driver).evaluateValueDetail("#page-marker", "(element) => element.textContent")
        }
    }

    @Test
    fun `eval accepts page expression`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValueDetail("document.title"))
                .thenReturn(JsEvaluation(value = "Browser4 CLI Other Fixture"))

            val result = executor.callFunctionOn(
                ToolCall("tab", "eval", mutableMapOf<String, Any?>("expression" to "document.title")),
                driver
            )

            assertEquals("Browser4 CLI Other Fixture", result.value)
            verify(driver).evaluateValueDetail("document.title")
        }
    }

    @Test
    fun `eval accepts element selector and expression`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValueDetail("#page-marker", "(element) => element.textContent"))
                .thenReturn(JsEvaluation(value = "other page"))

            val result = executor.callFunctionOn(
                ToolCall(
                    "tab",
                    "eval",
                    mutableMapOf<String, Any?>(
                        "selector" to "#page-marker",
                        "expression" to "(element) => element.textContent"
                    )
                ),
                driver
            )

            assertEquals("other page", result.value)
            verify(driver).evaluateValueDetail("#page-marker", "(element) => element.textContent")
        }
    }

    @Test
    fun `screenshot viewport scrolls before capture`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValue("window.innerWidth")).thenReturn(1920)
            `when`(driver.evaluateValue("window.innerHeight")).thenReturn(1080)
            // scrollToViewport returns the actual scrollY after scrolling;
            // starting from top (scrollY=0): 0 + 2*1080 = 2160
            `when`(driver.scrollToViewport(2.0)).thenReturn(2160.0)

            executor.callFunctionOn(
                ToolCall("tab", "screenshot", mutableMapOf<String, Any?>("viewport" to 2)),
                driver
            )

            verify(driver).scrollToViewport(2.0)
            // Rect uses the actual scrollY returned by scrollToViewport
            verify(driver).screenshot(ai.platon.pulsar.common.math.geometric.RectD(0.0, 2160.0, 1920.0, 1080.0))
        }
    }

    @Test
    fun `screenshot viewport negative scrolls up from current position`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValue("window.innerWidth")).thenReturn(1920)
            `when`(driver.evaluateValue("window.innerHeight")).thenReturn(1080)
            // Starting from scrollY=2160 (viewport 2), -1 scrolls up one: 2160 + (-1*1080) = 1080
            `when`(driver.scrollToViewport(-1.0)).thenReturn(1080.0)

            executor.callFunctionOn(
                ToolCall("tab", "screenshot", mutableMapOf<String, Any?>("viewport" to -1)),
                driver
            )

            verify(driver).scrollToViewport(-1.0)
            verify(driver).screenshot(ai.platon.pulsar.common.math.geometric.RectD(0.0, 1080.0, 1920.0, 1080.0))
        }
    }

    @Test
    fun `screenshot viewport negative at top clamps to zero`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.evaluateValue("window.innerWidth")).thenReturn(1920)
            `when`(driver.evaluateValue("window.innerHeight")).thenReturn(1080)
            // Starting from top (scrollY=0): 0 + (-1*1080) = -1080, clamped to 0
            `when`(driver.scrollToViewport(-1.0)).thenReturn(0.0)

            executor.callFunctionOn(
                ToolCall("tab", "screenshot", mutableMapOf<String, Any?>("viewport" to -1)),
                driver
            )

            verify(driver).scrollToViewport(-1.0)
            verify(driver).screenshot(ai.platon.pulsar.common.math.geometric.RectD(0.0, 0.0, 1920.0, 1080.0))

            // ── awaitPromise tests ──────────────────────────────────────────────

            @Test
            fun `eval with awaitPromise calls two-arg evaluateValueDetail`() {
                runBlocking {
                    val driver = Mockito.mock(WebDriver::class.java)
                    `when`(driver.evaluateValueDetail("fetch('/api/data')", true))
                        .thenReturn(JsEvaluation(value = mapOf("status" to 200)))

                    val result = executor.callFunctionOn(
                        ToolCall(
                            "tab",
                            "eval",
                            mutableMapOf<String, Any?>(
                                "expression" to "fetch('/api/data')",
                                "awaitPromise" to true
                            )
                        ),
                        driver
                    )

                    assertEquals(mapOf("status" to 200), result.value)
                    verify(driver).evaluateValueDetail("fetch('/api/data')", true)
                }
            }

            @Test
            fun `eval without awaitPromise defaults to one-arg overload`() {
                runBlocking {
                    val driver = Mockito.mock(WebDriver::class.java)
                    `when`(driver.evaluateValueDetail("document.title"))
                        .thenReturn(JsEvaluation(value = "Browser4 CLI Other Fixture"))

                    val result = executor.callFunctionOn(
                        ToolCall("tab", "eval", mutableMapOf<String, Any?>("expression" to "document.title")),
                        driver
                    )

                    assertEquals("Browser4 CLI Other Fixture", result.value)
                    verify(driver).evaluateValueDetail("document.title")
                }
            }

            @Test
            fun `evaluateValue with awaitPromise calls two-arg evaluateValueDetail`() {
                runBlocking {
                    val driver = Mockito.mock(WebDriver::class.java)
                    `when`(driver.evaluateValueDetail("new Promise(r => setTimeout(() => r(42), 100))", true))
                        .thenReturn(JsEvaluation(value = 42))

                    val result = executor.callFunctionOn(
                        ToolCall(
                            "tab",
                            "evaluateValue",
                            mutableMapOf<String, Any?>(
                                "expression" to "new Promise(r => setTimeout(() => r(42), 100))",
                                "awaitPromise" to true
                            )
                        ),
                        driver
                    )

                    assertEquals(42, result.value)
                    verify(driver).evaluateValueDetail("new Promise(r => setTimeout(() => r(42), 100))", true)
                }
            }

        }
    }
}
