package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.dom.model.PageTarget
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

@Tag("E2ETest")
class AriaSnapshotRendererE2ETest : WebDriverTestBase() {
    private val rendererFixtureURL get() = interactiveDynamicURL
    private val nestedFramesURL get() = "$assetsBaseURL/frames/nested-frames.html"

    private val snapshotOptions = SnapshotOptions(
        maxDepth = 200,
        includeAX = true,
        includeSnapshot = true,
        includeStyles = true,
        includePaintOrder = true,
        includeDOMRects = true,
        includeScrollAnalysis = true,
        includeVisibility = true,
        includeInteractivity = true
    )

    @Test
    @DisplayName("Render Playwright-style aria snapshot output on a real server-hosted page")
    fun renderPlaywrightStyleAriaSnapshotOutputOnRealFixturePage() =
        runEnhancedWebDriverTest(rendererFixtureURL) { driver ->
            assertIs<PulsarWebDriver>(driver)
            driver.waitForSelector("h1")
            driver.bringToFront()

            val devTools = driver.implementation as RemoteDevTools
            installRendererFixture(devTools)
            driver.waitForSelector("h1")

            val service = CDPSnapshotService(devTools)
            val normalized = normalizeRefs(collectAriaSnapshot(service)).lowercase()

            assertTrue(normalized.contains("- region \"collapsed generic\" [ref=#]:"), normalized)
            assertTrue(normalized.contains("- button \"collapsed button\" [ref=#] [cursor=pointer]"), normalized)
            assertTrue(normalized.contains("- region \"nested cursor pointer\" [ref=#]:"), normalized)
            assertTrue(
                normalized.contains("- link \"link with a button button\" [ref=#] [cursor=pointer]:"),
                normalized
            )
            assertTrue(normalized.contains("- /url: about:blank"), normalized)
            assertTrue(normalized.contains("- text: link with a button"), normalized)
            assertTrue(normalized.contains("- button \"button\" [ref=#]"), normalized)
            assertTrue(normalized.contains("- region \"presentational wrapper\" [ref=#]:"), normalized)
            assertTrue(normalized.contains("- heading \"presentational heading\" [level=2] [ref=#]"), normalized)
            assertTrue(normalized.contains("- textbox \"search\" [ref=#]"), normalized)
            assertTrue(normalized.contains("- /placeholder: search docs"), normalized)
            assertTrue(normalized.contains("- generic \"element title\" [ref=#]"), normalized)
        }

    @Test
    @DisplayName("Render iframe nodes and nested frame content on a real frames page")
    fun renderIframeNodesAndNestedFrameContentOnRealFramesPage() = runEnhancedWebDriverTest(nestedFramesURL) { driver ->
        assertIs<PulsarWebDriver>(driver)
        driver.waitForSelector("iframe")
        driver.bringToFront()

        val service = CDPSnapshotService(driver.implementation as RemoteDevTools)
        val normalized = normalizeRefs(collectAriaSnapshot(service)).lowercase()

        assertTrue(Regex("""- iframe \[ref=#]""").findAll(normalized).count() >= 2, normalized)
        assertTrue(!normalized.contains("whitelabel error page"), normalized)
    }

    private suspend fun collectAriaSnapshot(service: CDPSnapshotService): String {
        val trees = service.buildTargetTrees(target = PageTarget(), options = snapshotOptions)
        assertTrue(trees.axTree.isNotEmpty(), "AX tree should be collected for aria snapshot rendering")

        val enhancedRoot = collectEnhancedRoot(service, snapshotOptions)
        val optimizedTree = service.buildOptimizedDOMTreeNode(enhancedRoot)
        val domState = service.buildDOMState(optimizedTree)

        assertTrue(domState.ariaSnapshot.isNotBlank(), "Aria snapshot should not be blank")
        return domState.ariaSnapshot
    }

    private fun normalizeRefs(snapshot: String): String {
        return snapshot.replace(Regex("""\[ref=[^\]]+]"""), "[ref=#]")
    }

    private suspend fun installRendererFixture(devTools: RemoteDevTools) {
        devTools.runtime.evaluate(
            """
            document.head.innerHTML = '<meta charset="UTF-8"><title>Aria Snapshot Renderer Fixtures</title>';
            document.body.innerHTML = `
            <main>
                <h1>Aria Snapshot Renderer Fixtures</h1>
                <section aria-label="Collapsed Generic">
                    <div><div><div><button style="cursor: pointer">Collapsed Button</button></div></div></div>
                </section>
                <section aria-label="Nested Cursor Pointer">
                    <a href="about:blank" style="cursor: pointer">
                        Link with a button
                        <button style="cursor: pointer">Button</button>
                    </a>
                </section>
                <section aria-label="Presentational Wrapper">
                    <div role="none">
                        <h2>Presentational heading</h2>
                    </div>
                </section>
                <section aria-label="Textbox Properties">
                    <label for="search">Search</label>
                    <input id="search" type="text" aria-label="Search" placeholder="Search docs">
                </section>
                <section aria-label="Titled Generic">
                    <div title="Element title">Element content</div>
                </section>
            </main>`;
            """.trimIndent()
        )
    }
}
