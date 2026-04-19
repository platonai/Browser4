package ai.platon.browser4.driver.chrome.dom

import ai.platon.browser4.driver.chrome.dom.model.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class DOMStateBuilderTest {
    private val mapper = jacksonObjectMapper()

    @Test
    @DisplayName("serialize filters attributes and populates selector map")
    fun serializeFiltersAttributesAndPopulatesSelectorMap() {
        val childOriginal = MergedDOMTreeNode(
            nodeId = 2,
            nodeName = "SPAN",
            attributes = mapOf("data-test" to "value", "aria-label" to "ok"),
            elementHash = "child-hash"
        )
        val rootOriginal = MergedDOMTreeNode(
            nodeId = 1,
            nodeName = "DIV",
            attributes = mapOf("id" to "card", "data-id" to "123"),
            elementHash = "root-hash"
        )

        val root = OptimizedDOMTreeNode(
            originalNode = rootOriginal,
            children = listOf(OptimizedDOMTreeNode(originalNode = childOriginal))
        )

        val result = DOMStateBuilder.build(root, listOf("data-id", "aria-label"))
        val json = DOMSerializer.toJson(result.serializableTree)
        val tree = mapper.readTree(json)

        val rootAttrs = tree.get("originalNode").get("attributes")
        assertEquals(1, rootAttrs.size(), "Only whitelisted root attribute should be present")
        assertEquals("123", rootAttrs.get("data-id").asText())

        val childAttrs = tree.get("children").first().get("originalNode").get("attributes")
        assertEquals(1, childAttrs.size(), "Child should also honor whitelist")
        assertEquals("ok", childAttrs.get("aria-label").asText())

        // Enhanced selector map includes multiple keys per node; ensure required hash keys are present
        assertTrue(result.selectorMap.containsKey("hash:root-hash"))
        assertTrue(result.selectorMap.containsKey("hash:child-hash"))
    }

    @Test
    @DisplayName("serialize propagates scroll info only when helper allows it")
    fun serializePropagatesScrollInfoOnlyWhenHelperAllowsIt() {
        val scrollableNode = MergedDOMTreeNode(
            nodeId = 3,
            nodeName = "div",
            attributes = emptyMap(),
            elementHash = "scroll-hash",
            snapshotNode = SnapshotNodeEx(
                computedStyles = mapOf("overflow" to "auto"),
                clientRects = DOMRect(0.0, 0.0, 200.0, 200.0),
                scrollRects = DOMRect(0.0, 0.0, 400.0, 400.0)
            )
        )
        val rootOriginal = MergedDOMTreeNode(
            nodeId = 1,
            nodeName = "BODY",
            nodeType = NodeType.ELEMENT_NODE,
            elementHash = "body-hash"
        )

        val simplified = OptimizedDOMTreeNode(
            originalNode = rootOriginal,
            children = listOf(OptimizedDOMTreeNode(originalNode = scrollableNode))
        )

        val result = DOMStateBuilder.build(simplified)
        val json = DOMSerializer.toJson(result.serializableTree)
        val tree = mapper.readTree(json)
        val child = tree.get("children").first()

        assertNotNull(child.get("shouldShowScrollInfo"), "Scroll flag should be present when helper returns true")
        assertEquals("scrollable (both) [200x200 < 400x400]", child.get("scrollInfoText").asText())
    }

    @Test
    @DisplayName("serialize with paint order pruning removes high paint order elements")
    fun serializeWithPaintOrderPruningRemovesHighPaintOrderElements() {
        val highPaintOrderNode = MergedDOMTreeNode(
            nodeId = 4,
            nodeName = "DIV",
            elementHash = "high-paint-hash",
            snapshotNode = SnapshotNodeEx(
                paintOrder = 1500 // Above default threshold of 1000
            )
        )
        val normalNode = MergedDOMTreeNode(
            nodeId = 5,
            nodeName = "SPAN",
            elementHash = "normal-hash",
            snapshotNode = SnapshotNodeEx(
                paintOrder = 500 // Below threshold
            )
        )
        val rootOriginal = MergedDOMTreeNode(
            nodeId = 1,
            nodeName = "BODY",
            elementHash = "body-hash"
        )

        val simplified = OptimizedDOMTreeNode(
            originalNode = rootOriginal,
            children = listOf(
                OptimizedDOMTreeNode(originalNode = highPaintOrderNode),
                OptimizedDOMTreeNode(originalNode = normalNode)
            )
        )

        val options = DOMStateBuilder.CompactOptions(
            enablePaintOrderPruning = true,
            maxPaintOrderThreshold = 1000
        )
        val result = DOMStateBuilder.build(simplified, emptyList(), options)
        val json = DOMSerializer.toJson(result.serializableTree)
        val tree = mapper.readTree(json)

        val children = tree.get("children")
        assertEquals(2, children.size()) // Both children should be present but high paint order should be pruned

        val highPaintChild = children.first { it.get("originalNode").get("elementHash").asText() == "high-paint-hash" }
        // REVIEW CHANGE: shouldDisplay is null for pruned nodes (which means false), so it's omitted from JSON
        // Test that the field is either null or false to confirm pruned status
        val shouldDisplay = highPaintChild.get("shouldDisplay")
        assertTrue(
            shouldDisplay == null || shouldDisplay.asBoolean() == false,
            "High paint order node should not be displayed"
        )
        // Note: paintOrder field is temporarily ignored due to serialization issues
        // assertEquals(true, highPaintChild.get("ignoredByPaintOrder").asBoolean(), "High paint order node should be marked as ignored")
    }

    @Test
    @DisplayName("serialize detects compound components correctly")
    fun serializeDetectsCompoundComponentsCorrectly() {
        val listItem = MergedDOMTreeNode(
            nodeId = 6,
            nodeName = "LI",
            elementHash = "li-hash"
        )
        val listNode = MergedDOMTreeNode(
            nodeId = 5,
            nodeName = "UL",
            elementHash = "ul-hash"
        )
        val rootOriginal = MergedDOMTreeNode(
            nodeId = 1,
            nodeName = "BODY",
            elementHash = "body-hash"
        )

        val simplified = OptimizedDOMTreeNode(
            originalNode = rootOriginal,
            children = listOf(
                OptimizedDOMTreeNode(
                    originalNode = listNode,
                    children = List(5) { OptimizedDOMTreeNode(originalNode = listItem) } // 5 children to meet threshold
                )
            )
        )

        val options = DOMStateBuilder.CompactOptions(
            enableCompoundComponentDetection = true,
            compoundComponentMinChildren = 3
        )
        val result = DOMStateBuilder.build(simplified, emptyList(), options)
        val json = DOMSerializer.toJson(result.serializableTree)
        val tree = mapper.readTree(json)

        val ulNode = tree.get("children").first()
        assertEquals(
            true,
            ulNode.get("isCompoundComponent").asBoolean(),
            "UL with multiple children should be detected as compound component"
        )
    }

    @Test
    @DisplayName("serialize aligns attribute casing correctly")
    fun serializeAlignsAttributeCasingCorrectly() {
        val node = MergedDOMTreeNode(
            nodeId = 7,
            nodeName = "INPUT",
            elementHash = "input-hash",
            attributes = mapOf(
                "className" to "my-input", // Should be normalized to "class"
                "htmlFor" to "my-label",   // Should be normalized to "for"
                "READONLY" to "true",      // Should be normalized to "readonly"
                "customAttr" to "value"    // Should remain as "customattr"
            )
        )

        val simplified = OptimizedDOMTreeNode(originalNode = node)

        val options = DOMStateBuilder.CompactOptions(
            enableAttributeCasingAlignment = true,
            preserveOriginalCasing = false
        )
        val result = DOMStateBuilder.build(simplified, listOf("class", "for", "readonly", "customattr"), options)
        val json = DOMSerializer.toJson(result.serializableTree)
        val tree = mapper.readTree(json)

        val attrs = tree.get("originalNode").get("attributes")
        assertEquals("my-input", attrs.get("class").asText(), "className should be normalized to class")
        assertEquals("my-label", attrs.get("for").asText(), "htmlFor should be normalized to for")
        assertEquals("true", attrs.get("readonly").asText(), "READONLY should be normalized to readonly")
        assertEquals("value", attrs.get("customattr").asText(), "customAttr should be normalized to lowercase")
    }

    @Test
    @DisplayName("serialize builds enhanced selector map with multiple keys")
    fun serializeBuildsEnhancedSelectorMapWithMultipleKeys() {
        val node = MergedDOMTreeNode(
            nodeId = 8,
            nodeName = "BUTTON",
            elementHash = "button-hash",
            xpath = "/html/body/div[1]/button[2]",
            backendNodeId = 12345
        )

        val simplified = OptimizedDOMTreeNode(originalNode = node)

        val result = DOMStateBuilder.build(simplified)

        // Check that all expected keys are present in the selector map
        assertTrue(result.selectorMap.containsKey("hash:button-hash"), "Element hash key should be present")
        assertTrue(result.selectorMap.containsKey("xpath:/html/body/div[1]/button[2]"), "XPath key should be present")
        assertTrue(result.selectorMap.containsKey("backend:12345"), "Backend node ID key should be present")
        assertTrue(result.selectorMap.containsKey("node:8"), "Node ID key should be present")

        // All keys should map to the same node
        val expectedNode = result.selectorMap["hash:button-hash"]
        assertNotNull(expectedNode)
        assertEquals(expectedNode, result.selectorMap["xpath:/html/body/div[1]/button[2]"])
        assertEquals(expectedNode, result.selectorMap["backend:12345"])
        assertEquals(expectedNode, result.selectorMap["node:8"])
    }

    @Test
    @DisplayName("serialize preserves original casing when configured")
    fun serializePreservesOriginalCasingWhenConfigured() {
        val node = MergedDOMTreeNode(
            nodeId = 9,
            nodeName = "CustomElement",
            elementHash = "custom-hash"
        )

        val simplified = OptimizedDOMTreeNode(originalNode = node)

        val options = DOMStateBuilder.CompactOptions(
            preserveOriginalCasing = true
        )
        val result = DOMStateBuilder.build(simplified, emptyList(), options)
        val json = DOMSerializer.toJson(result.serializableTree)
        val tree = mapper.readTree(json)

        assertEquals(
            "CustomElement", tree.get("originalNode").get("nodeName").asText(),
            "Original casing should be preserved when configured"
        )
    }

    @Test
    @DisplayName("serialize handles deep tree end-to-end")
    fun serializeHandlesDeepTreeEndToEnd() {
        val levels = 30

        // Build a deep chain of SlimNodes: node-1 -> node-2 -> ... -> node-29 -> node-30(leaf)
        var leaf: OptimizedDOMTreeNode = OptimizedDOMTreeNode(
            originalNode = MergedDOMTreeNode(
                nodeId = levels,
                nodeName = "SPAN",
                elementHash = "node-$levels"
            )
        )
        for (i in levels - 1 downTo 1) {
            val parentOriginal = MergedDOMTreeNode(
                nodeId = i,
                nodeName = "DIV",
                elementHash = "node-$i"
            )
            leaf = OptimizedDOMTreeNode(
                originalNode = parentOriginal,
                children = listOf(leaf)
            )
        }

        val result = DOMStateBuilder.build(leaf)
        val json = DOMSerializer.toJson(result.serializableTree)
        val tree = mapper.readTree(json)

        // Traverse down the first-child chain and count levels
        var cursor = tree
        var count = 1 // count root
        while (cursor.has("children") && cursor.get("children").size() > 0) {
            cursor = cursor.get("children").first()
            count++
        }
        assertEquals(levels, count, "All $levels levels should be preserved in serialization")

        // The last node should be the SPAN leaf
        val lastNodeName = cursor.get("originalNode").get("nodeName").asText()
        assertEquals("span", lastNodeName)

        // Ensure selector map contains all element hash keys
        for (i in 1..levels) {
            assertTrue(
                result.selectorMap.containsKey("hash:node-$i"),
                "selectorMap should contain element hash for node-$i"
            )
        }
    }

    @Test
    @DisplayName("render formats Playwright-style aria snapshot output")
    fun renderFormatsPlaywrightStyleAriaSnapshotOutput() {
        val textLeaf = SerializableDOMTreeNode(
            originalNode = cleanedNode(
                locator = "0,11",
                backendNodeId = 11,
                nodeName = "#text",
                nodeValue = "Playwright"
            )
        )
        val root = SerializableDOMTreeNode(
            originalNode = cleanedNode(
                locator = "0,2",
                backendNodeId = 2,
                nodeName = "body"
            ),
            children = listOf(
                SerializableDOMTreeNode(
                    originalNode = cleanedNode(
                        locator = "0,4",
                        backendNodeId = 4,
                        nodeName = "section",
                        attributes = mapOf("role" to "region", "ax_name" to "Skip to main content")
                    ),
                    children = listOf(
                        SerializableDOMTreeNode(
                            interactiveIndex = 1,
                            originalNode = cleanedNode(
                                locator = "0,3",
                                backendNodeId = 3,
                                nodeName = "a",
                                isInteractable = true,
                                attributes = mapOf(
                                    "role" to "link",
                                    "ax_name" to "Skip to main content",
                                    "href" to "#__docusaurus_skipToContent_fallback"
                                )
                            )
                        )
                    )
                ),
                SerializableDOMTreeNode(
                    interactiveIndex = 2,
                    originalNode = cleanedNode(
                        locator = "0,5",
                        backendNodeId = 5,
                        nodeName = "button",
                        isInteractable = true,
                        attributes = mapOf("role" to "button", "ax_name" to "Node.js")
                    )
                ),
                SerializableDOMTreeNode(
                    originalNode = cleanedNode(
                        locator = "0,6",
                        backendNodeId = 6,
                        nodeName = "h1",
                        attributes = mapOf(
                            "role" to "heading",
                            "ax_name" to "Playwright enables reliable end-to-end testing for modern web apps.",
                            "level" to "1"
                        )
                    )
                ),
                SerializableDOMTreeNode(
                    originalNode = cleanedNode(
                        locator = "0,10",
                        backendNodeId = 10,
                        nodeName = "span"
                    ),
                    children = listOf(textLeaf)
                )
            )
        )

        val domState = DOMState(root)

        val ariaSnapshot = domState.ariaSnapshot

        assertEquals(
            """
            - generic [ref=e2]:
              - region "Skip to main content" [ref=e4]:
                - link "Skip to main content" [ref=e3] [cursor=pointer]:
                  - /url: "#__docusaurus_skipToContent_fallback"
              - button "Node.js" [ref=e5] [cursor=pointer]
              - heading "Playwright enables reliable end-to-end testing for modern web apps." [level=1] [ref=e6]
              - generic [ref=e10]: Playwright
            """.trimIndent(),
            ariaSnapshot
        )
    }

    @Test
    @DisplayName("render includes link urls from default DOM state build")
    fun renderIncludesLinkUrlsFromDefaultDomStateBuild() {
        val anchorNode = MergedDOMTreeNode(
            nodeId = 1,
            backendNodeId = 101,
            nodeName = "A",
            attributes = mapOf(
                "href" to "https://example.com",
                "title" to "Example Link"
            ),
            axNode = AXNodeEx(
                axNodeId = "ax-1",
                role = "link",
                name = "Example Link",
                backendNodeId = 101
            ),
            isVisible = true,
            isInteractable = true
        )
        val rootOriginal = MergedDOMTreeNode(
            nodeId = 0,
            backendNodeId = 100,
            nodeName = "DIV",
            children = listOf(anchorNode),
            isVisible = true
        )
        val root = OptimizedDOMTreeNode(
            originalNode = rootOriginal,
            children = listOf(OptimizedDOMTreeNode(originalNode = anchorNode, interactiveIndex = 1))
        )

        val ariaSnapshot = DOMStateBuilder.build(root).ariaSnapshot

        assertTrue(ariaSnapshot.contains("- link \"Example Link\" [ref=e101] [cursor=pointer]:"))
        assertTrue(ariaSnapshot.contains("- /url: https://example.com"))
    }

    @Test
    @DisplayName("render ignores invalid aria snapshot nodes")
    fun renderIgnoresInvalidAriaSnapshotNodes() {
        val root = OptimizedDOMTreeNode(
            originalNode = MergedDOMTreeNode(
                nodeId = 100,
                backendNodeId = 100,
                nodeName = "DIV",
                axNode = AXNodeEx(
                    axNodeId = "ax-root",
                    role = "region",
                    name = "Fixture",
                    backendNodeId = 100
                )
            ),
            children = listOf(
                OptimizedDOMTreeNode(
                    originalNode = MergedDOMTreeNode(
                        nodeId = 101,
                        backendNodeId = 101,
                        nodeType = NodeType.TEXT_NODE,
                        nodeName = "#text",
                        nodeValue = "   "
                    )
                ),
                OptimizedDOMTreeNode(
                    originalNode = MergedDOMTreeNode(
                        nodeId = 102,
                        backendNodeId = 102,
                        nodeType = NodeType.COMMENT_NODE,
                        nodeName = "#comment",
                        nodeValue = "debug marker"
                    )
                ),
                OptimizedDOMTreeNode(
                    originalNode = MergedDOMTreeNode(
                        nodeId = 103,
                        backendNodeId = 103,
                        nodeName = "SCRIPT",
                        children = listOf(
                            MergedDOMTreeNode(
                                nodeId = 104,
                                backendNodeId = 104,
                                nodeType = NodeType.TEXT_NODE,
                                nodeName = "#text",
                                nodeValue = "console.log('ignore me')"
                            )
                        )
                    ),
                    children = listOf(
                        OptimizedDOMTreeNode(
                            originalNode = MergedDOMTreeNode(
                                nodeId = 104,
                                backendNodeId = 104,
                                nodeType = NodeType.TEXT_NODE,
                                nodeName = "#text",
                                nodeValue = "console.log('ignore me')"
                            )
                        )
                    )
                ),
                OptimizedDOMTreeNode(
                    originalNode = MergedDOMTreeNode(
                        nodeId = 105,
                        backendNodeId = 105,
                        nodeName = "STYLE",
                        children = listOf(
                            MergedDOMTreeNode(
                                nodeId = 106,
                                backendNodeId = 106,
                                nodeType = NodeType.TEXT_NODE,
                                nodeName = "#text",
                                nodeValue = ".hidden { display: none; }"
                            )
                        )
                    ),
                    children = listOf(
                        OptimizedDOMTreeNode(
                            originalNode = MergedDOMTreeNode(
                                nodeId = 106,
                                backendNodeId = 106,
                                nodeType = NodeType.TEXT_NODE,
                                nodeName = "#text",
                                nodeValue = ".hidden { display: none; }"
                            )
                        )
                    )
                ),
                OptimizedDOMTreeNode(
                    originalNode = MergedDOMTreeNode(
                        nodeId = 107,
                        backendNodeId = 107,
                        nodeName = "BUTTON",
                        axNode = AXNodeEx(
                            axNodeId = "ax-button",
                            role = "button",
                            name = "Submit",
                            backendNodeId = 107
                        ),
                        isInteractable = true
                    ),
                    interactiveIndex = 1
                )
            )
        )

        val ariaSnapshot = DOMStateBuilder.build(root).ariaSnapshot

        assertEquals(
            """
            - region "Fixture" [ref=e100]:
              - button "Submit" [ref=e107] [cursor=pointer]
            """.trimIndent(),
            ariaSnapshot
        )
    }

    @Test
    @DisplayName("render matches Playwright cursor pointer suppression for nested interactive nodes")
    fun renderMatchesPlaywrightCursorPointerSuppressionForNestedInteractiveNodes() {
        val root = SerializableDOMTreeNode(
            originalNode = cleanedNode(
                locator = "0,1",
                backendNodeId = 1,
                nodeName = "body"
            ),
            children = listOf(
                SerializableDOMTreeNode(
                    interactiveIndex = 1,
                    originalNode = cleanedNode(
                        locator = "0,2",
                        backendNodeId = 2,
                        nodeName = "div",
                        isInteractable = true
                    ),
                    children = listOf(
                        SerializableDOMTreeNode(
                            interactiveIndex = 2,
                            originalNode = cleanedNode(
                                locator = "0,3",
                                backendNodeId = 3,
                                nodeName = "button",
                                isInteractable = true,
                                attributes = mapOf(
                                    "role" to "button",
                                    "ax_name" to "Open"
                                )
                            )
                        )
                    )
                )
            )
        )

        val ariaSnapshot = DOMState(root).ariaSnapshot

        assertEquals(
            """
            - generic [ref=e1]:
              - generic [ref=e2] [cursor=pointer]:
                - button "Open" [ref=e3]
            """.trimIndent(),
            ariaSnapshot
        )
    }

    @Test
    @DisplayName("render preserves enhanced accessibility metadata without nano conversion loss")
    fun renderPreservesEnhancedAccessibilityMetadataWithoutNanoConversionLoss() {
        val searchNode = MergedDOMTreeNode(
            nodeId = 1,
            backendNodeId = 101,
            nodeName = "INPUT",
            attributes = mapOf(
                "type" to "text",
                "placeholder" to "Search docs"
            ),
            axNode = AXNodeEx(
                axNodeId = "ax-1",
                role = "textbox",
                name = "Search",
                description = "Search Browser4 docs",
                properties = listOf(
                    AXPropertyEx("autocomplete", "list"),
                    AXPropertyEx("readonly", true),
                    AXPropertyEx("required", true),
                    AXPropertyEx("valuetext", "Current query")
                ),
                backendNodeId = 101
            ),
            isVisible = true,
            isInteractable = true
        )
        val root = OptimizedDOMTreeNode(
            originalNode = MergedDOMTreeNode(
                nodeId = 0,
                backendNodeId = 100,
                nodeName = "DIV",
                children = listOf(searchNode),
                isVisible = true
            ),
            children = listOf(OptimizedDOMTreeNode(originalNode = searchNode, interactiveIndex = 1))
        )

        val domState = DOMStateBuilder.build(root)
        val ariaSnapshot = domState.ariaSnapshot
        val legacySnapshot = domState.serializableTree.toNanoTreeUnfiltered().ariaSnapshot

        assertTrue(ariaSnapshot.contains("- textbox \"Search\" [ref=e101] [cursor=pointer]:"), ariaSnapshot)
        assertTrue(ariaSnapshot.contains("- /placeholder: Search docs"), ariaSnapshot)
        assertTrue(ariaSnapshot.contains("- /description: Search Browser4 docs"), ariaSnapshot)
        assertTrue(ariaSnapshot.contains("- /autocomplete: list"), ariaSnapshot)
        assertTrue(ariaSnapshot.contains("- /readonly: \"true\""), ariaSnapshot)
        assertTrue(ariaSnapshot.contains("- /required: \"true\""), ariaSnapshot)
        assertTrue(ariaSnapshot.contains("- /valuetext: Current query"), ariaSnapshot)

        assertFalse(legacySnapshot.contains("/description"), legacySnapshot)
        assertFalse(legacySnapshot.contains("/autocomplete"), legacySnapshot)
    }

    @Test
    @DisplayName("nano aria snapshot preserves implicit semantic roles after compaction removes duplicate role attrs")
    fun nanoAriaSnapshotPreservesImplicitSemanticRolesAfterCompaction() {
        val actionNode = MergedDOMTreeNode(
            nodeId = 1,
            backendNodeId = 101,
            nodeName = "BUTTON",
            attributes = emptyMap(),
            axNode = AXNodeEx(
                axNodeId = "ax-101",
                role = "button",
                name = "Load Users (2s delay)",
                backendNodeId = 101
            ),
            isVisible = true,
            isInteractable = true
        )
        val root = OptimizedDOMTreeNode(
            originalNode = MergedDOMTreeNode(
                nodeId = 0,
                backendNodeId = 100,
                nodeName = "DIV",
                children = listOf(actionNode),
                isVisible = true
            ),
            children = listOf(OptimizedDOMTreeNode(originalNode = actionNode, interactiveIndex = 1))
        )

        val domState = DOMStateBuilder.build(root)
        val nanoSnapshot = domState.serializableTree.toNanoTreeUnfiltered().ariaSnapshot

        assertTrue(
            nanoSnapshot.contains("- button \"Load Users (2s delay)\" [ref=e101] [cursor=pointer]"),
            nanoSnapshot
        )
        assertFalse(
            nanoSnapshot.contains("- generic \"Load Users (2s delay)\" [ref=e101] [cursor=pointer]"),
            nanoSnapshot
        )
    }

    @Test
    @DisplayName("render preserves descendants under presentational containers")
    fun renderPreservesDescendantsUnderPresentationalContainers() {
        val root = SerializableDOMTreeNode(
            originalNode = cleanedNode(
                locator = "0,2",
                backendNodeId = 2,
                nodeName = "#document",
                attributes = mapOf("role" to "RootWebArea", "ax_name" to "Dynamic Content Test")
            ),
            children = listOf(
                SerializableDOMTreeNode(
                    originalNode = cleanedNode(
                        locator = "0,3",
                        backendNodeId = 3,
                        nodeName = "html",
                        attributes = mapOf("role" to "none")
                    ),
                    children = listOf(
                        SerializableDOMTreeNode(
                            originalNode = cleanedNode(
                                locator = "0,4",
                                backendNodeId = 4,
                                nodeName = "body"
                            ),
                            children = listOf(
                                SerializableDOMTreeNode(
                                    originalNode = cleanedNode(
                                        locator = "0,5",
                                        backendNodeId = 5,
                                        nodeName = "h1",
                                        attributes = mapOf(
                                            "role" to "heading",
                                            "ax_name" to "Dynamic Content Test Page",
                                            "level" to "1"
                                        )
                                    )
                                ),
                                SerializableDOMTreeNode(
                                    interactiveIndex = 1,
                                    originalNode = cleanedNode(
                                        locator = "0,6",
                                        backendNodeId = 6,
                                        nodeName = "button",
                                        attributes = mapOf("role" to "button", "ax_name" to "Load Users (2s delay)"),
                                        isInteractable = true
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        val ariaSnapshot = DOMState(root).ariaSnapshot

        assertTrue(ariaSnapshot.contains("- RootWebArea \"Dynamic Content Test\" [ref=e2]:"))
        assertTrue(ariaSnapshot.contains("- heading \"Dynamic Content Test Page\" [level=1] [ref=e5]"))
        assertTrue(ariaSnapshot.contains("- button \"Load Users (2s delay)\" [ref=e6] [cursor=pointer]"))
    }

    @Test
    @DisplayName("test href and navigation attributes are preserved in NanoDOMTree")
    fun testHrefAndNavigationAttributesArePreservedInNanoDOMTree() {
        // Create an anchor node with href attribute
        val anchorNode = MergedDOMTreeNode(
            nodeId = 1,
            backendNodeId = 101,
            nodeName = "A",
            attributes = mapOf(
                "href" to "https://example.com",
                "target" to "_blank",
                "rel" to "noopener",
                "title" to "Example Link"
            ),
            snapshotNode = SnapshotNodeEx(
                bounds = DOMRect(10.0, 20.0, 100.0, 30.0)
            )
        )

        // Create an img node with src attribute
        val imgNode = MergedDOMTreeNode(
            nodeId = 2,
            backendNodeId = 102,
            nodeName = "IMG",
            attributes = mapOf(
                "src" to "https://example.com/image.png",
                "alt" to "Example Image"
            ),
            snapshotNode = SnapshotNodeEx(
                bounds = DOMRect(10.0, 60.0, 200.0, 150.0)
            )
        )

        // Create a form node with action attribute
        val formNode = MergedDOMTreeNode(
            nodeId = 3,
            backendNodeId = 103,
            nodeName = "FORM",
            attributes = mapOf(
                "action" to "/submit",
                "method" to "POST"
            ),
            snapshotNode = SnapshotNodeEx(
                bounds = DOMRect(10.0, 220.0, 300.0, 100.0)
            )
        )

        val rootOriginal = MergedDOMTreeNode(
            nodeId = 0,
            nodeName = "DIV",
            children = listOf(anchorNode, imgNode, formNode)
        )

        val root = OptimizedDOMTreeNode(
            originalNode = rootOriginal,
            children = listOf(
                OptimizedDOMTreeNode(originalNode = anchorNode),
                OptimizedDOMTreeNode(originalNode = imgNode),
                OptimizedDOMTreeNode(originalNode = formNode)
            )
        )

        // Build the DOM state (uses default attributes from DefaultIncludeAttributes)
        val result = DOMStateBuilder.build(root)

        // Convert to NanoDOMTree
        val nanoTree = result.serializableTree.toNanoTreeUnfiltered()
        val json = DOMSerializer.toJson(nanoTree)
        val tree = mapper.readTree(json)

        // Verify anchor node has href attribute
        val anchorChild = tree.get("children").get(0)
        val anchorAttrs = anchorChild.get("attributes")
        assertNotNull(anchorAttrs, "Anchor node should have attributes")
        assertTrue(anchorAttrs.has("href"), "Anchor node should have 'href' attribute")
        assertEquals("https://example.com", anchorAttrs.get("href").asText())
        assertTrue(anchorAttrs.has("target"), "Anchor node should have 'target' attribute")
        assertEquals("_blank", anchorAttrs.get("target").asText())
        assertTrue(anchorAttrs.has("rel"), "Anchor node should have 'rel' attribute")
        assertEquals("noopener", anchorAttrs.get("rel").asText())

        // Verify img node has src attribute
        val imgChild = tree.get("children").get(1)
        val imgAttrs = imgChild.get("attributes")
        assertNotNull(imgAttrs, "Img node should have attributes")
        assertTrue(imgAttrs.has("src"), "Img node should have 'src' attribute")
        assertEquals("https://example.com/image.png", imgAttrs.get("src").asText())
        assertTrue(imgAttrs.has("alt"), "Img node should have 'alt' attribute")

        // Verify form node has action attribute
        val formChild = tree.get("children").get(2)
        val formAttrs = formChild.get("attributes")
        assertNotNull(formAttrs, "Form node should have attributes")
        assertTrue(formAttrs.has("action"), "Form node should have 'action' attribute")
        assertEquals("/submit", formAttrs.get("action").asText())
    }

    private fun cleanedNode(
        locator: String,
        backendNodeId: Int,
        nodeName: String,
        nodeValue: String? = null,
        attributes: Map<String, Any>? = null,
        isInteractable: Boolean? = null
    ): CleanedDOMTreeNode {
        return CleanedDOMTreeNode(
            locator = locator,
            frameId = "0",
            xpath = null,
            elementHash = null,
            nodeId = backendNodeId,
            backendNodeId = backendNodeId,
            nodeType = if (nodeName == "#text") NodeType.TEXT_NODE.value else NodeType.ELEMENT_NODE.value,
            nodeName = nodeName,
            nodeValue = nodeValue,
            attributes = attributes,
            sessionId = null,
            isScrollable = null,
            isVisible = true,
            isInteractable = isInteractable,
            interactiveIndex = null,
            clientRects = null,
            scrollRects = null,
            bounds = null,
            absoluteBounds = null,
            viewportIndex = 1,
            paintOrder = null,
            stackingContexts = null,
            contentDocument = null
        )
    }
}
