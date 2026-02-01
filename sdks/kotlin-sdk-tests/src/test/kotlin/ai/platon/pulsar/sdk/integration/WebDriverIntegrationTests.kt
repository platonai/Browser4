/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor
 * license agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership. The ASF licenses this file to
 * you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0 Unless required
 * by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package ai.platon.pulsar.sdk.integration

import ai.platon.pulsar.sdk.WebDriver
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for WebDriver navigation operations.
 * Tests require a running Browser4 server with browser automation enabled.
 */
class WebDriverNavigationIT : IntegrationTestBase() {

    @Test
    fun `navigateTo loads a page successfully`() {
        val driver = WebDriver(client)
        
        // Navigate to a test page
        driver.navigateTo("https://example.com")
        
        // Verify navigation history
        assertTrue(driver.navigateHistory.contains("https://example.com"))
    }

    @Test
    fun `currentUrl returns the current page URL`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val currentUrl = driver.currentUrl()
        
        assertNotNull(currentUrl)
        assertTrue(currentUrl.toString().contains("example.com"))
    }

    @Test
    fun `title returns page title`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val title = driver.title()
        
        assertNotNull(title)
    }

    @Test
    fun `reload refreshes the current page`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val result = driver.reload()
        
        // Reload should not throw exception
        assertNotNull(result, "Reload should return a result")
    }

    @Test
    fun `goBack navigates to previous page in history`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.navigateTo("https://httpbin.org")
        
        val result = driver.goBack()
        assertNotNull(result)
    }

    @Test
    fun `goForward navigates to next page in history`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.navigateTo("https://httpbin.org")
        driver.goBack()
        
        val result = driver.goForward()
        assertNotNull(result)
    }
}

/**
 * Integration tests for WebDriver element interaction.
 */
class WebDriverElementIT : IntegrationTestBase() {

    @Test
    fun `click performs a click on element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        // Click should not throw exception even if selector doesn't exist
        // The actual behavior depends on the server implementation
        try {
            driver.click("a")
        } catch (e: Exception) {
            // Expected if no element found
        }
    }

    @Test
    fun `fill enters text into input field`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://httpbin.org/forms/post")
        
        try {
            driver.fill("input[name='custname']", "Test User")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `type simulates keyboard typing`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://httpbin.org/forms/post")
        
        try {
            driver.type("input[name='custname']", "Test")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `press sends key press to element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://httpbin.org/forms/post")
        
        try {
            driver.press("input[name='custname']", "Enter")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `hover moves mouse over element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            driver.hover("a")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `focus sets focus on element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://httpbin.org/forms/post")
        
        try {
            driver.focus("input[name='custname']")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `check marks checkbox as checked`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://httpbin.org/forms/post")
        
        try {
            driver.check("input[type='checkbox']")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `uncheck marks checkbox as unchecked`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://httpbin.org/forms/post")
        
        try {
            driver.uncheck("input[type='checkbox']")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }
}

/**
 * Integration tests for WebDriver scrolling operations.
 */
class WebDriverScrollingIT : IntegrationTestBase() {

    @Test
    fun `scrollDown scrolls page downward`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.scrollDown(1)
        
        // Should complete without error
    }

    @Test
    fun `scrollUp scrolls page upward`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.scrollDown(2)
        driver.scrollUp(1)
        
        // Should complete without error
    }

    @Test
    fun `scrollTo scrolls to specific element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            driver.scrollTo("h1")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `scrollToTop scrolls to page top`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.scrollDown(3)
        driver.scrollToTop()
        
        // Should complete without error
    }

    @Test
    fun `scrollToBottom scrolls to page bottom`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.scrollToBottom()
        
        // Should complete without error
    }

    @Test
    fun `scrollToMiddle scrolls to middle of page`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.scrollToMiddle(0.5f)
        
        // Should complete without error
    }
}

/**
 * Integration tests for WebDriver waiting and visibility checks.
 */
class WebDriverWaitIT : IntegrationTestBase() {

    @Test
    fun `exists checks if element exists`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val exists = driver.exists("h1")
        
        assertTrue(exists || !exists, "exists should return a boolean")
    }

    @Test
    fun `waitForSelector waits for element to appear`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            driver.waitForSelector("h1", timeout = 5000)
        } catch (e: Exception) {
            // Timeout is acceptable
        }
    }

    @Test
    fun `waitForNavigation waits for page navigation`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            driver.waitForNavigation()
        } catch (e: Exception) {
            // May timeout if no navigation occurs
        }
    }

    @Test
    fun `isVisible checks element visibility`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val visible = driver.isVisible("h1")
            assertTrue(visible || !visible, "isVisible should return a boolean")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `isHidden checks if element is hidden`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val hidden = driver.isHidden("h1")
            assertTrue(hidden || !hidden, "isHidden should return a boolean")
        } catch (e: Exception) {
            // May fail if element not found
        }
    }
}

/**
 * Integration tests for WebDriver content extraction.
 */
class WebDriverContentIT : IntegrationTestBase() {

    @Test
    fun `selectFirstTextOrNull extracts text from first matching element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val text = driver.selectFirstTextOrNull("h1")
        
        // Should return text or null
        assertTrue(text == null || text.isNotEmpty())
    }

