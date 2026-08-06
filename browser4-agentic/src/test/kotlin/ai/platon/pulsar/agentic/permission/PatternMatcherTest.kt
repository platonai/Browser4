package ai.platon.pulsar.agentic.permission

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PatternMatcher")
class PatternMatcherTest {

    // Glob to Regex conversion

    @Nested
    @DisplayName("globToRegex()")
    inner class GlobToRegex {

        @Test
        @DisplayName("literal string to anchored exact match")
        fun literalAnchored() {
            val r = PatternMatcher.globToRegex("hello")
            assertTrue(r.matches("hello"))
            assertFalse(r.matches("hello world"))
            assertFalse(r.matches("xhellox"))
        }

        @Test
        @DisplayName("star matches within a single path segment")
        fun starSingleSegment() {
            val r = PatternMatcher.globToRegex("src/*.kt")
            assertTrue(r.matches("src/Main.kt"))
            assertTrue(r.matches("src/.kt"))
            assertFalse(r.matches("src/sub/Main.kt"))
        }

        @Test
        @DisplayName("double-star matches across path segments")
        fun doubleStarRecursive() {
            val r = PatternMatcher.globToRegex("src/**/*.kt")
            assertTrue(r.matches("src/Main.kt"))
            assertTrue(r.matches("src/a/b/c/Main.kt"))
            assertTrue(r.matches("src/.kt"))
        }

        @Test
        @DisplayName("question-mark matches single non-separator char")
        fun questionMark() {
            val r = PatternMatcher.globToRegex("file?.txt")
            assertTrue(r.matches("file1.txt"))
            assertTrue(r.matches("fileA.txt"))
            assertFalse(r.matches("file12.txt"))
            assertFalse(r.matches("file.txt"))
        }

        @Test
        @DisplayName("regex-special characters are escaped")
        fun regexSpecialEscaped() {
            val r = PatternMatcher.globToRegex("test[abc].txt")
            assertTrue(r.matches("test[abc].txt"))
            assertFalse(r.matches("testa.txt"))
        }

        @Test
        @DisplayName("double-star at end matches everything after prefix")
        fun doubleStarAtEnd() {
            val r = PatternMatcher.globToRegex("git push**")
            assertTrue(r.matches("git push"))
            assertTrue(r.matches("git push --force"))
            assertTrue(r.matches("git push origin main"))
        }
    }

    // EXACT matching

    @Nested
    @DisplayName("EXACT matching")
    inner class ExactMatching {

        @Test
        @DisplayName("case-sensitive for PATH resources")
        fun pathCaseSensitive() {
            assertTrue(PatternMatcher.matches(
                "src/Main.kt", "src/Main.kt", PatternType.EXACT, ResourceType.PATH))
            assertFalse(PatternMatcher.matches(
                "src/Main.kt", "src/main.kt", PatternType.EXACT, ResourceType.PATH))
        }

        @Test
        @DisplayName("case-insensitive for COMMAND resources")
        fun commandCaseInsensitive() {
            assertTrue(PatternMatcher.matches(
                "GIT", "git", PatternType.EXACT, ResourceType.COMMAND))
            assertTrue(PatternMatcher.matches(
                "Git", "GIT", PatternType.EXACT, ResourceType.COMMAND))
        }

        @Test
        @DisplayName("exact string equality")
        fun exactEquality() {
            assertTrue(PatternMatcher.matches(
                "hello", "hello", PatternType.EXACT, ResourceType.NONE))
            assertFalse(PatternMatcher.matches(
                "hello", "Hello", PatternType.EXACT, ResourceType.NONE))
        }
    }

    // GLOB matching

