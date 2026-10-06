package ai.platon.pulsar.agentic.tools.advanced.format.providers

import ai.platon.pulsar.agentic.tools.advanced.format.AssemblyContext
import ai.platon.pulsar.agentic.tools.advanced.format.FormatOptions
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStage
import ai.platon.pulsar.agentic.tools.advanced.format.LIVE_TAB_DOMAIN
import ai.platon.pulsar.agentic.tools.advanced.format.StepPolicy
import ai.platon.pulsar.agentic.tools.advanced.format.StepResult
import ai.platon.pulsar.agentic.tools.advanced.format.UnsupportedFormatOptionException
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import ai.platon.pulsar.skeleton.workflow.format.FormatViewport
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScreenshotFormatProvider")
class ScreenshotFormatProviderTest {

    private fun format(vararg raw: Any?): PageFormat =
        FormatOptionSchema.parse(raw.toList()).requireValid().single()

    private fun steps(format: PageFormat) =
        ScreenshotFormatProvider.steps(format, FormatOptions())

    // ---- planning -----------------------------------------------------------

    @Test
    @DisplayName("the default is the visible viewport, captured on the live tab")
    fun defaultCapturesTheViewport() {
        val step = steps(format("screenshot")).single()

        // The live stage is the point: this provider is the only one that needs the
        // browser rather than the capture, and it must run after every snapshot read.
        assertEquals(FormatStage.LIVE_TAB, step.stage)
        assertEquals(LIVE_TAB_DOMAIN, step.domain)
        assertEquals("screenshot", step.method)
        // No region argument at all is what `tab.screenshot` documents as "the visible
        // viewport"; the other two spellings are mutually exclusive with it.
        assertTrue(step.args.isEmpty(), "expected no region argument, got ${step.args}")
        assertEquals(StepPolicy.DEGRADABLE, step.policy)
    }

    @Test
    @DisplayName("the capture is persisted as a png, and the provider never writes it itself")
    fun theCaptureIsPersistedAsPng() {
        val step = steps(format("screenshot")).single()

        // The artifact spec is how the engine knows to persist before the provider
        // sees the result — a provider that did its own I/O could not be tested.
        assertEquals("png", step.artifact?.extension)
        assertEquals("screenshot", step.artifact?.nameHint)
    }

    @Test
    @DisplayName("fullPage is forwarded as the single region argument")
    fun fullPageIsForwarded() {
        val step = steps(format(mapOf("type" to "screenshot", "fullPage" to true))).single()

        assertEquals(mapOf("fullPage" to true), step.args)
    }

    @Test
    @DisplayName("a viewport size is refused rather than mistaken for a scroll index")
    fun viewportSizeIsRefused() {
        // Firecrawl's `viewport` is {width, height}; `tab.screenshot`'s is the Nth
        // viewport. Passing one as the other would capture the wrong region and still
        // report success, so it fails by name instead.
        val viewportSize = format(
            mapOf("type" to "screenshot", "viewport" to mapOf("width" to 1280, "height" to 800))
        )
        assertEquals(FormatViewport(1280, 800), viewportSize.viewport)

        val error = assertThrows(UnsupportedFormatOptionException::class.java) { steps(viewportSize) }
        assertTrue(error.message!!.contains("viewport"), error.message)
        assertTrue(error.message!!.contains("scroll index"), error.message)
    }

    @Test
    @DisplayName("quality is refused because no tool has that knob")
    fun qualityIsRefused() {
        val error = assertThrows(UnsupportedFormatOptionException::class.java) {
            steps(format(mapOf("type" to "screenshot", "quality" to 80)))
        }
        assertTrue(error.message!!.contains("quality"), error.message)
    }

    // ---- assembly -----------------------------------------------------------

    @Test
    @DisplayName("the path the host wrote becomes the screenshot field")
    fun assembleWritesThePath() {
        val step = steps(format("screenshot")).single()
        val result = StepResult(step, output = "/tmp/web/screenshot/shot.png", raw = "aGk=")

        val document = ScreenshotFormatProvider.assemble(
            format("screenshot"), listOf(result), context(), ScrapedDocument(),
        )

        assertEquals("/tmp/web/screenshot/shot.png", document.screenshot)
        // Not asked for, not present — the bytes dwarf the document, so they are opt-in.
        assertNull(document.screenshotBase64)
    }

    @Test
    @DisplayName("base64 rides alongside the path when the format asks for it")
    fun assembleAddsBase64OnlyWhenAsked() {
        val asked = format(mapOf("type" to "screenshot", "base64" to true))
        val step = steps(asked).single()
        val result = StepResult(step, output = "/tmp/shot.png", raw = "aGk=")

        val document = ScreenshotFormatProvider.assemble(asked, listOf(result), context(), ScrapedDocument())

        // Both, not either: the file is written regardless, so losing the path when the
        // bytes are requested would throw away information the caller already paid for.
        assertEquals("/tmp/shot.png", document.screenshot)
        assertEquals("aGk=", document.screenshotBase64)
    }

    @Test
    @DisplayName("a blank step output leaves the document untouched")
    fun assembleIgnoresABlankOutput() {
        val step = steps(format("screenshot")).single()

        val document = ScreenshotFormatProvider.assemble(
            format("screenshot"), listOf(StepResult(step, output = "", raw = "")), context(), ScrapedDocument(),
        )

        assertNull(document.screenshot)
        assertNull(document.screenshotBase64)
    }

    private fun context() = AssemblyContext(snapshot = null, options = FormatOptions())
}
