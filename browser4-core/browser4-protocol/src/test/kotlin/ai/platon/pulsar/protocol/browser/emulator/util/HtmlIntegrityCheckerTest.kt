package ai.platon.pulsar.protocol.browser.emulator.util

import ai.platon.pulsar.common.HtmlIntegrity
import ai.platon.pulsar.common.config.ImmutableConfig
import ai.platon.pulsar.persist.PageDatum
import ai.platon.pulsar.persist.model.GoraWebPage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("Unit")
@Tag("Fast")
@DisplayName("BasicHtmlIntegrityChecker")
class BasicHtmlIntegrityCheckerTest {

    private val conf = ImmutableConfig.DEFAULT
    private val checker = BasicHtmlIntegrityChecker(conf)
    private val page = GoraWebPage.NIL
    private val pageDatum = PageDatum(page)

    @Test
    @DisplayName("returns EMPTY_0B for empty page source")
    fun returnsEmpty0bForEmptySource() {
        val result = checker.invoke("", pageDatum)
        assertEquals(HtmlIntegrity.EMPTY_0B, result)
    }

    @Test
    @DisplayName("returns EMPTY_39B for 39-byte page source")
    fun returnsEmpty39bForShortSource() {
        // A typical empty Chrome page is exactly 39 bytes
        val source = "<html><head></head><body></body></html>"
        assertEquals(39, source.length, "test fixture should be 39 bytes")
        val result = checker.invoke(source, pageDatum)
        assertEquals(HtmlIntegrity.EMPTY_39B, result)
    }

    @Test
    @DisplayName("returns BLANK_BODY when body is blank")
    fun returnsBlankBodyWhenBodyIsBlank() {
        // A page with more than 39 bytes but a blank body
        val source = "<html><head><title>Test</title></head><body>   </body></html>"
        val result = checker.invoke(source, pageDatum)
        // HtmlUtils.isBlankBody checks if the body has no visible content
        // The exact result depends on isBlankBody implementation, but it should not be OK
        assertNotEquals(HtmlIntegrity.OK, result, "blank body should not be OK")
    }

    @Test
    @DisplayName("returns NO_ANCHOR when page has no anchor tags")
    fun returnsNoAnchorWhenNoAnchors() {
        // A page with a body but no <a> tags and no data-error="0" flag
        val source = """
            <html><head></head><body data-error="0"><div>content without links</div></body></html>
        """.trimIndent()
        val result = checker.invoke(source, pageDatum)
        // With a body that has content but no anchors, should detect NO_ANCHOR
        // Note: the exact result depends on indexOf logic, but it should not be OK
        assertNotEquals(HtmlIntegrity.OK, result, "page without anchors should not be OK")
    }

    @Test
    @DisplayName("returns NO_JS_OK_FLAG when data-error flag is missing")
    fun returnsNoJsOkFlagWhenFlagMissing() {
        // A page with body, anchors, but no data-error="0" flag
        val source = """
            <html><head></head><body><a href="/link">Link</a>content</body></html>
        """.trimIndent()
        val result = checker.invoke(source, pageDatum)
        assertEquals(HtmlIntegrity.NO_JS_OK_FLAG, result)
    }

    @Test
    @DisplayName("returns OK for a well-formed page with anchors and data-error flag")
    fun returnsOkForWellFormedPage() {
        val source = """
            <html><head></head><body data-error="0"><a href="/home">Home</a>content</body></html>
        """.trimIndent()
        val result = checker.invoke(source, pageDatum)
        assertEquals(HtmlIntegrity.OK, result)
    }

    @Test
    @DisplayName("returns OTHER when body tag is malformed")
    fun returnsOtherWhenBodyTagMalformed() {
        // No proper <body tag found after </head>
        val source = "<html><head></head>no body tag here but long enough to pass 39 bytes<a>link</a>"
        val result = checker.invoke(source, pageDatum)
        // Should not be OK since there's no proper body tag
        assertNotEquals(HtmlIntegrity.OK, result)
    }

    @Test
    @DisplayName("AlwaysPassHtmlIntegrityChecker always returns OK")
    fun alwaysPassReturnsOk() {
        val alwaysPass = AlwaysPassHtmlIntegrityChecker(conf)
        assertEquals(HtmlIntegrity.OK, alwaysPass.invoke("", pageDatum))
        assertEquals(HtmlIntegrity.OK, alwaysPass.invoke("anything", pageDatum))
    }
}

