package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.common.SimpleScriptConfuser
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.prettyPulsarObjectMapper
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.persist.model.ActiveDOMMessage
import ai.platon.pulsar.persist.model.ActiveDOMMetadata
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PulsarWebDriverMockSiteJsTests : WebDriverTestBase() {

    protected suspend fun computeActiveDOMMetadata(driver: WebDriver): ActiveDOMMetadata {
        val detail = driver.evaluateDetail("JSON.stringify(__pulsar_utils__.computeMetadata())")
        printlnPro(detail)
        assertNotNull(detail)
        assertNotNull(detail.value)
        printlnPro(detail.value)
        val data = requireNotNull(detail.value?.toString())
        return pulsarObjectMapper().readValue(data)
    }

    @Test
    fun ensureInjectedJsVariablesAreNotSeen() = runEnhancedWebDriverTest(interactiveUrl, browser) { driver ->
        val windowVariables = driver.evaluate("JSON.stringify(Object.keys(window))").toString()
        assertTrue { windowVariables.contains("document") }
        assertTrue { windowVariables.contains("setTimeout") }
        assertTrue { windowVariables.contains("scrollTo") }

        val variables = windowVariables.split(",")
            .map { it.trim('\"') }
            .filter { it.contains("__pulsar_") }
        Assumptions.assumeTrue(
            SimpleScriptConfuser.IDENTITY_NAME_MANGLER != confuser.nameMangler,
            "confuser.nameMangler should not be IDENTITY_NAME_MANGLER to run this test." +
                    "Since we have injected scripts in an isolated world, there is no need to mangle names anymore."
        )
        assertEquals(0, variables.size, "__pulsar_ should be confused")

        val injectedNames = listOf(
            "__pulsar_utils__"
        )
        var expected = "function"
        var expression = ""
        var actual = ""
        injectedNames.forEach { name ->
            actual = driver.evaluate("typeof($name)").toString()
            expression = "typeof($name)"
            assertEquals(expected, actual, "$expression should be $expected")
        }

        val notInjectedNames = listOf(
            "__pulsar_setAttributeIfNotBlank",
            "__pulsar_NodeFeatureCalculator",
            "__pulsar_NodeTraversor"
        )
        expected = "undefined"
        notInjectedNames.forEach { name ->
            actual = driver.evaluate("typeof($name)").toString()
            expression = "typeof($name)"
            assertEquals(expected, actual, "$expression should be $expected")
        }
    }

    @Test
    fun whenOpenAHtmlPageThenScriptIsInjected() = runEnhancedWebDriverTest(interactiveUrl, browser) { driver ->
        var detail = driver.evaluateDetail("typeof(window)")
        printlnPro(detail)
        // assertNotNull(detail?.value)

        detail = driver.evaluateDetail("typeof(document)")
        printlnPro(detail)
        // assertNotNull(detail?.value)

        val r = driver.evaluate("__pulsar_utils__.add(1, 1)")
        assertEquals(2, r)

        detail = driver.evaluateDetail("JSON.stringify(__pulsar_CONFIGS)")
        val value = detail?.value?.toString()
        assertNotNull(value)
        printlnPro(value)
        assertTrue { value.contains("viewPortWidth") }

        detail = driver.evaluateDetail("JSON.stringify(__pulsar_utils__.getConfig())")
        val value2 = detail?.value?.toString()
        assertNotNull(value2)
        printlnPro(value2)
        assertTrue { value2.contains("viewPortWidth") }
    }

    @Test
    fun openAHtmlPageAndComputeMetadata() = runEnhancedWebDriverTest(interactiveUrl, browser) { driver ->
        driver.evaluate("__pulsar_utils__.scrollToMiddle()")
        var detail = driver.evaluateDetail("__pulsar_utils__.compute()")
        printlnPro(detail)

        detail = driver.evaluateDetail("__pulsar_utils__.getActiveDomMessage()")
        printlnPro(detail)
        val data = detail?.value?.toString()
        assertNotNull(data)

        val message = ActiveDOMMessage.fromJson(data)
        val urls = message.urls
        assertNotNull(urls)
        assertEquals(interactiveUrl, urls.URL)

        val metadata = message.metadata
        assertNotNull(metadata)
        printlnPro(prettyPulsarObjectMapper().writeValueAsString(metadata))
        assertEquals(1920, metadata.viewPortWidth)
        assertEquals(1080, metadata.viewPortHeight)
        // Assumptions.assumeTrue(metadata.scrollTop > metadata.viewPortHeight)
        assertTrue { metadata.scrollTop >= 0 }
        assertTrue { metadata.scrollLeft.toInt() == 0 }
        assertTrue { metadata.clientWidth > 0 } // 1683 on my laptop
        assertTrue { metadata.clientHeight > 0 } // 986 on my laptop
    }

    @Test
    fun openAHtmlPageAndComputeScreenNumber() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.evaluate("__pulsar_utils__.scrollToTop()")
        var metadata = computeActiveDOMMetadata(driver)
        assertEquals(0f, metadata.screenNumber)

        driver.evaluate("window.scrollTo(0, 300)")
        metadata = computeActiveDOMMetadata(driver)
        assertTrue { metadata.screenNumber > 0.0 }
        assertTrue { metadata.screenNumber < 1.0 }

        driver.evaluate("window.scrollTo(0, 1080)")
        metadata = computeActiveDOMMetadata(driver)
        assertTrue { metadata.screenNumber > 1 }
    }

    @Test
    fun ensureNoInjectedDocumentVariablesAreSeen() = runEnhancedWebDriverTest(interactiveUrl, browser) { driver ->
        val nodeVariables = driver.evaluate("JSON.stringify(Object.keys(document))").toString()
//            assertTrue { nodeVariables.contains("querySelector") }
//            assertTrue { nodeVariables.contains("textContent") }

        val variables = nodeVariables.split(",").map { it.trim('\"') }

        printlnPro(variables)

        val pulsarVariables = variables.filter { it.contains("__pulsar_") }
        assertTrue { pulsarVariables.isEmpty() }

        val expected = "undefined"
        val expression = "typeof(__pulsar_setAttributeIfNotBlank)"
        val actual = driver.evaluate(expression).toString()
        assertEquals(expected, actual, "$expression should be $expected")
    }
}
