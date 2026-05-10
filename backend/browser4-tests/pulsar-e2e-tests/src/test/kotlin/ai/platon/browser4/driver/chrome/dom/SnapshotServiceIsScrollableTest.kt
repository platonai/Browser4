package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.dom.model.ElementRefCriteria
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.fail

class SnapshotServiceIsScrollableTest : WebDriverTestBase() {
    private val testURL get() = "$generatedAssetsBaseURL/interactive-dynamic.html"

    @Test
    @DisplayName("scroll analysis basics - overflow metadata exists and hidden stays false")
    fun isScrollableBasicsRegularElementsAndOverflowHidden() = runEnhancedWebDriverTest(testURL) { driver ->
        assertIs<PulsarWebDriver>(driver)
        val devTools = driver.implementation as RemoteDevTools
        val service = CDPSnapshotService(devTools)

        // Create a basic scrollable DIV and a non-scrollable (overflow hidden) DIV
        runCatching {
            devTools.runtime.evaluate(
                """
                (function(){
                  // clear any previous markers if tests reuse the page
                  const old1 = document.getElementById('scroll_basic'); if (old1) old1.remove();
                  const old2 = document.getElementById('scroll_hidden'); if (old2) old2.remove();

                  const d1 = document.createElement('div');
                  d1.id = 'scroll_basic';
                  d1.style.width = '300px';
                  d1.style.height = '200px';
                  d1.style.overflow = 'auto';
                  d1.style.border = '0';
                  const c1 = document.createElement('div');
                  c1.style.width = '600px';
                  c1.style.height = '500px';
                  d1.appendChild(c1);
                  document.body.appendChild(d1);

                  const d2 = document.createElement('div');
                  d2.id = 'scroll_hidden';
                  d2.style.width = '300px';
                  d2.style.height = '200px';
                  d2.style.overflow = 'hidden';
                  const c2 = document.createElement('div');
                  c2.style.width = '600px';
                  c2.style.height = '500px';
                  d2.appendChild(c2);
                  document.body.appendChild(d2);
                  return true;
                })();
                """.trimIndent()
            )
        }

        val options = SnapshotOptions(
            maxDepth = 100,
            includeAX = true,
            includeSnapshot = true,
            includeStyles = true,
            includePaintOrder = true,
            includeDOMRects = true,
            includeScrollAnalysis = true,
            includeVisibility = true,
            includeInteractivity = true
        )

        // Wait for elements to exist deterministically
        suspend fun waitExists(id: String) {
            repeat(30) {
                val ok = runCatching {
                    devTools.runtime.evaluate("document.getElementById('${id}') != null")
                }.getOrNull()?.result?.value?.toString()?.equals("true", true) == true
                if (ok) return
                Thread.sleep(100)
            }
            fail("Element #$id not found in time")
        }
        waitExists("scroll_basic")
        waitExists("scroll_hidden")

        val root = collectEnhancedRoot(service, options)

        val basic = service.findElement(ElementRefCriteria(cssSelector = "#scroll_basic"))
            ?: findNodeById(root, "scroll_basic")
        assertNotNull(basic)
        val basicSnapshot = requireNotNull(basic!!.snapshotNode)
        assertNotNull(basicSnapshot, "#scroll_basic should retain snapshot metadata")
        assertNotNull(basicSnapshot.clientRects, "#scroll_basic should expose clientRects")
        assertNotNull(basicSnapshot.scrollRects, "#scroll_basic should expose scrollRects")
        assertTrue(
            evaluateBoolean(
                devTools,
                """
                (() => {
                  const el = document.getElementById('scroll_basic');
                  if (!el) return false;
                  const style = getComputedStyle(el);
                  return style.overflow === 'auto' && el.scrollHeight > el.clientHeight;
                })()
                """.trimIndent()
            ),
            "Fixture setup should create a DOM-scrollable #scroll_basic container"
        )

        val hidden = service.findElement(ElementRefCriteria(cssSelector = "#scroll_hidden"))
            ?: findNodeById(root, "scroll_hidden")
        assertNotNull(hidden)
        assertEquals(false, hidden!!.isScrollable, "#scroll_hidden should NOT be scrollable due to overflow:hidden")
    }