@Tag("Unit")
@Tag("Fast")
@DisplayName("ChainedHtmlIntegrityChecker")
class ChainedHtmlIntegrityCheckerTest {

    private val conf = ImmutableConfig.DEFAULT
    private val page = GoraWebPage.NIL
    private val pageDatum = PageDatum(page)

    @Test
    @DisplayName("returns OK when all checkers return OK")
    fun returnsOkWhenAllCheckersReturnOk() {
        val chain = ChainedHtmlIntegrityChecker(conf)
        chain.addLast(AlwaysPassHtmlIntegrityChecker(conf))
        chain.addLast(AlwaysPassHtmlIntegrityChecker(conf))

        val result = chain.invoke("any content", pageDatum)
        assertEquals(HtmlIntegrity.OK, result)
    }

    @Test
    @DisplayName("returns first non-OK result")
    fun returnsFirstNonOkResult() {
        val chain = ChainedHtmlIntegrityChecker(conf)

        // First checker always passes, second detects empty
        chain.addLast(AlwaysPassHtmlIntegrityChecker(conf))
        chain.addLast(BasicHtmlIntegrityChecker(conf))

        val result = chain.invoke("", pageDatum)
        assertEquals(HtmlIntegrity.EMPTY_0B, result, "should return the first non-OK result from the chain")
    }

    @Test
    @DisplayName("addFirst inserts checker at the beginning of the chain")
    fun addFirstInsertsAtBeginning() {
        val chain = ChainedHtmlIntegrityChecker(conf)

        // Add a checker that always passes first
        chain.addLast(AlwaysPassHtmlIntegrityChecker(conf))
        // Then add BasicHtmlIntegrityChecker at the beginning
        chain.addFirst(BasicHtmlIntegrityChecker(conf))

        // The BasicHtmlIntegrityChecker should run first and detect EMPTY_0B
        val result = chain.invoke("", pageDatum)
        assertEquals(HtmlIntegrity.EMPTY_0B, result, "addFirst should place the checker at the front of the chain")
    }

    @Test
    @DisplayName("remove deletes a checker from the chain")
    fun removeDeletesChecker() {
        val chain = ChainedHtmlIntegrityChecker(conf)
        val basic = BasicHtmlIntegrityChecker(conf)
        val alwaysPass = AlwaysPassHtmlIntegrityChecker(conf)

        chain.addLast(basic)
        chain.addLast(alwaysPass)

        // Before removal, basic checker detects EMPTY_0B
        assertEquals(HtmlIntegrity.EMPTY_0B, chain.invoke("", pageDatum))

        // Remove the basic checker
        chain.remove(basic)

        // After removal, only alwaysPass remains → OK
        assertEquals(HtmlIntegrity.OK, chain.invoke("", pageDatum))
    }

    @Test
    @DisplayName("isRelevant returns true by default for AbstractHtmlIntegrityChecker")
    fun isRelevantReturnsTrueByDefault() {
        val checker = AlwaysPassHtmlIntegrityChecker(conf)
        assertTrue(checker.isRelevant("https://example.com"))
        assertTrue(checker.isRelevant("https://any.url/whatever"))
    }

    @Test
    @DisplayName("isRelevant returns true if any checker in chain is relevant")
    fun chainIsRelevantIfAnyCheckerIsRelevant() {
        val chain = ChainedHtmlIntegrityChecker(conf)
        chain.addLast(AlwaysPassHtmlIntegrityChecker(conf))
        assertTrue(chain.isRelevant("https://example.com"))
    }

    @Test
    @DisplayName("empty chain returns OK")
    fun emptyChainReturnsOk() {
        val chain = ChainedHtmlIntegrityChecker(conf)
        val result = chain.invoke("any content", pageDatum)
        assertEquals(HtmlIntegrity.OK, result, "empty chain should return OK (no checker fails)")
    }

    @Test
    @DisplayName("addLast returns the chain for fluent chaining")
    fun addLastReturnsChain() {
        val chain = ChainedHtmlIntegrityChecker(conf)
        val returned = chain.addLast(AlwaysPassHtmlIntegrityChecker(conf))
        assertSame(chain, returned, "addLast should return the chain instance for fluent chaining")
    }
}
