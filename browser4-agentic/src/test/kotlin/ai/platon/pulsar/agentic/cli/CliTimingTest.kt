package ai.platon.pulsar.agentic.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The CLI's `[timing]` footer (evaluation E-4): parsing it, and keeping it out of
 * the text the model reads.
 *
 * The footer is a one-line contract between `cli/browser4-cli/src/timing.rs` and
 * this package. Its whole value is that a `b4.run` can be attributed to startup,
 * transport or page instead of one opaque duration.
 */
@DisplayName("CLI timing footer")
class CliTimingTest {

    private val footer = "[timing] startup=282ms http=2310ms server=2240ms net=70ms calls=2 total=2600ms"

    @Test
    @DisplayName("the footer parses into the four segments")
    fun footerParsesIntoAllSegments() {
        val timing = parseCliTimingLine(footer)
        assertNotNull(timing, "the footer must parse: $footer")
        timing!!
        assertEquals(282, timing.startupMs, "startup")
        assertEquals(2310, timing.httpMs, "http")
        assertEquals(2240, timing.serverMs, "server")
        assertEquals(70, timing.netMs, "net")
        assertEquals(2, timing.calls, "calls")
        assertEquals(2600, timing.totalMs, "total")
    }

    @Test
    @DisplayName("a command with no request reports startup = total and no http")
    fun noRequestKeepsStartupOnly() {
        // `--version` answers from the binary: nothing was sent, so the whole
        // invocation is startup.
        val timing = parseCliTimingLine("[timing] startup=53ms http=0ms server=0ms net=0ms calls=0 total=53ms")
        assertNotNull(timing)
        assertEquals(53, timing!!.startupMs)
        assertEquals(0, timing.httpMs)
        assertEquals(0, timing.calls)
    }

    @Test
    @DisplayName("an unknown extra field does not break the parse")
    fun extraFieldsAreIgnored() {
        // The footer is diagnostics: adding a field must not silently disable the
        // measurement it belongs to.
        val timing = parseCliTimingLine(
            "[timing] startup=10ms http=20ms server=15ms net=5ms calls=1 total=40ms queue=7ms"
        )
        assertNotNull(timing)
        assertEquals(15, timing!!.serverMs)
    }

    @Test
    @DisplayName("a line that is not the footer parses to null")
    fun otherLinesDoNotParse() {
        assertNull(parseCliTimingLine("browser4-cli 4.16.8"))
        assertNull(parseCliTimingLine("[timing] nonsense"))
        assertNull(parseCliTimingLine(""))
    }

    @Test
    @DisplayName("server time can never exceed the round trip")
    fun serverTimeIsClamped() {
        // A backend clock or a stale header must not produce `net=-40ms`, which no
        // reader could interpret.
        val timing = parseCliTimingLine("[timing] startup=1ms http=100ms server=140ms calls=1 total=120ms")
        assertNotNull(timing)
        assertEquals(100, timing!!.serverMs, "server is clamped to http")
        assertTrue(timing.netMs >= 0, "net must never be negative: ${timing.netMs}")
    }

    @Test
    @DisplayName("the footer is stripped from stderr, the command's own lines survive")
    fun footerIsRemovedFromStderr() {
        val stderr = "Error: page not ready\n$footer\n"

        val (cleaned, timing) = extractCliTiming(stderr)

        assertEquals("Error: page not ready\n", cleaned, "only the footer line goes away")
        assertNotNull(timing, "the footer is parsed on the way out")
        assertEquals(2240, timing!!.serverMs)
    }

    @Test
    @DisplayName("stderr without a footer is returned untouched")
    fun plainStderrIsUntouched() {
        val stderr = "Error: boom\n"
        val (cleaned, timing) = extractCliTiming(stderr)
        assertEquals(stderr, cleaned, "no timing channel, no rewrite")
        assertNull(timing)
    }

    @Test
    @DisplayName("startup trace lines never reach the model")
    fun startupTraceIsStrippedToo() {
        // `BROWSER4_CLI_TRACE_STARTUP=1` writes one line per startup phase. A user
        // who exports it in their shell must not fill every b4.run result with it.
        val stderr = "[startup] hooks=0ms\n[startup] dev-port=52ms\n$footer\n"

        val (cleaned, timing) = extractCliTiming(stderr)

        assertFalse(cleaned.contains("[startup]"), cleaned)
        assertFalse(cleaned.contains("[timing]"), cleaned)
        assertNotNull(timing)
    }

    @Test
    @DisplayName("an unparseable footer is still stripped")
    fun unknownFooterShapeIsStripped() {
        // A newer CLI may add or rename fields. The line is instrumentation either
        // way; leaving it in the model's text is worse than losing the numbers.
        val (cleaned, timing) = extractCliTiming("out\n[timing] startup=? http=?\n")

        assertEquals("out\n", cleaned)
        assertNull(timing, "an unknown shape has no segments to report")
    }

    @Test
    @DisplayName("the launcher's share renders first and parses back")
    fun launchSegmentRendersAndParses() {
        // The CLI cannot report what the shell/process spawn cost before it existed;
        // the manager adds `launch` when it renders the line, and the same line must
        // parse back into the same segments.
        val rendered = CliTiming(282, 2310, 2240, 70, 2, 2600, launchMs = 220).format()

        assertTrue(rendered.startsWith("[timing] launch=220ms startup=282ms"), rendered)
        val parsed = parseCliTimingLine(rendered)
        assertNotNull(parsed, rendered)
        assertEquals(220, parsed!!.launchMs)
        assertEquals(282, parsed.startupMs)
        assertEquals(2310, parsed.httpMs)
    }

    @Test
    @DisplayName("a CLI-only footer reports no launch segment")
    fun cliOnlyFooterHasNoLaunch() {
        // The CLI's own footer must not invent a launcher number it cannot observe.
        val rendered = CliTiming(282, 2310, 2240, 70, 2, 2600).format()
        assertFalse(rendered.contains("launch="), rendered)
        assertNull(parseCliTimingLine(rendered)!!.launchMs)
    }
}