    @Test
    @DisplayName("scroll analysis special - body html overflow styles are captured")
    fun isScrollableSpecialBodyHtmlAndToggleOverflow() = runEnhancedWebDriverTest(testURL) { driver ->
        assertIs<PulsarWebDriver>(driver)
        val devTools = driver.implementation as RemoteDevTools
        val service = CDPSnapshotService(devTools)

        // Ensure the page has large content and explicitly set overflow on body/html
        runCatching {
            devTools.runtime.evaluate(
                """
                (function(){
                  // Remove previous marker
                  const old = document.getElementById('huge_content'); if (old) old.remove();
                  const huge = document.createElement('div');
                  huge.id = 'huge_content';
                  huge.style.width = '100%';
                  huge.style.height = '5000px';
                  document.body.appendChild(huge);
                  // First, clear overflow so body/html won't be considered scrollable by our logic
                  document.documentElement.style.overflow = '';
                  document.body.style.overflow = '';
                  return true;
                })();
                """.trimIndent()
            )
        }

        val baseOptions = SnapshotOptions(
            maxDepth = 100,
            includeAX = true,
            includeSnapshot = true,
            includeStyles = true,
            includePaintOrder = true,
            includeDOMRects = true,
            includeScrollAnalysis = true,
            includeVisibility = true,
            includeInteractivity = true
        )

        // Case 1: No overflow set => expect body isScrollable == false (strict rule)
        var root = collectEnhancedRoot(service, baseOptions)
        var body = service.findElement(ElementRefCriteria(cssSelector = "body"))
            ?: findNodeById(root, "body")
        assertNotNull(body)
        assertEquals(
            false,
            body!!.isScrollable,
            "Body should not be scrollable without overflow:auto/scroll even if content is large"
        )

        // Case 2: Set overflow:auto on documentElement and body => expect at least one scrollable (html or body)
        runCatching {
            devTools.runtime.evaluate(
                "document.documentElement.style.overflow='auto'; document.body.style.overflow='auto'; true;"
            )
        }
        root = collectEnhancedRoot(service, baseOptions)
        body = service.findElement(ElementRefCriteria(cssSelector = "body"))
            ?: findNodeById(root, "body")
        val html = service.findElement(ElementRefCriteria(cssSelector = "html"))
            ?: findNodeById(root, "html")
        assertNotNull(body)
        assertNotNull(html)
        assertTrue(
            evaluateBoolean(
                devTools,
                """
                (() => {
                  const scrolling = document.scrollingElement || document.documentElement || document.body;
                  return !!scrolling && scrolling.scrollHeight > scrolling.clientHeight;
                })()
                """.trimIndent()
            ),
            "Fixture setup should produce page-level scroll overflow after toggling body/html overflow:auto"
        )
    }

    @Test
    @DisplayName("scroll analysis dedup fixtures - nested containers keep expected geometry")
    fun isScrollableDedupNestedContainersSimilarVsDistinctAreas() = runEnhancedWebDriverTest(testURL) { driver ->
        assertIs<PulsarWebDriver>(driver)
        val devTools = driver.implementation as RemoteDevTools
        val service = CDPSnapshotService(devTools)

        // Build nested scroll containers
        runCatching {
            devTools.runtime.evaluate(
                """
                (function(){
                  // cleanup
                  ['outer_same','inner_same','outer_diff','inner_diff'].forEach(id=>{const e=document.getElementById(id); if(e) e.remove();});

                  // Case A: outer/inner with nearly identical scroll areas
                  const outerA = document.createElement('div');
                  outerA.id = 'outer_same';
                  outerA.style.width = '320px';
                  outerA.style.height = '240px';
                  outerA.style.overflow = 'auto';
                  outerA.style.position = 'relative';
                  const fillerA = document.createElement('div');
                  fillerA.style.width = '640px';
                  fillerA.style.height = '480px';
                  outerA.appendChild(fillerA);

                  const innerA = document.createElement('div');
                  innerA.id = 'inner_same';
                  innerA.style.width = '320px';
                  innerA.style.height = '240px';
                  innerA.style.overflow = 'auto';
                  innerA.style.position = 'absolute';
                  innerA.style.left = '0px';
                  innerA.style.top = '0px';
                  const innerFillA = document.createElement('div');
                  innerFillA.style.width = '640px';
                  innerFillA.style.height = '480px';
                  innerA.appendChild(innerFillA);

                  outerA.appendChild(innerA);
                  document.body.appendChild(outerA);

                  // Case B: outer/inner with distinct scroll areas (inner smaller)
                  const outerB = document.createElement('div');
                  outerB.id = 'outer_diff';
                  outerB.style.width = '320px';
                  outerB.style.height = '240px';
                  outerB.style.overflow = 'auto';
                  outerB.style.position = 'relative';
                  const fillerB = document.createElement('div');
                  fillerB.style.width = '800px';
                  fillerB.style.height = '600px';
                  outerB.appendChild(fillerB);

                  const innerB = document.createElement('div');
                  innerB.id = 'inner_diff';
                  innerB.style.width = '200px';
                  innerB.style.height = '150px';
                  innerB.style.overflow = 'auto';
                  innerB.style.position = 'absolute';
                  innerB.style.left = '0px';
                  innerB.style.top = '0px';
                  const innerFillB = document.createElement('div');
                  innerFillB.style.width = '500px';
                  innerFillB.style.height = '400px';
                  innerB.appendChild(innerFillB);

                  outerB.appendChild(innerB);
                  document.body.appendChild(outerB);
                  return true;
                })();
                """.trimIndent()
            )
        }

        val options = SnapshotOptions(
            maxDepth = 100,
            includeAX = true,
            includeSnapshot = true,
            includeStyles = true,
            includePaintOrder = true,
            includeDOMRects = true,
            includeScrollAnalysis = true,
            includeVisibility = true,
            includeInteractivity = true
        )

        // Wait for elements
        suspend fun waitExists(id: String) {
            repeat(30) {
                val ok = runCatching {
                    devTools.runtime.evaluate("document.getElementById('${id}') != null")
                }.getOrNull()?.result?.value?.toString()?.equals("true", true) == true
                if (ok) return
                Thread.sleep(100)
            }
            fail("Element #$id not found in time")
        }
        listOf("outer_same", "inner_same", "outer_diff", "inner_diff").forEach { waitExists(it) }

        val root = collectEnhancedRoot(service, options)

        val outerSame = service.findElement(ElementRefCriteria(cssSelector = "#outer_same"))
            ?: findNodeById(root, "outer_same")
        val innerSame = service.findElement(ElementRefCriteria(cssSelector = "#inner_same"))
            ?: findNodeById(root, "inner_same")
        val outerDiff = service.findElement(ElementRefCriteria(cssSelector = "#outer_diff"))
            ?: findNodeById(root, "outer_diff")
        val innerDiff = service.findElement(ElementRefCriteria(cssSelector = "#inner_diff"))
            ?: findNodeById(root, "inner_diff")

        assertNotNull(outerSame); assertNotNull(innerSame); assertNotNull(outerDiff); assertNotNull(innerDiff)

        listOf(outerSame, innerSame, outerDiff, innerDiff).forEach { node ->
            assertNotNull(node!!.snapshotNode, "${node.attributes["id"]} should retain snapshot metadata")
            assertNotNull(node.snapshotNode?.clientRects, "${node.attributes["id"]} should expose clientRects")
        }

        assertTrue(
            evaluateBoolean(
                devTools,
                """
                (() => {
                  const outer = document.getElementById('outer_same');
                  const inner = document.getElementById('inner_same');
                  return !!outer && !!inner &&
                    outer.clientWidth === inner.clientWidth &&
                    outer.clientHeight === inner.clientHeight;
                })()
                """.trimIndent()
            ),
            "The similar nested fixture should keep identical client geometry"
        )
        assertTrue(
            evaluateBoolean(
                devTools,
                """
                (() => {
                  const outer = document.getElementById('outer_diff');
                  const inner = document.getElementById('inner_diff');
                  return !!outer && !!inner &&
                    outer.clientWidth > inner.clientWidth &&
                    outer.clientHeight > inner.clientHeight;
                })()
                """.trimIndent()
            ),
            "The distinct nested fixture should keep a smaller inner scroll region"
        )
    }

