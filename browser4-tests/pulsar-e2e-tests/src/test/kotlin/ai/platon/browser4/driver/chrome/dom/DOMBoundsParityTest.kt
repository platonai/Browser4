package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import kotlin.math.abs
import kotlin.test.Ignore
import kotlin.test.assertIs

class DOMBoundsParityTest : WebDriverTestBase() {

    private fun assertNear(actual: Double?, expected: Double, delta: Double = 2.0, message: String? = null) {
        assertNotNull(actual, message ?: "Actual value is null")
        val a = actual
        assertTrue(abs(a - expected) <= delta, message ?: "Expected ~$expected ±$delta, got $a")
    }

    private fun findIframeHtmlNode(root: MergedDOMTreeNode, iframeId: String): MergedDOMTreeNode {
        val iframe = findNodeById(root, iframeId)
        assertNotNull(iframe, "iframe#$iframeId should exist")
        val doc = iframe.contentDocument
        assertNotNull(doc, "iframe#$iframeId should have a contentDocument")
        // Depth-first find the HTML element under this content document
        fun dfs(n: MergedDOMTreeNode?): MergedDOMTreeNode? {
            n ?: return null
            if (n.nodeName.equals("HTML", true)) return n
            n.children.forEach { dfs(it)?.let { return it } }
            n.shadowRoots.forEach { dfs(it)?.let { return it } }
            n.contentDocument?.let { dfs(it)?.let { return it } }
            return null
        }

        val html = dfs(doc)
        assertNotNull(html, "iframe#$iframeId contentDocument should contain an HTML element")
        return html
    }

    @Test
    @DisplayName("bounds are in CSS pixels independent of DPR (scaling applied)")
    fun boundsAreInCssPixelsIndependentOfDprScalingApplied() =
        runEnhancedWebDriverTest(interactiveDynamicURL) { driver ->
            assertIs<PulsarWebDriver>(driver)
            val devTools = driver.implementation as RemoteDevTools
            val service = CDPSnapshotService(devTools)

            // Inject a simple layout with a known CSS box size
            devTools.runtime.evaluate(
                """
            (function(){
              document.open();
              document.write(`<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0}</style></head>
              <body>
                <div id="box" style="position:absolute; left:10px; top:20px; width:200px; height:120px; background:#eee;"></div>
              </body></html>`);
              document.close();
            })();
            """.trimIndent()
            )
            Thread.sleep(200)

            val options = SnapshotOptions(
                maxDepth = 50,
                includeAX = false,
                includeSnapshot = true,
                includeStyles = true,
                includePaintOrder = true,
                includeDOMRects = true,
                includeScrollAnalysis = false,
                includeVisibility = true,
                includeInteractivity = false
            )

            val root = collectEnhancedRoot(service, options)
            val box = findNodeById(root, "box")
            assertNotNull(box)
            val bounds = box.snapshotNode?.bounds
            assertNotNull(bounds, "Box should have snapshot bounds")
            // CSS width/height must be as authored (not multiplied by DPR)
            assertNear(bounds.width, 200.0, 1.0, "Box width should be 200 CSS px")
            assertNear(bounds.height, 120.0, 1.0, "Box height should be 120 CSS px")
        }

