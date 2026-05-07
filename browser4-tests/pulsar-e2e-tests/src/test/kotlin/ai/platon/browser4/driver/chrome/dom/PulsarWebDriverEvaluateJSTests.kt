package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.common.B4JsUtils
import ai.platon.pulsar.FastWebDriverService
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.skeleton.workflow.fetch.driver.AbstractWebDriver
import ai.platon.pulsar.skeleton.workflow.fetch.driver.JsEvaluation
import ai.platon.pulsar.skeleton.workflow.fetch.driver.WebDriver
import org.junit.jupiter.api.assertNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PulsarWebDriverEvaluateJSTests : WebDriverTestBase() {

    override val webDriverService get() = FastWebDriverService(browserFactory)

    val text = "awesome AI enabled Browser4!"

    protected val expressions = """
            typeof(window)

            typeof(window.history)
            window.history
            window.history.length

            typeof(document)
            document.location
            document.baseURI

            typeof(document.body)
            document.body.clientWidth

            typeof(__pulsar_)
            __pulsar_utils__.add(1, 1)
        """.trimIndent().split("\n").map { it.trim() }.filter { it.isNotBlank() }

    suspend fun evaluateExpressions(driver: WebDriver, type: String) {
        expressions.forEach { expression ->
            val detail = driver.evaluateDetail(expression)
            printlnPro(String.format("%-6s%-40s%s", type, expression, detail))
        }
    }

    private fun assertPrimitiveDetail(detail: JsEvaluation?, expected: Any?) {
        assertNotNull(detail)
        assertNull(detail.exception)
        assertEquals(expected, detail.value)
    }

    private fun assertObjectDetail(detail: JsEvaluation?) {
        assertNotNull(detail)
        assertNull(detail.exception)
        assertNull(detail.value)
        assertEquals("Object", detail.className)
        assertEquals("Object", detail.description)
    }

    @Test
    fun testEvaluateThatReturnsPrimitiveValues() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val code = """1+1"""

            val result = driver.evaluate(code)
            assertEquals(2, result)

            assertPrimitiveDetail(driver.evaluateDetail(code), 2)
        }

    @Test
    fun testEvaluateThatReturnsObject() = runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
        val code = """__pulsar_utils__.getConfig()"""

        val result = driver.evaluateDetail(code)
        printlnPro(result)
        assertNotNull(result)
        assertNull(result.value)
        assertNull(result.exception)
        assertEquals("Object", result.className)
        assertEquals("Object", result.description)

        val result2 = driver.evaluateValueDetail(code)
        printlnPro(result2)
        assertNotNull(result2)
        assertNull(result2.exception)
        assertNull(result2.className)
        assertNull(result2.description)
        val value2 = result2.value
        assertNotNull(value2)
        assertEquals("java.util.LinkedHashMap", value2::class.qualifiedName)
        assertTrue { value2 is Map<*, *> }
        value2 as Map<*, *>
        assertEquals(browser.settings.viewportSize.width, value2["viewPortWidth"])

        val propertyNames = value2["propertyNames"]
        assertNotNull(propertyNames)
        assertEquals("java.util.ArrayList", propertyNames::class.qualifiedName)
        assertTrue { propertyNames is List<*> }
    }

    @Test
    fun whenOpenAJsonPageThenScriptIsInjected() = runWebDriverTest(jsonUrl) { driver ->
        require(driver is AbstractWebDriver)

        val expression = "__browser4_runtime__"
        val detail = driver.evaluateDetail(expression)
        printlnPro(String.format("%-6s%-40s%s", "JSON", expression, detail))

        val r = driver.evaluate("__pulsar_utils__.add(1, 1)")
        assertEquals(2, r)

        evaluateExpressions(driver, "JSON")
    }

    @Test
    fun testEvaluateSingleLineExpressions() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val code = "(() => {\n  const a = 1;\n  const b = 2;\n  return a + b;\n})()"

            val result = driver.evaluate(code)
            assertEquals(3, result)

            assertPrimitiveDetail(driver.evaluateDetail(code), 3)
        }

    @Test
    fun testEvaluateAndEvaluateDetailKeepGroupedExpressionsAsExpressions() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val code = "(1 + 2)"

            assertEquals(3, driver.evaluate(code))
            assertPrimitiveDetail(driver.evaluateDetail(code), 3)
        }

    @Test
    fun testEvaluateAndEvaluateDetailKeepAsyncPrefixedCallsAsCalls() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val setup = """
                (() => {
                    window.asyncOperation = () => 7;
                    return true;
                })()
            """.trimIndent()
            assertEquals(true, driver.evaluate(setup))

            val code = "asyncOperation()"
            assertEquals(7, driver.evaluate(code))
            assertPrimitiveDetail(driver.evaluateDetail(code), 7)
        }

    @Test
    fun testEvaluateAndEvaluateDetailNormalizeReturnedObjectLiterals() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val code = "return { answer: 42, nested: { ok: true } }"

            assertNull(driver.evaluate(code))
            assertObjectDetail(driver.evaluateDetail(code))

            val valueDetail = driver.evaluateValueDetail(code)
            assertNotNull(valueDetail)
            assertNull(valueDetail.exception)
            val value = valueDetail.value
            assertNotNull(value)
            assertTrue(value is Map<*, *>)
            value as Map<*, *>
            assertEquals(42, value["answer"])
            val nested = value["nested"]
            assertNotNull(nested)
            assertTrue(nested is Map<*, *>)
            assertEquals(true, (nested as Map<*, *>) ["ok"])
        }

    @Test
    fun testEvaluateAndEvaluateDetailInvokeCallableRawInputs() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            assertEquals(5, driver.evaluate("() => 5"))
            assertPrimitiveDetail(driver.evaluateDetail("() => 5"), 5)

            assertEquals(6, driver.evaluate("function() { return 6; }"))
            assertPrimitiveDetail(driver.evaluateDetail("function() { return 6; }"), 6)

            assertEquals(7, driver.evaluate("(() => 7)()"))
            assertPrimitiveDetail(driver.evaluateDetail("(() => 7)()"), 7)
        }

    @Test
    fun testEvaluateMultiLineExpressions() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val code = """
() => {
  const a = 10;
  const b = 20;
  return a * b;
}
        """.trimIndent()

            val result = driver.evaluate(B4JsUtils.toIIFE(code))
            assertEquals(200, result)

            val code2 = """
  const a = 10;
  const b = 20;
  return a * b;
        """.trimIndent()

            // converted to "// ❌ Unsupported format: not a valid JS function"
            // so it's an empty expressions sent to the browser

            val result2 = driver.evaluateValueDetail(B4JsUtils.toIIFE(code2))
            printlnPro(result2)
            assertNotNull(result2)
            val exception = result2.exception
            assertNull(exception)
            // assertIs<JsException>(exception)
        }

    @Test
    fun testEvaluateIifeImmediatelyInvokedFunctionExpression() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val code = """
(() => {
  const a = 10;
  const b = 20;
  return a * b;
})()
        """.trimIndent()

            val result = driver.evaluate(code)
            assertEquals(200, result)
        }

    @Test
    fun whenOpenAPlainTxtPageThenScriptIsInjected() = runWebDriverTest(plainTextUrl) { driver ->
        val r = driver.evaluate("__pulsar_utils__.add(1, 1)")
        assertEquals(2, r)

        evaluateExpressions(driver, "PLAIN TXT")
    }

    @Test
    fun whenOpenACsvTxtPageThenScriptIsInjected() = runWebDriverTest(csvTextUrl) { driver ->
        expressions.forEach { expression ->
            val detail = driver.evaluateDetail(expression)
            printlnPro(String.format("%-10s %-40s %s", "CSV TXT", expression, detail))
            assertNotNull(detail)
        }
    }

    @Test
    fun testAlreadyInvokedIifeIsNotDoubleWrappedAndEvaluates() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val iife = "(() => { return 3 })()"
            val expression = B4JsUtils.toIIFE(iife)
            // should normalize with trailing semicolon
            assertTrue(expression.trim().endsWith(";"))
            val result = driver.evaluate(expression)
            assertEquals(3, result)
        }

    @Test
    fun testArrowFunctionWithArgumentsViaIife() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val arrow = "x => x * 2"
            val expression = B4JsUtils.toIIFE(arrow, "5")
            val result = driver.evaluate(expression)
            assertEquals(10, result)
        }

    @Test
    fun testObjectLiteralIifeReturnsObjectByValue() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val obj = "{ answer: 42, nested: { ok: true } }"
            val expression = B4JsUtils.toIIFE(obj)
            val detail = driver.evaluateValueDetail(expression)
            assertNotNull(detail)
            assertNull(detail.exception)
            val value = detail.value
            assertNotNull(value)
            assertTrue(value is Map<*, *>)
            assertEquals(42, (value as Map<*, *>)["answer"])
            val nested = value["nested"]
            assertNotNull(nested)
            assertTrue(nested is Map<*, *>)
            assertEquals(true, (nested as Map<*, *>)["ok"])
        }

    @Test
    fun testPlainFunctionIifePassthrough() =
        runEnhancedWebDriverTest("$assetsBaseURL/dom.html", browser) { driver ->
            val funcIife = "(function(){ return 2 * 3 })()"
            val expression = B4JsUtils.toIIFE(funcIife)
            var result = driver.evaluate(expression)
            assertEquals(6, result)

            result = driver.evaluate(B4JsUtils.toExpression(funcIife))
            assertEquals(6, result)
        }

    @Test
    fun testElementTargetedEvaluateSupportsArrowAndFunctionArgumentSyntax() =
        runEnhancedWebDriverTest(interactiveUrl, browser) { driver ->
            val selector = "#pageHeader h1"
            val expected = "Welcome to the Interactive Page"

            val arrowResult = driver.evaluateValue(selector, "element => element.textContent")
            assertEquals(expected, arrowResult?.toString()?.trim())

            val functionResult = driver.evaluateValue(
                selector,
                "function(element) { return element.textContent; }"
            )
            assertEquals(expected, functionResult?.toString()?.trim())

            val thisResult = driver.evaluateValue(selector, "function() { return this.textContent; }")
            assertEquals(expected, thisResult?.toString()?.trim())
        }
}
