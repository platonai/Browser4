package ai.platon.browser4.driver.chrome.dom.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ViewportSpecTest {

    @Test
    @DisplayName("null spec returns null (all viewports)")
    fun testNullSpecReturnsNull() {
        assertNull(ViewportSpec.parse(null))
    }

    @Test
    @DisplayName("empty string returns null (all viewports)")
    fun testEmptyStringReturnsNull() {
        assertNull(ViewportSpec.parse(""))
        assertNull(ViewportSpec.parse("  "))
    }

    @Test
    @DisplayName("'all' returns null (all viewports)")
    fun testAllReturnsNull() {
        assertNull(ViewportSpec.parse("all"))
        assertNull(ViewportSpec.parse("ALL"))
        assertNull(ViewportSpec.parse(" All "))
    }

    @Test
    @DisplayName("single viewport index")
    fun testSingleViewport() {
        assertEquals(listOf(3), ViewportSpec.parse("3"))
        assertEquals(listOf(1), ViewportSpec.parse("1"))
    }

    @Test
    @DisplayName("comma-separated viewport indices")
    fun testCommaSeparated() {
        assertEquals(listOf(1, 3, 5), ViewportSpec.parse("1,3,5"))
        assertEquals(listOf(2, 4), ViewportSpec.parse(" 4 , 2 "))
    }

    @Test
    @DisplayName("range specification")
    fun testRange() {
        assertEquals(listOf(2, 3, 4), ViewportSpec.parse("2-4"))
        assertEquals(listOf(1, 2, 3), ViewportSpec.parse("1-3"))
    }

    @Test
    @DisplayName("mixed individual and range")
    fun testMixed() {
        assertEquals(listOf(1, 3, 4, 5, 8), ViewportSpec.parse("1,3-5,8"))
    }

    @Test
    @DisplayName("duplicate indices are deduplicated")
    fun testDeduplicated() {
        assertEquals(listOf(1, 2, 3), ViewportSpec.parse("1,2,3,2,1"))
    }

    @Test
    @DisplayName("overlapping ranges are merged")
    fun testOverlappingRanges() {
        assertEquals(listOf(1, 2, 3, 4, 5), ViewportSpec.parse("1-3,2-5"))
    }

    @Test
    @DisplayName("indices less than 1 are clamped to 1")
    fun testClampedToOne() {
        assertEquals(listOf(1), ViewportSpec.parse("0"))
    }

    @Test
    @DisplayName("invalid tokens are ignored")
    fun testInvalidTokensIgnored() {
        assertEquals(listOf(3), ViewportSpec.parse("abc,3,xyz"))
        assertNull(ViewportSpec.parse("abc"))
    }

    @Test
    @DisplayName("result is sorted")
    fun testSorted() {
        assertEquals(listOf(1, 2, 5, 9), ViewportSpec.parse("9,5,1,2"))
    }
}
