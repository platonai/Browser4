package ai.platon.browser4.driver.chrome.dom

import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.common.serialize.json.pulsarObjectMapper
import ai.platon.pulsar.persist.model.ActiveDOMMetadata
import ai.platon.pulsar.skeleton.crawl.fetch.driver.WebDriver
import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.*

class PulsarWebDriverMockSiteJsFullTests : WebDriverTestBase() {

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
    fun openAHtmlPageAndUpdateStat() = runEnhancedWebDriverTest(multiScreensInteractiveUrl, browser) { driver ->
        driver.evaluate("__pulsar_utils__.scrollToBottom()")
        val evaluation = driver.evaluateValueDetail("__pulsar_utils__.checkStatus()")
        printlnPro(evaluation)
        assertNotNull(evaluation)
        assertNull(evaluation.exception)
        assertNotNull(evaluation.value)
        printlnPro(evaluation.value)
        // {"trace":{"status":{"n":1,"scroll":1,"idl":0,"st":"c","r":"st"},"initStat":{"w":0,"h":0,"na":0,"ni":0,"nst":0,"nnm":0},"lastStat":{"w":0,"h":0,"na":0,"ni":0,"nst":0,"nnm":0},"lastD":{"w":0,"h":0,"na":0,"ni":0,"nst":0,"nnm":0},"initD":{"w":0,"h":0,"na":0,"ni":0,"nst":0,"nnm":0}},"urls":{"URL":"http://127.0.0.1:18080/generated/interactive-screens.html","baseURI":"http://127.0.0.1:18080/generated/interactive-screens.html","location":"http://127.0.0.1:18080/generated/interactive-screens.html","documentURI":"http://127.0.0.1:18080/generated/interactive-screens.html","referrer":""},"metadata":{"viewPortWidth":1920,"viewPortHeight":1080,"scrollTop":"500.00","scrollLeft":"0.00","clientWidth":"1683.00","clientHeight":"986.00","screenNumber":"0.51","dateTime":"2026/1/23 19:24:27","timestamp":"1769167467032"}}
        val data = requireNotNull(evaluation.value?.toString())
        assertTrue { data.isNotEmpty() }
        assertTrue { data.contains("trace") }
        assertTrue { data.contains("status") }
        assertTrue { data.contains("initStat") }
        assertTrue { data.contains("lastStat") }
        assertTrue { data.contains("metadata") }

        // Regression: lastStat.{w,h,na,ni} should be computed from DOM, not left as 0.
        val json = pulsarObjectMapper().readTree(data)
        val lastStat = json.path("trace").path("lastStat")
//        assertTrue(lastStat.path("w").asInt() > 0, "Expected lastStat.w > 0")
//        assertTrue(lastStat.path("h").asInt() > 0, "Expected lastStat.h > 0")
        assertTrue(lastStat.path("na").asInt() > 0, "Expected lastStat.na > 0")
        assertTrue(lastStat.path("nst").asInt() > 0, "Expected lastStat.na > 0")
//        assertTrue(lastStat.path("ni").asInt() > 0, "Expected lastStat.ni > 0")
    }
}