    @Test
    @Ignore("inner iframe features are postponed")
    @DisplayName("iframe offsets and scroll are reflected in absolutePosition and visibility true when within viewport")
    fun iframeOffsetsAndScrollAreReflectedInAbsolutePositionAndVisibilityTrueWhenWithinViewport() =
        runEnhancedWebDriverTest(interactiveDynamicURL) { driver ->
            assertIs<PulsarWebDriver>(driver)
            val devTools = driver.implementation as RemoteDevTools
            val service = driver.snapshotService as CDPSnapshotService

            // Build parent with an iframe using srcdoc (same-origin), scroll iframe content to 400
            devTools.runtime.evaluate(
                """
            (function(){
              document.open();
              document.write(`<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0}</style></head>
              <body>
                <iframe id="f1" style="position:absolute; left:50px; top:80px; width:300px; height:200px; border:0; background-color:#ccc" srcdoc="
                  <!doctype html>
                  <html><head><meta charset=\"utf-8\"><style>body{margin:0;height:2000px}</style></head>
                  <body>
                    <div id='inner' style='position:absolute; left:20px; top:500px; width:50px; height:40px; background:#0f0;'></div>
                    <script>window.scrollTo(0,400);</script>
                  </body></html>
                "></iframe>
              </body></html>`);
              document.close();
            })();
            """.trimIndent()
            )
            Thread.sleep(250)

            val options = SnapshotOptions(
                maxDepth = 100,
                includeAX = false,
                includeSnapshot = true,
                includeStyles = true,
                includePaintOrder = true,
                includeDOMRects = true,
                includeScrollAnalysis = true,
                includeVisibility = true,
                includeInteractivity = false
            )

            // Retry a few times until iframe HTML scroll reflects script
            var root = collectEnhancedRoot(service, options)
            var html = findIframeHtmlNode(root, "f1")
            var retries = 0
            while ((html.snapshotNode?.scrollRects?.y ?: 0.0) < 350.0 && retries < 3) {
                Thread.sleep(200)
                root = collectEnhancedRoot(service, options)
                html = findIframeHtmlNode(root, "f1")
                retries++
            }

            val inner = findNodeById(root, "inner")
            assertNotNull(inner)

            // Expected absolute X/Y: iframe.left + inner.left, iframe.top + (inner.top - scrollTop)
            val expectedAbsX = 50.0 + 20.0
            val expectedAbsY = 80.0 + (500.0 - 400.0)

            val ap = inner.absolutePosition
            assertNotNull(ap, "Absolute position should be computed for iframe descendants")
            assertNear(ap.x, expectedAbsX, 3.0, "Absolute X should include iframe offset")
            assertNear(ap.y, expectedAbsY, 3.0, "Absolute Y should include iframe offset minus scroll")

            assertEquals(
                true,
                inner.isVisible,
                "Inner element brought into iframe viewport by scroll should be visible"
            )

            // Validate iframe HTML's clientRects and scrollRects
            val client = html.snapshotNode?.clientRects
            val scroll = html.snapshotNode?.scrollRects
            assertNotNull(client, "iframe HTML should have clientRects")
            assertNotNull(scroll, "iframe HTML should have scrollRects")
            assertNear(client.width, 300.0, 3.0, "iframe client width should match CSS width")
            assertNear(client.height, 200.0, 3.0, "iframe client height should match CSS height")
            assertNear(scroll.x, 0.0, 1.0, "iframe scrollX should be 0")
            assertNear(scroll.y, 400.0, 50.0, "iframe scrollY should reflect window.scrollTo")
        }

    @Test
    @Ignore("inner iframe features are postponed")
    @DisplayName("iframe content outside viewport is not visible")
    fun iframeContentOutsideViewportIsNotVisible() = runEnhancedWebDriverTest(interactiveDynamicURL) { driver ->
        assertIs<PulsarWebDriver>(driver)
        val devTools = driver.implementation as RemoteDevTools
        val service = CDPSnapshotService(devTools)

        // Large content inside iframe without sufficient scroll to reveal inner2
        devTools.runtime.evaluate(
            """
            (function(){
              document.open();
              document.write(`<!doctype html><html><head><meta charset=\"utf-8\"><style>body{margin:0}</style></head>
              <body>
                <iframe id=\"f1\" style=\"position:absolute; left:20px; top:30px; width:260px; height:180px; border:0;\" srcdoc=\"
                  <!doctype html>
                  <html><head><meta charset=\\\"utf-8\\\"><style>body{margin:0;height:8000px}</style></head>
                  <body>
                    <div id='inner2' style='position:absolute; left:10px; top:5000px; width:60px; height:40px; background:#f00;'></div>
                    <script>window.scrollTo(0,300);</script>
                  </body></html>
                \" ></iframe>
              </body></html>`);
              document.close();
            })();
            """.trimIndent()
        )
        Thread.sleep(250)

        val options = SnapshotOptions(
            maxDepth = 100,
            includeAX = false,
            includeSnapshot = true,
            includeStyles = true,
            includePaintOrder = true,
            includeDOMRects = true,
            includeScrollAnalysis = true,
            includeVisibility = true,
            includeInteractivity = false
        )

        // Retry until scrollRects is populated
        var root = collectEnhancedRoot(service, options)
        var html = findIframeHtmlNode(root, "f1")
        var retries = 0
        while ((html.snapshotNode?.scrollRects?.y ?: 0.0) < 200.0 && retries < 3) {
            Thread.sleep(200)
            root = collectEnhancedRoot(service, options)
            html = findIframeHtmlNode(root, "f1")
            retries++
        }

        val inner2 = findNodeById(root, "inner2")
        assertNotNull(inner2)

        assertEquals(false, inner2.isVisible, "Far below iframe viewport content should be not visible")

        // Validate iframe HTML viewport and scroll
        val client = html.snapshotNode?.clientRects
        val scroll = html.snapshotNode?.scrollRects
        assertNotNull(client, "iframe HTML should have clientRects")
        assertNotNull(scroll, "iframe HTML should have scrollRects")
        assertNear(client.width, 260.0, 3.0, "iframe client width should match CSS width")
        assertNear(client.height, 180.0, 3.0, "iframe client height should match CSS height")
        assertNear(scroll.y, 300.0, 80.0, "iframe scrollY should reflect window.scrollTo")
    }
}