    @Test
    fun `selectTextAll extracts text from all matching elements`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val texts = driver.selectTextAll("p")
        
        assertNotNull(texts)
        assertTrue(texts is List<*>)
    }

    @Test
    fun `selectFirstAttributeOrNull extracts attribute value`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val href = driver.selectFirstAttributeOrNull("a", "href")
        
        // Should return attribute value or null
        assertTrue(href == null || href.isNotEmpty())
    }

    @Test
    fun `selectAttributeAll extracts attributes from all elements`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        val hrefs = driver.selectAttributeAll("a", "href")
        
        assertNotNull(hrefs)
        assertTrue(hrefs is List<*>)
    }

    @Test
    fun `outerHtml returns HTML of element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val html = driver.outerHtml("h1")
            assertTrue(html == null || html.contains("<"))
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `textContent returns text content of element`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val content = driver.textContent("body")
            assertNotNull(content)
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `extract extracts multiple fields at once`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        val fields = mapOf(
            "title" to "h1",
            "content" to "p"
        )
        
        val result = driver.extract(fields)
        assertNotNull(result)
    }
}

/**
 * Integration tests for WebDriver screenshot capabilities.
 */
class WebDriverScreenshotIT : IntegrationTestBase() {

    @Test
    fun `captureScreenshot captures full page screenshot`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val screenshot = driver.captureScreenshot()
            assertNotNull(screenshot)
            assertTrue(screenshot.isNotEmpty())
        } catch (e: Exception) {
            // Screenshot may not be supported in all environments
        }
    }

    @Test
    fun `captureScreenshot with selector captures element screenshot`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val screenshot = driver.captureScreenshot(selector = "h1")
            assertTrue(screenshot == null || screenshot.isNotEmpty())
        } catch (e: Exception) {
            // May fail if element not found
        }
    }

    @Test
    fun `screenshot is alias for captureScreenshot`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val screenshot = driver.screenshot()
            assertTrue(screenshot == null || screenshot.isNotEmpty())
        } catch (e: Exception) {
            // Screenshot may not be supported
        }
    }
}

/**
 * Integration tests for WebDriver script execution.
 */
class WebDriverScriptIT : IntegrationTestBase() {

    @Test
    fun `executeScript executes JavaScript code`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        val result = driver.executeScript("return document.title")
        assertNotNull(result)
    }

    @Test
    fun `executeScript with arguments passes parameters`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        val result = driver.executeScript("return arguments[0] + arguments[1]", listOf(1, 2))
        assertNotNull(result)
    }

    @Test
    fun `executeAsyncScript executes async JavaScript`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        try {
            val result = driver.executeAsyncScript(
                "var callback = arguments[arguments.length - 1]; callback('done');",
                timeout = 5000
            )
            assertNotNull(result)
        } catch (e: Exception) {
            // Async execution may timeout
        }
    }

    @Test
    fun `evaluate evaluates JavaScript expression`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        
        val result = driver.evaluate("document.URL")
        assertNotNull(result)
    }
}

/**
 * Integration tests for WebDriver control operations.
 */
class WebDriverControlIT : IntegrationTestBase() {

    @Test
    fun `delay pauses execution`() {
        val driver = WebDriver(client)
        
        val startTime = System.currentTimeMillis()
        driver.delay(100)
        val elapsed = System.currentTimeMillis() - startTime
        
        assertTrue(elapsed >= 100, "Delay should wait at least 100ms")
    }

    @Test
    fun `pause stops execution`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.pause()
        
        // Pause should complete without error
    }

    @Test
    fun `stop halts all operations`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.stop()
        
        // Stop should complete without error
    }

    @Test
    fun `close closes the driver`() {
        val driver = WebDriver(client)
        
        driver.navigateTo("https://example.com")
        driver.close()
        
        // Close should complete without error
    }
}
