package ai.platon.pulsar.agent.tool

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HTMLSnapshotToolExecutorTest {
    private val h2MissingColumn = "Column \"A\" not found; SQL statement"

    @Test
    fun `double quoted DOM selector receives the single quote hint`() {
        assertTrue(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT DOM_TEXT(DOM) FROM DOM_LOAD_AND_SELECT('https://example.com', \"a\")"
            )
        )
    }

    @Test
    fun `unrelated missing quoted column does not receive the selector hint`() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT \"missing_column\" FROM pages"
            )
        )
    }

    @Test
    fun `single quoted DOM selector does not receive the hint`() {
        assertFalse(
            HTMLSnapshotToolExecutor.shouldAppendSelectorQuoteHint(
                h2MissingColumn,
                "SELECT DOM_TEXT(DOM) FROM DOM_LOAD_AND_SELECT('https://example.com', 'a')"
            )
        )
    }
}
