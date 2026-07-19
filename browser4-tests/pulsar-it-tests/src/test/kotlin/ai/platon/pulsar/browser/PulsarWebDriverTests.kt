package ai.platon.pulsar.browser

import ai.platon.pulsar.FastWebDriverService
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.sleepSeconds
import org.junit.jupiter.api.DisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PulsarWebDriverTests : WebDriverTestBase() {

    override val webDriverService get() = FastWebDriverService(browserFactory)

    val text = "awesome AI enabled Browser4!"

    @Test
    @DisplayName("test fill form with JavaScript")
    fun testFillFormWithJavascript() = runWebDriverTestAndCompute(simpleDomURL, browser) { driver ->
        val selector = "input[id=input]"

        driver.fill(selector, text)

        val detail = driver.evaluateDetail("document.querySelector('$selector')")
        printlnPro(detail)

        val inputValue = driver.selectFirstPropertyValueOrNull(selector, "value")

        assertEquals(text, inputValue)
    }

    @Test
    @DisplayName("test fill")
    fun testFill() = runWebDriverTestAndCompute(simpleDomURL, browser) { driver ->
        val selector = "input[id=input]"

        driver.fill(selector, text)

        val detail = driver.evaluateDetail("document.querySelector('input[id=input]').value")
        assertEquals(text, detail?.value)
    }

    @Test
    @DisplayName("test scrollBy")
    fun testScrollBy() = runWebDriverTestAndCompute(multiScreensInteractiveUrl, browser) { driver ->
        val scrollY = driver.scrollBy(200.0, smooth = true)

        assertEquals(200.0, scrollY, 1.0)
        assertEquals(200.0, driver.evaluate("window.scrollY", 200.0), 1.0)
    }

    @Test
    @DisplayName("test hover")
    fun testHover() = runWebDriverTestAndCompute(interactiveUrl2, browser) { driver ->
        // First scroll to ensure the element is in view and page is in a stable state
        driver.scrollToTop()
        driver.delay(300)

        var n = 0
        while (n++ < 5) {
            // Move mouse away from the card to ensure it's not in hover state
            driver.mouseMove(10.0, 10.0)
            driver.delay(200)

            // Use getBoundingClientRect which IS affected by transform
            val rect1 = driver.evaluate(
                "JSON.stringify(document.querySelector('.hover-card').getBoundingClientRect())",
                "{}"
            )

            driver.hover(".hover-card")
            driver.delay(500)

            val rect2 = driver.evaluate(
                "JSON.stringify(document.querySelector('.hover-card').getBoundingClientRect())",
                "{}"
            )

            println("Iteration $n:")
            println("  Before hover: $rect1")
            println("  After hover:  $rect2")
            sleepSeconds(2)
            assertNotEquals(rect1, rect2)
        }
    }

    @Test
    @DisplayName("test selectFirstPropertyValueOrNull")
    fun testSelectFirstPropertyValueOrNull() =
        runWebDriverTestAndCompute(simpleDomURL, browser) { driver ->
            val selector = "input[id=input]"

            driver.fill(selector, text)

            val propValue = driver.selectFirstPropertyValueOrNull(selector, "value")

            assertEquals(text, propValue)
        }

    @Test
    @DisplayName("test selectPropertyValueAll")
    fun testSelectPropertyValueAll() = runWebDriverTestAndCompute(simpleDomURL, browser) { driver ->
        val selector = "input:not([type=hidden])"

        val propValues = driver.selectPropertyValueAll(selector, "tagName")
        printlnPro(propValues)
        assertEquals(listOf("INPUT", "INPUT"), propValues)
    }

    @Test
    @DisplayName("test setProperty")
    fun testSetProperty() = runWebDriverTestAndCompute(simpleDomURL, browser) { driver ->
        val selector = "input"
        val propName = "value"

        driver.setProperty(selector, propName, text)

        val propValue = driver.selectFirstPropertyValueOrNull(selector, propName)
        assertEquals(text, propValue)
    }

    @Test
    @DisplayName("test setPropertyAll")
    fun testSetPropertyAll() = runWebDriverTestAndCompute(simpleDomURL, browser) { driver ->
        val selector = "input:not([type=hidden])"
        val propName = "value"

        driver.setPropertyAll(selector, propName, text)

        val propValues = driver.selectPropertyValueAll(selector, propName)
        printlnPro(propValues)
        assertEquals(listOf(text, text), propValues)
    }

    @Test
    @DisplayName("test deleteCookies")
    fun testDeleteCookies() = runWebDriverTestAndCompute("$assetsPBaseURL/cookie.html", browser) { driver ->
        var cookies = driver.getCookies()

        printlnPro(cookies.toString())

        assertTrue(cookies.toString()) { cookies.isNotEmpty() }
        val cookie = cookies[0]
        assertEquals("token", cookie["name"])
        assertEquals("abc123", cookie["value"])
        assertEquals("127.0.0.1", cookie["domain"])
        assertEquals("/", cookie["path"])

        driver.deleteCookies("token", url = assetsPBaseURL) // OK
        // driver.deleteCookies("token", url = "$assetsPBaseURL/cookie.html") // OK

        cookies = driver.getCookies()
        assertTrue(cookies.toString()) { cookies.isEmpty() }
    }

    @Test
    @DisplayName("test clearBrowserCookies")
    fun testClearBrowserCookies() = runWebDriverTestAndCompute("$assetsPBaseURL/cookie.html", browser) { driver ->
        var cookies = driver.getCookies()

        printlnPro(cookies.toString())

        assertTrue(cookies.toString()) { cookies.isNotEmpty() }
        val cookie = cookies[0]
        assertEquals("token", cookie["name"])
        assertEquals("abc123", cookie["value"])
        assertEquals("127.0.0.1", cookie["domain"])
        assertEquals("/", cookie["path"])

        driver.clearBrowserCookies()

        cookies = driver.getCookies()
        assertTrue(cookies.toString()) { cookies.isEmpty() }
    }

    @Test
    @DisplayName("test scrollToBottom")
    fun testScrolltoBottom() = runWebDriverTestAndCompute(multiScreensInteractiveUrl, browser) { driver ->
        val bottomY = driver.scrollToBottom()
        val viewportHeight = (driver.evaluate("window.innerHeight", 0.0) as? Number)?.toDouble() ?: 0.0
        val totalHeight = (driver.evaluate(
            "Math.min(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), 15000)",
            0.0
        ) as? Number)?.toDouble() ?: 0.0
        val expectedBottomY = (totalHeight - viewportHeight).coerceAtLeast(0.0)
        val actualY = (driver.evaluate("window.scrollY", 0.0) as? Number)?.toDouble() ?: 0.0
        assertEquals(expectedBottomY, bottomY, 3.0)
        assertEquals(expectedBottomY, actualY, 3.0)
    }

    @Test
    @DisplayName("test scrollToTop")
    fun testScrolltotop() = runWebDriverTestAndCompute(multiScreensInteractiveUrl, browser) { driver ->
        // First go to bottom to ensure movement
        driver.scrollToBottom()
        val topY = driver.scrollToTop()
        val actualY = (driver.evaluate("window.scrollY", -1.0) as? Number)?.toDouble() ?: -1.0
        assertEquals(0.0, topY, 1.0)
        assertEquals(0.0, actualY, 1.0)
    }

    @Test
    @DisplayName("test scrollToMiddle")
    fun testScrolltomiddle() = runWebDriverTestAndCompute(multiScreensInteractiveUrl, browser) { driver ->
        val ratio = 0.5
        val middleY = driver.scrollToMiddle(ratio)
        val viewportHeight = (driver.evaluate("window.innerHeight", 0.0) as? Number)?.toDouble() ?: 0.0
        val totalHeight = (driver.evaluate(
            "Math.min(Math.max(document.documentElement.scrollHeight, document.body.scrollHeight), 15000)",
            0.0
        ) as? Number)?.toDouble() ?: 0.0
        val maxScrollY = (totalHeight - viewportHeight).coerceAtLeast(0.0)
        val expectedMiddleY = maxScrollY * ratio
        val actualY = (driver.evaluate("window.scrollY", 0.0) as? Number)?.toDouble() ?: 0.0
        assertEquals(expectedMiddleY, middleY, 5.0)
        assertEquals(expectedMiddleY, actualY, 5.0)
    }

    @Test
    @DisplayName("test pageSource returns HTML with vi attributes after compute")
    fun testPageSourceReturnsViAttributes() = runWebDriverTestAndCompute(interactiveUrl, browser) { driver ->
        val pageSource = driver.pageSource() ?: ""

        // Verify vi attributes exist in the captured HTML
        // Format: vi="x y w h" (space-separated, rounded to 1 decimal)
        val viRegex = Regex("""\bvi="\d+(?:\.\d+)? \d+(?:\.\d+)? \d+(?:\.\d+)? \d+(?:\.\d+)?"""")
        val viMatches = viRegex.findAll(pageSource).toList()

        assertTrue(
            viMatches.isNotEmpty(),
            "pageSource() should return HTML with vi attributes, got none | ${pageSource.take(500)}"
        )

        // The vi value should contain 4 space-separated numbers (x y w h)
        val firstVi = viMatches.first().value
        val parts = firstVi.substringAfter("vi=\"").substringBefore("\"").split(" ")
        assertEquals(4, parts.size, "vi attribute should contain 4 values (x y w h): $firstVi")
        parts.forEach { assertTrue(it.toDoubleOrNull() != null, "vi value should be numeric: '$it' in $firstVi") }
    }

    @Test
    @DisplayName("test outerHTML returns HTML with vi attributes after compute")
    fun testOuterHTMLReturnsViAttributes() = runWebDriverTestAndCompute(interactiveUrl, browser) { driver ->
        val html = driver.outerHTML() ?: ""

        // outerHTML(":root") should include vi attributes just like pageSource
        val viRegex = Regex("""\bvi="\d+(?:\.\d+)? \d+(?:\.\d+)? \d+(?:\.\d+)? \d+(?:\.\d+)?"""")
        val viMatches = viRegex.findAll(html).toList()

        assertTrue(
            viMatches.isNotEmpty(),
            "outerHTML() should return HTML with vi attributes, got none | ${html.take(500)}"
        )
    }

    @Test
    @DisplayName("test outerHTML with selector returns annotated subtree")
    fun testOuterHTMLSelectorReturnsViAttributes() = runWebDriverTestAndCompute(interactiveUrl, browser) { driver ->
        val html = driver.outerHTML("body") ?: ""

        // The body subtree should contain vi attributes
        assertTrue(
            html.contains("vi="),
            "outerHTML('body') should return HTML with vi attributes | ${html.take(300)}"
        )
        assertTrue(
            html.trimStart().startsWith("<body"),
            "outerHTML('body') should start with <body>: ${html.take(80)}"
        )
    }

    @Test
    @DisplayName("test vi attributes are NOT in the live DOM after compute")
    fun testViAttributesNotInLiveDOM() = runWebDriverTestAndCompute(interactiveUrl, browser) { driver ->
        // After compute(), the live DOM must NOT have vi attributes.
        // This is the core of the feature: visual info is stored in a WeakMap,
        // not written as DOM attributes.
        val hasViAttr = driver.evaluateValue(
            "document.querySelector('[vi]') !== null"
        ) as? Boolean ?: true

        assertEquals(
            false, hasViAttr,
            "Live DOM must NOT have vi attributes after compute() — they should only appear in captured HTML"
        )

        // Also verify _h and _oh are not on the live DOM
        val hasHiddenAttr = driver.evaluateValue(
            "document.querySelector('[_h]') !== null"
        ) as? Boolean ?: true
        assertEquals(
            false, hasHiddenAttr,
            "Live DOM must NOT have _h attributes after compute()"
        )
    }
}
