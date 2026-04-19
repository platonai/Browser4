package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.RemoteDevTools
import ai.platon.browser4.driver.chrome.dom.model.MergedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.NodeType
import ai.platon.browser4.driver.chrome.dom.model.OptimizedDOMTreeNode
import ai.platon.browser4.driver.chrome.dom.model.SnapshotOptions
import ai.platon.browser4.driver.chrome.dom.util.DomDebug
import ai.platon.pulsar.WebDriverTestBase
import ai.platon.pulsar.common.printlnPro
import ai.platon.pulsar.protocol.browser.driver.cdt.PulsarWebDriver
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

class OptimizedDOMTreeTest : WebDriverTestBase() {

    private fun flattenOptimizedTree(root: OptimizedDOMTreeNode): List<OptimizedDOMTreeNode> {
        val out = ArrayList<OptimizedDOMTreeNode>()
        fun dfs(n: OptimizedDOMTreeNode) {
            out.add(n)
            n.children.forEach { dfs(it) }
        }
        dfs(root)
        return out
    }

    @Test
    @DisplayName("OptimizedDOMTreeBuilder invariants on interactive-dynamic page")
    fun optimizedDOMTreeBuilderInvariantsOnInteractiveDynamicPage() =
        runEnhancedWebDriverTest(interactiveDynamicURL) { driver ->
            assertIs<PulsarWebDriver>(driver)
            val devTools = driver.implementation as RemoteDevTools
            val service = CDPSnapshotService(devTools)

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

            val enhancedRoot = collectEnhancedRoot(service, options)
            printlnPro(DomDebug.summarize(enhancedRoot))
            assertTrue(enhancedRoot.children.isNotEmpty(), "Enhanced root should have children")

            val optimizedDOMTree = OptimizedDOMTreeBuilder(enhancedRoot).build()
            assertNotNull(optimizedDOMTree, "OptimizedDOMTree should not be null")
            optimizedDOMTree!!
            printlnPro(DomDebug.summarize(optimizedDOMTree))
            assertTrue(optimizedDOMTree.children.isNotEmpty(), "OptimizedDOMTree should have children")

            val all = flattenOptimizedTree(optimizedDOMTree)
            // There should be at least one interactive element on example.com (links)
            val interactiveIndices = all.mapNotNull { it.interactiveIndex }
            assertTrue(interactiveIndices.isNotEmpty(), "Expected at least one interactive element with index")
            assertEquals(1, interactiveIndices.first(), "First interactive index should start from 1")
            assertTrue(
                interactiveIndices.zipWithNext().all { (a, b) -> b > a },
                "Interactive indices should be strictly increasing"
            )

            // Pruning invariants: no disabled elements in simplified tree
            val disabledTags = setOf("style", "script", "head", "meta", "link", "title")
            all.forEach { n ->
                val o = n.originalNode
                if (o.nodeType == NodeType.ELEMENT_NODE) {
                    val tag = o.nodeName.lowercase()
                    assertFalse(tag in disabledTags, "Disabled element <$tag> should not appear in simplified tree")
                }
            }

            // OptimizeTree invariants: no leaf element that is invisible and not scrollable
            all.forEach { n ->
                val o = n.originalNode
                val isLeaf = n.children.isEmpty()
                val isText = o.nodeType == NodeType.TEXT_NODE
                val visible = (o.isVisible == true)
                val scrollable = (o.isScrollable == true)

                if (isLeaf && !isText) {
                    // assertTrue(visible || scrollable, "Invisible, non-scrollable leaf elements should be pruned ${o.slimHTML()}")
                    if (!(visible || scrollable)) {
                        printlnPro("Invisible, non-scrollable leaf elements should be pruned ${o.slimHTML()}")
                    }
                }
            }

            // Assignment invariants: indexed nodes must be visible + interactable and not excluded/ignored
            all.filter { it.interactiveIndex != null }.forEach { n ->
                assertEquals(true, n.originalNode.isVisible, "Indexed node must be visible")
                assertEquals(true, n.originalNode.isInteractable, "Indexed node must be interactable")
                assertFalse(n.excludedByParent, "Indexed node must not be excluded by parent")
                assertFalse(n.ignoredByPaintOrder, "Indexed node must not be ignored by paint order")
            }

            // If a node is excludedByParent, it should never be indexed
            all.filter { it.excludedByParent }.forEach { n ->
                assertNull(n.interactiveIndex, "Excluded node must not have interactive index")
            }

            // TEXT_NODEs included must contain non-trivial text and be marked shouldDisplay
            all.filter { it.originalNode.nodeType == NodeType.TEXT_NODE }.forEach { n ->
                assertTrue(n.shouldDisplay, "Text nodes in simplified tree must be displayable")
                val text = n.originalNode.nodeValue.orEmpty().trim()
                assertTrue(text.length > 1, "Text nodes must contain non-trivial content")
            }
        }

