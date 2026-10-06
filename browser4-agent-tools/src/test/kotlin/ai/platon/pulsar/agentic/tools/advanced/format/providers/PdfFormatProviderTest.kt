package ai.platon.pulsar.agentic.tools.advanced.format.providers

import ai.platon.pulsar.agentic.tools.advanced.format.AssemblyContext
import ai.platon.pulsar.agentic.tools.advanced.format.FormatOptions
import ai.platon.pulsar.agentic.tools.advanced.format.FormatStage
import ai.platon.pulsar.agentic.tools.advanced.format.LIVE_TAB_DOMAIN
import ai.platon.pulsar.agentic.tools.advanced.format.StepPolicy
import ai.platon.pulsar.agentic.tools.advanced.format.StepResult
import ai.platon.pulsar.agentic.tools.advanced.format.UnsupportedFormatOptionException
import ai.platon.pulsar.skeleton.workflow.format.FormatOptionSchema
import ai.platon.pulsar.skeleton.workflow.format.PageFormat
import ai.platon.pulsar.skeleton.workflow.format.ScrapedDocument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PdfFormatProvider")
class PdfFormatProviderTest {

    private fun format(vararg raw: Any?): PageFormat =
        FormatOptionSchema.parse(raw.toList()).requireValid().single()

    private fun steps(format: PageFormat) =
        PdfFormatProvider.steps(format, FormatOptions())

    // ---- planning -----------------------------------------------------------

    @Test
    @DisplayName("the page is printed on the live tab, with no arguments at all")
    fun defaultPrintsOnTheLiveTab() {
        val step = steps(format("pdf")).single()

        assertEquals(FormatStage.LIVE_TAB, step.stage)
        assertEquals(LIVE_TAB_DOMAIN, step.domain)
        assertEquals("pdf", step.method)
        // Not merely "no region selector": `tab.pdf` validates against an *empty*
        // allowed set, so any argument would be refused as extraneous rather than used.
        assertTrue(step.args.isEmpty(), "expected no arguments, got ${step.args}")
        assertEquals(StepPolicy.DEGRADABLE, step.policy)
    }

    @Test
    @DisplayName("the document is persisted as a pdf, and the provider never writes it itself")
    fun theDocumentIsPersistedAsPdf() {
        val step = steps(format("pdf")).single()

        assertEquals("pdf", step.artifact?.extension)
        assertEquals("pdf", step.artifact?.nameHint)
    }

    @Test
    @DisplayName("fullPage is accepted and changes nothing, because a PDF already is the whole page")
    fun fullPageIsAcceptedWithoutChangingTheCall() {
        // The asymmetry is deliberate and is the interesting part of this provider:
        // `PageFormat.fullPage` is a non-null Boolean defaulting to false, so "the
        // caller said nothing" and "the caller said false" are the same value.
        // Refusing `false` would therefore refuse every plain `"pdf"` request, so it
        // is accepted; `true` is simply the truth about a PDF.
        val plain = steps(format("pdf")).single()
        val asked = steps(format(mapOf("type" to "pdf", "fullPage" to true))).single()

        assertEquals(plain, asked)
        assertTrue(asked.args.isEmpty(), "expected no arguments, got ${asked.args}")
    }

    @Test
    @DisplayName("a viewport size is refused: Page.printToPDF has no region")
    fun viewportIsRefused() {
        val error = assertThrows(UnsupportedFormatOptionException::class.java) {
            steps(format(mapOf("type" to "pdf", "viewport" to mapOf("width" to 1280, "height" to 800))))
        }
        assertTrue(error.message!!.contains("viewport"), error.message)
        // The reason must be the real one — a PDF cannot be printed per-region — not a
        // generic "unsupported option".
        assertTrue(error.message!!.contains("Page.printToPDF"), error.message)
    }

    @Test
    @DisplayName("quality is refused because the PDF path has no such knob")
    fun qualityIsRefused() {
        val error = assertThrows(UnsupportedFormatOptionException::class.java) {
            steps(format(mapOf("type" to "pdf", "quality" to 80)))
        }
        assertTrue(error.message!!.contains("quality"), error.message)
    }

    // ---- assembly -----------------------------------------------------------

    @Test
    @DisplayName("the path the host wrote becomes the pdf field")
    fun assembleWritesThePath() {
        val step = steps(format("pdf")).single()
        val result = StepResult(step, output = "/tmp/web/pdf/page.pdf", raw = "aGk=")

        val document = PdfFormatProvider.assemble(
            format("pdf"), listOf(result), context(), ScrapedDocument(),
        )

        assertEquals("/tmp/web/pdf/page.pdf", document.pdf)
        // Opt-in, like the screenshot bytes: a base64 PDF dwarfs the rest of the
        // document, so it appears only when it was asked for.
        assertNull(document.pdfBase64)
    }

    @Test
    @DisplayName("base64 rides alongside the path when the format asks for it")
    fun assembleAddsBase64OnlyWhenAsked() {
        val asked = format(mapOf("type" to "pdf", "base64" to true))
        val step = steps(asked).single()
        val result = StepResult(step, output = "/tmp/page.pdf", raw = "aGk=")

        val document = PdfFormatProvider.assemble(asked, listOf(result), context(), ScrapedDocument())

        // Both, not either — the same rule the screenshot provider follows.
        assertEquals("/tmp/page.pdf", document.pdf)
        assertEquals("aGk=", document.pdfBase64)
    }

    @Test
    @DisplayName("a blank step output leaves the document untouched")
    fun assembleIgnoresABlankOutput() {
        val step = steps(format("pdf")).single()

        val document = PdfFormatProvider.assemble(
            format("pdf"), listOf(StepResult(step, output = "", raw = "")), context(), ScrapedDocument(),
        )

        assertNull(document.pdf)
        assertNull(document.pdfBase64)
    }

    private fun context() = AssemblyContext(snapshot = null, options = FormatOptions())
}