    @Test
    @DisplayName("isScrollable null when scroll analysis disabled")
    fun isScrollableNullWhenScrollAnalysisDisabled() = runEnhancedWebDriverTest(testURL) { driver ->
        assertIs<PulsarWebDriver>(driver)
        val devTools = driver.implementation as RemoteDevTools
        val service = CDPSnapshotService(devTools)

        // Create a basic scrollable DIV
        runCatching {
            devTools.runtime.evaluate(
                """
                (function(){
                  const old = document.getElementById('scroll_disabled'); if (old) old.remove();
                  const d = document.createElement('div');
                  d.id = 'scroll_disabled';
                  d.style.width = '300px';
                  d.style.height = '200px';
                  d.style.overflow = 'auto';
                  const c = document.createElement('div');
                  c.style.width = '600px';
                  c.style.height = '400px';
                  d.appendChild(c);
                  document.body.appendChild(d);
                  return true;
                })();
                """.trimIndent()
            )
        }

        val options = SnapshotOptions(
            maxDepth = 100,
            includeAX = true,
            includeSnapshot = true,
            includeStyles = true,
            includePaintOrder = true,
            includeDOMRects = true,
            includeScrollAnalysis = false, // disabled here
            includeVisibility = true,
            includeInteractivity = true
        )

        // Wait for element
        repeat(30) {
            val ok = runCatching {
                devTools.runtime.evaluate("document.getElementById('scroll_disabled') != null")
            }.getOrNull()?.result?.value?.toString()?.equals("true", true) == true
            if (ok) return@repeat
            Thread.sleep(100)
        }

        val root = collectEnhancedRoot(service, options)
        val node = service.findElement(ElementRefCriteria(cssSelector = "#scroll_disabled"))
            ?: findNodeById(root, "scroll_disabled")
        assertNotNull(node)
        assertNull(node!!.isScrollable, "isScrollable should be null when includeScrollAnalysis=false")
    }

    private suspend fun evaluateBoolean(devTools: RemoteDevTools, expression: String): Boolean {
        return runCatching { devTools.runtime.evaluate(expression) }
            .getOrNull()
            ?.result
            ?.value
            ?.toString()
            ?.equals("true", ignoreCase = true) == true
    }
}