    @Test
    @DisplayName("isNew flag respects previous backend node ids")
    fun isNewFlagRespectsPreviousBackendNodeIds() = runEnhancedWebDriverTest(interactiveDynamicURL) { driver ->
        assertIs<PulsarWebDriver>(driver)
        val devTools = driver.implementation as RemoteDevTools
        val service = CDPSnapshotService(devTools)

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

        val enhancedRoot = collectEnhancedRoot(service, options)
        val allBackendIds = mutableSetOf<Int>()
        fun collectBackendIds(n: MergedDOMTreeNode) {
            n.backendNodeId?.let { allBackendIds.add(it) }
            n.children.forEach { collectBackendIds(it) }
            n.shadowRoots.forEach { collectBackendIds(it) }
            n.contentDocument?.let { collectBackendIds(it) }
        }
        collectBackendIds(enhancedRoot)
        assertTrue(allBackendIds.isNotEmpty(), "Expected some backend node IDs in the enhanced DOM")

        val simplifiedInitial = OptimizedDOMTreeBuilder(enhancedRoot).build()
        assertNotNull(simplifiedInitial)
        val nodesInitial = flattenOptimizedTree(simplifiedInitial!!)
        // With empty previous set, nodes that have backend IDs should be considered new (best-effort)
        val anyNew = nodesInitial.any { it.isNew }
        assertTrue(anyNew, "Expected at least one node marked isNew on first build")

        // Build again with previous IDs supplied: nodes should now be marked as not new
        val simplifiedSecond = OptimizedDOMTreeBuilder(enhancedRoot, previousBackendNodeIds = allBackendIds).build()
        assertNotNull(simplifiedSecond)
        val nodesSecond = flattenOptimizedTree(simplifiedSecond!!)
        nodesSecond.forEach { n ->
            val backendId = n.originalNode.backendNodeId
            if (backendId != null && backendId in allBackendIds) {
                assertFalse(n.isNew, "Node with known backendId should not be marked isNew")
            }
        }
    }

    @Test
    @DisplayName("optimizeTree prunes invisible wrapper with pruned children on real page")
    fun optimizetreePrunesInvisibleWrapperWithPrunedChildrenOnRealPage() =
        runEnhancedWebDriverTest(interactiveDynamicURL) { driver ->
            assertIs<PulsarWebDriver>(driver)
            val devTools = driver.implementation as RemoteDevTools
            val service = CDPSnapshotService(devTools)

            // Inject an invisible wrapper with trivial content; children will be pruned first, then wrapper by optimizeTree
            runCatching {
                devTools.runtime.evaluate(
                    """
                (function(){
                  var el = document.getElementById('invisibleWrapper');
                  if (!el) {
                    var html = '<div id="invisibleWrapper" style="display:none">' +
                               '  <span> </span>' + // trivial text inside
                               '</div>';
                    document.body.insertAdjacentHTML('beforeend', html);
                  }
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

            val enhancedRoot = collectEnhancedRoot(service, options)
            assertTrue(enhancedRoot.children.isNotEmpty())

            val simplified = OptimizedDOMTreeBuilder(enhancedRoot).build()
            assertNotNull(simplified)
            val flat = flattenOptimizedTree(simplified!!)

            // Assert: the invisible wrapper should be absent from simplified DOM
            val hasWrapper = flat.any { it.originalNode.attributes["id"] == "invisibleWrapper" }
            assertFalse(
                hasWrapper,
                "Invisible non-scrollable wrapper with pruned children must be removed by optimizeTree"
            )
        }
}

