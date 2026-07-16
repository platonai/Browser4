package ai.platon.pulsar.agentic.tools.experience

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.*

@DisplayName("UrlNormalizer")
class UrlNormalizerTest {

    @Nested
    @DisplayName("normalize")
    inner class Normalize {
        @Test
        @DisplayName("strips www prefix")
        fun testStripWww() {
            val result = UrlNormalizer.normalize("https://www.amazon.com/dp/B0CXJ1NT4B")
            assertTrue(result.startsWith("amazon.com"))
            assertFalse(result.contains("www."))
        }

        @Test
        @DisplayName("strips trailing slash")
        fun testStripTrailingSlash() {
            val result = UrlNormalizer.normalize("https://amazon.com/dp/B0CXJ1NT4B/")
            assertEquals("amazon.com/dp/B0CXJ1NT4B", result)
        }

        @Test
        @DisplayName("strips fragment")
        fun testStripFragment() {
            val result = UrlNormalizer.normalize("https://amazon.com/dp/B0CXJ1NT4B#reviews")
            assertEquals("amazon.com/dp/B0CXJ1NT4B", result)
        }

        @Test
        @DisplayName("strips non-significant query params and www prefix")
        fun testStripNonSignificantParams() {
            val result = UrlNormalizer.normalize(
                "https://www.amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?keywords=laptop&qid=1234567"
            )
            // Note: /ref=sr_1_1 is a path segment, not a query param — it is preserved
            assertEquals("amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1", result)
        }

        @Test
        @DisplayName("preserves semantically significant query params")
        fun testPreserveSignificantParams() {
            val result = UrlNormalizer.normalize(
                "https://amazon.com/s?k=laptop&ref=nb_sb_noss&qid=1234567"
            )
            assertEquals("amazon.com/s?k=laptop", result)
        }

        @Test
        @DisplayName("handles URL without scheme")
        fun testWithoutScheme() {
            val result = UrlNormalizer.normalize("amazon.com/dp/test")
            assertTrue(result.contains("amazon.com"))
        }

        @Test
        @DisplayName("handles URL without scheme")
        fun testSimpleUrl() {
            val result = UrlNormalizer.normalize("example.com/page")
            assertTrue(result.contains("example.com"))
        }
    }

    @Nested
    @DisplayName("extractDomain")
    inner class ExtractDomain {
        @Test
        @DisplayName("extracts domain from full URL")
        fun testExtractDomain() {
            assertEquals("amazon.com", UrlNormalizer.extractDomain("https://www.amazon.com/dp/test"))
        }

        @Test
        @DisplayName("extracts domain from simple hostname")
        fun testExtractSimpleDomain() {
            assertEquals("example.com", UrlNormalizer.extractDomain("example.com/path"))
        }

        @Test
        @DisplayName("strips www prefix from domain")
        fun testStripWwwFromDomain() {
            assertEquals("amazon.com", UrlNormalizer.extractDomain("https://www.amazon.com/"))
        }
    }

    @Nested
    @DisplayName("extractPath")
    inner class ExtractPath {
        @Test
        @DisplayName("extracts path from URL")
        fun testExtractPath() {
            assertEquals("/dp/B0CXJ1NT4B", UrlNormalizer.extractPath("https://amazon.com/dp/B0CXJ1NT4B"))
        }

        @Test
        @DisplayName("returns / for root path")
        fun testRootPath() {
            assertEquals("/", UrlNormalizer.extractPath("https://amazon.com"))
        }
    }

    @Nested
    @DisplayName("matches")
    inner class Matches {
        @Test
        @DisplayName("exact pattern match")
        fun testExactMatch() {
            assertTrue(UrlNormalizer.matches("/dp/*", "/dp/B0CXJ1NT4B"))
        }

        @Test
        @DisplayName("pattern with query param wildcard")
        fun testQueryWildcard() {
            assertTrue(UrlNormalizer.matches("/s?k=*", "/s?k=laptop"))
        }

        @Test
        @DisplayName("no match for different pattern")
        fun testNoMatch() {
            assertFalse(UrlNormalizer.matches("/dp/*", "/s?k=laptop"))
        }

        @Test
        @DisplayName("wildcard does not match empty segment")
        fun testWildcardNotMatchEmpty() {
            assertFalse(UrlNormalizer.matches("/dp/*", "/dp/"))
        }

        @Test
        @DisplayName("multiple wildcards match multi-segment path")
        fun testMultipleWildcards() {
            assertTrue(UrlNormalizer.matches("/cat/*/detail/*", "/cat/electronics/detail/42"))
        }
    }

    @Nested
    @DisplayName("specificity")
    inner class Specificity {
        @Test
        @DisplayName("root wildcard has specificity 0")
        fun testRootSpecificity() {
            assertEquals(0, UrlNormalizer.specificity("/*"))
        }

        @Test
        @DisplayName("single literal segment has specificity 1")
        fun testSingleLiteral() {
            assertEquals(1, UrlNormalizer.specificity("/dp/*"))
        }

        @Test
        @DisplayName("query wildcard does not increase specificity")
        fun testQueryWildcard() {
            assertEquals(1, UrlNormalizer.specificity("/s?k=*"))
        }

        @Test
        @DisplayName("multiple literal segments increase specificity")
        fun testMultipleLiterals() {
            assertEquals(2, UrlNormalizer.specificity("/cat/electronics/*"))
        }
    }

    @Nested
    @DisplayName("findBestMatch")
    inner class FindBestMatch {
        @Test
        @DisplayName("finds most specific matching pattern")
        fun testFindBest() {
            val patterns = listOf("/*", "/dp/*", "/dp/B0CXJ1NT4B")
            val best = UrlNormalizer.findBestMatch("/dp/B0CXJ1NT4B", patterns)
            assertEquals("/dp/B0CXJ1NT4B", best)
        }

        @Test
        @DisplayName("returns null when no pattern matches")
        fun testNoMatch() {
            val patterns = listOf("/s?k=*")
            val best = UrlNormalizer.findBestMatch("/dp/test", patterns)
            assertNull(best)
        }

        @Test
        @DisplayName("prefers /dp/* over /* for specificity")
        fun testPrefersSpecific() {
            val patterns = listOf("/*", "/dp/*")
            val best = UrlNormalizer.findBestMatch("/dp/B0CXJ1NT4B", patterns)
            assertEquals("/dp/*", best)
        }
    }
}
