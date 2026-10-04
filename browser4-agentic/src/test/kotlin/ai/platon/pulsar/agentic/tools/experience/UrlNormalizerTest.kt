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
        @DisplayName("drops the trailing-slash spelling of one page onto one key")
        fun testRootIsIdempotent() {
            // `normalize` feeds both the store and the matcher, so it has to be idempotent: a page
            // whose key changed on the second pass would be stored under one spelling and looked up
            // under another.  The root used to differ ("amazon.com/" vs "amazon.com").
            assertEquals("amazon.com/", UrlNormalizer.normalize("https://amazon.com"))
            assertEquals("amazon.com/", UrlNormalizer.normalize("https://amazon.com/"))
            listOf(
                "https://amazon.com",
                "https://amazon.com/",
                "amazon.com/dp/test",
                "https://www.amazon.com/s?k=laptop&qid=1",
                "https://amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?k=laptop#reviews",
            ).forEach { input ->
                val once = UrlNormalizer.normalize(input)
                assertEquals(once, UrlNormalizer.normalize(once), "normalize must be idempotent for $input")
            }
        }

        @Test
        @DisplayName("drops tracking path segments, so one page yields one key")
        fun testDropsTrackingPathSegments() {
            // Amazon spells click tracking inside the *path* (`/dp/<asin>/ref=sr_1_1`).  Keeping it
            // gave one page two spellings -- and the pattern producer turned the second one into a
            // second pattern (`/dp/<asin>/*` next to `/dp/*`), so which knowledge answered depended
            // on which file was written last.
            val result = UrlNormalizer.normalize(
                "https://www.amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?keywords=laptop&qid=1234567"
            )
            assertEquals("amazon.com/dp/B0CXJ1NT4B", result)
        }

        @Test
        @DisplayName("keeps a path segment that only looks like a tracking marker")
        fun testKeepsBase64PaddedSegment() {
            // A padded base64 id also contains '=', and folding it away would merge two resources.
            // Only the *known* tracking keys are dropped.
            val result = UrlNormalizer.normalize("https://example.com/blob/abc==")
            assertEquals("example.com/blob/abc==", result)
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
        @DisplayName("matches a significant parameter key case-insensitively")
        fun testSignificantParamKeyIsCaseInsensitive() {
            // `?K=` is the same parameter as `?k=`; comparing case-sensitively dropped it entirely,
            // which made a search page indistinguishable from the parameter-less page it is not.
            assertEquals("amazon.com/s?k=laptop", UrlNormalizer.normalize("https://amazon.com/s?K=laptop"))
        }

        @Test
        @DisplayName("does not decode before splitting the query")
        fun testEncodedAmpersandIsNotASeparator() {
            // Splitting the *decoded* query turned an encoded `&` inside a value into a separator and
            // truncated it: `?k=a%26b` was stored as `?k=a`, colliding with a different page.
            assertEquals("amazon.com/s?k=a%26b", UrlNormalizer.normalize("https://amazon.com/s?k=a%26b&qid=1"))
        }

        @Test
        @DisplayName("keeps a percent-encoded path separator encoded")
        fun testEncodedSlashStaysEncoded() {
            // `uri.path` decodes, so `/dp/a%2Fb` became `/dp/a/b` — the same key as a genuinely
            // different page.
            assertEquals("amazon.com/dp/a%2Fb", UrlNormalizer.normalize("https://amazon.com/dp/a%2Fb"))
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

        @Test
        @DisplayName("lowercases the host, so one site is one store")
        fun testHostIsLowercased() {
            // The domain names the directory a fact lives in: `WWW.Amazon.com` and `amazon.com` used
            // to be two stores, and a save through one was invisible to a query through the other.
            assertEquals("amazon.com", UrlNormalizer.extractDomain("https://WWW.Amazon.com/dp/test"))
            assertEquals("amazon.com", UrlNormalizer.extractDomain("HTTPS://Amazon.com/dp/test"))
        }

        @Test
        @DisplayName("ASCII-folds an internationalised host instead of returning the url")
        fun testIdnHostIsFolded() {
            // `URI.host` is null for a unicode host, and the old fallback returned the *whole raw
            // url* -- which then became a directory name.
            val domain = UrlNormalizer.extractDomain("https://中文.cn/path")
            assertEquals("xn--fiq228c.cn", domain)
        }

        @Test
        @DisplayName("never returns something that can escape the store directory")
        fun testGarbageInputCannotEscape() {
            listOf("../x", "..", "not a url", "?only=query", "").forEach { input ->
                val domain = UrlNormalizer.extractDomain(input)
                assertFalse(domain.contains(".."), "no parent-directory component for <$input>: $domain")
                assertFalse(domain.contains('/'), "no path separator for <$input>: $domain")
                assertTrue(domain.isNotBlank(), "a blank domain would resolve to the store root: <$input>")
            }
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

        @Test
        @DisplayName("does not mistake a dot in the query for a host")
        fun testPathOnlyUrlWithDotInQuery() {
            // The old fast path (`startsWith("/") && !contains(".")`) sent `/s?k=a.b` down the
            // full-url branch, which then reported `/s` and lost the query.
            assertEquals("/s", UrlNormalizer.extractPath("/s?k=a.b"))
            assertEquals("/dp/x", UrlNormalizer.extractPath("/dp/x"))
        }
    }

    @Nested
    @DisplayName("urlPatternOf")
    inner class UrlPatternOf {
        @Test
        @DisplayName("wildcards the last segment when it looks like an id")
        fun testWildcardsId() {
            assertEquals("/dp/*", UrlNormalizer.urlPatternOf("https://amazon.com/dp/B0CXJ1NT4B"))
        }

        @Test
        @DisplayName("keeps a literal last segment")
        fun testKeepsLiteralSegment() {
            assertEquals("/s", UrlNormalizer.urlPatternOf("https://amazon.com/s"))
        }

        @Test
        @DisplayName("keeps the significant query, values wildcarded")
        fun testKeepsQueryShape() {
            // This is the pattern the KDoc promises for a search page.  The old producer took the
            // path only, so `/s?k=*` was never written -- and the matcher, which did look at the
            // query, could not match a `host/path` url with it either.
            assertEquals("/s?k=*", UrlNormalizer.urlPatternOf("https://amazon.com/s?k=laptop&qid=1"))
        }

        @Test
        @DisplayName("produces one pattern for one page, tracking segment or not")
        fun testOnePageOnePattern() {
            val plain = UrlNormalizer.urlPatternOf("https://amazon.com/dp/B0CXJ1NT4B")
            val tracked = UrlNormalizer.urlPatternOf(
                "https://amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1?k=laptop&qid=1"
            )
            assertEquals("/dp/*", plain)
            assertEquals("/dp/*?k=*", tracked)
            // The page itself is one key...
            assertEquals(
                UrlNormalizer.normalize("https://amazon.com/dp/B0CXJ1NT4B"),
                UrlNormalizer.normalize("https://amazon.com/dp/B0CXJ1NT4B/ref=sr_1_1")
            )
        }

        @Test
        @DisplayName("the root is an exact pattern")
        fun testRootPattern() {
            assertEquals("/", UrlNormalizer.urlPatternOf("https://amazon.com"))
            assertEquals("/", UrlNormalizer.urlPatternOf("https://amazon.com/"))
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
        @DisplayName("a stored pattern matches the url spelling production passes it")
        fun testQueryPatternMatchesHostPrefixedUrl() {
            // The store matches `facts.urlPattern` against `UrlNormalizer.normalize(url)`, which is
            // `host/path?query` -- not a path-only string.  Matching only the path made every
            // query-bearing pattern unmatchable in production while the path-only unit test passed.
            assertTrue(UrlNormalizer.matches("/s?k=*", "amazon.com/s?k=laptop"))
            assertTrue(UrlNormalizer.matches("/dp/*", "amazon.com/dp/B0CXJ1NT4B"))
        }

        @Test
        @DisplayName("the site-wide catch-all matches any path, including the root")
        fun testCatchAllMatchesEverything() {
            // `/` is what a homepage records, but `/*` is the hand-authored catch-all; it used to
            // match neither a multi-segment path nor the root.
            assertTrue(UrlNormalizer.matches("/*", "amazon.com/dp/B0CXJ1NT4B"))
            assertTrue(UrlNormalizer.matches("/*", "amazon.com/"))
            assertTrue(UrlNormalizer.matches("/*", "/dp/B0CXJ1NT4B"))
        }

        @Test
        @DisplayName("a trailing wildcard stands for the rest of the path")
        fun testTrailingWildcardCoversRest() {
            assertTrue(UrlNormalizer.matches("/dp/*", "/dp/a/b"))
            assertTrue(UrlNormalizer.matches("/s/*/detail/*", "/s/x/detail/a/b"))
        }

        @Test
        @DisplayName("a pattern without a query accepts any query on that path")
        fun testPatternWithoutQueryIsQueryAgnostic() {
            // Knowledge written before the query became part of the identity must keep matching.
            assertTrue(UrlNormalizer.matches("/s", "amazon.com/s?k=laptop"))
        }

        @Test
        @DisplayName("a pattern with a query requires the parameters it names")
        fun testPatternWithQueryIsExact() {
            assertFalse(UrlNormalizer.matches("/s?k=*", "amazon.com/s"))
            assertFalse(UrlNormalizer.matches("/s?k=*", "amazon.com/s?q=laptop"))
            // The pattern constrains the parameters it names; the url may carry more of them, because
            // the insignificant ones were removed from it before it was matched.
            assertTrue(UrlNormalizer.matches("/s?k=*", "amazon.com/s?k=laptop&page=2"))
            assertTrue(UrlNormalizer.matches("/s?k=*&page=*", "amazon.com/s?k=laptop&page=2"))
            assertFalse(UrlNormalizer.matches("/s?k=*&page=*", "amazon.com/s?k=laptop"))
        }

        @Test
        @DisplayName("no match for different pattern")
        fun testNoMatch() {
            assertFalse(UrlNormalizer.matches("/dp/*", "/s?k=laptop"))
            assertFalse(UrlNormalizer.matches("/dp/*", "amazon.com/s?k=laptop"))
        }

        @Test
        @DisplayName("wildcard does not match empty segment")
        fun testWildcardNotMatchEmpty() {
            assertFalse(UrlNormalizer.matches("/dp/*", "/dp/"))
        }

        @Test
        @DisplayName("a pattern for the root matches only the root")
        fun testRootPatternIsExact() {
            assertTrue(UrlNormalizer.matches("/", "amazon.com/"))
            assertFalse(UrlNormalizer.matches("/", "amazon.com/dp/x"))
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
        @DisplayName("pinning the query makes a pattern more specific than the bare path")
        fun testQueryShapeAddsSpecificity() {
            // `/s` and `/s?k=*` both match `…/s?k=laptop`; without this the two tie and the winner is
            // whatever order the directory listing came back in.
            assertEquals(2, UrlNormalizer.specificity("/s?k=*"))
            assertTrue(UrlNormalizer.specificity("/s?k=*") > UrlNormalizer.specificity("/s"))
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

        @Test
        @DisplayName("prefers the query-bearing pattern over the bare path")
        fun testPrefersQueryShape() {
            val best = UrlNormalizer.findBestMatch("amazon.com/s?k=laptop", listOf("/s", "/s?k=*"))
            assertEquals("/s?k=*", best)
        }
    }
}
