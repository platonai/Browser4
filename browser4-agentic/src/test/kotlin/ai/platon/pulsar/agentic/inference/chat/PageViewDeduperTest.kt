package ai.platon.pulsar.agentic.inference.chat

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DisplayName("PageViewDeduper web-context dedup")
class PageViewDeduperTest {

    private fun result(body: String, tool: String = "tab.ariaSnapshot", id: String = "call-x"): ToolExecutionResultMessage =
        ToolExecutionResultMessage.from(id, tool, body)

    /** A page snapshot long enough that a 300-char digest truncates it. */
    private fun page(items: List<String>, box: String = "[box=0,0,100,40]"): String = buildString {
        appendLine("tab.ariaSnapshot [ok] snapshot")
        appendLine("  heading: Home")
        for (item in items) appendLine("  link: $item")
        appendLine("  $box")
    }

    private val homeItems = (1..40).map { "Item $it" }
    private val home = page(homeItems)
    private val homeOtherBox = page(homeItems, box = "[box=9,9,120,44]")
    private val homeWithPricing = page(homeItems.map { if (it == "Item 7") "Pricing" else it })
    private val completelyDifferent = page(listOf("Login", "Sign up"), box = "[box=1,1,10,10]")

    /** Append [decorated] like the loop does. */
    private fun append(messages: MutableList<ChatMessage>, decorated: ToolExecutionResultMessage) {
        messages.add(decorated)
    }

    @Test
    @DisplayName("identical view content enters the conversation once; later copies become self-sufficient references")
    fun identicalViewsFoldIntoReferences() {
        val deduper = PageViewDeduper()
        val messages = mutableListOf<ChatMessage>()

        val first = deduper.decorate(result(home), messages)
        append(messages, first)
        assertEquals(home, first.text(), "first occurrence must stay full")

        val second = deduper.decorate(result(homeOtherBox), messages)
        append(messages, second)
        assertTrue(second.text().contains(PageViewDeduper.DUPLICATE_MARKER + "0"), "must reference result #0: ${second.text()}")
        assertTrue(second.text().contains("fp="), "reference must carry the fingerprint")
        assertTrue(second.text().contains("heading: Home"), "reference digest must make it self-sufficient")
        assertFalse(second.text().contains("Item 40"), "reference must not carry the full content")
        assertTrue(second is ToolExecutionResultMessage, "reference keeps tool-result role semantics")
    }

    @Test
    @DisplayName("volatile regions (bounding boxes) are stripped before fingerprinting")
    fun boxesDoNotBreakDedup() {
        val deduper = PageViewDeduper()
        assertEquals(
            deduper.fingerprint("a\n[box=1,2,3,4] b"),
            deduper.fingerprint("a\n[box=5,6,7,8] b"),
            "box coordinates must not affect the fingerprint"
        )
    }