    @Nested
    @DisplayName("GLOB matching")
    inner class GlobMatching {

        @Test
        @DisplayName("glob with no wildcards is anchored exact match")
        fun noWildcards() {
            assertTrue(PatternMatcher.matches(
                "hello", "hello", PatternType.GLOB, ResourceType.NONE))
            assertFalse(PatternMatcher.matches(
                "hello", "prefix hello", PatternType.GLOB, ResourceType.NONE))
        }

        @Test
        @DisplayName("case-insensitive for COMMAND resources")
        fun commandGlobCaseInsensitive() {
            assertTrue(PatternMatcher.matches(
                "git push*", "GIT PUSH origin main", PatternType.GLOB, ResourceType.COMMAND))
        }

        @Test
        @DisplayName("trailing star matches anything after prefix")
        fun trailingStar() {
            assertTrue(PatternMatcher.matches(
                "git push*", "git push --force origin main", PatternType.GLOB, ResourceType.COMMAND))
            assertTrue(PatternMatcher.matches(
                "git push*", "git push", PatternType.GLOB, ResourceType.COMMAND))
            assertFalse(PatternMatcher.matches(
                "git push*", "git pull", PatternType.GLOB, ResourceType.COMMAND))
        }

        @Test
        @DisplayName("double-star in path matches across segments")
        fun globPathRecursive() {
            assertTrue(PatternMatcher.matches(
                "src/**/*.kt", "src/main/kotlin/Foo.kt", PatternType.GLOB, ResourceType.PATH))
            assertTrue(PatternMatcher.matches(
                "src/**/*.kt", "src/Foo.kt", PatternType.GLOB, ResourceType.PATH))
            assertFalse(PatternMatcher.matches(
                "src/**/*.kt", "test/Foo.kt", PatternType.GLOB, ResourceType.PATH))
        }
    }

    // REGEX matching

    @Nested
    @DisplayName("REGEX matching")
    inner class RegexMatching {

        @Test
        @DisplayName("full-string match not substring")
        fun fullStringMatch() {
            assertTrue(PatternMatcher.matches(
                "git push --force", "git push --force", PatternType.REGEX, ResourceType.COMMAND))
            assertFalse(PatternMatcher.matches(
                "git push --force", "git push --forcefully", PatternType.REGEX, ResourceType.COMMAND))
        }

        @Test
        @DisplayName("regex with alternation")
        fun alternation() {
            assertTrue(PatternMatcher.matches(
                "read|write|delete", "write", PatternType.REGEX, ResourceType.NONE))
            assertFalse(PatternMatcher.matches(
                "read|write|delete", "execute", PatternType.REGEX, ResourceType.NONE))
        }

        @Test
        @DisplayName("regex with explicit anchors")
        fun explicitAnchors() {
            assertTrue(PatternMatcher.matches(
                "rm\\s+-rf.*", "rm -rf /tmp", PatternType.REGEX, ResourceType.COMMAND))
            assertFalse(PatternMatcher.matches(
                "^rm\\s+-rf$", "xrm -rf", PatternType.REGEX, ResourceType.COMMAND))
        }

        @Test
        @DisplayName("invalid regex returns false without throwing")
        fun invalidRegex() {
            assertFalse(PatternMatcher.matches(
                "[unclosed", "anything", PatternType.REGEX, ResourceType.NONE))
        }
    }

    // Windows path normalization

    @Nested
    @DisplayName("Windows path normalization")
    inner class WindowsPathNormalization {

        @Test
        @DisplayName("backslash in value normalized to forward slash")
        fun backslashValue() {
            assertTrue(PatternMatcher.matches(
                "src/**/*.kt", "src\\main\\kotlin\\Foo.kt", PatternType.GLOB, ResourceType.PATH))
        }

        @Test
        @DisplayName("backslash in pattern normalized to forward slash")
        fun backslashPattern() {
            assertTrue(PatternMatcher.matches(
                "src\\**\\*.kt", "src/main/kotlin/Foo.kt", PatternType.GLOB, ResourceType.PATH))
        }

        @Test
        @DisplayName("backslash normalization not applied to COMMAND resources")
        fun noBackslashNormForCommands() {
            assertFalse(PatternMatcher.matches(
                "git\\ push", "git push", PatternType.EXACT, ResourceType.COMMAND))
        }
    }
}
