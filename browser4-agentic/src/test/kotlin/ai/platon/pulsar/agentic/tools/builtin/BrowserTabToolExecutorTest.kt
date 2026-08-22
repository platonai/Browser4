package ai.platon.pulsar.agentic.tools.builtin

import ai.platon.pulsar.api.model.JsEvaluation
import ai.platon.pulsar.agentic.model.ToolCall
import ai.platon.pulsar.chrome.Browser4WebDriver
import ai.platon.pulsar.chrome.PulsarWebDriver
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
    fun `keydown dispatches driver keyDown with the key`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)

            executor.callFunctionOn(
                ToolCall("tab", "keyDown", mutableMapOf<String, Any?>("key" to "Control")),
                driver
            )

            // Browser4WebDriver overrides keyDown with the stateful
            // Keyboard.down() path; the executor must route the modifier
            // key through the driver rather than dispatching its own JS event.
            verify(driver).keyDown("Control")
        }
    }

    @Test
    fun `keyup dispatches driver keyUp with the key`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)

            executor.callFunctionOn(
                ToolCall("tab", "keyUp", mutableMapOf<String, Any?>("key" to "Control")),
                driver
            )

            verify(driver).keyUp("Control")
        }
    }

    @Test
    fun `fill fallback JS respects input constraints`() {
        runBlocking {
            // The mock must be a PulsarWebDriver so the executor's
            // `driver is PulsarWebDriver` check routes to the fallback path.
            // The fallback now reuses the shared Browser4WebDriver.fillValueJs
            // helper via evaluateValue(selector, functionDeclaration) — capture
            // the function declaration (2nd argument) and assert it matches the
            // shared helper (constraint-aware, `this`-bound element).
            val driver = Mockito.mock(PulsarWebDriver::class.java)
            val captured = java.util.concurrent.atomic.AtomicReference<String>()
            Mockito.doAnswer { inv ->
                captured.set(inv.getArgument(1))
                null
            }.`when`(driver).evaluateValue(Mockito.anyString(), Mockito.anyString())

            executor.callFunctionOn(
                ToolCall(
                    "tab", "fill",
                    mutableMapOf<String, Any?>("selector" to "#number-target", "text" to "42")
                ),
                driver
            )

            val js = captured.get()
            assertEquals(Browser4WebDriver.fillValueJs("42"), js)
            // read-only/disabled inputs keep their value (user input blocked)
            assertTrue(js.contains("el.disabled || el.readOnly"), "JS must skip disabled/readonly: $js")
            // number/range inputs set valueAsNumber instead of string coercion
            assertTrue(js.contains("valueAsNumber"), "JS must use valueAsNumber: $js")
            // contenteditable elements get textContent instead of value
            assertTrue(js.contains("isContentEditable"), "JS must handle contenteditable: $js")
            // maxlength guard still present
            assertTrue(js.contains("maxLength"), "JS must guard maxlength: $js")
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

    @Test
    fun `reload waits for readyState instead of burning waitForNavigation on same-URL navigation`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.currentUrl()).thenReturn("http://example.com")
            // A reload never changes the URL; the first readyState poll (after
            // the initial 200ms settle) still reports "loading", the second
            // "complete" — the navigation finishes without any URL change.
            `when`(driver.evaluateValue("document.readyState")).thenReturn("loading", "complete")

            executor.callFunctionOn(
                ToolCall("tab", "reload", mutableMapOf<String, Any?>()),
                driver
            )

            // Regression: the executor used to call
            // waitForNavigation(urlBefore, 30s) here, whose predicate is
            // `currentUrl() != urlBefore` — it can never become true when the
            // URL is unchanged (reload / same-URL goto), so it silently burned
            // the full 30s poll timeout. The fix polls document.readyState in
            // a loop instead: readyState must be re-read after the initial
            // check ("loading" -> "complete").
            Mockito.verify(driver, Mockito.atLeast(2))
                .evaluateValue("document.readyState")
        }
    }

    @Test
    fun `reload with no in-flight navigation skips the wait`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.currentUrl()).thenReturn("http://example.com")
            `when`(driver.evaluateValue("document.readyState")).thenReturn("complete")

            executor.callFunctionOn(
                ToolCall("tab", "reload", mutableMapOf<String, Any?>()),
                driver
            )

            // readyState was already complete — no polling loop at all.
            Mockito.verify(driver, Mockito.times(1))
                .evaluateValue("document.readyState")
        }
    }

    @Test
    fun `navigate to a same-URL destination polls readyState instead of a no-op waitForNavigation`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.currentUrl()).thenReturn("http://example.com")
            // The URL does not change (SPA route / same-URL goto); the first
            // readyState poll (after the initial settle) still reports "loading",
            // the second "complete" — the navigation finishes without any URL change.
            `when`(driver.evaluateValue("document.readyState")).thenReturn("loading", "complete")

            executor.callFunctionOn(
                ToolCall("tab", "navigate", mutableMapOf<String, Any?>("url" to "http://example.com")),
                driver
            )

            // Regression: the executor used to call the no-arg waitForNavigation()
            // here, whose predicate is `"" != currentUrl()` — true as soon as the
            // page has any URL, so it returned immediately without waiting at all.
            // The oldUrl overload can never complete for same-URL navigations.
            // readyState must now be polled: at least 2 reads (loading -> complete).
            Mockito.verify(driver, Mockito.atLeast(2))
                .evaluateValue("document.readyState")
            Mockito.verify(driver, Mockito.never()).waitForNavigation()
        }
    }

    @Test
    fun `navigate to a different URL waits for the new body`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            // URL changes during navigation: the executor must wait for the new
            // document's body rather than poll readyState indefinitely.
            `when`(driver.currentUrl()).thenReturn("http://old.example.com", "http://new.example.com")

            executor.callFunctionOn(
                ToolCall("tab", "navigate", mutableMapOf<String, Any?>("url" to "http://new.example.com")),
                driver
            )

            Mockito.verify(driver).navigate("http://new.example.com")
            // URL already changed — wait for the body element of the new page.
            Mockito.verify(driver).waitForSelector("body", 10_000L)
            // readyState polling must not happen for a URL-changing navigation.
            Mockito.verify(driver, Mockito.never()).evaluateValue("document.readyState")
        }
    }

    @Test
    fun `explicit waitForNavigation without oldUrl polls readyState instead of the no-op overload`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.currentUrl()).thenReturn("http://example.com")
            // The navigation keeps the URL; readyState transitions loading -> complete.
            `when`(driver.evaluateValue("document.readyState")).thenReturn("loading", "complete")

            executor.callFunctionOn(
                ToolCall("tab", "waitForNavigation", mutableMapOf<String, Any?>()),
                driver
            )

            // Regression: the no-arg driver.waitForNavigation() has predicate
            // `"" != currentUrl()` — true as soon as the page has any URL, so it
            // returned immediately without waiting. The explicit wait tool must
            // poll readyState instead (at least 2 reads: loading -> complete).
            Mockito.verify(driver, Mockito.atLeast(2))
                .evaluateValue("document.readyState")
            Mockito.verify(driver, Mockito.never()).waitForNavigation()
        }
    }

    @Test
    fun `explicit waitForNavigation with oldUrl polls readyState on same-URL navigation`() {
        runBlocking {
            val driver = Mockito.mock(WebDriver::class.java)
            `when`(driver.currentUrl()).thenReturn("http://example.com")
            `when`(driver.evaluateValue("document.readyState")).thenReturn("loading", "complete")

            executor.callFunctionOn(
                ToolCall("tab", "waitForNavigation", mutableMapOf<String, Any?>("oldUrl" to "http://example.com")),
                driver
            )

            // Regression: driver.waitForNavigation(oldUrl) has predicate
            // `oldUrl != currentUrl()` — it can never become true for a same-URL
            // navigation and would burn the whole timeout silently.
            Mockito.verify(driver, Mockito.atLeast(2))
                .evaluateValue("document.readyState")
            Mockito.verify(driver, Mockito.never())
                .waitForNavigation("http://example.com")
        }
    }
}