    @Test
    @DisplayName("changed view with heavy overlap renders as a diff vs the previous view")
    fun changedViewRendersAsDiff() {
        val deduper = PageViewDeduper()
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result(home), messages))

        val second = deduper.decorate(result(homeWithPricing), messages)
        append(messages, second)

        assertTrue(second.text().contains(PageViewDeduper.DIFF_MARKER + "0"), "must diff against result #0: ${second.text()}")
        assertTrue(second.text().contains("+ link: Pricing"), "diff must carry the added line")
        assertTrue(second.text().contains("- link: Item 7"), "diff must carry the removed line")
        assertTrue(second.text().contains("  link: Item 6"), "diff must keep context before the change")
        assertTrue(second.text().contains("  link: Item 8"), "diff must keep context after the change")
        assertFalse(second.text().contains("  link: Item 30"), "diff must not carry the unchanged bulk")
    }

    @Test
    @DisplayName("a completely different page falls back to the full snapshot (no diff)")
    fun bigChangeFallsBackToFullSnapshot() {
        val deduper = PageViewDeduper()
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result(home), messages))

        val second = deduper.decorate(result(completelyDifferent), messages)
        append(messages, second)

        assertEquals(completelyDifferent, second.text(), "a >60% change must keep the full snapshot")
        assertFalse(second.text().contains(PageViewDeduper.DIFF_MARKER))
    }

    @Test
    @DisplayName("diff body is capped; an over-budget diff falls back to the full snapshot")
    fun oversizedDiffFallsBackToFullSnapshot() {
        val deduper = PageViewDeduper(diffMaxChars = 100)
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result(home), messages))

        val second = deduper.decorate(result(homeWithPricing), messages)
        append(messages, second)

        assertEquals(homeWithPricing, second.text(), "over-budget diff must fall back to the full snapshot")
    }

    @Test
    @DisplayName("non-view tools fold exact duplicates only when duplicateFoldEnabled")
    fun nonViewToolsFoldWhenEnabled() {
        val enabled = PageViewDeduper(viewToolNames = emptySet(), duplicateFoldEnabled = true)
        val messages = mutableListOf<ChatMessage>()
        append(messages, enabled.decorate(result("eval result 42", tool = "tab.eval"), messages))
        val folded = enabled.decorate(result("eval result 42", tool = "tab.eval"), messages)
        assertTrue(folded.text().contains(PageViewDeduper.DUPLICATE_MARKER), "duplicates fold when enabled")

        val disabled = PageViewDeduper(viewToolNames = emptySet(), duplicateFoldEnabled = false)
        val messages2 = mutableListOf<ChatMessage>()
        append(messages2, disabled.decorate(result("eval result 42", tool = "tab.eval"), messages2))
        val kept = disabled.decorate(result("eval result 42", tool = "tab.eval"), messages2)
        assertEquals("eval result 42", kept.text(), "duplicates stay full when folding is disabled")
    }

    @Test
    @DisplayName("non-view tools never get diffs (only exact duplicates fold)")
    fun nonViewToolsNeverDiff() {
        val deduper = PageViewDeduper(viewToolNames = emptySet())
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result("one", tool = "tab.eval"), messages))
        val second = deduper.decorate(result("two", tool = "tab.eval"), messages)
        assertEquals("two", second.text(), "changed non-view results stay full")
    }

    @Test
    @DisplayName("view-tool matching accepts both bare and domain-prefixed names")
    fun viewToolMatchingHandlesDomainPrefix() {
        val deduper = PageViewDeduper(viewToolNames = setOf("ariaSnapshot"))
        assertTrue(deduper.isViewTool("ariaSnapshot"))
        assertTrue(deduper.isViewTool("tab.ariaSnapshot"))
        assertFalse(deduper.isViewTool("tab.eval"))
        assertTrue(PageViewDeduper.matchesViewTool("tab.ariaSnapshot", setOf("ariaSnapshot")))
    }

    @Test
    @DisplayName("reset() forgets prior state so post-compaction content re-registers")
    fun resetClearsState() {
        val deduper = PageViewDeduper()
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result(home), messages))

        deduper.reset()

        val second = deduper.decorate(result(home), messages)
        assertEquals(home, second.text(), "after reset the same content must be treated as new")
    }

    @Test
    @DisplayName("disabled deduper passes results through untouched")
    fun disabledDeduperPassesThrough() {
        val deduper = PageViewDeduper(enabled = false)
        val messages = mutableListOf<ChatMessage>()
        val first = deduper.decorate(result(home), messages)
        val second = deduper.decorate(result(home), messages)
        assertEquals(home, first.text())
        assertEquals(home, second.text())
    }

    @Test
    @DisplayName("whitespace differences do not defeat the fingerprint")
    fun whitespaceInsensitiveFingerprint() {
        val deduper = PageViewDeduper()
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result("a\n  b   c\n"), messages))
        val second = deduper.decorate(result("a\nb c"), messages)
        assertTrue(second.text().contains(PageViewDeduper.DUPLICATE_MARKER), "whitespace-only changes must dedup: ${second.text()}")
    }

    @Test
    @DisplayName("fingerprints differ for genuinely different content")
    fun fingerprintsDifferForDifferentContent() {
        val deduper = PageViewDeduper()
        assertNotEquals(deduper.fingerprint("home"), deduper.fingerprint("login"))
    }

    @Test
    @DisplayName("references carry the stable callId so they survive compression")
    fun referencesCarryStableCallId() {
        val deduper = PageViewDeduper()
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result(home, id = "call-first"), messages))

        val second = deduper.decorate(result(homeOtherBox, id = "call-second"), messages)
        append(messages, second)

        assertTrue(second.text().contains("call=call-second"),
            "the reference must carry the current call's stable id: ${second.text()}")
        assertTrue(second.text().contains("call=call-first") || second.text().contains("fp="),
            "the reference keeps its digest/fp self-sufficiency")
    }

    @Test
    @DisplayName("folds and registrations are recorded on a shared ledger")
    fun ledgerRecordsFoldsAndRegistrations() {
        val ledger = CompactionLedger()
        val deduper = PageViewDeduper(ledger = ledger)
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result(home, id = "call-a"), messages))

        val second = deduper.decorate(result(homeOtherBox, id = "call-b"), messages)
        append(messages, second)

        val registrations = ledger.entries.filterIsInstance<CompactionLedger.Entry.ResultRegistered>()
        assertEquals(1, registrations.size, "the first full view registers as a live result")
        assertEquals("call-a", registrations.single().callId)
        val folded = ledger.entries.filterIsInstance<CompactionLedger.Entry.Folded>().single()
        assertEquals("call-b", folded.callId, "the folded duplicate is recorded with its own callId")
        assertEquals(0, folded.originalIndex)
        assertEquals(1, folded.compactIndex)
    }

    @Test
    @DisplayName("ledger resolves a folded callId back to its live compact form")
    fun ledgerResolvesFoldedCallId() {
        val ledger = CompactionLedger()
        val deduper = PageViewDeduper(ledger = ledger)
        val messages = mutableListOf<ChatMessage>()
        append(messages, deduper.decorate(result(home, id = "call-a"), messages))
        append(messages, deduper.decorate(result(homeOtherBox, id = "call-b"), messages))

        val resolution = ledger.resolve("call-b")

        assertTrue(resolution is CompactionLedger.Resolution.Live, "a folded form is still live: $resolution")
        assertEquals(1, (resolution as CompactionLedger.Resolution.Live).messageIndex)
    }
}
